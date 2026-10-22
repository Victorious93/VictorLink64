package com.victorious.zombie.logic

/** Magazine + reload state for the equipped weapon. Reserve ammo is unlimited; reloading just takes time. */
class Ammo(weapon: Weapon) {
    var weapon: Weapon = weapon
        private set
    var rounds: Int = weapon.magSize
        private set
    var reloadLeft: Float = 0f
        private set

    val reloading: Boolean get() = reloadLeft > 0f
    val canFire: Boolean get() = !reloading && rounds > 0

    /** Uses one round. Returns true if that emptied the magazine and a reload began. */
    fun fire(): Boolean {
        check(canFire) { "cannot fire" }
        rounds--
        if (rounds == 0) { reloadLeft = weapon.reloadTime; return true }
        return false
    }

    fun update(dt: Float) {
        if (!reloading) return
        reloadLeft -= dt
        if (reloadLeft <= 0f) { reloadLeft = 0f; rounds = weapon.magSize }
    }

    /** New or upgraded weapon: starts with a full magazine. */
    fun swap(newWeapon: Weapon) { weapon = newWeapon; rounds = newWeapon.magSize; reloadLeft = 0f }
}
