package dev.steinhorst.backbuttonhero

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class DoubleTapDetectorTest {

    /** Advances Robolectric's clock, which is what SystemClock.uptimeMillis() reads. */
    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    @Before
    fun moveClockAwayFromZero() {
        // DoubleTapDetector treats last == 0L as "no previous press", so a press recorded at
        // uptime 0 can never complete a double tap. See PITFALLS.md P1.
        advance(10_000)
    }

    @Test
    fun `two presses within the window trigger a double tap`() {
        var fired = 0
        val detector = DoubleTapDetector({ 300L }) { fired++ }
        detector.onPress()
        advance(100)
        detector.onPress()
        assertEquals(1, fired)
    }

    @Test
    fun `two presses exactly at the window boundary trigger a double tap`() {
        var fired = 0
        val detector = DoubleTapDetector({ 300L }) { fired++ }
        detector.onPress()
        advance(300)
        detector.onPress()
        assertEquals(1, fired)
    }

    @Test
    fun `two presses just past the window do not trigger a double tap`() {
        var fired = 0
        val detector = DoubleTapDetector({ 300L }) { fired++ }
        detector.onPress()
        advance(301)
        detector.onPress()
        assertEquals(0, fired)
    }

    @Test
    fun `a single press never fires`() {
        var fired = 0
        val detector = DoubleTapDetector({ 300L }) { fired++ }
        detector.onPress()
        assertEquals(0, fired)
    }

    @Test
    fun `after a double tap the state resets, so a third press starts a new window`() {
        var fired = 0
        val detector = DoubleTapDetector({ 300L }) { fired++ }
        detector.onPress()
        advance(100)
        detector.onPress()      // double tap #1
        advance(100)
        detector.onPress()      // lone third press
        assertEquals(1, fired)
    }

    @Test
    fun `a fourth press after the reset completes a second double tap`() {
        var fired = 0
        val detector = DoubleTapDetector({ 300L }) { fired++ }
        detector.onPress()
        advance(100)
        detector.onPress()      // double tap #1
        advance(100)
        detector.onPress()      // starts a new window
        advance(100)
        detector.onPress()      // double tap #2
        assertEquals(2, fired)
    }

    @Test
    fun `windowMs is read fresh on every press, not captured at construction`() {
        var currentWindow = 100L
        var fired = 0
        val detector = DoubleTapDetector({ currentWindow }) { fired++ }
        detector.onPress()
        currentWindow = 1000L
        advance(500)            // outside the old 100ms window, inside the new 1000ms one
        detector.onPress()
        assertEquals(1, fired)
    }
}
