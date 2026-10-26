package dev.steinhorst.backbuttonhero

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import java.util.concurrent.Executors

/** Routes the Thor's controller buttons to one display through AYN's own pservice sequence. */
class ControllerFocus(
    private val context: Context,
    private val onFailed: () -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Main thread only. The setting changes only once the queued root command has run, so a toggle
    // requested before then must resolve against the last requested value, or two quick presses
    // would both pick the same display.
    private var requested: String? = null
    private var queued = 0

    /** True when [target] resolves to the top display, from the setting AYN's own tool keeps current. */
    fun targetIsTop(target: String): Boolean = resolveValue(target, requested ?: currentValue()) == TOP_VALUE

    fun run(target: String) {
        if (!PService.cachedAvailable()) {
            // The indicator is already up by now; it must not confirm a switch that never happens.
            onFailed()
            return unavailable()
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
                "settings put system $FOCUS_SETTING $value && " +
                "input keyevent 60 && echo OK"
            val ok = PService.exec(command)?.trim()?.endsWith("OK") == true
            main.post {
                if (--queued == 0) requested = null
                if (!ok) failed()
            }
        }
    }

    private fun currentValue(): String? = Settings.System.getString(context.contentResolver, FOCUS_SETTING)

    private fun failed() {
        onFailed()
        unavailable()
    }

    private fun unavailable() {
        Toast.makeText(context, R.string.shoulder_action_unavailable, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val INPUT_DISPLAY_PROPERTY = "persist.sys.input.dispaly" // Firmware typo is intentional.
        const val FOCUS_SETTING = "screen_focus_lock"
        const val TOP_VALUE = "1"
        const val BOTTOM_VALUE = "2"

        fun resolveValue(target: String, current: String?): String = when (target) {
            Prefs.DISPLAY_TOP -> TOP_VALUE
            Prefs.DISPLAY_BOTTOM -> BOTTOM_VALUE
            else -> if (current == TOP_VALUE) BOTTOM_VALUE else TOP_VALUE
        }
    }
}
