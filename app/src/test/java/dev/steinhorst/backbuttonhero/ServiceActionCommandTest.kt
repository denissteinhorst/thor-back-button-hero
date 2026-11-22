package dev.steinhorst.backbuttonhero

import android.os.Looper
import dev.steinhorst.backbuttonhero.support.FakePService
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration

/**
 * Runs every stored action through the connected service's Back single tap and freezes the root
 * commands it sends, including "none". Recorded once on the baseline production code into
 * `src/test/resources/commands/actions.txt`; UPDATE_COMMAND_FIXTURES=1 re-records, only for an
 * explicitly approved command change. Polling loops repeat a command a timing-dependent number of
 * times, so consecutive identical commands are recorded once; the root helper's background launch is
 * recorded once, separately.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceActionCommandTest {

    private fun run(action: String): List<String> {
        val fake = FakePService().install()
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            settle()
            val startup = fake.commands.size
            val source = object : AppSource {
                override fun appsOnDisplay(displayId: Int) = emptyList<RunningApp>()
                override fun forget(packageName: String) = Unit
            }
            ReflectionHelpers.setField(service, "overlay", SwitcherOverlay(service, source, { true }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}))
            ReflectionHelpers.setField(service, "focusIndicator", FocusIndicatorOverlay(service))
            val prefs = Prefs(service)
            val before = prefs.snapshot()
            val single = BackGestureButton.SINGLE.prefKey
            prefs.applySettings(before, before.copy(bindings = before.bindings + (single to before.bindings.getValue(single).copy(action = action))))
            service.onSingleTap()
            settle()
            val all = fake.commands.map { normalize(service, it) }
            helperLaunches += all.filter { it.contains(HELPER_START) }
            return all.drop(startup).filterNot { it.contains(HELPER_START) }
                .fold(mutableListOf()) { acc, c -> if (acc.lastOrNull() != c) acc += c; acc }
        } finally {
            runCatching { service.onDestroy() }
            fake.uninstall()
        }
    }

    /** The root helper is (re)launched in the background at timing-dependent moments, independent of the action. */
    private val helperLaunches = sortedSetOf<String>()

    private fun settle() = repeat(30) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        Thread.sleep(20)
    }

    private fun normalize(service: SwitcherService, text: String) = Regex("pservice_\\d+\\.sh").replace(
        text.replace(service.cacheDir.absolutePath, "<cache>").replace(Regex("'[^']*/libpadhelper\\.so'"), "'<lib>/libpadhelper.so'").replace(service.applicationInfo.sourceDir, "<apk>"), "pservice_<n>.sh")

    @Test
    fun `every stored action sends unchanged root commands`() {
        val out = StringBuilder()
        for (action in Prefs.KNOWN_ACTIONS.sorted()) {
            out.append("## ").append(action).append('\n')
            run(action).forEach { out.append(it).append('\n') }
        }
        out.append("## root helper launch, whenever it happens\n")
        helperLaunches.forEach { out.append(it).append('\n') }
        val file = File("src/test/resources/commands/actions.txt")
        if (System.getenv("UPDATE_COMMAND_FIXTURES") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(out.toString())
            return
        }
        if (!file.exists()) fail("Command fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("root commands differ from the frozen fixture", file.readText(), out.toString())
    }

    private companion object {
        const val HELPER_START = "padhelper_start.sh"
    }
}
