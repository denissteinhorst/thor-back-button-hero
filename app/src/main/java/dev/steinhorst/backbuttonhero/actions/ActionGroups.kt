package dev.steinhorst.backbuttonhero.actions

import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.prefs.Prefs

/**
 * Single actions that the settings list shows as one entry with a choice below it. The stored value
 * stays the single action ([Option.action]), so bindings, backups and the key handling never learn
 * about groups; only the settings screen folds them together.
 */
object ActionGroups {
    class Option(val action: String, val labelRes: Int)

    enum class Group(val key: String, val titleRes: Int, val options: List<Option>) {
        APP_SWITCHER(
            "app_switcher", R.string.group_app_switcher,
            listOf(
                Option(Prefs.SHOULDER_RECENTS, R.string.level_target_top),
                Option(Prefs.SHOULDER_SWITCHER, R.string.level_target_bottom),
                Option(Prefs.SHOULDER_RECENTS_FOCUSED, R.string.target_focused),
            ),
        ),
        VOLUME(
            "volume", R.string.group_volume,
            listOf(
                Option(Prefs.SHOULDER_VOLUME_UP, R.string.group_up),
                Option(Prefs.SHOULDER_VOLUME_DOWN, R.string.group_down),
                Option(Prefs.SHOULDER_VOLUME_MUTE, R.string.group_mute),
            ),
        ),
        BRIGHTNESS(
            "brightness", R.string.group_brightness,
            listOf(Option(Prefs.SHOULDER_BRIGHTNESS_UP, R.string.group_up), Option(Prefs.SHOULDER_BRIGHTNESS_DOWN, R.string.group_down)),
        ),
        MEDIA(
            "media", R.string.group_media,
            listOf(
                Option(Prefs.SHOULDER_MEDIA_PLAY_PAUSE, R.string.media_play_pause),
                Option(Prefs.SHOULDER_MEDIA_NEXT, R.string.media_next),
                Option(Prefs.SHOULDER_MEDIA_PREVIOUS, R.string.media_previous),
            ),
        ),
        FLICK(
            "flick", R.string.group_flick,
            listOf(Option(Prefs.SHOULDER_FLICK_LEFT, R.string.group_left), Option(Prefs.SHOULDER_FLICK_RIGHT, R.string.group_right)),
        ),
        CLOSE_APPS(
            "close_apps", R.string.shoulder_close_apps,
            listOf(
                Option(Prefs.SHOULDER_CLOSE_APPS, R.string.close_apps_all),
                Option(Prefs.SHOULDER_CLOSE_BACKGROUND_APPS, R.string.close_apps_background),
            ),
        ),
        POWER(
            "power", R.string.group_power,
            listOf(
                Option(Prefs.SHOULDER_POWER_MENU, R.string.shoulder_power_menu),
                Option(Prefs.SHOULDER_STANDBY, R.string.group_standby),
            ),
        ),
        PANELS(
            "panels", R.string.group_panels,
            listOf(
                Option(Prefs.SHOULDER_QUICK_SETTINGS, R.string.shoulder_quick_settings),
                Option(Prefs.SHOULDER_NOTIFICATIONS, R.string.shoulder_notifications),
            ),
        ),
        SETTINGS(
            "settings", R.string.group_settings,
            listOf(
                Option(Prefs.SHOULDER_INTERNET, R.string.shoulder_internet),
                Option(Prefs.SHOULDER_BLUETOOTH, R.string.shoulder_bluetooth),
                Option(Prefs.SHOULDER_NIGHT_LIGHT, R.string.shoulder_night_light),
                Option(Prefs.SHOULDER_EXTRA_DIM, R.string.shoulder_extra_dim),
                Option(Prefs.SHOULDER_DISPLAY_SETTINGS, R.string.shoulder_display_settings),
                Option(Prefs.SHOULDER_SOUND_SETTINGS, R.string.shoulder_sound_settings),
                Option(Prefs.SHOULDER_LOCATION_SETTINGS, R.string.shoulder_location_settings),
                Option(Prefs.SHOULDER_CAST_SETTINGS, R.string.shoulder_cast_settings),
                Option(Prefs.SHOULDER_DND_SETTINGS, R.string.shoulder_dnd_settings),
                Option(Prefs.SHOULDER_BATTERY_SAVER, R.string.shoulder_battery_saver),
            ),
        ),
        ;

        /** The entry value in the action list; it is never stored. */
        val entry: String get() = "@group_$key"

        fun option(action: String): Option? = options.firstOrNull { it.action == action }
    }

    fun groupOf(action: String): Group? = Group.values().firstOrNull { it.option(action) != null }

    fun groupOfEntry(entry: String): Group? = Group.values().firstOrNull { it.entry == entry }

    /** Every single action some group folds away. */
    val GROUPED: Set<String> by lazy { Group.values().flatMap { g -> g.options.map { it.action } }.toSet() }
}
