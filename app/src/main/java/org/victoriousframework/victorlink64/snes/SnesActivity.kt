package org.victoriousframework.victorlink64.snes

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalView
import org.victoriousframework.victorlink64.ui.*
import java.io.File
import kotlin.concurrent.thread

class SnesActivity : ComponentActivity(), SurfaceHolder.Callback {
    companion object { const val EXTRA_ROM_URI = "rom_uri"; const val EXTRA_ROM_NAME = "rom_name" }

    @Volatile private var running = false
    @Volatile private var paused = false
    private var loop: Thread? = null
    private var touchMask = 0
    private var keyMask = 0
    private lateinit var stateFile: File
    private lateinit var surface: SurfaceView
    private lateinit var frame: FrameLayout
    private var aspect by mutableStateOf(Aspect.NORMAL)
    private val coreTasks = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val uri = Uri.parse(intent.getStringExtra(EXTRA_ROM_URI) ?: run { finish(); return })
        val name = intent.getStringExtra(EXTRA_ROM_NAME) ?: "game.sfc"

        aspect = Aspect.fromName(getSharedPreferences("snes", MODE_PRIVATE).getString("aspect", null))
        surface = SurfaceView(this).apply { holder.addCallback(this@SnesActivity) }
        val overlay = ComposeView(this).apply {
            setContent { Gamepad(PadPrefs(this@SnesActivity), aspect.label, { touchMask = it; push() }, ::cycleAspect, ::saveState, ::loadState) }
        }
        frame = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = android.view.Gravity.CENTER
            })
            addView(overlay)
            // Fit on every layout pass, not once: the window changes shape on rotation, split-screen and folding.
            addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> fitVideo(r - l, b - t) }
        }
        setContentView(frame)

        val base = File(filesDir, "snes").apply { mkdirs() }
        val sys = File(base, "system").apply { mkdirs() }
        val saves = File(base, "saves").apply { mkdirs() }
        val rom = File(cacheDir, "snes_rom_" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        stateFile = File(saves, rom.nameWithoutExtension + ".state")

        loop = thread(name = "snes-loop") {
            try {
                contentResolver.openInputStream(uri)!!.use { i -> rom.outputStream().use { i.copyTo(it) } }
                val core = applicationInfo.nativeLibraryDir + "/libbsnes_libretro.so"
                if (!SnesCore.nativeLoad(core, rom.absolutePath, sys.absolutePath, saves.absolutePath)) {
                    runOnUiThread { Toast.makeText(this, "Could not load SNES game", Toast.LENGTH_LONG).show(); finish() }
                    return@thread
                }
                runLoop()
            } catch (e: Throwable) {
                Log.e("VL64Snes", "loop", e)
                runOnUiThread { Toast.makeText(this, "SNES error: ${e.message}", Toast.LENGTH_LONG).show(); finish() }
            }
        }
    }

    private fun runLoop() {
        val rate = SnesCore.nativeSampleRate().toInt().coerceIn(8000, 96000)
        val fps = SnesCore.nativeFps()
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minBuf, rate / 10 * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track.play()
        val buf = ShortArray(16384)
        val frameNs = (1e9 / fps).toLong()
        var next = System.nanoTime()
        running = true
        while (running) {
            // Core calls queued by the UI run here, between frames, never concurrently with nativeRunFrame.
            while (true) { val task = coreTasks.poll() ?: break; task() }
            if (paused) { Thread.sleep(30); next = System.nanoTime(); continue }
            val n = SnesCore.nativeRunFrame(buf)
            if (n > 0) track.write(buf, 0, n) // blocking write paces to audio clock
            else { next += frameNs; val d = next - System.nanoTime(); if (d > 0) Thread.sleep(d / 1_000_000, (d % 1_000_000).toInt()) }
        }
        track.stop(); track.release()
        SnesCore.nativeUnload()
    }

    private fun push() = SnesCore.nativeSetButtons(touchMask or keyMask)

    /** Sizes and places the picture inside a [frameW] x [frameH] window; a no-op when nothing changed. */
    private fun fitVideo(frameW: Int, frameH: Int) {
        val (w, h) = fitPicture(frameW, frameH, aspect)
        if (w <= 0 || h <= 0) return
        val landscape = frameW > frameH
        val gravity = if (landscape) android.view.Gravity.CENTER else android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
        val top = if (landscape) 0 else 48
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (lp.width == w && lp.height == h && lp.gravity == gravity && lp.topMargin == top) return
        lp.width = w; lp.height = h; lp.gravity = gravity; lp.topMargin = top
        surface.layoutParams = lp
    }

    private fun cycleAspect() {
        aspect = aspect.next()
        getSharedPreferences("snes", MODE_PRIVATE).edit().putString("aspect", aspect.name).apply()
        fitVideo(frame.width, frame.height)
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    // libretro forbids calling into the core while retro_run is executing. A host test of the real core crashed within
    // a few dozen save/load presses when they ran on another thread, so both go through the emulation thread.
    private fun saveState() {
        coreTasks.add {
            val data = SnesCore.nativeSaveState()
            toast(if (data != null && writeStateFile(data)) "State saved" else "Save failed")
        }
    }
    private fun loadState() {
        if (!stateFile.exists()) { Toast.makeText(this, "No saved state", Toast.LENGTH_SHORT).show(); return }
        coreTasks.add {
            val ok = try { SnesCore.nativeLoadState(stateFile.readBytes()) } catch (e: java.io.IOException) { Log.e("VL64Snes", "read state", e); false }
            toast(if (ok) "State loaded" else "Load failed")
        }
    }

    /** Writes via a temp file and rename so a kill mid-write cannot destroy the previous good state. */
    private fun writeStateFile(data: ByteArray): Boolean = try {
        val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
        tmp.writeBytes(data)
        tmp.renameTo(stateFile)
    } catch (e: java.io.IOException) { Log.e("VL64Snes", "write state", e); false }

    private fun keyBit(code: Int) = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> SnesCore.UP; KeyEvent.KEYCODE_DPAD_DOWN -> SnesCore.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> SnesCore.LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> SnesCore.RIGHT
        KeyEvent.KEYCODE_BUTTON_A -> SnesCore.B; KeyEvent.KEYCODE_BUTTON_B -> SnesCore.A
        KeyEvent.KEYCODE_BUTTON_X -> SnesCore.Y; KeyEvent.KEYCODE_BUTTON_Y -> SnesCore.X
        KeyEvent.KEYCODE_BUTTON_L1 -> SnesCore.L; KeyEvent.KEYCODE_BUTTON_R1 -> SnesCore.R
        KeyEvent.KEYCODE_BUTTON_START -> SnesCore.START; KeyEvent.KEYCODE_BUTTON_SELECT -> SnesCore.SELECT
        else -> -1
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val bit = keyBit(event.keyCode)
        if (bit < 0) return super.dispatchKeyEvent(event)
        keyMask = if (event.action == KeyEvent.ACTION_DOWN) keyMask or (1 shl bit) else keyMask and (1 shl bit).inv()
        push(); return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) = SnesCore.nativeSetSurface(holder.surface)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = SnesCore.nativeSetSurface(holder.surface)
    override fun surfaceDestroyed(holder: SurfaceHolder) = SnesCore.nativeSetSurface(null)

    override fun onPause() { super.onPause(); paused = true }
    override fun onResume() { super.onResume(); paused = false }
    override fun onDestroy() {
        running = false
        loop?.join(2000)
        super.onDestroy()
    }
}


