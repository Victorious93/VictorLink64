package com.victorious.zombie.logic

/** A window barricade made of planks. Zombies tear planks off; players repair them. */
class Barricade(val maxPlanks: Int = 6, val plankHp: Int = 50) {
    var hp: Int = maxPlanks * plankHp
        private set
    val maxHp: Int get() = maxPlanks * plankHp
    val isBroken: Boolean get() = hp <= 0
    val planks: Int get() = (hp + plankHp - 1) / plankHp

    fun damage(amount: Int) { require(amount >= 0); hp = maxOf(0, hp - amount) }

    /** Restores one plank. Returns true if a plank was actually added (so points can be awarded). */
    fun repairPlank(): Boolean {
        if (hp >= maxHp) return false
        hp = minOf(maxHp, (hp / plankHp + 1) * plankHp)
        return true
    }

    /** Restores up to [n] planks; returns how many were actually added. */
    fun repairPlanks(n: Int): Int { var done = 0; while (done < n && repairPlank()) done++; return done }

    /** Restores every missing plank; returns how many were added. */
    fun repairAll(): Int = repairPlanks(maxPlanks)
}
