package dev.steinhorst.backbuttonhero

import android.view.InputDevice
import android.view.KeyEvent

/**
 * Observes toggle buttons; mirrored devices share one hold timer. Every pair but Back + Home only
 * observes. Back + Home must do nothing but toggle, so once both keys are down neither press reaches
 * anything else: the second is consumed whole, and a first press the service already handled gets
 * [onPaired] to cancel its Back gesture. A press that would otherwise reach Android at once (any
 * while paused, [defers]) is held back and handed to [onReplay] on release, unless it got paired.
 */
class ServiceToggleGesture(
    private val prefs: Prefs,
    private val scheduler: GestureScheduler,
    private val defers: (keyCode: Int) -> Boolean = { false },
    private val onPaired: () -> Unit = {},
    private val onReplay: (down: KeyEvent, up: KeyEvent) -> Unit = { _, _ -> },
    private val onToggle: () -> Unit,
) {
    private enum class Fate { PASS, DEFER, SWALLOW }
    private class Press(val fate: Fate, val down: KeyEvent) { var paired = false }

    private val keys = PhysicalKeyTracker()
    // Back + Home only: the current physical press of each key, shared by its mirrored devices.
    private val presses = mutableMapOf<Int, Press>()
    private var armed = false
    private var fired = false
    private var binding = prefs.toggleButtons
    private val hold = Runnable {
        armed = false
        fired = true
        if (prefs.toggleButtons == binding && binding != Prefs.TOGGLE_NONE) onToggle()
    }

    /** True when [event] must be consumed; every other key continues unchanged. */
    fun handle(event: KeyEvent): Boolean {
        if (binding != prefs.toggleButtons) {
            reset()
            binding = prefs.toggleButtons
        }
        if (binding == Prefs.TOGGLE_NONE) {
            reset()
            return false
        }
        val required = when (binding) {
            Prefs.TOGGLE_L1_R1 -> listOf(KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1)
            Prefs.TOGGLE_L2_R2 -> listOf(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2)
            Prefs.TOGGLE_START_SELECT -> listOf(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT)
            Prefs.TOGGLE_BACK_HOME -> listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME)
            else -> listOf(KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR)
        }
        if (event.keyCode !in required) return false
        // AYN's own Home button shares the key code, and an injected Back is never a physical press.
        if (event.keyCode == KeyEvent.KEYCODE_HOME && !event.isFromSource(InputDevice.SOURCE_GAMEPAD)) return false
        if (event.deviceId < 0) return false
        val consume = when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0 && keys.down(event.keyCode, event.deviceId) && binding == Prefs.TOGGLE_BACK_HOME) {
                    presses[event.keyCode] = newPress(event, required.first { it != event.keyCode })
                }
                presses[event.keyCode]?.let { it.fate != Fate.PASS } ?: false
            }
            KeyEvent.ACTION_UP -> {
                val press = presses[event.keyCode]
                keys.up(event.keyCode, event.deviceId)
                if (press != null && !keys.isDown(event.keyCode)) {
                    presses.remove(event.keyCode)
                    if (press.fate == Fate.DEFER && !press.paired) onReplay(press.down, event)
                }
                press?.let { it.fate != Fate.PASS } ?: false
            }
            else -> false
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
        return consume
    }

    private fun newPress(down: KeyEvent, partner: Int): Press {
        val first = presses[partner] ?: return Press(if (defers(down.keyCode)) Fate.DEFER else Fate.PASS, down)
        first.paired = true
        if (first.fate == Fate.PASS) onPaired()
        return Press(Fate.SWALLOW, down)
    }

    fun reset() {
        scheduler.removeCallbacks(hold)
        keys.clear()
        presses.clear()
        armed = false
        fired = false
    }

    companion object {
        /** The buttons of [binding] as printed on the device; null when no shortcut is set. */
        fun label(binding: String): String? = when (binding) {
            Prefs.TOGGLE_NONE -> null
            Prefs.TOGGLE_L1_R1 -> "L1 + R1"
            Prefs.TOGGLE_L2_R2 -> "L2 + R2"
            Prefs.TOGGLE_START_SELECT -> "Start + Select"
            Prefs.TOGGLE_BACK_HOME -> "Back + Home"
            else -> "L3 + R3"
        }
    }
}
