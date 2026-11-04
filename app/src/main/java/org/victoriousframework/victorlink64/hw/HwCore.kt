package org.victoriousframework.victorlink64.hw

import android.view.Surface

/** JNI bridge to the GL ES libretro frontend (libvl64hw) driving the PPSSPP and Dolphin cores. Victorious Framework. */
object HwCore {
    init { System.loadLibrary("vl64hw") }

    // RetroPad bit ids (libretro RETRO_DEVICE_ID_JOYPAD_*)
    const val B = 0; const val Y = 1; const val SELECT = 2; const val START = 3
    const val UP = 4; const val DOWN = 5; const val LEFT = 6; const val RIGHT = 7
    const val A = 8; const val X = 9; const val L = 10; const val R = 11; const val L2 = 12; const val R2 = 13

    @JvmStatic external fun nativeLoad(corePath: String, romPath: String, systemDir: String, saveDir: String): Boolean
    @JvmStatic external fun nativeSampleRate(): Double
    @JvmStatic external fun nativeFps(): Double
    @JvmStatic external fun nativeAspect(): Float
    @JvmStatic external fun nativeRunFrame(audioOut: ShortArray): Int
    @JvmStatic external fun nativeSetButtons(mask: Int)
    @JvmStatic external fun nativeSetAnalog(x: Int, y: Int)
    @JvmStatic external fun nativeReset()
    @JvmStatic external fun nativeSaveState(): ByteArray?
    @JvmStatic external fun nativeLoadState(data: ByteArray): Boolean
    @JvmStatic external fun nativeSetSurface(surface: Surface?)
    @JvmStatic external fun nativeUnload()
}
