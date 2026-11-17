package org.victoriousframework.victorlink64.controls

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.hypot

/** Snapshot of the control being edited, for the edit panel. [scale] is relative to the control's default size. */
data class EditSelection(val id: String, val label: String, val scale: Float, val opacity: Float)

/**
 * The on-screen controller for every console. Victorious Framework.
 *
 * Draws the console's controls (brand navy + cyan, never red), feeds fingers to a [TouchEngine] and reports a
 * [PadState] whenever it changes. In edit mode the same view lets the user drag controls, resize them with the corner
 * handle and tune each control's opacity; layouts are saved per console and orientation by [LayoutStore].
 *
 * Design reference: the official Dolphin Android and PPSSPP overlays (GPL-2.0+). This is original code.
 */
@SuppressLint("ViewConstructor")
class TouchOverlayView(
    context: Context,
    val console: Console,
    private val settings: OverlaySettings,
    private val store: LayoutStore,
) : View(context) {
    var onPadState: ((PadState) -> Unit)? = null
    var onSelection: ((EditSelection?) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val engine = TouchEngine()
    private val defs = ConsoleLayouts.defs(console)
    private var area = Area(0f, 0f, 0f, 0f)
    private var landscape = true
    private var placements: MutableMap<String, Placement> = HashMap()
    private var placed: List<PlacedControl> = emptyList()
    private var inset = android.graphics.Rect()
    private var last = PadState.NEUTRAL
    private var hideAlpha = 1f
    private var hideAnim: ValueAnimator? = null
    private val stickRest = HashMap<String, FloatArray>()

    var hidden = false; private set
    var editing = false; private set
    private var selected: PlacedControl? = null
    private var drag = Drag.NONE
    private var grabDx = 0f
    private var grabDy = 0f
    private var resizeStartDist = 1f
    private var resizeStartW = 1f

    private enum class Drag { NONE, MOVE, RESIZE }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val path = Path()
    private val clip = Path()
    private val box = RectF()

    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    private fun dp(v: Float) = v * density

    // ---- layout -------------------------------------------------------------------------------------------------

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val c = WindowInsetsCompat.toWindowInsetsCompat(insets, this)
            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        if (inset.left != c.left || inset.top != c.top || inset.right != c.right || inset.bottom != c.bottom) {
            inset.set(c.left, c.top, c.right, c.bottom)
            rebuild()
        }
        return insets
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    /** Re-reads settings and the saved layout for the current size and orientation. Call after settings change. */
    fun rebuild() {
        if (width <= 0 || height <= 0) return
        val margin = dp(8f)
        area = Area(
            inset.left + margin, inset.top + margin,
            (width - inset.left - inset.right - 2 * margin).coerceAtLeast(1f),
            (height - inset.top - inset.bottom - 2 * margin).coerceAtLeast(1f),
        )
        landscape = area.width > area.height
        placements = store.load(console, landscape).toMutableMap()
        placed = LayoutGeometry.place(defs, placements, area, settings.masterScale)
        engine.slopPx = dp(10f)
        engine.floatingSticks = settings.stickMode == StickMode.FLOATING
        engine.analogTriggers = settings.analogTriggers
        engine.stickConfig = settings.stickConfig()
        engine.bounds = Bounds(area.left, area.top, area.right, area.bottom)
        engine.controls = placed
        stickRest.clear()
        selected = selected?.let { s -> placed.firstOrNull { it.def.id == s.def.id } }
        emit(PadState.NEUTRAL)
        invalidate()
    }

    // ---- state --------------------------------------------------------------------------------------------------

    private fun emit(s: PadState) {
        if (s == last) return
        last = s
        onPadState?.invoke(s)
    }

    /** Lifts every virtual finger (focus lost, activity paused, edit mode opened) so nothing stays held. */
    fun release() {
        engine.releaseAll()
        emit(PadState.NEUTRAL)
        invalidate()
    }

    /** Fades the controls out (hardware controller in use) or back in. Touches are ignored while hidden. */
    fun setHidden(value: Boolean, animate: Boolean = true) {
        if (hidden == value) return
        hidden = value
        if (value) release()
        hideAnim?.cancel()
        val target = if (value) 0f else 1f
        if (!animate) { hideAlpha = target; invalidate(); return }
        hideAnim = ValueAnimator.ofFloat(hideAlpha, target).apply {
            duration = 220
            addUpdateListener { hideAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) release()
    }

    override fun onDetachedFromWindow() {
        hideAnim?.cancel()
        release()
        super.onDetachedFromWindow()
    }

    // ---- touch --------------------------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editing) return editTouch(e)
        if (hidden) return false
        val pts = ArrayList<Pointer>(e.pointerCount)
        when (e.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Unit
            else -> {
                val lifting = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
                for (i in 0 until e.pointerCount) if (i != lifting) pts += Pointer(e.getPointerId(i), e.getX(i), e.getY(i))
            }
        }
        val s = engine.update(pts)
        if (engine.freshPress && settings.haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        emit(s)
        invalidate()
        return true
    }

    // ---- edit mode ----------------------------------------------------------------------------------------------

    fun setEditing(value: Boolean) {
        if (editing == value) return
        editing = value
        release()
        drag = Drag.NONE
        if (!value) selected = null
        notifySelection()
        invalidate()
    }

    fun resetLayout() {
        store.reset(console, landscape)
        rebuild()
        notifySelection()
    }

    fun setSelectedScale(scale: Float) {
        val c = selected ?: return
        val s = scale.coerceIn(Placement.MIN_SCALE, Placement.MAX_SCALE) * settings.masterScale
        c.w = c.def.size * area.edge * s
        c.h = c.def.height * area.edge * s
        LayoutGeometry.clamp(c, area)
        commit(c)
        notifySelection()
    }

    fun setSelectedOpacity(opacity: Float) {
        val c = selected ?: return
        c.opacity = opacity.coerceIn(Placement.MIN_OPACITY, 1f)
        commit(c)
        notifySelection()
    }

    private fun relScale(c: PlacedControl) = c.w / (c.def.size * area.edge * settings.masterScale)

    private fun notifySelection() {
        val c = selected
        onSelection?.invoke(if (!editing || c == null) null else EditSelection(c.def.id, labelFor(c), relScale(c), c.opacity))
    }

    private fun labelFor(c: PlacedControl): String = when (c.def.id) {
        "dpad" -> "D-pad"
        "stick" -> if (console == Console.GAMECUBE || console == Console.N64) "Control stick" else "Analog stick"
        "cstick" -> "C-stick"
        "rstick" -> "Right analog stick"
        "l3" -> "L3 (left stick click)"; "r3" -> "R3 (right stick click)"
        "lt" -> "L trigger"
        "rt" -> "R trigger"
        "cu", "cd", "cl", "cr" -> "C button"
        "tri" -> "Triangle"; "sq" -> "Square"; "cir" -> "Circle"; "cross" -> "Cross"
        else -> c.def.label.ifBlank { c.def.id.uppercase() }
    }

    private fun commit(c: PlacedControl) {
        val old = placements[c.def.id] ?: return
        placements[c.def.id] = LayoutGeometry.toPlacement(c, area, old, settings.masterScale)
        invalidate()
    }

    /** Writes the current arrangement to storage (called when a drag, resize or slider change finishes). */
    fun saveLayout() = store.save(console, landscape, placements.values)

    private fun handle(c: PlacedControl) = Pair(c.right, c.bottom)

    private fun editTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = e.x; val y = e.y
                val sel = selected
                if (sel != null) {
                    val (hx, hy) = handle(sel)
                    if (hypot(x - hx, y - hy) <= dp(26f)) {
                        drag = Drag.RESIZE
                        resizeStartDist = hypot(x - sel.cx, y - sel.cy).coerceAtLeast(1f)
                        resizeStartW = sel.w
                        return true
                    }
                }
                val hit = placed.filter { x in it.left..it.right && y in it.top..it.bottom }
                    .minByOrNull { hypot((x - it.cx) / it.w, (y - it.cy) / it.h) }
                selected = hit
                if (hit != null) { drag = Drag.MOVE; grabDx = hit.cx - x; grabDy = hit.cy - y } else drag = Drag.NONE
                notifySelection()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val c = selected ?: return true
                when (drag) {
                    Drag.MOVE -> {
                        c.cx = e.x + grabDx; c.cy = e.y + grabDy
                        LayoutGeometry.clamp(c, area)
                        commit(c)
                    }
                    Drag.RESIZE -> {
                        val ratio = hypot(e.x - c.cx, e.y - c.cy) / resizeStartDist
                        val minW = c.def.size * area.edge * Placement.MIN_SCALE * settings.masterScale
                        val maxW = c.def.size * area.edge * Placement.MAX_SCALE * settings.masterScale
                        val w = (resizeStartW * ratio).coerceIn(minW, maxW)
                        c.h = c.h * (w / c.w); c.w = w
                        LayoutGeometry.clamp(c, area)
                        commit(c)
                        notifySelection()
                    }
                    Drag.NONE -> Unit
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag != Drag.NONE) { saveLayout(); notifySelection() }
                drag = Drag.NONE
            }
        }
        return true
    }

    // ---- drawing ------------------------------------------------------------------------------------------------

    private fun argb(base: Int, alpha: Float): Int =
        Color.argb((Color.alpha(base) * alpha.coerceIn(0f, 1f)).toInt(), Color.red(base), Color.green(base), Color.blue(base))

    private fun accent(c: PlacedControl) = if (c.def.accent == Accent.STEEL) STEEL else CYAN

    private fun buildShape(c: PlacedControl, grow: Float, out: Path) {
        out.reset()
        box.set(c.left - grow, c.top - grow, c.right + grow, c.bottom + grow)
        val h = box.height()
        when (c.def.shape) {
            ControlShape.CIRCLE, ControlShape.OVAL -> out.addOval(box, Path.Direction.CW)
            ControlShape.PILL -> out.addRoundRect(box, h / 2, h / 2, Path.Direction.CW)
            ControlShape.SHOULDER_LEFT -> {
                val big = h * 0.9f; val small = h * 0.22f
                out.addRoundRect(box, floatArrayOf(big, big, small, small, small, small, small, small), Path.Direction.CW)
            }
            ControlShape.SHOULDER_RIGHT -> {
                val big = h * 0.9f; val small = h * 0.22f
                out.addRoundRect(box, floatArrayOf(small, small, big, big, small, small, small, small), Path.Direction.CW)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (placed.isEmpty()) return
        var animating = false
        for (c in placed) {
            val a = settings.masterOpacity * c.opacity * (if (editing) 1f else hideAlpha)
            if (a <= 0.01f) continue
            val v = engine.visual(c.def.id)
            when (c.def.kind) {
                ControlKind.BUTTON -> drawButton(canvas, c, v.pressed, a)
                ControlKind.TRIGGER -> drawTrigger(canvas, c, v.value, a)
                ControlKind.DPAD -> drawDpad(canvas, c, v.dirs, a)
                ControlKind.STICK -> if (drawStick(canvas, c, v, a)) animating = true
            }
        }
        if (editing) drawEditChrome(canvas)
        if (animating) postInvalidateOnAnimation()
    }

    private fun labelSize(c: PlacedControl): Float =
        (c.def.fontScale * 0.04f * area.edge * (c.w / (c.def.size * area.edge))).coerceAtLeast(dp(8f))

    private fun drawLabel(canvas: Canvas, c: PlacedControl, label: String, alpha: Float, cy: Float = c.cy) {
        if (label.isBlank()) return
        text.textSize = labelSize(c)
        text.color = argb(Color.WHITE, alpha)
        text.setShadowLayer(dp(2f), 0f, dp(1f), argb(Color.BLACK, alpha * 0.7f))
        canvas.drawText(label, c.cx, cy - (text.ascent() + text.descent()) / 2, text)
    }

    private fun drawButton(canvas: Canvas, c: PlacedControl, down: Boolean, a: Float) {
        val acc = accent(c)
        canvas.save()
        if (down) { val s = 0.94f; canvas.scale(s, s, c.cx, c.cy) }
        buildShape(c, 0f, path)
        if (down) {
            buildShape(c, dp(3f), clip)
            rim.strokeWidth = dp(4f); rim.color = argb(acc, 0.28f * a); canvas.drawPath(clip, rim)
        }
        fill.style = Paint.Style.FILL
        fill.color = if (down) argb(acc, 0.85f * a) else argb(NAVY, 0.62f * a)
        canvas.drawPath(path, fill)
        glassHighlight(canvas, c, a)
        rim.strokeWidth = if (down) dp(2.6f) else dp(1.6f)
        rim.color = argb(acc, (if (down) 1f else 0.8f) * a)
        canvas.drawPath(path, rim)
        drawLabel(canvas, c, c.def.label, a)
        canvas.restore()
    }

    private fun glassHighlight(canvas: Canvas, c: PlacedControl, a: Float) {
        canvas.save()
        canvas.clipPath(path)
        fill.color = argb(Color.WHITE, 0.10f * a)
        canvas.drawRect(c.left, c.top, c.right, c.cy, fill)
        canvas.restore()
    }

    private fun drawTrigger(canvas: Canvas, c: PlacedControl, value: Float, a: Float) {
        val acc = accent(c)
        buildShape(c, 0f, path)
        fill.style = Paint.Style.FILL
        fill.color = argb(NAVY, 0.62f * a)
        canvas.drawPath(path, fill)
        if (value > 0f) {
            canvas.save()
            canvas.clipPath(path)
            fill.color = argb(acc, 0.80f * a)
            canvas.drawRect(c.left, c.bottom - c.h * value, c.right, c.bottom, fill)
            canvas.restore()
        }
        glassHighlight(canvas, c, a)
        rim.strokeWidth = if (value > 0f) dp(2.6f) else dp(1.6f)
        rim.color = argb(acc, (if (value > 0f) 1f else 0.8f) * a)
        canvas.drawPath(path, rim)
        drawLabel(canvas, c, c.def.label, a, c.top + c.h * 0.3f)
    }

    private fun drawDpad(canvas: Canvas, c: PlacedControl, dirs: Int, a: Float) {
        val arm = c.w / 3f
        val r = arm * 0.28f
        path.reset()
        clip.reset()
        path.addRoundRect(c.cx - c.w / 2, c.cy - arm / 2, c.cx + c.w / 2, c.cy + arm / 2, r, r, Path.Direction.CW)
        clip.addRoundRect(c.cx - arm / 2, c.cy - c.h / 2, c.cx + arm / 2, c.cy + c.h / 2, r, r, Path.Direction.CW)
        path.op(clip, Path.Op.UNION)
        fill.style = Paint.Style.FILL
        fill.color = argb(NAVY, 0.62f * a)
        canvas.drawPath(path, fill)
        canvas.save()
        canvas.clipPath(path)
        fill.color = argb(CYAN, 0.85f * a)
        if (dirs and DPadMath.UP != 0) canvas.drawRect(c.cx - arm / 2, c.top, c.cx + arm / 2, c.cy, fill)
        if (dirs and DPadMath.DOWN != 0) canvas.drawRect(c.cx - arm / 2, c.cy, c.cx + arm / 2, c.bottom, fill)
        if (dirs and DPadMath.LEFT != 0) canvas.drawRect(c.left, c.cy - arm / 2, c.cx, c.cy + arm / 2, fill)
        if (dirs and DPadMath.RIGHT != 0) canvas.drawRect(c.cx, c.cy - arm / 2, c.right, c.cy + arm / 2, fill)
        fill.color = argb(Color.WHITE, 0.10f * a)
        canvas.drawRect(c.left, c.top, c.right, c.cy, fill)
        canvas.restore()
        rim.strokeWidth = if (dirs != 0) dp(2.4f) else dp(1.6f)
        rim.color = argb(CYAN, (if (dirs != 0) 1f else 0.8f) * a)
        canvas.drawPath(path, rim)
        drawArrow(canvas, c.cx, c.top + arm * 0.32f, 0f, -1f, arm, a)
        drawArrow(canvas, c.cx, c.bottom - arm * 0.32f, 0f, 1f, arm, a)
        drawArrow(canvas, c.left + arm * 0.32f, c.cy, -1f, 0f, arm, a)
        drawArrow(canvas, c.right - arm * 0.32f, c.cy, 1f, 0f, arm, a)
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float, arm: Float, a: Float) {
        val s = arm * 0.2f
        path.reset()
        path.moveTo(x + dx * s, y + dy * s)
        path.lineTo(x - dx * s + dy * s, y - dy * s + dx * s)
        path.lineTo(x - dx * s - dy * s, y - dy * s - dx * s)
        path.close()
        fill.style = Paint.Style.FILL
        fill.color = argb(Color.WHITE, 0.85f * a)
        canvas.drawPath(path, fill)
    }

    /** Returns true while the knob is still springing back, so the view keeps animating. */
    private fun drawStick(canvas: Canvas, c: PlacedControl, v: ControlVisual, a: Float): Boolean {
        val s = v.stick
        val floating = engine.floatingSticks
        val bx = if (s.active) s.baseX else c.cx
        val by = if (s.active) s.baseY else c.cy
        val rest = stickRest.getOrPut(c.def.id) { floatArrayOf(c.cx, c.cy) }
        var springing = false
        if (s.active) { rest[0] = s.knobX; rest[1] = s.knobY } else {
            val dx = c.cx - rest[0]; val dy = c.cy - rest[1]
            if (abs(dx) + abs(dy) > 0.5f) { rest[0] += dx * 0.4f; rest[1] += dy * 0.4f; springing = true } else { rest[0] = c.cx; rest[1] = c.cy }
        }
        val idle = !s.active
        val ghost = if (floating && idle) 0.55f else 1f
        val r = c.radius
        fill.style = Paint.Style.FILL
        fill.color = argb(NAVY, 0.45f * a * ghost)
        canvas.drawCircle(bx, by, r, fill)
        rim.strokeWidth = dp(1.6f)
        rim.color = argb(CYAN, (if (idle) 0.65f else 0.95f) * a * ghost)
        canvas.drawCircle(bx, by, r, rim)
        rim.strokeWidth = dp(1f)
        rim.color = argb(CYAN, 0.25f * a * ghost)
        canvas.drawCircle(bx, by, r * s.config.deadzone.coerceAtLeast(0.12f), rim)
        val kr = r * 0.46f
        val kx = if (s.active) s.knobX else rest[0]
        val ky = if (s.active) s.knobY else rest[1]
        fill.color = argb(CYAN, (if (idle) 0.55f else 0.92f) * a * ghost)
        canvas.drawCircle(kx, ky, kr, fill)
        fill.color = argb(Color.WHITE, 0.16f * a * ghost)
        canvas.drawCircle(kx, ky - kr * 0.28f, kr * 0.62f, fill)
        rim.strokeWidth = dp(1.6f)
        rim.color = argb(Color.WHITE, 0.7f * a * ghost)
        canvas.drawCircle(kx, ky, kr, rim)
        if (c.def.label.isNotBlank()) drawLabel(canvas, c, c.def.label, a * 0.9f * ghost, ky)
        return springing
    }

    private val dash = DashPathEffect(floatArrayOf(18f, 12f), 0f)

    private fun drawEditChrome(canvas: Canvas) {
        for (c in placed) {
            val sel = c === selected
            buildShape(c, dp(4f), path)
            rim.strokeWidth = if (sel) dp(2.4f) else dp(1.2f)
            rim.color = if (sel) CYAN else argb(Color.WHITE, 0.55f)
            rim.pathEffect = if (sel) null else dash
            canvas.drawPath(path, rim)
            rim.pathEffect = null
        }
        selected?.let { c ->
            val (hx, hy) = handle(c)
            fill.style = Paint.Style.FILL
            fill.color = NAVY
            canvas.drawCircle(hx, hy, dp(12f), fill)
            rim.strokeWidth = dp(2f); rim.color = CYAN
            canvas.drawCircle(hx, hy, dp(12f), rim)
            fill.color = CYAN
            canvas.drawCircle(hx, hy, dp(4f), fill)
        }
    }

    companion object {
        val CYAN = Color.rgb(0x3F, 0xD0, 0xFF)
        val NAVY = Color.rgb(0x0A, 0x11, 0x28)
        val STEEL = Color.rgb(0x8F, 0xA3, 0xC8)
    }
}
