package dev.steinhorst.backbuttonhero.macro

import dev.steinhorst.backbuttonhero.input.ShoulderButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacroPlaybackTest {
    private val l1 = ShoulderButton.L1
    private val r1 = ShoulderButton.R1

    private class Host(var result: Boolean? = true) : MacroPlayback.Host {
        val log = mutableListOf<String>()
        override fun play(button: dev.steinhorst.backbuttonhero.input.ComboButton, id: Int): Boolean? {
            log += "play ${button.prefKey} $id"
            return result
        }
        override fun stopHelper() { log += "stop" }
        override fun helperMissing() { log += "missing" }
    }

    @Test
    fun `a stored macro plays under a fresh id, a refused one explains, none does nothing`() {
        val host = Host()
        val playback = MacroPlayback(host)
        playback.run(l1)
        assertEquals(l1, playback.playing)
        playback.forget()
        host.result = false
        playback.run(l1)
        host.result = null
        playback.run(l1)
        assertNull(playback.playing)
        assertEquals(listOf("play ${l1.prefKey} 1", "play ${l1.prefKey} 2", "missing", "play ${l1.prefKey} 3"), host.log)
    }

    @Test
    fun `Back stops the playing macro and its hotkey in that press only cancels once`() {
        val host = Host()
        val playback = MacroPlayback(host)
        playback.run(l1)
        playback.backDown()
        assertNull(playback.playing)
        playback.run(r1)
        playback.run(l1)
        playback.run(l1)
        assertEquals(listOf("play ${l1.prefKey} 1", "stop", "play ${r1.prefKey} 2", "play ${l1.prefKey} 3"), host.log)
    }

    @Test
    fun `a reported stop waits for its Back press, and the next one expires it`() {
        val host = Host()
        val playback = MacroPlayback(host)
        playback.run(l1)
        playback.ended(1, stopped = true)
        playback.backDown()
        playback.run(l1)
        assertEquals("cancelled by that press", 1, host.log.size)
        playback.run(l1)
        playback.ended(2, stopped = true)
        playback.backDown()
        playback.backDown()
        playback.run(l1)
        assertEquals("play ${l1.prefKey} 3", host.log.last())
    }

    @Test
    fun `a natural end, a stale id or a lost pad leaves nothing to cancel`() {
        val host = Host()
        val playback = MacroPlayback(host)
        playback.run(l1)
        playback.ended(1, stopped = false)
        playback.ended(1, stopped = true)
        playback.backDown()
        playback.run(l1)
        playback.ended(1, stopped = true)
        assertEquals(l1, playback.playing)
        playback.ended(2, stopped = true)
        playback.padLost()
        playback.backDown()
        playback.run(l1)
        assertEquals(listOf("play ${l1.prefKey} 1", "play ${l1.prefKey} 2", "play ${l1.prefKey} 3"), host.log)
    }
}
