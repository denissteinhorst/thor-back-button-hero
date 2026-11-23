package dev.steinhorst.backbuttonhero.actions

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.overlay.NoticeOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
class SystemActionsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun notices() = ShadowLog.getLogsForTag("BackButtonHero").map { it.msg }.filter { it.startsWith("Notice: ") }

    @Test
    fun `a media key is one DOWN and one UP of that key`() {
        val actions = SystemActions(app, NoticeOverlay(app))
        actions.sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        val events = shadowOf(app.getSystemService(AudioManager::class.java)).dispatchedMediaKeyEvents
        assertEquals(listOf(KeyEvent.ACTION_DOWN to KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_UP to KeyEvent.KEYCODE_MEDIA_NEXT),
            events.map { it.action to it.keyCode })
    }

    @Test
    fun `a settings page opens as a new task, its fallback when it is refused, else a message`() {
        var refuse = 0
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                if (refuse-- > 0) throw android.content.ActivityNotFoundException()
                super.startActivity(intent)
            }
        }
        val actions = SystemActions(context, NoticeOverlay(app))
        actions.openSystemSettings(Settings.ACTION_SOUND_SETTINGS)
        shadowOf(app).nextStartedActivity.let {
            assertEquals(Settings.ACTION_SOUND_SETTINGS, it.action)
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, it.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        refuse = 1
        actions.openSystemSettings(Settings.ACTION_NIGHT_DISPLAY_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS)
        assertEquals(Settings.ACTION_DISPLAY_SETTINGS, shadowOf(app).nextStartedActivity.action)
        refuse = 2
        ShadowLog.clear()
        actions.openSystemSettings(Settings.ACTION_CAST_SETTINGS)
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(1, notices().size)
    }

    @Test
    fun `the status notice names the battery level`() {
        ShadowLog.clear()
        SystemActions(app, NoticeOverlay(app)).showStatus()
        assertEquals(1, notices().size)
        assertEquals(true, notices().single().startsWith("Notice: Battery "))
    }

    @Test
    fun `plugged in with a known charge time, the notice adds when it is full and the temperature`() {
        @Suppress("DEPRECATION")
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB)
            .putExtra(BatteryManager.EXTRA_TEMPERATURE, 312))
        shadowOf(app.getSystemService(BatteryManager::class.java)).setChargeTimeRemaining(65 * 60_000L)
        ShadowLog.clear()
        SystemActions(app, NoticeOverlay(app)).showStatus()
        val level = app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val expected = listOf(
            app.getString(R.string.status_battery, level),
            app.getString(R.string.status_full_in, app.getString(R.string.status_hours_minutes, 1, 5)),
            app.getString(R.string.status_temperature, 31.2f),
        ).joinToString(" · ")
        assertEquals("Notice: $expected", notices().single())
    }
}
