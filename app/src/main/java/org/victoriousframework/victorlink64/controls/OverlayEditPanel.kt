package org.victoriousframework.victorlink64.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.victoriousframework.victorlink64.ui.PadChip
import org.victoriousframework.victorlink64.ui.PadCyan
import org.victoriousframework.victorlink64.ui.PadNavy
import org.victoriousframework.victorlink64.ui.PadSteel

/** Layout editor controls shown while the overlay is in edit mode. Navy + cyan only. Victorious Framework. */
@Composable
fun OverlayEditPanel(
    console: Console,
    settings: OverlaySettings,
    selection: EditSelection?,
    onScale: (Float) -> Unit,
    onOpacity: (Float) -> Unit,
    onCommit: () -> Unit,
    onSettingsChanged: () -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val hasSticks = ConsoleLayouts.defs(console).any { it.kind == ControlKind.STICK }
    val hasTriggers = ConsoleLayouts.defs(console).any { it.kind == ControlKind.TRIGGER }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 6.dp).widthIn(max = 460.dp)
            .clip(shape).background(PadNavy.copy(alpha = 0.94f)).border(1.dp, PadCyan.copy(alpha = 0.6f), shape)
            .padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("EDIT CONTROLS", color = PadCyan, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            PadChip(if (tab == 0) "\u25CF ONE" else "ONE") { tab = 0 }
            PadChip(if (tab == 1) "\u25CF ALL" else "ALL") { tab = 1 }
            PadChip("RESET", onReset)
            PadChip("DONE", onDone)
        }
        if (tab == 0) {
            if (selection == null) {
                Text(
                    "Tap a control to select it. Drag to move it; drag the corner dot to resize.",
                    color = PadSteel, fontSize = 12.sp,
                )
            } else {
                Text(selection.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                LabeledSlider("Size", "${(selection.scale * 100).toInt()}%", selection.scale, Placement.MIN_SCALE..Placement.MAX_SCALE, onScale, onCommit)
                LabeledSlider("Opacity", "${(selection.opacity * 100).toInt()}%", selection.opacity, Placement.MIN_OPACITY..1f, onOpacity, onCommit)
            }
        } else {
            var size by remember { mutableIntStateOf((settings.masterScale * 100).toInt()) }
            var opacity by remember { mutableIntStateOf((settings.masterOpacity * 100).toInt()) }
            var deadzone by remember { mutableIntStateOf((settings.stickDeadzone * 100).toInt()) }
            var floating by remember { mutableIntStateOf(if (settings.stickMode == StickMode.FLOATING) 1 else 0) }
            var haptics by remember { mutableIntStateOf(if (settings.haptics) 1 else 0) }
            var analog by remember { mutableIntStateOf(if (settings.analogTriggers) 1 else 0) }
            var hide by remember { mutableIntStateOf(if (settings.hideWithController) 1 else 0) }

            LabeledSlider("All sizes", "$size%", size / 100f, 0.6f..1.6f, { size = (it * 100).toInt(); settings.masterScale = it; onSettingsChanged() }, {})
            LabeledSlider("All opacity", "$opacity%", opacity / 100f, 0.2f..1f, { opacity = (it * 100).toInt(); settings.masterOpacity = it; onSettingsChanged() }, {})
            if (hasSticks) {
                LabeledSlider("Stick dead zone", "$deadzone%", deadzone / 100f, 0f..0.4f, { deadzone = (it * 100).toInt(); settings.stickDeadzone = it; onSettingsChanged() }, {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (hasSticks) PadChip(if (floating == 1) "STICK: FLOATING" else "STICK: FIXED") {
                    floating = 1 - floating
                    settings.stickMode = if (floating == 1) StickMode.FLOATING else StickMode.FIXED
                    onSettingsChanged()
                }
                PadChip(if (haptics == 1) "HAPTICS: ON" else "HAPTICS: OFF") { haptics = 1 - haptics; settings.haptics = haptics == 1; onSettingsChanged() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (hasTriggers) PadChip(if (analog == 1) "TRIGGERS: ANALOG" else "TRIGGERS: DIGITAL") {
                    analog = 1 - analog; settings.analogTriggers = analog == 1; onSettingsChanged()
                }
                PadChip(if (hide == 1) "HIDE WITH PAD: ON" else "HIDE WITH PAD: OFF") {
                    hide = 1 - hide; settings.hideWithController = hide == 1; onSettingsChanged()
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = PadSteel, fontSize = 12.sp, modifier = Modifier.widthIn(min = 92.dp))
        Slider(
            value = current.coerceIn(range.start, range.endInclusive), onValueChange = onChange, onValueChangeFinished = onFinished,
            valueRange = range, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = PadCyan, activeTrackColor = PadCyan, inactiveTrackColor = PadSteel.copy(alpha = 0.35f)),
        )
        Text(value, color = Color.White, fontSize = 12.sp, modifier = Modifier.widthIn(min = 44.dp))
    }
}
