package org.victoriousframework.victorlink64.graphics

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.PowerManager
import android.view.Display

enum class DeviceClass(val label: String) { LOW("Entry-level"), MID("Mid-range"), HIGH("High-end") }

/** What we know about the phone, gathered once. */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val gpu: String,
    val ramMb: Int,
    val refreshHz: Float,
    val peakRefreshHz: Float,
    val glEsVersion: Int,
) {
    val deviceClass: DeviceClass get() = classify(soc, gpu, ramMb)
    val supportsGles31: Boolean get() = glEsVersion >= 0x30001

    companion object {
        private fun rankOf(c: DeviceClass) = c.ordinal

        /**
         * Classifies by GPU first (the main limit for these emulators), falls back to the SoC name,
         * then caps by RAM. Unknown hardware is treated conservatively.
         */
        fun classify(soc: String, gpu: String, ramMb: Int): DeviceClass {
            val byHw = gpuClass(gpu) ?: socClass(soc) ?: if (ramMb <= 4608) DeviceClass.LOW else DeviceClass.MID
            val ramCap = when {
                ramMb < 3200 -> DeviceClass.LOW
                ramMb < 5600 -> DeviceClass.MID
                else -> DeviceClass.HIGH
            }
            return if (rankOf(byHw) <= rankOf(ramCap)) byHw else ramCap
        }

        fun gpuClass(gpu: String): DeviceClass? {
            val g = gpu.lowercase()
            Regex("""mali-?g(\d+)""").find(g)?.let { m ->
                return when (m.groupValues[1].toInt()) {
                    31, 51, 52, 57, 71, 72 -> DeviceClass.LOW
                    68, 76, 77, 78, 310, 510, 610, 615 -> DeviceClass.MID
                    else -> if (m.groupValues[1].toInt() >= 710) DeviceClass.HIGH else DeviceClass.MID
                }
            }
            Regex("""adreno.*?(\d{3})""").find(g)?.let { m ->
                val n = m.groupValues[1].toInt()
                return when {
                    n < 630 -> DeviceClass.LOW
                    n < 730 -> DeviceClass.MID
                    else -> DeviceClass.HIGH
                }
            }
            if (g.contains("powervr") || g.contains("videocore") || g.contains("vivante")) return DeviceClass.LOW
            if (g.contains("xclipse")) return DeviceClass.HIGH
            return null
        }

        fun socClass(soc: String): DeviceClass? {
            val s = soc.lowercase()
            Regex("""dimensity\s*(\d)(\d{3})""").find(s)?.let { m ->
                return when (m.groupValues[1].toInt()) { 6 -> DeviceClass.LOW; 7 -> DeviceClass.MID; 8 -> DeviceClass.MID; else -> DeviceClass.HIGH }
            }
            Regex("""sm?(\d{4})""").find(s)?.let { m ->
                val n = m.groupValues[1].toInt()
                return when { n >= 8450 -> DeviceClass.HIGH; n >= 7000 -> DeviceClass.MID; else -> DeviceClass.LOW }
            }
            if (s.contains("tensor")) return DeviceClass.MID
            if (s.contains("exynos")) return DeviceClass.MID
            return null
        }
    }
}

/** Reads the live phone. Android only; everything decision-making lives in the pure classes above. */
object DeviceProfiler {
    @Volatile private var cached: DeviceProfile? = null

    fun profile(ctx: Context): DeviceProfile {
        cached?.let { return it }
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val (hz, peak) = displayRates(app)
        val soc = if (Build.VERSION.SDK_INT >= 31) listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).joinToString(" ") else Build.HARDWARE
        val p = DeviceProfile(
            manufacturer = Build.MANUFACTURER, model = Build.MODEL, soc = soc.trim(),
            gpu = queryGpuRenderer(), ramMb = (mi.totalMem / (1024 * 1024)).toInt(),
            refreshHz = hz, peakRefreshHz = peak,
            glEsVersion = am.deviceConfigurationInfo.reqGlEsVersion,
        )
        cached = p
        return p
    }

    private fun displayRates(ctx: Context): Pair<Float, Float> = runCatching {
        val d: Display = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val cur = d.refreshRate
        val peak = d.supportedModes.maxOfOrNull { it.refreshRate } ?: cur
        cur to maxOf(cur, peak)
    }.getOrDefault(60f to 60f)

    /** Thermal status 0 (none) .. 6 (shutdown); 0 when the platform cannot say. */
    fun thermalStatus(ctx: Context): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.currentThermalStatus.coerceIn(0, 6)
    }

    /**
     * GL_RENDERER from a throw-away 1x1 pbuffer context; "" if EGL is unavailable. The display is left initialised on
     * purpose: other EGL users in this process may already hold it.
     */
    private fun queryGpuRenderer(): String = runCatching {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        if (!EGL14.eglInitialize(dpy, v, 0, v, 1)) return@runCatching ""
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0) || n[0] < 1) return@runCatching ""
        val ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
        val r = GLES20.glGetString(GLES20.GL_RENDERER) ?: ""
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(dpy, surf); EGL14.eglDestroyContext(dpy, ctx)
        r
    }.getOrDefault("")
}
