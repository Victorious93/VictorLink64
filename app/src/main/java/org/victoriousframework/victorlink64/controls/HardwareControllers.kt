package org.victoriousframework.victorlink64.controls

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Tells the activity whether a physical gamepad is attached, so the touch overlay can step aside. Victorious Framework.
 * Call [start] in onResume and [stop] in onPause.
 */
class HardwareControllers(context: Context, private val onChange: (connected: Boolean) -> Unit) : InputManager.InputDeviceListener {
    private val manager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    var connected = false; private set

    fun start() {
        manager.registerInputDeviceListener(this, null)
        refresh()
    }

    fun stop() = manager.unregisterInputDeviceListener(this)

    private fun refresh() {
        val now = InputDevice.getDeviceIds().any { isGamepad(InputDevice.getDevice(it)) }
        if (now != connected) { connected = now; onChange(now) }
    }

    override fun onInputDeviceAdded(deviceId: Int) = refresh()
    override fun onInputDeviceRemoved(deviceId: Int) = refresh()
    override fun onInputDeviceChanged(deviceId: Int) = refresh()

    companion object {
        /** A real, attached game controller: gamepad or joystick sources, not the phone's own keys or a virtual device. */
        fun isGamepad(d: InputDevice?): Boolean {
            if (d == null || d.isVirtual) return false
            val s = d.sources
            return (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        }

        fun isGamepadEvent(source: Int) =
            (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
    }
}

/**
 * Hardware gamepad input for one console: key presses go through the user's [KeyRemap], sticks, triggers and the hat
 * D-pad become a [PadState]. Merge it with the touch state before sending to the core.
 */
class HardwareInput(private var remap: KeyRemap, private val stickAsDpad: Boolean = false) {
    private var keys = 0
    private var axes = AxisReading()

    fun setRemap(r: KeyRemap) { remap = r; keys = 0 }

    /** Returns true if the key was a mapped gamepad button (consumed). */
    fun onKey(e: KeyEvent): Boolean {
        if (!HardwareControllers.isGamepadEvent(e.source)) return false
        val bit = remap.bitFor(e.keyCode) ?: return false
        keys = if (e.action == KeyEvent.ACTION_UP) keys and (1 shl bit).inv() else keys or (1 shl bit)
        return true
    }

    /** Returns true if the event carried gamepad axes (consumed). */
    fun onMotion(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || e.action != MotionEvent.ACTION_MOVE) return false
        val d = e.device
        fun has(axis: Int) = d?.getMotionRange(axis, e.source) != null
        val hasRx = has(MotionEvent.AXIS_RX) && has(MotionEvent.AXIS_RY)
        val rxAxis = if (has(MotionEvent.AXIS_Z) && has(MotionEvent.AXIS_RZ)) MotionEvent.AXIS_Z else if (hasRx) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z
        val ryAxis = if (rxAxis == MotionEvent.AXIS_Z) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY
        val lTrig = if (has(MotionEvent.AXIS_LTRIGGER)) MotionEvent.AXIS_LTRIGGER else MotionEvent.AXIS_BRAKE
        val rTrig = if (has(MotionEvent.AXIS_RTRIGGER)) MotionEvent.AXIS_RTRIGGER else MotionEvent.AXIS_GAS
        axes = AxisReading(
            lx = e.getAxisValue(MotionEvent.AXIS_X), ly = e.getAxisValue(MotionEvent.AXIS_Y),
            rx = e.getAxisValue(rxAxis), ry = e.getAxisValue(ryAxis),
            lt = e.getAxisValue(lTrig), rt = e.getAxisValue(rTrig),
            hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X), hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y),
        )
        return true
    }

    fun state(): PadState = PadState(keys).merge(HardwarePad.fromAxes(axes, stickAsDpad = stickAsDpad))

    fun release() { keys = 0; axes = AxisReading() }
}
