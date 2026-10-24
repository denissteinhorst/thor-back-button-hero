package dev.steinhorst.backbuttonhero

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Polls the project's GitHub releases for a newer version and raises a notification
 * that deep-links to the release page. No in-app downloader/installer on purpose:
 * that needs REQUEST_INSTALL_PACKAGES and a download manager, more surface than a
 * single-user side-loaded app needs. If that becomes annoying, add it later.
 */
object UpdateChecker {

    // The only two lines to touch once the repo is created under its final name.
    private const val GITHUB_OWNER = "denissteinhorst"
    private const val GITHUB_REPO = "thor-back-button-hero"
    const val REPOSITORY_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"

    private const val CHANNEL_ID = "update_available"
    private const val NOTIFICATION_ID = 1001

    enum class Status { UPDATE_AVAILABLE, UP_TO_DATE, ERROR }

    data class CheckResult(
        val status: Status,
        val tagName: String? = null,
        val htmlUrl: String? = null,
    )

    fun checkInBackground(context: Context, callback: ((CheckResult) -> Unit)? = null) {
        val appContext = context.applicationContext
        Thread {
            val release = runCatching { fetchLatestRelease() }.getOrNull()
            if (release == null) {
                deliver(callback, CheckResult(Status.ERROR))
                return@Thread
            }
            val currentVersionCode = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode.toInt()
            }.getOrNull()
            if (currentVersionCode == null) {
                deliver(callback, CheckResult(Status.ERROR))
                return@Thread
            }
            if (release.versionCode > currentVersionCode) {
                notify(appContext, release)
                deliver(callback, CheckResult(Status.UPDATE_AVAILABLE, release.tagName, release.htmlUrl))
            } else {
                deliver(callback, CheckResult(Status.UP_TO_DATE))
            }
        }.start()
    }

    private fun deliver(callback: ((CheckResult) -> Unit)?, result: CheckResult) {
        if (callback == null) return
        Handler(Looper.getMainLooper()).post { callback(result) }
    }

    internal data class Release(val tagName: String, val htmlUrl: String, val versionCode: Int)

    /** CI tags releases "v<versionCode>" (see release workflow) so no extra version parsing is needed. */
    private fun fetchLatestRelease(): Release? {
        val connection = URL("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest")
            .openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseRelease(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseRelease(body: String): Release? {
        val json = JSONObject(body)
        val tagName = json.getString("tag_name")
        val versionCode = tagName.removePrefix("v").toIntOrNull() ?: return null
        return Release(tagName, json.getString("html_url"), versionCode)
    }

    private fun notify(context: Context, release: Release) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val appName = context.getString(R.string.app_name)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.update_available_title, appName))
            .setContentText(context.getString(R.string.update_available_text, release.tagName))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
