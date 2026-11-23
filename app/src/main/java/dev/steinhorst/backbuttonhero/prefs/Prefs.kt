package dev.steinhorst.backbuttonhero.prefs

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import dev.steinhorst.backbuttonhero.actions.Ayn
import dev.steinhorst.backbuttonhero.input.AndroidButton
import dev.steinhorst.backbuttonhero.input.BackGestureButton
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.input.DpadDirection
import dev.steinhorst.backbuttonhero.input.FaceButton
import dev.steinhorst.backbuttonhero.input.GestureSettings
import dev.steinhorst.backbuttonhero.input.HomeLayerButton
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.input.Stick
import dev.steinhorst.backbuttonhero.input.StickButton
import dev.steinhorst.backbuttonhero.input.StickClickButton
import dev.steinhorst.backbuttonhero.macro.Macro
import dev.steinhorst.backbuttonhero.macro.SavedMacro
import dev.steinhorst.backbuttonhero.pad.GyroBias
import dev.steinhorst.backbuttonhero.pad.GyroCalibration
import dev.steinhorst.backbuttonhero.pad.GyroSettings

class Prefs(context: Context) : GestureSettings {
    private val sp = context.applicationContext.getSharedPreferences("back_button_hero", Context.MODE_PRIVATE)

    init {
        Migrations.run(sp)
    }

    /** Whether a gesture has an action; the setter assigns the gesture's factory action or clears it. */
    private fun gestureEnabled(gesture: BackGestureButton) = shoulderAction(gesture).isNotEmpty()
    private fun setGestureEnabled(gesture: BackGestureButton, on: Boolean) =
        setShoulderAction(gesture, if (on) gesture.earlierDefault else "")

    override var doubleTapEnabled: Boolean
        get() = gestureEnabled(BackGestureButton.DOUBLE)
        set(v) = setGestureEnabled(BackGestureButton.DOUBLE, v)

    override var doubleTapMs: Long
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

    /**
     * Whether the 2.0 welcome sheet has ended with a choice of start setup. Not a setting: backups and
     * resets leave it alone, and backing out of the sheet keeps it false so it returns next time.
     */
    var welcomeSeen: Boolean
        get() = sp.getBoolean("welcome_v2_seen", false)
        set(v) = sp.edit().putBoolean("welcome_v2_seen", v).apply()

    /** Whether the pad's Home key acts exactly like Back: every gesture, combo and exception. Excludes [dualMode]. */
    var homeAsBack: Boolean
        get() = sp.getBoolean("home_as_back", DEFAULT_HOME_AS_BACK)
        set(v) {
            val edit = sp.edit().putBoolean("home_as_back", v)
            if (v) edit.putBoolean("dual_mode", false)
            edit.apply()
        }

    /**
     * Whether the pad's Home key is a second modifier with its own bindings (docs/DUAL-MODE.md). Excludes
     * [homeAsBack]. The first switch-on copies the Back layer once; later ones find the stored Home layer.
     */
    var dualMode: Boolean
        get() = sp.getBoolean("dual_mode", DEFAULT_DUAL_MODE)
        set(v) {
            val before = snapshot()
            var after = before.copy(dualMode = v, homeAsBack = before.homeAsBack && !v)
            if (v && !before.dualSeeded) after = after.seededHomeLayer()
            applySettings(before, after)
        }

    var toggleButtons: String
        get() = sp.getString("toggle_buttons", DEFAULT_TOGGLE_BUTTONS) ?: DEFAULT_TOGGLE_BUTTONS
        set(v) = sp.edit().putString("toggle_buttons", v).apply()

    var toggleHoldMs: Long
        get() = sp.getLong("toggle_hold_ms", DEFAULT_TOGGLE_HOLD_MS).coerceIn(100L, 3000L)
        set(v) = sp.edit().putLong("toggle_hold_ms", v).apply()

    override var holdEnabled: Boolean
        get() = gestureEnabled(BackGestureButton.HOLD)
        set(v) = setGestureEnabled(BackGestureButton.HOLD, v)

    override var holdMs: Long
        get() = sp.getLong("hold_ms", DEFAULT_HOLD_MS)
        set(v) = sp.edit().putLong("hold_ms", v).apply()

