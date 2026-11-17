package org.victoriousframework.victorlink64.controls

import kotlin.math.abs
import kotlin.math.hypot

/** Bits set in [new] that were not set in [old]: one haptic tick per fresh press, none while a button is held. */
fun newlyPressed(old: Int, new: Int): Int = new and old.inv()

/** One finger currently on the screen, in the same pixel space as the [PlacedControl]s. */
data class Pointer(val id: Int, val x: Float, val y: Float)

/** What the view needs to draw one control right now. */
class ControlVisual {
    var pressed = false
    var dirs = 0
    var value = 0f
    val stick = StickTracker()
}

/**
 * Multi-touch brain of the overlay: turns the set of fingers on the screen into a [PadState]. No Android types.
 *
 * Rules, so the pad feels like the official overlays:
 *  - A finger that lands on a stick, D-pad or analog trigger is captured by it until it lifts, so drifting past the
 *    edge never drops the control (and a floating stick can be swiped far).
 *  - Any other finger is "free": it presses every button under it, re-evaluated as it moves, so a thumb can roll from
 *    one face button to the next (and press two at a seam). A direct hit wins over neighbours' touch slop.
 *  - Every finger is independent; one thumb on the stick never disturbs the buttons.
 */
class TouchEngine {
    var controls: List<PlacedControl> = emptyList()
        set(value) { releaseAll(); field = value }
    var slopPx = 12f
    var floatingSticks = false
    var analogTriggers = true
    var stickConfig = StickConfig()
        set(value) { field = value; visuals.values.forEach { it.stick.config = value } }
    var bounds: Bounds? = null

    /** True when the last [update] added a fresh press (button, D-pad direction or trigger): the view ticks haptics. */
    var freshPress = false; private set

    private val visuals = HashMap<String, ControlVisual>()
    private val captures = HashMap<Int, PlacedControl>()
    private val seen = HashSet<Int>()
    private var lastMask = 0

    fun visual(id: String): ControlVisual = visuals.getOrPut(id) { ControlVisual().also { it.stick.config = stickConfig } }

    fun releaseAll() {
        captures.clear(); seen.clear()
        visuals.values.forEach { it.pressed = false; it.dirs = 0; it.value = 0f; it.stick.end() }
        lastMask = 0; freshPress = false
    }

    /** True if some control would take a finger landing at ([x], [y]); lets the host pass other touches through. */
    fun wouldCapture(x: Float, y: Float): Boolean = pickTarget(x, y) != null

    /** Distance from [c]'s centre in units of its half size: <= 1 means inside its shape. */
    private fun norm(c: PlacedControl, x: Float, y: Float): Float {
        val dx = x - c.cx; val dy = y - c.cy
        return when (c.def.shape) {
            ControlShape.CIRCLE, ControlShape.OVAL -> hypot(dx / (c.w / 2), dy / (c.h / 2))
            else -> maxOf(abs(dx) / (c.w / 2), abs(dy) / (c.h / 2))
        }
    }

    private fun isCapture(c: PlacedControl) = c.def.kind != ControlKind.BUTTON

    /** How far past its edge a control still catches a finger. Sticks catch a wider zone around their home. */
    private fun reach(c: PlacedControl): Float = when (c.def.kind) {
        ControlKind.STICK -> c.radius * ((if (floatingSticks) FLOAT_ZONE else FIXED_ZONE) - 1f)
        ControlKind.DPAD -> slopPx * 1.5f
        else -> slopPx
    }

    private fun within(c: PlacedControl, x: Float, y: Float, withSlop: Boolean): Boolean {
        val r = if (withSlop) reach(c) else 0f
        val dx = abs(x - c.cx); val dy = abs(y - c.cy)
        return when (c.def.shape) {
            ControlShape.CIRCLE, ControlShape.OVAL -> {
                val a = c.w / 2 + r; val b = c.h / 2 + r
                (dx / a) * (dx / a) + (dy / b) * (dy / b) <= 1f
            }
            else -> dx <= c.w / 2 + r && dy <= c.h / 2 + r
        }
    }

    /** Control a fresh finger lands on: a direct hit beats a slop hit; ties go to the nearest centre. */
    private fun pickTarget(x: Float, y: Float): PlacedControl? {
        val open = controls.filter { !isCapture(it) || it !in captures.values }
        open.filter { within(it, x, y, false) }.minByOrNull { norm(it, x, y) }?.let { return it }
        return open.filter { within(it, x, y, true) }.minByOrNull { norm(it, x, y) }
    }

