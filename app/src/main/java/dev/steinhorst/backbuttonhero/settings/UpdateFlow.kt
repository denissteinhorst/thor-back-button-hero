package dev.steinhorst.backbuttonhero.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.UpdateChecker
import dev.steinhorst.backbuttonhero.UpdateInstaller

/** One release check; the app wires [UpdateChecker.checkInBackground], tests a local fake. */
internal fun interface UpdateCheck {
    fun run(context: Context, callback: ((UpdateChecker.CheckResult) -> Unit)?)
}

/**
 * The manual update check of the settings: the busy row, the result dialogs and, on release builds,
 * the download into Android's installer. Results arriving after the activity ended are dropped.
 */
internal class UpdateFlow(
    private val activity: Activity,
    private val check: UpdateCheck,
    private val openUrl: (String) -> Unit,
) {
    private var dialog: AlertDialog? = null

    private val alive get() = !activity.isFinishing && !activity.isDestroyed

    fun dismiss() {
        dialog?.dismiss()
    }

    fun checkNow(handle: SettingsPage.ActionHandle) {
        handle.row.isClickable = false
        handle.row.alpha = 0.5f
        handle.title.setText(R.string.checking_for_updates)
        check.run(activity) { result ->
            if (!alive) return@run
            handle.row.isClickable = true
            handle.row.alpha = 1f
            handle.title.setText(R.string.check_for_updates)
            when (result.status) {
                UpdateChecker.Status.UPDATE_AVAILABLE -> {
                    val apk = result.apk
                    val developerBuild = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                    val offerInstall = UpdateInstaller.canOfferInstall(apk, developerBuild)
                    val found = activity.getString(R.string.update_found_message, result.tagName.orEmpty())
                    val builder = AlertDialog.Builder(activity)
                        .setTitle(R.string.update_found_title)
                        .setMessage(when {
                            offerInstall -> "$found\n\n${activity.getString(R.string.update_install_hint)}"
                            apk != null -> "$found\n\n${activity.getString(R.string.update_developer_build_hint)}"
                            else -> found
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                    if (offerInstall && apk != null) {
                        builder.setPositiveButton(R.string.update_install_action) { _, _ -> startUpdate(apk) }
                        builder.setNeutralButton(R.string.open_release) { _, _ -> result.htmlUrl?.let(openUrl) }
                    } else {
                        builder.setPositiveButton(R.string.open_release) { _, _ -> result.htmlUrl?.let(openUrl) }
                    }
                    builder.show()
                }
                UpdateChecker.Status.UP_TO_DATE -> AlertDialog.Builder(activity)
                    .setTitle(R.string.no_update_title)
                    .setMessage(R.string.no_update_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                UpdateChecker.Status.ERROR -> AlertDialog.Builder(activity)
                    .setTitle(R.string.update_check_failed_title)
                    .setMessage(failureMessage(result))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /** Downloads the release into Android's installer; Android then asks for the final confirmation itself. */
    private fun startUpdate(apk: UpdateChecker.Asset) {
        val installer = UpdateInstaller(activity)
        if (!installer.canInstall()) return explainInstallPermission()
        val bar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(activity).apply { textSize = 14f; text = activity.getString(R.string.update_installing_progress, 0) }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(24), activity.dp(16), activity.dp(24), activity.dp(8))
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = activity.dp(8) })
        }
        dialog?.dismiss()
        val progress = AlertDialog.Builder(activity)
            .setTitle(R.string.update_installing_title)
            .setView(content)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> installer.cancel() }
            .show()
        dialog = progress
        installer.install(apk, object : UpdateInstaller.Listener {
            override fun onProgress(done: Long, total: Long) {
                val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                bar.progress = percent
                label.text = activity.getString(R.string.update_installing_progress, percent)
            }

            override fun onHandedOver() {
                progress.dismiss()
            }

            override fun onFailed(failure: UpdateInstaller.Failure) {
                progress.dismiss()
                if (!alive) return
                if (failure == UpdateInstaller.Failure.NOT_ALLOWED) return explainInstallPermission()
                AlertDialog.Builder(activity)
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
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_allow_title)
            .setMessage(R.string.update_allow_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.update_allow_open) { _, _ ->
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                }
            }
            .show()
    }

    /** Names what actually went wrong; "check your connection" is only true when no answer came at all. */
    private fun failureMessage(result: UpdateChecker.CheckResult): String = when (result.failure) {
        UpdateChecker.Failure.RATE_LIMITED -> result.retryMinutes
            ?.let { activity.resources.getQuantityString(R.plurals.update_rate_limited_minutes, it, it) }
            ?: activity.getString(R.string.update_rate_limited_message)
        UpdateChecker.Failure.UNEXPECTED -> activity.getString(R.string.update_check_unexpected_message)
        else -> activity.getString(R.string.update_check_failed_message)
    }
}
