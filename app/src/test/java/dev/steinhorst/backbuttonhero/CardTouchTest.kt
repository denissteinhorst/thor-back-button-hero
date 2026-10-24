package dev.steinhorst.backbuttonhero

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CardTouchTest {

    private fun motionEvent(action: Int, x: Float, y: Float) =
        MotionEvent.obtain(0, 0, action, x, y, 0)

    private fun view(): View {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return View(context).also { FrameLayout(context).addView(it) }
    }

    @Test
    fun `a plain tap with no movement calls done(false)`() {
        var result: Boolean? = null
        val touch = CardTouch(ApplicationProvider.getApplicationContext(), 200) { result = it }
        val v = view()
        touch.onTouch(v, motionEvent(MotionEvent.ACTION_DOWN, 100f, 100f))
        touch.onTouch(v, motionEvent(MotionEvent.ACTION_UP, 100f, 100f))
        assertEquals(false, result)
    }

    @Test
    fun `a vertical swipe up past the threshold calls done(true)`() {
        var result: Boolean? = null
        val threshold = 200
        val touch = CardTouch(ApplicationProvider.getApplicationContext(), threshold) { result = it }
        val v = view()
        touch.onTouch(v, motionEvent(MotionEvent.ACTION_DOWN, 100f, 500f))
        touch.onTouch(v, motionEvent(MotionEvent.ACTION_MOVE, 100f, 500f - threshold - 50f))
        touch.onTouch(v, motionEvent(MotionEvent.ACTION_UP, 100f, 500f - threshold - 50f))
        assertEquals(true, result)
    }

    @Test
    fun `a vertical swipe that does not pass 30 percent of the threshold snaps back, calls nothing`() {
        var result: Boolean? = null
        val threshold = 200
        val touch = CardTouch(ApplicationProvider.getApplicationContext(), threshold) { result = it }
        val v = view()
        touch.onTouch(v, motionEvent(MotionEvent.ACTION_DOWN, 100f, 500f))
        touch.onTouch(v, motionEvent(MotionEvent.ACTION_MOVE, 100f, 500f - threshold - 10f))
        touch.onTouch(v, motionEvent(MotionEvent.ACTION_UP, 100f, 500f - 10f))
        assertEquals(null, result)
    }

    @Test
    fun `a predominantly horizontal drag never starts a vertical close-drag`() {
        var result: Boolean? = null
        val touch = CardTouch(ApplicationProvider.getApplicationContext(), 200) { result = it }
        val v = view()
        touch.onTouch(v, motionEvent(MotionEvent.ACTION_DOWN, 100f, 500f))
        touch.onTouch(v, motionEvent(MotionEvent.ACTION_MOVE, 300f, 480f))
        touch.onTouch(v, motionEvent(MotionEvent.ACTION_UP, 300f, 480f))
        assertEquals(null, result)
    }

    @Test
    fun `ACTION_CANCEL resets drag state without calling done`() {
        var result: Boolean? = null
        val touch = CardTouch(ApplicationProvider.getApplicationContext(), 200) { result = it }
        val v = view()
        touch.onTouch(v, motionEvent(MotionEvent.ACTION_DOWN, 100f, 500f))
        touch.onTouch(v, motionEvent(MotionEvent.ACTION_MOVE, 100f, 300f))
        touch.onTouch(v, motionEvent(MotionEvent.ACTION_CANCEL, 100f, 300f))
        assertEquals(null, result)
    }
}
