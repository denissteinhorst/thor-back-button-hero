package dev.steinhorst.backbuttonhero.recents

import android.os.Handler
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import dev.steinhorst.backbuttonhero.input.DpadDirection

/**
 * The controller session over native top Recents: whether it runs, whether Quickstep confirmed it,
 * when D-pad input may reach it, the focus transaction that prepares it, queued card dismissals and
 * the Back that closes it. Every flag lives here and is written only here; work that finishes on a
 * worker carries the [token] it started with and is dropped once a newer session began.
 * [TopRecentsLauncher] decides when to open; [Host] does the platform work.
 */
class TopRecentsSession(private val handler: Handler, private val host: Host) {
    interface Host {
        /** A tap on a card is still being turned into a task move; state checks wait for it. */
        val selectionPending: Boolean
        /** Runs on a worker: Quickstep's state line, null without an answer. */
        fun queryState(): String?
        /** Runs on a worker: the root focus step, `null` for the launcher handoff or `"controller"`. */
        fun runFocus(step: String?): Boolean
        /** The task card nearest the top screen's centre, if Quickstep shows one. */
        fun centeredCard(): AccessibilityNodeInfo?
        /** Whether input focus left Quickstep's cards and actions. */
        fun lostFocus(): Boolean
        /** Swipes the card at [bounds] away; [done] follows on the main thread. False if not dispatched. */
        fun swipeUp(bounds: TopRecentsState.CardBounds, done: () -> Unit): Boolean
        fun navigate(direction: DpadDirection)
        /** Runs on a worker: injects Back into Quickstep; false if the root path failed. */
        fun injectBack(): Boolean
        fun globalBack()
        fun cancelLaunch()
        /** The session is over: give back the controller focus it took. */
        fun end()
    }

    var active = false
        private set
    var confirmed = false
        private set
    /** Bumped by every start, end and retirement; stale worker results compare against it. */
    var token = 0
        private set
    var exitRequested = false
        private set
    /** A physical Back is down; the close is injected only after its UP. */
    var backClosePending = false
        private set
    private var focusPrimed = false
    private var inputReady = false
    private var focusWorkerRunning = false
    private var focusVersion = 0
    private var stateCheckRunning = false
    private var stateCheckPending = false
    private var readyAttempts = 0
    private var cardBounds: TopRecentsState.CardBounds? = null
    private var pendingDismissals = 0
    private var closing = false
    private var closeBacks = 0
    private var closeBackRunning = false
    private var closeBackAt = 0L
    private val navigation = TopRecentsNavigation(host::navigate) { queueDismissal() }
    private val stateRunnable = Runnable { requestStateCheck() }

    fun start() {
        active = true
        confirmed = false
        inputReady = false
        focusPrimed = false
        focusWorkerRunning = false
        exitRequested = false
        navigation.reset()
        token++
        readyAttempts = 0
        stateCheckRunning = false
        stateCheckPending = false
        cardBounds = null
        pendingDismissals = 0
        closing = false
        closeBacks = 0
        closeBackRunning = false
    }

    /** Quickstep was asked to open; wait briefly for its first state. */
    fun opened() {
        readyAttempts = 0
        scheduleStateCheck(READY_RETRY_MS)
    }

    fun isCurrent(started: Int) = started == token

    /** The session ended normally; everything returns to its idle value. */
    fun reset() {
        active = false
        confirmed = false
        inputReady = false
        focusPrimed = false
        focusWorkerRunning = false
        exitRequested = false
        navigation.reset()
        token++
        readyAttempts = 0
        stateCheckRunning = false
        stateCheckPending = false
        cardBounds = null
        pendingDismissals = 0
        closing = false
        closeBacks = 0
        closeBackRunning = false
        handler.removeCallbacks(stateRunnable)
    }

    /** The service shuts down; a replacement shares nothing, so in-flight work must not revive this one. */
    fun retire() {
        active = false
        token++
        navigation.reset()
        handler.removeCallbacks(stateRunnable)
    }

    fun scheduleStateCheck(delayMs: Long) {
        if (!active) return
        handler.removeCallbacks(stateRunnable)
        handler.postDelayed(stateRunnable, delayMs)
    }

    /** Back + D-pad while the session runs. */
    fun press(direction: DpadDirection) {
        if (host.selectionPending || backClosePending) return
        if (inputReady && host.lostFocus()) {
            inputReady = false
            navigation.pause()
        }
        if (!inputReady && !focusWorkerRunning && !exitRequested) {
            focusPrimed = false
            readyAttempts = 0
            scheduleStateCheck(0L)
        }
        navigation.press(direction)
    }

    /** The physical Back went down while the session runs. */
    fun backPressed() {
        backClosePending = true
        host.cancelLaunch()
        navigation.reset()
    }

    /** The physical Back that [backPressed] reported came up. */
    fun backReleased() {
        val closeTop = backClosePending
        backClosePending = false
        if (closeTop && active) close()
    }

    /** Every key counts as released (standby, interception paused). */
    fun forgetBack() {
        backClosePending = false
    }

    fun close() {
        host.cancelLaunch()
        if (exitRequested) return
        exitRequested = true
        navigation.reset()
        injectCloseBack()
    }

