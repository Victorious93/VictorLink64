package org.victoriousframework.victorlink64.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Victorious Framework: pure helpers behind the on-screen pad settings and haptics. */
class PadKitTest {
    @Test fun opacityCyclesAndWraps() {
        assertEquals(1f, PadPrefs.next(PadPrefs.OPACITIES, 0.8f))
        assertEquals(0.4f, PadPrefs.next(PadPrefs.OPACITIES, 1f))
        assertEquals(0.4f, PadPrefs.next(PadPrefs.OPACITIES, 5f))
    }
    @Test fun scaleLabels() {
        assertEquals("S", PadPrefs.scaleLabel(0.85f)); assertEquals("M", PadPrefs.scaleLabel(1f))
        assertEquals("L", PadPrefs.scaleLabel(1.15f)); assertEquals("XL", PadPrefs.scaleLabel(1.3f))
    }
    @Test fun hapticOnlyOnFreshPress() {
        assertEquals(0b100, newlyPressed(0b011, 0b111))
        assertEquals(0, newlyPressed(0b111, 0b011))
    }
}
