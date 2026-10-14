package org.victoriousframework.victorlink64.chess

/*
 * VictorLink64 Chess engine — original implementation.
 * Board: IntArray(64), index = rank*8 + file (a1 = 0, h8 = 63).
 * Pieces: +white / -black; 1=P 2=N 3=B 4=R 5=Q 6=K; 0 = empty.
 */
const val EMPTY = 0
const val PAWN = 1
const val KNIGHT = 2
const val BISHOP = 3
const val ROOK = 4
const val QUEEN = 5
const val KING = 6

const val WHITE = 1
const val BLACK = -1

const val CASTLE_WK = 1
const val CASTLE_WQ = 2
const val CASTLE_BK = 4
const val CASTLE_BQ = 8

fun fileOf(sq: Int) = sq and 7
fun rankOf(sq: Int) = sq shr 3
fun sqName(sq: Int) = "${'a' + fileOf(sq)}${'1' + rankOf(sq)}"
fun parseSq(s: String): Int {
    require(s.length == 2 && s[0] in 'a'..'h' && s[1] in '1'..'8') { "bad square $s" }
    return (s[1] - '1') * 8 + (s[0] - 'a')
}

data class Move(val from: Int, val to: Int, val promo: Int = 0) {
    fun uci(): String = sqName(from) + sqName(to) + when (promo) {
        KNIGHT -> "n"; BISHOP -> "b"; ROOK -> "r"; QUEEN -> "q"; else -> ""
    }
    companion object {
        fun fromUci(s: String): Move {
            require(s.length == 4 || s.length == 5) { "bad uci $s" }
            val p = if (s.length == 5) when (s[4]) {
                'n' -> KNIGHT; 'b' -> BISHOP; 'r' -> ROOK; 'q' -> QUEEN
                else -> throw IllegalArgumentException("bad promo $s")
            } else 0
            return Move(parseSq(s.substring(0, 2)), parseSq(s.substring(2, 4)), p)
        }
    }
}

private val KNIGHT_D = arrayOf(intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2),
    intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2))
private val KING_D = arrayOf(intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1),
    intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1))
private val BISHOP_D = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
private val ROOK_D = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))

