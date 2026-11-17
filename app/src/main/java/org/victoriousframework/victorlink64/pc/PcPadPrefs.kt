package org.victoriousframework.victorlink64.pc

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import org.victoriousframework.victorlink64.controls.ControlsPrefs

/**
 * Size and opacity of the PC Streaming touch pad, stored in the shared overlay settings so every console's
 * on-screen controls use the same values (Victorious Framework).
 */
class PadPrefs(context: Context) {
    private val settings = ControlsPrefs.settings(context)
    var opacity: Float
        get() = settings.masterOpacity
        set(v) { settings.masterOpacity = v }
    var scale: Float
        get() = settings.masterScale
        set(v) { settings.masterScale = v }

    companion object {
        val OPACITIES = floatArrayOf(0.4f, 0.6f, 0.8f, 1f)
        val SCALES = floatArrayOf(0.85f, 1f, 1.15f, 1.3f)
        fun next(steps: FloatArray, cur: Float): Float {
            val i = steps.indexOfFirst { it >= cur - 0.01f }
            return steps[if (i < 0 || i == steps.size - 1) 0 else i + 1]
        }
        fun scaleLabel(s: Float) = when { s < 0.9f -> "S"; s < 1.1f -> "M"; s < 1.2f -> "L"; else -> "XL" }
    }
}

/** One short haptic tick for a fresh touch-pad press. */
fun padHaptic(view: View) {
    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
}
