package org.victoriousframework.victorlink64.library

import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * Reads games straight out of archives (zip, 7z, tar, gz, xz and, when the native helper is present, rar) without
 * unpacking the whole thing: listing streams entry headers only, and extraction copies one chosen entry.
 * Pure JVM (Apache Commons Compress + XZ for Java) so it is unit-tested with tiny synthetic archives.
 * Victorious Framework.
 */

/** Where archive bytes come from: a local file in tests, a SAF document on the phone. */
interface ArchiveSource {
    val name: String
    val size: Long
    fun openStream(): InputStream
    /** Random access (needed by 7z, faster for zip). Null when the provider can't seek (e.g. some cloud drives). */
    fun openChannel(): SeekableByteChannel?
}

class FileArchiveSource(private val file: File) : ArchiveSource {
    override val name: String get() = file.name
    override val size: Long get() = file.length()
    override fun openStream(): InputStream = file.inputStream()
    override fun openChannel(): SeekableByteChannel = java.nio.file.Files.newByteChannel(file.toPath())
}

/** An error whose message is already plain language and can be shown to the user unchanged. */
class FriendlyException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A game found inside an archive. [path] is the entry name, [size] is -1 when the archive doesn't say. */
data class ArchiveGame(val path: String, val size: Long, val console: Console, val format: String)

data class ArchiveListing(val games: List<ArchiveGame>, val problems: List<Pair<String, String>>)

/** Optional RAR reader (libarchive over JNI on the phone). Absent in JVM tests. */
interface RarBackend {
    /** Calls [visit] for each file entry with a stream positioned at its data; return false to stop. */
    fun forEach(source: ArchiveSource, visit: (path: String, size: Long, data: InputStream) -> Boolean)
}

class ArchiveReader(private val rar: RarBackend? = null) {

    fun list(src: ArchiveSource, kind: ArchiveKind): ArchiveListing {
        val games = mutableListOf<ArchiveGame>()
        val problems = mutableListOf<Pair<String, String>>()
        guard(src, kind) {
            forEachEntry(src, kind, wantData = ::wantsHeader) { path, size, data ->
                val leaf = path.substringAfterLast('/')
                val ext = GameFormats.ext(leaf)
                when {
                    GameFormats.archiveKind(leaf) != null && ext in setOf("zip", "7z", "rar") ->
                        problems += "${src.name} › $leaf" to "This archive is inside another archive. Unpack the outer one first, then add the inner file."
                    ext in GameFormats.GAME_EXT || (data != null && ext !in SKIP_EXT) -> {
                        val header = data?.let { readHeader(it) }
                        when (val d = GameFormats.detect(leaf, header, size)) {
                            is Detection.Game -> games += ArchiveGame(path, size, d.console, d.format)
                            is Detection.Unsupported -> problems += "${src.name} › $leaf" to d.reason
                            Detection.NotAGame -> {}
                        }
                    }
                }
                games.size + problems.size < MAX_ITEMS
            }
        }
        return ArchiveListing(games, problems)
    }

    private fun wantsHeader(path: String): Boolean {
        val leaf = path.substringAfterLast('/')
        val e = GameFormats.ext(leaf)
        return if (e in GameFormats.GAME_EXT) GameFormats.needsHeader(leaf) else e !in SKIP_EXT && GameFormats.archiveKind(leaf) == null
    }

    /**
     * Copies [entryPath] to [dest] (via a .part file, so a cancelled or failed copy never looks finished).
     * [onProgress] gets (bytesDone, totalOrMinus1) and returns false to cancel.
     */
    fun extract(src: ArchiveSource, kind: ArchiveKind, entryPath: String, dest: File, onProgress: (Long, Long) -> Boolean = { _, _ -> true }): File {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        var found = false
        try {
            guard(src, kind) {
                forEachEntry(src, kind, wantData = { it == entryPath }) { path, size, data ->
                    if (path != entryPath || data == null) return@forEachEntry true
                    found = true
                    part.outputStream().buffered(1 shl 16).use { out -> copy(data, out, size, onProgress) }
                    // gzip/xz sizes are approximate (gzip stores the size modulo 4 GiB), so only trust the others.
                    if (size >= 0 && kind != ArchiveKind.GZIP && kind != ArchiveKind.XZ && part.length() != size)
                        throw FriendlyException("${src.name} looks incomplete or damaged (it may still be downloading). Download it again and retry.")
                    false
                }
            }
            if (!found) throw FriendlyException("“${entryPath.substringAfterLast('/')}” is no longer inside ${src.name}. Rescan your library.")
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw FriendlyException("Couldn't save the unpacked game. Free up some space and try again.")
            return dest
        } finally {
            part.delete()
        }
    }

