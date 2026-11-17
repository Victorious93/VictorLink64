package org.victoriousframework.victorlink64.controls

import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import org.victoriousframework.victorlink64.ui.PadChip

/** A row of menu chips (SAVE, LOAD, EDIT...) pinned to the top centre; it only covers the chips themselves. */
fun ComponentActivity.chipBar(content: @Composable RowScope.() -> Unit): ComposeView = ComposeView(this).apply {
    setContent {
        Row(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

fun FrameLayout.addChipBar(bar: ComposeView) =
    addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

/** Chip that shows or hides the touch pad while a hardware controller is connected. */
@Composable
fun PadVisibilityChip(session: PadSession) {
    if (session.hardwarePresent && session.settings.hideWithController)
        PadChip(if (session.forcedVisible) "PAD: SHOWN" else "PAD: HIDDEN") { session.toggleForcedVisible() }
}
