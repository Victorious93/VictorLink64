package org.victoriousframework.victorlink64.chess

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.victoriousframework.victorlink64.ui.*
import kotlin.math.abs

class ChessActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ONLINE = "online"
        const val EXTRA_HOST_WHITE = "host_white"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val online = intent.getBooleanExtra(EXTRA_ONLINE, false)
        val hostWhite = intent.getBooleanExtra(EXTRA_HOST_WHITE, true)
        setContent { VictorTheme { ChessScreen(onBack = { finish() }, online = online, hostWhiteInit = hostWhite) } }
    }
}

private const val CHESS_PREFS = "victorlink64_chess"

private val LIGHT_SQ = Color(0xFF1C3A6E)
private val DARK_SQ = Color(0xFF0E1D40)

enum class ChessMode { VS_PHONE, PASS_AND_PLAY, ONLINE }

private fun glyph(v: Int): String = when (abs(v)) {
    KING -> "♚"; QUEEN -> "♛"; ROOK -> "♜"; BISHOP -> "♝"; KNIGHT -> "♞"; PAWN -> "♟"; else -> ""
}

@Composable
fun ChessScreen(onBack: () -> Unit, online: Boolean = false, hostWhiteInit: Boolean = true) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(CHESS_PREFS, Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(if (online) ChessMode.ONLINE else ChessMode.valueOf(prefs.getString("mode", ChessMode.VS_PHONE.name)!!)) }
    val peer by org.victoriousframework.victorlink64.connect.ConnectSession.status.collectAsState()
    var hostWhite by remember { mutableStateOf(hostWhiteInit) }
    var difficulty by remember { mutableStateOf(Difficulty.valueOf(prefs.getString("difficulty", Difficulty.MEDIUM.name)!!)) }
    var humanColor by remember { mutableIntStateOf(prefs.getInt("human", WHITE)) }
    var game by remember {
        mutableStateOf(if (online) ChessGame() else prefs.getString("game", null)?.let { runCatching { ChessGame.deserialize(it) }.getOrNull() } ?: ChessGame())
    }
    var version by remember { mutableIntStateOf(0) } // bump to recompose after mutating game
    var selected by remember { mutableIntStateOf(-1) }
    var pendingPromo by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showSetup by remember { mutableStateOf(false) }

    fun save() {
        if (mode == ChessMode.ONLINE) return
        prefs.edit().putString("game", game.serialize()).putString("mode", mode.name)
            .putString("difficulty", difficulty.name).putInt("human", humanColor).apply()
    }
    val net = org.victoriousframework.victorlink64.connect.ConnectSession
    if (mode == ChessMode.ONLINE) humanColor = if (net.isHost == hostWhite) WHITE else BLACK
    fun commit(uci: String, remote: Boolean = false) {
        if (game.play(uci)) {
            selected = -1; version++; save()
            if (mode == ChessMode.ONLINE && !remote) net.send("move") { put("uci", uci); put("ply", game.uciMoves.size) }
        }
    }
    if (mode == ChessMode.ONLINE) LaunchedEffect(Unit) {
        net.messages.collect { m ->
            when (m.optString("t")) {
                "move" -> {
                    val ply = m.optInt("ply")
                    if (ply == game.uciMoves.size + 1 && game.play(m.optString("uci"))) { selected = -1; version++ }
                    else if (ply > game.uciMoves.size) net.send("sync_req")
                }
                "resign" -> { game.resign(m.optInt("color")); version++ }
                "rematch", "chess_invite" -> { hostWhite = m.optBoolean("hostWhite", true); game = ChessGame(); selected = -1; version++ }
                "sync_req" -> net.send("sync") { put("game", game.serialize()); put("hostWhite", hostWhite) }
                "sync" -> runCatching { ChessGame.deserialize(m.optString("game")) }.onSuccess {
                    game = it; hostWhite = m.optBoolean("hostWhite", hostWhite); selected = -1; version++
                }
            }
        }
    }

    val pos = remember(version, game) { game.position }
    val result = remember(version, game) { game.result() }
    val legal = remember(version, game) { game.legalMoves() }
    val aiTurn = mode == ChessMode.VS_PHONE && pos.side != humanColor && result == GameResult.ONGOING
    // Derived rather than stored: a stored flag stuck at true when "New game" cancelled a running search.
    val thinking = aiTurn
    val flipped = if (mode == ChessMode.VS_PHONE || mode == ChessMode.ONLINE) humanColor == BLACK else false

    LaunchedEffect(version, aiTurn, game) {
        if (aiTurn) {
            val m = withContext(Dispatchers.Default) { ChessAi.bestMove(pos, difficulty) }
            m?.let { commit(it.uci()) }
        }
    }

    fun onTap(sq: Int) {
        if (aiTurn || result != GameResult.ONGOING) return
        if (mode == ChessMode.ONLINE && pos.side != humanColor) return
        val v = pos.board[sq]
        if (selected >= 0) {
            val cands = legal.filter { it.from == selected && it.to == sq }
            if (cands.isNotEmpty()) {
                if (cands.any { it.promo != 0 }) pendingPromo = selected to sq else commit(cands[0].uci())
                return
            }
        }
        selected = if (v != 0 && (v > 0) == (pos.side > 0)) sq else -1
    }

    val lastMove = game.uciMoves.lastOrNull()?.let { Move.fromUci(it) }
    val targets = legal.filter { it.from == selected }.map { it.to }.toSet()
    val checkSq = if (pos.inCheck()) pos.kingSquare(pos.side) else -1

    Column(Modifier.fillMaxSize().background(Navy).statusBarsPadding().verticalScroll(rememberScrollState()).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back", color = NeonBlue) }
            Text("Chess", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            if (mode != ChessMode.ONLINE) TextButton(onClick = { showSetup = true }) { Text("New game", color = NeonRed) }
        }
        Text(
            when (mode) {
                ChessMode.VS_PHONE -> "vs Phone · ${difficulty.label}"; ChessMode.PASS_AND_PLAY -> "Pass & play"
                ChessMode.ONLINE -> "vs ${peer.peerName ?: "friend"} · ${peer.kind?.label ?: ""} · you play ${if (humanColor == WHITE) "White" else "Black"}" +
                    if (peer.state != org.victoriousframework.victorlink64.connect.PeerState.CONNECTED) " · ${peer.text}" else ""
            },
            color = Muted, fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        val status = when {
            result != GameResult.ONGOING -> result.text
            thinking -> "Phone is thinking…"
            mode == ChessMode.ONLINE -> (if (pos.side == humanColor) "Your move" else "Waiting for friend…") + (if (pos.inCheck()) " — CHECK" else "")
            else -> (if (pos.side == WHITE) "White" else "Black") + " to move" + if (pos.inCheck()) " — CHECK" else ""
        }
        Text(status, color = if (result != GameResult.ONGOING || pos.inCheck()) NeonRed else Color.White,
            fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))

        // Board
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f).border(2.dp, NeonBlue, RoundedCornerShape(6.dp)).padding(2.dp)) {
            val cell = maxWidth / 8
            Column {
                for (row in 0..7) Row {
                    for (col in 0..7) {
                        val rank = if (flipped) row else 7 - row
                        val file = if (flipped) 7 - col else col
                        val sq = rank * 8 + file
                        val base = if ((rank + file) % 2 == 1) LIGHT_SQ else DARK_SQ
                        val bg = when {
                            sq == selected -> NeonBlue.copy(alpha = .55f)
                            sq == checkSq -> NeonRed.copy(alpha = .6f)
                            lastMove != null && (sq == lastMove.from || sq == lastMove.to) -> Color(0xFF2A4F8F)
                            else -> base
                        }
                        Box(Modifier.size(cell).background(bg).clickable { onTap(sq) }, contentAlignment = Alignment.Center) {
                            val v = pos.board[sq]
                            if (v != 0) Text(glyph(v), fontSize = (cell.value * 0.68f).sp,
                                color = if (v > 0) Color.White else NeonRed)
                            if (sq in targets) Box(Modifier.size(cell / 4).clip(RoundedCornerShape(50))
                                .background(NeonBlue.copy(alpha = .8f)))
                            if (col == 0) Text("${rank + 1}", color = Muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.TopStart).padding(2.dp))
                            if (row == 7) Text("${'a' + file}", color = Muted, fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (mode != ChessMode.ONLINE) OutlinedButton(enabled = !thinking && game.uciMoves.isNotEmpty(), onClick = {
                game.undo()
                if (mode == ChessMode.VS_PHONE && game.position.side != humanColor) game.undo()
                selected = -1; version++; save()
            }) { Text("Undo") }
            OutlinedButton(enabled = result == GameResult.ONGOING && !thinking, onClick = {
                val c = if (mode == ChessMode.VS_PHONE || mode == ChessMode.ONLINE) humanColor else pos.side
                game.resign(c); version++
                if (mode == ChessMode.ONLINE) net.send("resign") { put("color", c) }
            }) { Text("Resign") }
            if (mode == ChessMode.ONLINE && result != GameResult.ONGOING) Button(onClick = {
                hostWhite = !hostWhite; game = ChessGame(); selected = -1; version++
                net.send("rematch") { put("hostWhite", hostWhite) }
            }, colors = ButtonDefaults.buttonColors(containerColor = NeonRed)) { Text("Rematch") }
        }
        Spacer(Modifier.height(8.dp))
        Text(game.uciMoves.chunked(2).mapIndexed { i, p -> "${i + 1}. ${p.joinToString(" ")}" }.joinToString("  "),
            color = Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
    }

    pendingPromo?.let { (from, to) ->
        AlertDialog(onDismissRequest = { pendingPromo = null }, containerColor = NavyCard,
            title = { Text("Promote to", color = Color.White) },
            text = {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    for (t in intArrayOf(QUEEN, ROOK, BISHOP, KNIGHT)) {
                        Text(glyph(t), fontSize = 40.sp, color = NeonBlue, modifier = Modifier.clickable {
                            pendingPromo = null; commit(Move(from, to, t).uci())
                        }.padding(6.dp))
                    }
                }
            }, confirmButton = {})
    }

    if (showSetup) {
        var m by remember { mutableStateOf(mode) }
        var d by remember { mutableStateOf(difficulty) }
        var c by remember { mutableIntStateOf(humanColor) }
        AlertDialog(onDismissRequest = { showSetup = false }, containerColor = NavyCard,
            title = { Text("New game", color = Color.White) },
            text = {
                Column {
                    Chips(listOf("vs Phone", "Pass & play"), if (m == ChessMode.VS_PHONE) 0 else 1) {
                        m = if (it == 0) ChessMode.VS_PHONE else ChessMode.PASS_AND_PLAY
                    }
                    if (m == ChessMode.VS_PHONE) {
                        Spacer(Modifier.height(8.dp)); Text("Difficulty", color = Muted)
                        Chips(Difficulty.entries.map { it.label }, d.ordinal) { d = Difficulty.entries[it] }
                        Spacer(Modifier.height(8.dp)); Text("You play", color = Muted)
                        Chips(listOf("White", "Black"), if (c == WHITE) 0 else 1) { c = if (it == 0) WHITE else BLACK }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    mode = m; difficulty = d; humanColor = c; game = ChessGame(); selected = -1; version++
                    showSetup = false; save()
                }, colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { showSetup = false }) { Text("Cancel", color = Muted) } })
    }
}

@Composable
private fun Chips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, l ->
            FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(l, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = NeonBlue, labelColor = Color.White))
        }
    }
}
