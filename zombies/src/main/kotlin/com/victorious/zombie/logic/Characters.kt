package com.victorious.zombie.logic

enum class Ability { BASS_DROP, FORTIFY }

/**
 * A playable character: stat modifiers plus one active ability.
 * Names are display data only. The game draws every character as a placeholder shape and uses no
 * real photos, voices or music. Swap [name] here to rebrand a character; nothing else depends on it.
 */
data class Character(
    val id: String,
    val name: String,
    val tagline: String,
    val maxHealth: Float,
    val speedMult: Float,
    val fireIntervalMult: Float,
    val startWeapon: Weapon,
    val repairPlanksPerUse: Int,
    val repairPointsMult: Int,
    val ability: Ability,
    val abilityName: String,
    val abilityCooldown: Float,
)

object Characters {
    val SNOOP = Character(
        id = "snoop", name = "Snoop Dogg", tagline = "Fast and smooth. Bass Drop knocks the horde back.",
        maxHealth = 100f, speedMult = 1.12f, fireIntervalMult = 0.85f, startWeapon = Weapons.SMG,
        repairPlanksPerUse = 1, repairPointsMult = 1,
        ability = Ability.BASS_DROP, abilityName = "Bass Drop", abilityCooldown = 25f,
    )
    val MARTHA = Character(
        id = "martha", name = "Martha Stewart", tagline = "Tough and tidy. Repairs fast, Fortify rebuilds every window.",
        maxHealth = 130f, speedMult = 1.0f, fireIntervalMult = 1.0f, startWeapon = Weapons.SCATTER,
        repairPlanksPerUse = 2, repairPointsMult = 2,
        ability = Ability.FORTIFY, abilityName = "Fortify", abilityCooldown = 35f,
    )
    val ALL = listOf(SNOOP, MARTHA)
    val DEFAULT = SNOOP
    fun byId(id: String): Character = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

/** Ability tuning shared by game and tests. */
object AbilityRules {
    const val BASS_RADIUS = 240f
    const val BASS_KNOCKBACK = 140f
    fun bassDamage(round: Int): Int = 100 + 40 * round
}
