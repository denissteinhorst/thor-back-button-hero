package dev.steinhorst.backbuttonhero

import android.os.Looper
import android.provider.Settings
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.overlay.FocusIndicatorOverlay
import dev.steinhorst.backbuttonhero.prefs.Prefs
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

/**
 * Top Recents in the service: the launch phase (baseline task list, native Recents, the 250 ms
 * Quickstep poll, the selection read once Overview closes, its attempt limit and cancellation)
 * and the controller session (routing to the top screen, the state checks, focus priming, the
 * attempt limit, the restore of the earlier route, and closing with an injected or global Back).
 * Quickstep's dump is scripted per call; time advances only in explicit steps.
 * Frozen in `src/test/resources/commands/toprecents.txt`, recorded once on the pre-R1 baseline;
 * UPDATE_COMMAND_FIXTURES=1 re-records, only for an approved change.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceTopRecentsTest {
    private var bottomId = -1
    private lateinit var fake: FakePService
    private lateinit var out: StringBuilder
    private var seen = 0
    private var actionsSeen = 0
    /** The controller session's 40 ms checks interleave with the launch poll; keep only what ran. */
    private var unordered = false

    private fun scenario(
        label: String,
        controller: Boolean = false,
        baseline: (Int) -> String? = { bottom -> "OK [{\"package\":\"com.example.a\",\"display\":$bottom,\"locked\":false,\"task\":1011}]" },
        dumps: List<String?>,
        selection: (Int) -> String? = { null },
        route: String = "OK",
        back: String = "OK",
        block: (SwitcherService) -> Unit,
    ) {
        unordered = controller
        var dump = 0
        fake = FakePService { command ->
            val script = Regex("^sh '([^']+)'$").find(command)?.let { File(it.groupValues[1]).takeIf(File::exists)?.readText() }.orEmpty()
            when {
                command.startsWith("dumpsys activity com.android.launcher3") -> dumps[minOf(dump++, dumps.lastIndex)] ?: ""
                "RecentsStateReader 'selection'" in script -> selection(bottomId) ?: ""
                "RecentsStateReader" in script -> baseline(bottomId) ?: ""
                command.startsWith("setprop") -> route
                command.startsWith("input keyboard") -> back
                else -> "OK"
            }
        }.install()
        bottomId = ShadowDisplayManager.addDisplay("w400dp-h300dp")
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        try {
            Settings.System.putString(service.contentResolver, "screen_focus_lock", "2")
            Prefs(service).switcherControllerEnabled = controller
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            drain()
            val empty = object : AppSource {
                override fun appsOnDisplay(displayId: Int) = emptyList<RunningApp>()
                override fun forget(packageName: String) = Unit
            }
            ReflectionHelpers.setField(service, "overlay", SwitcherOverlay(service, empty, { true }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}))
            ReflectionHelpers.setField(service, "focusIndicator", FocusIndicatorOverlay(service))
            seen = fake.commands.size
            actionsSeen = shadowOf(service).globalActionsPerformed.size
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

    private fun advance(ms: Long, times: Int = 1) = repeat(times) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        drain()
    }

    private fun normalize(text: String) = Regex("pservice_\\d+\\.sh").replace(text, "pservice_<n>.sh")
        .replace(Regex("'/[^']*'"), "'<path>'").replace("'$bottomId'", "'<bottom>'")
        .replace(Regex("'selection' '\\d+'"), "'selection' '<time>'")

    private fun log(service: SwitcherService, step: String) {
        val commands = fake.commands
        val run = commands.drop(seen).filterNot { it.contains("padhelper_start.sh") }.map(::normalize)
        (if (unordered) run.distinct().sorted().map { listOf(it) }.flatten() else run)
            .fold(mutableListOf<Pair<String, Int>>()) { acc, c ->
                if (acc.lastOrNull()?.first == c) acc[acc.lastIndex] = c to acc.last().second + 1 else acc += c to 1
                acc
            }.forEach { (c, n) -> out.append("root ").append(if (n > 1) "x$n " else "").append(c).append('\n') }
        seen = commands.size
        val actions = shadowOf(service).globalActionsPerformed
        (if (unordered) actions.drop(actionsSeen).distinct().sorted() else actions.drop(actionsSeen)).forEach { out.append("global ").append(it).append('\n') }
        actionsSeen = actions.size
        fun flag(name: String) = ReflectionHelpers.getField<Any?>(service, name)
        out.append("[").append(step).append("] active=").append(flag("topRecentsControllerActive"))
            .append(" confirmed=").append(flag("topRecentsConfirmed"))
            .append(" exit=").append(flag("topRecentsExitRequested"))
            .append(" primed=").append(flag("topRecentsFocusPrimed"))
            .append(" launch=").append((flag("topLaunchBaseline") != null))
            .append(" restore=").append(flag("recentsFocusRestore"))
            .append('\n')
    }

    private fun open(service: SwitcherService) = ReflectionHelpers.callInstanceMethod<Unit>(service, "openTopRecentsNow")

    @Test
    fun `top Recents launch and controller session keep their order and limits`() {
        out = StringBuilder()
        val overview = "mState:Overview|mResumed=true|mHasWindowFocus=true|TaskView{1 V.ED..... ........ 100,200-500,700}|"
        val closed = "mState:Normal|mResumed=true|mHasWindowFocus=true|"
        val moved: (Int) -> String = { bottom -> "OK [{\"package\":\"com.example.a\",\"display\":$bottom,\"locked\":false,\"task\":1011}]" }
        scenario("launch, baseline unreadable", baseline = { null }, dumps = listOf(overview)) { s ->
            open(s); drain(); log(s, "opened")
            advance(250, 3); log(s, "later")
        }
        scenario("launch, a card moves its task to the top", dumps = listOf(overview, overview, closed), selection = moved) { s ->
            open(s); drain(); log(s, "opened")
            advance(250); log(s, "poll 1")
            advance(250); log(s, "poll 2")
            advance(250); log(s, "poll 3")
            advance(250, 2); log(s, "later")
        }
        scenario("launch, Overview never appears", dumps = listOf(closed)) { s ->
            open(s); drain()
            advance(250, 24); log(s, "24 polls")
            advance(250, 4); log(s, "later")
        }
        scenario("launch, cancelled while polling", dumps = listOf(overview)) { s ->
            open(s); drain()
            advance(250, 2); log(s, "polling")
            ReflectionHelpers.callInstanceMethod<Unit>(s, "cancelTopRecentsLaunch")
            advance(250, 3); log(s, "cancelled")
        }
        scenario("launch, selection unreadable", dumps = listOf(overview, closed)) { s ->
            open(s); drain()
            advance(250, 2); log(s, "closed")
            advance(250, 2); log(s, "later")
        }
        scenario("controller, confirmed then closed", controller = true, dumps = listOf(overview, overview, closed), selection = moved) { s ->
            open(s); drain()
            advance(40, 10)
        }
        scenario("controller, never confirmed", controller = true, dumps = listOf(closed)) { s ->
            open(s); drain()
            advance(40, 30)
        }
        scenario("controller, route refused", controller = true, route = "ERR", dumps = listOf(overview)) { s ->
            open(s); drain()
            advance(40, 3)
        }
        scenario("controller, closed with Back", controller = true, back = "ERR", dumps = listOf(overview)) { s ->
            open(s); drain(); advance(40)
            ReflectionHelpers.callInstanceMethod<Unit>(s, "closeTopRecents"); drain()
            ReflectionHelpers.callInstanceMethod<Unit>(s, "closeTopRecents"); drain()
        }
        val file = File("src/test/resources/commands/toprecents.txt")
        if (System.getenv("UPDATE_COMMAND_FIXTURES") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(out.toString())
            return
        }
        if (!file.exists()) fail("Fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("top Recents paths differ from the frozen fixture", file.readText(), out.toString())
    }
}
