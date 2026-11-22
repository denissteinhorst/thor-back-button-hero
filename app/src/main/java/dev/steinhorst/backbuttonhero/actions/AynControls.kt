package dev.steinhorst.backbuttonhero.actions

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.core.PService
import dev.steinhorst.backbuttonhero.prefs.Prefs
import java.util.concurrent.Executors

/**
 * The Thor settings AYN's own dual-screen assistant and quick-settings panel expose as tiles. The
 * assistant keeps them in `Settings.System` (the firmware observes them) and, for the controller
 * style, in one system property; the hotkeys write exactly the same values, so the tiles stay
 * truthful. Do Not Disturb and the microphone switch go through Android's own services instead.
 */
object Ayn {
    /** The stored value that makes a hotkey ask instead of applying one option. */
    const val MODAL = "modal"

    /** One selectable value of a [Group]; [key] is what a hotkey stores, [commands] apply it as root. */
    class Option(val key: String, val labelRes: Int, val commands: String = "")

    /**
     * One hotkey action with the choice below it. [action] is the entry in the action list; its
     * [options] are picked on the row, so the list holds one entry per tile, not one per value.
     */
    enum class Group(val action: String, val titleRes: Int, val hasModal: Boolean, val options: List<Option>) {
        PERFORMANCE(
            "@performance", R.string.ayn_performance, true,
            listOf(
                Option("standard", R.string.ayn_performance_standard, "settings put system performance_mode 0"),
                Option("medium", R.string.ayn_performance_medium, "settings put system performance_mode 1"),
                Option("high", R.string.ayn_performance_high, "settings put system performance_mode 2"),
            ),
        ),
        FAN(
            "@fan", R.string.ayn_fan, true,
            listOf(
                Option("off", R.string.ayn_fan_off, "settings put system fan_mode 0"),
                Option("quiet", R.string.ayn_fan_quiet, "settings put system fan_mode 1"),
                Option("smart", R.string.ayn_fan_smart, "settings put system fan_mode 4"),
                Option("sports", R.string.ayn_fan_sports, "settings put system fan_mode 5"),
            ),
        ),
        HANDLE(
            "@handle", R.string.ayn_handle, true,
            listOf(
                Option("standard", R.string.ayn_handle_standard, handle(flip = 0, layout = 1, noLayout = 0, type = 0)),
                Option("xbox", R.string.ayn_handle_xbox, handle(flip = 1, layout = 0, noLayout = 0, type = 1)),
                Option("ban", R.string.ayn_handle_ban, handle(flip = 0, layout = 2, noLayout = 1, type = 2)),
            ),
        ),
        // Switches that screen on or off; what that means for the other one is [displayTarget].
        DISPLAY(
            "@display", R.string.ayn_display, false,
            listOf(
                Option("top", R.string.level_target_top),
                Option("bottom", R.string.level_target_bottom),
            ),
        ),
        // Toggling is first: it is what a single hotkey for this tile most likely wants.
        REFRESH_RATE(
            "@refresh_rate", R.string.ayn_refresh_rate, false,
            listOf(
                Option(REFRESH_TOGGLE, R.string.ayn_refresh_toggle),
                Option("60", R.string.ayn_refresh_60, refreshCommands(60)),
                Option("120", R.string.ayn_refresh_120, refreshCommands(120)),
            ),
        ),
        // Each option flips its own stick's light; [lightTarget] works out the new pair.
        STICK_LIGHT(
            "@stick_light", R.string.ayn_stick_light, false,
            listOf(
                Option(STICKS_BOTH, R.string.ayn_sticks_both),
                Option(STICKS_LEFT, R.string.ayn_sticks_left),
                Option(STICKS_RIGHT, R.string.ayn_sticks_right),
            ),
        ),
        ;

        /** What a hotkey does until an option is chosen. */
        val defaultOption: String get() = if (hasModal) MODAL else options.first().key

        fun isValid(key: String): Boolean = (hasModal && key == MODAL) || options.any { it.key == key }
    }