    /** Tap, then hold again for [tapHoldMs] opens the top screen's Recents
     *  instead of swapping screens. Only takes effect while double-tap is enabled, since
     *  it needs the "was there a pending tap" window double-tap already tracks. */
    override var tapHoldEnabled: Boolean
        get() = gestureEnabled(BackGestureButton.TAP_HOLD)
        set(v) = setGestureEnabled(BackGestureButton.TAP_HOLD, v)

    override var tapHoldMs: Long
        get() = sp.getLong("tap_hold_ms", DEFAULT_TAP_HOLD_MS)
        set(v) = sp.edit().putLong("tap_hold_ms", v).apply()

    /** The Home layer's own gesture timings in dual mode. */
    var homeDoubleTapMs: Long
        get() = sp.getLong("${HomeLayerButton.PREFIX}double_ms", DEFAULT_DOUBLE_TAP_MS)
        set(v) = sp.edit().putLong("${HomeLayerButton.PREFIX}double_ms", v).apply()
    var homeTapHoldMs: Long
        get() = sp.getLong("${HomeLayerButton.PREFIX}tap_hold_ms", DEFAULT_TAP_HOLD_MS)
        set(v) = sp.edit().putLong("${HomeLayerButton.PREFIX}tap_hold_ms", v).apply()
    var homeHoldMs: Long
        get() = sp.getLong("${HomeLayerButton.PREFIX}hold_ms", DEFAULT_HOLD_MS)
        set(v) = sp.edit().putLong("${HomeLayerButton.PREFIX}hold_ms", v).apply()

    /** What the Home key's own gestures do in dual mode, read like Back's from this instance. */
    val homeGestures: GestureSettings = object : GestureSettings {
        override val doubleTapEnabled get() = shoulderAction(HomeLayerButton.of(BackGestureButton.DOUBLE)).isNotEmpty()
        override val doubleTapMs get() = homeDoubleTapMs
        override val tapHoldEnabled get() = shoulderAction(HomeLayerButton.of(BackGestureButton.TAP_HOLD)).isNotEmpty()
        override val tapHoldMs get() = homeTapHoldMs
        override val holdEnabled get() = shoulderAction(HomeLayerButton.of(BackGestureButton.HOLD)).isNotEmpty()
        override val holdMs get() = homeHoldMs
    }

    /** Holding Back and pressing [button] runs this action. Plain values are app package names. */
    fun shoulderAction(button: ComboButton): String =
        sp.getString("${button.prefKey}_package", button.defaultAction) ?: button.defaultAction
    fun setShoulderAction(button: ComboButton, action: String) {
        sp.edit().putString("${button.prefKey}_package", action).apply()
    }

    /** Apps starred in the app list: one set for every hotkey, offered by [SHOULDER_APP_FAVORITES]. */
    var favoriteApps: Set<String>
        get() = (sp.getStringSet("favorite_apps", null) ?: emptySet()).toSet()
        set(v) = sp.edit().putStringSet("favorite_apps", v.toSet()).apply()
    fun setFavorite(pkg: String, favorite: Boolean) {
        favoriteApps = if (favorite) favoriteApps + pkg else favoriteApps - pkg
    }

    /** [DISPLAY_TOP], [DISPLAY_BOTTOM] or [APP_SCREEN_ASK] - which screen [button]'s app opens on. */
    fun shoulderDisplay(button: ComboButton): String = sp.getString("${button.prefKey}_display", DISPLAY_BOTTOM) ?: DISPLAY_BOTTOM
    fun setShoulderDisplay(button: ComboButton, display: String) { sp.edit().putString("${button.prefKey}_display", display).apply() }

    /** [TARGET_BOTH], [DISPLAY_TOP] or [DISPLAY_BOTTOM] - which screens [button]'s volume, brightness or
     *  screenshot action applies to. Until chosen: both screens, except for the screenshot, which has
     *  always been the system's top-screen screenshot and stays that way. */
    fun levelTarget(button: ComboButton): String =
        sp.getString("${button.prefKey}_target", null)
            ?: if (shoulderAction(button) == SHOULDER_SCREENSHOT) DISPLAY_TOP else TARGET_BOTH
    fun setLevelTarget(button: ComboButton, target: String) { sp.edit().putString("${button.prefKey}_target", target).apply() }

