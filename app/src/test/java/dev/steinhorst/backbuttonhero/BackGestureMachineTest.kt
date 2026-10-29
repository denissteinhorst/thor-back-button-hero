package dev.steinhorst.backbuttonhero

import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeClock(var now: Long) : GestureClock {
    override fun uptimeMillis() = now
}

private class FakeScheduler : GestureScheduler {
    /** runnable -> the delay it was posted with. */
    val pending = linkedMapOf<Runnable, Long>()

    override fun postDelayed(runnable: Runnable, delayMs: Long) { pending[runnable] = delayMs }
    override fun removeCallbacks(runnable: Runnable) { pending.remove(runnable) }
    override fun hasCallbacks(runnable: Runnable) = pending.containsKey(runnable)

    /** Fires every pending runnable whose posted delay is <= [delayMs], in posting order. */
    fun fireDue(delayMs: Long) {
        val due = pending.filterValues { it <= delayMs }.keys.toList()
        due.forEach { pending.remove(it); it.run() }
    }

    fun fireAll() {
        val all = pending.keys.toList()
        pending.clear()
        all.forEach { it.run() }
    }
}

private class RecordingActions : GestureActions {
    var swapScreensCalls = 0
    var openTopRecentsCalls = 0
    var showSwitcherCalls = 0
    var onSingleTapCalls = 0
    override fun swapScreens() { swapScreensCalls++ }
    override fun openTopRecents() { openTopRecentsCalls++ }
    override fun showSwitcher() { showSwitcherCalls++ }
    override fun onSingleTap() { onSingleTapCalls++ }
}

@RunWith(RobolectricTestRunner::class)
class BackGestureMachineTest {

    // Must be > 0: DoubleTapDetector uses last == 0L as its "no previous press" sentinel,
    // so a clock starting at 0 can never recognize a double tap. See PITFALLS.md P1.
    private val startTime = 10_000L

    private lateinit var prefs: Prefs
    private lateinit var clock: FakeClock
    private lateinit var scheduler: FakeScheduler
    private lateinit var actions: RecordingActions
    private lateinit var machine: BackGestureMachine

    @Before
    fun setUp() {
        prefs = Prefs(ApplicationProvider.getApplicationContext())
        clock = FakeClock(startTime)
        scheduler = FakeScheduler()
        actions = RecordingActions()
        machine = BackGestureMachine(prefs, clock, scheduler, actions)
    }

    /** A DOWN event whose downTime is the current fake-clock reading. */
    private fun pressDown() =
        KeyEvent(clock.now, clock.now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)

    /** An UP event for a press that went down at [downTime]. */
    private fun release(downTime: Long) =
        KeyEvent(downTime, clock.now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)

