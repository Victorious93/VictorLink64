package org.victoriousframework.victorlink64.pc

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.mdns.JmDNSDiscoveryAgent
import com.limelight.nvstream.mdns.MdnsComputer
import com.limelight.nvstream.mdns.MdnsDiscoveryAgent
import com.limelight.nvstream.mdns.MdnsDiscoveryListener
import com.limelight.nvstream.mdns.NsdManagerDiscoveryAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.victoriousframework.victorlink64.ui.Muted
import org.victoriousframework.victorlink64.ui.Navy
import org.victoriousframework.victorlink64.ui.NavyCard
import org.victoriousframework.victorlink64.ui.NeonBlue
import org.victoriousframework.victorlink64.ui.PlayBadge
import org.victoriousframework.victorlink64.ui.VictorTheme
import org.victoriousframework.victorlink64.ui.clip16

/*
 * Victorious Framework: "PC Games" — stream games from your own PC (Sunshine or NVIDIA
 * GameStream) to this phone. Original VictorLink64 screens on top of the vendored
 * Moonlight core (moonlight-stream/moonlight-android, GPL-3.0).
 */
class PcGamesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VictorTheme { PcGamesApp(onExit = { finish() }) } }
    }

    companion object {
        fun launch(c: Context) = c.startActivity(Intent(c, PcGamesActivity::class.java))
    }
}

private sealed interface PcScreen {
    data object Hosts : PcScreen
    data class Apps(val uuid: String) : PcScreen
    data object Settings : PcScreen
    data object Help : PcScreen
}

private enum class Reach { CHECKING, ONLINE, OFFLINE }

@Composable
private fun PcGamesApp(onExit: () -> Unit) {
    var screen by remember { mutableStateOf<PcScreen>(PcScreen.Hosts) }
    BackHandler { if (screen == PcScreen.Hosts) onExit() else screen = PcScreen.Hosts }
    Surface(color = Navy, modifier = Modifier.fillMaxSize()) {
        when (val s = screen) {
            PcScreen.Hosts -> HostsScreen(
                onOpen = { screen = PcScreen.Apps(it) }, onSettings = { screen = PcScreen.Settings },
                onHelp = { screen = PcScreen.Help }, onBack = onExit
            )
            is PcScreen.Apps -> AppsScreen(s.uuid, onBack = { screen = PcScreen.Hosts })
            PcScreen.Settings -> SettingsScreen(onBack = { screen = PcScreen.Hosts })
            PcScreen.Help -> HelpScreen(onBack = { screen = PcScreen.Hosts })
        }
    }
}