/** Touch target registered by a visible control, in window coordinates. */
private class Zone(val rect: Rect, val round: Boolean, val slop: Float) {
    fun hit(p: Offset) =
        if (round) (p - rect.center).getDistance() <= rect.width / 2 + slop else rect.inflate(slop).contains(p)
}

/** Where each control currently is; written by layout callbacks, read by the touch handler (both on the main thread). */
private class PadLayout {
    var origin = Offset.Zero
    val zones = HashMap<Int, Zone>()
}

private const val DPAD = -1

private fun dpadBits(z: Zone?, p: Offset): Int {
    if (z == null) return 0
    val d = dpadDirection(p.x - z.rect.center.x, p.y - z.rect.center.y, z.rect.width)
    var m = 0
    if (d and DIR_UP != 0) m = m or (1 shl SnesCore.UP)
    if (d and DIR_DOWN != 0) m = m or (1 shl SnesCore.DOWN)
    if (d and DIR_LEFT != 0) m = m or (1 shl SnesCore.LEFT)
    if (d and DIR_RIGHT != 0) m = m or (1 shl SnesCore.RIGHT)
    return m
}

/**
 * On-screen controller. One touch handler watches every finger and derives the button mask from where each
 * finger is, so a thumb can roll between face buttons (A+B at the seam) and the D-pad reads 8 directions.
 * A finger that lands on the D-pad stays the D-pad finger until it lifts, so drifting off the pad doesn't drop it.
 */
