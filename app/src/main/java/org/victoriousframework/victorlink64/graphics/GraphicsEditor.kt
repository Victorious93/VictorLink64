package org.victoriousframework.victorlink64.graphics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.victoriousframework.victorlink64.ui.Muted
import org.victoriousframework.victorlink64.ui.NavyCard
import org.victoriousframework.victorlink64.ui.NeonBlue
import org.victoriousframework.victorlink64.ui.ToggleRow

@Composable
internal fun SectionTitle(text: String) {
    Text(text, color = NeonBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = NavyCard, labelColor = Color.White,
                    selectedContainerColor = NeonBlue.copy(alpha = 0.28f), selectedLabelColor = NeonBlue,
                ),
            )
        }
    }
}

@Composable
private fun Option(title: String, supported: Boolean, note: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        if (supported) content()
        if (note != null) Text(if (supported) note else "Not available: $note", color = Muted, fontSize = 12.sp)
        if (!supported && note == null) Text("Not available for this emulator.", color = Muted, fontSize = 12.sp)
    }
}

/** Editor for one set of picture choices; controls the emulator cannot honour are explained, not hidden. */
@Composable
fun SettingsEditor(system: GfxSystem, value: GraphicsSettings, onChange: (GraphicsSettings) -> Unit) {
    val caps = Presets.capabilities(system)
    val v = caps.sanitize(value)
    Column {
        Option("Quick presets", true, "Fills in the choices below. Auto Best Settings walks this same list.") {
            ChipRow(Presets.ladder(system), selected = Presets.ladder(system).firstOrNull { caps.sanitize(it.settings) == v }, label = { it?.name.orEmpty() }) {
                if (it != null) onChange(caps.sanitize(it.settings))
            }
        }
        if (caps.resolutions.size > 1) Option("Internal resolution", true, caps.notes["resolution"]) {
            ChipRow(caps.resolutions, v.resolution, { it.label }) { onChange(v.copy(resolution = it)) }
        } else caps.notes["resolution"]?.let { Text(it, color = Muted, fontSize = 12.sp) }
        Option("Texture filtering", caps.textureFilter, caps.notes["textureFilter"]) {
            ChipRow(TexFilter.entries, v.textureFilter, { it.label }) { onChange(v.copy(textureFilter = it)) }
        }
        Option("Anisotropic filtering", caps.anisotropy, caps.notes["anisotropy"]) {
            ChipRow(GraphicsSettings.ANISO_STEPS.toList(), v.anisotropy, { if (it == 1) "Off" else "${it}x" }) { onChange(v.copy(anisotropy = it)) }
        }
        Option("Anti-aliasing (MSAA)", caps.msaa, caps.notes["msaa"] ?: if (caps.msaa) "Used where the phone and core support it." else null) {
            ChipRow(GraphicsSettings.MSAA_STEPS.toList(), v.msaa, { if (it == 0) "Off" else "${it}x" }) { onChange(v.copy(msaa = it)) }
        }
        Option("Texture upscaling", caps.textureEnhance, caps.notes["textureEnhance"]) {
            ChipRow(TexEnhance.entries, v.textureEnhance, { it.label }) { onChange(v.copy(textureEnhance = it)) }
        }
        Option("Screen effect", true, v.postFx.hint + (caps.notes["postFx"]?.let { "  $it" } ?: "")) {
            ChipRow(caps.postFx, v.postFx, { it.label }) { onChange(v.copy(postFx = it)) }
        }
        if (caps.widescreen) ToggleRow("Widescreen hack", v.widescreen) { onChange(v.copy(widescreen = it)) }
        else caps.notes["widescreen"]?.let { Text("Widescreen hack: $it", color = Muted, fontSize = 12.sp) }
        Spacer(Modifier.height(4.dp))
    }
}
