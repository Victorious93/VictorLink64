package com.victorious.zombie.sim

import com.badlogic.gdx.utils.Base64Coder
import com.victorious.zombie.logic.Weapon
import com.victorious.zombie.logic.Weapons
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class PlayerSnap(
    val id: Int, val characterId: String, val x: Float, val y: Float,
    val health: Float, val maxHealth: Float, val armor: Float, val downed: Boolean, val reviveProgress: Float,
    val points: Int, val weapon: Weapon, val rounds: Int, val reloading: Boolean,
    val abilityCd: Float, val perkOwned: Boolean, val bark: String,
)

class PointSnap(val x: Float, val y: Float)
class ZombieSnap(val id: Int, val x: Float, val y: Float)

/**
 * Everything a screen needs to draw one frame, from the point of view of one player.
 * The host builds it from the [World]; clients decode it from the network. [prompt] and [message] are personal.
 */
class Snapshot(
    val round: Int, val gameOver: Boolean, val banner: String,
    val players: List<PlayerSnap>, val barricadeHp: IntArray,
    val zombies: List<ZombieSnap>, val bullets: List<PointSnap>, val pickups: List<PointSnap>,
    val prompt: String, val message: String,
)

object SnapshotCodec {
    private const val MAX_ENTITIES = 512

    fun encode(s: Snapshot): ByteArray {
        val bo = ByteArrayOutputStream(1024)
        DataOutputStream(bo).use { o ->
            o.writeShort(s.round); o.writeBoolean(s.gameOver); o.writeUTF(s.banner); o.writeUTF(s.prompt); o.writeUTF(s.message)
            o.writeByte(s.players.size)
            for (p in s.players) {
                o.writeByte(p.id); o.writeUTF(p.characterId); o.writeFloat(p.x); o.writeFloat(p.y)
                o.writeFloat(p.health); o.writeFloat(p.maxHealth); o.writeFloat(p.armor); o.writeBoolean(p.downed); o.writeFloat(p.reviveProgress)
                o.writeInt(p.points); o.writeUTF(p.weapon.id); o.writeByte(p.weapon.tier); o.writeShort(p.rounds); o.writeBoolean(p.reloading)
                o.writeFloat(p.abilityCd); o.writeBoolean(p.perkOwned); o.writeUTF(p.bark)
            }
            o.writeByte(s.barricadeHp.size); for (h in s.barricadeHp) o.writeShort(h)
            o.writeShort(s.zombies.size); for (z in s.zombies) { o.writeShort(z.id); o.writeFloat(z.x); o.writeFloat(z.y) }
            o.writeShort(s.bullets.size); for (b in s.bullets) { o.writeFloat(b.x); o.writeFloat(b.y) }
            o.writeShort(s.pickups.size); for (b in s.pickups) { o.writeFloat(b.x); o.writeFloat(b.y) }
        }
        return bo.toByteArray()
    }

    /** Decodes untrusted bytes; throws on malformed input (callers should drop the packet). */
    fun decode(bytes: ByteArray): Snapshot {
        val i = DataInputStream(ByteArrayInputStream(bytes))
        val round = i.readShort().toInt(); val over = i.readBoolean(); val banner = i.readUTF(); val prompt = i.readUTF(); val msg = i.readUTF()
        val players = List(i.readUnsignedByte()) {
            val id = i.readUnsignedByte(); val ch = i.readUTF(); val x = i.readFloat(); val y = i.readFloat()
            val hp = i.readFloat(); val mhp = i.readFloat(); val ar = i.readFloat(); val dn = i.readBoolean(); val rv = i.readFloat()
            val pts = i.readInt(); val wid = i.readUTF(); val tier = i.readUnsignedByte(); val rounds = i.readShort().toInt(); val rl = i.readBoolean()
            val acd = i.readFloat(); val perk = i.readBoolean(); val bark = i.readUTF()
            PlayerSnap(id, ch, x, y, hp, mhp, ar, dn, rv, pts, Weapons.at(wid, tier), rounds, rl, acd, perk, bark)
        }
        val bars = IntArray(i.readUnsignedByte()) { i.readShort().toInt() }
        fun count(): Int = i.readUnsignedShort().also { require(it <= MAX_ENTITIES) { "too many entities" } }
        val zombies = List(count()) { ZombieSnap(i.readUnsignedShort(), i.readFloat(), i.readFloat()) }
        val bullets = List(count()) { PointSnap(i.readFloat(), i.readFloat()) }
        val pickups = List(count()) { PointSnap(i.readFloat(), i.readFloat()) }
        return Snapshot(round, over, banner, players, bars, zombies, bullets, pickups, prompt, msg)
    }

    /** Text-safe form for JSON/line transports. */
    fun toText(s: Snapshot): String = String(Base64Coder.encode(encode(s)))
    fun fromText(t: String): Snapshot = decode(Base64Coder.decode(t))
}

/** One player's controls for a tick. Sent client -> host. */
data class PlayerInput(val dx: Float = 0f, val dy: Float = 0f, val use: Boolean = false, val ability: Boolean = false) {
    fun toText(): String = "${(dx.coerceIn(-1f, 1f) * 100).toInt()},${(dy.coerceIn(-1f, 1f) * 100).toInt()},${if (use) 1 else 0},${if (ability) 1 else 0}"
    companion object {
        fun fromText(t: String): PlayerInput? {
            val p = t.split(',')
            if (p.size != 4) return null
            val dx = p[0].toIntOrNull() ?: return null; val dy = p[1].toIntOrNull() ?: return null
            return PlayerInput(dx.coerceIn(-100, 100) / 100f, dy.coerceIn(-100, 100) / 100f, p[2] == "1", p[3] == "1")
        }
    }
}
