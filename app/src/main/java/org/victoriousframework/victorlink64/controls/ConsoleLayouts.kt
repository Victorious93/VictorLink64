package org.victoriousframework.victorlink64.controls

import org.victoriousframework.victorlink64.controls.ControlKind.BUTTON
import org.victoriousframework.victorlink64.controls.ControlKind.DPAD
import org.victoriousframework.victorlink64.controls.ControlKind.STICK
import org.victoriousframework.victorlink64.controls.ControlKind.TRIGGER
import org.victoriousframework.victorlink64.controls.ControlShape.CIRCLE
import org.victoriousframework.victorlink64.controls.ControlShape.OVAL
import org.victoriousframework.victorlink64.controls.ControlShape.PILL
import org.victoriousframework.victorlink64.controls.ControlShape.SHOULDER_LEFT
import org.victoriousframework.victorlink64.controls.ControlShape.SHOULDER_RIGHT

/**
 * Console-accurate control sets and their default positions. Victorious Framework.
 *
 * Face-button positions follow the real pads: SNES diamond (X top, Y left, A right, B bottom), PSP symbols
 * (triangle top, square left, circle right, cross bottom), GameCube big A with a small B, kidney-shaped X and Y,
 * Z on the right shoulder and a C-stick, and N64 A/B with the yellow C-button cluster and a Z trigger.
 */
object ConsoleLayouts {
    private const val LEFT = 0f
    private const val CENTER = 0.5f
    private const val RIGHT = 1f
    private const val TOP = 0f
    private const val BOTTOM = 1f

    private fun dpad(size: Float, upDownLeftRight: List<Int>) =
        ControlDef("dpad", "D-PAD", DPAD, CIRCLE, size, bits = upDownLeftRight)

    private val retroDpad = listOf(RetroPad.UP, RetroPad.DOWN, RetroPad.LEFT, RetroPad.RIGHT)

    private fun face(id: String, label: String, bit: Int, size: Float, font: Float = 1.4f) =
        ControlDef(id, label, BUTTON, CIRCLE, size, bits = listOf(bit), fontScale = font)

    private fun shoulder(id: String, label: String, bit: Int, left: Boolean) = ControlDef(
        id, label, BUTTON, if (left) SHOULDER_LEFT else SHOULDER_RIGHT, 0.26f, 0.42f, listOf(bit), fontScale = 1.25f,
    )

    private fun pill(id: String, label: String, bit: Int, size: Float = 0.2f) =
        ControlDef(id, label, BUTTON, PILL, size, 0.38f, listOf(bit), accent = Accent.STEEL, fontScale = 0.8f)

