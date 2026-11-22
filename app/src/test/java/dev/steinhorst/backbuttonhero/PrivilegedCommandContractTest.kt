package dev.steinhorst.backbuttonhero

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.actions.AutoControllerFocus
import dev.steinhorst.backbuttonhero.actions.Ayn
import dev.steinhorst.backbuttonhero.actions.AynControls
import dev.steinhorst.backbuttonhero.actions.ControllerFocus
import dev.steinhorst.backbuttonhero.core.PService
import dev.steinhorst.backbuttonhero.pad.LevelControl
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.recents.FrontendParking
import dev.steinhorst.backbuttonhero.support.FakePService
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager

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

    /** Waits until no further command arrives for a while: for call sites whose count depends on state. */
    private fun awaitQuiet() {
        var last = -1
        var stableSince = System.currentTimeMillis()
        val end = stableSince + 5000
        while (System.currentTimeMillis() - stableSince < 300) {
            shadowOf(Looper.getMainLooper()).idle()
            val now = fake.commands.size
            if (now != last) { last = now; stableSince = System.currentTimeMillis() }
            if (System.currentTimeMillis() > end) fail("commands kept arriving: ${fake.commands}")
            Thread.sleep(10)
        }
    }

    private fun quiet(label: String, block: () -> Unit): String {
        val before = fake.commands.size
        block()
        awaitQuiet()
        return "## $label\n" + fake.commands.drop(before).joinToString("") { normalize(it) + "\n" }
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

    @Test
    fun `volume, mute and AYN controls send unchanged root commands`() {
        val out = StringBuilder()
        android.provider.Settings.System.putInt(context.contentResolver, "secondary_screen_volume_level", 5)
        val levels = LevelControl(context, {}, { 4 })
        out.append(quiet("bottom volume up") { levels.run(Prefs.SHOULDER_VOLUME_UP, Prefs.DISPLAY_BOTTOM) })
        out.append(quiet("bottom volume down") { levels.run(Prefs.SHOULDER_VOLUME_DOWN, Prefs.DISPLAY_BOTTOM) })
        out.append(quiet("bottom mute") { levels.toggleMute(Prefs.DISPLAY_BOTTOM) })
        val ayn = AynControls(context) {}
        for (group in Ayn.Group.values()) {
            for (option in group.options) {
                out.append(quiet("${group.name} ${option.key}") { ayn.apply(group, option) })
            }
        }
        for (toggle in Ayn.Toggle.values()) {
            out.append(quiet("toggle ${toggle.name}") { ayn.toggle(toggle) })
        }
        verify(out.toString(), "ayn.txt")
    }

    @Test
    fun `frontend parking and auto-lock focus send unchanged root commands`() {
        fake.uninstall()
        val parkingFake = FakePService { "OK task=42" }.install()
        val before = parkingFake.commands.size
        val bottomId = ShadowDisplayManager.addDisplay("w400dp-h300dp")
        val bottom = context.getSystemService(DisplayManager::class.java).getDisplay(bottomId)
        val out = StringBuilder()
        fun take(label: String, block: () -> Unit) {
            val start = parkingFake.commands.size
            block()
            val end = System.currentTimeMillis() + 5000
            var last = -1
            var stableSince = System.currentTimeMillis()
            while (System.currentTimeMillis() - stableSince < 300) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
                markParkingDisplaysPrivate()
                val now = parkingFake.commands.size
                if (now != last) { last = now; stableSince = System.currentTimeMillis() }
                if (System.currentTimeMillis() > end) fail("commands kept arriving: ${parkingFake.commands}")
                Thread.sleep(10)
            }
            out.append("## ").append(label).append('\n')
            parkingFake.commands.drop(start).forEach { out.append(normalizeParking(it, bottomId)).append('\n') }
        }
        val parking = FrontendParking(context)
        take("park the frontend shown on both screens") { parking.prepareLaunch(bottom, "com.example.frontend", "com.example.frontend") {} }
        take("verify drops a task on another display") { parking.verify() }
        take("park again") { parking.prepareLaunch(bottom, "com.example.frontend", "com.example.frontend") {} }
        take("restore to the top screen") { parking.restore(android.view.Display.DEFAULT_DISPLAY) {} }
        take("park before closing") { parking.prepareLaunch(bottom, "com.example.frontend", "com.example.frontend") {} }
        take("close restores to the bottom screen") { parking.close() }
        take("auto-lock focus touch on the bottom screen") { AutoControllerFocus(context).run(bottomId) {} }
        check(parkingFake.commands.size > before)
        verify(out.toString(), "parking.txt")
    }

    /** Robolectric drops the flags of a virtual display; the real one created without PUBLIC is private. */
    private fun markParkingDisplaysPrivate() = context.getSystemService(DisplayManager::class.java).displays
        .filter { it.name == FrontendParking.PARKING_DISPLAY_NAME }
        .forEach { shadowOf(it).setFlags(it.flags or android.view.Display.FLAG_PRIVATE) }

    /** The parking display is created per park, so its id is substituted, and only in that position. */
    private fun normalizeParking(text: String, bottomId: Int): String = normalize(text)
        .replace(Regex("TaskParker 'park' '([^']+)' '$bottomId' '(\\d+)'")) { m ->
            check(m.groupValues[2].toInt() > bottomId) { "park target ${m.groupValues[2]} is not a newly created display" }
            "TaskParker 'park' '${m.groupValues[1]}' '$bottomId' '<parking>'"
        }
        .replace(Regex("'BBHAutoFocus-[0-9a-f-]{36}'"), "'BBHAutoFocus-<uuid>'")
        .replace("'${android.os.Process.myUid()}'", "'<uid>'")
        .replace("'$bottomId'", "'<bottom>'")

    private fun verify(actual: String, name: String = "privileged.txt") {
        val file = File("src/test/resources/commands/$name")
        if (System.getenv("UPDATE_COMMAND_FIXTURES") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(actual)
            return
        }
        if (!file.exists()) fail("Command fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("root commands differ from the frozen fixture", file.readText(), actual)
    }
}
