package dev.steinhorst.backbuttonhero.prefs

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.input.HomeLayerButton
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every binding field travels raw key → snapshot → apply → backup → import under its own name, in
 * both layers. Neighbouring fields of one type carry different values, so a swapped argument or a
 * missing mapping in one of the hand-written projections fails here with the field's name.
 */
@RunWith(RobolectricTestRunner::class)
class BindingProjectionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = Prefs(context)
    private val sp = context.getSharedPreferences("back_button_hero", Context.MODE_PRIVATE)

    private class Field(val name: String, val suffix: String, val raw: Any?, val set: (BindingSettings) -> BindingSettings)

    /** String, long and int fields at once: distinct non-default values per layer. */
    private fun valueFields(home: Boolean): List<Field> = listOf(
        Field("action", "package", if (home) "com.example.home" else "com.example.back") { it.copy(action = if (home) "com.example.home" else "com.example.back") },
        Field("display", "display", Prefs.DISPLAY_TOP) { it.copy(display = Prefs.DISPLAY_TOP) },
        Field("targetOverride", "target", if (home) Prefs.DISPLAY_BOTTOM else Prefs.TARGET_BOTH) {
            it.copy(targetOverride = if (home) Prefs.DISPLAY_BOTTOM else Prefs.TARGET_BOTH)
        },
        Field("focusTarget", "focus_target", Prefs.DISPLAY_BOTTOM) { it.copy(focusTarget = Prefs.DISPLAY_BOTTOM) },
        Field("duration", "focus_duration_ms", if (home) 700L else 900L) { it.copy(duration = if (home) 700L else 900L) },
        Field("position", "focus_position", Prefs.FOCUS_POS_TOP_LEFT) { it.copy(position = Prefs.FOCUS_POS_TOP_LEFT) },
        Field("appMode", "app_mode", Prefs.APP_FORCE_CLOSE) { it.copy(appMode = Prefs.APP_FORCE_CLOSE) },
        Field("appScreen", "app_screen", Prefs.DISPLAY_BOTTOM) { it.copy(appScreen = Prefs.DISPLAY_BOTTOM) },
        Field("aynOption", "ayn_option", if (home) "quiet" else "smart") { it.copy(aynOption = if (home) "quiet" else "smart") },
        Field("recordQuality", "record_quality", Prefs.RECORD_LARGE) { it.copy(recordQuality = Prefs.RECORD_LARGE) },
        Field("recordPosition", "record_position", Prefs.FOCUS_POS_BOTTOM_CENTER) { it.copy(recordPosition = Prefs.FOCUS_POS_BOTTOM_CENTER) },
        Field("recordPttKey", "record_ptt_key", if (home) KeyEvent.KEYCODE_BUTTON_X else KeyEvent.KEYCODE_BUTTON_Y) {
            it.copy(recordPttKey = if (home) KeyEvent.KEYCODE_BUTTON_X else KeyEvent.KEYCODE_BUTTON_Y)
        },
        Field("recordDuck", "record_duck", Prefs.DUCK_DEVICE) { it.copy(recordDuck = Prefs.DUCK_DEVICE) },
    )

    /** Booleans only have one non-default value each, so they are flipped one at a time. */
    private val flags: List<Field> = listOf(
        Field("indicator", "focus_indicator", false) { it.copy(indicator = false) },
        Field("appConfirm", "app_confirm", true) { it.copy(appConfirm = true) },
        Field("switchAppFollow", "switch_app_follow", false) { it.copy(switchAppFollow = false) },
        Field("closeConfirm", "close_confirm", false) { it.copy(closeConfirm = false) },
        Field("standbyConfirm", "standby_confirm", true) { it.copy(standbyConfirm = true) },
        Field("recordMic", "record_mic", true) { it.copy(recordMic = true) },
        Field("recordIndicator", "record_indicator", false) { it.copy(recordIndicator = false) },
        Field("recordPtt", "record_ptt", true) { it.copy(recordPtt = true) },
    )

    private val keys = listOf("l1", "${HomeLayerButton.PREFIX}l1")

    private fun changed(fields: (home: Boolean) -> List<Field>): SettingsState = SettingsState().let { factory ->
        factory.copy(bindings = factory.bindings.mapValues { (key, value) ->
            if (key in keys) fields(key != keys[0]).fold(value) { binding, field -> field.set(binding) } else value
        })
    }

    private fun checkRoundTrip(state: SettingsState, fields: (home: Boolean) -> List<Field>) {
        assertTrue(prefs.applySettings(prefs.snapshot(), state))
        keys.forEach { key ->
            fields(key != keys[0]).forEach { field ->
                assertEquals("raw ${key}_${field.suffix} (${field.name})", field.raw, sp.all["${key}_${field.suffix}"])
            }
            assertEquals("snapshot $key", state.bindings.getValue(key), prefs.snapshot().bindings.getValue(key))
        }
        val text = SettingsBackup.write(prefs.snapshot(), "1.5.0", 15, Instant.parse("2026-11-24T10:00:00Z"))
        val loaded = (SettingsBackup.read(text) as SettingsBackup.Result.Loaded).state
        keys.forEach { key -> assertEquals("backup $key", state.bindings.getValue(key), loaded.bindings.getValue(key)) }
        assertTrue(prefs.applySettings(prefs.snapshot(), SettingsState()))
        assertTrue(prefs.applySettings(prefs.snapshot(), loaded))
        assertEquals(state, prefs.snapshot())
    }

    @Test
    fun `value fields keep their names through every projection in both layers`() {
        checkRoundTrip(changed(::valueFields), ::valueFields)
    }

    @Test
    fun `each flag keeps its name through every projection in both layers`() {
        flags.forEach { flag ->
            val one = { _: Boolean -> listOf(flag) }
            checkRoundTrip(changed(one), one)
            assertTrue(prefs.applySettings(prefs.snapshot(), SettingsState()))
        }
    }
}
