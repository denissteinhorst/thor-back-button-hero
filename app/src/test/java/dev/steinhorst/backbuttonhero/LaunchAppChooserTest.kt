package dev.steinhorst.backbuttonhero

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.support.selectSettingsPage
import dev.steinhorst.backbuttonhero.support.useEarlierDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** Launch app's list: Ask and Favorites on top, then the starred apps, then all others. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class LaunchAppChooserTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = Prefs(context)

    @Before
    fun setUp() {
        prefs.useEarlierDefaults()
        listOf("Alpha Emulator" to "org.example.alpha", "Beta Game" to "org.example.beta", "Gamma Player" to "org.example.gamma").forEach { (label, pkg) ->
            shadowOf(context.packageManager).addResolveInfoForIntent(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                ResolveInfo().apply {
                    nonLocalizedLabel = label
                    activityInfo = ActivityInfo().apply {
                        packageName = pkg
                        name = "$pkg.Main"
                        applicationInfo = ApplicationInfo().apply { packageName = pkg }
                    }
                },
            )
        }
    }

    private fun find(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), match)?.let { return it }
        return null
    }

    private fun content(activity: SettingsActivity): View = activity.findViewById(android.R.id.content)

    private fun row(activity: SettingsActivity, label: String): View {
        var view = checkNotNull(find(content(activity)) { it is TextView && it.text.toString() == label })
        while (!view.isClickable) view = view.parent as View
        return view
    }

    /** Opens L1's action list, picks Launch app and returns the app list it leads to. */
    private fun openApps(activity: SettingsActivity): ViewGroup {
        row(activity, "L1").performClick()
        val chooser = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        val list = chooser.listView
        val index = (0 until list.adapter.count).first { list.adapter.getItem(it).toString().substringBefore('\n') == activity.getString(R.string.shoulder_launch_app) }
        list.performItemClick(null, index, index.toLong())
        shadowOf(Looper.getMainLooper()).idle()
        val apps = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        assertTrue(apps !== chooser)
        return find(apps.window!!.decorView) { it.tag == "settings_app_choice_${Prefs.SHOULDER_APP_ASK}" }!!.parent as ViewGroup
    }

    // This app is installed too and listed like any other; the fake apps are what the order is about.
    private fun choices(list: ViewGroup) = (0 until list.childCount)
        .mapNotNull { (list.getChildAt(it).tag as? String)?.removePrefix("settings_app_choice_") }
        .filter { it != context.packageName }

    private fun open(): SettingsActivity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get().also {
        shadowOf(Looper.getMainLooper()).idle()
        selectSettingsPage(it, SettingsSection.SHOULDERS)
    }

    @Test
    fun `ask and favorites lead, a star moves an app up the next time and the choice is stored`() {
        var activity = open()
        var list = openApps(activity)
        assertEquals(
            listOf(Prefs.SHOULDER_APP_ASK, Prefs.SHOULDER_APP_FAVORITES, "org.example.alpha", "org.example.beta", "org.example.gamma"),
            choices(list),
        )
        // Only apps carry a star; tapping it marks the app at once but keeps the list where it is.
        assertNull(find(list.getChildAt(0)) { it is ImageButton })
        val star = find(list) { it.tag == "settings_app_star_org.example.gamma" } as ImageButton
        assertEquals("Star Gamma Player as a favorite", star.contentDescription)
        star.performClick()
        assertEquals(setOf("org.example.gamma"), prefs.favoriteApps)
        assertEquals("Remove Gamma Player from favorites", star.contentDescription)
        assertEquals("org.example.gamma", choices(list).last())
        list.findViewWithTag<View>("settings_app_choice_${Prefs.SHOULDER_APP_FAVORITES}").performClick()
        assertEquals(Prefs.SHOULDER_APP_FAVORITES, prefs.shoulderAction(ShoulderButton.L1))
        assertEquals("Selected: Launch app: Favorites", (row(activity, "L1").let { find(it) { v -> v is TextView && v.text.startsWith("Selected: Launch app") } } as TextView).text.toString())
        // A chosen app opens on a screen, so the screen row shows for Ask and Favorites too.
        assertTrue(find(content(activity)) { it is TextView && it.text.toString() == activity.getString(R.string.level_target_bottom) }?.isShown == true)

        activity = open()
        list = openApps(activity)
        assertEquals(
            listOf(Prefs.SHOULDER_APP_ASK, Prefs.SHOULDER_APP_FAVORITES, "org.example.gamma", "org.example.alpha", "org.example.beta"),
            choices(list),
        )
        list.findViewWithTag<View>("settings_app_choice_${Prefs.SHOULDER_APP_ASK}").performClick()
        assertEquals(Prefs.SHOULDER_APP_ASK, prefs.shoulderAction(ShoulderButton.L1))
    }

    @Test
    fun `favorites without a starred app say so on the row`() {
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_APP_FAVORITES)
        val activity = open()
        assertTrue(find(content(activity)) { it is TextView && it.text.toString() == activity.getString(
            R.string.shoulder_action_selected, activity.getString(R.string.shoulder_launch_app_favorites_empty),
        ) } != null)
    }
}
