package org.victoriousframework.victorlink64.hw

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * PlayStation 2 disc detection for the library. Victorious Framework.
 *
 * A PS1 and a PS2 disc both say "PLAYSTATION" in the ISO 9660 header, so the only reliable way to tell them apart is
 * SYSTEM.CNF: PS2 discs boot with "BOOT2", PS1 discs with "BOOT". Everything here reads through [DiscReader], so it is
 * plain Kotlin that the JVM unit tests drive with in-memory images; the app plugs in a file-descriptor reader.
 */
interface DiscReader {
    val size: Long
    /** Up to [length] bytes at [offset]; shorter at the end of the image, null if the image can't be read there. */
    fun read(offset: Long, length: Int): ByteArray?
}

class ByteArrayDisc(private val data: ByteArray) : DiscReader {
    override val size get() = data.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray? {
        if (offset < 0 || offset >= data.size) return null
        val n = minOf(length.toLong(), data.size - offset).toInt()
        return data.copyOfRange(offset.toInt(), offset.toInt() + n)
    }
}

object Ps2Disc {
    /** How the data bytes of sector N sit in the image: 2048-byte sectors, or raw CD sectors (sync + header first). */
    private data class Layout(val sectorSize: Int, val dataOffset: Int)

    private val LAYOUTS = listOf(Layout(2048, 0), Layout(2352, 16), Layout(2352, 24))
    private const val PVD_LBA = 16L

    private fun Layout.read(r: DiscReader, lba: Long, length: Int): ByteArray? {
        val sectors = (length + 2047) / 2048
        val out = java.io.ByteArrayOutputStream(length)
        for (i in 0 until sectors) {
            val chunk = r.read((lba + i) * sectorSize + dataOffset, minOf(2048, length - i * 2048)) ?: return null
            out.write(chunk)
        }
        return out.toByteArray()
    }

    private fun u32le(b: ByteArray, o: Int): Long = (0 until 4).fold(0L) { acc, i -> acc or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }

    private fun layoutOf(r: DiscReader): Layout? = LAYOUTS.firstOrNull { l ->
        val pvd = l.read(r, PVD_LBA, 8) ?: return@firstOrNull false
        pvd.size >= 6 && pvd[0] == 1.toByte() && String(pvd, 1, 5, Charsets.ISO_8859_1) == "CD001"
    }

    /** The text of SYSTEM.CNF from the root directory of the ISO 9660 image, or null when the image has none. */
    fun systemCnf(r: DiscReader): String? {
        val l = layoutOf(r) ?: return null
        val pvd = l.read(r, PVD_LBA, 2048) ?: return null
        if (pvd.size < 190) return null
        val rootLba = u32le(pvd, 156 + 2)
        val rootLen = u32le(pvd, 156 + 10).coerceIn(0L, 65536L).toInt()
        if (rootLen == 0) return null
        val dir = l.read(r, rootLba, rootLen) ?: return null
        var pos = 0
        while (pos < dir.size) {
            val recLen = dir[pos].toInt() and 0xFF
            if (recLen == 0) { pos = (pos / 2048 + 1) * 2048; continue }
            if (pos + 33 > dir.size || pos + recLen > dir.size) break
            val nameLen = dir[pos + 32].toInt() and 0xFF
            if (pos + 33 + nameLen <= dir.size) {
                val name = String(dir, pos + 33, nameLen, Charsets.ISO_8859_1).substringBefore(';').uppercase()
                if (name == "SYSTEM.CNF") {
                    val lba = u32le(dir, pos + 2)
                    val len = u32le(dir, pos + 10).coerceIn(0L, 4096L).toInt()
                    return l.read(r, lba, len)?.let { String(it, Charsets.ISO_8859_1) }
                }
            }
            pos += recLen
        }
        return null
    }

