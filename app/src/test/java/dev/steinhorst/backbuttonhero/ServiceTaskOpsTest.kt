package dev.steinhorst.backbuttonhero

import android.app.ActivityManager
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Task operations beside the success paths service.txt already freezes: closing without root, a
 * failed root removal, closing our own settings, the memory report, a launch the task launcher
 * refuses (relaunch where allowed, else a message), and the Home a closed front app leaves behind.
 * Frozen in `src/test/resources/commands/taskops.txt`, recorded once on the pre-R1 baseline;
 * UPDATE_COMMAND_FIXTURES=1 re-records, only for an approved change.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceTaskOpsTest {
    private var bottomId = -1

    private fun scenario(label: String, out: StringBuilder, root: Boolean = true, answer: (String) -> String = { "OK" },
        front: String? = null, block: (SwitcherService) -> Unit) {
        val fake = FakePService { command ->
            val script = Regex("^sh '([^']+)'$").find(command)?.let { File(it.groupValues[1]).takeIf(File::exists)?.readText() }.orEmpty()
            answer(script.ifEmpty { command })
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
            for (pkg in listOf("com.example.a", "com.example.b")) launchable(service, pkg)
            if (front != null) {
                shadowOf(service).setWindowsOnDisplay(0, listOf(appWindow(front)))
                ReflectionHelpers.getField<AccessibilityAppSource>(service, "source").record(0)
            }
            val startup = fake.commands.size
            val shadow = shadowOf(service as ContextWrapper)
            while (shadow.nextStartedActivityForResult != null) Unit
            ShadowLog.clear()
            val activityShadow = shadowOf(service.getSystemService(ActivityManager::class.java))
            ReflectionHelpers.setField(activityShadow, "backgroundPackage", null)
            out.append("## ").append(label).append('\n')
            block(service)
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
            ShadowLog.getLogsForTag("BackButtonHero").filter { it.msg.startsWith("Notice: ") }
                .forEach { out.append(it.msg.replace(Regex("[0-9.,]+ ?[KMGT]?B"), "<size>")).append('\n') }
            activityShadow.backgroundPackage?.let { out.append("killBackgroundProcesses ").append(it).append('\n') }
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

    private fun settle() = repeat(40) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        Thread.sleep(20)
    }

    private fun normalize(service: SwitcherService, text: String) = Regex("pservice_\\d+\\.sh").replace(
        text.replace(service.cacheDir.absolutePath, "<cache>").replace(service.applicationInfo.sourceDir, "<apk>"), "pservice_<n>.sh")
        .replace("'$bottomId'", "'<bottom>'")

    private fun kill(service: SwitcherService, packages: List<String>, report: Boolean, afterwards: (() -> Unit)? = null) =
        ReflectionHelpers.callInstanceMethod<Any?>(service, "killOnly", ClassParameter.from(List::class.java, packages),
            ClassParameter.from(Set::class.java, setOf(0)), ClassParameter.from(Boolean::class.javaPrimitiveType, report),
            ClassParameter.from(Function0::class.java, afterwards))

    private fun launch(service: SwitcherService, pkg: String, relaunch: Boolean = true) =
        ReflectionHelpers.callInstanceMethod<Boolean>(service, "launchTask", ClassParameter.from(String::class.java, pkg),
            ClassParameter.from(Int::class.javaPrimitiveType, 0), ClassParameter.from(Int::class.javaObjectType, 17),
            ClassParameter.from(Boolean::class.javaPrimitiveType, relaunch))

    @Test
    fun `task operations keep their fallbacks and messages`() {
        val out = StringBuilder()
        scenario("close without root", out, root = false) { kill(it, listOf("com.example.a"), report = true) { out.append("afterwards ran\n") } }
        scenario("close, root removal refused", out, answer = { if ("TaskRemover" in it) "ERR nope" else "OK" }) {
            kill(it, listOf("com.example.a"), report = false)
        }
        scenario("close, force-stop refused", out, answer = { if (it.startsWith("am force-stop")) "ERR" else "OK" }) {
            kill(it, listOf("com.example.a"), report = false)
        }
        scenario("close the app in front", out, front = "com.example.a") { kill(it, listOf("com.example.a", "com.example.b"), report = true) }
        scenario("close our own settings and another app", out) {
            kill(it, listOf(it.packageName, "com.example.a"), report = false) { out.append("afterwards ran\n") }
        }
        scenario("launch refused, relaunch allowed", out, answer = { if ("RecentsTaskLauncher" in it) "ERR task unavailable" else "OK" }) {
            out.append("result ").append(launch(it, "com.example.a")).append('\n')
        }
        scenario("launch refused, relaunch not allowed", out, answer = { if ("RecentsTaskLauncher" in it) "ERR task unavailable" else "OK" }) {
            out.append("result ").append(launch(it, "com.example.a", relaunch = false)).append('\n')
        }
        scenario("launch refused for another display", out, answer = { if ("RecentsTaskLauncher" in it) "ERR other display" else "OK" }) {
            out.append("result ").append(launch(it, "com.example.a")).append('\n')
        }
        scenario("launch without root", out, root = false) { out.append("result ").append(launch(it, "com.example.a")).append('\n') }
        val file = File("src/test/resources/commands/taskops.txt")
        if (System.getenv("UPDATE_COMMAND_FIXTURES") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(out.toString())
            return
        }
        if (!file.exists()) fail("Fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("task operations differ from the frozen fixture", file.readText(), out.toString())
    }
}
