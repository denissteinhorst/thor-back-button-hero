package dev.steinhorst.backbuttonhero.macro

import dev.steinhorst.backbuttonhero.input.ComboButton

/**
 * Which hotkey's macro the helper plays and the one-press cancel: Back stops a playing macro, and
 * that macro's own hotkey in the same press only cancels instead of restarting it.
 */
class MacroPlayback(private val host: Host) {
    interface Host {
        /** Hands [button]'s macro to the helper under [id]; null when none is stored, false when it was refused. */
        fun play(button: ComboButton, id: Int): Boolean?
        fun stopHelper()
        fun helperMissing()
    }

    /** The hotkey whose macro the helper is playing, under [playingId]; null when none is. */
    var playing: ComboButton? = null
        private set
    private var playingId = 0
    /** The macro this Back press stopped: its own hotkey in the same press only cancels, it does not restart. */
    private var cancelled: ComboButton? = null
    /**
     * The helper stops a macro the moment Back goes down and reports it over its socket, which usually
     * arrives before the Back key itself has passed Android's input pipeline. That Back press must
     * still count as the one that cancelled.
     */
    private var stopAwaitsBack = false

    /**
     * Plays [button]'s macro through the helper's pad clone. The helper starts it once Back is
     * released, so the macro's keys never read as Back + button shortcuts; pressing the same hotkey
     * while it runs only stops it.
     */
    fun run(button: ComboButton) {
        if (cancelled == button) {
            cancelled = null
            return
        }
        when (host.play(button, ++playingId)) {
            null -> Unit
            true -> playing = button
            false -> host.helperMissing()
        }
    }

    /** A Back press begins; the helper stops the macro on it too, so mirror that and remember whose it was. */
    fun backDown() {
        if (playing != null) {
            cancelled = playing
            stop()
        } else if (!stopAwaitsBack) {
            cancelled = null
        }
        stopAwaitsBack = false
    }

    fun ended(id: Int, stopped: Boolean) {
        if (id != playingId || playing == null) return
        if (stopped) {
            cancelled = playing
            stopAwaitsBack = true
        }
        playing = null
    }

    fun stop() {
        host.stopHelper()
        playing = null
    }

    /** A lost pad stops a macro without any Back press that could claim the cancel. */
    fun padLost() {
        playing = null
        cancelled = null
        stopAwaitsBack = false
    }

    /** The service goes away; the helper ends with it. */
    fun forget() {
        playing = null
    }
}
