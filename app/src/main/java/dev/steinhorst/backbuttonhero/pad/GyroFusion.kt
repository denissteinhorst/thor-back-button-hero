package dev.steinhorst.backbuttonhero.pad

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** Device measurements, not user preferences. The source identifies the exact Android sensor pair. */
data class GyroCalibration(
    val source: String,
    val bias: List<Float>,
    val noise: List<Float>,
    val accelerationNoise: Float,
    val gravity: Float,
) {
    fun encode(): String = org.json.JSONObject().put("version", 1).put("source", source)
        .put("values", (bias + noise + listOf(accelerationNoise, gravity)).joinToString(",")).toString()

    companion object {
        fun decode(stored: String?): GyroCalibration? = runCatching {
            val json = org.json.JSONObject(stored ?: return null)
            if (json.getInt("version") != 1) return null
            val values = json.getString("values").split(',').map(String::toFloat)
            if (values.size != 8 || values.any { !it.isFinite() } ||
                values.take(3).any { abs(it) > GyroRest.MAX_BIAS } ||
                values.subList(3, 6).any { it !in 0f..0.006f } || values[6] !in 0f..0.05f || values[7] !in 7f..12f) return null
            GyroCalibration(json.getString("source"), values.take(3), values.subList(3, 6), values[6], values[7])
        }.getOrNull()
    }
}

/**
 * A bounded continuous rest window. Gravity may point anywhere: a sloping table is fine.
 * A quiet gyro alone cannot distinguish constant rotation from bias; the acceleration direction
 * must also stay fixed. Rotation exactly about gravity remains unobservable, so an absolute rate
 * ceiling bounds what an explicit rest calibration may accept. No window changes sensor scale.
 */
class GyroRest(private val durationNanos: Long = 3_000_000_000L) {
    private data class Sample(val time: Long, val rate: FloatArray, val acceleration: FloatArray)
    private val samples = ArrayDeque<Sample>()

    fun reset() = samples.clear()

    fun add(time: Long, rates: FloatArray, acceleration: FloatArray): GyroCalibration? {
        if (!valid(rates) || !valid(acceleration) || rates.any { abs(it) > MAX_BIAS } ||
            length(acceleration) !in 7f..12f) {
            reset()
            return null
        }
        val last = samples.lastOrNull()
        if (last != null && (time <= last.time || time - last.time > MAX_GAP_NANOS)) reset()
        val first = samples.firstOrNull()
        // Bound both total movement and individual transients, rather than accepting a steady turn.
        if (first != null && ((0..2).any { abs(rates[it] - first.rate[it]) > 0.025f } ||
                distance(acceleration, first.acceleration) > 0.12f)) reset()
        samples.addLast(Sample(time, rates.copyOf(3), acceleration.copyOf(3)))
        if (samples.size > 1000) samples.removeFirst()
        if (time - samples.first().time < durationNanos || samples.size < 30) return null
        val mean = FloatArray(3) { axis -> samples.sumOf { it.rate[axis].toDouble() }.toFloat() / samples.size }
        val acc = FloatArray(3) { axis -> samples.sumOf { it.acceleration[axis].toDouble() }.toFloat() / samples.size }
        val noise = FloatArray(3) { axis ->
            sqrt(samples.sumOf { val d = (it.rate[axis] - mean[axis]).toDouble(); d * d } / samples.size).toFloat()
        }
        val accNoise = sqrt(samples.sumOf { distance(it.acceleration, acc).toDouble().let { d -> d * d } } / samples.size).toFloat()
        val half = samples.size / 2
        val drifting = (0..2).any { axis ->
            val a = samples.take(half).sumOf { it.rate[axis].toDouble() } / half
            val b = samples.drop(half).sumOf { it.rate[axis].toDouble() } / (samples.size - half)
            abs(a - b) > 0.002
        }
        reset()
        if (drifting || noise.any { it > 0.006f } || accNoise > 0.05f) return null
        return GyroCalibration("", mean.toList(), noise.toList(), accNoise, length(acc))
    }

    companion object {
        // Broad quality limits, in SI units, independent of a device model or its output deadzone.
        const val MAX_BIAS = 0.08f
        const val MAX_GAP_NANOS = 100_000_000L
        fun valid(v: FloatArray): Boolean = v.size >= 3 && (0..2).all { v[it].isFinite() }
        fun length(v: FloatArray): Float = sqrt((0..2).sumOf { (v[it] * v[it]).toDouble() }).toFloat()
        fun distance(a: FloatArray, b: FloatArray): Float = length(FloatArray(3) { a[it] - b[it] })
    }
}

