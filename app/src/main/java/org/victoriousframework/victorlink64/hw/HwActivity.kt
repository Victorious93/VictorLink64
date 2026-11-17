package org.victoriousframework.victorlink64.hw

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.system.Os
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
import org.victoriousframework.victorlink64.controls.PadConvert
import org.victoriousframework.victorlink64.controls.PadSession
import org.victoriousframework.victorlink64.controls.PadState
import org.victoriousframework.victorlink64.controls.PadVisibilityChip
import org.victoriousframework.victorlink64.controls.addChipBar
import org.victoriousframework.victorlink64.controls.chipBar
import org.victoriousframework.victorlink64.ui.PadChip
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Plays PSP (PPSSPP core), GameCube (Dolphin core) and PlayStation 2 (LRPS2 core) discs through libvl64hw.
 * Victorious Framework.
 * Mirrors SnesActivity: one emulation thread owns the core and the GL context; the UI only queues work.
 * Input comes from the shared overlay ([PadSession]): true analog sticks, analog GameCube triggers, hardware pads.
 */
class HwActivity : ComponentActivity(), SurfaceHolder.Callback {
    companion object {
        const val EXTRA_ROM_URI = "rom_uri"; const val EXTRA_ROM_NAME = "rom_name"; const val EXTRA_SYSTEM = "system"
        /** Track files of a .cue sheet (parallel arrays), opened beside the cue copy so the core finds them. */
        const val EXTRA_COMPANION_NAMES = "companion_names"; const val EXTRA_COMPANION_URIS = "companion_uris"
        private const val TAG = "VL64Hw"
    }

    @Volatile private var running = false
    @Volatile private var paused = false
    private var loop: Thread? = null
    private var aspect = 4f / 3f
    private lateinit var system: HwSystem
    private lateinit var stateFile: File
    private lateinit var surface: SurfaceView
    private lateinit var frame: FrameLayout
    private lateinit var pad: PadSession
    private var romFd: ParcelFileDescriptor? = null
    private val extraFds = mutableListOf<ParcelFileDescriptor>()
    private val surfaceReady = CountDownLatch(1)
    private val coreTasks = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val uri = Uri.parse(intent.getStringExtra(EXTRA_ROM_URI) ?: run { finish(); return })
        system = runCatching { HwSystem.valueOf(intent.getStringExtra(EXTRA_SYSTEM)!!) }.getOrElse { finish(); return }
        val name = intent.getStringExtra(EXTRA_ROM_NAME) ?: "game.iso"

