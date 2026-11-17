package org.victoriousframework.victorlink64.apkplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Victorious Framework: manifest and ABI reading, against APKs that aapt2 compiled during this build. */
class ApkInspectorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fakeSo = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

    @Test fun readsALegacyGameManifest() {
        val info = ApkInspector.inspect(TestApks.fixture("legacy-game"))
        val m = info.manifest
        assertEquals("org.victoriousframework.fixture.legacy", m.packageName)
        assertEquals(7L, m.versionCode)
        assertEquals("1.2.3", m.versionName)
        assertEquals(9, m.minSdk)
        assertEquals(10, m.targetSdk)
        assertEquals("Legacy Test Game", m.labelText)
        assertEquals(listOf("android.permission.INTERNET", "android.permission.WAKE_LOCK"), m.permissions)
        assertEquals("org.victoriousframework.fixture.legacy.MainActivity", m.launcherActivity)
        assertTrue(info.nativeAbis.isEmpty())
        assertFalse(info.hasDex)
    }

    @Test fun readsReferencesAliasesAndSdkDefaults() {
        val m = ApkInspector.inspect(TestApks.fixture("modern-reference")).manifest
        assertNull("a resource reference is not literal text", m.labelText)
        assertTrue(m.labelRes != 0)
        assertTrue(m.iconRes != 0)
        assertEquals(21, m.minSdk)
        assertEquals("targetSdk defaults to minSdk when absent", 21, m.targetSdk)
        assertNull(m.versionName)
        assertEquals(42L, m.versionCode)
        assertEquals("the alias launches its target activity", "org.victoriousframework.fixture.modern.Real", m.launcherActivity)
    }

    @Test fun findsNativeAbisAndIgnoresLookAlikes() {
        val variant = TestApks.withEntries(
            TestApks.fixture("legacy-game"), tmp.root,
            mapOf(
                "lib/armeabi-v7a/libgame.so" to fakeSo,
                "lib/armeabi/libold.so" to fakeSo,
                "lib/armeabi-v7a/readme.txt" to fakeSo,
                "lib/x86/nested/libdeep.so" to fakeSo,
                "assets/lib/arm64-v8a/libno.so" to fakeSo,
                "classes.dex" to fakeSo,
            ),
        )
        val info = ApkInspector.inspect(variant)
        assertEquals(setOf("armeabi-v7a", "armeabi"), info.nativeAbis)
        assertTrue(info.hasDex)
        assertTrue(info.hasNativeCode)
    }

    @Test fun libraryEntryParsing() {
        assertEquals("arm64-v8a", ApkInspector.abiOfLibraryEntry("lib/arm64-v8a/libfoo.so"))
        assertNull(ApkInspector.abiOfLibraryEntry("lib/arm64-v8a/"))
        assertNull(ApkInspector.abiOfLibraryEntry("lib//libfoo.so"))
        assertNull(ApkInspector.abiOfLibraryEntry("res/lib/x86/libfoo.so"))
    }

    @Test fun rejectsFilesThatAreNotApks() {
        val text = tmp.newFile("notes.apk").apply { writeText("hello") }
        expectParseError { ApkInspector.inspect(text) }
        expectParseError { ApkInspector.inspect(File(tmp.root, "missing.apk")) }

        val noManifest = tmp.newFile("empty.apk")
        ZipOutputStream(noManifest.outputStream()).use { it.putNextEntry(ZipEntry("hello.txt")); it.write(1); it.closeEntry() }
        expectParseError { ApkInspector.inspect(noManifest) }
    }

    @Test fun damagedManifestsFailCleanlyAtEveryLength() {
        val good = TestApks.manifestBytes(TestApks.fixture("legacy-game"))
        for (len in 0 until good.size step 3) {
            expectParseError { ApkInspector.parseManifest(good.copyOf(len)) }
        }
        val flipped = good.copyOf()
        for (i in 8 until flipped.size step 7) flipped[i] = (flipped[i].toInt() xor 0x5A).toByte()
        try {
            ApkInspector.parseManifest(flipped)
        } catch (_: ApkParseException) {
            // acceptable: any other exception type would propagate and fail the test
        }
    }

    private fun expectParseError(block: () -> Unit) {
        try {
            block()
            fail("expected ApkParseException")
        } catch (e: ApkParseException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }
}
