package dev.steinhorst.backbuttonhero.actions

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.core.PService
import dev.steinhorst.backbuttonhero.overlay.NoticeOverlay
import dev.steinhorst.backbuttonhero.prefs.Prefs
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The values "Hide pop-ups" found before it changed them: the global heads-up setting ("null" when unset)
 * and SystemUI's TOAST_WINDOW app-op mode. Only values a restore command can safely carry are accepted.
 */
data class PopupRestore(val headsUp: String, val toastMode: String) {
    companion object {
        private val HEADS_UP = Regex("null|-?\\d{1,9}")
        private val MODES = setOf("allow", "ignore", "deny", "default", "foreground")

        fun of(headsUp: String, toastMode: String): PopupRestore? =
            PopupRestore(headsUp, toastMode).takeIf { HEADS_UP.matches(headsUp) && toastMode in MODES }
    }
}

/**
 * Do Not Disturb's "Try to hide all pop-ups" option. On, it mutes this app's own notices and, through AYN's root service, every text
 * toast (Android 11+ draws them in SystemUI, whose TOAST_WINDOW app-op hides the window) and heads-up
 * notifications. Both system values survive a reboot, so the values found before are saved in
 * [Prefs.hiddenPopups] first and a root [Guard] restores them if this process dies; a toggle off, a pause, the
 * service's end and the next start restore them too. A restore only puts back a value that still holds what
 * this toggle set, so a change the user made meanwhile stays. Root work runs in order on one worker thread.
 */
class HidePopups(private val prefs: Prefs, private val notice: NoticeOverlay, private val host: Host) {
    /** The root side and the main thread, replaceable in tests. */
    interface Host {
        fun rootAvailable(): Boolean
        fun exec(command: String): String?
        /** Starts the root guard for [restore]; null when it could not start. [onLost] runs if it goes away. */
        fun startGuard(restore: PopupRestore, onLost: (Guard) -> Unit): Guard?
        fun post(block: () -> Unit)
    }

    /** The root process that restores the saved values when its link to this process ends. */
    interface Guard {
        /** Ends the link; [restored] tells the guard that the values are back, so it leaves them alone. */
        fun close(restored: Boolean)
    }

    private val worker = ScheduledThreadPoolExecutor(1) { Thread(it, "HidePopups").apply { isDaemon = true } }.apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    /** Main thread: whether pop-ups are hidden. */
    var on = false
        private set

    // Worker thread only, apart from the identity check of a lost guard.
    @Volatile private var guard: Guard? = null
    private var applied: PopupRestore? = null

    /**
     * Hides pop-ups, or shows them again; nothing happens when they already are. Do Not Disturb's own
     * notice is the confirmation, so it must show before [hide] mutes and after a show unmutes.
     */
    fun set(hide: Boolean) {
        if (hide == on) return
        if (!hide) return turnOff()
        on = true
        notice.muted = true
        submit(::apply)
    }

    /** Shows pop-ups again: a pause and the service's end. */
    fun release() = set(false)

    /** Puts back what an earlier session left applied, after a crash or a reboot. */
    fun restoreLeftover() {
        if (prefs.hiddenPopups != null) submit { restoreLeftover(LEFTOVER_ATTEMPTS) }
    }

    /** [release] and the worker's end, once the restore has run. */
    fun close() {
        release()
        worker.shutdown()
    }

    private fun turnOff() {
        on = false
        notice.muted = false
        submit {
            val restore = applied
            if (restore != null) {
                val restored = succeeded(host.exec(restoreCommand(restore)))
                guard?.close(restored)
                guard = null
                applied = null
                // A failed restore stays saved: the guard has just tried again, the next start does too.
                if (restored) prefs.hiddenPopups = null
            }
        }
    }

    private fun apply() {
        if (!on || applied != null) return
        if (!host.rootAvailable() || !restorePending()) return partial()
        val found = parse(host.exec(READ_COMMAND)) ?: return partial()
        // Saved before anything changes: from here on a crash is restored by the guard or the next start.
        prefs.hiddenPopups = found
        val started = host.startGuard(found) { lost -> host.post { if (on && guard === lost) turnOff() } }
        if (started == null) {
            prefs.hiddenPopups = null
            return partial()
        }
        guard = started
        if (!succeeded(host.exec(APPLY_COMMAND))) {
            // Part of the chain may have run; the restore puts back whatever it changed.
            val restored = succeeded(host.exec(restoreCommand(found)))
            started.close(restored)
            guard = null
            if (restored) prefs.hiddenPopups = null
            return partial()
        }
        applied = found
    }

