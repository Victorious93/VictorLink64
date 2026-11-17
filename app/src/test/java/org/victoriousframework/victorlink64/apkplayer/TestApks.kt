package org.victoriousframework.victorlink64.apkplayer

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Test helper: the base APKs are compiled by aapt2 during the build (task `generateTestApks`);
 * variants with fake native libraries are assembled here. Nothing is read from or written to the repository.
 */
object TestApks {
    fun fixture(name: String): File {
        val dir = System.getProperty("vl64.testApkDir") ?: error("vl64.testApkDir not set; run through Gradle")
        return File(dir, "$name.apk").also { check(it.isFile) { "missing generated fixture $it" } }
    }

    /** Copies [base] and adds [extra] entries, so a manifest-only APK can look like one with native code. */
    fun withEntries(base: File, dir: File, extra: Map<String, ByteArray>): File {
        val out = File.createTempFile("variant", ".apk", dir)
        ZipOutputStream(out.outputStream()).use { zos ->
            ZipFile(base).use { zf ->
                zf.entries().asSequence().forEach { e ->
                    zos.putNextEntry(ZipEntry(e.name))
                    zf.getInputStream(e).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            extra.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out
    }

    fun manifestBytes(apk: File): ByteArray = ZipFile(apk).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes() }
}
