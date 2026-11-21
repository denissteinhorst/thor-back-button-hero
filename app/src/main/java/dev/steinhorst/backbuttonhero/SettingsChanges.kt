package dev.steinhorst.backbuttonhero

/** A value snapshot lets the confirmation preview and the eventual write share one plan. */
data class SettingsState(
    val doubleMs: Long = Prefs.DEFAULT_DOUBLE_TAP_MS,
    val tapHoldMs: Long = Prefs.DEFAULT_TAP_HOLD_MS,
    val holdMs: Long = Prefs.DEFAULT_HOLD_MS,
    val allApps: Boolean = Prefs.DEFAULT_SWITCHER_SHOWS_ALL_APPS,
    val controller: Boolean = Prefs.DEFAULT_SWITCHER_CONTROLLER_ENABLED,
    val toggleButtons: String = Prefs.DEFAULT_TOGGLE_BUTTONS,
    val toggleMs: Long = Prefs.DEFAULT_TOGGLE_HOLD_MS,
    val bindings: Map<String, BindingSettings> = factoryBindings(),
    val exceptions: List<AppException> = emptyList(),
    /** Per stick (keyed by [Stick.prefix]): its double-press and hold times. */
    val stickTimings: Map<String, StickTimings> = Stick.entries.associate { it.prefix to StickTimings() },
    val homeAsBack: Boolean = Prefs.DEFAULT_HOME_AS_BACK,
    /** The macro library; resets and unbinding leave it alone, only hotkeys lose their macro. */
    val macros: List<SavedMacro> = emptyList(),
    /** Dual mode (docs/DUAL-MODE.md): the Home layer's bindings live in [bindings] under [HomeLayerButton.PREFIX]. */
    val dualMode: Boolean = Prefs.DEFAULT_DUAL_MODE,
    /** Whether the Home layer already got its one-time copy of the Back layer. */
    val dualSeeded: Boolean = false,
    val homeDoubleMs: Long = Prefs.DEFAULT_DOUBLE_TAP_MS,
    val homeTapHoldMs: Long = Prefs.DEFAULT_TAP_HOLD_MS,
    val homeHoldMs: Long = Prefs.DEFAULT_HOLD_MS,
    val homeStickTimings: Map<String, StickTimings> = Stick.entries.associate { it.prefix to StickTimings() },
) {
    /**
     * The Home layer as dual mode's first switch-on creates it: a copy of the Back layer, timings
     * included, except that a single tap on Home stays Home.
     */
    fun seededHomeLayer(): SettingsState = copy(
        bindings = bindings.toMutableMap().apply {
            Prefs.HOME_LAYER_BASES.forEach { base ->
                put(HomeLayerButton.of(base).prefKey,
                    if (base == BackGestureButton.SINGLE) HOME_SINGLE_TAP else getValue(base.prefKey))
            }
        },
        homeDoubleMs = doubleMs,
        homeTapHoldMs = tapHoldMs,
        homeHoldMs = holdMs,
        homeStickTimings = stickTimings,
        dualSeeded = true,
    )

    companion object {
        /** Home's single tap in a freshly copied Home layer: what the key did before. */
        val HOME_SINGLE_TAP = BindingSettings(Prefs.SHOULDER_HOME, display = Prefs.DISPLAY_TOP)
    }
}

/** The binding layers an Unbind or reset can reach in dual mode. */
enum class BindingLayer { HOME, BACK }

data class StickTimings(
    val doubleMs: Long = Prefs.DEFAULT_DOUBLE_TAP_MS,
    val tapHoldMs: Long = Prefs.DEFAULT_TAP_HOLD_MS,
)

