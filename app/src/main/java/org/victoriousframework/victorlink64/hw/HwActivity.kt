package org.victoriousframework.victorlink64.hw

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.Os
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import org.victoriousframework.victorlink64.ui.NeonBlue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Plays PSP (PPSSPP core) and GameCube (Dolphin core) discs through libvl64hw. Victorious Framework.
 * Mirrors SnesActivity: one emulation thread owns the core and the GL context; the UI only queues work.
 * Action buttons use the brand blue, never red.
 */
class HwActivity : ComponentActivity(), SurfaceHolder.Callback {
    companion object {
        const val EXTRA_ROM_URI = "rom_uri"; const val EXTRA_ROM_NAME = "rom_name"; const val EXTRA_SYSTEM = "system"
        private const val TAG = "VL64Hw"
    }

    @Volatile private var running = false
    @Volatile private var paused = false
    private var loop: Thread? = null
    private var touchMask = 0
    private var keyMask = 0
    private var stick by mutableStateOf(false)
    private var aspect = 4f / 3f
    private lateinit var system: HwSystem
    private lateinit var stateFile: File
    private lateinit var surface: SurfaceView
    private lateinit var frame: FrameLayout
    private var romFd: ParcelFileDescriptor? = null
    private val surfaceReady = CountDownLatch(1)
    private val coreTasks = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val uri = Uri.parse(intent.getStringExtra(EXTRA_ROM_URI) ?: run { finish(); return })
        system = runCatching { HwSystem.valueOf(intent.getStringExtra(EXTRA_SYSTEM)!!) }.getOrElse { finish(); return }
        val name = intent.getStringExtra(EXTRA_ROM_NAME) ?: "game.iso"
        stick = system == HwSystem.GAMECUBE

