package org.victoriousframework.victorlink64.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicsStoreTest {
    private fun store(backend: TextBackend = MemoryBackend()) = GraphicsStore(backend)

    @Test fun autoIsOnByDefaultAndPersists() {
        val b = MemoryBackend()
        assertTrue(store(b).global().auto)
        store(b).setAuto(false)
        assertFalse(store(b).global().auto)
    }

    @Test fun systemChoicesRoundTrip() {
        val b = MemoryBackend()
        val s = GraphicsSettings(Resolution.X2, TexFilter.SMOOTH, 4, 2, false, TexEnhance.X2, PostFx.FXAA)
        store(b).setSystemManual(GfxSystem.PSP, s)
        assertEquals(s, store(b).global().manualFor(GfxSystem.PSP))
        store(b).setSystemManual(GfxSystem.PSP, null)
        assertEquals(Presets.manualDefault(GfxSystem.PSP), store(b).global().manualFor(GfxSystem.PSP))
    }

    @Test fun perGameChoicesAreRememberedAcrossSessions() {
        val b = MemoryBackend()
        val key = GameKeys.byFile(GfxSystem.PSP, "Some Game.ISO")
        store(b).touchGame(key, GfxSystem.PSP, "Some Game", 1000)
        store(b).updateGame(key) { it.copy(auto = false, manual = GraphicsSettings(Resolution.X3), learnedTier = 2, knownBadTier = 3) }
        val r = store(b).game(key)!!
        assertEquals("Some Game", r.name)
        assertEquals(false, r.auto)
        assertEquals(Resolution.X3, r.manual!!.resolution)
        assertEquals(2, r.learnedTier)
        assertEquals(3, r.knownBadTier)
        assertTrue(r.hasOverride)
    }

    @Test fun touchingAGameKeepsItsChoices() {
        val s = store()
        s.touchGame("k", GfxSystem.SNES, "Game", 1)
        s.updateGame("k") { it.copy(auto = true, learnedTier = 1) }
        s.touchGame("k", GfxSystem.SNES, "Game (renamed)", 99)
        val r = s.game("k")!!
        assertEquals(true, r.auto)
        assertEquals(1, r.learnedTier)
        assertEquals("Game (renamed)", r.name)
        assertEquals(99L, r.lastPlayedMs)
    }

    @Test fun gamesAreListedMostRecentFirstAndCanBeRemoved() {
        val s = store()
        s.touchGame("a", GfxSystem.PSP, "A", 10)
        s.touchGame("b", GfxSystem.PSP, "B", 20)
        assertEquals(listOf("b", "a"), s.games().map { it.key })
        s.removeGame("b")
        assertEquals(listOf("a"), s.games().map { it.key })
        assertNull(s.game("b"))
    }

    @Test fun updatingAnUnknownGameDoesNothing() {
        val s = store()
        s.updateGame("missing") { it.copy(learnedTier = 3) }
        assertTrue(s.games().isEmpty())
    }

    @Test fun corruptStorageFallsBackToDefaults() {
        val s = store(MemoryBackend("{ not json"))
        assertTrue(s.global().auto)
        assertTrue(s.games().isEmpty())
        s.setAuto(false) // and recovers by overwriting
        assertFalse(s.global().auto)
    }

    @Test fun storedSettingsAreSanitizedWhenRead() {
        val b = MemoryBackend()
        val wild = GraphicsSettings(Resolution.P1080, TexFilter.SMOOTH, 16, 8, true, TexEnhance.X4, PostFx.SHARPEN)
        store(b).setSystemManual(GfxSystem.SNES, wild)
        val back = store(b).global().manualFor(GfxSystem.SNES)
        assertEquals(Resolution.ORIGINAL, back.resolution)
        assertEquals(1, back.anisotropy)
        assertEquals(0, back.msaa)
        assertEquals(PostFx.SHARPEN, back.postFx)
    }

    @Test fun unknownEnumNamesDecodeToDefaults() {
        val b = MemoryBackend("""{"v":1,"auto":true,"sys":{"psp":{"res":"NOPE","filter":"X","aniso":3,"msaa":7,"fx":"??"}},"games":{}}""")
        val s = store(b).global().manualFor(GfxSystem.PSP)
        assertEquals(GraphicsSettings(), s)
    }

    @Test fun gameKeysAreStable() {
        assertEquals("n64:abc123", GameKeys.n64(" ABC123 "))
        assertEquals("psp:game.iso", GameKeys.byFile(GfxSystem.PSP, "Game.ISO"))
        assertNotNull(GfxSystem.fromKey("gamecube"))
        assertNull(GfxSystem.fromKey("wii"))
    }
}
