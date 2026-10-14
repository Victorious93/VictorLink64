package org.victoriousframework.victorlink64.chess

import kotlin.math.abs
import kotlin.random.Random

/** Original alpha-beta (negamax) searcher with material + piece-square evaluation. */
enum class Difficulty(val label: String, val depth: Int, val noise: Int) {
    EASY("Easy", 1, 120), MEDIUM("Medium", 2, 30), HARD("Hard", 3, 0), EXPERT("Expert", 4, 0)
}

object ChessAi {
    private val VALUE = intArrayOf(0, 100, 320, 330, 500, 900, 0)
    private const val MATE = 100000

    // Simple centralisation table (from white's view), shared by minor pieces and used lightly for others.
    private val CENTER = IntArray(64) { sq ->
        val f = fileOf(sq); val r = rankOf(sq)
        val df = minOf(f, 7 - f); val dr = minOf(r, 7 - r)
        (df + dr) * 5
    }

    fun evaluate(p: Position): Int {
        var s = 0
        for (sq in 0..63) {
            val v = p.board[sq]
            if (v == 0) continue
            val t = abs(v); val c = if (v > 0) 1 else -1
            var sc = VALUE[t]
            when (t) {
                PAWN -> { val adv = if (c > 0) rankOf(sq) - 1 else 6 - rankOf(sq); sc += adv * 8 + if (fileOf(sq) in 2..5) CENTER[sq] / 2 else 0 }
                KNIGHT, BISHOP -> sc += CENTER[sq] * 2
                QUEEN -> sc += CENTER[sq] / 2
                KING -> { val home = if (c > 0) rankOf(sq) == 0 else rankOf(sq) == 7; if (home) sc += 20 }
            }
            s += sc * c
        }
        return s * p.side
    }

    private fun order(p: Position, moves: List<Move>) = moves.sortedByDescending { m ->
        val cap = abs(p.board[m.to]); (if (cap != 0) VALUE[cap] * 10 - VALUE[abs(p.board[m.from])] else 0) + m.promo * 100
    }

    private fun quiesce(p: Position, alphaIn: Int, beta: Int, depth: Int): Int {
        var alpha = alphaIn
        val stand = evaluate(p)
        if (stand >= beta || depth == 0) return stand
        if (stand > alpha) alpha = stand
        for (m in order(p, p.pseudoMoves().filter { p.board[it.to] != 0 || it.promo != 0 })) {
            val n = p.play(m)
            if (n.inCheck(p.side)) continue
            val sc = -quiesce(n, -beta, -alpha, depth - 1)
            if (sc >= beta) return sc
            if (sc > alpha) alpha = sc
        }
        return alpha
    }

    private fun search(p: Position, depth: Int, alphaIn: Int, beta: Int, ply: Int): Int {
        var alpha = alphaIn
        val moves = p.legalMoves()
        if (moves.isEmpty()) return if (p.inCheck()) -MATE + ply else 0
        if (p.halfmove >= 100) return 0
        if (depth == 0) return quiesce(p, alpha, beta, 4)
        var best = -MATE * 2
        for (m in order(p, moves)) {
            val sc = -search(p.play(m), depth - 1, -beta, -alpha, ply + 1)
            if (sc > best) best = sc
            if (sc > alpha) alpha = sc
            if (alpha >= beta) break
        }
        return best
    }

    fun bestMove(p: Position, d: Difficulty, rnd: Random = Random.Default): Move? {
        val moves = p.legalMoves()
        if (moves.isEmpty()) return null
        var best: Move? = null; var bestScore = Int.MIN_VALUE
        var alpha = -MATE * 2
        for (m in order(p, moves)) {
            var sc = -search(p.play(m), d.depth - 1, -MATE * 2, if (d.noise > 0) MATE * 2 else -alpha, 1)
            if (d.noise > 0) sc += rnd.nextInt(d.noise)
            if (sc > bestScore) { bestScore = sc; best = m }
            if (sc > alpha) alpha = sc
        }
        return best
    }
}
