package dev.steinhorst.backbuttonhero

import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.steinhorst.backbuttonhero.actions.Ayn
import dev.steinhorst.backbuttonhero.actions.HidePopups
import dev.steinhorst.backbuttonhero.actions.PopupRestore
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.input.BackGestureButton
import dev.steinhorst.backbuttonhero.overlay.FocusIndicatorOverlay
import dev.steinhorst.backbuttonhero.overlay.NoticeOverlay
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.recents.SwitcherOverlay
import dev.steinhorst.backbuttonhero.support.FakePService
import java.time.Duration
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

/** "Hide pop-ups" in the service: the hotkey, the restore at the start, on a pause and at the service's end. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class ServiceHidePopupsTest {
    private val found = PopupRestore("1", "allow")

    private class Host : HidePopups.Host {
        val commands = mutableListOf<String>()
        private val main = Handler(Looper.getMainLooper())
        override fun rootAvailable() = true
        override fun exec(command: String): String? {
            synchronized(commands) { commands += command }
            return if (command == HidePopups.READ_COMMAND) "1 TOAST_WINDOW: allow; time=+1s" else "OK"
        }
        override fun startGuard(restore: PopupRestore, onLost: (HidePopups.Guard) -> Unit) =
            object : HidePopups.Guard { override fun close(restored: Boolean) = Unit }
        override fun post(block: () -> Unit) { main.post(block) }
    }

    private fun connected(fake: FakePService, check: (SwitcherService, Prefs) -> Unit) {
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            val source = object : AppSource {
                override fun appsOnDisplay(displayId: Int) = emptyList<RunningApp>()
                override fun forget(packageName: String) = Unit
            }
            ReflectionHelpers.setField(service, "overlay", SwitcherOverlay(service, source, { true }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}))
            ReflectionHelpers.setField(service, "focusIndicator", FocusIndicatorOverlay(service))
            check(service, Prefs(service))
        } finally {
            runCatching { service.onDestroy() }
            fake.uninstall()
        }
    }

    private fun settle(hide: HidePopups) {
        ReflectionHelpers.getField<ScheduledThreadPoolExecutor>(hide, "worker").submit {}.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }

    private fun hidePopups(service: SwitcherService) = ReflectionHelpers.getField<HidePopups>(service, "hidePopups")

    /** Swaps in a fake root, so the toggle can reach its applied state without a real guard. */
    private fun withFakeRoot(service: SwitcherService, prefs: Prefs): Pair<HidePopups, Host> {
        hidePopups(service).close()
        val host = Host()
        val notice = ReflectionHelpers.callInstanceMethod<NoticeOverlay>(service, "getNotice")
        val hide = HidePopups(prefs, notice, host)
        ReflectionHelpers.setField(service, "hidePopups", hide)
        return hide to host
    }

    @Test
    fun `values left applied by an earlier session are restored when the service starts`() {
        val fake = FakePService().install()
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs(app).hiddenPopups = found
        connected(fake) { service, prefs ->
            settle(hidePopups(service))
            assertTrue(fake.commands.any { it.endsWith(HidePopups.restoreCommand(found)) })
            assertNull(prefs.hiddenPopups)
        }
    }

    private fun bindDoNotDisturb(prefs: Prefs, hidePopups: Boolean) {
        val before = prefs.snapshot()
        val single = BackGestureButton.SINGLE.prefKey
        prefs.applySettings(before, before.copy(bindings = before.bindings +
            (single to before.bindings.getValue(single).copy(action = Ayn.Toggle.DO_NOT_DISTURB.action, dndHidePopups = hidePopups))))
    }

    @Test
    fun `Do Not Disturb hides pop-ups only with its option, and turning it off always shows them again`() {
        connected(FakePService().install()) { service, prefs ->
            val (hide, host) = withFakeRoot(service, prefs)
            bindDoNotDisturb(prefs, hidePopups = false)
            service.onSingleTap()
            settle(hide)
            assertFalse(hide.on)
            assertEquals(emptyList<String>(), host.commands)
            Settings.Global.putInt(service.contentResolver, "zen_mode", 1)
            service.onSingleTap()
            settle(hide)
            Settings.Global.putInt(service.contentResolver, "zen_mode", 0)
            bindDoNotDisturb(prefs, hidePopups = true)
            ShadowLog.clear()
            service.onSingleTap()
            settle(hide)
            assertTrue(hide.on)
            // Do Not Disturb's own notice still appears: it shows before the mute.
            val notices = ShadowLog.getLogsForTag("BackButtonHero").map { it.msg }.filter { it.startsWith("Notice") }
            assertTrue(notices.toString(), notices.any { it.startsWith("Notice: ") })
            // The option switched off meanwhile does not keep pop-ups hidden past Do Not Disturb.
            bindDoNotDisturb(prefs, hidePopups = false)
            Settings.Global.putInt(service.contentResolver, "zen_mode", 1)
            ShadowLog.clear()
            service.onSingleTap()
            settle(hide)
            assertFalse(hide.on)
            assertEquals(HidePopups.restoreCommand(found), host.commands.last())
            assertTrue(ShadowLog.getLogsForTag("BackButtonHero").any { it.msg.startsWith("Notice: ") })
        }
    }

    @Test
    fun `the hotkey hides, a pause restores and the pause message still appears`() {
        connected(FakePService().install()) { service, prefs ->
            val (hide, host) = withFakeRoot(service, prefs)
            // Without a shortcut a pause would undo itself at the next settings write.
            prefs.toggleButtons = Prefs.TOGGLE_L1_R1
            bindDoNotDisturb(prefs, hidePopups = true)
            service.onSingleTap()
            settle(hide)
            assertTrue(hide.on)
            assertEquals(listOf(HidePopups.READ_COMMAND, HidePopups.APPLY_COMMAND), host.commands)
            ShadowLog.clear()
            ReflectionHelpers.callInstanceMethod<Unit>(service, "toggleInterception")
            settle(hide)
            assertFalse(hide.on)
            assertEquals(HidePopups.restoreCommand(found), host.commands.last())
            val notices = ShadowLog.getLogsForTag("BackButtonHero").map { it.msg }.filter { it.startsWith("Notice") }
            assertEquals(1, notices.size)
            assertTrue(notices.single(), notices.single().startsWith("Notice: "))
            assertFalse(notices.single().contains("Pop-ups"))
        }
    }

    @Test
    fun `the service's end restores`() {
        connected(FakePService().install()) { service, prefs ->
            val (hide, host) = withFakeRoot(service, prefs)
            hide.set(true)
            settle(hide)
            assertEquals(found, prefs.hiddenPopups)
            service.onUnbind(null)
            assertTrue(ReflectionHelpers.getField<ScheduledThreadPoolExecutor>(hide, "worker").awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(HidePopups.restoreCommand(found), host.commands.last())
            assertNull(prefs.hiddenPopups)
        }
    }
}
