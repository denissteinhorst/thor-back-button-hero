package dev.steinhorst.backbuttonhero

import android.view.Surface
import dev.steinhorst.backbuttonhero.capture.PushToTalk
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Which turn of the handheld moves the stick sideways. */
enum class GyroAxis(val wire: String) {
    /** Swinging it left and right, like turning on the spot. */
    YAW("yaw"),
    /** Tilting it like a steering wheel. */
    ROLL("roll"),
}

/**
 * The settings of one gyro hotkey. They are stored as a single line ([encode]) so a hotkey's gyro
 * setup travels through previews, resets and backups as one value; [decode] falls back to the default
 * for every field it cannot read, so a damaged or older line never loses the rest.
 */
data class GyroSettings(
    val stick: Stick = Stick.RIGHT,
    val axis: GyroAxis = GyroAxis.YAW,
    /**
     * Whether the stick follows how fast the handheld turns, like a camera, instead of how far it is
     * tilted from the zero posture. Tilting is the default: back in the posture, the stick is back in
     * the middle.
     */
    val rate: Boolean = false,
    /** The key that has to be held for the gyro to steer; 0 for none, then it steers whenever it is on. */
    val holdKey: Int = 0,
    /** Whether the held key still reaches the game, so an aim button can aim and steer at once. */
    val holdPasses: Boolean = true,
    /** Per cent of the base speed. */
    val sensitivity: Int = DEFAULT_SENSITIVITY,
    /**
     * Tilts smaller than this, in tenths of a degree (turns slower than this, in tenths of a degree
     * per second, with [rate]), are hand tremor and ignored.
     */
    val deadzone: Int = DEFAULT_DEADZONE,
    /** The smallest deflection, in per cent, any real turn produces; it bridges the game's own stick deadzone. */
    val minOutput: Int = 0,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
) {
    val holdKeyInUse: Int? get() = holdKey.takeIf(PushToTalk::isAllowed)

    fun encode(): String = listOf(
        "stick=${stick.wire}", "axis=${axis.wire}", "rate=${flag(rate)}", "key=$holdKey", "pass=${flag(holdPasses)}",
        "sens=$sensitivity", "dz=$deadzone", "min=$minOutput", "ix=${flag(invertX)}", "iy=${flag(invertY)}",
    ).joinToString(",")

    companion object {
        const val DEFAULT_SENSITIVITY = 100
        const val MIN_SENSITIVITY = 10
        const val MAX_SENSITIVITY = 300
        const val DEFAULT_DEADZONE = 15
        const val MAX_DEADZONE = 100
        const val MAX_MIN_OUTPUT = 50

        private fun flag(value: Boolean) = if (value) "1" else "0"

        fun decode(stored: String?): GyroSettings {
            val default = GyroSettings()
            if (stored.isNullOrEmpty()) return default
            val values = stored.split(',').mapNotNull { part ->
                part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }.toMap()
            fun flag(key: String, fallback: Boolean) = when (values[key]) { "1" -> true; "0" -> false; else -> fallback }
            fun number(key: String, range: IntRange, fallback: Int) = values[key]?.toIntOrNull()?.takeIf { it in range } ?: fallback
            return GyroSettings(
                stick = values["stick"]?.let(Stick::fromWire) ?: default.stick,
                axis = GyroAxis.entries.firstOrNull { it.wire == values["axis"] } ?: default.axis,
                rate = flag("rate", default.rate),
                holdKey = values["key"]?.toIntOrNull()?.takeIf { it == 0 || PushToTalk.isAllowed(it) } ?: default.holdKey,
                holdPasses = flag("pass", default.holdPasses),
                sensitivity = number("sens", MIN_SENSITIVITY..MAX_SENSITIVITY, default.sensitivity),
                deadzone = number("dz", 0..MAX_DEADZONE, default.deadzone),
                minOutput = number("min", 0..MAX_MIN_OUTPUT, default.minOutput),
                invertX = flag("ix", default.invertX),
                invertY = flag("iy", default.invertY),
            )
        }

        /** A stored line as [decode] reads it, or null when it is missing or reads as the defaults. */
        fun canonical(stored: String?): String? = decode(stored).takeIf { it != GyroSettings() }?.encode()
    }
}

/** What the running gyro does, for the settings preview; [prefKey] names the hotkey that turned it on. */
data class GyroStatus(
    val prefKey: String? = null,
    val steering: Boolean = false,
    val x: Int = 0,
    val y: Int = 0,
    val seconds: Float = 0f,
    val sequence: Long = 0,
    val calibrating: Boolean = false,
)

/** The gyroscope's reading at rest, which calibration measures and every reading then has subtracted. */
object GyroBias {
    fun encode(bias: FloatArray): String = bias.joinToString(",")

