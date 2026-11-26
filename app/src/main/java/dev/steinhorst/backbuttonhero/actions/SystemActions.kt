package dev.steinhorst.backbuttonhero.actions

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.overlay.NoticeOverlay

/**
 * The plain system actions: media keys for whatever app plays, Android's settings pages (with a
 * fallback page and a message when neither opens), and the one-line battery status notice.
 */
class SystemActions(private val context: Context, private val notice: NoticeOverlay) {
    fun sendMediaKey(keyCode: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    /** One line of battery facts as a toast on the top screen, the one AYN's assistant leaves visible. */
    fun showStatus() {
        val battery = context.getSystemService(BatteryManager::class.java)
        // Sticky: no receiver is registered, the last battery broadcast is only read.
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val tenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val minutes = if (plugged) {
            battery.computeChargeTimeRemaining().takeIf { it > 0 }?.let { (it / 60_000L).toInt() }
        } else {
            StatusSummary.minutesLeft(
                battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            )
        }
        fun duration(m: Int) = if (m >= 60) context.getString(R.string.status_hours_minutes, m / 60, m % 60) else context.getString(R.string.status_minutes, m)
        val parts = listOfNotNull(
            context.getString(R.string.status_battery, level),
            when {
                plugged && minutes != null -> context.getString(R.string.status_full_in, duration(minutes))
                plugged -> context.getString(R.string.status_plugged)
                minutes != null -> context.getString(R.string.status_left, duration(minutes))
                else -> null
            },
            if (tenths == Int.MIN_VALUE) null else context.getString(R.string.status_temperature, tenths / 10f),
        )
        notice.show(parts.joinToString(" · "), long = true)
    }

    fun openSystemSettings(action: String, fallback: String = Settings.ACTION_SETTINGS, data: Uri? = null) {
        val primary = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { this.data = data }
        val fallbackIntent = Intent(fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(primary) }
            .recoverCatching { context.startActivity(fallbackIntent) }
            .onFailure { notice.show(R.string.shoulder_action_unavailable) }
    }
}
