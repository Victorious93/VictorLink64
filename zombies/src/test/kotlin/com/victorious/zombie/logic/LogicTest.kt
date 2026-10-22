package com.victorious.zombie.logic

import kotlin.random.Random
import kotlin.test.*

class RoundsTest {
    @Test fun round1Baseline() { assertEquals(6, Rounds.zombieCount(1)); assertEquals(100, Rounds.zombieHealth(1)) }
    @Test fun countIsMonotonic() { for (r in 1..60) assertTrue(Rounds.zombieCount(r + 1) >= Rounds.zombieCount(r)) }
    @Test fun countCapped() { assertEquals(120, Rounds.zombieCount(500)) }
    @Test fun healthLinearThenExponential() {
        assertEquals(500, Rounds.zombieHealth(9))
        assertEquals(550, Rounds.zombieHealth(10))
        assertTrue(Rounds.zombieHealth(20) > Rounds.zombieHealth(19))
    }
    @Test fun speedAndIntervalBounded() {
        assertEquals(150f, Rounds.zombieSpeed(100)); assertEquals(0.4f, Rounds.spawnInterval(100))
    }
    @Test fun invalidRoundRejected() { assertFailsWith<IllegalArgumentException> { Rounds.zombieCount(0) } }
}

class EconomyTest {
    @Test fun startsWith500() = assertEquals(500, Economy().points)
    @Test fun earning() {
        val e = Economy(0); e.onHit(); e.onKill(); e.onKill(headshot = true); e.onRepair()
        assertEquals(10 + 60 + 100 + 10, e.points)
    }
    @Test fun cannotOverspend() { val e = Economy(100); assertFalse(e.spend(101)); assertEquals(100, e.points) }
    @Test fun spendDeducts() { val e = Economy(1000); assertTrue(e.spend(950)); assertEquals(50, e.points) }
    @Test fun perkOnlyOnce() {
        val e = Economy(6000); val p = PerkMachine()
        assertTrue(p.buy(e)); assertFalse(p.buy(e)); assertEquals(3500, e.points)
    }
    @Test fun upgradeDoublesDamageOnce() {
        val e = Economy(10000); val u = UpgradeMachine()
        val up = u.upgrade(Weapons.STARTER, e)!!
        assertEquals(Weapons.STARTER.damage * 2, up.damage); assertNull(u.upgrade(up, e)); assertEquals(5000, e.points)
    }
}

class MysteryBoxTest {
    @Test fun probabilitiesSumToOne() {
        val b = MysteryBox(); assertEquals(1.0, b.table.sumOf { b.probabilityOf(it.first.id) }, 1e-9)
        assertEquals(0.05, b.probabilityOf("nova"), 1e-9)
    }
    @Test fun empiricalOddsMatchWeights() {
        val b = MysteryBox(); val rng = Random(42); val n = 100_000
        val counts = (1..n).groupingBy { b.roll(rng).id }.eachCount()
        for ((w, _) in b.table) assertEquals(b.probabilityOf(w.id), counts.getValue(w.id).toDouble() / n, 0.01)
    }
    @Test fun excludesCurrentWeapon() {
        val b = MysteryBox(); val rng = Random(1); repeat(1000) { assertNotEquals("smg", b.roll(rng, exclude = "smg").id) }
    }
    @Test fun buyRequiresPoints() {
        val b = MysteryBox(); assertNull(b.buy(Economy(900), Random(0)))
        val e = Economy(1000); assertNotNull(b.buy(e, Random(0))); assertEquals(50, e.points)
    }
}

class BarricadeTest {
    @Test fun startsFull() { val b = Barricade(); assertEquals(300, b.hp); assertEquals(6, b.planks); assertFalse(b.isBroken) }
    @Test fun damageClampsAtZero() { val b = Barricade(); b.damage(1000); assertEquals(0, b.hp); assertTrue(b.isBroken) }
    @Test fun partialPlank() { val b = Barricade(); b.damage(60); assertEquals(5, b.planks); assertEquals(240, b.hp) }
    @Test fun repairRestoresOnePlank() {
        val b = Barricade(); b.damage(300); assertTrue(b.repairPlank()); assertEquals(50, b.hp)
        b.damage(10); assertTrue(b.repairPlank()); assertEquals(50, b.hp)
    }
    @Test fun repairFullIsNoop() { assertFalse(Barricade().repairPlank()) }
}

class WeaponTierTest {
    @Test fun threeTiersThenMaxed() {
        var w = Weapons.STARTER
        assertEquals("Volt Sidearm", w.displayName)
        w = w.upgradedVersion(); assertEquals("Volt Sidearm Mk.2", w.displayName); assertEquals(60, w.damage); assertFalse(w.maxed)
        w = w.upgradedVersion(); assertEquals("Volt Sidearm Mk.3", w.displayName); assertEquals(90, w.damage); assertTrue(w.maxed)
        assertSame(w, w.upgradedVersion())
    }
    @Test fun fireGetsFasterEachTier() {
        val a = Weapons.RIFLE; val b = a.upgradedVersion(); val c = b.upgradedVersion()
        assertTrue(a.fireInterval > b.fireInterval && b.fireInterval > c.fireInterval)
    }
    @Test fun scatterGainsPelletsAndLancerPierces() {
        assertEquals(9, Weapons.SCATTER.upgradedVersion().upgradedVersion().pellets)
        assertEquals(3, Weapons.CANNON.upgradedVersion().upgradedVersion().pierce)
        assertEquals(0, Weapons.RIFLE.upgradedVersion().pierce); assertEquals(1, Weapons.RIFLE.upgradedVersion().upgradedVersion().pierce)
    }
    @Test fun novaBlastGrows() { assertTrue(Weapons.NOVA.upgradedVersion().splash > Weapons.NOVA.splash) }
    @Test fun forgeCostsEscalateAndStop() {
        val e = Economy(100_000); val f = UpgradeMachine()
        assertEquals(5000, f.costFor(Weapons.SMG))
        val t1 = f.upgrade(Weapons.SMG, e)!!; assertEquals(95_000, e.points); assertEquals(10_000, f.costFor(t1))
        val t2 = f.upgrade(t1, e)!!; assertEquals(85_000, e.points)
        assertNull(f.costFor(t2)); assertNull(f.upgrade(t2, e)); assertEquals(85_000, e.points)
    }
}

