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
private val N64_EXT = setOf("z64", "n64", "v64")
private val SNES_EXT = setOf("sfc", "smc")
private val ROM_EXT = N64_EXT + SNES_EXT

data class Game(val name: String, val uri: Uri, val size: Long, val snes: Boolean = false, val fileName: String = "",
    /** For .zip games: the ROM entry inside the archive. */ val zipEntry: String? = null)

data class ScanResult(val games: List<Game>, val filesSeen: Int, val error: String? = null)

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
                Tab.Connect -> org.victoriousframework.victorlink64.connect.ConnectScreen()
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

/** Finds the first ROM entry inside a zip (streamed; most ROM zips have a single entry). */
fun findRomInZip(c: Context, uri: Uri): String? = runCatching {
    c.contentResolver.openInputStream(uri)?.use { ins ->
        java.util.zip.ZipInputStream(java.io.BufferedInputStream(ins)).use { z ->
            var e = z.nextEntry
            var guard = 0
            while (e != null && guard++ < 64) {
                if (!e.isDirectory && e.name.substringAfterLast('.', "").lowercase() in ROM_EXT) return@runCatching e.name
                e = z.nextEntry
            }
            null
        }
    }
}.getOrNull()

fun scanFolder(c: Context, tree: Uri): ScanResult {
    val root = DocumentFile.fromTreeUri(c, tree) ?: return ScanResult(emptyList(), 0, "Folder can't be opened")
    if (!root.canRead()) return ScanResult(emptyList(), 0, "No permission to read this folder — pick it again")
    val zipCache = c.getSharedPreferences("victorlink64_zipcache", Context.MODE_PRIVATE)
    val out = mutableListOf<Game>()
    var seen = 0
    fun walk(d: DocumentFile, depth: Int) {
        for (f in d.listFiles()) {
            if (f.isDirectory) { if (depth < 4) walk(f, depth + 1); continue }
            seen++
            val n = f.name ?: continue
            val ext = n.substringAfterLast('.', "").lowercase()
            val base = n.substringBeforeLast('.')
            when {
                ext in ROM_EXT -> out += Game(base, f.uri, f.length(), ext in SNES_EXT, n)
                ext == "zip" -> {
                    val key = f.uri.toString() + "#" + f.length() + "#" + f.lastModified()
                    val entry = if (zipCache.contains(key)) zipCache.getString(key, "") else findRomInZip(c, f.uri).also { zipCache.edit().putString(key, it ?: "").apply() }
                    if (!entry.isNullOrEmpty()) {
                        val inner = entry.substringAfterLast('.', "").lowercase()
                        out += Game(base, f.uri, f.length(), inner in SNES_EXT, entry.substringAfterLast('/'), entry)
                    }
                }
            }
        }
    }
    return try {
        walk(root, 0)
        ScanResult(out.sortedBy { it.name.lowercase() }, seen)
    } catch (e: Exception) {
        ScanResult(out, seen, "Scan failed: ${e.message}")
    }
}

/** Extracts a zipped ROM to the app cache (re-used if already extracted). Call off the main thread. */
fun extractZipRom(c: Context, g: Game): java.io.File {
    val dir = java.io.File(c.cacheDir, "roms").apply { mkdirs() }
    // Keyed by archive + entry + size so two different zips holding e.g. "game.z64" never share a cache file.
    val tag = Integer.toHexString("${g.uri}#${g.zipEntry}#${g.size}".hashCode())
    val out = java.io.File(dir, tag + "_" + g.fileName.replace(Regex("[^A-Za-z0-9._ -]"), "_"))
    if (out.exists() && out.length() > 0) return out
    // keep the cache small: only the most recent extracted ROM
    dir.listFiles()?.forEach { it.delete() }
    c.contentResolver.openInputStream(g.uri)!!.use { ins ->
        java.util.zip.ZipInputStream(java.io.BufferedInputStream(ins)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name == g.zipEntry) { val tmp = java.io.File(dir, out.name + ".part"); tmp.outputStream().use { z.copyTo(it) }; tmp.renameTo(out); return out }
                e = z.nextEntry
            }
        }
    }
    throw java.io.FileNotFoundException("${g.zipEntry} not found in zip")
}

