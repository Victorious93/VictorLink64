package org.victoriousframework.victorlink64.graphics

/** Where graphics options are delivered: the native frontend that owns the core. */
interface GfxSink {
    /** Sets a libretro core variable; the core sees it on its next frame. */
    fun setVariable(key: String, value: String)

    /** Selects the shared GL pass effect ([PostFx.id]); [nearest] scales without smoothing. */
    fun setPost(mode: Int, nearest: Boolean)

    /** Forgets everything set for the previous game. */
    fun clear()
}

/** Variable and GL-pass control inside libvl64hw (PSP and GameCube). */
object HwGfxNative : GfxSink {
    init { System.loadLibrary("vl64hw") }

    @JvmStatic external fun nativeSetVariable(key: String, value: String)
    @JvmStatic external fun nativeSetPost(mode: Int, nearest: Boolean)
    @JvmStatic external fun nativeClear()

    override fun setVariable(key: String, value: String) = nativeSetVariable(key, value)
    override fun setPost(mode: Int, nearest: Boolean) = nativeSetPost(mode, nearest)
    override fun clear() = nativeClear()
}

/** GL-pass control inside libvl64snes. bsnes has no graphics variables worth changing. */
object SnesGfxNative : GfxSink {
    init { System.loadLibrary("vl64snes") }

    @JvmStatic external fun nativeSetPost(mode: Int, nearest: Boolean)
    @JvmStatic external fun nativeClear()

    override fun setVariable(key: String, value: String) {}
    override fun setPost(mode: Int, nearest: Boolean) = nativeSetPost(mode, nearest)
    override fun clear() = nativeClear()
}
