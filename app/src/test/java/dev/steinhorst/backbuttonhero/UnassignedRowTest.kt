package dev.steinhorst.backbuttonhero

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.support.selectSettingsPage
import dev.steinhorst.backbuttonhero.support.useEarlierDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A binding without an action has nothing to configure, so its card shows the action row alone. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class UnassignedRowTest {
    private val prefs = Prefs(ApplicationProvider.getApplicationContext())

    @Before
    fun earlierDefaults() = prefs.useEarlierDefaults()

    private fun shownClickables(root: View): List<View> = buildList {
        if (root.isShown && root.isClickable) add(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) addAll(shownClickables(root.getChildAt(i)))
    }

    @Test
    fun `an unassigned row shows no sub-options whatever action it had before`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible()
        val activity = controller.get()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        val root = activity.findViewById<View>(android.R.id.content)
        val actionRow = { checkNotNull(findRow(root, "L1 action:")) }
        val card = { actionRow().parent as View }
        val stray = mutableListOf<String>()
        (Prefs.KNOWN_ACTIONS + "com.example.game").forEach { action ->
            prefs.setShoulderAction(ShoulderButton.L1, action)
            controller.pause().resume()
            shadowOf(Looper.getMainLooper()).idle()
            prefs.setShoulderAction(ShoulderButton.L1, "")
            controller.pause().resume()
            shadowOf(Looper.getMainLooper()).idle()
            val extra = shownClickables(card()).filter { it !== actionRow() }
            if (extra.isNotEmpty()) stray += "$action -> ${extra.map { it.contentDescription ?: it.javaClass.simpleName }}"
        }
        assertEquals(emptyList<String>(), stray)
        // The check itself sees sub-options where an action has them.
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_SCREENSHOT)
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shownClickables(card()).any { it !== actionRow() })
    }

    @Test
    fun `the on-off shortcut shows its hold time only while a shortcut is chosen`() {
        prefs.toggleButtons = Prefs.TOGGLE_NONE
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible()
        val activity = controller.get()
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        val slider = { checkNotNull(find(activity.findViewById(android.R.id.content)) { it is SeekBar && it.max == 29 }) }
        assertFalse(slider().isShown)
        prefs.toggleButtons = Prefs.TOGGLE_L1_R1
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(slider().isShown)
    }

    private fun find(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), match)?.let { return it }
        return null
    }

    private fun findRow(root: View, prefix: String): View? {
        if (root.contentDescription?.toString()?.startsWith(prefix) == true) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findRow(root.getChildAt(i), prefix)?.let { return it }
        return null
    }
}
