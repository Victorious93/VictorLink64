package org.victoriousframework.victorlink64.connect

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.victoriousframework.victorlink64.chess.ChessActivity
import org.victoriousframework.victorlink64.ui.*
import paulscode.android.mupen64plusae.BuildConfig

private fun btPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun hasAll(ctx: Context, perms: Array<String>) = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

fun openOnlineChess(ctx: Context, hostWhite: Boolean = true) {
    ctx.startActivity(Intent(ctx, ChessActivity::class.java).putExtra(ChessActivity.EXTRA_ONLINE, true)
        .putExtra(ChessActivity.EXTRA_HOST_WHITE, hostWhite).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
}

@SuppressLint("MissingPermission")
@Composable
fun ConnectScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by ConnectSession.status.collectAsState()
    val prefs = remember { ctx.getSharedPreferences("victorlink64_prefs", Context.MODE_PRIVATE) }
    var nick by remember { mutableStateOf(prefs.getString("nick", null) ?: (Build.MODEL ?: "Player").take(16)) }
    var code by remember { mutableStateOf("") }
    var btDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var showBtPicker by remember { mutableStateOf(false) }
    var pendingBt by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) pendingBt?.invoke() else Toast.makeText(ctx, "Bluetooth permission is needed", Toast.LENGTH_LONG).show()
        pendingBt = null
    }
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    fun withBt(action: () -> Unit) {
        val p = btPermissions()
        if (hasAll(ctx, p)) action() else { pendingBt = action; permLauncher.launch(p) }
    }
    fun saveNick() = prefs.edit().putString("nick", nick).apply()

    // Incoming invites: the friend tapped "Play chess"
    LaunchedEffect(Unit) {
        ConnectSession.messages.collect { m ->
            if (m.optString("t") == "chess_invite") openOnlineChess(ctx, m.optBoolean("hostWhite", true))
        }
    }

    // Bluetooth discovery receiver while the picker is open
    DisposableEffect(showBtPicker) {
        if (!showBtPicker) return@DisposableEffect onDispose {}
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION") val d: BluetoothDevice? = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d != null && btDevices.none { it.address == d.address }) btDevices = btDevices + d
            }
        }
        ContextCompat.registerReceiver(ctx, rx, IntentFilter(BluetoothDevice.ACTION_FOUND), ContextCompat.RECEIVER_EXPORTED)
        val a = BluetoothTransport.adapter(ctx)
        runCatching { btDevices = a?.bondedDevices?.toList() ?: emptyList(); a?.startDiscovery() }
        onDispose { runCatching { ctx.unregisterReceiver(rx); a?.cancelDiscovery() } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Connect", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Play with friends far away or right next to you", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        StatusCard(status, onLeave = { ConnectSession.stop() }, onChess = {
            ConnectSession.send("chess_invite") { put("hostWhite", true) }
            openOnlineChess(ctx, true)
        })
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(nick, { nick = it.take(16) }, label = { Text("Your name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))

        Section("Online · anywhere", Icons.Filled.Public, NeonBlue) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    saveNick()
                    scope.launch {
                        val c = withContext(Dispatchers.IO) { runCatching { OnlineTransport.newRoomCode(BuildConfig.RELAY_URL) } }
                        c.onSuccess { ConnectSession.start(OnlineTransport(BuildConfig.RELAY_URL, it, nick, true)) }
                            .onFailure { Toast.makeText(ctx, "Relay unreachable: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)) { Text("Create room") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(code, { code = OnlineTransport.normalize(it).take(6) }, label = { Text("Room code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OutlinedButton(enabled = OnlineTransport.isValidCode(code), onClick = {
                    saveNick(); ConnectSession.start(OnlineTransport(BuildConfig.RELAY_URL, code, nick, false))
                }) { Text("Join") }
            }
        }
        Section("Nearby · same Wi-Fi", Icons.Filled.Wifi, NeonBlue) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { saveNick(); ConnectSession.start(LanTransport(ctx, true, nick)) },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)) { Text("Host") }
                OutlinedButton(onClick = { saveNick(); ConnectSession.start(LanTransport(ctx, false, nick)) }) { Text("Join") }
            }
        }
        Section("Nearby · Bluetooth", Icons.Filled.Bluetooth, NeonRed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    withBt {
                        discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
                        ConnectSession.start(BluetoothTransport(ctx, true))
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = NeonRed)) { Text("Host") }
                OutlinedButton(onClick = { withBt { showBtPicker = true } }) { Text("Join") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Games: Chess works over every connection. N64 / SNES netplay is coming soon.", color = Muted, fontSize = 12.sp)
    }

    if (showBtPicker) {
        AlertDialog(onDismissRequest = { showBtPicker = false }, containerColor = NavyCard,
            title = { Text("Choose your friend's device", color = Color.White) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (btDevices.isEmpty()) Text("Searching… ask your friend to tap Host.", color = Muted)
                    btDevices.forEach { d ->
                        Text((runCatching { d.name }.getOrNull() ?: d.address), color = Color.White, modifier = Modifier.fillMaxWidth()
                            .clickable { showBtPicker = false; ConnectSession.start(BluetoothTransport(ctx, false, d)) }.padding(vertical = 10.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBtPicker = false }) { Text("Cancel", color = Muted) } })
    }
}

@Composable
private fun StatusCard(s: SessionStatus, onLeave: () -> Unit, onChess: () -> Unit) {
    val color = when (s.state) {
        PeerState.CONNECTED -> Color(0xFF3DDC84); PeerState.WAITING -> NeonBlue
        PeerState.ERROR, PeerState.DISCONNECTED -> NeonRed; PeerState.IDLE -> Muted
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(NavyCard)
        .border(1.dp, color.copy(alpha = .6f), RoundedCornerShape(16.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
            Spacer(Modifier.width(8.dp))
            Text(s.text, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        if (s.kind != null) Text("${s.kind.label} · ${if (s.isHost) "Host (plays White)" else "Guest (plays Black)"}", color = Muted, fontSize = 12.sp)
        s.roomCode?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = NeonBlue, fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, letterSpacing = 6.sp)
        }
        if (s.state != PeerState.IDLE) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.state == PeerState.CONNECTED) Button(onClick = onChess, colors = ButtonDefaults.buttonColors(containerColor = NeonRed)) { Text("♞  Play chess") }
                OutlinedButton(onClick = onLeave) { Text("Leave") }
            }
        }
    }
}

@Composable
private fun Section(title: String, icon: ImageVector, tint: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(NavyCard).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint); Spacer(Modifier.width(8.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}
