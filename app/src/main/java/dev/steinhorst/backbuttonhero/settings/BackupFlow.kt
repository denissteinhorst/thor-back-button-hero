package dev.steinhorst.backbuttonhero.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.prefs.SettingsBackup
import dev.steinhorst.backbuttonhero.prefs.SettingsChangeAction
import dev.steinhorst.backbuttonhero.prefs.SettingsChangePlan

/**
 * Saving and restoring the settings file: the system file picker, reading and writing off the main
 * thread, the refusals, the version hint and finally the change preview [showChanges], which alone
 * applies anything. The activity forwards its picker results to [onResult].
 */
internal class BackupFlow(
    private val activity: Activity,
    private val prefs: Prefs,
    private val versionName: () -> String,
    private val showChanges: (SettingsChangePlan) -> Unit,
) {
    // The file picker is the whole permission model: the user hands over one file, nothing else.
    @Suppress("DEPRECATION")
    fun startSave() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, SettingsBackup.fileName(versionName()))
        runCatching { activity.startActivityForResult(intent, REQUEST_SAVE) }
            .onFailure { Toast.makeText(activity, R.string.backup_save_failed, Toast.LENGTH_LONG).show() }
    }

    @Suppress("DEPRECATION")
    fun startLoad() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        runCatching { activity.startActivityForResult(intent, REQUEST_LOAD) }
            .onFailure { Toast.makeText(activity, R.string.backup_read_failed, Toast.LENGTH_LONG).show() }
    }

    fun onResult(requestCode: Int, uri: Uri) {
        when (requestCode) {
            REQUEST_SAVE -> save(uri)
            REQUEST_LOAD -> load(uri)
        }
    }

    @Suppress("DEPRECATION")
    private fun save(uri: Uri) {
        val json = SettingsBackup.write(prefs.snapshot(), versionName(),
            activity.packageManager.getPackageInfo(activity.packageName, 0).let { it.longVersionCode })
        Thread {
            val saved = runCatching {
                activity.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }.isSuccess
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Toast.makeText(activity, if (saved) R.string.backup_saved else R.string.backup_save_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun load(uri: Uri) {
        Thread {
            // One byte over the limit is enough to tell a file is too large, without reading all of it.
            val bytes = runCatching {
                activity.contentResolver.openInputStream(uri)!!.use { input ->
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
            activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) offer(bytes) }
        }.apply { isDaemon = true }.start()
    }

    /** What a picked file leads to: a refusal, or the version hint and the preview of every change. */
    fun offer(bytes: ByteArray?) {
        if (bytes == null) { showProblem(R.string.backup_read_failed); return }
        when (val result = SettingsBackup.read(String(bytes, Charsets.UTF_8))) {
            is SettingsBackup.Result.Rejected -> showProblem(when (result.reason) {
                SettingsBackup.Rejection.TOO_LARGE -> R.string.backup_too_large
                SettingsBackup.Rejection.NOT_JSON -> R.string.backup_not_json
                SettingsBackup.Rejection.NOT_A_BACKUP -> R.string.backup_not_a_backup
            })
            is SettingsBackup.Result.Loaded -> confirm(result)
        }
    }

    private fun showProblem(message: Int) {
        AlertDialog.Builder(activity).setTitle(R.string.backup_problem_title).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    /** A backup from another version is allowed, but says so first: it may not fit, and that is all it says. */
    private fun confirm(loaded: SettingsBackup.Result.Loaded) {
        val plan = SettingsChangePlan(prefs.snapshot(), loaded.state, null, SettingsChangeAction.RESTORE)
        val version = versionName()
        val compatibility = SettingsBackup.compatibility(loaded.info, version)
        if (compatibility == SettingsBackup.Compatibility.SAME) { showChanges(plan); return }
        val message = when (compatibility) {
            SettingsBackup.Compatibility.OLDER -> R.string.backup_version_older
            SettingsBackup.Compatibility.NEWER -> R.string.backup_version_newer
            else -> R.string.backup_version_unknown
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.backup_version_title)
            .setMessage(if (compatibility == SettingsBackup.Compatibility.UNKNOWN) activity.getString(message, version)
                else activity.getString(message, loaded.info.appVersion ?: "?", version))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.backup_continue) { _, _ -> showChanges(plan) }
            .show()
    }

    companion object {
        private const val REQUEST_SAVE = 2
        private const val REQUEST_LOAD = 3
    }
}
