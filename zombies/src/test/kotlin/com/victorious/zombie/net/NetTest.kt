package com.victorious.zombie.net

import com.victorious.zombie.logic.Characters
import com.victorious.zombie.logic.Weapons
import com.victorious.zombie.sim.*
import kotlin.test.*

class CodecTest {
    private fun sample(): Snapshot {
        val w = World(1); w.addPlayer(0, Characters.SNOOP); w.addPlayer(1, Characters.MARTHA)
        w.players[1]!!.weapon = Weapons.at("cannon", 2); w.players[1]!!.economy.onKill()
        w.zombies += SimZombie(7, 10f, 20f, 100, 1); w.bullets += SimBullet(1f, 2f, 0f, 0f, 1, 0, 0f, 0); w.pickups += SimPickup(3f, 4f, com.victorious.zombie.logic.ArmorPacks.LIGHT)
        w.update(0.01f); return w.snapshot(1)
    }

    @Test fun roundTrip() {
        val s = sample(); val d = SnapshotCodec.fromText(SnapshotCodec.toText(s))
        assertEquals(s.round, d.round); assertEquals(2, d.players.size)
        val p = d.players[1]; assertEquals("martha", p.characterId); assertEquals("cannon", p.weapon.id); assertEquals(2, p.weapon.tier); assertEquals(s.players[1].points, p.points)
        assertEquals(7, d.zombies.single().id); assertEquals(1f, d.bullets.single().x); assertEquals(4f, d.pickups.single().y)
        assertContentEquals(s.barricadeHp, d.barricadeHp)
    }

    @Test fun snapshotStaysSmallForBluetooth() {
        val w = World(1); repeat(4) { w.addPlayer(it, Characters.SNOOP) }
        repeat(24) { w.zombies += SimZombie(it, it * 3f, it * 2f, 100, 0) }; repeat(80) { w.bullets += SimBullet(0f, 0f, 0f, 0f, 1, 0, 0f, 0) }
        val bytes = SnapshotCodec.encode(w.snapshot(0))
        assertTrue(bytes.size < 2000, "snapshot was ${bytes.size} bytes")
    }

    @Test fun garbageIsRejectedNotCrashing() {
        assertFailsWith<Exception> { SnapshotCodec.decode(byteArrayOf(1, 2, 3)) }
        assertFailsWith<Exception> { SnapshotCodec.fromText("%%%not base64%%%") }
        assertFailsWith<Exception> { SnapshotCodec.decode(ByteArray(40) { 0x7f }) }
    }

    @Test fun inputRoundTripAndClamping() {
        assertEquals(PlayerInput(0.5f, -1f, true, false), PlayerInput.fromText(PlayerInput(0.5f, -1f, true, false).toText()))
        assertEquals(PlayerInput(1f, -1f, false, false), PlayerInput.fromText("900,-900,0,0"))
        assertNull(PlayerInput.fromText("junk")); assertNull(PlayerInput.fromText("1,2,3")); assertNull(PlayerInput.fromText("a,b,c,d"))
    }

    @Test fun weaponRebuildFromIdAndTier() {
        assertEquals(Weapons.RIFLE.upgradedVersion(), Weapons.at("rifle", 1)); assertEquals(Weapons.STARTER, Weapons.at("???", 0))
        assertTrue(Weapons.at("nova", 99).maxed)
    }
}

class SessionTest {
    private fun step(host: HostSession, client: ClientSession, input: PlayerInput, seconds: Float) {
        var t = 0f; while (t < seconds) { client.tick(1 / 60f, input); host.tick(1 / 60f); t += 1 / 60f }
    }

    @Test fun clientJoinsMovesAndSeesStateOverLoopback() {
        val world = World(3); world.addPlayer(HostSession.HOST_ID, Characters.SNOOP)
        val host = HostSession(world); val (a, b) = LoopbackLink.pair()
        host.attach(a); val client = ClientSession(b, "martha")
        step(host, client, PlayerInput(dx = 1f), 1f)
        assertEquals(1, client.playerId); assertEquals(2, world.players.size); assertEquals("martha", world.players[1]!!.character.id)
        val snap = assertNotNull(client.latest); assertEquals(2, snap.players.size)
        val me = snap.players.first { it.id == client.playerId }
        assertTrue(me.x > Layout.W / 2 + 60f, "client input should have moved its player, x=${me.x}")
    }

    @Test fun fifthPlayerIsTurnedAway() {
        val world = World(3); world.addPlayer(0, Characters.SNOOP); val host = HostSession(world)
        val clients = (1..3).map { val (a, b) = LoopbackLink.pair(); host.attach(a); ClientSession(b, "snoop") }
        val (a, b) = LoopbackLink.pair(); host.attach(a); val extra = ClientSession(b, "snoop")
        repeat(10) { host.tick(0.05f) }
        assertEquals(4, world.players.size); assertTrue(extra.rejected); assertEquals(listOf(1, 2, 3), clients.map { it.playerId })
    }

