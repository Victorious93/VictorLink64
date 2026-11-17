package org.victoriousframework.victorlink64.hw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/** Victorious Framework: PS2 disc recognition on synthetic in-memory images (no game data is ever used). */
class Ps2DiscTest {
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    /** A tiny ISO 9660 image: PVD at LBA 16, root directory at LBA 20 holding SYSTEM.CNF;1 at LBA 21. */
    private fun iso(cnf: String?): ByteArray {
        val img = ByteArray(2048 * 24)
        fun put(off: Int, b: ByteArray) = b.copyInto(img, off)
        val pvd = 16 * 2048
        img[pvd] = 1; put(pvd + 1, "CD001".toByteArray()); img[pvd + 6] = 1
        put(pvd + 8, "PLAYSTATION".toByteArray())
        val root = pvd + 156
        img[root] = 34
        put(root + 2, le32(20)); put(root + 10, le32(2048)); img[root + 25] = 2; img[root + 32] = 1
        if (cnf != null) {
            val dir = 20 * 2048
            var p = dir
            for (name in listOf("\u0000", "\u0001")) { img[p] = 34; img[p + 32] = 1; img[p + 33] = name[0].code.toByte(); p += 34 }
            val nm = "SYSTEM.CNF;1"
            img[p] = (33 + nm.length + (if (nm.length % 2 == 0) 1 else 0)).toByte()
            put(p + 2, le32(21)); put(p + 10, le32(cnf.length)); put(p + 32, byteArrayOf(nm.length.toByte())); put(p + 33, nm.toByteArray())
            put(21 * 2048, cnf.toByteArray())
        }
        return img
    }

    private fun raw(iso: ByteArray, dataOffset: Int): ByteArray {
        val sectors = iso.size / 2048
        val out = ByteArray(sectors * 2352)
        for (s in 0 until sectors) {
            out[s * 2352] = 0; for (i in 1..10) out[s * 2352 + i] = 0xFF.toByte()
            iso.copyInto(out, s * 2352 + dataOffset, s * 2048, (s + 1) * 2048)
        }
        return out
    }

    private val ps2Cnf = "BOOT2 = cdrom0:\\SLUS_200.62;1\r\nVER = 1.00\r\nVMODE = NTSC\r\n"
    private val ps1Cnf = "BOOT = cdrom:\\SLUS_000.01;1\r\nTCB = 4\r\nEVENT = 10\r\nSTACK = 801FFF00\r\n"

    @Test fun ps2IsoIsRecognised() {
        assertEquals(ps2Cnf, Ps2Disc.systemCnf(ByteArrayDisc(iso(ps2Cnf))))
        assertTrue(Ps2Disc.isPs2Disc(ByteArrayDisc(iso(ps2Cnf))))
    }

    @Test fun ps1IsoIsNotPs2() {
        assertFalse(Ps2Disc.isPs2Disc(ByteArrayDisc(iso(ps1Cnf))))
    }

    @Test fun isoWithoutSystemCnfIsNotPs2() {
        assertFalse(Ps2Disc.isPs2Disc(ByteArrayDisc(iso(null))))
        assertNull(Ps2Disc.systemCnf(ByteArrayDisc(ByteArray(100_000))))
    }

    @Test fun rawCdSectorsAreRecognised() {
        assertTrue(Ps2Disc.isPs2Disc(ByteArrayDisc(raw(iso(ps2Cnf), 16))))
        assertTrue(Ps2Disc.isPs2Disc(ByteArrayDisc(raw(iso(ps2Cnf), 24))))
        assertFalse(Ps2Disc.isPs2Disc(ByteArrayDisc(raw(iso(ps1Cnf), 24))))
    }

    @Test fun bootLineIsMatchedExactly() {
        assertTrue(Ps2Disc.isPs2BootConfig("boot2=cdrom0:\\X.01;1"))
        assertFalse(Ps2Disc.isPs2BootConfig("BOOT = cdrom:\\X;1"))
        assertFalse(Ps2Disc.isPs2BootConfig("VER = BOOT2"))
    }

    @Test fun truncatedImagesNeverCrash() {
        val full = iso(ps2Cnf)
        for (n in listOf(0, 10, 2048, 16 * 2048, 16 * 2048 + 100, 20 * 2048, 21 * 2048 + 4)) {
            Ps2Disc.isPs2Disc(ByteArrayDisc(full.copyOf(n)))
        }
    }

    /** CISO v1 with 2048-byte blocks; every other block stored raw to exercise both paths. */
    private fun cso(iso: ByteArray): ByteArray {
        val blocks = iso.size / 2048
        val bodies = (0 until blocks).map { b ->
            val src = iso.copyOfRange(b * 2048, (b + 1) * 2048)
            if (b % 2 == 0) {
                val d = Deflater(9, true); d.setInput(src); d.finish()
                val buf = ByteArray(4096); val n = d.deflate(buf); d.end()
                buf.copyOf(n) to false
            } else src to true
        }
        val headerSize = 24 + 4 * (blocks + 1)
        val out = ByteArrayOutputStream()
        out.write("CISO".toByteArray()); out.write(le32(24)); out.write(le32(iso.size)); out.write(le32(0))
        out.write(le32(2048)); out.write(byteArrayOf(1, 0, 0, 0))
        var pos = headerSize
        for ((body, rawBlock) in bodies) { out.write(le32(if (rawBlock) pos or Int.MIN_VALUE else pos)); pos += body.size }
        out.write(le32(pos))
        for ((body, _) in bodies) out.write(body)
        return out.toByteArray()
    }