    fun defs(console: Console): List<ControlDef> = when (console) {
        Console.SNES -> listOf(
            shoulder("l", "L", RetroPad.L, true), shoulder("r", "R", RetroPad.R, false),
            dpad(0.40f, retroDpad),
            face("x", "X", RetroPad.X, 0.15f), face("y", "Y", RetroPad.Y, 0.15f),
            face("a", "A", RetroPad.A, 0.15f), face("b", "B", RetroPad.B, 0.15f),
            pill("select", "SELECT", RetroPad.SELECT), pill("start", "START", RetroPad.START),
        )
        Console.PSP -> listOf(
            shoulder("l", "L", RetroPad.L, true), shoulder("r", "R", RetroPad.R, false),
            dpad(0.36f, retroDpad),
            ControlDef("stick", "", STICK, CIRCLE, 0.30f, index = 0),
            face("tri", "\u25B3", RetroPad.X, 0.15f, 1.5f), face("sq", "\u25A1", RetroPad.Y, 0.15f, 1.5f),
            face("cir", "\u25CB", RetroPad.A, 0.15f, 1.5f), face("cross", "\u2715", RetroPad.B, 0.15f, 1.5f),
            pill("select", "SELECT", RetroPad.SELECT), pill("start", "START", RetroPad.START),
        )
        Console.GAMECUBE -> listOf(
            ControlDef("lt", "L", TRIGGER, PILL, 0.15f, 1.5f, listOf(RetroPad.L2), index = 0, fontScale = 1.5f),
            ControlDef("rt", "R", TRIGGER, PILL, 0.15f, 1.5f, listOf(RetroPad.R2), index = 1, fontScale = 1.5f),
            ControlDef("z", "Z", BUTTON, PILL, 0.17f, 0.45f, listOf(RetroPad.R), accent = Accent.STEEL, fontScale = 1.1f),
            ControlDef("stick", "", STICK, CIRCLE, 0.34f, index = 0),
            ControlDef("cstick", "C", STICK, CIRCLE, 0.24f, index = 1),
            dpad(0.32f, retroDpad),
            face("a", "A", RetroPad.A, 0.21f, 2.1f),
            face("b", "B", RetroPad.B, 0.13f, 1.4f),
            ControlDef("x", "X", BUTTON, OVAL, 0.09f, 1.75f, listOf(RetroPad.X), fontScale = 1.25f),
            ControlDef("y", "Y", BUTTON, OVAL, 0.16f, 0.55f, listOf(RetroPad.Y), fontScale = 1.25f),
            pill("start", "START", RetroPad.START),
        )
        Console.N64 -> listOf(
            shoulder("l", "L", N64Pad.L, true), shoulder("r", "R", N64Pad.R, false),
            ControlDef("z", "Z", BUTTON, PILL, 0.20f, 0.55f, listOf(N64Pad.Z), fontScale = 1.4f),
            ControlDef("stick", "", STICK, CIRCLE, 0.36f, index = 0),
            dpad(0.30f, listOf(N64Pad.DPD_U, N64Pad.DPD_D, N64Pad.DPD_L, N64Pad.DPD_R)),
            face("a", "A", N64Pad.A, 0.17f, 1.7f), face("b", "B", N64Pad.B, 0.15f, 1.5f),
            face("cu", "C\u25B2", N64Pad.CPD_U, 0.12f, 0.9f), face("cd", "C\u25BC", N64Pad.CPD_D, 0.12f, 0.9f),
            face("cl", "C\u25C0", N64Pad.CPD_L, 0.12f, 0.9f), face("cr", "C\u25B6", N64Pad.CPD_R, 0.12f, 0.9f),
            pill("start", "START", N64Pad.START),
        )
    }

    private fun p(id: String, ax: Float, ay: Float, ox: Float, oy: Float) = Placement(id, ax, ay, ox, oy)

