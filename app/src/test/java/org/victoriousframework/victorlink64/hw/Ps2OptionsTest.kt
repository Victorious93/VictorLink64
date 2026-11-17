package org.victoriousframework.victorlink64.hw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.victoriousframework.victorlink64.hw.Ps2Performance.Level

/** Victorious Framework: PS2 option mapping, resolution choices and the speed advice. */
class Ps2OptionsTest {
    @Test fun nativeIsTheDefaultAndMapsToTheCoresOwnLabel() {
        assertEquals(Ps2Resolution.NATIVE, Ps2Resolution.DEFAULT)
        assertEquals(Ps2Resolution.NATIVE, Ps2Resolution.fromId(null))
        assertEquals(Ps2Resolution.NATIVE, Ps2Resolution.fromId("nonsense"))
        assertEquals("1x (Native)", Ps2Options.build(Ps2Resolution.NATIVE, "bios.bin")["pcsx2_upscale_multiplier"])
        assertEquals("2x", Ps2Options.build(Ps2Resolution.X2, "bios.bin")["pcsx2_upscale_multiplier"])
        assertEquals("3x", Ps2Options.build(Ps2Resolution.X3, "bios.bin")["pcsx2_upscale_multiplier"])
    }

    @Test fun everyMultiplierParsesTheWayTheCoreParsesIt() {
        // The core does atoi(value); this is the same leading-integer read.
        for (r in Ps2Resolution.entries) {
            val v = Ps2Options.build(r, "b")["pcsx2_upscale_multiplier"]!!
            assertEquals(r.multiplier, v.takeWhile { it.isDigit() }.toInt())
        }
        assertEquals(listOf(1, 2, 3), Ps2Resolution.entries.map { it.multiplier })
    }

    @Test fun theBiosAndRendererAreAlwaysSet() {
        val o = Ps2Options.build(Ps2Resolution.NATIVE, "scph39001.bin")
        assertEquals("scph39001.bin", o["pcsx2_bios"])
        assertEquals("Auto", o["pcsx2_renderer"])
        assertEquals("enabled", o["pcsx2_analog_mode1"])
        assertEquals("enabled", o["pcsx2_fastboot"])
    }

    @Test fun onlyKeysTheCoreDefinesAreUsed() {
        val known = setOf("pcsx2_bios", "pcsx2_renderer", "pcsx2_upscale_multiplier", "pcsx2_fastboot", "pcsx2_analog_mode1")
        assertEquals(known, Ps2Options.build(Ps2Resolution.X2, "b").keys)
    }

    @Test fun outputSizesAreSpelledOut() {
        assertTrue(Ps2Resolution.NATIVE.output.contains("640×448"))
        assertTrue(Ps2Resolution.X3.output.contains("1920×1344"))
        assertTrue(Ps2Resolution.X3.output.contains("1080p"))
    }

    @Test fun phonesAreSortedByMemoryAndAbi() {
        assertEquals(Level.UNSUPPORTED, Ps2Performance.assess(8000, is64Bit = false).level)
        assertEquals(Level.ENTRY, Ps2Performance.assess(3700, true).level)
        assertEquals(Level.ENTRY, Ps2Performance.assess(5500, true).level)
        assertEquals(Level.MID, Ps2Performance.assess(7000, true).level)
        assertEquals(Level.HIGH, Ps2Performance.assess(11000, true).level)
    }

    @Test fun biggerPicturesWarnOnWeakerPhones() {
        assertNull(Ps2Performance.resolutionWarning(Level.ENTRY, Ps2Resolution.NATIVE))
        assertNotNull(Ps2Performance.resolutionWarning(Level.ENTRY, Ps2Resolution.X2))
        assertNull(Ps2Performance.resolutionWarning(Level.MID, Ps2Resolution.X2))
        assertNotNull(Ps2Performance.resolutionWarning(Level.MID, Ps2Resolution.X3))
        assertNull(Ps2Performance.resolutionWarning(Level.HIGH, Ps2Resolution.X3))
    }

    @Test fun speedNotesNameTheOwnersPhoneClassHonestly() {
        assertTrue(Ps2Performance.GENERAL_NOTE.contains("heaviest"))
        assertTrue(Ps2Performance.ENTRY_CHIP_NOTE.contains("Dimensity 6300"))
        assertTrue("must not claim it was tested", Ps2Performance.ENTRY_CHIP_NOTE.contains("has not been tested"))
    }

    @Test fun libraryTapSkipsSetupOnlyWhenReady() {
        assertTrue(Ps2SetupActivity.canSkipSetup(hasBios = true, noteSeen = true))
        assertFalse(Ps2SetupActivity.canSkipSetup(hasBios = false, noteSeen = true))
        assertFalse(Ps2SetupActivity.canSkipSetup(hasBios = true, noteSeen = false))
    }
}
