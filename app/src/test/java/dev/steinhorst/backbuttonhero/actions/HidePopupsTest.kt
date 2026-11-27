package dev.steinhorst.backbuttonhero.actions

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.overlay.NoticeOverlay
import dev.steinhorst.backbuttonhero.prefs.Prefs
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

/**
 * The "Hide pop-ups" toggle against a fake root: what it reads, applies and restores, when, which notices
 * get through while it is on, and how saved values of an earlier session are put back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class HidePopupsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = Prefs(app)
    private val notice = NoticeOverlay(app)
    private val found = PopupRestore("1", "allow")

    private class FakeGuard(val restore: PopupRestore, val onLost: (HidePopups.Guard) -> Unit) : HidePopups.Guard {
        var closed: Boolean? = null
        override fun close(restored: Boolean) { closed = restored }
    }

    private inner class FakeHost : HidePopups.Host {
        var root = true
        var guardStarts = true
        val commands = mutableListOf<String>()
        val guards = mutableListOf<FakeGuard>()
        val replies = mutableMapOf<String, String?>(
            HidePopups.READ_COMMAND to "1 TOAST_WINDOW: allow; time=+1h2m3s ago",
            HidePopups.APPLY_COMMAND to "OK",
        )
        private val main = Handler(Looper.getMainLooper())
        override fun rootAvailable() = root
        override fun exec(command: String): String? {
            synchronized(commands) { commands += command }
            return if (command in replies) replies[command] else "OK"
        }
        override fun startGuard(restore: PopupRestore, onLost: (HidePopups.Guard) -> Unit): HidePopups.Guard? =
            if (!guardStarts) null else FakeGuard(restore, onLost).also { synchronized(guards) { guards += it } }
        override fun post(block: () -> Unit) { main.post(block) }
    }

    private val host = FakeHost()
    private val hide = HidePopups(prefs, notice, host)

    /** Runs what waits on the main thread, the root work it queued, then what that posted back. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        ReflectionHelpers.getField<ScheduledThreadPoolExecutor>(hide, "worker").submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun shown(): List<String> = ShadowLog.getLogsForTag("BackButtonHero").map { it.msg }
        .filter { it.startsWith("Notice") }

    private fun restore(values: PopupRestore = found) = HidePopups.restoreCommand(values)

    @Test
    fun `on saves what it found, starts the guard and hides toasts and heads-up`() {
        ShadowLog.clear()
        hide.set(!hide.on)
        settle()
        assertTrue(hide.on)
        assertEquals(listOf(HidePopups.READ_COMMAND, HidePopups.APPLY_COMMAND), host.commands)
        assertEquals(found, prefs.hiddenPopups)
        assertEquals(listOf(found), host.guards.map { it.restore })
        assertNull(host.guards.single().closed)
        // Do Not Disturb's own notice is the confirmation; this option shows none.
        assertEquals(emptyList<String>(), shown())
    }

    @Test
    fun `while on only forced notices appear, the rest is logged as muted`() {
        hide.set(!hide.on)
        settle()
        ShadowLog.clear()
        notice.show("Gyro on")
        notice.show("Memory cleared", force = true)
        assertEquals(listOf("Notice muted: Gyro on", "Notice: Memory cleared"), shown())
        hide.set(!hide.on)
        settle()
        ShadowLog.clear()
        notice.show("Gyro off")
        assertEquals(listOf("Notice: Gyro off"), shown())
    }

    @Test
    fun `off unmutes at once, restores, tells the guard and forgets the saved values`() {
        hide.set(!hide.on)
        settle()
        host.commands.clear()
        ShadowLog.clear()
        hide.set(!hide.on)
        // Unmuted at once, so Do Not Disturb's "off" notice that follows is shown.
        assertFalse(notice.muted)
        assertEquals(emptyList<String>(), shown())
        settle()
        assertFalse(hide.on)
        assertEquals(listOf(restore()), host.commands)
        assertEquals(true, host.guards.single().closed)
        assertNull(prefs.hiddenPopups)
        assertEquals(emptyList<String>(), shown())
    }

    @Test
    fun `a failed restore keeps the saved values and leaves the restore to the guard`() {
        hide.set(!hide.on)
        settle()
        host.replies[restore()] = null
        hide.set(!hide.on)
        settle()
        assertEquals(false, host.guards.single().closed)
        assertEquals(found, prefs.hiddenPopups)
    }

    @Test
    fun `a pause and the service's end restore without a message, and nothing runs after the end`() {
        hide.set(!hide.on)
        settle()
        ShadowLog.clear()
        hide.release()
        settle()
        assertFalse(hide.on)
        assertEquals(restore(), host.commands.last())
        assertEquals(emptyList<String>(), shown())
        hide.release()
        hide.set(!hide.on)
        settle()
        host.commands.clear()
        hide.close()
        assertTrue(ReflectionHelpers.getField<ScheduledThreadPoolExecutor>(hide, "worker").awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(listOf(restore()), host.commands)
        hide.set(!hide.on)
        hide.restoreLeftover()
        assertEquals(listOf(restore()), host.commands)
    }

    @Test
    fun `without root only the own notices are muted and a forced message says so`() {
        host.root = false
        ShadowLog.clear()
        hide.set(!hide.on)
        settle()
        assertTrue(hide.on)
        assertTrue(notice.muted)
        assertEquals(emptyList<String>(), host.commands)
        assertEquals(listOf("Notice: System pop-ups could not be hidden, only this app's own messages."), shown())
        ShadowLog.clear()
        hide.set(!hide.on)
        settle()
        assertEquals(emptyList<String>(), host.commands)
        assertEquals(emptyList<String>(), shown())
        assertFalse(notice.muted)
    }

    @Test
    fun `setting the state it already has does nothing`() {
        hide.set(false)
        settle()
        assertEquals(emptyList<String>(), host.commands)
        hide.set(true)
        settle()
        host.commands.clear()
        hide.set(true)
        settle()
        assertEquals(emptyList<String>(), host.commands)
        assertEquals(1, host.guards.size)
    }

    @Test
    fun `an unreadable state changes nothing`() {
        host.replies[HidePopups.READ_COMMAND] = "OK"
        hide.set(!hide.on)
        settle()
        assertEquals(listOf(HidePopups.READ_COMMAND), host.commands)
        assertNull(prefs.hiddenPopups)
        assertTrue(host.guards.isEmpty())
    }

    @Test
    fun `without a guard nothing system-wide is changed`() {
        host.guardStarts = false
        ShadowLog.clear()
        hide.set(!hide.on)
        settle()
        assertEquals(listOf(HidePopups.READ_COMMAND), host.commands)
        assertNull(prefs.hiddenPopups)
        assertEquals("Notice: System pop-ups could not be hidden, only this app's own messages.", shown().last())
    }

    @Test
    fun `a failed apply puts back whatever part of it ran`() {
        host.replies[HidePopups.APPLY_COMMAND] = ""
        hide.set(!hide.on)
        settle()
        assertEquals(listOf(HidePopups.READ_COMMAND, HidePopups.APPLY_COMMAND, restore()), host.commands)
        assertEquals(true, host.guards.single().closed)
        assertNull(prefs.hiddenPopups)
        hide.set(!hide.on)
        settle()
        assertEquals(3, host.commands.size)
    }

    @Test
    fun `a failed apply whose restore fails too keeps the saved values for the next start`() {
        host.replies[HidePopups.APPLY_COMMAND] = null
        host.replies[restore()] = null
        hide.set(!hide.on)
        settle()
        assertEquals(false, host.guards.single().closed)
        assertEquals(found, prefs.hiddenPopups)
    }

    @Test
    fun `a lost guard shows pop-ups again, a stale one changes nothing`() {
        hide.set(!hide.on)
        settle()
        val first = host.guards.single()
        hide.set(!hide.on)
        settle()
        hide.set(!hide.on)
        settle()
        first.onLost(first)
        settle()
        assertTrue(hide.on)
        ShadowLog.clear()
        val current = host.guards.last()
        current.onLost(current)
        settle()
        assertFalse(hide.on)
        assertEquals(restore(), host.commands.last())
        assertFalse(notice.muted)
    }

    @Test
    fun `values left by an earlier session are restored at the start, or first when turning on`() {
        val leftover = PopupRestore("null", "default")
        prefs.hiddenPopups = leftover
        hide.restoreLeftover()
        settle()
        assertEquals(listOf(restore(leftover)), host.commands)
        assertNull(prefs.hiddenPopups)
        host.commands.clear()
        hide.restoreLeftover()
        settle()
        assertEquals(emptyList<String>(), host.commands)

        prefs.hiddenPopups = leftover
        hide.set(!hide.on)
        settle()
        assertEquals(listOf(restore(leftover), HidePopups.READ_COMMAND, HidePopups.APPLY_COMMAND), host.commands)
        assertEquals(found, prefs.hiddenPopups)
    }

    @Test
    fun `a leftover that cannot be restored blocks turning on and is retried later`() {
        val leftover = PopupRestore("0", "allow")
        prefs.hiddenPopups = leftover
        host.root = false
        hide.restoreLeftover()
        settle()
        assertEquals(leftover, prefs.hiddenPopups)
        assertEquals(1, ReflectionHelpers.getField<ScheduledThreadPoolExecutor>(hide, "worker").queue.size)
        host.root = true
        host.replies[restore(leftover)] = null
        hide.set(!hide.on)
        settle()
        assertEquals(listOf(restore(leftover)), host.commands)
        assertEquals(leftover, prefs.hiddenPopups)
        assertTrue(host.guards.isEmpty())
    }

    @Test
    fun `a retry after a session applied leaves the values to that session`() {
        prefs.hiddenPopups = PopupRestore("0", "allow")
        host.root = false
        hide.restoreLeftover()
        settle()
        host.root = true
        hide.set(!hide.on)
        settle()
        host.commands.clear()
        ReflectionHelpers.callInstanceMethod<Unit>(hide, "restoreLeftover", ReflectionHelpers.ClassParameter.from(Int::class.java, 1))
        assertEquals(emptyList<String>(), host.commands)
        assertEquals(found, prefs.hiddenPopups)
    }

    @Test
    fun `the last retry gives up`() {
        prefs.hiddenPopups = PopupRestore("0", "allow")
        host.root = false
        ReflectionHelpers.callInstanceMethod<Unit>(hide, "restoreLeftover", ReflectionHelpers.ClassParameter.from(Int::class.java, 1))
        assertEquals(0, ReflectionHelpers.getField<ScheduledThreadPoolExecutor>(hide, "worker").queue.size)
        prefs.hiddenPopups = null
        ReflectionHelpers.callInstanceMethod<Unit>(hide, "restoreLeftover", ReflectionHelpers.ClassParameter.from(Int::class.java, 1))
        assertEquals(emptyList<String>(), host.commands)
    }

    @Test
    fun `the read line is parsed into what a restore can carry`() {
        assertEquals(PopupRestore("1", "allow"), HidePopups.parse("1 TOAST_WINDOW: allow; time=+3m ago"))
        assertEquals(PopupRestore("null", "default"), HidePopups.parse(" null No operations.\n"))
        assertEquals(PopupRestore("0", "deny"), HidePopups.parse("0 Uid mode: TOAST_WINDOW: ignore TOAST_WINDOW: deny; time=+1s"))
        assertEquals(PopupRestore("1", "default"), HidePopups.parse("1 Uid mode: TOAST_WINDOW: ignore"))
        assertNull(HidePopups.parse(null))
        assertNull(HidePopups.parse("OK"))
        assertNull(HidePopups.parse("1 Error: unknown operation"))
        assertNull(HidePopups.parse("1 TOAST_WINDOW: bogus"))
        assertNull(HidePopups.parse("on No operations."))
        assertNull(HidePopups.parse("1; reboot No operations."))
        assertTrue(HidePopups.succeeded(" OK\n"))
        assertFalse(HidePopups.succeeded(null))
        assertFalse(HidePopups.succeeded("ERR"))
    }

    @Test
    fun `only values a shell command can safely carry are accepted`() {
        assertEquals(PopupRestore("-1", "foreground"), PopupRestore.of("-1", "foreground"))
        assertNull(PopupRestore.of("1 && reboot", "allow"))
        assertNull(PopupRestore.of("1", "allow; reboot"))
        assertNull(PopupRestore.of("", "allow"))
        assertNull(PopupRestore.of("1234567890", "allow"))
    }

    @Test
    fun `the saved values are device state with both keys required`() {
        assertNull(prefs.hiddenPopups)
        prefs.hiddenPopups = found
        assertEquals(found, prefs.hiddenPopups)
        val sp = app.getSharedPreferences("back_button_hero", 0)
        assertEquals("1", sp.getString("hide_popups_heads_up", null))
        assertEquals("allow", sp.getString("hide_popups_toast", null))
        sp.edit().remove("hide_popups_toast").commit()
        assertNull(prefs.hiddenPopups)
        sp.edit().putString("hide_popups_toast", "bogus").commit()
        assertNull(prefs.hiddenPopups)
        prefs.hiddenPopups = null
        assertFalse(sp.contains("hide_popups_heads_up") || sp.contains("hide_popups_toast"))
        assertFalse("hide_popups_heads_up" in prefs.snapshot().toString())
    }

    /**
     * Freezes the root commands of the toggle and the guard's launch script in
     * `src/test/resources/commands/popups.txt`; UPDATE_COMMAND_FIXTURES=1 re-records, only for an approved change.
     */
    @Test
    fun `the root commands are unchanged`() {
        val commands = listOf(
            "read" to HidePopups.READ_COMMAND,
            "apply" to HidePopups.APPLY_COMMAND,
            "restore 1 allow" to restore(),
            "restore null default" to restore(PopupRestore("null", "default")),
            "guard launch" to PopupGuardLink.launchScript("<apk>", "dev.steinhorst.backbuttonhero",
                "dev.steinhorst.backbuttonhero.popups.<uuid>", 10123, PopupRestore("null", "foreground")).trimEnd(),
        )
        // pservice mangles commands of 256 characters and more (measured on the Thor), also with the longest values.
        val longest = "restore longest" to restore(PopupRestore("-123456789", "foreground"))
        (commands.take(4) + longest).forEach { (label, command) -> assertTrue("$label: ${command.length}", command.length < 256) }
        val out = commands.joinToString("") { (label, command) -> "## $label\n$command\n" }
        val file = File("src/test/resources/commands/popups.txt")
        if (System.getenv("UPDATE_COMMAND_FIXTURES") == "1") {
            file.writeText(out)
            return
        }
        if (!file.exists()) fail("Command fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("root commands differ from the frozen fixture", file.readText(), out)
    }
}