    fun defaults(console: Console, landscape: Boolean): List<Placement> = when (console) {
        Console.SNES -> if (landscape) listOf(
            p("l", LEFT, TOP, .15f, .075f), p("r", RIGHT, TOP, .15f, .075f),
            p("dpad", LEFT, BOTTOM, .25f, .31f),
            p("x", RIGHT, BOTTOM, .27f, .45f), p("y", RIGHT, BOTTOM, .41f, .31f),
            p("a", RIGHT, BOTTOM, .13f, .31f), p("b", RIGHT, BOTTOM, .27f, .17f),
            p("select", CENTER, BOTTOM, -.13f, .07f), p("start", CENTER, BOTTOM, .13f, .07f),
        ) else listOf(
            p("l", LEFT, BOTTOM, .15f, .80f), p("r", RIGHT, BOTTOM, .15f, .80f),
            p("dpad", LEFT, BOTTOM, .23f, .34f),
            p("x", RIGHT, BOTTOM, .25f, .50f), p("y", RIGHT, BOTTOM, .39f, .36f),
            p("a", RIGHT, BOTTOM, .11f, .36f), p("b", RIGHT, BOTTOM, .25f, .22f),
            p("select", CENTER, BOTTOM, -.13f, .06f), p("start", CENTER, BOTTOM, .13f, .06f),
        )
        Console.PSP -> if (landscape) listOf(
            p("l", LEFT, TOP, .15f, .075f), p("r", RIGHT, TOP, .15f, .075f),
            p("dpad", LEFT, BOTTOM, .24f, .56f), p("stick", LEFT, BOTTOM, .24f, .19f),
            p("tri", RIGHT, BOTTOM, .26f, .48f), p("sq", RIGHT, BOTTOM, .40f, .34f),
            p("cir", RIGHT, BOTTOM, .12f, .34f), p("cross", RIGHT, BOTTOM, .26f, .20f),
            p("select", CENTER, BOTTOM, -.13f, .07f), p("start", CENTER, BOTTOM, .13f, .07f),
        ) else listOf(
            p("l", LEFT, BOTTOM, .15f, .88f), p("r", RIGHT, BOTTOM, .15f, .88f),
            p("dpad", LEFT, BOTTOM, .20f, .64f), p("stick", LEFT, BOTTOM, .20f, .27f),
            p("tri", RIGHT, BOTTOM, .25f, .52f), p("sq", RIGHT, BOTTOM, .39f, .38f),
            p("cir", RIGHT, BOTTOM, .11f, .38f), p("cross", RIGHT, BOTTOM, .25f, .24f),
            p("select", CENTER, BOTTOM, -.13f, .06f), p("start", CENTER, BOTTOM, .13f, .06f),
        )
        Console.GAMECUBE -> if (landscape) listOf(
            p("lt", LEFT, TOP, .11f, .125f), p("rt", RIGHT, TOP, .11f, .125f), p("z", RIGHT, TOP, .30f, .075f),
            p("stick", LEFT, TOP, .25f, .45f), p("dpad", LEFT, BOTTOM, .39f, .19f),
            p("a", RIGHT, BOTTOM, .27f, .50f), p("x", RIGHT, BOTTOM, .105f, .50f),
            p("y", RIGHT, BOTTOM, .26f, .665f), p("b", RIGHT, BOTTOM, .455f, .40f),
            p("cstick", RIGHT, BOTTOM, .13f, .165f), p("start", CENTER, BOTTOM, 0f, .08f),
        ) else listOf(
            p("lt", LEFT, BOTTOM, .09f, .89f), p("rt", RIGHT, BOTTOM, .09f, .89f), p("z", RIGHT, BOTTOM, .40f, .90f),
            p("stick", LEFT, BOTTOM, .20f, .59f), p("dpad", LEFT, BOTTOM, .20f, .24f),
            p("a", RIGHT, BOTTOM, .27f, .62f), p("x", RIGHT, BOTTOM, .10f, .62f),
            p("y", RIGHT, BOTTOM, .26f, .78f), p("b", RIGHT, BOTTOM, .44f, .55f),
            p("cstick", RIGHT, BOTTOM, .17f, .30f), p("start", CENTER, BOTTOM, 0f, .06f),
        )
        Console.N64 -> if (landscape) listOf(
            p("l", LEFT, TOP, .15f, .075f), p("r", RIGHT, TOP, .15f, .075f), p("z", RIGHT, TOP, .13f, .21f),
            p("stick", LEFT, BOTTOM, .24f, .24f), p("dpad", LEFT, TOP, .17f, .42f),
            p("a", RIGHT, BOTTOM, .17f, .19f), p("b", RIGHT, BOTTOM, .37f, .27f),
            p("cu", RIGHT, BOTTOM, .30f, .72f), p("cd", RIGHT, BOTTOM, .30f, .44f),
            p("cl", RIGHT, BOTTOM, .45f, .58f), p("cr", RIGHT, BOTTOM, .15f, .58f),
            p("start", CENTER, BOTTOM, 0f, .08f),
        ) else listOf(
            p("l", LEFT, BOTTOM, .15f, .92f), p("r", RIGHT, BOTTOM, .15f, .92f), p("z", RIGHT, BOTTOM, .11f, .80f),
            p("stick", LEFT, BOTTOM, .22f, .34f), p("dpad", LEFT, BOTTOM, .17f, .70f),
            p("a", RIGHT, BOTTOM, .17f, .20f), p("b", RIGHT, BOTTOM, .37f, .28f),
            p("cu", RIGHT, BOTTOM, .28f, .77f), p("cd", RIGHT, BOTTOM, .28f, .47f),
            p("cl", RIGHT, BOTTOM, .43f, .62f), p("cr", RIGHT, BOTTOM, .13f, .62f),
            p("start", CENTER, BOTTOM, 0f, .06f),
        )
    }
}
