package org.victoriousframework.victorlink64.library

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import org.victoriousframework.victorlink64.ui.Game
import org.victoriousframework.victorlink64.ui.Muted
import org.victoriousframework.victorlink64.ui.Navy
import org.victoriousframework.victorlink64.ui.NavyCard
import org.victoriousframework.victorlink64.ui.NeonBlue
import org.victoriousframework.victorlink64.ui.VictorTheme

/** Library-row subtitle: "GameCube · RVZ · in 7z · 1.2 GB". Victorious Framework. */
fun gameSubtitle(g: Game): String = buildList {
    add(g.systemLabel)
    if (g.format.isNotEmpty()) add(g.format.uppercase())
    g.archive?.let { add("in ${it.label}") }
    add(formatBytes(g.size))
}.joinToString(" · ")

/** Progress dialog while an archived game is unpacked for the first time. */
@Composable
fun UnpackDialog() {
    val st = ImportProgress.state ?: return
    AlertDialog(
        onDismissRequest = {},
        containerColor = NavyCard,
        title = { Text("Unpacking ${st.title}", color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (st.total > 0) LinearProgressIndicator(progress = { (st.done.toFloat() / st.total).coerceIn(0f, 1f) },
                    color = NeonBlue, trackColor = Navy, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(color = NeonBlue, trackColor = Navy, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(if (st.total > 0) "${formatBytes(st.done)} of ${formatBytes(st.total)}" else formatBytes(st.done), color = Color.White)
                Text("First time only — next time it starts straight away.", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { ImportProgress.cancelled = true }) { Text("Cancel", color = NeonBlue) } }
    )
}

@Composable
fun ProblemsDialog(problems: List<Pair<String, String>>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = NavyCard,
        title = { Text("Files that couldn't be added", color = Color.White) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(problems) { (name, why) ->
                    Column {
                        Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(why, color = Muted, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("OK", color = NeonBlue) } }
    )
}

/** Settings › Game storage: unpacked-game cache with sizes, plus individually added files. */
class StorageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VictorTheme { StorageScreen(onBack = { finish() }) } }
    }
}

@Composable
fun StorageScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cache = remember { GameLauncher.cache(ctx) }
    var version by remember { mutableIntStateOf(0) }
    val entries = remember(version) { cache.entries() }
    val total = remember(version) { cache.totalBytes() }
    val free = remember(version) { cache.root.usableSpace }
    val added = remember(version) { LibraryScanner.addedFiles(ctx) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Navy).statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Game storage", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Done", color = NeonBlue) }
        }
        Spacer(Modifier.height(8.dp))
        Text("Games inside archives are unpacked once and kept here so they start fast next time. " +
            "Deleting one only frees space — it's unpacked again the next time you play it.", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        var capGb by remember { mutableIntStateOf(GameLauncher.capGb(ctx)) }
        Text("Keep unpacked games under", color = Color.White, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GameLauncher.CAP_CHOICES_GB.forEach { gb ->
                FilterChip(
                    selected = capGb == gb,
                    onClick = { capGb = gb; GameLauncher.setCapGb(ctx, gb); cache.trimToCap(GameLauncher.capBytes(ctx)); version++ },
                    label = { Text(if (gb == 0) "No limit" else "$gb GB") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NeonBlue, selectedLabelColor = Navy, labelColor = Color.White)
                )
            }
        }
        Text("When the limit is reached, the games you played longest ago are removed first. They unpack again when you play them.",
            color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        SectionHeader("Unpacked games", "${entries.size} · ${formatBytes(total)} used · ${formatBytes(free)} free on phone")
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) Text("Nothing unpacked yet.", color = Muted)
        entries.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatBytes(e.bytes) + (if (e.source.isNotBlank()) " · from ${e.source}" else ""), color = Muted, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { cache.delete(e); version++ }) { Text("Delete", color = NeonBlue) }
            }
            HorizontalDivider(color = NavyCard)
        }
        if (entries.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear all unpacked games", color = NeonBlue) }
        }
        Spacer(Modifier.height(24.dp))
        SectionHeader("Added files", "${added.size} single files added from the Library tab")
        Spacer(Modifier.height(8.dp))
        added.forEach { u ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(displayName(ctx, u), color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { LibraryScanner.removeFile(ctx, u); version++ }) { Text("Remove", color = NeonBlue) }
            }
            HorizontalDivider(color = NavyCard)
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, containerColor = NavyCard,
        title = { Text("Clear unpacked games?", color = Color.White) },
        text = { Text("Frees ${formatBytes(total)}. Your original files are not touched.", color = Muted) },
        confirmButton = { TextButton(onClick = { cache.clear(); confirmClear = false; version++ }) { Text("Clear", color = NeonBlue) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep", color = Muted) } }
    )
}

@Composable
private fun SectionHeader(title: String, sub: String) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NavyCard).padding(14.dp)) {
        Text(title, color = NeonBlue, fontWeight = FontWeight.Bold)
        Text(sub, color = Muted, fontSize = 12.sp)
    }
}

private fun displayName(ctx: android.content.Context, u: Uri): String =
    runCatching { DocumentFile.fromSingleUri(ctx, u)?.name }.getOrNull() ?: u.lastPathSegment?.substringAfterLast('/') ?: u.toString()
