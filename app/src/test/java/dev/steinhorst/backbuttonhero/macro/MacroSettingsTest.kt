package dev.steinhorst.backbuttonhero.macro

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.MacroRecorderBridge
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.SettingsActivity
import dev.steinhorst.backbuttonhero.SettingsSection
import dev.steinhorst.backbuttonhero.SwitcherService
import dev.steinhorst.backbuttonhero.core.PService
import dev.steinhorst.backbuttonhero.input.BackGestureButton
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.input.DpadDirection
import dev.steinhorst.backbuttonhero.input.FaceButton
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.prefs.BindingSettings
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.prefs.SettingsBackup
import dev.steinhorst.backbuttonhero.prefs.SettingsChangeAction
import dev.steinhorst.backbuttonhero.prefs.SettingsChangePlan
import dev.steinhorst.backbuttonhero.prefs.SettingsState
import dev.steinhorst.backbuttonhero.support.selectSettingsPage
import dev.steinhorst.backbuttonhero.support.startOnBasic
import dev.steinhorst.backbuttonhero.support.useEarlierDefaults
import java.time.Instant
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class MacroSettingsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs by lazy { Prefs(context) }
    private val tap = Macro(listOf(MacroStep(listOf(MacroInput.Button(MacroButton.A)), 80, 120), MacroStep(listOf(MacroInput.Button(MacroButton.B)), 400, 0)))

    @Before
    fun earlierDefaults() = prefs.useEarlierDefaults()

    @After
    fun resetRoot() {
        setRoot(false)
        setBridge(null)
    }

    private fun setBridge(bridge: MacroRecorderBridge?) {
        SwitcherService::class.java.getDeclaredField("macroRecorder").apply { isAccessible = true }.set(null, bridge)
    }

    private fun setRoot(available: Boolean) {
        PService::class.java.getDeclaredField("checked").apply { isAccessible = true }.setBoolean(null, available)
    }

    private val hold = Macro(listOf(MacroStep(listOf(MacroInput.Button(MacroButton.X)), 900, 0)))

    /** Saves [macro] as [name] and lets [button] play it. */
    private fun bind(button: ComboButton, name: String = "Tap", macro: Macro = tap): SavedMacro =
        prefs.saveMacro(null, name, macro).also { prefs.setMacroRef(button, it.id) }

    @Test
    fun `hotkeys play library macros and only a bound one needs the helper`() {
        assertNull(prefs.macro(ShoulderButton.L1))
        val saved = bind(ShoulderButton.L1)
        assertEquals(SavedMacro("m1", "Tap", tap), saved)
        assertEquals(tap, prefs.macro(ShoulderButton.L1))
        assertFalse(prefs.hasMacroAction())
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_MACRO)
        assertTrue(prefs.hasMacroAction())
        prefs.setMacroRef(ShoulderButton.L1, null)
        assertFalse(prefs.hasMacroAction())
        assertEquals(listOf(saved), prefs.macroLibrary)
        // A reference to a macro the library does not hold plays nothing.
        prefs.setMacroRef(ShoulderButton.L1, "m9")
        assertNull(prefs.savedMacro(ShoulderButton.L1))
        // Back's own gestures never play macros, whatever is stored for them.
        prefs.setShoulderAction(BackGestureButton.SINGLE, Prefs.SHOULDER_MACRO)
        prefs.setMacroRef(BackGestureButton.SINGLE, saved.id)
        assertFalse(prefs.hasMacroAction())
        assertFalse(BackGestureButton.SINGLE in Prefs.MACRO_BUTTONS)
    }

    @Test
    fun `one library macro serves several hotkeys, and renaming, editing and deleting reach all of them`() {
        val saved = bind(ShoulderButton.L1)
        prefs.setMacroRef(FaceButton.X, saved.id)
        assertEquals(listOf(ShoulderButton.L1, FaceButton.X), prefs.macroUsers(saved.id))
        prefs.saveMacro(saved.id, "  Tap\ttwice  ", hold)
        assertEquals(SavedMacro("m1", "Taptwice", hold), prefs.savedMacro(FaceButton.X))
        prefs.renameMacro(saved.id, "Combo")
        prefs.renameMacro(saved.id, " \n ")
        assertEquals("Combo", prefs.savedMacro(ShoulderButton.L1)!!.name)
        // An unknown id saves a new macro; an empty name falls back to its id.
        val other = prefs.saveMacro("m7", "", tap)
        assertEquals(SavedMacro("m2", "m2", tap), other)
        prefs.deleteMacro(saved.id)
        assertEquals(listOf(other), prefs.macroLibrary)
        assertNull(prefs.savedMacro(ShoulderButton.L1))
        assertNull(prefs.savedMacro(FaceButton.X))
        assertTrue(prefs.macroUsers(saved.id).isEmpty())
    }

    @Test
    fun `snapshots carry the library and applying a plan writes it and the references`() {
        val saved = bind(FaceButton.X)
        val before = prefs.snapshot()
        assertEquals(listOf(saved), before.macros)
        assertEquals(saved.id, before.bindings.getValue(FaceButton.X.prefKey).macro)
        val added = SavedMacro("m2", "Hold", hold)
        val after = before.copy(macros = before.macros + added, bindings = before.bindings.mapValues { (key, value) ->
            when (key) {
                FaceButton.X.prefKey -> value.copy(macro = null)
                DpadDirection.UP.prefKey -> value.copy(action = Prefs.SHOULDER_MACRO, macro = added.id)
                else -> value
            }
        })
        assertTrue(prefs.applySettings(before, after))
        assertNull(prefs.macro(FaceButton.X))
        assertEquals(hold, prefs.macro(DpadDirection.UP))
        assertEquals(listOf(saved, added), prefs.macroLibrary)
    }

    @Test
    fun `resets and unbinding keep the library`() {
        bind(ShoulderButton.L1)
        val before = prefs.snapshot()
        listOf(SettingsChangeAction.PRESET, SettingsChangeAction.UNBIND).forEach { action ->
            val plan = SettingsChangePlan.create(before, null, action)
            assertEquals(before.macros, plan.after.macros)
            assertEquals(Prefs.SHOULDER_MACRO == plan.after.bindings.getValue(ShoulderButton.L1.prefKey).action, false)
        }
    }

    @Test
    fun `backups keep the library and references and drop damaged entries and macros on Back gestures`() {
        val saved = SavedMacro("m3", "Tap", tap)
        val state = SettingsState().let { factory ->
            factory.copy(macros = listOf(saved), bindings = factory.bindings.mapValues { (key, value) ->
                if (key == ShoulderButton.R1.prefKey) value.copy(action = Prefs.SHOULDER_MACRO, macro = saved.id) else value
            })
        }
        val text = SettingsBackup.write(state, "1.0.0", 1, Instant.EPOCH)
        val loaded = SettingsBackup.read(text) as SettingsBackup.Result.Loaded
        assertEquals(listOf(saved), loaded.state.macros)
        assertEquals(state.bindings.getValue(ShoulderButton.R1.prefKey), loaded.state.bindings.getValue(ShoulderButton.R1.prefKey))
        assertFalse(JSONObject(text).getJSONObject("settings").getJSONObject("bindings").getJSONObject("l1").has("macro"))

        val damaged = """{"format":"${SettingsBackup.FORMAT}","settings":{
            "macros":[{"id":"m1","name":"\u0007","steps":"${tap.encode()}"},{"id":"m1","name":"Again","steps":"${tap.encode()}"},
              {"id":"x1","name":"Bad id","steps":"${tap.encode()}"},{"id":"m2","name":"Bad steps","steps":"1:Home;20;0"},7],
            "bindings":{
            "r1":{"action":"@macro","macro":"m2"},
            "l1":{"action":"@macro","macro":"m1"},
            "back_single":{"action":"@macro","macro":"m1"}}}}"""
        val read = (SettingsBackup.read(damaged) as SettingsBackup.Result.Loaded).state
        assertEquals(listOf(SavedMacro("m1", "m1", tap)), read.macros)
        assertEquals(Prefs.SHOULDER_MACRO, read.bindings.getValue("r1").action)
        assertNull(read.bindings.getValue("r1").macro)
        assertEquals("m1", read.bindings.getValue("l1").macro)
        assertEquals(BindingSettings(BackGestureButton.SINGLE.defaultAction).action, read.bindings.getValue("back_single").action)
    }

    private fun open(): SettingsActivity = Robolectric.buildActivity(SettingsActivity::class.java).setup(startOnBasic()).visible().get().also {
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun find(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), match)?.let { return it }
        return null
    }

    private fun click(root: View, text: String) {
        val label = checkNotNull(find(root) { it is TextView && it.text.toString() == text && it.isShown }) { "No view with text: $text" }
        generateSequence(label) { it.parent as? View }.first { it.isClickable }.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun content(activity: SettingsActivity): View = activity.findViewById(android.R.id.content)

    private fun actionLabels(): List<String> {
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        return (0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString().substringBefore('\n') }
    }

    private fun chooseAction(label: String) {
        val dialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
        val index = actionLabels().indexOf(label)
        dialog.listView.performItemClick(null, index, index.toLong())
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun `Back gestures do not offer macros, combos do`() {
        val activity = open()
        click(content(activity), activity.getString(R.string.back_single_row))
        assertFalse(activity.getString(R.string.shoulder_macro) in actionLabels())
        (ShadowAlertDialog.getLatestAlertDialog() as AlertDialog).dismiss()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        click(content(activity), activity.getString(R.string.shoulder_l1))
        assertTrue(activity.getString(R.string.shoulder_macro) in actionLabels())
    }

    @Test
    fun `without the root helper a macro cannot be chosen`() {
        val activity = open()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        val before = prefs.shoulderAction(ShoulderButton.L1)
        click(content(activity), activity.getString(R.string.shoulder_l1))
        chooseAction(activity.getString(R.string.shoulder_macro))
        assertEquals(activity.getString(R.string.macro_unavailable_title), shadowOf(ShadowAlertDialog.getLatestAlertDialog()).title.toString())
        assertEquals(before, prefs.shoulderAction(ShoulderButton.L1))
    }

    private val three = Macro(listOf(
        MacroStep(listOf(MacroInput.Button(MacroButton.A)), 80, 120),
        MacroStep(listOf(MacroInput.Button(MacroButton.B)), 400, 300),
        MacroStep(listOf(MacroInput.Button(MacroButton.X)), 60, 0),
    ))

    private fun latest(): AlertDialog = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog

    private fun latestDialog(): View = ShadowDialog.getLatestDialog().window!!.decorView

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun nameAndSave(name: String?, button: Int = AlertDialog.BUTTON_POSITIVE) {
        val dialog = latest()
        name?.let { (find(dialog.window!!.decorView) { v -> v is EditText } as EditText).setText(it) }
        dialog.getButton(button).performClick()
        idle()
    }

    /** Clicks the view described as [description]. */
    private fun tap(root: View, description: String) {
        checkNotNull(find(root) { it.contentDescription?.toString() == description && it.isShown }) { "No view described as: $description" }.performClick()
        idle()
    }

    private fun descriptions(root: View, prefix: String): List<String> = buildList {
        fun walk(view: View) {
            view.contentDescription?.toString()?.takeIf { it.startsWith(prefix) && view.isShown }?.let(::add)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
    }

    private fun menuItem(title: String) {
        val popup = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu()
        val item = (0 until popup.menu.size()).map { popup.menu.getItem(it) }.first { it.title.toString() == title }
        shadowOf(popup).onMenuItemClickListener.onMenuItemClick(item)
        idle()
    }

    private fun openEditor(activity: SettingsActivity): Dialog {
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        val list = openLibrary(activity)
        find(list) { it.contentDescription == activity.getString(R.string.macro_options, prefs.savedMacro(ShoulderButton.L1)!!.name) }!!.performClick()
        menuItem(activity.getString(R.string.macro_edit))
        return ShadowDialog.getLatestDialog()
    }

    /** An editor of [initial] outside the settings screen, collecting what it saves. */
    private fun editor(activity: SettingsActivity, initial: SavedMacro?, saved: MutableList<Triple<String?, String, Macro?>>): View =
        MacroEditor(activity, "L1", initial, "Macro L1", { prefs.macroLibrary }, { false }) { id, name, macro -> saved += Triple(id, name, macro) }
            .show().window!!.decorView

    @Test
    fun `choosing a macro opens the editor, which explains recording until something is recorded`() {
        setRoot(true)
        val activity = open()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        click(content(activity), activity.getString(R.string.shoulder_l1))
        chooseAction(activity.getString(R.string.shoulder_macro))
        assertEquals(Prefs.SHOULDER_MACRO, prefs.shoulderAction(ShoulderButton.L1))
        val editor = ShadowDialog.getLatestDialog()
        val root = editor.window!!.decorView
        assertNotNull(find(root) { it is TextView && it.text.toString() == activity.getString(R.string.macro_empty_title) && it.isShown })
        // Nothing to save yet, and nothing to lose: closing asks nothing.
        assertFalse(find(root) { it is TextView && it.text.toString() == activity.getString(R.string.macro_save) }!!.isEnabled)
        tap(root, activity.getString(R.string.macro_close))
        assertFalse(editor.isShowing)
        assertNull(prefs.macro(ShoulderButton.L1))
        assertNotNull(find(content(activity)) { it is TextView && it.text.toString() == activity.getString(R.string.macro_not_recorded) && it.isShown })
    }

    @Test
    fun `a step sheet changes the hold by steps, presets and typing, and moves, duplicates and deletes the step`() {
        setRoot(true)
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_MACRO)
        bind(ShoulderButton.L1)
        val activity = open()
        val root = openEditor(activity).window!!.decorView
        tap(root, activity.getString(R.string.macro_step_accessibility, 1, "A", "Tap 80 ms"))
        val sheet = latestDialog()
        tap(sheet, activity.getString(R.string.macro_longer))
        assertEquals(listOf("Step 1: A, Tap 130 ms"), descriptions(root, "Step 1:"))
        tap(sheet, activity.getString(R.string.macro_shorter))
        tap(sheet, activity.getString(R.string.macro_preset_label, activity.getString(R.string.macro_preset_long), "1000 ms"))
        assertTrue(find(sheet) { it.contentDescription == activity.getString(R.string.macro_preset_label, activity.getString(R.string.macro_preset_long), "1000 ms") }!!.isSelected)
        assertEquals(listOf("Step 1: A, Hold 1000 ms"), descriptions(root, "Step 1:"))
        tap(sheet, activity.getString(R.string.macro_exact_accessibility, "1000 ms"))
        find(latest().window!!.decorView) { it is EditText }.let { (it as EditText).setText("5") }
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(listOf("Step 1: A, Tap 20 ms, may be too short for some games"), descriptions(root, "Step 1:"))
        // A press some games may miss is flagged on the card and explained on the sheet.
        assertNotNull(find(sheet) { it is TextView && it.text.toString() == "${MacroEditor.WARNING_SIGN} ${activity.getString(R.string.macro_short_hold_hint)}" })
        assertNotNull(find(root) { it is TextView && it.text.toString() == "${MacroEditor.WARNING_SIGN} 20 ms" })

        // A hold that would make the macro too long is refused.
        tap(sheet, activity.getString(R.string.macro_exact_accessibility, "20 ms"))
        find(latest().window!!.decorView) { it is EditText }.let { (it as EditText).setText("abc") }
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()

        tap(sheet, activity.getString(R.string.macro_move_later))
        assertEquals(listOf("Step 1: B, Hold 400 ms", "Step 2: A, Tap 20 ms, may be too short for some games"), descriptions(root, "Step "))
        assertNotNull(find(sheet) { it is TextView && it.text.toString() == activity.getString(R.string.macro_step_title, 2, 2) })
        // ‹ and › walk through the steps without closing the sheet; there is nothing past either end.
        assertFalse(find(sheet) { it.contentDescription == activity.getString(R.string.macro_step_next) }!!.isEnabled)
        tap(sheet, activity.getString(R.string.macro_step_previous))
        assertNotNull(find(sheet) { it is TextView && it.text.toString() == activity.getString(R.string.macro_step_title, 1, 2) })
        assertFalse(find(sheet) { it.contentDescription == activity.getString(R.string.macro_step_previous) }!!.isEnabled)
        tap(sheet, activity.getString(R.string.macro_step_next))
        assertFalse(find(sheet) { it.contentDescription == activity.getString(R.string.macro_move_later) }!!.isEnabled)
        tap(sheet, activity.getString(R.string.macro_move_earlier))
        tap(sheet, activity.getString(R.string.macro_duplicate))
        assertEquals(listOf("Step 1: A, Tap 20 ms, may be too short for some games", "Step 2: A, Tap 20 ms, may be too short for some games", "Step 3: B, Hold 400 ms"), descriptions(root, "Step "))
        // The original keeps its pause before the copy; the copy takes over the one to B.
        assertEquals(listOf("Pause of 120 ms before step 2", "Pause of 120 ms before step 3"), descriptions(root, "Pause"))

        tap(sheet, activity.getString(R.string.macro_delete))
        assertEquals(2, descriptions(root, "Step ").size)
        assertNotNull(find(root) { it is TextView && it.text.toString() == activity.getString(R.string.macro_step_deleted) && it.isShown })
        click(root, activity.getString(R.string.macro_undo))
        assertEquals(3, descriptions(root, "Step ").size)
        assertNull(find(root) { it is TextView && it.text.toString() == activity.getString(R.string.macro_step_deleted) && it.isShown })

        // An edited library macro saves straight away, under its name.
        click(root, activity.getString(R.string.macro_save))
        assertEquals(listOf(20, 20, 400), prefs.macro(ShoulderButton.L1)!!.steps.map { it.holdMs })
        assertEquals("Tap", prefs.savedMacro(ShoulderButton.L1)!!.name)
    }

    @Test
    fun `pauses change one at a time or all at once`() {
        setRoot(true)
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_MACRO)
        bind(ShoulderButton.L1, macro = three)
        val activity = open()
        val root = openEditor(activity).window!!.decorView
        tap(root, activity.getString(R.string.macro_gap_accessibility, 120, 2))
        var sheet = latestDialog()
        click(sheet, activity.getString(R.string.macro_preset_none))
        assertEquals("Pause of 0 ms before step 2, may be too short for some games", descriptions(root, "Pause").first())
        assertNotNull(find(sheet) { it is TextView && it.text.toString() == "${MacroEditor.WARNING_SIGN} ${activity.getString(R.string.macro_short_gap_hint)}" })
        assertNotNull(find(root) { it is TextView && it.text.toString() == "${MacroEditor.WARNING_SIGN} 0 ms" })
        tap(sheet, activity.getString(R.string.macro_longer))
        assertEquals(listOf("Pause of 50 ms before step 2", "Pause of 300 ms before step 3"), descriptions(root, "Pause"))
        click(sheet, activity.getString(R.string.macro_apply_all))
        assertEquals(listOf("Pause of 50 ms before step 2", "Pause of 50 ms before step 3"), descriptions(root, "Pause"))

        tap(root, activity.getString(R.string.macro_more))
        menuItem(activity.getString(R.string.macro_all_pauses))
        sheet = latestDialog()
        assertNotNull(find(sheet) { it is TextView && it.text.toString() == activity.getString(R.string.macro_all_pauses_title) })
        click(sheet, "250 ms")
        assertEquals(listOf("Pause of 250 ms before step 2", "Pause of 250 ms before step 3"), descriptions(root, "Pause"))
        click(sheet, activity.getString(R.string.macro_done))
        click(root, activity.getString(R.string.macro_save))
        assertEquals(listOf(250, 250, 0), prefs.macro(ShoulderButton.L1)!!.steps.map { it.gapMs })
    }

    @Test
    fun `steps are added by hand and their buttons changed from the palette`() {
        setRoot(true)
        val activity = open()
        val saved = mutableListOf<Triple<String?, String, Macro?>>()
        val root = editor(activity, null, saved)
        click(root, activity.getString(R.string.macro_add_manual))
        var palette = latestDialog()
        val add = find(palette) { it is TextView && it.text.toString() == activity.getString(R.string.macro_add) }!!
        assertFalse(add.isEnabled)
        tap(palette, "A")
        tap(palette, "D-pad up")
        // A second D-pad direction replaces the first; the D-pad has one direction at a time.
        tap(palette, "D-pad down")
        assertNotNull(find(palette) { it.contentDescription == "A, selected" })
        assertNotNull(find(palette) { it.contentDescription == "D-pad down, selected" })
        assertNotNull(find(palette) { it.contentDescription == "D-pad up" })
        click(palette, activity.getString(R.string.macro_add))
        assertEquals(listOf("Step 1: A, D-pad down, Tap 80 ms"), descriptions(root, "Step "))

        tap(root, activity.getString(R.string.macro_add_manual))
        palette = latestDialog()
        tap(palette, "Left stick up right")
        click(palette, activity.getString(R.string.macro_add))
        assertEquals(listOf("Pause of 100 ms before step 2"), descriptions(root, "Pause"))

        tap(root, "Step 1: A, D-pad down, Tap 80 ms")
        // The large step card opens the palette just like the button under it.
        tap(latestDialog(), activity.getString(R.string.macro_change))
        assertNotNull(find(latestDialog()) { it is TextView && it.text.toString() == activity.getString(R.string.macro_apply) })
        ShadowDialog.getLatestDialog().dismiss()
        idle()
        tap(root, "Step 1: A, D-pad down, Tap 80 ms")
        click(latestDialog(), activity.getString(R.string.macro_change))
        palette = latestDialog()
        tap(palette, "A, selected")
        click(palette, activity.getString(R.string.macro_apply))
        assertEquals("Step 1: D-pad down, Tap 80 ms", descriptions(root, "Step ").first())

        // A new macro is named when it is first saved.
        click(root, activity.getString(R.string.macro_save))
        assertEquals("Macro L1", (find(latest().window!!.decorView) { it is EditText } as EditText).text.toString())
        nameAndSave("Combo")
        assertEquals("Combo", saved.single().second)
        assertEquals(2, saved.single().third!!.steps.size)
    }

    private fun motion(action: Int, x: Float, y: Float): android.view.MotionEvent =
        android.view.MotionEvent.obtain(0, 0, action, x, y, 0)

    @Test
    fun `dragging a step moves it, dropping it on the bin deletes it, and undo brings it back`() {
        setRoot(true)
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_MACRO)
        bind(ShoulderButton.L1, macro = three)
        val activity = open()
        val root = openEditor(activity).window!!.decorView
        val strip = find(root) { it is StepStrip } as StepStrip
        fun drag(index: Int, toX: Float, dropAt: (() -> Pair<Float, Float>)? = null) {
            val card = strip.stepViews[index]
            val y = (card.top + card.bottom) / 2f
            strip.dispatchTouchEvent(motion(android.view.MotionEvent.ACTION_DOWN, (card.left + card.right) / 2f, y))
            card.performLongClick()
            idle()
            assertTrue(strip.dragging)
            assertNotNull(find(root) { it is TextView && it.text.toString() == activity.getString(R.string.macro_drop_delete) && it.isShown })
            strip.dispatchTouchEvent(motion(android.view.MotionEvent.ACTION_MOVE, toX, y))
            strip.draw(android.graphics.Canvas(android.graphics.Bitmap.createBitmap(maxOf(1, strip.width), maxOf(1, strip.height), android.graphics.Bitmap.Config.ARGB_8888)))
            val (x, rawY) = dropAt?.invoke() ?: (toX to y)
            strip.dispatchTouchEvent(motion(android.view.MotionEvent.ACTION_UP, x, rawY))
            idle()
            assertFalse(strip.dragging)
        }
        drag(0, strip.stepViews.last().right + 10f)
        assertEquals(listOf("Step 1: B, Hold 400 ms", "Step 2: X, Tap 60 ms", "Step 3: A, Tap 80 ms"), descriptions(root, "Step "))
        // The pauses stay in their places; only the presses changed order.
        assertEquals(listOf("Pause of 120 ms before step 2", "Pause of 300 ms before step 3"), descriptions(root, "Pause"))

        drag(1, 0f) {
            val bin = find(root) { it is TextView && it.text.toString() == activity.getString(R.string.macro_drop_delete) }!!
            val at = IntArray(2).also { bin.getLocationOnScreen(it) }
            (at[0] + bin.width / 2f) to (at[1] + bin.height / 2f)
        }
        assertEquals(listOf("Step 1: B, Hold 400 ms", "Step 2: A, Tap 80 ms"), descriptions(root, "Step "))
        tap(root, activity.getString(R.string.macro_undo))
        assertEquals(3, descriptions(root, "Step ").size)
        tap(root, activity.getString(R.string.macro_undo))
        assertEquals(listOf("Step 1: A, Tap 80 ms", "Step 2: B, Hold 400 ms", "Step 3: X, Tap 60 ms"), descriptions(root, "Step "))
        assertFalse(find(root) { it.contentDescription == activity.getString(R.string.macro_undo) }!!.isEnabled)

        // A drag cut off by the system changes nothing.
        val card = strip.stepViews[0]
        strip.dispatchTouchEvent(motion(android.view.MotionEvent.ACTION_DOWN, card.left + 1f, card.top + 1f))
        card.performLongClick()
        strip.dispatchTouchEvent(motion(android.view.MotionEvent.ACTION_CANCEL, 0f, 0f))
        assertFalse(strip.dragging)
        assertEquals(3, descriptions(root, "Step ").size)
    }

    @Test
    fun `the preview lights the steps up one after another and can be stopped`() {
        setRoot(true)
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_MACRO)
        bind(ShoulderButton.L1)
        val activity = open()
        val root = openEditor(activity).window!!.decorView
        val looper = shadowOf(android.os.Looper.getMainLooper())
        val first = find(root) { it.contentDescription == "Step 1: A, Tap 80 ms" } as ViewGroup
        tap(root, activity.getString(R.string.macro_preview))
        assertTrue(first.getChildAt(0).scaleX > 1f)
        looper.idleFor(java.time.Duration.ofMillis(100))
        assertEquals(1f, first.getChildAt(0).scaleX)
        looper.idleFor(java.time.Duration.ofMillis(1000))
        assertNotNull(find(root) { it.contentDescription == activity.getString(R.string.macro_preview) })

        tap(root, activity.getString(R.string.macro_preview))
        tap(root, activity.getString(R.string.macro_preview_stop))
        assertEquals(1f, first.getChildAt(0).scaleX)
        assertNotNull(find(root) { it.contentDescription == activity.getString(R.string.macro_preview) })
    }

    @Test
    fun `the name is changed in the header, and Save as new keeps the original for other hotkeys`() {
        setRoot(true)
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_MACRO)
        val shared = bind(ShoulderButton.L1)
        prefs.setMacroRef(FaceButton.X, shared.id)
        val activity = open()
        var root = openEditor(activity).window!!.decorView
        tap(root, activity.getString(R.string.macro_name_accessibility, "Tap", "Macro: L1"))
        assertEquals(activity.getString(R.string.macro_rename_title), shadowOf(latest()).title.toString())
        nameAndSave("Combo")
        click(root, activity.getString(R.string.macro_save))
        assertEquals("Combo", prefs.savedMacro(FaceButton.X)!!.name)

        root = openEditor(activity).window!!.decorView
        tap(root, "Step 2: B, Hold 400 ms")
        tap(latestDialog(), activity.getString(R.string.macro_delete))
        tap(root, activity.getString(R.string.macro_more))
        menuItem(activity.getString(R.string.macro_save_as_new))
        assertEquals("Combo (2)", (find(latest().window!!.decorView) { it is EditText } as EditText).text.toString())
        nameAndSave(null)
        assertEquals("Combo (2)", prefs.savedMacro(ShoulderButton.L1)!!.name)
        assertEquals(1, prefs.macro(ShoulderButton.L1)!!.steps.size)
        assertEquals(2, prefs.macro(FaceButton.X)!!.steps.size)
    }

    @Test
    fun `closing and loading ask before they drop changes`() {
        setRoot(true)
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_MACRO)
        bind(ShoulderButton.L1)
        val other = prefs.saveMacro(null, "Hold", hold)
        val activity = open()
        val editor = openEditor(activity)
        val root = editor.window!!.decorView
        tap(root, "Step 2: B, Hold 400 ms")
        tap(latestDialog(), activity.getString(R.string.macro_delete))
        tap(root, activity.getString(R.string.macro_close))
        assertEquals(activity.getString(R.string.macro_discard_title), shadowOf(latest()).title.toString())
        latest().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue(editor.isShowing)

        tap(root, activity.getString(R.string.macro_more))
        menuItem(activity.getString(R.string.macro_load_title))
        assertEquals(activity.getString(R.string.macro_discard_title), shadowOf(latest()).title.toString())
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        latest().listView.performItemClick(null, 1, 1)
        idle()
        assertNotNull(find(root) { it is TextView && it.text.toString() == other.name && it.isShown })
        assertEquals(listOf("Step 1: X, Hold 900 ms"), descriptions(root, "Step "))
        tap(root, activity.getString(R.string.macro_close))
        assertFalse(editor.isShowing)
        assertEquals(tap, prefs.macro(ShoulderButton.L1))
    }

    @Test
    fun `an empty editor offers saved macros, and clearing and saving unassigns the hotkey`() {
        setRoot(true)
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_MACRO)
        val shared = bind(ShoulderButton.L1)
        val activity = open()
        val saved = mutableListOf<Triple<String?, String, Macro?>>()
        val fresh = editor(activity, null, saved)
        click(fresh, activity.getString(R.string.macro_load_title))
        latest().listView.performItemClick(null, 0, 0)
        idle()
        assertEquals(2, descriptions(fresh, "Step ").size)

        val root = openEditor(activity).window!!.decorView
        tap(root, activity.getString(R.string.macro_more))
        menuItem(activity.getString(R.string.macro_clear_all))
        assertNotNull(find(root) { it is TextView && it.text.toString() == activity.getString(R.string.macro_cleared) && it.isShown })
        click(root, activity.getString(R.string.macro_save))
        assertNull(prefs.savedMacro(ShoulderButton.L1))
        assertEquals(listOf(shared), prefs.macroLibrary)
    }

    private class FakeBridge : MacroRecorderBridge {
        var input: ((List<String>) -> Unit)? = null
        override fun start(onInput: (List<String>) -> Unit) { input = onInput }
        override fun stop() { input = null }
        override val ready = true
    }

    @Test
    fun `recording adds to the end, shows the steps live and can be undone`() {
        setRoot(true)
        val bridge = FakeBridge()
        setBridge(bridge)
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_MACRO)
        bind(ShoulderButton.L1)
        val activity = open()
        val root = openEditor(activity).window!!.decorView
        val looper = shadowOf(android.os.Looper.getMainLooper())
        click(root, activity.getString(R.string.macro_record))
        assertEquals(2, descriptions(root, "Step ").size)
        fun send(vararg lines: String) = lines.forEach { bridge.input!!(it.split(' ')) }

        send("5000 K ${Evdev.BTN_A} 1", "5000 S")
        looper.idleFor(java.time.Duration.ofMillis(500))
        val held = checkNotNull(find(root) { it.contentDescription?.startsWith("Step 3: A, Hold") == true && it.isShown })
        assertFalse(held.isClickable)
        assertEquals(listOf("Pause of 120 ms before step 2", "Pause of 100 ms before step 3"), descriptions(root, "Pause").take(2))

        send("5500 K ${Evdev.BTN_A} 0", "5500 S")
        looper.idleFor(java.time.Duration.ofMillis(400))
        // The pause after the last release keeps counting until the next press.
        val resting = checkNotNull(find(root) { it.contentDescription?.toString()?.matches(Regex("Pause of \\d+ ms before step 4")) == true })
        assertTrue(resting.contentDescription.toString().removePrefix("Pause of ").substringBefore(" ").toInt() >= 300)

        click(root, activity.getString(R.string.macro_stop))
        assertNull(bridge.input)
        assertEquals(3, descriptions(root, "Step ").size)
        assertTrue(find(root) { it.contentDescription?.startsWith("Step 3: A") == true }!!.isClickable)
        tap(root, activity.getString(R.string.macro_undo))
        assertEquals(2, descriptions(root, "Step ").size)

        // Stopping before anything was pressed keeps the macro as it was.
        click(root, activity.getString(R.string.macro_record))
        click(root, activity.getString(R.string.macro_stop))
        assertEquals(2, descriptions(root, "Step ").size)
    }

    private fun openLibrary(activity: SettingsActivity): View {
        click(content(activity), activity.getString(R.string.macro_library_title))
        return latest().window!!.decorView
    }

    @Test
    fun `the saved macros list chooses a macro or starts a new one`() {
        setRoot(true)
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_MACRO)
        val first = bind(ShoulderButton.L1)
        val second = prefs.saveMacro(null, "Hold", hold)
        val activity = open()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        var list = openLibrary(activity)
        assertNotNull(find(list) { it.contentDescription == activity.getString(R.string.macro_library_current, first.name, MacroEditor.summary(activity, tap)) })
        click(list, second.name)
        assertEquals(second, prefs.savedMacro(ShoulderButton.L1))
        assertNotNull(find(content(activity)) { it is TextView && it.text.toString() == MacroEditor.macroLine(activity, second) && it.isShown })
        // The one sub-option row both picks and records, and names the macro the hotkey plays.
        val label = activity.getString(R.string.shoulder_l1)
        assertNotNull(find(content(activity)) { it.contentDescription == activity.getString(R.string.macro_library_accessibility, label, MacroEditor.macroLine(activity, second)) })

        list = openLibrary(activity)
        click(list, activity.getString(R.string.macro_new))
        val editor = ShadowDialog.getLatestDialog().window!!.decorView
        assertNotNull(find(editor) { it is TextView && it.text.toString() == activity.getString(R.string.macro_empty_title) && it.isShown })
    }

    @Test
    fun `the saved macros list renames and deletes, asking first and naming how many hotkeys still play it`() {
        setRoot(true)
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_MACRO)
        val used = bind(ShoulderButton.L1)
        val spare = prefs.saveMacro(null, "Spare", hold)
        val activity = open()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        val list = openLibrary(activity)
        fun menu(name: String, item: Int) {
            find(list) { it.contentDescription == activity.getString(R.string.macro_options, name) }!!.performClick()
            val popup = org.robolectric.shadows.ShadowPopupMenu.getLatestPopupMenu()
            shadowOf(popup).onMenuItemClickListener.onMenuItemClick(popup.menu.getItem(item))
            idle()
        }
        menu(spare.name, 1)
        assertEquals(activity.getString(R.string.macro_rename_title), shadowOf(latest()).title.toString())
        nameAndSave("Kept")
        assertEquals("Kept", prefs.macroLibrary.last().name)
        assertNotNull(find(list) { it is TextView && it.text.toString() == "Kept" })

        menu("Kept", 2)
        assertEquals(listOf("Tap", "Kept", "Kept (2)"), prefs.macroLibrary.map { it.name })
        menu("Kept (2)", 3)
        assertEquals(activity.getString(R.string.macro_delete_body), shadowOf(latest()).message.toString())
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        menu("Kept", 3)
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(listOf(used), prefs.macroLibrary)

        menu(used.name, 3)
        assertEquals(activity.resources.getQuantityString(R.plurals.macro_delete_used, 1, 1), shadowOf(latest()).message.toString())
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertTrue(prefs.macroLibrary.isEmpty())
        assertNull(prefs.savedMacro(ShoulderButton.L1))
        assertNotNull(find(list) { it is TextView && it.text.toString() == activity.getString(R.string.macro_library_empty) })
        assertNotNull(find(content(activity)) { it is TextView && it.text.toString() == activity.getString(R.string.macro_not_recorded) && it.isShown })
    }

    @Test
    fun `Edit in the saved macros list opens that macro and keeps the hotkey's own choice`() {
        setRoot(true)
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_MACRO)
        val used = bind(ShoulderButton.L1)
        val spare = prefs.saveMacro(null, "Spare", hold)
        val activity = open()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        val list = openLibrary(activity)
        find(list) { it.contentDescription == activity.getString(R.string.macro_options, spare.name) }!!.performClick()
        menuItem(activity.getString(R.string.macro_edit))
        val root = ShadowDialog.getLatestDialog().window!!.decorView
        tap(root, activity.getString(R.string.macro_name_accessibility, "Spare", "Macro: L1"))
        nameAndSave("Edited")
        click(root, activity.getString(R.string.macro_save))
        assertEquals(listOf("Tap", "Edited"), prefs.macroLibrary.map { it.name })
        assertEquals(hold, prefs.macroLibrary.last().macro)
        assertEquals(used, prefs.savedMacro(ShoulderButton.L1))
    }

    @Test
    fun `the scroll indicator shows only while the row scrolls, with a thumb for the part in view`() {
        val activity = open()
        val indicator = ScrollIndicator(activity).apply { setColors(Color.GRAY, Color.WHITE) }
        indicator.layout(0, 0, 100, 4)
        assertEquals(View.INVISIBLE, indicator.visibility)
        indicator.update(0, 500, 400)
        assertEquals(View.INVISIBLE, indicator.visibility)
        indicator.update(0, 0, 400)
        assertEquals(View.INVISIBLE, indicator.visibility)
        val drawn = mutableListOf<Pair<Int, android.graphics.RectF>>()
        val canvas = object : android.graphics.Canvas() {
            override fun drawRoundRect(rect: android.graphics.RectF, rx: Float, ry: Float, paint: android.graphics.Paint) {
                drawn += paint.color to android.graphics.RectF(rect)
            }
        }
        indicator.update(300, 400, 1000)
        assertEquals(View.VISIBLE, indicator.visibility)
        indicator.draw(canvas)
        // Scrolled half way: the 40 px thumb sits in the middle of the 100 px track.
        assertEquals(listOf(Color.GRAY to android.graphics.RectF(0f, 0f, 100f, 4f), Color.WHITE to android.graphics.RectF(30f, 0f, 70f, 4f)), drawn)
    }

    @Test
    fun `choosing a macro for a hotkey with saved macros offers them first`() {
        setRoot(true)
        prefs.saveMacro(null, "Tap", tap)
        val activity = open()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        click(content(activity), activity.getString(R.string.shoulder_l1))
        chooseAction(activity.getString(R.string.shoulder_macro))
        assertEquals(activity.getString(R.string.macro_library_title), shadowOf(latest()).title.toString())
    }

    @Test
    fun `a fresh recording is offered the default name, numbered past names already taken`() {
        setRoot(true)
        val bridge = FakeBridge()
        setBridge(bridge)
        prefs.saveMacro(null, "Macro L1", hold)
        val activity = open()
        val saved = mutableListOf<Pair<String?, String>>()
        val root = MacroEditor(activity, "L1", null, "Macro L1", { prefs.macroLibrary }, { false }) { id, name, _ -> saved += id to name }
            .show().window!!.decorView
        click(root, activity.getString(R.string.macro_record))
        listOf("5000 K ${Evdev.BTN_A} 1", "5000 S", "5100 K ${Evdev.BTN_A} 0", "5100 S").forEach { bridge.input!!(it.split(' ')) }
        click(root, activity.getString(R.string.macro_stop))
        click(root, activity.getString(R.string.macro_save))
        assertNull(latest().getButton(AlertDialog.BUTTON_NEUTRAL)?.takeIf { it.visibility == View.VISIBLE })
        assertEquals("Macro L1 (2)", (find(latest().window!!.decorView) { it is EditText } as EditText).text.toString())
        nameAndSave(null)
        assertEquals(listOf<Pair<String?, String>>(null to "Macro L1 (2)"), saved)
    }
}
