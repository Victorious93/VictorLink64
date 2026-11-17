package org.victoriousframework.victorlink64.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetSelectionTest {
    private val motoG = DeviceProfile(
        manufacturer = "motorola", model = "moto g play - 2026", soc = "MediaTek MT6835 Dimensity 6300",
        gpu = "Mali-G57 MC2", ramMb = 3700, refreshHz = 60f, peakRefreshHz = 90f, glEsVersion = 0x30002,
    )

    @Test fun targetPhoneIsEntryLevel() {
        assertEquals(DeviceClass.LOW, motoG.deviceClass)
    }

    @Test fun gpuNamesClassifyAsExpected() {
        assertEquals(DeviceClass.LOW, DeviceProfile.classify("", "Mali-G52 MC2", 6000))
        assertEquals(DeviceClass.LOW, DeviceProfile.classify("", "Adreno (TM) 619", 8000))
        assertEquals(DeviceClass.MID, DeviceProfile.classify("", "Mali-G610 MC4", 8000))
        assertEquals(DeviceClass.MID, DeviceProfile.classify("", "Adreno (TM) 650", 8000))
        assertEquals(DeviceClass.HIGH, DeviceProfile.classify("", "Adreno (TM) 750", 12000))
        assertEquals(DeviceClass.HIGH, DeviceProfile.classify("", "Mali-G720-Immortalis MC12", 12000))
    }

    @Test fun ramCapsAFastGpu() {
        assertEquals(DeviceClass.LOW, DeviceProfile.classify("", "Adreno (TM) 750", 2800))
        assertEquals(DeviceClass.MID, DeviceProfile.classify("", "Adreno (TM) 750", 4500))
    }

    @Test fun unknownHardwareFallsBackToSocThenRam() {
        assertEquals(DeviceClass.LOW, DeviceProfile.classify("Dimensity 6300", "", 8000))
        assertEquals(DeviceClass.HIGH, DeviceProfile.classify("SM8650", "", 12000))
        assertEquals(DeviceClass.LOW, DeviceProfile.classify("", "", 4000))
        assertEquals(DeviceClass.MID, DeviceProfile.classify("", "", 8000))
    }

    @Test fun everyPresetUsesOnlyControlsTheEmulatorSupports() {
        for (system in GfxSystem.entries) {
            val caps = Presets.capabilities(system)
            Presets.ladder(system).forEach {
                assertEquals("${system.key} ${it.name}", it.settings, caps.sanitize(it.settings))
            }
            assertEquals(caps.sanitize(Presets.manualDefault(system)), Presets.manualDefault(system))
        }
    }

    @Test fun laddersNeverGetCheaperAsTheyClimb() {
        for (system in GfxSystem.entries) {
            Presets.ladder(system).map { it.settings }.zipWithNext().forEach { (a, b) ->
                assertTrue("${system.key}: resolution", cost(b.resolution) >= cost(a.resolution))
                assertTrue("${system.key}: aniso", b.anisotropy >= a.anisotropy)
                assertTrue("${system.key}: msaa", b.msaa >= a.msaa)
                assertTrue("${system.key}: enhance", b.textureEnhance.ordinal >= a.textureEnhance.ordinal)
            }
        }
    }

    private fun cost(r: Resolution) = when (r) {
        Resolution.ORIGINAL -> 0; Resolution.X2 -> 1; Resolution.P720 -> 1; Resolution.X3 -> 2; Resolution.P1080 -> 2
    }

    @Test fun startTierFollowsDeviceClass() {
        val n = Thermal.NONE
        assertEquals(1, AutoPolicy.startTier(GfxSystem.N64, DeviceClass.LOW, n, null, null))
        assertEquals(2, AutoPolicy.startTier(GfxSystem.N64, DeviceClass.MID, n, null, null))
        assertEquals(3, AutoPolicy.startTier(GfxSystem.N64, DeviceClass.HIGH, n, null, null))
        assertEquals(1, AutoPolicy.startTier(GfxSystem.PSP, DeviceClass.LOW, n, null, null))
        assertEquals(0, AutoPolicy.startTier(GfxSystem.GAMECUBE, DeviceClass.LOW, n, null, null))
        assertEquals(2, AutoPolicy.startTier(GfxSystem.SNES, DeviceClass.LOW, n, null, null))
    }

    @Test fun learnedTierWinsButStaysUnderTheClassCeiling() {
        assertEquals(2, AutoPolicy.startTier(GfxSystem.PSP, DeviceClass.LOW, Thermal.NONE, learned = 2, knownBad = null))
        assertEquals(2, AutoPolicy.startTier(GfxSystem.PSP, DeviceClass.LOW, Thermal.NONE, learned = 4, knownBad = null))
        assertEquals(3, AutoPolicy.startTier(GfxSystem.PSP, DeviceClass.MID, Thermal.NONE, learned = 3, knownBad = null))
    }

    @Test fun knownBadTierIsNeverStartedAtOrAbove() {
        assertEquals(1, AutoPolicy.startTier(GfxSystem.PSP, DeviceClass.HIGH, Thermal.NONE, learned = null, knownBad = 2))
        assertEquals(0, AutoPolicy.startTier(GfxSystem.PSP, DeviceClass.HIGH, Thermal.NONE, learned = null, knownBad = 1))
        assertEquals(0, AutoPolicy.startTier(GfxSystem.PSP, DeviceClass.HIGH, Thermal.NONE, learned = null, knownBad = 0))
    }

    @Test fun aWarmPhoneStartsOneTierLower() {
        assertEquals(0, AutoPolicy.startTier(GfxSystem.PSP, DeviceClass.LOW, Thermal.MODERATE, null, null))
        assertEquals(1, AutoPolicy.startTier(GfxSystem.PSP, DeviceClass.MID, Thermal.SEVERE, null, null))
        assertEquals(0, AutoPolicy.startTier(GfxSystem.GAMECUBE, DeviceClass.LOW, Thermal.SEVERE, null, null))
    }

    @Test fun tiersAlwaysInsideTheLadder() {
        for (system in GfxSystem.entries) for (c in DeviceClass.entries) for (l in listOf(null, -3, 0, 9)) for (b in listOf(null, 0, 2, 12)) for (t in 0..4) {
            val tier = AutoPolicy.startTier(system, c, t, l, b)
            assertTrue(tier in 0..AutoPolicy.maxTier(system, c))
            assertTrue(AutoPolicy.maxTier(system, c) <= Presets.maxTier(system))
        }
    }

    @Test fun autoIsTheDefaultAndPicksAPreset() {
        val r = GraphicsResolver.resolve(GfxSystem.PSP, null, GlobalGraphics(), DeviceClass.LOW, Thermal.NONE)
        assertTrue(r.auto)
        assertEquals(1, r.tier)
        assertEquals(Presets.preset(GfxSystem.PSP, 1).settings, r.settings)
        assertEquals(2, r.maxTier)
    }

    @Test fun manualOverrideBeatsAuto() {
        val manual = GraphicsSettings(Resolution.P1080, TexFilter.SMOOTH, anisotropy = 16, postFx = PostFx.CRT)
        val g = GlobalGraphics(auto = false, perSystem = mapOf(GfxSystem.PSP to manual))
        val r = GraphicsResolver.resolve(GfxSystem.PSP, null, g, DeviceClass.LOW, Thermal.NONE)
        assertFalse(r.auto)
        assertEquals(null, r.tier)
        assertEquals(manual, r.settings)
    }

    @Test fun perGameChoiceBeatsTheAppWideOne() {
        val perGame = GameRecord("k", GfxSystem.GAMECUBE, "Game", auto = false, manual = GraphicsSettings(Resolution.X2))
        val r = GraphicsResolver.resolve(GfxSystem.GAMECUBE, perGame, GlobalGraphics(auto = true), DeviceClass.HIGH, Thermal.NONE)
        assertFalse(r.auto)
        assertEquals(Resolution.X2, r.settings.resolution)
        val forced = GameRecord("k", GfxSystem.GAMECUBE, "Game", auto = true)
        assertTrue(GraphicsResolver.resolve(GfxSystem.GAMECUBE, forced, GlobalGraphics(auto = false), DeviceClass.HIGH, Thermal.NONE).auto)
    }

    @Test fun manualChoicesAreCutDownToWhatTheEmulatorCanDo() {
        val wild = GraphicsSettings(Resolution.P1080, TexFilter.SMOOTH, 16, 8, widescreen = true, textureEnhance = TexEnhance.X4, postFx = PostFx.SHARPEN)
        val n64 = GraphicsResolver.resolve(GfxSystem.N64, null, GlobalGraphics(false, mapOf(GfxSystem.N64 to wild)), DeviceClass.LOW, 0).settings
        assertEquals(PostFx.NONE, n64.postFx)
        val snes = GraphicsResolver.resolve(GfxSystem.SNES, null, GlobalGraphics(false, mapOf(GfxSystem.SNES to wild)), DeviceClass.LOW, 0).settings
        assertEquals(GraphicsSettings(Resolution.ORIGINAL, TexFilter.SMOOTH, 1, 0, false, TexEnhance.OFF, PostFx.SHARPEN), snes)
        val psp = GraphicsResolver.resolve(GfxSystem.PSP, null, GlobalGraphics(false, mapOf(GfxSystem.PSP to wild)), DeviceClass.LOW, 0).settings
        assertFalse(psp.widescreen)
    }

    @Test fun targetPhoneNeverAutoStartsAboveWhatItCanHold() {
        for (s in GfxSystem.entries) {
            val r = GraphicsResolver.resolve(s, null, GlobalGraphics(), motoG.deviceClass, Thermal.NONE)
            assertTrue(r.tier!! <= r.maxTier)
        }
        assertEquals(2, GraphicsResolver.resolve(GfxSystem.N64, null, GlobalGraphics(), motoG.deviceClass, 0).maxTier)
        assertEquals(1, GraphicsResolver.resolve(GfxSystem.GAMECUBE, null, GlobalGraphics(), motoG.deviceClass, 0).maxTier)
    }
}
