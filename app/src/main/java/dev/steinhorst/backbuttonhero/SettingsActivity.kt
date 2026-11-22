package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import dev.steinhorst.backbuttonhero.pad.GyroAxis
import dev.steinhorst.backbuttonhero.pad.GyroSettings
import dev.steinhorst.backbuttonhero.prefs.AppException
import dev.steinhorst.backbuttonhero.prefs.BindingLayer
import dev.steinhorst.backbuttonhero.prefs.BindingSettings
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.prefs.SettingsBackup
import dev.steinhorst.backbuttonhero.prefs.SettingsChangeAction
import dev.steinhorst.backbuttonhero.prefs.SettingsChangePlan

/** Configuration pages share the same rows and keep their state while navigating. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var navigation: SettingsNavigation
    private val pages = mutableMapOf<SettingsSection, SettingsPage>()
    private var exceptionsPage: ExceptionSettingsPage? = null
    private lateinit var warnings: LinearLayout
    private val appLabels = mutableMapOf<String, String>()
    private lateinit var serviceWarning: TextView
    private lateinit var rootWarning: SettingsPage.WarningHandle
    private var toggleButtonsValue: TextView? = null
    /** The hold-time slider below the on/off shortcut; without a shortcut there is nothing to time. */
    private var toggleDurationOptions: View? = null
    private var statusLabel: TextView? = null
    private val comboRows = mutableMapOf<String, ComboRow>()
    private val stickPages = mutableListOf<SettingsPage>()
    private val stickBoxes = mutableMapOf<Stick, LinearLayout>()
    private var stickTabBar: LinearLayout? = null
    private var stickTab = Stick.LEFT
    /** Which layer the overview draws in dual mode. */
    private var overviewHome = false
    private var repairHandle: SettingsPage.ActionHandle? = null
    private var updateHandle: SettingsPage.ActionHandle? = null
    private var updateDialog: AlertDialog? = null
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
        UpdateChecker.checkInBackground(this)
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
        appLabels.clear()
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
        updateDialog?.dismiss()
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

    /** A short name for the overview's narrow columns; the pages keep the full wording. */
    private fun overviewLabel(action: String): String {
        val res = when (action) {
            Prefs.SHOULDER_BACK -> R.string.overview_action_back
            Prefs.SHOULDER_RECENTS -> R.string.overview_action_recents_top
            Prefs.SHOULDER_SWITCHER -> R.string.overview_action_recents_bottom
            Prefs.SHOULDER_SWITCH_APP -> R.string.overview_action_switch_app
            Prefs.SHOULDER_LAST_APP -> R.string.overview_action_last_app
            Prefs.SHOULDER_SCREENSHOT -> R.string.overview_action_screenshot
            Prefs.SHOULDER_CONTROLLER_FOCUS -> R.string.overview_action_controller_focus
            Prefs.SHOULDER_GYRO -> R.string.overview_action_gyro
            Prefs.SHOULDER_GYRO_CALIBRATE -> R.string.overview_action_gyro_calibrate
            Prefs.SHOULDER_QUICK_SETTINGS -> R.string.overview_action_quick_settings
            Prefs.SHOULDER_NOTIFICATIONS -> R.string.overview_action_notifications
            Prefs.SHOULDER_INTERNET -> R.string.overview_action_internet
            Prefs.SHOULDER_BLUETOOTH -> R.string.overview_action_bluetooth
            Prefs.SHOULDER_NIGHT_LIGHT -> R.string.overview_action_night_light
            Prefs.SHOULDER_EXTRA_DIM -> R.string.overview_action_extra_dim
            Prefs.SHOULDER_DISPLAY_SETTINGS -> R.string.overview_action_display
            Prefs.SHOULDER_SOUND_SETTINGS -> R.string.overview_action_sound
            Prefs.SHOULDER_LOCATION_SETTINGS -> R.string.overview_action_location
            Prefs.SHOULDER_CAST_SETTINGS -> R.string.overview_action_cast
            Prefs.SHOULDER_DND_SETTINGS -> R.string.overview_action_dnd
            Prefs.SHOULDER_BATTERY_SAVER -> R.string.overview_action_battery_saver
            Prefs.SHOULDER_VOLUME_UP -> R.string.overview_action_volume_up
            Prefs.SHOULDER_VOLUME_DOWN -> R.string.overview_action_volume_down
            Prefs.SHOULDER_VOLUME_MUTE -> R.string.overview_action_mute
            Prefs.SHOULDER_BRIGHTNESS_UP -> R.string.overview_action_brightness_up
            Prefs.SHOULDER_BRIGHTNESS_DOWN -> R.string.overview_action_brightness_down
            Prefs.SHOULDER_FLICK_LEFT -> R.string.overview_action_flick_left
            Prefs.SHOULDER_FLICK_RIGHT -> R.string.overview_action_flick_right
            Prefs.SHOULDER_MEDIA_PLAY_PAUSE -> R.string.overview_action_play_pause
            Prefs.SHOULDER_MEDIA_NEXT -> R.string.overview_action_next
            Prefs.SHOULDER_MEDIA_PREVIOUS -> R.string.overview_action_previous
            Prefs.SHOULDER_CLOSE_APPS -> R.string.overview_action_close_all
            Prefs.SHOULDER_CLOSE_BACKGROUND_APPS -> R.string.overview_action_close_background
            Prefs.SHOULDER_POWER_MENU -> R.string.overview_action_power_menu
            Prefs.SHOULDER_STANDBY -> R.string.overview_action_standby
            Prefs.SHOULDER_APP_ASK -> R.string.overview_action_app_ask
            Prefs.SHOULDER_APP_FAVORITES -> R.string.overview_action_app_favorites
            else -> null
        }
        if (res != null) return getString(res)
        if (Prefs.isShoulderApp(action)) {
            return runCatching { getString(R.string.overview_action_open_app, packageManager.getApplicationInfo(action, 0).loadLabel(packageManager)) }
                .getOrDefault(getString(R.string.shoulder_launch_app))
        }
        return describeAction(action)
    }

    /** One group per physical control, holding only the bindings that are assigned; [home] draws dual mode's Home layer. */
    private fun overviewGroups(home: Boolean = false): List<OverviewGroup> {
        fun label(control: ComboButton): String? {
            val button = if (home) HomeLayerButton.of(control) else control
            val action = prefs.shoulderAction(button).ifEmpty { return null }
            // AYN's switches name their chosen value too, like "Performance · Max".
            val ayn = describeAynOption(button).takeIf { Ayn.groupOf(action) != null && it.isNotEmpty() }
            return ayn?.let { getString(R.string.overview_grouped_action, describeAction(action), it) } ?: overviewLabel(action)
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
            actionRow(R.string.backup_save, R.string.backup_save_hint) { startBackupSave() }
            actionRow(R.string.backup_load, R.string.backup_load_hint) { startBackupLoad() }
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

    private fun toggleButtonsLabel(): String = toggleLabel(prefs.toggleButtons)

    private fun toggleLabel(value: String): String = ServiceToggleGesture.label(value) ?: getString(R.string.shoulder_none)

    private fun chooseToggleButtons() {
        val values = listOf(Prefs.TOGGLE_L1_R1, Prefs.TOGGLE_L2_R2, Prefs.TOGGLE_BOTH, Prefs.TOGGLE_START_SELECT,
            Prefs.TOGGLE_BACK_HOME, Prefs.TOGGLE_NONE)
        AlertDialog.Builder(this)
            .setTitle(R.string.service_toggle_buttons)
            .setSingleChoiceItems(values.map(::toggleLabel).toTypedArray(), values.indexOf(prefs.toggleButtons)) { dialog, which ->
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

    private data class Choice(val value: String, val label: String)

    /** Every launchable app on the device, shown only after choosing "Launch app". */
    private fun allApps(): List<Choice> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).map {
            Choice(it.activityInfo.packageName, it.loadLabel(packageManager).toString())
        }.distinctBy { it.value }.sortedBy { it.label }
    }

    /** [macro]: whether the list offers macros; Back's own gestures cannot play them. */
    private fun shoulderActions(macro: Boolean = true): List<Choice> {
        fun group(g: ActionGroups.Group) = Choice(g.entry, getString(g.titleRes))
        return listOfNotNull(
            Choice("", getString(R.string.shoulder_none)),
            Choice(ACTION_APP, getString(R.string.shoulder_launch_app)),
            Choice(Prefs.SHOULDER_HOME, getString(R.string.shoulder_home)),
            Choice(Prefs.SHOULDER_BACK, getString(R.string.shoulder_back)),
            group(ActionGroups.Group.APP_SWITCHER),
            Choice(Prefs.SHOULDER_SWAP_APPS, getString(R.string.shoulder_swap_apps)),
            Choice(Prefs.SHOULDER_LAST_APP, getString(R.string.shoulder_last_app)),
            Choice(Prefs.SHOULDER_SCREENSHOT, getString(R.string.shoulder_screenshot)),
            Choice(Prefs.SHOULDER_RECORD_VIDEO, getString(R.string.shoulder_record_video)),
            Choice(Prefs.SHOULDER_MACRO, getString(R.string.shoulder_macro)).takeIf { macro },
            Choice(Prefs.SHOULDER_GYRO, getString(R.string.shoulder_gyro)),
            Choice(Prefs.SHOULDER_GYRO_CALIBRATE, getString(R.string.shoulder_gyro_calibrate)),
            group(ActionGroups.Group.FLICK),
            group(ActionGroups.Group.VOLUME),
            group(ActionGroups.Group.BRIGHTNESS),
            group(ActionGroups.Group.MEDIA),
            Choice(Prefs.SHOULDER_CONTROLLER_FOCUS, getString(R.string.shoulder_controller_focus)),
            Choice(Prefs.SHOULDER_SWITCH_APP, getString(R.string.shoulder_switch_app)),
            Choice(Prefs.SHOULDER_APP_CONTROL, getString(R.string.shoulder_app_control)),
            group(ActionGroups.Group.CLOSE_APPS),
            group(ActionGroups.Group.POWER),
            Choice(Prefs.SHOULDER_STATUS, getString(R.string.shoulder_status)),
        ) + aynChoices() + listOf(group(ActionGroups.Group.PANELS), group(ActionGroups.Group.SETTINGS))
    }

    /** One entry per AYN tile; its options are chosen on the row, like a volume action's screen. */
    private fun aynChoices(): List<Choice> =
        Ayn.Group.values().map { Choice(it.action, getString(it.titleRes)) } +
            Ayn.Toggle.values().map { Choice(it.action, getString(it.titleRes)) }

    /** What an entry of the action list does, shown below its name; "Not set" needs none. */
    private fun actionDescription(value: String): Int? = when (value) {
        ACTION_APP -> R.string.action_desc_launch_app
        Prefs.SHOULDER_HOME -> R.string.action_desc_home
        Prefs.SHOULDER_BACK -> R.string.action_desc_back
        ActionGroups.Group.APP_SWITCHER.entry -> R.string.action_desc_app_switcher
        Prefs.SHOULDER_SWAP_APPS -> R.string.action_desc_swap_apps
        Prefs.SHOULDER_SCREENSHOT -> R.string.action_desc_screenshot
        Prefs.SHOULDER_RECORD_VIDEO -> R.string.action_desc_record_video
        Prefs.SHOULDER_MACRO -> R.string.action_desc_macro
        Prefs.SHOULDER_GYRO -> R.string.action_desc_gyro
        Prefs.SHOULDER_GYRO_CALIBRATE -> R.string.action_desc_gyro_calibrate
        ActionGroups.Group.FLICK.entry -> R.string.action_desc_flick
        ActionGroups.Group.VOLUME.entry -> R.string.action_desc_volume
        ActionGroups.Group.BRIGHTNESS.entry -> R.string.action_desc_brightness
        ActionGroups.Group.MEDIA.entry -> R.string.action_desc_media
        Prefs.SHOULDER_CONTROLLER_FOCUS -> R.string.action_desc_controller_focus
        Prefs.SHOULDER_SWITCH_APP -> R.string.action_desc_switch_app
        Prefs.SHOULDER_LAST_APP -> R.string.action_desc_last_app
        Prefs.SHOULDER_APP_CONTROL -> R.string.action_desc_app_control
        ActionGroups.Group.CLOSE_APPS.entry -> R.string.action_desc_close_apps
        ActionGroups.Group.POWER.entry -> R.string.action_desc_power
        Prefs.SHOULDER_STATUS -> R.string.action_desc_status
        Ayn.Group.PERFORMANCE.action -> R.string.action_desc_performance
        Ayn.Group.FAN.action -> R.string.action_desc_fan
        Ayn.Group.HANDLE.action -> R.string.action_desc_handle
        Ayn.Group.DISPLAY.action -> R.string.action_desc_display
        Ayn.Group.REFRESH_RATE.action -> R.string.action_desc_refresh_rate
        Ayn.Group.STICK_LIGHT.action -> R.string.action_desc_stick_light
        Ayn.Toggle.DIRECT_POWER.action -> R.string.action_desc_direct_power
        Ayn.Toggle.VIBRATION.action -> R.string.action_desc_vibration
        Ayn.Toggle.FORCE_LANDSCAPE.action -> R.string.action_desc_force_landscape
        Ayn.Toggle.VIRTUAL_MOUSE.action -> R.string.action_desc_virtual_mouse
        Ayn.Toggle.DO_NOT_DISTURB.action -> R.string.action_desc_do_not_disturb
        Ayn.Toggle.MUTE_MICROPHONE.action -> R.string.action_desc_mute_microphone
        ActionGroups.Group.PANELS.entry -> R.string.action_desc_panels
        ActionGroups.Group.SETTINGS.entry -> R.string.action_desc_settings
        else -> null
    }

    /**
     * Words the action search also finds for an entry, beyond its name and description: what players
     * actually type. [key] is the action, or "tile action:option" for an option inside an AYN tile,
     * whose option keys repeat between tiles.
     */
    private fun searchKeywords(key: String): String = when (key) {
        "" -> R.string.search_keywords_none
        ACTION_APP -> R.string.search_keywords_launch_app
        Prefs.SHOULDER_HOME -> R.string.search_keywords_home
        Prefs.SHOULDER_BACK -> R.string.search_keywords_back
        ActionGroups.Group.APP_SWITCHER.entry -> R.string.search_keywords_app_switcher
        Prefs.SHOULDER_RECENTS -> R.string.search_keywords_recents
        Prefs.SHOULDER_SWITCHER -> R.string.search_keywords_switcher
        Prefs.SHOULDER_SWAP_APPS -> R.string.search_keywords_swap_apps
        Prefs.SHOULDER_LAST_APP -> R.string.search_keywords_last_app
        Prefs.SHOULDER_SCREENSHOT -> R.string.search_keywords_screenshot
        Prefs.SHOULDER_RECORD_VIDEO -> R.string.search_keywords_record_video
        Prefs.SHOULDER_MACRO -> R.string.search_keywords_macro
        Prefs.SHOULDER_GYRO -> R.string.search_keywords_gyro
        Prefs.SHOULDER_GYRO_CALIBRATE -> R.string.search_keywords_gyro_calibrate
        ActionGroups.Group.FLICK.entry -> R.string.search_keywords_flick
        Prefs.SHOULDER_FLICK_LEFT -> R.string.search_keywords_flick_left
        Prefs.SHOULDER_FLICK_RIGHT -> R.string.search_keywords_flick_right
        ActionGroups.Group.VOLUME.entry -> R.string.search_keywords_volume
        Prefs.SHOULDER_VOLUME_UP -> R.string.search_keywords_volume_up
        Prefs.SHOULDER_VOLUME_DOWN -> R.string.search_keywords_volume_down
        Prefs.SHOULDER_VOLUME_MUTE -> R.string.search_keywords_volume_mute
        ActionGroups.Group.BRIGHTNESS.entry -> R.string.search_keywords_brightness
        Prefs.SHOULDER_BRIGHTNESS_UP -> R.string.search_keywords_brightness_up
        Prefs.SHOULDER_BRIGHTNESS_DOWN -> R.string.search_keywords_brightness_down
        ActionGroups.Group.MEDIA.entry -> R.string.search_keywords_media
        Prefs.SHOULDER_MEDIA_PLAY_PAUSE -> R.string.search_keywords_media_play_pause
        Prefs.SHOULDER_MEDIA_NEXT -> R.string.search_keywords_media_next
        Prefs.SHOULDER_MEDIA_PREVIOUS -> R.string.search_keywords_media_previous
        Prefs.SHOULDER_CONTROLLER_FOCUS -> R.string.search_keywords_controller_focus
        Prefs.SHOULDER_SWITCH_APP -> R.string.search_keywords_switch_app
        Prefs.SHOULDER_APP_CONTROL -> R.string.search_keywords_app_control
        ActionGroups.Group.CLOSE_APPS.entry -> R.string.search_keywords_close_apps_group
        Prefs.SHOULDER_CLOSE_APPS -> R.string.search_keywords_close_apps
        Prefs.SHOULDER_CLOSE_BACKGROUND_APPS -> R.string.search_keywords_close_background
        ActionGroups.Group.POWER.entry -> R.string.search_keywords_power
        Prefs.SHOULDER_POWER_MENU -> R.string.search_keywords_power_menu
        Prefs.SHOULDER_STANDBY -> R.string.search_keywords_standby
        Prefs.SHOULDER_STATUS -> R.string.search_keywords_status
        ActionGroups.Group.PANELS.entry -> R.string.search_keywords_panels
        Prefs.SHOULDER_QUICK_SETTINGS -> R.string.search_keywords_quick_settings
        Prefs.SHOULDER_NOTIFICATIONS -> R.string.search_keywords_notifications
        ActionGroups.Group.SETTINGS.entry -> R.string.search_keywords_settings
        Prefs.SHOULDER_INTERNET -> R.string.search_keywords_internet
        Prefs.SHOULDER_BLUETOOTH -> R.string.search_keywords_bluetooth
        Prefs.SHOULDER_NIGHT_LIGHT -> R.string.search_keywords_night_light
        Prefs.SHOULDER_EXTRA_DIM -> R.string.search_keywords_extra_dim
        Prefs.SHOULDER_DISPLAY_SETTINGS -> R.string.search_keywords_display_settings
        Prefs.SHOULDER_SOUND_SETTINGS -> R.string.search_keywords_sound_settings
        Prefs.SHOULDER_LOCATION_SETTINGS -> R.string.search_keywords_location_settings
        Prefs.SHOULDER_CAST_SETTINGS -> R.string.search_keywords_cast_settings
        Prefs.SHOULDER_DND_SETTINGS -> R.string.search_keywords_dnd_settings
        Prefs.SHOULDER_BATTERY_SAVER -> R.string.search_keywords_battery_saver
        Ayn.Group.PERFORMANCE.action -> R.string.search_keywords_performance
        "${Ayn.Group.PERFORMANCE.action}:standard" -> R.string.search_keywords_performance_standard
        "${Ayn.Group.PERFORMANCE.action}:medium" -> R.string.search_keywords_performance_medium
        "${Ayn.Group.PERFORMANCE.action}:high" -> R.string.search_keywords_performance_high
        Ayn.Group.FAN.action -> R.string.search_keywords_fan
        "${Ayn.Group.FAN.action}:off" -> R.string.search_keywords_fan_off
        "${Ayn.Group.FAN.action}:quiet" -> R.string.search_keywords_fan_quiet
        "${Ayn.Group.FAN.action}:smart" -> R.string.search_keywords_fan_smart
        "${Ayn.Group.FAN.action}:sports" -> R.string.search_keywords_fan_sports
        Ayn.Group.HANDLE.action -> R.string.search_keywords_handle
        "${Ayn.Group.HANDLE.action}:standard" -> R.string.search_keywords_handle_standard
        "${Ayn.Group.HANDLE.action}:xbox" -> R.string.search_keywords_handle_xbox
        "${Ayn.Group.HANDLE.action}:ban" -> R.string.search_keywords_handle_ban
        Ayn.Group.DISPLAY.action -> R.string.search_keywords_display
        "${Ayn.Group.DISPLAY.action}:top" -> R.string.search_keywords_display_top
        "${Ayn.Group.DISPLAY.action}:bottom" -> R.string.search_keywords_display_bottom
        Ayn.Group.REFRESH_RATE.action -> R.string.search_keywords_refresh_rate
        "${Ayn.Group.REFRESH_RATE.action}:60" -> R.string.search_keywords_refresh_60
        "${Ayn.Group.REFRESH_RATE.action}:120" -> R.string.search_keywords_refresh_120
        Ayn.Group.STICK_LIGHT.action -> R.string.search_keywords_stick_light
        Ayn.Toggle.DIRECT_POWER.action -> R.string.search_keywords_direct_power
        Ayn.Toggle.VIBRATION.action -> R.string.search_keywords_vibration
        Ayn.Toggle.FORCE_LANDSCAPE.action -> R.string.search_keywords_force_landscape
        Ayn.Toggle.VIRTUAL_MOUSE.action -> R.string.search_keywords_virtual_mouse
        Ayn.Toggle.DO_NOT_DISTURB.action -> R.string.search_keywords_do_not_disturb
        Ayn.Toggle.MUTE_MICROPHONE.action -> R.string.search_keywords_mute_microphone
        else -> null
    }?.let(::getString).orEmpty()

    /**
     * What the search finds inside groups and AYN tiles: each option as "Group › Option", carrying the
     * group's description. Picking one sets the group and its option in one go.
     */
    private fun actionOptions(choices: List<Choice>): List<ActionChooser.Entry> = choices.flatMap { choice ->
        val description = actionDescription(choice.value)?.let(::getString)
        fun entry(option: String, label: String, key: String = option) =
            ActionChooser.Entry(choice.value, getString(R.string.action_search_option, choice.label, label), description, option, searchKeywords(key))
        ActionGroups.groupOfEntry(choice.value)?.options?.map { entry(it.action, getString(it.labelRes)) }
            ?: Ayn.groupOf(choice.value)?.let { group -> aynOptions(group).map { entry(it.value, it.label, "${group.action}:${it.value}") } }
            ?: emptyList()
    }

    private fun aynOptions(group: Ayn.Group): List<Choice> =
        (if (group.hasModal) listOf(Choice(Ayn.MODAL, getString(R.string.ayn_modal))) else emptyList()) +
            group.options.map { Choice(it.key, getString(it.labelRes)) }

    private fun describeOption(button: ComboButton): String {
        val action = prefs.shoulderAction(button)
        ActionGroups.groupOf(action)?.let { group -> return getString(checkNotNull(group.option(action)).labelRes) }
        return describeAynOption(button)
    }

    private fun describeAynOption(button: ComboButton): String {
        val group = Ayn.groupOf(prefs.shoulderAction(button)) ?: return ""
        val chosen = prefs.aynOption(button, group)
        return aynOptions(group).first { it.value == chosen }.label
    }

    private fun describeShoulderAction(button: ComboButton): String = describeAction(prefs.shoulderAction(button))

    private fun describeAction(action: String): String {
        return when (action) {
            "" -> getString(R.string.shoulder_none)
            Prefs.SHOULDER_APP_ASK -> getString(R.string.shoulder_launch_app_named, getString(R.string.ayn_modal))
            // An empty list would leave the hotkey with nothing to offer, so the row says so.
            Prefs.SHOULDER_APP_FAVORITES -> if (prefs.favoriteApps.isEmpty()) getString(R.string.shoulder_launch_app_favorites_empty)
                else getString(R.string.shoulder_launch_app_named, getString(R.string.shoulder_app_favorites))
            else -> ActionGroups.groupOf(action)?.let { getString(it.titleRes) } ?: shoulderActions().firstOrNull { it.value == action }?.label
                ?: runCatching {
                    appLabels.getOrPut(action) {
                        getString(R.string.shoulder_launch_app_named, packageManager.getApplicationInfo(action, 0).loadLabel(packageManager))
                    }
                }.getOrDefault(getString(R.string.shoulder_launch_app))
        }
    }

    private fun appModes(): List<Choice> = listOf(
        Choice(Prefs.APP_MINIMIZE, getString(R.string.app_control_minimize)),
        Choice(Prefs.APP_FORCE_CLOSE, getString(R.string.app_control_force_close)),
        Choice(Prefs.APP_RESTART, getString(R.string.app_control_restart)),
    )

    // The same screens as everywhere else, plus the prompt.
    private fun appScreens(): List<Choice> = levelTargets() + Choice(Prefs.APP_SCREEN_ASK, getString(R.string.ayn_modal))

    private fun describeAppMode(button: ComboButton): String =
        (appModes().firstOrNull { it.value == prefs.appMode(button) } ?: appModes().first()).label

    private fun describeAppScreen(button: ComboButton): String =
        (appScreens().firstOrNull { it.value == prefs.appScreen(button) } ?: appScreens().last()).label

    /** Every screen choice lists top, bottom, then both; special values like the prompt follow. */
    private fun screenTargets(): List<Choice> = listOf(
        Choice(Prefs.DISPLAY_TOP, getString(R.string.level_target_top)),
        Choice(Prefs.DISPLAY_BOTTOM, getString(R.string.level_target_bottom)),
    )

    private fun levelTargets(): List<Choice> = screenTargets() + Choice(Prefs.TARGET_BOTH, getString(R.string.level_target_both))

    private fun describeLevelTarget(button: ComboButton): String = levelTargets().let { targets ->
        (targets.firstOrNull { it.value == prefs.levelTarget(button) } ?: targets.last()).label
    }

    // Where an app opens: either screen, or asked when the hotkey is pressed.
    private fun launchScreens(): List<Choice> = screenTargets() + Choice(Prefs.APP_SCREEN_ASK, getString(R.string.ayn_modal))

    private fun describeShoulderDisplay(button: ComboButton): String = launchScreens().let { targets ->
        (targets.firstOrNull { it.value == prefs.shoulderDisplay(button) } ?: targets.last()).label
    }

    private fun focusTargets(): List<Choice> = screenTargets() + listOf(
        Choice(Prefs.FOCUS_TOGGLE, getString(R.string.focus_target_toggle)),
        Choice(Prefs.FOCUS_MODAL, getString(R.string.ayn_modal)),
    )

    private fun describeFocusTarget(button: ComboButton): String = focusTargets().let { targets ->
        (targets.firstOrNull { it.value == prefs.focusTarget(button) } ?: targets.first { it.value == Prefs.FOCUS_TOGGLE }).label
    }

    /** Grouped actions whose choice below is really a screen, so the row reads like every other screen row. */
    private fun isScreenOption(action: String): Boolean =
        ActionGroups.groupOf(action) == ActionGroups.Group.APP_SWITCHER || Ayn.groupOf(action) == Ayn.Group.DISPLAY

    /** The nine pictogram positions, row by row as the picker lays them out. */
    private fun focusPositions(): List<Choice> = listOf(
        Choice(Prefs.FOCUS_POS_TOP_LEFT, getString(R.string.focus_position_top_left)),
        Choice(Prefs.FOCUS_POS_TOP_CENTER, getString(R.string.focus_position_top_center)),
        Choice(Prefs.FOCUS_POS_TOP_RIGHT, getString(R.string.focus_position_top_right)),
        Choice(Prefs.FOCUS_POS_CENTER_LEFT, getString(R.string.focus_position_center_left)),
        Choice(Prefs.FOCUS_POS_CENTER, getString(R.string.focus_position_center)),
        Choice(Prefs.FOCUS_POS_CENTER_RIGHT, getString(R.string.focus_position_center_right)),
        Choice(Prefs.FOCUS_POS_BOTTOM_LEFT, getString(R.string.focus_position_bottom_left)),
        Choice(Prefs.FOCUS_POS_BOTTOM_CENTER, getString(R.string.focus_position_bottom_center)),
        Choice(Prefs.FOCUS_POS_BOTTOM_RIGHT, getString(R.string.focus_position_bottom_right)),
    )

    private fun recordQualities(): List<Choice> = listOf(
        Choice(Prefs.RECORD_SMALL, getString(R.string.record_quality_small)),
        Choice(Prefs.RECORD_MEDIUM, getString(R.string.record_quality_medium)),
        Choice(Prefs.RECORD_LARGE, getString(R.string.record_quality_large)),
    )

    private fun describeRecordQuality(button: ComboButton): String =
        recordQualities().first { it.value == prefs.recordQuality(button) }.label

    private fun recordDucks(): List<Choice> = listOf(
        Choice(Prefs.DUCK_OFF, getString(R.string.record_duck_off)),
        Choice(Prefs.DUCK_VIDEO, getString(R.string.record_duck_video)),
        Choice(Prefs.DUCK_DEVICE, getString(R.string.record_duck_device)),
    )

    private fun describeRecordDuck(button: ComboButton): String =
        recordDucks().first { it.value == prefs.recordDuck(button) }.label

    private fun describePushToTalkKey(keyCode: Int): String =
        if (keyCode == 0) getString(R.string.record_ptt_key_none) else PushToTalk.label(keyCode)

    private fun chooseRecordDuck(row: ComboRow) {
        val choices = recordDucks()
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
    private fun choosePushToTalkKey(row: ComboRow) = chooseKey(
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

    /** The gyro setup in a few words: stick, sideways turn, following turns, speed and the hold key. */
    private fun describeGyro(settings: GyroSettings): String = listOfNotNull(
        getString(if (settings.stick == Stick.LEFT) R.string.gyro_stick_left else R.string.gyro_stick_right),
        getString(if (settings.axis == GyroAxis.YAW) R.string.gyro_axis_yaw else R.string.gyro_axis_roll),
        if (settings.rate) getString(R.string.gyro_summary_rate) else null,
        getString(R.string.gyro_percent, settings.sensitivity),
        settings.holdKeyInUse?.let { getString(R.string.gyro_hold_summary, PushToTalk.label(it)) },
    ).joinToString(", ")

    private fun openGyroSettings(row: ComboRow) {
        GyroSettingsDialog(this, row.button.prefKey, prefs.gyro(row.button), { hasKey, onChosen ->
            chooseKey(R.string.gyro_hold_key_title, R.string.gyro_key_dialog_message, R.string.gyro_key_dialog_warning,
                R.string.gyro_key_rejected, if (hasKey) ({ onChosen(0) }) else null, onChosen = onChosen)
        }) { prefs.setGyro(row.button, it) }.show { updateShoulderRow(row) }
    }

    private fun describeRecordIndicator(button: ComboButton): String =
        if (!prefs.recordIndicator(button)) getString(R.string.focus_position_off)
        else focusPositions().first { it.value == prefs.recordPosition(button) }.label

    private fun chooseRecordQuality(row: ComboRow) {
        val choices = recordQualities()
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

    private fun describeFocusPosition(button: ComboButton): String =
        if (!prefs.focusIndicator(button)) getString(R.string.focus_position_off)
        else focusPositions().first { it.value == prefs.focusPosition(button) }.label

    /** The indicator dialog of the controller-focus pictogram, or with [record] of the recording dot,
     *  which has no display time. */
    private fun choosePosition(row: ComboRow, record: Boolean = false) {
        val positions = focusPositions()
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

    /** The views of one button's card that change with its action. */
    private class ComboRow(
        val card: SettingsPage.Card,
        val button: ComboButton,
        val label: String,
        val action: SettingsPage.ChooserHandle,
        val display: SettingsPage.ChooserHandle,
        val target: SettingsPage.ChooserHandle,
        val displayOptions: View,
        val targetOptions: View,
        /** A timing slider that only means something once the row has an action. */
        val timingOptions: View? = null,
    ) {
        var focusOptions: SettingsPage.FocusOptionsHandle? = null
        /** The choice below a grouped action, built when first needed so unassigned rows stay as they were. */
        var option: SettingsPage.ChooserHandle? = null
        var optionOptions: View? = null
        /** The "apps of both screens" switch, shown wherever the app switcher is the action. */
        var switcherOptions: View? = null
        var switchAppOptions: View? = null
        var closeAppsOptions: View? = null
        var standbyOptions: View? = null
        var recordOptions: RecordOptions? = null
        var appOptions: SettingsPage.AppControlOptionsHandle? = null
        var macroOptions: View? = null
        var macroLibrary: SettingsPage.ChooserHandle? = null
        var gyroOptions: View? = null
        var gyroSetup: SettingsPage.ChooserHandle? = null
        val position: SettingsPage.ChooserHandle get() = checkNotNull(focusOptions).position
    }

    private class RecordOptions(
        val group: View,
        val pushToTalk: SettingsPage.ToggleHandle,
        val pushToTalkKey: SettingsPage.ChooserHandle,
        val duck: SettingsPage.ChooserHandle,
        val quality: SettingsPage.ChooserHandle,
        val indicator: SettingsPage.ChooserHandle,
    )

    private fun chooseShoulderAction(row: ComboRow) {
        val choices = shoulderActions(macro = row.button.base !is BackGestureButton)
        val stored = prefs.shoulderAction(row.button)
        val current = if (Prefs.launchesApp(stored)) ACTION_APP else ActionGroups.groupOf(stored)?.entry ?: stored
        ActionChooser(
            this,
            getString(R.string.shoulder_choose_action_title, row.label),
            choices.map { ActionChooser.Entry(it.value, it.label, actionDescription(it.value)?.let(::getString), keywords = searchKeywords(it.value)) },
            actionOptions(choices),
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
    private class OptionGuide(val row: ComboRow, skip: Set<View>) {
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

    private fun startGuide(row: ComboRow, skip: Set<View> = emptySet()) {
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
    private fun crumb(row: ComboRow, function: String? = functionName(prefs.shoulderAction(row.button))): String =
        function?.let { getString(R.string.dialog_breadcrumb, row.label, it) } ?: row.label

    /** The action as the action list names it: a group or AYN tile by its own name, not by its option. */
    private fun functionName(action: String): String? = when {
        action.isEmpty() -> null
        else -> ActionGroups.groupOf(action)?.let { getString(it.titleRes) }
            ?: Ayn.groupOf(action)?.let { getString(it.titleRes) }
            ?: describeAction(action)
    }

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
    private fun openMacroEditor(row: ComboRow, initial: SavedMacro? = prefs.savedMacro(row.button), assign: Boolean = true) {
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

    private fun openMacroLibrary(row: ComboRow) {
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

    private fun describeMacro(saved: SavedMacro?): String =
        saved?.let { MacroEditor.macroLine(this, it) } ?: getString(R.string.macro_not_recorded)

    /**
     * The apps a hotkey can open: "Ask" and "Favorites" choose when it is pressed, then the starred
     * apps and every other app. A star marks an app for every hotkey; the order follows the stars only
     * the next time the list opens, so nothing moves under the finger.
     */
    private fun chooseShoulderApp(row: ComboRow) {
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

    private fun chooseOption(row: ComboRow) {
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

    private fun chooseAynOption(row: ComboRow, group: Ayn.Group) {
        val choices = aynOptions(group)
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

    private fun chooseTarget(row: ComboRow) {
        val focus = prefs.shoulderAction(row.button) == Prefs.SHOULDER_CONTROLLER_FOCUS
        val targets = if (focus) focusTargets() else levelTargets()
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

    private fun chooseShoulderDisplay(row: ComboRow) {
        // Ask is offered where an app opens; Home goes to a fixed screen.
        val targets = if (Prefs.launchesApp(prefs.shoulderAction(row.button))) launchScreens() else screenTargets()
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

    private fun chooseAppControl(row: ComboRow, mode: Boolean) {
        val choices = if (mode) appModes() else appScreens()
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
        val card = page.card {}
        lateinit var row: ComboRow
        val action = card.chooserRow(titleRes, describeShoulderAction(button)) { chooseShoulderAction(row) }
        val timing = extra?.let { card.it(button) }
        lateinit var display: SettingsPage.ChooserHandle
        val displayOptions = card.subOptions {
            display = chooserRow(R.string.level_target_title, describeShoulderDisplay(button)) { chooseShoulderDisplay(row) }
        }
        lateinit var target: SettingsPage.ChooserHandle
        val targetOptions = card.subOptions {
            target = chooserRow(R.string.level_target_title, describeLevelTarget(button)) { chooseTarget(row) }
        }
        // The Home column's label names its layer, so the dialogs and screen readers say which binding it is.
        val label = if (button is HomeLayerButton) getString(R.string.settings_home_binding_label, getString(titleRes)) else getString(titleRes)
        row = ComboRow(card, button, label, action, display, target, displayOptions, targetOptions, timing)
        comboRows[button.prefKey] = row
        updateShoulderRow(row)
    }

    private fun updateShoulderRow(row: ComboRow) {
        // A guided dialog that writes its choice updates its row itself; a page-wide refresh says nothing about it.
        guide?.let { if (it.row === row && it.pending && !refreshingRows) it.chosen = true }
        val action = prefs.shoulderAction(row.button)
        val described = describeShoulderAction(row.button)
        // The chosen action stands out from the options under it; "Not set" stays plain.
        row.action.value.text = if (action.isEmpty()) described else getString(R.string.shoulder_action_selected, described)
        row.action.value.typeface = if (action.isEmpty()) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        row.display.row.visibility = if (Prefs.launchesApp(action) || action == Prefs.SHOULDER_HOME) View.VISIBLE else View.GONE
        row.display.value.text = describeShoulderDisplay(row.button)
        row.display.row.contentDescription = getString(R.string.level_target_accessibility, row.label, row.display.value.text)
        val hasOption = Ayn.groupOf(action) != null || ActionGroups.groupOf(action) != null
        val hasTarget = Prefs.hasScreenTarget(action)
        val focus = action == Prefs.SHOULDER_CONTROLLER_FOCUS
        row.target.row.visibility = if (hasTarget && !focus) View.VISIBLE else View.GONE
        row.displayOptions.visibility = row.display.row.visibility
        row.timingOptions?.visibility = if (action.isEmpty()) View.GONE else View.VISIBLE
        val switcher = action == Prefs.SHOULDER_SWITCHER
        if (switcher && row.switcherOptions == null) {
            row.switcherOptions = row.card.subOptions {
                toggleRow(R.string.switcher_all_apps, R.string.switcher_all_apps_hint, prefs.switcherShowsAllApps, readValue = { prefs.switcherShowsAllApps }) {
                    prefs.switcherShowsAllApps = it
                }
            }
        }
        row.switcherOptions?.visibility = if (switcher) View.VISIBLE else View.GONE
        row.targetOptions.visibility = row.target.row.visibility
        val switchApp = action == Prefs.SHOULDER_SWITCH_APP
        val appControl = action == Prefs.SHOULDER_APP_CONTROL
        row.action.row.setPadding(row.action.row.paddingLeft, row.action.row.paddingTop, row.action.row.paddingRight,
            dp(if (hasTarget || hasOption || appControl || switchApp || row.display.row.visibility == View.VISIBLE) 0 else 10))
        if (hasOption && row.option == null) {
            lateinit var created: SettingsPage.ChooserHandle
            val options = row.card.subOptions {
                created = chooserRow(R.string.ayn_option_title, describeOption(row.button)) { chooseOption(row) }
            }
            // Built last, but it belongs right above the screen row.
            (options.parent as ViewGroup).let { parent ->
                parent.removeView(options)
                parent.addView(options, parent.indexOfChild(row.targetOptions))
            }
            row.option = created
            row.optionOptions = options
        }
        row.option?.let { option ->
            option.row.visibility = if (hasOption) View.VISIBLE else View.GONE
            row.optionOptions?.visibility = option.row.visibility
            option.value.text = describeOption(row.button)
            val screen = isScreenOption(action)
            option.title.setText(if (screen) R.string.level_target_title else R.string.ayn_option_title)
            option.row.contentDescription =
                getString(if (screen) R.string.level_target_accessibility else R.string.ayn_option_accessibility, row.label, option.value.text)
        }
        row.target.value.text = describeLevelTarget(row.button)
        row.target.row.contentDescription = getString(R.string.level_target_accessibility, row.label, row.target.value.text)
        if (focus && row.focusOptions == null) {
            row.focusOptions = row.card.focusOptions(
                describeFocusTarget(row.button), describeFocusPosition(row.button),
                { chooseTarget(row) }, { choosePosition(row) },
            )
        }
        if (switchApp && row.switchAppOptions == null) {
            row.switchAppOptions = row.card.subOptions {
                toggleRow(R.string.switch_app_follow_focus, null, prefs.switchAppFollowFocus(row.button),
                    readValue = { prefs.switchAppFollowFocus(row.button) }) {
                    prefs.setSwitchAppFollowFocus(row.button, it)
                }
            }
        }
        row.switchAppOptions?.visibility = if (switchApp) View.VISIBLE else View.GONE
        val closeApps = Prefs.isCloseApps(action)
        if (closeApps && row.closeAppsOptions == null) {
            row.closeAppsOptions = row.card.subOptions {
                toggleRow(R.string.app_control_confirm_title, null, prefs.closeConfirm(row.button),
                    readValue = { prefs.closeConfirm(row.button) }) {
                    prefs.setCloseConfirm(row.button, it)
                }
            }
        }
        row.closeAppsOptions?.visibility = if (closeApps) View.VISIBLE else View.GONE
        val standby = action == Prefs.SHOULDER_STANDBY
        if (standby && row.standbyOptions == null) {
            row.standbyOptions = row.card.subOptions {
                toggleRow(R.string.standby_confirm_title, null, prefs.standbyConfirm(row.button),
                    readValue = { prefs.standbyConfirm(row.button) }) {
                    prefs.setStandbyConfirm(row.button, it)
                }
            }
        }
        row.standbyOptions?.visibility = if (standby) View.VISIBLE else View.GONE
        val record = action == Prefs.SHOULDER_RECORD_VIDEO
        if (record && row.recordOptions == null) {
            lateinit var pushToTalk: SettingsPage.ToggleHandle
            lateinit var pushToTalkKey: SettingsPage.ChooserHandle
            lateinit var duck: SettingsPage.ChooserHandle
            lateinit var quality: SettingsPage.ChooserHandle
            lateinit var indicator: SettingsPage.ChooserHandle
            val group = row.card.subOptions {
                toggleRow(R.string.record_mic_title, null, prefs.recordMic(row.button),
                    readValue = { prefs.recordMic(row.button) }) {
                    prefs.setRecordMic(row.button, it)
                    updateShoulderRow(row)
                }
                pushToTalk = toggleRow(R.string.record_ptt_title, null, prefs.recordPtt(row.button),
                    readValue = { prefs.recordPtt(row.button) }) {
                    prefs.setRecordPtt(row.button, it)
                    updateShoulderRow(row)
                }
                pushToTalkKey = chooserRow(R.string.record_ptt_key_title, describePushToTalkKey(prefs.recordPttKey(row.button))) {
                    choosePushToTalkKey(row)
                }
                duck = chooserRow(R.string.record_duck_title, describeRecordDuck(row.button)) { chooseRecordDuck(row) }
                quality = chooserRow(R.string.record_quality_title, describeRecordQuality(row.button)) { chooseRecordQuality(row) }
                indicator = chooserRow(R.string.record_indicator_row, describeRecordIndicator(row.button)) {
                    choosePosition(row, record = true)
                }
            }
            row.recordOptions = RecordOptions(group, pushToTalk, pushToTalkKey, duck, quality, indicator)
        }
        row.recordOptions?.let { options ->
            options.group.visibility = if (record) View.VISIBLE else View.GONE
            // Push to talk only exists with the microphone, its button and fade only with push to talk.
            val mic = prefs.recordMic(row.button)
            val talk = mic && prefs.recordPtt(row.button)
            fun show(view: View, visible: Boolean) {
                val state = if (visible) View.VISIBLE else View.GONE
                view.visibility = state
                dividerBefore(view)?.visibility = state
            }
            show(options.pushToTalk.row, mic)
            show(options.pushToTalkKey.row, talk)
            show(options.duck.row, talk)
            options.pushToTalkKey.value.text = describePushToTalkKey(prefs.recordPttKey(row.button))
            options.pushToTalkKey.row.contentDescription =
                getString(R.string.record_ptt_key_accessibility, row.label, options.pushToTalkKey.value.text)
            options.duck.value.text = describeRecordDuck(row.button)
            options.duck.row.contentDescription = getString(R.string.record_duck_accessibility, row.label, options.duck.value.text)
            options.quality.value.text = describeRecordQuality(row.button)
            options.quality.row.contentDescription = getString(R.string.record_quality_accessibility, row.label, options.quality.value.text)
            options.indicator.value.text = describeRecordIndicator(row.button)
            options.indicator.row.contentDescription =
                getString(R.string.record_indicator_accessibility, row.label, options.indicator.value.text)
        }
        val macroAction = action == Prefs.SHOULDER_MACRO
        if (macroAction && row.macroOptions == null) {
            lateinit var library: SettingsPage.ChooserHandle
            // One row: the list both picks a saved macro and records a new one, and its value names the one this hotkey plays.
            row.macroOptions = row.card.subOptions {
                library = chooserRow(R.string.macro_library_title, describeMacro(prefs.savedMacro(row.button))) {
                    if (PService.cachedAvailable()) openMacroLibrary(row) else showMacroUnavailable()
                }
            }
            row.macroLibrary = library
        }
        row.macroOptions?.visibility = if (macroAction) View.VISIBLE else View.GONE
        row.macroLibrary?.let { library ->
            library.value.text = describeMacro(prefs.savedMacro(row.button))
            library.row.contentDescription = getString(R.string.macro_library_accessibility, row.label, library.value.text)
        }
        val gyroAction = action == Prefs.SHOULDER_GYRO
        if (gyroAction && row.gyroOptions == null) {
            lateinit var setup: SettingsPage.ChooserHandle
            row.gyroOptions = row.card.subOptions {
                setup = chooserRow(R.string.gyro_setup_title, describeGyro(prefs.gyro(row.button))) { openGyroSettings(row) }
            }
            row.gyroSetup = setup
        }
        row.gyroOptions?.visibility = if (gyroAction) View.VISIBLE else View.GONE
        row.gyroSetup?.let { setup ->
            setup.value.text = describeGyro(prefs.gyro(row.button))
            setup.row.contentDescription = getString(R.string.gyro_setup_accessibility, row.label, setup.value.text)
        }
        if (appControl && row.appOptions == null) {
            row.appOptions = row.card.appControlOptions(
                describeAppMode(row.button), describeAppScreen(row.button), prefs.appConfirm(row.button),
                { chooseAppControl(row, mode = true) }, { chooseAppControl(row, mode = false) },
                { prefs.setAppConfirm(row.button, it) },
            )
        }
        row.appOptions?.let { options ->
            val close = prefs.appMode(row.button) != Prefs.APP_MINIMIZE
            options.row.visibility = if (appControl) View.VISIBLE else View.GONE
            options.mode.value.text = describeAppMode(row.button)
            options.screen.value.text = describeAppScreen(row.button)
            options.mode.row.contentDescription = getString(R.string.app_control_mode_accessibility, row.label, options.mode.value.text)
            options.screen.row.contentDescription = getString(R.string.level_target_accessibility, row.label, options.screen.value.text)
            // Ask already is a confirmation; minimizing destroys nothing.
            fun show(view: View, visible: Boolean) {
                val state = if (visible) View.VISIBLE else View.GONE
                view.visibility = state
                dividerBefore(view)?.visibility = state
            }
            show(options.confirm.row, appControl && close && prefs.appScreen(row.button) != Prefs.APP_SCREEN_ASK)
            show(options.warning.parent as View, appControl && close)
        }
        row.focusOptions?.let { options ->
            options.row.visibility = if (focus) View.VISIBLE else View.GONE
            options.target.value.text = describeFocusTarget(row.button)
            options.target.row.contentDescription = getString(R.string.level_target_accessibility, row.label, options.target.value.text)
            options.position.value.text = describeFocusPosition(row.button)
            options.position.row.contentDescription = getString(R.string.focus_position_accessibility, row.label, options.position.value.text)
        }
        // With Home standing in for Back, or Back + Home as the activation shortcut, Back + Home cannot
        // run its own shortcut; the binding stays stored.
        val toggleBlocks = row.button == AndroidButton.HOME && prefs.toggleButtons == Prefs.TOGGLE_BACK_HOME
        val dualBlocks = row.button == AndroidButton.HOME && prefs.dualMode
        val blocked = toggleBlocks || dualBlocks || row.button == AndroidButton.HOME && prefs.homeAsBack
        if (blocked) row.action.value.text = getString(when {
            toggleBlocks -> R.string.toggle_combo_blocked
            dualBlocks -> R.string.dual_mode_combo_blocked
            else -> R.string.home_as_back_combo_blocked
        })
        (row.action.row.parent as View).let { card ->
            card.alpha = if (blocked) 0.5f else 1f
            setTreeEnabled(card, !blocked)
        }
        row.action.row.contentDescription =
            getString(R.string.shoulder_action_accessibility, row.label, if (blocked) row.action.value.text else described)
    }

    private fun setTreeEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (i in 0 until view.childCount) setTreeEnabled(view.getChildAt(i), enabled)
    }

    private fun dividerBefore(row: View): View? =
        (row.parent as? ViewGroup)?.let { card -> card.getChildAt(card.indexOfChild(row) - 1) }

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

    // The file picker is the whole permission model: the user hands over one file, nothing else.
    private fun startBackupSave() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, SettingsBackup.fileName(appVersionName()))
        runCatching { startActivityForResult(intent, REQUEST_BACKUP_SAVE) }
            .onFailure { Toast.makeText(this, R.string.backup_save_failed, Toast.LENGTH_LONG).show() }
    }

    private fun startBackupLoad() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        runCatching { startActivityForResult(intent, REQUEST_BACKUP_LOAD) }
            .onFailure { Toast.makeText(this, R.string.backup_read_failed, Toast.LENGTH_LONG).show() }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data?.takeIf { resultCode == RESULT_OK } ?: return
        when (requestCode) {
            REQUEST_BACKUP_SAVE -> saveBackup(uri)
            REQUEST_BACKUP_LOAD -> loadBackup(uri)
        }
    }

    @Suppress("DEPRECATION")
    private fun saveBackup(uri: Uri) {
        val json = SettingsBackup.write(prefs.snapshot(), appVersionName(),
            packageManager.getPackageInfo(packageName, 0).let { it.longVersionCode })
        Thread {
            val saved = runCatching {
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }.isSuccess
            runOnUiThread { if (!isFinishing && !isDestroyed) Toast.makeText(this, if (saved) R.string.backup_saved else R.string.backup_save_failed, Toast.LENGTH_LONG).show() }
        }.apply { isDaemon = true }.start()
    }

    private fun loadBackup(uri: Uri) {
        Thread {
            // One byte over the limit is enough to tell a file is too large, without reading all of it.
            val bytes = runCatching {
                contentResolver.openInputStream(uri)!!.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (out.size() <= SettingsBackup.MAX_BYTES) {
                        val n = input.read(chunk, 0, minOf(chunk.size, SettingsBackup.MAX_BYTES + 1 - out.size()))
                        if (n < 0) break
                        out.write(chunk, 0, n)
                    }
                    out.toByteArray()
                }
            }.getOrNull()
            runOnUiThread { if (!isFinishing && !isDestroyed) offerBackup(bytes) }
        }.apply { isDaemon = true }.start()
    }

    /** What a picked file leads to: a refusal, or the version hint and the preview of every change. */
    internal fun offerBackup(bytes: ByteArray?) {
        if (bytes == null) { showBackupProblem(R.string.backup_read_failed); return }
        when (val result = SettingsBackup.read(String(bytes, Charsets.UTF_8))) {
            is SettingsBackup.Result.Rejected -> showBackupProblem(when (result.reason) {
                SettingsBackup.Rejection.TOO_LARGE -> R.string.backup_too_large
                SettingsBackup.Rejection.NOT_JSON -> R.string.backup_not_json
                SettingsBackup.Rejection.NOT_A_BACKUP -> R.string.backup_not_a_backup
            })
            is SettingsBackup.Result.Loaded -> confirmBackup(result)
        }
    }

    private fun showBackupProblem(message: Int) {
        AlertDialog.Builder(this).setTitle(R.string.backup_problem_title).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    /** A backup from another version is allowed, but says so first: it may not fit, and that is all it says. */
    private fun confirmBackup(loaded: SettingsBackup.Result.Loaded) {
        val plan = SettingsChangePlan(prefs.snapshot(), loaded.state, null, SettingsChangeAction.RESTORE)
        val version = appVersionName()
        val compatibility = SettingsBackup.compatibility(loaded.info, version)
        if (compatibility == SettingsBackup.Compatibility.SAME) { showChanges(plan); return }
        val message = when (compatibility) {
            SettingsBackup.Compatibility.OLDER -> R.string.backup_version_older
            SettingsBackup.Compatibility.NEWER -> R.string.backup_version_newer
            else -> R.string.backup_version_unknown
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_version_title)
            .setMessage(if (compatibility == SettingsBackup.Compatibility.UNKNOWN) getString(message, version)
                else getString(message, loaded.info.appVersion ?: "?", version))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.backup_continue) { _, _ -> showChanges(plan) }
            .show()
    }

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
            changeLines(plan).forEach(::line)
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

    private fun changeLines(plan: SettingsChangePlan): List<String> = buildList {
        fun addChange(label: String, old: String, new: String) {
            if (old != new) add(getString(R.string.settings_change_line, label, old, new))
        }
        fun onOff(value: Boolean) = getString(if (value) R.string.settings_on else R.string.settings_off)
        fun ms(value: Long) = getString(R.string.focus_duration, value)
        fun target(value: String) = levelTargets().first { it.value == value }.label
        fun focus(value: String) = focusTargets().first { it.value == value }.label
        fun appMode(value: String) = appModes().first { it.value == value }.label
        fun appScreen(value: String) = appScreens().first { it.value == value }.label
        fun position(value: String) = focusPositions().first { it.value == value }.label
        fun quality(value: String) = recordQualities().first { it.value == value }.label
        fun duck(value: String) = recordDucks().first { it.value == value }.label
        fun aynOption(value: BindingSettings): String {
            val group = checkNotNull(Ayn.groupOf(value.action))
            val chosen = value.aynOption?.takeIf(group::isValid) ?: group.defaultOption
            return aynOptions(group).first { it.value == chosen }.label
        }
        fun launches(value: BindingSettings) = Prefs.launchesApp(value.action) || value.action == Prefs.SHOULDER_HOME
        fun binding(value: BindingSettings): String {
            val detail = when {
                launches(value) -> launchScreens().first { it.value == value.display }.label
                value.action == Prefs.SHOULDER_SCREENSHOT -> target(value.screenshotTarget())
                Prefs.isLevelAction(value.action) || value.action == Prefs.SHOULDER_RECORD_VIDEO -> target(value.levelTarget())
                value.action == Prefs.SHOULDER_CONTROLLER_FOCUS -> focus(value.focusTarget)
                value.action == Prefs.SHOULDER_APP_CONTROL -> "${appMode(value.appMode)}, ${appScreen(value.appScreen)}"
                Prefs.isCloseApps(value.action) || value.action == Prefs.SHOULDER_VOLUME_MUTE ->
                    "${getString(checkNotNull(ActionGroups.groupOf(value.action)?.option(value.action)).labelRes)}, ${target(value.levelTarget())}"
                Ayn.groupOf(value.action) != null -> aynOption(value)
                ActionGroups.groupOf(value.action) != null -> return getString(
                    R.string.settings_action_detail, describeAction(value.action),
                    getString(checkNotNull(ActionGroups.groupOf(value.action)?.option(value.action)).labelRes),
                )
                else -> return describeAction(value.action)
            }
            return getString(R.string.settings_action_detail, describeAction(value.action), detail)
        }
        val a = plan.before
        val b = plan.after
        addChange(getString(R.string.settings_double_time), ms(a.doubleMs), ms(b.doubleMs))
        addChange(getString(R.string.settings_tap_hold_time), ms(a.tapHoldMs), ms(b.tapHoldMs))
        addChange(getString(R.string.settings_hold_time), ms(a.holdMs), ms(b.holdMs))
        addChange(getString(R.string.switcher_all_apps), onOff(a.allApps), onOff(b.allApps))
        addChange(getString(R.string.switcher_controller), onOff(a.controller), onOff(b.controller))
        addChange(getString(R.string.home_as_back), onOff(a.homeAsBack), onOff(b.homeAsBack))
        addChange(getString(R.string.dual_mode), onOff(a.dualMode), onOff(b.dualMode))
        val homeSide = getString(R.string.dual_column_home)
        fun homeTiming(res: Int, old: Long, new: Long) =
            addChange(getString(R.string.settings_property_label, homeSide, getString(res)), ms(old), ms(new))
        homeTiming(R.string.settings_double_time, a.homeDoubleMs, b.homeDoubleMs)
        homeTiming(R.string.settings_tap_hold_time, a.homeTapHoldMs, b.homeTapHoldMs)
        homeTiming(R.string.settings_hold_time, a.homeHoldMs, b.homeHoldMs)
        addChange(getString(R.string.service_toggle_buttons), toggleLabel(a.toggleButtons), toggleLabel(b.toggleButtons))
        addChange(getString(R.string.settings_toggle_time), ms(a.toggleMs), ms(b.toggleMs))
        fun apps(value: List<AppException>) = value.joinToString(", ") { it.name }.ifEmpty { getString(R.string.exceptions_none) }
        addChange(getString(R.string.exceptions_apps_title), apps(a.exceptions), apps(b.exceptions))
        fun macros(value: List<SavedMacro>) = value.joinToString(", ") { it.name }.ifEmpty { getString(R.string.macro_library_empty) }
        addChange(getString(R.string.macro_library_title), macros(a.macros), macros(b.macros))
        val buttons = listOf(
            BackGestureButton.SINGLE to R.string.back_single_row, BackGestureButton.DOUBLE to R.string.back_double_row,
            BackGestureButton.TAP_HOLD to R.string.back_tap_hold_row, BackGestureButton.HOLD to R.string.back_hold_row,
            AndroidButton.HOME to R.string.button_home, AndroidButton.START to R.string.button_start,
            AndroidButton.SELECT to R.string.button_select,
            FaceButton.A to R.string.button_a, FaceButton.B to R.string.button_b,
            FaceButton.X to R.string.button_x, FaceButton.Y to R.string.button_y,
            ShoulderButton.L1 to R.string.shoulder_l1, ShoulderButton.R1 to R.string.shoulder_r1,
            ShoulderButton.L2 to R.string.shoulder_l2, ShoulderButton.R2 to R.string.shoulder_r2,
            DpadDirection.UP to R.string.dpad_up, DpadDirection.DOWN to R.string.dpad_down,
            DpadDirection.LEFT to R.string.dpad_left, DpadDirection.RIGHT to R.string.dpad_right,
            StickButton.LEFT_UP to R.string.stick_left_up, StickButton.LEFT_DOWN to R.string.stick_left_down,
            StickButton.LEFT_LEFT to R.string.stick_left_left, StickButton.LEFT_RIGHT to R.string.stick_left_right,
            StickButton.RIGHT_UP to R.string.stick_right_up, StickButton.RIGHT_DOWN to R.string.stick_right_down,
            StickButton.RIGHT_LEFT to R.string.stick_right_left, StickButton.RIGHT_RIGHT to R.string.stick_right_right,
            StickClickButton.LEFT_PRESS to R.string.stick_left_press, StickClickButton.LEFT_DOUBLE to R.string.stick_left_double,
            StickClickButton.LEFT_TAP_HOLD to R.string.stick_left_tap_hold, StickClickButton.RIGHT_PRESS to R.string.stick_right_press,
            StickClickButton.RIGHT_DOUBLE to R.string.stick_right_double, StickClickButton.RIGHT_TAP_HOLD to R.string.stick_right_tap_hold,
        )
        Stick.entries.forEach { stick ->
            val old = a.stickTimings.getValue(stick.prefix)
            val new = b.stickTimings.getValue(stick.prefix)
            val side = getString(if (stick == Stick.LEFT) R.string.stick_tab_left else R.string.stick_tab_right)
            addChange(getString(R.string.settings_property_label, side, getString(R.string.stick_double_title)), ms(old.doubleMs), ms(new.doubleMs))
            addChange(getString(R.string.settings_property_label, side, getString(R.string.stick_tap_hold_title)), ms(old.tapHoldMs), ms(new.tapHoldMs))
            val oldHome = a.homeStickTimings.getValue(stick.prefix)
            val newHome = b.homeStickTimings.getValue(stick.prefix)
            val homeStick = getString(R.string.settings_property_label, homeSide, side)
            addChange(getString(R.string.settings_property_label, homeStick, getString(R.string.stick_double_title)), ms(oldHome.doubleMs), ms(newHome.doubleMs))
            addChange(getString(R.string.settings_property_label, homeStick, getString(R.string.stick_tap_hold_title)), ms(oldHome.tapHoldMs), ms(newHome.tapHoldMs))
        }
        // The Home layer follows the Back layer, binding by binding.
        val layers = buttons.map { (button, labelRes) -> Triple(button, labelRes, R.string.settings_binding_label) } +
            buttons.filter { (button, _) -> button in Prefs.HOME_LAYER_BASES }
                .map { (button, labelRes) -> Triple(HomeLayerButton.of(button), labelRes, R.string.settings_home_binding_label) }
        layers.forEach { (button, labelRes, layerRes) ->
            val old = a.bindings.getValue(button.prefKey)
            val new = b.bindings.getValue(button.prefKey)
            val label = getString(layerRes, getString(labelRes))
            addChange(label, binding(old), binding(new))
            fun extra(res: Int, from: String, to: String) = addChange(getString(R.string.settings_property_label, label, getString(res)), from, to)
            if (!(launches(old) && launches(new))) extra(R.string.level_target_title, target(old.display), target(new.display))
            if (!(old.action == Prefs.SHOULDER_SCREENSHOT && new.action == old.action))
                extra(R.string.settings_screenshot_screen, target(old.screenshotTarget()), target(new.screenshotTarget()))
            fun screenTarget(action: String) =
                Prefs.isLevelAction(action) || Prefs.isCloseApps(action) || action == Prefs.SHOULDER_VOLUME_MUTE ||
                    action == Prefs.SHOULDER_RECORD_VIDEO
            if (!(screenTarget(old.action) && screenTarget(new.action)))
                extra(R.string.settings_level_screen, target(old.levelTarget()), target(new.levelTarget()))
            if (!(old.action == Prefs.SHOULDER_CONTROLLER_FOCUS && new.action == old.action))
                extra(R.string.level_target_title, focus(old.focusTarget), focus(new.focusTarget))
            extra(R.string.app_control_mode_title, appMode(old.appMode), appMode(new.appMode))
            extra(R.string.level_target_title, appScreen(old.appScreen), appScreen(new.appScreen))
            extra(R.string.switch_app_follow_focus, onOff(old.switchAppFollow), onOff(new.switchAppFollow))
            extra(R.string.app_control_confirm_title, onOff(old.appConfirm), onOff(new.appConfirm))
            extra(R.string.app_control_confirm_title, onOff(old.closeConfirm), onOff(new.closeConfirm))
            extra(R.string.standby_confirm_title, onOff(old.standbyConfirm), onOff(new.standbyConfirm))
            extra(R.string.record_mic_title, onOff(old.recordMic), onOff(new.recordMic))
            extra(R.string.record_ptt_title, onOff(old.recordPtt), onOff(new.recordPtt))
            extra(R.string.record_ptt_key_title, describePushToTalkKey(old.recordPttKey), describePushToTalkKey(new.recordPttKey))
            extra(R.string.record_duck_title, duck(old.recordDuck), duck(new.recordDuck))
            extra(R.string.record_quality_title, quality(old.recordQuality), quality(new.recordQuality))
            extra(R.string.record_indicator_title, onOff(old.recordIndicator), onOff(new.recordIndicator))
            extra(R.string.record_indicator_row, position(old.recordPosition), position(new.recordPosition))
            extra(R.string.gyro_setup_title, describeGyro(GyroSettings.decode(old.gyro)), describeGyro(GyroSettings.decode(new.gyro)))
            extra(R.string.macro_title, describeMacro(a.macros.firstOrNull { it.id == old.macro }),
                describeMacro(b.macros.firstOrNull { it.id == new.macro }))
            extra(R.string.focus_indicator_title, onOff(old.indicator), onOff(new.indicator))
            extra(R.string.focus_header_duration, ms(old.duration), ms(new.duration))
            extra(R.string.focus_header_position, position(old.position), position(new.position))
        }
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
        val updateHandle = updateHandle ?: return
        updateHandle.row.isClickable = false
        updateHandle.row.alpha = 0.5f
        updateHandle.title.setText(R.string.checking_for_updates)
        UpdateChecker.checkInBackground(this) { result ->
            if (isFinishing || isDestroyed) return@checkInBackground
            updateHandle.row.isClickable = true
            updateHandle.row.alpha = 1f
            updateHandle.title.setText(R.string.check_for_updates)
            when (result.status) {
                UpdateChecker.Status.UPDATE_AVAILABLE -> {
                    val apk = result.apk
                    val developerBuild = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                    val offerInstall = UpdateInstaller.canOfferInstall(apk, developerBuild)
                    val found = getString(R.string.update_found_message, result.tagName.orEmpty())
                    val builder = AlertDialog.Builder(this)
                        .setTitle(R.string.update_found_title)
                        .setMessage(when {
                            offerInstall -> "$found\n\n${getString(R.string.update_install_hint)}"
                            apk != null -> "$found\n\n${getString(R.string.update_developer_build_hint)}"
                            else -> found
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                    if (offerInstall && apk != null) {
                        builder.setPositiveButton(R.string.update_install_action) { _, _ -> startUpdate(apk) }
                        builder.setNeutralButton(R.string.open_release) { _, _ -> result.htmlUrl?.let { openUrl(it) } }
                    } else {
                        builder.setPositiveButton(R.string.open_release) { _, _ -> result.htmlUrl?.let { openUrl(it) } }
                    }
                    builder.show()
                }
                UpdateChecker.Status.UP_TO_DATE -> AlertDialog.Builder(this)
                    .setTitle(R.string.no_update_title)
                    .setMessage(R.string.no_update_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                UpdateChecker.Status.ERROR -> AlertDialog.Builder(this)
                    .setTitle(R.string.update_check_failed_title)
                    .setMessage(updateFailureMessage(result))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /** Downloads the release into Android's installer; Android then asks for the final confirmation itself. */
    private fun startUpdate(apk: UpdateChecker.Asset) {
        val installer = UpdateInstaller(this)
        if (!installer.canInstall()) return explainInstallPermission()
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(this).apply { textSize = 14f; text = getString(R.string.update_installing_progress, 0) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        updateDialog?.dismiss()
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.update_installing_title)
            .setView(content)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> installer.cancel() }
            .show()
        updateDialog = progress
        installer.install(apk, object : UpdateInstaller.Listener {
            override fun onProgress(done: Long, total: Long) {
                val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                bar.progress = percent
                label.text = getString(R.string.update_installing_progress, percent)
            }

            override fun onHandedOver() {
                progress.dismiss()
            }

            override fun onFailed(failure: UpdateInstaller.Failure) {
                progress.dismiss()
                if (isFinishing || isDestroyed) return
                if (failure == UpdateInstaller.Failure.NOT_ALLOWED) return explainInstallPermission()
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.update_failed_title)
                    .setMessage(when (failure) {
                        UpdateInstaller.Failure.NETWORK -> R.string.update_failed_network
                        UpdateInstaller.Failure.CORRUPT -> R.string.update_failed_corrupt
                        else -> R.string.update_failed_session
                    })
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        })
    }

    /** Installing from an app is something Android asks the user to allow per app, once. */
    private fun explainInstallPermission() {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_allow_title)
            .setMessage(R.string.update_allow_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.update_allow_open) { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
            }
            .show()
    }

    /** Names what actually went wrong; "check your connection" is only true when no answer came at all. */
    private fun updateFailureMessage(result: UpdateChecker.CheckResult): String = when (result.failure) {
        UpdateChecker.Failure.RATE_LIMITED -> result.retryMinutes
            ?.let { resources.getQuantityString(R.plurals.update_rate_limited_minutes, it, it) }
            ?: getString(R.string.update_rate_limited_message)
        UpdateChecker.Failure.UNEXPECTED -> getString(R.string.update_check_unexpected_message)
        else -> getString(R.string.update_check_failed_message)
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    companion object {
        const val EXTRA_INDICATOR_BUTTON = "indicator_button"
        private const val REQUEST_NOTIFICATIONS = 1
        private const val REQUEST_BACKUP_SAVE = 2
        private const val REQUEST_BACKUP_LOAD = 3
        private const val ACTION_APP = "@choose_app"
        private const val GUIDE_SETTLE_MS = 150L
        private const val CHEVRON_TAG = "settings_chooser_chevron"
        /** The pages dual mode splits into a Home and a Back column. */
        private val DUAL_SECTIONS = setOf(
            SettingsSection.BASIC, SettingsSection.FACE, SettingsSection.SHOULDERS, SettingsSection.DPAD, SettingsSection.STICKS,
        )
    }
}