    /** Streams one entry to [use] without writing anything to disk (used for small ROMs and header reads). */
    fun <T> read(src: ArchiveSource, kind: ArchiveKind, entryPath: String, use: (InputStream) -> T): T {
        var result: Result<T>? = null
        guard(src, kind) {
            forEachEntry(src, kind, wantData = { it == entryPath }) { path, _, data ->
                if (path == entryPath && data != null) { result = runCatching { use(data) }; false } else true
            }
        }
        return (result ?: throw FriendlyException("“${entryPath.substringAfterLast('/')}” is no longer inside ${src.name}. Rescan your library.")).getOrThrow()
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, total: Long, onProgress: (Long, Long) -> Boolean) {
        val buf = ByteArray(1 shl 16)
        var done = 0L
        var lastReport = -1L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (done - lastReport >= (1 shl 20) || lastReport < 0) {
                lastReport = done
                if (!onProgress(done, total)) throw CancelledException()
            }
        }
        onProgress(done, total)
    }

    class CancelledException : IOException("Cancelled")

    /** Turns library exceptions into plain language. */
    private inline fun guard(src: ArchiveSource, kind: ArchiveKind, block: () -> Unit) {
        try {
            block()
        } catch (e: FriendlyException) { throw e
        } catch (e: CancelledException) { throw e
        } catch (e: OutOfMemoryError) {
            throw FriendlyException("${src.name} is too big to open this way. Unpack it on a computer and add the game file directly.", e)
        } catch (e: Exception) {
            val m = (e.message ?: "").lowercase()
            throw FriendlyException(when {
                "password" in m || "encrypt" in m -> "${src.name} is password-protected. Unpack it with its password first."
                "unsupported compression" in m || "unsupported" in m && "method" in m || e is org.apache.commons.compress.archivers.ArchiveException ->
                    "${src.name} uses a compression type VictorLink64 can't read. Re-save it as a normal .zip or .7z."
                "no space" in m || "enospc" in m -> "Your phone is out of space. Free some up or clear unpacked games in Settings › Game storage."
                else -> "${src.name} couldn't be read as a ${kind.label} file. It may be damaged or still downloading."
            }, e)
        }
    }

    /**
     * Visits each file entry. [wantData] says whether the visitor needs the entry's bytes (reading them costs time in
     * solid 7z/rar archives). The visitor returns false to stop early.
     */
    private fun forEachEntry(src: ArchiveSource, kind: ArchiveKind, wantData: (String) -> Boolean,
                             visit: (path: String, size: Long, data: InputStream?) -> Boolean) {
        when (kind) {
            ArchiveKind.ZIP -> {
                val ch = runCatching { src.openChannel() }.getOrNull()
                if (ch != null) {
                    @Suppress("DEPRECATION") ZipFile(ch).use { z ->
                        for (e in z.entries.toList()) {
                            if (e.isDirectory) continue
                            val keepGoing = if (wantData(e.name)) z.getInputStream(e).use { visit(e.name, e.size, it) } else visit(e.name, e.size, null)
                            if (!keepGoing) break
                        }
                    }
                } else streamArchive(ZipArchiveInputStream(BufferedInputStream(src.openStream())), wantData, visit)
            }
            ArchiveKind.SEVEN_Z -> {
                val ch = src.openChannel() ?: throw FriendlyException("${src.name} needs to be saved on the phone (not a cloud drive) to open it.")
                @Suppress("DEPRECATION") SevenZFile(ch).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && e.hasStream()) {
                            val name = e.name
                            val keep = if (wantData(name)) visit(name, e.size, SevenZEntryStream(z)) else visit(name, e.size, null)
                            if (!keep) break
                        }
                        e = z.nextEntry
                    }
                }
            }
            ArchiveKind.TAR -> streamArchive(TarArchiveInputStream(BufferedInputStream(src.openStream())), wantData, visit)
            ArchiveKind.TAR_GZ -> streamArchive(TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(src.openStream()), true)), wantData, visit)
            ArchiveKind.TAR_XZ -> streamArchive(TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(src.openStream()))), wantData, visit)
            ArchiveKind.GZIP, ArchiveKind.XZ -> {
                val inner = GameFormats.innerNameOfCompressed(src.name)
                // A .tar inside .gz/.xz without the .tar.gz name: treat as tar.
                if (GameFormats.ext(inner) == "tar") return forEachEntry(src, if (kind == ArchiveKind.GZIP) ArchiveKind.TAR_GZ else ArchiveKind.TAR_XZ, wantData, visit)
                val size = if (kind == ArchiveKind.GZIP) gzipSize(src) else -1L
                if (!wantData(inner)) { visit(inner, size, null); return }
                val raw = BufferedInputStream(src.openStream())
                val s = if (kind == ArchiveKind.GZIP) GzipCompressorInputStream(raw, true) else XZCompressorInputStream(raw)
                s.use { visit(inner, size, it) }
            }
            ArchiveKind.RAR -> {
                val backend = rar ?: throw FriendlyException("RAR files can't be opened on this build. Re-save the game as .zip or .7z.")
                backend.forEach(src) { path, size, data -> visit(path, size, if (wantData(path)) data else null) }
            }
        }
    }

    private fun streamArchive(ais: ArchiveInputStream<*>, wantData: (String) -> Boolean, visit: (String, Long, InputStream?) -> Boolean) {
        ais.use { a ->
            while (true) {
                val e = a.nextEntry ?: break
                if (e.isDirectory || !a.canReadEntryData(e)) continue
                val guardStream = NonClosingStream(a)
                if (!visit(e.name, e.size, if (wantData(e.name)) guardStream else null)) break
            }
        }
    }

    /** Uncompressed size from the gzip trailer (mod 4 GiB; fine for ROMs, approximate for huge discs). */
    private fun gzipSize(src: ArchiveSource): Long = runCatching {
        src.openChannel()?.use { ch ->
            if (ch.size() < 18) return -1
            ch.position(ch.size() - 4)
            val b = ByteBuffer.allocate(4); while (b.hasRemaining() && ch.read(b) > 0) {}
            val a = b.array(); (0 until 4).fold(0L) { acc, i -> acc or ((a[i].toLong() and 0xFF) shl (8 * i)) }
        } ?: -1L
    }.getOrDefault(-1L)

    private class NonClosingStream(inner: InputStream) : java.io.FilterInputStream(inner) { override fun close() {} }

    private class SevenZEntryStream(private val z: SevenZFile) : InputStream() {
        override fun read(): Int = z.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = z.read(b, off, len)
    }

    companion object {
        private const val MAX_ITEMS = 2000
        /** Entries never worth sniffing inside archives (artwork, docs, saves). */
        private val SKIP_EXT = setOf("txt", "nfo", "jpg", "jpeg", "png", "gif", "pdf", "htm", "html", "url", "sfv", "md5", "sha1",
            "srm", "sav", "state", "dat", "xml", "json", "ini", "cfg", "db", "m3u", "exe", "dll", "ds_store", "")
        fun readHeader(input: InputStream, max: Int = GameFormats.HEADER_BYTES): ByteArray {
            val b = ByteArray(max); var n = 0
            while (n < max) { val k = input.read(b, n, max - n); if (k < 0) break; n += k }
            return if (n == max) b else b.copyOf(n)
        }
    }
}
