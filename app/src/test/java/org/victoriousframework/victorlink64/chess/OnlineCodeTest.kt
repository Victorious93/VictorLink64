package org.victoriousframework.victorlink64.chess

import org.junit.Assert.*
import org.junit.Test
import org.victoriousframework.victorlink64.connect.OnlineTransport

class OnlineCodeTest {
    @Test fun roomCodes() {
        assertEquals("ABC234", OnlineTransport.normalize(" abc-234 "))
        assertTrue(OnlineTransport.isValidCode("abc234"))
        assertFalse(OnlineTransport.isValidCode("abc23"))
    }

    @Test fun remoteMoveReplayKeepsGamesInSync() {
        // Simulates two peers exchanging UCI moves and checks both sides reach the same position.
        val a = ChessGame(); val b = ChessGame()
        for (u in listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5")) { assertTrue(a.play(u)); assertTrue(b.play(u)) }
        assertEquals(a.position.toFen(), b.position.toFen())
        assertEquals(a.serialize(), ChessGame.deserialize(b.serialize()).serialize())
    }
}