    /** [APP_MINIMIZE], [APP_FORCE_CLOSE] or [APP_RESTART] for the active-app-control action. */
    fun appMode(button: ComboButton): String =
        sp.getString("${button.prefKey}_app_mode", APP_MINIMIZE) ?: APP_MINIMIZE
    fun setAppMode(button: ComboButton, mode: String) { sp.edit().putString("${button.prefKey}_app_mode", mode).apply() }

    /** [DISPLAY_TOP], [DISPLAY_BOTTOM], [TARGET_BOTH] or [APP_SCREEN_ASK] - whose foreground app the action hits. */
    fun appScreen(button: ComboButton): String =
        sp.getString("${button.prefKey}_app_screen", APP_SCREEN_ASK) ?: APP_SCREEN_ASK
    fun setAppScreen(button: ComboButton, screen: String) { sp.edit().putString("${button.prefKey}_app_screen", screen).apply() }

    /** Whether a force-close with a fixed screen asks first. Off by default: the action is meant to be instant. */
    fun appConfirm(button: ComboButton): Boolean = sp.getBoolean("${button.prefKey}_app_confirm", false)
    fun setAppConfirm(button: ComboButton, confirm: Boolean) { sp.edit().putBoolean("${button.prefKey}_app_confirm", confirm).apply() }

    /** The stored option of an [Ayn] hotkey, or null until chosen. */
    private fun aynOptionRaw(button: ComboButton): String? = sp.getString("${button.prefKey}_ayn_option", null)

    /** The option [button] applies for [group]; a value left over from another action counts as unchosen. */
    fun aynOption(button: ComboButton, group: Ayn.Group): String =
        aynOptionRaw(button)?.takeIf(group::isValid) ?: group.defaultOption
    fun setAynOption(button: ComboButton, option: String) { sp.edit().putString("${button.prefKey}_ayn_option", option).apply() }

    /** Whether switching the active app also sends controller input to its destination. */
    fun switchAppFollowFocus(button: ComboButton): Boolean = sp.getBoolean("${button.prefKey}_switch_app_follow", true)
    fun setSwitchAppFollowFocus(button: ComboButton, follow: Boolean) {
        sp.edit().putBoolean("${button.prefKey}_switch_app_follow", follow).apply()
    }

    /** Whether closing apps asks first. On by default: it ends several apps at once, unsaved work included. */
    fun closeConfirm(button: ComboButton): Boolean = sp.getBoolean("${button.prefKey}_close_confirm", true)
    fun setCloseConfirm(button: ComboButton, confirm: Boolean) { sp.edit().putBoolean("${button.prefKey}_close_confirm", confirm).apply() }

    /** Whether standby asks first. Off by default: it stands in for a press of the power button. */
    fun standbyConfirm(button: ComboButton): Boolean = sp.getBoolean("${button.prefKey}_standby_confirm", false)
    fun setStandbyConfirm(button: ComboButton, confirm: Boolean) { sp.edit().putBoolean("${button.prefKey}_standby_confirm", confirm).apply() }

    /** Whether a video recording also takes the microphone, mixed into the device audio. */
    fun recordMic(button: ComboButton): Boolean = sp.getBoolean("${button.prefKey}_record_mic", false)
    fun setRecordMic(button: ComboButton, on: Boolean) { sp.edit().putBoolean("${button.prefKey}_record_mic", on).apply() }

    /** Whether the microphone is only live while [recordPttKey] is held. */
    fun recordPtt(button: ComboButton): Boolean = sp.getBoolean("${button.prefKey}_record_ptt", false)
    fun setRecordPtt(button: ComboButton, on: Boolean) { sp.edit().putBoolean("${button.prefKey}_record_ptt", on).apply() }

    /** The key code of the push-to-talk button; 0 until one is chosen. */
    fun recordPttKey(button: ComboButton): Int = sp.getInt("${button.prefKey}_record_ptt_key", 0)
    fun setRecordPttKey(button: ComboButton, keyCode: Int) { sp.edit().putInt("${button.prefKey}_record_ptt_key", keyCode).apply() }

