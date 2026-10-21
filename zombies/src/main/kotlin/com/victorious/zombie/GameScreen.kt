package com.victorious.zombie

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.viewport.FitViewport
import com.victorious.zombie.logic.*
import com.victorious.zombie.net.ClientSession
import com.victorious.zombie.net.HostSession
import com.victorious.zombie.sim.Layout
import com.victorious.zombie.sim.PlayerInput
import com.victorious.zombie.sim.Snapshot
import com.victorious.zombie.sim.World

/** How this screen gets its game state. Solo and Host run the [World] locally; Client renders what the host sends. */
sealed class Mode {
    /** Offline single player. Never touches the network. */
    object Solo : Mode()
    /** Runs the world and serves peers attached to [session] (Wi-Fi, Bluetooth, ...). */
    class Host(val session: HostSession, val onClose: () -> Unit = {}) : Mode()
    class Client(val session: ClientSession) : Mode()
}

/** Playable game. All visuals are procedural placeholder shapes. */
class GameScreen(private val character: Character = Characters.DEFAULT, private val mode: Mode = Mode.Solo) : ScreenAdapter() {
    private val W = Layout.W; private val H = Layout.H
    private val cam = OrthographicCamera(); private val view = FitViewport(W, H, cam)
    private val shapes = ShapeRenderer(); private val batch = SpriteBatch(); private val font = BitmapFont()
    private val tmp = Vector3()
    private val playerColors = listOf(Color(0.1f, 0.95f, 1f, 1f), Color(1f, 0.6f, 0.2f, 1f), Color(0.5f, 1f, 0.4f, 1f), Color(1f, 0.4f, 0.9f, 1f))
    private val magenta = Color(1f, 0.15f, 0.85f, 1f)

    // Local simulation (Solo/Host only)
    private val host: HostSession? = when (mode) {
        is Mode.Solo -> HostSession.create(character)
        is Mode.Host -> mode.session
        is Mode.Client -> null
    }
    private val client: ClientSession? = (mode as? Mode.Client)?.session
    private val localId: Int get() = client?.playerId ?: HostSession.HOST_ID

    // Touch controls
    private val isTouch = Gdx.app.type == Application.ApplicationType.Android
    private val stickCenter = Vector2(150f, 150f); private val stick = Vector2()
    private val useBtn = Vector2(W - 130f, 130f); private val abilityBtn = Vector2(W - 280f, 110f)
    private var useTouched = false; private var abilityTouched = false

    private var snap: Snapshot? = null
    private var done = false // GL resources are disposed after leaving; never draw after that
    private val shown = HashMap<Int, Vector2>() // smoothed positions for network clients

    override fun resize(width: Int, height: Int) { view.update(width, height, true) }

    override fun render(delta: Float) {
        if (done) return
        val dt = minOf(delta, 1 / 30f)
        val input = readInput()
        host?.world?.setInput(HostSession.HOST_ID, input)
        host?.tick(dt)
        client?.tick(dt, input)
        snap = host?.world?.snapshot(HostSession.HOST_ID) ?: client?.latest

        val s = snap
        val restartPressed = Gdx.input.justTouched() || Gdx.input.isKeyJustPressed(Input.Keys.ENTER)
        if (restartPressed) when {
            client != null && (!client.connected || client.rejected) -> backToMenu()
            s != null && s.gameOver && host != null -> host.world.reset()
        }
        if (!done) draw(s, dt)
    }

    private fun backToMenu() {
        if (done) return
        done = true
        (Gdx.app.applicationListener as? VictorOpsGame)?.setScreen(CharacterSelectScreen()); dispose()
    }

    private fun readInput(): PlayerInput {
        var dx = 0f; var dy = 0f
        if (Gdx.input.isKeyPressed(Input.Keys.W)) dy += 1
        if (Gdx.input.isKeyPressed(Input.Keys.S)) dy -= 1
        if (Gdx.input.isKeyPressed(Input.Keys.A)) dx -= 1
        if (Gdx.input.isKeyPressed(Input.Keys.D)) dx += 1
        useTouched = false; abilityTouched = false; stick.setZero()
        var stickActive = false
        for (p in 0 until 5) {
            if (!Gdx.input.isTouched(p)) continue
            view.unproject(tmp.set(Gdx.input.getX(p).toFloat(), Gdx.input.getY(p).toFloat(), 0f))
            if (tmp.x < W / 2) { stick.set(tmp.x - stickCenter.x, tmp.y - stickCenter.y).limit(90f); stickActive = true }
            else if (Vector2.dst(tmp.x, tmp.y, useBtn.x, useBtn.y) < 90f) useTouched = true
            else if (Vector2.dst(tmp.x, tmp.y, abilityBtn.x, abilityBtn.y) < 70f) abilityTouched = true
        }
        if (stickActive) { dx = stick.x / 90f; dy = stick.y / 90f }
        return PlayerInput(dx, dy, Gdx.input.isKeyPressed(Input.Keys.E) || useTouched, Gdx.input.isKeyPressed(Input.Keys.Q) || abilityTouched)
    }

