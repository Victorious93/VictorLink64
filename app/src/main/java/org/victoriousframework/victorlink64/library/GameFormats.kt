package org.victoriousframework.victorlink64.library

import org.victoriousframework.victorlink64.hw.HwSystem

/**
 * Format detection for the library: decides which console a file belongs to from its name plus a peek at its
 * first bytes ("magic" sniffing). Pure Kotlin so it is unit-tested on the JVM. Victorious Framework.
 */
enum class Console(val label: String) { N64("N64"), SNES("SNES"), PSP("PSP"), GAMECUBE("GameCube");
    val hw: HwSystem? get() = when (this) { PSP -> HwSystem.PSP; GAMECUBE -> HwSystem.GAMECUBE; else -> null }
}

/** Container formats the importer can open. RAR is listed so we can explain it instead of ignoring it. */
enum class ArchiveKind(val label: String) { ZIP("ZIP"), SEVEN_Z("7z"), RAR("RAR"), TAR("TAR"), GZIP("GZ"), XZ("XZ"), TAR_GZ("TAR.GZ"), TAR_XZ("TAR.XZ") }

sealed class Detection {
    data class Game(val console: Console, val format: String) : Detection()
    /** Looks like a game file, but one VictorLink64 can't play; [reason] is shown to the user as-is. */
    data class Unsupported(val reason: String) : Detection()
    object NotAGame : Detection()
}

object GameFormats {
    /** How many leading bytes [detect] wants. Covers the PSP ISO volume descriptor and SNES headers (+copier header). */
    const val HEADER_BYTES = 0x10200

    val N64_EXT = setOf("z64", "n64", "v64")
    val SNES_EXT = setOf("sfc", "smc", "swc", "fig")
    val PSP_EXT = setOf("cso", "zso", "pbp", "chd")
    val GC_EXT = setOf("gcm", "rvz", "ciso", "gcz", "wia")
    /** Extensions whose console can't be known without reading the header. */
    val AMBIGUOUS_EXT = setOf("iso", "bin", "img")
    val WII_EXT = setOf("wbfs", "wad")
    val CD_EXT = setOf("cue", "ccd", "mds", "mdf", "nrg", "gdi")

    /** Every file name extension the scanner should look at (outside archives). */
    val GAME_EXT: Set<String> = N64_EXT + SNES_EXT + PSP_EXT + GC_EXT + AMBIGUOUS_EXT + WII_EXT + CD_EXT
    val ARCHIVE_SUFFIXES = listOf(".tar.gz" to ArchiveKind.TAR_GZ, ".tgz" to ArchiveKind.TAR_GZ, ".tar.xz" to ArchiveKind.TAR_XZ,
        ".txz" to ArchiveKind.TAR_XZ, ".zip" to ArchiveKind.ZIP, ".7z" to ArchiveKind.SEVEN_Z, ".rar" to ArchiveKind.RAR,
        ".tar" to ArchiveKind.TAR, ".gz" to ArchiveKind.GZIP, ".xz" to ArchiveKind.XZ)

    fun ext(name: String) = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    fun archiveKind(name: String): ArchiveKind? {
        val n = name.lowercase()
        return ARCHIVE_SUFFIXES.firstOrNull { n.endsWith(it.first) }?.second
    }

