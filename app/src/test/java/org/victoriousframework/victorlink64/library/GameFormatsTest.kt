package org.victoriousframework.victorlink64.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameFormatsTest {
    private fun console(name: String, header: ByteArray?) = (GameFormats.detect(name, header, header?.size?.toLong() ?: -1) as? Detection.Game)?.console

    @Test fun cartridgesByExtension() {
        assertEquals(Console.N64, console("Mario.z64", null))
        assertEquals(Console.N64, console("Mario.v64", null))
        assertEquals(Console.N64, console("Mario.N64", null))
        assertEquals(Console.SNES, console("Zelda.sfc", null))
        assertEquals(Console.SNES, console("Zelda.SMC", null))
    }

    @Test fun n64ByteOrdersSniffed() {
        for (o in listOf("z64", "v64", "n64")) assertEquals(o, GameFormats.n64Order(Fixtures.n64(o)))
        // Mis-named file: the header wins over the extension.
        assertEquals(Console.N64, console("game.bin", Fixtures.n64("v64")))
    }

    @Test fun snesHeaderSniffed() {
        assertTrue(GameFormats.looksLikeSnes(Fixtures.snes()))
        assertTrue(GameFormats.looksLikeSnes(Fixtures.snes(copierHeader = true)))
        assertEquals(Console.SNES, console("cart.bin", Fixtures.snes()))
        assertTrue(!GameFormats.looksLikeSnes(ByteArray(0x8000)))
    }

    @Test fun isoSplitsGameCubeAndPsp() {
        assertEquals(Console.GAMECUBE, console("Game.iso", Fixtures.gameCubeIso()))
        assertEquals(Console.PSP, console("Game.iso", Fixtures.iso9660("PSP GAME")))
        assertTrue(GameFormats.detect("Game.iso", Fixtures.iso9660("PLAYSTATION")) is Detection.Unsupported)
        assertTrue(GameFormats.detect("Game.iso", ByteArray(0x9000)) is Detection.Unsupported)
        // Unread header keeps the old behaviour (PSP).
        assertEquals(Console.PSP, console("Game.iso", null))
    }

    @Test fun compressedDiscFormats() {
        assertEquals(Console.PSP, console("a.cso", Fixtures.pspCso()))
        assertEquals(Console.GAMECUBE, console("a.ciso", Fixtures.gcCiso()))
        assertEquals(Console.GAMECUBE, console("a.rvz", Fixtures.magic("RVZ", 1)))
        assertEquals(Console.GAMECUBE, console("a.wia", Fixtures.magic("WIA", 1)))
        assertEquals(Console.GAMECUBE, console("a.gcz", Fixtures.magic("", 0x01, 0xC0, 0x0B, 0xB1)))
        assertEquals(Console.PSP, console("a.pbp", Fixtures.magic("", 0x00, 0x50, 0x42, 0x50)))
        assertEquals(Console.PSP, console("a.chd", Fixtures.magic("MComprHD")))
        assertEquals(Console.PSP, console("a.zso", Fixtures.magic("ZISO")))
        assertEquals(Console.GAMECUBE, console("a.gcm", null))
    }

    @Test fun unsupportedFormatsGetPlainReasons() {
        val wbfs = GameFormats.detect("Party.wbfs", Fixtures.magic("WBFS"))
        assertTrue(wbfs is Detection.Unsupported && "Wii" in wbfs.reason)
        assertTrue(GameFormats.detect("Party.iso", Fixtures.wiiIso()) is Detection.Unsupported)
        val cue = GameFormats.detect("Racer.cue", null)
        assertTrue(cue is Detection.Unsupported && "CD image" in cue.reason)
        assertTrue(GameFormats.detect("notes.txt", null) == Detection.NotAGame)
        assertTrue(GameFormats.detect("random.bin", ByteArray(512)) == Detection.NotAGame)
    }

    @Test fun archiveKinds() {
        assertEquals(ArchiveKind.ZIP, GameFormats.archiveKind("a.ZIP"))
        assertEquals(ArchiveKind.SEVEN_Z, GameFormats.archiveKind("a.7z"))
        assertEquals(ArchiveKind.RAR, GameFormats.archiveKind("a.rar"))
        assertEquals(ArchiveKind.TAR_GZ, GameFormats.archiveKind("a.tar.gz"))
        assertEquals(ArchiveKind.TAR_GZ, GameFormats.archiveKind("a.tgz"))
        assertEquals(ArchiveKind.TAR_XZ, GameFormats.archiveKind("a.tar.xz"))
        assertEquals(ArchiveKind.GZIP, GameFormats.archiveKind("a.z64.gz"))
        assertEquals(ArchiveKind.XZ, GameFormats.archiveKind("a.iso.xz"))
        assertNull(GameFormats.archiveKind("a.iso"))
        assertEquals(ArchiveKind.ZIP, GameFormats.archiveKindByMagic(Fixtures.magic("PK", 3, 4)))
        assertEquals(ArchiveKind.SEVEN_Z, GameFormats.archiveKindByMagic(Fixtures.magic("7z", 0xBC, 0xAF, 0x27, 0x1C)))
        assertEquals(ArchiveKind.RAR, GameFormats.archiveKindByMagic(Fixtures.magic("Rar!", 0x1A, 0x07, 0x00)))
        assertNull(GameFormats.archiveKindByMagic(Fixtures.n64()))
    }

    @Test fun multiDiscNames() {
        assertEquals(2, GameFormats.discNumber("Tales (USA) (Disc 2).iso"))
        assertEquals(1, GameFormats.discNumber("Tales [Disc 1 of 2].rvz"))
        assertEquals(3, GameFormats.discNumber("Saga CD3.cso"))
        assertNull(GameFormats.discNumber("Discworld.iso"))
        assertEquals("Tales (USA)", GameFormats.baseTitle("dir/Tales (USA) (Disc 2).iso"))
        assertEquals("Mario", GameFormats.baseTitle("Mario.tar.gz"))
        assertEquals("game.z64", GameFormats.innerNameOfCompressed("game.z64.gz"))
    }
}
