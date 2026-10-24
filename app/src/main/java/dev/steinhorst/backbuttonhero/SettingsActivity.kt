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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The single settings screen: back-button gestures first (used daily), then shoulder-button
 * assignments, then help/debug/maintenance last - grouped into cards with consistent section
 * headers rather than one flat list.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var content: LinearLayout
    private lateinit var warning: SettingsPage.WarningHandle
    private lateinit var singleValue: TextView
    private lateinit var statusLabel: TextView
    private lateinit var openA11yHandle: SettingsPage.ActionHandle
    private lateinit var repairHandle: SettingsPage.ActionHandle
    private lateinit var updateHandle: SettingsPage.ActionHandle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = getString(R.string.app_name)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        build()
        ensureNotificationPermission()
        UpdateChecker.checkInBackground(this)
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
        refresh()
        // A service rebind can finish just after the activity resumes.
        content.postDelayed({ if (!isFinishing && !isDestroyed) refresh() }, 600)
    }

    private fun build() {
        val page = SettingsPage(this, content)

        page.card {
            warning = warningRow(serviceStatusText(currentServiceStatus()))
        }

        page.sectionHeader(R.string.single_section_title)
        page.card {
            singleValue = chooserRow(R.string.single_title, describeSingleAction()) { chooseSingleAction() }.value
        }

        page.sectionHeader(R.string.double_section_title)
        page.card {
            toggleRow(R.string.double_enabled, R.string.double_title, prefs.doubleTapEnabled) { prefs.doubleTapEnabled = it }
            sliderRow(R.string.double_speed, 150, 600, 50, prefs.doubleTapMs, R.string.double_hint) { prefs.doubleTapMs = it }
        }

        page.sectionHeader(R.string.tap_hold_section_title)
        page.card {
            toggleRow(R.string.tap_hold_enabled, R.string.tap_hold_title, prefs.tapHoldEnabled) { prefs.tapHoldEnabled = it }
            sliderRow(R.string.tap_hold_speed, 500, 2000, 100, prefs.tapHoldMs, R.string.tap_hold_hint) { prefs.tapHoldMs = it }
        }

        page.sectionHeader(R.string.hold_section_title)
        page.card {
            toggleRow(R.string.hold_enabled, R.string.hold_title, prefs.holdEnabled) { prefs.holdEnabled = it }
            sliderRow(R.string.hold_speed, 500, 2000, 100, prefs.holdMs, R.string.hold_hint) { prefs.holdMs = it }
        }

        page.sectionHeader(R.string.shoulder_title)
        page.hintText(R.string.shoulder_hint)
        buildShoulderRow(page, ShoulderButton.L1, R.string.shoulder_l1)
        buildShoulderRow(page, ShoulderButton.R1, R.string.shoulder_r1)
        buildShoulderRow(page, ShoulderButton.L2, R.string.shoulder_l2)
        buildShoulderRow(page, ShoulderButton.R2, R.string.shoulder_r2)

        page.sectionHeader(R.string.help_debug_title)
        page.card {
            statusLabel = textRow(serviceStatusText(currentServiceStatus()))
            openA11yHandle = actionRow(R.string.open_a11y) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            repairHandle = actionRow(R.string.repair_service, R.string.repair_service_hint) { explainServiceRepair() }
        }
        page.card {
            actionRow(R.string.reset_settings) { confirmResetSettings() }
        }
        page.card {
            updateHandle = actionRow(R.string.check_for_updates) { checkForUpdates() }
            textRow(getString(R.string.version_label, appVersionName()))
            linkRow(getString(R.string.github_repository), R.string.open_github_repository) { openUrl(UpdateChecker.REPOSITORY_URL) }
        }
    }

    private fun refresh() {
        val status = currentServiceStatus()
        warning.label.text = serviceStatusText(status)
        warning.row.visibility = if (status == ServiceStatus.ON) View.GONE else View.VISIBLE
        singleValue.text = describeSingleAction()
        statusLabel.text = serviceStatusText(status)
        val enabled = isAccessibilityServiceEnabled()
        openA11yHandle.row.visibility = if (enabled) View.GONE else View.VISIBLE
        repairHandle.row.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private data class Choice(val value: String, val label: String)

    private fun launchers(): List<Choice> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_SECONDARY_HOME)
        return packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).map {
            Choice(
                "${it.activityInfo.packageName}/${it.activityInfo.name}",
                getString(R.string.single_launcher_named, it.loadLabel(packageManager)),
            )
        }.distinctBy { it.value }
    }

    private fun describeSingleAction(): String = when (prefs.singleAction) {
        Prefs.SINGLE_BACK -> getString(R.string.single_back)
        Prefs.SINGLE_LAUNCHER -> getString(R.string.single_launcher_default)
        else -> launchers().firstOrNull { it.value == prefs.singleAction }?.label ?: prefs.singleAction
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

    private fun describeShoulderAction(button: ShoulderButton): String {
        val action = prefs.shoulderAction(button)
        return when (action) {
            "" -> getString(R.string.shoulder_none)
            else -> shoulderActions().firstOrNull { it.value == action }?.label
                ?: runCatching {
                    getString(
                        R.string.shoulder_launch_app_named,
                        packageManager.getApplicationInfo(action, 0).loadLabel(packageManager),
                    )
                }.getOrDefault(action)
        }
    }

    private fun chooseShoulderAction(button: ShoulderButton, handle: SettingsPage.ChooserHandle, displayRow: View) {
        val choices = shoulderActions()
        val stored = prefs.shoulderAction(button)
        val current = if (Prefs.isShoulderApp(stored)) ACTION_APP else stored
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.shoulder_choose_action_title, button.name))
            .setSingleChoiceItems(
                choices.map { it.label }.toTypedArray(),
                choices.indexOfFirst { it.value == current }.coerceAtLeast(0),
            ) { dialog, which ->
                dialog.dismiss()
                val action = choices[which].value
                if (action == ACTION_APP) {
                    chooseShoulderApp(button, handle, displayRow)
                } else {
                    prefs.setShoulderAction(button, action)
                    updateShoulderRow(button, handle, displayRow)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseShoulderApp(button: ShoulderButton, handle: SettingsPage.ChooserHandle, displayRow: View) {
        val apps = allApps()
        val current = prefs.shoulderAction(button)
        AlertDialog.Builder(this)
            .setTitle(R.string.shoulder_choose_app_title)
            .setSingleChoiceItems(
                apps.map { it.label }.toTypedArray(),
                apps.indexOfFirst { it.value == current },
            ) { dialog, which ->
                prefs.setShoulderAction(button, apps[which].value)
                dialog.dismiss()
                updateShoulderRow(button, handle, displayRow)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildShoulderRow(page: SettingsPage, button: ShoulderButton, titleRes: Int) {
        val card = page.card {}
        lateinit var handle: SettingsPage.ChooserHandle
        lateinit var displayHandle: SettingsPage.ToggleHandle
        handle = card.chooserRow(titleRes, describeShoulderAction(button)) {
            chooseShoulderAction(button, handle, displayHandle.row)
        }
        displayHandle = card.toggleRow(R.string.shoulder_top_screen, null, prefs.shoulderDisplay(button) == Prefs.DISPLAY_TOP) {
            prefs.setShoulderDisplay(button, if (it) Prefs.DISPLAY_TOP else Prefs.DISPLAY_BOTTOM)
        }
        updateShoulderRow(button, handle, displayHandle.row)
    }

    private fun updateShoulderRow(button: ShoulderButton, handle: SettingsPage.ChooserHandle, displayRow: View) {
        handle.value.text = describeShoulderAction(button)
        val action = prefs.shoulderAction(button)
        displayRow.visibility = if (Prefs.isShoulderApp(action) || action == Prefs.SHOULDER_HOME) View.VISIBLE else View.GONE
        handle.row.contentDescription = getString(
            R.string.shoulder_action_accessibility,
            button.name,
            handle.value.text,
        )
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

    private fun confirmResetSettings() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_settings_title)
            .setMessage(R.string.reset_settings_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.reset_settings_confirm) { _, _ ->
                prefs.resetToDefaults()
                content.removeAllViews()
                build()
                refresh()
                Toast.makeText(this, R.string.reset_settings_done, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun checkForUpdates() {
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
        private const val REQUEST_NOTIFICATIONS = 1
        private const val ACTION_APP = "@choose_app"
    }
}
