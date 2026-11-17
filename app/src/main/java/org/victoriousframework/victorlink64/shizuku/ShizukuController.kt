package org.victoriousframework.victorlink64.shizuku

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import org.victoriousframework.victorlink64.graphics.DeviceProfiler
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private class PrefsMemory(private val p: SharedPreferences) : TweakMemory {
    override fun get(key: String): String? = p.getString("mem.$key", null)
    override fun put(key: String, value: String?) { p.edit().apply { if (value == null) remove("mem.$key") else putString("mem.$key", value) }.apply() }
}

/**
 * Decides when Shizuku tweaks run. Everything is opt-in per tweak, does nothing unless Shizuku is installed, running
 * and granted, and never blocks a game from starting. Session tweaks are undone when the game ends, or the next time
 * the library is shown if the game's process went away without telling us.
 */
object ShizukuController {
    private const val TAG = "VL64Shizuku"
    private const val PREFS = "vl64_shizuku"

    private val activeGames = AtomicInteger(0)
    private val lock = Any()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context, t: Tweak) = prefs(ctx).getBoolean("on.${t.id}", false)

    /** Last result text for a tweak, or null if it has not run. */
    fun lastStatus(ctx: Context, t: Tweak): String? = prefs(ctx).getString("status.${t.id}", null)

    fun setEnabled(ctx: Context, t: Tweak, on: Boolean) {
        val app = ctx.applicationContext
        prefs(app).edit().putBoolean("on.${t.id}", on).apply()
        if (t.persistent) background { synchronized(lock) { if (on) applyOne(app, t) else revertOne(app, t) } }
        else if (!on) background { synchronized(lock) { if (prefs(app).getBoolean("applied.${t.id}", false)) revertOne(app, t) } }
    }

    /** [tracked] games report their end through [onGameEnd]; N64 runs in another process and is cleaned up by [revertIfIdle]. */
    fun onGameStart(ctx: Context, tracked: Boolean) {
        val app = ctx.applicationContext
        if (tracked) activeGames.incrementAndGet()
        if (Tweak.entries.none { isEnabled(app, it) }) return
        background {
            synchronized(lock) {
                Tweak.entries.filter { !it.persistent && isEnabled(app, it) }.forEach { applyOne(app, it) }
            }
        }
    }

    fun onGameEnd(ctx: Context) {
        val app = ctx.applicationContext
        if (activeGames.decrementAndGet() > 0) return
        activeGames.set(0)
        revertSession(app)
    }

    /** Called when the library comes to the front: nothing can be playing in this process. */
    fun revertIfIdle(ctx: Context) {
        if (activeGames.get() == 0) revertSession(ctx.applicationContext)
    }

    private fun revertSession(app: Context) {
        val p = prefs(app)
        if (Tweak.entries.none { !it.persistent && p.getBoolean("applied.${it.id}", false) }) return
        background { synchronized(lock) { Tweak.entries.filter { !it.persistent && p.getBoolean("applied.${it.id}", false) }.forEach { revertOne(app, it) } } }
    }

    private fun runner(app: Context): TweakRunner? {
        val shell = ShizukuBridge.shell(app) ?: return null
        return TweakRunner(shell, app.packageName, PrefsMemory(prefs(app)), Build.VERSION.SDK_INT, DeviceProfiler.profile(app).peakRefreshHz)
    }

    private fun applyOne(app: Context, t: Tweak) {
        val r = runner(app) ?: run { record(app, t, "Shizuku not ready"); return }
        val o = r.apply(t)
        prefs(app).edit().putBoolean("applied.${t.id}", o is Outcome.Applied).apply()
        record(app, t, describe(o, applied = true))
    }

    private fun revertOne(app: Context, t: Tweak) {
        val r = runner(app) ?: return
        val o = r.revert(t)
        prefs(app).edit().putBoolean("applied.${t.id}", false).apply()
        record(app, t, describe(o, applied = false))
    }

    private fun describe(o: Outcome, applied: Boolean) = when (o) {
        Outcome.Applied -> if (applied) "On" else "Restored"
        is Outcome.Skipped -> "Skipped: ${o.why}"
        is Outcome.Failed -> "Failed: ${o.why}"
    }

    private fun record(app: Context, t: Tweak, text: String) {
        Log.i(TAG, "${t.id}: $text")
        prefs(app).edit().putString("status.${t.id}", text).apply()
    }

    private fun background(block: () -> Unit) { thread(name = "shizuku-tweaks") { runCatching(block).onFailure { Log.w(TAG, "tweak", it) } } }
}
