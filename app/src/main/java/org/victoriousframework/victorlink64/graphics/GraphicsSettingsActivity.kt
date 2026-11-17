package org.victoriousframework.victorlink64.graphics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.victoriousframework.victorlink64.shizuku.ShizukuBridge
import org.victoriousframework.victorlink64.shizuku.ShizukuController
import org.victoriousframework.victorlink64.shizuku.ShizukuState
import org.victoriousframework.victorlink64.shizuku.Tweak
import org.victoriousframework.victorlink64.ui.Muted
import org.victoriousframework.victorlink64.ui.Navy
import org.victoriousframework.victorlink64.ui.NavyCard
import org.victoriousframework.victorlink64.ui.NeonBlue
import org.victoriousframework.victorlink64.ui.SettingRow
import org.victoriousframework.victorlink64.ui.ToggleRow
import org.victoriousframework.victorlink64.ui.VictorTheme
import rikka.shizuku.Shizuku

/** Graphics & performance: Auto Best Settings, per-emulator and per-game picture quality, FPS counter, Shizuku boosts. */
class GraphicsSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VictorTheme { GraphicsApp(onClose = { finish() }) } }
    }
}

private sealed interface Screen {
    data object Main : Screen
    data class Emulator(val system: GfxSystem) : Screen
    data class Game(val key: String) : Screen
    data object Shizuku : Screen
}

@Composable
private fun GraphicsApp(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { GraphicsStores.of(ctx) }
    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    var rev by remember { mutableIntStateOf(0) }
    val device by produceState<DeviceProfile?>(null) { value = withContext(Dispatchers.IO) { DeviceProfiler.profile(ctx) } }
    val title = when (val s = screen) {
        Screen.Main -> "Graphics & performance"
        is Screen.Emulator -> "${s.system.label} graphics"
        is Screen.Game -> store.game(s.key)?.name ?: "Game graphics"
        Screen.Shizuku -> "Shizuku boosts"
    }
    val back = { if (screen == Screen.Main) onClose() else screen = Screen.Main }
    BackHandler { back() }
    Scaffold(
        containerColor = Navy,
        topBar = {
            Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = screen) {
                Screen.Main -> MainScreen(store, device, rev, { screen = it }) { rev++ }
                is Screen.Emulator -> EmulatorScreen(store, s.system, device) { rev++ }
                is Screen.Game -> GameScreen(store, s.key, { screen = Screen.Main }) { rev++ }
                Screen.Shizuku -> ShizukuScreen(device)
            }
        }
    }
}

private fun tierName(system: GfxSystem, tier: Int?) = tier?.let { Presets.preset(system, it).name } ?: "none yet"

private fun deviceNow(ctx: Context, device: DeviceProfile?): Pair<DeviceClass, Int>? =
    device?.let { it.deviceClass to DeviceProfiler.thermalStatus(ctx) }

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NavyCard).padding(14.dp)) { content() }
}