    /** Archive kind from the first bytes, for archives with a wrong or missing extension. */
    fun archiveKindByMagic(h: ByteArray): ArchiveKind? = when {
        h.startsWith(0x50, 0x4B, 0x03, 0x04) || h.startsWith(0x50, 0x4B, 0x05, 0x06) -> ArchiveKind.ZIP
        h.startsWith(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> ArchiveKind.SEVEN_Z
        h.startsWith(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) -> ArchiveKind.RAR
        h.startsWith(0x1F, 0x8B) -> ArchiveKind.GZIP
        h.startsWith(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> ArchiveKind.XZ
        h.size >= 262 && String(h, 257, 5, Charsets.ISO_8859_1) == "ustar" -> ArchiveKind.TAR
        else -> null
    }

    /** True when the console is clear from the name alone, so the header doesn't need reading (saves time in 7z/rar). */
    fun needsHeader(name: String): Boolean = ext(name) !in (N64_EXT + SNES_EXT + setOf("pbp", "gcm", "rvz", "gcz", "wia", "chd") + WII_EXT + CD_EXT)

    /**
     * Picks the console for [name]. [header] is the file's first bytes (up to [HEADER_BYTES]) or null when not read.
     * Header magic wins over the extension, so a mis-named file still lands in the right place.
     */
    fun detect(name: String, header: ByteArray?, size: Long = -1): Detection {
        val e = ext(name)
        if (header != null) sniff(header, size)?.let { return it }
        return when (e) {
            in N64_EXT -> Detection.Game(Console.N64, e)
            in SNES_EXT -> Detection.Game(Console.SNES, e)
            "pbp", "zso", "chd", "cso" -> Detection.Game(Console.PSP, e)
            "gcm", "rvz", "gcz", "wia", "ciso" -> Detection.Game(Console.GAMECUBE, e)
            "iso" -> if (header == null) Detection.Game(Console.PSP, e)
                     else Detection.Unsupported("This .iso isn't a PSP or GameCube disc VictorLink64 recognises (it may be for another console, or damaged).")
            in WII_EXT -> WII
            in CD_EXT -> CD_IMAGE
            else -> Detection.NotAGame
        }
    }

    private val WII = Detection.Unsupported("This is a Wii game. VictorLink64 plays GameCube discs, but Wii isn't supported yet.")
    private val CD_IMAGE = Detection.Unsupported("This is a CD image (.bin/.cue style, used by PlayStation 1, Sega CD and similar). Those consoles aren't supported yet.")

    /** Magic-byte checks. Returns null when nothing is recognised. */
    fun sniff(h: ByteArray, size: Long = -1): Detection? {
        if (h.size >= 4 && n64Order(h) != null) return Detection.Game(Console.N64, n64Order(h)!!)
        if (h.startsWith(0x00, 0x50, 0x42, 0x50)) return Detection.Game(Console.PSP, "pbp")
        if (h.startsWith('Z'.code, 'I'.code, 'S'.code, 'O'.code)) return Detection.Game(Console.PSP, "zso")
        if (h.startsWith('C'.code, 'I'.code, 'S'.code, 'O'.code)) return Detection.Game(if (isPspCso(h)) Console.PSP else Console.GAMECUBE, if (isPspCso(h)) "cso" else "ciso")
        if (h.startsWith('R'.code, 'V'.code, 'Z'.code, 0x01)) return Detection.Game(Console.GAMECUBE, "rvz")
        if (h.startsWith('W'.code, 'I'.code, 'A'.code, 0x01)) return Detection.Game(Console.GAMECUBE, "wia")
        if (h.startsWith(0x01, 0xC0, 0x0B, 0xB1)) return Detection.Game(Console.GAMECUBE, "gcz")
        if (h.startsWith('W'.code, 'B'.code, 'F'.code, 'S'.code)) return WII
        if (h.startsWith('M'.code, 'C'.code, 'o'.code, 'm'.code, 'p'.code, 'r'.code, 'H'.code, 'D'.code)) return Detection.Game(Console.PSP, "chd")
        if (HwSystem.isGameCubeHeader(h)) return Detection.Game(Console.GAMECUBE, "iso")
        if (h.size >= 0x1C && h.at(0x18, 0x5D, 0x1C, 0x9E, 0xA3)) return WII
        if (h.size >= 0x8028 && String(h, 0x8001, 5, Charsets.ISO_8859_1) == "CD001") {
            val sysId = String(h, 0x8008, 32, Charsets.ISO_8859_1).trim()
            return when {
                sysId.startsWith("PSP GAME") || sysId.startsWith("UMD") -> Detection.Game(Console.PSP, "iso")
                sysId.startsWith("PLAYSTATION") -> Detection.Unsupported("This looks like a PlayStation 1 or 2 disc. Those aren't supported yet.")
                else -> Detection.Unsupported("This disc image isn't a PSP or GameCube game.")
            }
        }
        if (h.size >= 16 && String(h, 0, 15, Charsets.ISO_8859_1) == "SEGADISCSYSTEM " || h.size >= 32 && h.at(0, 0x00, 0xFF, 0xFF, 0xFF) && h.at(4, 0xFF, 0xFF, 0xFF, 0xFF) && h.at(8, 0xFF, 0xFF, 0xFF, 0x00))
            return CD_IMAGE
        if (looksLikeSnes(h, size)) return Detection.Game(Console.SNES, "sfc")
        return null
    }

    /** N64 byte order from the first word: z64 big-endian, v64 byte-swapped, n64 little-endian. */
    fun n64Order(h: ByteArray): String? = when {
        h.size < 4 -> null
        h.startsWith(0x80, 0x37, 0x12, 0x40) -> "z64"
        h.startsWith(0x37, 0x80, 0x40, 0x12) -> "v64"
        h.startsWith(0x40, 0x12, 0x37, 0x80) -> "n64"
        else -> null
    }

    /** PSP .cso and GameCube .ciso share the "CISO" magic; PSP stores header size 0x18 (or 0) right after it. */
    fun isPspCso(h: ByteArray): Boolean = h.size >= 8 && u32le(h, 4).let { it == 0x18L || it == 0L }

    /** SNES internal header at 0x7FC0 (LoROM) or 0xFFC0 (HiROM), optionally after a 512-byte copier header. */
    fun looksLikeSnes(h: ByteArray, size: Long = -1): Boolean {
        if (size > 0 && (size % 1024L != 0L && size % 1024L != 512L)) return false
        for (copier in intArrayOf(0, 512)) for (base in intArrayOf(0x7FC0, 0xFFC0)) {
            val o = copier + base
            if (h.size < o + 0x20) continue
            val complement = u16le(h, o + 0x1C); val checksum = u16le(h, o + 0x1E)
            if (complement xor checksum != 0xFFFF || checksum == 0 || complement == 0) continue
            val map = h[o + 0x15].toInt() and 0xFF
            if (map and 0xE0 != 0x20) continue
            val title = (0 until 21).map { h[o + it].toInt() and 0xFF }
            if (title.all { it in 0x20..0x7E }) return true
        }
        return false
    }

    /** "(Disc 2)", "[Disc 2 of 3]", "CD2", "Disk 2" → 2. */
    fun discNumber(name: String): Int? =
        Regex("""(?i)[\(\[\s_-](?:disc|disk|cd)\s*(\d{1,2})(?:\s*of\s*\d{1,2})?[\)\]\s_.-]?""").find(name.substringAfterLast('/'))?.groupValues?.get(1)?.toIntOrNull()

    /** Display title: drop folders, extension and disc tag, so multi-disc sets share one title. */
    fun baseTitle(name: String): String {
        val leaf = name.substringAfterLast('/')
        val noExt = archiveKind(leaf)?.let { k -> ARCHIVE_SUFFIXES.first { it.second == k && leaf.lowercase().endsWith(it.first) }.let { leaf.dropLast(it.first.length) } }
            ?: leaf.substringBeforeLast('.', leaf)
        return noExt.replace(Regex("""(?i)\s*[\(\[]\s*(?:disc|disk|cd)\s*\d{1,2}(?:\s*of\s*\d{1,2})?\s*[\)\]]"""), "").trim()
    }

    /** Name of the single file inside a .gz/.xz: "game.iso.gz" → "game.iso". */
    fun innerNameOfCompressed(name: String): String {
        val n = name.substringAfterLast('/')
        return when {
            n.lowercase().endsWith(".gz") -> n.dropLast(3)
            n.lowercase().endsWith(".xz") -> n.dropLast(3)
            else -> n
        }
    }

    private fun ByteArray.startsWith(vararg b: Int) = size >= b.size && b.indices.all { this[it] == b[it].toByte() }
    private fun ByteArray.at(off: Int, vararg b: Int) = size >= off + b.size && b.indices.all { this[off + it] == b[it].toByte() }
    private fun u16le(h: ByteArray, o: Int) = (h[o].toInt() and 0xFF) or ((h[o + 1].toInt() and 0xFF) shl 8)
    private fun u32le(h: ByteArray, o: Int): Long = (0 until 4).fold(0L) { acc, i -> acc or ((h[o + i].toLong() and 0xFF) shl (8 * i)) }
}
