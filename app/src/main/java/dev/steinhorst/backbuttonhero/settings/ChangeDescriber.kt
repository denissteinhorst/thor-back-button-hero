package dev.steinhorst.backbuttonhero.settings

import android.content.Context
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.actions.ActionGroups
import dev.steinhorst.backbuttonhero.actions.Ayn
import dev.steinhorst.backbuttonhero.input.AndroidButton
import dev.steinhorst.backbuttonhero.input.BackGestureButton
import dev.steinhorst.backbuttonhero.input.DpadDirection
import dev.steinhorst.backbuttonhero.input.FaceButton
import dev.steinhorst.backbuttonhero.input.HomeLayerButton
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.input.Stick
import dev.steinhorst.backbuttonhero.input.StickButton
import dev.steinhorst.backbuttonhero.input.StickClickButton
import dev.steinhorst.backbuttonhero.macro.SavedMacro
import dev.steinhorst.backbuttonhero.pad.GyroSettings
import dev.steinhorst.backbuttonhero.prefs.AppException
import dev.steinhorst.backbuttonhero.prefs.BindingSettings
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.prefs.SettingsChangePlan

/**
 * The lines of a change preview: one "label: old → new" line per setting a [SettingsChangePlan]
 * changes, in the order the pages show them, worded like the rows through [describer].
 */
