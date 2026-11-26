package dev.steinhorst.backbuttonhero.input

import android.view.KeyEvent
import dev.steinhorst.backbuttonhero.prefs.Prefs

/**
 * Holding Back and pressing a shoulder, Home/Start/Select or face button runs that button's
 * configured action instead of whatever Back alone would have done, and swallows the button's
 * own event so the focused app/game never sees a stray press. Buttons with nothing configured
 * are left alone, even while Back is held. The effects stay in [Host]; this keeps the decision
 * and the per-device release tracking.
 */
class ComboRouter(private val host: Host) {
    interface Host {
        /** Back, or in dual mode Home, is currently held. */
        val modifierDown: Boolean
        fun action(button: ComboButton): String
        fun allows(button: ComboButton): Boolean
        fun markComboFired()
        fun log(message: String)
        fun runCombo(button: ComboButton, action: String)
        fun showFocusIndicator(button: ComboButton)
        fun runReleased(button: ComboButton)
    }

    private val keys = PhysicalKeyTracker()
    private val pendingFocusTargets = mutableMapOf<Int, ComboButton>()

    fun isDown(keyCode: Int): Boolean = keys.isDown(keyCode)

    fun clear() {
        keys.clear()
        pendingFocusTargets.clear()
    }

    fun handle(action: Int, keyCode: Int, deviceId: Int, repeatCount: Int, button: ComboButton): Boolean {
        val bound = host.action(button)
        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                // Outside an active Back combo the controller belongs entirely to the foreground
                // app. Do not even track the key in that case, so its UP remains untouched too.
                if (!host.modifierDown || bound.isEmpty()) return false
                if (!host.allows(button)) {
                    // The app gets the button, yet the press was still a combo: Back's release must
                    // not turn into a tap that opens something on top of the app's own shortcut.
                    host.markComboFired()
                    host.log("SHOULDER $button passthrough action=$bound")
                    return false
                }
                if (keys.down(keyCode, deviceId) && repeatCount == 0) {
                    host.markComboFired()
                    // Changing the controller route while its trigger is physically held is racy
                    // in AYN's firmware: the setting changes, but the route can stay on the old
                    // display. Arm focus here and commit after both mirrored devices report UP.
                    if (bound == Prefs.SHOULDER_CONTROLLER_FOCUS || bound == Prefs.SHOULDER_SWITCH_APP) {
                        pendingFocusTargets[keyCode] = button
                        if (bound == Prefs.SHOULDER_CONTROLLER_FOCUS) host.showFocusIndicator(button)
                    } else {
                        host.runCombo(button, bound)
                    }
                }
                true
            }
            // Consume releases only for shoulder presses that began as a Back combo. The Thor can
            // report one physical controller through two device ids, so both releases are tracked.
            KeyEvent.ACTION_UP -> {
                val tracked = keys.up(keyCode, deviceId)
                if (tracked && !keys.isDown(keyCode)) {
                    pendingFocusTargets.remove(keyCode)?.let(host::runReleased)
                }
                tracked
            }
            else -> false
        }
    }
}
