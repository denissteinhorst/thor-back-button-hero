package dev.steinhorst.backbuttonhero.actions

import dev.steinhorst.backbuttonhero.prefs.Prefs

/**
 * The screen the controller drives, for [Prefs.TARGET_FOCUSED] and a toggle out of AYN's Auto-lock.
 * A fixed route names its screen. In Auto-lock AYN keeps no screen in its setting, so the screen is
 * the display of Android's focused input window, read the way Switch active app reads it; when that
 * cannot be read or names neither screen, the answer is the top screen.
 */
object FocusedScreen {
    const val INPUT_FOCUS_COMMAND = "dumpsys input | grep -m1 'FocusedDisplayId:'"

    /** Whether [route] ([ControllerFocus.currentTarget]) needs Android's input display, a root read. */
    fun needsInputDisplay(route: String): Boolean = route != Prefs.DISPLAY_TOP && route != Prefs.DISPLAY_BOTTOM

    /** [Prefs.DISPLAY_TOP] or [Prefs.DISPLAY_BOTTOM]; [readInput] runs only for Auto-lock and may block. */
    fun resolve(route: String, bottomDisplayId: Int?, readInput: () -> String?): String = when {
        !needsInputDisplay(route) -> route
        bottomDisplayId != null && AutoControllerFocus.focusedDisplay(readInput()) == bottomDisplayId -> Prefs.DISPLAY_BOTTOM
        else -> Prefs.DISPLAY_TOP
    }

    /**
     * The display id the controller drives, for callers that must decide at once (Last app, native
     * Back, prompts, app exceptions). [setting] is AYN's raw `screen_focus_lock`. In Auto-lock the
     * route follows the screen last touched, which is the display holding Android's active window
     * ([activeDisplayId], from the accessibility windows: per display one window is focused, but
     * only the top-focused display's window is active); top when that names neither screen.
     */
    fun displayId(setting: String?, topDisplayId: Int, bottomDisplayId: Int?, activeDisplayId: () -> Int?): Int = when (setting) {
        ControllerFocus.TOP_VALUE -> topDisplayId
        ControllerFocus.BOTTOM_VALUE -> bottomDisplayId ?: topDisplayId
        else -> activeDisplayId()?.takeIf { bottomDisplayId != null && it == bottomDisplayId } ?: topDisplayId
    }

    /**
     * [displayId] with Android's input display: in Auto-lock [readInput] (blocking, root) names the
     * focused input display, which the accessibility windows only learn about on a window change (a
     * launch onto an already shown app moves input focus without one); [fallback] when unreadable.
     */
    fun readDisplayId(setting: String?, topDisplayId: Int, bottomDisplayId: Int?, fallback: Int, readInput: () -> String?): Int =
        displayId(setting, topDisplayId, bottomDisplayId) {
            AutoControllerFocus.focusedDisplay(readInput())?.takeIf { it == topDisplayId || it == bottomDisplayId } ?: fallback
        }
}
