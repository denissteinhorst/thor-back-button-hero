package dev.steinhorst.backbuttonhero

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.provider.Settings
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.overlay.FocusIndicatorOverlay
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.recents.ShownCard
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
 * The bottom switcher's lifecycle in the service: opening (task list first, then the overlay), a
 * second press while it opens or while it shows, the task-state poll every second while it shows
 * and never after it closes, thumbnail requests (at most three per task, a delivered one is final),
 * an unreadable task list, controller routing on open and its restore on close, a chosen card,
 * and a disconnect while opening. The overlay is wired to the service's own callbacks as in
 * production. Time advances only in explicit steps, so the poll count is exact.
 * Frozen in `src/test/resources/commands/bottomswitcher.txt`, recorded once on the pre-R1
 * baseline; UPDATE_COMMAND_FIXTURES=1 re-records, only for an approved change.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceBottomSwitcherTest {
    private var bottomId = -1
    private lateinit var fake: FakePService
    private lateinit var out: StringBuilder
    private var seen = 0

    private val apps = listOf("com.example.a", "com.example.b").map {
        RunningApp(it, it, ColorDrawable(0), null)
    }

    private fun scenario(
        label: String,
        controller: Boolean = false,
        tasks: (Int) -> String? = { bottom ->
            "OK [{\"package\":\"com.example.a\",\"display\":$bottom,\"locked\":false,\"task\":1011,\"active\":5}," +
                "{\"package\":\"com.example.b\",\"display\":$bottom,\"locked\":false,\"task\":2022,\"active\":6}]"
        },
        block: (SwitcherService) -> Unit,
    ) {
        fake = FakePService { command ->
            val script = Regex("^sh '([^']+)'$").find(command)?.let { File(it.groupValues[1]).takeIf(File::exists)?.readText() }.orEmpty()
            when {
                "'snapshot'" in script -> snapshot(script)
                "RecentsStateReader" in script -> tasks(bottomId) ?: ""
                else -> "OK"
            }
        }.install()
        bottomId = ShadowDisplayManager.addDisplay("w400dp-h300dp")
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        try {
            Settings.System.putString(service.contentResolver, "screen_focus_lock", "1")
            Prefs(service).switcherControllerEnabled = controller
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            drain()
            val source = object : AppSource {
                override fun appsOnDisplay(displayId: Int) = apps
                override fun forget(packageName: String) = Unit
            }
            ReflectionHelpers.setField(service, "overlay", SwitcherOverlay(service, source, { false }, {}, { _, _ -> }, {},
                { _, _ -> },
                { display -> ReflectionHelpers.callInstanceMethod<Unit>(service, "onBottomSwitcherShown", ClassParameter.from(Int::class.javaPrimitiveType, display)) },
                { ReflectionHelpers.callInstanceMethod<Unit>(service, "onBottomSwitcherDismissed") }))
            ReflectionHelpers.setField(service, "focusIndicator", FocusIndicatorOverlay(service))
            seen = fake.commands.size
            out.append("## ").append(label).append('\n')
            block(service)
            drain()
            log(service, "end")
        } finally {
            runCatching { service.onDestroy() }
            fake.uninstall()
            ShadowDisplayManager.removeDisplay(bottomId)
        }
    }

    /** Task 1011 delivers its snapshot; 2022 never does. */
    private fun snapshot(script: String): String {
        val args = Regex("'([^']*)'").findAll(script).map { it.groupValues[1] }.toList()
        val path = args[args.indexOf("snapshot") + 3]
        if ("'1011'" !in script) return "ERR no snapshot"
        File(path).outputStream().use { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "OK snapshot"
    }

    /** Runs the main looper's due work and lets daemon workers answer until nothing new arrives. */
    private fun drain() {
        var quiet = 0
        var last = -1
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(15)
            val now = if (::fake.isInitialized) fake.commands.size else 0
            if (now == last) quiet++ else { quiet = 0; last = now }
            if (quiet >= 20) return
        }
    }

    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        drain()
    }

    private fun normalize(text: String) = Regex("pservice_\\d+\\.sh").replace(text, "pservice_<n>.sh")
        .replace(Regex("'/[^']*'"), "'<path>'").replace("'$bottomId'", "'<bottom>'").replace("-d $bottomId ", "-d <bottom> ")
        .replace(Regex("BBHAutoFocus-[0-9a-f-]+"), "BBHAutoFocus-<id>")

    private fun log(service: SwitcherService, step: String) {
        val commands = fake.commands
        commands.drop(seen).filterNot { it.contains("padhelper_start.sh") }.map(::normalize)
            .forEach { out.append("root ").append(it).append('\n') }
        seen = commands.size
        val overlay = ReflectionHelpers.getField<SwitcherOverlay>(service, "overlay")
        val shown = ReflectionHelpers.getField<List<ShownCard>>(overlay, "shown").map { it.app }
        val switcher = ReflectionHelpers.callInstanceMethod<Any>(service, "getBottomSwitcher")
        val requested = ReflectionHelpers.getField<Map<Any, Int>>(switcher, "requestedThumbnails")
            .map { (key, count) -> "${ReflectionHelpers.getField<Int>(key, "taskId")}=$count" }.sorted()
        out.append("[").append(step).append("] showing=").append(overlay.isShowing)
            .append(" opening=").append(ReflectionHelpers.getField<Boolean>(switcher, "opening"))
            .append(" chose=").append(ReflectionHelpers.getField<Boolean>(switcher, "chose"))
            .append(" thumbs=").append(shown.filter { it.thumbnail != null }.map { it.packageName })
            .append(" requested=").append(requested)
            .append(" focus=").append(Settings.System.getString(service.contentResolver, "screen_focus_lock"))
            .append('\n')
    }

    private fun press(service: SwitcherService) = ReflectionHelpers.callInstanceMethod<Unit>(service, "showSwitcherNow")

    @Test
    fun `bottom switcher lifecycle keeps its order and limits`() {
        out = StringBuilder()
        scenario("open, poll, thumbnails, close") { s ->
            press(s); drain(); log(s, "opened")
            advance(1000); log(s, "poll 1")
            advance(1000); log(s, "poll 2")
            advance(1000); log(s, "poll 3")
            press(s); drain(); log(s, "closed")
            advance(2000); log(s, "after close")
        }
        scenario("second press while opening") { s ->
            press(s); press(s); drain(); log(s, "pressed twice")
            advance(1000); log(s, "later")
        }
        scenario("task list unreadable", tasks = { null }) { s ->
            press(s); drain(); log(s, "opened")
            advance(1000); log(s, "poll 1")
        }
        // Without task ids: no thumbnail worker races the second state refresh the routing causes.
        scenario("controller routes to the switcher and back", controller = true, tasks = { bottom ->
            "OK [{\"package\":\"com.example.a\",\"display\":$bottom,\"locked\":false}]"
        }) { s ->
            press(s); drain(); log(s, "opened")
            press(s); drain(); log(s, "closed")
        }
        scenario("a chosen card") { s ->
            press(s); drain()
            ReflectionHelpers.callInstanceMethod<Unit>(s, "launchFromRecents", ClassParameter.from(String::class.java, "com.example.b"),
                ClassParameter.from(Int::class.javaPrimitiveType, bottomId))
            log(s, "chosen")
            drain(); log(s, "launched")
            ReflectionHelpers.getField<SwitcherOverlay>(s, "overlay").dismiss(); drain(); log(s, "closed")
        }
        scenario("disconnect while opening") { s ->
            press(s)
            ReflectionHelpers.setStaticField(SwitcherService::class.java, "isConnected", false)
            drain(); log(s, "disconnected")
            ReflectionHelpers.setStaticField(SwitcherService::class.java, "isConnected", true)
        }
        val file = File("src/test/resources/commands/bottomswitcher.txt")
        if (System.getenv("UPDATE_COMMAND_FIXTURES") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(out.toString())
            return
        }
        if (!file.exists()) fail("Fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("bottom switcher lifecycle differs from the frozen fixture", file.readText(), out.toString())
    }
}
