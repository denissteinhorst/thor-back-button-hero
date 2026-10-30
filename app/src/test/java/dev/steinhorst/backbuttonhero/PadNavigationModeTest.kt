package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Test

class PadNavigationModeTest {
    @Test
    fun `wire values match the native helper protocol`() {
        assertEquals(0, PadNavigationMode.OFF.wireValue)
        assertEquals(1, PadNavigationMode.CAPTURE.wireValue)
        assertEquals(2, PadNavigationMode.TOP.wireValue)
        assertEquals(3, PadNavigationMode.PASSTHROUGH.wireValue)
    }
}
