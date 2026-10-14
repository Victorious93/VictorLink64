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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.victoriousframework.victorlink64.ui.NeonBlue
import org.victoriousframework.victorlink64.ui.NeonRed
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val uri = Uri.parse(intent.getStringExtra(EXTRA_ROM_URI) ?: run { finish(); return })
        val name = intent.getStringExtra(EXTRA_ROM_NAME) ?: "game.sfc"

        val surface = SurfaceView(this).apply { holder.addCallback(this@SnesActivity) }
        val overlay = ComposeView(this).apply { setContent { Gamepad({ touchMask = it; push() }, ::saveState, ::loadState) } }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = android.view.Gravity.CENTER
            })
            addView(overlay)
        })
        surface.post { // keep 8:7 SNES-ish 4:3 display aspect
            val w = surface.width; val h = surface.height
            val tw: Int; val th: Int
            if (w * 3 > h * 4) { th = h; tw = h * 4 / 3 } else { tw = w; th = w * 3 / 4 }
            surface.layoutParams = (surface.layoutParams as FrameLayout.LayoutParams).apply {
                width = tw; height = th; gravity = if (w > h) android.view.Gravity.CENTER else android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                topMargin = if (w > h) 0 else 48
            }
        }

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
            if (paused) { Thread.sleep(30); next = System.nanoTime(); continue }
            val n = SnesCore.nativeRunFrame(buf)
            if (n > 0) track.write(buf, 0, n) // blocking write paces to audio clock
            else { next += frameNs; val d = next - System.nanoTime(); if (d > 0) Thread.sleep(d / 1_000_000, (d % 1_000_000).toInt()) }
        }
        track.stop(); track.release()
        SnesCore.nativeUnload()
    }

    private fun push() = SnesCore.nativeSetButtons(touchMask or keyMask)

    private fun saveState() {
        paused = true
        thread { SnesCore.nativeSaveState()?.let { stateFile.writeBytes(it) }; paused = false
            runOnUiThread { Toast.makeText(this, "State saved", Toast.LENGTH_SHORT).show() } }
    }
    private fun loadState() {
        if (!stateFile.exists()) { Toast.makeText(this, "No saved state", Toast.LENGTH_SHORT).show(); return }
        paused = true
        thread { val ok = SnesCore.nativeLoadState(stateFile.readBytes()); paused = false
            runOnUiThread { Toast.makeText(this, if (ok) "State loaded" else "Load failed", Toast.LENGTH_SHORT).show() } }
    }

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

@Composable
private fun Gamepad(onMask: (Int) -> Unit, onSave: () -> Unit, onLoad: () -> Unit) {
    val pressed = remember { mutableStateMapOf<Int, Boolean>() }
    fun set(bit: Int, down: Boolean) {
        pressed[bit] = down
        onMask(pressed.filterValues { it }.keys.fold(0) { m, b -> m or (1 shl b) })
    }
    @Composable
    fun Btn(label: String, bit: Int, size: Dp, color: Color, shape: Shape = CircleShape, w: Dp = size) {
        val down = pressed[bit] == true
        Box(
            Modifier.size(w, size).clip(shape).background(color.copy(alpha = if (down) .7f else .28f))
                .border(2.dp, color, shape)
                .pointerInput(bit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false); set(bit, true)
                        do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                        set(bit, false)
                    }
                }, contentAlignment = Alignment.Center
        ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(12.dp)) {
        Row(Modifier.align(Alignment.TopCenter).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SAVE", color = NeonBlue, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x66000000))
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); onSave() } }.padding(8.dp, 4.dp), fontSize = 12.sp)
            Text("LOAD", color = NeonBlue, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x66000000))
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); onLoad() } }.padding(8.dp, 4.dp), fontSize = 12.sp)
        }
        Box(Modifier.align(Alignment.BottomStart).padding(bottom = 70.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Btn("▲", SnesCore.UP, 56.dp, NeonBlue, RoundedCornerShape(10.dp))
                Row { Btn("◀", SnesCore.LEFT, 56.dp, NeonBlue, RoundedCornerShape(10.dp)); Spacer(Modifier.width(56.dp)); Btn("▶", SnesCore.RIGHT, 56.dp, NeonBlue, RoundedCornerShape(10.dp)) }
                Btn("▼", SnesCore.DOWN, 56.dp, NeonBlue, RoundedCornerShape(10.dp))
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 70.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Btn("X", SnesCore.X, 58.dp, NeonBlue)
                Row { Btn("Y", SnesCore.Y, 58.dp, NeonBlue); Spacer(Modifier.width(50.dp)); Btn("A", SnesCore.A, 58.dp, NeonRed) }
                Btn("B", SnesCore.B, 58.dp, NeonRed)
            }
        }
        Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Btn("SELECT", SnesCore.SELECT, 34.dp, Color.Gray, RoundedCornerShape(17.dp), 84.dp)
            Btn("START", SnesCore.START, 34.dp, Color.Gray, RoundedCornerShape(17.dp), 84.dp)
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 290.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Btn("L", SnesCore.L, 40.dp, NeonBlue, RoundedCornerShape(10.dp), 90.dp)
            Btn("R", SnesCore.R, 40.dp, NeonBlue, RoundedCornerShape(10.dp), 90.dp)
        }
    }
}
