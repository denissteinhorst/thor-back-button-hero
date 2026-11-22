package dev.steinhorst.backbuttonhero.pad

import android.net.LocalSocket
import android.os.Looper
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.input.DpadDirection
import dev.steinhorst.backbuttonhero.input.Stick
import dev.steinhorst.backbuttonhero.input.StickButton
import dev.steinhorst.backbuttonhero.macro.MacroEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Freezes the line protocol between the service and the root pad helper (docs/HELPER.md) at the
 * connected-client seam: every Kotlin->C command, the state replay when the helper takes a pad over,
 * and what every C->Kotlin event, malformed ones included, reaches. Recorded once on baseline
 * production into `src/test/resources/commands/padhelper.txt`; UPDATE_COMMAND_FIXTURES=1 re-records,
 * only for an explicitly approved protocol change. `>` is a helper event, `->` what it reached, `<-` the
 * commands written to the helper during that step, in order.
 *
 * Not reachable on the JVM (Robolectric cannot bind a LocalServerSocket): the accept loop's uid-0 peer
 * check, line framing from the socket reader, and the relaunch after a lost helper. Those stay on the
 * device suite until a production seam exists (due-before R3.1/R11.1).
 */
@RunWith(RobolectricTestRunner::class)
class PadHelperProtocolTest {

    private val trace = StringBuilder()
    private val wire = ByteArrayOutputStream()

    private val helper = PadHelper(
        RuntimeEnvironment.getApplication(),
        onPress = { trace.append("  -> press ").append(it).append('\n') },
        onRelease = { trace.append("  -> release horizontal=").append(it).append('\n') },
        onActiveChanged = { trace.append("  -> active ").append(it).append('\n') },
        onRecord = { trace.append("  -> record ").append(it).append('\n') },
        onMacroEnd = { id, stopped -> trace.append("  -> macro ").append(id).append(" stopped=").append(stopped).append('\n') },
        onStick = { stick, direction -> trace.append("  -> stick ").append(stick).append(' ').append(direction).append('\n') },
    )

    private fun connect() {
        val socket = object : LocalSocket() {
            override fun getOutputStream(): OutputStream = wire
        }
        ReflectionHelpers.setField(helper, "client", socket)
        ReflectionHelpers.setField(helper, "wanted", true)
    }

    private fun step(label: String, block: () -> Unit) {
        trace.append("## ").append(label).append('\n')
        wire.reset()
        block()
        shadowOf(Looper.getMainLooper()).idle()
        wire.toString(Charsets.UTF_8.name()).lineSequence().filter { it.isNotEmpty() }.forEach { trace.append("  <- ").append(it).append('\n') }
    }

    /** One helper event; idling right away keeps each reaction directly under the line that caused it. */
    private fun line(text: String) {
        trace.append("  > ").append(text).append('\n')
        ReflectionHelpers.callInstanceMethod<Unit>(helper, "handle", ClassParameter.from(String::class.java, text))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `pad helper line protocol is unchanged`() {
        step("commands without a helper are dropped") {
            helper.setPassthrough(setOf(DpadDirection.UP))
            helper.navigateTop(DpadDirection.DOWN)
            trace.append("  playMacro=").append(helper.playMacro(1, listOf(MacroEvent(0, true, 304, 1)))).append('\n')
        }
        connect()
        step("switcher navigation and suspension") {
            PadNavigationMode.values().forEach(helper::setSwitcherNavigation)
            helper.setSuspended(true)
            helper.setSwitcherNavigation(PadNavigationMode.CAPTURE)
            helper.setSuspended(false)
        }
        step("masks, changes only") {
            helper.setPassthrough(setOf(DpadDirection.UP, DpadDirection.RIGHT))
            helper.setPassthrough(setOf(DpadDirection.RIGHT, DpadDirection.UP))
            helper.setSticks(setOf(Stick.LEFT, Stick.RIGHT))
            helper.setStickPassthrough(setOf(StickButton.RIGHT_LEFT, StickButton.LEFT_DOWN))
            helper.setHomeMode(PadHelper.HOME_DUAL)
            helper.setHomeMode(PadHelper.HOME_DUAL)
            helper.setRecording(true)
        }
        step("helper takes a pad over: state replay") { line("PAD 1") }
        step("navigation, gyro and macros") {
            DpadDirection.values().forEach(helper::navigateTop)
            helper.setGyro(Stick.LEFT, 120, -340)
            helper.setGyro(Stick.RIGHT, 0, 5)
            helper.setGyro(null, 0, 0)
            trace.append("  playMacro=").append(helper.playMacro(7, listOf(MacroEvent(0, true, 304, 1), MacroEvent(120, false, 0, -32767), MacroEvent(250, true, 304, 0)))).append('\n')
            helper.stopMacro()
        }
        step("events from the helper") {
            listOf(
                "HAT X -1", "HAT X 1", "HAT X 0", "HAT Y -1", "HAT Y 0.4", "HAT Y 1", "HAT Y", "HAT Y x",
                "STICK L U", "STICK R N", "STICK Q U", "STICK L",
                "REC K 304 1", "MACRO_DONE 7", "MACRO_STOPPED 8", "MACRO_DONE x", "MACRO_DONE",
                "MODE 2", "UNKNOWN 1", "", " ",
            ).forEach(::line)
        }
        step("recording off drops REC") {
            helper.setRecording(false)
            line("REC K 304 1")
        }
        step("pad gone, then back") { line("PAD_GONE"); line("PAD 2") }
        step("emergency release") { line("RELEASED"); line("HAT X 1"); line("PAD 3") }
        val file = File("src/test/resources/commands/padhelper.txt")
        if (System.getenv("UPDATE_COMMAND_FIXTURES") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(trace.toString())
            return
        }
        if (!file.exists()) fail("Protocol fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("pad helper protocol differs from the frozen fixture", file.readText(), trace.toString())
    }
}
