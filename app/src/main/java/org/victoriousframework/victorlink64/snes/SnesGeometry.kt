package org.victoriousframework.victorlink64.snes

import kotlin.math.sqrt

// Pure geometry for the SNES screen, kept free of Android/Compose types so it runs in JVM unit tests.

/** How the picture is fitted to the window in landscape. NORMAL is the SNES-correct 4:3 display aspect. */
internal enum class Aspect(val label: String, val w: Int, val h: Int) {
    NORMAL("4:3", 4, 3),
    WIDE("16:9", 16, 9),
    FILL("FILL", 0, 0);

    fun next() = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/**
 * Size (width, height) of the picture inside a [frameW] x [frameH] window. Re-run on every layout pass:
 * a size computed once goes stale as soon as the window changes shape (e.g. portrait -> landscape).
 * Portrait always uses 4:3 fit-to-width; [Aspect.WIDE] and [Aspect.FILL] only apply in landscape.
 */
internal fun fitPicture(frameW: Int, frameH: Int, aspect: Aspect): Pair<Int, Int> {
    if (frameW <= 0 || frameH <= 0) return 0 to 0
    val a = if (frameW > frameH) aspect else Aspect.NORMAL
    if (a == Aspect.FILL) return frameW to frameH
    return if (frameW * a.h > frameH * a.w) (frameH * a.w / a.h) to frameH
    else frameW to (frameW * a.h / a.w)
}

internal const val DIR_UP = 1
internal const val DIR_DOWN = 2
internal const val DIR_LEFT = 4
internal const val DIR_RIGHT = 8

/**
 * Direction bits ([DIR_UP] etc.) for a touch at ([dx], [dy]) from the centre of a square D-pad of side [size]
 * (screen coordinates: +y is down). Inside the dead zone nothing is pressed; outside it the plane is split
 * into eight 45-degree sectors, so the four diagonals are as easy to hit as the four straight directions.
 */
internal fun dpadDirection(dx: Float, dy: Float, size: Float): Int {
    val r = sqrt(dx * dx + dy * dy)
    if (r < size * 0.12f) return 0
    val t = r * 0.3827f // sin(22.5 deg): an axis counts once the touch is more than 22.5 deg off the other axis
    var m = 0
    if (dx > t) m = m or DIR_RIGHT else if (dx < -t) m = m or DIR_LEFT
    if (dy > t) m = m or DIR_DOWN else if (dy < -t) m = m or DIR_UP
    return m
}