@Composable
private fun MainScreen(store: GraphicsStore, device: DeviceProfile?, rev: Int, go: (Screen) -> Unit, changed: () -> Unit) {
    val ctx = LocalContext.current
    val appPrefs = remember { ctx.getSharedPreferences(GraphicsStores.APP_PREFS, Context.MODE_PRIVATE) }
    val global = remember(rev) { store.global() }
    var fps by remember { mutableStateOf(appPrefs.getBoolean(GraphicsStores.KEY_SHOW_FPS, false)) }
    val games = remember(rev) { store.games() }
    val shizukuState = remember(rev) { ShizukuBridge.state(ctx) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Card {
            Text("Your phone", color = NeonBlue, fontWeight = FontWeight.Bold)
            if (device == null) Text("Checking…", color = Muted) else {
                Text("${device.manufacturer} ${device.model}", color = Color.White)
                Text("Chip: ${device.soc.ifBlank { "unknown" }}", color = Muted, fontSize = 13.sp)
                Text("Graphics: ${device.gpu.ifBlank { "unknown" }}", color = Muted, fontSize = 13.sp)
                Text("Memory: ${"%.1f".format(device.ramMb / 1024f)} GB   Screen: ${device.refreshHz.toInt()} Hz (up to ${device.peakRefreshHz.toInt()} Hz)", color = Muted, fontSize = 13.sp)
                Text("Heat: ${thermalWord(DeviceProfiler.thermalStatus(ctx))}", color = Muted, fontSize = 13.sp)
                Text("Treated as: ${device.deviceClass.label}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))
        ToggleRow("Auto Best Settings", global.auto) { store.setAuto(it); changed() }
        Text(
            "On by default. Picks the sharpest picture your phone can run at full speed for each game, steps down if the game " +
                "slows or the phone gets hot, and remembers what worked. Turn it off to choose everything yourself.",
            color = Muted, fontSize = 13.sp,
        )
        ToggleRow("Show FPS counter", fps) { fps = it; appPrefs.edit().putBoolean(GraphicsStores.KEY_SHOW_FPS, it).apply() }
        Text("Shows frames per second over PSP, GameCube and SNES games. N64 has its own counter under N64 touch controls.", color = Muted, fontSize = 13.sp)

        SectionTitle("Quality per emulator")
        GfxSystem.entries.forEach { s ->
            SettingRow(s.label, emulatorSummary(s, global, device, ctx)) { go(Screen.Emulator(s)) }
        }

        SectionTitle("Games with saved settings")
        if (games.isEmpty()) Text("Games you play appear here with what Auto Best Settings learned. You can override any of them.", color = Muted, fontSize = 13.sp)
        games.forEach { g -> SettingRow(g.name, "${g.system.label} · ${gameSummary(g, global)}") { go(Screen.Game(g.key)) } }

        SectionTitle("Optional extras")
        SettingRow("Shizuku boosts", "Off by default. ${shizukuState.label}.") { go(Screen.Shizuku) }
        Spacer(Modifier.height(24.dp))
    }
}

private fun thermalWord(status: Int) = when (status) {
    Thermal.NONE -> "cool"
    Thermal.LIGHT -> "slightly warm"
    Thermal.MODERATE -> "warm (Auto will lower quality)"
    else -> "hot (Auto will lower quality)"
}

private fun emulatorSummary(s: GfxSystem, global: GlobalGraphics, device: DeviceProfile?, ctx: Context): String {
    if (!global.auto) return "Manual choices"
    val d = deviceNow(ctx, device) ?: return "Auto Best Settings"
    val r = GraphicsResolver.resolve(s, null, global, d.first, d.second)
    return "Auto: starts at ${r.label}, may climb to ${Presets.preset(s, r.maxTier).name}"
}

private fun gameSummary(g: GameRecord, global: GlobalGraphics): String {
    val mode = when (g.auto) { true -> "Auto"; false -> "Manual"; null -> if (global.auto) "Auto" else "Manual" }
    val learned = if (mode == "Auto") ", learned: ${tierName(g.system, g.learnedTier)}" else ""
    return mode + learned + if (g.hasOverride) " (custom)" else ""
}

@Composable
private fun EmulatorScreen(store: GraphicsStore, system: GfxSystem, device: DeviceProfile?, changed: () -> Unit) {
    val ctx = LocalContext.current
    var rev by remember { mutableIntStateOf(0) }
    val global = remember(rev) { store.global() }
    val manual = global.manualFor(system)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Card {
            Text(if (global.auto) "Auto Best Settings is on" else "Auto Best Settings is off", color = NeonBlue, fontWeight = FontWeight.Bold)
            Text(emulatorSummary(system, global, device, ctx), color = Color.White, fontSize = 13.sp)
            Text(
                "The choices below are used when Auto Best Settings is off, and for any game you set to Manual. " +
                    "Auto always keeps to the same picture options, so these never fight with it.",
                color = Muted, fontSize = 13.sp,
            )
        }
        SectionTitle("Manual choices for ${system.label}")
        SettingsEditor(system, manual) { store.setSystemManual(system, it); rev++; changed() }
        OutlinedButton(onClick = { store.setSystemManual(system, null); rev++; changed() }) { Text("Reset to defaults", color = NeonBlue) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GameScreen(store: GraphicsStore, key: String, leave: () -> Unit, changed: () -> Unit) {
    var rev by remember { mutableIntStateOf(0) }
    val rec = remember(rev) { store.game(key) }
    val global = remember(rev) { store.global() }
    if (rec == null) { Text("This game is no longer saved.", color = Muted, modifier = Modifier.padding(16.dp)); return }
    val mode = when (rec.auto) { null -> 0; true -> 1; false -> 2 }
    fun update(f: (GameRecord) -> GameRecord) { store.updateGame(key, f); rev++; changed() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Card {
            Text("${rec.system.label} · ${rec.name}", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text("Learned best preset: ${tierName(rec.system, rec.learnedTier)}", color = Muted, fontSize = 13.sp)
            Text("Too heavy: ${rec.knownBadTier?.let { tierName(rec.system, it) } ?: "nothing found yet"}", color = Muted, fontSize = 13.sp)
            if (rec.learnedTier != null || rec.knownBadTier != null) {
                OutlinedButton(onClick = { update { it.copy(learnedTier = null, knownBadTier = null) } }) { Text("Forget what was learned", color = NeonBlue) }
            }
        }
        SectionTitle("How this game picks its picture")
        listOf("Same as the app setting (${if (global.auto) "Auto" else "Manual"})", "Always Auto", "Manual for this game").forEachIndexed { i, label ->
            Row(Modifier.fillMaxWidth().clickable {
                update { it.copy(auto = when (i) { 0 -> null; 1 -> true; else -> false }) }
            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = mode == i, onClick = { update { it.copy(auto = when (i) { 0 -> null; 1 -> true; else -> false }) } },
                    colors = RadioButtonDefaults.colors(selectedColor = NeonBlue, unselectedColor = Muted),
                )
                Text(label, color = Color.White)
            }
        }
        val effectiveManual = rec.auto == false || (rec.auto == null && !global.auto)
        if (effectiveManual) {
            SectionTitle("Picture for this game")
            SettingsEditor(rec.system, rec.manual ?: global.manualFor(rec.system)) { s -> update { it.copy(manual = s) } }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { store.removeGame(key); changed(); leave() }) { Text("Remove saved settings for this game", color = NeonBlue) }
        Spacer(Modifier.height(24.dp))
    }
}

private const val SHIZUKU_PERMISSION_CODE = 4510

@Composable
private fun ShizukuScreen(device: DeviceProfile?) {
    val ctx = LocalContext.current
    var rev by remember { mutableIntStateOf(0) }
    val state = remember(rev) { ShizukuBridge.state(ctx) }
    DisposableEffect(Unit) {
        val l = Shizuku.OnRequestPermissionResultListener { _, _ -> rev++ }
        ShizukuBridge.addPermissionListener(l)
        onDispose { ShizukuBridge.removePermissionListener(l) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Card {
            Text(state.label, color = NeonBlue, fontWeight = FontWeight.Bold)
            Text(
                "Shizuku is a free, separate app that lets VictorLink64 change a few system settings for you, without rooting. " +
                    "These boosts are optional and all off by default. Everything in VictorLink64 works without Shizuku.",
                color = Muted, fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            when (state) {
                ShizukuState.NOT_INSTALLED -> Button(
                    onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                ) { Text("About Shizuku") }
                ShizukuState.NOT_RUNNING -> {
                    Text("Open the Shizuku app and start it (its own screen explains how), then come back here.", color = Color.White, fontSize = 13.sp)
                    Button(
                        onClick = { ctx.packageManager.getLaunchIntentForPackage(ShizukuBridge.PACKAGE)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                    ) { Text("Open Shizuku") }
                    OutlinedButton(onClick = { rev++ }) { Text("Check again", color = NeonBlue) }
                }
                ShizukuState.NEEDS_PERMISSION -> Button(
                    onClick = { ShizukuBridge.requestPermission(SHIZUKU_PERMISSION_CODE) },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
                ) { Text("Allow VictorLink64") }
                ShizukuState.READY -> Text("Choose which boosts to use below.", color = Color.White, fontSize = 13.sp)
            }
        }
        SectionTitle("Boosts")
        val ready = state == ShizukuState.READY
        Tweak.entries.forEach { t ->
            var on by remember(t, rev) { mutableStateOf(ShizukuController.isEnabled(ctx, t)) }
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.title, color = if (ready) Color.White else Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(
                        checked = on && ready, enabled = ready,
                        onCheckedChange = { on = it; ShizukuController.setEnabled(ctx, t, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = NeonBlue),
                    )
                }
                Text(t.plain, color = Muted, fontSize = 13.sp)
                if (Build.VERSION.SDK_INT < t.minSdk) Text("Needs a newer Android version than this phone has.", color = Muted, fontSize = 12.sp)
                else if (t == Tweak.REFRESH && device != null && device.peakRefreshHz <= 60f) Text("This display only runs at 60 Hz, so this does nothing here.", color = Muted, fontSize = 12.sp)
                ShizukuController.lastStatus(ctx, t)?.let { Text("Last result: $it", color = Muted, fontSize = 12.sp) }
            }
            HorizontalDivider(color = NavyCard)
        }
        Spacer(Modifier.height(24.dp))
        Text("Shizuku API © RikkaApps, Apache License 2.0.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
    }
}
