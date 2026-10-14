package org.victoriousframework.victorlink64.connect

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class PeerState { IDLE, WAITING, CONNECTED, DISCONNECTED, ERROR }

data class SessionStatus(
    val state: PeerState = PeerState.IDLE,
    val text: String = "Not connected",
    val kind: TransportKind? = null,
    val isHost: Boolean = false,
    val peerName: String? = null,
    val roomCode: String? = null,
)

/**
 * App-wide multiplayer session. One active transport at a time; screens (Connect tab, Chess)
 * observe [status] and [messages]. Messages are small JSON objects with a "t" type field.
 */
object ConnectSession : TransportListener {
    private var transport: Transport? = null
    private val _status = MutableStateFlow(SessionStatus())
    val status: StateFlow<SessionStatus> = _status.asStateFlow()
    private val _messages = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val messages: SharedFlow<JSONObject> = _messages.asSharedFlow()

    val active get() = transport != null && _status.value.state == PeerState.CONNECTED
    val isHost get() = transport?.isHost ?: false

    @Synchronized
    fun start(t: Transport) {
        stop()
        transport = t
        _status.value = SessionStatus(PeerState.WAITING, "Starting…", t.kind, t.isHost, roomCode = (t as? OnlineTransport)?.roomCode)
        t.start(this)
    }

    @Synchronized
    fun stop() {
        transport?.close()
        transport = null
        _status.value = SessionStatus()
    }

    fun send(type: String, fill: JSONObject.() -> Unit = {}) {
        val o = JSONObject().put("t", type).apply(fill)
        transport?.send(o.toString())
    }

    private fun update(f: (SessionStatus) -> SessionStatus) { _status.value = f(_status.value).copy(isHost = transport?.isHost ?: false) }

    override fun onStatus(text: String) = update { it.copy(text = text, state = if (it.state == PeerState.CONNECTED) it.state else PeerState.WAITING) }
    override fun onPeerConnected(name: String?) {
        update { it.copy(state = PeerState.CONNECTED, peerName = name, text = "Connected" + (name?.let { n -> " to $n" } ?: "")) }
        send("hello") { put("v", 1) }
    }
    override fun onPeerDisconnected() = update { it.copy(state = PeerState.DISCONNECTED, text = "Friend disconnected") }
    override fun onError(text: String) = update { it.copy(state = PeerState.ERROR, text = text) }
    override fun onMessage(msg: String) {
        runCatching { JSONObject(msg) }.getOrNull()?.let { _messages.tryEmit(it) }
    }
}
