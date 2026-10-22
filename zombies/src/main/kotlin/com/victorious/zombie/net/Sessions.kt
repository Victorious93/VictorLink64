package com.victorious.zombie.net

import com.victorious.zombie.logic.Characters
import com.victorious.zombie.sim.PlayerInput
import com.victorious.zombie.sim.Snapshot
import com.victorious.zombie.sim.SnapshotCodec
import com.victorious.zombie.sim.World
import java.util.concurrent.ConcurrentLinkedQueue

/** Wire message types. */
object Msg {
    const val JOIN = "zj"      // client -> host: "<characterId>"
    const val INPUT = "zi"     // client -> host: PlayerInput text
    const val WELCOME = "zw"   // host -> client: "<playerId>"
    const val STATE = "zs"     // host -> client: Snapshot base64
    const val FULL = "zf"      // host -> client: lobby full
    const val BYE = "zb"       // client -> host: leaving the game (the link itself may stay up, e.g. a shared Connect session)
}

/**
 * Host side: owns the [World] and talks to every connected peer. The host's own player is id [HOST_ID].
 * Call [tick] once per frame from the game thread; network callbacks only enqueue.
 */
class HostSession(val world: World, private val sendHz: Float = 15f) {
    companion object {
        const val HOST_ID = 0
        /** A fresh world with the host's own player already in it. */
        fun create(hostCharacter: com.victorious.zombie.logic.Character): HostSession =
            HostSession(World().also { it.addPlayer(HOST_ID, hostCharacter) })
    }

    private class Peer(val id: Int, val link: NetLink)
    private sealed class Event { class Join(val link: NetLink) : Event(); class Msg(val link: NetLink, val type: String, val data: String) : Event(); class Closed(val link: NetLink) : Event() }

    private val events = ConcurrentLinkedQueue<Event>()
    private val peers = LinkedHashMap<NetLink, Peer>()
    private var sendT = 0f
    val peerCount: Int get() = peers.size

    /** Thread-safe: may be called from a socket accept thread. */
    fun attach(link: NetLink) {
        link.listener = object : NetLink.Listener {
            override fun onMessage(type: String, data: String) { events.add(Event.Msg(link, type, data)) }
            override fun onClosed() { events.add(Event.Closed(link)) }
        }
        events.add(Event.Join(link))
    }

    fun tick(dt: Float) {
        while (true) {
            when (val e = events.poll() ?: break) {
                is Event.Join -> { /* wait for the JOIN message so we know the character */ }
                is Event.Msg -> handle(e.link, e.type, e.data)
                is Event.Closed -> peers.remove(e.link)?.let { world.removePlayer(it.id) }
            }
        }
        world.update(dt)
        sendT += dt
        if (sendT >= 1f / sendHz) {
            sendT = 0f
            for (p in peers.values) p.link.send(Msg.STATE, SnapshotCodec.toText(world.snapshot(p.id)))
        }
    }

    private fun handle(link: NetLink, type: String, data: String) {
        val peer = peers[link]
        when (type) {
            Msg.JOIN -> if (peer == null) {
                val id = (1..World.MAX_PLAYERS).firstOrNull { cand -> world.players[cand] == null && peers.values.none { it.id == cand } }
                val added = id?.let { world.addPlayer(it, Characters.byId(data.trim())) }
                if (id == null || added == null) { link.send(Msg.FULL, "full"); link.close() }
                else { peers[link] = Peer(id, link); link.send(Msg.WELCOME, id.toString()) }
            }
            Msg.INPUT -> if (peer != null) PlayerInput.fromText(data)?.let { world.setInput(peer.id, it) }
            Msg.BYE -> if (peer != null) { peers.remove(link); world.removePlayer(peer.id) }
        }
    }

    fun close() { peers.keys.toList().forEach { it.close() }; peers.clear() }
}

/** Client side: sends inputs, keeps the latest snapshot. Safe to read [latest] from the render thread. */
class ClientSession(private val link: NetLink, private val characterId: String, private val inputHz: Float = 20f) {
    @Volatile var latest: Snapshot? = null; private set
    @Volatile var playerId: Int = -1; private set
    @Volatile var connected = true; private set
    @Volatile var rejected = false; private set
    private var sendT = 0f
    private var joinT = 0f

    init {
        link.listener = object : NetLink.Listener {
            override fun onMessage(type: String, data: String) {
                when (type) {
                    Msg.WELCOME -> data.trim().toIntOrNull()?.let { playerId = it }
                    Msg.STATE -> try { latest = SnapshotCodec.fromText(data) } catch (_: Exception) { /* drop bad packet */ }
                    Msg.FULL -> rejected = true
                }
            }
            override fun onClosed() { connected = false }
        }
        link.send(Msg.JOIN, characterId)
    }

    fun tick(dt: Float, input: PlayerInput) {
        sendT += dt
        if (sendT >= 1f / inputHz) { sendT = 0f; link.send(Msg.INPUT, input.toText()) }
        // A shared link has no handshake of its own, so the host may not have been listening for the first JOIN. Repeat it until welcomed.
        if (playerId < 0 && !rejected && connected) { joinT += dt; if (joinT >= JOIN_RETRY_S) { joinT = 0f; link.send(Msg.JOIN, characterId) } }
    }

    fun close() { link.send(Msg.BYE, "bye"); link.close() }

    private companion object { const val JOIN_RETRY_S = 1f }
}