/**
 * Our sensor path for both aiming modes. Gyro integration retains all motion, before any stick
 * deadzone or clipping. Acceleration corrects only observable tilt, never heading about gravity.
 * Independently implemented from rotation/vector algebra; no external fusion implementation.
 */
class GyroFusion {
    private val tilt = GyroTilt()
    private var gravityReference: FloatArray? = null
    private var acceleration: FloatArray? = null
    private var accelerationTime = 0L
    private var lastTime = 0L
    private var previousRate: FloatArray? = null
    private var bias = FloatArray(3)
    private var profile: GyroCalibration? = null
    private val resting = GyroRest()
    var seconds = 0f
        private set
    var rates = FloatArray(3)
        private set

    fun configure(value: GyroCalibration?) {
        profile = value
        bias = value?.bias?.toFloatArray() ?: FloatArray(3)
        resting.reset()
    }

    fun recenter() {
        tilt.reset()
        gravityReference = null
        lastTime = 0L
        previousRate = null
        seconds = 0f
        resting.reset()
    }

    fun acceleration(values: FloatArray, time: Long) {
        if (time <= accelerationTime) return
        accelerationTime = time
        acceleration = values.takeIf(GyroRest::valid)?.copyOf(3)
    }

    fun freshAcceleration(time: Long): FloatArray? = acceleration?.takeIf {
        // Independent sensor queues can deliver the newer acceleration before an older gyro event.
        time - accelerationTime in -GyroRest.MAX_GAP_NANOS..GyroRest.MAX_GAP_NANOS
    }

    /** False means invalid/out-of-order data; callers must release any previous stick deflection. */
    fun step(values: FloatArray, time: Long, synthetic: Boolean = false): Boolean {
        if (!GyroRest.valid(values) || time <= lastTime) return false
        val delta = if (lastTime == 0L) 0L else time - lastTime
        lastTime = time
        seconds = if (delta in 1..GyroRest.MAX_GAP_NANOS) delta / 1e9f else 0f
        rates = FloatArray(3) { values[it] - if (synthetic) 0f else bias[it] }
        if (seconds == 0f) {
            previousRate = rates.copyOf()
            resting.reset()
            return true
        }
        val old = previousRate ?: rates
        tilt.step(FloatArray(3) { (old[it] + rates[it]) * 0.5f }, seconds)
        previousRate = rates.copyOf()
        val acc = freshAcceleration(time).takeUnless { synthetic }
        if (acc == null) {
            resting.reset()
            return true
        }
        val magnitude = GyroRest.length(acc)
        val gravity = profile?.gravity ?: 9.80665f
        if (abs(magnitude - gravity) < maxOf(0.15f, 4f * (profile?.accelerationNoise ?: 0f))) {
            val measured = FloatArray(3) { acc[it] / magnitude }
            val reference = gravityReference ?: tilt.toReference(measured).also { gravityReference = it }
            val expected = tilt.toLocal(reference)
            val error = floatArrayOf(
                measured[1] * expected[2] - measured[2] * expected[1],
                measured[2] * expected[0] - measured[0] * expected[2],
                measured[0] * expected[1] - measured[1] * expected[0],
            )
            // Large disagreement can be translation rather than gravity; never snap to it.
            if (GyroRest.length(error) < 0.25f) tilt.step(error, 1f - exp(-seconds / 0.5f))
        }
        // Auto tracking is deliberately much stricter than an explicit rest measurement. Without
        // an accepted profile there is no evidence for a sensor's noise, so do not learn motion.
        val calibrated = profile
        if (calibrated != null && (0..2).all { abs(rates[it]) < maxOf(0.0005f, minOf(0.002f, calibrated.noise[it] * 3f)) }) {
            resting.add(time, values, acc)?.let { measured ->
                // Small bounded changes compensate warming without erasing slow intentional turns.
                for (axis in 0..2) bias[axis] += (measured.bias[axis] - bias[axis]).coerceIn(-0.0002f, 0.0002f)
            }
        } else resting.reset()
        return true
    }

    fun angles(): FloatArray = tilt.angles()
}
