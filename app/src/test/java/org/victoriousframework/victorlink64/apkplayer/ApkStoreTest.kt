package org.victoriousframework.victorlink64.apkplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Victorious Framework: importing into private storage never trusts picker file names and never keeps junk. */
class ApkStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(max: Long = ApkStore.DEFAULT_MAX_BYTES, clock: () -> Long = { 1000L }) =
        ApkStore(tmp.newFolder("library"), max, clock)

    private fun apkBytes() = TestApks.fixture("legacy-game").readBytes()

    @Test fun importsCopiesAndListsAnApk() {
        val s = store()
        val stored = s.import(apkBytes().inputStream(), "My Game.apk")
        assertEquals("My Game", stored.displayName)
        assertEquals("org.victoriousframework.fixture.legacy", stored.info.packageName)
        assertTrue(stored.file.isFile)
        assertEquals(stored.file.length(), stored.info.sizeBytes)
        assertEquals(1000L, stored.importedAtMillis)
        assertEquals(listOf(stored.id), s.list().map { it.id })
        assertEquals("My Game", s.get(stored.id)!!.displayName)
    }

    @Test fun importingTheSameFileTwiceReusesTheFirstCopy() {
        val s = store()
        val a = s.import(apkBytes().inputStream(), "one.apk")
        val b = s.import(apkBytes().inputStream(), "two.apk")
        assertEquals(a.id, b.id)
        assertEquals(1, s.list().size)
        assertEquals("one", s.list().single().displayName)
    }

    @Test fun theIdIsAHashNotAUserSuppliedName() {
        val s = store()
        val stored = s.import(apkBytes().inputStream(), "../../../evil/name.apk")
        assertTrue(ApkStore.isValidId(stored.id))
        assertEquals("name", stored.displayName)
        assertTrue(stored.file.canonicalPath.startsWith(tmp.root.canonicalPath))
    }

    @Test fun rejectedFilesLeaveNothingBehind() {
        val s = store()
        for (bad in listOf(ByteArray(0), "not an apk".toByteArray(), apkBytes().copyOf(40))) {
            try {
                s.import(bad.inputStream(), "bad.apk")
                fail("expected rejection")
            } catch (e: ApkParseException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
        assertTrue(s.list().isEmpty())
        val leftovers = tmp.root.resolve("library").listFiles()!!.toList()
        assertTrue("no temp files or folders remain: $leftovers", leftovers.isEmpty())
    }

    @Test fun oversizeFilesAreRejectedWhileCopying() {
        val s = store(max = 100)
        try {
            s.import(apkBytes().inputStream(), "big.apk")
            fail("expected rejection")
        } catch (e: ApkParseException) {
            assertTrue(e.message!!.contains("larger"))
        }
        assertTrue(tmp.root.resolve("library").listFiles()!!.isEmpty())
    }

    @Test fun removeDeletesTheCopyAndRejectsBadIds() {
        val s = store()
        val stored = s.import(apkBytes().inputStream(), null)
        assertEquals("falls back to the manifest label", "Legacy Test Game", stored.displayName)
        assertFalse(s.remove("../library"))
        assertFalse(s.remove("short"))
        assertNull(s.get("../library"))
        assertTrue(s.remove(stored.id))
        assertNull(s.get(stored.id))
        assertTrue(s.list().isEmpty())
    }

    @Test fun listIsNewestFirstAndSurvivesCorruptEntries() {
        var now = 1L
        val s = store(clock = { now++ })
        val first = s.import(apkBytes().inputStream(), "first")
        val withLib = TestApks.withEntries(TestApks.fixture("legacy-game"), tmp.root, mapOf("lib/armeabi-v7a/libx.so" to byteArrayOf(1, 2, 3)))
        val second = s.import(withLib.inputStream(), "second")
        assertEquals(listOf(second.id, first.id), s.list().map { it.id })
        assertEquals(setOf("armeabi-v7a"), s.get(second.id)!!.info.nativeAbis)

        second.file.writeText("garbage")
        assertEquals(listOf(first.id), s.list().map { it.id })
        assertNotNull(s.get(first.id))
    }

    @Test fun nameSanitizing() {
        assertEquals("Game", ApkStore.sanitizeName("C:\\x\\Game.APK"))
        assertEquals("a b", ApkStore.sanitizeName("a\u0000 b.apk "))
        assertNull(ApkStore.sanitizeName("   "))
        assertNull(ApkStore.sanitizeName(null))
        assertEquals(80, ApkStore.sanitizeName("x".repeat(200))!!.length)
    }
}
