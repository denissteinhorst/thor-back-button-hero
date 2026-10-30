package dev.steinhorst.backbuttonhero

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
            "org.example.alpha\t4095\t1\tAlpha",
            "org.example.alpha\t0\t1\tDuplicate",
            "not a package\t1\t1\tBad",
            "org.example.beta\tx\t1\tBad mask",
            "org.example.gamma\t1",
            "org.example.delta\t65535\t0\t",
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
        val buttons: List<ComboButton> = ShoulderButton.entries + DpadDirection.entries
        assertEquals(ExceptionControl.entries - ExceptionControl.BACK.toSet(),
            buttons.map(ExceptionControl::of).sortedBy { it.ordinal })
    }
}
