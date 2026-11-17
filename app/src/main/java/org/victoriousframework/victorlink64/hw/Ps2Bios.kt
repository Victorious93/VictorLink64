package org.victoriousframework.victorlink64.hw

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * PlayStation 2 BIOS handling. Victorious Framework.
 *
 * The PS2 needs a BIOS dumped from the user's own console. VictorLink64 never ships, downloads or commits one: the
 * user imports their own files, which are copied into the app's private storage (filesDir/ps2/system/pcsx2/bios,
 * where the LRPS2 core looks). The checks here mirror the core's own BIOS scan (pcsx2/ps2/BiosTools.c in libretro/ps2),
 * so a file accepted here is a file the core will accept.
 */
object Ps2Bios {
    const val MIN_BYTES = 4L * 1024 * 1024
    const val MAX_BYTES = 8L * 1024 * 1024
    /** Sidecar files the core reads or writes next to the BIOS image; copied as they are. */
    private val SIDECAR_SUFFIXES = listOf(".rom1", ".rom2", ".nvm", ".mec")
    private const val SIDECAR_MAX_BYTES = 16L * 1024 * 1024
    private const val ROMDIR_SCAN_BYTES = MAX_BYTES

    data class Info(val region: String, val version: String, val date: String, val console: String) {
        val description get() = "$region v$version ($date)" + if (console.isNotEmpty()) " · $console" else ""
    }

    private class RomdirEntry(val name: String, val size: Long)

    private fun entryAt(b: ByteArray, o: Int): RomdirEntry {
        val raw = String(b, o, 10, Charsets.ISO_8859_1)
        val nul = raw.indexOf('\u0000')
        val size = (0 until 4).fold(0L) { acc, i -> acc or ((b[o + 12 + i].toLong() and 0xFF) shl (8 * i)) }
        return RomdirEntry(if (nul < 0) raw else raw.substring(0, nul), size)
    }

    private fun regionOf(c: Char): String = when (c) {
        'J' -> "Japan"; 'A' -> "USA"; 'E' -> "Europe"; 'H' -> "Asia"; 'C' -> "China"
        'T' -> "Arcade/T10K"; 'X' -> "Test"; 'P' -> "Free"
        else -> "Unknown region"
    }

    /**
     * Reads the ROM directory the way the core does: find the "RESET" entry, walk the entries adding up file offsets
     * until "ROMVER" is found, read its 14-character version string. Null means "not a PS2 BIOS the core would use".
     */
    fun inspect(r: DiscReader): Info? {
        if (r.size < MIN_BYTES || r.size > MAX_BYTES) return null
        var pos = 0L
        var reset: Long = -1
        scan@ while (pos < ROMDIR_SCAN_BYTES) {
            val chunk = r.read(pos, 64 * 1024) ?: return null
            if (chunk.size < 16) return null
            var i = 0
            while (i + 16 <= chunk.size) {
                if (entryAt(chunk, i).name == "RESET") { reset = pos + i; break@scan }
                i += 16
            }
            pos += chunk.size - chunk.size % 16
            if (chunk.size < 64 * 1024) break
        }
        if (reset < 0) return null

        var offset = 0L
        var romver: String? = null
        var entryPos = reset
        var guard = 0
        while (guard++ < 4096) {
            val raw = r.read(entryPos, 16) ?: break
            if (raw.size < 16) break
            val e = entryAt(raw, 0)
            if (e.name.isEmpty() || String(raw, 0, 10, Charsets.ISO_8859_1).indexOf('\u0000') < 0) break
            if (e.name == "ROMVER") {
                romver = r.read(offset, 14)?.takeIf { it.size == 14 }?.let { String(it, Charsets.ISO_8859_1) }
            }
            offset += if (e.size % 16 == 0L) e.size else (e.size + 16) and 0xFFFFFFF0L
            entryPos += 16
        }
        val v = romver ?: return null
        if (!v.all { it.code in 0x20..0x7E }) return null
        val ch = v[4]
        return Info(
            region = regionOf(ch),
            version = "${v.substring(0, 2)}.${v.substring(2, 4)}",
            date = "${v.substring(12, 14)}/${v.substring(10, 12)}/${v.substring(6, 10)}",
            console = when (v[5]) { 'C' -> "Console"; 'D' -> "Devel"; else -> "" },
        )
    }

