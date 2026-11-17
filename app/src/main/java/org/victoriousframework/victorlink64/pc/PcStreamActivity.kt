package org.victoriousframework.victorlink64.pc

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.limelight.binding.PlatformBinding
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.binding.video.CrashListener
import com.limelight.binding.video.MediaCodecDecoderRenderer
import com.limelight.binding.video.MediaCodecHelper
import com.limelight.binding.video.PerfOverlayListener
import com.limelight.nvstream.NvConnection
import com.limelight.nvstream.NvConnectionListener
import com.limelight.nvstream.StreamConfiguration
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.input.MouseButtonPacket
import com.limelight.nvstream.jni.MoonBridge
import com.limelight.preferences.PreferenceConfiguration
import kotlin.math.abs

/*
 * Victorious Framework: full-screen PC stream. Video, audio and the network protocol are
 * Moonlight's (vendored :moonlight module, GPL-3.0); this screen, its controls and the
 * controller mapping are VictorLink64's own.
 */
class PcStreamActivity : Activity(), SurfaceHolder.Callback, NvConnectionListener {

    private lateinit var conn: NvConnection
    private lateinit var decoder: MediaCodecDecoderRenderer
    private lateinit var settings: StreamSettings
    private lateinit var host: PcHost
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private var pad: PcTouchPad? = null
    private var surfaceReady = false
    private var started = false
    private var connected = false
    private var finishing = false

