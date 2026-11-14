package dev.steinhorst.backbuttonhero

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.steinhorst.backbuttonhero.support.selectSettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class SettingsBackupUiTest {
    private val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    private val prefs = Prefs(activity)

    private fun find(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), match)?.let { return it }
        return null
    }

    private fun text(view: View): String = buildString {
        if (view is TextView) append(view.text).append('\n')
        if (view is ViewGroup) for (i in 0 until view.childCount) append(text(view.getChildAt(i)))
    }

    private fun click(res: Int) {
        var view = find(activity.findViewById(android.R.id.content)) {
            it is TextView && it.text.toString() == activity.getString(res)
        }!!
        while (!view.isClickable) view = view.parent as View
        view.performClick()
    }

    private fun dialog() = ShadowAlertDialog.getLatestAlertDialog()
    private fun dialogText() = text(dialog().window!!.decorView)
    private fun press(button: Int) {
        dialog().getButton(button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun version(): String = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "1.0.0"

    private fun backup(version: String = version(), change: (SettingsState) -> SettingsState): ByteArray =
        SettingsBackup.write(change(prefs.snapshot()), version, 1, Instant.EPOCH).toByteArray()

    private fun withL1(state: SettingsState) = state.copy(
        bindings = state.bindings + ("l1" to state.bindings.getValue("l1").copy(action = Prefs.SHOULDER_HOME)),
        doubleMs = 500,
    )

    @Test
    fun `the Settings page offers the backup instead of the old reset rows`() {
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        val root = activity.findViewById<View>(android.R.id.content)
        listOf(R.string.backup_save, R.string.backup_load).forEach { label ->
            assertNotNull(find(root) { it is TextView && it.text.toString() == activity.getString(label) })
        }
        assertNull(find(root) { it is TextView && it.text.toString() == "Reset settings" })
        assertNull(find(root) { it is TextView && it.text.toString() == "Unbind optionals" })
        // Pages with bindings keep a single, full-width "Clear bindings" and no "Load defaults".
        val actions = find(root) { it.tag == "settings_page_actions" } as ViewGroup
        assertEquals(1, actions.childCount)
        assertNull(find(root) { it is TextView && it.text.toString() == "Load defaults" })
    }

    @Test
    fun `saving offers a file named after the version and the date`() {
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        click(R.string.backup_save)
        val intent = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/json", intent.type)
        assertTrue(intent.getStringExtra(Intent.EXTRA_TITLE)!!
            .matches(Regex("back-button-hero-backup_v${Regex.escape(version())}_\\d{4}-\\d{2}-\\d{2}\\.json")))
    }

    @Test
    fun `loading opens the file picker`() {
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        click(R.string.backup_load)
        val intent = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
    }

    @Test
    fun `a backup of this version shows every change first and applies it atomically on confirm`() {
        activity.offerBackup(backup { withL1(it) })
        val preview = dialogText()
        assertTrue(preview, preview.contains("Back + L1: Not set → Home"))
        assertTrue(preview, preview.contains("Double-press timing: 300 ms → 500 ms"))
        assertEquals("", prefs.shoulderAction(ShoulderButton.L1))
        press(AlertDialog.BUTTON_POSITIVE)
        assertEquals(Prefs.SHOULDER_HOME, prefs.shoulderAction(ShoulderButton.L1))
        assertEquals(500L, prefs.doubleTapMs)
    }

    @Test
    fun `cancelling the preview changes nothing`() {
        val before = prefs.snapshot()
        activity.offerBackup(backup { withL1(it) })
        press(AlertDialog.BUTTON_NEGATIVE)
        assertEquals(before, prefs.snapshot())
    }

    @Test
    fun `a backup that matches the current settings says so`() {
        activity.offerBackup(backup { it })
        assertTrue(dialogText().contains(activity.getString(R.string.backup_matches)))
    }

    @Test
    fun `an older or newer version warns that it may not fit, but does not have to, then continues`() {
        listOf("0.0.1" to R.string.backup_version_older, "99.0.0" to R.string.backup_version_newer).forEach { (other, message) ->
            val before = prefs.snapshot()
            activity.offerBackup(backup(other) { withL1(it) })
            assertTrue(dialogText().contains(activity.getString(message, other, version())))
            assertTrue(dialogText().contains("may cause incompatibilities, but it doesn't have to"))
            press(AlertDialog.BUTTON_NEGATIVE)
            assertEquals(before, prefs.snapshot())
            activity.offerBackup(backup(other) { withL1(it) })
            press(AlertDialog.BUTTON_POSITIVE)
            assertTrue(dialogText().contains("Back + L1: Not set → Home"))
            press(AlertDialog.BUTTON_POSITIVE)
            assertEquals(Prefs.SHOULDER_HOME, prefs.shoulderAction(ShoulderButton.L1))
            prefs.setShoulderAction(ShoulderButton.L1, "")
            prefs.doubleTapMs = Prefs.DEFAULT_DOUBLE_TAP_MS
        }
    }

    @Test
    fun `a backup without a version warns too`() {
        val text = """{"format":"${SettingsBackup.FORMAT}","settings":{"backTimings":{"doubleMs":500}}}"""
        activity.offerBackup(text.toByteArray())
        assertTrue(dialogText().contains("does not say which version"))
        press(AlertDialog.BUTTON_POSITIVE)
        assertTrue(dialogText().contains("500 ms"))
    }

    @Test
    fun `files that are no backup are refused without touching anything`() {
        val before = prefs.snapshot()
        listOf("hello".toByteArray() to R.string.backup_not_json,
            """{"a":1}""".toByteArray() to R.string.backup_not_a_backup,
            ByteArray(SettingsBackup.MAX_BYTES + 1) { ' '.code.toByte() } to R.string.backup_too_large,
            null to R.string.backup_read_failed).forEach { (bytes, message) ->
            activity.offerBackup(bytes)
            assertTrue(dialogText().contains(activity.getString(message)))
            assertFalse(dialog().getButton(AlertDialog.BUTTON_NEGATIVE).isShown)
            press(AlertDialog.BUTTON_POSITIVE)
            assertEquals(before, prefs.snapshot())
        }
    }

    @Test
    fun `the restore preview goes stale like every other one`() {
        activity.offerBackup(backup { withL1(it) })
        prefs.doubleTapMs = 550
        press(AlertDialog.BUTTON_POSITIVE)
        assertEquals(550L, prefs.doubleTapMs)
        assertEquals("", prefs.shoulderAction(ShoulderButton.L1))
        assertTrue(dialogText().contains("550 ms → 500 ms"))
        press(AlertDialog.BUTTON_POSITIVE)
        assertEquals(500L, prefs.doubleTapMs)
        assertEquals(Prefs.SHOULDER_HOME, prefs.shoulderAction(ShoulderButton.L1))
    }
}
