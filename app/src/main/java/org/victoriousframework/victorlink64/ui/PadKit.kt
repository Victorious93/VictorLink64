package org.victoriousframework.victorlink64.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared brand colours and menu chips for the emulator screens. Victorious Framework.
 * Brand rule: action/face buttons use navy + cyan-blue, never red. The controls themselves are drawn by
 * `controls.TouchOverlayView`.
 */
val PadCyan = Color(0xFF3FD0FF)
val PadNavy = Color(0xFF0A1128)
val PadSteel = Color(0xFF8FA3C8)

/** Small pill for menu actions (save/load/edit) along the top edge. */
@Composable
fun PadChip(label: String, onTap: () -> Unit) =
    Text(
        label, color = PadCyan, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.8.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(PadNavy.copy(alpha = .7f))
            .border(1.dp, PadCyan.copy(alpha = .5f), RoundedCornerShape(50))
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); onTap() } }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
