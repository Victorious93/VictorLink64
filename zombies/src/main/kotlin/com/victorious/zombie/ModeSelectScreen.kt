package com.victorious.zombie

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.utils.viewport.FitViewport
import com.victorious.zombie.logic.Character
import com.victorious.zombie.net.ClientSession
import com.victorious.zombie.net.HostSession
import com.victorious.zombie.net.TcpHost
import com.victorious.zombie.net.TcpLink
import com.victorious.zombie.sim.Layout

/** Solo (offline) / host on Wi-Fi / join on Wi-Fi. Solo is first and needs no network at all. */
class ModeSelectScreen(private val character: Character) : ScreenAdapter() {
    private val W = Layout.W; private val H = Layout.H
    private val cam = OrthographicCamera(); private val view = FitViewport(W, H, cam)
    private val shapes = ShapeRenderer(); private val batch = SpriteBatch(); private val font = BitmapFont()
    private val tmp = Vector3()
    @Volatile private var status = ""
    private var done = false // GL resources are disposed once we switch screens; never draw after that
    private val labels = listOf("1  SOLO  (offline)", "2  HOST  (Wi-Fi, up to 4 players)", "3  JOIN  (Wi-Fi, enter host address)")
    private fun btn(i: Int) = floatArrayOf(340f, 440f - i * 120f, 600f, 90f) // x, y, w, h

    override fun resize(width: Int, height: Int) { view.update(width, height, true) }

    override fun render(delta: Float) {
        if (done) return
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_1)) solo()
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_2)) hostGame()
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_3)) joinGame()
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE) || Gdx.input.isKeyJustPressed(Input.Keys.BACKSPACE)) back()
        if (Gdx.input.justTouched()) {
            view.unproject(tmp.set(Gdx.input.x.toFloat(), Gdx.input.y.toFloat(), 0f))
            for (i in labels.indices) { val b = btn(i); if (tmp.x in b[0]..b[0] + b[2] && tmp.y in b[1]..b[1] + b[3]) when (i) { 0 -> solo(); 1 -> hostGame(); else -> joinGame() } }
        }
        if (done) return
        Gdx.gl.glClearColor(0.03f, 0.02f, 0.06f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        view.apply(); shapes.projectionMatrix = cam.combined; batch.projectionMatrix = cam.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        labels.indices.forEach { i -> val b = btn(i); shapes.color = Color(0.14f, 0.12f, 0.22f, 1f); shapes.rect(b[0], b[1], b[2], b[3]) }
        shapes.end()
        batch.begin(); font.color = Color.WHITE
        font.data.setScale(2.2f); font.draw(batch, "${character.name} - how do you want to play?", 220f, H - 60f)
        font.data.setScale(1.5f); labels.forEachIndexed { i, t -> val b = btn(i); font.draw(batch, t, b[0] + 24, b[1] + 58) }
        font.data.setScale(1f)
        font.draw(batch, "Solo needs no internet or Wi-Fi. Hosting and joining need both devices on the same network (no internet required).", 120f, 110f)
        if (status.isNotEmpty()) { font.color = Color.YELLOW; font.draw(batch, status, 120f, 80f) }
        font.color = Color.LIGHT_GRAY; font.draw(batch, "Esc / Backspace: change character", 120f, 50f)
        batch.end()
    }

    private fun go(mode: Mode) { if (done) return; done = true; (Gdx.app.applicationListener as? VictorOpsGame)?.setScreen(GameScreen(character, mode)); dispose() }
    private fun back() { if (done) return; done = true; (Gdx.app.applicationListener as? VictorOpsGame)?.setScreen(CharacterSelectScreen()); dispose() }
    private fun solo() = go(Mode.Solo)

    private fun hostGame() {
        try {
            val session = HostSession.create(character)
            val server = TcpHost(TcpHost.DEFAULT_PORT) { session.attach(it) }
            val addr = TcpHost.localAddress()
            Gdx.app.log("zombies", "Hosting on ${addr ?: "this device"}:${server.port}")
            go(Mode.Host(session) { server.close() })
        } catch (e: Exception) { status = "Could not host: ${e.message}" }
    }

    private fun joinGame() {
        Gdx.input.getTextInput(object : Input.TextInputListener {
            override fun input(text: String) {
                val host = text.trim().substringBefore(':'); val port = text.trim().substringAfter(':', "").toIntOrNull() ?: TcpHost.DEFAULT_PORT
                if (host.isEmpty()) return
                status = "Connecting to $host..."
                Thread {
                    try {
                        val link = TcpLink.connect(host, port)
                        Gdx.app.postRunnable { go(Mode.Client(ClientSession(link, character.id))) }
                    } catch (e: Exception) { status = "Could not connect: ${e.message}" }
                }.start()
            }
            override fun canceled() {}
        }, "Host address", "", "e.g. 192.168.1.20")
    }

    override fun dispose() { shapes.dispose(); batch.dispose(); font.dispose() }
}
