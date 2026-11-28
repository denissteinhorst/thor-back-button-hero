package dev.steinhorst.backbuttonhero

import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.steinhorst.backbuttonhero.core.AccessibilityAppSource
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.input.BackGestureButton
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.overlay.FocusIndicatorOverlay
import dev.steinhorst.backbuttonhero.prefs.AppException
import dev.steinhorst.backbuttonhero.prefs.ExceptionControl
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.recents.SwitcherOverlay
import dev.steinhorst.backbuttonhero.support.FakePService
import java.io.File
import java.time.Duration
import org.json.JSONArray
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
 * Hold-to-swap and the "switch active app" action beyond the success paths service.txt freezes:
 * Hold exceptions on one or both partners, an unreadable task list, IISU's own swap (pressed, or switched
 * off), Cocoon's own swap, apps that take their swapped tasks back and are refused afterwards, and the active-app switch for
 * every controller route (fixed top/bottom, Auto with a known or unknown focused display), with
 * and without focus following, a refused move, a second press while one runs, and no root.
 * Frozen in `src/test/resources/commands/swap.txt`, recorded once on the pre-R1 baseline;
 * UPDATE_COMMAND_FIXTURES=1 re-records, only for an approved change. The panel refusal needs a
 * captured third-party panel and is checked on the device (area frontends).
 */
@RunWith(RobolectricTestRunner::class)
class ServiceSwapTest {
    private var bottomId = -1

