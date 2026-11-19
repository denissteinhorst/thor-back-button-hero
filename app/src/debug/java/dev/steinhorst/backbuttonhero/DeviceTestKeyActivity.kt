package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * Debug builds only. Stands in for a game during the on-device test suite: it logs every key and
 * every D-pad or stick movement it receives, which is exactly what a game would get, so the suite
 * can prove that bound presses are withheld and everything else arrives untouched.
 */
class DeviceTestKeyActivity : Activity() {
    private val axes = HashMap<Int, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this))
        current = WeakReference(this)
        Log.i(TAG, "PROBE_READY")
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.repeatCount == 0) {
            val action = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
            Log.i(TAG, "PROBE_KEY $action ${KeyEvent.keyCodeToString(event.keyCode)}")
        }
        // Consumed, so Back or Home never leave this screen while the suite runs.
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            for (axis in AXES) {
                // -1, 0 or 1: only a real deflection or its release is worth a line.
                val value = event.getAxisValue(axis).let { if (abs(it) < 0.5f) 0 else if (it > 0) 1 else -1 }
                if (axes[axis] != value) {
                    axes[axis] = value
                    Log.i(TAG, "PROBE_AXIS ${MotionEvent.axisToString(axis)} $value")
                }
            }
        }
        return true
    }

    companion object {
        private const val TAG = "DeviceTest"
        private val AXES = intArrayOf(
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_X, MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
        )

        private var current: WeakReference<DeviceTestKeyActivity>? = null

        fun finishCurrent() = current?.get()?.finish()
    }
}