    fun decode(stored: String?): FloatArray =
        stored?.split(',')?.mapNotNull { it.toFloatOrNull()?.takeIf(Float::isFinite) }?.takeIf { it.size == 3 }?.toFloatArray()
            ?: FloatArray(3)

    /** The mean of [samples], each one reading of three rates. */
    fun average(samples: List<FloatArray>): FloatArray =
        if (samples.isEmpty()) FloatArray(3) else FloatArray(3) { axis -> samples.sumOf { it[axis].toDouble() }.toFloat() / samples.size }

    fun apply(rates: FloatArray, bias: FloatArray): FloatArray = FloatArray(3) { rates[it] - bias[it] }
}

/**
 * How far the handheld has turned since its zero posture, from adding up every gyroscope reading.
 * Nothing is dropped on the way, no deadzone and no limit, so returning to the posture returns it to
 * zero. It is kept as one rotation rather than three sums, because turns around different axes do
 * not add up axis by axis: tipping, swinging and tipping back would otherwise leave a remainder.
 */
class GyroTilt {
    // Row-major rotation from the handheld's present axes into those of the zero posture.
    private val m = DoubleArray(9)
    private var steps = 0

    init {
        reset()
    }

    /** The present posture becomes the zero posture. */
    fun reset() {
        m.fill(0.0)
        m[0] = 1.0
        m[4] = 1.0
        m[8] = 1.0
        steps = 0
    }

    /** Adds [rates], radians per second around the sensor's axes, held for [seconds]. */
    fun step(rates: FloatArray, seconds: Float) {
        val wx = rates[0].toDouble() * seconds
        val wy = rates[1].toDouble() * seconds
        val wz = rates[2].toDouble() * seconds
        val angle = sqrt(wx * wx + wy * wy + wz * wz)
        if (angle < 1e-12) return
        val x = wx / angle
        val y = wy / angle
        val z = wz / angle
        val s = sin(angle)
        val c = 1 - cos(angle)
        // The turn of this one reading as a rotation about its own axis.
        val turn = doubleArrayOf(
            1 + c * (x * x - 1), c * x * y - s * z, c * x * z + s * y,
            c * x * y + s * z, 1 + c * (y * y - 1), c * y * z - s * x,
            c * x * z - s * y, c * y * z + s * x, 1 + c * (z * z - 1),
        )
        val product = DoubleArray(9) { i -> (0..2).sumOf { k -> m[i / 3 * 3 + k] * turn[k * 3 + i % 3] } }
        product.copyInto(m)
        if (++steps % RENORMALIZE_STEPS == 0) renormalize()
    }

    /** A vector in the reference frame expressed in the current sensor frame. */
    fun toLocal(reference: FloatArray): FloatArray = FloatArray(3) { col ->
        (0..2).sumOf { row -> m[row * 3 + col] * reference[row] }.toFloat()
    }

    /** A vector in the current sensor frame expressed in the reference frame. */
    fun toReference(local: FloatArray): FloatArray = FloatArray(3) { row ->
        (0..2).sumOf { col -> m[row * 3 + col] * local[col] }.toFloat()
    }

    /**
     * The turn from the zero posture as angles in radians around the sensor's axes: its axis, which
     * is the same in both postures, scaled by its angle. Small tilts read as plain angles per axis.
     */
    fun angles(): FloatArray {
        val vx = (m[7] - m[5]) / 2
        val vy = (m[2] - m[6]) / 2
        val vz = (m[3] - m[1]) / 2
        val sine = sqrt(vx * vx + vy * vy + vz * vz)
        val scale = if (sine < 1e-9) 1.0 else atan2(sine, (m[0] + m[4] + m[8] - 1) / 2) / sine
        return floatArrayOf((vx * scale).toFloat(), (vy * scale).toFloat(), (vz * scale).toFloat())
    }

    // Rounding slowly bends the rotation out of shape; straighten its columns now and then.
    private fun renormalize() {
        fun column(c: Int) = doubleArrayOf(m[c], m[3 + c], m[6 + c])
        fun unit(v: DoubleArray) = sqrt(v.sumOf { it * it }).let { length -> DoubleArray(3) { v[it] / length } }
        val a = unit(column(0))
        val raw = column(1)
        val dot = (0..2).sumOf { a[it] * raw[it] }
        val b = unit(DoubleArray(3) { raw[it] - dot * a[it] })
        val c = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        for (row in 0..2) {
            m[row * 3] = a[row]
            m[row * 3 + 1] = b[row]
            m[row * 3 + 2] = c[row]
        }
    }

    private companion object {
        const val RENORMALIZE_STEPS = 100
    }
}

