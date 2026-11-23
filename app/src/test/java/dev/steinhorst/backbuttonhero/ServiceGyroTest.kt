package dev.steinhorst.backbuttonhero

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import dev.steinhorst.backbuttonhero.actions.AppControlPrompt
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.overlay.FocusIndicatorOverlay
import dev.steinhorst.backbuttonhero.pad.DpadOverlay
import dev.steinhorst.backbuttonhero.pad.GyroActions
import dev.steinhorst.backbuttonhero.pad.GyroControl
import dev.steinhorst.backbuttonhero.pad.GyroSettings
import dev.steinhorst.backbuttonhero.pad.PadHelper
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.recents.SwitcherOverlay
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSensor
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** The gyro actions through the service: on/off with its messages, calibration and the hold key. */
@RunWith(RobolectricTestRunner::class)
class ServiceGyroTest {
    private val button: ComboButton = ShoulderButton.R1
    private val hold = KeyEvent.KEYCODE_BUTTON_Y

    private fun withService(sensors: Boolean = true, helper: Boolean = true, check: (SwitcherService, Prefs) -> Unit) {
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        if (sensors) {
            val manager = shadowOf(service.getSystemService(SensorManager::class.java))
            manager.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE))
            manager.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))
        }
        val source = object : AppSource {
            override fun appsOnDisplay(displayId: Int) = emptyList<RunningApp>()
            override fun forget(packageName: String) = Unit
        }
        ReflectionHelpers.setField(service, "overlay", SwitcherOverlay(service, source, { true }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}))
        val pad = PadHelper(service, {}, {}, {})
        ReflectionHelpers.setField(pad, "closed", true)
        if (helper) ReflectionHelpers.setField(pad, "active", true)
        ReflectionHelpers.setField(service, "padHelper", pad)
        ReflectionHelpers.setField(service, "dpadOverlay", DpadOverlay(service, {}, {}))
        ReflectionHelpers.setField(service, "appControlPrompt", AppControlPrompt(service) { false })
        ReflectionHelpers.setField(service, "focusIndicator", FocusIndicatorOverlay(service))
        try {
            check(service, Prefs(service))
        } finally {
            service.onDestroy()
        }
    }

    private fun notices(block: () -> Unit): List<String> {
        ShadowLog.clear()
        block()
        shadowOf(Looper.getMainLooper()).idle()
        return ShadowLog.getLogsForTag("BackButtonHero").map { it.msg }.filter { it.startsWith("Notice: ") }.map { it.removePrefix("Notice: ") }
    }

    private fun gyro(service: SwitcherService) = ReflectionHelpers.callInstanceMethod<GyroControl>(service, "getGyro")

    private fun actions(service: SwitcherService) = ReflectionHelpers.callInstanceMethod<GyroActions>(service, "getGyroActions")

    private fun toggle(service: SwitcherService) = actions(service).toggle(button)

    private fun calibrate(service: SwitcherService) = actions(service).calibrate()

    private fun key(service: SwitcherService, action: Int, code: Int, device: Int = 7) = ReflectionHelpers.callInstanceMethod<Boolean>(
        service, "onKeyEvent", ClassParameter.from(KeyEvent::class.java,
            KeyEvent(100, 180, action, code, 0, 0, device, 0, 0, InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD)))

    private fun held(service: SwitcherService) = ReflectionHelpers.getField<Boolean>(gyro(service), "holding")

    @Test
    fun `without sensors both gyro actions only explain`() = withService(sensors = false) { service, _ ->
        val text = service.getString(R.string.gyro_no_sensor)
        assertEquals(listOf(text), notices { toggle(service) })
        assertEquals(listOf(text), notices { calibrate(service) })
        assertFalse(gyro(service).on)
    }

    @Test
    fun `without the helper the gyro stays off`() = withService(helper = false) { service, _ ->
        assertEquals(listOf(service.getString(R.string.gyro_helper_missing)), notices { toggle(service) })
        assertFalse(gyro(service).on)
    }

    @Test
    fun `the action turns the gyro on with the button's setup and off again`() = withService { service, prefs ->
        prefs.setGyro(button, GyroSettings(rate = true))
        assertEquals(listOf(service.getString(R.string.gyro_on)), notices { toggle(service) })
        assertTrue(gyro(service).on)
        assertEquals(button, gyro(service).button)
        assertEquals(GyroSettings(rate = true), gyro(service).settings)
        assertEquals(listOf(service.getString(R.string.gyro_off)), notices { toggle(service) })
        assertFalse(gyro(service).on)
    }

    @Test
    fun `calibration announces itself and reports a timeout as failed`() = withService { service, _ ->
        assertEquals(listOf(service.getString(R.string.gyro_calibration_started)), notices { calibrate(service) })
        ShadowLog.clear()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21))
        assertEquals(listOf("Notice: " + service.getString(R.string.gyro_calibration_failed)),
            ShadowLog.getLogsForTag("BackButtonHero").map { it.msg }.filter { it.startsWith("Notice: ") })
    }

    @Test
    fun `a calibration at rest is announced and kept`() = withService { service, prefs ->
        val manager = service.getSystemService(SensorManager::class.java)
        val rates = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)!!
        val gravity = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!
        assertNull(prefs.gyroCalibration)
        calibrate(service)
        ShadowLog.clear()
        var time = 1_000_000_000L
        repeat(600) {
            time += 10_000_000L
            gyro(service).onSensorChanged(SensorEventBuilder.newBuilder().setSensor(gravity).setTimestamp(time).setValues(floatArrayOf(0f, 0f, 9.81f)).build())
            gyro(service).onSensorChanged(SensorEventBuilder.newBuilder().setSensor(rates).setTimestamp(time).setValues(floatArrayOf(0.001f, 0f, 0f)).build())
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("Notice: " + service.getString(R.string.gyro_calibrated)),
            ShadowLog.getLogsForTag("BackButtonHero").map { it.msg }.filter { it.startsWith("Notice: ") })
        assertNotNull(prefs.gyroCalibration)
    }

    @Test
    fun `a kept-back hold key steers while any device holds it`() = withService { service, prefs ->
        prefs.setGyro(button, GyroSettings(holdKey = hold, holdPasses = false))
        toggle(service)
        assertTrue(key(service, KeyEvent.ACTION_DOWN, hold, 7))
        assertTrue(held(service))
        assertTrue(key(service, KeyEvent.ACTION_DOWN, hold, 8))
        assertTrue(key(service, KeyEvent.ACTION_UP, hold, 7))
        assertTrue("the other device still holds it", held(service))
        assertTrue(key(service, KeyEvent.ACTION_UP, hold, 8))
        assertFalse(held(service))
        assertFalse("an UP without its DOWN is not the gyro's", key(service, KeyEvent.ACTION_UP, hold, 9))
    }

    @Test
    fun `a passing hold key steers and still reaches the game`() = withService { service, prefs ->
        prefs.setGyro(button, GyroSettings(holdKey = hold, holdPasses = true))
        toggle(service)
        assertFalse(key(service, KeyEvent.ACTION_DOWN, hold))
        assertTrue(held(service))
        assertFalse(key(service, KeyEvent.ACTION_UP, hold))
        assertFalse(held(service))
    }

    @Test
    fun `with Back held the hold key stays its Back combination, and an off gyro ignores it`() = withService { service, prefs ->
        prefs.setGyro(button, GyroSettings(holdKey = hold, holdPasses = false))
        assertFalse("gyro off", key(service, KeyEvent.ACTION_DOWN, hold))
        key(service, KeyEvent.ACTION_UP, hold)
        toggle(service)
        key(service, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        key(service, KeyEvent.ACTION_DOWN, hold)
        assertFalse(held(service))
        key(service, KeyEvent.ACTION_UP, hold)
        key(service, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
    }

    @Test
    fun `turning it off forgets who held the key`() = withService { service, prefs ->
        prefs.setGyro(button, GyroSettings(holdKey = hold, holdPasses = false))
        toggle(service)
        key(service, KeyEvent.ACTION_DOWN, hold)
        toggle(service)
        toggle(service)
        assertFalse(held(service))
        assertFalse("the earlier press is forgotten", key(service, KeyEvent.ACTION_UP, hold))
    }
}
