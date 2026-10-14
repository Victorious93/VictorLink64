package org.victoriousframework.victorlink64.chess

import org.junit.Assert.*
import org.junit.Test

class ChessTest {
    @Test fun perftStart() {
        val p = Position.start()
        assertEquals(20L, perft(p, 1))
        assertEquals(400L, perft(p, 2))
        assertEquals(8902L, perft(p, 3))
    }

    @Test fun perftKiwipete() {
        // Exercises castling, en passant, promotions.
        val p = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48L, perft(p, 1))
        assertEquals(2039L, perft(p, 2))
    }

    @Test fun perftPosition3() {
        val p = Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        assertEquals(14L, perft(p, 1)); assertEquals(191L, perft(p, 2)); assertEquals(2812L, perft(p, 3))
    }

    @Test fun foolsMate() {
        val g = ChessGame()
        listOf("f2f3", "e7e5", "g2g4", "d8h4").forEach { assertTrue(g.play(it)) }
        assertEquals(GameResult.BLACK_MATES, g.result())
    }

    @Test fun stalemate() {
        val g = ChessGame("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1")
        assertEquals(GameResult.STALEMATE, g.result())
    }

    @Test fun threefold() {
        val g = ChessGame()
        repeat(2) { listOf("g1f3", "g8f6", "f3g1", "f6g8").forEach { assertTrue(g.play(it)) } }
        assertEquals(GameResult.THREEFOLD, g.result())
    }

    @Test fun insufficient() {
        assertEquals(GameResult.INSUFFICIENT, ChessGame("8/8/8/4k3/8/8/2B5/4K3 w - - 0 1").result())
        assertEquals(GameResult.ONGOING, ChessGame("8/8/8/4k3/8/8/2R5/4K3 w - - 0 1").result())
    }

    @Test fun fiftyMove() {
        assertEquals(GameResult.FIFTY_MOVE, ChessGame("8/8/8/4k3/8/8/2R5/4K3 w - - 100 80").result())
    }

    @Test fun enPassantAndPromotionAndSerialize() {
        val g = ChessGame()
        listOf("e2e4", "a7a6", "e4e5", "d7d5", "e5d6").forEach { assertTrue(it, g.play(it)) }
        assertEquals(0, g.position.board[parseSq("d5")])
        val g2 = ChessGame("8/P6k/8/8/8/8/8/K7 w - - 0 1")
        assertTrue(g2.play("a7a8n"))
        assertEquals(KNIGHT, g2.position.board[parseSq("a8")])
        val s = g.serialize()
        assertEquals(g.position.toFen(), ChessGame.deserialize(s).position.toFen())
    }

    @Test fun aiFindsMateInOne() {
        val p = Position.fromFen("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")
        assertEquals("a1a8", ChessAi.bestMove(p, Difficulty.MEDIUM)!!.uci())
    }
}
