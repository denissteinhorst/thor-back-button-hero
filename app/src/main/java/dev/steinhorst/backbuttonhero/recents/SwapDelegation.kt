package dev.steinhorst.backbuttonhero.recents

import android.view.KeyEvent
import dev.steinhorst.backbuttonhero.core.Shell
import java.io.File

/**
 * Frontends that lay out both screens themselves undo a task swap at once (see [TaskMovePolicy.takenBack]).
 * IISU and Cocoon have a swap of their own, agreed as package-specific exceptions: while one of them fills
 * both screens, Hold presses its screen swap button instead of moving its tasks.
 *  - IISU's button is its "Screen swap" input setting, read as root from IISU's settings store; a missing
 *    value is IISU's default, Y.
 *  - Cocoon swaps on X, fixed in Cocoon (3.12) and handled by both of its screens' activities; it toggles
 *    Cocoon's own `swap_screens` layout setting.
 */
object SwapDelegation {
    const val IISU = "com.iisulauncher"
    const val COCOON = "rip.moth.cocoonshell"
    const val COCOON_KEY = KeyEvent.KEYCODE_BUTTON_X

    /**
     * Prints the value stored after the key as one line (pservice returns only the first), empty without one.
     * IISU's data lives beside ours, under the same user: [ownDataDir] is this app's `dataDir`.
     */
    fun iisuButtonCommand(ownDataDir: String): String {
        val settings = File(File(ownDataDir).parentFile, "$IISU/files/datastore/launcher_settings.preferences_pb")
        return "tr -cs 'A-Za-z_' '\\n' < ${Shell.q(settings.path)} | grep -x -A1 screen_swap_shortcut_button | tail -1"
    }

    /** The frontend whose own swap Hold presses: one with a swap of its own that fills both screens. */
    fun delegate(topPackage: String?, bottomPackage: String?): String? =
        topPackage?.takeIf { it == bottomPackage && (it == IISU || it == COCOON) }

    /** The key code IISU swaps its screens on; null when its swap is switched off or the value is unknown. */
    fun iisuKeyCode(stored: String?): Int? = when (stored?.trim().orEmpty()) {
        "", "Y" -> KeyEvent.KEYCODE_BUTTON_Y
        "X" -> KeyEvent.KEYCODE_BUTTON_X
        "B" -> KeyEvent.KEYCODE_BUTTON_B
        else -> null
    }
}
