package org.victoriousframework.victorlink64.zombies

import com.victorious.zombie.net.Msg
import com.victorious.zombie.net.NetLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.victoriousframework.victorlink64.connect.ConnectSession
import org.victoriousframework.victorlink64.connect.PeerState
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Victorious Framework: carries VictorOps Zombies' [NetLink] messages over the app-wide [ConnectSession]
 * (Bluetooth, Wi-Fi or online relay). Wire format: `{"t":"<type>","d":"<single-line data>"}`.
 *
 * Only the game's own message types ([GAME_TYPES]) are forwarded, so chess and the "hello" handshake that share
 * the session are ignored. Messages that arrive before a listener is set are held (bounded) and delivered once it is,
 * because the host's game screen may still be loading when the first packet lands.
 * [close] detaches this link only; it never stops the shared session.
 */
class ConnectNetLink(
    scope: CoroutineScope,
    messages: Flow<JSONObject>,
    peerStates: Flow<PeerState>,
    private val sender: (type: String, data: String) -> Unit,
) : NetLink {
    companion object {
        val GAME_TYPES = setOf(Msg.JOIN, Msg.INPUT, Msg.WELCOME, Msg.STATE, Msg.FULL, Msg.BYE)
        private const val MAX_PENDING = 64

        /** Link over the live [ConnectSession]. */
        fun forSession(scope: CoroutineScope) = ConnectNetLink(
            scope, ConnectSession.messages, kotlinx.coroutines.flow.flow {
                ConnectSession.status.collect { emit(it.state) }
            }
        ) { type, data -> ConnectSession.send(type) { put("d", data) } }
    }

    private val pending = ConcurrentLinkedQueue<Pair<String, String>>()
    private val closedFlag = AtomicBoolean(false)
    @Volatile private var sessionEnded = false
    private val jobs = mutableListOf<Job>()
    private val lock = Any()

    @Volatile override var listener: NetLink.Listener? = null
        set(value) { field = value; if (value != null) { drain(); notifyEndedOnce() } }

    init {
        jobs += scope.launch {
            messages.collect { m ->
                val type = m.optString("t")
                if (type in GAME_TYPES && !closedFlag.get()) deliver(type, m.optString("d"))
            }
        }
        jobs += scope.launch {
            peerStates.collect { s ->
                if (s == PeerState.DISCONNECTED || s == PeerState.ERROR || s == PeerState.IDLE) finish()
            }
        }
    }

    private fun deliver(type: String, data: String) {
        val l = listener
        if (l == null) {
            pending.add(type to data)
            while (pending.size > MAX_PENDING) pending.poll()
        } else synchronized(lock) { l.onMessage(type, data) }
    }

    private fun drain() {
        val l = listener ?: return
        synchronized(lock) { while (true) { val m = pending.poll() ?: break; l.onMessage(m.first, m.second) } }
    }

    /** The shared session ended: stop forwarding and tell the game once (now, or as soon as it sets a listener). */
    private fun finish() {
        if (!closedFlag.compareAndSet(false, true)) return
        sessionEnded = true
        jobs.forEach { it.cancel() }
        notifyEndedOnce()
    }

    private val endedNotified = AtomicBoolean(false)
    private fun notifyEndedOnce() {
        val l = listener ?: return
        if (sessionEnded && endedNotified.compareAndSet(false, true)) synchronized(lock) { l.onClosed() }
    }

    override fun send(type: String, data: String) {
        if (closedFlag.get()) return
        sender(type, data.replace('\n', ' '))
    }

    /** Detach from the session (the game is leaving). Does not call [NetLink.Listener.onClosed] and does not stop the session. */
    override fun close() {
        if (!closedFlag.compareAndSet(false, true)) return
        jobs.forEach { it.cancel() }
        listener = null
    }
}