/** Launches any library game (N64 or SNES, plain or zipped). Call off the main thread. */
fun launchGame(c: Context, g: Game) {
    try {
        val uri = if (g.zipEntry != null) { toast(c, "Unpacking ${g.name}…"); Uri.fromFile(extractZipRom(c, g)) } else g.uri
        if (g.snes) c.startActivity(Intent(c, org.victoriousframework.victorlink64.snes.SnesActivity::class.java)
            .putExtra(org.victoriousframework.victorlink64.snes.SnesActivity.EXTRA_ROM_URI, uri.toString())
            .putExtra(org.victoriousframework.victorlink64.snes.SnesActivity.EXTRA_ROM_NAME, g.fileName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else launchRom(c, uri, g.name)
    } catch (e: Exception) {
        toast(c, "Could not open ${g.name}: ${e.message}")
    }
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
        val md5 = c.contentResolver.openInputStream(uri)!!.use { FileUtil.computeMd5(java.io.BufferedInputStream(it)) }
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
    var scanInfo by remember { mutableStateOf<ScanResult?>(null) }

    fun refresh() {
        val f = folder ?: return
        loading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { scanFolder(ctx, Uri.parse(f)) }.getOrElse { ScanResult(emptyList(), 0, it.message) } }
            games = r.games; scanInfo = r
            loading = false
            when {
                r.error != null -> Toast.makeText(ctx, r.error, Toast.LENGTH_LONG).show()
                r.games.isEmpty() -> Toast.makeText(ctx, "No games found (${r.filesSeen} files checked). Supported: .z64 .n64 .v64 .sfc .smc and .zip", Toast.LENGTH_LONG).show()
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onFailure { Toast.makeText(ctx, "Couldn't keep access to that folder: ${it.message}", Toast.LENGTH_LONG).show() }
            prefs(ctx).edit().putString(KEY_FOLDER, uri.toString()).apply()
            if (folder == uri.toString()) refresh() else folder = uri.toString() // LaunchedEffect(folder) rescans
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
        Row(
            Modifier.fillMaxWidth().clip16().background(NeonBlue.copy(alpha = .12f))
                .border(1.dp, NeonBlue, RoundedCornerShape(16.dp))
                .clickable { ctx.startActivity(Intent(ctx, org.victoriousframework.victorlink64.chess.ChessActivity::class.java)) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("♞", fontSize = 30.sp, color = NeonBlue)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Chess", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Built-in · vs phone or pass & play", color = Muted, fontSize = 12.sp)
            }
            Icon(Icons.Filled.PlayArrow, "Play chess", tint = NeonBlue)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip16().background(NeonRed.copy(alpha = .12f))
                .border(1.dp, NeonRed, RoundedCornerShape(16.dp))
                .clickable { org.victoriousframework.victorlink64.zombies.ZombiesActivity.launch(ctx, org.victoriousframework.victorlink64.zombies.ZombiesRole.SOLO) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("☠", fontSize = 30.sp, color = NeonRed)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("VictorOps Zombies", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Built-in · solo offline · co-op from the Connect tab", color = Muted, fontSize = 12.sp)
            }
            Icon(Icons.Filled.PlayArrow, "Play zombies", tint = NeonRed)
        }
        Spacer(Modifier.height(12.dp))
        when {
            folder == null -> EmptyState("Choose your ROM folder", "Tap “Pick folder” and select where your .z64 / .n64 / .v64 / .sfc / .smc / .zip games live.")
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = NeonBlue) }
            games.isEmpty() -> EmptyState(scanInfo?.error ?: "No games found",
                "Checked ${scanInfo?.filesSeen ?: 0} files. VictorLink64 looks for .z64 .n64 .v64 (N64), .sfc .smc (SNES) and .zip files containing them, up to 4 folders deep.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(games) { g ->
                    Row(
                        Modifier.fillMaxWidth().clip16().background(NavyCard)
                            .border(1.dp, NeonBlue.copy(alpha = .35f), RoundedCornerShape(16.dp))
                            .clickable { Thread { launchGame(ctx, g) }.start() }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(44.dp).clip16().background(NeonRed.copy(alpha = .18f)), Alignment.Center) {
                            Icon(Icons.Filled.SportsEsports, null, tint = NeonRed)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text((if (g.snes) "SNES" else "N64") + (if (g.zipEntry != null) " · ZIP" else "") + " · %.1f MB".format(g.size / 1048576f), color = Muted, fontSize = 12.sp)
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
            "SNES emulation uses bsnes by byuu and contributors (libretro port, https://github.com/libretro/bsnes), " +
            "licensed under the GNU GPL v3; its bundled Game Boy core (SameBoy, © Lior Halphon) is MIT licensed.\n\n" +
            "Chess engine and all VictorLink64 screens are original code.\n\n" +
            "This program is distributed WITHOUT ANY WARRANTY. See the GNU GPL v3 " +
            "(https://www.gnu.org/licenses/gpl-3.0.html) for details. Source code: " +
            "https://github.com/Victorious93/VictorLink64",
            color = Muted, fontSize = 13.sp
        )
    }
}
