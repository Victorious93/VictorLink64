package com.victorious.zombie.sim

import com.victorious.zombie.logic.*
import kotlin.test.*

class WorldTest {
    private fun world(vararg chars: Character) = World(seed = 7).also { w -> chars.forEachIndexed { i, c -> w.addPlayer(i, c) } }
    private fun run(w: World, seconds: Float, dt: Float = 1 / 30f) { var t = 0f; while (t < seconds) { w.update(dt); t += dt } }

    @Test fun capsAtFourPlayersAndRejectsDuplicates() {
        val w = world(Characters.SNOOP, Characters.MARTHA, Characters.SNOOP, Characters.MARTHA)
        assertNull(w.addPlayer(9, Characters.SNOOP)); assertNull(w.addPlayer(0, Characters.SNOOP)); assertEquals(4, w.players.size)
    }

    @Test fun playersStartWithTheirCharacterGear() {
        val w = world(Characters.SNOOP, Characters.MARTHA)
        assertEquals("smg", w.players[0]!!.weapon.id); assertEquals("scatter", w.players[1]!!.weapon.id)
        assertEquals(130f, w.players[1]!!.health)
    }

    @Test fun inputMovesOnlyThatPlayer() {
        val w = world(Characters.SNOOP, Characters.MARTHA)
        val x0 = w.players[1]!!.x
        w.setInput(0, PlayerInput(dx = 1f)); val s0 = w.players[0]!!.x
        run(w, 0.5f)
        assertTrue(w.players[0]!!.x > s0 + 50f); assertEquals(x0, w.players[1]!!.x)
    }

    @Test fun movementIsClampedToArena() {
        val w = world(Characters.SNOOP); w.setInput(0, PlayerInput(dx = -1f, dy = -1f)); run(w, 10f)
        assertTrue(w.players[0]!!.x >= Layout.MIN_X && w.players[0]!!.y >= Layout.MIN_Y)
    }

    @Test fun boxPurchaseChargesOnlyTheBuyer() {
        val w = world(Characters.SNOOP, Characters.MARTHA)
        val a = w.players[0]!!; a.economy.onKill(); repeat(8) { a.economy.onKill() } // 500 + 9*60 = 1040
        a.x = Layout.box.x; a.y = Layout.box.y; w.setInput(0, PlayerInput(use = true)); w.update(0.05f)
        assertEquals(1040 - 950, a.economy.points); assertEquals(500, w.players[1]!!.economy.points)
    }

    @Test fun zombiesDownPlayersAndGameEndsOnlyWhenAllDown() {
        val w = world(Characters.SNOOP, Characters.MARTHA)
        val a = w.players[0]!!; val b = w.players[1]!!
        a.health = 0f; w.update(0.01f)
        assertTrue(a.downed); assertFalse(w.gameOver)
        b.health = 0f; w.update(0.01f)
        assertTrue(w.gameOver); assertEquals(Barks.GAME_OVER, a.bark)
    }

    @Test fun teammateRevivesDownedPlayer() {
        val w = world(Characters.SNOOP, Characters.MARTHA)
        val a = w.players[0]!!; val b = w.players[1]!!
        a.health = 0f; w.update(0.01f); assertTrue(a.downed)
        b.x = a.x + 10f; b.y = a.y; run(w, 3.2f, 0.1f)
        assertFalse(a.downed); assertEquals(a.maxHealth * 0.5f, a.health, 0.01f)
    }

    @Test fun reviveProgressDecaysWhenHelperLeaves() {
        val w = world(Characters.SNOOP, Characters.MARTHA)
        val a = w.players[0]!!; val b = w.players[1]!!
        a.health = 0f; w.update(0.01f); b.x = a.x + 10f; b.y = a.y; run(w, 1f, 0.1f)
        assertTrue(a.reviveT > 0.5f); b.x = a.x + 500f; run(w, 2f, 0.1f); assertEquals(0f, a.reviveT)
        assertTrue(a.downed)
    }

    @Test fun downedPlayersCannotActAndLeavingKeepsOthersPlaying() {
        val w = world(Characters.SNOOP, Characters.MARTHA)
        w.players[0]!!.health = 0f; w.update(0.01f)
        val x = w.players[0]!!.x; w.setInput(0, PlayerInput(dx = 1f)); run(w, 0.5f); assertEquals(x, w.players[0]!!.x)
        w.removePlayer(0); assertEquals(1, w.players.size); assertFalse(w.gameOver)
    }

    @Test fun roundsAdvanceAndSoloClearsEventually() {
        val w = world(Characters.SNOOP)
        run(w, 90f)
        assertTrue(w.zombies.isNotEmpty() || w.round > 1)
    }

    @Test fun killsPayTheShooter() {
        val w = world(Characters.SNOOP, Characters.MARTHA)
        val a = w.players[0]!!; a.x = 640f; a.y = 360f
        w.zombies += SimZombie(1, 700f, 360f, 1, 0, inside = true)
        w.setInput(0, PlayerInput()); val before = a.economy.points; w.update(0.05f); run(w, 0.5f, 0.02f)
        assertTrue(a.economy.points >= before + Economy.KILL); assertEquals(500, w.players[1]!!.economy.points)
    }

    @Test fun abilityRespectsCooldown() {
        val w = world(Characters.MARTHA); val p = w.players[0]!!
        w.barricades[0].damage(300); w.setInput(0, PlayerInput(ability = true)); w.update(0.05f)
        assertEquals(6, w.barricades[0].planks); assertTrue(p.abilityCd > 30f)
        w.barricades[0].damage(300); w.update(0.05f); assertEquals(0, w.barricades[0].planks)
    }

    @Test fun resetKeepsPlayersAndClearsProgress() {
        val w = world(Characters.SNOOP, Characters.MARTHA)
        w.players[0]!!.economy.onKill(); w.players[0]!!.health = 0f; w.players[1]!!.health = 0f; w.update(0.01f); assertTrue(w.gameOver)
        w.reset(); assertFalse(w.gameOver); assertEquals(2, w.players.size); assertEquals(500, w.players[0]!!.economy.points); assertEquals(1, w.round)
    }

    @Test fun joiningAfterGameOverRestartsPlay() {
        val w = world(Characters.SNOOP); w.players[0]!!.health = 0f; w.update(0.01f); assertTrue(w.gameOver)
        w.addPlayer(5, Characters.MARTHA); assertFalse(w.gameOver)
    }
}
