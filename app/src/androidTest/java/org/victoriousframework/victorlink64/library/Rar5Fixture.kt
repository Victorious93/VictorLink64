package org.victoriousframework.victorlink64.library

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Writes a tiny RAR 5 archive in "store" mode (no compression) so the on-device test needs no binary fixture and no
 * proprietary RAR tool. Layout per the public RAR 5 technical note. Synthetic data only. Victorious Framework.
 */
object Rar5Fixture {
    private val SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

    fun build(files: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        out.write(block(type = 1, flags = 0L, body = vint(0L)))
        for ((name, data) in files) {
            val crc = CRC32().apply { update(data) }.value
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val body = ByteArrayOutputStream().apply {
                write(vint(data.size.toLong()))          // data area size
                write(vint(0x4L))                        // file flags: CRC32 present
                write(vint(data.size.toLong()))          // unpacked size
                write(vint(0x81A4L))                     // attributes: unix mode 0100644 (regular file)
                write(le32(crc))                         // data CRC32
                write(vint(0L))                          // compression info: version 0, method 0 (store)
                write(vint(1L))                          // host OS: unix
                write(vint(nameBytes.size.toLong()))
                write(nameBytes)
            }.toByteArray()
            out.write(block(type = 2, flags = 0x2L, body = body))   // 0x2: data area follows
            out.write(data)
        }
        out.write(block(type = 5, flags = 0L, body = vint(0L)))
        return out.toByteArray()
    }

    private fun block(type: Long, flags: Long, body: ByteArray): ByteArray {
        val afterSize = vint(type) + vint(flags) + body
        val sizeAndRest = vint(afterSize.size.toLong()) + afterSize
        val crc = CRC32().apply { update(sizeAndRest) }.value
        return le32(crc) + sizeAndRest
    }

    private fun vint(value: Long): ByteArray {
        val b = ByteArrayOutputStream()
        var v = value
        while (v >= 0x80) { b.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        b.write(v.toInt())
        return b.toByteArray()
    }

    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
