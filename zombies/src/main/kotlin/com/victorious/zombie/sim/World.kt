package com.victorious.zombie.sim

import com.victorious.zombie.logic.*
import kotlin.math.hypot
import kotlin.random.Random

class SimPlayer(val id: Int, val character: Character) {
    var x = Layout.W / 2; var y = Layout.H / 2
    var maxHealth = character.maxHealth; var health = character.maxHealth
    val armor = ArmorState(); val economy = Economy(); val perk = PerkMachine()
    var weapon: Weapon = character.startWeapon; val ammo = Ammo(character.startWeapon)
    var fireCd = 0f; var useCd = 0f; var abilityCd = 0f
    var downed = false; var reviveT = 0f
    var input = PlayerInput()
    var message = ""; var messageT = 0f
    var bark = ""; var barkT = 0f
    val lowHealth = LowHealthTrigger()

    fun say(s: String) { message = s; messageT = 2f }
    fun shout(s: String, secs: Float = Barks.DURATION) { bark = s; barkT = secs }
}

class SimZombie(val id: Int, var x: Float, var y: Float, var hp: Int, val window: Int, var inside: Boolean = false)
class SimBullet(var x: Float, var y: Float, val vx: Float, val vy: Float, val dmg: Int, var pierce: Int, val splash: Float, val owner: Int) {
    var life = 1.2f
    val hit = HashSet<SimZombie>()
}
class SimPickup(val x: Float, val y: Float, val pack: ArmorPack) { var life = 15f }

/**
 * Authoritative game simulation for 1..[MAX_PLAYERS] players. No rendering, no input devices, no networking:
 * the host feeds it [PlayerInput]s and calls [update]; everything else reads [snapshot]s.
 */
class World(seed: Long = System.nanoTime()) {
    companion object {
        const val MAX_PLAYERS = 4
        const val PLAYER_SPEED = 220f
        const val REVIVE_TIME = 3f
        const val REVIVE_RANGE = 60f
    }

    private val rng = Random(seed)
    val players = LinkedHashMap<Int, SimPlayer>()
    val zombies = ArrayList<SimZombie>(); val bullets = ArrayList<SimBullet>(); val pickups = ArrayList<SimPickup>()
    val barricades = List(Layout.windows.size) { Barricade() }
    private val box = MysteryBox(); private val forge = UpgradeMachine()
    var round = 1; private set
    var gameOver = false; private set
    var banner = "Round 1"; private set
    private var bannerT = 2f
    private var toSpawn = Rounds.zombieCount(1); private var spawnCd = 1f; private var intermission = 0f
    private var nextZombieId = 1

    fun addPlayer(id: Int, character: Character): SimPlayer? {
        if (players.size >= MAX_PLAYERS || id in players) return null
        return SimPlayer(id, character).also {
            it.x = Layout.W / 2 + (players.size - 1.5f) * 40f
            players[id] = it
            if (gameOver) { gameOver = false; banner = "Round $round"; bannerT = 2f }
        }
    }

    fun removePlayer(id: Int) { players.remove(id) }

    fun setInput(id: Int, input: PlayerInput) { players[id]?.input = input }

    /** Fresh game with the same players and characters. */
    fun reset() {
        val keep = players.values.map { it.id to it.character }
        players.clear(); zombies.clear(); bullets.clear(); pickups.clear()
        barricades.forEach { it.repairAll() }
        round = 1; gameOver = false; banner = "Round 1"; bannerT = 2f
        toSpawn = Rounds.zombieCount(1); spawnCd = 1f; intermission = 0f
        keep.forEach { (id, c) -> addPlayer(id, c) }
    }

    private fun alive() = players.values.filter { !it.downed }