/**
 * The tilt from the zero posture read off Android's game rotation vector, which fuses the gyroscope
 * with the accelerometer: gravity holds up and down and the sideways lean to the real posture, so
 * shocks and the gyroscope's own errors cannot pile up there. Only swinging around the vertical has
 * nothing to hold on to and may still creep slowly, which recentering clears.
 */
object GyroPosture {
    /** The posture as a unit quaternion (w, x, y, z) from a game rotation vector reading. */
    fun quaternion(values: FloatArray): FloatArray {
        val x = values[0]
        val y = values[1]
        val z = values[2]
        val w = if (values.size > 3) values[3] else sqrt(maxOf(0f, 1f - x * x - y * y - z * z))
        return floatArrayOf(w, x, y, z)
    }

    /**
     * The turn from [zero] to [now], both from [quaternion], as angles in radians around the sensor's
     * axes: the same reading [GyroTilt.angles] gives.
     */
    fun angles(zero: FloatArray, now: FloatArray): FloatArray {
        val (aw, ax, ay, az) = zero
        val (bw, bx, by, bz) = now
        // The zero posture's inverse times the present one: the turn in the handheld's own frame.
        var w = aw * bw + ax * bx + ay * by + az * bz
        var x = aw * bx - ax * bw - ay * bz + az * by
        var y = aw * by + ax * bz - ay * bw - az * bx
        var z = aw * bz - ax * by + ay * bx - az * bw
        // Both signs describe the same turn; the positive one is the short way round.
        if (w < 0) {
            w = -w
            x = -x
            y = -y
            z = -z
        }
        val sine = sqrt(x * x + y * y + z * z)
        val scale = if (sine < 1e-6f) 2f else 2f * atan2(sine, w) / sine
        return floatArrayOf(x * scale, y * scale, z * scale)
    }
}

/**
 * Turns the gyroscope's rotation rates, or the tilt added up from them, into a stick deflection. Written for this app from the
 * sensor's documented coordinate system; it only ever produces an ordinary stick position, which the
 * root helper adds to the real stick.
 */
object GyroMapper {
    /** Degrees per second that give a full deflection at 100 % sensitivity, following turns. */
    const val FULL_SCALE_DPS = 100f
    /** Degrees of tilt that give a full deflection at 100 % sensitivity. */
    const val FULL_SCALE_DEGREES = 30f
    private const val DEGREES_PER_RADIAN = 57.29578f

    /**
     * The sensor reports around the panel's natural axes; the screen content may be turned against
     * them. Returns the rates around the screen's right, up and outward axes, as the user sees them.
     */
    fun toScreen(rotation: Int, x: Float, y: Float, z: Float): FloatArray = when (rotation) {
        Surface.ROTATION_90 -> floatArrayOf(-y, x, z)
        Surface.ROTATION_180 -> floatArrayOf(-x, -y, z)
        Surface.ROTATION_270 -> floatArrayOf(y, -x, z)
        else -> floatArrayOf(x, y, z)
    }

    /**
     * [screen] holds, from [toScreen], rates in radians per second with [GyroSettings.rate] and tilt
     * angles in radians without. Returns the deflection as per mille, x to the right and y downwards
     * like a gamepad stick.
     */
    fun map(settings: GyroSettings, screen: FloatArray): IntArray {
        val pitch = screen[0] * DEGREES_PER_RADIAN
        // Turning right is a negative turn around the up axis (and, steering, around the outward axis).
        var x = -(if (settings.axis == GyroAxis.YAW) screen[1] else screen[2]) * DEGREES_PER_RADIAN
        // Tipping the top edge away is a negative turn around the right axis, and looks up: stick up is negative.
        var y = pitch
        if (settings.invertX) x = -x
        if (settings.invertY) y = -y
        val degrees = hypot(x, y)
        val deadzone = settings.deadzone / 10f
        if (degrees <= deadzone) return intArrayOf(0, 0)
        // The deadzone is cut out rather than gated, so a turn grows smoothly from zero past it.
        val fullScale = if (settings.rate) FULL_SCALE_DPS else FULL_SCALE_DEGREES
        val gain = (degrees - deadzone) / degrees * settings.sensitivity / 100f / fullScale
        x *= gain
        y *= gain
        val length = hypot(x, y)
        if (settings.minOutput > 0) {
            val floor = settings.minOutput / 100f
            val scaled = floor + (1f - floor) * minOf(length, 1f)
            x *= scaled / length
            y *= scaled / length
        }
        return intArrayOf(permille(x), permille(y))
    }

    private fun permille(value: Float): Int = (value.coerceIn(-1f, 1f) * 1000f).roundToInt()
}
