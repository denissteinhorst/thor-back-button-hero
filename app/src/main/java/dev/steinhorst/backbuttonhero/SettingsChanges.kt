package dev.steinhorst.backbuttonhero

/** A value snapshot lets the confirmation preview and the eventual write share one plan. */
data class SettingsState(
    val singleAction: String = Prefs.SINGLE_LAUNCHER,
    val doubleEnabled: Boolean = Prefs.DEFAULT_DOUBLE_TAP_ENABLED,
    val doubleMs: Long = Prefs.DEFAULT_DOUBLE_TAP_MS,
    val tapHoldEnabled: Boolean = Prefs.DEFAULT_TAP_HOLD_ENABLED,
    val tapHoldMs: Long = Prefs.DEFAULT_TAP_HOLD_MS,
    val holdEnabled: Boolean = Prefs.DEFAULT_HOLD_ENABLED,
    val holdMs: Long = Prefs.DEFAULT_HOLD_MS,
    val allApps: Boolean = Prefs.DEFAULT_SWITCHER_SHOWS_ALL_APPS,
    val controller: Boolean = Prefs.DEFAULT_SWITCHER_CONTROLLER_ENABLED,
    val toggleButtons: String = Prefs.DEFAULT_TOGGLE_BUTTONS,
    val toggleMs: Long = Prefs.DEFAULT_TOGGLE_HOLD_MS,
    val bindings: Map<String, BindingSettings> = factoryBindings(),
    val exceptions: List<AppException> = emptyList(),
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
) {
    fun screenshotTarget(): String = targetOverride ?: Prefs.DISPLAY_TOP
    fun levelTarget(): String = targetOverride ?: Prefs.TARGET_BOTH
}

private fun factoryBindings(): Map<String, BindingSettings> = buildMap {
    put(ShoulderButton.L1.prefKey, BindingSettings(Prefs.SHOULDER_FLICK_LEFT))
    put(ShoulderButton.R1.prefKey, BindingSettings(Prefs.SHOULDER_FLICK_RIGHT))
    put(ShoulderButton.L2.prefKey, BindingSettings(Prefs.SHOULDER_SCREENSHOT, targetOverride = Prefs.DISPLAY_TOP))
    put(ShoulderButton.R2.prefKey, BindingSettings(Prefs.SHOULDER_CONTROLLER_FOCUS))
    DpadDirection.entries.forEach { put(it.prefKey, BindingSettings(it.defaultAction)) }
}

enum class SettingsChangeAction { PRESET, UNBIND }

data class SettingsChangePlan(
    val before: SettingsState,
    val after: SettingsState,
    val section: SettingsSection?,
    val action: SettingsChangeAction,
) {
    val isEmpty: Boolean get() = before == after
    val removesToggle: Boolean get() = before.toggleButtons != Prefs.TOGGLE_NONE && after.toggleButtons == Prefs.TOGGLE_NONE

    companion object {
        /** A null section denotes the explicitly labelled whole-app actions. */
        fun create(before: SettingsState, section: SettingsSection?, action: SettingsChangeAction): SettingsChangePlan {
            val factory = SettingsState()
            var after = before
            val preset = action == SettingsChangeAction.PRESET
            if (section == null || section == SettingsSection.BASIC) {
                // Whole-app Unbind affects optional combinations, never primary Back gestures.
                if (preset || section != null) after = after.copy(
                    singleAction = if (preset) factory.singleAction else Prefs.SINGLE_BACK,
                    doubleEnabled = if (preset) factory.doubleEnabled else false,
                    tapHoldEnabled = if (preset) factory.tapHoldEnabled else false,
                    holdEnabled = if (preset) factory.holdEnabled else false,
                    doubleMs = if (preset) factory.doubleMs else before.doubleMs,
                    tapHoldMs = if (preset) factory.tapHoldMs else before.tapHoldMs,
                    holdMs = if (preset) factory.holdMs else before.holdMs,
                )
            }
            val buttons = when (section) {
                null -> ShoulderButton.entries + DpadDirection.entries
                SettingsSection.SHOULDERS -> ShoulderButton.entries
                SettingsSection.DPAD -> DpadDirection.entries
                else -> emptyList()
            }
            if (buttons.isNotEmpty()) after = after.copy(bindings = after.bindings.toMutableMap().apply {
                buttons.forEach { button ->
                    put(button.prefKey, if (preset) factory.bindings.getValue(button.prefKey)
                    else getValue(button.prefKey).copy(action = ""))
                }
            })
            if (section == null || section == SettingsSection.SETTINGS) after = after.copy(
                allApps = if (preset) factory.allApps else before.allApps,
                controller = if (preset) factory.controller else before.controller,
                toggleButtons = if (preset) factory.toggleButtons else Prefs.TOGGLE_NONE,
                toggleMs = if (preset) factory.toggleMs else before.toggleMs,
            )
            // Exceptions are not bindings: only factory settings, never Unbind, remove them.
            if (preset && (section == null || section == SettingsSection.EXCEPTIONS)) after = after.copy(exceptions = factory.exceptions)
            return SettingsChangePlan(before, after, section, action)
        }
    }
}