        surface = SurfaceView(this).apply { holder.addCallback(this@HwActivity) }
        val overlay = ComposeView(this).apply {
            setContent { HwGamepad(system, stick, { touchMask = it; push() }, { stick = !stick; push() }, ::saveState, ::loadState) }
        }
        frame = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = android.view.Gravity.CENTER
            })
            addView(overlay)
            addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> fitVideo(r - l, b - t) }
        }
        setContentView(frame)

        val base = File(filesDir, system.dirName).apply { mkdirs() }
        val sys = File(base, "system").apply { mkdirs() }
        val saves = File(base, "saves").apply { mkdirs() }
        stateFile = File(saves, name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_") + ".state")
        val core = File(applicationInfo.nativeLibraryDir, system.coreLib)

        loop = thread(name = "hw-loop") {
            try {
                if (!core.exists()) { fail("${system.label} needs a 64-bit ARM (arm64-v8a) device"); return@thread }
                val romPath = openRom(uri, name)
                if (!surfaceReady.await(10, TimeUnit.SECONDS)) { fail("No display surface"); return@thread }
                if (!HwCore.nativeLoad(core.absolutePath, romPath, sys.absolutePath, saves.absolutePath)) {
                    fail("Could not load ${system.label} game"); return@thread
                }
                aspect = HwCore.nativeAspect().takeIf { it > 0.1f } ?: (4f / 3f)
                runOnUiThread { fitVideo(frame.width, frame.height) }
                runLoop()
            } catch (e: Throwable) {
                Log.e(TAG, "loop", e)
                fail("${system.label} error: ${e.message}")
            }
        }
    }

    /**
     * Disc images are often several GB, so instead of copying (as SNES does) the document is opened once and the
     * core reads it through a cache symlink to /proc/self/fd/N; the symlink keeps the extension cores look at.
     */
    private fun openRom(uri: Uri, name: String): String {
        if (uri.scheme == "file") return uri.path!!
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw java.io.FileNotFoundException(name)
        romFd = pfd
        val dir = File(cacheDir, "hw").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val link = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        Os.symlink("/proc/self/fd/${pfd.fd}", link.absolutePath)
        return link.absolutePath
    }

    private fun fail(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); finish() }

    private fun runLoop() {
        val rate = HwCore.nativeSampleRate().toInt().coerceIn(8000, 96000)
        val fps = HwCore.nativeFps().takeIf { it > 1 } ?: 60.0
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minBuf, rate / 10 * 4)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track.play()
        val buf = ShortArray(32768)
        val frameNs = (1e9 / fps).toLong()
        var next = System.nanoTime()
        running = true
        while (running) {
            while (true) { val task = coreTasks.poll() ?: break; task() }
            if (paused) { Thread.sleep(30); next = System.nanoTime(); continue }
            val n = HwCore.nativeRunFrame(buf)
            if (n > 0) track.write(buf, 0, n)
            else { next += frameNs; val d = next - System.nanoTime(); if (d > 0) Thread.sleep(d / 1_000_000, (d % 1_000_000).toInt()) }
        }
        track.stop(); track.release()
        HwCore.nativeUnload()
    }

    /** With STICK on, the D-pad drives the left analog stick (GameCube and most 3D PSP games need it). */
    private fun push() {
        val m = touchMask or keyMask
        if (!stick) { HwCore.nativeSetButtons(m); HwCore.nativeSetAnalog(0, 0); return }
        val dirs = (1 shl HwCore.UP) or (1 shl HwCore.DOWN) or (1 shl HwCore.LEFT) or (1 shl HwCore.RIGHT)
        HwCore.nativeSetButtons(m and dirs.inv())
        HwCore.nativeSetAnalog(dpadToAxis(m, HwCore.LEFT, HwCore.RIGHT), dpadToAxis(m, HwCore.UP, HwCore.DOWN))
    }

    private fun fitVideo(frameW: Int, frameH: Int) {
        if (frameW <= 0 || frameH <= 0) return
        val (w, h) = fitAspect(frameW, frameH, aspect)
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (lp.width == w && lp.height == h) return
        lp.width = w; lp.height = h; surface.layoutParams = lp
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun saveState() {
        coreTasks.add {
            val data = HwCore.nativeSaveState()
            val ok = data != null && try {
                val tmp = File(stateFile.parentFile, stateFile.name + ".tmp"); tmp.writeBytes(data); tmp.renameTo(stateFile)
            } catch (e: java.io.IOException) { Log.e(TAG, "write state", e); false }
            toast(if (ok) "State saved" else "Save failed")
        }
    }
    private fun loadState() {
        if (!stateFile.exists()) { Toast.makeText(this, "No saved state", Toast.LENGTH_SHORT).show(); return }
        coreTasks.add {
            val ok = try { HwCore.nativeLoadState(stateFile.readBytes()) } catch (e: java.io.IOException) { false }
            toast(if (ok) "State loaded" else "Load failed")
        }
    }

    private fun keyBit(code: Int) = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> HwCore.UP; KeyEvent.KEYCODE_DPAD_DOWN -> HwCore.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> HwCore.LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> HwCore.RIGHT
        KeyEvent.KEYCODE_BUTTON_A -> HwCore.B; KeyEvent.KEYCODE_BUTTON_B -> HwCore.A
        KeyEvent.KEYCODE_BUTTON_X -> HwCore.Y; KeyEvent.KEYCODE_BUTTON_Y -> HwCore.X
        KeyEvent.KEYCODE_BUTTON_L1 -> HwCore.L; KeyEvent.KEYCODE_BUTTON_R1 -> HwCore.R
        KeyEvent.KEYCODE_BUTTON_L2 -> HwCore.L2; KeyEvent.KEYCODE_BUTTON_R2 -> HwCore.R2
        KeyEvent.KEYCODE_BUTTON_START -> HwCore.START; KeyEvent.KEYCODE_BUTTON_SELECT -> HwCore.SELECT
        else -> -1
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val bit = keyBit(event.keyCode)
        if (bit < 0) return super.dispatchKeyEvent(event)
        keyMask = if (event.action == KeyEvent.ACTION_DOWN) keyMask or (1 shl bit) else keyMask and (1 shl bit).inv()
        push(); return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) { HwCore.nativeSetSurface(holder.surface); surfaceReady.countDown() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = HwCore.nativeSetSurface(holder.surface)
    override fun surfaceDestroyed(holder: SurfaceHolder) = HwCore.nativeSetSurface(null)

    override fun onPause() { super.onPause(); paused = true }
    override fun onResume() { super.onResume(); paused = false }
    override fun onDestroy() {
        running = false
        loop?.join(3000)
        romFd?.close()
        super.onDestroy()
    }
}

