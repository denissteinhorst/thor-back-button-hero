package dev.steinhorst.backbuttonhero.settings

import android.content.Context
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.actions.ActionGroups
import dev.steinhorst.backbuttonhero.actions.Ayn
import dev.steinhorst.backbuttonhero.capture.PushToTalk
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.input.ServiceToggleGesture
import dev.steinhorst.backbuttonhero.input.Stick
import dev.steinhorst.backbuttonhero.macro.MacroEditor
import dev.steinhorst.backbuttonhero.macro.SavedMacro
import dev.steinhorst.backbuttonhero.pad.GyroAxis
import dev.steinhorst.backbuttonhero.pad.GyroSettings
import dev.steinhorst.backbuttonhero.prefs.Action
import dev.steinhorst.backbuttonhero.prefs.ActionCodec
import dev.steinhorst.backbuttonhero.prefs.Prefs

/** A choice in a settings list: the stored [value] and the [label] the list shows. */
internal data class Choice(val value: String, val label: String)

/**
 * Every text the settings derive from a binding and every list a binding chooses from: the action
 * list and its search words, each option's name, and the screens, modes, targets, qualities, ducks
 * and indicator positions a row describes. Texts come from resources of [context]; app names are
 * cached until [clearAppLabels].
 */
internal class BindingDescriber(private val context: Context, private val prefs: Prefs) {
    private val appLabels = mutableMapOf<String, String>()

    fun clearAppLabels() = appLabels.clear()

    /** A short name for the overview's narrow columns; the pages keep the full wording. */
    fun overviewLabel(action: String): String {
        val decoded = ActionCodec.decode(action)
        val res = when (decoded) {
            Action.Builtin.BACK -> R.string.overview_action_back
            Action.Builtin.RECENTS -> R.string.overview_action_recents_top
            Action.Builtin.SWITCHER -> R.string.overview_action_recents_bottom
            Action.Builtin.RECENTS_FOCUSED -> R.string.overview_action_recents_focused
            Action.Builtin.SWITCH_ACTIVE_APP -> R.string.overview_action_switch_app
            Action.Builtin.LAST_APP -> R.string.overview_action_last_app
            Action.Builtin.SCREENSHOT -> R.string.overview_action_screenshot
            Action.Builtin.CONTROLLER_FOCUS -> R.string.overview_action_controller_focus
            Action.Builtin.GYRO -> R.string.overview_action_gyro
            Action.Builtin.GYRO_CALIBRATE -> R.string.overview_action_gyro_calibrate
            Action.Builtin.QUICK_SETTINGS -> R.string.overview_action_quick_settings
            Action.Builtin.NOTIFICATIONS -> R.string.overview_action_notifications
            Action.Builtin.INTERNET -> R.string.overview_action_internet
            Action.Builtin.BLUETOOTH -> R.string.overview_action_bluetooth
            Action.Builtin.NIGHT_LIGHT -> R.string.overview_action_night_light
            Action.Builtin.EXTRA_DIM -> R.string.overview_action_extra_dim
            Action.Builtin.DISPLAY_SETTINGS -> R.string.overview_action_display
            Action.Builtin.SOUND_SETTINGS -> R.string.overview_action_sound
            Action.Builtin.LOCATION_SETTINGS -> R.string.overview_action_location
            Action.Builtin.CAST_SETTINGS -> R.string.overview_action_cast
            Action.Builtin.DND_SETTINGS -> R.string.overview_action_dnd
            Action.Builtin.BATTERY_SAVER -> R.string.overview_action_battery_saver
            Action.Builtin.VOLUME_UP -> R.string.overview_action_volume_up
            Action.Builtin.VOLUME_DOWN -> R.string.overview_action_volume_down
            Action.Builtin.VOLUME_MUTE -> R.string.overview_action_mute
            Action.Builtin.BRIGHTNESS_UP -> R.string.overview_action_brightness_up
            Action.Builtin.BRIGHTNESS_DOWN -> R.string.overview_action_brightness_down
            Action.Builtin.FLICK_LEFT -> R.string.overview_action_flick_left
            Action.Builtin.FLICK_RIGHT -> R.string.overview_action_flick_right
            Action.Builtin.MEDIA_PLAY_PAUSE -> R.string.overview_action_play_pause
            Action.Builtin.MEDIA_NEXT -> R.string.overview_action_next
            Action.Builtin.MEDIA_PREVIOUS -> R.string.overview_action_previous
            Action.Builtin.CLOSE_APPS -> R.string.overview_action_close_all
            Action.Builtin.CLOSE_BACKGROUND_APPS -> R.string.overview_action_close_background
            Action.Builtin.POWER_MENU -> R.string.overview_action_power_menu
            Action.Builtin.STANDBY -> R.string.overview_action_standby
            Action.Builtin.APP_ASK -> R.string.overview_action_app_ask
            Action.Builtin.APP_FAVORITES -> R.string.overview_action_app_favorites
            else -> null
        }
        if (res != null) return context.getString(res)
        if (decoded is Action.PressButton) return context.getString(R.string.overview_action_press_button, context.getString(decoded.button.labelRes))
        if (decoded is Action.LaunchApp) {
            return runCatching { context.getString(R.string.overview_action_open_app, context.packageManager.getApplicationInfo(decoded.pkg, 0).loadLabel(context.packageManager)) }
                .getOrDefault(context.getString(R.string.shoulder_launch_app))
        }
        return describeAction(action)
    }

