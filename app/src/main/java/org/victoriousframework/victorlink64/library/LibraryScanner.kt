package org.victoriousframework.victorlink64.library

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import org.victoriousframework.victorlink64.ui.Game
import org.victoriousframework.victorlink64.ui.ScanResult
import java.io.File
import java.io.InputStream
import java.nio.channels.SeekableByteChannel

/**
 * Builds the game library from a picked folder and/or individually added files (Android Storage Access Framework),
 * looking inside archives and sniffing headers. Victorious Framework.
 */
object LibraryScanner {
    private const val ARCHIVE_CACHE = "victorlink64_archivecache"
    private const val FILES_PREFS = "victorlink64_prefs"
    private const val KEY_FILES = "imported_files"
    private const val MAX_DEPTH = 4

    // ---- individually added files -------------------------------------------------------------------------------
    fun addedFiles(c: Context): List<Uri> =
        c.getSharedPreferences(FILES_PREFS, Context.MODE_PRIVATE).getString(KEY_FILES, null)
            ?.let { s -> runCatching { JSONArray(s).let { a -> (0 until a.length()).map { Uri.parse(a.getString(it)) } } }.getOrNull() }
            ?: emptyList()

    fun setAddedFiles(c: Context, uris: List<Uri>) {
        val a = JSONArray(); uris.distinct().forEach { a.put(it.toString()) }
        c.getSharedPreferences(FILES_PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FILES, a.toString()).apply()
    }

    fun addFiles(c: Context, uris: List<Uri>) = setAddedFiles(c, addedFiles(c) + uris)