class Position(
    val board: IntArray = IntArray(64),
    var side: Int = WHITE,
    var castling: Int = 0,
    var ep: Int = -1,
    var halfmove: Int = 0,
    var fullmove: Int = 1,
) {
    fun copy() = Position(board.copyOf(), side, castling, ep, halfmove, fullmove)

    companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        fun start() = fromFen(START_FEN)

        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(Regex("\\s+"))
            val p = Position()
            var rank = 7; var file = 0
            for (c in parts[0]) {
                when {
                    c == '/' -> { rank--; file = 0 }
                    c.isDigit() -> file += c - '0'
                    else -> {
                        val t = when (c.lowercaseChar()) {
                            'p' -> PAWN; 'n' -> KNIGHT; 'b' -> BISHOP; 'r' -> ROOK; 'q' -> QUEEN; 'k' -> KING
                            else -> throw IllegalArgumentException("bad fen")
                        }
                        p.board[rank * 8 + file] = if (c.isUpperCase()) t else -t
                        file++
                    }
                }
            }
            p.side = if (parts.getOrElse(1) { "w" } == "b") BLACK else WHITE
            val cs = parts.getOrElse(2) { "-" }
            if ('K' in cs) p.castling = p.castling or CASTLE_WK
            if ('Q' in cs) p.castling = p.castling or CASTLE_WQ
            if ('k' in cs) p.castling = p.castling or CASTLE_BK
            if ('q' in cs) p.castling = p.castling or CASTLE_BQ
            val e = parts.getOrElse(3) { "-" }
            p.ep = if (e == "-") -1 else parseSq(e)
            p.halfmove = parts.getOrElse(4) { "0" }.toInt()
            p.fullmove = parts.getOrElse(5) { "1" }.toInt()
            return p
        }
    }

    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val v = board[r * 8 + f]
                if (v == 0) { empty++; continue }
                if (empty > 0) { sb.append(empty); empty = 0 }
                val ch = ".pnbrqk"[kotlin.math.abs(v)]
                sb.append(if (v > 0) ch.uppercaseChar() else ch)
            }
            if (empty > 0) sb.append(empty)
            if (r > 0) sb.append('/')
        }
        sb.append(if (side == WHITE) " w " else " b ")
        var cs = ""
        if (castling and CASTLE_WK != 0) cs += "K"
        if (castling and CASTLE_WQ != 0) cs += "Q"
        if (castling and CASTLE_BK != 0) cs += "k"
        if (castling and CASTLE_BQ != 0) cs += "q"
        sb.append(cs.ifEmpty { "-" }).append(' ')
        sb.append(if (ep < 0) "-" else sqName(ep)).append(' ').append(halfmove).append(' ').append(fullmove)
        return sb.toString()
    }

    /** Key for repetition detection: placement + side + castling + ep (only if an ep capture is legal). */
    fun repetitionKey(): String {
        val f = toFen().split(' ')
        val epKey = if (ep >= 0 && legalMoves().any { it.to == ep && kotlin.math.abs(board[it.from]) == PAWN }) f[3] else "-"
        return "${f[0]} ${f[1]} ${f[2]} $epKey"
    }

    fun kingSquare(color: Int): Int {
        for (i in 0..63) if (board[i] == KING * color) return i
        return -1
    }

    /** Is square attacked by pieces of [by]? */
    fun attacked(sq: Int, by: Int): Boolean {
        val f = fileOf(sq); val r = rankOf(sq)
        // pawns
        val pr = r - by
        if (pr in 0..7) {
            if (f > 0 && board[pr * 8 + f - 1] == PAWN * by) return true
            if (f < 7 && board[pr * 8 + f + 1] == PAWN * by) return true
        }
        for (d in KNIGHT_D) {
            val nf = f + d[0]; val nr = r + d[1]
            if (nf in 0..7 && nr in 0..7 && board[nr * 8 + nf] == KNIGHT * by) return true
        }
        for (d in KING_D) {
            val nf = f + d[0]; val nr = r + d[1]
            if (nf in 0..7 && nr in 0..7 && board[nr * 8 + nf] == KING * by) return true
        }
        for (d in BISHOP_D) {
            var nf = f + d[0]; var nr = r + d[1]
            while (nf in 0..7 && nr in 0..7) {
                val v = board[nr * 8 + nf]
                if (v != 0) { if (v == BISHOP * by || v == QUEEN * by) return true; break }
                nf += d[0]; nr += d[1]
            }
        }
        for (d in ROOK_D) {
            var nf = f + d[0]; var nr = r + d[1]
            while (nf in 0..7 && nr in 0..7) {
                val v = board[nr * 8 + nf]
                if (v != 0) { if (v == ROOK * by || v == QUEEN * by) return true; break }
                nf += d[0]; nr += d[1]
            }
        }
        return false
    }

    fun inCheck(color: Int = side): Boolean {
        val k = kingSquare(color)
        return k >= 0 && attacked(k, -color)
    }

    fun pseudoMoves(): List<Move> {
        val out = ArrayList<Move>(48)
        val us = side
        for (sq in 0..63) {
            val v = board[sq]
            if (v == 0 || (v > 0) != (us > 0)) continue
            val f = fileOf(sq); val r = rankOf(sq)
            when (kotlin.math.abs(v)) {
                PAWN -> {
                    val nr = r + us
                    val promoRank = if (us == WHITE) 7 else 0
                    fun add(to: Int) {
                        if (rankOf(to) == promoRank) for (p in intArrayOf(QUEEN, ROOK, BISHOP, KNIGHT)) out += Move(sq, to, p)
                        else out += Move(sq, to)
                    }
                    if (nr in 0..7) {
                        val one = nr * 8 + f
                        if (board[one] == 0) {
                            add(one)
                            val startRank = if (us == WHITE) 1 else 6
                            val two = (r + 2 * us) * 8 + f
                            if (r == startRank && board[two] == 0) out += Move(sq, two)
                        }
                        for (df in intArrayOf(-1, 1)) {
                            val nf = f + df
                            if (nf !in 0..7) continue
                            val to = nr * 8 + nf
                            val t = board[to]
                            if ((t != 0 && (t > 0) != (us > 0)) || to == ep) add(to)
                        }
                    }
                }
                KNIGHT -> jump(sq, KNIGHT_D, out)
                KING -> {
                    jump(sq, KING_D, out)
                    // castling
                    if (us == WHITE && sq == 4) {
                        if (castling and CASTLE_WK != 0 && board[5] == 0 && board[6] == 0 && board[7] == ROOK &&
                            !attacked(4, BLACK) && !attacked(5, BLACK) && !attacked(6, BLACK)) out += Move(4, 6)
                        if (castling and CASTLE_WQ != 0 && board[3] == 0 && board[2] == 0 && board[1] == 0 && board[0] == ROOK &&
                            !attacked(4, BLACK) && !attacked(3, BLACK) && !attacked(2, BLACK)) out += Move(4, 2)
                    } else if (us == BLACK && sq == 60) {
                        if (castling and CASTLE_BK != 0 && board[61] == 0 && board[62] == 0 && board[63] == -ROOK &&
                            !attacked(60, WHITE) && !attacked(61, WHITE) && !attacked(62, WHITE)) out += Move(60, 62)
                        if (castling and CASTLE_BQ != 0 && board[59] == 0 && board[58] == 0 && board[57] == 0 && board[56] == -ROOK &&
                            !attacked(60, WHITE) && !attacked(59, WHITE) && !attacked(58, WHITE)) out += Move(60, 58)
                    }
                }
                BISHOP -> slide(sq, BISHOP_D, out)
                ROOK -> slide(sq, ROOK_D, out)
                QUEEN -> { slide(sq, BISHOP_D, out); slide(sq, ROOK_D, out) }
            }
        }
        return out
    }

    private fun jump(sq: Int, dirs: Array<IntArray>, out: MutableList<Move>) {
        val f = fileOf(sq); val r = rankOf(sq)
        for (d in dirs) {
            val nf = f + d[0]; val nr = r + d[1]
            if (nf !in 0..7 || nr !in 0..7) continue
            val to = nr * 8 + nf
            val t = board[to]
            if (t == 0 || (t > 0) != (side > 0)) out += Move(sq, to)
        }
    }

    private fun slide(sq: Int, dirs: Array<IntArray>, out: MutableList<Move>) {
        val f = fileOf(sq); val r = rankOf(sq)
        for (d in dirs) {
            var nf = f + d[0]; var nr = r + d[1]
            while (nf in 0..7 && nr in 0..7) {
                val to = nr * 8 + nf
                val t = board[to]
                if (t == 0) out += Move(sq, to)
                else { if ((t > 0) != (side > 0)) out += Move(sq, to); break }
                nf += d[0]; nr += d[1]
            }
        }
    }

    /** Returns a new position after [m] (assumed pseudo-legal). */
    fun play(m: Move): Position {
        val p = copy()
        val v = p.board[m.from]
        val type = kotlin.math.abs(v)
        val captured = p.board[m.to]
        p.board[m.to] = if (m.promo != 0) m.promo * side else v
        p.board[m.from] = 0
        if (type == PAWN && m.to == ep) p.board[m.to - 8 * side] = 0
        if (type == KING && kotlin.math.abs(m.to - m.from) == 2) {
            when (m.to) {
                6 -> { p.board[5] = p.board[7]; p.board[7] = 0 }
                2 -> { p.board[3] = p.board[0]; p.board[0] = 0 }
                62 -> { p.board[61] = p.board[63]; p.board[63] = 0 }
                58 -> { p.board[59] = p.board[56]; p.board[56] = 0 }
            }
        }
        fun clear(sq: Int) {
            when (sq) {
                0 -> p.castling = p.castling and CASTLE_WQ.inv()
                7 -> p.castling = p.castling and CASTLE_WK.inv()
                56 -> p.castling = p.castling and CASTLE_BQ.inv()
                63 -> p.castling = p.castling and CASTLE_BK.inv()
                4 -> p.castling = p.castling and (CASTLE_WK or CASTLE_WQ).inv()
                60 -> p.castling = p.castling and (CASTLE_BK or CASTLE_BQ).inv()
            }
        }
        clear(m.from); clear(m.to)
        p.ep = if (type == PAWN && kotlin.math.abs(m.to - m.from) == 16) (m.from + m.to) / 2 else -1
        p.halfmove = if (type == PAWN || captured != 0) 0 else halfmove + 1
        if (side == BLACK) p.fullmove++
        p.side = -side
        return p
    }

    fun legalMoves(): List<Move> = pseudoMoves().filter { !play(it).inCheck(side) }

    fun insufficientMaterial(): Boolean {
        val minors = ArrayList<Pair<Int, Int>>() // (type, square color)
        for (i in 0..63) {
            val t = kotlin.math.abs(board[i])
            when (t) {
                0, KING -> {}
                KNIGHT, BISHOP -> minors += t to ((fileOf(i) + rankOf(i)) and 1)
                else -> return false
            }
        }
        if (minors.size <= 1) return true
        // only bishops, all on same color square
        return minors.all { it.first == BISHOP } && minors.map { it.second }.distinct().size == 1
    }
}

