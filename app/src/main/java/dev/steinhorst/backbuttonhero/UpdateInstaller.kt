package dev.steinhorst.backbuttonhero

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a release APK straight into an Android [PackageInstaller] session and commits it. Android
 * then shows its own confirmation and installs only a package signed with the installed app's key, so a
 * wrong or tampered file cannot replace the app. The checksum GitHub publishes is checked before the
 * commit as well; nothing is ever stored as a file of its own.
 */
class UpdateInstaller(private val context: Context) {
    enum class Failure { NOT_ALLOWED, NETWORK, CORRUPT, SESSION }

    interface Listener {
        /** [total] is the size GitHub announced; called on the main thread. */
        fun onProgress(done: Long, total: Long)
        /** The package is with Android now, which asks the user next. */
        fun onHandedOver()
        fun onFailed(failure: Failure)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun cancel() { cancelled = true }

    fun install(asset: UpdateChecker.Asset, listener: Listener) {
        if (!canInstall()) return listener.onFailed(Failure.NOT_ALLOWED)
        Thread {
            val failure = runCatching { transfer(asset, listener) }.getOrElse { if (it is IOException) Failure.NETWORK else Failure.SESSION }
            if (cancelled) return@Thread
            main.post { if (failure == null) listener.onHandedOver() else listener.onFailed(failure) }
        }.apply { isDaemon = true }.start()
    }

    private fun transfer(asset: UpdateChecker.Asset, listener: Listener): Failure? {
        val installer = context.packageManager.packageInstaller
        // A session left behind by an earlier try would only hold storage.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setSize(asset.size) }
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        var committed = false
        try {
            val connection = URL(asset.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return Failure.NETWORK
                val digest = MessageDigest.getInstance("SHA-256")
                val written = session.openWrite("update.apk", 0, asset.size).use { out ->
                    copy(connection.inputStream, out, digest, asset.size) { done -> main.post { listener.onProgress(done, asset.size) } }
                        .also { session.fsync(out) }
                }
                if (cancelled) return null
                if (!matches(written, asset.size, digest.digest(), asset.sha256)) return Failure.CORRUPT
            } finally {
                connection.disconnect()
            }
            val receipt = PendingIntent.getBroadcast(
                context, id, Intent(context, StatusReceiver::class.java),
                // The installer adds its status extras to this intent, so it has to be mutable.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(receipt.intentSender)
            committed = true
            return null
        } finally {
            session.close()
            if (!committed) runCatching { installer.abandonSession(id) }
        }
    }

    /** Receives the installer's answer: asks the user to confirm, or says why Android refused. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
                else -> {
                    val message = refusalMessage(intent.getIntExtra(PackageInstaller.EXTRA_STATUS, 0))
                    Toast.makeText(context, context.getString(message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        /** Which message names Android's refusal; a signature or version clash is the common one. */
        fun refusalMessage(status: Int): Int = when (status) {
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.update_install_conflict
            PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.update_install_storage
            else -> R.string.update_install_failed
        }

        /**
         * Copies [input] to [output] while feeding [digest], reporting progress about every percent, and
         * returns the bytes copied. A stream longer than [expected] is cut off there: it can only be wrong.
         */
        internal fun copy(input: InputStream, output: OutputStream, digest: MessageDigest, expected: Long, progress: (Long) -> Unit = {}): Long {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            var reported = -1L
            while (total <= expected) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                total += read
                val step = total * 100 / expected
                if (step != reported) { reported = step; progress(total) }
            }
            return total
        }

        /** The download is what GitHub announced: the right length and, when GitHub published one, the right checksum. */
        internal fun matches(written: Long, expected: Long, sha256: ByteArray, published: String?): Boolean =
            written == expected && (published == null || sha256.joinToString("") { "%02x".format(it) } == published)
    }
}
