package dev.steinhorst.backbuttonhero

/** Step arithmetic for the volume and brightness actions, free of Android so it can be unit-tested. */
object LevelSteps {
    /** Brightness 0..1 is split into this many equal steps; one press moves one step. */
    const val BRIGHTNESS_STEPS = 15

    /** The next brightness one step [up] or down. The current value is snapped to the step grid first,
     *  so a value set elsewhere (AYN's slider) still moves in whole steps from then on. */
    fun nextBrightness(current: Float, up: Boolean): Float {
        val index = Math.round(current.coerceIn(0f, 1f) * BRIGHTNESS_STEPS) + if (up) 1 else -1
        return index.coerceIn(0, BRIGHTNESS_STEPS).toFloat() / BRIGHTNESS_STEPS
    }

    /** The next volume index one step [up] or down, kept within 0..[max]. */
    fun nextVolume(current: Int, up: Boolean, max: Int): Int = (current + if (up) 1 else -1).coerceIn(0, max)
}
