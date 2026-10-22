package com.victorious.zombie.logic

import kotlin.random.Random

/** Original weapon roster (placeholder names, no third-party IP). */
data class Weapon(
    val id: String,
    val name: String,
    val damage: Int,
    val fireInterval: Float,
    val bulletSpeed: Float = 700f,
    val pellets: Int = 1,
    /** Extra zombies a bullet passes through before it stops. */
    val pierce: Int = 0,
    /** Radius of the blast on impact; splash targets take half damage. 0 = none. */
    val splash: Float = 0f,
    /** Forge upgrade level, 0..[MAX_TIER]. */
    val tier: Int = 0,
    /** Shots per magazine; one trigger pull uses one round regardless of [pellets]. */
    val magSize: Int = 12,
    val reloadTime: Float = 1.5f,
) {
    val upgraded: Boolean get() = tier > 0
    val maxed: Boolean get() = tier >= MAX_TIER
    val displayName: String get() = if (tier == 0) name else "$name Mk.${tier + 1}"

    /** One Forge step. Mk.II: x2 damage, faster fire. Mk.III: x1.5 more damage, faster again, +1 pierce.
     *  Multi-pellet weapons gain 2 pellets per step; blast weapons gain 50% radius per step. */
    fun upgradedVersion(): Weapon {
        if (maxed) return this
        val t = tier + 1
        return copy(
            tier = t,
            damage = (damage * DAMAGE_STEP[tier]).toInt(),
            fireInterval = fireInterval * INTERVAL_STEP[tier],
            pellets = if (pellets > 1) pellets + 2 else pellets,
            pierce = if (t == MAX_TIER) pierce + 1 else pierce,
            splash = splash * 1.5f,
            magSize = (magSize * 1.5f).toInt(),
            reloadTime = reloadTime * 0.85f,
        )
    }

    companion object {
        const val MAX_TIER = 2
        private val DAMAGE_STEP = floatArrayOf(2.0f, 1.5f)
        private val INTERVAL_STEP = floatArrayOf(0.8f, 0.8125f)
    }
}

object Weapons {
    val STARTER = Weapon("sidearm", "Volt Sidearm", 30, 0.35f, magSize = 12, reloadTime = 1.2f)
    val SCATTER = Weapon("scatter", "Scatterbolt", 25, 0.8f, pellets = 5, magSize = 6, reloadTime = 2.0f)
    val RIFLE = Weapon("rifle", "Pulse Rifle", 40, 0.12f, magSize = 30, reloadTime = 1.8f)
    val CANNON = Weapon("cannon", "Rail Lancer", 200, 0.9f, bulletSpeed = 1100f, pierce = 2, magSize = 5, reloadTime = 2.2f)
    val SMG = Weapon("smg", "Buzzsaw SMG", 22, 0.07f, magSize = 40, reloadTime = 1.9f)
    val NOVA = Weapon("nova", "Nova Disruptor", 400, 0.6f, bulletSpeed = 500f, splash = 60f, magSize = 4, reloadTime = 2.5f)

    val ALL = listOf(STARTER, SCATTER, RIFLE, CANNON, SMG, NOVA)

    /** Base weapon with the given id; null if unknown. */
    fun byId(id: String): Weapon? = ALL.firstOrNull { it.id == id }

    /** Reconstructs a weapon from its id and Forge tier (used to rebuild state received over the network). */
    fun at(id: String, tier: Int): Weapon {
        var w = byId(id) ?: STARTER
        repeat(tier.coerceIn(0, Weapon.MAX_TIER)) { w = w.upgradedVersion() }
        return w
    }
}

/** Mystery weapon box: weighted random weapon for a point cost. */
class MysteryBox(
    val cost: Int = COST,
    val table: List<Pair<Weapon, Int>> = DEFAULT_TABLE,
) {
    init { require(table.isNotEmpty() && table.all { it.second > 0 }) }

    val totalWeight: Int get() = table.sumOf { it.second }

    fun probabilityOf(weaponId: String): Double =
        table.filter { it.first.id == weaponId }.sumOf { it.second }.toDouble() / totalWeight

    fun roll(rng: Random, exclude: String? = null): Weapon {
        val pool = table.filter { it.first.id != exclude }.ifEmpty { table }
        var r = rng.nextInt(pool.sumOf { it.second })
        for ((w, weight) in pool) {
            if (r < weight) return w
            r -= weight
        }
        return pool.last().first
    }

    /** Spend points and roll; null if unaffordable. */
    fun buy(economy: Economy, rng: Random, current: String? = null): Weapon? =
        if (economy.spend(cost)) roll(rng, current) else null

    companion object {
        const val COST = 950
        val DEFAULT_TABLE = listOf(
            Weapons.SCATTER to 30,
            Weapons.SMG to 30,
            Weapons.RIFLE to 25,
            Weapons.CANNON to 10,
            Weapons.NOVA to 5,
        )
    }
}
