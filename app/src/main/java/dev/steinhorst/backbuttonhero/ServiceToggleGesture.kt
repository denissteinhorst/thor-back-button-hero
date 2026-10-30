package dev.steinhorst.backbuttonhero

import android.view.KeyEvent

/** Observes toggle buttons without consuming them; mirrored devices share one hold timer. */
class ServiceToggleGesture(
    private val prefs: Prefs,
    private val scheduler: GestureScheduler,
    private val onToggle: () -> Unit,
) {
    private val keys = PhysicalKeyTracker()
    private var armed = false
    private var fired = false
    private var binding = prefs.toggleButtons
    private val hold = Runnable {
        armed = false
        fired = true
        if (prefs.toggleButtons == binding && binding != Prefs.TOGGLE_NONE) onToggle()
    }

    fun handle(event: KeyEvent) {
        if (binding != prefs.toggleButtons) {
            reset()
            binding = prefs.toggleButtons
        }
        if (binding == Prefs.TOGGLE_NONE) {
            reset()
            return
        }
        val required = when (binding) {
            Prefs.TOGGLE_L1_R1 -> listOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1)
            Prefs.TOGGLE_L2_R2 -> listOf(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2)
            else -> listOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)
        }
        if (event.keyCode !in required) return
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) keys.down(event.keyCode, event.deviceId)
            KeyEvent.ACTION_UP -> keys.up(event.keyCode, event.deviceId)
        }
        val held = required.all(keys::isDown)
        if (!held) {
            scheduler.removeCallbacks(hold)
            armed = false
            fired = false
        } else if (!armed && !fired) {
            armed = true
            scheduler.postDelayed(hold, prefs.toggleHoldMs)
        }
    }

    fun reset() {
        scheduler.removeCallbacks(hold)
        keys.clear()
        armed = false
        fired = false
    }
}