fun perft(p: Position, depth: Int): Long {
    if (depth == 0) return 1
    val moves = p.legalMoves()
    if (depth == 1) return moves.size.toLong()
    var n = 0L
    for (m in moves) n += perft(p.play(m), depth - 1)
    return n
}

enum class GameResult(val text: String) {
    ONGOING(""), WHITE_MATES("Checkmate — White wins"), BLACK_MATES("Checkmate — Black wins"),
    STALEMATE("Draw by stalemate"), FIFTY_MOVE("Draw by 50-move rule"),
    THREEFOLD("Draw by threefold repetition"), INSUFFICIENT("Draw by insufficient material"),
    WHITE_RESIGNS("White resigned — Black wins"), BLACK_RESIGNS("Black resigned — White wins")
}

/**
 * A game is fully described by its start FEN and a list of UCI moves, so it can be
 * serialized (see [serialize]) and replayed/synchronised over the network later.
 */
class ChessGame(val startFen: String = Position.START_FEN) {
    private val positions = mutableListOf(Position.fromFen(startFen))
    private val moves = mutableListOf<String>()
    private val keys = mutableListOf(positions[0].repetitionKey())
    var resigned: GameResult? = null
        private set

    val position: Position get() = positions.last()
    val uciMoves: List<String> get() = moves.toList()

