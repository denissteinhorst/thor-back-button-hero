package dev.steinhorst.backbuttonhero

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.widget.TextView
import dev.steinhorst.backbuttonhero.settings.SettingsPage
import dev.steinhorst.backbuttonhero.settings.UpdateCheck
import dev.steinhorst.backbuttonhero.settings.UpdateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** Release checks under test control: no request leaves the process, and every result is delivered by hand. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class UpdateFlowTest {
    private class FakeCheck : UpdateCheck {
        val calls = mutableListOf<((UpdateChecker.CheckResult) -> Unit)?>()
        override fun run(context: Context, callback: ((UpdateChecker.CheckResult) -> Unit)?) {
            calls += callback
        }
    }

    class FakeCheckSettingsActivity : SettingsActivity() {
        override fun updateCheck(): UpdateCheck = check

        companion object {
            // Read during onCreate, before a test could reach the instance.
            private val check = FakeCheck()
            val calls get() = check.calls
        }
    }

    @Test
    fun `starting the settings asks the release check exactly once and without a callback`() {
        FakeCheckSettingsActivity.calls.clear()
        val controller = Robolectric.buildActivity(FakeCheckSettingsActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, FakeCheckSettingsActivity.calls.size)
        assertNull(FakeCheckSettingsActivity.calls.single())
        controller.pause().stop().destroy()
    }

    private class Fixture {
        val controller = Robolectric.buildActivity(FakeCheckSettingsActivity::class.java).setup()
        val activity = controller.get()
        val check = FakeCheck()
        val opened = mutableListOf<String>()
        val flow = UpdateFlow(activity, check) { opened += it }
        val title = TextView(activity).apply { setText(R.string.check_for_updates) }
        val handle = SettingsPage.ActionHandle(TextView(activity).apply { isClickable = true }, title)

        fun press(): (UpdateChecker.CheckResult) -> Unit {
            ShadowAlertDialog.reset()
            flow.checkNow(handle)
            assertFalse(handle.row.isClickable)
            assertEquals(0.5f, handle.row.alpha)
            assertEquals(activity.getString(R.string.checking_for_updates), title.text)
            return checkNotNull(check.calls.last())
        }

        fun dialogMessage(): String? = ShadowAlertDialog.getLatestAlertDialog()?.let { shadowOf(it).message?.toString() }

        fun assertIdle() {
            assertTrue(handle.row.isClickable)
            assertEquals(1f, handle.row.alpha)
            assertEquals(activity.getString(R.string.check_for_updates), title.text)
        }
    }

    @Test
    fun `each result restores the row and names what was found`() {
        val f = Fixture()
        f.press()(UpdateChecker.CheckResult(UpdateChecker.Status.UP_TO_DATE))
        f.assertIdle()
        assertEquals(f.activity.getString(R.string.no_update_message), f.dialogMessage())

        f.press()(UpdateChecker.CheckResult(UpdateChecker.Status.ERROR, failure = UpdateChecker.Failure.NETWORK))
        f.assertIdle()
        assertEquals(f.activity.getString(R.string.update_check_failed_message), f.dialogMessage())

        f.press()(UpdateChecker.CheckResult(UpdateChecker.Status.ERROR, failure = UpdateChecker.Failure.RATE_LIMITED, retryMinutes = 3))
        assertEquals(f.activity.resources.getQuantityString(R.plurals.update_rate_limited_minutes, 3, 3), f.dialogMessage())

        f.press()(UpdateChecker.CheckResult(UpdateChecker.Status.UPDATE_AVAILABLE, "v9.9.9", "https://example.invalid/release"))
        f.assertIdle()
        assertEquals(f.activity.getString(R.string.update_found_message, "v9.9.9"), f.dialogMessage())
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("https://example.invalid/release"), f.opened)
        assertEquals(4, f.check.calls.size)
        f.controller.pause().stop().destroy()
    }

    @Test
    fun `a result after the settings closed shows nothing`() {
        val f = Fixture()
        val deliver = f.press()
        f.controller.pause().stop().destroy()
        deliver(UpdateChecker.CheckResult(UpdateChecker.Status.UP_TO_DATE))
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertFalse(f.handle.row.isClickable)
    }
}
