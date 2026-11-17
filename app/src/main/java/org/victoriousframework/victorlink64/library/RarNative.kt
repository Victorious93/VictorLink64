package org.victoriousframework.victorlink64.library

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * RAR reading through libarchive (BSD licence, GPL-compatible) via the Apache-2.0 `libarchive-android` bindings.
 * The unRAR / junrar source is not used because the unRAR licence is not GPL-compatible. libarchive reads RAR 4 and
 * RAR 5, solid archives included; it cannot open password-protected or multi-part RARs, which are reported plainly.
 * Native code, so it can only be exercised on a device or emulator (see the androidTest). Victorious Framework.
 */
object RarNative {
    fun backendOrNull(): RarBackend? = if (available) LibarchiveRarBackend else null

    private val available: Boolean by lazy { runCatching { Archive.readNew().also { Archive.readFree(it) } }.isSuccess }
}

/** Streams a RAR through libarchive's read callbacks so any [ArchiveSource] (SAF document or file) works. */
object LibarchiveRarBackend : RarBackend {
    private const val BLOCK = 64 * 1024

    override fun forEach(source: ArchiveSource, visit: (path: String, size: Long, data: InputStream) -> Boolean) {
        val raw = source.openStream()
        var archive = 0L
        try {
            archive = Archive.readNew()
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatRar(archive)
            Archive.readSupportFormatRar5(archive)
            val chunk = ByteArray(BLOCK)
            val direct = ByteBuffer.allocateDirect(BLOCK)
            Archive.readSetCallbackData(archive, raw)
            Archive.readSetReadCallback(archive, Archive.ReadCallback<InputStream> { _, stream ->
                val n = try { stream.read(chunk) } catch (e: IOException) { throw ArchiveException(Archive.ERRNO_FATAL, "read", e) }
                direct.clear()
                if (n > 0) direct.put(chunk, 0, n)
                direct.flip()
                direct
            })
            Archive.readOpen1(archive)

            while (true) {
                val entry = try { Archive.readNextHeader(archive) } catch (e: ArchiveException) { throw explain(source, e) }
                if (entry == 0L) break
                // Some writers leave the type bits empty; only skip entries that are clearly not plain files.
                val type = ArchiveEntry.filetype(entry) and ArchiveEntry.AE_IFMT
                if (type != ArchiveEntry.AE_IFREG && type != 0) continue
                if (ArchiveEntry.isEncrypted(entry) || ArchiveEntry.isDataEncrypted(entry))
                    throw FriendlyException("${source.name} is password-protected. Unpack it with its password first.")
                val path = ArchiveEntry.pathnameUtf8(entry) ?: continue
                val size = if (ArchiveEntry.sizeIsSet(entry)) ArchiveEntry.size(entry) else -1L
                val keepGoing = visit(path, size, EntryStream(archive, source))
                if (!keepGoing) break
            }
        } catch (e: ArchiveException) {
            throw explain(source, e)
        } finally {
            if (archive != 0L) runCatching { Archive.readClose(archive); Archive.readFree(archive) }
            runCatching { raw.close() }
        }
    }

    private fun explain(source: ArchiveSource, e: ArchiveException): FriendlyException {
        val m = (e.message ?: "").lowercase()
        return FriendlyException(when {
            "password" in m || "encrypt" in m || "passphrase" in m -> "${source.name} is password-protected. Unpack it with its password first."
            "multi" in m && ("volume" in m || "part" in m) || "multivolume" in m ->
                "${source.name} is one part of a multi-part RAR. Join the parts on a computer and re-save it as .zip or .7z."
            else -> "${source.name} couldn't be read as a RAR file. It may be damaged, still downloading, or use a RAR feature VictorLink64 can't open."
        }, e)
    }

    /** The data of the entry libarchive is currently positioned on. */
    private class EntryStream(private val archive: Long, private val source: ArchiveSource) : InputStream() {
        private val buf = ByteBuffer.allocateDirect(BLOCK)
        private val one = ByteArray(1)

        override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            buf.clear()
            buf.limit(minOf(len, BLOCK))
            try { Archive.readData(archive, buf) } catch (e: ArchiveException) { throw explain(source, e) }
            val n = buf.position()
            if (n == 0) return -1
            buf.flip()
            buf.get(b, off, n)
            return n
        }
    }
}