    /** True when a SYSTEM.CNF boots through BOOT2, which only PlayStation 2 discs do. */
    fun isPs2BootConfig(cnf: String): Boolean =
        cnf.lineSequence().any { Regex("""^\s*BOOT2\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(it) }

    fun isPs2Disc(r: DiscReader): Boolean = systemCnf(r)?.let(::isPs2BootConfig) ?: false

    /** ".cso" (CISO v1, block size 2048): reads decompressed sectors so [isPs2Disc] works on the compressed image. */
    class CsoReader private constructor(private val src: DiscReader, private val blockSize: Int, private val align: Int,
                                        override val size: Long, private val blocks: Int) : DiscReader {
        private var cachedIndex = -1
        private var cached: ByteArray? = null

        private fun indexEntry(i: Int): Long? = src.read(24L + 4L * i, 4)?.takeIf { it.size == 4 }?.let { u32le(it, 0) }

        private fun block(i: Int): ByteArray? {
            if (i == cachedIndex) return cached
            if (i < 0 || i >= blocks) return null
            val a = indexEntry(i) ?: return null
            val b = indexEntry(i + 1) ?: return null
            val raw = a and 0x80000000L != 0L
            val start = (a and 0x7FFFFFFFL) shl align
            val end = (b and 0x7FFFFFFFL) shl align
            val len = (end - start).toInt()
            if (len <= 0 || len > blockSize * 2) return null
            val stored = src.read(start, len) ?: return null
            val data = if (raw) stored else inflate(stored) ?: return null
            cachedIndex = i; cached = data
            return data
        }

        private fun inflate(stored: ByteArray): ByteArray? {
            val inf = Inflater(true)
            return try {
                inf.setInput(stored)
                val out = ByteArray(blockSize)
                var n = 0
                while (n < blockSize && !inf.finished()) {
                    val k = inf.inflate(out, n, blockSize - n)
                    if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    n += k
                }
                if (n == 0) null else out.copyOf(n)
            } catch (e: DataFormatException) {
                null
            } finally { inf.end() }
        }

        override fun read(offset: Long, length: Int): ByteArray? {
            if (offset < 0 || offset >= size) return null
            val out = java.io.ByteArrayOutputStream(length)
            var pos = offset
            val end = minOf(offset + length, size)
            while (pos < end) {
                val blk = block((pos / blockSize).toInt()) ?: return null
                val within = (pos % blockSize).toInt()
                val take = minOf(blk.size - within, (end - pos).toInt())
                if (take <= 0) return null
                out.write(blk, within, take)
                pos += take
            }
            return out.toByteArray()
        }

        companion object {
            fun open(src: DiscReader): CsoReader? {
                val h = src.read(0, 24) ?: return null
                if (h.size < 24 || String(h, 0, 4, Charsets.ISO_8859_1) != "CISO") return null
                val total = u32le(h, 8) or (u32le(h, 12) shl 32)
                val block = u32le(h, 16).toInt()
                val align = h[21].toInt() and 0xFF
                if (block <= 0 || block > 1 shl 16 || total <= 0 || align > 8) return null
                val blocks = ((total + block - 1) / block).toInt()
                return CsoReader(src, block, align, total, blocks)
            }
        }
    }

    enum class ChdKind { CD, DVD, OTHER }
    data class ChdInfo(val kind: ChdKind, val logicalBytes: Long)

    private fun u32be(b: ByteArray, o: Int): Long = (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (b[o + i].toLong() and 0xFF) }
    private fun u64be(b: ByteArray, o: Int): Long = (u32be(b, o) shl 32) or u32be(b, o + 4)

    /** Version 5 CHD header + the metadata tags that say CD or DVD. Null for anything that isn't a V5 CHD. */
    fun chdInfo(r: DiscReader): ChdInfo? {
        val h = r.read(0, 124) ?: return null
        if (h.size < 124 || String(h, 0, 8, Charsets.ISO_8859_1) != "MComprHD" || u32be(h, 12) != 5L) return null
        val logical = u64be(h, 32)
        var meta = u64be(h, 48)
        var kind = ChdKind.OTHER
        var guard = 0
        while (meta > 0 && guard++ < 64) {
            val e = r.read(meta, 16) ?: break
            if (e.size < 16) break
            when (String(e, 0, 4, Charsets.ISO_8859_1)) {
                "CHT2", "CHTR", "CHCD" -> kind = ChdKind.CD
                "DVD " -> if (kind == ChdKind.OTHER) kind = ChdKind.DVD
            }
            meta = u64be(e, 8)
        }
        return ChdInfo(kind, logical)
    }

    /**
     * Largest PSP UMD is about 1.8 GB. A DVD-style CHD bigger than this cannot be a PSP game, so it is treated as PS2.
     * Smaller DVD CHDs are ambiguous (PSP and PS2 both store them the same way) and are not claimed.
     * CD CHDs can't be told from PlayStation 1 discs without decompressing them; PS1 isn't supported, so they are
     * listed as PS2 and the core will refuse a PS1 disc with a clear error.
     */
    const val PSP_MAX_BYTES = 1_850_000_000L

    fun chdLooksPs2(info: ChdInfo): Boolean = when (info.kind) {
        ChdKind.CD -> true
        ChdKind.DVD -> info.logicalBytes > PSP_MAX_BYTES
        ChdKind.OTHER -> false
    }

    /** Every "FILE" named in a .cue sheet, in order. Folder parts are dropped: the file is looked up beside the cue. */
    fun cueFiles(cue: String): List<String> = cue.lineSequence().mapNotNull { line ->
        val m = Regex("""^\s*FILE\s+(?:"([^"]+)"|(\S+))""", RegexOption.IGNORE_CASE).find(line) ?: return@mapNotNull null
        (m.groupValues[1].ifEmpty { m.groupValues[2] }).replace('\\', '/').substringAfterLast('/')
    }.toList()

    /** The cue with every FILE entry reduced to the bare file name, so the core finds the files beside the cue copy. */
    fun rewriteCue(cue: String): String = cue.lineSequence().joinToString("\n") { line ->
        val m = Regex("""^(\s*FILE\s+)(?:"([^"]+)"|(\S+))(.*)$""", RegexOption.IGNORE_CASE).find(line) ?: return@joinToString line
        val name = (m.groupValues[2].ifEmpty { m.groupValues[3] }).replace('\\', '/').substringAfterLast('/')
        "${m.groupValues[1]}\"$name\"${m.groupValues[4]}"
    }

    val EXTENSIONS = setOf("iso", "chd", "cso", "cue", "bin")
}