    fun removeFile(c: Context, uri: Uri) {
        setAddedFiles(c, addedFiles(c) - uri)
        runCatching { c.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    // ---- scanning --------------------------------------------------------------------------------------------------
    fun scan(c: Context, folder: Uri?, files: List<Uri> = addedFiles(c)): ScanResult {
        val out = mutableListOf<Game>()
        val problems = mutableListOf<Pair<String, String>>()
        var seen = 0
        var error: String? = null
        val reader = ArchiveReader(RarNative.backendOrNull())
        val docs = mutableListOf<DocumentFile>()

        fun walk(d: DocumentFile, depth: Int) {
            for (f in d.listFiles()) {
                if (f.isDirectory) { if (depth < MAX_DEPTH) walk(f, depth + 1) } else docs += f
            }
        }
        if (folder != null) {
            val root = DocumentFile.fromTreeUri(c, folder)
            when {
                root == null -> error = "That folder can't be opened. Pick it again."
                !root.canRead() -> error = "VictorLink64 lost permission to read your game folder. Pick it again."
                else -> runCatching { walk(root, 0) }.onFailure { error = "Couldn't finish reading your game folder: ${it.message}" }
            }
        }
        for (u in files) {
            val d = DocumentFile.fromSingleUri(c, u)
            if (d == null || !d.exists()) problems += (u.lastPathSegment?.substringAfterLast('/') ?: u.toString()) to
                "This added file can't be found any more. It may have been moved or deleted — remove it in Settings › Game storage."
            else docs += d
        }

        val seenUris = HashSet<String>()
        for (f in docs) {
            if (!seenUris.add(f.uri.toString())) continue
            seen++
            val name = f.name ?: continue
            try {
                scanDocument(c, reader, f.uri, name, f.length(), f.lastModified(), out, problems)
            } catch (e: Exception) {
                problems += name to (e as? FriendlyException)?.message.orEmpty().ifEmpty { "This file couldn't be read. It may be damaged or still downloading." }
            }
        }
        val sorted = out.sortedWith(compareBy<Game>({ it.name.lowercase() }, { it.disc ?: 0 }, { it.archiveName.lowercase() }, { it.fileName.lowercase() }))
        return ScanResult(sorted, seen, error, dedupeCdProblems(problems))
    }

    private fun scanDocument(c: Context, reader: ArchiveReader, uri: Uri, name: String, size: Long, modified: Long,
                             out: MutableList<Game>, problems: MutableList<Pair<String, String>>) {
        val byName = GameFormats.archiveKind(name)
        val ext = GameFormats.ext(name)
        if (byName == null && ext !in GameFormats.GAME_EXT) return
        val header: ByteArray? = if (byName == null && GameFormats.needsHeader(name)) readHeader(c, uri) else null
        val kind: ArchiveKind? = byName ?: header?.let { GameFormats.archiveKindByMagic(it) }
        if (kind != null) {
            val listing = cachedListing(c, reader, UriSource(c, uri, name, size), kind, modified)
            listing.games.forEach { g ->
                val leaf = g.path.substringAfterLast('/')
                out += Game(
                    name = GameFormats.baseTitle(leaf), uri = uri, size = g.size, snes = g.console == Console.SNES, fileName = leaf,
                    zipEntry = g.path, hw = g.console.hw, archive = kind, disc = GameFormats.discNumber(leaf),
                    sourceSize = size, sourceModified = modified, format = g.format, archiveName = name
                )
            }
            problems += listing.problems
            if (listing.games.isEmpty() && listing.problems.isEmpty())
                problems += name to "No games were found inside this ${kind.label} file."
            return
        }
        when (val d = GameFormats.detect(name, header, size)) {
            is Detection.Game -> out += Game(GameFormats.baseTitle(name), uri, size, d.console == Console.SNES, name,
                hw = d.console.hw, disc = GameFormats.discNumber(name), sourceSize = size, sourceModified = modified, format = d.format)
            is Detection.Unsupported -> problems += name to d.reason
            Detection.NotAGame -> {}
        }
    }

    /** A .cue and its .bin tracks say the same thing; keep one line per set. */
    private fun dedupeCdProblems(p: List<Pair<String, String>>): List<Pair<String, String>> =
        p.distinctBy { (n, m) -> if ("CD image" in m) n.substringBeforeLast('.').substringBefore(" (Track") + m else n + m }

    private fun readHeader(c: Context, uri: Uri): ByteArray? = runCatching {
        c.contentResolver.openInputStream(uri)?.use { ArchiveReader.readHeader(it) }
    }.getOrNull()

    /** Archive contents are cached per (uri, size, date) so rescans don't reread big archives. */
    private fun cachedListing(c: Context, reader: ArchiveReader, src: UriSource, kind: ArchiveKind, modified: Long): ArchiveListing {
        val prefs = c.getSharedPreferences(ARCHIVE_CACHE, Context.MODE_PRIVATE)
        val key = "v2#${src.uri}#${src.size}#$modified"
        prefs.getString(key, null)?.let { s -> runCatching { return decode(s) } }
        val listing = try { reader.list(src, kind) } catch (e: FriendlyException) { ArchiveListing(emptyList(), listOf(src.name to e.message!!)) }
        prefs.edit().putString(key, encode(listing)).apply()
        return listing
    }

    private fun encode(l: ArchiveListing): String = JSONObject().apply {
        put("g", JSONArray().apply { l.games.forEach { put(JSONObject().put("p", it.path).put("s", it.size).put("c", it.console.name).put("f", it.format)) } })
        put("x", JSONArray().apply { l.problems.forEach { put(JSONArray().put(it.first).put(it.second)) } })
    }.toString()

    private fun decode(s: String): ArchiveListing {
        val o = JSONObject(s)
        val g = o.getJSONArray("g"); val x = o.getJSONArray("x")
        return ArchiveListing(
            (0 until g.length()).map { g.getJSONObject(it).let { j -> ArchiveGame(j.getString("p"), j.getLong("s"), Console.valueOf(j.getString("c")), j.getString("f")) } },
            (0 until x.length()).map { x.getJSONArray(it).let { a -> a.getString(0) to a.getString(1) } })
    }
}

/** A SAF document as an [ArchiveSource]. */
class UriSource(private val c: Context, val uri: Uri, override val name: String, override val size: Long) : ArchiveSource {
    override fun openStream(): InputStream = c.contentResolver.openInputStream(uri) ?: throw FriendlyException("$name can't be opened.")
    override fun openChannel(): SeekableByteChannel? = runCatching {
        val pfd = c.contentResolver.openFileDescriptor(uri, "r") ?: return null
        // Closing the channel closes the stream, which closes the descriptor.
        ParcelFileDescriptor.AutoCloseInputStream(pfd).channel
    }.getOrNull()
}

/** Unpack progress shown by the library screen. Updated from the launch thread. */
object ImportProgress {
    data class State(val title: String, val done: Long, val total: Long)
    var state by mutableStateOf<State?>(null)
    @Volatile var cancelled = false
}

/** Turns a library game into something a core can open, unpacking archive members once into the cache. */
object GameLauncher {
    fun cacheRoot(c: Context): File = File(c.externalCacheDir ?: c.cacheDir, "unpacked").apply { mkdirs() }

    fun cache(c: Context) = ExtractCache(cacheRoot(c))

    /** Cache size cap choices in GB (0 = no cap) and the stored choice. The cap is soft: one big game may exceed it. */
    val CAP_CHOICES_GB = listOf(2, 4, 8, 16, 0)
    private const val KEY_CAP_GB = "unpack_cache_cap_gb"
    private const val DEFAULT_CAP_GB = 4
    fun capGb(c: Context): Int = c.getSharedPreferences("victorlink64_prefs", Context.MODE_PRIVATE).getInt(KEY_CAP_GB, DEFAULT_CAP_GB)
    fun setCapGb(c: Context, gb: Int) { c.getSharedPreferences("victorlink64_prefs", Context.MODE_PRIVATE).edit().putInt(KEY_CAP_GB, gb).apply() }
    fun capBytes(c: Context): Long = capGb(c).toLong() shl 30

    /** Call off the main thread. Returns null if the user cancelled. */
    fun prepare(c: Context, g: Game): Uri? {
        val entry = g.zipEntry ?: return g.uri
        val kind = g.archive ?: GameFormats.archiveKind(g.uri.lastPathSegment ?: "") ?: ArchiveKind.ZIP
        val cache = cache(c)
        val slot = cache.slotFor(g.uri.toString(), g.sourceSize, g.sourceModified, entry)
        cache.reuse(slot)?.let { return Uri.fromFile(it) }
        cache.cleanPartials()
        cache.ensureSpace(g.size, slot, capBytes(c))
        ImportProgress.cancelled = false
        ImportProgress.state = ImportProgress.State(g.name, 0, g.size)
        try {
            val srcName = DocumentFile.fromSingleUri(c, g.uri)?.name ?: g.fileName
            ArchiveReader(RarNative.backendOrNull()).extract(UriSource(c, g.uri, srcName, g.sourceSize), kind, entry, slot) { done, total ->
                ImportProgress.state = ImportProgress.State(g.name, done, if (total > 0) total else g.size)
                !ImportProgress.cancelled
            }
            cache.writeInfo(slot, g.name + (g.disc?.let { " (Disc $it)" } ?: ""), srcName)
            cache.trimToCap(capBytes(c), keep = slot)
            return Uri.fromFile(slot)
        } catch (e: ArchiveReader.CancelledException) {
            slot.parentFile?.deleteRecursively()
            return null
        } catch (e: Exception) {
            slot.parentFile?.deleteRecursively()
            throw e
        } finally {
            ImportProgress.state = null
        }
    }
}
