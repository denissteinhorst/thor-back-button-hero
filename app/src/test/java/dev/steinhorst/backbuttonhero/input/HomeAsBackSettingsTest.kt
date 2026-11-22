package dev.steinhorst.backbuttonhero.input

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.SettingsActivity
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.settings.SettingsSection
import dev.steinhorst.backbuttonhero.support.selectSettingsPage
import dev.steinhorst.backbuttonhero.support.startOnBasic
import dev.steinhorst.backbuttonhero.support.useEarlierDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeAsBackSettingsTest {
    private val prefs = Prefs(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>())

    @org.junit.Before
    fun earlierDefaults() = prefs.useEarlierDefaults()

    private fun find(view: View, match: (View) -> Boolean): View? {
        if (match(view)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), match)?.let { return it }
        return null
    }

    private fun text(view: View, value: String) = find(view) { it is TextView && it.text.toString() == value }

    @Test
    fun `the option blocks the Back + Home row and keeps its binding`() {
        prefs.setShoulderAction(AndroidButton.HOME, Prefs.SHOULDER_SCREENSHOT)
        prefs.homeAsBack = true
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup(startOnBasic()).visible().get()
        val root = activity.findViewById<View>(android.R.id.content)
        val blocked = checkNotNull(text(root, activity.getString(R.string.home_as_back_combo_blocked)))
        val card = generateSequence(blocked) { it.parent as? View }.first { it.alpha == 0.5f }
        assertFalse(card.isEnabled)
        assertEquals(Prefs.SHOULDER_SCREENSHOT, prefs.shoulderAction(AndroidButton.HOME))

        selectSettingsPage(activity, SettingsSection.SETTINGS)
        val toggle = checkNotNull(text(root, activity.getString(R.string.home_as_back)))
        generateSequence(toggle) { it.parent as? View }.first { it.isClickable }.performClick()
        assertFalse(prefs.homeAsBack)
        assertNull(text(root, activity.getString(R.string.home_as_back_combo_blocked)))
        assertTrue(card.isEnabled)
        assertEquals(1f, card.alpha)
    }

    @Test
    fun `Back + Home as activation shortcut blocks the row with its own reason, ahead of the Home option`() {
        prefs.setShoulderAction(AndroidButton.HOME, Prefs.SHOULDER_SCREENSHOT)
        prefs.homeAsBack = true
        prefs.toggleButtons = Prefs.TOGGLE_BACK_HOME
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup(startOnBasic()).visible().get()
        val root = activity.findViewById<View>(android.R.id.content)
        val blocked = checkNotNull(text(root, activity.getString(R.string.toggle_combo_blocked)))
        assertNull(text(root, activity.getString(R.string.home_as_back_combo_blocked)))
        val card = generateSequence(blocked) { it.parent as? View }.first { it.alpha == 0.5f }
        assertFalse(card.isEnabled)
        assertEquals(Prefs.SHOULDER_SCREENSHOT, prefs.shoulderAction(AndroidButton.HOME))
    }
}
