package org.victoriousframework.victorlink64.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import org.victoriousframework.victorlink64.graphics.GraphicsSettingsActivity
import org.victoriousframework.victorlink64.graphics.N64Graphics
import org.victoriousframework.victorlink64.hw.HwSystem
import org.victoriousframework.victorlink64.shizuku.ShizukuController
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
import androidx.compose.foundation.shape.CircleShape
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
data class Game(val name: String, val uri: Uri, val size: Long, val snes: Boolean = false, val fileName: String = "",
    /** For archived games (zip, 7z, rar, tar, gz, xz): the game's entry inside the archive. */ val zipEntry: String? = null,
    /** PSP / GameCube / PS2 disc images (never inside archives for PS2). */ val hw: org.victoriousframework.victorlink64.hw.HwSystem? = null,
    /** Archive type when [zipEntry] is set. */ val archive: org.victoriousframework.victorlink64.library.ArchiveKind? = null,
    /** Disc number for multi-disc sets ("(Disc 2)"), null for single-disc games. */ val disc: Int? = null,
    /** Size and date of the file on storage (the archive, for archived games); keys the unpack cache. */
    val sourceSize: Long = size, val sourceModified: Long = 0L,
    /** Detected format, e.g. "z64", "iso", "rvz". */ val format: String = "",
    /** File name of the archive the game lives in (empty for plain files); tells apart same-titled games. */ val archiveName: String = "",
    /** For PS2 .cue sheets: the track files named by the sheet. */ val tracks: List<Pair<String, Uri>> = emptyList()) {
    val systemLabel get() = hw?.label ?: if (snes) "SNES" else "N64"
}

