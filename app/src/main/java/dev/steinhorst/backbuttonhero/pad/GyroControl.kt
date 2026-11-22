package dev.steinhorst.backbuttonhero.pad

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import dev.steinhorst.backbuttonhero.ComboButton
import dev.steinhorst.backbuttonhero.Stick

/** The sole live sensor path. The preview receives the same mapped samples sent to the helper. */
class GyroControl(
    context: Context,
    private val send: (stick: Stick?, x: Int, y: Int) -> Unit,
    private val onStatus: (GyroStatus) -> Unit = {},
) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    // Avoid layering a fixed measured offset over a vendor's changing online bias estimate.
    // Android still applies factory/temperature compensation to UNCALIBRATED readings.
    private val gyroscope = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val handler = Handler(Looper.getMainLooper())
    private val fusion = GyroFusion()
    val source: String = listOf(gyroscope, accelerometer).joinToString("|") {
        "${it?.type}:${it?.name}:${it?.vendor}:${it?.version}"
    }
    var calibration: GyroCalibration? = null
        set(value) {
            field = value?.takeIf { it.source == source }
            fusion.configure(field)
        }
    var button: ComboButton? = null
        private set
    var settings = GyroSettings()
        private set
    /** Debug device tests inject rates but still exercise mapping and the actual helper. */
    var override: FloatArray? = null
        set(value) {
            field = value
            if (value == null) recenter()
        }
    private var status = GyroStatus()
    private var holding = false
    private var screenOn = true
    private var listening = false
    private var sentStick: Stick? = null
    private var sent = intArrayOf(0, 0)
    private var sequence = 0L
    private var measurement: GyroRest? = null
    private var measurementStart = 0L
    private var onCalibrated: ((GyroCalibration?) -> Unit)? = null
    private val calibrationTimeout = Runnable { finishCalibration(null) }
    private val staleInput = Runnable { rest(); report() }

    val available: Boolean get() = gyroscope != null && accelerometer != null
    val on: Boolean get() = button != null
    val steering: Boolean get() = on && listening && screenOn && measurement == null && (settings.holdKeyInUse == null || holding)

    fun start(button: ComboButton, settings: GyroSettings) {
        this.button = button
        holding = false
        recenter()
        update(settings)
    }

    fun update(settings: GyroSettings) {
        if (settings.rate != this.settings.rate) recenter()
        this.settings = settings
        listen()
        if (!steering) rest()
        report()
    }

    fun stop() {
        button = null
        holding = false
        finishCalibration(null)
        listen()
        rest()
        report()
    }

    fun setHeld(held: Boolean) {
        if (held == holding) return
        holding = held
        if (held) recenter() else rest()
        report()
    }

    private fun report(seconds: Float = 0f) {
        val now = GyroStatus(button?.prefKey, steering, sent[0], sent[1], seconds, sequence, measurement != null)
        if (now == status) return
        status = now
        onStatus(now)
    }

    /** Changes the reference posture, never the sensor calibration. */
    fun recenter() {
        fusion.recenter()
        rest()
        report()
    }

    /** One second to settle, then three continuous seconds of measured rest; at most twenty seconds. */
    fun calibrate(onDone: (GyroCalibration?) -> Unit) {
        finishCalibration(null)
        if (!available || !screenOn) return onDone(null)
        measurement = GyroRest()
        measurementStart = 0L
        onCalibrated = onDone
        recenter()
        handler.postDelayed(calibrationTimeout, 20_000)
        listen()
    }

    private fun finishCalibration(result: GyroCalibration?) {
        if (measurement == null) return
        measurement = null
        handler.removeCallbacks(calibrationTimeout)
        val callback = onCalibrated
        onCalibrated = null
        if (result != null) calibration = result
        recenter()
        listen()
        callback?.invoke(result)
    }

    fun setScreenOn(value: Boolean) {
        screenOn = value
        if (!value) finishCalibration(null)
        recenter()
        listen()
    }

    private fun listen() {
        val wanted = (on || measurement != null) && screenOn && available
        if (wanted == listening) return
        listening = wanted
        if (wanted) {
            val gyroRegistered = sensors.registerListener(this, gyroscope, SAMPLING_US)
            val accRegistered = sensors.registerListener(this, accelerometer, SAMPLING_US)
            if (!gyroRegistered || !accRegistered) {
                sensors.unregisterListener(this)
                listening = false
                finishCalibration(null)
                rest()
            }
        } else {
            sensors.unregisterListener(this)
            handler.removeCallbacks(staleInput)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!listening) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            fusion.acceleration(event.values, event.timestamp)
            return
        }
        if (event.sensor.type != gyroscope?.type) return
        handler.removeCallbacks(staleInput)
        handler.postDelayed(staleInput, 250)
        measurement?.let { rest ->
            if (measurementStart == 0L) measurementStart = event.timestamp
            val acc = fusion.freshAcceleration(event.timestamp)
            if (acc == null) rest.reset()
            else if (event.timestamp - measurementStart >= 1_000_000_000L) {
                rest.add(event.timestamp, event.values, acc)?.let { finishCalibration(it.copy(source = source)) }
            }
            return
        }
        if (!steering) return rest()
        val valid = fusion.step(override ?: event.values, event.timestamp, override != null)
        if (!valid || (override == null && fusion.freshAcceleration(event.timestamp) == null)) {
            rest()
            report()
            return
        }
        val turn = if (settings.rate) fusion.rates else fusion.angles()
        val rotation = displays.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
        val deflection = GyroMapper.map(settings, GyroMapper.toScreen(rotation, turn[0], turn[1], turn[2]))
        output(settings.stick, deflection[0], deflection[1])
        sequence++
        report(fusion.seconds)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun rest() = output(null, 0, 0)

    private fun output(stick: Stick?, x: Int, y: Int) {
        val target = stick.takeUnless { x == 0 && y == 0 }
        if (target == sentStick && (target == null || sent[0] == x && sent[1] == y)) return
        sentStick = target
        sent = intArrayOf(x, y)
        send(target, x, y)
    }

    companion object {
        private const val SAMPLING_US = 10_000
    }
}