    fun legalMoves() = if (result() == GameResult.ONGOING) position.legalMoves() else emptyList()

    /** Applies a UCI move if legal. Returns false otherwise. */
    fun play(uci: String): Boolean {
        val m = runCatching { Move.fromUci(uci) }.getOrNull() ?: return false
        if (m !in legalMoves()) return false
        val next = position.play(m)
        positions += next; moves += m.uci(); keys += next.repetitionKey()
        return true
    }

    fun undo(): Boolean {
        if (moves.isEmpty()) return false
        positions.removeAt(positions.lastIndex); moves.removeAt(moves.lastIndex); keys.removeAt(keys.lastIndex)
        resigned = null
        return true
    }

    fun resign(color: Int) { resigned = if (color == WHITE) GameResult.WHITE_RESIGNS else GameResult.BLACK_RESIGNS }

    fun result(): GameResult {
        resigned?.let { return it }
        val p = position
        if (p.legalMoves().isEmpty()) {
            return if (p.inCheck()) (if (p.side == WHITE) GameResult.BLACK_MATES else GameResult.WHITE_MATES)
            else GameResult.STALEMATE
        }
        if (p.halfmove >= 100) return GameResult.FIFTY_MOVE
        if (keys.count { it == keys.last() } >= 3) return GameResult.THREEFOLD
        if (p.insufficientMaterial()) return GameResult.INSUFFICIENT
        return GameResult.ONGOING
    }

    fun serialize(): String = "$startFen|${moves.joinToString(" ")}"

    companion object {
        fun deserialize(s: String): ChessGame {
            val (fen, mv) = s.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val g = ChessGame(fen)
            for (u in mv.split(' ').filter { it.isNotBlank() }) require(g.play(u)) { "illegal move $u" }
            return g
        }
    }
}
