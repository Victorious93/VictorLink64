package org.victoriousframework.victorlink64.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStore : KeyValueStore {
    val data = HashMap<String, String>()
    override fun getString(key: String) = data[key]
    override fun putString(key: String, value: String) { data[key] = value }
    override fun remove(key: String) { data.remove(key) }
}

/** Victorious Framework: saved layouts, settings and hardware key bindings. */
class LayoutPersistenceTest {
    private val area = Area(0f, 0f, 2000f, 1000f)

    @Test fun freshInstallUsesTheDefaultsForEveryControl() {
        for (c in Console.entries) for (land in listOf(true, false)) {
            val loaded = LayoutStore(MemoryStore()).load(c, land)
            assertEquals(ConsoleLayouts.defs(c).map { it.id }.toSet(), loaded.keys)
        }
    }

    @Test fun saveThenLoadRoundTrips() {
        val kv = MemoryStore()
        val store = LayoutStore(kv)
        val edited = store.load(Console.GAMECUBE, true).toMutableMap()
        edited["a"] = edited.getValue("a").copy(ox = 0.31f, oy = 0.42f, scale = 1.4f, opacity = 0.35f)
        store.save(Console.GAMECUBE, true, edited.values)

        val again = LayoutStore(kv).load(Console.GAMECUBE, true)
        assertEquals(edited.getValue("a"), again.getValue("a"))
        assertEquals(edited, again)
    }

    @Test fun layoutsAreSeparatePerConsoleAndOrientation() {
        val kv = MemoryStore()
        val store = LayoutStore(kv)
        val land = store.load(Console.SNES, true).toMutableMap()
        land["a"] = land.getValue("a").copy(scale = 1.8f)
        store.save(Console.SNES, true, land.values)

        assertEquals(1.8f, store.load(Console.SNES, true).getValue("a").scale, 0f)
        assertEquals(1f, store.load(Console.SNES, false).getValue("a").scale, 0f)
        assertEquals(1f, store.load(Console.PSP, true).getValue("cir").scale, 0f)
        assertTrue(store.hasCustomLayout(Console.SNES, true))
        assertFalse(store.hasCustomLayout(Console.SNES, false))
    }

    @Test fun resetRestoresTheDefaultLayout() {
        val store = LayoutStore(MemoryStore())
        val edited = store.load(Console.N64, false).toMutableMap()
        edited["start"] = edited.getValue("start").copy(ox = 0.9f)
        store.save(Console.N64, false, edited.values)
        store.reset(Console.N64, false)
        assertEquals(store.defaultsFor(Console.N64, false), store.load(Console.N64, false))
        assertFalse(store.hasCustomLayout(Console.N64, false))
    }

    @Test fun newControlsAppearAndRemovedControlsAreDropped() {
        val kv = MemoryStore()
        kv.putString("layout.snes.land", "a,1.0,1.0,0.2,0.2,1.2,0.9\nghost,0.0,0.0,0.1,0.1,1.0,1.0")
        val loaded = LayoutStore(kv).load(Console.SNES, true)
        assertEquals(1.2f, loaded.getValue("a").scale, 0f)
        assertTrue("b" in loaded && "start" in loaded)
        assertFalse("ghost" in loaded)
    }

    @Test fun damagedDataFallsBackToDefaultsPerControl() {
        val kv = MemoryStore()
        kv.putString("layout.psp.port", "garbage\n\na,1,1\ncross,NaN,1,1,1,1,1\ntri,1,1,0.3,0.3,1,1,extra\nsq,1.0,1.0,0.3,0.3,1.0,1.0")
        val store = LayoutStore(kv)
        val loaded = store.load(Console.PSP, false)
        assertEquals(store.defaultsFor(Console.PSP, false).getValue("cross"), loaded.getValue("cross"))
        assertEquals(0.3f, loaded.getValue("sq").ox, 0f)
    }

    @Test fun outOfRangeValuesAreClamped() {
        val p = LayoutCodec.decode("a,1.0,1.0,99,-99,50,0").single()
        assertEquals(3f, p.ox, 0f); assertEquals(-3f, p.oy, 0f)
        assertEquals(Placement.MAX_SCALE, p.scale, 0f)
        assertEquals(Placement.MIN_OPACITY, p.opacity, 0f)
    }

    @Test fun dragSavesAnEdgeAnchoredPlacement() {
        val defs = ConsoleLayouts.defs(Console.SNES)
        val placements = ConsoleLayouts.defaults(Console.SNES, true).associateBy { it.id }
        val placed = LayoutGeometry.place(defs, placements, area, 1f)
        val a = placed.first { it.def.id == "a" }
        val old = placements.getValue("a")

        a.cx -= 100f; a.cy -= 50f
        val moved = LayoutGeometry.toPlacement(a, area, old, 1f)
        val again = LayoutGeometry.place(defs, mapOf("a" to moved), area, 1f).single()
        assertEquals(a.cx, again.cx, 0.01f)
        assertEquals(a.cy, again.cy, 0.01f)
        assertEquals(1f, moved.ax, 0f); assertEquals(1f, moved.ay, 0f)
    }