internal class ChangeDescriber(private val context: Context, private val describer: BindingDescriber) {
    fun lines(plan: SettingsChangePlan): List<String> = buildList {
        fun addChange(label: String, old: String, new: String) {
            if (old != new) add(context.getString(R.string.settings_change_line, label, old, new))
        }
        fun onOff(value: Boolean) = context.getString(if (value) R.string.settings_on else R.string.settings_off)
        fun ms(value: Long) = context.getString(R.string.focus_duration, value)
        fun target(value: String) = describer.levelTargets().first { it.value == value }.label
        fun focus(value: String) = describer.focusTargets().first { it.value == value }.label
        fun appMode(value: String) = describer.appModes().first { it.value == value }.label
        fun appScreen(value: String) = describer.appScreens().first { it.value == value }.label
        fun position(value: String) = describer.focusPositions().first { it.value == value }.label
        fun quality(value: String) = describer.recordQualities().first { it.value == value }.label
        fun duck(value: String) = describer.recordDucks().first { it.value == value }.label
        fun aynOption(value: BindingSettings): String {
            val group = checkNotNull(Ayn.groupOf(value.action))
            val chosen = value.aynOption?.takeIf(group::isValid) ?: group.defaultOption
            return describer.aynOptions(group).first { it.value == chosen }.label
        }
        fun launches(value: BindingSettings) = Prefs.launchesApp(value.action) || value.action == Prefs.SHOULDER_HOME
        fun binding(value: BindingSettings): String {
            val detail = when {
                launches(value) -> describer.launchScreens().first { it.value == value.display }.label
                value.action == Prefs.SHOULDER_SCREENSHOT -> target(value.screenshotTarget())
                Prefs.isLevelAction(value.action) || value.action == Prefs.SHOULDER_RECORD_VIDEO -> target(value.levelTarget())
                value.action == Prefs.SHOULDER_CONTROLLER_FOCUS -> focus(value.focusTarget)
                value.action == Prefs.SHOULDER_APP_CONTROL -> "${appMode(value.appMode)}, ${appScreen(value.appScreen)}"
                Prefs.isCloseApps(value.action) || value.action == Prefs.SHOULDER_VOLUME_MUTE ->
                    "${context.getString(checkNotNull(ActionGroups.groupOf(value.action)?.option(value.action)).labelRes)}, ${target(value.levelTarget())}"
                Ayn.groupOf(value.action) != null -> aynOption(value)
                ActionGroups.groupOf(value.action) != null -> return context.getString(
                    R.string.settings_action_detail, describer.describeAction(value.action),
                    context.getString(checkNotNull(ActionGroups.groupOf(value.action)?.option(value.action)).labelRes),
                )
                else -> return describer.describeAction(value.action)
            }
            return context.getString(R.string.settings_action_detail, describer.describeAction(value.action), detail)
        }
        val a = plan.before
        val b = plan.after
        addChange(context.getString(R.string.settings_double_time), ms(a.doubleMs), ms(b.doubleMs))
        addChange(context.getString(R.string.settings_tap_hold_time), ms(a.tapHoldMs), ms(b.tapHoldMs))
        addChange(context.getString(R.string.settings_hold_time), ms(a.holdMs), ms(b.holdMs))
        addChange(context.getString(R.string.switcher_all_apps), onOff(a.allApps), onOff(b.allApps))
        addChange(context.getString(R.string.switcher_controller), onOff(a.controller), onOff(b.controller))
        addChange(context.getString(R.string.home_as_back), onOff(a.homeAsBack), onOff(b.homeAsBack))
        addChange(context.getString(R.string.dual_mode), onOff(a.dualMode), onOff(b.dualMode))
        val homeSide = context.getString(R.string.dual_column_home)
        fun homeTiming(res: Int, old: Long, new: Long) =
            addChange(context.getString(R.string.settings_property_label, homeSide, context.getString(res)), ms(old), ms(new))
        homeTiming(R.string.settings_double_time, a.homeDoubleMs, b.homeDoubleMs)
        homeTiming(R.string.settings_tap_hold_time, a.homeTapHoldMs, b.homeTapHoldMs)
        homeTiming(R.string.settings_hold_time, a.homeHoldMs, b.homeHoldMs)
        addChange(context.getString(R.string.service_toggle_buttons), describer.toggleLabel(a.toggleButtons), describer.toggleLabel(b.toggleButtons))
        addChange(context.getString(R.string.settings_toggle_time), ms(a.toggleMs), ms(b.toggleMs))
        fun apps(value: List<AppException>) = value.joinToString(", ") { it.name }.ifEmpty { context.getString(R.string.exceptions_none) }
        addChange(context.getString(R.string.exceptions_apps_title), apps(a.exceptions), apps(b.exceptions))
        fun macros(value: List<SavedMacro>) = value.joinToString(", ") { it.name }.ifEmpty { context.getString(R.string.macro_library_empty) }
        addChange(context.getString(R.string.macro_library_title), macros(a.macros), macros(b.macros))
        val buttons = listOf(
            BackGestureButton.SINGLE to R.string.back_single_row, BackGestureButton.DOUBLE to R.string.back_double_row,
            BackGestureButton.TAP_HOLD to R.string.back_tap_hold_row, BackGestureButton.HOLD to R.string.back_hold_row,
            AndroidButton.HOME to R.string.button_home, AndroidButton.START to R.string.button_start,
            AndroidButton.SELECT to R.string.button_select,
            FaceButton.A to R.string.button_a, FaceButton.B to R.string.button_b,
            FaceButton.X to R.string.button_x, FaceButton.Y to R.string.button_y,
            ShoulderButton.L1 to R.string.shoulder_l1, ShoulderButton.R1 to R.string.shoulder_r1,
            ShoulderButton.L2 to R.string.shoulder_l2, ShoulderButton.R2 to R.string.shoulder_r2,
            DpadDirection.UP to R.string.dpad_up, DpadDirection.DOWN to R.string.dpad_down,
            DpadDirection.LEFT to R.string.dpad_left, DpadDirection.RIGHT to R.string.dpad_right,
            StickButton.LEFT_UP to R.string.stick_left_up, StickButton.LEFT_DOWN to R.string.stick_left_down,
            StickButton.LEFT_LEFT to R.string.stick_left_left, StickButton.LEFT_RIGHT to R.string.stick_left_right,
            StickButton.RIGHT_UP to R.string.stick_right_up, StickButton.RIGHT_DOWN to R.string.stick_right_down,
            StickButton.RIGHT_LEFT to R.string.stick_right_left, StickButton.RIGHT_RIGHT to R.string.stick_right_right,
            StickClickButton.LEFT_PRESS to R.string.stick_left_press, StickClickButton.LEFT_DOUBLE to R.string.stick_left_double,
            StickClickButton.LEFT_TAP_HOLD to R.string.stick_left_tap_hold, StickClickButton.RIGHT_PRESS to R.string.stick_right_press,
            StickClickButton.RIGHT_DOUBLE to R.string.stick_right_double, StickClickButton.RIGHT_TAP_HOLD to R.string.stick_right_tap_hold,
        )
        Stick.entries.forEach { stick ->
            val old = a.stickTimings.getValue(stick.prefix)
            val new = b.stickTimings.getValue(stick.prefix)
            val side = context.getString(if (stick == Stick.LEFT) R.string.stick_tab_left else R.string.stick_tab_right)
            addChange(context.getString(R.string.settings_property_label, side, context.getString(R.string.stick_double_title)), ms(old.doubleMs), ms(new.doubleMs))
            addChange(context.getString(R.string.settings_property_label, side, context.getString(R.string.stick_tap_hold_title)), ms(old.tapHoldMs), ms(new.tapHoldMs))
            val oldHome = a.homeStickTimings.getValue(stick.prefix)
            val newHome = b.homeStickTimings.getValue(stick.prefix)
            val homeStick = context.getString(R.string.settings_property_label, homeSide, side)
            addChange(context.getString(R.string.settings_property_label, homeStick, context.getString(R.string.stick_double_title)), ms(oldHome.doubleMs), ms(newHome.doubleMs))
            addChange(context.getString(R.string.settings_property_label, homeStick, context.getString(R.string.stick_tap_hold_title)), ms(oldHome.tapHoldMs), ms(newHome.tapHoldMs))
        }
        // The Home layer follows the Back layer, binding by binding.
        val layers = buttons.map { (button, labelRes) -> Triple(button, labelRes, R.string.settings_binding_label) } +
            buttons.filter { (button, _) -> button in Prefs.HOME_LAYER_BASES }
                .map { (button, labelRes) -> Triple(HomeLayerButton.of(button), labelRes, R.string.settings_home_binding_label) }
        layers.forEach { (button, labelRes, layerRes) ->
            val old = a.bindings.getValue(button.prefKey)
            val new = b.bindings.getValue(button.prefKey)
            val label = context.getString(layerRes, context.getString(labelRes))
            addChange(label, binding(old), binding(new))
            fun extra(res: Int, from: String, to: String) = addChange(context.getString(R.string.settings_property_label, label, context.getString(res)), from, to)
            if (!(launches(old) && launches(new))) extra(R.string.level_target_title, target(old.display), target(new.display))
            if (!(old.action == Prefs.SHOULDER_SCREENSHOT && new.action == old.action))
                extra(R.string.settings_screenshot_screen, target(old.screenshotTarget()), target(new.screenshotTarget()))
            fun screenTarget(action: String) =
                Prefs.isLevelAction(action) || Prefs.isCloseApps(action) || action == Prefs.SHOULDER_VOLUME_MUTE ||
                    action == Prefs.SHOULDER_RECORD_VIDEO
            if (!(screenTarget(old.action) && screenTarget(new.action)))
                extra(R.string.settings_level_screen, target(old.levelTarget()), target(new.levelTarget()))
            if (!(old.action == Prefs.SHOULDER_CONTROLLER_FOCUS && new.action == old.action))
                extra(R.string.level_target_title, focus(old.focusTarget), focus(new.focusTarget))
            extra(R.string.app_control_mode_title, appMode(old.appMode), appMode(new.appMode))
            extra(R.string.level_target_title, appScreen(old.appScreen), appScreen(new.appScreen))
            extra(R.string.switch_app_follow_focus, onOff(old.switchAppFollow), onOff(new.switchAppFollow))
            extra(R.string.app_control_confirm_title, onOff(old.appConfirm), onOff(new.appConfirm))
            extra(R.string.app_control_confirm_title, onOff(old.closeConfirm), onOff(new.closeConfirm))
            extra(R.string.standby_confirm_title, onOff(old.standbyConfirm), onOff(new.standbyConfirm))
            extra(R.string.record_mic_title, onOff(old.recordMic), onOff(new.recordMic))
            extra(R.string.record_ptt_title, onOff(old.recordPtt), onOff(new.recordPtt))
            extra(R.string.record_ptt_key_title, describer.describePushToTalkKey(old.recordPttKey), describer.describePushToTalkKey(new.recordPttKey))
            extra(R.string.record_duck_title, duck(old.recordDuck), duck(new.recordDuck))
            extra(R.string.record_quality_title, quality(old.recordQuality), quality(new.recordQuality))
            extra(R.string.record_indicator_title, onOff(old.recordIndicator), onOff(new.recordIndicator))
            extra(R.string.record_indicator_row, position(old.recordPosition), position(new.recordPosition))
            extra(R.string.gyro_setup_title, describer.describeGyro(GyroSettings.decode(old.gyro)), describer.describeGyro(GyroSettings.decode(new.gyro)))
            extra(R.string.macro_title, describer.describeMacro(a.macros.firstOrNull { it.id == old.macro }),
                describer.describeMacro(b.macros.firstOrNull { it.id == new.macro }))
            extra(R.string.focus_indicator_title, onOff(old.indicator), onOff(new.indicator))
            extra(R.string.focus_header_duration, ms(old.duration), ms(new.duration))
            extra(R.string.focus_header_position, position(old.position), position(new.position))
        }
    }
}