    @Test fun disconnectRemovesPlayerAndFreesSlot() {
        val world = World(3); world.addPlayer(0, Characters.SNOOP); val host = HostSession(world)
        val (a, b) = LoopbackLink.pair(); host.attach(a); val c = ClientSession(b, "snoop"); repeat(5) { host.tick(0.05f) }
        assertEquals(2, world.players.size); c.close(); repeat(2) { host.tick(0.05f) }
        assertEquals(1, world.players.size); assertEquals(0, host.peerCount)
        val (a2, b2) = LoopbackLink.pair(); host.attach(a2); val c2 = ClientSession(b2, "martha"); repeat(5) { host.tick(0.05f) }
        assertEquals(1, c2.playerId)
    }

    @Test fun malformedMessagesAreIgnored() {
        val world = World(3); world.addPlayer(0, Characters.SNOOP); val host = HostSession(world)
        val (a, b) = LoopbackLink.pair(); host.attach(a)
        b.send(Msg.INPUT, "1,1,1,1")                       // before joining: ignored
        b.send(Msg.JOIN, "nonexistent-character"); b.send(Msg.INPUT, "garbage"); b.send("zz", "?")
        repeat(5) { host.tick(0.05f) }
        assertEquals("snoop", world.players[1]!!.character.id)   // unknown character falls back to the default
    }

    @Test fun clientDropsCorruptSnapshotAndKeepsLast() {
        val (a, b) = LoopbackLink.pair(); val c = ClientSession(b, "snoop")
        val w = World(1); w.addPlayer(1, Characters.SNOOP)
        a.send(Msg.STATE, SnapshotCodec.toText(w.snapshot(1))); a.send(Msg.STATE, "AAAA")
        assertNotNull(c.latest)
    }
}

class TcpTest {
    @Test fun twoPlayersOverRealSocketsOnLocalhost() {
        val world = World(5); world.addPlayer(0, Characters.SNOOP); val host = HostSession(world)
        val server = TcpHost(0) { host.attach(it) }
        try {
            val client = ClientSession(TcpLink.connect("127.0.0.1", server.port), "martha")
            val deadline = System.currentTimeMillis() + 5000
            while (client.latest == null && System.currentTimeMillis() < deadline) { client.tick(0.05f, PlayerInput(dx = 1f)); host.tick(0.05f); Thread.sleep(10) }
            assertEquals(1, client.playerId); assertNotNull(client.latest); assertEquals(2, world.players.size)
            client.close()
            val d2 = System.currentTimeMillis() + 5000
            while (world.players.size > 1 && System.currentTimeMillis() < d2) { host.tick(0.05f); Thread.sleep(10) }
            assertEquals(1, world.players.size)
        } finally { server.close() }
    }
}

/** Added for the VictorLink64 integration (Victorious Framework): leaving a shared link, and a host that wasn't listening yet. */
class SharedLinkTest {
    @Test fun byeRemovesPlayerButKeepsLinkUsable() {
        val (a, b) = LoopbackLink.pair()
        val host = HostSession.create(Characters.SNOOP); host.attach(a)
        val client = ClientSession(b, "martha")
        host.tick(0.05f); assertEquals(1, host.peerCount); assertEquals(1, client.playerId)
        client.close() // sends BYE then closes the link
        host.tick(0.05f)
        assertEquals(0, host.peerCount); assertNull(host.world.players[1])
    }

    /** Drops everything sent to it until [hearing] is set, like a shared session with nobody collecting yet. */
    private class DeafStartLink(private val peerRef: () -> DeafStartLink?) : NetLink {
        @Volatile var hearing = false
        @Volatile override var listener: NetLink.Listener? = null
        override fun send(type: String, data: String) { val p = peerRef(); if (p != null && p.hearing) p.listener?.onMessage(type, data) }
        override fun close() { listener?.onClosed() }
    }

    @Test fun joinIsRepeatedUntilWelcomed() {
        var h: DeafStartLink? = null; var c: DeafStartLink? = null
        val hostLink = DeafStartLink { c }.also { h = it }; val clientLink = DeafStartLink { h }.also { c = it }
        clientLink.hearing = true
        val client = ClientSession(clientLink, "martha") // host not listening: this JOIN is lost
        val host = HostSession.create(Characters.SNOOP); host.attach(hostLink); hostLink.hearing = true
        host.tick(0.05f); assertEquals(0, host.peerCount)
        client.tick(1.1f, PlayerInput()) // retry fires
        host.tick(0.05f)
        assertEquals(1, host.peerCount); assertEquals(1, client.playerId)
    }
}
