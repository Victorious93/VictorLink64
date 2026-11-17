package org.victoriousframework.victorlink64.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Victorious Framework: multi-touch behaviour of the shared overlay engine. */
class TouchEngineTest {
    private val area = Area(0f, 0f, 2000f, 1000f)

    private fun engine(console: Console, floating: Boolean = false, analog: Boolean = true): Pair<TouchEngine, Map<String, PlacedControl>> {
        val placements = ConsoleLayouts.defaults(console, true).associateBy { it.id }
        val placed = LayoutGeometry.place(ConsoleLayouts.defs(console), placements, area, 1f)
        val e = TouchEngine().apply {
            slopPx = 30f; floatingSticks = floating; analogTriggers = analog
            stickConfig = StickConfig(0.1f, 0.95f, 1f); controls = placed
        }
        return e to placed.associateBy { it.def.id }
    }

    private fun Map<String, PlacedControl>.at(id: String, dx: Float = 0f, dy: Float = 0f, pid: Int = 0) =
        Pointer(pid, getValue(id).cx + dx, getValue(id).cy + dy)

    private fun bit(b: Int) = 1 shl b

    @Test fun tapPressesOnlyThatButtonAndReleases() {
        val (e, c) = engine(Console.SNES)
        assertEquals(bit(RetroPad.A), e.update(listOf(c.at("a"))).mask)
        assertTrue(e.freshPress)
        assertTrue(e.visual("a").pressed)
        assertEquals(0, e.update(emptyList()).mask)
        assertFalse(e.visual("a").pressed)
    }

    @Test fun hapticOnlyOnFreshBits() {
        assertEquals(0b100, newlyPressed(0b011, 0b111))
        assertEquals(0, newlyPressed(0b111, 0b011))
    }

    @Test fun holdingDoesNotRetriggerHaptics() {
        val (e, c) = engine(Console.SNES)
        e.update(listOf(c.at("a")))
        e.update(listOf(c.at("a", 2f, 2f)))
        assertFalse(e.freshPress)
    }

    @Test fun twoFingersPressTwoButtonsAtOnce() {
        val (e, c) = engine(Console.SNES)
        val s = e.update(listOf(c.at("a", pid = 0), c.at("l", pid = 1)))
        assertEquals(bit(RetroPad.A) or bit(RetroPad.L), s.mask)
    }

    @Test fun thumbRollsFromOneFaceButtonToTheNext() {
        val (e, c) = engine(Console.SNES)
        e.update(listOf(c.at("a")))
        val s = e.update(listOf(c.at("b")))
        assertEquals(bit(RetroPad.B), s.mask)
        assertTrue(e.freshPress)
    }

    @Test fun fingerAtTheSeamPressesBothButtons() {
        val (e, c) = engine(Console.SNES)
        val a = c.getValue("a"); val b = c.getValue("b")
        val seam = Pointer(0, (a.cx + b.cx) / 2, (a.cy + b.cy) / 2)
        assertEquals(bit(RetroPad.A) or bit(RetroPad.B), e.update(listOf(seam)).mask)
    }

    @Test fun directHitBeatsANeighboursSlop() {
        val (e, c) = engine(Console.SNES)
        val a = c.getValue("a")
        // Just inside A's edge, within slop of B: only A.
        assertEquals(bit(RetroPad.A), e.update(listOf(Pointer(0, a.cx + a.w / 2 - 3f, a.cy))).mask)
    }

    @Test fun slopCatchesAnImpreciseTouch() {
        val (e, c) = engine(Console.SNES)
        val a = c.getValue("a")
        assertEquals(bit(RetroPad.A), e.update(listOf(Pointer(0, a.cx + a.w / 2 + 10f, a.cy))).mask)
        assertEquals(0, e.update(listOf(Pointer(0, a.cx + a.w / 2 + 80f, a.cy))).mask)
    }