    fun toggleLabel(value: String): String = ServiceToggleGesture.label(value) ?: context.getString(R.string.shoulder_none)

    /** [macro]: whether the list offers macros; Back's own gestures cannot play them. */
    fun shoulderActions(macro: Boolean = true): List<Choice> {
        fun group(g: ActionGroups.Group) = Choice(g.entry, context.getString(g.titleRes))
        return listOfNotNull(
            Choice("", context.getString(R.string.shoulder_none)),
            Choice(ACTION_APP, context.getString(R.string.shoulder_launch_app)),
            Choice(Prefs.SHOULDER_HOME, context.getString(R.string.shoulder_home)),
            Choice(Prefs.SHOULDER_BACK, context.getString(R.string.shoulder_back)),
            group(ActionGroups.Group.APP_SWITCHER),
            Choice(Prefs.SHOULDER_SWAP_APPS, context.getString(R.string.shoulder_swap_apps)),
            Choice(Prefs.SHOULDER_LAST_APP, context.getString(R.string.shoulder_last_app)),
            Choice(Prefs.SHOULDER_SCREENSHOT, context.getString(R.string.shoulder_screenshot)),
            Choice(Prefs.SHOULDER_RECORD_VIDEO, context.getString(R.string.shoulder_record_video)),
            Choice(Prefs.SHOULDER_MACRO, context.getString(R.string.shoulder_macro)).takeIf { macro },
            group(ActionGroups.Group.PRESS_BUTTON),
            Choice(Prefs.SHOULDER_GYRO, context.getString(R.string.shoulder_gyro)),
            Choice(Prefs.SHOULDER_GYRO_CALIBRATE, context.getString(R.string.shoulder_gyro_calibrate)),
            group(ActionGroups.Group.FLICK),
            group(ActionGroups.Group.VOLUME),
            group(ActionGroups.Group.BRIGHTNESS),
            group(ActionGroups.Group.MEDIA),
            Choice(Prefs.SHOULDER_CONTROLLER_FOCUS, context.getString(R.string.shoulder_controller_focus)),
            Choice(Prefs.SHOULDER_SWITCH_APP, context.getString(R.string.shoulder_switch_app)),
            Choice(Prefs.SHOULDER_APP_CONTROL, context.getString(R.string.shoulder_app_control)),
            group(ActionGroups.Group.CLOSE_APPS),
            group(ActionGroups.Group.POWER),
            Choice(Prefs.SHOULDER_STATUS, context.getString(R.string.shoulder_status)),
        ) + aynChoices() + listOf(group(ActionGroups.Group.PANELS), group(ActionGroups.Group.SETTINGS))
    }

    /** One entry per AYN tile; its options are chosen on the row, like a volume action's screen. */
    fun aynChoices(): List<Choice> =
        Ayn.Group.values().map { Choice(it.action, context.getString(it.titleRes)) } +
            Ayn.Toggle.values().map { Choice(it.action, context.getString(it.titleRes)) }

