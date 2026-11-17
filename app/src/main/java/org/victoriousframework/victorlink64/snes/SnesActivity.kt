package org.victoriousframework.victorlink64.snes

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.victoriousframework.victorlink64.controls.Console
import org.victoriousframework.victorlink64.controls.PadSession
import org.victoriousframework.victorlink64.controls.PadVisibilityChip
import org.victoriousframework.victorlink64.controls.addChipBar
import org.victoriousframework.victorlink64.controls.chipBar
import org.victoriousframework.victorlink64.ui.PadChip
import java.io.File
import kotlin.concurrent.thread

/**
 * Plays SNES games through the bsnes libretro core. Input comes from the shared overlay ([PadSession]): the
 * console-accurate diamond, slide D-pad, multi-touch, haptics, layout editing and hardware pads with remapping.
 */
class SnesActivity : ComponentActivity(), SurfaceHolder.Callback {
    companion object { const val EXTRA_ROM_URI = "rom_uri"; const val EXTRA_ROM_NAME = "rom_name" }

    @Volatile private var running = false
    @Volatile private var paused = false
    private var loop: Thread? = null
    private lateinit var pad: PadSession
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
        pad = PadSession(this, Console.SNES, stickAsDpad = true) { SnesCore.nativeSetButtons(it.mask) }
        val chips = chipBar {
            if (!pad.editing) {
                PadChip("SAVE") { saveState() }
                PadChip("LOAD") { loadState() }
                PadChip("SCREEN ${aspect.label}") { cycleAspect() }
                PadChip("EDIT") { pad.startEditing() }
                PadVisibilityChip(pad)
            }
        }
        frame = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = android.view.Gravity.CENTER
            })
            addView(pad.host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addChipBar(chips)
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
            if (paused || pad.editing) { Thread.sleep(30); next = System.nanoTime(); continue }
            val n = SnesCore.nativeRunFrame(buf)
            if (n > 0) track.write(buf, 0, n) // blocking write paces to audio clock
            else { next += frameNs; val d = next - System.nanoTime(); if (d > 0) Thread.sleep(d / 1_000_000, (d % 1_000_000).toInt()) }
        }
        track.stop(); track.release()
        SnesCore.nativeUnload()
    }

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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        pad.handleKey(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        pad.handleMotion(event) || super.dispatchGenericMotionEvent(event)

    override fun surfaceCreated(holder: SurfaceHolder) = SnesCore.nativeSetSurface(holder.surface)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = SnesCore.nativeSetSurface(holder.surface)
    override fun surfaceDestroyed(holder: SurfaceHolder) = SnesCore.nativeSetSurface(null)

    override fun onPause() { super.onPause(); paused = true; pad.onPause() }
    override fun onResume() { super.onResume(); paused = false; pad.onResume() }
    override fun onDestroy() {
        running = false
        loop?.join(2000)
        super.onDestroy()
    }
}
