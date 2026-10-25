package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellTest {

    @Test fun acceptsRealPackageNames() {
        assertTrue(Shell.isPkg("com.android.chrome"))
        assertTrue(Shell.isPkg("dev.steinhorst.backbuttonhero"))
        assertTrue(Shell.isPkg("org.ppsspp.ppsspp_gold"))
    }

    @Test fun rejectsAnythingAShellCouldInterpret() {
        assertFalse(Shell.isPkg(null))
        assertFalse(Shell.isPkg(""))
        assertFalse(Shell.isPkg("nodots"))
        assertFalse(Shell.isPkg("com.a'; reboot; '"))
        assertFalse(Shell.isPkg("com.a b"))
        assertFalse(Shell.isPkg("com.a\$(id)"))
        assertFalse(Shell.isPkg("com.a,com.b"))
        assertFalse(Shell.isPkg("1com.example"))
    }

    @Test fun rejectsOverlongNames() {
        assertFalse(Shell.isPkg("a." + "b".repeat(254)))
    }

    @Test fun quotesEmbeddedSingleQuotes() {
        assertEquals("'plain'", Shell.q("plain"))
        assertEquals("'it'\\''s'", Shell.q("it's"))
    }
}
