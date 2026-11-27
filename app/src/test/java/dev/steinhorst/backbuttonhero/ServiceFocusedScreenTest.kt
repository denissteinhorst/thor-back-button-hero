package dev.steinhorst.backbuttonhero

import android.accessibilityservice.AccessibilityService
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import dev.steinhorst.backbuttonhero.actions.AppControlPrompt
import dev.steinhorst.backbuttonhero.actions.ControllerFocus
import dev.steinhorst.backbuttonhero.actions.FocusedScreen
import dev.steinhorst.backbuttonhero.core.AccessibilityAppSource
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.input.BackGestureButton
import dev.steinhorst.backbuttonhero.overlay.FocusIndicatorOverlay
import dev.steinhorst.backbuttonhero.prefs.BindingSettings
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.recents.SwitcherOverlay
import dev.steinhorst.backbuttonhero.support.FakePService
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

/**
 * The focused-screen target acts exactly as the fixed screen the controller drives: a fixed route
 * names it, Auto-lock reads it from Android's input focus (falling back to the top screen). Each
 * focused run is compared with the run of the same action on that fixed screen, so the effects
 * themselves stay frozen by the action fixtures.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceFocusedScreenTest {
    private var bottomId = -1

    private class Run(val effects: List<String>, val inputReads: Int, val root: List<String>)

    private fun run(route: String?, input: String?, apps: Boolean = true, binding: (BindingSettings) -> BindingSettings): Run {
        // The bottom display's id is only known once the run added it; the answer names it then.
        val fake = FakePService { if (it == FocusedScreen.INPUT_FOCUS_COMMAND) input.orEmpty().replace("<bottom>", "$bottomId") else "OK" }.install()
        bottomId = ShadowDisplayManager.addDisplay("w400dp-h300dp")
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        try {
            shadowOf(service).setWindowsOnDisplay(0, listOf(appWindow("com.example.top")))
            // An app in front on the bottom screen makes the switcher take a thumbnail, which Robolectric cannot.
            if (apps) shadowOf(service).setWindowsOnDisplay(bottomId, listOf(appWindow("com.example.bottom")))
            val info = ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = GAME
                    name = "$GAME.Main"
                    applicationInfo = ApplicationInfo().apply { packageName = GAME }
                }
            }
            shadowOf(service.packageManager).addResolveInfoForIntent(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(GAME), info)
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            settle()
            val source = object : AppSource {
                override fun appsOnDisplay(displayId: Int) = emptyList<RunningApp>()
                override fun forget(packageName: String) = Unit
            }
            val overlay = SwitcherOverlay(service, source, { true }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {})
            ReflectionHelpers.setField(service, "overlay", overlay)
            ReflectionHelpers.setField(service, "focusIndicator", FocusIndicatorOverlay(service))
            ReflectionHelpers.getField<AccessibilityAppSource>(service, "source").apply {
                record(0)
                record(bottomId)
            }
            Settings.System.putString(service.contentResolver, ControllerFocus.FOCUS_SETTING, route)
            val prefs = Prefs(service)
            val before = prefs.snapshot()
            val single = BackGestureButton.SINGLE.prefKey
            prefs.applySettings(before, before.copy(bindings = before.bindings + (single to binding(before.bindings.getValue(single)))))
            val shadow = shadowOf(service as ContextWrapper)
            while (shadow.nextStartedActivityForResult != null) Unit
            shadowOf(service as AccessibilityService).globalActionsPerformed.clear()
            ShadowLog.clear()
            val startup = fake.commands.size
            service.onSingleTap()
            settle()
            val out = mutableListOf<String>()
            shadowOf(service as AccessibilityService).globalActionsPerformed.forEach { out += "global $it" }
            while (true) {
                val started = shadow.nextStartedActivityForResult ?: break
                val display = started.options?.getInt("android.activity.launchDisplayId", -1)
                out += "activity ${started.intent.action} ${started.intent.categories?.sorted()} ${started.intent.`package`} " +
                    "display=${if (display == bottomId) "<bottom>" else "$display"}"
            }
            val commands = fake.commands.drop(startup).filterNot { it.contains("padhelper_start.sh") }
            val reads = commands.count { it.endsWith(FocusedScreen.INPUT_FOCUS_COMMAND) }
            // Every run adds its own bottom display, so its id (and the combined screenshot id) gets a
            // name; polls repeat as often as the run's timing allows, so a repeat counts once.
            commands.filterNot { it.endsWith(FocusedScreen.INPUT_FOCUS_COMMAND) }
                .map {
                    Regex("pservice_\\d+\\.sh").replace(it, "pservice_<n>.sh")
                        .replace("'${bottomId shl SwitcherService.COMBINED_DISPLAY_SHIFT}'", "'<both>'")
                        .replace("'$bottomId'", "'<bottom>'")
                }
                .fold(mutableListOf<String>()) { acc, c -> if (acc.lastOrNull() != c) acc += c; acc }
                .forEach { out += "root $it" }
            ShadowLog.getLogsForTag("BackButtonHero").filter { it.msg.startsWith("Notice: ") }.forEach { out += it.msg }
            val prompt = ReflectionHelpers.getField<AppControlPrompt>(service, "appControlPrompt")
            ReflectionHelpers.getField<Pair<WindowManager, View>?>(prompt, "window")?.let { (_, view) -> out += "prompt ${texts(view)}" }
            if (overlay.isShowing) out += "switcher showing"
            return Run(out, reads, commands)
        } finally {
            runCatching { service.onDestroy() }
            fake.uninstall()
            ShadowDisplayManager.removeDisplay(bottomId)
        }
    }

    private fun appWindow(pkg: String): AccessibilityWindowInfo = AccessibilityWindowInfo.obtain().also { window ->
        shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(window).setRoot(AccessibilityNodeInfo.obtain().apply { packageName = pkg })
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun settle() = repeat(30) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        Thread.sleep(20)
    }

    /** A route, the input display Auto-lock reports, and the screen the controller then drives. */
    private class Case(val name: String, val route: String?, val input: String?, val screen: String)

    private val cases get() = listOf(
        Case("fixed top", ControllerFocus.TOP_VALUE, null, Prefs.DISPLAY_TOP),
        Case("fixed bottom", ControllerFocus.BOTTOM_VALUE, null, Prefs.DISPLAY_BOTTOM),
        Case("auto on bottom", ControllerFocus.AUTO_VALUE, "  FocusedDisplayId: <bottom>", Prefs.DISPLAY_BOTTOM),
        Case("auto on top", ControllerFocus.AUTO_VALUE, "  FocusedDisplayId: 0", Prefs.DISPLAY_TOP),
        Case("unset, unreadable", null, "", Prefs.DISPLAY_TOP),
    )

    /** Runs [focused] in every case and compares it with [fixed] on the screen of that case. */
    private fun sameAsFixed(
        label: String,
        focused: (BindingSettings) -> BindingSettings,
        apps: Boolean = true,
        fixed: (BindingSettings, String) -> BindingSettings,
    ): Map<String, List<String>> =
        cases.associate { case ->
            val auto = run(case.route, case.input, apps, focused)
            val expected = run(case.route, null, apps) { fixed(it, case.screen) }
            assertEquals("$label, ${case.name}", expected.effects, auto.effects)
            val autoLock = case.route != ControllerFocus.TOP_VALUE && case.route != ControllerFocus.BOTTOM_VALUE
            assertEquals("$label, ${case.name}: input reads", if (autoLock) 1 else 0, auto.inputReads)
            case.screen to auto.effects
        }

    @Test
    fun `Home, apps, screenshot, brightness, app control and the app switcher act on the focused screen`() {
        val home = sameAsFixed("home", { it.copy(action = Prefs.SHOULDER_HOME, display = Prefs.TARGET_FOCUSED) }) { b, screen ->
            b.copy(action = Prefs.SHOULDER_HOME, display = screen)
        }
        assertNotEquals(home[Prefs.DISPLAY_TOP], home[Prefs.DISPLAY_BOTTOM])
        sameAsFixed("app", { it.copy(action = GAME, display = Prefs.TARGET_FOCUSED) }) { b, screen -> b.copy(action = GAME, display = screen) }
        val shot = sameAsFixed("screenshot", { it.copy(action = Prefs.SHOULDER_SCREENSHOT, targetOverride = Prefs.TARGET_FOCUSED) }) { b, screen ->
            b.copy(action = Prefs.SHOULDER_SCREENSHOT, targetOverride = screen)
        }
        assertNotEquals(shot[Prefs.DISPLAY_TOP], shot[Prefs.DISPLAY_BOTTOM])
        val brightness = sameAsFixed("brightness", { it.copy(action = Prefs.SHOULDER_BRIGHTNESS_UP, targetOverride = Prefs.TARGET_FOCUSED) }) { b, screen ->
            b.copy(action = Prefs.SHOULDER_BRIGHTNESS_UP, targetOverride = screen)
        }
        assertNotEquals(brightness[Prefs.DISPLAY_TOP], brightness[Prefs.DISPLAY_BOTTOM])
        val control = sameAsFixed("app control", {
            it.copy(action = Prefs.SHOULDER_APP_CONTROL, appMode = Prefs.APP_FORCE_CLOSE, appScreen = Prefs.TARGET_FOCUSED)
        }) { b, screen -> b.copy(action = Prefs.SHOULDER_APP_CONTROL, appMode = Prefs.APP_FORCE_CLOSE, appScreen = screen) }
        assertNotEquals(control[Prefs.DISPLAY_TOP], control[Prefs.DISPLAY_BOTTOM])
        val switcher = sameAsFixed("app switcher", { it.copy(action = Prefs.SHOULDER_RECENTS_FOCUSED) }, apps = false) { b, screen ->
            b.copy(action = if (screen == Prefs.DISPLAY_TOP) Prefs.SHOULDER_RECENTS else Prefs.SHOULDER_SWITCHER)
        }
        assertNotEquals(switcher[Prefs.DISPLAY_TOP], switcher[Prefs.DISPLAY_BOTTOM])
    }

    @Test
    fun `volume keeps treating a stored focused target as both screens`() {
        val focused = run(ControllerFocus.BOTTOM_VALUE, null) { it.copy(action = Prefs.SHOULDER_VOLUME_UP, targetOverride = Prefs.TARGET_FOCUSED) }
        val both = run(ControllerFocus.BOTTOM_VALUE, null) { it.copy(action = Prefs.SHOULDER_VOLUME_UP, targetOverride = Prefs.TARGET_BOTH) }
        assertEquals(both.effects, focused.effects)
        assertEquals(0, focused.inputReads)
    }

    @Test
    fun `the app list on the focused screen reads the screen before the list opens`() {
        val list = run(ControllerFocus.AUTO_VALUE, "  FocusedDisplayId: 0") { it.copy(action = Prefs.SHOULDER_APP_ASK, display = Prefs.TARGET_FOCUSED) }
        assertEquals(1, list.inputReads)
        assertTrue(list.effects.toString(), list.effects.any { it.startsWith("prompt ") })
    }

    @Test
    fun `controller focus offers Auto in its prompt and sets AYN's auto-lock`() {
        val ask = run(ControllerFocus.TOP_VALUE, null) { it.copy(action = Prefs.SHOULDER_CONTROLLER_FOCUS, focusTarget = Prefs.FOCUS_MODAL) }
        assertEquals(listOf("prompt [Set controller focus, Top screen, Bottom screen, Auto (follow touch), Cancel, Confirm, Cancel]"), ask.effects.filter { it.startsWith("prompt") })
        val auto = run(ControllerFocus.TOP_VALUE, null) { it.copy(action = Prefs.SHOULDER_CONTROLLER_FOCUS, focusTarget = Prefs.FOCUS_AUTO) }
        assertEquals(listOf(focusCommand("0")), auto.root)
        val fromAuto = run(ControllerFocus.AUTO_VALUE, "  FocusedDisplayId: 0") { it.copy(action = Prefs.SHOULDER_CONTROLLER_FOCUS, focusTarget = Prefs.FOCUS_TOGGLE) }
        assertEquals(listOf("code=0 args=[0] ${FocusedScreen.INPUT_FOCUS_COMMAND}", focusCommand("2")), fromAuto.root)
    }

    private fun focusCommand(value: String) =
        "code=0 args=[0] setprop persist.sys.input.dispaly $value && settings put system screen_focus_lock $value && input keyevent 60 && echo OK"

    private companion object {
        const val GAME = "com.example.game"
    }
}
