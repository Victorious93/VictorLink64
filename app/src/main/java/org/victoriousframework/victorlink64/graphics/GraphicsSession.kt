package org.victoriousframework.victorlink64.graphics

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.victoriousframework.victorlink64.shizuku.ShizukuController
import java.io.File
import kotlin.math.roundToInt

object GraphicsStores {
    fun of(ctx: Context): GraphicsStore = GraphicsStore(FileBackend(File(ctx.applicationContext.filesDir, "vl64_graphics.json")))

    /** Shared with the Settings tab's FPS toggle. */
    const val APP_PREFS = "victorlink64_prefs"
    const val KEY_SHOW_FPS = "show_fps"

    fun fpsCounterOn(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_FPS, false)
}

/**
 * Applies a game's graphics settings to a running libretro frontend (PSP, GameCube, SNES), watches the frame rate,
 * and in Auto mode moves along the quality ladder, remembering what worked for the game.
 * Create it before the core loads, call [onFrame] on the emulation thread after every frame, call [close] when done.
 */
class GraphicsSession private constructor(
    private val ctx: Context,
    private val system: GfxSystem,
    private val gameKey: String,
    private val sink: GfxSink,
    private val store: GraphicsStore,
    private val targetFps: () -> Double,
    resolved: Resolved,
) {
    private val ui = Handler(Looper.getMainLooper())
    private val showFps = GraphicsStores.fpsCounterOn(ctx)
    private var overlay: TextView? = null
    private var monitor: FrameMonitor? = null
    private val tuner: AutoTuner? = if (resolved.auto) AutoTuner(resolved.maxTier, resolved.tier ?: 0, resolved.knownBadTier) else null
    private var label = resolved.label
    private var lastSavedLearned: Int? = null
    private var lastSavedBad: Int? = null

    init {
        sink.clear()
        apply(resolved.settings)
        lastSavedBad = resolved.knownBadTier
    }

    /** Adds the optional FPS counter on top of [frame]. */
    fun attach(frame: FrameLayout) {
        if (!showFps) return
        val tv = TextView(frame.context).apply {
            setTextColor(Color.WHITE); setShadowLayer(4f, 0f, 0f, Color.BLACK)
            typeface = Typeface.MONOSPACE; textSize = 11f
            setBackgroundColor(0x990A1128.toInt())
            setPadding(12, 4, 12, 4)
            isClickable = false; isFocusable = false
            text = "-- FPS"
        }
        frame.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
            .apply { setMargins(8, 8, 0, 0) })
        overlay = tv
    }

    fun onFrame() {
        val m = monitor ?: FrameMonitor(targetFps().takeIf { it > 1 } ?: 60.0).also { monitor = it }
        val w = m.onFrame(System.nanoTime()) ?: return
        overlay?.let { tv -> ui.post { tv.text = "${w.fps.roundToInt()} FPS \u00B7 $label" } }
        val t = tuner ?: return
        val decision = t.onWindow(w.speed, DeviceProfiler.thermalStatus(ctx), WINDOW_MS)
        if (decision is AutoTuner.Decision.Change) {
            val (settings, name) = GraphicsResolver.forTier(system, decision.to)
            apply(settings)
            label = "$name (auto)"
            val why = when (decision.reason) {
                AutoTuner.Reason.SLOW -> "to keep full speed"
                AutoTuner.Reason.THERMAL -> "because the phone is hot"
                AutoTuner.Reason.PROBE -> "after a smooth run"
            }
            ui.post { Toast.makeText(ctx, "Graphics: $name $why", Toast.LENGTH_SHORT).show() }
        }
        persist(t)
    }

    fun close() {
        tuner?.let { persist(it) }
        overlay?.let { tv -> ui.post { (tv.parent as? FrameLayout)?.removeView(tv) } }
        ShizukuController.onGameEnd(ctx)
    }

    private fun apply(s: GraphicsSettings) {
        val o = CoreOptionMapping.forSystem(system, s)
        o.vars.forEach { (k, v) -> sink.setVariable(k, v) }
        sink.setPost(o.postFx.id, o.nearest)
    }

    private fun persist(t: AutoTuner) {
        val learned = t.learnedTier
        val bad = t.knownBadTier
        if (learned == lastSavedLearned && bad == lastSavedBad) return
        lastSavedLearned = learned; lastSavedBad = bad
        store.updateGame(gameKey) { r ->
            val keptLearned = learned ?: r.learnedTier?.takeIf { bad == null || it < bad }
            r.copy(learnedTier = keptLearned, knownBadTier = bad)
        }
    }

    companion object {
        const val WINDOW_MS = 2000L

        /** Resolves the game's settings, applies them to [sink] and starts tracking. Call before the core loads. */
        fun start(ctx: Context, system: GfxSystem, gameKey: String, gameName: String, sink: GfxSink, targetFps: () -> Double): GraphicsSession {
            val app = ctx.applicationContext
            val store = GraphicsStores.of(app)
            store.touchGame(gameKey, system, gameName, System.currentTimeMillis())
            val device = DeviceProfiler.profile(app)
            val resolved = GraphicsResolver.resolve(system, store.game(gameKey), store.global(), device.deviceClass, DeviceProfiler.thermalStatus(app))
            return GraphicsSession(app, system, gameKey, sink, store, targetFps, resolved)
        }
    }
}