    @Test fun resizeSurvivesSaveWithMasterScale() {
        val defs = ConsoleLayouts.defs(Console.PSP)
        val placements = ConsoleLayouts.defaults(Console.PSP, true).associateBy { it.id }
        val c = LayoutGeometry.place(defs, placements, area, 1.2f).first { it.def.id == "cross" }
        c.w *= 1.5f; c.h *= 1.5f
        val saved = LayoutGeometry.toPlacement(c, area, placements.getValue("cross"), 1.2f)
        assertEquals(1.5f, saved.scale, 1e-4f)
        val back = LayoutGeometry.place(defs, mapOf("cross" to saved), area, 1.2f).single()
        assertEquals(c.w, back.w, 0.01f)
    }

    @Test fun layoutFollowsAChangeOfScreenShape() {
        val defs = ConsoleLayouts.defs(Console.SNES)
        val placements = ConsoleLayouts.defaults(Console.SNES, true).associateBy { it.id }
        val wide = LayoutGeometry.place(defs, placements, Area(0f, 0f, 2400f, 1080f), 1f).first { it.def.id == "a" }
        val narrow = LayoutGeometry.place(defs, placements, Area(0f, 0f, 1920f, 1080f), 1f).first { it.def.id == "a" }
        // Right-anchored: the A button hugs the right edge at any width.
        assertEquals(2400f - wide.cx, 1920f - narrow.cx, 0.01f)
        assertNotEquals(wide.cx, narrow.cx)
    }

    @Test fun controlsAreClampedIntoTheScreen() {
        val defs = ConsoleLayouts.defs(Console.SNES)
        val off = Placement("a", 1f, 1f, -2f, -2f)
        val c = LayoutGeometry.place(defs, mapOf("a" to off), area, 1f).single()
        assertTrue(c.right <= area.right && c.left >= area.left && c.bottom <= area.bottom && c.top >= area.top)
    }

    @Test fun settingsDefaultsAndClamping() {
        val kv = MemoryStore()
        val s = OverlaySettings(kv)
        assertEquals(0.8f, s.masterOpacity, 0f)
        assertEquals(StickMode.FIXED, s.stickMode)
        assertTrue(s.haptics && s.analogTriggers && s.hideWithController)

        s.masterOpacity = 9f; s.masterScale = -1f; s.stickMode = StickMode.FLOATING; s.haptics = false
        val s2 = OverlaySettings(kv)
        assertEquals(1f, s2.masterOpacity, 0f)
        assertEquals(0.6f, s2.masterScale, 0f)
        assertEquals(StickMode.FLOATING, s2.stickMode)
        assertFalse(s2.haptics)

        kv.putString("opacity", "not a number")
        assertEquals(0.8f, OverlaySettings(kv).masterOpacity, 0f)
    }

    @Test fun keyRemapDefaultsAndRebinding() {
        val snes = KeyRemap.defaults(Console.SNES)
        assertEquals(RetroPad.B, snes.bitFor(android.view.KeyEvent.KEYCODE_BUTTON_A))
        val gc = KeyRemap.defaults(Console.GAMECUBE)
        assertEquals(RetroPad.A, gc.bitFor(android.view.KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(RetroPad.R, gc.bitFor(android.view.KeyEvent.KEYCODE_BUTTON_R1))

        val swapped = snes.assign(RetroPad.A, android.view.KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(RetroPad.A, swapped.bitFor(android.view.KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(null, swapped.keyFor(RetroPad.B)) // the key moved; its old target is now unbound
    }

    @Test fun keyRemapPersistsPerConsole() {
        val kv = MemoryStore()
        val store = RemapStore(kv)
        val custom = KeyRemap.defaults(Console.PSP).assign(RetroPad.B, android.view.KeyEvent.KEYCODE_BUTTON_Z).clear(RetroPad.SELECT)
        store.save(custom)
        val loaded = RemapStore(kv).load(Console.PSP)
        assertEquals(RetroPad.B, loaded.bitFor(android.view.KeyEvent.KEYCODE_BUTTON_Z))
        assertEquals(null, loaded.keyFor(RetroPad.SELECT))
        assertEquals(KeyRemap.defaults(Console.SNES).bindings, RemapStore(kv).load(Console.SNES).bindings)
        store.reset(Console.PSP)
        assertEquals(KeyRemap.defaults(Console.PSP).bindings, RemapStore(kv).load(Console.PSP).bindings)
    }

    @Test fun keyRemapIgnoresDamagedData() {
        val kv = MemoryStore()
        kv.putString("keymap.snes", "x=y;8=96;999=5;0=-3;;0=97;1=97")
        val r = RemapStore(kv).load(Console.SNES)
        assertEquals(RetroPad.A, r.bitFor(96))
        assertEquals(RetroPad.Y, r.bitFor(97)) // later binding of the same key wins
        assertEquals(null, r.keyFor(RetroPad.B))
    }
}
