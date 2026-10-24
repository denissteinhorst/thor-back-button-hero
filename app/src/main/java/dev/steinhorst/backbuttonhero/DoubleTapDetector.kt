package dev.steinhorst.backbuttonhero

import android.os.SystemClock

/** Fires [onDoubleTap] when two presses land within [windowMs]. */
class DoubleTapDetector(private val windowMs: () -> Long, private val onDoubleTap: () -> Unit) {
    private var last = 0L

    fun onPress() {
        val now = SystemClock.uptimeMillis()
        if (last != 0L && now - last <= windowMs()) {
            last = 0L
            onDoubleTap()
        } else {
            last = now
        }
    }
}
