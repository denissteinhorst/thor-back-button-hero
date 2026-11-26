package dev.steinhorst.backbuttonhero

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.capture.ScreenRecording
import dev.steinhorst.backbuttonhero.support.FakePService
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Freezes the launch script of the screen recorder, a root process reached over a binder rather than a
 * single command. Only the launch is frozen; the recorder never registers in the test. The panel window
 * host is launched the same way but only after a LocalServerSocket exists, which Robolectric cannot
 * create, so its launch stays device-only (panels suite). Recorded once on
 * baseline production into `src/test/resources/commands/launch.txt`; UPDATE_COMMAND_FIXTURES=1
 * re-records, only for an explicitly approved command change.
 */
@RunWith(RobolectricTestRunner::class)
class SocketLaunchCommandTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val fake = FakePService().install()

    @After
    fun uninstall() = fake.uninstall()

    private fun awaitQuiet(minimum: Int) {
        val end = System.currentTimeMillis() + 10_000
        var last = -1
        var stableSince = System.currentTimeMillis()
        while (fake.commands.size < minimum || System.currentTimeMillis() - stableSince < 500) {
            shadowOf(Looper.getMainLooper()).idle()
            val now = fake.commands.size
            if (now != last) { last = now; stableSince = System.currentTimeMillis() }
            if (System.currentTimeMillis() > end) fail("expected at least $minimum commands, got ${fake.commands}")
            Thread.sleep(10)
        }
    }

    private fun section(label: String, minimum: Int, block: () -> Unit): String {
        val before = fake.commands.size
        block()
        awaitQuiet(before + minimum)
        return "## $label\n" + fake.commands.drop(before).joinToString("") { normalize(it) + "\n" }
    }

    private fun normalize(text: String) = text
        .replace(context.cacheDir.absolutePath, "<cache>")
        .replace(context.applicationInfo.sourceDir, "<apk>")

    @Test
    fun `screen recorder launch script is unchanged`() {
        val out = StringBuilder()
        val recording = ScreenRecording(context) {}
        out.append(section("screen recording, bottom target without a bottom screen", 0) {
            recording.toggle { ScreenRecording.Request(ScreenRecorder.MODE_BOTTOM, 0, null, 8_000_000, false) }
        })
        out.append(section("screen recording, top screen", 1) {
            recording.toggle { ScreenRecording.Request(ScreenRecorder.MODE_TOP, 0, null, 8_000_000, true) }
        })
        val file = File("src/test/resources/commands/launch.txt")
        if (System.getenv("UPDATE_COMMAND_FIXTURES") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(out.toString())
            return
        }
        if (!file.exists()) fail("Command fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("root commands differ from the frozen fixture", file.readText(), out.toString())
    }
}
