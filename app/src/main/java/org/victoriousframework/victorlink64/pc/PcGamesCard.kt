package org.victoriousframework.victorlink64.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.victoriousframework.victorlink64.ui.Muted
import org.victoriousframework.victorlink64.ui.NeonBlue
import org.victoriousframework.victorlink64.ui.PlayBadge
import org.victoriousframework.victorlink64.ui.clip16

/** Victorious Framework: Library entry for PC Games, styled like the Chess / Zombies cards. */
@Composable
fun PcGamesCard() {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip16().background(NeonBlue.copy(alpha = .12f))
            .border(1.dp, NeonBlue, RoundedCornerShape(16.dp))
            .clickable { PcGamesActivity.launch(ctx) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Computer, null, tint = NeonBlue, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("PC Games", color = Color.White, fontWeight = FontWeight.Bold)
            Text("Stream games from your own PC over Wi-Fi", color = Muted, fontSize = 12.sp)
        }
        PlayBadge("Open PC Games")
    }
}
