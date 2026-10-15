package org.victoriousframework.victorlink64.connect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class TransportKind(val label: String) { ONLINE("Online"), LAN("Wi-Fi"), BLUETOOTH("Bluetooth") }

/** Callbacks a transport reports into. Invoked on background threads. */
interface TransportListener {
    fun onStatus(text: String)
    fun onPeerConnected(name: String?)
    fun onPeerDisconnected()
    fun onMessage(msg: String)
    fun onError(text: String)
}

/** A bidirectional message pipe between this device and one (or more, for Online) peers. Messages are JSON strings. */
interface Transport {
    val kind: TransportKind
    val isHost: Boolean
    fun start(listener: TransportListener)
    fun send(msg: String)
    fun close()
}

private const val TAG = "VL64Connect"

/** Newline-delimited message framing over any socket-like stream pair (shared by LAN and Bluetooth). */
abstract class StreamTransport : Transport {
    @Volatile protected var out: OutputStream? = null
    @Volatile protected var closed = false
    protected lateinit var listener: TransportListener
    // One writer thread keeps messages in order (a thread per send could reorder moves).
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vl64-send").apply { isDaemon = true } }
    protected fun shutdownSender() { sender.shutdownNow() }

    protected fun pump(input: InputStream, output: OutputStream, peer: String?) {
        out = output
        listener.onPeerConnected(peer)
        try {
            val r = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            while (!closed) {
                val line = r.readLine() ?: break
                if (line.isNotBlank()) listener.onMessage(line)
            }
        } catch (e: Exception) {
            if (!closed) Log.w(TAG, "stream ended", e)
        }
        out = null
        if (!closed) listener.onPeerDisconnected()
    }

    override fun send(msg: String) {
        val o = out ?: return
        try {
            sender.execute { try { o.write((msg.replace("\n", " ") + "\n").toByteArray()); o.flush() } catch (e: Exception) { Log.w(TAG, "send", e) } }
        } catch (_: java.util.concurrent.RejectedExecutionException) { /* closed */ }
    }
}

/** Same Wi-Fi: host advertises via NSD (mDNS) and accepts a TCP connection; joiner discovers and connects. */
class LanTransport(private val ctx: Context, override val isHost: Boolean, private val nick: String) : StreamTransport() {
    override val kind = TransportKind.LAN
    private val nsd by lazy { ctx.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null

    companion object { const val SERVICE_TYPE = "_vl64._tcp." }

    override fun start(listener: TransportListener) {
        this.listener = listener
        if (isHost) host() else join()
    }

    private fun host() = thread {
        try {
            val ss = ServerSocket(0).also { server = it }
            val info = NsdServiceInfo().apply { serviceName = "VictorLink64-$nick"; serviceType = SERVICE_TYPE; port = ss.localPort }
            regListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) = listener.onStatus("Hosting on Wi-Fi as ${i.serviceName} — waiting for a friend…")
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) = listener.onError("Wi-Fi advertise failed ($e)")
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener)
            val s = ss.accept().also { socket = it }
            unregister()
            pump(s.getInputStream(), s.getOutputStream(), s.inetAddress.hostAddress)
        } catch (e: Exception) { if (!closed) listener.onError("Wi-Fi host error: ${e.message}") }
    }

    private fun join() {
        listener.onStatus("Searching for games on this Wi-Fi…")
        discListener = object : NsdManager.DiscoveryListener {
            @Volatile var resolving = false
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) = listener.onError("Wi-Fi discovery failed ($e)")
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                if (resolving || !i.serviceType.startsWith("_vl64._tcp")) return
                resolving = true
                @Suppress("DEPRECATION")
                nsd.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, e: Int) { resolving = false }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        stopDiscovery()
                        @Suppress("DEPRECATION") val host = si.host
                        listener.onStatus("Found ${si.serviceName}, connecting…")
                        thread {
                            try {
                                val s = Socket().also { socket = it }
                                s.connect(InetSocketAddress(host, si.port), 8000)
                                pump(s.getInputStream(), s.getOutputStream(), si.serviceName)
                            } catch (e: Exception) { if (!closed) listener.onError("Could not connect: ${e.message}") }
                        }
                    }
                })
            }
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discListener)
    }

    private fun unregister() { regListener?.let { runCatching { nsd.unregisterService(it) } }; regListener = null }
    private fun stopDiscovery() { discListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }; discListener = null }

    override fun close() {
        closed = true
        shutdownSender()
        unregister(); stopDiscovery()
        runCatching { socket?.close() }; runCatching { server?.close() }
    }
}

/**
 * Nearby without Wi-Fi: classic Bluetooth RFCOMM. (Google Nearby Connections is part of proprietary
 * Play Services, which is not compatible with shipping inside a GPLv3 app, so we use the platform API.)
 */
