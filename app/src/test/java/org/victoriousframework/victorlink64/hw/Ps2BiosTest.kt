package org.victoriousframework.victorlink64.hw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/** Victorious Framework: BIOS checks on synthetic images that only imitate the PS2 ROM directory layout. */
class Ps2BiosTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun le32(b: ByteArray, o: Int, v: Int) { for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte() }

    /** Directory entries: (name, size). The table sits at [tableAt]; file contents are implied by the running sizes. */
    private fun bios(romver: String = "0160AC20040614", size: Int = 4 * 1024 * 1024, tableAt: Int = 0x200,
                     entries: List<Pair<String, Int>> = listOf("RESET" to 0x100, "ROMDIR" to 0x40, "ROMVER" to 0x10)): ByteArray {
        val img = ByteArray(size)
        var p = tableAt
        for ((name, sz) in entries) {
            name.toByteArray().copyInto(img, p); le32(img, p + 12, sz); p += 16
        }
        var off = 0
        for ((name, sz) in entries) {
            if (name == "ROMVER") romver.toByteArray().copyInto(img, off)
            off += if (sz % 16 == 0) sz else (sz + 16) and 0xFFFFFFF0.toInt()
        }
        return img
    }

    @Test fun readsRegionVersionAndDate() {
        val info = Ps2Bios.inspect(ByteArrayDisc(bios()))!!
        assertEquals("USA", info.region); assertEquals("01.60", info.version)
        assertEquals("14/06/2004", info.date); assertEquals("Console", info.console)
        assertEquals("USA v01.60 (14/06/2004) · Console", info.description)
    }

    @Test fun regionsFollowTheVersionLetter() {
        fun region(c: Char) = Ps2Bios.inspect(ByteArrayDisc(bios("0200${c}C20060101")))!!.region
        assertEquals("Japan", region('J')); assertEquals("Europe", region('E')); assertEquals("Asia", region('H'))
    }

    @Test fun tableMayStartLateInTheImage() {
        assertNotNull(Ps2Bios.inspect(ByteArrayDisc(bios(tableAt = 0x1F0010))))
    }

    @Test fun sizeAndContentDecideWhatIsABios() {
        assertNull("too small", Ps2Bios.inspect(ByteArrayDisc(bios(size = 2 * 1024 * 1024))))
        assertNull("too big", Ps2Bios.inspect(ByteArrayDisc(bios(size = 9 * 1024 * 1024))))
        assertNull("zeros", Ps2Bios.inspect(ByteArrayDisc(ByteArray(4 * 1024 * 1024))))
        assertNull("no ROMVER entry", Ps2Bios.inspect(ByteArrayDisc(bios(entries = listOf("RESET" to 0x100, "ROMDIR" to 0x40)))))
        assertNull("unreadable version", Ps2Bios.inspect(ByteArrayDisc(bios(romver = "\u0001\u0002\u0003\u0004\u0005\u0006\u0007\u0008\u0009\u000a\u000b\u000c\u000d\u000e"))))
        val noise = ByteArray(4 * 1024 * 1024).also { java.util.Random(7).nextBytes(it) }
        assertNull("random data", Ps2Bios.inspect(ByteArrayDisc(noise)))
    }

    @Test fun importKeepsOnlyRealBiosFiles() {
        val dir = tmp.newFolder("bios")
        val ok = Ps2Bios.import(dir, "scph10000.bin", ByteArrayInputStream(bios()))
        assertTrue(ok is Ps2Bios.Outcome.Added)
        assertTrue(File(dir, "scph10000.bin").exists())

        val bad = Ps2Bios.import(dir, "notes.bin", ByteArrayInputStream(ByteArray(4 * 1024 * 1024)))
        assertTrue(bad is Ps2Bios.Outcome.Skipped)
        assertFalse(File(dir, "notes.bin").exists())

        val small = Ps2Bios.import(dir, "ps1.bin", ByteArrayInputStream(ByteArray(512 * 1024)))
        assertTrue((small as Ps2Bios.Outcome.Skipped).reason.contains("too small"))

        val huge = Ps2Bios.import(dir, "huge.iso", ByteArrayInputStream(ByteArray(9 * 1024 * 1024)))
        assertTrue((huge as Ps2Bios.Outcome.Skipped).reason.contains("bigger"))

        assertEquals(listOf("scph10000.bin"), dir.listFiles()!!.map { it.name }.sorted())
        assertEquals(listOf("scph10000.bin"), Ps2Bios.list(dir).map { it.file.name })
    }

    @Test fun sidecarFilesAreCopiedAsTheyAre() {
        val dir = tmp.newFolder("bios2")
        val r = Ps2Bios.import(dir, "scph39001.nvm", ByteArrayInputStream(ByteArray(1024) { 3 }))
        assertEquals(Ps2Bios.Outcome.Sidecar("scph39001.nvm"), r)
        assertEquals(1024L, File(dir, "scph39001.nvm").length())
        assertTrue("a sidecar is not a BIOS", Ps2Bios.list(dir).isEmpty())
    }

    @Test fun namesAreMadeSafe() {
        assertEquals("scph_10000_.bin", Ps2Bios.safeName("scph 10000?.bin"))
        assertEquals("evil.bin", Ps2Bios.safeName("../../evil.bin"))
        assertEquals("evil.bin", Ps2Bios.safeName("a\\b\\evil.bin"))
        assertEquals("bios.bin", Ps2Bios.safeName("..."))
        val dir = tmp.newFolder("bios3")
        Ps2Bios.import(dir, "../../escape.bin", ByteArrayInputStream(bios()))
        assertEquals(listOf("escape.bin"), dir.listFiles()!!.map { it.name })
        assertFalse(File(dir.parentFile!!.parentFile!!, "escape.bin").exists())
    }

    @Test fun choosePrefersTheUsersPickThenTheFirst() {
        val dir = tmp.newFolder("bios4")
        Ps2Bios.import(dir, "b.bin", ByteArrayInputStream(bios("0200EC20060101")))
        Ps2Bios.import(dir, "a.bin", ByteArrayInputStream(bios("0160AC20040614")))
        val all = Ps2Bios.list(dir)
        assertEquals(listOf("a.bin", "b.bin"), all.map { it.file.name })
        assertEquals("a.bin", Ps2Bios.choose(all, null)!!.file.name)
        assertEquals("b.bin", Ps2Bios.choose(all, "b.bin")!!.file.name)
        assertEquals("a.bin", Ps2Bios.choose(all, "gone.bin")!!.file.name)
        assertNull(Ps2Bios.choose(emptyList(), "b.bin"))
    }

    @Test fun biosFolderIsWhereTheCoreLooks() {
        assertEquals(File("/data/files/ps2/system/pcsx2/bios"), Ps2Bios.biosDir(File("/data/files")))
    }
}
