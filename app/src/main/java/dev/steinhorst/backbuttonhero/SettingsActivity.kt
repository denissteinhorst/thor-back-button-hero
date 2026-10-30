package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast

/** Configuration pages share the same rows and keep their state while navigating. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var navigation: SettingsNavigation
    private val pages = mutableMapOf<SettingsSection, SettingsPage>()
    private var exceptionsPage: ExceptionSettingsPage? = null
    private lateinit var warnings: LinearLayout
    private var launcherChoices: List<Choice>? = null
    private val appLabels = mutableMapOf<String, String>()
    private lateinit var serviceWarning: TextView
    private lateinit var rootWarning: SettingsPage.WarningHandle
    private var toggleButtonsValue: TextView? = null
    private var toggleDuration: SettingsPage.SliderHandle? = null
    private var singleValue: TextView? = null
    private var statusLabel: TextView? = null
    private lateinit var openA11yHandle: SettingsPage.ActionHandle
    private val comboRows = mutableMapOf<String, ComboRow>()
    private var repairHandle: SettingsPage.ActionHandle? = null
    private var updateHandle: SettingsPage.ActionHandle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = getString(R.string.app_name)
        actionBar?.hide()
        warnings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        serviceWarning = TextView(this).apply {
            tag = "settings_service_warning"
            textSize = 14f
            setTextColor(themedColor(android.R.attr.colorError, Color.parseColor("#B3261E")))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
            setPadding(dp(12), dp(8), dp(8), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        SettingsPage(this, warnings).card {
            rootWarning = warningRow(getString(R.string.dpad_root_missing))
            openA11yHandle = actionRow(R.string.enable_service) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        navigation = SettingsNavigation(this, savedInstanceState, warnings, serviceWarning, ::buildPage, ::refresh)
        setContentView(navigation.view)
        openIndicatorFromIntent()
        ensureNotificationPermission()
        UpdateChecker.checkInBackground(this)
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
        val button = (ShoulderButton.entries + DpadDirection.entries).firstOrNull { it.prefKey == key } ?: return
        if (prefs.shoulderAction(button) != Prefs.SHOULDER_CONTROLLER_FOCUS) return
        navigation.show(if (button is ShoulderButton) SettingsSection.SHOULDERS else SettingsSection.DPAD)
        val row = comboRows.getValue(key)
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
        launcherChoices = null
        appLabels.clear()
        refresh()
        // A service rebind can finish just after the activity resumes.
        navigation.view.postDelayed({ if (!isFinishing && !isDestroyed) refresh() }, 600)
        PService.probeAsync { runOnUiThread { if (!isFinishing && !isDestroyed) refresh() } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        navigation.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        navigation.dismiss()
        exceptionsPage?.dispose()
        super.onDestroy()
    }

    private fun buildPage(section: SettingsSection, content: LinearLayout) {
        val page = SettingsPage(this, content)
        pages[section] = page
        fun change(action: SettingsChangeAction) = { showChanges(SettingsChangePlan.create(prefs.snapshot(), section, action)) }
        val actions = when (section) {
            SettingsSection.HELP -> emptyList()
            SettingsSection.EXCEPTIONS -> listOf(
                R.string.exceptions_add_app to { exceptionsPage?.addApp() ?: Unit },
                R.string.exceptions_remove_all to change(SettingsChangeAction.PRESET),
            )
            else -> listOf(
                R.string.settings_preset to change(SettingsChangeAction.PRESET),
                R.string.settings_unbind to change(SettingsChangeAction.UNBIND),
            )
        }
        page.introduction(section.hintRes, actions, primary = R.string.exceptions_add_app)
        when (section) {
            SettingsSection.BASIC -> buildBasic(page)
            SettingsSection.SHOULDERS -> {
                page.sectionHeader(R.string.shoulder_bindings_section)
                buildShoulderRow(page, ShoulderButton.L1, R.string.shoulder_l1)
                buildShoulderRow(page, ShoulderButton.R1, R.string.shoulder_r1)
                buildShoulderRow(page, ShoulderButton.L2, R.string.shoulder_l2)
                buildShoulderRow(page, ShoulderButton.R2, R.string.shoulder_r2)
            }
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

    private fun buildBasic(page: SettingsPage) {
        page.sectionHeader(R.string.single_section_title)
        page.card {
            singleValue = chooserRow(R.string.single_title, describeSingleAction()) { chooseSingleAction() }.value
        }

        page.sectionHeader(R.string.double_section_title)
        page.card {
            val enabled = toggleRow(R.string.double_enabled, R.string.double_title, prefs.doubleTapEnabled, readValue = { prefs.doubleTapEnabled }) { prefs.doubleTapEnabled = it }
            subOptions(endControl = enabled.switch) {
                sliderRow(R.string.double_speed, 150, 600, 50, prefs.doubleTapMs, R.string.double_hint, readValue = { prefs.doubleTapMs }) { prefs.doubleTapMs = it }
            }
        }

        page.sectionHeader(R.string.tap_hold_section_title)
        page.card {
            val enabled = toggleRow(R.string.tap_hold_enabled, R.string.tap_hold_title, prefs.tapHoldEnabled, readValue = { prefs.tapHoldEnabled }) { prefs.tapHoldEnabled = it }
            subOptions(endControl = enabled.switch) {
                sliderRow(R.string.tap_hold_speed, 500, 2000, 100, prefs.tapHoldMs, R.string.tap_hold_hint, readValue = { prefs.tapHoldMs }) { prefs.tapHoldMs = it }
            }
        }

        page.sectionHeader(R.string.hold_section_title)
        page.card {
            val enabled = toggleRow(R.string.hold_enabled, R.string.hold_title, prefs.holdEnabled, readValue = { prefs.holdEnabled }) { prefs.holdEnabled = it }
            subOptions(endControl = enabled.switch) {
                sliderRow(R.string.hold_speed, 500, 2000, 100, prefs.holdMs, R.string.hold_hint, readValue = { prefs.holdMs }) { prefs.holdMs = it }
            }
        }
    }

    private fun buildSettings(page: SettingsPage) {
        page.sectionHeader(R.string.settings_switcher)
        page.card {
            toggleRow(R.string.switcher_all_apps, R.string.switcher_all_apps_hint, prefs.switcherShowsAllApps, readValue = { prefs.switcherShowsAllApps }) {
                prefs.switcherShowsAllApps = it
            }
            toggleRow(R.string.switcher_controller, R.string.switcher_controller_hint, prefs.switcherControllerEnabled, readValue = { prefs.switcherControllerEnabled }) {
                prefs.switcherControllerEnabled = it
            }
        }
        page.sectionHeader(R.string.service_toggle_title)
        page.card {
            val buttons = chooserRow(R.string.service_toggle_buttons, toggleButtonsLabel()) { chooseToggleButtons() }
            toggleButtonsValue = buttons.value
            subOptions(endControl = buttons.row.findViewWithTag("settings_chooser_chevron")) {
                toggleDuration = sliderRow(R.string.service_toggle_duration, 100, 3000, 100, prefs.toggleHoldMs, R.string.service_toggle_hint, readValue = { prefs.toggleHoldMs }) {
                    prefs.toggleHoldMs = it
                }
            }
        }

        page.sectionHeader(R.string.settings_reset)
        page.card {
            actionRow(R.string.unbind_optionals, R.string.settings_unbind_scope) { confirmUnbindOptionals() }
            actionRow(R.string.reset_settings, R.string.settings_reset_scope) { confirmResetSettings() }
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

    private fun toggleButtonsLabel(): String = when (prefs.toggleButtons) {
        Prefs.TOGGLE_NONE -> getString(R.string.shoulder_none)
        Prefs.TOGGLE_L1_R1 -> "L1 + R1"
        Prefs.TOGGLE_L2_R2 -> "L2 + R2"
        else -> "L3 + R3"
    }

    private fun chooseToggleButtons() {
        val values = listOf(Prefs.TOGGLE_L1_R1, Prefs.TOGGLE_L2_R2, Prefs.TOGGLE_BOTH, Prefs.TOGGLE_NONE)
        AlertDialog.Builder(this)
            .setTitle(R.string.service_toggle_buttons)
            .setSingleChoiceItems(arrayOf("L1 + R1", "L2 + R2", "L3 + R3", getString(R.string.shoulder_none)), values.indexOf(prefs.toggleButtons)) { dialog, which ->
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

    private fun refresh() {
        pages.values.forEach { it.refresh() }
        comboRows.values.forEach { updateShoulderRow(it) }
        toggleButtonsValue?.text = toggleButtonsLabel()
        toggleDuration?.setEnabled(prefs.toggleButtons != Prefs.TOGGLE_NONE)
        val status = currentServiceStatus()
        serviceWarning.text = serviceStatusText(status)
        serviceWarning.visibility = if (status == ServiceStatus.ON) View.GONE else View.VISIBLE
        // Without pservice, Back + D-pad has to take window focus (DpadOverlay) and games may pause.
        rootWarning.row.visibility = if (prefs.hasDpadAction() && !PService.cachedAvailable()) View.VISIBLE else View.GONE
        singleValue?.text = describeSingleAction()
        statusLabel?.text = serviceStatusText(status)
        val enabled = isAccessibilityServiceEnabled()
        openA11yHandle.row.visibility = if (enabled) View.GONE else View.VISIBLE
        dividerBefore(openA11yHandle.row)?.visibility = if (!enabled) View.VISIBLE else View.GONE
        repairHandle?.row?.visibility = if (enabled) View.VISIBLE else View.GONE
        warnings.visibility = if (rootWarning.row.visibility == View.VISIBLE || !enabled) View.VISIBLE else View.GONE
    }

    private data class Choice(val value: String, val label: String)

    private fun launchers(): List<Choice> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_SECONDARY_HOME)
        launcherChoices?.let { return it }
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).map {
            Choice(
                "${it.activityInfo.packageName}/${it.activityInfo.name}",
                getString(R.string.single_launcher_named, it.loadLabel(packageManager)),
            )
        }.distinctBy { it.value }.also { launcherChoices = it }
    }

    private fun describeSingleAction(action: String = prefs.singleAction): String = when (action) {
        Prefs.SINGLE_BACK -> getString(R.string.single_back)
        Prefs.SINGLE_LAUNCHER -> getString(R.string.single_launcher_default)
        else -> launchers().firstOrNull { it.value == action }?.label ?: getString(R.string.single_launcher_default)
    }

    private fun chooseSingleAction() {
        val choices = listOf(
            Choice(Prefs.SINGLE_BACK, getString(R.string.single_back)),
            Choice(Prefs.SINGLE_LAUNCHER, getString(R.string.single_launcher_default)),
        ) + launchers()
        AlertDialog.Builder(this)
            .setTitle(R.string.single_dialog_title)
            .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), choices.indexOfFirst { it.value == prefs.singleAction }) { dialog, which ->
                prefs.singleAction = choices[which].value
                dialog.dismiss()
                refresh()
            }
            .show()
    }

    /** Every launchable app on the device, shown only after choosing "Launch app". */
    private fun allApps(): List<Choice> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        launcherChoices?.let { return it }
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).map {
            Choice(it.activityInfo.packageName, it.loadLabel(packageManager).toString())
        }.distinctBy { it.value }.sortedBy { it.label }
    }

    private fun shoulderActions(): List<Choice> = listOf(
        Choice("", getString(R.string.shoulder_none)),
        Choice(ACTION_APP, getString(R.string.shoulder_launch_app)),
        Choice(Prefs.SHOULDER_HOME, getString(R.string.shoulder_home)),
        Choice(Prefs.SHOULDER_RECENTS, getString(R.string.shoulder_recents)),
        Choice(Prefs.SHOULDER_SCREENSHOT, getString(R.string.shoulder_screenshot)),
        Choice(Prefs.SHOULDER_FLICK_LEFT, getString(R.string.shoulder_flick_left)),
        Choice(Prefs.SHOULDER_FLICK_RIGHT, getString(R.string.shoulder_flick_right)),
        Choice(Prefs.SHOULDER_VOLUME_UP, getString(R.string.shoulder_volume_up)),
        Choice(Prefs.SHOULDER_VOLUME_DOWN, getString(R.string.shoulder_volume_down)),
        Choice(Prefs.SHOULDER_BRIGHTNESS_UP, getString(R.string.shoulder_brightness_up)),
        Choice(Prefs.SHOULDER_BRIGHTNESS_DOWN, getString(R.string.shoulder_brightness_down)),
        Choice(Prefs.SHOULDER_CONTROLLER_FOCUS, getString(R.string.shoulder_controller_focus)),
        Choice(Prefs.SHOULDER_QUICK_SETTINGS, getString(R.string.shoulder_quick_settings)),
        Choice(Prefs.SHOULDER_NOTIFICATIONS, getString(R.string.shoulder_notifications)),
        Choice(Prefs.SHOULDER_INTERNET, getString(R.string.shoulder_internet)),
        Choice(Prefs.SHOULDER_BLUETOOTH, getString(R.string.shoulder_bluetooth)),
        Choice(Prefs.SHOULDER_NIGHT_LIGHT, getString(R.string.shoulder_night_light)),
        Choice(Prefs.SHOULDER_EXTRA_DIM, getString(R.string.shoulder_extra_dim)),
        Choice(Prefs.SHOULDER_DISPLAY_SETTINGS, getString(R.string.shoulder_display_settings)),
        Choice(Prefs.SHOULDER_SOUND_SETTINGS, getString(R.string.shoulder_sound_settings)),
        Choice(Prefs.SHOULDER_LOCATION_SETTINGS, getString(R.string.shoulder_location_settings)),
        Choice(Prefs.SHOULDER_CAST_SETTINGS, getString(R.string.shoulder_cast_settings)),
        Choice(Prefs.SHOULDER_DND_SETTINGS, getString(R.string.shoulder_dnd_settings)),
        Choice(Prefs.SHOULDER_BATTERY_SAVER, getString(R.string.shoulder_battery_saver)),
    )

    private fun describeShoulderAction(button: ComboButton): String = describeAction(prefs.shoulderAction(button))

    private fun describeAction(action: String): String {
        return when (action) {
            "" -> getString(R.string.shoulder_none)
            else -> shoulderActions().firstOrNull { it.value == action }?.label
                ?: runCatching {
                    appLabels.getOrPut(action) {
                        getString(R.string.shoulder_launch_app_named, packageManager.getApplicationInfo(action, 0).loadLabel(packageManager))
                    }
                }.getOrDefault(getString(R.string.shoulder_launch_app))
        }
    }

    private fun levelTargets(): List<Choice> = listOf(
        Choice(Prefs.TARGET_BOTH, getString(R.string.level_target_both)),
        Choice(Prefs.DISPLAY_TOP, getString(R.string.level_target_top)),
        Choice(Prefs.DISPLAY_BOTTOM, getString(R.string.level_target_bottom)),
    )

    private fun describeLevelTarget(button: ComboButton): String = levelTargets().let { targets ->
        (targets.firstOrNull { it.value == prefs.levelTarget(button) } ?: targets.first()).label
    }

    private fun focusTargets(): List<Choice> = listOf(
        Choice(Prefs.FOCUS_TOGGLE, getString(R.string.focus_target_toggle)),
        Choice(Prefs.DISPLAY_TOP, getString(R.string.focus_target_top)),
        Choice(Prefs.DISPLAY_BOTTOM, getString(R.string.focus_target_bottom)),
    )

    private fun describeFocusTarget(button: ComboButton): String = focusTargets().let { targets ->
        (targets.firstOrNull { it.value == prefs.focusTarget(button) } ?: targets.first()).label
    }

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

    private fun describeFocusPosition(button: ComboButton): String =
        if (!prefs.focusIndicator(button)) getString(R.string.focus_position_off)
        else focusPositions().first { it.value == prefs.focusPosition(button) }.label

    private fun choosePosition(row: ComboRow) {
        val positions = focusPositions()
        lateinit var grid: PositionGrid
        fun refresh() = grid.update(prefs.focusPosition(row.button), prefs.focusIndicator(row.button))
        fun preview() {
            val on = prefs.focusIndicator(row.button)
            SwitcherService.previewFocusIndicator?.invoke(
                prefs.focusTarget(row.button) != Prefs.DISPLAY_BOTTOM,
                prefs.focusPosition(row.button).takeIf { on },
                prefs.focusDuration(row.button),
            )
        }
        grid = positionGrid(positions.map { it.value to it.label }, { getString(R.string.focus_position_selected, it) }) {
            prefs.setFocusPosition(row.button, it)
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
            setText(R.string.focus_indicator_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isChecked = prefs.focusIndicator(row.button)
            setOnCheckedChangeListener { _, on ->
                prefs.setFocusIndicator(row.button, on)
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
            .setTitle(R.string.focus_position_title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null)
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
        val display: View,
        val target: SettingsPage.ChooserHandle,
        val displayOptions: View,
        val targetOptions: View,
    ) {
        var focusOptions: SettingsPage.FocusOptionsHandle? = null
        val position: SettingsPage.ChooserHandle get() = checkNotNull(focusOptions).position
    }

    private fun chooseShoulderAction(row: ComboRow) {
        val choices = shoulderActions()
        val stored = prefs.shoulderAction(row.button)
        val current = if (Prefs.isShoulderApp(stored)) ACTION_APP else stored
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.shoulder_choose_action_title, row.label))
            .setSingleChoiceItems(
                choices.map { it.label }.toTypedArray(),
                choices.indexOfFirst { it.value == current }.coerceAtLeast(0),
            ) { dialog, which ->
                dialog.dismiss()
                val action = choices[which].value
                if (action == ACTION_APP) {
                    chooseShoulderApp(row)
                } else {
                    prefs.setShoulderAction(row.button, action)
                    updateShoulderRow(row)
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseShoulderApp(row: ComboRow) {
        val apps = allApps()
        val current = prefs.shoulderAction(row.button)
        AlertDialog.Builder(this)
            .setTitle(R.string.shoulder_choose_app_title)
            .setSingleChoiceItems(
                apps.map { it.label }.toTypedArray(),
                apps.indexOfFirst { it.value == current },
            ) { dialog, which ->
                prefs.setShoulderAction(row.button, apps[which].value)
                dialog.dismiss()
                updateShoulderRow(row)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseTarget(row: ComboRow) {
        val focus = prefs.shoulderAction(row.button) == Prefs.SHOULDER_CONTROLLER_FOCUS
        val targets = if (focus) focusTargets() else levelTargets()
        val selected = if (focus) prefs.focusTarget(row.button) else prefs.levelTarget(row.button)
        AlertDialog.Builder(this)
            .setTitle(if (focus) R.string.focus_target_dialog_title else R.string.level_target_dialog_title)
            .setSingleChoiceItems(
                targets.map { it.label }.toTypedArray(),
                targets.indexOfFirst { it.value == selected }.coerceAtLeast(0),
            ) { dialog, which ->
                if (focus) prefs.setFocusTarget(row.button, targets[which].value)
                else prefs.setLevelTarget(row.button, targets[which].value)
                dialog.dismiss()
                updateShoulderRow(row)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildShoulderRow(
        page: SettingsPage,
        button: ComboButton,
        titleRes: Int,
    ) {
        val card = page.card {}
        lateinit var row: ComboRow
        val action = card.chooserRow(titleRes, describeShoulderAction(button)) { chooseShoulderAction(row) }
        lateinit var display: SettingsPage.ToggleHandle
        val displayOptions = card.subOptions {
            display = toggleRow(R.string.shoulder_top_screen, null, prefs.shoulderDisplay(button) == Prefs.DISPLAY_TOP, readValue = { prefs.shoulderDisplay(button) == Prefs.DISPLAY_TOP }) {
                prefs.setShoulderDisplay(button, if (it) Prefs.DISPLAY_TOP else Prefs.DISPLAY_BOTTOM)
            }
        }
        lateinit var target: SettingsPage.ChooserHandle
        val targetOptions = card.subOptions {
            target = chooserRow(R.string.level_target_title, describeLevelTarget(button)) { chooseTarget(row) }
        }
        row = ComboRow(card, button, getString(titleRes), action, display.row, target, displayOptions, targetOptions)
        comboRows[button.prefKey] = row
        updateShoulderRow(row)
    }

    private fun updateShoulderRow(row: ComboRow) {
        row.action.value.text = describeShoulderAction(row.button)
        val action = prefs.shoulderAction(row.button)
        row.display.visibility = if (Prefs.isShoulderApp(action) || action == Prefs.SHOULDER_HOME) View.VISIBLE else View.GONE
        val hasTarget = Prefs.hasScreenTarget(action)
        val focus = action == Prefs.SHOULDER_CONTROLLER_FOCUS
        row.target.row.visibility = if (hasTarget && !focus) View.VISIBLE else View.GONE
        row.displayOptions.visibility = row.display.visibility
        row.targetOptions.visibility = row.target.row.visibility
        row.action.row.setPadding(row.action.row.paddingLeft, row.action.row.paddingTop, row.action.row.paddingRight,
            dp(if (hasTarget || row.display.visibility == View.VISIBLE) 0 else 10))
        row.target.value.text = describeLevelTarget(row.button)
        row.target.row.contentDescription = getString(R.string.level_target_accessibility, row.label, row.target.value.text)
        if (focus && row.focusOptions == null) {
            row.focusOptions = row.card.focusOptions(
                describeFocusTarget(row.button), describeFocusPosition(row.button),
                { chooseTarget(row) }, { choosePosition(row) },
            )
        }
        row.focusOptions?.let { options ->
            options.row.visibility = if (focus) View.VISIBLE else View.GONE
            options.target.value.text = describeFocusTarget(row.button)
            options.target.row.contentDescription = getString(R.string.focus_target_accessibility, row.label, options.target.value.text)
            options.position.value.text = describeFocusPosition(row.button)
            options.position.row.contentDescription = getString(R.string.focus_position_accessibility, row.label, options.position.value.text)
        }
        row.action.row.contentDescription = getString(R.string.shoulder_action_accessibility, row.label, row.action.value.text)
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

    private fun confirmUnbindOptionals() = showChanges(
        SettingsChangePlan.create(prefs.snapshot(), null, SettingsChangeAction.UNBIND),
    )

    private fun confirmResetSettings() = showChanges(
        SettingsChangePlan.create(prefs.snapshot(), null, SettingsChangeAction.PRESET),
    )

    private fun showChanges(plan: SettingsChangePlan) {
        val scope = getString(plan.section?.titleRes ?: R.string.settings_entire_app)
        val preset = plan.action == SettingsChangeAction.PRESET
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
        if (plan.isEmpty) line(getString(if (preset) R.string.settings_already_preset else R.string.settings_already_unbound))
        else {
            line(getString(R.string.settings_changes_scope, scope))
            changeLines(plan).forEach(::line)
            if (plan.removesToggle) line(getString(R.string.settings_unbind_reactivates))
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(if (preset) R.string.settings_preset_title else R.string.settings_unbind_title, scope))
            .setView(ScrollView(this).apply { addView(content) })
        if (plan.isEmpty) builder.setPositiveButton(android.R.string.ok, null)
        else builder.setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_apply) { _, _ ->
                if (prefs.applySettings(plan.before, plan.after)) {
                    invalidatePages()
                    Toast.makeText(this, if (preset) R.string.reset_settings_done else R.string.unbind_optionals_done, Toast.LENGTH_SHORT).show()
                } else {
                    // A preview is a promise: changed preferences require a new confirmation.
                    showChanges(SettingsChangePlan.create(prefs.snapshot(), plan.section, plan.action))
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
        fun position(value: String) = focusPositions().first { it.value == value }.label
        fun toggle(value: String) = when (value) {
            Prefs.TOGGLE_NONE -> getString(R.string.shoulder_none)
            Prefs.TOGGLE_L1_R1 -> "L1 + R1"
            Prefs.TOGGLE_L2_R2 -> "L2 + R2"
            else -> "L3 + R3"
        }
        fun launches(value: BindingSettings) = Prefs.isShoulderApp(value.action) || value.action == Prefs.SHOULDER_HOME
        fun binding(value: BindingSettings): String {
            val detail = when {
                launches(value) -> target(value.display)
                value.action == Prefs.SHOULDER_SCREENSHOT -> target(value.screenshotTarget())
                Prefs.isLevelAction(value.action) -> target(value.levelTarget())
                value.action == Prefs.SHOULDER_CONTROLLER_FOCUS -> focus(value.focusTarget)
                else -> return describeAction(value.action)
            }
            return getString(R.string.settings_action_detail, describeAction(value.action), detail)
        }
        val a = plan.before
        val b = plan.after
        addChange(getString(R.string.single_section_title), describeSingleAction(a.singleAction), describeSingleAction(b.singleAction))
        addChange(getString(R.string.double_enabled), onOff(a.doubleEnabled), onOff(b.doubleEnabled))
        addChange(getString(R.string.settings_double_time), ms(a.doubleMs), ms(b.doubleMs))
        addChange(getString(R.string.tap_hold_enabled), onOff(a.tapHoldEnabled), onOff(b.tapHoldEnabled))
        addChange(getString(R.string.settings_tap_hold_time), ms(a.tapHoldMs), ms(b.tapHoldMs))
        addChange(getString(R.string.hold_enabled), onOff(a.holdEnabled), onOff(b.holdEnabled))
        addChange(getString(R.string.settings_hold_time), ms(a.holdMs), ms(b.holdMs))
        addChange(getString(R.string.switcher_all_apps), onOff(a.allApps), onOff(b.allApps))
        addChange(getString(R.string.switcher_controller), onOff(a.controller), onOff(b.controller))
        addChange(getString(R.string.service_toggle_buttons), toggle(a.toggleButtons), toggle(b.toggleButtons))
        addChange(getString(R.string.settings_toggle_time), ms(a.toggleMs), ms(b.toggleMs))
        fun apps(value: List<AppException>) = value.joinToString(", ") { it.name }.ifEmpty { getString(R.string.exceptions_none) }
        addChange(getString(R.string.exceptions_apps_title), apps(a.exceptions), apps(b.exceptions))
        val buttons = listOf(
            ShoulderButton.L1 to R.string.shoulder_l1, ShoulderButton.R1 to R.string.shoulder_r1,
            ShoulderButton.L2 to R.string.shoulder_l2, ShoulderButton.R2 to R.string.shoulder_r2,
            DpadDirection.UP to R.string.dpad_up, DpadDirection.DOWN to R.string.dpad_down,
            DpadDirection.LEFT to R.string.dpad_left, DpadDirection.RIGHT to R.string.dpad_right,
        )
        buttons.forEach { (button, labelRes) ->
            val old = a.bindings.getValue(button.prefKey)
            val new = b.bindings.getValue(button.prefKey)
            val label = getString(R.string.settings_binding_label, getString(labelRes))
            addChange(label, binding(old), binding(new))
            fun extra(res: Int, from: String, to: String) = addChange(getString(R.string.settings_property_label, label, getString(res)), from, to)
            if (!(launches(old) && launches(new))) extra(R.string.shoulder_top_screen, target(old.display), target(new.display))
            if (!(old.action == Prefs.SHOULDER_SCREENSHOT && new.action == old.action))
                extra(R.string.settings_screenshot_screen, target(old.screenshotTarget()), target(new.screenshotTarget()))
            if (!(Prefs.isLevelAction(old.action) && Prefs.isLevelAction(new.action)))
                extra(R.string.settings_level_screen, target(old.levelTarget()), target(new.levelTarget()))
            if (!(old.action == Prefs.SHOULDER_CONTROLLER_FOCUS && new.action == old.action))
                extra(R.string.focus_target_title, focus(old.focusTarget), focus(new.focusTarget))
            extra(R.string.focus_indicator_title, onOff(old.indicator), onOff(new.indicator))
            extra(R.string.focus_header_duration, ms(old.duration), ms(new.duration))
            extra(R.string.focus_header_position, position(old.position), position(new.position))
        }
    }

    private fun invalidatePages() {
        exceptionsPage?.dispose()
        exceptionsPage = null
        pages.clear()
        comboRows.clear()
        singleValue = null
        toggleButtonsValue = null
        toggleDuration = null
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
                UpdateChecker.Status.UPDATE_AVAILABLE -> AlertDialog.Builder(this)
                    .setTitle(R.string.update_found_title)
                    .setMessage(getString(R.string.update_found_message, result.tagName.orEmpty()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.open_release) { _, _ ->
                        result.htmlUrl?.let { openUrl(it) }
                    }
                    .show()
                UpdateChecker.Status.UP_TO_DATE -> AlertDialog.Builder(this)
                    .setTitle(R.string.no_update_title)
                    .setMessage(R.string.no_update_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                UpdateChecker.Status.ERROR -> AlertDialog.Builder(this)
                    .setTitle(R.string.update_check_failed_title)
                    .setMessage(R.string.update_check_failed_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
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
        private const val ACTION_APP = "@choose_app"
    }
}
