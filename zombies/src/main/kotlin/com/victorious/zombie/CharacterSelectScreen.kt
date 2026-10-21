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
import com.victorious.zombie.logic.Characters

/** Pick a character: tap a card, or press 1/2 (or A/D then Enter) on desktop. */
class CharacterSelectScreen : ScreenAdapter() {
    private val W = 1280f; private val H = 720f
    private val cam = OrthographicCamera(); private val view = FitViewport(W, H, cam)
    private val shapes = ShapeRenderer(); private val batch = SpriteBatch(); private val font = BitmapFont()
    private val tmp = Vector3()
    private var selected = 0
    /** Set once we hand over to another screen: its GL resources are disposed, so this frame must not draw. */
    private var done = false
    private val colors = listOf(Color(0.1f, 0.95f, 1f, 1f), Color(1f, 0.6f, 0.2f, 1f))

    private fun cardX(i: Int) = 140f + i * 520f
    private val cardW = 480f; private val cardY = 160f; private val cardH = 400f

    override fun resize(width: Int, height: Int) { view.update(width, height, true) }

    override fun render(delta: Float) {
        if (done) return
        val n = Characters.ALL.size
        if (Gdx.input.isKeyJustPressed(Input.Keys.A) || Gdx.input.isKeyJustPressed(Input.Keys.LEFT)) selected = (selected + n - 1) % n
        if (Gdx.input.isKeyJustPressed(Input.Keys.D) || Gdx.input.isKeyJustPressed(Input.Keys.RIGHT)) selected = (selected + 1) % n
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_1)) { selected = 0; start() }
        if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_2) && n > 1) { selected = 1; start() }
        if (Gdx.input.isKeyJustPressed(Input.Keys.ENTER)) start()
        if (Gdx.input.justTouched()) {
            view.unproject(tmp.set(Gdx.input.x.toFloat(), Gdx.input.y.toFloat(), 0f))
            for (i in 0 until n) if (tmp.x in cardX(i)..cardX(i) + cardW && tmp.y in cardY..cardY + cardH) { selected = i; start() }
        }
        if (!done) draw()
    }

    private fun start() {
        if (done) return
        done = true
        val game = Gdx.app.applicationListener as? VictorOpsGame ?: return
        game.onCharacterChosen(Characters.ALL[selected]); dispose()
    }

    private fun draw() {
        Gdx.gl.glClearColor(0.03f, 0.02f, 0.06f, 1f); Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        view.apply(); shapes.projectionMatrix = cam.combined; batch.projectionMatrix = cam.combined
        shapes.begin(ShapeRenderer.ShapeType.Filled)
        Characters.ALL.forEachIndexed { i, _ ->
            shapes.color = if (i == selected) Color(0.18f, 0.16f, 0.26f, 1f) else Color(0.1f, 0.09f, 0.15f, 1f)
            shapes.rect(cardX(i), cardY, cardW, cardH)
            shapes.color = colors[i % colors.size]; shapes.circle(cardX(i) + cardW / 2, cardY + cardH - 110f, 60f)
        }
        shapes.end()
        batch.begin()
        font.color = Color.WHITE; font.data.setScale(2.5f)
        font.draw(batch, "CHOOSE YOUR SURVIVOR", W / 2 - 280, H - 50)
        font.data.setScale(1.6f)
        Characters.ALL.forEachIndexed { i, c ->
            val x = cardX(i) + 24
            font.draw(batch, "${i + 1}. ${c.name}", x, cardY + cardH - 200)
        }
        font.data.setScale(1.1f)
        Characters.ALL.forEachIndexed { i, c ->
            val x = cardX(i) + 24; var y = cardY + cardH - 240
            for (line in listOf(
                c.tagline, "HP ${c.maxHealth.toInt()}   Speed x${c.speedMult}   Start: ${c.startWeapon.name}",
                "Ability: ${c.abilityName} (${c.abilityCooldown.toInt()}s cooldown)",
                "Repairs ${c.repairPlanksPerUse} plank(s) per use",
            )) { font.draw(batch, line, x, y, cardW - 48, 1, true); y -= 46 }
        }
        font.data.setScale(1f)
        font.draw(batch, "Tap a card, or press 1 / 2", W / 2 - 90, 110f)
        batch.end()
    }

    override fun dispose() { shapes.dispose(); batch.dispose(); font.dispose() }
}