@Composable
private fun Gamepad(prefs: PadPrefs, aspectLabel: String, onMask: (Int) -> Unit, onAspect: () -> Unit, onSave: () -> Unit, onLoad: () -> Unit) {
    var mask by remember { mutableIntStateOf(0) }
    val sendMask by rememberUpdatedState(onMask)
    val pad = remember { PadLayout() }
    val dp1 = with(LocalDensity.current) { 1.dp.toPx() }
    val view = LocalView.current
    var opacity by remember { mutableFloatStateOf(prefs.opacity) }
    var scale by remember { mutableFloatStateOf(prefs.scale) }
    val u = { d: Int -> (d * scale).dp }

    /** A visible control; [touch] = false for D-pad arms, which share one zone that reads 8 directions. */
    @Composable
    fun Btn(label: String, bit: Int, size: Dp, shape: Shape = CircleShape, w: Dp = size, slopDp: Int = 6, touch: Boolean = true,
            accent: Color = PadCyan, font: Int = 16, modifier: Modifier = Modifier) {
        PadFace(label, (mask shr bit) and 1 == 1, size,
            modifier.onGloballyPositioned {
                if (touch) pad.zones[bit] = Zone(Rect(it.positionInRoot(), it.size.toSize()), shape === CircleShape, slopDp * dp1)
            }, shape, w, accent, font)
    }

    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { pad.origin = it.positionInRoot() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var dpadId: PointerId? = null
                    while (true) {
                        val ev = awaitPointerEvent()
                        var m = 0
                        for (c in ev.changes) {
                            val p = pad.origin + c.position
                            if (dpadId == null && c.pressed && !c.previousPressed && pad.zones[DPAD]?.hit(p) == true) dpadId = c.id
                            if (!c.pressed) { if (c.id == dpadId) dpadId = null; continue }
                            if (c.id == dpadId) m = m or dpadBits(pad.zones[DPAD], p)
                            else for ((bit, z) in pad.zones) if (bit != DPAD && z.hit(p)) m = m or (1 shl bit)
                        }
                        if (m != mask) {
                            if (newlyPressed(mask, m) != 0) padHaptic(view)
                            mask = m; sendMask(m)
                        }
                    }
                }
            }
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(Modifier.align(Alignment.TopCenter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadChip("SAVE", onSave); PadChip("LOAD", onLoad); PadChip("SCREEN $aspectLabel", onAspect)
            PadChip("PAD ${PadPrefs.scaleLabel(scale)}") { scale = PadPrefs.next(PadPrefs.SCALES, scale); prefs.scale = scale }
            PadChip("${(opacity * 100).toInt()}%") { opacity = PadPrefs.next(PadPrefs.OPACITIES, opacity); prefs.opacity = opacity }
        }
        Box(Modifier.fillMaxSize().alpha(opacity)) {
            // Shoulders sit in the top corners where index fingers rest in landscape.
            Btn("L", SnesCore.L, u(44), RoundedCornerShape(topStart = 22.dp, topEnd = 8.dp, bottomEnd = 8.dp, bottomStart = 8.dp), u(104),
                slopDp = 12, modifier = Modifier.align(Alignment.TopStart))
            Btn("R", SnesCore.R, u(44), RoundedCornerShape(topStart = 8.dp, topEnd = 22.dp, bottomEnd = 8.dp, bottomStart = 8.dp), u(104),
                slopDp = 12, modifier = Modifier.align(Alignment.TopEnd))
            Box(
                Modifier.align(Alignment.BottomStart).padding(bottom = 24.dp)
                    .onGloballyPositioned { pad.zones[DPAD] = Zone(Rect(it.positionInRoot(), it.size.toSize()), false, 18 * dp1) }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val arm = RoundedCornerShape(12.dp)
                    Btn("▲", SnesCore.UP, u(54), arm, touch = false)
                    Row { Btn("◀", SnesCore.LEFT, u(54), arm, touch = false); Spacer(Modifier.size(u(54))); Btn("▶", SnesCore.RIGHT, u(54), arm, touch = false) }
                    Btn("▼", SnesCore.DOWN, u(54), arm, touch = false)
                }
            }
            // Console-accurate diamond: X top, Y left, A right, B bottom. 14 dp reach lets one thumb press two at a seam.
            Column(Modifier.align(Alignment.BottomEnd).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Btn("X", SnesCore.X, u(60), slopDp = 14)
                Row { Btn("Y", SnesCore.Y, u(60), slopDp = 14); Spacer(Modifier.size(u(48))); Btn("A", SnesCore.A, u(60), slopDp = 14) }
                Btn("B", SnesCore.B, u(60), slopDp = 14)
            }
            Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val pill = RoundedCornerShape(50)
                Btn("SELECT", SnesCore.SELECT, u(30), pill, u(82), accent = PadSteel, font = 11)
                Btn("START", SnesCore.START, u(30), pill, u(82), accent = PadSteel, font = 11)
            }
        }
    }
}
