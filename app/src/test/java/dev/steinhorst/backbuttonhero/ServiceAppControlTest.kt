package dev.steinhorst.backbuttonhero

import android.accessibilityservice.AccessibilityService
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import dev.steinhorst.backbuttonhero.actions.AppControlActions
import dev.steinhorst.backbuttonhero.actions.AppControlPrompt
import dev.steinhorst.backbuttonhero.core.AccessibilityAppSource
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.overlay.FaceButtonPictogram
import dev.steinhorst.backbuttonhero.overlay.FocusIndicatorOverlay
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.recents.SwitcherOverlay
import dev.steinhorst.backbuttonhero.support.FakePService
import java.io.File
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

/**
 * App control, close apps and standby with apps on both screens: their prompts, what a choice or
 * Cancel does, and that "ask" acts on the apps in front when the prompt opened. Root commands,
 * global actions, launches and notices are frozen in `src/test/resources/commands/appcontrol.txt`,
 * recorded once on the pre-R1 baseline; UPDATE_COMMAND_FIXTURES=1 re-records, only for an approved change.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceAppControlTest {
    private val button: ComboButton = ShoulderButton.L1
    private var bottomId = -1

    private fun scenario(label: String, out: StringBuilder, setup: (Prefs) -> Unit, block: (SwitcherService, Prompt) -> Unit) {
        val fake = FakePService().install()
        bottomId = ShadowDisplayManager.addDisplay("w400dp-h300dp")
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        try {
            front(service, "com.example.top", "com.example.bottom")
            // Launchable, so a restart has something to start again.
            for (pkg in listOf("com.example.top", "com.example.bottom", "com.example.later")) {
                val info = ResolveInfo().apply {
                    activityInfo = ActivityInfo().apply {
                        packageName = pkg
                        name = "$pkg.Main"
                        applicationInfo = ApplicationInfo().apply { packageName = pkg }
                    }
                }
                shadowOf(service.packageManager).addResolveInfoForIntent(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), info)
            }
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            settle()
            val empty = object : AppSource {
                override fun appsOnDisplay(displayId: Int) = emptyList<RunningApp>()
                override fun forget(packageName: String) = Unit
            }
            ReflectionHelpers.setField(service, "overlay", SwitcherOverlay(service, empty, { true }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}))
            ReflectionHelpers.setField(service, "focusIndicator", FocusIndicatorOverlay(service))
            val source = ReflectionHelpers.getField<AccessibilityAppSource>(service, "source")
            source.record(0)
            source.record(bottomId)
            setup(Prefs(service))
            val startup = fake.commands.size
            val shadow = shadowOf(service as ContextWrapper)
            while (shadow.nextStartedActivityForResult != null) Unit
            shadowOf(service as AccessibilityService).globalActionsPerformed.clear()
            ShadowLog.clear()
            out.append("## ").append(label).append('\n')
            val prompt = Prompt(service, out)
            block(service, prompt)
            settle()
            // The helper starts asynchronously with the connection; its timing is not this family's.
            fake.commands.drop(startup).filterNot { it.contains("padhelper_start.sh") }.map { normalize(service, it) }
                .fold(mutableListOf<String>()) { acc, c -> if (acc.lastOrNull() != c) acc += c; acc }
                .forEach { out.append("root ").append(it).append('\n') }
            shadowOf(service as AccessibilityService).globalActionsPerformed.forEach { out.append("global ").append(it).append('\n') }
            while (true) {
                val started = shadow.nextStartedActivityForResult ?: break
                val intent = started.intent
                out.append("activity action=").append(intent.action).append(" categories=").append(intent.categories?.sorted())
                    .append(" package=").append(intent.`package` ?: intent.component?.packageName)
                    .append(" display=").append(started.options?.getInt("android.activity.launchDisplayId", -1)?.let { if (it == bottomId) "<bottom>" else "$it" }).append('\n')
            }
            ShadowLog.getLogsForTag("BackButtonHero").filter { it.msg.startsWith("Notice: ") }.forEach { out.append(it.msg).append('\n') }
        } finally {
            runCatching { service.onDestroy() }
            fake.uninstall()
            ShadowDisplayManager.removeDisplay(bottomId)
        }
    }

    inner class Prompt(private val service: SwitcherService, private val out: StringBuilder) {
        private fun view() = ReflectionHelpers.getField<Pair<WindowManager, View>?>(
            ReflectionHelpers.getField<AppControlPrompt>(service, "appControlPrompt"), "window")?.second

        /** Records the open prompt, then taps the row labelled [label]. */
        fun choose(label: String) {
            val view = view()
            if (view == null) {
                out.append("no prompt\n")
                return
            }
            out.append("prompt ").append(texts(view)).append(" -> ").append(label).append('\n')
            rows(view).first { it.text.toString() == label }.performClick()
            settle()
        }

        fun none() = out.append(if (view() == null) "no prompt\n" else "prompt ${texts(view()!!)}\n")
    }

    private fun front(service: SwitcherService, top: String, bottom: String) {
        shadowOf(service).setWindowsOnDisplay(0, listOf(appWindow(top)))
        shadowOf(service).setWindowsOnDisplay(bottomId, listOf(appWindow(bottom)))
    }

    private fun appWindow(pkg: String): AccessibilityWindowInfo = AccessibilityWindowInfo.obtain().also { window ->
        shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(window).setRoot(AccessibilityNodeInfo.obtain().apply { packageName = pkg })
    }

    private fun texts(view: View): List<String> = rows(view).map { it.text.toString() }

    private fun rows(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        // The controller legend under the rows is a hint, not something the prompt offers.
        is ViewGroup -> if (view.getChildAt(0) is FaceButtonPictogram) emptyList() else (0 until view.childCount).flatMap { rows(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun settle() = repeat(30) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        Thread.sleep(20)
    }

    private fun normalize(service: SwitcherService, text: String) = Regex("pservice_\\d+\\.sh").replace(
        text.replace(service.cacheDir.absolutePath, "<cache>").replace(service.applicationInfo.sourceDir, "<apk>"), "pservice_<n>.sh")
        .replace("'$bottomId'", "'<bottom>'").replace("-d $bottomId ", "-d <bottom> ").replace("display $bottomId", "display <bottom>")

    private fun handler(service: SwitcherService) = ReflectionHelpers.callInstanceMethod<AppControlActions>(service, "getAppControl")

    private fun appControl(service: SwitcherService) = handler(service).runAppControl(button)

    private fun closeApps(service: SwitcherService, keep: Boolean) = handler(service).runCloseApps(button, keep)

    private fun standby(service: SwitcherService) = handler(service).runStandby(button)

    private fun mode(mode: String, screen: String, confirm: Boolean): (Prefs) -> Unit = { prefs ->
        prefs.setAppMode(button, mode)
        prefs.setAppScreen(button, screen)
        prefs.setAppConfirm(button, confirm)
    }

    @Test
    fun `app control, close apps and standby keep their prompts and effects`() {
        val out = StringBuilder()
        scenario("minimize top", out, mode(Prefs.APP_MINIMIZE, Prefs.DISPLAY_TOP, confirm = true)) { s, p -> appControl(s); p.none() }
        scenario("minimize both", out, mode(Prefs.APP_MINIMIZE, Prefs.TARGET_BOTH, confirm = false)) { s, p -> appControl(s); p.none() }
        scenario("force close bottom", out, mode(Prefs.APP_FORCE_CLOSE, Prefs.DISPLAY_BOTTOM, confirm = false)) { s, p -> appControl(s); p.none() }
        scenario("force close top, confirm, cancel", out, mode(Prefs.APP_FORCE_CLOSE, Prefs.DISPLAY_TOP, confirm = true)) { s, p ->
            appControl(s); p.choose(s.getString(R.string.app_control_prompt_cancel))
        }
        scenario("force close top, confirm, yes", out, mode(Prefs.APP_FORCE_CLOSE, Prefs.DISPLAY_TOP, confirm = true)) { s, p ->
            appControl(s); p.choose(s.getString(R.string.app_control_prompt_close_yes))
        }
        scenario("restart both, confirm, yes", out, mode(Prefs.APP_RESTART, Prefs.TARGET_BOTH, confirm = true)) { s, p ->
            appControl(s); p.choose(s.getString(R.string.app_control_prompt_restart_yes))
        }
        scenario("restart top", out, mode(Prefs.APP_RESTART, Prefs.DISPLAY_TOP, confirm = false)) { s, p -> appControl(s); p.none() }
        scenario("ask, front changes while open, top", out, mode(Prefs.APP_FORCE_CLOSE, Prefs.APP_SCREEN_ASK, confirm = true)) { s, p ->
            appControl(s)
            front(s, "com.example.later", "com.example.laterbottom")
            p.choose(s.getString(R.string.level_target_top))
        }
        scenario("ask, cancel", out, mode(Prefs.APP_MINIMIZE, Prefs.APP_SCREEN_ASK, confirm = false)) { s, p ->
            appControl(s); p.choose(s.getString(R.string.app_control_prompt_cancel))
        }
        scenario("ask, bottom", out, mode(Prefs.APP_MINIMIZE, Prefs.APP_SCREEN_ASK, confirm = false)) { s, p ->
            appControl(s); p.choose(s.getString(R.string.level_target_bottom))
        }
        scenario("force close top without app", out, mode(Prefs.APP_FORCE_CLOSE, Prefs.DISPLAY_TOP, confirm = true)) { s, p ->
            front(s, "com.android.launcher3", "com.example.bottom")
            appControl(s); p.none()
        }
        val both: (Prefs) -> Unit = { it.setLevelTarget(button, Prefs.TARGET_BOTH) }
        scenario("close apps, confirm, yes", out, both) { s, p -> closeApps(s, keep = false); p.choose(s.getString(R.string.close_apps_prompt_yes)) }
        scenario("close apps, confirm, cancel", out, both) { s, p -> closeApps(s, keep = false); p.choose(s.getString(R.string.app_control_prompt_cancel)) }
        scenario("close background apps, no confirm", out, { both(it); it.setCloseConfirm(button, false) }) { s, p -> closeApps(s, keep = true); p.none() }
        scenario("close apps top, no confirm", out, { it.setLevelTarget(button, Prefs.DISPLAY_TOP); it.setCloseConfirm(button, false) }) { s, p ->
            closeApps(s, keep = false); p.none()
        }
        scenario("standby, confirm, yes", out, { it.setStandbyConfirm(button, true) }) { s, p -> standby(s); p.choose(s.getString(R.string.group_standby)) }
        scenario("standby, confirm, cancel", out, { it.setStandbyConfirm(button, true) }) { s, p -> standby(s); p.choose(s.getString(R.string.app_control_prompt_cancel)) }
        scenario("standby, no confirm", out, { it.setStandbyConfirm(button, false) }) { s, p -> standby(s); p.none() }
        val file = File("src/test/resources/commands/appcontrol.txt")
        if (System.getenv("UPDATE_COMMAND_FIXTURES") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(out.toString())
            return
        }
        if (!file.exists()) fail("Fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("app control effects differ from the frozen fixture", file.readText(), out.toString())
    }
}