class ArmorTest {
    @Test fun absorbsBeforeHealth() {
        val a = ArmorState(); a.add(50)
        assertEquals(0f, a.absorb(30f)); assertEquals(20f, a.value)
        assertEquals(10f, a.absorb(30f)); assertEquals(0f, a.value)
    }
    @Test fun capsAtMax() { val a = ArmorState(200); a.add(150); a.add(150); assertEquals(200f, a.value); assertTrue(a.isFull) }
    @Test fun buyChargesOnlyWhenUseful() {
        val a = ArmorState(); val e = Economy(10_000)
        assertTrue(a.buy(ArmorPacks.HEAVY, e)); assertEquals(8200, e.points); assertEquals(150f, a.value)
        assertTrue(a.buy(ArmorPacks.MEDIUM, e)); assertEquals(200f, a.value)
        val before = e.points; assertFalse(a.buy(ArmorPacks.LIGHT, e)); assertEquals(before, e.points)
    }
    @Test fun cannotAffordIsNoop() { val a = ArmorState(); val e = Economy(499); assertFalse(a.buy(ArmorPacks.LIGHT, e)); assertEquals(0f, a.value); assertEquals(499, e.points) }
    @Test fun threePacksAscend() {
        val p = ArmorPacks.ALL; assertEquals(3, p.size)
        assertTrue(p.zipWithNext().all { (x, y) -> x.cost < y.cost && x.armor < y.armor })
    }
}

class CharacterTest {
    @Test fun rosterAndLookup() { assertEquals(2, Characters.ALL.size); assertSame(Characters.MARTHA, Characters.byId("martha")); assertSame(Characters.DEFAULT, Characters.byId("nope")) }
    @Test fun marthaRepairsMoreAndEarnsMore() {
        assertTrue(Characters.MARTHA.repairPlanksPerUse > Characters.SNOOP.repairPlanksPerUse)
        val e = Economy(0); e.onRepair(2, Characters.MARTHA.repairPointsMult); assertEquals(40, e.points)
    }
    @Test fun snoopFasterShooterAndMover() { assertTrue(Characters.SNOOP.fireIntervalMult < 1f && Characters.SNOOP.speedMult > 1f) }
    @Test fun bassDamageScalesWithRound() { assertTrue(AbilityRules.bassDamage(10) > AbilityRules.bassDamage(1)) }
    @Test fun repairPlanksCountsActuallyAdded() {
        val b = Barricade(); b.damage(120); assertEquals(4, b.planks) // 180hp = 3 full planks + a partial one
        assertEquals(3, b.repairPlanks(5)); assertEquals(6, b.planks); assertEquals(0, b.repairAll())
    }
}

class AmmoTest {
    @Test fun firesThenAutoReloads() {
        val a = Ammo(Weapons.SCATTER)
        repeat(Weapons.SCATTER.magSize - 1) { assertFalse(a.fire()) }
        assertTrue(a.fire()); assertTrue(a.reloading); assertFalse(a.canFire)
        a.update(Weapons.SCATTER.reloadTime - 0.1f); assertTrue(a.reloading)
        a.update(0.2f); assertFalse(a.reloading); assertEquals(Weapons.SCATTER.magSize, a.rounds)
    }
    @Test fun swapRefills() { val a = Ammo(Weapons.RIFLE); a.fire(); a.swap(Weapons.NOVA); assertEquals(4, a.rounds); assertTrue(a.canFire) }
    @Test fun cannotFireWhileReloading() { val a = Ammo(Weapons.NOVA); repeat(4) { if (a.canFire) a.fire() }; assertFailsWith<IllegalStateException> { a.fire() } }
    @Test fun upgradeEnlargesMagAndSpeedsReload() {
        val u = Weapons.RIFLE.upgradedVersion(); assertTrue(u.magSize > Weapons.RIFLE.magSize && u.reloadTime < Weapons.RIFLE.reloadTime)
    }
}

class BarkTest {
    @Test fun lowHealthFiresOnceUntilRecovered() {
        val t = LowHealthTrigger()
        assertFalse(t.check(100f, 100f)); assertTrue(t.check(25f, 100f)); assertFalse(t.check(10f, 100f))
        assertFalse(t.check(40f, 100f)); assertFalse(t.check(20f, 100f))
        assertFalse(t.check(60f, 100f)); assertTrue(t.check(20f, 100f))
    }
    @Test fun deadDoesNotShoutDanger() { assertFalse(LowHealthTrigger().check(0f, 100f)) }
}