    @Test fun dpadSlidesThroughDiagonalsAndStaysCapturedPastItsEdge() {
        val (e, c) = engine(Console.SNES)
        val d = c.getValue("dpad")
        val up = bit(RetroPad.UP); val right = bit(RetroPad.RIGHT); val down = bit(RetroPad.DOWN)
        assertEquals(right, e.update(listOf(c.at("dpad", 50f, 0f))).mask)
        assertEquals(right or up, e.update(listOf(c.at("dpad", 40f, -40f))).mask)
        assertEquals(up, e.update(listOf(c.at("dpad", 0f, -50f))).mask)
        // Far outside the pad: still this finger's D-pad.
        assertEquals(right or down, e.update(listOf(Pointer(0, d.cx + 900f, d.cy + 900f))).mask)
        assertEquals(0, e.update(emptyList()).mask)
    }

    @Test fun dpadDoesNotStealAFingerThatStartedOnAButton() {
        val (e, c) = engine(Console.SNES)
        val d = c.getValue("dpad")
        e.update(listOf(c.at("a")))
        val s = e.update(listOf(Pointer(0, d.cx + 50f, d.cy)))
        assertEquals(0, s.mask)
    }

    @Test fun gameCubeStickAndCStickAreIndependentAxes() {
        val (e, c) = engine(Console.GAMECUBE)
        val sr = c.getValue("stick").radius
        val cr = c.getValue("cstick").radius
        val s = e.update(listOf(c.at("stick", sr, 0f, pid = 0), c.at("cstick", 0f, cr, pid = 1)))
        assertEquals(1f, s.lx, 1e-3f); assertEquals(0f, s.ly, 1e-3f)
        assertEquals(0f, s.rx, 1e-3f); assertEquals(1f, s.ry, 1e-3f)
        val after = e.update(listOf(c.at("cstick", 0f, cr, pid = 1)))
        assertEquals(0f, after.lx, 0f)
        assertEquals(1f, after.ry, 1e-3f)
    }

    @Test fun stickThumbDoesNotDisturbButtons() {
        val (e, c) = engine(Console.GAMECUBE)
        val s = e.update(listOf(c.at("stick", 30f, 0f, pid = 0), c.at("a", pid = 1)))
        assertTrue(s.lx > 0f)
        assertEquals(bit(RetroPad.A), s.mask)
    }

    @Test fun analogTriggerScalesWithFingerTravel() {
        val (e, c) = engine(Console.GAMECUBE)
        val t = c.getValue("lt")
        val light = e.update(listOf(Pointer(0, t.cx, t.top + t.h * 0.3f))).lt
        val heavy = e.update(listOf(Pointer(0, t.cx, t.top + t.h * 0.6f))).lt
        val full = e.update(listOf(Pointer(0, t.cx, t.bottom - 2f)))
        assertTrue("$light < $heavy", light in 0.01f..0.5f && heavy > light)
        assertEquals(1f, full.lt, 0f)
        assertTrue("full squeeze sets the digital click", full.mask and bit(RetroPad.L2) != 0)
        val released = e.update(emptyList())
        assertEquals(0f, released.lt, 0f); assertEquals(0, released.mask)
    }

    @Test fun triggersAreIndependent() {
        val (e, c) = engine(Console.GAMECUBE)
        val l = c.getValue("lt"); val r = c.getValue("rt")
        val s = e.update(listOf(Pointer(0, l.cx, l.bottom - 2f), Pointer(1, r.cx, r.top + r.h * 0.4f)))
        assertEquals(1f, s.lt, 0f)
        assertTrue(s.rt in 0.05f..0.9f)
    }

    @Test fun analogTriggersCanBeSwitchedToDigital() {
        val (e, c) = engine(Console.GAMECUBE, analog = false)
        val t = c.getValue("rt")
        val s = e.update(listOf(Pointer(0, t.cx, t.top + 2f)))
        assertEquals(1f, s.rt, 0f)
        assertTrue(s.mask and bit(RetroPad.R2) != 0)
    }

