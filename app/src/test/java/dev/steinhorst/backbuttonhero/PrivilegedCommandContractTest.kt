package dev.steinhorst.backbuttonhero

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.support.FakePService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Freezes the exact root commands that reachable call sites send to AYN's daemon, including the
 * content of generated scripts. Recorded once on the baseline production code into
 * `src/test/resources/commands/privileged.txt`; UPDATE_COMMAND_FIXTURES=1 re-records, only for an
 * explicitly approved command change. Volatile parts (cache paths, script numbers, APK path) are
 * replaced one-to-one by named symbols.
 */
@RunWith(RobolectricTestRunner::class)
class PrivilegedCommandContractTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val fake = FakePService().install()

    @After
    fun uninstall() = fake.uninstall()

    private fun awaitCommands(count: Int) {
        val end = System.currentTimeMillis() + 5000
        while (fake.commands.size < count) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("expected $count commands, got ${fake.commands}")
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun normalize(text: String): String {
        var out = text
        out = out.replace(context.cacheDir.absolutePath, "<cache>")
        out = out.replace(context.applicationInfo.sourceDir, "<apk>")
        out = Regex("pservice_\\d+\\.sh").replace(out, "pservice_<n>.sh")
        return out
    }

    private fun section(label: String, block: () -> Int): String {
        val before = fake.commands.size
        val expected = block()
        awaitCommands(before + expected)
        return "## $label\n" + fake.commands.drop(before).joinToString("") { normalize(it) + "\n" }
    }

    @Test
    fun `root commands of the reachable call sites are unchanged`() {
        val out = StringBuilder()
        out.append(section("force-stop a valid package") { PService.forceStop("com.example.game"); 1 })
        out.append(section("force-stop refuses an invalid name") { PService.forceStop("bad name; rm -rf /"); 0 })
        out.append(section("entry point with quoted arguments") {
            PService.runEntryPoint(context, "TaskRemover", "com.example.a,com.example.b", "it's"); 1
        })
        val focus = ControllerFocus(context, {}, {})
        for (target in listOf(Prefs.DISPLAY_TOP, Prefs.DISPLAY_BOTTOM)) {
            out.append(section("controller focus $target") { focus.run(target); 1 })
        }
        val levels = LevelControl(context, {}, { 4 })
        out.append(section("brightness up, both screens") { levels.run(Prefs.SHOULDER_BRIGHTNESS_UP, Prefs.TARGET_BOTH); 1 })
        verify(out.toString())
    }

    private fun verify(actual: String) {
        val file = File("src/test/resources/commands/privileged.txt")
        if (System.getenv("UPDATE_COMMAND_FIXTURES") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(actual)
            return
        }
        if (!file.exists()) fail("Command fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("root commands differ from the frozen fixture", file.readText(), actual)
    }
}
