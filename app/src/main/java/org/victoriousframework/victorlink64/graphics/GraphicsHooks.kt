package org.victoriousframework.victorlink64.graphics

import android.content.Context
import android.widget.FrameLayout
import org.victoriousframework.victorlink64.hw.HwCore
import org.victoriousframework.victorlink64.hw.HwSystem
import org.victoriousframework.victorlink64.shizuku.ShizukuController
import org.victoriousframework.victorlink64.snes.SnesCore

/** One-line entry points for the game activities, so they stay free of graphics logic. */
object GraphicsHooks {
    private fun display(fileName: String) = fileName.substringBeforeLast('.')

    /** PlayStation 2 has its own picture-size setting (Settings -> PlayStation 2) and no Auto Best session yet, so it gets none. */
    fun forHw(ctx: Context, system: HwSystem, fileName: String, frame: FrameLayout): GraphicsSession? {
        val g = when (system) {
            HwSystem.PSP -> GfxSystem.PSP
            HwSystem.GAMECUBE -> GfxSystem.GAMECUBE
            HwSystem.PS2 -> return null
        }
        val s = GraphicsSession.start(ctx, g, GameKeys.byFile(g, fileName), display(fileName), HwGfxNative) { HwCore.nativeFps() }
        frame.post { s.attach(frame) }
        ShizukuController.onGameStart(ctx, tracked = true)
        return s
    }

    fun forSnes(ctx: Context, fileName: String, frame: FrameLayout): GraphicsSession {
        val s = GraphicsSession.start(ctx, GfxSystem.SNES, GameKeys.byFile(GfxSystem.SNES, fileName), display(fileName), SnesGfxNative) { SnesCore.nativeFps() }
        frame.post { s.attach(frame) }
        ShizukuController.onGameStart(ctx, tracked = true)
        return s
    }
}
