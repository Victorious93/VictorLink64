package org.victoriousframework.victorlink64.controls

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Everything an emulator activity needs for input, in one place: the touch overlay, the hardware gamepad (with the
 * user's button remapping), auto-hide while a controller is connected, and merging both sources into one [PadState]
 * delivered to [sink]. Victorious Framework.
 *
 * Wire it up: add [host] over the game surface, forward key and motion events to [handleKey]/[handleMotion], and call
 * [onResume]/[onPause] from the activity lifecycle.
 */
class PadSession(
    private val context: Context,
    val console: Console,
    stickAsDpad: Boolean,
    private val sink: (PadState) -> Unit,
) {
    val settings = ControlsPrefs.settings(context)
    val host = OverlayHost(context, console, settings)
    private val hardware = HardwareInput(ControlsPrefs.remaps(context).load(console), stickAsDpad)
    private var touch = PadState.NEUTRAL

    /** True while a physical gamepad is attached. */
    var hardwarePresent by mutableStateOf(false); private set

    /** The user asked to see the touch controls even though a gamepad is attached. */
    var forcedVisible by mutableStateOf(false); private set

    /** True while the layout editor is open. */
    var editing by mutableStateOf(false); private set

    private val controllers = HardwareControllers(context) { present ->
        hardwarePresent = present
        if (!present) forcedVisible = false
        applyVisibility()
    }

    init {
        host.onPadState = { touch = it; push() }
        host.onEditingChanged = { editing = it; if (!it) applyVisibility() }
    }

    private fun push() = sink(touch.merge(hardware.state()))

    fun handleKey(e: KeyEvent): Boolean = hardware.onKey(e).also { if (it) push() }

    fun handleMotion(e: MotionEvent): Boolean = hardware.onMotion(e).also { if (it) push() }

    fun toggleForcedVisible() { forcedVisible = !forcedVisible; applyVisibility() }

    fun startEditing() = host.startEditing()

    private fun applyVisibility() = host.setHidden(hardwarePresent && settings.hideWithController && !forcedVisible)

    fun onResume() {
        hardware.setRemap(ControlsPrefs.remaps(context).load(console))
        controllers.start()
    }

    /** Lifts every held button so nothing sticks while the game is in the background. */
    fun onPause() {
        controllers.stop()
        host.release()
        hardware.release()
        touch = PadState.NEUTRAL
        push()
    }
}