    /** A tile that flips one on/off state; most are a 0/1 `Settings.System` [key], see [toggleCommand]. */
    enum class Toggle(val action: String, val key: String, val titleRes: Int) {
        DIRECT_POWER("@direct_power", "is_charging_separation", R.string.ayn_direct_power),
        VIBRATION("@vibration", "vibrate_on", R.string.ayn_vibration),
        FORCE_LANDSCAPE("@force_landscape", "force_landscape", R.string.ayn_force_landscape),
        // The master switch of AYN's own virtual mouse, as its settings page writes it.
        VIRTUAL_MOUSE("@virtual_mouse", "global_gamepad_to_mouse_mode", R.string.ayn_virtual_mouse),
        // A `Settings.Global` value, written through the notification service like the system tile does.
        DO_NOT_DISTURB("@do_not_disturb", "zen_mode", R.string.ayn_do_not_disturb),
        // Android's own microphone privacy switch; it has no readable setting, see [microphoneBlocked].
        MUTE_MICROPHONE("@mute_microphone", "", R.string.ayn_mute_microphone),
    }

    /** Every action of this family, for [Prefs.KNOWN_ACTIONS]. */
    val ACTIONS: Set<String> by lazy { Group.values().map { it.action }.toSet() + Toggle.values().map { it.action } }

    /** Every option value any group stores, to validate a backup. */
    val OPTION_KEYS: Set<String> by lazy { Group.values().flatMap { g -> g.options.map { it.key } }.toSet() + MODAL }

    fun groupOf(action: String): Group? = Group.values().firstOrNull { it.action == action }

    fun toggleOf(action: String): Toggle? = Toggle.values().firstOrNull { it.action == action }

    fun toggleCommand(toggle: Toggle, on: Boolean): String = when (toggle) {
        // Priority mode, as the system tile switches on: alarms and the user's own exceptions still come through.
        Toggle.DO_NOT_DISTURB -> "cmd notification set_dnd ${if (on) "priority" else "off"}"
        Toggle.MUTE_MICROPHONE -> "cmd sensor_privacy ${if (on) "enable" else "disable"} \$(am get-current-user) microphone"
        else -> "settings put system ${toggle.key} ${if (on) 1 else 0}"
    }

    /** pservice returns only the first output line, so the dump arrives with all whitespace removed. */
    const val MICROPHONE_STATE_COMMAND = "dumpsys sensor_privacy | tr -d ' \\n'"

    /**
     * Whether `dumpsys sensor_privacy` reports the microphone (sensor 1) as blocked by its software
     * switch (toggle type 1); state 1 is enabled privacy, 2 disabled. Whitespace does not matter, see
     * [MICROPHONE_STATE_COMMAND]. Anything unreadable counts as free.
     */
    fun microphoneBlocked(dump: String?): Boolean {
        val compact = dump?.filterNot(Char::isWhitespace) ?: return false
        val start = Regex("""sensor=1(?!\d)""").find(compact) ?: return false
        val block = compact.substring(start.range.last + 1).substringBefore("sensor=")
        val toggle = Regex("""toggle_type=1state_type=(\d+)""").find(block) ?: return false
        return toggle.groupValues[1] == "1"
    }

    const val REFRESH_TOGGLE = "toggle"
    const val STICKS_BOTH = "both"
    const val STICKS_LEFT = "left"
    const val STICKS_RIGHT = "right"

    /** Above this, the peak rate counts as the high one; AYN stores both rates as floats. */
    private const val HIGH_REFRESH_FROM = 90f

    /** The rate a toggle switches to from the stored [peak]. */
    fun refreshTarget(peak: Float): Int = if (peak > HIGH_REFRESH_FROM) 60 else 120

    /** AYN's frame-rate tile pins both the minimum and the peak to the same rate. */
    fun refreshCommands(hz: Int): String =
        "settings put system min_refresh_rate $hz.0 && settings put system peak_refresh_rate $hz.0"

    /**
     * The stick lights after pressing [option], from AYN's `left,right` pair [current]. One stick
     * flips alone; both go dark if either was lit, else both come on, so the pair ends up matching.
     */
    fun lightTarget(option: String, current: String?): Pair<Boolean, Boolean> {
        val parts = current.orEmpty().split(',')
        val left = parts.getOrNull(0)?.trim() == "1"
        val right = parts.getOrNull(1)?.trim() == "1"
        return when (option) {
            STICKS_LEFT -> !left to right
            STICKS_RIGHT -> left to !right
            else -> (!(left || right)).let { it to it }
        }
    }

