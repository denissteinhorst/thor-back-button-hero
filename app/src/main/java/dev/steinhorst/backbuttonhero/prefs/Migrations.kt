package dev.steinhorst.backbuttonhero.prefs

import android.content.SharedPreferences
import dev.steinhorst.backbuttonhero.input.BackGestureButton
import dev.steinhorst.backbuttonhero.input.DpadDirection
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.prefs.Prefs.Companion.SHOULDER_BACK
import dev.steinhorst.backbuttonhero.prefs.Prefs.Companion.SHOULDER_FLICK_LEFT
import dev.steinhorst.backbuttonhero.prefs.Prefs.Companion.SHOULDER_FLICK_RIGHT
import dev.steinhorst.backbuttonhero.prefs.Prefs.Companion.SHOULDER_HOME
import dev.steinhorst.backbuttonhero.prefs.Prefs.Companion.SHOULDER_RECENTS
import dev.steinhorst.backbuttonhero.prefs.Prefs.Companion.SHOULDER_SWAP_APPS
import dev.steinhorst.backbuttonhero.prefs.Prefs.Companion.SHOULDER_SWITCHER
import dev.steinhorst.backbuttonhero.prefs.Prefs.Companion.TOGGLE_L2_R2

/**
 * The one-time rewrites of stored settings, in the order they ran when they lived in [Prefs]' `init`.
 * Each is guarded by its own versioned flag; a fresh install is marked as having been through all of them.
 */
internal object Migrations {
    /** The pre-assignable single-press choices; only the one-time migration still reads them. */
    private const val SINGLE_BACK = "back"
    private const val SINGLE_LAUNCHER = "launcher"

    fun run(sp: SharedPreferences) {
        markFreshInstall(sp)
        navigationFlick(sp)
        removeSwapScreens(sp)
        shoulderDefaultsV3(sp)
        backGesturesV1(sp)
        exceptionSticksV2(sp)
        exceptionButtonsV1(sp)
        togglePairsV1(sp)
        toggleDefaultV2(sp)
        unassignedDefaultsV1(sp)
    }

    private fun markFreshInstall(sp: SharedPreferences) {
        // A fresh install starts with nothing assigned. Every migration below exists for installs
        // that already hold settings, so a fresh one is marked as having been through all of them.
        if (sp.all.isEmpty()) {
            sp.edit()
                .putBoolean("navigation_flick_defaults_v1", true)
                .putBoolean("navigation_flick_directions_v2", true)
                .putBoolean("shoulder_defaults_v3", true)
                .putBoolean("back_gestures_v1", true)
                .putBoolean("toggle_pairs_v1", true)
                .putBoolean("toggle_default_v2", true)
                .putBoolean("unassigned_defaults_v1", true)
                .apply()
        }
    }

    private fun navigationFlick(sp: SharedPreferences) {
        // One-time test default for the navigation-flick experiment. Keep L2/R2 and all
        // subsequent user choices untouched.
        val hadV1Defaults = sp.getBoolean("navigation_flick_defaults_v1", false)
        if (!hadV1Defaults) {
            sp.edit()
                .putString("${ShoulderButton.L1.prefKey}_package", SHOULDER_FLICK_LEFT)
                .putString("${ShoulderButton.R1.prefKey}_package", SHOULDER_FLICK_RIGHT)
                .putBoolean("navigation_flick_defaults_v1", true)
                .putBoolean("navigation_flick_directions_v2", true)
                .apply()
        }
        // v1's labels were mirrored relative to the direction Quickstep actually moved.
        // Swap stored special actions once so existing button behavior does not change.
        if (hadV1Defaults && !sp.getBoolean("navigation_flick_directions_v2", false)) {
            val edit = sp.edit()
            ShoulderButton.entries.forEach { button ->
                val key = "${button.prefKey}_package"
                when (sp.getString(key, "")) {
                    SHOULDER_FLICK_LEFT -> edit.putString(key, SHOULDER_FLICK_RIGHT)
                    SHOULDER_FLICK_RIGHT -> edit.putString(key, SHOULDER_FLICK_LEFT)
                }
            }
            edit.putBoolean("navigation_flick_directions_v2", true).apply()
        }
    }

    private fun removeSwapScreens(sp: SharedPreferences) {
        // Screen swapping is already covered by holding Back, so remove the briefly
        // offered duplicate shoulder action from existing development installs.
        val edit = sp.edit()
        var changed = false
        ShoulderButton.entries.forEach { button ->
            val key = "${button.prefKey}_package"
            if (sp.getString(key, "") == "@swap_screens") {
                edit.putString(key, "")
                changed = true
            }
        }
        if (changed) edit.apply()
    }