    /** False while values of an earlier session are still waiting for their restore. */
    private fun restorePending(): Boolean {
        val leftover = prefs.hiddenPopups ?: return true
        if (!succeeded(host.exec(restoreCommand(leftover)))) return false
        prefs.hiddenPopups = null
        return true
    }

    private fun restoreLeftover(attemptsLeft: Int) {
        // A session that applied meanwhile restored the leftover first and owns the saved values now.
        if (applied != null) return
        val leftover = prefs.hiddenPopups ?: return
        // pservice can start after the service at boot, so a miss is retried for a while.
        if (host.rootAvailable() && succeeded(host.exec(restoreCommand(leftover)))) {
            prefs.hiddenPopups = null
            Log.i(TAG, "restored pop-ups left hidden by an earlier session")
        } else if (attemptsLeft > 1) {
            runCatching { worker.schedule({ restoreLeftover(attemptsLeft - 1) }, LEFTOVER_RETRY_MS, TimeUnit.MILLISECONDS) }
        }
    }

    private fun partial() = host.post { if (on) notice.show(R.string.hide_popups_partial, long = true, force = true) }

    private fun submit(task: () -> Unit) {
        if (!worker.isShutdown) worker.execute(task)
    }

    companion object {
        private const val TAG = "HidePopups"
        private const val LEFTOVER_ATTEMPTS = 18
        private const val LEFTOVER_RETRY_MS = 10_000L
        private const val HEADS_UP = "heads_up_notifications_enabled"
        private const val TOAST_OP = "com.android.systemui TOAST_WINDOW"

        /** pservice hands back one line, so the unquoted echo joins both answers into one. */
        const val READ_COMMAND = "echo \$(settings get global $HEADS_UP) \$(appops get $TOAST_OP)"
        const val APPLY_COMMAND = "settings put global $HEADS_UP 0 && appops set $TOAST_OP ignore && echo OK"

        /**
         * Puts back each value that still holds what [APPLY_COMMAND] set; the guard runs the same command.
         * pservice mangles commands of 256 characters and more, so the long names go into
         * shell variables (`$o` stays unquoted on purpose: it is the package and the op as two arguments).
         */
        fun restoreCommand(restore: PopupRestore): String {
            val headsUp = if (restore.headsUp == "null") "delete global \$h" else "put global \$h ${restore.headsUp}"
            return "h=$HEADS_UP; o='$TOAST_OP'; [ \"\$(settings get global \$h)\" = 0 ] && settings $headsUp; " +
                "appops get \$o | grep -q 'TOAST_WINDOW: ignore' && appops set \$o ${restore.toastMode}; echo OK"
        }

        private val PACKAGE_MODE = Regex("(?<!Uid mode: )TOAST_WINDOW: (\\w+)")

        /** The values in [READ_COMMAND]'s line; an app-op without a package mode of its own is "default". */
        fun parse(reply: String?): PopupRestore? {
            val parts = reply?.trim()?.split(Regex("\\s+"), limit = 2) ?: return null
            val rest = parts.getOrElse(1) { "" }
            val mode = PACKAGE_MODE.find(rest)?.groupValues?.get(1)
                ?: "default".takeIf { rest.startsWith("No operations") || rest.contains("TOAST_WINDOW:") }
                ?: return null
            return PopupRestore.of(parts[0], mode)
        }

        fun succeeded(reply: String?) = reply?.trim()?.endsWith("OK") == true

        /** The real host: pservice, the guard started through it and the main looper. */
        fun rootHost(context: Context): Host = object : Host {
            private val main = Handler(Looper.getMainLooper())
            override fun rootAvailable() = PService.isAvailable()
            override fun exec(command: String) = PService.exec(command)
            override fun startGuard(restore: PopupRestore, onLost: (Guard) -> Unit) = PopupGuardLink.start(context, restore, onLost)
            override fun post(block: () -> Unit) { main.post(block) }
        }
    }
}
