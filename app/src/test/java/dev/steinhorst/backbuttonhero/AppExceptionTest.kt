package dev.steinhorst.backbuttonhero

import dev.steinhorst.backbuttonhero.prefs.AppException
import dev.steinhorst.backbuttonhero.prefs.ExceptionControl
import dev.steinhorst.backbuttonhero.prefs.ExceptionGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppExceptionTest {
    private fun allowing(vararg controls: ExceptionControl) =
        AppException("org.example.game", "Game", controls.sumOf { it.bit })

    @Test
    fun `encoding round-trips and strips separators from labels`() {
        val exceptions = listOf(
            AppException("org.example.alpha", "Alpha\tEmu\nlator", 5, active = false),
            AppException("org.example.beta", "Beta"),
        )
        val decoded = AppException.decode(AppException.encode(exceptions))
        assertEquals(listOf(exceptions[0].copy(name = "Alpha Emu lator"), exceptions[1]), decoded)
    }

    @Test
    fun `decoding skips malformed lines, unsafe packages and duplicates`() {
        val stored = listOf(
            "org.example.alpha\t8589934591\t1\tAlpha",
            "org.example.alpha\t0\t1\tDuplicate",
            "not a package\t1\t1\tBad",
            "org.example.beta\tx\t1\tBad mask",
            "org.example.gamma\t1",
            "org.example.delta\t17179869183\t0\t",
        ).joinToString("\n")
        assertEquals(listOf(
            AppException("org.example.alpha", "Alpha"),
            AppException("org.example.delta", "org.example.delta", ExceptionControl.ALL, active = false),
        ), AppException.decode(stored))
        assertEquals(emptyList<AppException>(), AppException.decode(null))
    }

    @Test
    fun `combos need at least one Back gesture while an inactive exception allows everything`() {
        val native = allowing(ExceptionControl.L1, ExceptionControl.UP)
        assertTrue(native.backNative)
        assertFalse(native.allows(ExceptionControl.L1))
        assertFalse(native.allows(ExceptionControl.SINGLE))
        val single = allowing(ExceptionControl.SINGLE, ExceptionControl.L1)
        assertFalse(single.backNative)
        assertTrue(single.allows(ExceptionControl.L1))
        assertFalse(single.allows(ExceptionControl.R1))
        assertTrue(native.copy(active = false).allows(ExceptionControl.R1))
    }

    @Test
    fun `every combo button maps to its own column`() {
        val buttons: List<ComboButton> = ShoulderButton.entries + DpadDirection.entries + StickButton.entries + StickClickButton.entries +
            AndroidButton.entries + FaceButton.entries
        assertEquals(ExceptionControl.entries - ExceptionControl.BACK.toSet(),
            buttons.map(ExceptionControl::of).distinct().sortedBy { it.ordinal })
        // Every stick direction and click gesture has a column of its own.
        assertEquals(14, (StickButton.entries + StickClickButton.entries).map(ExceptionControl::of).toSet().size)
        assertEquals(ExceptionControl.LEFT_STICK_UP, ExceptionControl.of(StickButton.LEFT_UP))
        assertEquals(ExceptionControl.RIGHT_STICK_LEFT, ExceptionControl.of(StickButton.RIGHT_LEFT))
        assertEquals(ExceptionControl.LEFT_STICK_DOUBLE, ExceptionControl.of(StickClickButton.LEFT_DOUBLE))
        assertEquals(ExceptionControl.RIGHT_STICK_TAP_HOLD, ExceptionControl.of(StickClickButton.RIGHT_TAP_HOLD))
    }

    @Test
    fun `each stick control follows its own column and a native-Back app releases them all`() {
        val oneOff = AppException("com.a", "A", ExceptionControl.ALL and ExceptionControl.LEFT_STICK_UP.bit.inv())
        assertEquals(false, oneOff.allows(ExceptionControl.LEFT_STICK_UP))
        assertEquals(true, oneOff.allows(ExceptionControl.LEFT_STICK_DOWN))
        assertEquals(true, oneOff.allows(ExceptionControl.RIGHT_STICK_UP))
        val nativeBack = AppException("com.a", "A", ExceptionControl.ALL and ExceptionControl.BACK.sumOf { it.bit }.inv())
        assertEquals(false, nativeBack.allows(ExceptionControl.RIGHT_STICK_TAP_HOLD))
    }

    @Test
    fun `the button columns sit above every older bit, and a native-Back app releases them too`() {
        val buttons = listOf(ExceptionControl.HOME, ExceptionControl.START, ExceptionControl.SELECT,
            ExceptionControl.A, ExceptionControl.B, ExceptionControl.X, ExceptionControl.Y)
        assertEquals(ExceptionControl.BUTTON_BITS shl ExceptionControl.BUTTON_SHIFT, buttons.sumOf { it.bit })
        assertEquals(ExceptionControl.ALL, ExceptionControl.entries.sumOf { it.bit })
        val noA = AppException("com.a", "A", ExceptionControl.ALL and ExceptionControl.A.bit.inv())
        assertFalse(noA.allows(ExceptionControl.A))
        assertTrue(noA.allows(ExceptionControl.HOME))
        val nativeBack = AppException("com.a", "A", ExceptionControl.ALL and ExceptionControl.BACK.sumOf { it.bit }.inv())
        assertFalse(nativeBack.allows(ExceptionControl.HOME))
        assertFalse(nativeBack.allows(ExceptionControl.Y))
    }

    @Test
    fun `the Android group keeps its Back gestures editable while Back is native`() {
        val nativeBack = AppException("com.a", "A", ExceptionControl.ALL and ExceptionControl.BACK.sumOf { it.bit }.inv())
        assertTrue(ExceptionGroup.ANDROID.editable(nativeBack))
        assertTrue(ExceptionGroup.ANDROID.editable(nativeBack, ExceptionControl.SINGLE))
        assertFalse(ExceptionGroup.ANDROID.editable(nativeBack, ExceptionControl.HOME))
        assertFalse(ExceptionGroup.FACE.editable(nativeBack))
        assertTrue(ExceptionGroup.FACE.editable(AppException("com.a", "A")))
        assertFalse(ExceptionGroup.FACE.editable(AppException("com.a", "A", active = false)))
    }
}