    // Physical controller state (controller #0).
    private var hwButtons = 0
    private var hwLt = 0f; private var hwRt = 0f
    private var hwLx = 0f; private var hwLy = 0f; private var hwRx = 0f; private var hwRy = 0f
    private var hatBits = 0
    // On-screen controller state (merged into controller #0).
    private var touch = PcTouchPad.PadState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = PcRepo(this)
        val uuid = intent.getStringExtra(EXTRA_UUID)
        val h = uuid?.let { repo.host(it) }
        if (h == null || !h.paired) { finish(); return }
        host = h
        settings = repo.settings
        val appId = intent.getIntExtra(EXTRA_APP_ID, 0)
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "Desktop"

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // ---- Views -------------------------------------------------------------------------
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surface = SurfaceView(this)
        val aspect = AspectFrame(this, settings.res.width.toFloat() / settings.res.height).apply { addView(surface) }
        root.addView(aspect, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        surface.holder.addCallback(this)

        if (settings.touchControls) addPad(root)
        installMouseTouch(root)

        status = TextView(this).apply {
            setTextColor(0xFF8FA3C8.toInt()); textSize = 16f; gravity = Gravity.CENTER
            text = "Connecting to ${host.name}…\nStarting $appName"
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        stats = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 11f; setBackgroundColor(0x990A1128.toInt()); setPadding(12, 8, 12, 8)
            visibility = View.GONE
        }
        root.addView(stats, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        val menuBtn = TextView(this).apply {
            text = "•••"; setTextColor(0xFF3FD0FF.toInt()); textSize = 16f; setPadding(36, 6, 36, 10)
            setBackgroundColor(0xB30A1128.toInt())
            contentDescription = "Stream menu"
            setOnClickListener { showMenu(this) }
        }
        root.addView(menuBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        setContentView(root)
        hideSystemBars()

        // ---- Decoder + connection ---------------------------------------------------------
        val prefs = PreferenceConfiguration().apply {
            width = settings.res.width; height = settings.res.height; fps = settings.fps
            bitrate = settings.effectiveBitrateKbps
            videoFormat = when (settings.codec) {
                StreamCodec.AUTO -> PreferenceConfiguration.FormatOption.AUTO
                StreamCodec.H264 -> PreferenceConfiguration.FormatOption.FORCE_H264
                StreamCodec.HEVC -> PreferenceConfiguration.FormatOption.FORCE_HEVC
            }
            framePacing = when (settings.pacing) {
                FramePacing.LOW_LAG -> PreferenceConfiguration.FRAME_PACING_MIN_LATENCY
                FramePacing.BALANCED -> PreferenceConfiguration.FRAME_PACING_BALANCED
                FramePacing.SMOOTH -> PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS
            }
            enablePerfOverlay = settings.showStats
        }
        val glRenderer = glRenderer(this)
        MediaCodecHelper.initialize(this, glRenderer)
        decoder = MediaCodecDecoderRenderer(this, prefs,
            CrashListener { },
            0, false, false, glRenderer,
            PerfOverlayListener { t -> runOnUiThread { stats.text = t; stats.visibility = View.VISIBLE } })

        var formats = MoonBridge.VIDEO_FORMAT_H264
        if (settings.codec != StreamCodec.H264 && decoder.isHevcSupported) formats = formats or MoonBridge.VIDEO_FORMAT_H265

        val app = NvApp(appName, appId, false)
        val config = StreamConfiguration.Builder()
            .setResolution(prefs.width, prefs.height)
            .setLaunchRefreshRate(prefs.fps)
            .setRefreshRate(prefs.fps)
            .setApp(app)
            .setBitrate(prefs.bitrate)
            .setEnableSops(true)
            .enableLocalAudioPlayback(settings.playAudioOnPc)
            .setMaxPacketSize(1392)
            .setRemoteConfiguration(StreamConfiguration.STREAM_CFG_AUTO)
            .setSupportedVideoFormats(formats)
            .setAttachedGamepadMask(1)
            .setClientRefreshRateX100((displayRefreshRate() * 100).toInt())
            .setAudioConfiguration(MoonBridge.AUDIO_CONFIGURATION_STEREO)
            .setColorSpace(decoder.preferredColorSpace)
            .setColorRange(decoder.preferredColorRange)
            .setPersistGamepadsAfterDisconnect(true)
            .build()

        conn = NvConnection(applicationContext, ComputerDetails.AddressTuple(host.address, host.httpPort),
            host.httpsPort, repo.uniqueId, config, PlatformBinding.getCryptoProvider(this), PcRepo.certOf(host))
    }

    private fun addPad(root: FrameLayout) {
        val p = PcTouchPad(this) { s -> touch = s; sendPad() }
        val prefs = PadPrefs(this)
        p.alphaLevel = prefs.opacity; p.sizeScale = prefs.scale
        root.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pad = p
    }

    /** With the on-screen controller hidden, the screen works as a touchpad: drag to move, tap to click, two-finger tap to right-click. */
    private fun installMouseTouch(root: FrameLayout) {
        var lastX = 0f; var lastY = 0f; var moved = 0f; var downAt = 0L; var maxFingers = 0
        root.setOnTouchListener { _, e ->
            if (!started) return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; moved = 0f; downAt = e.eventTime; maxFingers = 1 }
                MotionEvent.ACTION_POINTER_DOWN -> maxFingers = maxOf(maxFingers, e.pointerCount)
                MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1) {
                    val dx = e.x - lastX; val dy = e.y - lastY
                    moved += abs(dx) + abs(dy)
                    if (dx != 0f || dy != 0f) conn.sendMouseMove(dx.toInt().toShort(), dy.toInt().toShort())
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_UP -> if (moved < 24f && e.eventTime - downAt < 300) {
                    val b = if (maxFingers >= 2) MouseButtonPacket.BUTTON_RIGHT else MouseButtonPacket.BUTTON_LEFT
                    conn.sendMouseButtonDown(b); conn.sendMouseButtonUp(b)
                }
            }
            true
        }
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, if (pad != null) "Hide on-screen controller" else "Show on-screen controller")
        if (pad != null) {
            val pp = PadPrefs(this)
            m.menu.add(0, 3, 1, "Controller size: ${PadPrefs.scaleLabel(pp.scale)}")
            m.menu.add(0, 4, 2, "Controller opacity: ${(pp.opacity * 100).toInt()}%")
        }
        m.menu.add(0, 2, 3, "Stop streaming")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> {
                    val root = anchor.parent as FrameLayout
                    pad?.let { p -> root.removeView(p); pad = null; touch = PcTouchPad.PadState(); sendPad() }
                        ?: run { addPad(root); anchor.bringToFront(); status.bringToFront(); stats.bringToFront() }
                }
                2 -> stopAndFinish()
                3 -> pad?.let { p -> val pp = PadPrefs(this); pp.scale = PadPrefs.next(PadPrefs.SCALES, pp.scale); p.sizeScale = pp.scale }
                4 -> pad?.let { p -> val pp = PadPrefs(this); pp.opacity = PadPrefs.next(PadPrefs.OPACITIES, pp.opacity); p.alphaLevel = pp.opacity }
            }
            true
        }
        m.show()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemBars() }

    private fun displayRefreshRate(): Float =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.refreshRate else null)
            ?: @Suppress("DEPRECATION") windowManager.defaultDisplay.refreshRate

    // ---- Surface ------------------------------------------------------------------------
    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { holder.surface.setFrameRate(settings.fps.toFloat(), android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!started && surfaceReady) {
            started = true
            decoder.setRenderTarget(holder)
            conn.start(AndroidAudioRenderer(this, false), decoder, this)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (started) {
            decoder.prepareForStop()
            stopConnection()
        }
    }

    private fun stopConnection() {
        if (connected || started) {
            connected = false
            val c = conn
            Thread { runCatching { c.stop() } }.start()
        }
    }

    private fun stopAndFinish() { finishing = true; stopConnection(); finish() }

    override fun onStop() {
        super.onStop()
        // Streaming in the background wastes the PC's and phone's resources; end it.
        if (started && !isChangingConfigurations) { stopConnection(); if (!finishing) { finishing = true; finish() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::decoder.isInitialized) runCatching { decoder.cleanup() }
    }

    @Deprecated("Uses the platform back callback on this plain Activity")
    override fun onBackPressed() {
        AlertDialog.Builder(this).setTitle("Stop streaming?")
            .setMessage("The game keeps running on your PC until you close it there or from the PC's game list.")
            .setPositiveButton("Stop") { _, _ -> stopAndFinish() }
            .setNegativeButton("Keep playing", null).show()
    }

    // ---- Physical controller passthrough -----------------------------------------------
    private fun isGamepad(dev: InputDevice?, source: Int): Boolean =
        (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (dev != null && (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (connected && isGamepad(e.device, e.source)) {
            val f = remapFace(keyFlag(e.keyCode), settings.swapAbxy)
            val trig = e.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || e.keyCode == KeyEvent.KEYCODE_BUTTON_R2
            if (f != 0 || trig) {
                val down = e.action == KeyEvent.ACTION_DOWN
                if (e.action == KeyEvent.ACTION_DOWN || e.action == KeyEvent.ACTION_UP) {
                    when {
                        e.keyCode == KeyEvent.KEYCODE_BUTTON_L2 -> hwLt = if (down) 1f else 0f
                        e.keyCode == KeyEvent.KEYCODE_BUTTON_R2 -> hwRt = if (down) 1f else 0f
                        down -> hwButtons = hwButtons or f
                        else -> hwButtons = hwButtons and f.inv()
                    }
                    sendPad()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (connected && isGamepad(e.device, e.source) && e.action == MotionEvent.ACTION_MOVE) {
            val (lx, ly) = deadZone(e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y))
            val (rx, ry) = deadZone(e.getAxisValue(MotionEvent.AXIS_Z), e.getAxisValue(MotionEvent.AXIS_RZ))
            hwLx = lx; hwLy = ly; hwRx = rx; hwRy = ry
            hwLt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
            hwRt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
            hatBits = hatFlags(e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y))
            sendPad()
            return true
        }
        return super.dispatchGenericMotionEvent(e)
    }

    /** Merges the physical controller and the on-screen one into Moonlight controller #0. */
    private fun sendPad() {
        if (!connected) return
        val t = touch
        val lx = if (abs(hwLx) > abs(t.lx)) hwLx else t.lx
        val ly = if (abs(hwLy) > abs(t.ly)) hwLy else t.ly
        val rx = if (abs(hwRx) > abs(t.rx)) hwRx else t.rx
        val ry = if (abs(hwRy) > abs(t.ry)) hwRy else t.ry
        conn.sendControllerInput(0, 1, hwButtons or hatBits or t.buttons,
            triggerToByte(maxOf(hwLt, t.lt)), triggerToByte(maxOf(hwRt, t.rt)),
            stickToShort(lx), stickToShort(ly, invert = true), stickToShort(rx), stickToShort(ry, invert = true))
    }

    // ---- NvConnectionListener -----------------------------------------------------------
    override fun stageStarting(stage: String) = runOnUiThread { status.text = "Connecting to ${host.name}…\n$stage" }
    override fun stageComplete(stage: String) {}
    override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) = runOnUiThread {
        showFatal("Couldn't connect to ${host.name} (step: $stage, code $errorCode).\n\n" +
            "Check that Sunshine is running, the PC's firewall allows Sunshine, and both devices are on the same Wi-Fi.")
    }
    override fun connectionStarted() = runOnUiThread {
        connected = true
        status.visibility = View.GONE
        sendPad()
    }
    override fun connectionTerminated(errorCode: Int) = runOnUiThread {
        connected = false
        if (finishing) return@runOnUiThread
        if (errorCode == MoonBridge.ML_ERROR_GRACEFUL_TERMINATION) { finishing = true; finish() }
        else showFatal(when (errorCode) {
            MoonBridge.ML_ERROR_NO_VIDEO_TRAFFIC -> "No picture arrived from your PC. Its firewall may be blocking Sunshine's UDP ports (47998–48000)."
            MoonBridge.ML_ERROR_NO_VIDEO_FRAME -> "Your Wi-Fi is too slow for these settings. Try a lower quality or 720p in Stream settings."
            MoonBridge.ML_ERROR_PROTECTED_CONTENT -> "The PC stopped the stream because something on screen is protected content."
            else -> "The stream ended unexpectedly (code $errorCode). Check your Wi-Fi and try again."
        })
    }
    override fun connectionStatusUpdate(connectionStatus: Int) = runOnUiThread {
        if (connectionStatus == MoonBridge.CONN_STATUS_POOR) { status.text = "Weak connection"; status.visibility = View.VISIBLE }
        else if (connected) status.visibility = View.GONE
    }
    override fun displayMessage(message: String) = runOnUiThread { showFatal(message) }
    override fun displayTransientMessage(message: String) = runOnUiThread {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }
    override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) {
        val strength = maxOf(lowFreqMotor.toInt() and 0xFFFF, highFreqMotor.toInt() and 0xFFFF)
        val v = vibrator() ?: return
        if (strength == 0) { v.cancel(); return }
        runCatching { v.vibrate(VibrationEffect.createOneShot(60_000, (strength shr 8).coerceIn(1, 255))) }
    }
    override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) {}
    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) { decoder.setHdrMode(enabled, hdrMetadata) }
    override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) {}
    override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) {}

    private fun vibrator(): Vibrator? {
        // Prefer the connected controller's motors; fall back to the phone.
        InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.firstOrNull { d ->
            d != null && (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        }?.let { d ->
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) d.vibratorManager.defaultVibrator else @Suppress("DEPRECATION") d.vibrator
            if (v.hasVibrator()) return v
        }
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        return if (v.hasVibrator()) v else null
    }

    private var fatalShown = false
    private fun showFatal(msg: String) {
        if (fatalShown || isFinishing) return
        fatalShown = true; finishing = true
        stopConnection()
        AlertDialog.Builder(this).setTitle("PC stream").setMessage(msg).setCancelable(false)
            .setPositiveButton("OK") { _, _ -> finish() }.show()
    }

    /** Keeps the video at the stream's aspect ratio, centred, with black bars. */
    private class AspectFrame(c: Context, private val ratio: Float) : FrameLayout(c) {
        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val w = MeasureSpec.getSize(wSpec); val h = MeasureSpec.getSize(hSpec)
            var cw = w; var ch = (w / ratio).toInt()
            if (ch > h) { ch = h; cw = (h * ratio).toInt() }
            val cs = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY); val hs = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) getChildAt(i).measure(cs, hs)
            setMeasuredDimension(w, h)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val v = getChildAt(i); val x = (r - l - v.measuredWidth) / 2; val y = (b - t - v.measuredHeight) / 2
                v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
            }
        }
    }

    companion object {
        const val EXTRA_UUID = "pc_uuid"
        const val EXTRA_APP_ID = "pc_app_id"
        const val EXTRA_APP_NAME = "pc_app_name"

        fun launch(c: Context, uuid: String, appId: Int, appName: String) = c.startActivity(
            Intent(c, PcStreamActivity::class.java).putExtra(EXTRA_UUID, uuid).putExtra(EXTRA_APP_ID, appId).putExtra(EXTRA_APP_NAME, appName)
        )

        /** Android gamepad key -> Moonlight (Xbox) button flag; 0 if not a mapped button. */
        fun keyFlag(code: Int): Int = when (code) {
            KeyEvent.KEYCODE_BUTTON_A -> PadFlags.A
            KeyEvent.KEYCODE_BUTTON_B -> PadFlags.B
            KeyEvent.KEYCODE_BUTTON_X -> PadFlags.X
            KeyEvent.KEYCODE_BUTTON_Y -> PadFlags.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> PadFlags.LB
            KeyEvent.KEYCODE_BUTTON_R1 -> PadFlags.RB
            KeyEvent.KEYCODE_BUTTON_THUMBL -> PadFlags.LS_CLICK
            KeyEvent.KEYCODE_BUTTON_THUMBR -> PadFlags.RS_CLICK
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> PadFlags.START
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> PadFlags.BACK
            KeyEvent.KEYCODE_BUTTON_MODE -> PadFlags.GUIDE
            KeyEvent.KEYCODE_DPAD_UP -> PadFlags.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> PadFlags.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> PadFlags.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> PadFlags.RIGHT
            else -> 0
        }

        /** Reads the GPU name once per OS build (Moonlight uses it for decoder quirks). */
        fun glRenderer(c: Context): String {
            val sp = c.getSharedPreferences("vl64_pc_streaming", Context.MODE_PRIVATE)
            if (sp.getString("gl_fp", null) == Build.FINGERPRINT) return sp.getString("gl_renderer", "") ?: ""
            val r = runCatching { queryGlRenderer() }.getOrNull() ?: ""
            sp.edit().putString("gl_fp", Build.FINGERPRINT).putString("gl_renderer", r).apply()
            return r
        }

        private fun queryGlRenderer(): String? {
            val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return null
            try {
                val attrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
                val cfgs = arrayOfNulls<android.opengl.EGLConfig>(1); val n = IntArray(1)
                if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0) || n[0] == 0) return null
                val ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                val surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
                val name = GLES20.glGetString(GLES20.GL_RENDERER)
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(dpy, surf); EGL14.eglDestroyContext(dpy, ctx)
                return name
            } finally { EGL14.eglTerminate(dpy) }
        }
    }
}
