package dev.steinhorst.backbuttonhero.prefs

import dev.steinhorst.backbuttonhero.actions.Ayn

/**
 * What a hotkey does, as a type instead of the stored string. Installs hold these as plain strings
 * (an app's package name or an `@`-prefixed sentinel), and [ActionCodec] is the only place that maps
 * between the two; a value this build does not know stays [Unknown] and is written back unchanged.
 */
sealed interface Action {
    /** No action: the button is left to the game. */
    data object Unassigned : Action

    /** Launches the app [pkg]; validated only where a privileged command executes it. */
    data class LaunchApp(val pkg: String) : Action

    /** A stored `@` value this build does not know, kept verbatim so a newer build's choice survives. */
    data class Unknown(val raw: String) : Action

    /** One fixed action; [sentinel] is exactly the string installs store for it. */
    enum class Builtin(val sentinel: String) : Action {
        FLICK_LEFT(Prefs.SHOULDER_FLICK_LEFT),
        FLICK_RIGHT(Prefs.SHOULDER_FLICK_RIGHT),
        HOME(Prefs.SHOULDER_HOME),
        BACK(Prefs.SHOULDER_BACK),
        SWITCHER(Prefs.SHOULDER_SWITCHER),
        SWAP_APPS(Prefs.SHOULDER_SWAP_APPS),
        RECENTS(Prefs.SHOULDER_RECENTS),
        SCREENSHOT(Prefs.SHOULDER_SCREENSHOT),
        QUICK_SETTINGS(Prefs.SHOULDER_QUICK_SETTINGS),
        NOTIFICATIONS(Prefs.SHOULDER_NOTIFICATIONS),
        INTERNET(Prefs.SHOULDER_INTERNET),
        BLUETOOTH(Prefs.SHOULDER_BLUETOOTH),
        NIGHT_LIGHT(Prefs.SHOULDER_NIGHT_LIGHT),
        EXTRA_DIM(Prefs.SHOULDER_EXTRA_DIM),
        DISPLAY_SETTINGS(Prefs.SHOULDER_DISPLAY_SETTINGS),
        SOUND_SETTINGS(Prefs.SHOULDER_SOUND_SETTINGS),
        LOCATION_SETTINGS(Prefs.SHOULDER_LOCATION_SETTINGS),
        CAST_SETTINGS(Prefs.SHOULDER_CAST_SETTINGS),
        DND_SETTINGS(Prefs.SHOULDER_DND_SETTINGS),
        BATTERY_SAVER(Prefs.SHOULDER_BATTERY_SAVER),
        VOLUME_UP(Prefs.SHOULDER_VOLUME_UP),
        VOLUME_DOWN(Prefs.SHOULDER_VOLUME_DOWN),
        VOLUME_MUTE(Prefs.SHOULDER_VOLUME_MUTE),
        BRIGHTNESS_UP(Prefs.SHOULDER_BRIGHTNESS_UP),
        BRIGHTNESS_DOWN(Prefs.SHOULDER_BRIGHTNESS_DOWN),
        CONTROLLER_FOCUS(Prefs.SHOULDER_CONTROLLER_FOCUS),
        SWITCH_ACTIVE_APP(Prefs.SHOULDER_SWITCH_APP),
        LAST_APP(Prefs.SHOULDER_LAST_APP),
        APP_CONTROL(Prefs.SHOULDER_APP_CONTROL),
        CLOSE_APPS(Prefs.SHOULDER_CLOSE_APPS),
        CLOSE_BACKGROUND_APPS(Prefs.SHOULDER_CLOSE_BACKGROUND_APPS),
        POWER_MENU(Prefs.SHOULDER_POWER_MENU),
        STANDBY(Prefs.SHOULDER_STANDBY),
        RECORD_VIDEO(Prefs.SHOULDER_RECORD_VIDEO),
        MACRO(Prefs.SHOULDER_MACRO),
        MEDIA_PLAY_PAUSE(Prefs.SHOULDER_MEDIA_PLAY_PAUSE),
        MEDIA_NEXT(Prefs.SHOULDER_MEDIA_NEXT),
        MEDIA_PREVIOUS(Prefs.SHOULDER_MEDIA_PREVIOUS),
        STATUS_OVERLAY(Prefs.SHOULDER_STATUS),
        GYRO(Prefs.SHOULDER_GYRO),
        GYRO_CALIBRATE(Prefs.SHOULDER_GYRO_CALIBRATE),
        APP_ASK(Prefs.SHOULDER_APP_ASK),
        APP_FAVORITES(Prefs.SHOULDER_APP_FAVORITES),
    }

    /** One of AYN's tiles with a choice of values; the chosen value is stored separately. */
    data class AynChoice(val group: Ayn.Group) : Action

    /** One of AYN's on/off tiles. */
    data class AynSwitch(val toggle: Ayn.Toggle) : Action
}

/** The only mapping between stored action strings and [Action]; `encode(decode(s)) == s` for every string. */
object ActionCodec {
    private val bySentinel: Map<String, Action> by lazy {
        Action.Builtin.entries.associateBy { it.sentinel } +
            Ayn.Group.entries.associate { it.action to Action.AynChoice(it) } +
            Ayn.Toggle.entries.associate { it.action to Action.AynSwitch(it) }
    }

    /** Mirrors [Prefs.isShoulderApp]: any non-empty value without the `@` prefix is a package name. */
    fun decode(raw: String): Action = when {
        raw.isEmpty() -> Action.Unassigned
        !raw.startsWith('@') -> Action.LaunchApp(raw)
        else -> bySentinel[raw] ?: Action.Unknown(raw)
    }

    fun encode(action: Action): String = when (action) {
        Action.Unassigned -> ""
        is Action.LaunchApp -> action.pkg
        is Action.Unknown -> action.raw
        is Action.Builtin -> action.sentinel
        is Action.AynChoice -> action.group.action
        is Action.AynSwitch -> action.toggle.action
    }
}
