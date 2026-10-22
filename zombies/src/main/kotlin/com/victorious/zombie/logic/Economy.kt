package com.victorious.zombie.logic

/** Points economy: earn by hitting/killing/repairing, spend at machines. */
class Economy(startingPoints: Int = 500) {
    var points: Int = startingPoints
        private set

    fun onHit() { points += HIT }
    fun onKill(headshot: Boolean = false) { points += if (headshot) KILL_CRIT else KILL }
    fun onRepair(planks: Int = 1, multiplier: Int = 1) { points += REPAIR * planks * multiplier }

    /** Attempts to spend; returns true and deducts only if affordable. */
    fun spend(cost: Int): Boolean {
        require(cost >= 0)
        if (cost > points) return false
        points -= cost
        return true
    }

    companion object {
        const val HIT = 10
        const val KILL = 60
        const val KILL_CRIT = 100
        const val REPAIR = 10
    }
}