/** Largest [aspect]-shaped box inside [frameW] x [frameH]. */
internal fun fitAspect(frameW: Int, frameH: Int, aspect: Float): Pair<Int, Int> =
    if (frameW.toFloat() / frameH > aspect) Pair((frameH * aspect).toInt(), frameH) else Pair(frameW, (frameW / aspect).toInt())

/** Full-scale libretro analog value (-0x7FFF..0x7FFF) from two opposite D-pad bits in [mask]. */
internal fun dpadToAxis(mask: Int, negBit: Int, posBit: Int): Int =
    (if (mask and (1 shl posBit) != 0) 0x7FFF else 0) - (if (mask and (1 shl negBit) != 0) 0x7FFF else 0)

private class HwZone(val rect: Rect, val slop: Float) {
    fun hit(p: Offset) = rect.inflate(slop).contains(p)
}

/** On-screen controller; labels follow the console (PSP: ✕ ○ □ △, GameCube: A B X Y Z). Brand blue only. */
@Composable
private fun HwGamepad(system: HwSystem, stick: Boolean, onMask: (Int) -> Unit, onStick: () -> Unit, onSave: () -> Unit, onLoad: () -> Unit) {
    var mask by remember { mutableIntStateOf(0) }
    val sendMask by rememberUpdatedState(onMask)
    val zones = remember { HashMap<Int, HwZone>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val dp1 = with(LocalDensity.current) { 1.dp.toPx() }
    val psp = system == HwSystem.PSP

    @Composable
    fun Btn(label: String, bit: Int, size: Dp, shape: Shape = CircleShape, w: Dp = size, color: Color = NeonBlue) {
        val down = (mask shr bit) and 1 == 1
        Box(
            Modifier.size(w, size).clip(shape).background(color.copy(alpha = if (down) .7f else .28f)).border(2.dp, color, shape)
                .onGloballyPositioned { zones[bit] = HwZone(Rect(it.positionInRoot(), it.size.toSize()), 10 * dp1) },
            contentAlignment = Alignment.Center
        ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
    }

    @Composable
    fun Chip(label: String, onTap: () -> Unit) =
        Text(label, color = NeonBlue, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x66000000))
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); onTap() } }.padding(8.dp, 4.dp), fontSize = 12.sp)

    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        var m = 0
                        for (c in ev.changes) if (c.pressed) {
                            val p = origin + c.position
                            for ((bit, z) in zones) if (z.hit(p)) m = m or (1 shl bit)
                        }
                        if (m != mask) { mask = m; sendMask(m) }
                    }
                }
            }
            .navigationBarsPadding().padding(12.dp)
    ) {
        Row(Modifier.align(Alignment.TopCenter).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("SAVE", onSave); Chip("LOAD", onLoad); Chip(if (stick) "STICK ON" else "STICK OFF", onStick)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(bottom = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val sq = RoundedCornerShape(10.dp)
            Btn("▲", HwCore.UP, 54.dp, sq)
            Row { Btn("◀", HwCore.LEFT, 54.dp, sq); Spacer(Modifier.width(54.dp)); Btn("▶", HwCore.RIGHT, 54.dp, sq) }
            Btn("▼", HwCore.DOWN, 54.dp, sq)
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(bottom = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Btn(if (psp) "△" else "Y", HwCore.X, 56.dp)
            Row { Btn(if (psp) "□" else "X", HwCore.Y, 56.dp); Spacer(Modifier.width(48.dp)); Btn(if (psp) "○" else "B", HwCore.A, 56.dp) }
            Btn(if (psp) "✕" else "A", HwCore.B, 56.dp)
        }
        Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (psp) Btn("SELECT", HwCore.SELECT, 34.dp, RoundedCornerShape(17.dp), 84.dp, Color.Gray)
            else Btn("Z", HwCore.R2, 34.dp, RoundedCornerShape(17.dp), 64.dp)
            Btn("START", HwCore.START, 34.dp, RoundedCornerShape(17.dp), 84.dp, Color.Gray)
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 280.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Btn("L", HwCore.L, 40.dp, RoundedCornerShape(10.dp), 90.dp)
            Btn("R", HwCore.R, 40.dp, RoundedCornerShape(10.dp), 90.dp)
        }
    }
}
