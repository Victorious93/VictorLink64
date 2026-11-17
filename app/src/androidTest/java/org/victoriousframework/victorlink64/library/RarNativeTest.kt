package org.victoriousframework.victorlink64.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real libarchive (native) RAR path on a device or emulator, with a tiny synthetic archive that the test
 * writes itself. Compression methods other than "store" are libarchive's own and are not covered here.
 * Victorious Framework.
 */
@RunWith(AndroidJUnit4::class)
class RarNativeTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "rar-test").apply { deleteRecursively(); mkdirs() }
    }

    private fun n64(size: Int, seed: Int) = ByteArray(size) { (it * seed).toByte() }.also {
        intArrayOf(0x80, 0x37, 0x12, 0x40).forEachIndexed { i, b -> it[i] = b.toByte() }
    }

    private fun write(name: String, bytes: ByteArray) = File(dir, name).also { it.writeBytes(bytes) }

    @Test fun nativeLibraryLoads() = assertNotNull(RarNative.backendOrNull())

    @Test fun listsGamesAndIgnoresJunk() {
        val rar = write("pack.rar", Rar5Fixture.build(listOf(
            "Games/Alpha.z64" to n64(4096, 3), "Games/Beta (USA).n64" to n64(8192, 5), "readme.txt" to "hello".toByteArray())))
        val listing = ArchiveReader(RarNative.backendOrNull()).list(FileArchiveSource(rar), ArchiveKind.RAR)
        assertEquals(listOf("Games/Alpha.z64", "Games/Beta (USA).n64"), listing.games.map { it.path })
        assertTrue(listing.games.all { it.console == Console.N64 })
        assertEquals(listOf(4096L, 8192L), listing.games.map { it.size })
        assertTrue(listing.problems.isEmpty())
    }

    @Test fun extractsExactBytesWithProgress() {
        val game = n64(3 * 1024 * 1024 + 123, 7)
        val rar = write("big.rar", Rar5Fixture.build(listOf("junk.nfo" to ByteArray(10), "Big.z64" to game)))
        var lastDone = 0L
        val out = ArchiveReader(RarNative.backendOrNull()).extract(FileArchiveSource(rar), ArchiveKind.RAR, "Big.z64", File(dir, "out/Big.z64")) { done, _ -> lastDone = done; true }
        assertArrayEquals(game, out.readBytes())
        assertEquals(game.size.toLong(), lastDone)
        assertTrue(dir.walkTopDown().none { it.name.endsWith(".part") })
    }

    @Test fun readStreamsAnEntry() {
        val game = n64(5000, 11)
        val rar = write("one.rar", Rar5Fixture.build(listOf("a.z64" to game)))
        val head = ArchiveReader(RarNative.backendOrNull()).read(FileArchiveSource(rar), ArchiveKind.RAR, "a.z64") { ArchiveReader.readHeader(it, 16) }
        assertArrayEquals(game.copyOf(16), head)
    }

    @Test fun truncatedRarGivesPlainMessageWhenUnpacking() {
        val rar = write("bad.rar", Rar5Fixture.build(listOf("a.z64" to n64(4096, 3))).copyOf(300))
        try {
            ArchiveReader(RarNative.backendOrNull()).extract(FileArchiveSource(rar), ArchiveKind.RAR, "a.z64", File(dir, "out/a.z64"))
            fail("expected a friendly error")
        } catch (e: FriendlyException) {
            assertTrue(e.message!!.contains("bad.rar"))
        }
        assertTrue(!File(dir, "out/a.z64").exists())
    }

    @Test fun notARarGivesPlainMessage() {
        val rar = write("fake.rar", ByteArray(2000) { 0x41 })
        try {
            ArchiveReader(RarNative.backendOrNull()).list(FileArchiveSource(rar), ArchiveKind.RAR)
            fail("expected a friendly error")
        } catch (e: FriendlyException) {
            assertTrue(e.message!!.contains("fake.rar"))
        }
    }
}
