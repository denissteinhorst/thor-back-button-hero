package dev.steinhorst.backbuttonhero

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("back_button_hero", Context.MODE_PRIVATE)

    init {
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

    var doubleTapEnabled: Boolean
        get() = sp.getBoolean("double_enabled", DEFAULT_DOUBLE_TAP_ENABLED)
        set(v) = sp.edit().putBoolean("double_enabled", v).apply()

    var doubleTapMs: Long
        get() = sp.getLong("double_ms", DEFAULT_DOUBLE_TAP_MS)
        set(v) = sp.edit().putLong("double_ms", v).apply()

    var holdEnabled: Boolean
        get() = sp.getBoolean("hold_enabled", DEFAULT_HOLD_ENABLED)
        set(v) = sp.edit().putBoolean("hold_enabled", v).apply()

    var holdMs: Long
        get() = sp.getLong("hold_ms", DEFAULT_HOLD_MS)
        set(v) = sp.edit().putLong("hold_ms", v).apply()

    /** Tap, then hold again for [tapHoldMs] opens the top screen's Recents
     *  instead of swapping screens. Only takes effect while double-tap is enabled, since
     *  it needs the "was there a pending tap" window double-tap already tracks. */
    var tapHoldEnabled: Boolean
        get() = sp.getBoolean("tap_hold_enabled", DEFAULT_TAP_HOLD_ENABLED)
        set(v) = sp.edit().putBoolean("tap_hold_enabled", v).apply()

    var tapHoldMs: Long
        get() = sp.getLong("tap_hold_ms", DEFAULT_TAP_HOLD_MS)
        set(v) = sp.edit().putLong("tap_hold_ms", v).apply()

    /** [SINGLE_BACK], [SINGLE_LAUNCHER] (system default secondary home) or a flattened launcher component. */
    var singleAction: String
        get() = sp.getString("single_action", SINGLE_LAUNCHER) ?: SINGLE_LAUNCHER
        set(v) = sp.edit().putString("single_action", v).apply()

    val singleLauncher: ComponentName?
        get() = ComponentName.unflattenFromString(singleAction)

    /** Holding Back and pressing [button] runs this action. Plain values are app package names. */
    fun shoulderAction(button: ComboButton): String =
        sp.getString("${button.prefKey}_package", button.defaultAction) ?: button.defaultAction
    fun setShoulderAction(button: ComboButton, action: String) {
        sp.edit().putString("${button.prefKey}_package", action).apply()
    }

    /** [DISPLAY_TOP] or [DISPLAY_BOTTOM] - which screen [button]'s app opens on. */
    fun shoulderDisplay(button: ComboButton): String = sp.getString("${button.prefKey}_display", DISPLAY_BOTTOM) ?: DISPLAY_BOTTOM
    fun setShoulderDisplay(button: ComboButton, display: String) { sp.edit().putString("${button.prefKey}_display", display).apply() }

    /** [TARGET_BOTH], [DISPLAY_TOP] or [DISPLAY_BOTTOM] - which screens [button]'s volume, brightness or
     *  screenshot action applies to. Until chosen: both screens, except for the screenshot, which has
     *  always been the system's top-screen screenshot and stays that way. */
    fun levelTarget(button: ComboButton): String =
        sp.getString("${button.prefKey}_target", null)
            ?: if (shoulderAction(button) == SHOULDER_SCREENSHOT) DISPLAY_TOP else TARGET_BOTH
    fun setLevelTarget(button: ComboButton, target: String) { sp.edit().putString("${button.prefKey}_target", target).apply() }

    /** Whether any D-pad direction has an action - only then is the D-pad worth intercepting. */
    fun hasDpadAction(): Boolean = DpadDirection.entries.any { shoulderAction(it).isNotEmpty() }

    // SharedPreferences keeps its listeners only weakly, so hold the wrappers here.
    private val listeners = mutableMapOf<() -> Unit, SharedPreferences.OnSharedPreferenceChangeListener>()

    /** Calls [listener] after any setting changes, from any [Prefs] instance in this process. */
    fun registerListener(listener: () -> Unit) {
        val wrapper = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> listener() }
        listeners[listener] = wrapper
        sp.registerOnSharedPreferenceChangeListener(wrapper)
    }

    fun unregisterListener(listener: () -> Unit) {
        listeners.remove(listener)?.let { sp.unregisterOnSharedPreferenceChangeListener(it) }
    }

    /** Restores app configuration only; Android permissions and service state are untouched. */
    fun resetToDefaults() {
        sp.edit().clear()
            .putBoolean("double_enabled", DEFAULT_DOUBLE_TAP_ENABLED)
            .putLong("double_ms", DEFAULT_DOUBLE_TAP_MS)
            .putBoolean("tap_hold_enabled", DEFAULT_TAP_HOLD_ENABLED)
            .putLong("tap_hold_ms", DEFAULT_TAP_HOLD_MS)
            .putBoolean("hold_enabled", DEFAULT_HOLD_ENABLED)
            .putLong("hold_ms", DEFAULT_HOLD_MS)
            .putString("single_action", SINGLE_LAUNCHER)
            .putString("${ShoulderButton.L1.prefKey}_package", SHOULDER_FLICK_LEFT)
            .putString("${ShoulderButton.R1.prefKey}_package", SHOULDER_FLICK_RIGHT)
            .putBoolean("navigation_flick_defaults_v1", true)
            .putBoolean("navigation_flick_directions_v2", true)
            .apply()
    }

    companion object {
        const val DEFAULT_DOUBLE_TAP_ENABLED = true
        const val DEFAULT_DOUBLE_TAP_MS = 300L
        const val DEFAULT_TAP_HOLD_ENABLED = true
        const val DEFAULT_TAP_HOLD_MS = 1000L
        const val DEFAULT_HOLD_ENABLED = true
        const val DEFAULT_HOLD_MS = 1000L
        const val SINGLE_BACK = "back"
        const val SINGLE_LAUNCHER = "launcher"
        const val DISPLAY_TOP = "top"
        const val DISPLAY_BOTTOM = "bottom"
        const val TARGET_BOTH = "both"
        const val SHOULDER_FLICK_LEFT = "@navigation_flick_left"
        const val SHOULDER_FLICK_RIGHT = "@navigation_flick_right"
        const val SHOULDER_HOME = "@home"
        const val SHOULDER_RECENTS = "@recents"
        const val SHOULDER_SCREENSHOT = "@screenshot"
        const val SHOULDER_QUICK_SETTINGS = "@quick_settings"
        const val SHOULDER_NOTIFICATIONS = "@notifications"
        const val SHOULDER_INTERNET = "@internet"
        const val SHOULDER_BLUETOOTH = "@bluetooth"
        const val SHOULDER_NIGHT_LIGHT = "@night_light"
        const val SHOULDER_EXTRA_DIM = "@extra_dim"
        const val SHOULDER_DISPLAY_SETTINGS = "@display_settings"
        const val SHOULDER_SOUND_SETTINGS = "@sound_settings"
        const val SHOULDER_LOCATION_SETTINGS = "@location_settings"
        const val SHOULDER_CAST_SETTINGS = "@cast_settings"
        const val SHOULDER_DND_SETTINGS = "@dnd_settings"
        const val SHOULDER_BATTERY_SAVER = "@battery_saver"
        const val SHOULDER_VOLUME_UP = "@volume_up"
        const val SHOULDER_VOLUME_DOWN = "@volume_down"
        const val SHOULDER_BRIGHTNESS_UP = "@brightness_up"
        const val SHOULDER_BRIGHTNESS_DOWN = "@brightness_down"

        fun isShoulderApp(action: String): Boolean = action.isNotEmpty() && !action.startsWith('@')

        /** Volume and brightness actions, which apply to [levelTarget] rather than one launch screen. */
        fun isLevelAction(action: String): Boolean = action in setOf(
            SHOULDER_VOLUME_UP, SHOULDER_VOLUME_DOWN, SHOULDER_BRIGHTNESS_UP, SHOULDER_BRIGHTNESS_DOWN,
        )

        /** Actions that offer the "Screen" choice: the level actions and the screenshot. */
        fun hasScreenTarget(action: String): Boolean = isLevelAction(action) || action == SHOULDER_SCREENSHOT
    }
}
