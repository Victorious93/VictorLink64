package org.victoriousframework.victorlink64.controls

import android.view.KeyEvent

// Victorious Framework: hardware controller button remapping and axis handling (pure, JVM-testable).
// KeyEvent.KEYCODE_* are compile-time constants, so nothing here needs a device.

/** A console button the user can bind a controller key to. [bit] is in the console's own bit space. */
data class RemapTarget(val label: String, val bit: Int)

object RemapTargets {
    private val dpad = listOf(
        RemapTarget("D-pad up", RetroPad.UP), RemapTarget("D-pad down", RetroPad.DOWN),
        RemapTarget("D-pad left", RetroPad.LEFT), RemapTarget("D-pad right", RetroPad.RIGHT),
    )

    /**
     * Buttons shown on the remap screen. N64 is intentionally absent: Mupen64Plus has its own per-controller profile
     * system (axes, deadzone, button map) that the game already uses for hardware pads.
     */
    fun forConsole(console: Console): List<RemapTarget> = when (console) {
        Console.SNES -> listOf(
            RemapTarget("A", RetroPad.A), RemapTarget("B", RetroPad.B), RemapTarget("X", RetroPad.X),
            RemapTarget("Y", RetroPad.Y), RemapTarget("L", RetroPad.L), RemapTarget("R", RetroPad.R),
            RemapTarget("Start", RetroPad.START), RemapTarget("Select", RetroPad.SELECT),
        ) + dpad
        Console.PSP -> listOf(
            RemapTarget("\u2715 Cross", RetroPad.B), RemapTarget("\u25CB Circle", RetroPad.A),
            RemapTarget("\u25A1 Square", RetroPad.Y), RemapTarget("\u25B3 Triangle", RetroPad.X),
            RemapTarget("L", RetroPad.L), RemapTarget("R", RetroPad.R),
            RemapTarget("Start", RetroPad.START), RemapTarget("Select", RetroPad.SELECT),
        ) + dpad
        Console.GAMECUBE -> listOf(
            RemapTarget("A", RetroPad.A), RemapTarget("B", RetroPad.B), RemapTarget("X", RetroPad.X),
            RemapTarget("Y", RetroPad.Y), RemapTarget("Z", RetroPad.R),
            RemapTarget("L trigger", RetroPad.L2), RemapTarget("R trigger", RetroPad.R2),
            RemapTarget("Start", RetroPad.START),
        ) + dpad
        Console.N64 -> emptyList()
    }
}

/** Android key code <-> console button bindings for one console. Immutable; edits return a new instance. */
class KeyRemap private constructor(val console: Console, private val byBit: Map<Int, Int>) {
    private val byKey: Map<Int, Int> = byBit.entries.associate { (bit, key) -> key to bit }

    fun keyFor(bit: Int): Int? = byBit[bit]
    fun bitFor(keyCode: Int): Int? = byKey[keyCode]
    val bindings: Map<Int, Int> get() = byBit

    /** Binds [keyCode] to [bit]; the key is taken away from whatever it was bound to before. */
    fun assign(bit: Int, keyCode: Int): KeyRemap {
        val next = byBit.filterValues { it != keyCode }.toMutableMap()
        next[bit] = keyCode
        return KeyRemap(console, next)
    }

    fun clear(bit: Int) = KeyRemap(console, byBit - bit)

