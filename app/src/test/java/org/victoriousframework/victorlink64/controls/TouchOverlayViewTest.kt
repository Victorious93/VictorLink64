package org.victoriousframework.victorlink64.controls

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Victorious Framework: the real overlay view driven with MotionEvents (Robolectric, no device): multi-touch output,
 * edit-mode drag/resize, and that edits are saved per console and orientation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TouchOverlayViewTest {
    private val w = 2400
    private val h = 1080
    private val area = Area(8f, 8f, w - 16f, h - 16f) // the view keeps an 8 dp margin; Robolectric density is 1

    private fun view(console: Console, kv: MemoryStore = MemoryStore()): TouchOverlayView {
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        return TouchOverlayView(act, console, OverlaySettings(kv), LayoutStore(kv)).also {
            it.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            it.layout(0, 0, w, h)
        }
    }

    private fun placed(console: Console, kv: MemoryStore = MemoryStore()) =
        LayoutGeometry.place(ConsoleLayouts.defs(console), LayoutStore(kv).load(console, true), area, 1f).associateBy { it.def.id }

    private fun single(v: View, action: Int, x: Float, y: Float) =
        v.dispatchTouchEvent(MotionEvent.obtain(0, SystemClock.uptimeMillis(), action, x, y, 0))

    private fun multi(v: View, action: Int, index: Int, pts: List<Triple<Int, Float, Float>>) {
        val props = pts.map { MotionEvent.PointerProperties().apply { id = it.first; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coords = pts.map { MotionEvent.PointerCoords().apply { x = it.second; y = it.third; pressure = 1f; size = 1f } }.toTypedArray()
        val a = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        v.dispatchTouchEvent(MotionEvent.obtain(0, SystemClock.uptimeMillis(), a, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0))
    }

    @Test fun fourFingersDriveStickDpadButtonAndTriggerTogether() {
        val v = view(Console.GAMECUBE)
        var last = PadState()
        v.onPadState = { last = it }
        val c = placed(Console.GAMECUBE)
        val stick = c.getValue("stick"); val dpad = c.getValue("dpad"); val a = c.getValue("a"); val lt = c.getValue("lt")
        val pts = listOf(
            Triple(0, stick.cx + stick.radius * 0.7f, stick.cy - stick.radius * 0.5f),
            Triple(1, dpad.cx + dpad.w * 0.3f, dpad.cy - dpad.h * 0.3f),
            Triple(2, a.cx, a.cy),
            Triple(3, lt.cx, lt.top + lt.h * 0.6f),
        )
        multi(v, MotionEvent.ACTION_DOWN, 0, pts.take(1))
        for (i in 1 until pts.size) multi(v, MotionEvent.ACTION_POINTER_DOWN, i, pts.take(i + 1))
        assertTrue(last.lx > 0.3f && last.ly < -0.2f)
        assertEquals((1 shl RetroPad.UP) or (1 shl RetroPad.RIGHT) or (1 shl RetroPad.A), last.mask)
        assertTrue(last.lt in 0.4f..0.9f)

        multi(v, MotionEvent.ACTION_POINTER_UP, 2, pts) // lift the A finger
        assertEquals((1 shl RetroPad.UP) or (1 shl RetroPad.RIGHT), last.mask)
        single(v, MotionEvent.ACTION_CANCEL, 0f, 0f)
        assertEquals(PadState.NEUTRAL, last)
    }

    @Test fun hiddenOverlayIgnoresTouchesAndShowsAgain() {
        val v = view(Console.SNES)
        var last = PadState()
        v.onPadState = { last = it }
        val a = placed(Console.SNES).getValue("a")
        v.setHidden(true, animate = false)
        assertFalse(single(v, MotionEvent.ACTION_DOWN, a.cx, a.cy))
        assertEquals(PadState.NEUTRAL, last)
        v.setHidden(false, animate = false)
        assertTrue(single(v, MotionEvent.ACTION_DOWN, a.cx, a.cy))
        assertEquals(1 shl RetroPad.A, last.mask)
    }

    @Test fun hidingWhileHeldReleasesTheButton() {
        val v = view(Console.SNES)
        var last = PadState()
        v.onPadState = { last = it }
        val a = placed(Console.SNES).getValue("a")
        single(v, MotionEvent.ACTION_DOWN, a.cx, a.cy)
        assertEquals(1 shl RetroPad.A, last.mask)
        v.setHidden(true, animate = false)
        assertEquals(PadState.NEUTRAL, last)
    }

    @Test fun editModeDragsResizesAndSavesPerOrientation() {
        val kv = MemoryStore()
        val store = LayoutStore(kv)
        val v = view(Console.SNES, kv)
        var sel: EditSelection? = null
        v.onSelection = { sel = it }
        var pressedWhileEditing = false
        v.onPadState = { if (it.mask != 0) pressedWhileEditing = true }
        v.setEditing(true)

        val before = store.load(Console.SNES, true).getValue("a")
        val a = placed(Console.SNES).getValue("a")
        single(v, MotionEvent.ACTION_DOWN, a.cx, a.cy)
        assertEquals("a", sel?.id)
        single(v, MotionEvent.ACTION_MOVE, a.cx - 200f, a.cy - 100f)
        single(v, MotionEvent.ACTION_UP, a.cx - 200f, a.cy - 100f)
        val moved = LayoutStore(kv).load(Console.SNES, true).getValue("a")
        assertNotEquals(before, moved)
        assertEquals(before.ox + 200f / area.edge, moved.ox, 0.002f)
        assertEquals(before.oy + 100f / area.edge, moved.oy, 0.002f)
        assertFalse("edit touches never reach the game", pressedWhileEditing)

        v.setSelectedScale(1.5f); v.setSelectedOpacity(0.4f); v.saveLayout()
        val tuned = LayoutStore(kv).load(Console.SNES, true).getValue("a")
        assertEquals(1.5f, tuned.scale, 0.01f); assertEquals(0.4f, tuned.opacity, 0.01f)
        assertEquals("portrait layout is separate", 1f, LayoutStore(kv).load(Console.SNES, false).getValue("a").scale, 0f)

        v.resetLayout()
        assertEquals(before, LayoutStore(kv).load(Console.SNES, true).getValue("a"))
    }

    @Test fun cornerHandleResizesTheSelectedControl() {
        val kv = MemoryStore()
        val v = view(Console.PSP, kv)
        v.setEditing(true)
        val c = placed(Console.PSP).getValue("cross")
        single(v, MotionEvent.ACTION_DOWN, c.cx, c.cy)
        single(v, MotionEvent.ACTION_UP, c.cx, c.cy)
        single(v, MotionEvent.ACTION_DOWN, c.right, c.bottom)
        single(v, MotionEvent.ACTION_MOVE, c.cx + (c.right - c.cx) * 1.8f, c.cy + (c.bottom - c.cy) * 1.8f)
        single(v, MotionEvent.ACTION_UP, c.right, c.bottom)
        assertEquals(1.8f, LayoutStore(kv).load(Console.PSP, true).getValue("cross").scale, 0.05f)
    }

    @Test fun leavingEditModeReleasesAndPlaysNormally() {
        val kv = MemoryStore()
        val v = view(Console.SNES, kv)
        var last = PadState()
        v.onPadState = { last = it }
        v.setEditing(true)
        v.setEditing(false)
        val b = placed(Console.SNES, kv).getValue("b")
        single(v, MotionEvent.ACTION_DOWN, b.cx, b.cy)
        assertEquals(1 shl RetroPad.B, last.mask)
    }
}