    /** Clients ease toward the last known position so 15 Hz updates look smooth; the host draws exact positions. */
    private fun pos(key: Int, x: Float, y: Float, dt: Float): Vector2 {
        if (client == null) return tmp2.set(x, y)
        if (shown.size > 600) shown.clear()
        val v = shown.getOrPut(key) { Vector2(x, y) }
        val k = 1f - Math.exp((-18f * dt).toDouble()).toFloat()
        v.x += (x - v.x) * k; v.y += (y - v.y) * k
        return v
    }
    private val tmp2 = Vector2()

    private fun draw(s: Snapshot?, dt: Float) {
        Gdx.gl.glClearColor(0.03f, 0.02f, 0.06f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        view.apply(); shapes.projectionMatrix = cam.combined; batch.projectionMatrix = cam.combined
        if (s == null) {
            batch.begin(); font.color = Color.WHITE
            font.draw(batch, if (client != null && (!client.connected || client.rejected)) "Could not join (host full or gone). Tap to go back." else "Connecting...", W / 2 - 150, H / 2)
            batch.end(); return
        }
        val me = s.players.firstOrNull { it.id == localId }
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        shapes.color = Color(0.08f, 0.07f, 0.12f, 1f); shapes.rect(Layout.MIN_X, Layout.MIN_Y, Layout.MAX_X - Layout.MIN_X, Layout.MAX_Y - Layout.MIN_Y)
        Layout.windows.forEachIndexed { i, w ->
            val hp = s.barricadeHp.getOrElse(i) { 0 }; val planks = (hp + 49) / 50
            shapes.color = Color.DARK_GRAY; shapes.rect(w.inside.x - 40, w.inside.y - 10, 80f, 20f)
            shapes.color = Color(0.6f, 0.4f, 0.2f, 1f)
            for (k in 0 until planks) shapes.rect(w.inside.x - 40 + k * 13.5f, w.inside.y - 10, 11f, 20f)
        }
        shapes.color = Color.GOLD; shapes.rect(Layout.box.x - 30, Layout.box.y - 18, 60f, 36f)
        shapes.color = Color.LIME; shapes.rect(Layout.perk.x - 18, Layout.perk.y - 30, 36f, 60f)
        shapes.color = magenta; shapes.rect(Layout.forge.x - 30, Layout.forge.y - 30, 60f, 60f)
        shapes.color = Color(0.4f, 0.55f, 0.85f, 1f)
        Layout.armorStations.forEach { (p, _) -> shapes.rect(p.x - 24, p.y - 28, 48f, 56f) }
        s.pickups.forEach { shapes.color = Color(0.4f, 0.7f, 1f, 0.8f); shapes.rect(it.x - 8, it.y - 8, 16f, 16f) }
        shapes.color = Color(0.35f, 0.75f, 0.3f, 1f); s.zombies.forEach { val p = pos(it.id + 10_000, it.x, it.y, dt); shapes.circle(p.x, p.y, 16f) }
        shapes.color = Color.YELLOW; s.bullets.forEach { shapes.circle(it.x, it.y, 3f) }
        for (p in s.players) {
            val d = pos(p.id, p.x, p.y, dt)
            shapes.color = if (p.downed) Color.GRAY else playerColors[p.id % playerColors.size]
            shapes.circle(d.x, d.y, 15f)
            if (p.id == localId) { shapes.color = Color(1f, 1f, 1f, 0.6f); shapes.circle(d.x, d.y, 5f) }
            if (p.downed) { shapes.color = Color(0.3f, 1f, 0.4f, 1f); shapes.rect(d.x - 20, d.y + 22, 40f * p.reviveProgress, 5f) }
        }
        if (me != null) {
            shapes.color = Color.RED; shapes.rect(20f, H - 30f, 200f * (me.health / me.maxHealth).coerceAtLeast(0f), 12f)
            shapes.color = Color(0.4f, 0.7f, 1f, 1f); shapes.rect(20f, H - 46f, 200f * (me.armor / 200f).coerceIn(0f, 1f), 8f)
        }
        if (isTouch) {
            shapes.color = Color(1f, 1f, 1f, 0.15f); shapes.circle(stickCenter.x, stickCenter.y, 90f)
            shapes.color = Color(0.1f, 0.95f, 1f, 0.5f); shapes.circle(stickCenter.x + stick.x, stickCenter.y + stick.y, 35f)
            shapes.color = Color(1f, 0.15f, 0.85f, 0.4f); shapes.circle(useBtn.x, useBtn.y, 60f)
            shapes.color = Color(1f, 0.8f, 0.1f, if (me != null && me.abilityCd <= 0f) 0.5f else 0.15f); shapes.circle(abilityBtn.x, abilityBtn.y, 50f)
        }
        shapes.end()

        batch.begin()
        font.color = Color.WHITE
        val ch = me?.let { Characters.byId(it.characterId) } ?: character
        if (me != null) font.draw(batch, "ROUND ${s.round}   POINTS ${me.points}   ${me.weapon.displayName} " + (if (me.reloading) "RELOADING" else "${me.rounds}/${me.weapon.magSize}") + "   [${ch.name}]" + if (me.perkOwned) "   [Iron Hide Tonic]" else "", 240f, H - 18f)
        Layout.armorStations.forEach { (p, pack) -> font.draw(batch, pack.id.uppercase(), p.x - 20, p.y + 46) }
        font.draw(batch, "CRATE", Layout.box.x - 20, Layout.box.y + 35); font.draw(batch, "PERK", Layout.perk.x - 16, Layout.perk.y + 48); font.draw(batch, "FORGE", Layout.forge.x - 20, Layout.forge.y + 48)
        for (p in s.players) {
            val d = pos(p.id, p.x, p.y, dt)
            if (s.players.size > 1) { font.color = Color.LIGHT_GRAY; font.draw(batch, Characters.byId(p.characterId).name.substringBefore(' '), d.x - 24, d.y - 20) }
            if (p.bark.isNotEmpty()) { font.color = Color.YELLOW; font.draw(batch, p.bark, d.x - 60, d.y + 50) }
        }
        font.color = Color.WHITE
        if (s.players.size > 1) {
            var y = H - 18f
            for (p in s.players) { font.draw(batch, "${Characters.byId(p.characterId).name.substringBefore(' ')}  ${if (p.downed) "DOWN" else "HP ${p.health.toInt()}"}  ${p.points}", W - 230, y); y -= 18f }
        }
        if (s.prompt.isNotEmpty()) font.draw(batch, s.prompt + if (isTouch) "  (tap USE)" else "  (press E)", W / 2 - 120, 110f)
        if (me != null && me.downed && !s.gameOver) { font.color = Color.RED; font.draw(batch, "YOU ARE DOWN - a teammate can revive you", W / 2 - 150, 150f); font.color = Color.WHITE }
        val big = s.banner.ifEmpty { s.message }
        if (big.isNotEmpty()) { font.data.setScale(2f); font.draw(batch, big, W / 2 - 140, H / 2 + 120); font.data.setScale(1f) }
        if (s.banner.isNotEmpty() && s.message.isNotEmpty()) font.draw(batch, s.message, W / 2 - 100, H / 2 + 80)
        if (s.gameOver) font.draw(batch, if (host != null) "Tap / Enter to restart" else "Waiting for host to restart", W / 2 - 80, H / 2 + 70)
        if (client != null && !client.connected) { font.color = Color.RED; font.draw(batch, "Disconnected from host. Tap to go back.", W / 2 - 130, H / 2 - 40) }
        if (isTouch) { font.draw(batch, "USE", useBtn.x - 14, useBtn.y + 6); font.draw(batch, if (me == null || me.abilityCd <= 0f) "SKILL" else me.abilityCd.toInt().toString(), abilityBtn.x - 16, abilityBtn.y + 6) }
        else if (me != null) font.draw(batch, "${ch.abilityName} [Q]: " + if (me.abilityCd <= 0f) "READY" else "${me.abilityCd.toInt()}s", 20f, H - 60f)
        batch.end()
    }

    override fun dispose() {
        shapes.dispose(); batch.dispose(); font.dispose()
        host?.close(); client?.close(); (mode as? Mode.Host)?.onClose?.invoke()
    }
}