@Composable
private fun TopRow(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(8.dp, 8.dp, 8.dp, 0.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White) }
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun HostsScreen(onOpen: (String) -> Unit, onSettings: () -> Unit, onHelp: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { PcRepo(ctx) }
    val scope = rememberCoroutineScope()
    var hosts by remember { mutableStateOf(repo.hosts()) }
    val reach = remember { mutableStateMapOf<String, Reach>() }
    var searching by remember { mutableStateOf(true) }
    var showAdd by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf<PcHost?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun refresh(h: PcHost) {
        reach[h.uuid] = Reach.CHECKING
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { repo.probe(h.address, h.httpPort, h, h.manual) } }
            r.onSuccess { nh -> if (nh.uuid == h.uuid) { repo.upsert(nh) }; reach[h.uuid] = Reach.ONLINE; hosts = repo.hosts() }
                .onFailure { reach[h.uuid] = Reach.OFFLINE }
        }
    }

    // Check saved PCs, then look for new ones on the Wi-Fi with mDNS.
    DisposableEffect(Unit) {
        hosts.forEach { refresh(it) }
        val listener = object : MdnsDiscoveryListener {
            override fun notifyComputerAdded(c: MdnsComputer) {
                val addr = (c.localAddress ?: c.ipv6Address)?.hostAddress ?: return
                val port = if (c.port > 0) c.port else PcHost.DEFAULT_HTTP_PORT
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { repo.probe(addr, port) } }
                    r.onSuccess { nh -> repo.upsert(nh); hosts = repo.hosts(); reach[nh.uuid] = Reach.ONLINE }
                }
            }
            override fun notifyDiscoveryFailure(e: Exception) {
                scope.launch { searching = false }
            }
        }
        val agent: MdnsDiscoveryAgent? = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) JmDNSDiscoveryAgent(ctx.applicationContext, listener)
            else NsdManagerDiscoveryAgent(ctx.applicationContext, listener)
        }.getOrNull()
        runCatching { agent?.startDiscovery(5000) }.onFailure { searching = false }
        onDispose { runCatching { agent?.stopDiscovery() } }
    }

    Column(Modifier.fillMaxSize()) {
        TopRow("PC Games", onBack) {
            IconButton(onClick = onHelp) { Icon(Icons.Filled.HelpOutline, "How to set up your PC", tint = NeonBlue) }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Tune, "Stream settings", tint = NeonBlue) }
        }
        Column(Modifier.padding(16.dp, 4.dp)) {
            Text("Play games from your own PC on this phone over Wi-Fi.", color = Muted)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { showAdd = true }, colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add PC by address")
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = onHelp) { Text("Set up my PC", color = NeonBlue) }
            }
            Spacer(Modifier.height(12.dp))
            if (searching) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = NeonBlue, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp)); Text("Looking for PCs on your Wi-Fi…", color = Muted, fontSize = 13.sp)
            }
        }
        if (hosts.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.Computer, null, tint = NeonBlue, modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(12.dp))
                Text("No PCs yet", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Install Sunshine on your PC and keep this phone on the same Wi-Fi. " +
                    "Your PC shows up here on its own, or tap “Add PC by address”.", color = Muted, modifier = Modifier.padding(8.dp))
            }
        } else LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(hosts, key = { it.uuid }) { h ->
                HostRow(h, reach[h.uuid] ?: Reach.CHECKING,
                    onTap = {
                        when {
                            reach[h.uuid] == Reach.OFFLINE -> { notice = "Can't reach ${h.name}. Make sure it's on, Sunshine is running, and you're on the same Wi-Fi."; refresh(h) }
                            h.paired -> onOpen(h.uuid)
                            else -> pairing = h
                        }
                    },
                    onRefresh = { refresh(h) },
                    onUnpair = { scope.launch { withContext(Dispatchers.IO) { repo.unpair(h) }; hosts = repo.hosts() } },
                    onForget = { repo.remove(h.uuid); hosts = repo.hosts() })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showAdd) AddHostDialog(repo, onDone = { added ->
        showAdd = false
        if (added != null) { hosts = repo.hosts(); reach[added.uuid] = Reach.ONLINE; if (!added.paired) pairing = added }
    })
    pairing?.let { h -> PairDialog(repo, h, onDone = { ok -> pairing = null; hosts = repo.hosts(); if (ok) onOpen(h.uuid) }) }
    notice?.let { n -> AlertDialog(onDismissRequest = { notice = null }, confirmButton = { TextButton({ notice = null }) { Text("OK", color = NeonBlue) } },
        text = { Text(n) }, containerColor = NavyCard) }
}

@Composable
private fun HostRow(h: PcHost, r: Reach, onTap: () -> Unit, onRefresh: () -> Unit, onUnpair: () -> Unit, onForget: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip16().background(NavyCard)
            .border(1.dp, NeonBlue.copy(alpha = .35f), RoundedCornerShape(16.dp))
            .clickable(onClick = onTap).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip16().background(NeonBlue.copy(alpha = .18f)), Alignment.Center) {
            Icon(Icons.Filled.Computer, null, tint = NeonBlue)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(h.name, color = Color.White, fontWeight = FontWeight.SemiBold)
            val status = when (r) {
                Reach.CHECKING -> "Checking…"
                Reach.OFFLINE -> "Can't reach it right now"
                Reach.ONLINE -> if (h.paired) "Ready" else "Tap to pair"
            }
            Text("${h.address} · $status", color = Muted, fontSize = 12.sp)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Muted) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Check again") }, onClick = { menu = false; onRefresh() })
                if (h.paired) DropdownMenuItem(text = { Text("Unpair") }, onClick = { menu = false; onUnpair() })
                DropdownMenuItem(text = { Text("Forget this PC") }, onClick = { menu = false; onForget() })
            }
        }
        if (h.paired && r == Reach.ONLINE) PlayBadge("Open ${h.name}")
    }
}

@Composable
private fun AddHostDialog(repo: PcRepo, onDone: (PcHost?) -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDone(null) }, containerColor = NavyCard,
        title = { Text("Add PC by address") },
        text = {
            Column {
                Text("Type your PC's IP address. You can find it in Sunshine, or on Windows by running “ipconfig”.", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(text, { text = it; err = null }, singleLine = true, enabled = !busy,
                    placeholder = { Text("192.168.1.20") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                if (busy) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = NeonBlue, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp)); Text("Connecting…", color = Muted)
                }
                err?.let { Text(it, color = Color(0xFFFFB4AB), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            Button(enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = NeonBlue), onClick = {
                val parsed = parseHostInput(text)
                if (parsed == null) { err = "That doesn't look like an address. Example: 192.168.1.20"; return@Button }
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { repo.probe(parsed.first, parsed.second, manual = true) } }
                    busy = false
                    r.onSuccess { h -> repo.upsert(h); onDone(repo.host(h.uuid) ?: h) }
                        .onFailure { err = "Couldn't reach a PC at that address. Check that Sunshine is running and both devices are on the same network." }
                }
            }) { Text("Add") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { onDone(null) }) { Text("Cancel", color = Muted) } }
    )
}

