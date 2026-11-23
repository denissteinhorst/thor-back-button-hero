package dev.steinhorst.backbuttonhero

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.steinhorst.backbuttonhero.core.AccessibilityAppSource
import dev.steinhorst.backbuttonhero.recents.FrontendParking
import dev.steinhorst.backbuttonhero.recents.WindowTracker
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

/**
 * What a window change sets off: both screens' app history is recorded at once, then the panel
 * refresh (400 ms), the parking reclaim check (300 ms, only while a frontend is parked), the top
 * Recents state check (140 ms, only while its controller session runs) and the thumbnail capture
 * (800 ms) each wait for the events to settle; every new event restarts their wait.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceWindowEventTest {
    private var bottomId = -1

    private fun withService(check: (SwitcherService) -> Unit) {
        bottomId = ShadowDisplayManager.addDisplay("w400dp-h300dp")
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            check(service)
        } finally {
            runCatching { service.onDestroy() }
            ShadowDisplayManager.removeDisplay(bottomId)
        }
    }

    @Suppress("DEPRECATION")
    private fun app(pkg: String): AccessibilityWindowInfo = AccessibilityWindowInfo.obtain().also { window ->
        shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(window).setRoot(AccessibilityNodeInfo.obtain().apply { packageName = pkg })
    }

    private fun event(service: SwitcherService) = service.onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOWS_CHANGED))

    private fun handler(service: SwitcherService) = ReflectionHelpers.getField<Handler>(service, "handler")

    /** The service's runnable of that name; the window-change ones live in its WindowTracker. */
    private fun runnable(service: SwitcherService, name: String): Runnable {
        val tracker = ReflectionHelpers.callInstanceMethod<WindowTracker>(service, "getWindowTracker")
        return when (name) {
            "panelStateRunnable" -> ReflectionHelpers.getField(tracker, "panelRefresh")
            "reclaimRunnable" -> ReflectionHelpers.getField(tracker, "reclaim")
            "captureRunnable" -> ReflectionHelpers.getField(tracker, "capture")
            else -> ReflectionHelpers.getField(service, name)
        }
    }

    /** Whether the named runnable of the service is waiting on its handler. */
    private fun pending(service: SwitcherService, name: String) = handler(service).hasCallbacks(runnable(service, name))

    /** How often the named runnable waits in the main queue: a debounce keeps it at one. */
    private fun queued(service: SwitcherService, name: String): Int {
        val runnable = runnable(service, name)
        var message = ReflectionHelpers.getField<android.os.Message?>(Looper.getMainLooper().queue, "mMessages")
        var count = 0
        while (message != null) {
            if (message.callback === runnable) count++
            message = ReflectionHelpers.getField<android.os.Message?>(message, "next")
        }
        return count
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun history(service: SwitcherService) =
        ReflectionHelpers.getField<Map<Int, List<String>>>(ReflectionHelpers.getField<AccessibilityAppSource>(service, "source"), "history")

    @Test
    fun `a window change records the apps of both screens at once`() = withService { service ->
        shadowOf(service).setWindowsOnDisplay(0, listOf(app("com.example.top")))
        shadowOf(service).setWindowsOnDisplay(bottomId, listOf(app("com.example.bottom")))
        event(service)
        assertEquals(listOf("com.example.top"), history(service)[0])
        assertEquals(listOf("com.example.bottom"), history(service)[bottomId])
    }

    @Test
    fun `panel refresh and capture wait for the events to settle`() = withService { service ->
        event(service)
        assertTrue(pending(service, "panelStateRunnable"))
        assertTrue(pending(service, "captureRunnable"))
        assertFalse("nothing is parked", pending(service, "reclaimRunnable"))
        assertFalse("no top Recents session", pending(service, "topRecentsStateRunnable"))
        idle(300)
        event(service)
        assertEquals("waiting once", 1, queued(service, "panelStateRunnable"))
        assertEquals("waiting once", 1, queued(service, "captureRunnable"))
        idle(300)
        assertTrue("restarted by the second event", pending(service, "panelStateRunnable"))
        idle(101)
        assertFalse(pending(service, "panelStateRunnable"))
        assertTrue(pending(service, "captureRunnable"))
        idle(400)
        assertFalse(pending(service, "captureRunnable"))
    }

    @Test
    fun `a parked frontend gets its reclaim check after 300 ms`() = withService { service ->
        val parking = ReflectionHelpers.getField<FrontendParking>(service, "parking")
        val parkedClass = Class.forName("dev.steinhorst.backbuttonhero.recents.FrontendParking\$Parked")
        val ctor = parkedClass.declaredConstructors.first().apply { isAccessible = true }
        ReflectionHelpers.setField(parking, "parked", ctor.newInstance("com.example.frontend", 5, 0L))
        event(service)
        event(service)
        assertEquals("waiting once", 1, queued(service, "reclaimRunnable"))
        idle(299)
        assertTrue(pending(service, "reclaimRunnable"))
        idle(2)
        assertFalse(pending(service, "reclaimRunnable"))
        ReflectionHelpers.setField(parking, "parked", null)
    }

    @Test
    fun `a running top Recents session rechecks its state after 140 ms`() = withService { service ->
        ReflectionHelpers.setField(service, "topRecentsControllerActive", true)
        event(service)
        assertTrue(pending(service, "topRecentsStateRunnable"))
        ReflectionHelpers.setField(service, "topRecentsControllerActive", false)
    }

    @Test
    fun `a click outside native top Recents only records nothing`() = withService { service ->
        shadowOf(service).setWindowsOnDisplay(0, listOf(app("com.example.top")))
        service.onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED).apply { packageName = "com.example.top" })
        assertEquals(null, history(service)[0])
        assertFalse(pending(service, "panelStateRunnable"))
        assertFalse(pending(service, "captureRunnable"))
    }
}
