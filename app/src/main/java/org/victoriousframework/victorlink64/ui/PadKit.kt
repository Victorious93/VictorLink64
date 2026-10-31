package org.victoriousframework.victorlink64.ui

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared look for the on-screen controllers (SNES, PSP, GameCube). Victorious Framework.
 * Brand rule: action/face buttons use navy + cyan-blue, never red.
 */
val PadCyan = Color(0xFF3FD0FF)
val PadNavy = Color(0xFF0A1128)
val PadSteel = Color(0xFF8FA3C8)

/** User-tunable overlay opacity and size, persisted per device. */
class PadPrefs(context: Context) {
    private val sp = context.getSharedPreferences("vl64_pad", Context.MODE_PRIVATE)
    var opacity: Float
        get() = sp.getFloat("opacity", 0.8f)
        set(v) = sp.edit().putFloat("opacity", v).apply()
    var scale: Float
        get() = sp.getFloat("scale", 1f)
        set(v) = sp.edit().putFloat("scale", v).apply()

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

/** Bits newly set in [new] relative to [old]; used to fire one haptic tick per fresh press. */
fun newlyPressed(old: Int, new: Int): Int = new and old.inv()

fun padHaptic(view: View) {
    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
}

/**
 * One controller button: translucent glassy fill, crisp rim, and a quick shrink + glow while held.
 * Purely visual; hit testing is done by the pad's single multi-touch handler.
 */
@Composable
fun PadFace(
    label: String,
    down: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    width: Dp = size,
    accent: Color = PadCyan,
    fontSize: Int = 16,
) {
    val press by animateFloatAsState(if (down) 1f else 0f, tween(70), label = "press")
    Box(
        modifier.size(width, size)
            .graphicsLayer { val s = 1f - 0.08f * press; scaleX = s; scaleY = s }
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        lerpC(PadNavy.copy(alpha = .55f), accent.copy(alpha = .85f), press),
                        lerpC(PadNavy.copy(alpha = .80f), accent.copy(alpha = .55f), press),
                    )
                )
            )
            .border(if (down) 2.5.dp else 1.5.dp, accent.copy(alpha = if (down) 1f else .75f), shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = fontSize.sp,
            style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = .6f), blurRadius = 4f))
        )
    }
}

private fun lerpC(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t
)

/** Small pill for menu actions (save/load/settings) along the top edge. */
@Composable
fun PadChip(label: String, onTap: () -> Unit) =
    Text(
        label, color = PadCyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.8.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(PadNavy.copy(alpha = .7f))
            .border(1.dp, PadCyan.copy(alpha = .5f), RoundedCornerShape(50))
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); onTap() } }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