    private class FileDisc(f: File) : DiscReader, java.io.Closeable {
        private val raf = RandomAccessFile(f, "r")
        override val size get() = raf.length()
        override fun read(offset: Long, length: Int): ByteArray? = try {
            if (offset >= raf.length()) null else {
                raf.seek(offset)
                val buf = ByteArray(minOf(length.toLong(), raf.length() - offset).toInt())
                raf.readFully(buf)
                buf
            }
        } catch (e: java.io.IOException) { null }
        override fun close() = raf.close()
    }

    private fun inspectFile(f: File): Info? = try { FileDisc(f).use { inspect(it) } } catch (e: java.io.IOException) { null }

    data class Entry(val file: File, val info: Info)

    /** Valid BIOS images in [dir], sorted by file name. */
    fun list(dir: File): List<Entry> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.length() in MIN_BYTES..MAX_BYTES }
            .sortedBy { it.name.lowercase() }
            .mapNotNull { f -> inspectFile(f)?.let { Entry(f, it) } }

    /** The BIOS to run: the user's [preferred] file if it is still valid, otherwise the first valid one. */
    fun choose(entries: List<Entry>, preferred: String?): Entry? = entries.firstOrNull { it.file.name == preferred } ?: entries.firstOrNull()

    fun biosDir(filesDir: File): File = File(filesDir, "ps2/system/pcsx2/bios")

    sealed class Outcome {
        data class Added(val name: String, val info: Info) : Outcome()
        data class Sidecar(val name: String) : Outcome()
        data class Skipped(val name: String, val reason: String) : Outcome()
    }

    fun safeName(name: String): String {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = leaf.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.')
        return cleaned.ifEmpty { "bios.bin" }.take(80)
    }

    /**
     * Copies one user-chosen file into [dir] if it is a PS2 BIOS (or a sidecar file the core can use). Nothing that
     * fails the check is kept. [name] is the original display name.
     */
    fun import(dir: File, name: String, input: InputStream): Outcome {
        val safe = safeName(name)
        dir.mkdirs()
        val tmp = File(dir, "$safe.import.tmp")
        try {
            val isSidecar = SIDECAR_SUFFIXES.any { safe.lowercase().endsWith(it) }
            val limit = if (isSidecar) SIDECAR_MAX_BYTES else MAX_BYTES + 1
            var total = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) return Outcome.Skipped(name, if (isSidecar) "This file is too big to be a PS2 BIOS add-on." else "This file is bigger than any PS2 BIOS (8 MB at most).")
                    out.write(buf, 0, n)
                }
            }
            if (isSidecar) {
                val dest = File(dir, safe)
                if (!tmp.renameTo(dest)) { dest.delete(); tmp.copyTo(dest, overwrite = true) }
                return Outcome.Sidecar(safe)
            }
            if (total < MIN_BYTES) return Outcome.Skipped(name, "This file is too small to be a PS2 BIOS (they are at least 4 MB). It may be a PS1 BIOS or a different file.")
            val info = inspectFile(tmp) ?: return Outcome.Skipped(name, "This doesn't look like a PS2 BIOS. Use the BIOS file dumped from your own PlayStation 2 (often named like scph10000.bin).")
            val dest = File(dir, safe)
            if (!tmp.renameTo(dest)) { dest.delete(); tmp.copyTo(dest, overwrite = true) }
            return Outcome.Added(safe, info)
        } finally {
            tmp.delete()
        }
    }
}