    fun lightCommand(lights: Pair<Boolean, Boolean>): String =
        "settings put system $LIGHT_KEY ${if (lights.first) 1 else 0},${if (lights.second) 1 else 0}"

    const val LIGHT_KEY = "joystick_light_enabled"

    /** The assistant's screen modes, as `dual_screen_display_mode` stores them. */
    const val MODE_DUAL = 0
    const val MODE_TOP = 1
    const val MODE_BOTTOM = 2

    /**
     * The mode that flipping [option] leads to from [mode]. Whichever screen is switched off, the
     * other one comes on, so the result is never both dark.
     */
    fun displayTarget(option: String, mode: Int): Int = when (option) {
        "top" -> if (mode == MODE_BOTTOM) MODE_DUAL else MODE_BOTTOM
        else -> if (mode == MODE_TOP) MODE_DUAL else MODE_TOP
    }

    fun displayLabel(mode: Int): Int = when (mode) {
        MODE_TOP -> R.string.ayn_display_only_top
        MODE_BOTTOM -> R.string.ayn_display_only_bottom
        else -> R.string.ayn_display_dual
    }

    /** What AYN writes to reach [mode]; the top-only route also pokes display 0 like AYN's own switch does. */
    fun displayCommands(mode: Int): String = when (mode) {
        MODE_TOP -> display(mode = MODE_TOP, lock = 1, route = 1) + " && input -d 0 tap -10 -10"
        MODE_BOTTOM -> display(mode = MODE_BOTTOM, lock = 1, route = 2)
        else -> display(mode = MODE_DUAL, lock = 0, route = 2)
    }
}

/**
 * Mirrors what AYN writes for a screen mode. [route] is where the controller goes: a single screen
 * must own it, and the key-focus lock (which the firmware turns into `persist.sys.input.enable`)
 * has to be on exactly while only one screen is lit.
 */
private fun display(mode: Int, lock: Int, route: Int): String =
    "settings put system dual_screen_display_mode $mode && settings put system enable_system_key_focus_lock $lock && " +
        "settings put system screen_focus_lock $route && setprop persist.sys.input.dispaly $route"

/** The assistant's pad-style tile keeps three settings and a property in step; so do we. */
private fun handle(flip: Int, layout: Int, noLayout: Int, type: Int): String =
    "settings put system flip_button_layout $flip && settings put system temp_abxy_layout_mode $layout && " +
        "settings put system no_create_gamepad_button_layout $noLayout && setprop persist.sys.gamepad.type $type"