    /**
     * Quickstep's Back opens the app Overview was opened over only while that app's card is fully in view;
     * otherwise it merely scrolls the card into view (after a D-pad move, or when Overview opened scrolled).
     * So while Overview still holds focus [CLOSE_RETRY_MS] after a Back, the state check sends another, up to
     * [CLOSE_BACKS]. The wait covers the scroll; once the app opens, Quickstep loses focus well within it.
     */
    private fun injectCloseBack() {
        closeBacks++
        closeBackRunning = true
        val session = token
        Thread {
            val injected = host.injectBack()
            handler.post {
                if (session != token) return@post
                closeBackRunning = false
                closeBackAt = SystemClock.uptimeMillis()
                if (!injected) host.globalBack()
                scheduleStateCheck(TopRecentsLauncher.EVENT_SETTLE_MS)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun queueDismissal() {
        navigation.pause()
        pendingDismissals++
        drainDismissals()
    }

    private fun drainDismissals() {
        if (!active || closing || cardBounds == null || pendingDismissals == 0) return
        pendingDismissals--
        dispatchSwipeUp()
    }

    private fun dispatchSwipeUp() {
        if (closing) return
        val bounds = cardBounds ?: return
        closing = true
        val accepted = host.swipeUp(bounds, ::finishDismiss)
        if (!accepted) closing = false
    }

    private fun finishDismiss() {
        inputReady = false
        focusPrimed = false
        closing = false
        scheduleStateCheck(DELETE_SETTLE_MS)
    }

    /** AYN's Quickstep keeps its task cards outside the accessibility window tree while its Recents
     *  animation owns input. Query its authoritative state only at lifecycle boundaries; controller
     *  navigation itself never waits for this root channel. */
    private fun requestStateCheck() {
        if (!active) return
        if (backClosePending) return
        if (host.selectionPending) {
            scheduleStateCheck(TopRecentsLauncher.EVENT_SETTLE_MS)
            return
        }
        if (stateCheckRunning) {
            stateCheckPending = true
            return
        }
        stateCheckRunning = true
        val session = token
        val version = focusVersion
        Thread {
            val state = host.queryState()
            handler.post {
                if (!active || session != token) return@post
                stateCheckRunning = false
                if (backClosePending) return@post
                if (version != focusVersion) {
                    scheduleStateCheck(0L)
                    return@post
                }
                if (host.selectionPending) {
                    scheduleStateCheck(TopRecentsLauncher.EVENT_SETTLE_MS)
                    return@post
                }
                val snapshot = TopRecentsState.parse(state)
                snapshot.cardBounds?.let { cardBounds = it }
                val overview = state?.let { snapshot.overview }
                // Window events bring checks forward; a Back is repeated only after the full wait.
                val closeWait = CLOSE_RETRY_MS - (SystemClock.uptimeMillis() - closeBackAt)
                when {
                    overview == true && exitRequested && snapshot.windowFocused && !closing && pendingDismissals == 0 &&
                        closeBacks < CLOSE_BACKS -> {
                        confirmed = true
                        when {
                            closeBackRunning -> Unit // Its end schedules the next check.
                            closeWait > 0 -> scheduleStateCheck(closeWait)
                            else -> injectCloseBack()
                        }
                    }
                    overview == true -> {
                        confirmed = true
                        if (cardBounds != null) {
                            if (inputReady && !snapshot.windowFocused) {
                                inputReady = false
                                navigation.pause()
                            }
                            if (!inputReady && focusPrimed && !focusWorkerRunning && snapshot.windowFocused && (snapshot.cardFocused || snapshot.actionsFocused)) {
                                inputReady = true
                                focusPrimed = true
                                navigation.ready(snapshot.actionsFocused)
                            }
                            if (inputReady || exitRequested) {
                                drainDismissals()
                            } else if (!focusPrimed && !focusWorkerRunning) {
                                primeFocus()
                            } else if (++readyAttempts < TopRecentsLauncher.READY_ATTEMPTS) {
                                scheduleStateCheck(READY_RETRY_MS)
                            } else {
                                host.end()
                                return@post
                            }

                        } else if (++readyAttempts < TopRecentsLauncher.READY_ATTEMPTS) {
                            scheduleStateCheck(READY_RETRY_MS)
                        } else {
                            host.end()
                            return@post
                        }
                    }
                    overview == false && confirmed -> {
                        host.end()
                        return@post
                    }
                    ++readyAttempts < TopRecentsLauncher.READY_ATTEMPTS -> {
                        scheduleStateCheck(READY_RETRY_MS)
                    }
                    else -> {
                        host.end()
                        return@post
                    }
                }
                if (stateCheckPending) {
                    stateCheckPending = false
                    scheduleStateCheck(0L)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun primeFocus() {
        focusPrimed = true
        focusWorkerRunning = true
        focusVersion++
        val session = token
        Thread {
            val focused = host.runFocus(null)
            handler.post {
                if (session != token || exitRequested) return@post
                // Capture the centered card after the launcher handoff but before Tab can advance
                // a pooled focus into another card/action. Its node identity survives scrolling.
                val card = if (focused) host.centeredCard() else null
                if (card == null) {
                    finishFocus(false)
                    return@post
                }
                Thread {
                    val primed = host.runFocus("controller")
                    handler.post {
                        if (session != token || exitRequested) return@post
                        val restored = primed && card.refresh() &&
                            (card.isFocused || card.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                        finishFocus(restored)
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun finishFocus(primed: Boolean) {
        focusWorkerRunning = false
        focusVersion++
        if (!primed) focusPrimed = false
        // Only a fresh snapshot after the focus transaction may release queued directions.
        scheduleStateCheck(if (primed) READY_RETRY_MS else TopRecentsLauncher.EVENT_SETTLE_MS)
    }

    companion object {
        const val READY_RETRY_MS = 40L
        const val DELETE_SETTLE_MS = 260L
        const val CLOSE_RETRY_MS = 700L
        const val CLOSE_BACKS = 3
    }
}