@Composable
private fun PairDialog(repo: PcRepo, h: PcHost, onDone: (Boolean) -> Unit) {
    val pin = remember { PairingManager.generatePinString() }
    var result by remember { mutableStateOf<PcRepo.PairResult?>(null) }
    var running by remember { mutableStateOf(true) }
    LaunchedEffect(h.uuid) {
        val r = withContext(Dispatchers.IO) { runCatching { repo.pair(h, pin).first }.getOrDefault(PcRepo.PairResult.FAILED) }
        running = false; result = r
        if (r == PcRepo.PairResult.PAIRED) onDone(true)
    }
    AlertDialog(
        onDismissRequest = { if (!running) onDone(false) }, containerColor = NavyCard,
        title = { Text("Pair with ${h.name}") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (running) {
                    Text("On your PC, open Sunshine, go to the PIN page, and type this PIN:", color = Muted)
                    Spacer(Modifier.height(12.dp))
                    Text(pin, color = NeonBlue, fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Sunshine's page is at https://localhost:47990 on the PC. Give this phone any name you like.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(color = NeonBlue, modifier = Modifier.fillMaxWidth())
                } else Text(result?.message ?: "", color = Color.White)
            }
        },
        confirmButton = { if (!running) TextButton({ onDone(result == PcRepo.PairResult.PAIRED) }) { Text("OK", color = NeonBlue) } }
    )
}

@Composable
private fun AppsScreen(uuid: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { PcRepo(ctx) }
    val scope = rememberCoroutineScope()
    val host = remember { repo.host(uuid) }
    var apps by remember { mutableStateOf<List<NvApp>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val art = remember { mutableStateMapOf<Int, ImageBitmap>() }
    var reloadKey by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        if (host == null) { err = "This PC was removed."; return@LaunchedEffect }
        err = null; apps = null
        withContext(Dispatchers.IO) { runCatching { repo.apps(host) } }
            .onSuccess { list ->
                apps = list
                list.forEach { a ->
                    launch(Dispatchers.IO) {
                        repo.boxArt(host, a)?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }?.let { art[a.appId] = it }
                    }
                }
            }
            .onFailure { err = "Couldn't load the game list. Make sure the PC is on and still paired." }
    }

    Column(Modifier.fillMaxSize()) {
        TopRow(host?.name ?: "PC", onBack) {
            IconButton(onClick = { reloadKey++ }) { Icon(Icons.Filled.Refresh, "Reload", tint = NeonBlue) }
        }
        Text("Pick what to play. It starts on your PC and streams here.", color = Muted, modifier = Modifier.padding(16.dp, 0.dp))
        Spacer(Modifier.height(10.dp))
        when {
            err != null -> Text(err!!, color = Color.White, modifier = Modifier.padding(16.dp))
            apps == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = NeonBlue) }
            apps!!.isEmpty() -> Text("No apps on this PC yet. Add games in Sunshine's Applications page.", color = Muted, modifier = Modifier.padding(16.dp))
            else -> LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(apps!!, key = { it.appId }) { a ->
                    Row(
                        Modifier.fillMaxWidth().clip16().background(NavyCard)
                            .border(1.dp, NeonBlue.copy(alpha = .35f), RoundedCornerShape(16.dp))
                            .clickable { host?.let { PcStreamActivity.launch(ctx, it.uuid, a.appId, a.appName) } }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(width = 48.dp, height = 64.dp).clip16().background(NeonBlue.copy(alpha = .18f)), Alignment.Center) {
                            val img = art[a.appId]
                            if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else Icon(Icons.Filled.SportsEsports, null, tint = NeonBlue)
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(a.appName, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        PlayBadge("Play ${a.appName}")
                    }
                }
                item {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val ok = host != null && withContext(Dispatchers.IO) { repo.quitApp(host) }
                            info = if (ok) "The game on your PC was closed." else "Nothing was closed. No game may be running."
                        }
                    }, modifier = Modifier.padding(vertical = 12.dp)) { Text("Close the running game on my PC", color = NeonBlue) }
                }
            }
        }
    }
    info?.let { n -> AlertDialog(onDismissRequest = { info = null }, confirmButton = { TextButton({ info = null }) { Text("OK", color = NeonBlue) } },
        text = { Text(n) }, containerColor = NavyCard) }
}

