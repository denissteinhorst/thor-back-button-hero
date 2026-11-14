package dev.steinhorst.backbuttonhero

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class UpdateInstallerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val bytes = ByteArray(300_000) { (it % 251).toByte() }

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
    private fun hex(data: ByteArray) = sha(data).joinToString("") { "%02x".format(it) }

    @Test
    fun `copying streams the bytes, digests them and reports progress`() {
        val out = ByteArrayOutputStream()
        val digest = MessageDigest.getInstance("SHA-256")
        val reports = mutableListOf<Long>()
        val total = UpdateInstaller.copy(ByteArrayInputStream(bytes), out, digest, bytes.size.toLong()) { reports += it }
        assertEquals(bytes.size.toLong(), total)
        assertArrayEquals(bytes, out.toByteArray())
        assertArrayEquals(sha(bytes), digest.digest())
        assertTrue(reports.isNotEmpty() && reports.last() == bytes.size.toLong())
    }

    @Test
    fun `a stream longer than announced is cut off and then rejected`() {
        val out = ByteArrayOutputStream()
        val total = UpdateInstaller.copy(ByteArrayInputStream(bytes), out, MessageDigest.getInstance("SHA-256"), 1_000)
        assertTrue(total > 1_000 && total < bytes.size)
        assertFalse(UpdateInstaller.matches(total, 1_000, ByteArray(32), null))
    }

    @Test
    fun `a download must have the announced length and, when published, the published checksum`() {
        val size = bytes.size.toLong()
        assertTrue(UpdateInstaller.matches(size, size, sha(bytes), null))
        assertTrue(UpdateInstaller.matches(size, size, sha(bytes), hex(bytes)))
        assertFalse(UpdateInstaller.matches(size, size, sha(bytes), "0".repeat(64)))
        assertFalse(UpdateInstaller.matches(size - 1, size, sha(bytes), hex(bytes)))
    }

    @Test
    fun `the install is offered only for a release build that has an APK`() {
        val apk = UpdateChecker.Asset("a.apk", "https://example.com/a.apk", 10, null)
        assertTrue(UpdateInstaller.canOfferInstall(apk, debuggableBuild = false))
        assertFalse(UpdateInstaller.canOfferInstall(apk, debuggableBuild = true))
        assertFalse(UpdateInstaller.canOfferInstall(null, debuggableBuild = false))
    }

    @Test
    fun `Android's refusal is named by its cause`() {
        assertEquals(R.string.update_install_conflict, UpdateInstaller.refusalMessage(PackageInstaller.STATUS_FAILURE_CONFLICT))
        assertEquals(R.string.update_install_conflict, UpdateInstaller.refusalMessage(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE))
        assertEquals(R.string.update_install_storage, UpdateInstaller.refusalMessage(PackageInstaller.STATUS_FAILURE_STORAGE))
        assertEquals(R.string.update_install_failed, UpdateInstaller.refusalMessage(PackageInstaller.STATUS_FAILURE))
    }

    @Test
    fun `the status receiver opens Android's confirmation and stays quiet on success or a cancel`() {
        val receiver = UpdateInstaller.StatusReceiver()
        val confirm = Intent("android.intent.action.VIEW")
        receiver.onReceive(context, Intent().putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, confirm))
        assertEquals(confirm.action, shadowOf(context as android.app.Application).nextStartedActivity.action)
        receiver.onReceive(context, Intent().putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS))
        receiver.onReceive(context, Intent().putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED))
        assertNull(shadowOf(context).nextStartedActivity)
        assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a refusal is shown as a message, and a pending action without an intent does nothing`() {
        val receiver = UpdateInstaller.StatusReceiver()
        receiver.onReceive(context, Intent().putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
        receiver.onReceive(context, Intent().putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_CONFLICT))
        assertEquals(context.getString(R.string.update_install_conflict), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
    }

    // A real transfer against a local server: the bytes reach an installer session, or the session is dropped.
    private class Outcome : UpdateInstaller.Listener {
        val progress = mutableListOf<Long>()
        var handedOver = false
        var failure: UpdateInstaller.Failure? = null
        override fun onProgress(done: Long, total: Long) { progress += done }
        override fun onHandedOver() { handedOver = true }
        override fun onFailed(failure: UpdateInstaller.Failure) { this.failure = failure }
        val finished get() = handedOver || failure != null
    }

    private fun serve(status: Int, body: ByteArray, block: (String) -> Unit) {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/apk") { exchange ->
            if (status == 200) { exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) } }
            else { exchange.sendResponseHeaders(status, -1); exchange.close() }
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}/apk") } finally { server.stop(0) }
    }

    private fun run(asset: UpdateChecker.Asset): Outcome {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        val outcome = Outcome()
        UpdateInstaller(context).install(asset, outcome)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!outcome.finished && System.nanoTime() < deadline) {
            Thread.sleep(10)
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        return outcome
    }

    @Test
    fun `without the install permission nothing is downloaded`() {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        val outcome = Outcome()
        UpdateInstaller(context).install(UpdateChecker.Asset("a.apk", "http://127.0.0.1:1/apk", 10, null), outcome)
        assertEquals(UpdateInstaller.Failure.NOT_ALLOWED, outcome.failure)
    }

    @Test
    fun `a matching download is handed to Android, with progress`() {
        serve(200, bytes) { url ->
            val outcome = run(UpdateChecker.Asset("a.apk", url, bytes.size.toLong(), hex(bytes)))
            assertTrue(outcome.failure?.toString(), outcome.handedOver)
            assertTrue(outcome.progress.isNotEmpty())
        }
    }

    @Test
    fun `a download with the wrong checksum is discarded`() {
        serve(200, bytes) { url ->
            assertEquals(UpdateInstaller.Failure.CORRUPT, run(UpdateChecker.Asset("a.apk", url, bytes.size.toLong(), "0".repeat(64))).failure)
        }
    }

    @Test
    fun `a server error or an unreachable server is a network failure`() {
        serve(500, ByteArray(0)) { url ->
            assertEquals(UpdateInstaller.Failure.NETWORK, run(UpdateChecker.Asset("a.apk", url, 10, null)).failure)
        }
        assertEquals(UpdateInstaller.Failure.NETWORK, run(UpdateChecker.Asset("a.apk", "http://127.0.0.1:1/apk", 10, null)).failure)
    }
}
