package org.victoriousframework.victorlink64.library

import java.io.File
import java.security.MessageDigest

/**
 * One-time unpack cache for games that live inside archives. Each game is unpacked once into its own folder
 * (keeping the real file name, since cores look at the extension) and reused on later launches. Oldest entries are
 * removed first when space runs low. Pure JVM so it is unit-tested. Victorious Framework.
 */
class ExtractCache(val root: File) {

    data class Entry(val dir: File, val file: File, val title: String, val source: String, val bytes: Long, val lastUsed: Long)

    /** Stable cache slot for one archive member: same archive (uri + size + date) and entry → same file. */
    fun slotFor(sourceId: String, sourceSize: Long, sourceModified: Long, entryPath: String): File {
        val key = "$sourceId|$sourceSize|$sourceModified|$entryPath"
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
        return File(File(root, hash), safeName(entryPath.substringAfterLast('/')))
    }

    /** The finished file for [slot], marked as just used, or null if it still needs unpacking. */
    fun reuse(slot: File): File? = slot.takeIf { it.isFile && it.length() > 0 }?.also { it.parentFile?.setLastModified(System.currentTimeMillis()) }

    fun writeInfo(slot: File, title: String, source: String) {
        slot.parentFile?.mkdirs()
        File(slot.parentFile, INFO).writeText("$title\n$source\n")
        slot.parentFile?.setLastModified(System.currentTimeMillis())
    }

    fun entries(): List<Entry> = (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { d ->
        val f = d.listFiles()?.firstOrNull { it.isFile && it.name != INFO && !it.name.endsWith(".part") } ?: return@mapNotNull null
        val info = runCatching { File(d, INFO).readLines() }.getOrDefault(emptyList())
        Entry(d, f, info.getOrNull(0)?.ifBlank { null } ?: f.name, info.getOrNull(1) ?: "", dirSize(d), d.lastModified())
    }.sortedByDescending { it.lastUsed }

    fun totalBytes(): Long = (root.listFiles() ?: emptyArray()).sumOf { dirSize(it) }

    fun delete(e: Entry) { e.dir.deleteRecursively() }

    fun clear() { root.listFiles()?.forEach { it.deleteRecursively() } }

    /** Removes leftovers from interrupted unpacks (".part" files and empty folders). */
    fun cleanPartials() {
        root.listFiles()?.forEach { d ->
            d.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
            if (d.isDirectory && (d.listFiles()?.none { it.name != INFO } != false)) d.deleteRecursively()
        }
    }

    /**
     * Makes room for [needed] bytes, deleting least-recently-used unpacked games (never [keep]).
     * Two limits apply: the user's size cap [capBytes] (0 = no cap; soft, so one game bigger than the cap is still
     * allowed) and the phone's free space. Throws a [FriendlyException] if there still isn't enough free space.
     */
    fun ensureSpace(needed: Long, keep: File? = null, capBytes: Long = 0L, freeBytes: () -> Long = { root.apply { mkdirs() }.usableSpace }) {
        if (capBytes > 0) evictWhile(keep) { totalBytes() + needed > capBytes }
        if (needed <= 0) return
        val margin = 64L shl 20
        if (freeBytes() >= needed + margin) return
        evictWhile(keep) { freeBytes() < needed + margin }
        if (freeBytes() >= needed + margin) return
        throw FriendlyException("Not enough free space to unpack this game (needs ${formatBytes(needed)}, " +
            "${formatBytes(freeBytes().coerceAtLeast(0))} free). Free up space on your phone and try again.")
    }

    /** Trims the cache back under [capBytes] (0 = no cap), oldest first, never touching [keep]. */
    fun trimToCap(capBytes: Long, keep: File? = null) {
        if (capBytes > 0) evictWhile(keep) { totalBytes() > capBytes }
    }

    private inline fun evictWhile(keep: File?, tooFull: () -> Boolean) {
        if (!tooFull()) return
        for (e in entries().sortedBy { it.lastUsed }) {
            if (keep != null && e.dir == keep.parentFile) continue
            delete(e)
            if (!tooFull()) return
        }
    }

    companion object {
        const val INFO = ".info"
        fun safeName(n: String): String = n.replace(Regex("[^A-Za-z0-9._ ()\\[\\]-]"), "_").ifBlank { "game" }.take(120)
        private fun dirSize(f: File): Long = if (f.isFile) f.length() else (f.listFiles() ?: emptyArray()).sumOf { dirSize(it) }
    }
}

fun formatBytes(b: Long): String = when {
    b < 0 -> "unknown size"
    b < 1024 -> "$b B"
    b < 1L shl 20 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
    b < 1L shl 30 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
    else -> String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0)
}
