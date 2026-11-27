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
 * Routes the Thor's controller buttons to one display through AYN's own pservice sequence.
 * [inputScreen] names the screen Auto-lock currently drives ([FocusedScreen]); it may block and runs
 * on the worker, and without it a toggle out of Auto-lock counts the top screen as driven.
 */
class ControllerFocus(
    private val context: Context,
    private val notice: (String) -> Unit,
    private val onFailed: () -> Unit,
    private val inputScreen: () -> String = { Prefs.DISPLAY_TOP },
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Main thread only. The setting changes only once the queued root command has run, so a toggle
    // requested before then must resolve against the last requested value, or two quick presses
    // would both pick the same display.
    private var requested: String? = null
    private var queued = 0
    // Main thread only. Presses that arrive while a toggle out of Auto-lock still reads its screen
    // wait here and run in order once it is known, so they flip from the screen it picks.
    private var waiting: MutableList<() -> Unit>? = null

    /**
     * True when [target] resolves to the top display, from the setting AYN's own tool keeps current.
     * A toggle out of Auto-lock is only known once [run] has read the driven screen ([needsInputScreen]).
     */
    fun targetIsTop(target: String): Boolean = resolveValue(target, requested ?: currentValue()) == TOP_VALUE

    /** True when [target] hands the controller to AYN's Auto-lock. */
    fun targetIsAuto(target: String): Boolean = target == Prefs.FOCUS_AUTO

    /** True when [run] must first read which screen Auto-lock drives: a toggle while no screen is locked. */
    fun needsInputScreen(target: String): Boolean =
        waiting != null || target == Prefs.FOCUS_TOGGLE && !isFixed(requested ?: currentValue())

    /**
     * The current route, retained before Recents temporarily takes controller input. AYN's
     * Auto-lock must survive that round trip: restoring it as a fixed screen would lock the
     * controller there until the user resets it in AYN's settings.
     */
    fun currentTarget(): String = when (requested ?: currentValue()) {
        TOP_VALUE -> Prefs.DISPLAY_TOP
        BOTTOM_VALUE -> Prefs.DISPLAY_BOTTOM
        else -> AUTO_TARGET
    }

    /**
     * Routes the controller to [target]. A toggle out of Auto-lock first reads the screen Auto-lock
     * drives and locks the other one; [onResolved] then names that fixed screen, for the indicator.
     */
    fun run(target: String, onResolved: ((String) -> Unit)? = null, onComplete: ((Boolean) -> Unit)? = null) {
        if (!PService.cachedAvailable()) {
            // The indicator is already up by now; it must not confirm a switch that never happens.
            onFailed()
            onComplete?.let { main.post { it(false) } }
            return unavailable()
        }
        waiting?.let { it += { run(target, onResolved, onComplete) }; return }
        if (target == Prefs.FOCUS_TOGGLE && !isFixed(requested ?: currentValue())) {
            waiting = mutableListOf()
            worker.execute {
                val driven = inputScreen()
                main.post {
                    val later = waiting.orEmpty()
                    waiting = null
                    val fixed = if (driven == Prefs.DISPLAY_BOTTOM) Prefs.DISPLAY_TOP else Prefs.DISPLAY_BOTTOM
                    onResolved?.invoke(fixed)
                    run(fixed, onComplete = onComplete)
                    later.forEach { it() }
                }
            }
            return
        }
        val value = resolveValue(target, requested ?: currentValue())
        requested = value
        queued++
        worker.execute {
            // Mirrors AYN's own tool (verified from its pservice log): Shift-right is deliberately
            // sent WITHOUT -d, so it reaches the currently focused display, where AYN's assistant
            // window commits the route. Sending it to the target display instead reaches the game
            // and the switch to top silently fails.
            val command = "setprop $INPUT_DISPLAY_PROPERTY $value && " +
                "settings put system $FOCUS_SETTING $value && input keyevent 60 && echo OK"
            // FocusedDisplayId describes the display owning the active Android window, not AYN's
            // controller route. It can legitimately remain on the game display after this command.
            // A successful OEM command sequence is therefore the only synchronous acknowledgement.
            val ok = commandSucceeded(PService.exec(command))
            main.post {
                if (--queued == 0) requested = null
                if (!ok) failed()
                onComplete?.invoke(ok)
            }
        }
    }

    private fun currentValue(): String? = Settings.System.getString(context.contentResolver, FOCUS_SETTING)

    private fun isFixed(value: String?) = value == TOP_VALUE || value == BOTTOM_VALUE

    private fun failed() {
        onFailed()
        unavailable()
    }

    private fun unavailable() {
        notice(context.getString(R.string.shoulder_action_unavailable))
    }

    companion object {
        const val INPUT_DISPLAY_PROPERTY = "persist.sys.input.dispaly" // Firmware typo is intentional.
        const val FOCUS_SETTING = "screen_focus_lock"
        const val TOP_VALUE = "1"
        const val BOTTOM_VALUE = "2"
        /** AYN's "Auto-lock": the route follows the screen last touched. */
        const val AUTO_VALUE = "0"
        /** Auto-lock as a target: the stored [Prefs.FOCUS_AUTO], and the restore target of a borrowed route. */
        const val AUTO_TARGET = Prefs.FOCUS_AUTO

        fun commandSucceeded(output: String?): Boolean = output?.trim()?.endsWith("OK") == true

        fun resolveValue(target: String, current: String?): String = when (target) {
            Prefs.DISPLAY_TOP -> TOP_VALUE
            Prefs.DISPLAY_BOTTOM -> BOTTOM_VALUE
            AUTO_TARGET -> AUTO_VALUE
            else -> if (current == TOP_VALUE) BOTTOM_VALUE else TOP_VALUE
        }
    }
}
