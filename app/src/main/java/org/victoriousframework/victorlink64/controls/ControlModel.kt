package org.victoriousframework.victorlink64.controls

// Victorious Framework: data model for the shared on-screen overlay (all consoles).

/** Consoles that use the shared overlay. [id] is the persistence key and must never change. */
enum class Console(val id: String, val label: String) {
    N64("n64", "Nintendo 64"),
    SNES("snes", "Super Nintendo"),
    PSP("psp", "PSP"),
    GAMECUBE("gc", "GameCube");

    companion object {
        fun fromId(id: String?): Console? = entries.firstOrNull { it.id == id }
    }
}

/** libretro RetroPad bit ids (RETRO_DEVICE_ID_JOYPAD_*), used by the SNES, PSP and GameCube cores. */
object RetroPad {
    const val B = 0; const val Y = 1; const val SELECT = 2; const val START = 3
    const val UP = 4; const val DOWN = 5; const val LEFT = 6; const val RIGHT = 7
    const val A = 8; const val X = 9; const val L = 10; const val R = 11; const val L2 = 12; const val R2 = 13
    const val L3 = 14; const val R3 = 15
}

/** N64 button indices; must match AbstractController / the input plug-in's EButton list. */
object N64Pad {
    const val DPD_R = 0; const val DPD_L = 1; const val DPD_D = 2; const val DPD_U = 3
    const val START = 4; const val Z = 5; const val B = 6; const val A = 7
    const val CPD_R = 8; const val CPD_L = 9; const val CPD_D = 10; const val CPD_U = 11
    const val R = 12; const val L = 13
    const val COUNT = 16
}

enum class ControlKind { BUTTON, DPAD, STICK, TRIGGER }

enum class ControlShape { CIRCLE, OVAL, PILL, SHOULDER_LEFT, SHOULDER_RIGHT }

enum class Accent { CYAN, STEEL }

/**
 * One on-screen control. [size] is its width in "edge units" (1.0 = the short edge of the usable screen area), so
 * a layout looks the same on a small phone and a tablet; height is [size] * [aspect].
 * [bits] are in the console's own bit space ([RetroPad] or [N64Pad]): one bit for a BUTTON, up/down/left/right for
 * a DPAD, and the optional "fully squeezed" digital bit for a TRIGGER. [index] picks the left (0) or right (1)
 * stick or trigger.
 */
data class ControlDef(
    val id: String,
    val label: String,
    val kind: ControlKind,
    val shape: ControlShape,
    val size: Float,
    val aspect: Float = 1f,
    val bits: List<Int> = emptyList(),
    val index: Int = 0,
    val accent: Accent = Accent.CYAN,
    val fontScale: Float = 1f,
) {
    val height get() = size * aspect
}

/**
 * Saved placement of one control. Position is anchored to the nearest screen edge so layouts follow the screen at any
 * aspect ratio: [ax] is 0 (left), 0.5 (centre) or 1 (right); [ay] is 0 (top), 0.5 or 1 (bottom); [ox]/[oy] are the
 * distance in edge units from that anchor to the control's centre, measured inwards (for the centre anchor, +x is
 * right and +y is down). [scale] multiplies the control's size and [opacity] its transparency (1 = opaque).
 */
data class Placement(
    val id: String,
    val ax: Float,
    val ay: Float,
    val ox: Float,
    val oy: Float,
    val scale: Float = 1f,
    val opacity: Float = 1f,
) {
    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.0f
        const val MIN_OPACITY = 0.1f
    }
}

/** What the overlay reports to the emulator each time a finger moves. Axes are -1..1 with +y down. */
data class PadState(
    val mask: Int = 0,
    val lx: Float = 0f, val ly: Float = 0f,
    val rx: Float = 0f, val ry: Float = 0f,
    val lt: Float = 0f, val rt: Float = 0f,
) {
    /** Combines two input sources (touch + hardware pad): buttons OR together, the larger deflection wins. */
    fun merge(o: PadState): PadState {
        val lm = if (lx * lx + ly * ly >= o.lx * o.lx + o.ly * o.ly) Vec2(lx, ly) else Vec2(o.lx, o.ly)
        val rm = if (rx * rx + ry * ry >= o.rx * o.rx + o.ry * o.ry) Vec2(rx, ry) else Vec2(o.rx, o.ry)
        return PadState(mask or o.mask, lm.x, lm.y, rm.x, rm.y, maxOf(lt, o.lt), maxOf(rt, o.rt))
    }

    companion object { val NEUTRAL = PadState() }
}

/** Per-frame values handed to the libretro frontends (hwfront.c), already in libretro units. */
data class RetroOutput(val buttons: Int, val lx: Int, val ly: Int, val rx: Int, val ry: Int, val lTrigger: Int, val rTrigger: Int)

object PadConvert {
    /**
     * libretro values for [s]. Analog triggers also set their digital L2/R2 bit once squeezed past 90% (the
     * GameCube "click"), so cores that only read the digital button still see the full press.
     */
    fun toRetro(s: PadState): RetroOutput {
        var m = s.mask
        if (s.lt >= TRIGGER_CLICK) m = m or (1 shl RetroPad.L2)
        if (s.rt >= TRIGGER_CLICK) m = m or (1 shl RetroPad.R2)
        return RetroOutput(
            m,
            StickMath.toAxis16(s.lx), StickMath.toAxis16(s.ly), StickMath.toAxis16(s.rx), StickMath.toAxis16(s.ry),
            StickMath.toPressure16(s.lt), StickMath.toPressure16(s.rt),
        )
    }

    /** N64 plug-in state: 16 button flags plus the stick as -1..1 fractions with +y UP (the plug-in's convention). */
    fun n64Buttons(mask: Int): BooleanArray = BooleanArray(N64Pad.COUNT) { mask and (1 shl it) != 0 }
    fun n64AxisX(s: PadState): Float = s.lx
    fun n64AxisY(s: PadState): Float = -s.ly

    const val TRIGGER_CLICK = 0.9f
}