    /** What an entry of the action list does, shown below its name; "Not set" needs none. */
    fun actionDescription(value: String): Int? = when (value) {
        ACTION_APP -> R.string.action_desc_launch_app
        Prefs.SHOULDER_HOME -> R.string.action_desc_home
        Prefs.SHOULDER_BACK -> R.string.action_desc_back
        ActionGroups.Group.APP_SWITCHER.entry -> R.string.action_desc_app_switcher
        Prefs.SHOULDER_SWAP_APPS -> R.string.action_desc_swap_apps
        Prefs.SHOULDER_SCREENSHOT -> R.string.action_desc_screenshot
        Prefs.SHOULDER_RECORD_VIDEO -> R.string.action_desc_record_video
        Prefs.SHOULDER_MACRO -> R.string.action_desc_macro
        ActionGroups.Group.PRESS_BUTTON.entry -> R.string.action_desc_press_button
        Prefs.SHOULDER_GYRO -> R.string.action_desc_gyro
        Prefs.SHOULDER_GYRO_CALIBRATE -> R.string.action_desc_gyro_calibrate
        ActionGroups.Group.FLICK.entry -> R.string.action_desc_flick
        ActionGroups.Group.VOLUME.entry -> R.string.action_desc_volume
        ActionGroups.Group.BRIGHTNESS.entry -> R.string.action_desc_brightness
        ActionGroups.Group.MEDIA.entry -> R.string.action_desc_media
        Prefs.SHOULDER_CONTROLLER_FOCUS -> R.string.action_desc_controller_focus
        Prefs.SHOULDER_SWITCH_APP -> R.string.action_desc_switch_app
        Prefs.SHOULDER_LAST_APP -> R.string.action_desc_last_app
        Prefs.SHOULDER_APP_CONTROL -> R.string.action_desc_app_control
        ActionGroups.Group.CLOSE_APPS.entry -> R.string.action_desc_close_apps
        ActionGroups.Group.POWER.entry -> R.string.action_desc_power
        Prefs.SHOULDER_STATUS -> R.string.action_desc_status
        Ayn.Group.PERFORMANCE.action -> R.string.action_desc_performance
        Ayn.Group.FAN.action -> R.string.action_desc_fan
        Ayn.Group.HANDLE.action -> R.string.action_desc_handle
        Ayn.Group.DISPLAY.action -> R.string.action_desc_display
        Ayn.Group.REFRESH_RATE.action -> R.string.action_desc_refresh_rate
        Ayn.Group.STICK_LIGHT.action -> R.string.action_desc_stick_light
        Ayn.Toggle.DIRECT_POWER.action -> R.string.action_desc_direct_power
        Ayn.Toggle.VIBRATION.action -> R.string.action_desc_vibration
        Ayn.Toggle.FORCE_LANDSCAPE.action -> R.string.action_desc_force_landscape
        Ayn.Toggle.VIRTUAL_MOUSE.action -> R.string.action_desc_virtual_mouse
        Ayn.Toggle.DO_NOT_DISTURB.action -> R.string.action_desc_do_not_disturb
        Ayn.Toggle.MUTE_MICROPHONE.action -> R.string.action_desc_mute_microphone
        ActionGroups.Group.PANELS.entry -> R.string.action_desc_panels
        ActionGroups.Group.SETTINGS.entry -> R.string.action_desc_settings
        else -> null
    }

