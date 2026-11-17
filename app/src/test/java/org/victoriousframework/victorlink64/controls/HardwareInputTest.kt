package org.victoriousframework.victorlink64.controls

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Victorious Framework: hardware gamepad events become pad state through the user's remapping. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HardwareInputTest {
    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN, source: Int = InputDevice.SOURCE_GAMEPAD) =
        KeyEvent(0, SystemClock.uptimeMillis(), action, code, 0, 0, 1, 0, 0, source)

    private fun stick(x: Float, y: Float, z: Float = 0f, rz: Float = 0f, brake: Float = 0f, gas: Float = 0f, hatX: Float = 0f): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_X, x); setAxisValue(MotionEvent.AXIS_Y, y)
            setAxisValue(MotionEvent.AXIS_Z, z); setAxisValue(MotionEvent.AXIS_RZ, rz)
            setAxisValue(MotionEvent.AXIS_BRAKE, brake); setAxisValue(MotionEvent.AXIS_GAS, gas)
            setAxisValue(MotionEvent.AXIS_HAT_X, hatX)
        })
        return MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 1, props, coords, 0, 0, 1f, 1f, 1, 0, InputDevice.SOURCE_JOYSTICK, 0)
    }

    @Test fun mappedKeysPressAndReleaseThroughTheRemap() {
        val hw = HardwareInput(KeyRemap.defaults(Console.SNES))
        assertTrue(hw.onKey(key(KeyEvent.KEYCODE_BUTTON_A)))
        assertEquals(1 shl RetroPad.B, hw.state().mask)
        hw.onKey(key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP))
        assertEquals(0, hw.state().mask)

        hw.setRemap(KeyRemap.defaults(Console.SNES).assign(RetroPad.X, KeyEvent.KEYCODE_BUTTON_A))
        hw.onKey(key(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(1 shl RetroPad.X, hw.state().mask)
    }

    @Test fun unmappedKeysAndNonGamepadSourcesAreLeftAlone() {
        val hw = HardwareInput(KeyRemap.defaults(Console.SNES))
        assertFalse(hw.onKey(key(KeyEvent.KEYCODE_VOLUME_UP)))
        assertFalse(hw.onKey(key(KeyEvent.KEYCODE_BACK)))
        assertFalse(hw.onKey(key(KeyEvent.KEYCODE_BUTTON_A, source = InputDevice.SOURCE_KEYBOARD)))
        assertEquals(0, hw.state().mask)
    }

    @Test fun gameCubeSticksAndTriggersAreAnalog() {
        val hw = HardwareInput(KeyRemap.defaults(Console.GAMECUBE))
        assertTrue(hw.onMotion(stick(1f, 0f, z = 0f, rz = -1f, brake = 0.5f, gas = 1f)))
        val s = hw.state()
        assertEquals(1f, s.lx, 1e-3f)
        assertEquals(0f, s.rx, 1e-3f)
        assertEquals(-1f, s.ry, 1e-3f)
        assertEquals(1f, s.rt, 1e-3f)
        assertTrue(s.lt in 0.4f..0.6f)
    }

    @Test fun snesTurnsTheStickAndHatIntoDpadBits() {
        val hw = HardwareInput(KeyRemap.defaults(Console.SNES), stickAsDpad = true)
        hw.onMotion(stick(1f, 0f))
        assertEquals(1 shl RetroPad.RIGHT, hw.state().mask)
        assertEquals(0f, hw.state().lx, 0f)
        hw.onMotion(stick(0f, 0f, hatX = -1f))
        assertEquals(1 shl RetroPad.LEFT, hw.state().mask)
    }

    @Test fun releaseClearsEverythingHeld() {
        val hw = HardwareInput(KeyRemap.defaults(Console.GAMECUBE))
        hw.onKey(key(KeyEvent.KEYCODE_BUTTON_A)); hw.onMotion(stick(1f, 1f))
        hw.release()
        assertEquals(PadState.NEUTRAL, hw.state())
    }

    @Test fun touchAndHardwareMerge() {
        val hw = HardwareInput(KeyRemap.defaults(Console.GAMECUBE))
        hw.onKey(key(KeyEvent.KEYCODE_BUTTON_A))
        val merged = PadState(mask = 1 shl RetroPad.START, lx = -0.4f).merge(hw.state())
        assertEquals((1 shl RetroPad.START) or (1 shl RetroPad.A), merged.mask)
        assertEquals(-0.4f, merged.lx, 0f)
    }
}