/** [problems]: files that look like games but can't be added, each with a plain-language reason. */
data class ScanResult(val games: List<Game>, val filesSeen: Int, val error: String? = null,
    val problems: List<Pair<String, String>> = emptyList())

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(ActivityHelper.Keys.ROM_PATH)?.let { path ->
            intent.removeExtra(ActivityHelper.Keys.ROM_PATH)
            Thread { launchRom(this, Uri.parse(path), path.substringAfterLast('/')) }.start()
        }
        setContent { VictorTheme { VictorApp() } }
    }

    override fun onResume() {
        super.onResume()
        ShizukuController.revertIfIdle(this)
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

/** Scans the picked folder (if any) plus individually added files, including games inside archives. */
fun scanFolder(c: Context, tree: Uri?): ScanResult = org.victoriousframework.victorlink64.library.LibraryScanner.scan(c, tree)

/** Launches any library game (N64, SNES, PSP or GameCube; N64/SNES may be zipped). Call off the main thread. */
fun launchGame(c: Context, g: Game) {
    try {
        val uri = org.victoriousframework.victorlink64.library.GameLauncher.prepare(c, g) ?: return
        if (g.hw == HwSystem.PS2) org.victoriousframework.victorlink64.hw.Ps2SetupActivity.open(c, uri, g.fileName, g.name, g.tracks)
        else if (g.hw != null) c.startActivity(Intent(c, org.victoriousframework.victorlink64.hw.HwActivity::class.java)
            .putExtra(org.victoriousframework.victorlink64.hw.HwActivity.EXTRA_ROM_URI, uri.toString())
            .putExtra(org.victoriousframework.victorlink64.hw.HwActivity.EXTRA_ROM_NAME, g.fileName)
            .putExtra(org.victoriousframework.victorlink64.hw.HwActivity.EXTRA_SYSTEM, g.hw.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else if (g.snes) c.startActivity(Intent(c, org.victoriousframework.victorlink64.snes.SnesActivity::class.java)
            .putExtra(org.victoriousframework.victorlink64.snes.SnesActivity.EXTRA_ROM_URI, uri.toString())
            .putExtra(org.victoriousframework.victorlink64.snes.SnesActivity.EXTRA_ROM_NAME, g.fileName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else launchRom(c, uri, g.name)
    } catch (e: org.victoriousframework.victorlink64.library.FriendlyException) {
        toast(c, e.message ?: "Could not open ${g.name}")
    } catch (e: Exception) {
        toast(c, "Could not open ${g.name}. The file may be damaged or still downloading.")
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
        N64Graphics.prepareLaunch(c, md5, header.name)
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
    var addedCount by remember { mutableStateOf(org.victoriousframework.victorlink64.library.LibraryScanner.addedFiles(ctx).size) }
    var showProblems by remember { mutableStateOf(false) }
    val hasSource = folder != null || addedCount > 0

    fun refresh() {
        if (folder == null && org.victoriousframework.victorlink64.library.LibraryScanner.addedFiles(ctx).isEmpty()) { games = emptyList(); scanInfo = null; return }
        val f = folder
        loading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { scanFolder(ctx, f?.let(Uri::parse)) }.getOrElse { ScanResult(emptyList(), 0, "Couldn't read your games. Pick the folder again.") } }
            games = r.games; scanInfo = r
            loading = false
            when {
                r.error != null -> Toast.makeText(ctx, r.error, Toast.LENGTH_LONG).show()
                r.games.isEmpty() -> Toast.makeText(ctx, "No games found (${r.filesSeen} files checked).", Toast.LENGTH_LONG).show()
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val kept = uris.filter { u -> runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess }
            if (kept.size < uris.size) Toast.makeText(ctx, "Some files can only be opened once. Copy them to your phone's storage to keep them in the library.", Toast.LENGTH_LONG).show()
            org.victoriousframework.victorlink64.library.LibraryScanner.addFiles(ctx, kept)
            addedCount = org.victoriousframework.victorlink64.library.LibraryScanner.addedFiles(ctx).size
            refresh()
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
    org.victoriousframework.victorlink64.library.UnpackDialog()
    if (showProblems) org.victoriousframework.victorlink64.library.ProblemsDialog(scanInfo?.problems.orEmpty()) { showProblems = false }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Library", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            if (hasSource) IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Refresh, "Rescan", tint = NeonBlue) }
            IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.UploadFile, "Add game files", tint = NeonBlue) }
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
            PlayBadge("Play chess")
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip16().background(NeonBlue.copy(alpha = .12f))
                .border(1.dp, NeonBlue, RoundedCornerShape(16.dp))
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
            PlayBadge("Play zombies")
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().clip16().background(NeonBlue.copy(alpha = .12f))
                .border(1.dp, NeonBlue, RoundedCornerShape(16.dp))
                .clickable { ctx.startActivity(Intent(ctx, org.victoriousframework.victorlink64.apkplayer.ApkPlayerActivity::class.java)) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Android, null, tint = NeonBlue, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("APK Player", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Run an Android app in a private sandbox · early beta", color = Muted, fontSize = 12.sp)
            }
            PlayBadge("Open APK Player")
        }
        Spacer(Modifier.height(12.dp))
        when {
            !hasSource -> EmptyState("Add your games", "Tap “Pick folder” for a whole folder, or the add-file button for single games. " +
                "Zip, 7z, RAR, tar, gz and xz archives work too — no need to unzip.")
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = NeonBlue) }
            games.isEmpty() -> Column {
                ProblemsBanner(scanInfo) { showProblems = true }
                EmptyState(scanInfo?.error ?: "No games found",
                    "Checked ${scanInfo?.filesSeen ?: 0} files. VictorLink64 finds N64 (.z64 .n64 .v64), SNES (.sfc .smc), " +
                    "PSP (.iso .cso .chd .pbp), PlayStation 2 (.iso .cso .chd .bin/.cue; not inside archives) and GameCube (.iso .gcm .rvz .gcz .wia .ciso) games, also inside .zip .7z .rar .tar .gz .xz archives, up to 4 folders deep.")
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { ProblemsBanner(scanInfo) { showProblems = true } }
                items(games) { g ->
                    Row(
                        Modifier.fillMaxWidth().clip16().background(NavyCard)
                            .border(1.dp, NeonBlue.copy(alpha = .35f), RoundedCornerShape(16.dp))
                            .clickable { Thread { launchGame(ctx, g) }.start() }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(44.dp).clip16().background(NeonBlue.copy(alpha = .18f)), Alignment.Center) {
                            Icon(Icons.Filled.SportsEsports, null, tint = NeonBlue)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.name + (g.disc?.let { " · Disc $it" } ?: ""), color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(org.victoriousframework.victorlink64.library.gameSubtitle(g), color = Muted, fontSize = 12.sp)
                        }
                        PlayBadge("Play")
                    }
                }
            }
        }
    }
}

/** "3 files couldn't be added" banner; tap for the reasons. */
@Composable
fun ProblemsBanner(r: ScanResult?, onClick: () -> Unit) {
    val n = r?.problems?.size ?: 0
    if (n == 0) return
    Row(Modifier.fillMaxWidth().clip16().background(NeonBlue.copy(alpha = .10f)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Info, null, tint = NeonBlue)
        Spacer(Modifier.width(10.dp))
        Text(if (n == 1) "1 file couldn't be added — tap to see why" else "$n files couldn't be added — tap to see why", color = Color.White, fontSize = 13.sp)
    }
}

fun Modifier.clip16() = this.clip(RoundedCornerShape(16.dp))

/** Consistent play affordance for every launchable row: filled brand-blue circle (never red). */
@Composable
fun PlayBadge(description: String) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(NeonBlue), Alignment.Center) {
        Icon(Icons.Filled.PlayArrow, description, tint = Navy, modifier = Modifier.size(26.dp))
    }
}

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
        Surface(color = NeonBlue, shape = RoundedCornerShape(50)) {
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
    val overlay = remember { org.victoriousframework.victorlink64.controls.ControlsPrefs.settings(ctx) }
    var vibrate by remember { mutableStateOf(overlay.haptics) }
    var hideWithPad by remember { mutableStateOf(overlay.hideWithController) }
    var fps by remember { mutableStateOf(p.getBoolean("show_fps", false)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(12.dp))
        SettingRow("Controller buttons", "Remap a gamepad for SNES, PSP, GameCube and PS2") {
            ctx.startActivity(Intent(ctx, org.victoriousframework.victorlink64.controls.ControllerMapActivity::class.java))
        }
        SettingRow("PlayStation 2", "Add your BIOS, choose picture size, read the speed note") {
            ctx.startActivity(Intent(ctx, org.victoriousframework.victorlink64.hw.Ps2SetupActivity::class.java))
        }
        SettingRow("N64 touch controls", "Classic skins and options (edit any console's layout in-game with EDIT)") { ActivityHelper.startTouchscreenPrefsActivity(ctx) }
        SettingRow("Backup & restore", "Import/export saves and settings") { ActivityHelper.startImportExportActivity(ctx) }
        SettingRow("APK Player", "Pick an Android app and run it in a private sandbox (early beta)") {
            ctx.startActivity(Intent(ctx, org.victoriousframework.victorlink64.apkplayer.ApkPlayerActivity::class.java))
        }
        SettingRow("Classic Android check", "Can this phone run old 32-bit games?") {
            ctx.startActivity(Intent(ctx, org.victoriousframework.victorlink64.classic.ClassicCheckActivity::class.java))
        }
        SettingRow("Game storage", "Unpacked games, added files and free space") {
            ctx.startActivity(Intent(ctx, org.victoriousframework.victorlink64.library.StorageActivity::class.java))
        }
        SettingRow("Graphics & performance", "Auto Best Settings, quality per emulator, FPS counter, Shizuku boosts") {
            ctx.startActivity(Intent(ctx, GraphicsSettingsActivity::class.java))
        }
        SettingRow("Forget ROM folder", "Clear the selected library folder") {
            p.edit().remove(KEY_FOLDER).apply(); Toast.makeText(ctx, "Library folder cleared", Toast.LENGTH_SHORT).show()
        }
        ToggleRow("Haptic feedback on touch controls", vibrate) { vibrate = it; overlay.haptics = it }
        ToggleRow("Hide touch controls when a controller is connected", hideWithPad) { hideWithPad = it; overlay.hideWithController = it }
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
            "Importing games from archives uses libarchive (BSD licence, https://libarchive.org) through the " +
            "libarchive-android bindings by Hai Zhang (Apache License 2.0) for RAR; Apache Commons Compress " +
            "(Apache License 2.0) for zip, 7z and tar; and XZ for Java (public domain / 0BSD) for xz and 7z.\n\n" +
            "SNES emulation uses bsnes by byuu and contributors (libretro port, https://github.com/libretro/bsnes), " +
            "licensed under the GNU GPL v3; its bundled Game Boy core (SameBoy, © Lior Halphon) is MIT licensed.\n\n" +
            "PSP emulation uses PPSSPP by Henrik Rydgård and contributors (libretro core, https://github.com/hrydgard/ppsspp), " +
            "licensed under the GNU GPL v2.0 or later.\n\n" +
            "GameCube emulation uses Dolphin by the Dolphin Emulator Project and contributors (libretro port, " +
            "https://github.com/libretro/dolphin), licensed under the GNU GPL v2.0 or later.\n\n" +
            "PlayStation 2 emulation uses LRPS2, the libretro PlayStation 2 core (https://github.com/libretro/ps2), " +
            "derived from PCSX2 by the PCSX2 Team and contributors and licensed under the GNU GPL v3. " +
            "VictorLink64 does not include a PS2 BIOS; you add your own.\n\n" +
            "The on-screen controls (analog stick, slide D-pad, analog GameCube triggers, layout editor) are original " +
            "VictorLink64 code. Their layouts and behaviour are modelled on the official on-screen controls of Dolphin " +
            "Android and PPSSPP (both GPL v2.0 or later); no code was copied from them.\n\n" +
            "The APK Player's 32-bit helper app runs apps through the BlackBox virtual container core " +
            "(NewBlackbox, a maintained fork of BlackBox, https://github.com/ALEX5402/NewBlackbox), licensed under the " +
            "Apache License 2.0, modified for VictorLink64, with the xDL (MIT) and Dobby (Apache 2.0) native libraries. " +
            "See NOTICE in the source repository.\n\n" +
            "Chess engine and all VictorLink64 screens are original code.\n\n" +
            "This program is distributed WITHOUT ANY WARRANTY. See the GNU GPL v3 " +
            "(https://www.gnu.org/licenses/gpl-3.0.html) for details. Source code: " +
            "https://github.com/Victorious93/VictorLink64",
            color = Muted, fontSize = 13.sp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Optional Shizuku support uses the Shizuku API by RikkaApps (https://github.com/RikkaApps/Shizuku-API), " +
            "licensed under the Apache License 2.0. Shizuku itself is a separate app and is never required.",
            color = Muted, fontSize = 13.sp
        )
    }
}