        surface = SurfaceView(this).apply { holder.addCallback(this@HwActivity) }
        pad = PadSession(this, system.console, stickAsDpad = false, sink = ::sendPad)
        val chips = chipBar {
            if (!pad.editing) {
                // PS2 save states are tens of MB and unverified here; PS2 games save to their memory card instead.
                if (system != HwSystem.PS2) {
                    PadChip("SAVE") { saveState() }
                    PadChip("LOAD") { loadState() }
                }
                PadChip("EDIT") { pad.startEditing() }
                PadVisibilityChip(pad)
            }
        }
        frame = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = android.view.Gravity.CENTER
            })
            addView(pad.host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addChipBar(chips)
            addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> fitVideo(r - l, b - t) }
        }
        setContentView(frame)

        val base = File(filesDir, system.dirName).apply { mkdirs() }
        val sys = File(base, "system").apply { mkdirs() }
        val saves = File(base, "saves").apply { mkdirs() }
        stateFile = File(saves, name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_") + ".state")
        val core = File(applicationInfo.nativeLibraryDir, system.coreLib)

        var coreOptions: Map<String, String> = emptyMap()
        if (system == HwSystem.PS2) {
            val bios = Ps2Bios.choose(Ps2Bios.list(Ps2Bios.biosDir(filesDir)), Ps2Prefs(this).bios)
            if (bios == null) {
                startActivity(Intent(this, Ps2SetupActivity::class.java).putExtra(Ps2SetupActivity.EXTRA_NEEDS_BIOS, true))
                fail("PlayStation 2 needs a BIOS first. Add one in PlayStation 2 setup.")
                return
            }
            coreOptions = Ps2Options.build(Ps2Prefs(this).resolution, bios.file.name)
        }

        loop = thread(name = "hw-loop") {
            try {
                if (!core.exists()) { fail("${system.label} needs a 64-bit ARM (arm64-v8a) device"); return@thread }
                val romPath = openRom(uri, name)
                if (!surfaceReady.await(10, TimeUnit.SECONDS)) { fail("No display surface"); return@thread }
                HwCore.nativeClearOptions()
                for ((k, v) in coreOptions) HwCore.nativeSetOption(k, v)
                if (!HwCore.nativeLoad(core.absolutePath, romPath, sys.absolutePath, saves.absolutePath)) {
                    fail(if (system == HwSystem.PS2) "Could not start this PlayStation 2 game. Check that your BIOS is a valid PS2 BIOS and the disc image is complete."
                         else "Could not load ${system.label} game"); return@thread
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
        val dir = File(cacheDir, "hw").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val trackNames = intent.getStringArrayExtra(EXTRA_COMPANION_NAMES)
        val trackUris = intent.getStringArrayExtra(EXTRA_COMPANION_URIS)
        if (name.endsWith(".cue", ignoreCase = true) && trackNames != null && trackUris != null && trackNames.size == trackUris.size) {
            // A cue sheet names its track files; give the core a copy of the sheet with the tracks linked beside it.
            val sheet = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw java.io.FileNotFoundException(name)
            val text = String(sheet, Charsets.ISO_8859_1)
            val cueFile = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            cueFile.writeText(Ps2Disc.rewriteCue(text), Charsets.ISO_8859_1)
            for (wanted in Ps2Disc.cueFiles(text).distinct()) {
                val i = trackNames.indexOfFirst { it.equals(wanted, ignoreCase = true) }
                if (i < 0) throw java.io.FileNotFoundException(wanted)
                val pfd = contentResolver.openFileDescriptor(Uri.parse(trackUris[i]), "r") ?: throw java.io.FileNotFoundException(wanted)
                extraFds += pfd
                Os.symlink("/proc/self/fd/${pfd.fd}", File(dir, wanted).absolutePath)
            }
            return cueFile.absolutePath
        }
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: throw java.io.FileNotFoundException(name)
        romFd = pfd
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
            if (paused || pad.editing) { Thread.sleep(30); next = System.nanoTime(); continue }
            val n = HwCore.nativeRunFrame(buf)
            if (n > 0) track.write(buf, 0, n)
            else { next += frameNs; val d = next - System.nanoTime(); if (d > 0) Thread.sleep(d / 1_000_000, (d % 1_000_000).toInt()) }
        }
        track.stop(); track.release()
        HwCore.nativeUnload()
    }

    /** Delivers the merged touch + hardware state to the core in libretro units. */
    private fun sendPad(s: PadState) {
        val r = PadConvert.toRetro(s)
        HwCore.nativeSetPad(r.buttons, r.lx, r.ly, r.rx, r.ry, r.lTrigger, r.rTrigger)
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        pad.handleKey(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        pad.handleMotion(event) || super.dispatchGenericMotionEvent(event)

    override fun surfaceCreated(holder: SurfaceHolder) { HwCore.nativeSetSurface(holder.surface); surfaceReady.countDown() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = HwCore.nativeSetSurface(holder.surface)
    override fun surfaceDestroyed(holder: SurfaceHolder) = HwCore.nativeSetSurface(null)

    override fun onPause() { super.onPause(); paused = true; if (::pad.isInitialized) pad.onPause() }
    override fun onResume() { super.onResume(); paused = false; if (::pad.isInitialized) pad.onResume() }
    override fun onDestroy() {
        running = false
        loop?.join(3000)
        romFd?.close()
        extraFds.forEach { it.close() }
        super.onDestroy()
    }
}

/** Largest [aspect]-shaped box inside [frameW] x [frameH]. */
internal fun fitAspect(frameW: Int, frameH: Int, aspect: Float): Pair<Int, Int> =
    if (frameW.toFloat() / frameH > aspect) Pair((frameH * aspect).toInt(), frameH) else Pair(frameW, (frameW / aspect).toInt())