    private fun scenario(
        label: String,
        out: StringBuilder,
        root: Boolean = true,
        route: String? = null,
        top: String? = null,
        bottom: String? = null,
        exceptions: List<String> = emptyList(),
        tasks: (Int) -> String? = { "OK []" },
        launch: String = "OK launched",
        focused: String = "  FocusedDisplayId: 0\n",
        iisuButton: String = "",
        block: (SwitcherService) -> Unit,
    ) {
        // Like Android, later task reads show a task the launcher moved on its new screen.
        val moved = mutableMapOf<Int, Int>()
        val fake = FakePService { command ->
            val script = Regex("^sh '([^']+)'$").find(command)?.let { File(it.groupValues[1]).takeIf(File::exists)?.readText() }.orEmpty()
            when {
                "RecentsStateReader" in script -> tasks(bottomId)?.let { movedTasks(it, moved) } ?: ""
                "RecentsTaskLauncher" in script -> launch.also {
                    val move = Regex("RecentsTaskLauncher '[^']+' '(\\d+)' '(\\d+)'").find(script)
                    if (it.startsWith("OK ") && move != null) moved[move.groupValues[2].toInt()] = move.groupValues[1].toInt()
                }
                command.startsWith("dumpsys input") -> focused
                "screen_swap_shortcut_button" in command -> iisuButton
                else -> "OK"
            }
        }
        if (root) fake.install()
        bottomId = ShadowDisplayManager.addDisplay("w400dp-h300dp")
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        try {
            if (route != null) Settings.System.putString(service.contentResolver, "screen_focus_lock", route)
            Prefs(service).appExceptions = exceptions.map {
                AppException(it, it, allowed = ExceptionControl.ALL and ExceptionControl.HOLD.bit.inv())
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
            for ((display, pkg) in listOf(0 to top, bottomId to bottom)) if (pkg != null) {
                shadowOf(service).setWindowsOnDisplay(display, listOf(appWindow(pkg)))
                source.record(display)
            }
            val startup = fake.commands.size
            ShadowLog.clear()
            out.append("## ").append(label).append('\n')
            block(service)
            settle()
            fake.commands.drop(startup).filterNot { it.contains("padhelper_start.sh") }.map(::normalize)
                .forEach { out.append("root ").append(it).append('\n') }
            ShadowLog.getLogsForTag("BackButtonHero").filter { it.msg.startsWith("Notice: ") }
                .forEach { out.append(it.msg).append('\n') }
            out.append("switching ").append(ReflectionHelpers.getField<Boolean>(ReflectionHelpers.callInstanceMethod<Any>(service, "getSwap"), "switching")).append('\n')
        } finally {
            runCatching { service.onDestroy() }
            if (root) fake.uninstall()
            ShadowDisplayManager.removeDisplay(bottomId)
        }
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

    private fun normalize(text: String) = Regex("pservice_\\d+\\.sh").replace(text, "pservice_<n>.sh")
        .replace(Regex("'/[^']*'"), "'<path>'").replace("'$bottomId'", "'<bottom>'").replace("-d $bottomId ", "-d <bottom> ")
        .replace(Regex("BBHAutoFocus-[0-9a-f-]+"), "BBHAutoFocus-<id>").replace("'${android.os.Process.myUid()}'", "'<uid>'")

    private fun movedTasks(state: String, moved: Map<Int, Int>): String {
        if (!state.startsWith("OK ") || moved.isEmpty()) return state
        val tasks = JSONArray(state.removePrefix("OK "))
        for (i in 0 until tasks.length()) tasks.getJSONObject(i).let { task ->
            moved[task.optInt("task", -1)]?.let { task.put("display", it) }
        }
        return "OK $tasks"
    }

    private fun swap(service: SwitcherService) = ReflectionHelpers.callInstanceMethod<Unit>(service, "swapScreensNow",
        ClassParameter.from(ComboButton::class.java, BackGestureButton.HOLD))

    private fun switch(service: SwitcherService, follow: Boolean = true) {
        Prefs(service).setSwitchAppFollowFocus(ShoulderButton.L1, follow)
        ReflectionHelpers.callInstanceMethod<Unit>(service, "switchActiveApp", ClassParameter.from(ComboButton::class.java, ShoulderButton.L1))
    }

    private companion object {
        const val IISU = "com.iisulauncher"
        const val COCOON = "rip.moth.cocoonshell"
    }

    private fun tasks(vararg entries: Pair<String, Boolean>): (Int) -> String = { bottom ->
        entries.mapIndexed { i, (pkg, onTop) ->
            "{\"package\":\"$pkg\",\"display\":${if (onTop) 0 else bottom},\"locked\":false,\"task\":${1011 * (i + 1)}}"
        }.joinToString(",", "OK [", "]")
    }

    @Test
    fun `swap and active app switch keep their branches`() {
        val out = StringBuilder()
        val both = tasks("com.example.top" to true, "com.example.bottom" to false)
        scenario("swap, both partners excepted", out, top = "com.example.top", bottom = "com.example.bottom",
            exceptions = listOf("com.example.top", "com.example.bottom"), tasks = both) { swap(it) }
        scenario("swap, top partner excepted", out, top = "com.example.top", bottom = "com.example.bottom",
            exceptions = listOf("com.example.top"), tasks = both) { swap(it) }
        scenario("swap, task list unreadable", out, top = "com.example.top", bottom = "com.example.bottom", tasks = { null }) { swap(it) }
        scenario("swap, launcher refuses the first move", out, top = "com.example.top", bottom = "com.example.bottom",
            tasks = both, launch = "ERR other display") { swap(it) }
        scenario("swap, IISU on both screens presses its own swap button", out, top = IISU, bottom = IISU) { swap(it) }
        scenario("swap, IISU's own swap switched off", out, top = IISU, bottom = IISU, iisuButton = "Off") { swap(it) }
        scenario("swap, Cocoon on both screens presses its own swap button", out, top = COCOON, bottom = COCOON) { swap(it) }
        var reads = 0
        scenario("swap, both apps take their tasks back and are refused afterwards", out, top = "com.example.top",
            bottom = "com.example.bottom", tasks = { if (reads++ == 0) both(it) else "OK []" }) {
            swap(it)
            settle()
            swap(it)
        }
        scenario("switch, top route, nothing on top", out, route = "1") { switch(it) }
        scenario("switch, top route, no movable task", out, route = "1", top = "com.example.top") { switch(it) }
        scenario("switch, top route, focus follows", out, route = "1", top = "com.example.top", tasks = both) { switch(it) }
        scenario("switch, top route, focus stays", out, route = "1", top = "com.example.top", tasks = both) { switch(it, follow = false) }
        scenario("switch, bottom route, focus follows", out, route = "2", bottom = "com.example.bottom", tasks = both) { switch(it) }
        scenario("switch, move refused", out, route = "1", top = "com.example.top", tasks = both, launch = "ERR nope") { switch(it) }
        scenario("switch, auto route on the top display", out, route = "0", top = "com.example.top", tasks = both) { switch(it) }
        scenario("switch, auto route stays", out, route = "0", top = "com.example.top", tasks = both) { switch(it, follow = false) }
        scenario("switch, auto route without a focused display", out, route = "0", top = "com.example.top", tasks = both,
            focused = "ERR unavailable") { switch(it) }
        scenario("switch, auto route on an unknown display", out, route = "0", top = "com.example.top", tasks = both,
            focused = "  FocusedDisplayId: 99\n") { switch(it) }
        scenario("switch, second press while running", out, route = "1", top = "com.example.top", tasks = both) {
            switch(it)
            switch(it)
        }
        scenario("switch without root", out, root = false, route = "1", top = "com.example.top") { switch(it) }
        val file = File("src/test/resources/commands/swap.txt")
        if (System.getenv("UPDATE_COMMAND_FIXTURES") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(out.toString())
            return
        }
        if (!file.exists()) fail("Fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("swap paths differ from the frozen fixture", file.readText(), out.toString())
    }
}
