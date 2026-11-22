package dev.steinhorst.backbuttonhero

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.Display
import dev.steinhorst.backbuttonhero.core.PService
import dev.steinhorst.backbuttonhero.prefs.Prefs
import java.util.concurrent.Executors

/**
 * The volume and brightness actions, per screen. The top screen's volume is plain STREAM_MUSIC. The
 * bottom screen's volume is AYN's own `secondary_screen_volume_level` setting, which AYN applies as
 * soon as it changes but which Android refuses to let a normal app write; brightness is per-display
 * state only [BrightnessControl] can change. Both of those go through [PService], one at a time, so
 * fast repeated presses still step one by one.
 */
class LevelControl(
    private val context: Context,
    private val notice: (String) -> Unit,
    private val bottomDisplayId: () -> Int?,
) {
    private val worker = Executors.newSingleThreadExecutor()
    private val queued = java.util.concurrent.atomic.AtomicInteger()

    private fun submit(job: () -> Unit) {
        queued.incrementAndGet()
        worker.execute { try { job() } finally { queued.decrementAndGet() } }
    }

    /** Runs a [Prefs.isLevelAction] action on [target] ([Prefs.TARGET_BOTH], top or bottom). */
    fun run(action: String, target: String, repeating: Boolean = false) {
        // A fast repeat must not queue root work faster than it completes, or the level keeps
        // moving after the control was released.
        if (repeating && queued.get() > 0) return
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
        submit {
            val current = Settings.System.getInt(context.contentResolver, SECONDARY_VOLUME, -1)
            if (current < 0) return@submit
            val next = LevelSteps.nextVolume(current, up, SECONDARY_VOLUME_MAX)
            if (next != current) PService.exec("settings put system $SECONDARY_VOLUME $next")
        }
    }

    /**
     * Mutes or unmutes [target] by storing each screen's volume, setting it to 0 and restoring it later.
     * The Thor's own mute of the music stream drops its level to 0 and does not bring it back, and AYN's
     * bottom-screen volume has no mute at all. With both screens the top screen's state decides, so the
     * two screens never end up flipped against each other.
     */
    fun toggleMute(target: String) {
        val top = target != Prefs.DISPLAY_BOTTOM
        val bottom = target != Prefs.DISPLAY_TOP
        val prefs = Prefs(context)
        val audio = context.getSystemService(AudioManager::class.java)
        val topLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val mute = if (top) topLevel > 0 else Settings.System.getInt(context.contentResolver, SECONDARY_VOLUME, 0) > 0
        if (top) {
            val (next, saved) = LevelSteps.mute(topLevel, mute, prefs.topVolumeBeforeMute)
            prefs.topVolumeBeforeMute = saved
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, AudioManager.FLAG_SHOW_UI)
        }
        if (!bottom) return
        if (!PService.cachedAvailable()) { if (!top) unavailable(); return }
        submit {
            val current = Settings.System.getInt(context.contentResolver, SECONDARY_VOLUME, -1)
            if (current < 0) return@submit
            val (next, saved) = LevelSteps.mute(current, mute, prefs.bottomVolumeBeforeMute)
            prefs.bottomVolumeBeforeMute = saved
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
        submit {
            PService.runEntryPoint(context, "BrightnessControl", if (up) "up" else "down", *displays.map(Int::toString).toTypedArray())
        }
    }

    private fun unavailable() = notice(context.getString(R.string.shoulder_action_unavailable))

    companion object {
        /** AYN's bottom-screen volume; its slider runs 0..15 like STREAM_MUSIC. */
        private const val SECONDARY_VOLUME = "secondary_screen_volume_level"
        private const val SECONDARY_VOLUME_MAX = 15
    }
}
