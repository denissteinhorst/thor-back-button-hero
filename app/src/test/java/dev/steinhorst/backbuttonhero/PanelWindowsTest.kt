package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelWindowsTest {
    private fun window(type: String = "PRESENTATION", pkg: String = "dev.example.frontend", uid: Int = 10150,
        display: Int = 17, frame: String = "[0,0][1240,1080]", visible: Boolean = true) = """
WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #0 Window{b1543e u0 $pkg}:
    mDisplayId=$display rootTaskId=7 mSession=Session{a 26031:u0a10150} mClient=android.os.BinderProxy@f90ccf9
    mOwnerUid=$uid showForAllUsers=false package=$pkg appop=NONE
    mAttrs={(0,0)(fillxfill) ty=$type fmt=TRANSLUCENT
      fl=NOT_FOCUSABLE}
    Frames: parent=[0,0][1240,1080] display=[0,0][1240,1080] frame=$frame last=$frame
    isVisible=$visible
""".trimIndent()

    @Test
    fun `recognizes full screen presentation private presentation and app overlay on dynamic bottom display`() {
        for (type in listOf("PRESENTATION", "PRIVATE_PRESENTATION", "APPLICATION_OVERLAY")) {
            val panel = PanelWindows.windows(window(type)).single()
            assertTrue(panel.isPanel(17, 1240, 1080))
            assertEquals("dev.example.frontend", panel.packageName)
            assertEquals("b1543e", panel.handle)
            assertEquals("f90ccf9", panel.client)
            assertTrue(panel.visible)
            assertFalse(panel.isPanel(31, 1240, 1080))
            assertFalse(panel.isPanel(0, 1240, 1080))
        }
    }

    @Test
    fun `system and input windows normal activities and small or offscreen overlays are never panels`() {
        for (type in listOf("BASE_APPLICATION", "ACCESSIBILITY_OVERLAY", "INPUT_METHOD", "MAGNIFICATION_OVERLAY", "POINTER")) {
            assertFalse(PanelWindows.windows(window(type)).single().isPanel(17, 1240, 1080))
        }
        assertFalse(PanelWindows.windows(window(uid = 1000)).single().isPanel(17, 1240, 1080))
        assertFalse(PanelWindows.windows(window(uid = 101000)).single().isPanel(17, 1240, 1080))
        assertFalse(PanelWindows.windows(window(frame = "[0,0][100,100]")).single().isPanel(17, 1240, 1080))
        assertFalse(PanelWindows.windows(window(frame = "[2000,0][3240,1080]")).single().isPanel(17, 1240, 1080))
        assertFalse(PanelWindows.windows(window()).single().isPanel(17, 0, 1080))
        assertTrue(PanelWindows.windows(window(uid = 110150)).single().isPanel(17, 1240, 1080))
    }

    @Test
    fun `hidden window still has identity for restoration but a malformed block cannot claim an owner`() {
        val hidden = PanelWindows.windows(window(visible = false)).single()
        assertFalse(hidden.visible)
        assertTrue(hidden.isPanel(17, 1240, 1080))
        assertTrue(PanelWindows.windows(window().replace("mOwnerUid=10150", "missingOwner")).isEmpty())
    }

    @Test
    fun `accessibility ids are joined by client token rather than title package task or order`() {
        val dump = """
ACCESSIBILITY MANAGER (dumpsys accessibility)
A11yWindow[AccessibilityWindowInfo[title=null, displayId=17, id=552, taskId=-1, type=<UNKNOWN:-1>]]
WindowInfo[WindowInfo[title=null, displayId=17, type=2037, token=android.os.BinderProxy@f90ccf9]]
A11yWindow[AccessibilityWindowInfo[title=Frontend, displayId=0, id=553, taskId=34]]
WindowInfo[WindowInfo[title=Frontend, displayId=0, token=android.os.BinderProxy@abc]]
WindowInfo[WindowInfo[title=Unpaired, token=android.os.BinderProxy@def]]
""".trimIndent()
        assertEquals(mapOf("f90ccf9" to 552, "abc" to 553), PanelWindows.accessibilityIds(dump))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `failed window dump is not a successful empty snapshot`() {
        PanelWindows.windows("Permission denied")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `failed accessibility dump is not a successful empty snapshot`() {
        PanelWindows.accessibilityIds("")
    }
}
