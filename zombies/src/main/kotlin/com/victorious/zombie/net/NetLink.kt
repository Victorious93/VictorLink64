package com.victorious.zombie.net

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One reliable, ordered message pipe to a single peer. Messages are (type, data) pairs where [data] is a
 * single line of text with no newlines. Implementations: [TcpLink] (Wi-Fi/LAN), [LoopbackLink] (tests), and in
 * VictorLink64 an adapter over its Bluetooth/online `ConnectSession`, which carries them as JSON `{"t":type,"d":data}`.
 * Callbacks may arrive on any thread.
 */
interface NetLink {
    interface Listener {
        fun onMessage(type: String, data: String)
        fun onClosed()
    }

    var listener: Listener?
    fun send(type: String, data: String)
    fun close()
}

/** In-memory pair for tests: whatever one end sends, the other receives (queued until a listener is set). */
class LoopbackLink private constructor() : NetLink {
    private lateinit var other: LoopbackLink
    private val pending = ConcurrentLinkedQueue<Pair<String, String>>()
    @Volatile private var closed = false
    @Volatile override var listener: NetLink.Listener? = null
        set(value) { field = value; if (value != null) drain() }

    private fun drain() { val l = listener ?: return; while (true) { val m = pending.poll() ?: break; l.onMessage(m.first, m.second) } }

    override fun send(type: String, data: String) { if (!closed) { other.pending.add(type to data); other.drain() } }
    override fun close() { if (closed) return; closed = true; other.listener?.onClosed() }

    companion object {
        fun pair(): Pair<LoopbackLink, LoopbackLink> { val a = LoopbackLink(); val b = LoopbackLink(); a.other = b; b.other = a; return a to b }
    }
}
