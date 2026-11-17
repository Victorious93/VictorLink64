package org.victoriousframework.victorlink64.hw

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.FileInputStream
import java.nio.ByteBuffer

/** A library disc entry: the console it belongs to and, for .cue sheets, the track files that travel with it. */
data class DiscHit(val system: HwSystem, val companions: List<Pair<String, Uri>> = emptyList())

/** Random-access read of a document through its file descriptor. */
class DescriptorDisc(private val input: FileInputStream) : DiscReader {
    private val channel = input.channel
    override val size: Long get() = channel.size()
    override fun read(offset: Long, length: Int): ByteArray? = try {
        val buf = ByteBuffer.allocate(length)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n <= 0) break
            pos += n
        }
        if (buf.position() == 0) null else buf.array().copyOf(buf.position())
    } catch (e: java.io.IOException) { null }
}

/**
 * Decides which console a disc file in the library belongs to. PSP and GameCube keep their existing rules; PlayStation 2
 * is recognised by reading the image (SYSTEM.CNF / CHD header / .cue sheet), because the file name alone can't say.
 * Victorious Framework. See docs/PS2.md.
 */
object DiscDetect {
    /** [siblings] are the other files of the same folder, used to find the tracks of a .cue sheet. */
    fun detect(c: Context, f: DocumentFile, ext: String, siblings: List<DocumentFile>): DiscHit? = when (ext) {
        "iso" -> withDisc(c, f.uri) { r ->
            val head = r.read(0, 64)
            when {
                head != null && HwSystem.isGameCubeHeader(head) -> DiscHit(HwSystem.GAMECUBE)
                Ps2Disc.isPs2Disc(r) -> DiscHit(HwSystem.PS2)
                else -> DiscHit(HwSystem.PSP)
            }
        } ?: DiscHit(HwSystem.PSP)
        "cso" -> withDisc(c, f.uri) { r ->
            val iso = Ps2Disc.CsoReader.open(r)
            if (iso != null && Ps2Disc.isPs2Disc(iso)) DiscHit(HwSystem.PS2) else DiscHit(HwSystem.PSP)
        } ?: DiscHit(HwSystem.PSP)
        "chd" -> withDisc(c, f.uri) { r -> Ps2Disc.chdInfo(r)?.takeIf(Ps2Disc::chdLooksPs2)?.let { DiscHit(HwSystem.PS2) } }
        "cue" -> detectCue(c, f, siblings)
        "bin" -> detectBareBin(c, f, siblings)
        else -> HwSystem.detect(ext, null)?.let { DiscHit(it) }
    }

    private fun detectCue(c: Context, cue: DocumentFile, siblings: List<DocumentFile>): DiscHit? {
        val text = readSmallText(c, cue.uri) ?: return null
        val names = Ps2Disc.cueFiles(text)
        if (names.isEmpty()) return null
        val tracks = names.map { n -> siblings.firstOrNull { it.name.equals(n, ignoreCase = true) } ?: return null }
        val isPs2 = withDisc(c, tracks.first().uri) { Ps2Disc.isPs2Disc(it) } ?: false
        if (!isPs2) return null
        return DiscHit(HwSystem.PS2, tracks.map { (it.name ?: "track.bin") to it.uri })
    }

    /** A .bin with no .cue beside it (and no cue naming it) that is a PS2 disc. BIOS .bin files fail the ISO check. */
    private fun detectBareBin(c: Context, bin: DocumentFile, siblings: List<DocumentFile>): DiscHit? {
        val name = bin.name ?: return null
        val base = name.substringBeforeLast('.')
        if (siblings.any { it.name.equals("$base.cue", ignoreCase = true) }) return null
        val referenced = siblings.filter { it.name?.endsWith(".cue", ignoreCase = true) == true }.any { cue ->
            readSmallText(c, cue.uri)?.let { t -> Ps2Disc.cueFiles(t).any { it.equals(name, ignoreCase = true) } } == true
        }
        if (referenced) return null
        val big = bin.length() > 32L * 1024 * 1024
        if (!big) return null
        return if (withDisc(c, bin.uri) { Ps2Disc.isPs2Disc(it) } == true) DiscHit(HwSystem.PS2) else null
    }

    private fun readSmallText(c: Context, uri: Uri): String? = runCatching {
        c.contentResolver.openInputStream(uri)?.use { ins ->
            val buf = ByteArray(64 * 1024)
            var n = 0
            while (n < buf.size) { val k = ins.read(buf, n, buf.size - n); if (k < 0) break; n += k }
            String(buf, 0, n, Charsets.ISO_8859_1)
        }
    }.getOrNull()

    private fun <T> withDisc(c: Context, uri: Uri, block: (DiscReader) -> T?): T? = runCatching {
        c.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { block(DescriptorDisc(it)) }
        }
    }.getOrNull()
}
