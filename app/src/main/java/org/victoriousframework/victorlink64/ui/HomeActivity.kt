package org.victoriousframework.victorlink64.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import paulscode.android.mupen64plusae.ActivityHelper
import paulscode.android.mupen64plusae.R
import paulscode.android.mupen64plusae.SplashActivity
import paulscode.android.mupen64plusae.persistent.AppData
import paulscode.android.mupen64plusae.task.ExtractAssetsOrCleanupTask
import paulscode.android.mupen64plusae.util.FileUtil
import paulscode.android.mupen64plusae.util.RomHeader

val Navy = Color(0xFF0A1128)
val NavyCard = Color(0xFF13204A)
val NeonBlue = Color(0xFF1EA0FF)
val NeonRed = Color(0xFFFF3B3B)
val Muted = Color(0xFF8FA3C8)

private const val PREFS = "victorlink64_prefs"
private const val KEY_FOLDER = "rom_folder"
private val ROM_EXT = setOf("z64", "n64", "v64")

data class Game(val name: String, val uri: Uri, val size: Long)

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(ActivityHelper.Keys.ROM_PATH)?.let { path ->
            intent.removeExtra(ActivityHelper.Keys.ROM_PATH)
            Thread { launchRom(this, Uri.parse(path), path.substringAfterLast('/')) }.start()
        }
        setContent { VictorTheme { VictorApp() } }
    }
}

@Composable
fun VictorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NeonBlue, secondary = NeonRed, background = Navy,
            surface = NavyCard, onBackground = Color.White, onSurface = Color.White
        ), content = content
    )
}

enum class Tab(val label: String, val icon: ImageVector) {
    Library("Library", Icons.Filled.SportsEsports),
    Connect("Connect", Icons.Filled.Groups),
    Cast("Cast", Icons.Filled.Cast),
    Settings("Settings", Icons.Filled.Settings),
    About("About", Icons.Filled.Info)
}

@Composable
fun VictorApp() {
    var tab by rememberSaveable { mutableStateOf(Tab.Library) }
    Scaffold(
        containerColor = Navy,
        topBar = { Header() },
        bottomBar = {
            NavigationBar(containerColor = NavyCard) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, t.label) }, label = { Text(t.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NeonBlue, selectedTextColor = NeonBlue,
                            indicatorColor = Navy, unselectedIconColor = Muted, unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.Library -> LibraryScreen()
                Tab.Connect -> ComingSoon("Connect", "Online rooms: host or join a game with friends.", Icons.Filled.Groups)
                Tab.Cast -> ComingSoon("Cast", "Stream your game to a TV or another screen.", Icons.Filled.Cast)
                Tab.Settings -> SettingsScreen()
                Tab.About -> AboutScreen()
            }
        }
    }
}

@Composable
fun Header() {
    Row(
        Modifier.fillMaxWidth().background(Navy).statusBarsPadding().padding(16.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painterResource(R.drawable.vl64_logo), null, Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Row {
                Text("Victor", color = NeonBlue, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text("Link64", color = NeonRed, fontWeight = FontWeight.Black, fontSize = 22.sp)
            }
            Text("PLAY • CONNECT • CAST", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
        }
    }
}

fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

fun scanFolder(c: Context, tree: Uri): List<Game> {
    val root = DocumentFile.fromTreeUri(c, tree) ?: return emptyList()
    val out = mutableListOf<Game>()
    fun walk(d: DocumentFile, depth: Int) {
        for (f in d.listFiles()) {
            if (f.isDirectory && depth < 3) walk(f, depth + 1)
            else {
                val n = f.name ?: continue
                if (n.substringAfterLast('.', "").lowercase() in ROM_EXT)
                    out += Game(n.substringBeforeLast('.'), f.uri, f.length())
            }
        }
    }
    walk(root, 0)
    return out.sortedBy { it.name.lowercase() }
}

/** Reads ROM header + md5 and hands off to the core's game activity. Call off the main thread. */
fun launchRom(c: Context, uri: Uri, display: String) {
    val appData = AppData(c)
    if (!ExtractAssetsOrCleanupTask.areAllAssetsPresent(SplashActivity.SOURCE_DIR, appData.coreSharedDataDir)) {
        appData.putAssetCheckNeeded(true)
        ActivityHelper.startSplashActivity(c); return
    }
    try {
        val header = RomHeader(c, uri)
        if (!header.isValid) { toast(c, "Not a valid N64 ROM"); return }
        val md5 = c.contentResolver.openInputStream(uri)!!.use { FileUtil.computeMd5(it) }
        ActivityHelper.startGameActivity(
            c, uri.toString(), null, md5, header.crc, header.name,
            header.countryCode.value, null, header.name, display, false
        )
    } catch (e: Exception) {
        toast(c, "Could not open ROM: ${e.message}")
    }
}

private fun toast(c: Context, m: String) = android.os.Handler(android.os.Looper.getMainLooper()).post {
    Toast.makeText(c, m, Toast.LENGTH_LONG).show()
}

@Composable
fun LibraryScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf(prefs(ctx).getString(KEY_FOLDER, null)) }
    var games by remember { mutableStateOf<List<Game>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        val f = folder ?: return
        loading = true
        scope.launch {
            games = withContext(Dispatchers.IO) { runCatching { scanFolder(ctx, Uri.parse(f)) }.getOrDefault(emptyList()) }
            loading = false
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs(ctx).edit().putString(KEY_FOLDER, uri.toString()).apply()
            folder = uri.toString(); refresh()
        }
    }
    LaunchedEffect(folder) { refresh() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Library", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            if (folder != null) IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Refresh, "Rescan", tint = NeonBlue) }
            Button(onClick = { picker.launch(null) }, colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)) {
                Icon(Icons.Filled.Folder, null); Spacer(Modifier.width(6.dp)); Text(if (folder == null) "Pick folder" else "Change")
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            folder == null -> EmptyState("Choose your ROM folder", "Tap “Pick folder” and select where your .z64 / .n64 / .v64 games live.")
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = NeonBlue) }
            games.isEmpty() -> EmptyState("No games found", "No .z64 / .n64 / .v64 files in that folder.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(games) { g ->
                    Row(
                        Modifier.fillMaxWidth().clip16().background(NavyCard)
                            .border(1.dp, NeonBlue.copy(alpha = .35f), RoundedCornerShape(16.dp))
                            .clickable { Thread { launchRom(ctx, g.uri, g.name) }.start() }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(44.dp).clip16().background(NeonRed.copy(alpha = .18f)), Alignment.Center) {
                            Icon(Icons.Filled.SportsEsports, null, tint = NeonRed)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("%.1f MB".format(g.size / 1048576f), color = Muted, fontSize = 12.sp)
                        }
                        Icon(Icons.Filled.PlayArrow, "Play", tint = NeonBlue)
                    }
                }
            }
        }
    }
}

