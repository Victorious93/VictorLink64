package org.victoriousframework.victorlink64.controls

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import paulscode.android.mupen64plusae.ActivityHelper
import org.victoriousframework.victorlink64.ui.PadChip
import org.victoriousframework.victorlink64.ui.PadCyan
import org.victoriousframework.victorlink64.ui.PadNavy
import org.victoriousframework.victorlink64.ui.PadSteel
import org.victoriousframework.victorlink64.ui.VictorTheme

/**
 * Hardware controller button remapping for SNES, PSP and GameCube. Victorious Framework.
 *
 * Pick a console, tap a button, press the controller button to bind it. Pressing controller buttons while nothing is
 * being bound lights up the matching row, so the screen doubles as a controller test. N64 keeps using Mupen64Plus's
 * own controller profiles (opened from here).
 */
class ControllerMapActivity : ComponentActivity() {
    private val store by lazy { ControlsPrefs.remaps(this) }
    private var console by mutableStateOf(Console.SNES)
    private var remap by mutableStateOf<KeyRemap?>(null)
    private var listening by mutableStateOf<Int?>(null)
    private var lastBit by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remap = store.load(console)
        setContent { VictorTheme { Screen() } }
    }

    private fun select(c: Console) {
        console = c; listening = null; lastBit = null
        remap = store.load(c)
    }

    private fun update(r: KeyRemap) { remap = r; store.save(r) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || !HardwareControllers.isGamepadEvent(event.source)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val r = remap
            val l = listening
            if (l != null && r != null) { update(r.assign(l, event.keyCode)); listening = null; lastBit = l }
            else lastBit = r?.bitFor(event.keyCode)
        }
        return true
    }

    @Composable
    private fun Screen() {
        Column(
            Modifier.fillMaxSize().background(PadNavy).windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Controller buttons", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "Connect a controller, tap a button below, then press the controller button you want for it. " +
                    "Pressing controller buttons at other times lights up the matching row, so you can test your pad.",
                color = PadSteel, fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in Console.entries) PadChip(if (c == console) "\u25CF ${c.shortLabel}" else c.shortLabel) { select(c) }
            }
            if (console == Console.N64) N64Card() else {
                val r = remap
                if (r != null) for (t in RemapTargets.forConsole(console)) RemapRow(t, r)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PadChip("RESET TO DEFAULTS") { store.reset(console); remap = store.load(console); listening = null }
                    PadChip("DONE") { finish() }
                }
            }
        }
    }

    @Composable
    private fun RemapRow(t: RemapTarget, r: KeyRemap) {
        val shape = RoundedCornerShape(12.dp)
        val active = listening == t.bit
        val lit = lastBit == t.bit && !active
        val key = r.keyFor(t.bit)
        Row(
            Modifier.fillMaxWidth().clip(shape)
                .background(if (lit) PadCyan.copy(alpha = 0.35f) else Color(0xFF13204A))
                .border(if (active) 2.dp else 1.dp, if (active || lit) PadCyan else PadCyan.copy(alpha = 0.25f), shape)
                .clickable { listening = if (active) null else t.bit }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t.label, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                if (active) "Press a controller button\u2026" else key?.let(::keyName) ?: "Not set",
                color = if (active) PadCyan else PadSteel, fontSize = 13.sp,
            )
            if (key != null && !active) {
                Spacer(Modifier.padding(start = 10.dp))
                PadChip("CLEAR") { update(r.clear(t.bit)) }
            }
        }
    }

    @Composable
    private fun N64Card() {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF13204A)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Nintendo 64 uses controller profiles", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(
                "N64 games run on Mupen64Plus, which maps controllers through profiles (buttons, stick range and dead zone, " +
                    "per player). Open the profile manager to change those.",
                color = PadSteel, fontSize = 13.sp,
            )
            PadChip("OPEN N64 CONTROLLER PROFILES") { ActivityHelper.startManageControllerProfilesActivity(this@ControllerMapActivity) }
        }
    }

    private fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ')
}
