package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.steinhorst.backbuttonhero.actions.ActionGroups
import dev.steinhorst.backbuttonhero.actions.Ayn
import dev.steinhorst.backbuttonhero.capture.PushToTalk
import dev.steinhorst.backbuttonhero.core.PService
import dev.steinhorst.backbuttonhero.input.AndroidButton
import dev.steinhorst.backbuttonhero.input.BackGestureButton
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.input.DpadDirection
import dev.steinhorst.backbuttonhero.input.FaceButton
import dev.steinhorst.backbuttonhero.input.HomeLayerButton
import dev.steinhorst.backbuttonhero.input.ServiceToggleGesture
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.input.Stick
import dev.steinhorst.backbuttonhero.input.StickButton
import dev.steinhorst.backbuttonhero.input.StickClick
import dev.steinhorst.backbuttonhero.input.StickClickButton
import dev.steinhorst.backbuttonhero.input.base
import dev.steinhorst.backbuttonhero.macro.MacroEditor
import dev.steinhorst.backbuttonhero.macro.MacroLibraryDialog
import dev.steinhorst.backbuttonhero.macro.SavedMacro
import dev.steinhorst.backbuttonhero.pad.DpadOverlay
import dev.steinhorst.backbuttonhero.prefs.BindingLayer
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.prefs.SettingsChangeAction
import dev.steinhorst.backbuttonhero.prefs.SettingsChangePlan
import dev.steinhorst.backbuttonhero.settings.ActionChooser
import dev.steinhorst.backbuttonhero.settings.BackupFlow
import dev.steinhorst.backbuttonhero.settings.BindingCard
import dev.steinhorst.backbuttonhero.settings.BindingDescriber
import dev.steinhorst.backbuttonhero.settings.ChangeDescriber
import dev.steinhorst.backbuttonhero.settings.Choice
import dev.steinhorst.backbuttonhero.settings.BindingsOverview
import dev.steinhorst.backbuttonhero.settings.ExceptionSettingsPage
import dev.steinhorst.backbuttonhero.settings.GyroSettingsDialog
import dev.steinhorst.backbuttonhero.settings.OverviewControl
import dev.steinhorst.backbuttonhero.settings.OverviewGroup
import dev.steinhorst.backbuttonhero.settings.POSITION_GRID_HEIGHT_DP
import dev.steinhorst.backbuttonhero.settings.POSITION_GRID_WIDTH_DP
import dev.steinhorst.backbuttonhero.settings.PositionGrid
import dev.steinhorst.backbuttonhero.settings.ServiceStatus
import dev.steinhorst.backbuttonhero.settings.SettingsNavigation
import dev.steinhorst.backbuttonhero.settings.SettingsPage
import dev.steinhorst.backbuttonhero.settings.SettingsSection
import dev.steinhorst.backbuttonhero.settings.SettingsSurface
import dev.steinhorst.backbuttonhero.settings.UpdateCheck
import dev.steinhorst.backbuttonhero.settings.UpdateFlow
import dev.steinhorst.backbuttonhero.settings.WelcomeSheet
import dev.steinhorst.backbuttonhero.settings.alignSettingsRange
import dev.steinhorst.backbuttonhero.settings.currentServiceStatus
import dev.steinhorst.backbuttonhero.settings.dp
import dev.steinhorst.backbuttonhero.settings.isAccessibilityServiceEnabled
import dev.steinhorst.backbuttonhero.settings.positionGrid
import dev.steinhorst.backbuttonhero.settings.selectTab
import dev.steinhorst.backbuttonhero.settings.serviceChipText
import dev.steinhorst.backbuttonhero.settings.serviceStatusText
import dev.steinhorst.backbuttonhero.settings.settingsSurfaceColor
import dev.steinhorst.backbuttonhero.settings.tabBar
import dev.steinhorst.backbuttonhero.settings.themedColor