    /**
     * Words the action search also finds for an entry, beyond its name and description: what players
     * actually type. [key] is the action, or "tile action:option" for an option inside an AYN tile,
     * whose option keys repeat between tiles.
     */
    fun searchKeywords(key: String): String = when (key) {
        "" -> R.string.search_keywords_none
        ACTION_APP -> R.string.search_keywords_launch_app
        Prefs.SHOULDER_HOME -> R.string.search_keywords_home
        Prefs.SHOULDER_BACK -> R.string.search_keywords_back
        ActionGroups.Group.APP_SWITCHER.entry -> R.string.search_keywords_app_switcher
        Prefs.SHOULDER_RECENTS -> R.string.search_keywords_recents
        Prefs.SHOULDER_SWITCHER -> R.string.search_keywords_switcher
        Prefs.SHOULDER_RECENTS_FOCUSED -> R.string.search_keywords_recents_focused
        Prefs.SHOULDER_SWAP_APPS -> R.string.search_keywords_swap_apps
        Prefs.SHOULDER_LAST_APP -> R.string.search_keywords_last_app
        Prefs.SHOULDER_SCREENSHOT -> R.string.search_keywords_screenshot
        Prefs.SHOULDER_RECORD_VIDEO -> R.string.search_keywords_record_video
        Prefs.SHOULDER_MACRO -> R.string.search_keywords_macro
        ActionGroups.Group.PRESS_BUTTON.entry -> R.string.search_keywords_press_button
        Prefs.SHOULDER_GYRO -> R.string.search_keywords_gyro
        Prefs.SHOULDER_GYRO_CALIBRATE -> R.string.search_keywords_gyro_calibrate
        ActionGroups.Group.FLICK.entry -> R.string.search_keywords_flick
        Prefs.SHOULDER_FLICK_LEFT -> R.string.search_keywords_flick_left
        Prefs.SHOULDER_FLICK_RIGHT -> R.string.search_keywords_flick_right
        ActionGroups.Group.VOLUME.entry -> R.string.search_keywords_volume
        Prefs.SHOULDER_VOLUME_UP -> R.string.search_keywords_volume_up
        Prefs.SHOULDER_VOLUME_DOWN -> R.string.search_keywords_volume_down
        Prefs.SHOULDER_VOLUME_MUTE -> R.string.search_keywords_volume_mute
        ActionGroups.Group.BRIGHTNESS.entry -> R.string.search_keywords_brightness
        Prefs.SHOULDER_BRIGHTNESS_UP -> R.string.search_keywords_brightness_up
        Prefs.SHOULDER_BRIGHTNESS_DOWN -> R.string.search_keywords_brightness_down
        ActionGroups.Group.MEDIA.entry -> R.string.search_keywords_media
        Prefs.SHOULDER_MEDIA_PLAY_PAUSE -> R.string.search_keywords_media_play_pause
        Prefs.SHOULDER_MEDIA_NEXT -> R.string.search_keywords_media_next
        Prefs.SHOULDER_MEDIA_PREVIOUS -> R.string.search_keywords_media_previous
        Prefs.SHOULDER_CONTROLLER_FOCUS -> R.string.search_keywords_controller_focus
        Prefs.SHOULDER_SWITCH_APP -> R.string.search_keywords_switch_app
        Prefs.SHOULDER_APP_CONTROL -> R.string.search_keywords_app_control
        ActionGroups.Group.CLOSE_APPS.entry -> R.string.search_keywords_close_apps_group
        Prefs.SHOULDER_CLOSE_APPS -> R.string.search_keywords_close_apps
        Prefs.SHOULDER_CLOSE_BACKGROUND_APPS -> R.string.search_keywords_close_background
        ActionGroups.Group.POWER.entry -> R.string.search_keywords_power
        Prefs.SHOULDER_POWER_MENU -> R.string.search_keywords_power_menu
        Prefs.SHOULDER_STANDBY -> R.string.search_keywords_standby
        Prefs.SHOULDER_STATUS -> R.string.search_keywords_status
        ActionGroups.Group.PANELS.entry -> R.string.search_keywords_panels
        Prefs.SHOULDER_QUICK_SETTINGS -> R.string.search_keywords_quick_settings
        Prefs.SHOULDER_NOTIFICATIONS -> R.string.search_keywords_notifications
        ActionGroups.Group.SETTINGS.entry -> R.string.search_keywords_settings
        Prefs.SHOULDER_INTERNET -> R.string.search_keywords_internet
        Prefs.SHOULDER_BLUETOOTH -> R.string.search_keywords_bluetooth
        Prefs.SHOULDER_NIGHT_LIGHT -> R.string.search_keywords_night_light
        Prefs.SHOULDER_EXTRA_DIM -> R.string.search_keywords_extra_dim
        Prefs.SHOULDER_DISPLAY_SETTINGS -> R.string.search_keywords_display_settings
        Prefs.SHOULDER_SOUND_SETTINGS -> R.string.search_keywords_sound_settings
        Prefs.SHOULDER_LOCATION_SETTINGS -> R.string.search_keywords_location_settings
        Prefs.SHOULDER_CAST_SETTINGS -> R.string.search_keywords_cast_settings
        Prefs.SHOULDER_DND_SETTINGS -> R.string.search_keywords_dnd_settings
        Prefs.SHOULDER_BATTERY_SAVER -> R.string.search_keywords_battery_saver
        Ayn.Group.PERFORMANCE.action -> R.string.search_keywords_performance
        "${Ayn.Group.PERFORMANCE.action}:standard" -> R.string.search_keywords_performance_standard
        "${Ayn.Group.PERFORMANCE.action}:medium" -> R.string.search_keywords_performance_medium
        "${Ayn.Group.PERFORMANCE.action}:high" -> R.string.search_keywords_performance_high
        Ayn.Group.FAN.action -> R.string.search_keywords_fan
        "${Ayn.Group.FAN.action}:off" -> R.string.search_keywords_fan_off
        "${Ayn.Group.FAN.action}:quiet" -> R.string.search_keywords_fan_quiet
        "${Ayn.Group.FAN.action}:smart" -> R.string.search_keywords_fan_smart
        "${Ayn.Group.FAN.action}:sports" -> R.string.search_keywords_fan_sports
        Ayn.Group.HANDLE.action -> R.string.search_keywords_handle
        "${Ayn.Group.HANDLE.action}:standard" -> R.string.search_keywords_handle_standard
        "${Ayn.Group.HANDLE.action}:xbox" -> R.string.search_keywords_handle_xbox
        "${Ayn.Group.HANDLE.action}:ban" -> R.string.search_keywords_handle_ban
        Ayn.Group.DISPLAY.action -> R.string.search_keywords_display
        "${Ayn.Group.DISPLAY.action}:top" -> R.string.search_keywords_display_top
        "${Ayn.Group.DISPLAY.action}:bottom" -> R.string.search_keywords_display_bottom
        Ayn.Group.REFRESH_RATE.action -> R.string.search_keywords_refresh_rate
        "${Ayn.Group.REFRESH_RATE.action}:60" -> R.string.search_keywords_refresh_60
        "${Ayn.Group.REFRESH_RATE.action}:120" -> R.string.search_keywords_refresh_120
        Ayn.Group.STICK_LIGHT.action -> R.string.search_keywords_stick_light
        Ayn.Toggle.DIRECT_POWER.action -> R.string.search_keywords_direct_power
        Ayn.Toggle.VIBRATION.action -> R.string.search_keywords_vibration
        Ayn.Toggle.FORCE_LANDSCAPE.action -> R.string.search_keywords_force_landscape
        Ayn.Toggle.VIRTUAL_MOUSE.action -> R.string.search_keywords_virtual_mouse
        Ayn.Toggle.DO_NOT_DISTURB.action -> R.string.search_keywords_do_not_disturb
        Ayn.Toggle.MUTE_MICROPHONE.action -> R.string.search_keywords_mute_microphone
        else -> null
    }?.let(context::getString).orEmpty()

