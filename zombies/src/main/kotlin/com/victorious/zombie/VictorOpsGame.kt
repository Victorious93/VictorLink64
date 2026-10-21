package com.victorious.zombie

import com.badlogic.gdx.Game
import com.victorious.zombie.logic.Character
import com.victorious.zombie.net.ClientSession
import com.victorious.zombie.net.HostSession
import com.victorious.zombie.net.NetLink

/**
 * How the game is started. [Menu] is the stand-alone flow (character -> Solo / Wi-Fi host / Wi-Fi join).
 * The other entries are for a host app that already owns the connection (VictorLink64's Connect tab).
 */
sealed class Entry {
    object Menu : Entry()
    /** Offline single player straight after picking a character. */
    object Solo : Entry()
    /** Run the world; [onSession] is called (on the GL thread) with the new session so the app can attach peer links. */
    class Host(val onSession: (HostSession) -> Unit, val onClose: () -> Unit = {}) : Entry()
    /** Join a host over an already-established link. */
    class Client(val link: NetLink) : Entry()
}

class VictorOpsGame(private val entry: Entry = Entry.Menu) : Game() {
    override fun create() { setScreen(CharacterSelectScreen()) }

    /** Called by [CharacterSelectScreen] once a survivor is picked. */
    internal fun onCharacterChosen(character: Character) {
        setScreen(when (val e = entry) {
            Entry.Menu -> ModeSelectScreen(character)
            Entry.Solo -> GameScreen(character, Mode.Solo)
            is Entry.Host -> GameScreen(character, Mode.Host(HostSession.create(character).also(e.onSession), e.onClose))
            is Entry.Client -> GameScreen(character, Mode.Client(ClientSession(e.link, character.id)))
        })
    }
}
