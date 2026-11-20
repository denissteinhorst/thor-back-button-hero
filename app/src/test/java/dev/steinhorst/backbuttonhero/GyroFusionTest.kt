package dev.steinhorst.backbuttonhero

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GyroFusionTest {
    private val gravity = 9.81f
    private val slope = 0.17f
    private fun acc(angle: Float = 0f) = floatArrayOf(
        gravity * sin(slope), gravity * cos(slope) * sin(angle), gravity * cos(slope) * cos(angle),
    )
    private fun profile(bias: FloatArray = FloatArray(3)) =
        GyroCalibration("test", bias.toList(), listOf(0.0001f, 0.0001f, 0.0001f), 0.005f, gravity)

    @Test
    fun `rest calibration measures each sensor on a sloping surface across sampling rates`() {
        for (hz in listOf(25, 50, 100, 200)) {
            val rest = GyroRest()
            var result: GyroCalibration? = null
            for (i in 0..hz * 3) {
                val noise = if (i % 2 == 0) 0.0002f else -0.0002f
                result = rest.add(1_000_000_000L + i * (1_000_000_000L / hz), floatArrayOf(0.012f + noise, -0.018f, 0.006f), acc())
            }
            assertNotNull("$hz Hz", result)
            assertEquals(0.012f, result!!.bias[0], 0.00001f)
            assertEquals(-0.018f, result.bias[1], 0.00001f)
            assertEquals(0.006f, result.bias[2], 0.00001f)
            assertTrue(result.noise[0] in 0.00019f..0.00021f)
            assertEquals(gravity, result.gravity, 0.00001f)
        }
    }

    @Test
    fun `motion noisy data trends gaps and invalid samples cannot establish rest`() {
        fun rejected(rate: (Int) -> FloatArray, acceleration: (Int) -> FloatArray = { acc() }, dt: Long = 10_000_000L) {
            val rest = GyroRest()
            repeat(500) { i -> assertNull(rest.add(1_000_000_000L + i * dt, rate(i), acceleration(i))) }
        }
        rejected({ floatArrayOf(0.2f, 0f, 0f) })
        rejected({ FloatArray(3) }, { acc(it * 0.0001f) }) // Constant slow tilt is not rest.
        rejected({ floatArrayOf(if (it % 2 == 0) 0.01f else -0.01f, 0f, 0f) })
        rejected({ floatArrayOf(it * 0.00003f, 0f, 0f) })
        rejected({ FloatArray(3) }, { floatArrayOf(0f, if (it % 2 == 0) 0.11f else 0f, gravity) })
        rejected({ floatArrayOf(Float.NaN, 0f, 0f) })
        rejected({ FloatArray(2) })
        rejected({ FloatArray(3) }, { FloatArray(3) })
        rejected({ FloatArray(3) }, dt = 200_000_000L)
        val rest = GyroRest()
        repeat(100) { assertNull(rest.add(1L, FloatArray(3), acc())) }
        // More than the bounded capacity cannot turn a short burst into a long rest measurement.
        repeat(1500) { assertNull(rest.add(2L + it * 100_000L, FloatArray(3), acc())) }
    }

    @Test
    fun `a movement restarts the full window and does not poison the next calibration`() {
        val rest = GyroRest()
        repeat(250) { assertNull(rest.add(1_000_000_000L + it * 10_000_000L, FloatArray(3), acc())) }
        assertNull(rest.add(3_500_000_000L, floatArrayOf(1f, 0f, 0f), acc(0.5f)))
        var result: GyroCalibration? = null
        repeat(301) { result = rest.add(3_510_000_000L + it * 10_000_000L, floatArrayOf(0.01f, 0f, 0f), acc(0.3f)) }
        assertNotNull(result)
        assertEquals(0.01f, result!!.bias[0], 1e-6f)
    }

    @Test
    fun `repeated tilts with offsets noise and warming return without growing error before mapping`() {
        for (hz in listOf(25, 50, 100, 200)) {
            val bias = floatArrayOf(0.012f, -0.018f, 0.006f)
            val fusion = GyroFusion().apply { configure(profile(bias)) }
            val dt = 1_000_000_000L / hz
            var largestReturn = 0f
            // Twenty complete smooth movements, including slow and fast sections in both directions.
            for (i in 0..hz * 40) {
                val t = i.toDouble() / hz
                val angle = (0.4 * (1 - cos(PI * t))).toFloat()
                val rate = (0.4 * PI * sin(PI * t)).toFloat()
                val time = 1_000_000_000L + i * dt
                fusion.acceleration(acc(angle), time)
                fusion.step(floatArrayOf(rate + bias[0] + 0.0005f + (0.0002 * sin(41 * t)).toFloat(), bias[1], bias[2]), time)
                if (i % (hz * 2) == 0) largestReturn = maxOf(largestReturn, abs(fusion.angles()[0]))
            }
            assertTrue("$hz Hz return error $largestReturn rad", largestReturn < 0.005f)
            assertTrue("no accumulated cross-axis error", fusion.angles().all { abs(it) < 0.01f })
        }
    }

    @Test
    fun `gravity holds a deliberate tilt rather than returning it to the centre`() {
        val fusion = GyroFusion().apply { configure(profile()) }
        var time = 1_000_000_000L
        fun sample(rate: Float, angle: Float) {
            fusion.acceleration(acc(angle), time)
            fusion.step(floatArrayOf(rate, 0f, 0f), time)
            time += 10_000_000
        }
        sample(0f, 0f)
        // A smooth turn ending at 0.5 rad, followed by a long hold in that actual posture.
        for (i in 1..100) {
            val t = i / 100.0
            sample((0.25 * PI * sin(PI * t)).toFloat(), (0.25 * (1 - cos(PI * t))).toFloat())
        }
        repeat(2000) { sample(0f, 0.5f) }
        assertEquals(0.5f, fusion.angles()[0], 0.002f)
        fusion.recenter()
        repeat(500) { sample(0f, 0.5f) }
        assertArrayEquals(FloatArray(3), fusion.angles(), 0.001f)
    }

    @Test
    fun `combined body rotations retrace their path with calibrated noisy samples`() {
        val truth = GyroTilt()
        val fusion = GyroFusion().apply { configure(profile(floatArrayOf(0.01f, -0.01f, 0.02f))) }
        val g = acc()
        var time = 1_000_000_000L
        fun sample(rate: FloatArray) {
            truth.step(rate, 0.01f)
            fusion.acceleration(truth.toLocal(g), time)
            fusion.step(floatArrayOf(rate[0] + 0.01f, rate[1] - 0.01f, rate[2] + 0.02f), time)
            time += 10_000_000
        }
        sample(FloatArray(3))
        val path = listOf(floatArrayOf(0.5f, 0.3f, 0.2f), floatArrayOf(-0.1f, 0.4f, -0.2f))
        repeat(20) {
            path.forEach { rate -> repeat(50) { sample(rate) } }
            path.reversed().forEach { rate -> repeat(50) { sample(FloatArray(3) { -rate[it] }) } }
            repeat(30) { sample(FloatArray(3)) }
            assertTrue(fusion.angles().all { abs(it) < 0.015f })
        }
    }

    @Test
    fun `very slow aiming survives rest tracking and rates remain physical rates`() {
        val fusion = GyroFusion().apply { configure(profile()) }
        for (i in 0..2000) {
            val time = 1_000_000_000L + i * 10_000_000L
            fusion.acceleration(acc(i * 0.00005f), time)
            fusion.step(floatArrayOf(0.005f, 0f, 0f), time)
        }
        assertEquals(0.005f, fusion.rates[0], 0.000001f)
        assertEquals(0.1f, fusion.angles()[0], 0.002f)
    }

    @Test
    fun `small rest bias changes are learned only with a quality checked profile`() {
        for (calibrated in listOf(false, true)) {
            val fusion = GyroFusion().apply { configure(if (calibrated) profile() else null) }
            repeat(1200) {
                val time = 1_000_000_000L + it * 10_000_000L
                fusion.acceleration(acc(), time)
                fusion.step(floatArrayOf(0.00015f, 0f, 0f), time)
            }
            assertEquals(if (calibrated) 0f else 0.00015f, fusion.rates[0], 0.00001f)
        }
    }

    @Test
    fun `stale timestamps invalid data and acceleration shocks do not inject fictitious rotation`() {
        val fusion = GyroFusion()
        assertFalse(fusion.step(floatArrayOf(Float.NaN, 0f, 0f), 1L))
        assertTrue(fusion.step(FloatArray(3), 1_000_000_000))
        assertFalse(fusion.step(FloatArray(3), 999_999_999))
        assertFalse(fusion.step(FloatArray(3), 1_000_000_000))
        fusion.acceleration(FloatArray(2), 1_000_000_000)
        assertNull(fusion.freshAcceleration(1_000_000_000))
        fusion.acceleration(acc(), 1_000_000_001)
        fusion.acceleration(floatArrayOf(0f, 0f, 50f), 999_999_999)
        assertArrayEquals(acc(), fusion.freshAcceleration(1_000_000_000), 0f)
        assertNull(fusion.freshAcceleration(1_200_000_000))
        fusion.step(floatArrayOf(1f, 0f, 0f), 2_000_000_000)
        assertArrayEquals(FloatArray(3), fusion.angles(), 0f)
        assertEquals(0f, fusion.seconds)
        fusion.recenter()
        fusion.step(FloatArray(3), 3_000_000_000)
        for (i in 1..30) {
            val time = 3_000_000_000L + i * 10_000_000L
            fusion.acceleration(if (i < 3) acc() else if (i < 15) floatArrayOf(0f, 0f, 20f) else floatArrayOf(9.81f, 0f, 0f), time)
            fusion.step(FloatArray(3), time)
        }
        assertArrayEquals(FloatArray(3), fusion.angles(), 1e-6f)
    }

    @Test
    fun `synthetic device rates ignore rest correction and acceleration`() {
        val fusion = GyroFusion().apply { configure(profile(floatArrayOf(0.01f, 0f, 0f))) }
        repeat(101) {
            val time = 1_000_000_000L + it * 10_000_000L
            fusion.acceleration(acc(), time)
            fusion.step(floatArrayOf(0.5f, 0f, 0f), time, synthetic = true)
        }
        assertEquals(0.5f, fusion.angles()[0], 1e-5f)
    }

    @Test
    fun `versioned profiles roundtrip and reject malformed unsupported or unsafe data`() {
        val profile = profile(floatArrayOf(0.01f, -0.02f, 0.03f))
        assertEquals(profile, GyroCalibration.decode(profile.encode()))
        for (bad in listOf(null, "", "not json", "{}", profile.encode().replace("\"version\":1", "\"version\":2"),
            profile.copy(bias = listOf(1f, 0f, 0f)).encode(), profile.copy(gravity = 0f).encode(),
            profile.copy(noise = listOf(-1f, 0f, 0f)).encode(), profile.copy(bias = listOf(0f)).encode(),
            profile.copy(accelerationNoise = Float.NaN).encode())) assertNull(bad, GyroCalibration.decode(bad))
    }
}
