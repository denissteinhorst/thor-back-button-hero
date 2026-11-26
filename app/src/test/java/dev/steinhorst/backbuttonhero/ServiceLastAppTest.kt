package dev.steinhorst.backbuttonhero

import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.steinhorst.backbuttonhero.actions.ControllerFocus
import dev.steinhorst.backbuttonhero.actions.LastAppAction
import dev.steinhorst.backbuttonhero.core.AccessibilityAppSource
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.overlay.FocusIndicatorOverlay
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
 * The last-app action on the controller's screen: no history, no root, a launched task, an app that
 * moved to the other screen, a task that is gone and a refused launch. Root commands, launches and
 * notices are frozen in `src/test/resources/commands/lastapp.txt`, recorded once on the pre-R1
 * baseline; UPDATE_COMMAND_FIXTURES=1 re-records, only for an approved change.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceLastAppTest {
    private var bottomId = -1

    /** [history] is recorded oldest first on [screen]; the last one is in front. [answer] maps a package to the launcher's reply. */
    private fun scenario(label: String, out: StringBuilder, history: List<String>, root: Boolean = true, bottom: Boolean = false,
        answer: (String) -> String = { "OK launched" }) {
        val fake = FakePService { command ->
            val script = Regex("^sh '([^']+)'$").find(command)?.let { File(it.groupValues[1]).takeIf(File::exists)?.readText() }.orEmpty()
            if ("RecentsTaskLauncher" in script) answer(Regex("RecentsTaskLauncher '([^']+)'").find(script)?.groupValues?.get(1).orEmpty()) else "OK"
        }
        if (root) fake.install()
        bottomId = ShadowDisplayManager.addDisplay("w400dp-h300dp")
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            settle()
            val empty = object : AppSource {
                override fun appsOnDisplay(displayId: Int) = emptyList<RunningApp>()
                override fun forget(packageName: String) = Unit
            }
            ReflectionHelpers.setField(service, "overlay", SwitcherOverlay(service, empty, { true }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}))
            ReflectionHelpers.setField(service, "focusIndicator", FocusIndicatorOverlay(service))
            if (bottom) Settings.System.putString(service.contentResolver, ControllerFocus.FOCUS_SETTING, ControllerFocus.BOTTOM_VALUE)
            val screen = if (bottom) bottomId else 0
            val source = ReflectionHelpers.getField<AccessibilityAppSource>(service, "source")
            for (pkg in history) {
                launchable(service, pkg)
                shadowOf(service).setWindowsOnDisplay(screen, listOf(appWindow(pkg)))
                source.record(screen)
            }
            val startup = fake.commands.size
            val shadow = shadowOf(service as ContextWrapper)
            while (shadow.nextStartedActivityForResult != null) Unit
            ShadowLog.clear()
            out.append("## ").append(label).append('\n')
            ReflectionHelpers.callInstanceMethod<LastAppAction>(service, "getLastApp").run()
            settle()
            fake.commands.drop(startup).filterNot { it.contains("padhelper_start.sh") }.map { normalize(service, it) }
                .forEach { out.append("root ").append(it).append('\n') }
            while (true) {
                val started = shadow.nextStartedActivityForResult ?: break
                val intent = started.intent
                out.append("activity action=").append(intent.action).append(" categories=").append(intent.categories?.sorted())
                    .append(" package=").append(intent.`package` ?: intent.component?.packageName)
                    .append(" display=").append(started.options?.getInt("android.activity.launchDisplayId", -1)?.let { if (it == bottomId) "<bottom>" else "$it" }).append('\n')
            }
            ShadowLog.getLogsForTag("BackButtonHero").filter { it.msg.startsWith("Notice: ") }.forEach { out.append(it.msg).append('\n') }
            out.append("history ").append(source.appsOnDisplay(screen).map { it.packageName }).append('\n')
        } finally {
            runCatching { service.onDestroy() }
            if (root) fake.uninstall()
            ShadowDisplayManager.removeDisplay(bottomId)
        }
    }

    private fun launchable(service: SwitcherService, pkg: String) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.Main"
                applicationInfo = ApplicationInfo().apply { packageName = pkg }
            }
        }
        @Suppress("DEPRECATION")
        shadowOf(service.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), info)
    }

    @Suppress("DEPRECATION")
    private fun appWindow(pkg: String): AccessibilityWindowInfo = AccessibilityWindowInfo.obtain().also { window ->
        shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(window).setRoot(AccessibilityNodeInfo.obtain().apply { packageName = pkg })
    }

    private fun settle() = repeat(30) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        Thread.sleep(20)
    }

    private fun normalize(service: SwitcherService, text: String) = Regex("pservice_\\d+\\.sh").replace(
        text.replace(service.cacheDir.absolutePath, "<cache>").replace(service.applicationInfo.sourceDir, "<apk>"), "pservice_<n>.sh")
        .replace("'$bottomId'", "'<bottom>'")

    @Test
    fun `last app keeps its launches, fallbacks and messages`() {
        val out = StringBuilder()
        val apps = listOf("com.example.oldest", "com.example.previous", "com.example.front")
        scenario("no history", out, listOf("com.example.front"))
        scenario("without root", out, apps, root = false)
        scenario("launched", out, apps)
        scenario("previous moved to the other screen", out, apps) { if (it == "com.example.previous") "ERR other display" else "OK launched" }
        scenario("every candidate moved", out, apps) { "ERR other display" }
        scenario("task gone", out, apps) { "ERR task unavailable" }
        scenario("launch refused", out, apps) { "ERR something else" }
        scenario("controller on the bottom screen", out, apps, bottom = true)
        val file = File("src/test/resources/commands/lastapp.txt")
        if (System.getenv("UPDATE_COMMAND_FIXTURES") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(out.toString())
            return
        }
        if (!file.exists()) fail("Fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("last app effects differ from the frozen fixture", file.readText(), out.toString())
    }
}