    @Test fun floatingStickPlantsWhereTheFingerLandsInsideItsZone() {
        val (e, c) = engine(Console.GAMECUBE, floating = true)
        val h = c.getValue("stick")
        val p = Pointer(0, h.cx + h.radius * 1.6f, h.cy)
        val s = e.update(listOf(p))
        assertEquals(0f, s.lx, 0f)
        assertEquals(p.x, e.visual("stick").stick.baseX, 0f)
        val moved = e.update(listOf(p.copy(x = p.x + 40f)))
        assertTrue(moved.lx > 0f)
    }

    @Test fun fixedStickIgnoresAFingerFarOutsideItsZone() {
        val (e, c) = engine(Console.GAMECUBE)
        val h = c.getValue("stick")
        val s = e.update(listOf(Pointer(0, h.cx + h.radius * 1.9f, h.cy)))
        assertEquals(0f, s.lx, 0f)
        assertFalse(e.visual("stick").stick.active)
    }

    @Test fun n64HasCButtonsAndStickAndZ() {
        val (e, c) = engine(Console.N64)
        assertEquals(1 shl N64Pad.CPD_U, e.update(listOf(c.at("cu"))).mask)
        e.update(emptyList())
        assertEquals(1 shl N64Pad.Z, e.update(listOf(c.at("z"))).mask)
        e.update(emptyList())
        val s = e.update(listOf(c.at("stick", -c.getValue("stick").radius, 0f)))
        assertEquals(-1f, s.lx, 1e-3f)
        val buttons = PadConvert.n64Buttons(1 shl N64Pad.A or (1 shl N64Pad.CPD_L))
        assertEquals(16, buttons.size)
        assertTrue(buttons[N64Pad.A] && buttons[N64Pad.CPD_L] && !buttons[N64Pad.B])
        assertEquals(1f, PadConvert.n64AxisY(PadState(ly = -1f)), 0f) // screen-up becomes N64 +y
    }

    @Test fun changingControlsReleasesEverything() {
        val (e, c) = engine(Console.SNES)
        e.update(listOf(c.at("a")))
        e.controls = e.controls
        assertEquals(0, e.update(emptyList()).mask)
        assertFalse(e.visual("a").pressed)
    }

    @Test fun wouldCaptureLetsEmptySpacePassThrough() {
        val (e, c) = engine(Console.SNES)
        assertTrue(e.wouldCapture(c.getValue("a").cx, c.getValue("a").cy))
        assertFalse(e.wouldCapture(area.width / 2, area.height * 0.3f))
    }

    @Test fun retroConversionUsesLibretroUnits() {
        val r = PadConvert.toRetro(PadState(mask = bit(RetroPad.A), lx = 1f, ly = -1f, rx = 0.5f, lt = 1f, rt = 0.25f))
        assertEquals(32767, r.lx); assertEquals(-32767, r.ly); assertEquals(16384, r.rx)
        assertEquals(32767, r.lTrigger); assertEquals(8192, r.rTrigger)
        assertTrue(r.buttons and bit(RetroPad.L2) != 0)
        assertFalse(r.buttons and bit(RetroPad.R2) != 0)
    }

    @Test fun mergingTouchAndHardwareKeepsTheStrongerInput() {
        val touch = PadState(mask = 1, lx = 0.2f, ly = 0.1f, lt = 0.3f)
        val hw = PadState(mask = 2, lx = -0.9f, ly = 0f, lt = 0.1f, rt = 0.5f)
        val m = touch.merge(hw)
        assertEquals(3, m.mask)
        assertEquals(-0.9f, m.lx, 0f)
        assertEquals(0.3f, m.lt, 0f); assertEquals(0.5f, m.rt, 0f)
    }

    @Test fun hardwareAxesAreShapedAndHatBecomesDpad() {
        val s = HardwarePad.fromAxes(AxisReading(lx = 0.05f, ly = 0.04f, rx = 1f, hatX = -1f, lt = 0.03f, rt = 1f))
        assertEquals(0f, s.lx, 0f)
        assertEquals(1f, s.rx, 1e-3f)
        assertEquals(1 shl RetroPad.LEFT, s.mask)
        assertEquals(0f, s.lt, 0f); assertEquals(1f, s.rt, 1e-3f)
    }
}
