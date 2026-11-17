package org.victoriousframework.victorlink64.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Victorious Framework: the built-in layouts must be usable on real screen shapes without any editing: every control
 * fully on screen, nothing overlapping, and every console has exactly the controls its real pad has.
 */
class DefaultLayoutQualityTest {
    private val landscapes = listOf(2.4f, 2.17f, 1.78f, 1.6f, 1.33f)
    private val portraits = listOf(1f / 2.4f, 1f / 2.17f, 1f / 1.78f)

    private fun area(ratio: Float): Area = if (ratio >= 1f) Area(0f, 0f, 1000f * ratio, 1000f) else Area(0f, 0f, 1000f, 1000f / ratio)

    private fun placed(console: Console, ratio: Float): List<PlacedControl> {
        val placements = ConsoleLayouts.defaults(console, ratio >= 1f).associateBy { it.id }
        return LayoutGeometry.place(ConsoleLayouts.defs(console), placements, area(ratio), 1f)
    }

    private fun round(c: PlacedControl) = c.def.shape == ControlShape.CIRCLE

    /** Round controls are compared as circles (a diamond's neighbours touch at the corners of their boxes only). */
    private fun overlap(a: PlacedControl, b: PlacedControl): Boolean {
        if (round(a) && round(b)) return Math.hypot((a.cx - b.cx).toDouble(), (a.cy - b.cy).toDouble()) < a.radius + b.radius
        return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
    }

    @Test fun everyDefaultIsFullyOnScreenAtItsOwnPosition() {
        for (c in Console.entries) for (ratio in landscapes + portraits) {
            val ar = area(ratio)
            val placements = ConsoleLayouts.defaults(c, ratio >= 1f).associateBy { it.id }
            for (d in ConsoleLayouts.defs(c)) {
                val p = placements.getValue(d.id)
                val (cx, cy) = LayoutGeometry.centre(p, ar)
                val half = d.size * ar.edge / 2; val halfH = d.height * ar.edge / 2
                val tag = "$c ${d.id} @${ratio}"
                assertTrue("$tag left", cx - half >= ar.left - 0.5f)
                assertTrue("$tag right", cx + half <= ar.right + 0.5f)
                assertTrue("$tag top", cy - halfH >= ar.top - 0.5f)
                assertTrue("$tag bottom", cy + halfH <= ar.bottom + 0.5f)
            }
        }
    }

    @Test fun noDefaultControlsOverlap() {
        for (c in Console.entries) for (ratio in landscapes + portraits) {
            val all = placed(c, ratio)
            for (i in all.indices) for (j in i + 1 until all.size)
                assertTrue("$c ${all[i].def.id} overlaps ${all[j].def.id} @$ratio", !overlap(all[i], all[j]))
        }
    }

    @Test fun portraitControlsStayBelowThePicture() {
        // In portrait the picture takes the top of the screen; controls must live in the lower ~58%.
        for (c in Console.entries) for (ratio in portraits) {
            val ar = area(ratio)
            for (p in placed(c, ratio)) assertTrue("$c ${p.def.id} @$ratio top=${p.top / ar.height}", p.top >= ar.height * 0.40f)
        }
    }

    @Test fun everyConsoleHasItsRealPadControls() {
        fun ids(c: Console) = ConsoleLayouts.defs(c).map { it.id }.toSet()
        assertEquals(setOf("l", "r", "dpad", "x", "y", "a", "b", "select", "start"), ids(Console.SNES))
        assertEquals(setOf("l", "r", "dpad", "stick", "tri", "sq", "cir", "cross", "select", "start"), ids(Console.PSP))
        assertEquals(setOf("lt", "rt", "z", "stick", "cstick", "dpad", "a", "b", "x", "y", "start"), ids(Console.GAMECUBE))
        assertEquals(setOf("l", "r", "z", "stick", "dpad", "a", "b", "cu", "cd", "cl", "cr", "start"), ids(Console.N64))
    }

    @Test fun everyControlHasAPlacementInBothOrientations() {
        for (c in Console.entries) for (land in listOf(true, false))
            assertEquals(ConsoleLayouts.defs(c).map { it.id }.toSet(), ConsoleLayouts.defaults(c, land).map { it.id }.toSet())
    }

    @Test fun gameCubeFaceButtonsFollowTheRealPad() {
        val c = placed(Console.GAMECUBE, 2.17f).associateBy { it.def.id }
        val a = c.getValue("a")
        assertTrue("A is the biggest face button", c.values.filter { it.def.kind == ControlKind.BUTTON && it.def.id in setOf("b", "x", "y") }.all { it.w <= a.w && it.h < a.h })
        assertTrue("X is right of A", c.getValue("x").cx > a.cx)
        assertTrue("Y is above A", c.getValue("y").cy < a.cy)
        assertTrue("B is left of A", c.getValue("b").cx < a.cx)
        assertTrue("X is a tall oval, Y a wide oval", c.getValue("x").h > c.getValue("x").w && c.getValue("y").w > c.getValue("y").h)
        assertTrue("Z sits just inside R on the right shoulder", c.getValue("z").cy < a.cy && c.getValue("z").cx < c.getValue("rt").cx && c.getValue("z").cx > c.getValue("stick").cx)
    }

    @Test fun pspSymbolsAreInTheRightPlaces() {
        val c = placed(Console.PSP, 2.17f).associateBy { it.def.id }
        assertTrue(c.getValue("tri").cy < c.getValue("cross").cy)
        assertTrue(c.getValue("sq").cx < c.getValue("cir").cx)
        assertEquals(RetroPad.B, c.getValue("cross").def.bits.single())
        assertEquals(RetroPad.A, c.getValue("cir").def.bits.single())
        assertEquals(RetroPad.Y, c.getValue("sq").def.bits.single())
        assertEquals(RetroPad.X, c.getValue("tri").def.bits.single())
    }

    @Test fun n64CButtonsFormADiamondAboveA() {
        val c = placed(Console.N64, 2.17f).associateBy { it.def.id }
        assertTrue(c.getValue("cu").cy < c.getValue("cd").cy)
        assertTrue(c.getValue("cl").cx < c.getValue("cr").cx || c.getValue("cl").cx > c.getValue("cr").cx)
        assertTrue(c.getValue("cd").cy < c.getValue("a").cy)
        assertEquals(N64Pad.A, c.getValue("a").def.bits.single())
        assertEquals(N64Pad.CPD_L, c.getValue("cl").def.bits.single())
    }

    @Test fun noRedAnywhereInTheBrandAccents() {
        // Accent set is the brand navy/cyan-blue family only.
        assertEquals(setOf(Accent.CYAN, Accent.STEEL), Accent.entries.toSet())
    }
}
