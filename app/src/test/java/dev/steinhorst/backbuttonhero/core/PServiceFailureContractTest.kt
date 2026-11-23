package dev.steinhorst.backbuttonhero.core

import android.app.ActivityManager
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.SwitcherService
import dev.steinhorst.backbuttonhero.support.FakePService
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Freezes how [PService] reports failure and how callers fall back, through the binder seam the
 * command tests already use: stdout or null (never an exit status), an empty reply as "", a cached
 * success with a 10 s retry after a miss, and the unprivileged kill when the daemon is out of reach.
 */
@RunWith(RobolectricTestRunner::class)
class PServiceFailureContractTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun field(name: String) = PService::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun install(binder: IBinder?, available: Boolean) {
        field("cached").set(PService, binder)
        field("checked").setBoolean(PService, available)
    }

    @After
    fun reset() {
        install(null, false)
        field("lastFail").setLong(PService, -10_000L)
    }

    private class Daemon(private val onTransact: (Parcel?) -> Boolean) : Binder() {
        var calls = 0
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            calls++
            return onTransact(reply)
        }
    }

    @Test
    fun `a refused transaction or a thrown one is null, an empty reply is an empty string`() {
        install(Daemon { false }, true)
        assertNull(PService.exec("id"))
        install(Daemon { throw RemoteException("gone") }, true)
        assertNull(PService.exec("id"))
        install(Daemon { true }, true)
        assertEquals("", PService.exec("id"))
        install(Daemon { it?.writeByteArray(ByteArray(0)); true }, true)
        assertEquals("", PService.exec("id"))
    }

    @Test
    fun `no daemon means unavailable and every privileged call reports failure`() {
        install(null, false)
        assertFalse(PService.isAvailable())
        assertFalse(PService.cachedAvailable())
        assertNull(PService.exec("id"))
        assertFalse(PService.forceStop("com.example.game"))
        assertNull(PService.runEntryPoint(context, "TaskRemover", "com.example.game"))
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("pservice_") })
    }

    @Test
    fun `a miss is retried only after ten seconds and a success stays cached`() {
        var uid = "uid=1000"
        val daemon = Daemon { it?.writeByteArray(uid.toByteArray()); true }
        install(daemon, false)
        assertFalse(PService.isAvailable())
        assertEquals(1, daemon.calls)
        uid = "uid=0(root)"
        assertFalse(PService.isAvailable())
        assertEquals(1, daemon.calls)
        ShadowSystemClock.advanceBy(Duration.ofMillis(10_001))
        assertTrue(PService.isAvailable())
        assertEquals(2, daemon.calls)
        uid = "uid=1000"
        assertTrue(PService.isAvailable())
        assertTrue(PService.cachedAvailable())
        assertEquals(2, daemon.calls)
    }

    @Test
    fun `a cached success runs the probe callback at once on the calling thread`() {
        install(FakePService(), true)
        var thread: Thread? = null
        PService.probeAsync { thread = Thread.currentThread() }
        assertSame(Thread.currentThread(), thread)
    }

    @Test
    fun `an entry point keeps no script behind and passes the first output line through`() {
        install(Daemon { it?.writeByteArray("ERR denied\n".toByteArray()); true }, true)
        assertEquals("ERR denied", PService.runEntryPoint(context, "TaskRemover", "com.example.game"))
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("pservice_") })
    }

    private fun swipeKill(packages: List<String>): List<String> {
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            val am = shadowOf(service.getSystemService(ActivityManager::class.java))
            ReflectionHelpers.callInstanceMethod<Any?>(service, "killOnly",
                ClassParameter.from(List::class.java, packages), ClassParameter.from(Set::class.java, setOf(0)),
                ClassParameter.from(Boolean::class.javaPrimitiveType, false), ClassParameter.from(Function0::class.java, null))
            val killed = mutableListOf<String>()
            repeat(30) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                Thread.sleep(20)
                am.backgroundPackage?.let { if (killed.lastOrNull() != it) killed += it }
            }
            return killed
        } finally {
            runCatching { service.onDestroy() }
        }
    }

    @Test
    fun `without the daemon a swipe-away falls back to the unprivileged kill`() {
        install(null, false)
        assertEquals(listOf("com.example.game"), swipeKill(listOf("com.example.game")))
    }

    @Test
    fun `a failed root removal also ends in the unprivileged kill`() {
        val fake = FakePService { "ERR no task" }.install()
        try {
            assertEquals(listOf("com.example.game"), swipeKill(listOf("com.example.game")))
            assertTrue(fake.commands.any { "force-stop" in it })
        } finally {
            fake.uninstall()
        }
    }
}
