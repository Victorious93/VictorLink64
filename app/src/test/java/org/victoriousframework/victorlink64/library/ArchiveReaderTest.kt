package org.victoriousframework.victorlink64.library

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Archive handling against tiny synthetic archives built in the test (no real games). Victorious Framework. */
class ArchiveReaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val reader = ArchiveReader()

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File = File(tmp.root, name).also { f ->
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }

    private fun sevenZ(name: String, vararg entries: Pair<String, ByteArray>): File = File(tmp.root, name).also { f ->
        SevenZOutputFile(f).use { z -> entries.forEach { (n, b) -> val e = z.createArchiveEntry(File(n), n); e.size = b.size.toLong(); z.putArchiveEntry(e); z.write(b); z.closeArchiveEntry() } }
    }

    private fun tarGz(name: String, vararg entries: Pair<String, ByteArray>): File = File(tmp.root, name).also { f ->
        TarArchiveOutputStream(GzipCompressorOutputStream(f.outputStream())).use { t ->
            entries.forEach { (n, b) -> t.putArchiveEntry(TarArchiveEntry(n).apply { size = b.size.toLong() }); t.write(b); t.closeArchiveEntry() }
        }
    }

    private fun list(f: File) = reader.list(FileArchiveSource(f), GameFormats.archiveKind(f.name)!!)

    @Test fun zipWithSeveralGamesAndJunk() {
        val f = zip("pack.zip", "n64/Racer.z64" to Fixtures.n64(), "Cart.sfc" to Fixtures.snes(), "readme.txt" to "hi".toByteArray(),
            "Cube.iso" to Fixtures.gameCubeIso(), "Hand.iso" to Fixtures.iso9660("PSP GAME"))
        val l = list(f)
        assertEquals(listOf("n64/Racer.z64" to Console.N64, "Cart.sfc" to Console.SNES, "Cube.iso" to Console.GAMECUBE, "Hand.iso" to Console.PSP),
            l.games.map { it.path to it.console })
        assertTrue(l.problems.isEmpty())
        assertEquals(4096L, l.games[0].size)
    }

    @Test fun zipStreamingFallbackWithoutSeek() {
        val f = zip("s.zip", "a.v64" to Fixtures.n64("v64"))
        val src = object : ArchiveSource by FileArchiveSource(f) { override fun openChannel(): java.nio.channels.SeekableByteChannel? = null }
        val l = reader.list(src, ArchiveKind.ZIP)
        assertEquals(Console.N64, l.games.single().console)
        val out = reader.extract(src, ArchiveKind.ZIP, "a.v64", File(tmp.root, "out/a.v64"))
        assertArrayEquals(Fixtures.n64("v64"), out.readBytes())
    }

    @Test fun sevenZipMultiDisc() {
        val f = sevenZ("set.7z", "Epic (Disc 1).iso" to Fixtures.iso9660("PSP GAME"), "Epic (Disc 2).iso" to Fixtures.iso9660("PSP GAME"),
            "Epic.nfo" to "info".toByteArray())
        val l = list(f)
        assertEquals(2, l.games.size)
        assertTrue(l.games.all { it.console == Console.PSP })
        assertEquals(listOf(1, 2), l.games.map { GameFormats.discNumber(it.path) })
        val out = reader.extract(FileArchiveSource(f), ArchiveKind.SEVEN_Z, "Epic (Disc 2).iso", File(tmp.root, "x/Epic (Disc 2).iso"))
        assertArrayEquals(Fixtures.iso9660("PSP GAME"), out.readBytes())
    }

    @Test fun tarGzAndSingleGzXz() {
        val tg = tarGz("bundle.tar.gz", "Cart.smc" to Fixtures.snes(copierHeader = true), "Cube.gcm" to Fixtures.gameCubeIso())
        assertEquals(listOf(Console.SNES, Console.GAMECUBE), list(tg).games.map { it.console })

        val gz = File(tmp.root, "Racer.z64.gz").also { f -> GzipCompressorOutputStream(f.outputStream()).use { it.write(Fixtures.n64()) } }
        val g = list(gz).games.single()
        assertEquals("Racer.z64" to Console.N64, g.path to g.console)
        assertEquals(4096L, g.size)

        val xz = File(tmp.root, "Cube.iso.xz").also { f -> XZCompressorOutputStream(f.outputStream()).use { it.write(Fixtures.gameCubeIso()) } }
        assertEquals(Console.GAMECUBE, list(xz).games.single().console)
        val out = reader.extract(FileArchiveSource(xz), ArchiveKind.XZ, "Cube.iso", File(tmp.root, "y/Cube.iso"))
        assertArrayEquals(Fixtures.gameCubeIso(), out.readBytes())
    }

    @Test fun nestedArchiveAndUnsupportedAreReported() {
        val f = zip("odd.zip", "inner.zip" to ByteArray(10), "Party.wbfs" to Fixtures.magic("WBFS"), "Racer.z64" to Fixtures.n64())
        val l = list(f)
        assertEquals(1, l.games.size)
        assertEquals(2, l.problems.size)
        assertTrue(l.problems.any { "inside another archive" in it.second })
        assertTrue(l.problems.any { "Wii" in it.second })
    }

    @Test fun corruptArchiveGivesPlainMessage() {
        val f = File(tmp.root, "broken.zip").apply { writeBytes(ByteArray(300) { (it * 7).toByte() }) }
        try { list(f); fail("expected error") } catch (e: FriendlyException) { assertTrue(e.message!!, "damaged" in e.message!! || "can't read" in e.message!!) }
        val f7 = File(tmp.root, "broken.7z").apply { writeBytes(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 4) + ByteArray(40)) }
        try { list(f7); fail("expected error") } catch (e: FriendlyException) { assertTrue(e.message!!.startsWith("broken.7z")) }
    }

    @Test fun rarWithoutBackendExplains() {
        val f = File(tmp.root, "game.rar").apply { writeBytes(Fixtures.magic("Rar!", 0x1A, 0x07, 0x01, 0x00)) }
        try { list(f); fail("expected error") } catch (e: FriendlyException) { assertTrue(".zip" in e.message!!) }
    }

    @Test fun extractReportsProgressAndCancelLeavesNothing() {
        val big = ByteArray(3 shl 20) { (it % 251).toByte() }
        Fixtures.gameCubeIso().copyInto(big)
        val f = zip("big.zip", "Big.iso" to big)
        var calls = 0; var last = 0L
        val dest = File(tmp.root, "c/Big.iso")
        reader.extract(FileArchiveSource(f), ArchiveKind.ZIP, "Big.iso", dest) { d, t -> calls++; last = d; assertEquals(big.size.toLong(), t); true }
        assertTrue(calls >= 3)
        assertEquals(big.size.toLong(), last)
        assertArrayEquals(big, dest.readBytes())

        val dest2 = File(tmp.root, "d/Big.iso")
        try {
            reader.extract(FileArchiveSource(f), ArchiveKind.ZIP, "Big.iso", dest2) { d, _ -> d < (1 shl 20) }
            fail("expected cancel")
        } catch (e: ArchiveReader.CancelledException) {}
        assertFalse(dest2.exists())
        assertFalse(File(dest2.parentFile, dest2.name + ".part").exists())
    }

    @Test fun missingEntryIsFriendly() {
        val f = zip("one.zip", "a.z64" to Fixtures.n64())
        try { reader.extract(FileArchiveSource(f), ArchiveKind.ZIP, "gone.z64", File(tmp.root, "e/gone.z64")); fail() }
        catch (e: FriendlyException) { assertTrue("Rescan" in e.message!!) }
    }

    @Test fun readStreamsWithoutUnpacking() {
        val f = zip("r.zip", "a.z64" to Fixtures.n64())
        val head = reader.read(FileArchiveSource(f), ArchiveKind.ZIP, "a.z64") { ArchiveReader.readHeader(it, 4) }
        assertEquals("z64", GameFormats.n64Order(head))
    }

    /** Stands in for the native libarchive reader: plays back entries from memory. */
    private class FakeRar(val entries: List<Triple<String, Long, ByteArray>>) : RarBackend {
        override fun forEach(source: ArchiveSource, visit: (String, Long, java.io.InputStream) -> Boolean) {
            for ((n, size, bytes) in entries) if (!visit(n, size, bytes.inputStream())) break
        }
    }

    @Test fun rarBackendListsAndUnpacksGames() {
        val game = Fixtures.n64("v64")
        val rarReader = ArchiveReader(FakeRar(listOf(
            Triple("Games/Racer.v64", game.size.toLong(), game), Triple("notes.txt", 2L, "hi".toByteArray()),
            Triple("Cube (Disc 2).iso", 0x400L, Fixtures.gameCubeIso()))))
        val f = File(tmp.root, "set.rar").apply { writeBytes(Fixtures.magic("Rar!", 0x1A, 0x07, 0x01, 0x00)) }
        val l = rarReader.list(FileArchiveSource(f), ArchiveKind.RAR)
        assertEquals(listOf("Games/Racer.v64" to Console.N64, "Cube (Disc 2).iso" to Console.GAMECUBE), l.games.map { it.path to it.console })
        val out = rarReader.extract(FileArchiveSource(f), ArchiveKind.RAR, "Games/Racer.v64", File(tmp.root, "x/Racer.v64"))
        assertArrayEquals(game, out.readBytes())
    }

    @Test fun shortCopyIsReportedAsIncomplete() {
        val rarReader = ArchiveReader(FakeRar(listOf(Triple("Big.iso", 5000L, ByteArray(1200)))))
        val f = File(tmp.root, "cut.rar").apply { writeBytes(Fixtures.magic("Rar!", 0x1A, 0x07, 0x01, 0x00)) }
        val dest = File(tmp.root, "y/Big.iso")
        try { rarReader.extract(FileArchiveSource(f), ArchiveKind.RAR, "Big.iso", dest); fail("expected error") }
        catch (e: FriendlyException) { assertTrue("incomplete" in e.message!!) }
        assertFalse(dest.exists())
        assertFalse(File(dest.parentFile, "Big.iso.part").exists())
    }
}