    private fun advanceBoth(ms: Long) {
        clock.now += ms
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(ms))
    }

    @Test
    fun `single tap waits only for configured double tap window`() {
        machine.handleBack(pressDown())
        machine.handleBack(release(startTime))
        assertEquals(0, actions.onSingleTapCalls)
        assertEquals(listOf(prefs.doubleTapMs), scheduler.pending.values.toList())
    }

    @Test
    fun `letting the single-tap delay elapse with no second press fires onSingleTap once`() {
        machine.handleBack(pressDown())
        machine.handleBack(release(startTime))
        scheduler.fireAll()
        assertEquals(1, actions.onSingleTapCalls)
    }

    @Test
    fun `double tap never runs single tap or minimizes the current app`() {
        prefs.doubleTapMs = 300L
        machine.handleBack(pressDown())
        machine.handleBack(release(startTime))
        advanceBoth(100)
        val secondDown = clock.now
        machine.handleBack(pressDown())
        machine.handleBack(release(secondDown))
        assertEquals(1, actions.showSwitcherCalls)
        assertEquals(0, actions.onSingleTapCalls)
        scheduler.fireAll()
        assertEquals(0, actions.onSingleTapCalls)
    }

    @Test
    fun `holding past holdMs swaps screens and the UP does not also fire tap logic`() {
        prefs.holdEnabled = true
        prefs.holdMs = 1000L
        machine.handleBack(pressDown())
        scheduler.fireDue(1000L)
        assertEquals(1, actions.swapScreensCalls)
        advanceBoth(1000)
        machine.handleBack(release(startTime))
        assertEquals(0, actions.onSingleTapCalls)
    }

    @Test
    fun `with hold disabled, nothing is scheduled at DOWN and UP still schedules a single tap`() {
        prefs.holdEnabled = false
        prefs.tapHoldEnabled = false
        machine.handleBack(pressDown())
        assertTrue(scheduler.pending.isEmpty())
        advanceBoth(2000)
        machine.handleBack(release(startTime))
        scheduler.fireAll()
        assertEquals(1, actions.onSingleTapCalls)
    }

    @Test
    fun `tap then hold again opens top recents instead of swapping screens`() {
        prefs.tapHoldEnabled = true
        prefs.tapHoldMs = 1000L
        machine.handleBack(pressDown())
        machine.handleBack(release(startTime))
        advanceBoth(100)
        machine.handleBack(pressDown())            // pressFollowsTap latches true here
        scheduler.fireDue(1000L)
        assertEquals(1, actions.openTopRecentsCalls)
        assertEquals(0, actions.swapScreensCalls)
    }

    @Test
    fun `second down cancels pending single tap while retaining tap hold recognition`() {
        // Regression guard for the pressFollowsTap latch-at-DOWN comment in the source.
        prefs.doubleTapEnabled = true
        prefs.tapHoldEnabled = true
        machine.handleBack(pressDown())
        machine.handleBack(release(startTime))
        advanceBoth(50)
        machine.handleBack(pressDown())
        scheduler.fireAll()
        assertEquals(0, actions.onSingleTapCalls)
        assertEquals(1, actions.openTopRecentsCalls)
    }

    @Test
    fun `with double-tap disabled, UP fires onSingleTap immediately and schedules nothing`() {
        prefs.doubleTapEnabled = false
        machine.handleBack(pressDown())
        machine.handleBack(release(startTime))
        assertEquals(1, actions.onSingleTapCalls)
        assertTrue(scheduler.pending.isEmpty())
    }
    @Test
    fun `tap hold fires at configured timeout while second press is still down and only once`() {
        prefs.tapHoldEnabled = true
        prefs.tapHoldMs = 750L
        machine.handleBack(pressDown())
        machine.handleBack(release(startTime))
        assertEquals(0, actions.onSingleTapCalls)
        advanceBoth(100)
        val secondDown = clock.now
        machine.handleBack(pressDown())
        scheduler.fireDue(749L)
        assertEquals(0, actions.openTopRecentsCalls)
        scheduler.fireDue(750L)
        assertTrue(machine.backDown)
        assertEquals(1, actions.openTopRecentsCalls)
        advanceBoth(750)
        machine.handleBack(release(secondDown))
        scheduler.fireAll()
        assertEquals(1, actions.openTopRecentsCalls)
        assertEquals(0, actions.onSingleTapCalls)
    }

    @Test
    fun `expired tap window schedules ordinary hold instead of tap hold`() {
        machine.handleBack(pressDown())
        machine.handleBack(release(startTime))
        advanceBoth(2000)
        machine.handleBack(pressDown())
        scheduler.fireAll()
        assertEquals(1, actions.swapScreensCalls)
        assertEquals(0, actions.openTopRecentsCalls)
    }

    @Test
    fun `shoulder combo cancels both hold actions`() {
        machine.handleBack(pressDown())
        machine.markComboFired()
        scheduler.fireAll()
        machine.handleBack(release(startTime))
        assertEquals(0, actions.swapScreensCalls)
        assertEquals(0, actions.openTopRecentsCalls)
        assertEquals(0, actions.onSingleTapCalls)
    }

    @Test
    fun `tap hold remains armed after single tap has already fired`() {
        machine.handleBack(pressDown())
        machine.handleBack(release(startTime))
        scheduler.fireDue(prefs.doubleTapMs)
        assertEquals(1, actions.onSingleTapCalls)
        advanceBoth(prefs.doubleTapMs + 10)
        machine.handleBack(pressDown())
        scheduler.fireDue(prefs.tapHoldMs)
        assertEquals(1, actions.openTopRecentsCalls)
    }

}
