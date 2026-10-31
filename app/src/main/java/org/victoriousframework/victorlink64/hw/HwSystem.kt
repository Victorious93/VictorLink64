package org.victoriousframework.victorlink64.hw

/**
 * Disc-based systems run through the GL ES libretro frontend. Victorious Framework.
 * [coreLib] is the file name under the app's native library dir (arm64-v8a only).
 */
enum class HwSystem(val label: String, val coreLib: String, val dirName: String, val extensions: Set<String>) {
    PSP("PSP", "libppsspp_libretro.so", "psp", setOf("iso", "cso", "pbp")),
    GAMECUBE("GameCube", "libdolphin_libretro.so", "gamecube", setOf("iso", "gcm", "rvz", "ciso"));

    companion object {
        /** GameCube disc header magic, big-endian, at offset 0x1C of a raw .iso/.gcm image. */
        private val GC_MAGIC = byteArrayOf(0xC2.toByte(), 0x33, 0x9F.toByte(), 0x3D)

        val ALL_EXTENSIONS: Set<String> = PSP.extensions + GAMECUBE.extensions

        /**
         * Picks the system for a file. Unambiguous extensions decide alone; a plain ".iso" is GameCube when its
         * first bytes carry the GameCube disc magic, otherwise PSP. [header] is the file's first 32+ bytes, if read.
         */
        fun detect(ext: String, header: ByteArray?): HwSystem? = when (ext.lowercase()) {
            "cso", "pbp" -> PSP
            "gcm", "rvz", "ciso" -> GAMECUBE
            "iso" -> if (header != null && isGameCubeHeader(header)) GAMECUBE else PSP
            else -> null
        }

        fun isGameCubeHeader(h: ByteArray): Boolean =
            h.size >= 0x20 && (0 until 4).all { h[0x1C + it] == GC_MAGIC[it] }
    }
}