@SuppressLint("MissingPermission")
class BluetoothTransport(
    private val ctx: Context, override val isHost: Boolean, private val device: BluetoothDevice? = null
) : StreamTransport() {
    override val kind = TransportKind.BLUETOOTH
    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6f1d2c3a-7b5e-4b1a-9e64-564c36340001")
        fun adapter(ctx: Context): BluetoothAdapter? =
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
    }

    override fun start(listener: TransportListener) {
        this.listener = listener
        val a = adapter(ctx) ?: run { listener.onError("Bluetooth not available"); return }
        if (!a.isEnabled) { listener.onError("Turn Bluetooth on first"); return }
        thread {
            try {
                if (isHost) {
                    listener.onStatus("Hosting over Bluetooth as “${a.name}” — waiting for a friend…")
                    val ss = a.listenUsingRfcommWithServiceRecord("VictorLink64", SERVICE_UUID).also { server = it }
                    val s = ss.accept().also { socket = it }
                    runCatching { ss.close() }
                    pump(s.inputStream, s.outputStream, s.remoteDevice?.name)
                } else {
                    val d = device ?: run { listener.onError("Pick a device"); return@thread }
                    listener.onStatus("Connecting to ${d.name ?: d.address}…")
                    runCatching { a.cancelDiscovery() }
                    val s = d.createRfcommSocketToServiceRecord(SERVICE_UUID).also { socket = it }
                    s.connect()
                    pump(s.inputStream, s.outputStream, d.name)
                }
            } catch (e: Exception) { if (!closed) listener.onError("Bluetooth error: ${e.message}") }
        }
    }

    override fun close() { closed = true; shutdownSender(); runCatching { socket?.close() }; runCatching { server?.close() } }
}

/** Far away: WebSocket to the VictorLink64 Cloudflare relay; peers share a 6-character room code. */
class OnlineTransport(private val relayUrl: String, val roomCode: String, private val nick: String, private val creating: Boolean) : Transport {
    override val kind = TransportKind.ONLINE
    @Volatile override var isHost: Boolean = creating
        private set
    private var ws: WebSocket? = null
    @Volatile private var closed = false
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    companion object {
        private const val MAX_RETRIES = 4
        private val CODE_RE = Regex("^[A-Z0-9]{6}$")
        fun normalize(code: String) = code.trim().uppercase().filter { it.isLetterOrDigit() }
        fun isValidCode(code: String) = CODE_RE.matches(normalize(code))

        /** Asks the relay for a fresh room code (blocking). */
        fun newRoomCode(relayUrl: String): String {
            val http = relayUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
            OkHttpClient().newCall(Request.Builder().url("$http/new").build()).execute().use {
                return JSONObject(it.body!!.string()).getString("code")
            }
        }
    }

    private var listener: TransportListener? = null
    @Volatile private var attempts = 0
    private val retryExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vl64-retry").apply { isDaemon = true } }

    override fun start(listener: TransportListener) {
        this.listener = listener
        listener.onStatus("Connecting to room $roomCode…")
        connect()
    }

    /** Transient drops (network blip, 1001/1006) are retried with backoff; rejections (4xx) are final. */
    private fun retryOrFail(text: String) {
        val l = listener ?: return
        if (closed) return
        if (attempts >= MAX_RETRIES) { l.onError(text); return }
        val delay = 1000L shl attempts
        attempts++
        l.onStatus("Connection lost — reconnecting ($attempts/$MAX_RETRIES)…")
        try {
            retryExec.schedule({ if (!closed) connect() }, delay, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) { /* closed */ }
    }

    private fun connect() {
        val listener = listener ?: return
        val url = "$relayUrl/room/$roomCode?name=" + java.net.URLEncoder.encode(nick, "UTF-8")
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { attempts = 0 }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== ws) return
                if (text == "pong") return
                val o = runCatching { JSONObject(text) }.getOrNull()
                when (o?.optString("t")) {
                    "welcome" -> {
                        isHost = o.optBoolean("host", creating)
                        val peers = o.optJSONArray("peers")
                        if (peers != null && peers.length() > 0) listener.onPeerConnected(peers.getJSONObject(0).optString("name"))
                        else listener.onStatus("Room $roomCode is open — share the code with a friend")
                    }
                    "peer_join" -> listener.onPeerConnected(o.optString("name"))
                    "peer_leave" -> listener.onPeerDisconnected()
                    else -> listener.onMessage(text)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed || webSocket !== ws) return
                val code = response?.code
                val text = "Connection failed: ${t.message ?: code}"
                if (code != null && code in 400..499) listener.onError(text) else retryOrFail(text)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closed || webSocket !== ws) return
                if (code == 1001 || code == 1006) retryOrFail("Connection closed") else listener.onPeerDisconnected()
            }
        })
    }

    override fun send(msg: String) { ws?.send(msg) }
    override fun close() {
        closed = true
        retryExec.shutdownNow()
        ws?.close(1000, "bye")
        client.dispatcher.executorService.shutdown()
    }
}
