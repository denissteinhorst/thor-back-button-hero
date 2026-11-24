package dev.steinhorst.backbuttonhero.settings

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.actions.Ayn
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.input.HomeLayerButton
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.prefs.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One card follows its own binding through action changes; its option rows come, go and come back in place. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class BindingCardTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val prefs = Prefs(activity)
    private val describer = BindingDescriber(activity, prefs)
    private val content = LinearLayout(activity).also(activity::setContentView)
    private val page = SettingsPage(activity, content)
    private val opened = mutableListOf<Pair<String, ComboButton>>()
    private val host = object : BindingCard.Host {
        override fun chooseAction(card: BindingCard) { opened += "action" to card.button }
        override fun chooseDisplay(card: BindingCard) { opened += "display" to card.button }
        override fun chooseTarget(card: BindingCard) { opened += "target" to card.button }
        override fun chooseOption(card: BindingCard) { opened += "option" to card.button }
        override fun choosePosition(card: BindingCard, record: Boolean) { opened += "position" to card.button }
        override fun choosePushToTalkKey(card: BindingCard) { opened += "ptt" to card.button }
        override fun chooseRecordDuck(card: BindingCard) { opened += "duck" to card.button }
        override fun chooseRecordQuality(card: BindingCard) { opened += "quality" to card.button }
        override fun chooseAppControl(card: BindingCard, mode: Boolean) { opened += "app" to card.button }
        override fun openMacroLibrary(card: BindingCard) { opened += "macro" to card.button }
        override fun openGyroSettings(card: BindingCard) { opened += "gyro" to card.button }
        override fun changed(card: BindingCard) = card.update()
    }

    private fun card(button: ComboButton) = BindingCard(page, button, R.string.shoulder_l1, null, activity, prefs, describer, host).also { it.update() }

    private fun shown(view: View?) = view?.visibility == View.VISIBLE

    @Test
    fun `an option row disappears with its action and returns for the next grouped one`() {
        val l1 = card(ShoulderButton.L1)
        val sibling = card(ShoulderButton.R1)
        val siblingText = sibling.actionRow.value.text.toString()

        prefs.setShoulderAction(ShoulderButton.L1, Ayn.Group.FAN.action)
        l1.update()
        val option = checkNotNull(l1.option)
        assertEquals(true, shown(option.row))
        assertEquals(activity.getString(R.string.ayn_option_title), option.title.text.toString())
        option.row.performClick()
        assertEquals("option" to ShoulderButton.L1, opened.last())

        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_SCREENSHOT)
        l1.update()
        assertEquals(false, shown(option.row))
        assertEquals(true, shown(l1.target.row))

        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_SWITCHER)
        l1.update()
        assertSame(option, l1.option)
        assertEquals(true, shown(option.row))
        assertEquals(false, shown(l1.target.row))
        assertEquals(activity.getString(R.string.level_target_title), option.title.text.toString())
        assertEquals(siblingText, sibling.actionRow.value.text.toString())
        assertEquals(null, sibling.option)
    }

    @Test
    fun `a Home layer card names its layer and opens choosers for its own binding`() {
        val home = card(HomeLayerButton.of(ShoulderButton.L1))
        assertEquals(activity.getString(R.string.settings_home_binding_label, activity.getString(R.string.shoulder_l1)), home.label)
        home.actionRow.row.performClick()
        assertEquals("action" to HomeLayerButton.of(ShoulderButton.L1), opened.last())
        prefs.setShoulderAction(HomeLayerButton.of(ShoulderButton.L1), Prefs.SHOULDER_SCREENSHOT)
        home.update()
        home.target.row.performClick()
        assertEquals("target" to HomeLayerButton.of(ShoulderButton.L1), opened.last())
        assertNotEquals("the Back layer keeps its own binding", Prefs.SHOULDER_SCREENSHOT, prefs.shoulderAction(ShoulderButton.L1))
    }
}
