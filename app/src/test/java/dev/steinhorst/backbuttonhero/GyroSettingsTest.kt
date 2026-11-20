package dev.steinhorst.backbuttonhero

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.support.selectSettingsPage
import dev.steinhorst.backbuttonhero.support.useEarlierDefaults
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class GyroSettingsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs by lazy { Prefs(context) }

    @Before
    fun earlierDefaults() = prefs.useEarlierDefaults()

    @After
    fun resetRoot() = setRoot(false)

    private fun setRoot(available: Boolean) {
        PService::class.java.getDeclaredField("checked").apply { isAccessible = true }.setBoolean(null, available)
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun open(): SettingsActivity = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get().also { idle() }

    private fun find(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), match)?.let { return it }
        return null
    }

    private fun click(root: View, text: String) {
        val label = checkNotNull(find(root) { it is TextView && it.text.toString() == text && it.isShown }) { "No view with text: $text" }
        generateSequence(label) { it.parent as? View }.first { it.isClickable }.performClick()
        idle()
    }

    private fun latest(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog

    private fun chooseAction(label: String) {
        val dialog = latest()
        val labels = (0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString().substringBefore('\n') }
        val index = labels.indexOf(label)
        dialog.listView.performItemClick(null, index, index.toLong())
        idle()
    }

    private fun content(activity: SettingsActivity): View = activity.findViewById(android.R.id.content)

    @Test
    fun `without the root helper the gyro cannot be chosen`() {
        val activity = open()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        val before = prefs.shoulderAction(ShoulderButton.L1)
        click(content(activity), activity.getString(R.string.shoulder_l1))
        chooseAction(activity.getString(R.string.shoulder_gyro))
        assertEquals(activity.getString(R.string.gyro_unavailable_title), shadowOf(latest()).title.toString())
        assertEquals(before, prefs.shoulderAction(ShoulderButton.L1))
    }

    @Test
    fun `the gyro hotkey's popup stores every change at once and resets to the defaults`() {
        setRoot(true)
        val activity = open()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        click(content(activity), activity.getString(R.string.shoulder_l1))
        chooseAction(activity.getString(R.string.shoulder_gyro))
        assertEquals(Prefs.SHOULDER_GYRO, prefs.shoulderAction(ShoulderButton.L1))
        val summary = "Right stick, Swing (yaw), 100 %"
        assertNotNull(find(content(activity)) { it is TextView && it.text.toString() == summary && it.isShown })
        click(content(activity), activity.getString(R.string.gyro_setup_title))
        val popupDialog = latest()
        val popup = popupDialog.window!!.decorView
        assertNotNull(find(popup) { it.contentDescription == activity.getString(R.string.gyro_preview_description) })
        assertNotNull(SwitcherService.gyroStatusListener)

        (find(popup) { it is RadioButton && it.text == activity.getString(R.string.gyro_stick_left) } as RadioButton).performClick()
        (find(popup) { it is RadioButton && it.text == activity.getString(R.string.gyro_axis_roll) } as RadioButton).performClick()
        (find(popup) { it is Switch && it.text == activity.getString(R.string.gyro_invert_x) } as Switch).performClick()
        (find(popup) { it is Switch && it.text == activity.getString(R.string.gyro_invert_y) } as Switch).performClick()
        assertEquals(GyroSettings(stick = Stick.LEFT, axis = GyroAxis.ROLL, invertX = true, invertY = true), prefs.gyro(ShoulderButton.L1))

        // Tilting by default; following turns is a switch of its own, and the deadzone's unit follows it.
        assertNotNull(find(popup) { it is TextView && it.text == activity.getString(R.string.gyro_rate_hint_off) })
        assertNotNull(find(popup) { it is TextView && it.text == activity.getString(R.string.gyro_degrees_tilt, 1.5f) })
        (find(popup) { it is Switch && it.text == activity.getString(R.string.gyro_rate) } as Switch).performClick()
        assertTrue(prefs.gyro(ShoulderButton.L1).rate)
        assertNotNull(find(popup) { it is TextView && it.text == activity.getString(R.string.gyro_rate_hint_on) })
        assertNotNull(find(popup) { it is TextView && it.text == activity.getString(R.string.gyro_degrees, 1.5f) })
        (find(popup) { it is Switch && it.text == activity.getString(R.string.gyro_rate) } as Switch).performClick()
        assertFalse(prefs.gyro(ShoulderButton.L1).rate)

        // Without a key the hotkey toggles; choosing one makes the gyro steer only while it is held.
        val passes = find(popup) { it is Switch && it.text == activity.getString(R.string.gyro_hold_passes) } as Switch
        assertFalse(passes.isShown)
        assertNotNull(find(popup) { it is TextView && it.text == activity.getString(R.string.gyro_hold_hint_none) })
        fun keyRow(name: String) = find(popup) { it.contentDescription == activity.getString(R.string.gyro_hold_key_accessibility, name) }!!
        keyRow(activity.getString(R.string.gyro_hold_none)).performClick()
        idle()
        assertFalse("nothing to clear yet", latest().getButton(AlertDialog.BUTTON_NEUTRAL).isShown)
        latest().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME))
        latest().dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L2))
        idle()
        assertEquals(KeyEvent.KEYCODE_BUTTON_L2, prefs.gyro(ShoulderButton.L1).holdKey)
        assertTrue(passes.isShown)
        assertNotNull(find(popup) { it is TextView && it.text == activity.getString(R.string.gyro_hold_hint_key, "L2") })
        passes.performClick()
        assertFalse(prefs.gyro(ShoulderButton.L1).holdPasses)
        // And the key can be taken away again.
        keyRow("L2").performClick()
        idle()
        latest().getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        assertEquals(0, prefs.gyro(ShoulderButton.L1).holdKey)
        assertFalse(passes.isShown)

        // Calibrating needs the sensor; the button only arms it here.
        click(popup, activity.getString(R.string.gyro_calibrate))
        popupDialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        assertEquals(GyroSettings(), prefs.gyro(ShoulderButton.L1))
        SwitcherService.gyroStatusListener!!(GyroStatus(ShoulderButton.L1.prefKey, true))
        popupDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(null, SwitcherService.gyroStatusListener)
        assertNotNull(find(content(activity)) { it is TextView && it.text.toString() == summary && it.isShown })
    }

    @Test
    fun `the summary names the hold key and the change preview lists the setup`() {
        prefs.setShoulderAction(ShoulderButton.R1, Prefs.SHOULDER_GYRO)
        prefs.setGyro(ShoulderButton.R1, GyroSettings(stick = Stick.LEFT, rate = true, holdKey = KeyEvent.KEYCODE_BUTTON_L2, sensitivity = 150))
        val activity = open()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        val summary = "Left stick, Swing (yaw), turning speed, 150 %, hold L2"
        assertNotNull(find(content(activity)) { it is TextView && it.text.toString() == summary && it.isShown })
        assertNotNull(find(content(activity)) {
            it.contentDescription == activity.getString(R.string.gyro_setup_accessibility, activity.getString(R.string.shoulder_r1), summary)
        })
    }

    @Test
    fun `narrower screens stack the columns, and sliders store what is dragged`() {
        listOf("+w700dp", "+w400dp").forEach { width ->
            RuntimeEnvironment.setQualifiers(width)
            val activity = open()
            var stored = GyroSettings()
            GyroSettingsDialog(activity, "l1", GyroSettings(), { _, _ -> }) { stored = it }.show {}
            val popup = latest().window!!.decorView
            val bar = find(popup) { it is SeekBar && it.contentDescription == activity.getString(R.string.gyro_header_sensitivity) } as SeekBar
            val listener = shadowOf(bar).onSeekBarChangeListener
            listener.onStartTrackingTouch(bar)
            listener.onProgressChanged(bar, 19, true)
            listener.onStopTrackingTouch(bar)
            assertEquals(200, stored.sensitivity)
            latest().dismiss()
            idle()
        }
    }

    private fun ballOf(preview: GyroPreview): Float = GyroPreview::class.java.getDeclaredField("x").apply { isAccessible = true }.getFloat(preview)

    @Test
    fun `the preview displays actual service output and requests recentering on tap`() {
        val preview = GyroPreview(context, GyroSettings(), "l1")
        preview.layout(0, 0, 400, 300)
        val canvas = Canvas(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888))
        listOf(GyroStatus(), GyroStatus("r1", true), GyroStatus("l1", false), GyroStatus(calibrating = true)).forEach {
            preview.status = it
            preview.draw(canvas)
            assertEquals(0f, ballOf(preview))
        }
        preview.status = GyroStatus("l1", true, -450, 200, 0.01f, 1)
        preview.draw(canvas)
        assertEquals(-0.45f, ballOf(preview))
        var centred = 0
        SwitcherService.gyroRecenter = { centred++ }
        val time = SystemClock.uptimeMillis()
        preview.onTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        preview.onTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_UP, 10f, 10f, 0))
        assertEquals(1, centred)
        assertEquals(0f, ballOf(preview))
        SwitcherService.gyroRecenter = null
        preview.recenter()
    }

    @Test
    fun `rate preview integrates each actual output sample once and mode switches centre it`() {
        val preview = GyroPreview(context, GyroSettings(rate = true), "l1")
        preview.status = GyroStatus("l1", true, -500, 0, 0.1f, 1)
        val rolled = ballOf(preview)
        assertTrue(rolled < 0f)
        preview.status = preview.status
        assertEquals(rolled, ballOf(preview))
        preview.status = GyroStatus("l1", true, 0, 0, 0.1f, 2)
        assertEquals(rolled, ballOf(preview))
        preview.status = GyroStatus("l1", false)
        assertEquals(rolled, ballOf(preview))
        preview.settings = GyroSettings()
        assertEquals(0f, ballOf(preview))
        preview.settings = GyroSettings(sensitivity = 200)
        assertEquals(GyroSettings(), GyroPreview(context).settings)
    }

    @Test
    fun `calibration is requested from the service and missing service is explained`() {
        val preview = GyroPreview(context)
        var requests = 0
        SwitcherService.gyroCalibrate = { requests++ }
        preview.calibrate()
        assertEquals(1, requests)
        SwitcherService.gyroCalibrate = null
        preview.calibrate()
        assertEquals(context.getString(R.string.gyro_service_needed), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }
}
