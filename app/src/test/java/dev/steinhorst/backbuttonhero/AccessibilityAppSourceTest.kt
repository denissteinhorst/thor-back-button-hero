package dev.steinhorst.backbuttonhero

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccessibilityAppSourceTest {

    private lateinit var source: AccessibilityAppSource

    @Before
    fun setUp() {
        // buildService(...).create() runs onCreate() but NOT onServiceConnected(), which would
        // try to build a SwitcherOverlay - not viable under Robolectric. See TEST-GAPS.md #3.
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        source = AccessibilityAppSource(service)
    }

    private fun bitmap() = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    @Test
    fun `saving a second thumbnail for the same package recycles the first`() {
        val first = bitmap()
        val second = bitmap()
        source.saveThumbnail("com.example.app", first)
        source.saveThumbnail("com.example.app", second)
        assertTrue("the replaced bitmap should have been recycled", first.isRecycled)
        assertFalse("the current bitmap must stay usable", second.isRecycled)
    }

    @Test
    fun `forget recycles the cached thumbnail for that package`() {
        val held = bitmap()
        source.saveThumbnail("com.example.app", held)
        source.forget("com.example.app")
        assertTrue(held.isRecycled)
    }

    @Test
    fun `forget leaves other packages' thumbnails untouched`() {
        val kept = bitmap()
        val dropped = bitmap()
        source.saveThumbnail("com.example.kept", kept)
        source.saveThumbnail("com.example.dropped", dropped)
        source.forget("com.example.dropped")
        assertTrue(dropped.isRecycled)
        assertFalse(kept.isRecycled)
    }

    @Test
    fun `forget on an unknown package does not throw`() {
        source.forget("com.never.seen")
    }

    // windowsOnAllDisplays returns an empty, non-null list under Robolectric for this app
    // (confirmed via a throwaway probe test - see step 07's task 3.2), so these two are safe.

    @Test
    fun `topApp returns null when the display has no windows`() {
        assertNull(source.topApp(0))
    }

    @Test
    fun `appsOnDisplay returns an empty list when nothing was ever recorded`() {
        assertEquals(emptyList<RunningApp>(), source.appsOnDisplay(0))
    }

    @Suppress("UNCHECKED_CAST")
    private fun history(): MutableMap<Int, MutableList<String>> = AccessibilityAppSource::class.java
        .getDeclaredField("history").apply { isAccessible = true }.get(source) as MutableMap<Int, MutableList<String>>

    @Test
    fun `system snapshot removes closed apps from both histories and recycles only their thumbnails`() {
        history()[0] = mutableListOf("dev.example.closed", "dev.example.alive")
        history()[17] = mutableListOf("dev.example.closed", "dev.example.alive")
        val closed = bitmap()
        val alive = bitmap()
        source.saveThumbnail("dev.example.closed", closed)
        source.saveThumbnail("dev.example.alive", alive)
        source.retainRecentTasks(listOf(RecentsTaskState("dev.example.alive", 17, true, 123)))
        assertEquals(listOf("dev.example.alive"), history()[0])
        assertEquals(listOf("dev.example.alive"), history()[17])
        assertTrue(closed.isRecycled)
        assertFalse(alive.isRecycled)
    }

    @Test
    fun `successful empty snapshot clears history without adding system only apps`() {
        history()[17] = mutableListOf("dev.example.closed")
        source.retainRecentTasks(listOf(RecentsTaskState("dev.example.unobserved", 0, false, 321)))
        assertEquals(emptyList<String>(), history()[17])
        assertEquals(setOf(17), history().keys)
        history()[17] = mutableListOf("dev.example.alive")
        source.retainRecentTasks(emptyList())
        assertEquals(emptyList<String>(), history()[17])
    }

    @Test
    fun `multiple tasks for one app keep its observed history regardless of lock or display`() {
        history()[31] = mutableListOf("dev.example.alive")
        source.retainRecentTasks(listOf(RecentsTaskState("dev.example.alive", 0, false, 123),
            RecentsTaskState("dev.example.alive", 17, true, 456)))
        assertEquals(listOf("dev.example.alive"), history()[31])
    }
}
