package com.victorious.zombie.logic

/** Pure round-scaling rules. Original tuning for VictorOps Zombies. */
object Rounds {
    const val MAX_ALIVE = 24

    /** Total zombies spawned in a round (round starts at 1). */
    fun zombieCount(round: Int): Int {
        require(round >= 1)
        return minOf(6 + (round - 1) * 3 + (round - 1) * (round - 1) / 4, 120)
    }

    /** Hit points of each zombie in a round: linear to round 9, then +10% per round. */
    fun zombieHealth(round: Int): Int {
        require(round >= 1)
        if (round <= 9) return 100 + (round - 1) * 50
        var hp = 500.0
        repeat(round - 9) { hp *= 1.1 }
        return hp.toInt()
    }

    /** Zombie movement speed in world units/s, capped. */
    fun zombieSpeed(round: Int): Float = minOf(60f + round * 6f, 150f)

    /** Seconds between spawns, getting faster but floored. */
    fun spawnInterval(round: Int): Float = maxOf(2.0f - round * 0.1f, 0.4f)
}
