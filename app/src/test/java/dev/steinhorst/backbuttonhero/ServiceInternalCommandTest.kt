package dev.steinhorst.backbuttonhero

import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.steinhorst.backbuttonhero.actions.LastAppAction
import dev.steinhorst.backbuttonhero.core.AccessibilityAppSource
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.PService
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.input.BackGestureButton
import dev.steinhorst.backbuttonhero.input.ComboButton
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
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Freezes the root commands of the service's call sites outside stored actions: native Back, swipe-kill,
 * Recents task routing, hold-to-swap, last app, screenshot and top/bottom Recents. The AYN panel close
 * only fires with a covering panel window, which Robolectric cannot provide; it stays device/manual. Each site is driven directly
 * on a connected service with the daemon faked. Recorded once on baseline production into
 * `src/test/resources/commands/service.txt`; UPDATE_COMMAND_FIXTURES=1 re-records, only for an
 * explicitly approved command change. Consecutive identical commands (polling) are recorded once.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceInternalCommandTest {

    private fun run(
        label: String,
        out: StringBuilder,
        front: Map<Boolean, String> = emptyMap(),
        state: (Int) -> String = { "OK" },
        block: (SwitcherService) -> Unit,
    ) {
        var bottomId = -1
        // The daemon answers by entry point; a script is read while the transaction runs, before PService deletes it.
        val fake = FakePService { command ->
            val script = Regex("^sh '([^']+)'$").find(command)?.let { File(it.groupValues[1]).takeIf(File::exists)?.readText() }.orEmpty()
            when {
                "RecentsStateReader" in script -> state(bottomId)
                "RecentsTaskLauncher" in script -> "OK launched"
                else -> "OK"
            }
        }.install()
        bottomId = ShadowDisplayManager.addDisplay("w400dp-h300dp")
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        try {
            for ((top, pkg) in front) shadowOf(service).setWindowsOnDisplay(if (top) 0 else bottomId, listOf(appWindow(pkg)))
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            settle()
            val startup = fake.commands.size
            val source = object : AppSource {
                override fun appsOnDisplay(displayId: Int) = emptyList<RunningApp>()
                override fun forget(packageName: String) = Unit
            }
            ReflectionHelpers.setField(service, "overlay", SwitcherOverlay(service, source, { true }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}))
            ReflectionHelpers.setField(service, "focusIndicator", FocusIndicatorOverlay(service))
            block(service)
            settle()
            out.append("## ").append(label).append('\n')
            fake.commands.drop(startup).map { normalize(service, it, bottomId) }
                .filterNot { it.contains(HELPER_START) }
                .fold(mutableListOf<String>()) { acc, c -> if (acc.lastOrNull() != c) acc += c; acc }
                .forEach { out.append(it).append('\n') }
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

    private fun settle() = repeat(40) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        Thread.sleep(20)
    }

    private fun normalize(service: SwitcherService, text: String, bottomId: Int) = Regex("pservice_\\d+\\.sh").replace(
        text.replace(service.cacheDir.absolutePath, "<cache>").replace(service.applicationInfo.sourceDir, "<apk>"), "pservice_<n>.sh")
        .replace("'$bottomId'", "'<bottom>'").replace("-d $bottomId ", "-d <bottom> ")

    private val hold = ClassParameter.from(ComboButton::class.java, BackGestureButton.HOLD)

    private fun call(service: SwitcherService, name: String, vararg args: ClassParameter<*>) {
        ReflectionHelpers.callInstanceMethod<Any?>(service, name, *args)
    }

    @Test
    fun `service call sites outside actions send unchanged root commands`() {
        val out = StringBuilder()
        run("native back", out) { call(it, "nativeBack", ClassParameter.from(Boolean::class.javaPrimitiveType, false)) }
        run("native back, long press", out) { call(it, "nativeBack", ClassParameter.from(Boolean::class.javaPrimitiveType, true)) }
        run("swipe-kill two apps", out) {
            call(it, "killOnly", ClassParameter.from(List::class.java, listOf("com.example.a", "com.example.b")),
                ClassParameter.from(Set::class.java, setOf(0)), ClassParameter.from(Boolean::class.javaPrimitiveType, false),
                ClassParameter.from(Function0::class.java, null))
        }
        run("swipe-kill refuses an invalid name", out) {
            call(it, "killOnly", ClassParameter.from(List::class.java, listOf("bad name; reboot")),
                ClassParameter.from(Set::class.java, setOf(0)), ClassParameter.from(Boolean::class.javaPrimitiveType, false),
                ClassParameter.from(Function0::class.java, null))
        }
        run("launch a known task on the top screen", out) {
            call(it, "launchTask", ClassParameter.from(String::class.java, "com.example.a"), ClassParameter.from(Int::class.javaPrimitiveType, 0),
                ClassParameter.from(Int::class.javaObjectType, 17), ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        }
        run("launch a package without task id", out) {
            call(it, "launchTask", ClassParameter.from(String::class.java, "com.example.a"), ClassParameter.from(Int::class.javaPrimitiveType, 0),
                ClassParameter.from(Int::class.javaObjectType, null), ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        }
        run("hold-to-swap without apps", out) { call(it, "swapScreensNow", hold) }
        val both = mapOf(true to "com.example.top", false to "com.example.bottom")
        val tasks: (Int) -> String = { bottom ->
            "OK [{\"package\":\"com.example.top\",\"display\":0,\"locked\":false,\"task\":11}," +
                "{\"package\":\"com.example.bottom\",\"display\":$bottom,\"locked\":false,\"task\":22}]"
        }
        run("hold-to-swap moves both known tasks", out, both, tasks) { call(it, "swapScreensNow", hold) }
        run("hold-to-swap with an unmovable task", out, both, { bottom ->
            "OK [{\"package\":\"com.example.top\",\"display\":0,\"locked\":false,\"task\":11,\"movable\":false}," +
                "{\"package\":\"com.example.bottom\",\"display\":$bottom,\"locked\":false,\"task\":22}]"
        }) { call(it, "swapScreensNow", hold) }
        run("switch to last app", out) { service ->
            val source = ReflectionHelpers.getField<AccessibilityAppSource>(service, "source")
            for (pkg in listOf("com.example.previous", "com.example.top")) {
                shadowOf(service).setWindowsOnDisplay(0, listOf(appWindow(pkg)))
                source.record(0)
            }
            ReflectionHelpers.callInstanceMethod<LastAppAction>(service, "getLastApp").run()
        }
        run("system screenshot top", out) {
            call(it, "nativeSystemScreenshot", ClassParameter.from(Int::class.javaPrimitiveType, 0), ClassParameter.from(Function0::class.java, {}))
        }
        run("open top Recents", out) { call(it, "openTopRecentsNow") }
        run("prime top Recents focus", out) {
            ReflectionHelpers.callInstanceMethod<Any?>(ReflectionHelpers.callInstanceMethod<Any>(it, "getTopSession"), "primeFocus")
        }
        run("close top Recents", out) { ReflectionHelpers.callInstanceMethod<Any?>(ReflectionHelpers.callInstanceMethod<Any>(it, "getTopSession"), "close") }
        run("refresh bottom task states", out) { call(it, "refreshBottomTaskStates") }
        run("show bottom Recents", out) { call(it, "showSwitcherNow") }
        verify(out.toString())
    }

    private fun verify(actual: String) {
        val file = File("src/test/resources/commands/service.txt")
        if (System.getenv("UPDATE_COMMAND_FIXTURES") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(actual)
            return
        }
        if (!file.exists()) fail("Command fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("root commands differ from the frozen fixture", file.readText(), actual)
    }

    private companion object {
        const val HELPER_START = "padhelper_start.sh"
    }
}
