package org.victoriousframework.victorlink64.apkplayer

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/** An APK that was copied into VictorLink64's private storage. */
data class StoredApk(
    val id: String,
    val file: File,
    val displayName: String,
    val importedAtMillis: Long,
    val info: ApkInfo,
)

/**
 * Private APK library: `<root>/<id>/base.apk` plus `meta.properties`.
 * The id is derived from the file's SHA-256, never from user-supplied names, so file names from
 * the picker cannot influence paths. Importing the same file twice reuses the first copy.
 */
class ApkStore(private val root: File, private val maxBytes: Long = DEFAULT_MAX_BYTES, private val clock: () -> Long = System::currentTimeMillis) {

    @Synchronized
    @Throws(ApkParseException::class)
    fun import(input: InputStream, displayName: String?): StoredApk {
        if (!root.isDirectory && !root.mkdirs()) throw ApkParseException("Private storage is not available.")
        val tmp = File(root, ".incoming-${UUID.randomUUID()}.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            DigestInputStream(input, digest).use { src ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) {
                            throw ApkParseException("This file is larger than ${maxBytes / (1024 * 1024)} MB, which is more than the APK Player accepts.")
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            if (total == 0L) throw ApkParseException("The file is empty.")
            val id = digest.digest().joinToString("") { "%02x".format(it) }.take(ID_LENGTH)
            val dir = File(root, id)
            val existing = load(dir)
            if (existing != null) return existing

            val info = ApkInspector.inspect(tmp)
            if (dir.exists()) dir.deleteRecursively()
            if (!dir.mkdirs()) throw ApkParseException("Could not create private storage for this app.")
            val apk = File(dir, APK_NAME)
            if (!tmp.renameTo(apk)) {
                tmp.copyTo(apk, overwrite = true)
            }
            val now = clock()
            val name = sanitizeName(displayName) ?: info.manifest.labelText ?: info.packageName
            Properties().apply {
                setProperty("displayName", name)
                setProperty("importedAt", now.toString())
            }.also { p -> File(dir, META_NAME).outputStream().use { p.store(it, "Victorious Framework APK Player") } }
            return StoredApk(id, apk, name, now, info.copy(sizeBytes = apk.length()))
        } catch (e: IOException) {
            throw ApkParseException("Could not copy the file into private storage: ${e.message ?: "I/O error"}", e)
        } finally {
            tmp.delete()
        }
    }

    @Synchronized
    fun list(): List<StoredApk> =
        (root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray())
            .mapNotNull { load(it) }
            .sortedByDescending { it.importedAtMillis }

    @Synchronized
    fun get(id: String): StoredApk? = if (isValidId(id)) load(File(root, id)) else null

    @Synchronized
    fun remove(id: String): Boolean = isValidId(id) && File(root, id).deleteRecursively()

    private fun load(dir: File): StoredApk? {
        val apk = File(dir, APK_NAME)
        if (!apk.isFile) return null
        val info = try { ApkInspector.inspect(apk) } catch (_: ApkParseException) { return null }
        val props = Properties()
        runCatching { File(dir, META_NAME).inputStream().use { props.load(it) } }
        return StoredApk(
            id = dir.name,
            file = apk,
            displayName = props.getProperty("displayName") ?: info.manifest.labelText ?: info.packageName,
            importedAtMillis = props.getProperty("importedAt")?.toLongOrNull() ?: apk.lastModified(),
            info = info,
        )
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024
        private const val ID_LENGTH = 24
        private const val APK_NAME = "base.apk"
        private const val META_NAME = "meta.properties"

        fun isValidId(id: String) = id.length == ID_LENGTH && id.all { it in '0'..'9' || it in 'a'..'f' }

        /** Drops a trailing `.apk`, control characters and path separators, and caps the length. */
        fun sanitizeName(raw: String?): String? {
            val s = raw?.substringAfterLast('/')?.substringAfterLast('\\')
                ?.filter { !it.isISOControl() }?.trim()
                ?.removeSuffix(".apk")?.removeSuffix(".APK")?.trim().orEmpty()
            return s.take(80).ifEmpty { null }
        }
    }
}
