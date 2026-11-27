package dev.steinhorst.backbuttonhero.recents

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import dev.steinhorst.backbuttonhero.input.DpadDirection
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The session owner with its worker results delivered by hand: a result that belongs to an older
 * session or an older focus transaction never releases a queued press or ends the current session.
 */
@RunWith(RobolectricTestRunner::class)
class TopRecentsSessionTest {
    private val states = LinkedBlockingQueue<String>()
    private val focusSteps = LinkedBlockingQueue<Boolean>()
    private var queries = 0
    private var ends = 0
    private val navigated = mutableListOf<DpadDirection>()
    private val swipes = mutableListOf<() -> Unit>()
    @Volatile private var backs = 0

    @Suppress("DEPRECATION")
    private val card = AccessibilityNodeInfo.obtain().apply { isFocused = true }

    private val session = TopRecentsSession(Handler(Looper.getMainLooper()), object : TopRecentsSession.Host {
        override val selectionPending = false
        override fun queryState(): String? { queries++; return states.poll(2, TimeUnit.SECONDS) }
        override fun runFocus(step: String?) = focusSteps.poll(2, TimeUnit.SECONDS) == true
        override fun centeredCard() = card
        override fun lostFocus() = false
        override fun swipeUp(bounds: TopRecentsState.CardBounds, done: () -> Unit): Boolean { swipes += done; return true }
        override fun navigate(direction: DpadDirection) { navigated += direction }
        override fun injectBack(): Boolean { backs++; return true }
        override fun globalBack() = Unit
        override fun cancelLaunch() = Unit
        override fun end() { ends++ }
    })

    /** Lets workers post and the main looper run what they posted, until [done] or a generous limit. */
    private fun pump(done: () -> Boolean = { false }) {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            if (done()) return
            Thread.sleep(5)
        }
    }

    private fun startQuery(): Int {
        val before = queries
        pump { queries > before }
        return queries
    }

    /** Opened and focused: queued presses flow straight to the pad. */
    private fun readySession() {
        session.start()
        session.opened()
        startQuery()
        states.put(UNFOCUSED)
        focusSteps.put(true)
        focusSteps.put(true)
        startQuery()
        states.put(FOCUSED)
        pump()
    }

    @Test
    fun `a state answer for a closed session neither confirms nor ends the reopened one`() {
        session.start()
        session.opened()
        startQuery()
        val stale = session.token
        session.reset()
        session.start()
        assertFalse(session.isCurrent(stale))
        states.put(FOCUSED)
        pump()
        assertFalse(session.confirmed)
        assertEquals(0, ends)

        session.opened()
        startQuery()
        states.put(CLOSED)
        pump()
        assertFalse("an unconfirmed session waits for Overview", session.confirmed)
        session.press(DpadDirection.RIGHT)
        assertTrue(navigated.isEmpty())
        assertEquals(0, ends)
    }

    @Test
    fun `an answer that started before the focus transaction finished never releases presses`() {
        session.start()
        session.opened()
        startQuery()
        states.put(UNFOCUSED)
        // The first answer starts the focus transaction; its root steps stay open for now.
        pump { session.confirmed }
        session.press(DpadDirection.RIGHT)
        session.scheduleStateCheck(0L)
        val olderQuery = startQuery()
        focusSteps.put(true)
        focusSteps.put(true)
        pump()
        // Quickstep reports focus, but the answer was asked for before the transaction ended.
        states.put(FOCUSED)
        pump { queries > olderQuery }
        assertTrue("the older answer only asks again", navigated.isEmpty())
        states.put(FOCUSED)
        pump { navigated.isNotEmpty() }
        assertEquals(listOf(DpadDirection.RIGHT), navigated)
        assertEquals(0, ends)
    }

    @Test
    fun `a retired session drops its queued presses and an unfinished dismissal`() {
        readySession()
        session.press(DpadDirection.UP)
        assertEquals("one card goes", 1, swipes.size)
        session.press(DpadDirection.LEFT)
        session.retire()
        val asked = queries
        swipes.single()()
        pump()
        assertEquals(asked, queries)
        assertTrue(navigated.isEmpty())
        assertEquals(0, ends)
        assertFalse(session.active)
    }

    /** Answers every state query with [state] until [done], within a generous number of queries. */
    private fun answerUntil(state: String, done: () -> Boolean) {
        repeat(20) {
            if (done()) return
            startQuery()
            states.put(state)
            pump { done() }
        }
    }

    @Test
    fun `a Back that only scrolled Overview is followed by another once the scroll had time`() {
        readySession()
        session.close()
        answerUntil(FOCUSED) { backs == 2 }
        assertEquals("Quickstep kept Overview, so the close sends a second Back", 2, backs)
        answerUntil(CLOSED) { ends > 0 }
        assertEquals(1, ends)
        assertEquals("closed: no further Back", 2, backs)
    }

    @Test
    fun `a check brought forward right after a Back never repeats it early`() {
        readySession()
        session.close()
        pump { backs == 1 }
        // A window event right after the Back: Overview is still drawn while the app opens.
        session.scheduleStateCheck(0L)
        startQuery()
        states.put(FOCUSED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TopRecentsSession.CLOSE_RETRY_MS / 2))
        assertEquals(1, backs)
    }

    @Test
    fun `the close gives up after a few Backs and Overview stays usable`() {
        readySession()
        session.close()
        answerUntil(FOCUSED) { backs == TopRecentsSession.CLOSE_BACKS }
        // The check after the last Back still finds Overview focused.
        startQuery()
        states.put(FOCUSED)
        pump()
        assertEquals(TopRecentsSession.CLOSE_BACKS, backs)
        assertEquals(0, ends)
    }

    private companion object {
        const val FOCUSED = "mState:Overview|mResumed=true|mHasWindowFocus=true|TaskView{1 VFED..... .F...... 100,200-500,700}|"
        const val UNFOCUSED = "mState:Overview|mResumed=true|mHasWindowFocus=true|TaskView{1 V.ED..... ........ 100,200-500,700}|"
        const val CLOSED = "mState:Normal|mResumed=true|mHasWindowFocus=true|"
    }
}
