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
        // L2 and R2 used to ship unassigned. A fresh install now starts with a screenshot and a
        // controller-focus shortcut, but an update must never hand those to someone who left them
        // empty: pin whatever an existing install has (unassigned included) before the flag is set.
        if (!sp.getBoolean("shoulder_defaults_v3", false)) {
            val defaults = sp.edit()
            if (hadV1Defaults) {
                listOf(ShoulderButton.L2, ShoulderButton.R2).forEach { button ->
                    val key = "${button.prefKey}_package"
                    if (!sp.contains(key)) defaults.putString(key, "")
                }
            } else {
                putFactoryShoulders(defaults)
            }
            defaults.putBoolean("shoulder_defaults_v3", true).apply()
        }
        // Single-stick toggles were offered in development builds; keep their replacement usable.
        if (!sp.getBoolean("toggle_pairs_v1", false)) {
            val toggle = sp.edit()
            if (sp.getString("toggle_buttons", DEFAULT_TOGGLE_BUTTONS) in listOf("l3", "r3")) {
                toggle.putString("toggle_buttons", DEFAULT_TOGGLE_BUTTONS)
            }
            toggle.putBoolean("toggle_pairs_v1", true).apply()
        }
        if (!sp.getBoolean("toggle_default_v2", false)) {
            val toggle = sp.edit()
            if (!sp.contains("toggle_buttons")) toggle.putString("toggle_buttons", DEFAULT_TOGGLE_BUTTONS)
            toggle.putBoolean("toggle_default_v2", true).apply()
        }
    }

    private fun putFactoryShoulders(edit: SharedPreferences.Editor) {
        edit.putString("${ShoulderButton.R2.prefKey}_package", SHOULDER_CONTROLLER_FOCUS)
            .putString("${ShoulderButton.L2.prefKey}_package", SHOULDER_SCREENSHOT)
            .putString("${ShoulderButton.L2.prefKey}_target", DISPLAY_TOP)
    }

    /** Clears optional combos, including the temporary activation shortcut. */
    fun unbindOptionals() {
        val edit = sp.edit()
        ShoulderButton.entries.forEach { edit.putString("${it.prefKey}_package", "") }
        DpadDirection.entries.forEach { edit.putString("${it.prefKey}_package", "") }
        edit.putString("toggle_buttons", TOGGLE_NONE).apply()
    }

    var doubleTapEnabled: Boolean
        get() = sp.getBoolean("double_enabled", DEFAULT_DOUBLE_TAP_ENABLED)
        set(v) = sp.edit().putBoolean("double_enabled", v).apply()

    var doubleTapMs: Long
        get() = sp.getLong("double_ms", DEFAULT_DOUBLE_TAP_MS)
        set(v) = sp.edit().putLong("double_ms", v).apply()

    /** Whether the bottom-screen switcher also shows apps observed on the top screen. */
    var switcherShowsAllApps: Boolean
        get() = sp.getBoolean("switcher_shows_all_apps", DEFAULT_SWITCHER_SHOWS_ALL_APPS)
        set(v) = sp.edit().putBoolean("switcher_shows_all_apps", v).apply()

    /** Whether Recents can temporarily take controller input while it is open. */
    var switcherControllerEnabled: Boolean
        get() = sp.getBoolean("switcher_controller_enabled", DEFAULT_SWITCHER_CONTROLLER_ENABLED)
        set(v) = sp.edit().putBoolean("switcher_controller_enabled", v).apply()

    var toggleButtons: String
        get() = sp.getString("toggle_buttons", DEFAULT_TOGGLE_BUTTONS) ?: DEFAULT_TOGGLE_BUTTONS
        set(v) = sp.edit().putString("toggle_buttons", v).apply()

    var toggleHoldMs: Long
        get() = sp.getLong("toggle_hold_ms", DEFAULT_TOGGLE_HOLD_MS).coerceIn(100L, 3000L)
        set(v) = sp.edit().putLong("toggle_hold_ms", v).apply()

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

    /** [FOCUS_TOGGLE], [DISPLAY_TOP] or [DISPLAY_BOTTOM] for the controller-focus action. */
    fun focusTarget(button: ComboButton): String =
        sp.getString("${button.prefKey}_focus_target", FOCUS_TOGGLE) ?: FOCUS_TOGGLE
    fun setFocusTarget(button: ComboButton, target: String) {
        sp.edit().putString("${button.prefKey}_focus_target", target).apply()
    }

    /** Whether the controller-focus pictogram is shown at all. */
    fun focusIndicator(button: ComboButton): Boolean = sp.getBoolean("${button.prefKey}_focus_indicator", true)
    fun setFocusIndicator(button: ComboButton, shown: Boolean) {
        sp.edit().putBoolean("${button.prefKey}_focus_indicator", shown).apply()
    }

    /** How long the controller-focus pictogram stays visible, [MIN_FOCUS_DURATION_MS]..[MAX_FOCUS_DURATION_MS]. */
    fun focusDuration(button: ComboButton): Long =
        sp.getLong("${button.prefKey}_focus_duration_ms", DEFAULT_FOCUS_DURATION_MS)
            .coerceIn(MIN_FOCUS_DURATION_MS, MAX_FOCUS_DURATION_MS)
    fun setFocusDuration(button: ComboButton, ms: Long) {
        sp.edit().putLong("${button.prefKey}_focus_duration_ms", ms).apply()
    }

    /** Where the controller-focus pictogram appears on its 3x3 grid; [DEFAULT_FOCUS_POSITION] until chosen. */
    fun focusPosition(button: ComboButton): String =
        sp.getString("${button.prefKey}_focus_position", DEFAULT_FOCUS_POSITION) ?: DEFAULT_FOCUS_POSITION
    fun setFocusPosition(button: ComboButton, position: String) {
        sp.edit().putString("${button.prefKey}_focus_position", position).apply()
    }

    /** Whether any D-pad direction has an action - only then is the D-pad worth intercepting. */
    fun hasDpadAction(): Boolean = DpadDirection.entries.any { shoulderAction(it).isNotEmpty() }

    fun snapshot(): SettingsState = SettingsState(
        singleAction, doubleTapEnabled, doubleTapMs, tapHoldEnabled, tapHoldMs,
        holdEnabled, holdMs, switcherShowsAllApps, switcherControllerEnabled, toggleButtons, toggleHoldMs,
        (ShoulderButton.entries + DpadDirection.entries).associate { button ->
            button.prefKey to BindingSettings(
                shoulderAction(button), shoulderDisplay(button), sp.getString("${button.prefKey}_target", null),
                focusTarget(button), focusIndicator(button), focusDuration(button), focusPosition(button),
            )
        },
    )

    /** Refuse stale previews; one editor makes listeners observe the complete new configuration. */
    fun applySettings(before: SettingsState, after: SettingsState): Boolean {
        if (snapshot() != before) return false
        if (before == after) return true
        val edit = sp.edit()
        fun string(key: String, old: String?, value: String?) {
            if (old != value) {
                if (value == null) edit.remove(key) else edit.putString(key, value)
            }
        }
        fun boolean(key: String, old: Boolean, value: Boolean) {
            if (old != value) edit.putBoolean(key, value)
        }
        fun long(key: String, old: Long, value: Long) {
            if (old != value) edit.putLong(key, value)
        }
        string("single_action", before.singleAction, after.singleAction)
        boolean("double_enabled", before.doubleEnabled, after.doubleEnabled)
        long("double_ms", before.doubleMs, after.doubleMs)
        boolean("tap_hold_enabled", before.tapHoldEnabled, after.tapHoldEnabled)
        long("tap_hold_ms", before.tapHoldMs, after.tapHoldMs)
        boolean("hold_enabled", before.holdEnabled, after.holdEnabled)
        long("hold_ms", before.holdMs, after.holdMs)
        boolean("switcher_shows_all_apps", before.allApps, after.allApps)
        boolean("switcher_controller_enabled", before.controller, after.controller)
        string("toggle_buttons", before.toggleButtons, after.toggleButtons)
        long("toggle_hold_ms", before.toggleMs, after.toggleMs)
        before.bindings.forEach { (key, old) ->
            val value = after.bindings.getValue(key)
            string("${key}_package", old.action, value.action)
            string("${key}_display", old.display, value.display)
            string("${key}_target", old.targetOverride, value.targetOverride)
            string("${key}_focus_target", old.focusTarget, value.focusTarget)
            boolean("${key}_focus_indicator", old.indicator, value.indicator)
            long("${key}_focus_duration_ms", old.duration, value.duration)
            string("${key}_focus_position", old.position, value.position)
        }
        edit.apply()
        return true
    }

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
        val edit = sp.edit().clear()
            .putBoolean("double_enabled", DEFAULT_DOUBLE_TAP_ENABLED)
            .putLong("double_ms", DEFAULT_DOUBLE_TAP_MS)
            .putBoolean("switcher_shows_all_apps", DEFAULT_SWITCHER_SHOWS_ALL_APPS)
            .putBoolean("switcher_controller_enabled", DEFAULT_SWITCHER_CONTROLLER_ENABLED)
            .putBoolean("tap_hold_enabled", DEFAULT_TAP_HOLD_ENABLED)
            .putLong("tap_hold_ms", DEFAULT_TAP_HOLD_MS)
            .putBoolean("hold_enabled", DEFAULT_HOLD_ENABLED)
            .putLong("hold_ms", DEFAULT_HOLD_MS)
            .putString("single_action", SINGLE_LAUNCHER)
            .putString("${ShoulderButton.L1.prefKey}_package", SHOULDER_FLICK_LEFT)
            .putString("${ShoulderButton.R1.prefKey}_package", SHOULDER_FLICK_RIGHT)
            .putBoolean("navigation_flick_defaults_v1", true)
            .putBoolean("navigation_flick_directions_v2", true)
            .putBoolean("shoulder_defaults_v3", true)
            .putBoolean("toggle_pairs_v1", true)
            .putBoolean("toggle_default_v2", true)
            .putString("toggle_buttons", DEFAULT_TOGGLE_BUTTONS)
        putFactoryShoulders(edit)
        edit.apply()
    }

    companion object {
        const val TOGGLE_NONE = ""
        const val TOGGLE_BOTH = "both"
        const val TOGGLE_L1_R1 = "l1_r1"
        const val TOGGLE_L2_R2 = "l2_r2"
        const val DEFAULT_TOGGLE_BUTTONS = TOGGLE_L2_R2
        const val DEFAULT_TOGGLE_HOLD_MS = 300L
        const val DEFAULT_DOUBLE_TAP_ENABLED = true
        const val DEFAULT_DOUBLE_TAP_MS = 300L
        const val DEFAULT_SWITCHER_SHOWS_ALL_APPS = false
        const val DEFAULT_SWITCHER_CONTROLLER_ENABLED = true
        const val DEFAULT_TAP_HOLD_ENABLED = true
        const val DEFAULT_TAP_HOLD_MS = 1000L
        const val DEFAULT_HOLD_ENABLED = true
        const val DEFAULT_HOLD_MS = 1000L
        const val SINGLE_BACK = "back"
        const val SINGLE_LAUNCHER = "launcher"
        const val DISPLAY_TOP = "top"
        const val DISPLAY_BOTTOM = "bottom"
        const val TARGET_BOTH = "both"
        const val FOCUS_TOGGLE = "toggle"
        const val FOCUS_POS_CENTER = "center"
        const val DEFAULT_FOCUS_DURATION_MS = 500L
        const val MIN_FOCUS_DURATION_MS = 200L
        const val MAX_FOCUS_DURATION_MS = 1_000L
        const val FOCUS_POS_TOP_LEFT = "top_left"
        const val FOCUS_POS_TOP_CENTER = "top_center"
        const val FOCUS_POS_TOP_RIGHT = "top_right"
        const val FOCUS_POS_CENTER_LEFT = "center_left"
        const val FOCUS_POS_CENTER_RIGHT = "center_right"
        const val DEFAULT_FOCUS_POSITION = FOCUS_POS_CENTER_RIGHT
        const val FOCUS_POS_BOTTOM_LEFT = "bottom_left"
        const val FOCUS_POS_BOTTOM_CENTER = "bottom_center"
        const val FOCUS_POS_BOTTOM_RIGHT = "bottom_right"
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
        const val SHOULDER_CONTROLLER_FOCUS = "@controller_focus"

        fun isShoulderApp(action: String): Boolean = action.isNotEmpty() && !action.startsWith('@')

        /** Volume and brightness actions, which apply to [levelTarget] rather than one launch screen. */
        fun isLevelAction(action: String): Boolean = action in setOf(
            SHOULDER_VOLUME_UP, SHOULDER_VOLUME_DOWN, SHOULDER_BRIGHTNESS_UP, SHOULDER_BRIGHTNESS_DOWN,
        )

        /** Actions that offer a secondary target choice below the action row. */
        fun hasScreenTarget(action: String): Boolean =
            isLevelAction(action) || action == SHOULDER_SCREENSHOT || action == SHOULDER_CONTROLLER_FOCUS
    }
}
