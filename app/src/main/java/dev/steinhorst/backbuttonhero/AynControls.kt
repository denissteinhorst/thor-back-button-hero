package dev.steinhorst.backbuttonhero

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * The Thor settings AYN's own dual-screen assistant exposes as tiles. The assistant keeps them in
 * `Settings.System` (the firmware observes them) and, for the controller style, in one system
 * property; the hotkeys write exactly the same values, so the assistant's tiles stay truthful.
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
                Option("top", R.string.ayn_display_top),
                Option("bottom", R.string.ayn_display_bottom),
            ),
        ),
        ;

        /** What a hotkey does until an option is chosen. */
        val defaultOption: String get() = if (hasModal) MODAL else options.first().key

        fun isValid(key: String): Boolean = (hasModal && key == MODAL) || options.any { it.key == key }
    }

    /** A tile that flips one 0/1 setting. */
    enum class Toggle(val action: String, val key: String, val titleRes: Int) {
        DIRECT_POWER("@direct_power", "is_charging_separation", R.string.ayn_direct_power),
        VIBRATION("@vibration", "vibrate_on", R.string.ayn_vibration),
        FORCE_LANDSCAPE("@force_landscape", "force_landscape", R.string.ayn_force_landscape),
    }

    /** Every action of this family, for [Prefs.KNOWN_ACTIONS]. */
    val ACTIONS: Set<String> by lazy { Group.values().map { it.action }.toSet() + Toggle.values().map { it.action } }

    /** Every option value any group stores, to validate a backup. */
    val OPTION_KEYS: Set<String> by lazy { Group.values().flatMap { g -> g.options.map { it.key } }.toSet() + MODAL }

    fun groupOf(action: String): Group? = Group.values().firstOrNull { it.action == action }

    fun toggleOf(action: String): Toggle? = Toggle.values().firstOrNull { it.action == action }

    fun toggleCommand(toggle: Toggle, on: Boolean): String = "settings put system ${toggle.key} ${if (on) 1 else 0}"

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
class AynControls(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var toast: Toast? = null

    // Main thread only. The setting changes only once the queued root command has run, so a second
    // press before then must flip the last requested value, or both would write the same one.
    private val requested = HashMap<Ayn.Toggle, Boolean>()
    private val queued = HashMap<Ayn.Toggle, Int>()
    private var requestedMode: Int? = null
    private var modeQueued = 0

    fun apply(group: Ayn.Group, option: Ayn.Option) {
        if (group == Ayn.Group.DISPLAY) return switchDisplay(option.key)
        run(option.commands) { ok ->
            if (ok) notice(context.getString(R.string.ayn_notice_value, context.getString(group.titleRes), context.getString(option.labelRes)))
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

    private fun currentlyOn(toggle: Ayn.Toggle): Boolean =
        Settings.System.getInt(context.contentResolver, toggle.key, 0) != 0

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

    private fun notice(text: String) {
        toast?.cancel()
        toast = Toast.makeText(context, text, Toast.LENGTH_SHORT).also { it.show() }
    }

    private companion object {
        const val DISPLAY_MODE = "dual_screen_display_mode"
    }
}
