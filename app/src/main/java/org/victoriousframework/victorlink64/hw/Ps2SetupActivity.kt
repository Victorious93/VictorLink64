package org.victoriousframework.victorlink64.hw

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.victoriousframework.victorlink64.ui.Muted
import org.victoriousframework.victorlink64.ui.Navy
import org.victoriousframework.victorlink64.ui.NavyCard
import org.victoriousframework.victorlink64.ui.NeonBlue
import org.victoriousframework.victorlink64.ui.VictorTheme
import org.victoriousframework.victorlink64.ui.clip16
import java.io.File

/**
 * PlayStation 2 setup: BIOS import, picture size and a plain-language speed note. Victorious Framework.
 *
 * Opened from Settings, or in front of a PS2 game from the library. In front of a game it steps aside (starts the
 * game at once) when a BIOS is already installed and the speed note has been seen; otherwise it shows what is missing.
 */
class Ps2SetupActivity : ComponentActivity() {
    companion object {
        const val EXTRA_NEEDS_BIOS = "needs_bios"
        private const val EXTRA_ROM_URI = "rom_uri"; private const val EXTRA_FILE = "file_name"; private const val EXTRA_TITLE = "title"
        private const val EXTRA_TRACK_NAMES = "track_names"; private const val EXTRA_TRACK_URIS = "track_uris"
        private const val KEY_NOTE_SEEN = "speed_note_seen"

        /** Opens a PS2 game from the library: straight into the game when ready, otherwise the setup screen. */
        fun open(c: Context, uri: Uri, fileName: String, title: String, tracks: List<Pair<String, Uri>>) {
            c.startActivity(Intent(c, Ps2SetupActivity::class.java)
                .putExtra(EXTRA_ROM_URI, uri.toString()).putExtra(EXTRA_FILE, fileName).putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TRACK_NAMES, tracks.map { it.first }.toTypedArray())
                .putExtra(EXTRA_TRACK_URIS, tracks.map { it.second.toString() }.toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** Whether a library tap can go straight to the game. Pure so it can be tested. */
        fun canSkipSetup(hasBios: Boolean, noteSeen: Boolean) = hasBios && noteSeen
    }

    private var game: GameRef? = null

    private class GameRef(val uri: Uri, val fileName: String, val title: String, val names: Array<String>, val uris: Array<String>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        game = intent.getStringExtra(EXTRA_ROM_URI)?.let {
            GameRef(Uri.parse(it), intent.getStringExtra(EXTRA_FILE) ?: "game.iso", intent.getStringExtra(EXTRA_TITLE) ?: "PlayStation 2 game",
                intent.getStringArrayExtra(EXTRA_TRACK_NAMES) ?: emptyArray(), intent.getStringArrayExtra(EXTRA_TRACK_URIS) ?: emptyArray())
        }
        val seen = getSharedPreferences("victorlink64_ps2", MODE_PRIVATE).getBoolean(KEY_NOTE_SEEN, false)
        if (game != null && canSkipSetup(Ps2Bios.list(Ps2Bios.biosDir(filesDir)).isNotEmpty(), seen)) {
            startGame(); finish(); return
        }
        setContent { VictorTheme { Ps2SetupScreen(
            gameTitle = game?.title,
            needsBios = intent.getBooleanExtra(EXTRA_NEEDS_BIOS, false),
            biosDir = Ps2Bios.biosDir(filesDir),
            onPlay = { getSharedPreferences("victorlink64_ps2", MODE_PRIVATE).edit().putBoolean(KEY_NOTE_SEEN, true).apply(); startGame(); finish() },
        ) } }
    }

    private fun startGame() {
        val g = game ?: return
        startActivity(Intent(this, HwActivity::class.java)
            .putExtra(HwActivity.EXTRA_ROM_URI, g.uri.toString()).putExtra(HwActivity.EXTRA_ROM_NAME, g.fileName)
            .putExtra(HwActivity.EXTRA_SYSTEM, HwSystem.PS2.name)
            .putExtra(HwActivity.EXTRA_COMPANION_NAMES, g.names).putExtra(HwActivity.EXTRA_COMPANION_URIS, g.uris))
    }
}

private fun displayName(c: Context, uri: Uri): String =
    c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        ?: uri.lastPathSegment ?: "file"

private fun totalRamMb(c: Context): Long {
    val info = ActivityManager.MemoryInfo()
    (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
    return info.totalMem / (1024 * 1024)
}

@Composable
private fun Card(modifier: Modifier = Modifier, highlight: Boolean = false, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip16().background(NavyCard)
            .border(1.dp, if (highlight) NeonBlue else NeonBlue.copy(alpha = .35f), RoundedCornerShape(16.dp)).padding(16.dp)
    ) { content() }
}

@Composable
private fun Ps2SetupScreen(gameTitle: String?, needsBios: Boolean, biosDir: File, onPlay: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { Ps2Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(Ps2Bios.list(biosDir)) }
    var chosen by remember { mutableStateOf(prefs.bios) }
    var resolution by remember { mutableStateOf(prefs.resolution) }
    var report by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val assessment = remember { Ps2Performance.assess(totalRamMb(ctx), Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) }
    val activeBios = Ps2Bios.choose(entries, chosen)
    val canPlay = activeBios != null && assessment.level != Ps2Performance.Level.UNSUPPORTED

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val lines = withContext(Dispatchers.IO) {
                uris.map { u ->
                    val name = displayName(ctx, u)
                    val outcome = runCatching {
                        ctx.contentResolver.openInputStream(u)!!.use { Ps2Bios.import(biosDir, name, it) }
                    }.getOrElse { Ps2Bios.Outcome.Skipped(name, "Could not read this file (${it.message}).") }
                    when (outcome) {
                        is Ps2Bios.Outcome.Added -> "Added ${outcome.name}: ${outcome.info.description}"
                        is Ps2Bios.Outcome.Sidecar -> "Added ${outcome.name} (BIOS add-on file)"
                        is Ps2Bios.Outcome.Skipped -> "Skipped ${outcome.name}: ${outcome.reason}"
                    }
                }
            }
            report = lines
            entries = withContext(Dispatchers.IO) { Ps2Bios.list(biosDir) }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(Navy).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("PlayStation 2", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(gameTitle ?: "BIOS, picture size and speed", color = Muted)

        if (needsBios || (gameTitle != null && activeBios == null)) {
            Card(highlight = true) {
                Text("PlayStation 2 games need a BIOS", color = NeonBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text("Add your BIOS below, then come back and tap the game again.", color = Muted, fontSize = 13.sp)
            }
        }

        Card {
            Text("PS2 BIOS", color = NeonBlue, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "The BIOS is the PS2's built-in startup software. Every PS2 emulator needs one, and it can't be shipped with " +
                    "this app. Use the file from your own PlayStation 2 (it is usually named like scph10000.bin and is 4 MB). " +
                    "Pick one or more files; VictorLink64 checks each one and keeps only real PS2 BIOS files, in the app's private storage.",
                color = Muted, fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            if (entries.isEmpty()) {
                Text("No BIOS added yet.", color = Color.White, fontWeight = FontWeight.SemiBold)
            } else {
                entries.forEach { e ->
                    Row(Modifier.fillMaxWidth().clickable { chosen = e.file.name; prefs.bios = e.file.name }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = e.file.name == activeBios?.file?.name, onClick = { chosen = e.file.name; prefs.bios = e.file.name },
                            colors = RadioButtonDefaults.colors(selectedColor = NeonBlue, unselectedColor = Muted))
                        Column(Modifier.weight(1f)) {
                            Text(e.file.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(e.info.description, color = Muted, fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            e.file.delete()
                            entries = Ps2Bios.list(biosDir)
                            if (chosen == e.file.name) { chosen = null; prefs.bios = null }
                        }) { Text("Remove", color = Muted) }
                    }
                }
                if (entries.size > 1) Text("Tap one to choose which BIOS to use.", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = NeonBlue, contentColor = Navy),
            ) { Text(if (busy) "Checking…" else if (entries.isEmpty()) "Add BIOS file(s)" else "Add another BIOS", fontWeight = FontWeight.Bold) }
            report.forEach { Text(it, color = if (it.startsWith("Added")) NeonBlue else Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
        }

        Card {
            Text("Picture size", color = NeonBlue, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Higher picture sizes look sharper but need a much faster phone. Takes effect the next time a game starts.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Ps2Resolution.entries.forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { resolution = r; prefs.resolution = r }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = r == resolution, onClick = { resolution = r; prefs.resolution = r },
                        colors = RadioButtonDefaults.colors(selectedColor = NeonBlue, unselectedColor = Muted))
                    Column(Modifier.weight(1f)) {
                        Text(r.label, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(r.output, color = Muted, fontSize = 12.sp)
                        Text(r.detail, color = Muted, fontSize = 12.sp)
                    }
                }
            }
            Ps2Performance.resolutionWarning(assessment.level, resolution)?.let {
                Spacer(Modifier.height(6.dp)); Text(it, color = NeonBlue, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }

        Card(highlight = assessment.level == Ps2Performance.Level.ENTRY || assessment.level == Ps2Performance.Level.UNSUPPORTED) {
            Text("Speed", color = NeonBlue, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(assessment.headline, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(assessment.advice, color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(Ps2Performance.GENERAL_NOTE, color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(Ps2Performance.ENTRY_CHIP_NOTE, color = Muted, fontSize = 13.sp)
        }

        if (gameTitle != null) {
            Button(
                onClick = onPlay, enabled = canPlay, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonBlue, contentColor = Navy),
            ) { Text(if (canPlay) "Play" else if (activeBios == null) "Add a BIOS to play" else "Not supported on this phone", fontWeight = FontWeight.Bold) }
        } else {
            OutlinedButton(onClick = { (ctx as? ComponentActivity)?.finish() }, modifier = Modifier.fillMaxWidth()) { Text("Done", color = NeonBlue) }
        }
        Spacer(Modifier.width(1.dp))
    }
}