data class BindingSettings(
    val action: String,
    val display: String = Prefs.DISPLAY_BOTTOM,
    // Absence has two meanings: top for screenshots, both for volume/brightness.
    val targetOverride: String? = null,
    val focusTarget: String = Prefs.FOCUS_TOGGLE,
    val indicator: Boolean = true,
    val duration: Long = Prefs.DEFAULT_FOCUS_DURATION_MS,
    val position: String = Prefs.DEFAULT_FOCUS_POSITION,
    val appMode: String = Prefs.APP_MINIMIZE,
    val appScreen: String = Prefs.APP_SCREEN_ASK,
    val appConfirm: Boolean = false,
    /** The choice of an [Ayn] hotkey; null until made. */
    val aynOption: String? = null,
    val switchAppFollow: Boolean = true,
    val closeConfirm: Boolean = true,
    val standbyConfirm: Boolean = false,
    val recordMic: Boolean = false,
    val recordQuality: String = Prefs.RECORD_MEDIUM,
    val recordIndicator: Boolean = true,
    val recordPosition: String = Prefs.DEFAULT_RECORD_POSITION,
    val recordPtt: Boolean = false,
    val recordPttKey: Int = 0,
    val recordDuck: String = Prefs.DUCK_VIDEO,
    /** The [SavedMacro.id] of the library macro it plays; null until one is chosen. */
    val macro: String? = null,
    /** The stored [GyroSettings] line; null until the gyro setup is changed. */
    val gyro: String? = null,
) {
    fun screenshotTarget(): String = targetOverride ?: Prefs.DISPLAY_TOP
    fun levelTarget(): String = targetOverride ?: Prefs.TARGET_BOTH
}

private fun factoryBindings(): Map<String, BindingSettings> = buildMap {
    ShoulderButton.entries.forEach { put(it.prefKey, BindingSettings(it.defaultAction)) }
    DpadDirection.entries.forEach { put(it.prefKey, BindingSettings(it.defaultAction)) }
    StickButton.entries.forEach { put(it.prefKey, BindingSettings(it.defaultAction)) }
    StickClickButton.entries.forEach { put(it.prefKey, BindingSettings(it.defaultAction)) }
    BackGestureButton.entries.forEach { put(it.prefKey, BindingSettings(it.defaultAction)) }
    AndroidButton.entries.forEach { put(it.prefKey, BindingSettings(it.defaultAction)) }
    FaceButton.entries.forEach { put(it.prefKey, BindingSettings(it.defaultAction)) }
    Prefs.HOME_LAYER_BUTTONS.forEach { put(it.prefKey, BindingSettings(it.defaultAction)) }
}

/** [DEFAULTS] loads the welcome sheet's defaults ([startSetup]); [PRESET] the empty factory settings. */
enum class SettingsChangeAction { PRESET, UNBIND, RESTORE, DEFAULTS }

