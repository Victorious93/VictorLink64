package org.victoriousframework.victorlink64.controls

import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

// Victorious Framework: pure math for the on-screen analog controls. No Android types, so it runs in JVM unit tests.
// The overlay design (fixed/floating stick, slide D-pad, analog triggers) follows the behaviour of the official
// Dolphin Android and PPSSPP overlays (GPL-2.0+); this implementation is original VictorLink64 code (GPL-3.0).

data class Vec2(val x: Float, val y: Float) {
    companion object { val ZERO = Vec2(0f, 0f) }
}

/**
 * Shape of a stick's response. [deadzone] and [outer] are fractions of the stick radius: nothing is reported
 * inside the dead zone and full deflection is reported from [outer] outwards (so the thumb never has to reach the
 * very rim). [curve] > 1 gives finer control near the centre.
 */
data class StickConfig(val deadzone: Float = 0.10f, val outer: Float = 0.96f, val curve: Float = 1.15f) {
    fun sanitized(): StickConfig {
        val dz = deadzone.coerceIn(0f, 0.6f)
        return StickConfig(dz, outer.coerceIn(dz + 0.1f, 1f), curve.coerceIn(0.5f, 3f))
    }
}

object StickMath {
    /**
     * Radial dead zone with smooth scaling. ([nx], [ny]) is the thumb offset divided by the stick radius, any
     * magnitude. The result keeps the thumb's direction, has magnitude 0 at the dead-zone edge rising continuously
     * to 1 at [StickConfig.outer], and never exceeds the unit circle (no square-corner overshoot).
     */
    fun shape(nx: Float, ny: Float, config: StickConfig): Vec2 {
        val c = config.sanitized()
        val m = hypot(nx, ny)
        if (m <= c.deadzone || m == 0f) return Vec2.ZERO
        val t = ((m - c.deadzone) / (c.outer - c.deadzone)).coerceIn(0f, 1f)
        val s = t.pow(c.curve)
        return Vec2(nx / m * s, ny / m * s)
    }

    /** libretro analog value (-0x7FFF..0x7FFF) from a -1..1 fraction. */
    fun toAxis16(v: Float): Int = (v.coerceIn(-1f, 1f) * 32767f).roundToInt()

    /** libretro analog-button pressure (0..0x7FFF) from a 0..1 fraction. */
    fun toPressure16(v: Float): Int = (v.coerceIn(0f, 1f) * 32767f).roundToInt()
}

/** Where the touch-stick's drag area may put its base centre; used to keep a floating base on screen. */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun clampX(x: Float, margin: Float = 0f) = if (right - left <= 2 * margin) (left + right) / 2 else x.coerceIn(left + margin, right - margin)
    fun clampY(y: Float, margin: Float = 0f) = if (bottom - top <= 2 * margin) (top + bottom) / 2 else y.coerceIn(top + margin, bottom - margin)
}

/**
 * One finger driving one analog stick. Pixel coordinates in, normalised output out (screen orientation: +x right,
 * +y down). Fixed sticks measure from their home position; floating sticks plant their base where the finger
 * lands and, when [follow] is set, drag the base along once the finger passes the rim so a long swipe never
 * leaves the stick pinned at full deflection with no way back.
 */
class StickTracker(var config: StickConfig = StickConfig()) {
    var active = false; private set
    var baseX = 0f; private set
    var baseY = 0f; private set
    var knobX = 0f; private set
    var knobY = 0f; private set
    var radius = 1f; private set
    var out = Vec2.ZERO; private set
    private var floating = false
    private var follow = true
    private var bounds: Bounds? = null

    fun begin(
        touchX: Float, touchY: Float, homeX: Float, homeY: Float, radius: Float,
        floating: Boolean, follow: Boolean = true, bounds: Bounds? = null,
    ) {
        this.radius = radius.coerceAtLeast(1f)
        this.floating = floating
        this.follow = follow
        this.bounds = bounds
        active = true
        if (floating) {
            baseX = bounds?.clampX(touchX, this.radius) ?: touchX
            baseY = bounds?.clampY(touchY, this.radius) ?: touchY
        } else {
            baseX = homeX; baseY = homeY
        }
        move(touchX, touchY)
    }

    fun move(touchX: Float, touchY: Float) {
        if (!active) return
        var dx = touchX - baseX
        var dy = touchY - baseY
        val m = hypot(dx, dy)
        if (m > radius) {
            if (floating && follow) {
                val pull = 1f - radius / m
                baseX += dx * pull; baseY += dy * pull
                bounds?.let { baseX = it.clampX(baseX, radius); baseY = it.clampY(baseY, radius) }
                dx = touchX - baseX; dy = touchY - baseY
            }
            val m2 = hypot(dx, dy)
            if (m2 > radius) { dx *= radius / m2; dy *= radius / m2 }
        }
        knobX = baseX + dx; knobY = baseY + dy
        out = StickMath.shape(dx / radius, dy / radius, config)
    }

    fun end() {
        active = false
        out = Vec2.ZERO
        knobX = baseX; knobY = baseY
    }
}

/** Analog trigger travel: slide the finger down the control to squeeze harder. */
object TriggerMath {
    const val DEAD = 0.12f
    const val SPAN = 0.66f

    /**
     * Trigger pull (0..1) from the finger's position [t] along the control, 0 at its top edge and 1 at its bottom.
     * The first 12% is dead so a light touch registers just above zero and the bottom third saturates, which
     * makes a full squeeze (the GameCube trigger click) easy to hit without hunting for the edge.
     */
    fun value(t: Float): Float = ((t - DEAD) / SPAN).coerceIn(0f, 1f)
}

object DPadMath {
    const val UP = 1
    const val DOWN = 2
    const val LEFT = 4
    const val RIGHT = 8

    /**
     * Direction bits for a touch at ([dx], [dy]) from the centre of a D-pad of side [size] (+y is down). Inside the
     * dead zone nothing is pressed; outside it the plane splits into eight 45-degree sectors, so each diagonal is
     * as easy to hit as each straight direction and a thumb can slide around the rim without lifting.
     */
    fun direction(dx: Float, dy: Float, size: Float, deadzone: Float = 0.12f): Int {
        val r = hypot(dx, dy)
        if (r < size * deadzone) return 0
        val t = r * SIN_22_5
        var m = 0
        if (dx > t) m = m or RIGHT else if (dx < -t) m = m or LEFT
        if (dy > t) m = m or DOWN else if (dy < -t) m = m or UP
        return m
    }

    private const val SIN_22_5 = 0.3826834f
}
