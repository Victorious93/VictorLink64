package com.victorious.zombie.logic

/** Shouts shown above the player. Text only, no voice audio. Shared by every character. */
object Barks {
    const val DANGER = "Oh shit, gang!"
    const val RELOAD = "Reload, bitch!!"
    const val GAME_OVER = "Game over, mofo!"
    const val DURATION = 2.0f
}

/** Fires once when health drops under [low], and re-arms only after it recovers above [rearm]. */
class LowHealthTrigger(private val low: Float = 0.3f, private val rearm: Float = 0.5f) {
    private var armed = true
    fun check(health: Float, maxHealth: Float): Boolean {
        val f = health / maxHealth
        if (f >= rearm) armed = true
        if (armed && f > 0f && f < low) { armed = false; return true }
        return false
    }
}
