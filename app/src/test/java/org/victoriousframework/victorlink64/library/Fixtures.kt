package org.victoriousframework.victorlink64.library

/** Tiny synthetic game-like byte arrays: just enough header for detection. Never real games. Victorious Framework. */
object Fixtures {
    fun n64(order: String = "z64", size: Int = 4096): ByteArray = ByteArray(size).also {
        val m = when (order) { "z64" -> intArrayOf(0x80, 0x37, 0x12, 0x40); "v64" -> intArrayOf(0x37, 0x80, 0x40, 0x12); else -> intArrayOf(0x40, 0x12, 0x37, 0x80) }
        m.forEachIndexed { i, b -> it[i] = b.toByte() }
        for (i in 0x40 until size) it[i] = (i * 31).toByte()
    }

    /** 32 KiB LoROM with a valid-looking internal header at 0x7FC0. */
    fun snes(copierHeader: Boolean = false): ByteArray {
        val off = if (copierHeader) 512 else 0
        val b = ByteArray(0x8000 + off)
        val o = off + 0x7FC0
        "VL64 TEST CART       ".toByteArray(Charsets.US_ASCII).copyInto(b, o)
        b[o + 0x15] = 0x20
        val checksum = 0x1234; val complement = checksum xor 0xFFFF
        b[o + 0x1C] = (complement and 0xFF).toByte(); b[o + 0x1D] = (complement shr 8).toByte()
        b[o + 0x1E] = (checksum and 0xFF).toByte(); b[o + 0x1F] = (checksum shr 8).toByte()
        return b
    }

    fun gameCubeIso(size: Int = 0x400): ByteArray = ByteArray(size).also {
        "GVLE01".toByteArray().copyInto(it)
        intArrayOf(0xC2, 0x33, 0x9F, 0x3D).forEachIndexed { i, v -> it[0x1C + i] = v.toByte() }
    }

    fun wiiIso(): ByteArray = ByteArray(0x400).also { intArrayOf(0x5D, 0x1C, 0x9E, 0xA3).forEachIndexed { i, v -> it[0x18 + i] = v.toByte() } }

    fun iso9660(systemId: String, size: Int = 0x8800): ByteArray = ByteArray(size).also {
        it[0x8000] = 1
        "CD001".toByteArray().copyInto(it, 0x8001)
        systemId.padEnd(32).toByteArray(Charsets.US_ASCII).copyInto(it, 0x8008)
    }

    fun magic(text: String, vararg tail: Int, size: Int = 64): ByteArray = ByteArray(size).also {
        text.toByteArray(Charsets.ISO_8859_1).copyInto(it)
        tail.forEachIndexed { i, v -> it[text.length + i] = v.toByte() }
    }

    fun pspCso(): ByteArray = magic("CISO", 0x18, 0, 0, 0)
    fun gcCiso(): ByteArray = magic("CISO", 0x00, 0x00, 0x20, 0x00)
}