@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NeonBlue, selectedLabelColor = Navy, labelColor = Color.White))
        }
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { PcRepo(ctx) }
    var s by remember { mutableStateOf(repo.settings) }
    fun set(n: StreamSettings) { s = n; repo.settings = n }
    Column(Modifier.fillMaxSize()) {
        TopRow("Stream settings", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Section("Picture size")
            ChoiceRow(StreamRes.entries, s.res, { it.label }) { set(s.copy(res = it)) }
            Note("1080p looks sharpest but needs strong Wi-Fi. 720p is a good start.")
            Section("Frame rate")
            ChoiceRow(StreamSettings.FPS_CHOICES, s.fps, { "$it FPS" }) { set(s.copy(fps = it)) }
            Section("Quality (bitrate)")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.bitrateKbps == 0) "Automatic (${s.effectiveBitrateKbps / 1000} Mbps)" else "${s.bitrateKbps / 1000} Mbps",
                    color = Color.White, modifier = Modifier.weight(1f))
                Text("Auto", color = Muted); Spacer(Modifier.width(6.dp))
                Switch(s.bitrateKbps == 0, { auto -> set(s.copy(bitrateKbps = if (auto) 0 else s.effectiveBitrateKbps)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = NeonBlue))
            }
            if (s.bitrateKbps != 0) Slider(
                value = s.bitrateKbps.toFloat(), onValueChange = { set(s.copy(bitrateKbps = (it / 1000).toInt() * 1000)) },
                valueRange = StreamSettings.MIN_KBPS.toFloat()..StreamSettings.MAX_KBPS.toFloat(),
                colors = SliderDefaults.colors(thumbColor = NeonBlue, activeTrackColor = NeonBlue)
            )
            Note("Higher looks better but can stutter on weak Wi-Fi. Lower it if the picture freezes.")
            Section("Video format")
            StreamCodec.entries.forEach { c -> Radio(c.label, null, s.codec == c) { set(s.copy(codec = c)) } }
            Section("Frame pacing")
            FramePacing.entries.forEach { p -> Radio(p.label, p.detail, s.pacing == p) { set(s.copy(pacing = p)) } }
            Section("Controls and extras")
            Toggle("On-screen controller", "Shows touch buttons. Turn off to use the screen as a mouse.", s.touchControls) { set(s.copy(touchControls = it)) }
            Toggle("Show stream stats", "Frame rate, lag and dropped frames in a corner.", s.showStats) { set(s.copy(showStats = it)) }
            Toggle("Also play sound on the PC", null, s.playAudioOnPc) { set(s.copy(playAudioOnPc = it)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable private fun Section(t: String) { Spacer(Modifier.height(18.dp)); Text(t, color = NeonBlue, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)) }
@Composable private fun Note(t: String) { Text(t, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }

@Composable
private fun Radio(title: String, sub: String?, sel: Boolean, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(sel, onPick, colors = RadioButtonDefaults.colors(selectedColor = NeonBlue))
        Column { Text(title, color = Color.White); sub?.let { Text(it, color = Muted, fontSize = 12.sp) } }
    }
}

@Composable
private fun Toggle(title: String, sub: String?, v: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = Color.White); sub?.let { Text(it, color = Muted, fontSize = 12.sp) } }
        Switch(v, on, colors = SwitchDefaults.colors(checkedTrackColor = NeonBlue))
    }
}

@Composable
private fun HelpScreen(onBack: () -> Unit) {
    val steps = listOf(
        "Install Sunshine on your PC" to "Sunshine is free. Download it from github.com/LizardByte/Sunshine/releases " +
            "(Windows, Linux or macOS) and install it. NVIDIA GeForce Experience GameStream also works on older PCs.",
        "Open Sunshine and make a login" to "Sunshine opens a web page at https://localhost:47990 on the PC. " +
            "Your browser may warn about the page; that's normal for Sunshine. Pick a username and password.",
        "Put both on the same Wi-Fi" to "Connect this phone to the same Wi-Fi or network as the PC. A PC plugged in with a cable is best.",
        "Find your PC here" to "Open PC Games. Your PC should appear by itself. If not, tap “Add PC by address” and type the PC's IP address.",
        "Pair once" to "Tap your PC. This phone shows a 4-digit PIN. In Sunshine, open the PIN page, type the PIN and a name for the phone, then send it.",
        "Pick a game and play" to "Choose “Desktop” or a game you added in Sunshine's Applications page. A controller you plug in or connect by Bluetooth works too.",
        "If it's laggy" to "Lower the quality or picture size in Stream settings, move closer to the router, or use 5 GHz Wi-Fi.",
    )
    Column(Modifier.fillMaxSize()) {
        TopRow("Set up your PC", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            steps.forEachIndexed { i, (t, b) ->
                Row(Modifier.padding(vertical = 8.dp)) {
                    Box(Modifier.size(30.dp).clip16().background(NeonBlue), Alignment.Center) {
                        Text("${i + 1}", color = Navy, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column { Text(t, color = Color.White, fontWeight = FontWeight.Bold); Text(b, color = Muted, fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("PC streaming is powered by Moonlight (moonlight-stream.org), free software under the GNU GPL v3.",
                color = Muted, fontSize = 12.sp)
        }
    }
}
