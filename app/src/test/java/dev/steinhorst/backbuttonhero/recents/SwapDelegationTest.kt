package dev.steinhorst.backbuttonhero.recents

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwapDelegationTest {
    @Test
    fun `only IISU or Cocoon on both screens hands the swap to that frontend`() {
        assertEquals(SwapDelegation.IISU, SwapDelegation.delegate(SwapDelegation.IISU, SwapDelegation.IISU))
        assertEquals(SwapDelegation.COCOON, SwapDelegation.delegate(SwapDelegation.COCOON, SwapDelegation.COCOON))
        assertNull(SwapDelegation.delegate(SwapDelegation.IISU, "dev.example.game"))
        assertNull(SwapDelegation.delegate("dev.example.game", SwapDelegation.COCOON))
        assertNull(SwapDelegation.delegate(SwapDelegation.IISU, SwapDelegation.COCOON))
        assertNull(SwapDelegation.delegate(SwapDelegation.COCOON, null))
        assertNull(SwapDelegation.delegate(null, null))
        assertNull(SwapDelegation.delegate("dev.example.game", "dev.example.game"))
    }

    @Test
    fun `Cocoon swaps on its fixed X button`() {
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, SwapDelegation.COCOON_KEY)
    }

    @Test
    fun `IISU's stored swap button maps to its key and a missing value is IISU's default Y`() {
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, SwapDelegation.iisuKeyCode(null))
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, SwapDelegation.iisuKeyCode(""))
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, SwapDelegation.iisuKeyCode("Y\n"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, SwapDelegation.iisuKeyCode("X"))
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, SwapDelegation.iisuKeyCode("B"))
        assertNull(SwapDelegation.iisuKeyCode("Off"))
        assertNull(SwapDelegation.iisuKeyCode("A"))
    }

    @Test
    fun `IISU's settings are read beside this app's data, for the same user`() {
        assertEquals("tr -cs 'A-Za-z_' '\\n' < '/data/user/10/com.iisulauncher/files/datastore/launcher_settings.preferences_pb'" +
            " | grep -x -A1 screen_swap_shortcut_button | tail -1",
            SwapDelegation.iisuButtonCommand("/data/user/10/dev.steinhorst.backbuttonhero"))
    }
}
