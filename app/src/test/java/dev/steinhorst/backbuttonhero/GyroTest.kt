package dev.steinhorst.backbuttonhero

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.view.KeyEvent
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class GyroTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ---- settings -------------------------------------------------------------------------------

    @Test
    fun `settings survive their stored line and the defaults read back from nothing`() {
        val custom = GyroSettings(
            stick = Stick.LEFT, axis = GyroAxis.ROLL, rate = true, holdKey = KeyEvent.KEYCODE_BUTTON_L2, holdPasses = false,
            sensitivity = 150, deadzone = 30, minOutput = 20, invertX = true, invertY = true,
        )
        assertEquals(custom, GyroSettings.decode(custom.encode()))
        assertEquals(GyroSettings(), GyroSettings.decode(null))
        assertEquals(GyroSettings(), GyroSettings.decode(""))
    }

    @Test
    fun `a damaged line keeps every field it can read`() {
        val read = GyroSettings.decode("stick=X,axis=spin,key=4,pass=0,sens=999,dz=-1,min=60,ix=1,iy=2,junk,=,sens")
        assertEquals(GyroSettings(holdPasses = false, invertX = true), read)
        assertEquals(KeyEvent.KEYCODE_BUTTON_R1, GyroSettings.decode("key=${KeyEvent.KEYCODE_BUTTON_R1}").holdKey)
        assertEquals(0, GyroSettings.decode("key=abc").holdKey)
    }

    @Test
    fun `the canonical line is null for defaults and complete otherwise`() {
        assertNull(GyroSettings.canonical(null))
        assertNull(GyroSettings.canonical("sens=999"))
        assertEquals(GyroSettings(sensitivity = 150).encode(), GyroSettings.canonical("sens=150"))
    }

    @Test
    fun `holding needs a usable key`() {
        assertNull(GyroSettings().holdKeyInUse)
        assertNull(GyroSettings(holdKey = KeyEvent.KEYCODE_BACK).holdKeyInUse)
        assertEquals(KeyEvent.KEYCODE_BUTTON_L2, GyroSettings(holdKey = KeyEvent.KEYCODE_BUTTON_L2).holdKeyInUse)
    }

    @Test
    fun `the calibrated rest is averaged, stored as a line and subtracted`() {
        val bias = GyroBias.average(listOf(floatArrayOf(0.01f, -0.02f, 0f), floatArrayOf(0.03f, 0f, 0.02f)))
        assertArrayEquals(floatArrayOf(0.02f, -0.01f, 0.01f), bias, 1e-6f)
        assertArrayEquals(bias, GyroBias.decode(GyroBias.encode(bias)), 0f)
        assertArrayEquals(FloatArray(3), GyroBias.average(emptyList()), 0f)
        assertArrayEquals(FloatArray(3), GyroBias.decode(null), 0f)
        assertArrayEquals(FloatArray(3), GyroBias.decode("1,2"), 0f)
        assertArrayEquals(FloatArray(3), GyroBias.decode("1,NaN,2"), 0f)
        assertArrayEquals(floatArrayOf(0.98f, 0.01f, -0.01f), GyroBias.apply(floatArrayOf(1f, 0f, 0f), bias), 1e-6f)
    }

    // ---- mapping --------------------------------------------------------------------------------

    @Test
    fun `rates are turned into the screen's frame for every rotation`() {
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), GyroMapper.toScreen(Surface.ROTATION_0, 1f, 2f, 3f), 0f)
        assertArrayEquals(floatArrayOf(-2f, 1f, 3f), GyroMapper.toScreen(Surface.ROTATION_90, 1f, 2f, 3f), 0f)
        assertArrayEquals(floatArrayOf(-1f, -2f, 3f), GyroMapper.toScreen(Surface.ROTATION_180, 1f, 2f, 3f), 0f)
        assertArrayEquals(floatArrayOf(2f, -1f, 3f), GyroMapper.toScreen(Surface.ROTATION_270, 1f, 2f, 3f), 0f)
    }

    @Test
    fun `swinging left moves the stick left and tipping back moves it up`() {
        // 1 rad/s is 57.3 °/s; less the 1.5 °/s deadzone, at 100 °/s for a full deflection.
        assertArrayEquals(intArrayOf(-558, 0), GyroMapper.map(GyroSettings(rate = true), floatArrayOf(0f, 1f, 0f)))
        assertArrayEquals(intArrayOf(0, 271), GyroMapper.map(GyroSettings(rate = true), floatArrayOf(0.5f, 0f, 0f)))
        assertArrayEquals(intArrayOf(0, -271), GyroMapper.map(GyroSettings(rate = true), floatArrayOf(-0.5f, 0f, 0f)))
    }

    @Test
    fun `steering uses the outward axis instead`() {
        val roll = GyroSettings(rate = true, axis = GyroAxis.ROLL)
        assertArrayEquals(intArrayOf(-558, 0), GyroMapper.map(roll, floatArrayOf(0f, 0f, 1f)))
        assertArrayEquals(intArrayOf(0, 0), GyroMapper.map(roll, floatArrayOf(0f, 1f, 0f)))
    }

    @Test
    fun `inverting flips each axis`() {
        val inverted = GyroSettings(rate = true, invertX = true, invertY = true)
        assertArrayEquals(intArrayOf(558, 0), GyroMapper.map(inverted, floatArrayOf(0f, 1f, 0f)))
        assertArrayEquals(intArrayOf(0, -271), GyroMapper.map(inverted, floatArrayOf(0.5f, 0f, 0f)))
    }

    @Test
    fun `tremor inside the deadzone is nothing and fast turns stop at a full deflection`() {
        assertArrayEquals(intArrayOf(0, 0), GyroMapper.map(GyroSettings(rate = true), floatArrayOf(0.02f, 0.01f, 0f)))
        assertArrayEquals(intArrayOf(-1000, 0), GyroMapper.map(GyroSettings(rate = true, sensitivity = 300), floatArrayOf(0f, 2f, 0f)))
    }

    @Test
    fun `the minimum deflection lifts slow turns past a game's own deadzone`() {
        // 0.05 rad/s right is 2.86 °/s: 1.36 °/s past the deadzone is 1.4 %, lifted onto the 20 % floor.
        assertArrayEquals(intArrayOf(211, 0), GyroMapper.map(GyroSettings(rate = true, minOutput = 20), floatArrayOf(0f, -0.05f, 0f)))
        assertArrayEquals(intArrayOf(0, 0), GyroMapper.map(GyroSettings(rate = true, minOutput = 20), floatArrayOf(0f, 0f, 0f)))
        assertArrayEquals(intArrayOf(1000, 0), GyroMapper.map(GyroSettings(rate = true, minOutput = 20, sensitivity = 300), floatArrayOf(0f, -3f, 0f)))
    }


    // ---- tilting --------------------------------------------------------------------------------

    private fun GyroTilt.turn(x: Float, y: Float, z: Float, seconds: Float, steps: Int = 10) =
        repeat(steps) { step(floatArrayOf(x, y, z), seconds / steps) }

    @Test
    fun `a tilt reads as its angle and returning to the posture reads as zero, whatever the order`() {
        val tilt = GyroTilt()
        tilt.turn(0.5f, 0f, 0f, 0.2f)
        assertArrayEquals(floatArrayOf(0.1f, 0f, 0f), tilt.angles(), 1e-5f)
        // Tipping and swinging, then undoing both in the other order: per-axis sums would agree, but
        // the posture differs, so it must not read as the middle.
        tilt.turn(0f, 0.5f, 0f, 0.4f)
        tilt.turn(-0.5f, 0f, 0f, 0.2f)
        tilt.turn(0f, -0.5f, 0f, 0.4f)
        assertTrue(tilt.angles().any { kotlin.math.abs(it) > 1e-3f })
        // Retracing the path backwards, in many small readings, lands exactly on the posture.
        tilt.turn(0f, 0.5f, 0f, 0.4f, 150)
        tilt.turn(0.5f, 0f, 0f, 0.2f, 150)
        tilt.turn(0f, -0.5f, 0f, 0.4f, 150)
        tilt.turn(-0.5f, 0f, 0f, 0.2f, 150)
        assertArrayEquals(FloatArray(3), tilt.angles(), 1e-5f)
        // Large turns keep their size, and a still reading changes nothing.
        tilt.turn(0f, 0f, 1f, 2f)
        tilt.step(FloatArray(3), 1f)
        assertArrayEquals(floatArrayOf(0f, 0f, 2f), tilt.angles(), 1e-4f)
        tilt.reset()
        assertArrayEquals(FloatArray(3), tilt.angles(), 0f)
    }

    @Test
    fun `tilting maps the angle, with a full deflection at 30 degrees`() {
        val degree = 0.017453292f
        // 15° past the 1.5° deadzone is 13.5° of 30°.
        assertArrayEquals(intArrayOf(0, 450), GyroMapper.map(GyroSettings(), floatArrayOf(15 * degree, 0f, 0f)))
        assertArrayEquals(intArrayOf(-450, 0), GyroMapper.map(GyroSettings(), floatArrayOf(0f, 15 * degree, 0f)))
        assertArrayEquals(intArrayOf(0, 0), GyroMapper.map(GyroSettings(), floatArrayOf(degree, 0f, 0f)))
        assertArrayEquals(intArrayOf(0, 1000), GyroMapper.map(GyroSettings(), floatArrayOf(45 * degree, 0f, 0f)))
    }


    private fun about(axis: Int, angle: Float): FloatArray =
        FloatArray(4).also { it[0] = kotlin.math.cos(angle / 2); it[axis + 1] = kotlin.math.sin(angle / 2) }

    private fun times(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
        a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
        a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
        a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0],
    )

    /** A game rotation vector reading of [q]: x, y, z, then w. */
    private fun reading(q: FloatArray) = floatArrayOf(q[1], q[2], q[3], q[0])

    @Test
    fun `the fused posture reads the turn from the zero posture in the handheld's own frame`() {
        val still = about(0, 0f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0.3f), GyroPosture.angles(still, GyroPosture.quaternion(reading(about(2, 0.3f)))), 1e-5f)
        // Readings may leave out w, and either sign of a quaternion is the same posture.
        val short = reading(about(1, 0.4f)).copyOf(3)
        assertArrayEquals(floatArrayOf(0f, 0.4f, 0f), GyroPosture.angles(still, GyroPosture.quaternion(short)), 1e-5f)
        assertArrayEquals(floatArrayOf(0f, 0.4f, 0f), GyroPosture.angles(still, about(1, 0.4f).map { -it }.toFloatArray()), 1e-5f)
        assertArrayEquals(FloatArray(3), GyroPosture.angles(about(2, 0.3f), about(2, 0.3f)), 1e-5f)
        // From a tipped zero posture, a swing reads as a swing around the handheld's own axis.
        val tipped = about(0, 0.4f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0.2f), GyroPosture.angles(tipped, times(tipped, about(2, 0.2f))), 1e-5f)
    }

    // ---- the running gyro -----------------------------------------------------------------------

    private data class Sent(val stick: Stick?, val x: Int, val y: Int)
    private class Inputs(context: Context) {
        val manager = shadowOf(context.getSystemService(SensorManager::class.java))
        val gyro = ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE).also { manager.addSensor(it) }
        val accel = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER).also { manager.addSensor(it) }
        var time = 1_000_000_000L
        fun event(sensor: Sensor, values: FloatArray, timestamp: Long = time): SensorEvent =
            SensorEventBuilder.newBuilder().setSensor(sensor).setValues(values).setTimestamp(timestamp).build()
        fun sample(control: GyroControl, y: Float = 0f, dt: Long = 10_000_000L) {
            time += dt
            control.onSensorChanged(event(accel, floatArrayOf(0f, 0f, 9.81f)))
            control.onSensorChanged(event(gyro, floatArrayOf(0f, y, 0f)))
        }
    }

    @Test
    fun `missing sensors cannot start calibration and report failure`() {
        val control = GyroControl(context, { _, _, _ -> })
        assertFalse(control.available)
        var called = false
        control.calibrate { called = true; assertNull(it) }
        assertTrue(called)
        control.start(ShoulderButton.L1, GyroSettings())
        assertTrue(control.on)
        control.stop()
        assertFalse(control.on)
    }

    @Test
    fun `mapped sensor samples reach helper and preview together and stopping releases the stick`() {
        val inputs = Inputs(context)
        val sent = mutableListOf<Sent>()
        val statuses = mutableListOf<GyroStatus>()
        val control = GyroControl(context, { stick, x, y -> sent += Sent(stick, x, y) }, statuses::add)
        inputs.sample(control, 1f)
        assertTrue(sent.isEmpty())
        assertTrue(control.available)
        control.start(ShoulderButton.L1, GyroSettings(rate = true))
        assertEquals(ShoulderButton.L1, control.button)
        assertTrue(inputs.manager.hasListener(control))
        inputs.sample(control, 1f)
        inputs.sample(control, 1f)
        assertEquals(listOf(Sent(Stick.RIGHT, -558, 0)), sent)
        assertEquals(-558, statuses.last().x)
        assertEquals(0.01f, statuses.last().seconds)
        control.update(GyroSettings(rate = true, stick = Stick.LEFT))
        inputs.sample(control, 1f)
        assertEquals(Sent(Stick.LEFT, -558, 0), sent.last())
        control.stop()
        assertEquals(Sent(null, 0, 0), sent.last())
        assertFalse(inputs.manager.hasListener(control))
        assertFalse(statuses.last().steering)
        control.onAccuracyChanged(inputs.gyro, SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
    }

    @Test
    fun `hold screen and settings transitions immediately release previous input`() {
        val inputs = Inputs(context)
        val sent = mutableListOf<Sent>()
        val control = GyroControl(context, { stick, x, y -> sent += Sent(stick, x, y) })
        val settings = GyroSettings(rate = true, holdKey = KeyEvent.KEYCODE_BUTTON_L2)
        control.start(FaceButton.X, settings)
        inputs.sample(control, 1f)
        assertTrue(sent.isEmpty())
        control.setHeld(true)
        control.setHeld(true)
        inputs.sample(control, 1f)
        assertTrue(control.steering)
        control.setHeld(false)
        assertEquals(Sent(null, 0, 0), sent.last())
        control.setHeld(true)
        inputs.sample(control, 1f)
        control.setScreenOn(false)
        assertFalse(control.steering)
        assertEquals(Sent(null, 0, 0), sent.last())
        var failed = false
        control.calibrate { failed = it == null }
        assertTrue(failed)
        control.setScreenOn(true)
        assertTrue(inputs.manager.hasListener(control))
        inputs.sample(control, 1f)
        control.update(GyroSettings())
        control.recenter()
        inputs.sample(control)
        assertEquals(Sent(null, 0, 0), sent.last())
        control.stop()
    }

    @Test
    fun `calibration waits for continuous measured rest and is separate from recentering`() {
        val inputs = Inputs(context)
        val control = GyroControl(context, { _, _, _ -> })
        var result: GyroCalibration? = null
        control.calibrate { result = it }
        repeat(100) { inputs.sample(control, 0.01f) }
        assertNull(result)
        repeat(310) { inputs.sample(control, 0.01f) }
        assertEquals(0.01f, result!!.bias[1], 1e-6f)
        assertEquals(control.source, result!!.source)
        assertEquals(result, control.calibration)
        assertFalse(inputs.manager.hasListener(control))
        control.recenter()
        assertEquals(result, control.calibration)
        // A profile from a different Android sensor pair must never be applied.
        control.calibration = result!!.copy(source = "another sensor")
        assertNull(control.calibration)
        control.calibration = result
        control.start(ShoulderButton.L1, GyroSettings(rate = true, deadzone = 0))
        inputs.sample(control, 0.01f)
        control.stop()
    }

    @Test
    fun `calibration can be cancelled timed out or retried without overwriting a good profile`() {
        val inputs = Inputs(context)
        val control = GyroControl(context, { _, _, _ -> })
        val profile = GyroCalibration(control.source, listOf(0f, 0f, 0f), listOf(0f, 0f, 0f), 0f, 9.81f)
        control.calibration = profile
        var failures = 0
        control.calibrate { if (it == null) failures++ }
        control.calibrate { if (it == null) failures++ }
        assertEquals(1, failures)
        // No acceleration stream cannot establish rest, even with a quiet gyroscope.
        repeat(410) {
            inputs.time += 10_000_000
            control.onSensorChanged(inputs.event(inputs.gyro, FloatArray(3)))
        }
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(20))
        assertEquals(2, failures)
        assertEquals(profile, control.calibration)
        control.calibrate { if (it == null) failures++ }
        control.setScreenOn(false)
        assertEquals(3, failures)
        control.setScreenOn(true)
        control.calibrate { if (it == null) failures++ }
        control.stop()
        assertEquals(4, failures)
    }

    @Test
    fun `invalid stale and unrelated sensor input cannot keep a stick held`() {
        val inputs = Inputs(context)
        val sent = mutableListOf<Sent>()
        val control = GyroControl(context, { stick, x, y -> sent += Sent(stick, x, y) })
        control.start(ShoulderButton.L1, GyroSettings(rate = true))
        inputs.sample(control, 1f)
        control.onSensorChanged(inputs.event(inputs.gyro, floatArrayOf(Float.NaN, 0f, 0f), inputs.time + 1))
        assertEquals(Sent(null, 0, 0), sent.last())
        inputs.sample(control, 1f)
        control.onSensorChanged(inputs.event(inputs.gyro, floatArrayOf(0f, 1f, 0f), inputs.time + 200_000_000))
        assertEquals(Sent(null, 0, 0), sent.last())
        inputs.time += 300_000_000
        inputs.sample(control, 1f)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
        assertEquals(Sent(null, 0, 0), sent.last())
        val posture = ShadowSensor.newInstance(Sensor.TYPE_GAME_ROTATION_VECTOR)
        control.onSensorChanged(inputs.event(posture, floatArrayOf(1f, 0f, 0f)))
        assertEquals(Sent(null, 0, 0), sent.last())
        control.stop()
    }

    @Test
    fun `device test rates bypass physical acceleration but use the real output path`() {
        val inputs = Inputs(context)
        val sent = mutableListOf<Sent>()
        val control = GyroControl(context, { stick, x, y -> sent += Sent(stick, x, y) })
        control.start(ShoulderButton.L1, GyroSettings())
        control.override = floatArrayOf(0f, 1f, 0f)
        repeat(20) { inputs.sample(control) }
        assertTrue(sent.last().x < 0)
        control.override = FloatArray(3)
        repeat(10) { inputs.sample(control) }
        val held = sent.last()
        repeat(10) { inputs.sample(control) }
        assertEquals(held, sent.last())
        control.override = null
        assertEquals(Sent(null, 0, 0), sent.last())
        control.stop()
    }

    @Test
    fun `sensor registration failure cancels calibration and releases listeners`() {
        val inputs = Inputs(context)
        inputs.manager.setForceListenersToFail(true)
        val control = GyroControl(context, { _, _, _ -> })
        var failed = false
        control.calibrate { failed = it == null }
        assertTrue(failed)
        assertFalse(inputs.manager.hasListener(control))
        control.start(ShoulderButton.L1, GyroSettings())
        assertFalse(inputs.manager.hasListener(control))
        control.stop()
    }

    @Test
    fun `uncalibrated source is preferred and its vendor bias estimate is not subtracted again`() {
        val inputs = Inputs(context)
        val raw = ShadowSensor.newInstance(Sensor.TYPE_GYROSCOPE_UNCALIBRATED).also { inputs.manager.addSensor(it) }
        val sent = mutableListOf<Sent>()
        val control = GyroControl(context, { stick, x, y -> sent += Sent(stick, x, y) })
        assertNull(control.override)
        control.start(ShoulderButton.L1, GyroSettings(rate = true))
        assertTrue(inputs.manager.hasListener(control, raw))
        assertFalse(inputs.manager.hasListener(control, inputs.gyro))
        inputs.sample(control, 1f)
        assertTrue(sent.isEmpty())
        control.onSensorChanged(inputs.event(raw, floatArrayOf(0f, 1f, 0f, 0f, 0.5f, 0f)))
        assertEquals(Sent(Stick.RIGHT, -558, 0), sent.last())
        control.stop()
    }

    // ---- preferences and backups ----------------------------------------------------------------

    @Test
    fun `a hotkey's setup is stored, defaults as nothing, and any hotkey counts for the helper`() {
        val prefs = Prefs(context)
        assertEquals(GyroSettings(), prefs.gyro(ShoulderButton.L1))
        assertFalse(prefs.hasGyroAction())
        prefs.setGyro(ShoulderButton.L1, GyroSettings(sensitivity = 200))
        assertEquals(200, prefs.gyro(ShoulderButton.L1).sensitivity)
        assertEquals(GyroSettings(sensitivity = 200).encode(), prefs.snapshot().bindings.getValue(ShoulderButton.L1.prefKey).gyro)
        prefs.setGyro(ShoulderButton.L1, GyroSettings())
        assertNull(prefs.snapshot().bindings.getValue(ShoulderButton.L1.prefKey).gyro)
        assertNull(prefs.gyroCalibration)
        val calibration = GyroCalibration("sensor pair", listOf(0.01f, 0f, 0f), listOf(0.001f, 0.001f, 0.001f), 0.01f, 9.81f)
        prefs.gyroCalibration = calibration
        assertEquals(calibration, prefs.gyroCalibration)
        prefs.gyroCalibration = null
        assertNull(prefs.gyroCalibration)
        assertArrayEquals(FloatArray(3), prefs.gyroBias, 0f)
        prefs.gyroBias = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f), prefs.gyroBias, 0f)
        prefs.setShoulderAction(BackGestureButton.DOUBLE, Prefs.SHOULDER_GYRO)
        assertTrue(prefs.hasGyroAction())
        assertTrue(Prefs.SHOULDER_GYRO in Prefs.KNOWN_ACTIONS)
        assertTrue(Prefs.SHOULDER_GYRO_CALIBRATE in Prefs.KNOWN_ACTIONS)
    }

    @Test
    fun `device calibration survives defaults and backup restore but never travels in a backup`() {
        val prefs = Prefs(context)
        prefs.gyroBias = floatArrayOf(0.04f, 0f, 0f)
        assertNull("legacy offsets have no quality evidence", prefs.gyroCalibration)
        val calibration = GyroCalibration("local sensors", listOf(0.01f, 0f, 0f), listOf(0.001f, 0.001f, 0.001f), 0.01f, 9.81f)
        prefs.gyroCalibration = calibration
        prefs.setGyro(ShoulderButton.L1, GyroSettings(sensitivity = 200))
        val encoded = SettingsBackup.write(prefs.snapshot(), "1.0.0", 1, Instant.EPOCH)
        assertFalse(encoded.contains("local sensors"))
        assertFalse(encoded.contains("gyro_calibration"))
        assertTrue(prefs.applySettings(prefs.snapshot(), SettingsState()))
        assertEquals(calibration, Prefs(context).gyroCalibration)
        val loaded = SettingsBackup.read(encoded) as SettingsBackup.Result.Loaded
        assertTrue(prefs.applySettings(prefs.snapshot(), loaded.state))
        assertEquals(calibration, prefs.gyroCalibration)
        assertEquals(200, prefs.gyro(ShoulderButton.L1).sensitivity)
    }

    @Test
    fun `applying a plan writes the setup`() {
        val prefs = Prefs(context)
        val before = prefs.snapshot()
        val line = GyroSettings(stick = Stick.LEFT).encode()
        val after = before.copy(bindings = before.bindings.mapValues { (key, value) ->
            if (key == FaceButton.Y.prefKey) value.copy(action = Prefs.SHOULDER_GYRO, gyro = line) else value
        })
        assertTrue(prefs.applySettings(before, after))
        assertEquals(Stick.LEFT, prefs.gyro(FaceButton.Y).stick)
    }

    @Test
    fun `backups keep the setup and read a damaged one field by field`() {
        val line = GyroSettings(axis = GyroAxis.ROLL, invertY = true).encode()
        val state = SettingsState().let { factory ->
            factory.copy(bindings = factory.bindings.mapValues { (key, value) ->
                if (key == DpadDirection.RIGHT.prefKey) value.copy(action = Prefs.SHOULDER_GYRO, gyro = line) else value
            })
        }
        val loaded = SettingsBackup.read(SettingsBackup.write(state, "1.0.0", 1, Instant.EPOCH)) as SettingsBackup.Result.Loaded
        assertEquals(state.bindings.getValue(DpadDirection.RIGHT.prefKey), loaded.state.bindings.getValue(DpadDirection.RIGHT.prefKey))
        val damaged = """{"format":"${SettingsBackup.FORMAT}","settings":{"bindings":{
            "l1":{"action":"@gyro","gyro":"sens=999,iy=1"},"r1":{"action":"@gyro","gyro":"stick=Q"}}}}"""
        val read = (SettingsBackup.read(damaged) as SettingsBackup.Result.Loaded).state
        assertEquals(GyroSettings(invertY = true).encode(), read.bindings.getValue("l1").gyro)
        assertNull(read.bindings.getValue("r1").gyro)
    }
}