    /**
     * What the search finds inside groups and AYN tiles: each option as "Group › Option", carrying the
     * group's description. Picking one sets the group and its option in one go.
     */
    fun actionOptions(choices: List<Choice>): List<ActionChooser.Entry> = choices.flatMap { choice ->
        val description = actionDescription(choice.value)?.let(context::getString)
        fun entry(option: String, label: String, key: String = option) =
            ActionChooser.Entry(choice.value, context.getString(R.string.action_search_option, choice.label, label), description, option, searchKeywords(key))
        ActionGroups.groupOfEntry(choice.value)?.options?.map { entry(it.action, context.getString(it.labelRes)) }
            ?: Ayn.groupOf(choice.value)?.let { group -> aynOptions(group).map { entry(it.value, it.label, "${group.action}:${it.value}") } }
            ?: emptyList()
    }

    fun aynOptions(group: Ayn.Group): List<Choice> =
        (if (group.hasModal) listOf(Choice(Ayn.MODAL, context.getString(R.string.ayn_modal))) else emptyList()) +
            group.options.map { Choice(it.key, context.getString(it.labelRes)) }

    fun describeOption(button: ComboButton): String {
        val action = prefs.shoulderAction(button)
        ActionGroups.groupOf(action)?.let { group -> return context.getString(checkNotNull(group.option(action)).labelRes) }
        return describeAynOption(button)
    }

    fun describeAynOption(button: ComboButton): String {
        val group = Ayn.groupOf(prefs.shoulderAction(button)) ?: return ""
        val chosen = prefs.aynOption(button, group)
        return aynOptions(group).first { it.value == chosen }.label
    }

    fun describeShoulderAction(button: ComboButton): String = describeAction(prefs.shoulderAction(button))