    @Test fun csoIsReadThroughItsBlocks() {
        val ps2 = Ps2Disc.CsoReader.open(ByteArrayDisc(cso(iso(ps2Cnf))))
        assertNotNull(ps2)
        assertTrue(Ps2Disc.isPs2Disc(ps2!!))
        val ps1 = Ps2Disc.CsoReader.open(ByteArrayDisc(cso(iso(ps1Cnf))))
        assertFalse(Ps2Disc.isPs2Disc(ps1!!))
    }

    @Test fun nonCsoIsRejected() {
        assertNull(Ps2Disc.CsoReader.open(ByteArrayDisc(ByteArray(64))))
        assertNull(Ps2Disc.CsoReader.open(ByteArrayDisc("CISO".toByteArray() + ByteArray(20))))
    }

    private fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun be64(v: Long) = be32(v shr 32) + be32(v and 0xFFFFFFFFL)

    private fun chd(logical: Long, vararg tags: String): ByteArray {
        val header = ByteArray(124)
        "MComprHD".toByteArray().copyInto(header)
        be32(124).copyInto(header, 8); be32(5).copyInto(header, 12)
        be64(logical).copyInto(header, 32)
        val metaStart = 124L
        if (tags.isNotEmpty()) be64(metaStart).copyInto(header, 48)
        val out = ByteArrayOutputStream(); out.write(header)
        tags.forEachIndexed { i, t ->
            val len = 4
            val next = if (i == tags.lastIndex) 0L else metaStart + (i + 1) * (16L + len)
            out.write(t.toByteArray()); out.write(1); out.write(byteArrayOf(0, 0, len.toByte())); out.write(be64(next)); out.write(ByteArray(len))
        }
        return out.toByteArray()
    }

    @Test fun chdKindsAndPs2Rule() {
        val dvdBig = Ps2Disc.chdInfo(ByteArrayDisc(chd(4_000_000_000L, "DVD ")))!!
        assertEquals(Ps2Disc.ChdKind.DVD, dvdBig.kind); assertTrue(Ps2Disc.chdLooksPs2(dvdBig))
        val dvdSmall = Ps2Disc.chdInfo(ByteArrayDisc(chd(900_000_000L, "DVD ")))!!
        assertFalse("small DVD CHDs are ambiguous with PSP and are not claimed", Ps2Disc.chdLooksPs2(dvdSmall))
        val cd = Ps2Disc.chdInfo(ByteArrayDisc(chd(700_000_000L, "CHT2", "CHT2")))!!
        assertEquals(Ps2Disc.ChdKind.CD, cd.kind); assertTrue(Ps2Disc.chdLooksPs2(cd))
        val none = Ps2Disc.chdInfo(ByteArrayDisc(chd(5_000_000_000L)))!!
        assertFalse(Ps2Disc.chdLooksPs2(none))
        assertNull(Ps2Disc.chdInfo(ByteArrayDisc(ByteArray(200))))
    }

    @Test fun cueSheetFilesAreFoundAndNormalised() {
        val cue = "FILE \"Game (Track 1).bin\" BINARY\r\n  TRACK 01 MODE2/2352\r\n    INDEX 01 00:00:00\r\n" +
            "FILE \"sub\\Game (Track 2).bin\" BINARY\r\n  TRACK 02 AUDIO\r\n    INDEX 01 00:00:00\r\nFILE plain.bin BINARY\r\n"
        assertEquals(listOf("Game (Track 1).bin", "Game (Track 2).bin", "plain.bin"), Ps2Disc.cueFiles(cue))
        val fixed = Ps2Disc.rewriteCue(cue)
        assertEquals(listOf("Game (Track 1).bin", "Game (Track 2).bin", "plain.bin"), Ps2Disc.cueFiles(fixed))
        assertTrue(fixed.contains("FILE \"plain.bin\" BINARY"))
        assertFalse(fixed.contains("sub"))
        assertTrue(fixed.contains("TRACK 02 AUDIO"))
        assertEquals(emptyList<String>(), Ps2Disc.cueFiles("REM nothing here"))
    }

    @Test fun systemKnowsItsExtensions() {
        for (e in listOf("iso", "chd", "cso", "cue", "bin")) assertTrue(e in HwSystem.PS2.extensions)
        assertTrue("chd" in HwSystem.ALL_EXTENSIONS)
        assertEquals("liblrps2_libretro.so", HwSystem.PS2.coreLib)
        assertEquals("ps2", HwSystem.PS2.dirName)
        assertEquals("PlayStation 2", HwSystem.PS2.label)
    }
}
