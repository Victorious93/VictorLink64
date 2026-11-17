package org.victoriousframework.victorlink64.graphics

import android.content.Context
import android.util.Log
import org.victoriousframework.victorlink64.shizuku.ShizukuController
import paulscode.android.mupen64plusae.profile.Profile
import paulscode.android.mupen64plusae.util.Plugin

/** PAL games run the video interrupt at 50 Hz, NTSC at 60 Hz; Auto needs the right target to judge speed. */
object N64Region {
    private val pal = setOf(0x44, 0x46, 0x49, 0x50, 0x53, 0x55, 0x58, 0x59, 0x20, 0x21, 0x38, 0x70)
    fun targetFps(countryCode: Int): Double = if ((countryCode and 0xFF) in pal) 50.0 else 60.0
}

/**
 * Glue between Graphics settings and the Mupen64Plus-AE code. The N64 core cannot change resolution mid-game, so
 * settings are applied when the game starts (through its emulation profile) and Auto only learns for the next launch.
 * Java entry points are static; all of them are no-ops for video plugins other than GLideN64.
 */
object N64Graphics {
    private const val TAG = "VL64Graphics"
    private const val SHADER_KEY = "shaderPass"
    private const val SHADER_OWNED_KEY = "vl64_shader_owned"

    private fun isGlideN64(profile: Profile) = Plugin(profile, "videoPlugin").name.lowercase().contains("gliden64")

    private fun resolve(ctx: Context, key: String): Resolved {
        val store = GraphicsStores.of(ctx)
        val device = DeviceProfiler.profile(ctx)
        return GraphicsResolver.resolve(GfxSystem.N64, store.game(key), store.global(), device.deviceClass, DeviceProfiler.thermalStatus(ctx))
    }

    /** Called while GamePrefs builds the emulation profile. Writes the chosen picture options into [profile]. */
    @JvmStatic
    fun applyToProfile(ctx: Context, md5: String, profile: Profile) = runCatching {
        if (!isGlideN64(profile)) return@runCatching
        val app = ctx.applicationContext
        val r = resolve(app, GameKeys.n64(md5))
        val o = CoreOptionMapping.n64(r.settings, DeviceProfiler.profile(app).supportsGles31, explicitWidescreen = !r.auto)
        o.profile.forEach { (k, v) -> profile.put(k, v) }
        o.renderHeight?.let { profile.put(CoreOptionMapping.RENDER_HEIGHT_KEY, it.toString()) }
        Log.i(TAG, "N64 ${r.label} auto=${r.auto} tier=${r.tier}")
    }.onFailure { Log.e(TAG, "apply", it) }.let { }

    /** Called from the library right before an N64 game starts (main process). */
    @JvmStatic
    fun prepareLaunch(ctx: Context, md5: String, name: String) {
        runCatching {
            val app = ctx.applicationContext
            val key = GameKeys.n64(md5)
            GraphicsStores.of(app).touchGame(key, GfxSystem.N64, name, System.currentTimeMillis())
            val r = resolve(app, key)
            val shaders = CoreOptionMapping.n64(r.settings, false, false).shaders
            val prefs = app.getSharedPreferences(app.packageName + "_preferences", Context.MODE_PRIVATE)
            val owned = prefs.getBoolean(SHADER_OWNED_KEY, false)
            if (shaders.isNotEmpty()) {
                prefs.edit().putString(SHADER_KEY, shaders.joinToString(",")).putBoolean(SHADER_OWNED_KEY, true).apply()
            } else if (owned) {
                prefs.edit().remove(SHADER_KEY).putBoolean(SHADER_OWNED_KEY, false).apply()
            }
        }.onFailure { Log.e(TAG, "prepareLaunch", it) }
        ShizukuController.onGameStart(ctx, tracked = false)
    }

    private val lock = Any()
    private var session: N64Session? = null

    @JvmStatic
    fun beginSession(ctx: Context, md5: String, profile: Profile, countryCode: Int) {
        runCatching {
            if (!isGlideN64(profile)) { synchronized(lock) { session = null }; return }
            val app = ctx.applicationContext
            val key = GameKeys.n64(md5)
            val r = resolve(app, key)
            synchronized(lock) { session = if (r.auto) N64Session(app, key, r, N64Region.targetFps(countryCode)) else null }
        }.onFailure { Log.e(TAG, "beginSession", it) }
    }

    /** Core FPS callback (about four times a second). */
    @JvmStatic
    fun onFps(fps: Int) {
        val s = synchronized(lock) { session } ?: return
        runCatching { s.onFps(fps, System.nanoTime()) }.onFailure { Log.e(TAG, "onFps", it) }
    }

    @JvmStatic
    fun endSession() = synchronized(lock) { session = null }
}

/**
 * Watches one N64 game in Auto mode. Because the core cannot change quality live, the first change the tuner asks
 * for is saved for the next launch and watching stops; thermal pressure is never saved.
 */
class N64Session(private val ctx: Context, private val key: String, r: Resolved, private val target: Double) {
    private val store = GraphicsStores.of(ctx)
    private val tuner = AutoTuner(
        r.maxTier, r.tier ?: 0, r.knownBadTier,
        AutoTuner.Params(warmupMs = 15_000, probeAfterMs = 300_000),
    )
    private var done = false
    private var windowStartNs = 0L
    private var lastNs = 0L
    private var sum = 0.0
    private var count = 0

    fun onFps(fps: Int, nowNs: Long) {
        if (done || fps <= 0) return
        if (lastNs == 0L || nowNs - lastNs > 2_000_000_000L) { windowStartNs = nowNs; sum = 0.0; count = 0 }
        lastNs = nowNs
        sum += fps; count++
        if (nowNs - windowStartNs < GraphicsSession.WINDOW_MS * 1_000_000) return
        val speed = FrameMonitor.speedOf(sum / count, target)
        windowStartNs = nowNs; sum = 0.0; count = 0
        val d = tuner.onWindow(speed, DeviceProfiler.thermalStatus(ctx), GraphicsSession.WINDOW_MS)
        tuner.learnedTier?.let { l -> store.updateGame(key) { it.copy(learnedTier = l) } }
        if (d is AutoTuner.Decision.Change) {
            done = true
            when (d.reason) {
                AutoTuner.Reason.SLOW -> store.updateGame(key) { it.copy(knownBadTier = tuner.knownBadTier, learnedTier = it.learnedTier?.takeIf { l -> l < d.from }) }
                AutoTuner.Reason.PROBE -> store.updateGame(key) { it.copy(learnedTier = d.to) }
                AutoTuner.Reason.THERMAL -> {}
            }
        }
    }
}