    fun update(dt: Float) {
        bannerT -= dt
        for (p in players.values) { p.messageT -= dt; p.barkT -= dt; p.useCd -= dt; p.abilityCd -= dt; p.fireCd -= dt; p.ammo.update(dt) }
        if (gameOver) return

        for (p in alive()) { move(p, dt); shoot(p); if (p.input.ability && p.abilityCd <= 0f) useAbility(p); if (p.input.use && p.useCd <= 0f) { interact(p); p.useCd = 0.35f } }
        updateBullets(dt); updateZombies(dt); updateRevives(dt); updatePickups(dt)

        for (p in players.values) if (!p.downed && p.lowHealth.check(p.health, p.maxHealth)) p.shout(Barks.DANGER)
        for (p in players.values) if (!p.downed && p.health <= 0f) { p.downed = true; p.reviveT = 0f; p.say("Down! A teammate can revive you") }
        if (players.isNotEmpty() && players.values.all { it.downed }) {
            gameOver = true; banner = "OVERRUN on round $round"; bannerT = 999f
            players.values.forEach { it.shout(Barks.GAME_OVER, 999f) }
            return
        }
        updateRounds(dt)
    }

    private fun move(p: SimPlayer, dt: Float) {
        var dx = p.input.dx; var dy = p.input.dy
        val len = hypot(dx, dy); if (len > 1f) { dx /= len; dy /= len }
        p.x = (p.x + dx * PLAYER_SPEED * p.character.speedMult * dt).coerceIn(Layout.MIN_X + 15, Layout.MAX_X - 15)
        p.y = (p.y + dy * PLAYER_SPEED * p.character.speedMult * dt).coerceIn(Layout.MIN_Y + 15, Layout.MAX_Y - 15)
    }

    private fun shoot(p: SimPlayer) {
        if (p.fireCd > 0f || !p.ammo.canFire) return
        val t = zombies.minByOrNull { dist2(it.x, it.y, p.x, p.y) } ?: return
        val d = hypot(t.x - p.x, t.y - p.y)
        if (d >= 520f || d == 0f) return
        val ax = (t.x - p.x) / d; val ay = (t.y - p.y) / d
        val w = p.weapon
        repeat(w.pellets) {
            val a = if (w.pellets > 1) (rng.nextFloat() - 0.5f) * 0.5f else 0f
            val c = kotlin.math.cos(a); val s = kotlin.math.sin(a)
            bullets += SimBullet(p.x, p.y, (ax * c - ay * s) * w.bulletSpeed, (ax * s + ay * c) * w.bulletSpeed, w.damage, w.pierce, w.splash, p.id)
        }
        p.fireCd = w.fireInterval * p.character.fireIntervalMult
        if (p.ammo.fire()) p.shout(Barks.RELOAD)
    }

    private fun damageZombie(z: SimZombie, dmg: Int, owner: Int) {
        if (z.hp <= 0) return
        z.hp -= dmg
        if (z.hp <= 0) {
            zombies.remove(z); players[owner]?.economy?.onKill()
            if (rng.nextDouble() < ArmorPacks.DROP_CHANCE) pickups += SimPickup(z.x, z.y, ArmorPacks.LIGHT)
        }
    }

    private fun updateBullets(dt: Float) {
        val it = bullets.iterator()
        while (it.hasNext()) {
            val b = it.next(); b.x += b.vx * dt; b.y += b.vy * dt; b.life -= dt
            val hit = zombies.firstOrNull { z -> z !in b.hit && dist2(z.x, z.y, b.x, b.y) < 18f * 18f }
            if (hit != null) {
                players[b.owner]?.economy?.onHit(); damageZombie(hit, b.dmg, b.owner)
                if (b.splash > 0f) zombies.filter { z -> z !== hit && dist2(z.x, z.y, hit.x, hit.y) < b.splash * b.splash }.forEach { z -> damageZombie(z, b.dmg / 2, b.owner) }
                b.hit += hit
                if (b.pierce > 0) b.pierce-- else it.remove()
            } else if (b.life <= 0f) it.remove()
        }
    }

