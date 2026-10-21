package com.victorious.zombie.logic

/** A purchasable armor pack. */
data class ArmorPack(val id: String, val name: String, val cost: Int, val armor: Int)

object ArmorPacks {
    val LIGHT = ArmorPack("light", "Light Plating", 500, 50)
    val MEDIUM = ArmorPack("medium", "Reinforced Vest", 1000, 100)
    val HEAVY = ArmorPack("heavy", "Bulwark Suit", 1800, 150)
    val ALL = listOf(LIGHT, MEDIUM, HEAVY)

    /** Chance that a killed zombie drops a free [LIGHT] pack. */
    const val DROP_CHANCE = 0.06
}

/** Armor soaks damage before health. Persists between rounds (health does not need to). */
class ArmorState(val max: Int = 200) {
    var value: Float = 0f
        private set

    val isFull: Boolean get() = value >= max

    /** Adds armor (clamped to [max]). Returns false and changes nothing if already full. */
    fun add(amount: Int): Boolean {
        require(amount >= 0)
        if (isFull) return false
        value = minOf(max.toFloat(), value + amount)
        return true
    }

    /** Buys [pack]. Not charged if armor is already full or points are short. */
    fun buy(pack: ArmorPack, economy: Economy): Boolean {
        if (isFull || pack.cost > economy.points) return false
        economy.spend(pack.cost)
        add(pack.armor)
        return true
    }

    /** Applies [damage]; armor takes it first. Returns the damage that reaches health. */
    fun absorb(damage: Float): Float {
        require(damage >= 0f)
        val soaked = minOf(value, damage)
        value -= soaked
        return damage - soaked
    }
}
