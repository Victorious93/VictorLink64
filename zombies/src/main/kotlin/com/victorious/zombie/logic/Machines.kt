package com.victorious.zombie.logic

/** One perk: "Iron Hide" doubles max health. */
class PerkMachine(val name: String = "Iron Hide Tonic", val cost: Int = 2500) {
    var owned = false
        private set
    fun buy(economy: Economy): Boolean {
        if (owned || !economy.spend(cost)) return false
        owned = true
        return true
    }
}

/** Weapon upgrade machine ("Overcharge Forge"). Each tier costs more: [cost], then 2x, ... */
class UpgradeMachine(val name: String = "Overcharge Forge", val cost: Int = 5000) {
    /** Price of the next upgrade for [weapon], or null if it is already maxed. */
    fun costFor(weapon: Weapon): Int? = if (weapon.maxed) null else cost * (weapon.tier + 1)

    fun upgrade(weapon: Weapon, economy: Economy): Weapon? {
        val price = costFor(weapon) ?: return null
        if (!economy.spend(price)) return null
        return weapon.upgradedVersion()
    }
}