    private fun updateZombies(dt: Float) {
        val speed = Rounds.zombieSpeed(round)
        val targets = alive()
        for (z in zombies) {
            val w = Layout.windows[z.window]; val bar = barricades[z.window]
            if (!z.inside) {
                if (hypot(w.inside.x - z.x, w.inside.y - z.y) > 22f) stepToward(z, w.inside.x, w.inside.y, speed * dt)
                else if (!bar.isBroken) { if (rng.nextFloat() < dt * 1.5f) bar.damage(bar.plankHp) }
                else z.inside = true
            } else {
                val t = targets.minByOrNull { dist2(it.x, it.y, z.x, z.y) } ?: continue
                stepToward(z, t.x, t.y, speed * dt)
                if (hypot(t.x - z.x, t.y - z.y) < 26f) t.health -= t.armor.absorb(30f * dt)
            }
        }
    }

    private fun stepToward(z: SimZombie, tx: Float, ty: Float, step: Float) {
        val d = hypot(tx - z.x, ty - z.y); if (d < 0.001f) return
        z.x += (tx - z.x) / d * step; z.y += (ty - z.y) / d * step
    }

    private fun updateRevives(dt: Float) {
        for (d in players.values.filter { it.downed }) {
            val helper = alive().any { hypot(it.x - d.x, it.y - d.y) < REVIVE_RANGE }
            d.reviveT = if (helper) d.reviveT + dt else maxOf(0f, d.reviveT - dt)
            if (d.reviveT >= REVIVE_TIME) { d.downed = false; d.reviveT = 0f; d.health = d.maxHealth * 0.5f; d.say("Revived!") }
        }
    }

    private fun updatePickups(dt: Float) {
        val it = pickups.iterator()
        while (it.hasNext()) {
            val p = it.next(); p.life -= dt
            if (p.life <= 0f) { it.remove(); continue }
            val taker = alive().firstOrNull { pl -> hypot(pl.x - p.x, pl.y - p.y) < 26f && !pl.armor.isFull } ?: continue
            taker.armor.add(p.pack.armor); taker.say("+${p.pack.armor} armor"); it.remove()
        }
    }

    private fun updateRounds(dt: Float) {
        if (intermission > 0f) {
            intermission -= dt
            if (intermission <= 0f) { round++; toSpawn = Rounds.zombieCount(round); banner = "Round $round"; bannerT = 2f }
        } else if (toSpawn > 0) {
            spawnCd -= dt
            if (spawnCd <= 0f && zombies.size < Rounds.MAX_ALIVE) {
                val wi = rng.nextInt(Layout.windows.size); val sp = Layout.windows[wi].spawn
                zombies += SimZombie(nextZombieId++ and 0xFFFF, sp.x + (rng.nextFloat() - 0.5f) * 40f, sp.y + (rng.nextFloat() - 0.5f) * 40f, Rounds.zombieHealth(round), wi)
                toSpawn--; spawnCd = Rounds.spawnInterval(round)
            }
        } else if (zombies.isEmpty()) {
            intermission = 6f; banner = "Round $round survived"; bannerT = 3f
            for (p in players.values) { p.downed = false; p.reviveT = 0f; p.health = p.maxHealth }
        }
    }

    private fun near(p: SimPlayer, pt: Layout.Point) = hypot(p.x - pt.x, p.y - pt.y) < Layout.INTERACT_RANGE

    private fun useAbility(p: SimPlayer) {
        when (p.character.ability) {
            Ability.BASS_DROP -> {
                val dmg = AbilityRules.bassDamage(round)
                for (z in zombies.toList()) {
                    val d = hypot(z.x - p.x, z.y - p.y)
                    if (d > AbilityRules.BASS_RADIUS) continue
                    if (d > 0.001f) { z.x += (z.x - p.x) / d * AbilityRules.BASS_KNOCKBACK; z.y += (z.y - p.y) / d * AbilityRules.BASS_KNOCKBACK }
                    damageZombie(z, dmg, p.id)
                }
                p.say("${p.character.abilityName}!")
            }
            Ability.FORTIFY -> {
                val planks = barricades.sumOf { it.repairAll() }
                p.economy.onRepair(planks); p.say("${p.character.abilityName}: +$planks planks")
            }
        }
        p.abilityCd = p.character.abilityCooldown
    }

