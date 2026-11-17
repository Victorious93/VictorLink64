package org.victoriousframework.victorlink64.apkplayer

import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** What the manifest says about an app. Plain data so it can be unit tested on the JVM. */
data class ManifestInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val targetSdk: Int,
    /** Literal label, or null when the label is a resource reference (see [labelRes]). */
    val labelText: String?,
    val labelRes: Int,
    val iconRes: Int,
    val permissions: List<String>,
    /** Fully qualified class name of the launcher activity, or null if the app has none. */
    val launcherActivity: String?,
)

data class ApkInfo(
    val manifest: ManifestInfo,
    /** ABI folders found under `lib/` that contain at least one `.so`, e.g. `armeabi-v7a`. */
    val nativeAbis: Set<String>,
    val hasDex: Boolean,
    val sizeBytes: Long,
) {
    val packageName get() = manifest.packageName
    val hasNativeCode get() = nativeAbis.isNotEmpty()
}

object ApkInspector {
    private const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024

    @Throws(ApkParseException::class)
    fun inspect(apk: File): ApkInfo {
        if (!apk.isFile) throw ApkParseException("The file could not be found.")
        try {
            ZipFile(apk).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml")
                    ?: throw ApkParseException("This file is not an Android app (no AndroidManifest.xml).")
                if (manifestEntry.size !in 1..MAX_MANIFEST_BYTES.toLong()) {
                    throw ApkParseException("The app's manifest has an unreasonable size.")
                }
                val bytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
                val manifest = parseManifest(bytes)

                val abis = LinkedHashSet<String>()
                var hasDex = false
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (name == "classes.dex") hasDex = true
                    abiOfLibraryEntry(name)?.let { abis.add(it) }
                }
                return ApkInfo(manifest, abis, hasDex, apk.length())
            }
        } catch (e: ZipException) {
            throw ApkParseException("This file is not a valid APK (it is not a readable zip archive).", e)
        } catch (e: IOException) {
            throw ApkParseException("The file could not be read: ${e.message ?: "I/O error"}", e)
        }
    }

    /** Returns the ABI folder for `lib/<abi>/<file>.so` entries, or null for anything else. */
    fun abiOfLibraryEntry(entryName: String): String? {
        if (!entryName.startsWith("lib/") || !entryName.endsWith(".so")) return null
        val parts = entryName.split('/')
        if (parts.size != 3 || parts[1].isEmpty()) return null
        return parts[1]
    }

    @Throws(ApkParseException::class)
    fun parseManifest(bytes: ByteArray): ManifestInfo {
        val collector = ManifestCollector()
        AxmlReader.parse(bytes, collector)
        return collector.build()
    }

    private class ManifestCollector : AxmlHandler {
        private val path = ArrayList<String>()
        private var pkg: String? = null
        private var versionCode = 0L
        private var versionName: String? = null
        private var minSdk: Int? = null
        private var targetSdk: Int? = null
        private var labelText: String? = null
        private var labelRes = 0
        private var iconRes = 0
        private val permissions = ArrayList<String>()

        private var currentComponent: String? = null
        private var hasMain = false
        private var hasLauncher = false
        private var launcher: String? = null

        private fun List<AxmlAttribute>.attr(name: String) = firstOrNull { it.name == name }

        override fun startElement(name: String, attributes: List<AxmlAttribute>) {
            val parent = path.lastOrNull()
            path.add(name)
            when (name) {
                "manifest" -> {
                    pkg = attributes.attr("package")?.stringValue
                    attributes.attr("versionCode")?.let { versionCode = (it.intValue ?: 0).toLong() and 0xFFFFFFFFL }
                    attributes.attr("versionCodeMajor")?.intValue?.let { versionCode = versionCode or (it.toLong() shl 32) }
                    versionName = attributes.attr("versionName")?.stringValue
                }
                "uses-sdk" -> {
                    minSdk = attributes.attr("minSdkVersion")?.intValue
                    targetSdk = attributes.attr("targetSdkVersion")?.intValue
                }
                "uses-permission", "uses-permission-sdk-23" ->
                    attributes.attr("name")?.stringValue?.let { permissions.add(it) }
                "application" -> if (parent == "manifest") {
                    attributes.attr("label")?.let { if (it.isReference) labelRes = it.data else labelText = it.stringValue }
                    attributes.attr("icon")?.let { if (it.isReference) iconRes = it.data }
                }
                "activity", "activity-alias" -> if (parent == "application") {
                    currentComponent = if (name == "activity-alias") {
                        attributes.attr("targetActivity")?.stringValue ?: attributes.attr("name")?.stringValue
                    } else attributes.attr("name")?.stringValue
                    hasMain = false
                    hasLauncher = false
                }
                "intent-filter" -> if (currentComponent != null) {
                    hasMain = false
                    hasLauncher = false
                }
                "action" -> if (currentComponent != null && attributes.attr("name")?.stringValue == "android.intent.action.MAIN") hasMain = true
                "category" -> if (currentComponent != null && attributes.attr("name")?.stringValue == "android.intent.category.LAUNCHER") hasLauncher = true
            }
        }

        override fun endElement(name: String) {
            if (path.isNotEmpty()) path.removeAt(path.size - 1)
            when (name) {
                "intent-filter" -> if (currentComponent != null && hasMain && hasLauncher && launcher == null) {
                    launcher = currentComponent
                }
                "activity", "activity-alias" -> if (path.lastOrNull() == "application") currentComponent = null
            }
        }

        fun build(): ManifestInfo {
            val packageName = pkg?.takeIf { it.isNotBlank() }
                ?: throw ApkParseException("The app's manifest has no package name.")
            val min = minSdk ?: 1
            val launcherClass = launcher?.let { l ->
                when {
                    l.startsWith(".") -> packageName + l
                    !l.contains('.') -> "$packageName.$l"
                    else -> l
                }
            }
            return ManifestInfo(
                packageName = packageName,
                versionCode = versionCode,
                versionName = versionName,
                minSdk = min,
                targetSdk = targetSdk ?: min,
                labelText = labelText,
                labelRes = labelRes,
                iconRes = iconRes,
                permissions = permissions.distinct(),
                launcherActivity = launcherClass,
            )
        }
    }
}
