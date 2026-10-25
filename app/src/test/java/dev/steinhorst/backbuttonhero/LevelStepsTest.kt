package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LevelStepsTest {

    private val step = 1f / LevelSteps.BRIGHTNESS_STEPS

    @Test
    fun `brightness moves one equal step up and down`() {
        assertEquals(6 * step, LevelSteps.nextBrightness(5 * step, up = true), 1e-6f)
        assertEquals(4 * step, LevelSteps.nextBrightness(5 * step, up = false), 1e-6f)
    }

    @Test
    fun `brightness walks the whole range in 15 presses`() {
        var value = 0f
        repeat(LevelSteps.BRIGHTNESS_STEPS) { value = LevelSteps.nextBrightness(value, up = true) }
        assertEquals(1f, value, 1e-6f)
    }

    @Test
    fun `brightness stops at both ends`() {
        assertEquals(1f, LevelSteps.nextBrightness(1f, up = true), 1e-6f)
        assertEquals(0f, LevelSteps.nextBrightness(0f, up = false), 1e-6f)
    }

    @Test
    fun `a value between steps snaps to the nearest step before moving`() {
        // 0.51 is AYN's slider default; the nearest step is 8/15 (0.533), so up lands on 9/15.
        assertEquals(9 * step, LevelSteps.nextBrightness(0.51f, up = true), 1e-6f)
        assertEquals(7 * step, LevelSteps.nextBrightness(0.51f, up = false), 1e-6f)
    }

    @Test
    fun `out-of-range brightness is clamped first`() {
        assertEquals(1f, LevelSteps.nextBrightness(1.7f, up = true), 1e-6f)
        assertEquals(step, LevelSteps.nextBrightness(-0.3f, up = true), 1e-6f)
    }

    @Test
    fun `volume moves by one and stays within 0 to max`() {
        assertEquals(5, LevelSteps.nextVolume(4, up = true, max = 15))
        assertEquals(3, LevelSteps.nextVolume(4, up = false, max = 15))
        assertEquals(15, LevelSteps.nextVolume(15, up = true, max = 15))
        assertEquals(0, LevelSteps.nextVolume(0, up = false, max = 15))
    }

    @Test
    fun `hat values map to D-pad directions, rest maps to null`() {
        assertEquals(DpadDirection.LEFT, DpadDirection.fromHat(horizontal = true, value = -1f))
        assertEquals(DpadDirection.RIGHT, DpadDirection.fromHat(horizontal = true, value = 1f))
        assertEquals(DpadDirection.UP, DpadDirection.fromHat(horizontal = false, value = -1f))
        assertEquals(DpadDirection.DOWN, DpadDirection.fromHat(horizontal = false, value = 1f))
        assertNull(DpadDirection.fromHat(horizontal = true, value = 0f))
        assertNull(DpadDirection.fromHat(horizontal = false, value = 0.2f))
    }
}
