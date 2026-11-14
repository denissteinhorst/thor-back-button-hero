package dev.steinhorst.backbuttonhero

import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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

/** While the accessibility service is off, one centred button over a tinted scrim is the only way on. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class ServiceBlockerTest {
    private fun root(activity: SettingsActivity) = activity.findViewById<View>(android.R.id.content)

    @Test
    fun `with the service off the scrim covers everything but the header and swallows touches`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
        val blocker = root(activity).findViewWithTag<View>("settings_service_blocker")
        assertEquals(View.VISIBLE, blocker.visibility)
        assertTrue(blocker.isClickable)
        // Below the header, not inside it: the bar keeps its menu and the status pill.
        val bar = root(activity).findViewWithTag<View>("settings_top_bar")
        assertFalse(isInside(blocker, bar))
        assertEquals(View.VISIBLE, bar.findViewWithTag<View>("settings_service_warning").visibility)
        // The menu, pages and warnings sit under it and are hidden from focus and screen readers.
        val navigation = root(activity).findViewWithTag<View>("settings_navigation")
        if (navigation != null) {
            val column = generateSequence(navigation as View?) { it.parent as? View }.first { it.parent === blocker.parent }
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, (column as ViewGroup).importantForAccessibility)
        }
    }

    @Test
    fun `the button opens the accessibility settings`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
        val button = root(activity).findViewWithTag<Button>("settings_service_blocker_button")
        assertEquals(activity.getString(R.string.enable_service), button.text.toString())
        button.performClick()
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(activity).nextStartedActivity.action)
    }

    @Test
    fun `the scrim is gone once the service is on and the old inline button no longer exists`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible()
        val activity = controller.get()
        Settings.Secure.putString(
            activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${activity.packageName}/${SwitcherService::class.java.name}",
        )
        controller.pause().resume()
        assertEquals(View.GONE, root(activity).findViewWithTag<View>("settings_service_blocker").visibility)
        assertNotNull(root(activity).findViewWithTag<View>("settings_top_bar"))
        // The pill still tells an enabled but silent service apart from a healthy one.
        assertEquals(activity.serviceChipText(ServiceStatus.UNRESPONSIVE),
            root(activity).findViewWithTag<android.widget.TextView>("settings_service_warning").text.toString())
        assertNull(findText(root(activity), activity.getString(R.string.enable_service), exclude = "settings_service_blocker_button"))
    }

    private fun isInside(view: View, ancestor: View): Boolean =
        generateSequence(view as View?) { it.parent as? View }.any { it === ancestor }

    private fun findText(view: View, text: String, exclude: String): View? {
        if (view.tag == exclude) return null
        if (view is android.widget.TextView && view.text.toString() == text && view.tag != exclude) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i), text, exclude)?.let { return it }
        return null
    }
}
