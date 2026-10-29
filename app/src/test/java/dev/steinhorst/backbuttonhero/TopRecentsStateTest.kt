package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopRecentsStateTest {
    @Test
    fun `overview and real task geometry are parsed from launcher dump`() {
        val state = TopRecentsState.parse(
            """
            com.android.quickstep.views.TaskView{abc VFE...C.. 402,55-1518,831}
            mState:Overview
            """.trimIndent(),
        )

        assertTrue(state.overview)
        assertEquals(TopRecentsState.CardBounds(402, 55, 1518, 831), state.cardBounds)
        assertEquals(776, state.cardBounds?.height)
    }

    @Test
    fun `offscreen horizontal task coordinates remain valid because only vertical bounds are used`() {
        val state = TopRecentsState.parse(
            "com.android.quickstep.views.TaskView{abc VFE...C.. -751,55-365,831}\nmState:Overview",
        )

        assertEquals(TopRecentsState.CardBounds(-751, 55, 365, 831), state.cardBounds)
    }

    @Test
    fun `normal launcher state is not overview even if pooled task views remain`() {
        val state = TopRecentsState.parse("TaskView{abc 402,55-1518,831}\nmState:Normal")

        assertFalse(state.overview)
    }

    @Test
    fun `missing malformed and degenerate geometry never creates a fallback target`() {
        assertNull(TopRecentsState.parse(null).cardBounds)
        assertNull(TopRecentsState.parse("mState:Overview").cardBounds)
        assertNull(TopRecentsState.parse("TaskView{abc 10,50-20,50}\nmState:Overview").cardBounds)
    }
    @Test
    fun `focused card is distinguished from action focus`() {
        assertTrue(TopRecentsState.parse("TaskView{abc VFE...C.. .F...... 402,55-1518,831}").cardFocused)
        assertFalse(TopRecentsState.parse("TaskView{abc VFE...C.. ........ 402,55-1518,831}").cardFocused)
        assertFalse(TopRecentsState.parse("Button{abc VFED..C.. .F...... 578,0-858,111}").cardFocused)
    }

    @Test
    fun `secondary drawer focus and stopped cached Overview never count as live top Recents`() {
        val dump = """
            ACTIVITY com.android.launcher3/.secondarydisplay.SecondaryDisplayLauncher
              mResumed=true mStopped=false
              TaskView{bottom VFE...C.. .F...... 10,55-20,831}
            ACTIVITY com.android.launcher3/.uioverrides.QuickstepLauncher
              mResumed=false mStopped=true
              TaskView{top VFE...C.. ........ 402,55-1518,831}
              mState:Overview
        """.trimIndent()
        val state = TopRecentsState.parse(dump)
        assertFalse(state.overview)
        assertFalse(state.cardFocused)
        assertEquals(TopRecentsState.CardBounds(402, 55, 1518, 831), state.cardBounds)
        assertTrue(TopRecentsState.parse(dump.replace("mStopped=true", "mStopped=false")).overview)
    }

    @Test
    fun `compact pservice records do not confuse action focus with a card on the same output line`() {
        val state = TopRecentsState.parse("mState:Overview|mResumed=true mStopped=false|" +
            "TaskView{top VFE...C.. ........ 402,55-1518,831}|" +
            "Button{action VFED..C.. .F...... app:id/action_screenshot aid=42}|")
        assertTrue(state.overview)
        assertFalse(state.cardFocused)
        assertTrue(state.actionsFocused)
        assertTrue(SwitcherService.QUICKSTEP_STATE_COMMAND.length < 300)
    }

    @Test
    fun `cached focused card cannot make an unfocused animation window ready on reopening`() {
        val cache = "mState:Overview|mResumed=false mStopped=false|mHasWindowFocus=false|" +
            "TaskView{pooled VFE...C.. .F...... 402,55-1518,831}|"
        assertTrue(TopRecentsState.parse(cache).cardFocused)
        assertTrue(TopRecentsState.parse(cache).overview)
        assertFalse(TopRecentsState.parse(cache).windowFocused)
        assertFalse(TopRecentsState.parse(cache.replace("mResumed=false", "mResumed=true")).windowFocused)
        assertTrue(TopRecentsState.parse(cache.replace("mResumed=false", "mResumed=true")
            .replace("mHasWindowFocus=false", "mHasWindowFocus=true")).windowFocused)
    }

}