    fun encode(): String = byBit.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }

    companion object {
        fun of(console: Console, byBit: Map<Int, Int>) = KeyRemap(console, byBit)

        fun decode(console: Console, text: String?): KeyRemap? {
            if (text == null) return null
            val valid = RemapTargets.forConsole(console).map { it.bit }.toSet()
            val map = LinkedHashMap<Int, Int>()
            for (part in text.split(';')) {
                val kv = part.split('=')
                if (kv.size != 2) continue
                val bit = kv[0].toIntOrNull() ?: continue
                val key = kv[1].toIntOrNull()?.takeIf { it > 0 } ?: continue
                if (bit !in valid) continue
                map.entries.removeAll { it.value == key }
                map[bit] = key
            }
            return KeyRemap(console, map)
        }

        /**
         * Out-of-the-box bindings. SNES and PSP follow button position (the bottom face button is the bottom button on
         * the console); GameCube follows the printed label, matching Dolphin's own controller defaults.
         */
        fun defaults(console: Console): KeyRemap {
            val m = LinkedHashMap<Int, Int>()
            val dpad = mapOf(
                RetroPad.UP to KeyEvent.KEYCODE_DPAD_UP, RetroPad.DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
                RetroPad.LEFT to KeyEvent.KEYCODE_DPAD_LEFT, RetroPad.RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
            )
            when (console) {
                Console.GAMECUBE -> {
                    m[RetroPad.A] = KeyEvent.KEYCODE_BUTTON_A; m[RetroPad.B] = KeyEvent.KEYCODE_BUTTON_B
                    m[RetroPad.X] = KeyEvent.KEYCODE_BUTTON_X; m[RetroPad.Y] = KeyEvent.KEYCODE_BUTTON_Y
                    m[RetroPad.R] = KeyEvent.KEYCODE_BUTTON_R1
                    m[RetroPad.L2] = KeyEvent.KEYCODE_BUTTON_L2; m[RetroPad.R2] = KeyEvent.KEYCODE_BUTTON_R2
                    m[RetroPad.START] = KeyEvent.KEYCODE_BUTTON_START
                }
                Console.SNES, Console.PSP -> {
                    m[RetroPad.B] = KeyEvent.KEYCODE_BUTTON_A; m[RetroPad.A] = KeyEvent.KEYCODE_BUTTON_B
                    m[RetroPad.Y] = KeyEvent.KEYCODE_BUTTON_X; m[RetroPad.X] = KeyEvent.KEYCODE_BUTTON_Y
                    m[RetroPad.L] = KeyEvent.KEYCODE_BUTTON_L1; m[RetroPad.R] = KeyEvent.KEYCODE_BUTTON_R1
                    m[RetroPad.START] = KeyEvent.KEYCODE_BUTTON_START; m[RetroPad.SELECT] = KeyEvent.KEYCODE_BUTTON_SELECT
                }
                Console.N64 -> return KeyRemap(console, emptyMap())
            }
            m.putAll(dpad)
            return KeyRemap(console, m)
        }
    }
}

class RemapStore(private val kv: KeyValueStore) {
    private fun key(console: Console) = "keymap.${console.id}"
    fun load(console: Console): KeyRemap = KeyRemap.decode(console, kv.getString(key(console))) ?: KeyRemap.defaults(console)
    fun save(remap: KeyRemap) = kv.putString(key(remap.console), remap.encode())
    fun reset(console: Console) = kv.remove(key(console))
}

/** Raw axis readings of a hardware gamepad, as Android reports them (+y down, triggers 0..1). */
data class AxisReading(
    val lx: Float = 0f, val ly: Float = 0f, val rx: Float = 0f, val ry: Float = 0f,
    val lt: Float = 0f, val rt: Float = 0f, val hatX: Float = 0f, val hatY: Float = 0f,
)

object HardwarePad {
    private val STICK = StickConfig(deadzone = 0.12f, outer = 0.97f, curve = 1.1f)
    const val TRIGGER_DEADZONE = 0.05f

    /** D-pad bits for a controller whose D-pad is reported as a hat axis (common on Android). */
    fun hatMask(hatX: Float, hatY: Float): Int {
        var m = 0
        if (hatY < -0.5f) m = m or (1 shl RetroPad.UP)
        if (hatY > 0.5f) m = m or (1 shl RetroPad.DOWN)
        if (hatX < -0.5f) m = m or (1 shl RetroPad.LEFT)
        if (hatX > 0.5f) m = m or (1 shl RetroPad.RIGHT)
        return m
    }

    fun trigger(v: Float): Float =
        if (v <= TRIGGER_DEADZONE) 0f else ((v - TRIGGER_DEADZONE) / (1f - TRIGGER_DEADZONE)).coerceIn(0f, 1f)

    /** D-pad bits from a stick, for consoles with no analog input (SNES): pressed past 50% deflection. */
    fun stickDpadMask(x: Float, y: Float): Int = hatMask(
        if (x * x + y * y < 0.25f) 0f else x,
        if (x * x + y * y < 0.25f) 0f else y,
    )

    /**
     * Pad state from axes only; key presses are merged in by the activity. With [stickAsDpad] the left stick drives
     * the D-pad bits instead of the analog output (SNES games only read digital directions).
     */
    fun fromAxes(r: AxisReading, config: StickConfig = STICK, stickAsDpad: Boolean = false): PadState {
        val l = StickMath.shape(r.lx, r.ly, config)
        val rs = StickMath.shape(r.rx, r.ry, config)
        val hat = hatMask(r.hatX, r.hatY)
        if (stickAsDpad) return PadState(hat or stickDpadMask(r.lx, r.ly), 0f, 0f, 0f, 0f, 0f, 0f)
        return PadState(hat, l.x, l.y, rs.x, rs.y, trigger(r.lt), trigger(r.rt))
    }
}
