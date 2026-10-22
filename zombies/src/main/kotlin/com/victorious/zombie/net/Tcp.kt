package com.victorious.zombie.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Wi-Fi/LAN link: one line per message, "type data\n". Works with no internet connection. */
class TcpLink(private val socket: Socket) : NetLink {
    @Volatile override var listener: NetLink.Listener? = null
    @Volatile private var closed = false
    private val out: OutputStream = socket.getOutputStream()
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "zombie-net-send").apply { isDaemon = true } }

    init {
        thread(isDaemon = true, name = "zombie-net-recv") {
            try {
                val r = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                while (!closed) {
                    val line = r.readLine() ?: break
                    if (line.length > MAX_LINE) break
                    val sp = line.indexOf(' ')
                    if (sp > 0) listener?.onMessage(line.substring(0, sp), line.substring(sp + 1))
                }
            } catch (_: Exception) { /* closed or reset */ }
            if (!closed) { closed = true; listener?.onClosed() }
        }
    }

    override fun send(type: String, data: String) {
        if (closed) return
        val bytes = "$type ${data.replace('\n', ' ')}\n".toByteArray(Charsets.UTF_8)
        try { sender.execute { try { out.write(bytes); out.flush() } catch (_: Exception) { close() } } } catch (_: Exception) { /* shut down */ }
    }

    override fun close() {
        if (closed) return
        closed = true; sender.shutdown(); runCatching { socket.close() }; listener?.onClosed()
    }

    companion object {
        const val MAX_LINE = 64 * 1024
        fun connect(host: String, port: Int, timeoutMs: Int = 8000): TcpLink {
            val s = Socket(); s.tcpNoDelay = true; s.connect(InetSocketAddress(host, port), timeoutMs); return TcpLink(s)
        }
    }
}

/** Accepts up to [maxPeers] players on [port] (0 = pick a free one). */
class TcpHost(port: Int = DEFAULT_PORT, private val onPeer: (NetLink) -> Unit) {
    private val server = ServerSocket(port)
    val port: Int get() = server.localPort
    @Volatile private var closed = false

    init {
        thread(isDaemon = true, name = "zombie-net-accept") {
            while (!closed) {
                try { val s = server.accept(); s.tcpNoDelay = true; onPeer(TcpLink(s)) } catch (_: Exception) { break }
            }
        }
    }

    fun close() { closed = true; runCatching { server.close() } }

    companion object {
        const val DEFAULT_PORT = 47464
        /** A LAN address friends can type in, or null. */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}
