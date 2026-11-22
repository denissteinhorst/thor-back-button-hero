package dev.steinhorst.backbuttonhero

import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.prefs.AppException
import dev.steinhorst.backbuttonhero.prefs.ExceptionControl
import dev.steinhorst.backbuttonhero.prefs.Prefs
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fixed-clock boundary cases (threshold - 1, threshold, threshold + 1) for every timing comparison in
 * [BackGestureMachine], and the exact delay each timer is posted with. Device timing has scheduling
 * jitter, so these are the only place a one-millisecond change in a threshold is guaranteed to show.
 * Timers never fire on their own here: each case fires them explicitly, so only the comparisons decide.
 */
@RunWith(RobolectricTestRunner::class)
class BackGestureThresholdTest {

    private class Clock(var now: Long) : GestureClock {
        override fun uptimeMillis() = now
    }

    private class Scheduler : GestureScheduler {
        val pending = linkedMapOf<Runnable, Long>()
        override fun postDelayed(runnable: Runnable, delayMs: Long) { pending[runnable] = delayMs }
        override fun removeCallbacks(runnable: Runnable) { pending.remove(runnable) }
        override fun hasCallbacks(runnable: Runnable) = pending.containsKey(runnable)
        fun delays() = pending.values.toList()
    }

    private class Actions : GestureActions {
        val log = mutableListOf<String>()
        override fun swapScreens() { log += "swap" }
        override fun openTopRecents() { log += "topRecents" }
        override fun showSwitcher() { log += "switcher" }
        override fun onSingleTap() { log += "single" }
        override fun nativeBack(longPress: Boolean) { log += "native($longPress)" }
        override fun holdSwapBlocked() { log += "blocked" }
    }

    private lateinit var prefs: Prefs
    private val clock = Clock(10_000L)
    private val scheduler = Scheduler()
    private val actions = Actions()
    private lateinit var machine: BackGestureMachine

    @Before
    fun setUp() {
        prefs = Prefs(ApplicationProvider.getApplicationContext())
        prefs.doubleTapEnabled = true
        prefs.holdEnabled = true
        prefs.tapHoldEnabled = false
        machine = BackGestureMachine(prefs, clock, scheduler, actions, exception = { null }, nativeLongPressMs = NATIVE_LONG_PRESS_MS)
    }

    private fun down(): Long {
        machine.handleBack(KeyEvent(clock.now, clock.now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        return clock.now
    }

    private fun up(downTime: Long) = machine.handleBack(KeyEvent(downTime, clock.now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))

    /** A tap whose UP lands at the current clock; returns that UP time. */
    private fun tap(): Long { up(down()); return clock.now }

    @Test
    fun `second tap within, at and just past the double tap window`() {
        val window = prefs.doubleTapMs
        for ((offset, expected) in listOf(-1L to "switcher", 0L to "switcher", 1L to "")) {
            machine.reset(); scheduler.pending.clear(); actions.log.clear()
            val first = tap()
            clock.now = first + window + offset
            up(down())
            assertEquals("offset $offset", listOf(expected).filter { it.isNotEmpty() }, actions.log)
        }
    }

    @Test
    fun `tap hold recognition within, at and just past its grace window`() {
        prefs.tapHoldEnabled = true
        val grace = maxOf(prefs.doubleTapMs, SwitcherService.TAP_HOLD_GRACE_MS)
        for ((offset, expected) in listOf(-1L to listOf(prefs.tapHoldMs), 0L to listOf(prefs.tapHoldMs), 1L to listOf(prefs.holdMs))) {
            machine.reset(); scheduler.pending.clear()
            val first = tap()
            scheduler.pending.clear()
            clock.now = first + grace + offset
            down()
            assertEquals("offset $offset", expected, scheduler.delays())
        }
    }

    @Test
    fun `follows recent tap without tap hold uses only the double tap window`() {
        val window = prefs.doubleTapMs
        for ((offset, expected) in listOf(-1L to true, 0L to true, 1L to false)) {
            machine.reset()
            val first = tap()
            clock.now = first + window + offset
            assertEquals("offset $offset", expected, machine.followsRecentTap())
        }
    }

    @Test
    fun `timers are posted with exactly the configured delays`() {
        val press = down()
        assertEquals("hold", listOf(prefs.holdMs), scheduler.delays())
        clock.now += 1
        up(press)
        assertEquals("single tap waits the double tap window", listOf(prefs.doubleTapMs), scheduler.delays())

        machine.reset(); scheduler.pending.clear()
        prefs.tapHoldEnabled = true
        val first = tap()
        scheduler.pending.clear()
        clock.now = first + 1
        down()
        assertEquals("tap hold", listOf(prefs.tapHoldMs), scheduler.delays())

        machine.reset(); scheduler.pending.clear()
        machine = BackGestureMachine(prefs, clock, scheduler, actions, nativeLongPressMs = NATIVE_LONG_PRESS_MS,
            exception = { AppException("org.example.game", "Game", ExceptionControl.ALL and ExceptionControl.HOLD.bit.inv(), true) })
        down()
        assertEquals("native long press", listOf(NATIVE_LONG_PRESS_MS), scheduler.delays())
    }

    @Test
    fun `the tap hold grace and the default native long press are unchanged`() {
        assertEquals(600L, SwitcherService.TAP_HOLD_GRACE_MS)
        val defaultMachine = BackGestureMachine(prefs, clock, scheduler, actions,
            exception = { AppException("org.example.game", "Game", ExceptionControl.ALL and ExceptionControl.HOLD.bit.inv(), true) })
        defaultMachine.handleBack(KeyEvent(clock.now, clock.now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        assertEquals(listOf(500L), scheduler.delays())
    }

    private companion object {
        const val NATIVE_LONG_PRESS_MS = 400L
    }
}