    /** Buttons pressed by a free finger at ([x], [y]): the ones it directly covers, else those within slop. */
    private fun buttonsAt(x: Float, y: Float): List<PlacedControl> {
        val buttons = controls.filter { it.def.kind == ControlKind.BUTTON }
        val direct = buttons.filter { within(it, x, y, false) }
        return direct.ifEmpty { buttons.filter { within(it, x, y, true) } }
    }

    fun update(pointers: List<Pointer>): PadState {
        val ids = pointers.mapTo(HashSet()) { it.id }

        for (id in captures.keys.filter { it !in ids }) captures.remove(id)?.let(::endCapture)
        seen.retainAll(ids)

        var freshCapture = false
        for (p in pointers) {
            val owner = captures[p.id]
            if (owner != null) { moveCapture(owner, p); continue }
            if (!seen.add(p.id)) continue
            val target = pickTarget(p.x, p.y)
            if (target != null && isCapture(target)) {
                captures[p.id] = target
                beginCapture(target, p)
                if (target.def.kind != ControlKind.STICK) freshCapture = true
            }
        }

        val pressedIds = HashSet<String>()
        var mask = 0
        for (p in pointers) {
            if (captures.containsKey(p.id)) continue
            for (c in buttonsAt(p.x, p.y)) { mask = mask or (1 shl c.def.bits[0]); pressedIds += c.def.id }
        }

        var lx = 0f; var ly = 0f; var rx = 0f; var ry = 0f; var lt = 0f; var rt = 0f
        for (c in controls) {
            val v = visual(c.def.id)
            when (c.def.kind) {
                ControlKind.BUTTON -> v.pressed = c.def.id in pressedIds
                ControlKind.DPAD -> mask = mask or dpadMask(c.def, v.dirs)
                ControlKind.STICK -> {
                    val o = v.stick.out
                    if (c.def.index == 0) { lx = o.x; ly = o.y } else { rx = o.x; ry = o.y }
                }
                ControlKind.TRIGGER -> {
                    if (c.def.index == 0) lt = v.value else rt = v.value
                    if (v.value >= PadConvert.TRIGGER_CLICK && c.def.bits.isNotEmpty()) mask = mask or (1 shl c.def.bits[0])
                }
            }
        }

        freshPress = freshCapture || newlyPressed(lastMask, mask) != 0
        lastMask = mask
        return PadState(mask, lx, ly, rx, ry, lt, rt)
    }

    private fun beginCapture(c: PlacedControl, p: Pointer) {
        val v = visual(c.def.id)
        v.pressed = true
        when (c.def.kind) {
            ControlKind.STICK -> v.stick.begin(p.x, p.y, c.cx, c.cy, c.radius, floatingSticks, true, bounds)
            ControlKind.DPAD -> v.dirs = DPadMath.direction(p.x - c.cx, p.y - c.cy, c.w)
            ControlKind.TRIGGER -> v.value = triggerValue(c, p)
            ControlKind.BUTTON -> Unit
        }
    }

    private fun moveCapture(c: PlacedControl, p: Pointer) {
        val v = visual(c.def.id)
        when (c.def.kind) {
            ControlKind.STICK -> v.stick.move(p.x, p.y)
            ControlKind.DPAD -> v.dirs = DPadMath.direction(p.x - c.cx, p.y - c.cy, c.w)
            ControlKind.TRIGGER -> v.value = triggerValue(c, p)
            ControlKind.BUTTON -> Unit
        }
    }

    private fun endCapture(c: PlacedControl) {
        val v = visual(c.def.id)
        v.pressed = false; v.dirs = 0; v.value = 0f
        if (c.def.kind == ControlKind.STICK) v.stick.end()
    }

    private fun triggerValue(c: PlacedControl, p: Pointer): Float =
        if (!analogTriggers) 1f else TriggerMath.value((p.y - c.top) / c.h)

    private fun dpadMask(d: ControlDef, dirs: Int): Int {
        var m = 0
        if (dirs and DPadMath.UP != 0) m = m or (1 shl d.bits[0])
        if (dirs and DPadMath.DOWN != 0) m = m or (1 shl d.bits[1])
        if (dirs and DPadMath.LEFT != 0) m = m or (1 shl d.bits[2])
        if (dirs and DPadMath.RIGHT != 0) m = m or (1 shl d.bits[3])
        return m
    }

    companion object {
        /** A fixed stick catches fingers within 1.35x its radius; a floating stick within 2x. */
        const val FIXED_ZONE = 1.35f
        const val FLOAT_ZONE = 2.0f
    }
}
