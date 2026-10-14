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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VictorTheme { ChessScreen(onBack = { finish() }) } }
    }
}

private const val CHESS_PREFS = "victorlink64_chess"

private val LIGHT_SQ = Color(0xFF1C3A6E)
private val DARK_SQ = Color(0xFF0E1D40)

enum class ChessMode { VS_PHONE, PASS_AND_PLAY }

private fun glyph(v: Int): String = when (abs(v)) {
    KING -> "♚"; QUEEN -> "♛"; ROOK -> "♜"; BISHOP -> "♝"; KNIGHT -> "♞"; PAWN -> "♟"; else -> ""
}

@Composable
fun ChessScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(CHESS_PREFS, Context.MODE_PRIVATE) }
    var mode by remember { mutableStateOf(ChessMode.valueOf(prefs.getString("mode", ChessMode.VS_PHONE.name)!!)) }
    var difficulty by remember { mutableStateOf(Difficulty.valueOf(prefs.getString("difficulty", Difficulty.MEDIUM.name)!!)) }
    var humanColor by remember { mutableIntStateOf(prefs.getInt("human", WHITE)) }
    var game by remember {
        mutableStateOf(prefs.getString("game", null)?.let { runCatching { ChessGame.deserialize(it) }.getOrNull() } ?: ChessGame())
    }
    var version by remember { mutableIntStateOf(0) } // bump to recompose after mutating game
    var selected by remember { mutableIntStateOf(-1) }
    var pendingPromo by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var thinking by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }

    fun save() {
        prefs.edit().putString("game", game.serialize()).putString("mode", mode.name)
            .putString("difficulty", difficulty.name).putInt("human", humanColor).apply()
    }
    fun commit(uci: String) { if (game.play(uci)) { selected = -1; version++; save() } }

    val pos = remember(version, game) { game.position }
    val result = remember(version, game) { game.result() }
    val legal = remember(version, game) { game.legalMoves() }
    val aiTurn = mode == ChessMode.VS_PHONE && pos.side != humanColor && result == GameResult.ONGOING
    val flipped = if (mode == ChessMode.VS_PHONE) humanColor == BLACK else false

    LaunchedEffect(version, aiTurn, game) {
        if (aiTurn) {
            thinking = true
            val m = withContext(Dispatchers.Default) { ChessAi.bestMove(pos, difficulty) }
            thinking = false
            m?.let { commit(it.uci()) }
        }
    }

    fun onTap(sq: Int) {
        if (aiTurn || result != GameResult.ONGOING) return
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
            TextButton(onClick = { showSetup = true }) { Text("New game", color = NeonRed) }
        }
        Text(
            when (mode) { ChessMode.VS_PHONE -> "vs Phone · ${difficulty.label}"; ChessMode.PASS_AND_PLAY -> "Pass & play" },
            color = Muted, fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        val status = when {
            result != GameResult.ONGOING -> result.text
            thinking -> "Phone is thinking…"
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
            OutlinedButton(enabled = !thinking && game.uciMoves.isNotEmpty(), onClick = {
                game.undo()
                if (mode == ChessMode.VS_PHONE && game.position.side != humanColor) game.undo()
                selected = -1; version++; save()
            }) { Text("Undo") }
            OutlinedButton(enabled = result == GameResult.ONGOING && !thinking, onClick = {
                game.resign(if (mode == ChessMode.VS_PHONE) humanColor else pos.side); version++
            }) { Text("Resign") }
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