data class SettingsChangePlan(
    val before: SettingsState,
    val after: SettingsState,
    val section: SettingsSection?,
    val action: SettingsChangeAction,
    val layers: Set<BindingLayer> = setOf(BindingLayer.BACK),
) {
    val isEmpty: Boolean get() = before == after
    val removesToggle: Boolean get() = before.toggleButtons != Prefs.TOGGLE_NONE && after.toggleButtons == Prefs.TOGGLE_NONE

    companion object {
        /** Whether [section]'s Unbind or reset has bindings in both layers, so dual mode asks which. */
        fun hasLayers(section: SettingsSection?): Boolean = section == null || section in setOf(
            SettingsSection.BASIC, SettingsSection.FACE, SettingsSection.SHOULDERS, SettingsSection.DPAD, SettingsSection.STICKS,
        )

        /**
         * A null section denotes the explicitly labelled whole-app actions. [layers] are the binding
         * layers it reaches; outside dual mode only Back's, so a resting Home layer stays as it is.
         */
        fun create(
            before: SettingsState,
            section: SettingsSection?,
            action: SettingsChangeAction,
            layers: Set<BindingLayer> = setOf(BindingLayer.BACK),
        ): SettingsChangePlan {
            if (action == SettingsChangeAction.DEFAULTS) {
                return SettingsChangePlan(before, startSetup(before, StartSetup.DEFAULTS), null, action, layers)
            }
            val factory = SettingsState()
            var after = before
            val preset = action == SettingsChangeAction.PRESET
            val back = BindingLayer.BACK in layers
            val home = BindingLayer.HOME in layers
            fun layered(buttons: List<ComboButton>): List<ComboButton> =
                (if (back) buttons else emptyList()) +
                    (if (home) buttons.filter { it in Prefs.HOME_LAYER_BASES }.map(HomeLayerButton::of) else emptyList())
            fun cleared(buttons: List<ComboButton>) = after.bindings.toMutableMap().apply {
                buttons.forEach { button ->
                    put(button.prefKey, if (preset) factory.bindings.getValue(button.prefKey)
                    else getValue(button.prefKey).copy(action = ""))
                }
            }
            if (section == null || section == SettingsSection.BASIC) {
                // Whole-app Unbind affects optional combinations, never primary Back gestures.
                if (preset || section != null) after = after.copy(
                    bindings = cleared(layered(BackGestureButton.entries)),
                    doubleMs = if (preset && back) factory.doubleMs else before.doubleMs,
                    tapHoldMs = if (preset && back) factory.tapHoldMs else before.tapHoldMs,
                    holdMs = if (preset && back) factory.holdMs else before.holdMs,
                    homeDoubleMs = if (preset && home) factory.homeDoubleMs else before.homeDoubleMs,
                    homeTapHoldMs = if (preset && home) factory.homeTapHoldMs else before.homeTapHoldMs,
                    homeHoldMs = if (preset && home) factory.homeHoldMs else before.homeHoldMs,
                )
            }
            val buttons = when (section) {
                null -> AndroidButton.entries + FaceButton.entries + ShoulderButton.entries + DpadDirection.entries +
                    StickButton.entries + StickClickButton.entries
                SettingsSection.BASIC -> AndroidButton.entries
                SettingsSection.FACE -> FaceButton.entries
                SettingsSection.SHOULDERS -> ShoulderButton.entries
                SettingsSection.DPAD -> DpadDirection.entries
                SettingsSection.STICKS -> StickButton.entries + StickClickButton.entries
                else -> emptyList()
            }
            if (buttons.isNotEmpty()) after = after.copy(bindings = cleared(layered(buttons)))
            // Timings belong to the stick click gestures; Unbind keeps them like the Back timings.
            if (preset && (section == null || section == SettingsSection.STICKS)) after = after.copy(
                stickTimings = if (back) factory.stickTimings else after.stickTimings,
                homeStickTimings = if (home) factory.homeStickTimings else after.homeStickTimings,
            )
            if (section == null || section == SettingsSection.SETTINGS) after = after.copy(
                allApps = if (preset) factory.allApps else before.allApps,
                controller = if (preset) factory.controller else before.controller,
                homeAsBack = if (preset) factory.homeAsBack else before.homeAsBack,
                toggleButtons = if (preset) factory.toggleButtons else Prefs.TOGGLE_NONE,
                toggleMs = if (preset) factory.toggleMs else before.toggleMs,
            )
            // The whole-app factory reset leaves no dual mode behind: off, Home layer cleared and unseeded.
            if (preset && section == null) after = after.copy(
                dualMode = factory.dualMode,
                dualSeeded = factory.dualSeeded,
                bindings = cleared(Prefs.HOME_LAYER_BUTTONS),
                homeDoubleMs = factory.homeDoubleMs,
                homeTapHoldMs = factory.homeTapHoldMs,
                homeHoldMs = factory.homeHoldMs,
                homeStickTimings = factory.homeStickTimings,
            )
            // Exceptions are not bindings: only factory settings, never Unbind, remove them.
            if (preset && (section == null || section == SettingsSection.EXCEPTIONS)) after = after.copy(exceptions = factory.exceptions)
            return SettingsChangePlan(before, after, section, action, layers)
        }
    }
}
