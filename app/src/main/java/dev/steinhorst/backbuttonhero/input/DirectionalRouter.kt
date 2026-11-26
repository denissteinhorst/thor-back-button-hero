package dev.steinhorst.backbuttonhero.input

import dev.steinhorst.backbuttonhero.prefs.Prefs

/**
 * Back + D-pad and Back + analog stick, as reported by the root helper or the focus overlay.
 * Decides whether a direction runs, is armed, or stays the game's, and when an armed controller
 * focus or app switch commits. Effects and level repeat stay in [Host].
 */
class DirectionalRouter(private val host: Host) {
    interface Host {
        /** Back, or in dual mode Home, is currently held. */
        val modifierDown: Boolean
        /** Paused interception, an open switcher or top Recents: stick deflections are not combos. */
        val stickInputBlocked: Boolean
        /** Without the helper the overlay holds window focus and would swallow the committing key. */
        val overlayAttached: Boolean
        val repeatingButton: ComboButton?
        fun layered(button: ComboButton): ComboButton
        fun action(button: ComboButton): String
        fun allows(button: ComboButton): Boolean
        fun markComboFired()
        fun log(message: String)
        fun showFocusIndicator(button: ComboButton)
        fun runCombo(button: ComboButton, action: String)
        fun startLevelRepeat(button: ComboButton, action: String)
        fun stopLevelRepeat()
        fun runReleased(button: ComboButton)
    }

    private var pendingFocus: ComboButton? = null
    private var pendingReleased = false

    /** A D-pad press while no switcher takes the D-pad. */
    fun press(direction: DpadDirection) {
        if (!host.modifierDown) return
        val button = host.layered(direction)
        val action = host.action(button)
        if (action.isEmpty()) return
        if (!host.allows(button)) {
            // The helper already forwarded this press to the game; see ComboRouter.
            host.markComboFired()
            host.log("DPAD   $button passthrough action=$action")
            return
        }
        host.markComboFired()
        pendingFocus = null
        // Same firmware race as for the shoulder buttons (see ComboRouter): arm on press,
        // commit on release.
        if (action == Prefs.SHOULDER_CONTROLLER_FOCUS || action == Prefs.SHOULDER_SWITCH_APP) {
            pendingFocus = button
            pendingReleased = false
            if (action == Prefs.SHOULDER_CONTROLLER_FOCUS) host.showFocusIndicator(button)
        } else {
            host.stopLevelRepeat()
            host.runCombo(button, action)
            host.startLevelRepeat(button, action)
        }
    }

    /** A null [direction] is the stick returning to neutral. */
    fun stick(stick: Stick, direction: DpadDirection?) {
        if (direction == null) {
            if ((host.repeatingButton?.base as? StickButton)?.stick == stick) host.stopLevelRepeat()
            if ((pendingFocus?.base as? StickButton)?.stick == stick) {
                pendingReleased = true
                commit()
            }
            return
        }
        if (host.stickInputBlocked || !host.modifierDown) return
        val button = host.layered(StickButton.of(stick, direction))
        val action = host.action(button)
        if (action.isEmpty() || !host.allows(button)) return
        host.markComboFired()
        pendingFocus = null
        host.stopLevelRepeat()
        // One deflection is one action, except the stepping ones, which repeat while it is held.
        if (action == Prefs.SHOULDER_CONTROLLER_FOCUS || action == Prefs.SHOULDER_SWITCH_APP) {
            pendingFocus = button
            pendingReleased = false
            if (action == Prefs.SHOULDER_CONTROLLER_FOCUS) host.showFocusIndicator(button)
        } else {
            host.log("STICK  $button action=$action")
            host.runCombo(button, action)
            host.startLevelRepeat(button, action)
        }
    }

    fun release(horizontal: Boolean) {
        (host.repeatingButton?.base as? DpadDirection)?.let {
            if ((it == DpadDirection.LEFT || it == DpadDirection.RIGHT) == horizontal) host.stopLevelRepeat()
        }
        val pending = pendingFocus?.base as? DpadDirection ?: return
        if ((pending == DpadDirection.LEFT || pending == DpadDirection.RIGHT) != horizontal) return
        pendingReleased = true
        commit()
    }

    /** Once the overlay is gone a still held direction is released to the app, unseen; Back's
     *  release is the last moment left to commit an armed controller focus. */
    fun overlayDetached() {
        pendingReleased = true
        commit()
    }

    fun forgetArmed() {
        pendingFocus = null
    }

    private fun commit() {
        val pending = pendingFocus ?: return
        if (!pendingReleased || host.overlayAttached) return
        pendingFocus = null
        host.runReleased(pending)
    }
}
