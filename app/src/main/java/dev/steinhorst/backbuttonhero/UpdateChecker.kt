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
 * that deep-links to the release page. The release's APK is described here as well
 * ([Asset]); [UpdateInstaller] downloads and hands it to Android's own installer, which
 * asks the user and refuses a package signed with another key.
 */
object UpdateChecker {

    // The only two lines to touch once the repo is created under its final name.
    private const val GITHUB_OWNER = "denissteinhorst"
    private const val GITHUB_REPO = "thor-back-button-hero"
    const val REPOSITORY_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"

    private const val CHANNEL_ID = "update_available"
    private const val NOTIFICATION_ID = 1001

    enum class Status { UPDATE_AVAILABLE, UP_TO_DATE, ERROR }

    /** Why a check failed, so the message can name the real cause instead of always blaming the connection. */
    enum class Failure { NETWORK, RATE_LIMITED, UNEXPECTED }

    /** The release's APK, as GitHub lists it; [sha256] is lower-case hex when GitHub publishes a digest. */
    data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)

    data class CheckResult(
        val status: Status,
        val tagName: String? = null,
        val htmlUrl: String? = null,
        val apk: Asset? = null,
        val failure: Failure? = null,
        /** For [Failure.RATE_LIMITED]: when GitHub will answer again, rounded up, when it said. */
        val retryMinutes: Int? = null,
    )

    internal sealed class Fetch {
        class Found(val release: Release) : Fetch()
        class Failed(val failure: Failure, val retryMinutes: Int? = null) : Fetch()
    }

    fun checkInBackground(context: Context, callback: ((CheckResult) -> Unit)? = null) {
        val appContext = context.applicationContext
        Thread {
            val fetched = fetchLatestRelease()
            if (fetched is Fetch.Failed) {
                deliver(callback, CheckResult(Status.ERROR, failure = fetched.failure, retryMinutes = fetched.retryMinutes))
                return@Thread
            }
            val release = (fetched as Fetch.Found).release
            val current = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
            }.getOrNull()?.let { parseVersion(it) }
            if (current == null) {
                deliver(callback, CheckResult(Status.ERROR, failure = Failure.UNEXPECTED))
                return@Thread
            }
            if (release.version > current) {
                notify(appContext, release)
                deliver(callback, CheckResult(Status.UPDATE_AVAILABLE, release.tagName, release.htmlUrl, release.apk))
            } else {
                deliver(callback, CheckResult(Status.UP_TO_DATE))
            }
        }.start()
    }

    private fun deliver(callback: ((CheckResult) -> Unit)?, result: CheckResult) {
        if (callback == null) return
        Handler(Looper.getMainLooper()).post { callback(result) }
    }

    internal data class Release(val tagName: String, val htmlUrl: String, val version: Version, val apk: Asset? = null)

    /**
     * Semantic version, compared field by field. Only the release-relevant core is kept:
     * a prerelease or build suffix is dropped by [parseVersion] rather than ordered, which
     * is enough because this project never tags prereleases.
     */
    internal data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

        override fun toString() = "$major.$minor.$patch"
    }

    /**
     * Accepts "v1.2.3" and every shorter form ("v1.2", "v1"), so the legacy
     * "v<versionCode>" tags of the 1.0.0/1.0.1 releases still resolve: "v2" becomes 2.0.0.
     * That keeps a build installed before the switch to semver tags comparable instead of
     * silently failing every future update check.
     */
    internal fun parseVersion(raw: String): Version? {
        // Cutting at '-' also removes any sign, so no component can come back negative.
        val core = raw.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.size > 3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return Version(numbers[0], numbers.getOrElse(1) { 0 }, numbers.getOrElse(2) { 0 })
    }

    /**
     * Unauthenticated GitHub calls are limited to 60 an hour per address, which a shared network or a
     * busy day of testing can use up. That answer is a 403 (or 429) whose headers say so; everything else
     * non-200 is an unexpected answer, and only a call that never got one is a connection problem.
     */
    internal fun httpFailure(code: Int, remaining: String?, reset: String?, retryAfter: String?, nowSeconds: Long): Fetch.Failed {
        val limited = code == 429 || (code == 403 && (remaining == "0" || retryAfter != null))
        if (!limited) return Fetch.Failed(Failure.UNEXPECTED)
        val seconds = retryAfter?.toLongOrNull() ?: reset?.toLongOrNull()?.minus(nowSeconds)
        return Fetch.Failed(Failure.RATE_LIMITED, seconds?.let { ((it + 59) / 60).coerceIn(1, 60).toInt() })
    }

    /** CI tags releases "v<versionName>" (see release workflow), e.g. "v1.1.0". */
    private fun fetchLatestRelease(): Fetch {
        return try {
            val connection = URL("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest")
                .openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    return httpFailure(
                        code, connection.getHeaderField("X-RateLimit-Remaining"), connection.getHeaderField("X-RateLimit-Reset"),
                        connection.getHeaderField("Retry-After"), System.currentTimeMillis() / 1000,
                    )
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parseRelease(body)?.let { Fetch.Found(it) } ?: Fetch.Failed(Failure.UNEXPECTED)
            } finally {
                connection.disconnect()
            }
        } catch (_: java.io.IOException) {
            Fetch.Failed(Failure.NETWORK)
        } catch (_: Exception) {
            Fetch.Failed(Failure.UNEXPECTED)
        }
    }

    internal fun parseRelease(body: String): Release? {
        val json = JSONObject(body)
        val tagName = json.getString("tag_name")
        val version = parseVersion(tagName) ?: return null
        return Release(tagName, json.getString("html_url"), version, parseApk(json))
    }

    /**
     * The one APK of a release, or null when it cannot be trusted enough to install: only an asset
     * of this repository's own release downloads counts, with a plausible size. Android still checks
     * the signature, but nothing from elsewhere should even be downloaded.
     */
    internal fun parseApk(release: JSONObject): Asset? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            val size = asset.optLong("size", -1)
            if (!name.endsWith(".apk") || size !in 1..MAX_APK_BYTES || !url.startsWith("$REPOSITORY_URL/releases/download/")) continue
            val digest = asset.optString("digest").takeIf { it.startsWith("sha256:") }
                ?.removePrefix("sha256:")?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            return Asset(name, url, size, digest)
        }
        return null
    }

    private const val MAX_APK_BYTES = 100L * 1024 * 1024

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
