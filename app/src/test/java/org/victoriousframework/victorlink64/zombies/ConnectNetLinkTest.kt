package org.victoriousframework.victorlink64.zombies

import com.victorious.zombie.net.NetLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.victoriousframework.victorlink64.connect.ConnectSession
import org.victoriousframework.victorlink64.connect.PeerState
import org.victoriousframework.victorlink64.connect.Transport
import org.victoriousframework.victorlink64.connect.TransportKind
import org.victoriousframework.victorlink64.connect.TransportListener

/** Victorious Framework: tests for the ConnectSession <-> VictorOps Zombies NetLink adapter. JVM only; no Bluetooth involved. */
class ConnectNetLinkTest {
    private lateinit var scope: CoroutineScope
    private val messages = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    private val states = MutableStateFlow(PeerState.CONNECTED)
    private val sent = mutableListOf<Pair<String, String>>()

    private class Recorder : NetLink.Listener {
        val got = mutableListOf<Pair<String, String>>(); var closed = 0
        override fun onMessage(type: String, data: String) { got += type to data }
        override fun onClosed() { closed++ }
    }

    // Unconfined: the collectors run inline on the emitting thread, so assertions need no waiting.
    @Before fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
    @After fun tearDown() { scope.cancel() }

    private fun link() = ConnectNetLink(scope, messages, states) { t, d -> sent += t to d }
    private fun msg(t: String, d: String? = null) = JSONObject().put("t", t).apply { if (d != null) put("d", d) }

    @Test fun forwardsGameMessagesAndDropsEverythingElse() {
        val l = link(); val r = Recorder(); l.listener = r
        listOf("zj", "zi", "zw", "zs", "zf", "zb").forEach { messages.tryEmit(msg(it, "x-$it")) }
        messages.tryEmit(msg("move", "e2e4")); messages.tryEmit(msg("hello")); messages.tryEmit(msg("chess_invite")); messages.tryEmit(msg("zx", "unknown"))
        assertEquals(listOf("zj", "zi", "zw", "zs", "zf", "zb").map { it to "x-$it" }, r.got)
    }

    @Test fun sendWrapsDataAsTypeAndD() {
        link().send("zs", "AAEC+/=="); link().send("zi", "1,0,1,0")
        assertEquals(listOf("zs" to "AAEC+/==", "zi" to "1,0,1,0"), sent)
    }

    @Test fun sendKeepsDataOnOneLine() {
        link().send("zi", "a\nb")
        assertEquals("a b", sent.single().second)
    }

    @Test fun holdsMessagesUntilListenerIsSetThenDeliversInOrder() {
        val l = link()
        messages.tryEmit(msg("zj", "snoop")); messages.tryEmit(msg("zi", "0,0,0,0"))
        val r = Recorder(); l.listener = r
        assertEquals(listOf("zj" to "snoop", "zi" to "0,0,0,0"), r.got)
        messages.tryEmit(msg("zi", "1,0,0,0"))
        assertEquals(3, r.got.size)
    }

    @Test fun pendingQueueIsBounded() {
        val l = link()
        repeat(200) { messages.tryEmit(msg("zs", "$it")) }
        val r = Recorder(); l.listener = r
        assertEquals(64, r.got.size); assertEquals("199", r.got.last().second)
    }

    @Test fun disconnectedErrorAndIdleCloseTheLinkOnce() {
        for (s in listOf(PeerState.DISCONNECTED, PeerState.ERROR, PeerState.IDLE)) {
            states.value = PeerState.CONNECTED
            val l = link(); val r = Recorder(); l.listener = r
            states.value = s
            assertEquals("state $s", 1, r.closed)
            states.value = PeerState.DISCONNECTED; states.value = PeerState.ERROR
            assertEquals(1, r.closed)
            messages.tryEmit(msg("zs", "late")); assertTrue(r.got.isEmpty())
            l.send("zi", "x"); assertTrue(sent.isEmpty())
        }
    }

    @Test fun waitingAndConnectedDoNotClose() {
        val l = link(); val r = Recorder(); l.listener = r
        states.value = PeerState.WAITING; states.value = PeerState.CONNECTED
        assertEquals(0, r.closed)
    }

    @Test fun linkCreatedOnAlreadyDeadSessionClosesImmediately() {
        states.value = PeerState.DISCONNECTED
        val l = link(); val r = Recorder(); l.listener = r
        // Ended before the game attached: it is told as soon as it attaches, and no traffic flows.
        assertEquals(1, r.closed)
        messages.tryEmit(msg("zs", "x")); l.send("zi", "x")
        assertTrue(r.got.isEmpty()); assertTrue(sent.isEmpty())
    }

    @Test fun closeDetachesWithoutNotifyingAndStopsTraffic() {
        val l = link(); val r = Recorder(); l.listener = r
        l.close()
        messages.tryEmit(msg("zs", "x")); l.send("zi", "x"); states.value = PeerState.DISCONNECTED
        assertTrue(r.got.isEmpty()); assertTrue(sent.isEmpty()); assertEquals(0, r.closed)
    }

    @Test fun twoLinksOnOneSessionBothSeeTraffic() {
        // The host attaches a fresh link per connected period; an old link must not break a new one.
        val a = link(); val ra = Recorder(); a.listener = ra
        a.close()
        val b = link(); val rb = Recorder(); b.listener = rb
        messages.tryEmit(msg("zi", "1")); 
        assertTrue(ra.got.isEmpty()); assertEquals(1, rb.got.size)
    }

    // ---- against the real ConnectSession object, with a fake Transport (no Android needed) ----

    private class FakeTransport(override val isHost: Boolean) : Transport {
        override val kind = TransportKind.BLUETOOTH
        lateinit var l: TransportListener
        val out = mutableListOf<String>()
        override fun start(listener: TransportListener) { l = listener }
        override fun send(msg: String) { out += msg }
        override fun close() {}
    }

    @Test fun roundTripThroughRealConnectSession() {
        val t = FakeTransport(isHost = true)
        ConnectSession.start(t); t.l.onPeerConnected("Pixel")
        val l = ConnectNetLink.forSession(scope); val r = Recorder(); l.listener = r
        t.out.clear()

        l.send("zs", "SNAP")
        assertEquals(1, t.out.size)
        val sentJson = JSONObject(t.out.single()); assertEquals("zs", sentJson.getString("t")); assertEquals("SNAP", sentJson.getString("d"))

        t.l.onMessage("""{"t":"zi","d":"1,0,0,1"}""")
        t.l.onMessage("""{"t":"move","uci":"e2e4"}""")   // chess traffic on the same session is ignored
        t.l.onMessage("not json")                           // and garbage doesn't crash
        assertEquals(listOf("zi" to "1,0,0,1"), r.got)

        t.l.onPeerDisconnected()
        assertEquals(1, r.closed)
        ConnectSession.stop()
    }

    @Test fun realSessionErrorClosesLink() {
        val t = FakeTransport(isHost = false)
        ConnectSession.start(t); t.l.onPeerConnected(null)
        val l = ConnectNetLink.forSession(scope); val r = Recorder(); l.listener = r
        t.l.onError("Bluetooth error: socket closed")
        assertEquals(1, r.closed); assertFalse(ConnectSession.active)
        ConnectSession.stop()
    }
}