    /** [DUCK_OFF], [DUCK_VIDEO] or [DUCK_DEVICE] - where the game sound is lowered while talking. */
    fun recordDuck(button: ComboButton): String = sp.getString("${button.prefKey}_record_duck", DUCK_VIDEO) ?: DUCK_VIDEO
    fun setRecordDuck(button: ComboButton, duck: String) { sp.edit().putString("${button.prefKey}_record_duck", duck).apply() }

    /** [RECORD_SMALL], [RECORD_MEDIUM] or [RECORD_LARGE] - the video bit rate and with it the file size. */
    fun recordQuality(button: ComboButton): String =
        sp.getString("${button.prefKey}_record_quality", RECORD_MEDIUM) ?: RECORD_MEDIUM
    fun setRecordQuality(button: ComboButton, quality: String) {
        sp.edit().putString("${button.prefKey}_record_quality", quality).apply()
    }

    /** Whether a blinking dot shows while a recording runs. */
    fun recordIndicator(button: ComboButton): Boolean = sp.getBoolean("${button.prefKey}_record_indicator", true)
    fun setRecordIndicator(button: ComboButton, shown: Boolean) {
        sp.edit().putBoolean("${button.prefKey}_record_indicator", shown).apply()
    }

    /** Where the recording dot appears, on the controller-focus pictogram's 3x3 grid. */
    fun recordPosition(button: ComboButton): String =
        sp.getString("${button.prefKey}_record_position", DEFAULT_RECORD_POSITION) ?: DEFAULT_RECORD_POSITION
    fun setRecordPosition(button: ComboButton, position: String) {
        sp.edit().putString("${button.prefKey}_record_position", position).apply()
    }

    /** The gyro setup of [button] for [SHOULDER_GYRO]. */
    fun gyro(button: ComboButton): GyroSettings = GyroSettings.decode(sp.getString("${button.prefKey}_gyro", null))
    fun setGyro(button: ComboButton, settings: GyroSettings) {
        val key = "${button.prefKey}_gyro"
        // Defaults are stored as nothing, the same way a backup reads them back.
        sp.edit().apply { if (settings == GyroSettings()) remove(key) else putString(key, settings.encode()) }.apply()
    }

    /** Legacy offset retained for compatibility; live steering uses the quality-checked profile below. */
    var gyroBias: FloatArray
        get() = GyroBias.decode(sp.getString("gyro_bias", null))
        set(v) = sp.edit().putString("gyro_bias", GyroBias.encode(v)).apply()

    /** Versioned quality-checked calibration; legacy offsets lack the measurements needed to trust them. */
    var gyroCalibration: GyroCalibration?
        get() = GyroCalibration.decode(sp.getString("gyro_calibration_v1", null))
        set(value) = sp.edit().putString("gyro_calibration_v1", value?.encode()).apply()

    /** Whether any hotkey turns the gyro on - the helper must then already hold the pad, so turning
     *  it on never recreates the controller under a running game. */
    fun hasGyroAction(): Boolean = withHomeLayer(ALL_BUTTONS).any { shoulderAction(it) == SHOULDER_GYRO }

    private val macros = MacroStore(sp)

    /** The named macros, in the order they were first saved. Hotkeys refer to them by id. */
    var macroLibrary: List<SavedMacro> by macros::library

    /** The library macro [button] plays for [SHOULDER_MACRO]; null until one is chosen or recorded. */
    fun savedMacro(button: ComboButton): SavedMacro? = macros.saved(button)
    fun macro(button: ComboButton): Macro? = savedMacro(button)?.macro
    fun setMacroRef(button: ComboButton, id: String?) = macros.setRef(button, id)

    /** Stores [macro] as [name] under [id], or as a new library entry when [id] is null or unknown. */
    fun saveMacro(id: String?, name: String, macro: Macro): SavedMacro = macros.save(id, name, macro)

    fun renameMacro(id: String, name: String) = macros.rename(id, name)

    /** The hotkeys that play library macro [id]. */
    fun macroUsers(id: String): List<ComboButton> = macros.users(id)

    /** Removes macro [id] from the library and from every hotkey that played it, in one write. */
    fun deleteMacro(id: String) = macros.delete(id)

