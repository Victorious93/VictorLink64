package org.victoriousframework.victorlink64.controls

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import org.victoriousframework.victorlink64.ui.VictorTheme

/**
 * Drop-in on-screen controller for an activity: the touch overlay plus its layout editor. Victorious Framework.
 * Add it over the game surface (match_parent), listen with [onPadState], and call [startEditing] from a menu.
 */
@SuppressLint("ViewConstructor")
class OverlayHost @JvmOverloads constructor(
    context: Context,
    val console: Console,
    private val settings: OverlaySettings = ControlsPrefs.settings(context),
    layouts: LayoutStore = ControlsPrefs.layouts(context),
) : FrameLayout(context) {
    val overlay = TouchOverlayView(context, console, settings, layouts)

    private var selection by mutableStateOf<EditSelection?>(null)
    private var settingsVersion by mutableStateOf(0)
    private val panel = ComposeView(context)

    /** Fired when edit mode opens or closes, so the activity can pause the game or hide its own chrome. */
    var onEditingChanged: ((Boolean) -> Unit)? = null

    var onPadState: ((PadState) -> Unit)?
        get() = overlay.onPadState
        set(value) { overlay.onPadState = value }

    val editing get() = overlay.editing

    init {
        isFocusable = false
        addView(overlay, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.onSelection = { selection = it }
        panel.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        panel.setContent {
            VictorTheme {
                @Suppress("UNUSED_EXPRESSION") settingsVersion
                OverlayEditPanel(
                    console = console,
                    settings = settings,
                    selection = selection,
                    onScale = overlay::setSelectedScale,
                    onOpacity = overlay::setSelectedOpacity,
                    onCommit = overlay::saveLayout,
                    onSettingsChanged = { overlay.rebuild(); settingsVersion++ },
                    onReset = overlay::resetLayout,
                    onDone = ::stopEditing,
                )
            }
        }
        panel.visibility = View.GONE
        addView(panel, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    fun startEditing() {
        if (overlay.editing) return
        overlay.setHidden(false, animate = false)
        overlay.setEditing(true)
        panel.visibility = View.VISIBLE
        onEditingChanged?.invoke(true)
    }

    fun stopEditing() {
        if (!overlay.editing) return
        overlay.setEditing(false)
        panel.visibility = View.GONE
        onEditingChanged?.invoke(false)
    }

    /** Hides the controls (a hardware controller is in use) or shows them again. Never hides while editing. */
    fun setHidden(hidden: Boolean) {
        if (!overlay.editing) overlay.setHidden(hidden)
    }

    val hidden get() = overlay.hidden

    fun release() = overlay.release()
}
