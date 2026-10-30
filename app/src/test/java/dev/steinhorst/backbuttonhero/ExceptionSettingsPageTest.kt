package dev.steinhorst.backbuttonhero

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
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
        listOf(
            Triple("Alpha Emulator", "org.example.alpha", 0),
            Triple("Beta Game", "org.example.beta", 0),
            Triple("Gamma Service", "org.example.gamma", ApplicationInfo.FLAG_SYSTEM),
        ).forEach { (name, pkg, system) ->
            shadowOf(activity.packageManager).installPackage(PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    nonLocalizedLabel = name
                    flags = ApplicationInfo.FLAG_INSTALLED or system
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
    /** Picks the only search hit, then answers the start dialog: 0 allows everything, 1 blocks everything. */
    private fun add(activity: SettingsActivity, pkg: String, start: Int = 0) {
        val list = picker(activity, pkg)
        assertEquals(1, list.adapter.count)
        list.performItemClick(null, 0, 0)
        val choice = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        choice.listView.performItemClick(null, start, start.toLong())
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun box(activity: SettingsActivity, pkg: String, control: ExceptionControl) =
        root(activity).findViewWithTag<CheckBox>("exception_${pkg}_${control.ordinal}")
    private fun latestTitle() = shadowOf(ShadowAlertDialog.getLatestAlertDialog()).title.toString()

    @Test
    fun `app picker searches names and packages and allowing everything checks every box`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
        installApps(activity)
        selectSettingsPage(activity, SettingsSection.EXCEPTIONS)
        val list = picker(activity, "ALPHA EMULATOR")
        assertEquals(1, list.adapter.count)
        list.performItemClick(null, 0, 0)
        val choice = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertEquals("Alpha Emulator", shadowOf(choice).title.toString())
        choice.listView.performItemClick(null, 0, 0)
        ExceptionControl.entries.forEach { assertTrue(box(activity, "org.example.alpha", it).isChecked) }
        assertTrue(root(activity).findViewWithTag<CheckBox>("exception_all_org.example.alpha").isChecked)
        assertEquals(listOf(AppException("org.example.alpha", "Alpha Emulator")), Prefs(activity).appExceptions)
    }

    @Test
    fun `system apps stay hidden until shown or searched for`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
        installApps(activity)
        selectSettingsPage(activity, SettingsSection.EXCEPTIONS)
        val list = picker(activity, "")
        val decor = ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
        fun listed() = (0 until list.adapter.count).map { (list.adapter.getItem(it)).toString() }
        assertTrue(listed().none { "org.example.gamma" in it })
        assertTrue(listed().any { "org.example.alpha" in it })
        decor.findViewWithTag<Switch>("exceptions_show_system").isChecked = true
        assertTrue(listed().any { "org.example.gamma" in it })
        decor.findViewWithTag<Switch>("exceptions_show_system").isChecked = false
        decor.findViewWithTag<EditText>("exceptions_search").setText("gamma")
        assertEquals(1, list.adapter.count)
        assertTrue(listed().single().contains("org.example.gamma"))
    }

    @Test
    fun `blocking everything explains that Back and its combos reach the app untouched`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
        installApps(activity)
        selectSettingsPage(activity, SettingsSection.EXCEPTIONS)
        add(activity, "org.example.beta", start = 1)
        assertEquals(activity.getString(R.string.exceptions_native_title), latestTitle())
        val saved = Prefs(activity).appExceptions.single()
        assertEquals(0, saved.allowed)
        assertTrue(saved.backNative)
        ExceptionControl.entries.forEach { control ->
            assertFalse(box(activity, "org.example.beta", control).isChecked)
            assertEquals(control in ExceptionControl.BACK, box(activity, "org.example.beta", control).isEnabled)
        }
    }

    @Test
    fun `unchecking the last Back gesture greys the combos and explains why once`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
        installApps(activity)
        selectSettingsPage(activity, SettingsSection.EXCEPTIONS)
        add(activity, "org.example.alpha")
        val pkg = "org.example.alpha"
        listOf(ExceptionControl.SINGLE, ExceptionControl.DOUBLE, ExceptionControl.TAP_HOLD).forEach {
            box(activity, pkg, it).isChecked = false
        }
        assertTrue(box(activity, pkg, ExceptionControl.L1).isEnabled)
        val before = ShadowAlertDialog.getLatestAlertDialog()
        box(activity, pkg, ExceptionControl.HOLD).isChecked = false
        assertNotSame(before, ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(activity.getString(R.string.exceptions_native_title), latestTitle())
        assertFalse(box(activity, pkg, ExceptionControl.L1).isEnabled)
        assertTrue(box(activity, pkg, ExceptionControl.L1).isChecked)
        assertFalse(root(activity).findViewWithTag<CheckBox>("exception_all_$pkg").isChecked)
        assertFalse(Prefs(activity).appExceptions.single().allows(ExceptionControl.L1))
        box(activity, pkg, ExceptionControl.SINGLE).isChecked = true
        assertTrue(box(activity, pkg, ExceptionControl.L1).isEnabled)
        assertTrue(Prefs(activity).appExceptions.single().allows(ExceptionControl.L1))
    }

    @Test
    fun `the all box clears and restores a whole row`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
        installApps(activity)
        selectSettingsPage(activity, SettingsSection.EXCEPTIONS)
        add(activity, "org.example.alpha")
        val all = root(activity).findViewWithTag<CheckBox>("exception_all_org.example.alpha")
        all.isChecked = false
        assertEquals(0, Prefs(activity).appExceptions.single().allowed)
        ExceptionControl.entries.forEach { assertFalse(box(activity, "org.example.alpha", it).isChecked) }
        all.isChecked = true
        assertEquals(ExceptionControl.ALL, Prefs(activity).appExceptions.single().allowed)
        box(activity, "org.example.alpha", ExceptionControl.UP).isChecked = false
        assertFalse(all.isChecked)
    }

    @Test
    fun `switching an exception off keeps its boxes but disables them`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
        installApps(activity)
        selectSettingsPage(activity, SettingsSection.EXCEPTIONS)
        add(activity, "org.example.alpha")
        box(activity, "org.example.alpha", ExceptionControl.R2).isChecked = false
        root(activity).findViewWithTag<Switch>("exception_active_org.example.alpha").isChecked = false
        val saved = Prefs(activity).appExceptions.single()
        assertFalse(saved.active)
        assertNull(Prefs(activity).appException("org.example.alpha"))
        assertFalse(box(activity, "org.example.alpha", ExceptionControl.R2).isChecked)
        ExceptionControl.entries.forEach { assertFalse(box(activity, "org.example.alpha", it).isEnabled) }
        assertFalse(root(activity).findViewWithTag<CheckBox>("exception_all_org.example.alpha").isEnabled)
        root(activity).findViewWithTag<Switch>("exception_active_org.example.alpha").isChecked = true
        assertTrue(box(activity, "org.example.alpha", ExceptionControl.R2).isEnabled)
    }

    @Test
    fun `exceptions persist across navigation and recreation and the trash removes them`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible()
        var activity = controller.get()
        installApps(activity)
        selectSettingsPage(activity, SettingsSection.EXCEPTIONS)
        add(activity, "org.example.alpha")
        box(activity, "org.example.alpha", ExceptionControl.HOLD).isChecked = false
        selectSettingsPage(activity, SettingsSection.BASIC)
        selectSettingsPage(activity, SettingsSection.EXCEPTIONS)
        assertFalse(box(activity, "org.example.alpha", ExceptionControl.HOLD).isChecked)
        controller.recreate()
        activity = controller.get()
        assertFalse(box(activity, "org.example.alpha", ExceptionControl.HOLD).isChecked)
        root(activity).findViewWithTag<View>("exception_remove_org.example.alpha").performClick()
        var confirm = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertEquals(activity.getString(R.string.exceptions_remove_title), shadowOf(confirm).title.toString())
        confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("org.example.alpha"), Prefs(activity).appExceptions.map { it.packageName })
        root(activity).findViewWithTag<View>("exception_remove_org.example.alpha").performClick()
        confirm = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(box(activity, "org.example.alpha", ExceptionControl.HOLD))
        assertEquals(emptyList<AppException>(), Prefs(activity).appExceptions)
    }

    @Test
    fun `already added apps cannot be added twice and cancelling adds nothing`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
        installApps(activity)
        selectSettingsPage(activity, SettingsSection.EXCEPTIONS)
        add(activity, "org.example.alpha")
        var list = picker(activity, "org.example.alpha")
        assertEquals(0, list.adapter.count)
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        list = picker(activity, "org.example.beta")
        list.performItemClick(null, 0, 0)
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(box(activity, "org.example.alpha", ExceptionControl.SINGLE))
        assertNull(box(activity, "org.example.beta", ExceptionControl.SINGLE))
        assertEquals(listOf("org.example.alpha"), Prefs(activity).appExceptions.map { it.packageName })
    }

    @Test
    fun `remove all previews the apps and clears only exceptions`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
        installApps(activity)
        selectSettingsPage(activity, SettingsSection.EXCEPTIONS)
        add(activity, "org.example.alpha")
        add(activity, "org.example.beta")
        val before = Prefs(activity).snapshot()
        click(activity, R.string.exceptions_remove_all)
        val preview = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        val line = activity.getString(R.string.settings_change_line, activity.getString(R.string.exceptions_apps_title),
            "Alpha Emulator, Beta Game", activity.getString(R.string.exceptions_none))
        assertNotNull(find(preview.window!!.decorView) { it is TextView && it.text.toString() == line })
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before.copy(exceptions = emptyList()), Prefs(activity).snapshot())
        assertNull(box(activity, "org.example.alpha", ExceptionControl.SINGLE))
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