    /** Whether any hotkey plays a recorded macro - only then must the helper hold the pad for it. */
    fun hasMacroAction(): Boolean = withHomeLayer(MACRO_BUTTONS).any { shoulderAction(it) == SHOULDER_MACRO && macro(it) != null }

    /** Each screen's volume before a mute toggle set it to 0; 0 when none is stored. */
    var topVolumeBeforeMute: Int
        get() = sp.getInt("top_volume_before_mute", 0)
        set(v) = sp.edit().putInt("top_volume_before_mute", v).apply()
    var bottomVolumeBeforeMute: Int
        get() = sp.getInt("bottom_volume_before_mute", 0)
        set(v) = sp.edit().putInt("bottom_volume_before_mute", v).apply()

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
    fun hasDpadAction(): Boolean = withHomeLayer(DpadDirection.entries).any { shoulderAction(it).isNotEmpty() }

    /** Whether [stick] (either one when null) has an action - only then are its axes worth intercepting. */
    fun hasStickAction(stick: Stick? = null): Boolean =
        withHomeLayer(StickButton.entries.filter { stick == null || it.stick == stick }).any { shoulderAction(it).isNotEmpty() }

    /** Whether [stick]'s click gestures have any action - only then is its L3/R3 press ours. */
    fun hasStickClickAction(stick: Stick): Boolean =
        withHomeLayer(StickClickButton.entries.filter { it.stick == stick }).any { shoulderAction(it).isNotEmpty() }

    /** The Home layer counts only in dual mode; otherwise its stored bindings rest. */
    private fun withHomeLayer(buttons: List<ComboButton>): List<ComboButton> =
        if (dualMode) buttons + buttons.filter { it in HOME_LAYER_BASES }.map(HomeLayerButton::of) else buttons

    private fun stickPrefix(stick: Stick, home: Boolean) = (if (home) HomeLayerButton.PREFIX else "") + stick.prefix

    /** Longest pause between the two presses of a stick's double press; [home] reads the Home layer's. */
    fun stickDoubleMs(stick: Stick, home: Boolean = false): Long =
        sp.getLong("${stickPrefix(stick, home)}_double_ms", DEFAULT_DOUBLE_TAP_MS).coerceIn(150L, 600L)
    fun setStickDoubleMs(stick: Stick, ms: Long, home: Boolean = false) {
        sp.edit().putLong("${stickPrefix(stick, home)}_double_ms", ms).apply()
    }

    /** How long a stick has to be held in after its first press for the tap-and-hold gesture. */
    fun stickTapHoldMs(stick: Stick, home: Boolean = false): Long =
        sp.getLong("${stickPrefix(stick, home)}_tap_hold_ms", DEFAULT_TAP_HOLD_MS).coerceIn(500L, 2000L)
    fun setStickTapHoldMs(stick: Stick, ms: Long, home: Boolean = false) {
        sp.edit().putLong("${stickPrefix(stick, home)}_tap_hold_ms", ms).apply()
    }

    /** Per-app opt-outs in the order the user added them. */
    var appExceptions: List<AppException>
        get() = AppException.decode(sp.getString("app_exceptions", null))
        set(v) = sp.edit().putString("app_exceptions", AppException.encode(v)).apply()

    /** The exception governing [packageName], or null when it has none or it is switched off. */
    fun appException(packageName: String?): AppException? =
        packageName?.let { pkg -> appExceptions.firstOrNull { it.packageName == pkg && it.active } }

    // The library is read once: every binding checks its macro against the same list.
    fun snapshot(): SettingsState = snapshot(macroLibrary)

