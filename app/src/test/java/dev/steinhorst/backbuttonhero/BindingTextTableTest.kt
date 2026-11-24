package dev.steinhorst.backbuttonhero

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.actions.ActionGroups
import dev.steinhorst.backbuttonhero.actions.Ayn
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.input.HomeLayerButton
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.input.Stick
import dev.steinhorst.backbuttonhero.pad.GyroAxis
import dev.steinhorst.backbuttonhero.pad.GyroSettings
import dev.steinhorst.backbuttonhero.prefs.Prefs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Every text the settings derive from a binding: the action's name, its option, the screen, mode,
 * target, quality, duck, indicator position, push-to-talk key, gyro summary and the overview's short
 * label, for every action and every value, in both layers and both languages.
 * Frozen in `src/test/resources/commands/bindingtext_<locale>.txt`, recorded once on the pre-R1
 * baseline; UPDATE_COMMAND_FIXTURES=1 re-records, only for an approved change.
 */
@RunWith(RobolectricTestRunner::class)
class BindingTextTableTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test @Config(qualifiers = "en-xxhdpi")
    fun `binding texts in English`() = table("en")

    @Test @Config(qualifiers = "de-xxhdpi")
    fun `binding texts in German`() = table("de")

    private fun table(locale: String) {
        val prefs = Prefs(context).apply { welcomeSeen = true }
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val out = StringBuilder()
        fun call(name: String, vararg args: ClassParameter<*>): String =
            runCatching { ReflectionHelpers.callInstanceMethod<Any?>(activity, name, *args).toString() }
                .getOrElse { "<${(it.cause ?: it)::class.java.simpleName}>" }
        fun on(button: ComboButton, name: String) = call(name, ClassParameter.from(ComboButton::class.java, button))
        fun str(name: String, value: String) = call(name, ClassParameter.from(String::class.java, value))

        val actions = buildList {
            addAll(listOf("", Prefs.SHOULDER_APP_ASK, Prefs.SHOULDER_APP_FAVORITES, Prefs.SHOULDER_HOME, Prefs.SHOULDER_BACK,
                Prefs.SHOULDER_SWAP_APPS, Prefs.SHOULDER_LAST_APP, Prefs.SHOULDER_SCREENSHOT, Prefs.SHOULDER_RECORD_VIDEO,
                Prefs.SHOULDER_MACRO, Prefs.SHOULDER_GYRO, Prefs.SHOULDER_GYRO_CALIBRATE, Prefs.SHOULDER_CONTROLLER_FOCUS,
                Prefs.SHOULDER_SWITCH_APP, Prefs.SHOULDER_APP_CONTROL, Prefs.SHOULDER_STATUS))
            ActionGroups.Group.values().forEach { g -> g.options.forEach { add(it.action) } }
            Ayn.Group.values().forEach { add(it.action) }
            Ayn.Toggle.values().forEach { add(it.action) }
            add(context.packageName)
            add("com.example.not.installed")
        }
        for (button in listOf<ComboButton>(ShoulderButton.L1, HomeLayerButton.of(ShoulderButton.L1))) {
            out.append("# ").append(button.name).append('\n')
            for (action in actions) {
                prefs.setShoulderAction(button, action)
                out.append("action ").append(action.ifEmpty { "<none>" })
                    .append(" | ").append(on(button, "describeShoulderAction"))
                    .append(" | option=").append(on(button, "describeOption"))
                    .append(" | function=").append(str("functionName", action))
                    .append(" | overview=").append(str("overviewLabel", action)).append('\n')
            }
            prefs.favoriteApps = setOf(context.packageName)
            prefs.setShoulderAction(button, Prefs.SHOULDER_APP_FAVORITES)
            out.append("favorites set | ").append(on(button, "describeShoulderAction")).append('\n')
            prefs.favoriteApps = emptySet()
            for (group in Ayn.Group.values()) {
                prefs.setShoulderAction(button, group.action)
                for (key in (if (group.hasModal) listOf(Ayn.MODAL) else emptyList()) + group.options.map { it.key }) {
                    prefs.setAynOption(button, key)
                    out.append("ayn ").append(group.action).append(' ').append(key).append(" | ").append(on(button, "describeOption")).append('\n')
                }
            }
            fun values(label: String, set: (String) -> Unit, describe: String, vararg values: String) = values.forEach {
                set(it)
                out.append(label).append(' ').append(it).append(" | ").append(on(button, describe)).append('\n')
            }
            values("appMode", { prefs.setAppMode(button, it) }, "describeAppMode",
                Prefs.APP_MINIMIZE, Prefs.APP_FORCE_CLOSE, Prefs.APP_RESTART, "bogus")
            values("appScreen", { prefs.setAppScreen(button, it) }, "describeAppScreen",
                Prefs.DISPLAY_TOP, Prefs.DISPLAY_BOTTOM, Prefs.TARGET_BOTH, Prefs.APP_SCREEN_ASK, "bogus")
            values("levelTarget", { prefs.setLevelTarget(button, it) }, "describeLevelTarget",
                Prefs.DISPLAY_TOP, Prefs.DISPLAY_BOTTOM, Prefs.TARGET_BOTH, "bogus")
            values("display", { prefs.setShoulderDisplay(button, it) }, "describeShoulderDisplay",
                Prefs.DISPLAY_TOP, Prefs.DISPLAY_BOTTOM, Prefs.APP_SCREEN_ASK, "bogus")
            values("focusTarget", { prefs.setFocusTarget(button, it) }, "describeFocusTarget",
                Prefs.DISPLAY_TOP, Prefs.DISPLAY_BOTTOM, Prefs.FOCUS_TOGGLE, Prefs.FOCUS_MODAL, "bogus")
            values("quality", { prefs.setRecordQuality(button, it) }, "describeRecordQuality",
                Prefs.RECORD_SMALL, Prefs.RECORD_MEDIUM, Prefs.RECORD_LARGE)
            values("duck", { prefs.setRecordDuck(button, it) }, "describeRecordDuck",
                Prefs.DUCK_OFF, Prefs.DUCK_VIDEO, Prefs.DUCK_DEVICE)
            val positions = arrayOf(Prefs.FOCUS_POS_TOP_LEFT, Prefs.FOCUS_POS_TOP_CENTER, Prefs.FOCUS_POS_TOP_RIGHT,
                Prefs.FOCUS_POS_CENTER_LEFT, Prefs.FOCUS_POS_CENTER, Prefs.FOCUS_POS_CENTER_RIGHT,
                Prefs.FOCUS_POS_BOTTOM_LEFT, Prefs.FOCUS_POS_BOTTOM_CENTER, Prefs.FOCUS_POS_BOTTOM_RIGHT)
            prefs.setFocusIndicator(button, true)
            values("focusPosition", { prefs.setFocusPosition(button, it) }, "describeFocusPosition", *positions)
            prefs.setFocusIndicator(button, false)
            out.append("focusPosition off | ").append(on(button, "describeFocusPosition")).append('\n')
            prefs.setRecordIndicator(button, true)
            values("recordPosition", { prefs.setRecordPosition(button, it) }, "describeRecordIndicator", *positions)
            prefs.setRecordIndicator(button, false)
            out.append("recordPosition off | ").append(on(button, "describeRecordIndicator")).append('\n')
        }
        for (key in listOf(0, 96, 97, 99, 100, 102, 103, 104, 105, 106, 107, 108, 109, 4, 24)) {
            out.append("ptt ").append(key).append(" | ")
                .append(call("describePushToTalkKey", ClassParameter.from(Int::class.javaPrimitiveType, key))).append('\n')
        }
        for (gyro in listOf(GyroSettings(), GyroSettings(stick = Stick.LEFT, axis = GyroAxis.ROLL, rate = true, sensitivity = 150),
            GyroSettings(holdKey = 103), GyroSettings(holdKey = 103, sensitivity = 40))) {
            out.append("gyro ").append(gyro).append(" | ")
                .append(call("describeGyro", ClassParameter.from(GyroSettings::class.java, gyro))).append('\n')
        }
        for (value in listOf(Prefs.TOGGLE_L1_R1, Prefs.TOGGLE_L2_R2, Prefs.TOGGLE_BOTH, "", "bogus")) {
            out.append("toggle ").append(value.ifEmpty { "<none>" }).append(" | ").append(str("toggleLabel", value)).append('\n')
        }
        val file = File("src/test/resources/commands/bindingtext_$locale.txt")
        if (System.getenv("UPDATE_COMMAND_FIXTURES") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(out.toString())
            return
        }
        if (!file.exists()) fail("Fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("binding texts differ from the frozen fixture", file.readText(), out.toString())
    }
}
