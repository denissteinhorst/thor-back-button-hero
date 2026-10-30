package dev.steinhorst.backbuttonhero

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import dev.steinhorst.backbuttonhero.support.selectSettingsPage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class ExceptionSettingsPageTest {
    private fun root(activity: SettingsActivity) = activity.findViewById<View>(android.R.id.content)
    private fun find(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), predicate)?.let { return it }
        return null
    }
    private fun click(activity: SettingsActivity, res: Int) {
        var view = find(root(activity)) { it is TextView && it.text.toString() == activity.getString(res) }!!
        while (!view.isClickable) view = view.parent as View
        view.performClick()
    }
    private fun installApps(activity: SettingsActivity) {
        listOf("Alpha Emulator" to "org.example.alpha", "Beta Game" to "org.example.beta").forEach { (name, pkg) ->
            shadowOf(activity.packageManager).installPackage(PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    nonLocalizedLabel = name
                    flags = ApplicationInfo.FLAG_INSTALLED
                }
            })
        }
    }
    private fun picker(activity: SettingsActivity, query: String): ListView {
        click(activity, R.string.exceptions_add_app)
        val decor = ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
        val list = decor.findViewWithTag<ListView>("exceptions_app_list")
        val deadline = System.nanoTime() + 3_000_000_000L
        while (list.adapter.count == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("App query completed", list.adapter.count > 0)
        decor.findViewWithTag<EditText>("exceptions_search").setText(query)
        return list
    }

    @Test
    fun `app picker searches names and packages and every new row starts fully checked`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
        installApps(activity)
        val prefs = Prefs(activity)
        val before = prefs.snapshot()
        selectSettingsPage(activity, SettingsSection.EXCEPTIONS)
        assertNull(root(activity).findViewWithTag<View>("settings_page_actions"))
        var list = picker(activity, "ALPHA EMULATOR")
        assertEquals(1, list.adapter.count)
        list.performItemClick(null, 0, 0)
        for (i in 0 until 10) assertTrue(root(activity).findViewWithTag<CheckBox>("exception_org.example.alpha_$i").isChecked)
        root(activity).findViewWithTag<CheckBox>("exception_org.example.alpha_0").isChecked = false
        list = picker(activity, "org.example.beta")
        assertEquals(1, list.adapter.count)
        list.performItemClick(null, 0, 0)
        for (i in 0 until 10) assertTrue(root(activity).findViewWithTag<CheckBox>("exception_org.example.beta_$i").isChecked)
        assertFalse(root(activity).findViewWithTag<CheckBox>("exception_org.example.alpha_0").isChecked)
        assertEquals(before, prefs.snapshot())
    }

    @Test
    fun `UI draft survives navigation and recreation but never becomes runtime preferences`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible()
        var activity = controller.get()
        installApps(activity)
        val before = Prefs(activity).snapshot()
        selectSettingsPage(activity, SettingsSection.EXCEPTIONS)
        picker(activity, "org.example.alpha").performItemClick(null, 0, 0)
        root(activity).findViewWithTag<CheckBox>("exception_org.example.alpha_3").isChecked = false
        selectSettingsPage(activity, SettingsSection.BASIC)
        selectSettingsPage(activity, SettingsSection.EXCEPTIONS)
        assertFalse(root(activity).findViewWithTag<CheckBox>("exception_org.example.alpha_3").isChecked)
        controller.recreate()
        activity = controller.get()
        assertFalse(root(activity).findViewWithTag<CheckBox>("exception_org.example.alpha_3").isChecked)
        assertEquals(before, Prefs(activity).snapshot())
        root(activity).findViewWithTag<View>("exception_remove_org.example.alpha").performClick()
        assertNull(root(activity).findViewWithTag<View>("exception_org.example.alpha_3"))
    }

    @Test
    fun `already added apps cannot be added twice and cancelling the picker adds nothing`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
        installApps(activity)
        selectSettingsPage(activity, SettingsSection.EXCEPTIONS)
        picker(activity, "org.example.alpha").performItemClick(null, 0, 0)
        val list = picker(activity, "org.example.alpha")
        assertEquals(0, list.adapter.count)
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(root(activity).findViewWithTag<View>("exception_org.example.alpha_0"))
        assertNull(root(activity).findViewWithTag<View>("exception_org.example.beta_0"))
    }

    @Test
    fun `help has no preset actions and bug report opens the existing GitHub template`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
        selectSettingsPage(activity, SettingsSection.HELP)
        assertNull(root(activity).findViewWithTag<View>("settings_page_actions"))
        assertNotNull(find(root(activity)) { it is TextView && it.text.toString() == activity.getString(R.string.report_bug_hint) })
        click(activity, R.string.report_bug)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(UpdateChecker.REPOSITORY_URL + "/issues/new", intent.data!!.buildUpon().clearQuery().build().toString())
        assertEquals("bug_report.yml", intent.data!!.getQueryParameter("template"))
    }
}