    /** What pressing USE would do for [p] here, or null. Also drives the on-screen prompt. */
    fun prompt(p: SimPlayer): String? {
        if (near(p, Layout.box)) return "Mystery Crate: ${box.cost}"
        if (near(p, Layout.perk)) return if (p.perk.owned) "${p.perk.name}: owned" else "${p.perk.name}: ${p.perk.cost}"
        if (near(p, Layout.forge)) return forge.costFor(p.weapon)?.let { "${forge.name} -> Mk.${p.weapon.tier + 2}: $it" } ?: "${forge.name}: maxed"
        Layout.armorStations.firstOrNull { near(p, it.first) }?.let { (_, pack) -> return "${pack.name} (+${pack.armor} armor): ${pack.cost}" }
        val i = Layout.windows.indexOfFirst { near(p, it.inside) }
        if (i >= 0 && barricades[i].hp < barricades[i].maxHp) return "Repair barricade (+${Economy.REPAIR})"
        return null
    }

    private fun interact(p: SimPlayer) {
        when {
            near(p, Layout.box) -> box.buy(p.economy, rng, p.weapon.id)?.let { p.weapon = it; p.ammo.swap(it); p.say("Got ${it.name}!") } ?: p.say("Not enough points")
            near(p, Layout.perk) -> if (p.perk.buy(p.economy)) { p.maxHealth = p.character.maxHealth * 2; p.health = p.maxHealth; p.say("${p.perk.name} active") } else p.say("Can't buy perk")
            near(p, Layout.forge) -> forge.upgrade(p.weapon, p.economy)?.let { p.weapon = it; p.ammo.swap(it); p.say("Upgraded: ${it.displayName}") } ?: p.say(if (p.weapon.maxed) "Already maxed" else "Not enough points")
            Layout.armorStations.any { near(p, it.first) } -> {
                val pack = Layout.armorStations.first { near(p, it.first) }.second
                when {
                    p.armor.isFull -> p.say("Armor full")
                    p.armor.buy(pack, p.economy) -> p.say("${pack.name} equipped")
                    else -> p.say("Not enough points")
                }
            }
            else -> {
                val i = Layout.windows.indexOfFirst { near(p, it.inside) }
                if (i >= 0) {
                    val n = barricades[i].repairPlanks(p.character.repairPlanksPerUse)
                    if (n > 0) p.economy.onRepair(n, p.character.repairPointsMult)
                }
            }
        }
    }

    /** The frame as seen by player [forId] (personal prompt and message). */
    fun snapshot(forId: Int): Snapshot {
        val me = players[forId]
        return Snapshot(
            round, gameOver, if (bannerT > 0f) banner else "",
            players.values.map { p ->
                PlayerSnap(p.id, p.character.id, p.x, p.y, p.health, p.maxHealth, p.armor.value, p.downed, p.reviveT / REVIVE_TIME,
                    p.economy.points, p.weapon, p.ammo.rounds, p.ammo.reloading, maxOf(0f, p.abilityCd), p.perk.owned, if (p.barkT > 0f) p.bark else "")
            },
            IntArray(barricades.size) { barricades[it].hp },
            zombies.map { ZombieSnap(it.id, it.x, it.y) }, bullets.map { PointSnap(it.x, it.y) }, pickups.map { PointSnap(it.x, it.y) },
            if (me != null && !me.downed) prompt(me) ?: "" else "", if (me != null && me.messageT > 0f) me.message else "",
        )
    }

    private fun dist2(ax: Float, ay: Float, bx: Float, by: Float) = (ax - bx) * (ax - bx) + (ay - by) * (ay - by)
}
