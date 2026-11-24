package dev.steinhorst.backbuttonhero

import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/**
 * Android may connect a replacement service before it unbinds the old one. The old instance's
 * teardown must leave the replacement's registrations in place; the replacement's own clears them.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceBridgeOwnerTest {
    @Test
    fun `an old instance's teardown leaves the replacement's bridges registered`() {
        val old = Robolectric.buildService(SwitcherService::class.java).create().get()
        val replacement = Robolectric.buildService(SwitcherService::class.java).create().get()
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(old, "onServiceConnected")
            ReflectionHelpers.callInstanceMethod<Unit>(replacement, "onServiceConnected")
            shadowOf(Looper.getMainLooper()).idle()
            val focus = SwitcherService.previewFocusIndicator
            val record = SwitcherService.previewRecordIndicator
            val macro = SwitcherService.macroRecorder
            val calibrate = SwitcherService.gyroCalibrate

            old.onUnbind(null)
            old.onDestroy()
            assertTrue(SwitcherService.isConnected)
            assertSame(focus, SwitcherService.previewFocusIndicator)
            assertSame(record, SwitcherService.previewRecordIndicator)
            assertSame(macro, SwitcherService.macroRecorder)
            assertSame(calibrate, SwitcherService.gyroCalibrate)
            assertNotNull(SwitcherService.gyroRecenter)

            replacement.onUnbind(null)
            assertFalse(SwitcherService.isConnected)
            assertNull(SwitcherService.previewFocusIndicator)
            assertNull(SwitcherService.previewRecordIndicator)
            assertNull(SwitcherService.macroRecorder)
            assertNull(SwitcherService.gyroCalibrate)
            assertNull(SwitcherService.gyroRecenter)
        } finally {
            replacement.onDestroy()
        }
    }
}
