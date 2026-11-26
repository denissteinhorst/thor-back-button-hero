package dev.steinhorst.backbuttonhero

import android.accessibilityservice.AccessibilityService
import android.content.ContextWrapper
import android.media.AudioManager
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import dev.steinhorst.backbuttonhero.actions.AppControlPrompt
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.input.BackGestureButton
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
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

/**
 * Runs every stored action (and one app) through the connected service's Back single tap and
 * freezes what it does besides root commands (those are in actions.txt): global actions, started
 * activities with their launch display, media keys, notices, toasts, the prompt it opens and whether
 * the bottom switcher shows. Recorded once on the pre-R1 baseline tree into
 * `src/test/resources/commands/effects.txt`; UPDATE_COMMAND_FIXTURES=1 re-records, only for an
 * explicitly approved change.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceActionEffectTest {

    private fun run(action: String): List<String> {
        val fake = FakePService().install()
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            settle()
            val source = object : AppSource {
                override fun appsOnDisplay(displayId: Int) = emptyList<RunningApp>()
                override fun forget(packageName: String) = Unit
            }
            val overlay = SwitcherOverlay(service, source, { true }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {})
            ReflectionHelpers.setField(service, "overlay", overlay)
            ReflectionHelpers.setField(service, "focusIndicator", FocusIndicatorOverlay(service))
            val prefs = Prefs(service)
            val before = prefs.snapshot()
            val single = BackGestureButton.SINGLE.prefKey
            prefs.applySettings(before, before.copy(bindings = before.bindings + (single to before.bindings.getValue(single).copy(action = action))))
            val shadow = shadowOf(service as ContextWrapper)
            while (shadow.nextStartedActivityForResult != null) Unit
            shadowOf(service as AccessibilityService).globalActionsPerformed.clear()
            val audio = shadowOf(service.getSystemService(AudioManager::class.java))
            audio.clearDispatchedMediaKeyEvents()
            ShadowLog.clear()
            val toasts = ShadowToast.shownToastCount()
            val manager = service.getSystemService(AudioManager::class.java)
            fun levels() = "music=${manager.getStreamVolume(AudioManager.STREAM_MUSIC)} muted=${manager.isStreamMute(AudioManager.STREAM_MUSIC)} " +
                "brightness=${Settings.System.getString(service.contentResolver, Settings.System.SCREEN_BRIGHTNESS)}"
            val levelsBefore = levels()
            service.onSingleTap()
            settle()
            val out = mutableListOf<String>()
            shadowOf(service as AccessibilityService).globalActionsPerformed.forEach { out += "global $it" }
            while (true) {
                val started = shadow.nextStartedActivityForResult ?: break
                val intent = started.intent
                // ActivityOptions keeps the launch display under this key; fromBundle is hidden API.
                val display = started.options?.getInt("android.activity.launchDisplayId", -1)
                out += "activity action=${intent.action} component=${intent.component?.flattenToShortString()} " +
                    "data=${intent.dataString} categories=${intent.categories?.sorted()} flags=0x${Integer.toHexString(intent.flags)} " +
                    "extras=${intent.extras?.keySet()?.sorted()} display=$display"
            }
            audio.dispatchedMediaKeyEvents.forEach { out += "media action=${it.action} key=${it.keyCode}" }
            ShadowLog.getLogsForTag("BackButtonHero").filter { it.msg.startsWith("Notice: ") }.forEach { out += it.msg }
            if (ShadowToast.shownToastCount() > toasts) out += "toast ${ShadowToast.getTextOfLatestToast()}"
            val prompt = ReflectionHelpers.getField<AppControlPrompt>(service, "appControlPrompt")
            ReflectionHelpers.getField<Pair<WindowManager, View>?>(prompt, "window")?.let { (_, view) -> out += "prompt ${texts(view)}" }
            if (overlay.isShowing) out += "switcher showing"
            levels().takeIf { it != levelsBefore }?.let { out += "levels $levelsBefore -> $it" }
            return out
        } finally {
            runCatching { service.onDestroy() }
            fake.uninstall()
        }
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun settle() = repeat(30) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        Thread.sleep(20)
    }

    @Test
    fun `every stored action has unchanged effects besides root commands`() {
        val out = StringBuilder()
        for (action in Prefs.KNOWN_ACTIONS.sorted() + "com.android.settings") {
            out.append("## ").append(action).append('\n')
            run(action).forEach { out.append(it).append('\n') }
        }
        val file = File("src/test/resources/commands/effects.txt")
        if (System.getenv("UPDATE_COMMAND_FIXTURES") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(out.toString())
            return
        }
        if (!file.exists()) fail("Effect fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("action effects differ from the frozen fixture", file.readText(), out.toString())
    }
}