    private fun snapshot(library: List<SavedMacro>): SettingsState = SettingsState(
        doubleTapMs, tapHoldMs, holdMs, switcherShowsAllApps, switcherControllerEnabled, toggleButtons, toggleHoldMs,
        STORED_BUTTONS.associate { button ->
            button.prefKey to BindingSettings(
                shoulderAction(button), shoulderDisplay(button), sp.getString("${button.prefKey}_target", null),
                focusTarget(button), focusIndicator(button), focusDuration(button), focusPosition(button),
                appMode(button), appScreen(button), appConfirm(button), aynOptionRaw(button), switchAppFollowFocus(button),
                closeConfirm(button), standbyConfirm(button),
                recordMic(button), recordQuality(button), recordIndicator(button), recordPosition(button),
                recordPtt(button), recordPttKey(button), recordDuck(button),
                sp.getString("${button.prefKey}_macro", null)?.takeIf { id -> library.any { it.id == id } },
                sp.getString("${button.prefKey}_gyro", null),
            )
        },
        appExceptions,
        Stick.entries.associate { it.prefix to StickTimings(stickDoubleMs(it), stickTapHoldMs(it)) },
        homeAsBack = homeAsBack,
        macros = library,
        dualMode = dualMode,
        dualSeeded = sp.getBoolean("dual_seeded", false),
        homeDoubleMs = homeDoubleTapMs,
        homeTapHoldMs = homeTapHoldMs,
        homeHoldMs = homeHoldMs,
        homeStickTimings = Stick.entries.associate { it.prefix to StickTimings(stickDoubleMs(it, true), stickTapHoldMs(it, true)) },
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
        fun int(key: String, old: Int, value: Int) {
            if (old != value) edit.putInt(key, value)
        }
        long("double_ms", before.doubleMs, after.doubleMs)
        long("tap_hold_ms", before.tapHoldMs, after.tapHoldMs)
        long("hold_ms", before.holdMs, after.holdMs)
        boolean("switcher_shows_all_apps", before.allApps, after.allApps)
        boolean("switcher_controller_enabled", before.controller, after.controller)
        boolean("home_as_back", before.homeAsBack, after.homeAsBack)
        boolean("dual_mode", before.dualMode, after.dualMode)
        boolean("dual_seeded", before.dualSeeded, after.dualSeeded)
        long("${HomeLayerButton.PREFIX}double_ms", before.homeDoubleMs, after.homeDoubleMs)
        long("${HomeLayerButton.PREFIX}tap_hold_ms", before.homeTapHoldMs, after.homeTapHoldMs)
        long("${HomeLayerButton.PREFIX}hold_ms", before.homeHoldMs, after.homeHoldMs)
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
            string("${key}_app_mode", old.appMode, value.appMode)
            string("${key}_app_screen", old.appScreen, value.appScreen)
            boolean("${key}_app_confirm", old.appConfirm, value.appConfirm)
            string("${key}_ayn_option", old.aynOption, value.aynOption)
            boolean("${key}_switch_app_follow", old.switchAppFollow, value.switchAppFollow)
            boolean("${key}_close_confirm", old.closeConfirm, value.closeConfirm)
            boolean("${key}_standby_confirm", old.standbyConfirm, value.standbyConfirm)
            boolean("${key}_record_mic", old.recordMic, value.recordMic)
            string("${key}_record_quality", old.recordQuality, value.recordQuality)
            boolean("${key}_record_indicator", old.recordIndicator, value.recordIndicator)
            string("${key}_record_position", old.recordPosition, value.recordPosition)
            boolean("${key}_record_ptt", old.recordPtt, value.recordPtt)
            int("${key}_record_ptt_key", old.recordPttKey, value.recordPttKey)
            string("${key}_record_duck", old.recordDuck, value.recordDuck)
            string("${key}_macro", old.macro, value.macro)
            string("${key}_gyro", old.gyro, value.gyro)
        }
        before.stickTimings.forEach { (prefix, old) ->
            val value = after.stickTimings.getValue(prefix)
            long("${prefix}_double_ms", old.doubleMs, value.doubleMs)
            long("${prefix}_tap_hold_ms", old.tapHoldMs, value.tapHoldMs)
        }
        before.homeStickTimings.forEach { (prefix, old) ->
            val value = after.homeStickTimings.getValue(prefix)
            long("${HomeLayerButton.PREFIX}${prefix}_double_ms", old.doubleMs, value.doubleMs)
            long("${HomeLayerButton.PREFIX}${prefix}_tap_hold_ms", old.tapHoldMs, value.tapHoldMs)
        }
        if (before.exceptions != after.exceptions) edit.putString("app_exceptions", AppException.encode(after.exceptions))
        if (before.macros != after.macros) edit.putString("macro_library", SavedMacro.encode(after.macros))
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

