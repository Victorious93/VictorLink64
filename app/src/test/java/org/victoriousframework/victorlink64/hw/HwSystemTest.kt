package org.victoriousframework.victorlink64.hw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HwSystemTest {
    private fun gcHeader() = ByteArray(0x40).also {
        it[0x1C] = 0xC2.toByte(); it[0x1D] = 0x33; it[0x1E] = 0x9F.toByte(); it[0x1F] = 0x3D
    }

    @Test fun pspOnlyExtensions() {
        assertEquals(HwSystem.PSP, HwSystem.detect("cso", null))
        assertEquals(HwSystem.PSP, HwSystem.detect("PBP", null))
    }

    @Test fun gameCubeOnlyExtensions() {
        for (e in listOf("gcm", "rvz", "ciso")) assertEquals(HwSystem.GAMECUBE, HwSystem.detect(e, null))
    }

    @Test fun isoUsesDiscMagic() {
        assertEquals(HwSystem.GAMECUBE, HwSystem.detect("iso", gcHeader()))
        assertEquals(HwSystem.PSP, HwSystem.detect("iso", ByteArray(0x40)))
        assertEquals(HwSystem.PSP, HwSystem.detect("iso", null))
        assertEquals(HwSystem.PSP, HwSystem.detect("iso", ByteArray(4)))
    }

    @Test fun unknownExtension() = assertNull(HwSystem.detect("z64", null))
}

class HwGeometryTest {
    @Test fun fitsWideWindow() = assertEquals(Pair(960, 720), fitAspect(1600, 720, 4f / 3f))
    @Test fun fitsTallWindow() = assertEquals(Pair(720, 540), fitAspect(720, 1600, 4f / 3f))
    @Test fun dpadAxis() {
        assertEquals(0x7FFF, dpadToAxis(1 shl HwCore.RIGHT, HwCore.LEFT, HwCore.RIGHT))
        assertEquals(-0x7FFF, dpadToAxis(1 shl HwCore.LEFT, HwCore.LEFT, HwCore.RIGHT))
        assertEquals(0, dpadToAxis(0, HwCore.LEFT, HwCore.RIGHT))
    }
}