/** Runs the [Ayn] actions through pservice and tells the user what changed. */
class AynControls(private val context: Context, private val show: (String) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Main thread only. The setting changes only once the queued root command has run, so a second
    // press before then must flip the last requested value, or both would write the same one.
    private val requested = HashMap<Ayn.Toggle, Boolean>()
    private val queued = HashMap<Ayn.Toggle, Int>()
    private var requestedMode: Int? = null
    private var modeQueued = 0
    private var requestedRate: Int? = null
    private var rateQueued = 0
    private var requestedLights: String? = null
    private var lightsQueued = 0

    fun apply(group: Ayn.Group, option: Ayn.Option) {
        if (group == Ayn.Group.DISPLAY) return switchDisplay(option.key)
        if (group == Ayn.Group.STICK_LIGHT) return switchLights(option)
        if (group == Ayn.Group.REFRESH_RATE && option.key == Ayn.REFRESH_TOGGLE) return toggleRefreshRate()
        run(option.commands) { ok ->
            if (ok) notice(context.getString(R.string.ayn_notice_value, context.getString(group.titleRes), context.getString(option.labelRes)))
        }
    }

    private fun toggleRefreshRate() {
        val hz = Ayn.refreshTarget(requestedRate?.toFloat() ?: Settings.System.getFloat(context.contentResolver, PEAK_RATE, 60f))
        requestedRate = hz
        rateQueued++
        run(Ayn.refreshCommands(hz)) { ok ->
            if (--rateQueued == 0) requestedRate = null
            if (ok) notice(context.getString(R.string.ayn_notice_value, context.getString(R.string.ayn_refresh_rate), context.getString(R.string.ayn_refresh_hz, hz)))
        }
    }

    private fun switchLights(option: Ayn.Option) {
        val lights = Ayn.lightTarget(option.key, requestedLights ?: Settings.System.getString(context.contentResolver, Ayn.LIGHT_KEY))
        requestedLights = "${if (lights.first) 1 else 0},${if (lights.second) 1 else 0}"
        lightsQueued++
        val on = if (option.key == Ayn.STICKS_RIGHT) lights.second else lights.first
        run(Ayn.lightCommand(lights)) { ok ->
            if (--lightsQueued == 0) requestedLights = null
            if (ok) notice(context.getString(
                R.string.ayn_notice_part, context.getString(R.string.ayn_stick_light), context.getString(option.labelRes),
                context.getString(if (on) R.string.ayn_on else R.string.ayn_off),
            ))
        }
    }

    private fun switchDisplay(option: String) {
        val mode = requestedMode ?: Settings.System.getInt(context.contentResolver, DISPLAY_MODE, Ayn.MODE_DUAL)
        val target = Ayn.displayTarget(option, mode)
        requestedMode = target
        modeQueued++
        run(Ayn.displayCommands(target)) { ok ->
            if (--modeQueued == 0) requestedMode = null
            if (ok) notice(context.getString(R.string.ayn_notice_value, context.getString(R.string.ayn_display), context.getString(Ayn.displayLabel(target))))
        }
    }

    fun toggle(toggle: Ayn.Toggle) {
        if (toggle == Ayn.Toggle.MUTE_MICROPHONE) return toggleMicrophone()
        val on = !(requested[toggle] ?: currentlyOn(toggle))
        requested[toggle] = on
        queued[toggle] = (queued[toggle] ?: 0) + 1
        val title = context.getString(toggle.titleRes)
        // The state is shown at once: it is what this press asked for, and a failure corrects it below.
        notice(context.getString(R.string.ayn_notice_value, title, context.getString(if (on) R.string.ayn_on else R.string.ayn_off)))
        run(Ayn.toggleCommand(toggle, on)) { _ ->
            val left = (queued[toggle] ?: 1) - 1
            if (left == 0) { queued.remove(toggle); requested.remove(toggle) } else queued[toggle] = left
        }
    }

    /**
     * The microphone switch can only be read as root, so the read and the flip run as one task on the
     * single worker: a second press queues behind the first and reads what that one wrote.
     */
    private fun toggleMicrophone() {
        if (!PService.cachedAvailable()) return unavailable()
        val title = context.getString(Ayn.Toggle.MUTE_MICROPHONE.titleRes)
        worker.execute {
            val on = !Ayn.microphoneBlocked(PService.exec(Ayn.MICROPHONE_STATE_COMMAND))
            val ok = ControllerFocus.commandSucceeded(PService.exec("${Ayn.toggleCommand(Ayn.Toggle.MUTE_MICROPHONE, on)} && echo OK"))
            main.post {
                if (ok) notice(context.getString(R.string.ayn_notice_value, title, context.getString(if (on) R.string.ayn_on else R.string.ayn_off)))
                else unavailable()
            }
        }
    }

    private fun currentlyOn(toggle: Ayn.Toggle): Boolean = when (toggle) {
        Ayn.Toggle.DO_NOT_DISTURB -> Settings.Global.getInt(context.contentResolver, toggle.key, 0) != 0
        else -> Settings.System.getInt(context.contentResolver, toggle.key, 0) != 0
    }

    private fun run(commands: String, onDone: (Boolean) -> Unit) {
        if (!PService.cachedAvailable()) {
            unavailable()
            main.post { onDone(false) }
            return
        }
        worker.execute {
            val ok = ControllerFocus.commandSucceeded(PService.exec("$commands && echo OK"))
            main.post {
                if (!ok) unavailable()
                onDone(ok)
            }
        }
    }

    private fun unavailable() = notice(context.getString(R.string.shoulder_action_unavailable))

    private fun notice(text: String) = show(text)

    private companion object {
        const val DISPLAY_MODE = "dual_screen_display_mode"
        const val PEAK_RATE = "peak_refresh_rate"
    }
}