/** Configuration pages share the same rows and keep their state while navigating. */
open class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var navigation: SettingsNavigation
    private val pages = mutableMapOf<SettingsSection, SettingsPage>()
    private var exceptionsPage: ExceptionSettingsPage? = null
    private lateinit var warnings: LinearLayout
    private val describer by lazy { BindingDescriber(this, prefs) }
    private val changeDescriber by lazy { ChangeDescriber(this, describer) }
    private val backup by lazy { BackupFlow(this, prefs, ::appVersionName, ::showChanges) }
    private lateinit var serviceWarning: TextView
    private lateinit var rootWarning: SettingsPage.WarningHandle
    private var toggleButtonsValue: TextView? = null
    /** The hold-time slider below the on/off shortcut; without a shortcut there is nothing to time. */
    private var toggleDurationOptions: View? = null
    private var statusLabel: TextView? = null
    private val comboRows = mutableMapOf<String, BindingCard>()
    private val stickPages = mutableListOf<SettingsPage>()
    private val stickBoxes = mutableMapOf<Stick, LinearLayout>()
    private var stickTabBar: LinearLayout? = null
    private var stickTab = Stick.LEFT
    /** Which layer the overview draws in dual mode. */
    private var overviewHome = false
    private var repairHandle: SettingsPage.ActionHandle? = null
    private var updateHandle: SettingsPage.ActionHandle? = null
    private val updateFlow by lazy { UpdateFlow(this, updateCheck(), ::openUrl) }
    private var macroEditor: MacroEditor? = null
    private var welcomeSheet: WelcomeSheet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        layoutDual = prefs.dualMode
        title = getString(R.string.app_name)
        actionBar?.hide()
        // The light theme's pale status bar left the clock and icons unreadable on the Thor; black with
        // white icons reads in both themes.
        window.statusBarColor = Color.BLACK
        window.insetsController?.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        warnings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        // A compact pill: one short line of fixed height, so it appearing never moves the bar.
        serviceWarning = TextView(this).apply {
            tag = "settings_service_warning"
            val error = themedColor(android.R.attr.colorError, Color.parseColor("#B3261E"))
            textSize = 13f
            setTextColor(error)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(36, Color.red(error), Color.green(error), Color.blue(error)))
                cornerRadius = dp(14).toFloat()
            }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            // The chip is the only sign of an unresponsive service, so it leads straight to the repair steps.
            setOnClickListener { explainServiceRepair() }
        }
        SettingsPage(this, warnings).card {
            rootWarning = warningRow(getString(R.string.dpad_root_missing))
        }
        navigation = SettingsNavigation(this, savedInstanceState, warnings, serviceWarning, serviceBlockerCard(), ::buildPage, ::refresh)
        setContentView(navigation.view)
        openIndicatorFromIntent()
        ensureNotificationPermission()
        updateCheck().run(this, null)
        if (!prefs.welcomeSeen) welcomeSheet = WelcomeSheet(this, prefs, ::invalidatePages).also { it.show() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openIndicatorFromIntent()
    }

    /** Tapping the focus pictogram lands here: bring that button's indicator settings up directly. */
    private fun openIndicatorFromIntent() {
        val key = intent?.getStringExtra(EXTRA_INDICATOR_BUTTON) ?: return
        intent.removeExtra(EXTRA_INDICATOR_BUTTON)
        val button = Prefs.STORED_BUTTONS.firstOrNull { it.prefKey == key } ?: return
        if (prefs.shoulderAction(button) != Prefs.SHOULDER_CONTROLLER_FOCUS) return
        when (val control = button.base) {
            is ShoulderButton -> navigation.show(SettingsSection.SHOULDERS)
            is StickButton -> showStickButton(control.stick)
            is StickClickButton -> showStickButton(control.stick)
            is BackGestureButton, is AndroidButton -> navigation.show(SettingsSection.BASIC)
            is FaceButton -> navigation.show(SettingsSection.FACE)
            else -> navigation.show(SettingsSection.DPAD)
        }
        val row = comboRows[key] ?: return
        row.position.row.post {
            if (isFinishing || isDestroyed) return@post
            row.position.row.requestRectangleOnScreen(Rect(0, 0, row.position.row.width, row.position.row.height), true)
            choosePosition(row)
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        describer.clearAppLabels()
        prefs.registerListener(storedChange)
        if (prefs.dualMode != layoutDual) invalidatePages() else refresh()
        // A service rebind can finish just after the activity resumes.
        navigation.view.postDelayed({ if (!isFinishing && !isDestroyed) refresh() }, 600)
        PService.probeAsync { runOnUiThread { if (!isFinishing && !isDestroyed) refresh() } }
    }

    override fun onPause() {
        prefs.unregisterListener(storedChange)
        macroEditor?.pause()
        super.onPause()
    }

    // Settings also change while the page stays open (the service, a restored backup, a device test);
    // the page must show what is stored, or Unbind and the previews judge something it does not show.
    private var layoutDual = false
    private val storedRefresh = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        if (prefs.dualMode != layoutDual) invalidatePages() else refresh()
    }
    private val storedChange: () -> Unit = {
        navigation.view.removeCallbacks(storedRefresh)
        navigation.view.post(storedRefresh)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (navigation.onWindowTouch(event) { super.dispatchTouchEvent(it) }) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        navigation.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        navigation.dismiss()
        updateFlow.dismiss()
        welcomeSheet?.dismiss()
        exceptionsPage?.dispose()
        super.onDestroy()
    }

    private fun buildPage(section: SettingsSection, content: LinearLayout) {
        val page = SettingsPage(this, content)
        pages[section] = page
        fun change(action: SettingsChangeAction, scope: SettingsSection? = section) = {
            if (prefs.dualMode && SettingsChangePlan.hasLayers(scope)) chooseLayers { layers ->
                showChanges(SettingsChangePlan.create(prefs.snapshot(), scope, action, layers))
            } else showChanges(SettingsChangePlan.create(prefs.snapshot(), scope, action))
        }
        val actions = when (section) {
            SettingsSection.HELP, SettingsSection.OVERVIEW -> emptyList()
            SettingsSection.EXCEPTIONS -> listOf(
                R.string.exceptions_add_app to { exceptionsPage?.addApp() ?: Unit },
                R.string.exceptions_remove_all to change(SettingsChangeAction.PRESET),
            )
            // The settings page is where the whole app is managed, so its Unbind reaches every page's buttons.
            SettingsSection.SETTINGS -> listOf(
                // The defaults are one whole-app setup, so dual mode has no layer to ask about.
                R.string.settings_load_defaults to {
                    showChanges(SettingsChangePlan.create(prefs.snapshot(), null, SettingsChangeAction.DEFAULTS))
                },
                R.string.settings_unbind to change(SettingsChangeAction.UNBIND, scope = null),
            )
            else -> listOf(R.string.settings_unbind to change(SettingsChangeAction.UNBIND))
        }
        // The overview needs the room for its drawing; it explains itself in one line underneath.
        if (section != SettingsSection.OVERVIEW) page.introduction(section.hintRes, actions, primary = R.string.exceptions_add_app)
        if (prefs.dualMode && section in DUAL_SECTIONS && section != SettingsSection.STICKS) dualHeader(page)
        when (section) {
            SettingsSection.OVERVIEW -> buildOverview(page)
            SettingsSection.BASIC -> buildBasic(page)
            SettingsSection.FACE -> {
                page.sectionHeader(R.string.face_bindings_section)
                buildShoulderRow(page, FaceButton.A, R.string.button_a)
                buildShoulderRow(page, FaceButton.B, R.string.button_b)
                buildShoulderRow(page, FaceButton.X, R.string.button_x)
                buildShoulderRow(page, FaceButton.Y, R.string.button_y)
            }
            SettingsSection.SHOULDERS -> {
                page.sectionHeader(R.string.shoulder_bindings_section)
                buildShoulderRow(page, ShoulderButton.L1, R.string.shoulder_l1)
                buildShoulderRow(page, ShoulderButton.R1, R.string.shoulder_r1)
                buildShoulderRow(page, ShoulderButton.L2, R.string.shoulder_l2)
                buildShoulderRow(page, ShoulderButton.R2, R.string.shoulder_r2)
            }
            SettingsSection.STICKS -> buildSticks(content)
            SettingsSection.DPAD -> {
                page.sectionHeader(R.string.dpad_bindings_section)
                buildShoulderRow(page, DpadDirection.UP, R.string.dpad_up)
                buildShoulderRow(page, DpadDirection.DOWN, R.string.dpad_down)
                buildShoulderRow(page, DpadDirection.LEFT, R.string.dpad_left)
                buildShoulderRow(page, DpadDirection.RIGHT, R.string.dpad_right)
            }
            SettingsSection.EXCEPTIONS -> exceptionsPage = ExceptionSettingsPage(this, page, prefs)
            SettingsSection.SETTINGS -> buildSettings(page)
            SettingsSection.HELP -> buildHelp(page)
        }
    }

    private fun buildOverview(page: SettingsPage) {
        val overview = BindingsOverview(this).apply {
            onOpen = { area ->
                area.stick?.let { stickTab = it }
                navigation.show(area.section, resetScroll = true)
                // A stick opens on its own tab, also when the page was built showing the other one.
                if (area.stick != null) showStickTab()
            }
        }
        fun note(res: Int) = TextView(this).apply {
            setText(res)
            setTextColor(themedColor(android.R.attr.textColorSecondary, Color.DKGRAY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        val empty = note(R.string.overview_empty)
        // Dual mode draws one layer at a time; the switch sits above the drawing.
        if (prefs.dualMode) {
            lateinit var tabs: LinearLayout
            tabs = tabBar(listOf(R.string.dual_column_home, R.string.dual_column_back), if (overviewHome) 0 else 1,
                listOf(R.drawable.ic_exc_home, R.drawable.ic_exc_back)) {
                overviewHome = it == 0
                selectTab(tabs, it)
                page.refresh()
            }
            tabs.tag = "settings_overview_layers"
            page.live(tabs) {}
        }
        page.live(overview) {
            val groups = overviewGroups(overviewHome && prefs.dualMode)
            overview.setGroups(groups)
            overview.setToggle(getString(R.string.service_toggle_title),
                ServiceToggleGesture.label(prefs.toggleButtons)?.let { getString(R.string.overview_toggle_hold, it) })
            empty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
        }
        overview.setHint(getString(R.string.nav_overview_hint))
        // The page's spare height goes to the overview, which centres its picture in it.
        overview.layoutParams = (overview.layoutParams as LinearLayout.LayoutParams).apply { height = 0; weight = 1f }
        page.live(empty) {}
    }

    /** One group per physical control, holding only the bindings that are assigned; [home] draws dual mode's Home layer. */
    private fun overviewGroups(home: Boolean = false): List<OverviewGroup> {
        fun label(control: ComboButton): String? {
            val button = if (home) HomeLayerButton.of(control) else control
            val action = prefs.shoulderAction(button).ifEmpty { return null }
            // AYN's switches name their chosen value too, like "Performance · Max".
            val ayn = describer.describeAynOption(button).takeIf { Ayn.groupOf(action) != null && it.isNotEmpty() }
            return ayn?.let { getString(R.string.overview_grouped_action, describer.describeAction(action), it) } ?: describer.overviewLabel(action)
        }
        fun group(control: OverviewControl, title: Int, section: SettingsSection, vararg rows: Pair<String, ComboButton>) =
            rows.mapNotNull { (prefix, button) -> label(button)?.let { prefix to it } }
                .takeIf { it.isNotEmpty() }?.let { OverviewGroup(control, getString(title), it, section) }
        fun bound(vararg rows: Pair<String, ComboButton>) = rows.mapNotNull { (prefix, button) -> label(button)?.let { prefix to it } }
        fun directions(of: (DpadDirection) -> ComboButton) = bound(
            "↑" to of(DpadDirection.UP), "↓" to of(DpadDirection.DOWN), "←" to of(DpadDirection.LEFT), "→" to of(DpadDirection.RIGHT),
        )
        fun pad(control: OverviewControl, title: Int, section: SettingsSection, of: (DpadDirection) -> ComboButton, vararg rows: Pair<String, ComboButton>) =
            OverviewGroup(control, getString(title), bound(*rows), section, directions(of))
                .takeIf { it.rows.isNotEmpty() || it.directions.isNotEmpty() }
        fun gestures(control: OverviewControl, title: Int) = group(control, title, SettingsSection.BASIC,
            getString(R.string.overview_press) to BackGestureButton.SINGLE,
            getString(R.string.overview_double_press) to BackGestureButton.DOUBLE,
            getString(R.string.overview_tap_hold) to BackGestureButton.TAP_HOLD,
            getString(R.string.overview_hold) to BackGestureButton.HOLD,
        )
        // Back mode and dual mode block the Back + Home shortcut, so Home shows its mode or its shortcut;
        // in the Home layer, Home holds the gestures Back holds in the Back layer.
        fun home() = when {
            home -> gestures(OverviewControl.HOME, R.string.button_home)
            prefs.homeAsBack || prefs.dualMode -> OverviewGroup(OverviewControl.HOME, getString(R.string.button_home),
                listOf("" to getString(if (prefs.dualMode) R.string.overview_home_dual_mode else R.string.overview_home_back_mode)),
                SettingsSection.BASIC)
            else -> group(OverviewControl.HOME, R.string.button_home, SettingsSection.BASIC, "" to AndroidButton.HOME)
        }
        fun stick(stick: Stick, control: OverviewControl, title: Int) = pad(control, title, SettingsSection.STICKS, { StickButton.of(stick, it) },
            getString(R.string.overview_click) to StickClickButton.of(stick, StickClick.PRESS),
            getString(R.string.overview_double_click) to StickClickButton.of(stick, StickClick.DOUBLE),
            getString(R.string.overview_click_tap_hold) to StickClickButton.of(stick, StickClick.TAP_HOLD),
        )
        return listOfNotNull(
            group(OverviewControl.L2, R.string.shoulder_l2, SettingsSection.SHOULDERS, "" to ShoulderButton.L2),
            group(OverviewControl.L1, R.string.shoulder_l1, SettingsSection.SHOULDERS, "" to ShoulderButton.L1),
            group(OverviewControl.SELECT, R.string.button_select, SettingsSection.BASIC, "" to AndroidButton.SELECT),
            stick(Stick.LEFT, OverviewControl.LEFT_STICK, R.string.stick_tab_left),
            pad(OverviewControl.DPAD, R.string.overview_dpad, SettingsSection.DPAD, { it }),
            home(),
            group(OverviewControl.R2, R.string.shoulder_r2, SettingsSection.SHOULDERS, "" to ShoulderButton.R2),
            group(OverviewControl.R1, R.string.shoulder_r1, SettingsSection.SHOULDERS, "" to ShoulderButton.R1),
            group(OverviewControl.START, R.string.button_start, SettingsSection.BASIC, "" to AndroidButton.START),
            group(OverviewControl.FACE, R.string.overview_face, SettingsSection.FACE,
                "A" to FaceButton.A, "B" to FaceButton.B, "X" to FaceButton.X, "Y" to FaceButton.Y),
            stick(Stick.RIGHT, OverviewControl.RIGHT_STICK, R.string.stick_tab_right),
            if (home) null else gestures(OverviewControl.BACK, R.string.overview_back),
        )
    }

    /** One page per stick behind two tabs; both are built up front so their rows stay in [comboRows]. */
    private fun buildSticks(content: LinearLayout) {
        stickPages.clear()
        stickBoxes.clear()
        val tabs = tabBar(listOf(R.string.stick_tab_left, R.string.stick_tab_right), Stick.entries.indexOf(stickTab)) {
            stickTab = Stick.entries[it]
            showStickTab()
        }
        stickTabBar = tabs
        content.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })
        val titles = mapOf(
            StickButton.LEFT_UP to R.string.stick_left_up, StickButton.LEFT_DOWN to R.string.stick_left_down,
            StickButton.LEFT_LEFT to R.string.stick_left_left, StickButton.LEFT_RIGHT to R.string.stick_left_right,
            StickButton.RIGHT_UP to R.string.stick_right_up, StickButton.RIGHT_DOWN to R.string.stick_right_down,
            StickButton.RIGHT_LEFT to R.string.stick_right_left, StickButton.RIGHT_RIGHT to R.string.stick_right_right,
        )
        val clickTitles = mapOf(
            StickClickButton.LEFT_PRESS to R.string.stick_left_press, StickClickButton.LEFT_DOUBLE to R.string.stick_left_double,
            StickClickButton.LEFT_TAP_HOLD to R.string.stick_left_tap_hold, StickClickButton.RIGHT_PRESS to R.string.stick_right_press,
            StickClickButton.RIGHT_DOUBLE to R.string.stick_right_double, StickClickButton.RIGHT_TAP_HOLD to R.string.stick_right_tap_hold,
        )
        Stick.entries.forEach { stick ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "settings_stick_${stick.name}" }
            content.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val page = SettingsPage(this, box)
            stickPages += page
            stickBoxes[stick] = box
            if (prefs.dualMode) dualHeader(page)
            page.sectionHeader(if (stick == Stick.LEFT) R.string.stick_left_bindings_section else R.string.stick_right_bindings_section)
            StickButton.entries.filter { it.stick == stick }.forEach { buildShoulderRow(page, it, titles.getValue(it)) }
            // Same three sections, in the same order, as the Back page: the action replaces the switch.
            fun clickRow(click: StickClick, extra: (SettingsPage.Card.(ComboButton) -> View)? = null) {
                val button = StickClickButton.of(stick, click)
                buildShoulderRow(page, button, clickTitles.getValue(button), extra)
            }
            page.sectionHeader(R.string.single_section_title)
            clickRow(StickClick.PRESS)
            page.sectionHeader(R.string.double_section_title)
            clickRow(StickClick.DOUBLE) { button ->
                val home = button is HomeLayerButton
                subOptions {
                    sliderRow(R.string.stick_double_speed, 150, 600, 50, prefs.stickDoubleMs(stick, home), R.string.stick_double_hint,
                        readValue = { prefs.stickDoubleMs(stick, home) }) { prefs.setStickDoubleMs(stick, it, home) }
                }
            }
            page.sectionHeader(R.string.tap_hold_section_title)
            clickRow(StickClick.TAP_HOLD) { button ->
                val home = button is HomeLayerButton
                subOptions {
                    sliderRow(R.string.stick_tap_hold_speed, 500, 2000, 100, prefs.stickTapHoldMs(stick, home), R.string.stick_tap_hold_hint,
                        readValue = { prefs.stickTapHoldMs(stick, home) }) { prefs.setStickTapHoldMs(stick, it, home) }
                }
            }
        }
        showStickTab()
    }

    private fun showStickButton(stick: Stick) {
        stickTab = stick
        navigation.show(SettingsSection.STICKS)
        showStickTab()
    }

    private fun showStickTab() {
        stickBoxes.forEach { (stick, box) -> box.visibility = if (stick == stickTab) View.VISIBLE else View.GONE }
        stickTabBar?.let { selectTab(it, Stick.entries.indexOf(stickTab)) }
    }

    private fun buildBasic(page: SettingsPage) {
        // Every gesture takes any action; its timing only shows once it has one.
        fun gesture(section: Int, button: BackGestureButton, row: Int, slider: (SettingsPage.Card.(ComboButton) -> View)? = null) {
            page.sectionHeader(section)
            buildShoulderRow(page, button, row, slider)
        }
        // In dual mode the Home column times its own gestures.
        fun timing(button: ComboButton, back: () -> Long, home: () -> Long, set: (Long, Boolean) -> Unit): Pair<() -> Long, (Long) -> Unit> {
            val ofHome = button is HomeLayerButton
            return (if (ofHome) home else back) to { value: Long -> set(value, ofHome) }
        }
        gesture(R.string.single_section_title, BackGestureButton.SINGLE, R.string.back_single_row)
        gesture(R.string.double_section_title, BackGestureButton.DOUBLE, R.string.back_double_row) { button ->
            val (read, write) = timing(button, { prefs.doubleTapMs }, { prefs.homeDoubleTapMs }) { v, home ->
                if (home) prefs.homeDoubleTapMs = v else prefs.doubleTapMs = v
            }
            subOptions { sliderRow(R.string.double_speed, 150, 600, 50, read(), R.string.double_hint, readValue = read, onChange = write) }
        }
        gesture(R.string.tap_hold_section_title, BackGestureButton.TAP_HOLD, R.string.back_tap_hold_row) { button ->
            val (read, write) = timing(button, { prefs.tapHoldMs }, { prefs.homeTapHoldMs }) { v, home ->
                if (home) prefs.homeTapHoldMs = v else prefs.tapHoldMs = v
            }
            subOptions { sliderRow(R.string.tap_hold_speed, 500, 2000, 100, read(), R.string.tap_hold_hint, readValue = read, onChange = write) }
        }
        gesture(R.string.hold_section_title, BackGestureButton.HOLD, R.string.back_hold_row) { button ->
            val (read, write) = timing(button, { prefs.holdMs }, { prefs.homeHoldMs }) { v, home ->
                if (home) prefs.homeHoldMs = v else prefs.holdMs = v
            }
            subOptions { sliderRow(R.string.hold_speed, 500, 2000, 100, read(), R.string.hold_hint, readValue = read, onChange = write) }
        }
        page.sectionHeader(R.string.android_bindings_section)
        buildShoulderRow(page, AndroidButton.HOME, R.string.button_home)
        buildShoulderRow(page, AndroidButton.START, R.string.button_start)
        buildShoulderRow(page, AndroidButton.SELECT, R.string.button_select)
    }

    private fun buildSettings(page: SettingsPage) {
        page.sectionHeader(R.string.settings_switcher)
        page.card {
            toggleRow(R.string.switcher_controller, R.string.switcher_controller_hint, prefs.switcherControllerEnabled, readValue = { prefs.switcherControllerEnabled }) {
                prefs.switcherControllerEnabled = it
            }
        }
        page.sectionHeader(R.string.home_as_back_section)
        page.card {
            toggleRow(R.string.home_as_back, R.string.home_as_back_hint, prefs.homeAsBack, readValue = { prefs.homeAsBack }) {
                val wasDual = prefs.dualMode
                prefs.homeAsBack = it
                if (wasDual != prefs.dualMode) invalidatePages()
                else comboRows[AndroidButton.HOME.prefKey]?.let(::updateShoulderRow)
            }
            // The two Home modes exclude each other; dual mode also rebuilds the binding pages into columns.
            toggleRow(R.string.dual_mode, R.string.dual_mode_hint, prefs.dualMode, readValue = { prefs.dualMode }) {
                prefs.dualMode = it
                invalidatePages()
            }
        }
        page.sectionHeader(R.string.service_toggle_title)
        page.card {
            val buttons = chooserRow(R.string.service_toggle_buttons, toggleButtonsLabel()) { chooseToggleButtons() }
            toggleButtonsValue = buttons.value
            toggleDurationOptions = subOptions(endControl = buttons.row.findViewWithTag("settings_chooser_chevron")) {
                sliderRow(R.string.service_toggle_duration, 100, 3000, 100, prefs.toggleHoldMs, R.string.service_toggle_hint, readValue = { prefs.toggleHoldMs }) {
                    prefs.toggleHoldMs = it
                }
            }.apply { visibility = if (prefs.toggleButtons == Prefs.TOGGLE_NONE) View.GONE else View.VISIBLE }
        }

        page.sectionHeader(R.string.backup_section)
        page.card {
            actionRow(R.string.backup_save, R.string.backup_save_hint) { backup.startSave() }
            actionRow(R.string.backup_load, R.string.backup_load_hint) { backup.startLoad() }
        }
    }

    private fun buildHelp(page: SettingsPage) {
        page.sectionHeader(R.string.settings_service)
        page.card {
            statusLabel = textRow(serviceStatusText(currentServiceStatus()))
            repairHandle = actionRow(R.string.repair_service, R.string.repair_service_hint) { explainServiceRepair() }
        }
        page.card {
            actionRow(R.string.report_bug, R.string.report_bug_hint) {
                openUrl(UpdateChecker.REPOSITORY_URL + "/issues/new?template=bug_report.yml")
            }.title.setTextColor(themedColor(android.R.attr.colorAccent, Color.parseColor("#6750A4")))
        }
        page.sectionHeader(R.string.settings_updates)
        page.card {
            updateHandle = actionRow(R.string.check_for_updates) { checkForUpdates() }
            textRow(getString(R.string.version_label, appVersionName()))
            linkRow(getString(R.string.github_repository), R.string.open_github_repository) { openUrl(UpdateChecker.REPOSITORY_URL) }
        }
    }

    private fun toggleButtonsLabel(): String = describer.toggleLabel(prefs.toggleButtons)

    private fun chooseToggleButtons() {
        val values = listOf(Prefs.TOGGLE_L1_R1, Prefs.TOGGLE_L2_R2, Prefs.TOGGLE_BOTH, Prefs.TOGGLE_START_SELECT,
            Prefs.TOGGLE_BACK_HOME, Prefs.TOGGLE_NONE)
        AlertDialog.Builder(this)
            .setTitle(R.string.service_toggle_buttons)
            .setSingleChoiceItems(values.map(describer::toggleLabel).toTypedArray(), values.indexOf(prefs.toggleButtons)) { dialog, which ->
                dialog.dismiss()
                if (values[which] == Prefs.TOGGLE_NONE) {
                    showChanges(SettingsChangePlan.create(prefs.snapshot(), SettingsSection.SETTINGS, SettingsChangeAction.UNBIND))
                } else {
                    prefs.toggleButtons = values[which]
                    refresh()
                }
            }
            .show()
    }

    /** What stands in the middle of the scrim while the service is off: why, and the one thing to do. */
    private fun serviceBlockerCard(): View {
        val accent = themedColor(android.R.attr.colorAccent, Color.parseColor("#6750A4"))
        val surface = settingsSurfaceColor(SettingsSurface.INTRODUCTION)
        return LinearLayout(this).apply {
            tag = "settings_service_blocker_card"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(24).toFloat()
            }
            elevation = dp(8).toFloat()
            addView(TextView(this@SettingsActivity).apply {
                tag = "settings_service_blocker_title"
                text = getString(R.string.service_blocker_title, getString(R.string.app_name))
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themedColor(android.R.attr.textColorPrimary, Color.BLACK))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@SettingsActivity).apply {
                text = getString(R.string.service_blocker_hint, getString(R.string.app_name))
                textSize = 15f
                setTextColor(themedColor(android.R.attr.textColorSecondary, Color.GRAY))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(android.widget.Button(this@SettingsActivity).apply {
                tag = "settings_service_blocker_button"
                setText(R.string.enable_service)
                isAllCaps = false
                textSize = 15f
                // Dark theme accents are light; the label takes whichever of black and white reads on it.
                setTextColor(if (Color.luminance(accent) > 0.5f) Color.BLACK else Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(accent)
                    cornerRadius = dp(24).toFloat()
                }
                minimumHeight = dp(48)
                setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(20) })
        }
    }

    private fun refresh() {
        pages.values.forEach { it.refresh() }
        stickPages.forEach { it.refresh() }
        refreshingRows = true
        comboRows.values.forEach { updateShoulderRow(it) }
        refreshingRows = false
        toggleButtonsValue?.text = toggleButtonsLabel()
        toggleDurationOptions?.visibility = if (prefs.toggleButtons == Prefs.TOGGLE_NONE) View.GONE else View.VISIBLE
        val status = currentServiceStatus()
        serviceWarning.text = serviceChipText(status)
        serviceWarning.contentDescription = serviceStatusText(status)
        serviceWarning.visibility = if (status == ServiceStatus.ON) View.GONE else View.VISIBLE
        // Without pservice, Back + D-pad has to take window focus (DpadOverlay) and games may pause.
        val dpadNeeds = prefs.hasDpadAction()
        val sticksNeed = prefs.hasStickAction()
        rootWarning.label.setText(when {
            dpadNeeds && sticksNeed -> R.string.dpad_stick_root_missing
            sticksNeed -> R.string.stick_root_missing
            else -> R.string.dpad_root_missing
        })
        rootWarning.row.visibility = if ((dpadNeeds || sticksNeed) && !PService.cachedAvailable()) View.VISIBLE else View.GONE
        statusLabel?.text = serviceStatusText(status)
        val enabled = isAccessibilityServiceEnabled()
        // The navigation selects its first page while it is still being built, before it can be asked.
        if (::navigation.isInitialized) navigation.setBlocked(!enabled)
        repairHandle?.row?.visibility = if (enabled) View.VISIBLE else View.GONE
        warnings.visibility = if (rootWarning.row.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    /** Every launchable app on the device, shown only after choosing "Launch app". */
    private fun allApps(): List<Choice> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).map {
            Choice(it.activityInfo.packageName, it.loadLabel(packageManager).toString())
        }.distinctBy { it.value }.sortedBy { it.label }
    }

    private fun chooseRecordDuck(row: BindingCard) {
        val choices = describer.recordDucks()
        AlertDialog.Builder(this)
            .bindingTitle(crumb(row), R.string.record_duck_title)
            .chooseOne(choices.map { it.label }.toTypedArray(),
                choices.indexOfFirst { it.value == prefs.recordDuck(row.button) }.coerceAtLeast(0)) { dialog, which ->
                prefs.setRecordDuck(row.button, choices[which].value)
                dialog.dismiss()
                updateShoulderRow(row)
            }
            .show()
    }

    /** Waits for the next button press and takes it as the push-to-talk button. */
    private fun choosePushToTalkKey(row: BindingCard) = chooseKey(
        R.string.record_ptt_key_title, R.string.record_ptt_dialog_message, R.string.record_ptt_dialog_warning,
        R.string.record_ptt_key_rejected,
        onKeep = { updateShoulderRow(row) },
        binding = crumb(row),
    ) { keyCode ->
        prefs.setRecordPttKey(row.button, keyCode)
        updateShoulderRow(row)
    }

    /**
     * Waits for the next button press; one [PushToTalk] does not reserve goes to [onChosen]. With
     * [onNone] the dialog also offers to choose no button.
     */
    private fun chooseKey(
        titleRes: Int,
        messageRes: Int,
        warningRes: Int,
        rejectedRes: Int,
        onNone: (() -> Unit)? = null,
        onKeep: (() -> Unit)? = null,
        binding: String? = null,
        onChosen: (Int) -> Unit,
    ) {
        val warning = getString(warningRes)
        val dialog = AlertDialog.Builder(this)
            .apply { if (binding != null) bindingTitle(binding, titleRes) else setTitle(titleRes) }
            .setMessage("${getString(messageRes)}\n\n$warning")
            .setNegativeButton(android.R.string.cancel, null)
            .apply { if (onNone != null) setNeutralButton(R.string.gyro_hold_none_button) { _, _ -> onNone() } }
            // Keeps the button already set, the way the confirm button takes a preselected answer.
            .apply { if (onKeep != null) setPositiveButton(confirmLabel()) { _, _ -> onKeep() } }
            .create()
        dialog.setOnKeyListener { _, keyCode, event ->
            // Back still cancels; every other key is an answer, never a dialog navigation.
            if (keyCode == KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return@setOnKeyListener true
            if (!PushToTalk.isAllowed(keyCode)) {
                dialog.setMessage("${getString(rejectedRes, PushToTalk.label(keyCode))}\n\n$warning")
                return@setOnKeyListener true
            }
            dialog.dismiss()
            onChosen(keyCode)
            true
        }
        dialog.show()
    }

    private fun openGyroSettings(row: BindingCard) {
        GyroSettingsDialog(this, row.button.prefKey, prefs.gyro(row.button), { hasKey, onChosen ->
            chooseKey(R.string.gyro_hold_key_title, R.string.gyro_key_dialog_message, R.string.gyro_key_dialog_warning,
                R.string.gyro_key_rejected, if (hasKey) ({ onChosen(0) }) else null, onChosen = onChosen)
        }) { prefs.setGyro(row.button, it) }.show { updateShoulderRow(row) }
    }

    private fun chooseRecordQuality(row: BindingCard) {
        val choices = describer.recordQualities()
        AlertDialog.Builder(this)
            .bindingTitle(crumb(row), R.string.record_quality_title)
            .chooseOne(choices.map { it.label }.toTypedArray(),
                choices.indexOfFirst { it.value == prefs.recordQuality(row.button) }.coerceAtLeast(0)) { dialog, which ->
                prefs.setRecordQuality(row.button, choices[which].value)
                dialog.dismiss()
                updateShoulderRow(row)
            }
            .show()
    }

    /** The indicator dialog of the controller-focus pictogram, or with [record] of the recording dot,
     *  which has no display time. */
    private fun choosePosition(row: BindingCard, record: Boolean = false) {
        val positions = describer.focusPositions()
        lateinit var grid: PositionGrid
        fun position() = if (record) prefs.recordPosition(row.button) else prefs.focusPosition(row.button)
        fun indicator() = if (record) prefs.recordIndicator(row.button) else prefs.focusIndicator(row.button)
        fun refresh() = grid.update(position(), indicator())
        fun preview() {
            val on = indicator()
            if (record) {
                if (on) SwitcherService.previewRecordIndicator?.invoke(position())
                return
            }
            SwitcherService.previewFocusIndicator?.invoke(
                prefs.focusTarget(row.button) != Prefs.DISPLAY_BOTTOM,
                prefs.focusPosition(row.button).takeIf { on },
                prefs.focusDuration(row.button),
                prefs.focusTarget(row.button) == Prefs.FOCUS_AUTO,
            )
        }
        grid = positionGrid(positions.map { it.value to it.label }, { getString(R.string.focus_position_selected, it) }) {
            if (record) prefs.setRecordPosition(row.button, it) else prefs.setFocusPosition(row.button, it)
            refresh()
            preview()
        }
        val durationValue = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.END
        }
        val step = 100
        val min = Prefs.MIN_FOCUS_DURATION_MS.toInt()
        val duration = SeekBar(this).apply {
            alignSettingsRange()
            max = (Prefs.MAX_FOCUS_DURATION_MS.toInt() - min) / step
            progress = (prefs.focusDuration(row.button).toInt() - min) / step
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val ms = min + progress * step
                    durationValue.text = getString(R.string.focus_duration, ms)
                    if (fromUser) prefs.setFocusDuration(row.button, ms.toLong())
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = preview()
            })
        }
        durationValue.text = getString(R.string.focus_duration, min + duration.progress * step)
        val toggle = Switch(this).apply {
            setText(if (record) R.string.record_indicator_title else R.string.focus_indicator_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isChecked = indicator()
            setOnCheckedChangeListener { _, on ->
                if (record) prefs.setRecordIndicator(row.button, on) else prefs.setFocusIndicator(row.button, on)
                duration.isEnabled = on
                durationValue.alpha = if (on) 1f else 0.35f
                refresh()
                preview()
            }
        }
        fun header(res: Int) = TextView(this).apply {
            setText(res)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.04f
            setTextColor(themedColor(android.R.attr.colorAccent, Color.parseColor("#6750A4")))
        }
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val full = ViewGroup.LayoutParams.MATCH_PARENT
        val gridWidth = minOf(POSITION_GRID_WIDTH_DP, (resources.configuration.screenWidthDp - 112).coerceAtLeast(160))
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header(R.string.focus_header_position))
            addView(grid.view, LinearLayout.LayoutParams(dp(gridWidth), dp(POSITION_GRID_HEIGHT_DP)).apply { topMargin = dp(16) })
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header(R.string.focus_header_indicator))
            toggle.minimumHeight = dp(48)
            addView(toggle, LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(8) })
            if (record) return@apply
            addView(
                LinearLayout(context).apply {
                    addView(header(R.string.focus_header_duration), LinearLayout.LayoutParams(0, wrap, 1f))
                    addView(durationValue)
                },
                LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(32) },
            )
            duration.contentDescription = getString(R.string.focus_header_duration)
            addView(duration, LinearLayout.LayoutParams(full, dp(48)).apply { topMargin = dp(12) })
        }
        val stacked = resources.configuration.screenWidthDp < 640
        val content = LinearLayout(this).apply {
            orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
            addView(left)
            addView(right, LinearLayout.LayoutParams(if (stacked) full else 0, wrap, if (stacked) 0f else 1f).apply {
                if (stacked) topMargin = dp(24) else marginStart = dp(24)
            })
        }
        duration.isEnabled = toggle.isChecked
        durationValue.alpha = if (toggle.isChecked) 1f else 0.35f
        refresh()
        preview()
        AlertDialog.Builder(this)
            .bindingTitle(crumb(row), R.string.focus_position_title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(confirmLabel(), null)
            .setOnDismissListener { updateShoulderRow(row) }
            .show().also { dialog ->
                // The default landscape dialog width can leave too little room for two columns.
                dialog.window?.setLayout(minOf(dp(720), dp(resources.configuration.screenWidthDp - 32)), wrap)
            }
    }

    private fun chooseShoulderAction(row: BindingCard) {
        val choices = describer.shoulderActions(macro = row.button.base !is BackGestureButton)
        val stored = prefs.shoulderAction(row.button)
        val current = if (Prefs.launchesApp(stored)) ACTION_APP else ActionGroups.groupOf(stored)?.entry ?: stored
        ActionChooser(
            this,
            getString(R.string.shoulder_choose_action_title, row.label),
            choices.map { ActionChooser.Entry(it.value, it.label, describer.actionDescription(it.value)?.let(::getString), keywords = describer.searchKeywords(it.value)) },
            describer.actionOptions(choices),
            current,
        ) { entry ->
            val action = entry.value
            val option = entry.option
            if (option != null) {
                // A group stores the option's own action; an AYN tile stores its action and the option apart.
                if (ActionGroups.groupOfEntry(action) != null) {
                    prefs.setShoulderAction(row.button, option)
                } else {
                    prefs.setShoulderAction(row.button, action)
                    prefs.setAynOption(row.button, option)
                }
                updateShoulderRow(row)
                refresh()
                // The option was just picked in the chooser itself; the walk asks only for the rest.
                if (prefs.shoulderAction(row.button) != stored) startGuide(row, setOfNotNull(row.option?.row))
            } else if (action == ACTION_APP) {
                chooseShoulderApp(row)
            } else if (action == Prefs.SHOULDER_MACRO && !PService.cachedAvailable()) {
                showMacroUnavailable()
            } else if (action == Prefs.SHOULDER_GYRO && !PService.cachedAvailable()) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.gyro_unavailable_title)
                    .setMessage(R.string.gyro_unavailable_body)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else {
                // A group stores its option's own action; re-picking the group keeps the option.
                val group = ActionGroups.groupOfEntry(action)
                prefs.setShoulderAction(
                    row.button,
                    if (group == null) action else if (group.option(stored) != null) stored else group.options.first().action,
                )
                updateShoulderRow(row)
                refresh()
                // A fresh macro hotkey has nothing to play yet: pick a saved one or record one right away.
                if (action == Prefs.SHOULDER_MACRO && stored != action && prefs.macro(row.button) == null) {
                    if (prefs.macroLibrary.isEmpty()) openMacroEditor(row) else openMacroLibrary(row)
                } else if (prefs.shoulderAction(row.button) != stored) {
                    startGuide(row)
                }
            }
        }.show()
    }

    /**
     * Leads through a freshly chosen action's options, one dialog after the other, in the order the card
     * shows them; options that only appear after an earlier answer join in. Leaving a dialog without an
     * answer ends the walk and keeps everything chosen so far.
     */
    private class OptionGuide(val row: BindingCard, skip: Set<View>) {
        val done = skip.toMutableSet()
        var pending = false
        var chosen = false
    }

    private var guide: OptionGuide? = null
    private var refreshingRows = false

    // Dialogs are judged once the activity holds the focus again: a dialog that opens a confirmation
    // right away hands it back only for a moment, so the check waits briefly and keeps only the latest.
    private val guideSettle = Runnable {
        val walk = guide ?: return@Runnable
        if (!hasWindowFocus()) return@Runnable
        if (walk.pending && !walk.chosen) guide = null else guideNext()
    }

    private fun startGuide(row: BindingCard, skip: Set<View> = emptySet()) {
        guide = OptionGuide(row, skip)
        // The action chooser is still closing; the walk starts when the page has the focus back.
        if (hasWindowFocus()) guideNext()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val decor = window.decorView
        decor.removeCallbacks(guideSettle)
        if (hasFocus && guide != null) decor.postDelayed(guideSettle, GUIDE_SETTLE_MS)
    }

    /** The options the walk still has to ask, as the card shows them now. */
    private fun guideSteps(walk: OptionGuide): List<View> = walk.row.card.optionRows().filter { option ->
        option !in walk.done && option.isEnabled && (toggleOf(option) != null || option.findViewWithTag<View>(CHEVRON_TAG) != null)
    }

    /** "Next" while the walk has another option to ask after this dialog, otherwise "OK". */
    private fun confirmLabel(): String {
        val walk = guide?.takeIf { it.pending } ?: return getString(android.R.string.ok)
        return getString(if (guideSteps(walk).isNotEmpty()) R.string.guide_next else android.R.string.ok)
    }

    /** Button › function, the path to what a dialog edits; the function stays while the steps change below it. */
    private fun crumb(row: BindingCard, function: String? = describer.functionName(prefs.shoulderAction(row.button))): String =
        function?.let { getString(R.string.dialog_breadcrumb, row.label, it) } ?: row.label

    private fun AlertDialog.Builder.bindingTitle(binding: String, titleRes: Int): AlertDialog.Builder =
        bindingTitle(binding, getString(titleRes))

    /** The dialog's title with the path to what it edits above it, small and in the accent colour of the section heads. */
    private fun AlertDialog.Builder.bindingTitle(binding: String, title: CharSequence): AlertDialog.Builder = setCustomTitle(
        LinearLayout(this@SettingsActivity).apply {
            tag = "settings_binding_title"
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(24), dp(18), dp(24), 0)
            addView(TextView(context).apply {
                tag = "settings_binding_title_button"
                text = binding
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                // A long path gets smaller rather than cut; only at the smallest size does it end in an ellipsis.
                setAutoSizeTextTypeUniformWithConfiguration(9, 13, 1, TypedValue.COMPLEX_UNIT_SP)
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.04f
                setTextColor(themedColor(android.R.attr.colorAccent, Color.parseColor("#6750A4")))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)))
            addView(TextView(context).apply {
                text = title
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_DialogWindowTitle)
                isAccessibilityHeading = true
                setPadding(0, dp(2), 0, 0)
            })
        },
    )

    /**
     * An answer per tap as before; the confirm button takes the checked answer as it stands, so a
     * preselected value needs no tap of its own.
     */
    private fun AlertDialog.Builder.chooseOne(
        labels: Array<out CharSequence>,
        checked: Int,
        onChoose: (DialogInterface, Int) -> Unit,
    ): AlertDialog.Builder = setSingleChoiceItems(labels, checked) { dialog, which -> onChoose(dialog, which) }
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(confirmLabel()) { dialog, _ ->
            onChoose(dialog, (dialog as AlertDialog).listView.checkedItemPosition.takeIf { it >= 0 } ?: checked)
        }

    private fun guideNext() {
        val walk = guide ?: return
        val next = guideSteps(walk).firstOrNull()
        if (next == null) {
            guide = null
            return
        }
        walk.done += next
        walk.pending = true
        walk.chosen = false
        val toggle = toggleOf(next)
        if (toggle != null) askToggle(toggle) else next.performClick()
    }

    private fun toggleOf(option: View): CompoundButton? =
        (option as? ViewGroup)?.let { row -> (0 until row.childCount).map(row::getChildAt).filterIsInstance<CompoundButton>().firstOrNull() }

    /** A switch has no dialog of its own; in the walk it is asked like the other options. */
    private fun askToggle(toggle: CompoundButton) {
        val choices = arrayOf(getString(R.string.settings_on), getString(R.string.settings_off))
        AlertDialog.Builder(this)
            .apply { guide?.let { bindingTitle(crumb(it.row), toggle.contentDescription) } ?: setTitle(toggle.contentDescription) }
            .chooseOne(choices, if (toggle.isChecked) 0 else 1) { dialog, which ->
                guide?.chosen = true
                dialog.dismiss()
                toggle.isChecked = which == 0
            }
            .show()
    }

    private fun showMacroUnavailable() {
        AlertDialog.Builder(this)
            .setTitle(R.string.macro_unavailable_title)
            .setMessage(R.string.macro_unavailable_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** [assign] false edits a library macro from the list: the hotkey keeps playing whatever it played before. */
    private fun openMacroEditor(row: BindingCard, initial: SavedMacro? = prefs.savedMacro(row.button), assign: Boolean = true) {
        if (macroEditor != null) return
        val editor = MacroEditor(this, row.label, initial, getString(R.string.macro_default_name, row.label), { prefs.macroLibrary }, {
            Settings.System.getInt(contentResolver, SwitcherService.CONTROLLER_FLIP_SETTING, 0) != 0
        }) { id, name, macro ->
            val saved = macro?.let { prefs.saveMacro(id, name, it).id }
            if (assign) prefs.setMacroRef(row.button, saved)
            // Other hotkeys may play the same library macro; their rows show its new name and length too.
            refresh()
        }
        macroEditor = editor
        editor.show().setOnDismissListener {
            editor.pause()
            macroEditor = null
        }
    }

    private fun openMacroLibrary(row: BindingCard) {
        MacroLibraryDialog(this, prefs, prefs.savedMacro(row.button)?.id, { saved ->
            if (saved == null) {
                openMacroEditor(row, null)
            } else {
                prefs.setMacroRef(row.button, saved.id)
                updateShoulderRow(row)
            }
        }, { saved ->
            // Editing the hotkey's own macro works as it always did, so "Save as new" hands the hotkey the variant.
            openMacroEditor(row, saved, assign = saved.id == prefs.savedMacro(row.button)?.id)
        }) { refresh() }.show()
    }

    /**
     * The apps a hotkey can open: "Ask" and "Favorites" choose when it is pressed, then the starred
     * apps and every other app. A star marks an app for every hotkey; the order follows the stars only
     * the next time the list opens, so nothing moves under the finger.
     */
    private fun chooseShoulderApp(row: BindingCard) {
        val favorites = prefs.favoriteApps
        val apps = allApps().sortedBy { it.value !in favorites }
        val current = prefs.shoulderAction(row.button)
        val accent = themedColor(android.R.attr.colorAccent, Color.parseColor("#6750A4"))
        val primary = themedColor(android.R.attr.textColorPrimary, Color.BLACK)
        fun background(attr: Int) = TypedValue().takeIf { theme.resolveAttribute(attr, it, true) }?.resourceId ?: 0
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        lateinit var dialog: AlertDialog
        fun entry(value: String, label: String, starred: Boolean?) {
            val chosen = value == current
            val line = LinearLayout(this).apply {
                tag = "settings_app_choice_$value"
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(24), 0, dp(12), 0)
                isFocusable = true
                isSelected = chosen
                setBackgroundResource(background(android.R.attr.selectableItemBackground))
                setOnClickListener {
                    prefs.setShoulderAction(row.button, value)
                    dialog.dismiss()
                    updateShoulderRow(row)
                    refresh()
                    if (value != current) startGuide(row)
                }
            }
            line.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(if (chosen) accent else primary)
                if (chosen) setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (starred != null) {
                var on: Boolean = starred
                line.addView(ImageButton(this).apply {
                    tag = "settings_app_star_$value"
                    setBackgroundResource(background(android.R.attr.selectableItemBackgroundBorderless))
                    fun paint() {
                        setImageResource(if (on) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
                        imageTintList = ColorStateList.valueOf(if (on) accent else primary)
                        contentDescription = getString(if (on) R.string.app_favorite_remove else R.string.app_favorite_add, label)
                    }
                    paint()
                    setOnClickListener {
                        on = !on
                        prefs.setFavorite(value, on)
                        paint()
                        updateShoulderRow(row)
                    }
                }, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
            list.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        entry(Prefs.SHOULDER_APP_ASK, getString(R.string.ayn_modal), null)
        entry(Prefs.SHOULDER_APP_FAVORITES, getString(R.string.shoulder_app_favorites), null)
        list.addView(View(this).apply {
            setBackgroundColor((primary and 0x00FFFFFF) or 0x33000000)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(dp(24), dp(4), dp(24), dp(4)) })
        apps.forEach { entry(it.value, it.label, it.value in favorites) }
        dialog = AlertDialog.Builder(this)
            .bindingTitle(crumb(row, getString(R.string.shoulder_launch_app)), R.string.shoulder_choose_app_title)
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun chooseOption(row: BindingCard) {
        val action = prefs.shoulderAction(row.button)
        Ayn.groupOf(action)?.let { return chooseAynOption(row, it) }
        val group = ActionGroups.groupOf(action) ?: return
        val labels = group.options.map { getString(it.labelRes) }
        AlertDialog.Builder(this)
            .bindingTitle(crumb(row), row.option?.title?.text ?: getString(group.titleRes))
            .chooseOne(labels.toTypedArray(), group.options.indexOfFirst { it.action == action }.coerceAtLeast(0)) { dialog, which ->
                prefs.setShoulderAction(row.button, group.options[which].action)
                dialog.dismiss()
                updateShoulderRow(row)
                refresh()
            }
            .show()
    }

    private fun chooseAynOption(row: BindingCard, group: Ayn.Group) {
        val choices = describer.aynOptions(group)
        val selected = prefs.aynOption(row.button, group)
        AlertDialog.Builder(this)
            .bindingTitle(crumb(row), row.option?.title?.text ?: getString(group.titleRes))
            .chooseOne(choices.map { it.label }.toTypedArray(), choices.indexOfFirst { it.value == selected }.coerceAtLeast(0)) { dialog, which ->
                prefs.setAynOption(row.button, choices[which].value)
                dialog.dismiss()
                updateShoulderRow(row)
            }
            .show()
    }

    private fun chooseTarget(row: BindingCard) {
        val focus = prefs.shoulderAction(row.button) == Prefs.SHOULDER_CONTROLLER_FOCUS
        val targets = if (focus) describer.focusTargets() else describer.levelTargets(prefs.shoulderAction(row.button))
        val selected = if (focus) prefs.focusTarget(row.button) else prefs.levelTarget(row.button)
        AlertDialog.Builder(this)
            .bindingTitle(crumb(row), if (focus) R.string.focus_target_dialog_title else R.string.level_target_dialog_title)
            .chooseOne(
                targets.map { it.label }.toTypedArray(),
                targets.indexOfFirst { it.value == selected }.coerceAtLeast(0),
            ) { dialog, which ->
                if (focus) prefs.setFocusTarget(row.button, targets[which].value)
                else prefs.setLevelTarget(row.button, targets[which].value)
                dialog.dismiss()
                updateShoulderRow(row)
            }
            .show()
    }

    private fun chooseShoulderDisplay(row: BindingCard) {
        // Ask is offered where an app opens; Home goes to a fixed screen or the focused one.
        val targets = if (Prefs.launchesApp(prefs.shoulderAction(row.button))) describer.launchScreens() else describer.homeScreens()
        AlertDialog.Builder(this)
            .bindingTitle(crumb(row), R.string.level_target_dialog_title)
            .chooseOne(
                targets.map { it.label }.toTypedArray(),
                targets.indexOfFirst { it.value == prefs.shoulderDisplay(row.button) }.coerceAtLeast(0),
            ) { dialog, which ->
                prefs.setShoulderDisplay(row.button, targets[which].value)
                dialog.dismiss()
                updateShoulderRow(row)
            }
            .show()
    }

    private fun chooseAppControl(row: BindingCard, mode: Boolean) {
        val choices = if (mode) describer.appModes() else describer.appScreens()
        val selected = if (mode) prefs.appMode(row.button) else prefs.appScreen(row.button)
        AlertDialog.Builder(this)
            .bindingTitle(crumb(row), if (mode) R.string.app_control_mode_dialog_title else R.string.app_control_screen_dialog_title)
            .chooseOne(
                choices.map { it.label }.toTypedArray(),
                choices.indexOfFirst { it.value == selected }.coerceAtLeast(0),
            ) { dialog, which ->
                dialog.dismiss()
                val value = choices[which].value
                if (!mode) {
                    prefs.setAppScreen(row.button, value)
                    updateShoulderRow(row)
                } else if (value != Prefs.APP_MINIMIZE && prefs.appMode(row.button) != value) {
                    // The close skips the app's own save path, so say so before it can be assigned;
                    // a restart closes the same way before it reopens the app.
                    val restart = value == Prefs.APP_RESTART
                    AlertDialog.Builder(this)
                        .bindingTitle(crumb(row), if (restart) R.string.app_control_restart else R.string.app_control_force_close)
                        .setMessage(R.string.app_control_force_close_warning)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(if (restart) R.string.app_control_restart_use else R.string.app_control_force_close_use) { _, _ ->
                            prefs.setAppMode(row.button, value)
                            updateShoulderRow(row)
                        }
                        .show()
                } else {
                    prefs.setAppMode(row.button, value)
                    updateShoulderRow(row)
                }
            }
            .show()
    }

    /** Dual mode's column heads above a page's bindings. */
    private fun dualHeader(page: SettingsPage) = page.dualHeader(
        R.drawable.ic_exc_home, getString(R.string.dual_column_home), R.drawable.ic_exc_back, getString(R.string.dual_column_back),
    )

    /**
     * One binding's card, or in dual mode the Home layer's and the Back layer's side by side. Back + Home
     * has no Home counterpart, so its Home side stays empty.
     */
    private fun buildShoulderRow(
        page: SettingsPage,
        button: ComboButton,
        titleRes: Int,
        extra: (SettingsPage.Card.(ComboButton) -> View)? = null,
    ) {
        if (!prefs.dualMode) return buildBindingCard(page, button, titleRes, extra)
        val (home, back) = page.columns()
        if (button in Prefs.HOME_LAYER_BASES) buildBindingCard(home, HomeLayerButton.of(button), titleRes, extra)
        buildBindingCard(back, button, titleRes, extra)
    }

    private fun buildBindingCard(
        page: SettingsPage,
        button: ComboButton,
        titleRes: Int,
        extra: (SettingsPage.Card.(ComboButton) -> View)?,
    ) {
        val row = BindingCard(page, button, titleRes, extra, this, prefs, describer, cardHost)
        comboRows[button.prefKey] = row
        updateShoulderRow(row)
    }

    private val cardHost = object : BindingCard.Host {
        override fun chooseAction(card: BindingCard) = chooseShoulderAction(card)
        override fun chooseDisplay(card: BindingCard) = chooseShoulderDisplay(card)
        override fun chooseTarget(card: BindingCard) = this@SettingsActivity.chooseTarget(card)
        override fun chooseOption(card: BindingCard) = this@SettingsActivity.chooseOption(card)
        override fun choosePosition(card: BindingCard, record: Boolean) = this@SettingsActivity.choosePosition(card, record)
        override fun choosePushToTalkKey(card: BindingCard) = this@SettingsActivity.choosePushToTalkKey(card)
        override fun chooseRecordDuck(card: BindingCard) = this@SettingsActivity.chooseRecordDuck(card)
        override fun chooseRecordQuality(card: BindingCard) = this@SettingsActivity.chooseRecordQuality(card)
        override fun chooseAppControl(card: BindingCard, mode: Boolean) = this@SettingsActivity.chooseAppControl(card, mode)
        override fun openMacroLibrary(card: BindingCard) =
            if (PService.cachedAvailable()) this@SettingsActivity.openMacroLibrary(card) else showMacroUnavailable()
        override fun openGyroSettings(card: BindingCard) = this@SettingsActivity.openGyroSettings(card)
        override fun changed(card: BindingCard) = updateShoulderRow(card)
    }

    private fun updateShoulderRow(row: BindingCard) {
        // A guided dialog that writes its choice updates its row itself; a page-wide refresh says nothing about it.
        guide?.let { if (it.row === row && it.pending && !refreshingRows) it.chosen = true }
        row.update()
    }

    private fun explainServiceRepair() {
        AlertDialog.Builder(this)
            .setTitle(R.string.repair_service_title)
            .setMessage(R.string.repair_service_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.open_a11y) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .show()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data?.takeIf { resultCode == RESULT_OK } ?: return
        backup.onResult(requestCode, uri)
    }

    /** What a picked file leads to: a refusal, or the version hint and the preview of every change. */
    internal fun offerBackup(bytes: ByteArray?) = backup.offer(bytes)

    /** Dual mode's Unbind asks first which layer it reaches; the answer goes to [then]. */
    private fun chooseLayers(then: (Set<BindingLayer>) -> Unit) {
        val choices = listOf(
            R.string.unbind_layer_home to setOf(BindingLayer.HOME),
            R.string.unbind_layer_back to setOf(BindingLayer.BACK),
            R.string.unbind_layer_both to setOf(BindingLayer.HOME, BindingLayer.BACK),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.unbind_layer_title)
            .setItems(choices.map { getString(it.first) }.toTypedArray()) { _, which -> then(choices[which].second) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showChanges(plan: SettingsChangePlan) {
        val scope = getString(plan.section?.titleRes ?: R.string.settings_entire_app)
        val restore = plan.action == SettingsChangeAction.RESTORE
        val preset = plan.action == SettingsChangeAction.PRESET
        val defaults = plan.action == SettingsChangeAction.DEFAULTS
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        fun line(text: String) {
            content.addView(TextView(this).apply {
                this.text = text
                textSize = 15f
                setPadding(0, dp(8), 0, dp(8))
            })
        }
        if (plan.isEmpty) line(getString(when {
            restore -> R.string.backup_matches
            defaults -> R.string.settings_already_defaults
            preset -> R.string.settings_already_preset
            else -> R.string.settings_already_unbound
        }))
        else {
            line(getString(R.string.settings_changes_scope, scope))
            changeDescriber.lines(plan).forEach(::line)
            if (plan.removesToggle) line(getString(R.string.settings_unbind_reactivates))
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(when {
                restore -> getString(R.string.backup_restore_title)
                defaults -> getString(R.string.settings_load_defaults)
                preset -> getString(R.string.settings_preset_title, scope)
                else -> getString(R.string.settings_unbind_title, scope)
            })
            .setView(ScrollView(this).apply { addView(content) })
        if (plan.isEmpty) builder.setPositiveButton(android.R.string.ok, null)
        else builder.setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_apply) { _, _ ->
                if (prefs.applySettings(plan.before, plan.after)) {
                    invalidatePages()
                    Toast.makeText(this, when {
                        restore -> R.string.backup_restored
                        defaults -> R.string.settings_defaults_loaded
                        preset -> R.string.reset_settings_done
                        else -> R.string.unbind_optionals_done
                    }, Toast.LENGTH_SHORT).show()
                } else {
                    // A preview is a promise: changed preferences require a new confirmation.
                    showChanges(if (restore) plan.copy(before = prefs.snapshot())
                    else SettingsChangePlan.create(prefs.snapshot(), plan.section, plan.action, plan.layers))
                }
            }
        builder.show()
    }

    private fun invalidatePages() {
        layoutDual = prefs.dualMode
        exceptionsPage?.dispose()
        exceptionsPage = null
        pages.clear()
        stickPages.clear()
        stickBoxes.clear()
        stickTabBar = null
        comboRows.clear()
        toggleButtonsValue = null
        toggleDurationOptions = null
        statusLabel = null
        repairHandle = null
        updateHandle = null
        navigation.invalidatePages()
        refresh()
    }

    private fun checkForUpdates() {
        updateHandle?.let(updateFlow::checkNow)
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"

    /** Where release checks go; activity tests substitute a local fake so nothing reaches the network. */
    internal open fun updateCheck(): UpdateCheck = UpdateCheck(UpdateChecker::checkInBackground)

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    companion object {
        const val EXTRA_INDICATOR_BUTTON = "indicator_button"
        private const val REQUEST_NOTIFICATIONS = 1
        private const val ACTION_APP = BindingDescriber.ACTION_APP
        private const val GUIDE_SETTLE_MS = 150L
        private const val CHEVRON_TAG = "settings_chooser_chevron"
        /** The pages dual mode splits into a Home and a Back column. */
        private val DUAL_SECTIONS = setOf(
            SettingsSection.BASIC, SettingsSection.FACE, SettingsSection.SHOULDERS, SettingsSection.DPAD, SettingsSection.STICKS,
        )
    }
}