    fun describeAction(action: String): String {
        return when (action) {
            "" -> context.getString(R.string.shoulder_none)
            Prefs.SHOULDER_APP_ASK -> context.getString(R.string.shoulder_launch_app_named, context.getString(R.string.ayn_modal))
            // An empty list would leave the hotkey with nothing to offer, so the row says so.
            Prefs.SHOULDER_APP_FAVORITES -> if (prefs.favoriteApps.isEmpty()) context.getString(R.string.shoulder_launch_app_favorites_empty)
                else context.getString(R.string.shoulder_launch_app_named, context.getString(R.string.shoulder_app_favorites))
            else -> ActionGroups.groupOf(action)?.let { context.getString(it.titleRes) } ?: shoulderActions().firstOrNull { it.value == action }?.label
                ?: runCatching {
                    appLabels.getOrPut(action) {
                        context.getString(R.string.shoulder_launch_app_named, context.packageManager.getApplicationInfo(action, 0).loadLabel(context.packageManager))
                    }
                }.getOrDefault(context.getString(R.string.shoulder_launch_app))
        }
    }

    fun appModes(): List<Choice> = listOf(
        Choice(Prefs.APP_MINIMIZE, context.getString(R.string.app_control_minimize)),
        Choice(Prefs.APP_FORCE_CLOSE, context.getString(R.string.app_control_force_close)),
        Choice(Prefs.APP_RESTART, context.getString(R.string.app_control_restart)),
    )

    // The same screens as everywhere else, plus the prompt.
    fun appScreens(): List<Choice> = levelTargets() + focusedScreen() + Choice(Prefs.APP_SCREEN_ASK, context.getString(R.string.ayn_modal))

    fun describeAppMode(button: ComboButton): String =
        (appModes().firstOrNull { it.value == prefs.appMode(button) } ?: appModes().first()).label

    fun describeAppScreen(button: ComboButton): String =
        (appScreens().firstOrNull { it.value == prefs.appScreen(button) } ?: appScreens().last()).label

    /** Every screen choice lists top, bottom, then both; special values like the prompt follow. */
    fun screenTargets(): List<Choice> = listOf(
        Choice(Prefs.DISPLAY_TOP, context.getString(R.string.level_target_top)),
        Choice(Prefs.DISPLAY_BOTTOM, context.getString(R.string.level_target_bottom)),
    )

    /** The screen the controller drives when the hotkey is pressed. */
    fun focusedScreen(): Choice = Choice(Prefs.TARGET_FOCUSED, context.getString(R.string.target_focused))

    fun levelTargets(): List<Choice> = screenTargets() + Choice(Prefs.TARGET_BOTH, context.getString(R.string.level_target_both))

    /** The screens [action] offers: the focused screen only where [Prefs.offersFocusedTarget]. */
    fun levelTargets(action: String): List<Choice> =
        if (Prefs.offersFocusedTarget(action)) levelTargets() + focusedScreen() else levelTargets()

    fun describeLevelTarget(button: ComboButton): String = levelTargets(prefs.shoulderAction(button)).let { targets ->
        (targets.firstOrNull { it.value == prefs.levelTarget(button) } ?: targets.last()).label
    }

    // Where Home goes: either screen, or the one the controller drives.
    fun homeScreens(): List<Choice> = screenTargets() + focusedScreen()

    // Where an app opens: as Home, or asked when the hotkey is pressed.
    fun launchScreens(): List<Choice> = homeScreens() + Choice(Prefs.APP_SCREEN_ASK, context.getString(R.string.ayn_modal))

    fun describeShoulderDisplay(button: ComboButton): String = launchScreens().let { targets ->
        (targets.firstOrNull { it.value == prefs.shoulderDisplay(button) } ?: targets.last()).label
    }

    fun focusTargets(): List<Choice> = screenTargets() + listOf(
        Choice(Prefs.FOCUS_TOGGLE, context.getString(R.string.focus_target_toggle)),
        Choice(Prefs.FOCUS_AUTO, context.getString(R.string.focus_target_auto)),
        Choice(Prefs.FOCUS_MODAL, context.getString(R.string.ayn_modal)),
    )

    fun describeFocusTarget(button: ComboButton): String = focusTargets().let { targets ->
        (targets.firstOrNull { it.value == prefs.focusTarget(button) } ?: targets.first { it.value == Prefs.FOCUS_TOGGLE }).label
    }

    /** Grouped actions whose choice below is really a screen, so the row reads like every other screen row. */
    fun isScreenOption(action: String): Boolean =
        ActionGroups.groupOf(action) == ActionGroups.Group.APP_SWITCHER || Ayn.groupOf(action) == Ayn.Group.DISPLAY

