package dev.steinhorst.backbuttonhero

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class RecentsCarouselTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val selected = mutableListOf<Int>()
    private val style = RecentsCardStyle.load(context)

    private fun carousel(): RecentsCarousel {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(300, 0, 300, 0)
            repeat(3) { addView(View(context), LinearLayout.LayoutParams(400, 200)) }
        }
        return RecentsCarousel(context, style, selected::add).apply {
            addView(row)
            measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1000, 200)
        }
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))

    @Test
    fun `controller selection centers each page and interrupted animation reaches latest page`() {
        val carousel = carousel()
        carousel.selectCard(1)
        carousel.selectCard(2)
        settle()
        assertEquals(800, carousel.scrollX)
        assertEquals(2, selected.last())
        carousel.selectCard(0)
        settle()
        assertEquals(0, carousel.scrollX)
    }

    @Test
    fun `finger release settles to the nearest centered card`() {
        val carousel = carousel()
        val down = MotionEvent.obtain(100, 100, MotionEvent.ACTION_DOWN, 500f, 100f, 0)
        carousel.dispatchTouchEvent(down)
        down.recycle()
        carousel.scrollTo(290, 0)
        val up = MotionEvent.obtain(100, 1100, MotionEvent.ACTION_UP, 500f, 100f, 0)
        carousel.dispatchTouchEvent(up)
        up.recycle()
        settle()
        assertEquals(400, carousel.scrollX)
        assertEquals(1, selected.last())
    }

    @Test
    fun `flings advance a page and stop at the edge instead of wrapping`() {
        val carousel = carousel()
        carousel.fling(style.minFlingVelocity + 100)
        settle()
        assertEquals(400, carousel.scrollX)
        carousel.selectCard(2)
        settle()
        carousel.fling(style.minFlingVelocity + 100)
        settle()
        assertEquals(800, carousel.scrollX)
        assertEquals(2, selected.last())
    }
}