    private fun shoulderDefaultsV3(sp: SharedPreferences) {
        // L2 and R2 used to ship unassigned. A fresh install now starts with a screenshot and a
        // controller-focus shortcut, but an update must never hand those to someone who left them
        // empty: pin whatever an existing install has (unassigned included) before the flag is set.
        if (!sp.getBoolean("shoulder_defaults_v3", false)) {
            val defaults = sp.edit()
            listOf(ShoulderButton.L2, ShoulderButton.R2).forEach { button ->
                val key = "${button.prefKey}_package"
                if (!sp.contains(key)) defaults.putString(key, "")
            }
            defaults.putBoolean("shoulder_defaults_v3", true).apply()
        }
    }

    private fun backGesturesV1(sp: SharedPreferences) {
        // The Back gestures became assignable: carry each one's old switch and the single-press
        // choice over, so an update behaves exactly as before. A custom launcher choice becomes plain Home.
        if (!sp.getBoolean("back_gestures_v1", false)) {
            val gestures = sp.edit()
            fun legacy(key: String, default: Boolean, action: String) =
                if (sp.getBoolean(key, default)) action else ""
            gestures.putString("${BackGestureButton.SINGLE.prefKey}_package",
                if (sp.getString("single_action", SINGLE_LAUNCHER) == SINGLE_BACK) SHOULDER_BACK else SHOULDER_HOME)
            gestures.putString("${BackGestureButton.DOUBLE.prefKey}_package", legacy("double_enabled", true, SHOULDER_SWITCHER))
            gestures.putString("${BackGestureButton.TAP_HOLD.prefKey}_package", legacy("tap_hold_enabled", true, SHOULDER_RECENTS))
            gestures.putString("${BackGestureButton.HOLD.prefKey}_package", legacy("hold_enabled", true, SHOULDER_SWAP_APPS))
            gestures.putBoolean("back_gestures_v1", true).apply()
        }
    }

    private fun exceptionSticksV2(sp: SharedPreferences) {
        // The sticks got their own exception columns. Until now their shortcuts were active in every
        // listed app, so existing exceptions keep them on instead of silently handing the sticks over.
        if (!sp.getBoolean("exception_sticks_v2", false)) {
            val migrated = sp.edit()
            sp.getString("app_exceptions", null)?.let { stored ->
                migrated.putString("app_exceptions", stored.lineSequence().joinToString("\n") { line ->
                    val parts = line.split('\t', limit = 4)
                    val allowed = parts.getOrNull(1)?.toLongOrNull()
                    if (parts.size == 4 && allowed != null) {
                        (parts.take(1) + (allowed or (ExceptionControl.STICK_BITS shl 12)) + parts.drop(2)).joinToString("\t")
                    } else line
                })
            }
            migrated.putBoolean("exception_sticks_v2", true).apply()
        }
    }

    private fun exceptionButtonsV1(sp: SharedPreferences) {
        // Home, Start, Select and the face buttons got exception columns too. Like the sticks, they
        // start as handled in every listed app instead of being handed over to it unasked.
        if (!sp.getBoolean("exception_buttons_v1", false)) {
            val migrated = sp.edit()
            sp.getString("app_exceptions", null)?.let { stored ->
                migrated.putString("app_exceptions", stored.lineSequence().joinToString("\n") { line ->
                    val parts = line.split('\t', limit = 4)
                    val allowed = parts.getOrNull(1)?.toLongOrNull()
                    if (parts.size == 4 && allowed != null) {
                        val buttons = ExceptionControl.BUTTON_BITS shl ExceptionControl.BUTTON_SHIFT
                        (parts.take(1) + (allowed or buttons) + parts.drop(2)).joinToString("\t")
                    } else line
                })
            }
            migrated.putBoolean("exception_buttons_v1", true).apply()
        }
    }

    private fun togglePairsV1(sp: SharedPreferences) {
        // Single-stick toggles were offered in development builds; keep their replacement usable.
        if (!sp.getBoolean("toggle_pairs_v1", false)) {
            val toggle = sp.edit()
            if (sp.getString("toggle_buttons", TOGGLE_L2_R2) in listOf("l3", "r3")) {
                toggle.putString("toggle_buttons", TOGGLE_L2_R2)
            }
            toggle.putBoolean("toggle_pairs_v1", true).apply()
        }
    }

    private fun toggleDefaultV2(sp: SharedPreferences) {
        if (!sp.getBoolean("toggle_default_v2", false)) {
            val toggle = sp.edit()
            if (!sp.contains("toggle_buttons")) toggle.putString("toggle_buttons", TOGGLE_L2_R2)
            toggle.putBoolean("toggle_default_v2", true).apply()
        }
    }

    private fun unassignedDefaultsV1(sp: SharedPreferences) {
        // Defaults became "nothing assigned": pin what an existing install was running on.
        if (!sp.getBoolean("unassigned_defaults_v1", false)) {
            val pinned = sp.edit()
            DpadDirection.entries.forEach {
                if (!sp.contains("${it.prefKey}_package")) pinned.putString("${it.prefKey}_package", it.earlierDefault)
            }
            if (!sp.contains("toggle_buttons")) pinned.putString("toggle_buttons", TOGGLE_L2_R2)
            pinned.putBoolean("unassigned_defaults_v1", true).apply()
        }
    }
}