    /** The nine pictogram positions, row by row as the picker lays them out. */
    fun focusPositions(): List<Choice> = listOf(
        Choice(Prefs.FOCUS_POS_TOP_LEFT, context.getString(R.string.focus_position_top_left)),
        Choice(Prefs.FOCUS_POS_TOP_CENTER, context.getString(R.string.focus_position_top_center)),
        Choice(Prefs.FOCUS_POS_TOP_RIGHT, context.getString(R.string.focus_position_top_right)),
        Choice(Prefs.FOCUS_POS_CENTER_LEFT, context.getString(R.string.focus_position_center_left)),
        Choice(Prefs.FOCUS_POS_CENTER, context.getString(R.string.focus_position_center)),
        Choice(Prefs.FOCUS_POS_CENTER_RIGHT, context.getString(R.string.focus_position_center_right)),
        Choice(Prefs.FOCUS_POS_BOTTOM_LEFT, context.getString(R.string.focus_position_bottom_left)),
        Choice(Prefs.FOCUS_POS_BOTTOM_CENTER, context.getString(R.string.focus_position_bottom_center)),
        Choice(Prefs.FOCUS_POS_BOTTOM_RIGHT, context.getString(R.string.focus_position_bottom_right)),
    )

    fun recordQualities(): List<Choice> = listOf(
        Choice(Prefs.RECORD_SMALL, context.getString(R.string.record_quality_small)),
        Choice(Prefs.RECORD_MEDIUM, context.getString(R.string.record_quality_medium)),
        Choice(Prefs.RECORD_LARGE, context.getString(R.string.record_quality_large)),
    )

    fun describeRecordQuality(button: ComboButton): String =
        recordQualities().first { it.value == prefs.recordQuality(button) }.label

    fun recordDucks(): List<Choice> = listOf(
        Choice(Prefs.DUCK_OFF, context.getString(R.string.record_duck_off)),
        Choice(Prefs.DUCK_VIDEO, context.getString(R.string.record_duck_video)),
        Choice(Prefs.DUCK_DEVICE, context.getString(R.string.record_duck_device)),
    )

    fun describeRecordDuck(button: ComboButton): String =
        recordDucks().first { it.value == prefs.recordDuck(button) }.label

    fun describePushToTalkKey(keyCode: Int): String =
        if (keyCode == 0) context.getString(R.string.record_ptt_key_none) else PushToTalk.label(keyCode)

    /** The gyro setup in a few words: stick, sideways turn, following turns, speed and the hold key. */
    fun describeGyro(settings: GyroSettings): String = listOfNotNull(
        context.getString(if (settings.stick == Stick.LEFT) R.string.gyro_stick_left else R.string.gyro_stick_right),
        context.getString(if (settings.axis == GyroAxis.YAW) R.string.gyro_axis_yaw else R.string.gyro_axis_roll),
        if (settings.rate) context.getString(R.string.gyro_summary_rate) else null,
        context.getString(R.string.gyro_percent, settings.sensitivity),
        settings.holdKeyInUse?.let { context.getString(R.string.gyro_hold_summary, PushToTalk.label(it)) },
    ).joinToString(", ")

    fun describeRecordIndicator(button: ComboButton): String =
        if (!prefs.recordIndicator(button)) context.getString(R.string.focus_position_off)
        else focusPositions().first { it.value == prefs.recordPosition(button) }.label

    fun describeFocusPosition(button: ComboButton): String =
        if (!prefs.focusIndicator(button)) context.getString(R.string.focus_position_off)
        else focusPositions().first { it.value == prefs.focusPosition(button) }.label

    /** The action as the action list names it: a group or AYN tile by its own name, not by its option. */
    fun functionName(action: String): String? = when {
        action.isEmpty() -> null
        else -> ActionGroups.groupOf(action)?.let { context.getString(it.titleRes) }
            ?: Ayn.groupOf(action)?.let { context.getString(it.titleRes) }
            ?: describeAction(action)
    }

    fun describeMacro(saved: SavedMacro?): String =
        saved?.let { MacroEditor.macroLine(context, it) } ?: context.getString(R.string.macro_not_recorded)

    companion object {
        /** The action list's "Launch app" entry, which opens the app list instead of being stored. */
        const val ACTION_APP = "@choose_app"
    }
}
