package dev.steinhorst.backbuttonhero.capture

import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.ActionGroups
import dev.steinhorst.backbuttonhero.ScreenRecorder
import dev.steinhorst.backbuttonhero.input.DpadDirection
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.prefs.BindingSettings
import dev.steinhorst.backbuttonhero.prefs.Prefs
import java.util.Date
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScreenRecordingTest {
    @Test
    fun `record video is a known ungrouped action with a screen choice, both screens until chosen`() {
        assertTrue(Prefs.SHOULDER_RECORD_VIDEO in Prefs.KNOWN_ACTIONS)
        assertTrue(Prefs.hasScreenTarget(Prefs.SHOULDER_RECORD_VIDEO))
        // A held button must not start and stop recordings over and over.
        assertFalse(Prefs.isLevelAction(Prefs.SHOULDER_RECORD_VIDEO))
        assertEquals(null, ActionGroups.groupOf(Prefs.SHOULDER_RECORD_VIDEO))
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        prefs.setShoulderAction(ShoulderButton.L2, Prefs.SHOULDER_RECORD_VIDEO)
        assertEquals(Prefs.TARGET_BOTH, prefs.levelTarget(ShoulderButton.L2))
    }

    @Test
    fun `recording options default to no microphone, medium quality and a dot top right`() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        assertFalse(prefs.recordMic(ShoulderButton.L1))
        assertEquals(Prefs.RECORD_MEDIUM, prefs.recordQuality(ShoulderButton.L1))
        assertTrue(prefs.recordIndicator(ShoulderButton.L1))
        assertEquals(Prefs.FOCUS_POS_TOP_RIGHT, prefs.recordPosition(ShoulderButton.L1))
        assertFalse(prefs.recordPtt(ShoulderButton.L1))
        assertEquals(0, prefs.recordPttKey(ShoulderButton.L1))
        assertEquals(Prefs.DUCK_VIDEO, prefs.recordDuck(ShoulderButton.L1))
        assertEquals(BindingSettings(""), prefs.snapshot().bindings.getValue(DpadDirection.LEFT.prefKey).copy(action = ""))
    }

    @Test
    fun `recording options round trip per button and through snapshots`() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        prefs.setRecordMic(ShoulderButton.R1, true)
        prefs.setRecordQuality(ShoulderButton.R1, Prefs.RECORD_LARGE)
        prefs.setRecordIndicator(ShoulderButton.R1, false)
        prefs.setRecordPosition(ShoulderButton.R1, Prefs.FOCUS_POS_BOTTOM_LEFT)
        prefs.setRecordPtt(ShoulderButton.R1, true)
        prefs.setRecordPttKey(ShoulderButton.R1, KeyEvent.KEYCODE_BUTTON_THUMBL)
        prefs.setRecordDuck(ShoulderButton.R1, Prefs.DUCK_DEVICE)
        val before = prefs.snapshot()
        val r1 = before.bindings.getValue(ShoulderButton.R1.prefKey)
        assertTrue(r1.recordMic)
        assertEquals(Prefs.RECORD_LARGE, r1.recordQuality)
        assertFalse(r1.recordIndicator)
        assertEquals(Prefs.FOCUS_POS_BOTTOM_LEFT, r1.recordPosition)
        assertTrue(r1.recordPtt)
        assertEquals(KeyEvent.KEYCODE_BUTTON_THUMBL, r1.recordPttKey)
        assertEquals(Prefs.DUCK_DEVICE, r1.recordDuck)
        assertFalse(prefs.recordMic(ShoulderButton.L1))

        val after = before.copy(bindings = before.bindings + (ShoulderButton.R1.prefKey to BindingSettings(
            Prefs.SHOULDER_RECORD_VIDEO, targetOverride = Prefs.DISPLAY_TOP, recordMic = false,
            recordQuality = Prefs.RECORD_SMALL, recordIndicator = true, recordPosition = Prefs.FOCUS_POS_CENTER,
            recordPtt = false, recordPttKey = KeyEvent.KEYCODE_VOLUME_DOWN, recordDuck = Prefs.DUCK_OFF,
        )))
        assertTrue(prefs.applySettings(before, after))
        assertEquals(Prefs.SHOULDER_RECORD_VIDEO, prefs.shoulderAction(ShoulderButton.R1))
        assertEquals(Prefs.DISPLAY_TOP, prefs.levelTarget(ShoulderButton.R1))
        assertFalse(prefs.recordMic(ShoulderButton.R1))
        assertEquals(Prefs.RECORD_SMALL, prefs.recordQuality(ShoulderButton.R1))
        assertTrue(prefs.recordIndicator(ShoulderButton.R1))
        assertEquals(Prefs.FOCUS_POS_CENTER, prefs.recordPosition(ShoulderButton.R1))
        assertFalse(prefs.recordPtt(ShoulderButton.R1))
        assertEquals(KeyEvent.KEYCODE_VOLUME_DOWN, prefs.recordPttKey(ShoulderButton.R1))
        assertEquals(Prefs.DUCK_OFF, prefs.recordDuck(ShoulderButton.R1))
    }

    @Test
    fun `push to talk takes any button but the app's own, the system's and the D-pad`() {
        listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_BUTTON_SELECT).forEach { assertTrue(PushToTalk.isAllowed(it)) }
        listOf(0, -1, KeyEvent.getMaxKeyCode() + 1, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_DOWN_RIGHT).forEach { assertFalse(PushToTalk.isAllowed(it)) }
    }

    @Test
    fun `push-to-talk buttons carry the controller's names, others a fallback`() {
        assertEquals("A", PushToTalk.label(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("L3", PushToTalk.label(KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertEquals("R3", PushToTalk.label(KeyEvent.KEYCODE_BUTTON_THUMBR))
        assertEquals("Select", PushToTalk.label(KeyEvent.KEYCODE_BUTTON_SELECT))
        // Android's own key names need the native lookup, which only the device has.
        assertTrue(PushToTalk.label(KeyEvent.KEYCODE_VOLUME_DOWN).isNotBlank())
    }

    @Test
    fun `quality maps to the bit rate, medium for anything unknown`() {
        assertEquals(4_000_000, ScreenRecording.bitRate(Prefs.RECORD_SMALL))
        assertEquals(10_000_000, ScreenRecording.bitRate(Prefs.RECORD_MEDIUM))
        assertEquals(20_000_000, ScreenRecording.bitRate(Prefs.RECORD_LARGE))
        assertEquals(10_000_000, ScreenRecording.bitRate("turbo"))
    }

    @Test
    fun `files are named by local time and screen in the BBH-Records folder`() {
        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("Recording_20261115-121103_both.mp4", ScreenRecording.fileName(Prefs.TARGET_BOTH, Date(1_794_744_663_000L)))
        } finally {
            TimeZone.setDefault(zone)
        }
        assertEquals("/storage/emulated/0/Videos/BBH-Records", ScreenRecording.DIRECTORY)
        assertEquals(Prefs.DISPLAY_TOP, ScreenRecorder.MODE_TOP)
        assertEquals(Prefs.DISPLAY_BOTTOM, ScreenRecorder.MODE_BOTTOM)
        assertEquals(Prefs.TARGET_BOTH, ScreenRecorder.MODE_BOTH)
    }
}