fun Modifier.clip16() = this.clip(RoundedCornerShape(16.dp))

@Composable
fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.vl64_logo), null, Modifier.size(120.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(body, color = Muted, modifier = Modifier.padding(24.dp, 8.dp))
    }
}

@Composable
fun ComingSoon(title: String, body: String, icon: ImageVector) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = NeonBlue, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Surface(color = NeonRed, shape = RoundedCornerShape(50)) {
            Text("COMING SOON", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(14.dp, 4.dp), letterSpacing = 2.sp)
        }
        Spacer(Modifier.height(12.dp))
        Text(body, color = Muted)
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val p = prefs(ctx)
    var vibrate by remember { mutableStateOf(p.getBoolean("haptics", true)) }
    var fps by remember { mutableStateOf(p.getBoolean("show_fps", false)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(12.dp))
        SettingRow("Touch controls layout", "Adjust on-screen buttons") { ActivityHelper.startTouchscreenPrefsActivity(ctx) }
        SettingRow("Backup & restore", "Import/export saves and settings") { ActivityHelper.startImportExportActivity(ctx) }
        SettingRow("Forget ROM folder", "Clear the selected library folder") {
            p.edit().remove(KEY_FOLDER).apply(); Toast.makeText(ctx, "Library folder cleared", Toast.LENGTH_SHORT).show()
        }
        ToggleRow("Haptic feedback", vibrate) { vibrate = it; p.edit().putBoolean("haptics", it).apply() }
        ToggleRow("Show FPS (coming soon)", fps) { fps = it; p.edit().putBoolean("show_fps", it).apply() }
    }
}

@Composable
fun SettingRow(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(sub, color = Muted, fontSize = 13.sp)
    }
    HorizontalDivider(color = NavyCard)
}

@Composable
fun ToggleRow(title: String, v: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, modifier = Modifier.weight(1f))
        Switch(v, on, colors = SwitchDefaults.colors(checkedTrackColor = NeonBlue))
    }
    HorizontalDivider(color = NavyCard)
}

@Composable
fun AboutScreen() {
    val ctx = LocalContext.current
    val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.vl64_logo), null, Modifier.size(140.dp))
        Text("VictorLink64", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text("Version $ver", color = Muted)
        Text("© 2026 Don Victor", color = Muted)
        Spacer(Modifier.height(20.dp))
        Text("Open-source licenses", color = NeonBlue, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
            "VictorLink64 is free software licensed under the GNU General Public License v3.\n\n" +
            "The emulation engine is based on Mupen64Plus and Mupen64Plus-AE " +
            "(https://github.com/mupen64plus-ae/mupen64plus-ae), including its core, RSP, audio, input " +
            "and video plugins (GLideN64, Glide64mk2, Rice, gln64, Angrylion-Plus, paraLLEl). " +
            "Copyright © their respective authors and contributors.\n\n" +
            "This program is distributed WITHOUT ANY WARRANTY. See the GNU GPL v3 " +
            "(https://www.gnu.org/licenses/gpl-3.0.html) for details. Source code: " +
            "https://github.com/Victorious93/VictorLink64",
            color = Muted, fontSize = 13.sp
        )
    }
}
