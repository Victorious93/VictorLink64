package org.victoriousframework.victorlink64.pc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/*
 * Victorious Framework: on-screen Xbox-style controller for PC streaming.
 * One multi-touch handler; each finger is bound to the control it first touched.
 * Brand rule: navy + cyan-blue only, never red, on every button.
 */
class PcTouchPad(context: Context, private val onState: (PadState) -> Unit) : View(context) {

    data class PadState(
        val buttons: Int = 0, val lt: Float = 0f, val rt: Float = 0f,
        val lx: Float = 0f, val ly: Float = 0f, val rx: Float = 0f, val ry: Float = 0f,
    )

    private sealed class Ctl(val r: RectF) {
        class Btn(r: RectF, val label: String, val flag: Int, val round: Boolean = true) : Ctl(r)
        class Trig(r: RectF, val label: String, val left: Boolean) : Ctl(r)
        class Stick(r: RectF, val left: Boolean) : Ctl(r)
        class Dpad(r: RectF) : Ctl(r)
    }

    private val cyan = 0xFF3FD0FF.toInt()
    private val navy = 0xFF0A1128.toInt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = cyan }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    private var ctls: List<Ctl> = emptyList()
    private val owner = HashMap<Int, Ctl>()           // pointerId -> control
    private val stickPos = HashMap<Ctl, Pair<Float, Float>>()
    private val dpadBits = HashMap<Int, Int>()        // pointerId -> dpad flags
    private var state = PadState()
    var alphaLevel = 0.8f; set(v) { field = v; invalidate() }
    var sizeScale = 1f; set(v) { field = v; if (width > 0) onSizeChanged(width, height, width, height); invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val u = min(w, h) / 10f * sizeScale        // base unit
        val m = u * 0.5f
        val b = u * 1.05f               // face button size
        val list = ArrayList<Ctl>()
        // Shoulders and triggers along the top corners.
        list += Ctl.Trig(RectF(m, m, m + 2.2f * u, m + 0.9f * u), "LT", true)
        list += Ctl.Btn(RectF(m, m + 1.1f * u, m + 2.2f * u, m + 2.0f * u), "LB", PadFlags.LB, round = false)
        list += Ctl.Trig(RectF(w - m - 2.2f * u, m, w - m, m + 0.9f * u), "RT", false)
        list += Ctl.Btn(RectF(w - m - 2.2f * u, m + 1.1f * u, w - m, m + 2.0f * u), "RB", PadFlags.RB, round = false)
        // Left stick bottom-left, D-pad above-right of it.
        val ls = 1.6f * u
        list += Ctl.Stick(RectF(m + 0.3f * u, h - m - 2 * ls, m + 0.3f * u + 2 * ls, h - m), true)
        list += Ctl.Dpad(RectF(m + 3.6f * u, h - m - 5.6f * u, m + 3.6f * u + 2.6f * u, h - m - 3.0f * u))
        // Face buttons bottom-right (Xbox layout: Y top, A bottom, X left, B right).
        val cx = w - m - 2.0f * u; val cy = h - m - 3.6f * u
        fun face(dx: Float, dy: Float, l: String, f: Int) =
            Ctl.Btn(RectF(cx + dx * u - b / 2, cy + dy * u - b / 2, cx + dx * u + b / 2, cy + dy * u + b / 2), l, f)
        list += face(0f, -1.2f, "Y", PadFlags.Y); list += face(0f, 1.2f, "A", PadFlags.A)
        list += face(-1.2f, 0f, "X", PadFlags.X); list += face(1.2f, 0f, "B", PadFlags.B)
        // Right stick below-left of the face buttons.
        val rs = 1.2f * u
        list += Ctl.Stick(RectF(w - m - 4.8f * u - 2 * rs, h - m - 2 * rs, w - m - 4.8f * u, h - m), false)
        // Back / Start in the middle bottom.
        list += Ctl.Btn(RectF(w / 2f - 2.2f * u, h - m - 0.8f * u, w / 2f - 0.4f * u, h - m), "BACK", PadFlags.BACK, round = false)
        list += Ctl.Btn(RectF(w / 2f + 0.4f * u, h - m - 0.8f * u, w / 2f + 2.2f * u, h - m), "START", PadFlags.START, round = false)
        ctls = list
        text.textSize = u * 0.42f
        rim.strokeWidth = u * 0.06f
    }

    private fun hit(x: Float, y: Float): Ctl? = ctls.firstOrNull { c ->
        val pad = if (c is Ctl.Stick) c.r.width() * 0.25f else c.r.width() * 0.12f
        x >= c.r.left - pad && x <= c.r.right + pad && y >= c.r.top - pad && y <= c.r.bottom + pad
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val c = hit(e.getX(i), e.getY(i)) ?: return owner.isNotEmpty()
                owner[e.getPointerId(i)] = c
                padHaptic(this)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                owner.remove(id)?.let { if (it is Ctl.Stick) stickPos.remove(it) }
                dpadBits.remove(id)
            }
            MotionEvent.ACTION_CANCEL -> { owner.clear(); stickPos.clear(); dpadBits.clear() }
        }
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i)
            val c = owner[id] ?: continue
            if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex) continue
            val x = e.getX(i); val y = e.getY(i)
            when (c) {
                is Ctl.Stick -> {
                    val rad = c.r.width() / 2f
                    var dx = (x - c.r.centerX()) / rad; var dy = (y - c.r.centerY()) / rad
                    val len = hypot(dx, dy); if (len > 1f) { dx /= len; dy /= len }
                    stickPos[c] = dx to dy
                }
                is Ctl.Dpad -> {
                    val dx = (x - c.r.centerX()) / (c.r.width() / 2f); val dy = (y - c.r.centerY()) / (c.r.height() / 2f)
                    var f = 0
                    if (dx < -0.35f) f = f or PadFlags.LEFT
                    if (dx > 0.35f) f = f or PadFlags.RIGHT
                    if (dy < -0.35f) f = f or PadFlags.UP
                    if (dy > 0.35f) f = f or PadFlags.DOWN
                    dpadBits[id] = f
                }
                else -> {}
            }
        }
        publish()
        return true
    }

    private fun publish() {
        var btn = 0; var lt = 0f; var rt = 0f
        owner.values.forEach { c ->
            if (c is Ctl.Btn) btn = btn or c.flag
            if (c is Ctl.Trig) { if (c.left) lt = 1f else rt = 1f }
        }
        dpadBits.values.forEach { btn = btn or it }
        var lx = 0f; var ly = 0f; var rx = 0f; var ry = 0f
        for ((c, p) in stickPos) {
            if ((c as Ctl.Stick).left) { lx = p.first; ly = p.second } else { rx = p.first; ry = p.second }
        }
        val n = PadState(btn, lt, rt, lx, ly, rx, ry)
        if (n != state) { state = n; onState(n); invalidate() }
    }

    override fun onDraw(cv: Canvas) {
        val a = (alphaLevel * 255).toInt()
        ctls.forEach { c ->
            val down = owner.values.contains(c) || (c is Ctl.Dpad && dpadBits.isNotEmpty())
            fill.color = if (down) cyan else navy
            fill.alpha = if (down) (a * 0.7f).toInt() else (a * 0.55f).toInt()
            rim.alpha = a
            text.alpha = a
            when (c) {
                is Ctl.Stick -> {
                    cv.drawCircle(c.r.centerX(), c.r.centerY(), c.r.width() / 2f, fill.apply { color = navy; alpha = (a * 0.45f).toInt() })
                    cv.drawCircle(c.r.centerX(), c.r.centerY(), c.r.width() / 2f, rim)
                    val p = stickPos[c] ?: (0f to 0f)
                    val kr = c.r.width() * 0.22f
                    fill.color = cyan; fill.alpha = (a * 0.8f).toInt()
                    cv.drawCircle(c.r.centerX() + p.first * c.r.width() / 2.6f, c.r.centerY() + p.second * c.r.width() / 2.6f, kr, fill)
                }
                is Ctl.Dpad -> {
                    val t = c.r.width() / 3f
                    val bits = dpadBits.values.fold(0) { acc, v -> acc or v }
                    fun arm(rr: RectF, flag: Int) {
                        fill.color = if (bits and flag != 0) cyan else navy
                        fill.alpha = (a * 0.6f).toInt()
                        cv.drawRoundRect(rr, t * 0.2f, t * 0.2f, fill); cv.drawRoundRect(rr, t * 0.2f, t * 0.2f, rim)
                    }
                    arm(RectF(c.r.left + t, c.r.top, c.r.left + 2 * t, c.r.top + t), PadFlags.UP)
                    arm(RectF(c.r.left + t, c.r.bottom - t, c.r.left + 2 * t, c.r.bottom), PadFlags.DOWN)
                    arm(RectF(c.r.left, c.r.top + t, c.r.left + t, c.r.top + 2 * t), PadFlags.LEFT)
                    arm(RectF(c.r.right - t, c.r.top + t, c.r.right, c.r.top + 2 * t), PadFlags.RIGHT)
                }
                is Ctl.Btn -> {
                    if (c.round) { cv.drawOval(c.r, fill); cv.drawOval(c.r, rim) }
                    else { val rr = c.r.height() / 2; cv.drawRoundRect(c.r, rr, rr, fill); cv.drawRoundRect(c.r, rr, rr, rim) }
                    cv.drawText(c.label, c.r.centerX(), c.r.centerY() + text.textSize / 3, text)
                }
                is Ctl.Trig -> {
                    val rr = c.r.height() / 2; cv.drawRoundRect(c.r, rr, rr, fill); cv.drawRoundRect(c.r, rr, rr, rim)
                    cv.drawText(c.label, c.r.centerX(), c.r.centerY() + text.textSize / 3, text)
                }
            }
        }
    }
}