    companion object {
        const val TOGGLE_NONE = ""
        const val TOGGLE_BOTH = "both"
        const val TOGGLE_L1_R1 = "l1_r1"
        const val TOGGLE_L2_R2 = "l2_r2"
        const val TOGGLE_START_SELECT = "start_select"
        const val TOGGLE_BACK_HOME = "back_home"
        const val DEFAULT_TOGGLE_BUTTONS = TOGGLE_NONE
        const val DEFAULT_TOGGLE_HOLD_MS = 300L
        const val DEFAULT_DOUBLE_TAP_ENABLED = true
        const val DEFAULT_DOUBLE_TAP_MS = 300L
        const val DEFAULT_SWITCHER_SHOWS_ALL_APPS = false
        const val DEFAULT_SWITCHER_CONTROLLER_ENABLED = true
        const val DEFAULT_HOME_AS_BACK = false
        const val DEFAULT_DUAL_MODE = false
        const val DEFAULT_TAP_HOLD_ENABLED = true
        const val DEFAULT_TAP_HOLD_MS = 1000L
        const val DEFAULT_HOLD_ENABLED = true
        const val DEFAULT_HOLD_MS = 1000L
        const val DISPLAY_TOP = "top"
        const val DISPLAY_BOTTOM = "bottom"
        const val TARGET_BOTH = "both"
        const val FOCUS_TOGGLE = "toggle"
        /** Asks which screen to focus instead of choosing it in advance. */
        const val FOCUS_MODAL = "modal"
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
        /** Opens a list of every app when pressed and launches the one chosen. */
        const val SHOULDER_APP_ASK = "@app_ask"
        /** Like [SHOULDER_APP_ASK], limited to [favoriteApps]. */
        const val SHOULDER_APP_FAVORITES = "@app_favorites"
        const val SHOULDER_BACK = "@back"
        const val SHOULDER_SWITCHER = "@switcher"
        const val SHOULDER_SWAP_APPS = "@swap_apps"
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
        const val SHOULDER_SWITCH_APP = "@switch_active_app"
        const val SHOULDER_LAST_APP = "@last_app"
        const val SHOULDER_APP_CONTROL = "@app_control"
        const val APP_MINIMIZE = "minimize"
        const val APP_FORCE_CLOSE = "force_close"
        const val APP_RESTART = "restart"
        const val APP_SCREEN_ASK = "ask"
        const val SHOULDER_CLOSE_APPS = "@close_apps"
        const val SHOULDER_CLOSE_BACKGROUND_APPS = "@close_background_apps"
        const val SHOULDER_POWER_MENU = "@power_menu"
        const val SHOULDER_STANDBY = "@standby"
        const val SHOULDER_VOLUME_MUTE = "@volume_mute"
        const val SHOULDER_RECORD_VIDEO = "@record_video"
        const val SHOULDER_MACRO = "@macro"
        const val SHOULDER_MEDIA_PLAY_PAUSE = "@media_play_pause"
        const val SHOULDER_MEDIA_NEXT = "@media_next"
        const val SHOULDER_MEDIA_PREVIOUS = "@media_previous"
        const val SHOULDER_STATUS = "@status_overlay"
        const val SHOULDER_GYRO = "@gyro"
        const val SHOULDER_GYRO_CALIBRATE = "@gyro_calibrate"
        const val RECORD_SMALL = "small"
        const val RECORD_MEDIUM = "medium"
        const val RECORD_LARGE = "large"
        const val DEFAULT_RECORD_POSITION = FOCUS_POS_TOP_RIGHT
        const val DUCK_OFF = "off"
        const val DUCK_VIDEO = "video"
        const val DUCK_DEVICE = "device"

        /** Every `@` action this build knows; a backup naming anything else is not applied. */
        val KNOWN_ACTIONS: Set<String> = setOf(
            SHOULDER_FLICK_LEFT, SHOULDER_FLICK_RIGHT, SHOULDER_HOME, SHOULDER_BACK, SHOULDER_SWITCHER, SHOULDER_SWAP_APPS,
            SHOULDER_RECENTS, SHOULDER_SCREENSHOT, SHOULDER_QUICK_SETTINGS, SHOULDER_NOTIFICATIONS, SHOULDER_INTERNET,
            SHOULDER_BLUETOOTH, SHOULDER_NIGHT_LIGHT, SHOULDER_EXTRA_DIM, SHOULDER_DISPLAY_SETTINGS, SHOULDER_SOUND_SETTINGS,
            SHOULDER_LOCATION_SETTINGS, SHOULDER_CAST_SETTINGS, SHOULDER_DND_SETTINGS, SHOULDER_BATTERY_SAVER,
            SHOULDER_VOLUME_UP, SHOULDER_VOLUME_DOWN, SHOULDER_BRIGHTNESS_UP, SHOULDER_BRIGHTNESS_DOWN,
            SHOULDER_CONTROLLER_FOCUS, SHOULDER_SWITCH_APP, SHOULDER_APP_CONTROL,
            SHOULDER_CLOSE_APPS, SHOULDER_CLOSE_BACKGROUND_APPS, SHOULDER_POWER_MENU, SHOULDER_STANDBY, SHOULDER_VOLUME_MUTE,
            SHOULDER_RECORD_VIDEO, SHOULDER_MACRO, SHOULDER_MEDIA_PLAY_PAUSE, SHOULDER_MEDIA_NEXT, SHOULDER_MEDIA_PREVIOUS,
            SHOULDER_STATUS, SHOULDER_LAST_APP, SHOULDER_GYRO,
            SHOULDER_GYRO_CALIBRATE, SHOULDER_APP_ASK, SHOULDER_APP_FAVORITES,
        ) + Ayn.ACTIONS

        /** Every hotkey that can play a macro: all combos, but not Back's own gestures. */
        val MACRO_BUTTONS: List<ComboButton> by lazy {
            ShoulderButton.entries + DpadDirection.entries + StickButton.entries + StickClickButton.entries +
                AndroidButton.entries + FaceButton.entries
        }

        /** Every hotkey, Back's own gestures included. */
        private val ALL_BUTTONS: List<ComboButton> by lazy { MACRO_BUTTONS + BackGestureButton.entries }

        /** The Back layer bindings the Home layer has too: all but Back + Home, which Home + Home cannot mirror. */
        val HOME_LAYER_BASES: List<ComboButton> by lazy { ALL_BUTTONS - AndroidButton.HOME }

        /** The Home layer, in the Back layer's order. */
        val HOME_LAYER_BUTTONS: List<ComboButton> by lazy { HOME_LAYER_BASES.map(HomeLayerButton::of) }

        /** Every stored binding of both layers, Back's first. */
        val STORED_BUTTONS: List<ComboButton> by lazy {
            ShoulderButton.entries + DpadDirection.entries + StickButton.entries + StickClickButton.entries + BackGestureButton.entries +
                AndroidButton.entries + FaceButton.entries + HOME_LAYER_BUTTONS
        }

        /** Every hotkey of either layer that can play a macro. */
        val ALL_MACRO_BUTTONS: List<ComboButton> by lazy { MACRO_BUTTONS + MACRO_BUTTONS.filter { it in HOME_LAYER_BASES }.map(HomeLayerButton::of) }

        fun isShoulderApp(action: String): Boolean = action.isNotEmpty() && !action.startsWith('@')

        /** A fixed app or one chosen when pressed; either opens on [shoulderDisplay]. */
        fun launchesApp(action: String): Boolean =
            isShoulderApp(action) || action == SHOULDER_APP_ASK || action == SHOULDER_APP_FAVORITES

        /** Volume and brightness actions, which apply to [levelTarget] rather than one launch screen. */
        fun isLevelAction(action: String): Boolean = action in setOf(
            SHOULDER_VOLUME_UP, SHOULDER_VOLUME_DOWN, SHOULDER_BRIGHTNESS_UP, SHOULDER_BRIGHTNESS_DOWN,
        )

        /** Actions that offer a secondary target choice below the action row. */
        fun hasScreenTarget(action: String): Boolean =
            isLevelAction(action) || action == SHOULDER_SCREENSHOT || action == SHOULDER_CONTROLLER_FOCUS ||
                isCloseApps(action) || action == SHOULDER_VOLUME_MUTE || action == SHOULDER_RECORD_VIDEO

        fun isCloseApps(action: String): Boolean = action == SHOULDER_CLOSE_APPS || action == SHOULDER_CLOSE_BACKGROUND_APPS
    }
}
