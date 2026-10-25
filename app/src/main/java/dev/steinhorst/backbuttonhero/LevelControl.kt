package dev.steinhorst.backbuttonhero

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.Display
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * The volume and brightness actions, per screen. The top screen's volume is plain STREAM_MUSIC. The
 * bottom screen's volume is AYN's own `secondary_screen_volume_level` setting, which AYN applies as
 * soon as it changes but which Android refuses to let a normal app write; brightness is per-display
 * state only [BrightnessControl] can change. Both of those go through [PService], one at a time, so
 * fast repeated presses still step one by one.
 */
class LevelControl(private val context: Context, private val bottomDisplayId: () -> Int?) {
    private val worker = Executors.newSingleThreadExecutor()

    /** Runs a [Prefs.isLevelAction] action on [target] ([Prefs.TARGET_BOTH], top or bottom). */
    fun run(action: String, target: String) {
        val top = target != Prefs.DISPLAY_BOTTOM
        val bottom = target != Prefs.DISPLAY_TOP
        when (action) {
            Prefs.SHOULDER_VOLUME_UP, Prefs.SHOULDER_VOLUME_DOWN -> volume(action == Prefs.SHOULDER_VOLUME_UP, top, bottom)
            Prefs.SHOULDER_BRIGHTNESS_UP, Prefs.SHOULDER_BRIGHTNESS_DOWN -> brightness(action == Prefs.SHOULDER_BRIGHTNESS_UP, top, bottom)
        }
    }

    private fun volume(up: Boolean, top: Boolean, bottom: Boolean) {
        if (top) {
            context.getSystemService(AudioManager::class.java).adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI,
            )
        }
        if (!bottom) return
        // Only say so when nothing happened at all; "both" still changed the top screen.
        if (!PService.cachedAvailable()) { if (!top) unavailable(); return }
        worker.execute {
            val current = Settings.System.getInt(context.contentResolver, SECONDARY_VOLUME, -1)
            if (current < 0) return@execute
            val next = LevelSteps.nextVolume(current, up, SECONDARY_VOLUME_MAX)
            if (next != current) PService.exec("settings put system $SECONDARY_VOLUME $next")
        }
    }

    private fun brightness(up: Boolean, top: Boolean, bottom: Boolean) {
        val displays = listOfNotNull(
            Display.DEFAULT_DISPLAY.takeIf { top },
            bottomDisplayId().takeIf { bottom },
        )
        if (displays.isEmpty()) return
        if (!PService.cachedAvailable()) { unavailable(); return }
        worker.execute {
            PService.runEntryPoint(context, "BrightnessControl", if (up) "up" else "down", *displays.map(Int::toString).toTypedArray())
        }
    }

    private fun unavailable() =
        Toast.makeText(context, R.string.shoulder_action_unavailable, Toast.LENGTH_SHORT).show()

    companion object {
        /** AYN's bottom-screen volume; its slider runs 0..15 like STREAM_MUSIC. */
        private const val SECONDARY_VOLUME = "secondary_screen_volume_level"
        private const val SECONDARY_VOLUME_MAX = 15
    }
}
