package org.victoriousframework.victorlink64.controls

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Victorious Framework: the DualShock 2 layout, its libretro bits and its default controller mapping. */
class Ps2ControlsTest {
    private fun area(ratio: Float) = Area(0f, 0f, 1000f * ratio, 1000f)

    private fun placed(ratio: Float = 2.17f) = LayoutGeometry.place(
        ConsoleLayouts.defs(Console.PS2), ConsoleLayouts.defaults(Console.PS2, true).associateBy { it.id }, area(ratio), 1f,
    ).associateBy { it.def.id }

    @Test fun hasEveryDualShock2Control() {
        assertEquals(
            setOf("l", "r", "l2", "r2", "dpad", "stick", "rstick", "tri", "sq", "cir", "cross", "l3", "r3", "select", "start"),
            ConsoleLayouts.defs(Console.PS2).map { it.id }.toSet(),
        )
    }

    @Test fun twoAnalogSticksOnTheLeftAndRightIndexes() {
        val c = placed()
        assertEquals(0, c.getValue("stick").def.index); assertEquals(1, c.getValue("rstick").def.index)
        assertEquals(ControlKind.STICK, c.getValue("stick").def.kind); assertEquals(ControlKind.STICK, c.getValue("rstick").def.kind)
        assertTrue("left stick on the left half", c.getValue("stick").cx < c.getValue("rstick").cx)
    }

    @Test fun symbolsMapToTheLibretroButtonsThePs2CoreReads() {
        // LRPS2 pcsx2/PAD/PAD.c: Cross=B, Circle=A, Triangle=X, Square=Y, L1=L, R1=R, L2/R2, L3/R3.
        val c = placed()
        assertEquals(RetroPad.B, c.getValue("cross").def.bits.single())
        assertEquals(RetroPad.A, c.getValue("cir").def.bits.single())
        assertEquals(RetroPad.X, c.getValue("tri").def.bits.single())
        assertEquals(RetroPad.Y, c.getValue("sq").def.bits.single())
        assertEquals(RetroPad.L, c.getValue("l").def.bits.single()); assertEquals(RetroPad.R, c.getValue("r").def.bits.single())
        assertEquals(RetroPad.L2, c.getValue("l2").def.bits.single()); assertEquals(RetroPad.R2, c.getValue("r2").def.bits.single())
        assertEquals(RetroPad.L3, c.getValue("l3").def.bits.single()); assertEquals(RetroPad.R3, c.getValue("r3").def.bits.single())
        assertEquals(14, RetroPad.L3); assertEquals(15, RetroPad.R3)
    }

    @Test fun symbolsSitInTheDualShockDiamond() {
        val c = placed()
        assertTrue(c.getValue("tri").cy < c.getValue("cross").cy)
        assertTrue(c.getValue("sq").cx < c.getValue("cir").cx)
        assertEquals("\u25B3", c.getValue("tri").def.label); assertEquals("\u2715", c.getValue("cross").def.label)
    }

    @Test fun shouldersSitAboveTheirSiblings() {
        val c = placed()
        assertTrue(c.getValue("l").cy < c.getValue("l2").cy)
        assertTrue(c.getValue("r").cy < c.getValue("r2").cy)
        assertTrue(c.getValue("l").cx < c.getValue("r").cx)
    }

    @Test fun layoutIsUsableOnEveryCommonScreenShape() {
        for (ratio in listOf(2.4f, 2.17f, 1.78f, 1.6f, 1.33f)) {
            val all = placed(ratio).values.toList()
            val ar = area(ratio)
            for (p in all) assertTrue("${p.def.id} on screen @$ratio", p.left >= -0.5f && p.right <= ar.right + 0.5f && p.top >= -0.5f && p.bottom <= ar.bottom + 0.5f)
        }
    }

    @Test fun controllerDefaultsFollowButtonPositionAndIncludeStickClicks() {
        val m = KeyRemap.defaults(Console.PS2)
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, m.keyFor(RetroPad.B))
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, m.keyFor(RetroPad.A))
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, m.keyFor(RetroPad.Y))
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, m.keyFor(RetroPad.X))
        assertEquals(KeyEvent.KEYCODE_BUTTON_L1, m.keyFor(RetroPad.L)); assertEquals(KeyEvent.KEYCODE_BUTTON_R1, m.keyFor(RetroPad.R))
        assertEquals(KeyEvent.KEYCODE_BUTTON_L2, m.keyFor(RetroPad.L2)); assertEquals(KeyEvent.KEYCODE_BUTTON_R2, m.keyFor(RetroPad.R2))
        assertEquals(KeyEvent.KEYCODE_BUTTON_THUMBL, m.keyFor(RetroPad.L3)); assertEquals(KeyEvent.KEYCODE_BUTTON_THUMBR, m.keyFor(RetroPad.R3))
        assertEquals(KeyEvent.KEYCODE_BUTTON_START, m.keyFor(RetroPad.START)); assertEquals(KeyEvent.KEYCODE_BUTTON_SELECT, m.keyFor(RetroPad.SELECT))
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, m.keyFor(RetroPad.UP))
    }

    @Test fun everyRemapTargetHasADefaultKey() {
        val m = KeyRemap.defaults(Console.PS2)
        for (t in RemapTargets.forConsole(Console.PS2)) assertTrue("${t.label} has a default", m.keyFor(t.bit) != null)
        assertEquals(16, RemapTargets.forConsole(Console.PS2).size)
    }

    @Test fun remapSurvivesEncodeAndDecode() {
        val custom = KeyRemap.defaults(Console.PS2).assign(RetroPad.L3, KeyEvent.KEYCODE_BUTTON_MODE)
        val back = KeyRemap.decode(Console.PS2, custom.encode())!!
        assertEquals(KeyEvent.KEYCODE_BUTTON_MODE, back.keyFor(RetroPad.L3))
    }

    @Test fun triggersAndSticksReachTheCoreInLibretroUnits() {
        val out = PadConvert.toRetro(PadState(mask = 1 shl RetroPad.L3, lx = 1f, ry = -1f, lt = 1f, rt = 0.5f))
        assertTrue(out.buttons and (1 shl RetroPad.L3) != 0)
        assertTrue("full L2 also sets its digital bit", out.buttons and (1 shl RetroPad.L2) != 0)
        assertTrue(out.lx > 0 && out.ry < 0 && out.lTrigger > out.rTrigger && out.rTrigger > 0)
    }

    @Test fun noRedInThePs2Accents() {
        for (d in ConsoleLayouts.defs(Console.PS2)) assertTrue(d.accent in setOf(Accent.CYAN, Accent.STEEL))
    }
}
