package dev.steinhorst.backbuttonhero

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** Shared finger/controller paging, with Quickstep's quintic settling curve and velocity timing. */
class RecentsCarousel @JvmOverloads constructor(
    context: Context,
    private val style: RecentsCardStyle = RecentsCardStyle.load(context),
    private val onPageSelected: (Int) -> Unit = {},
) : HorizontalScrollView(context) {
    private var animation: ValueAnimator? = null
    private var downScroll = 0
    private var downPage = 0
    private var flung = false
    private var targetPage = -1
    private val row get() = getChildAt(0) as? ViewGroup

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipChildren = false
        clipToPadding = false
    }

    fun selectCard(index: Int, animate: Boolean = true) {
        val card = row?.getChildAt(index) ?: return
        val target = (card.left + card.right) / 2 - width / 2
        settle(index, target, if (animate) style.snapDuration.toLong() else 0L)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            animation?.cancel()
            animation = null
            targetPage = -1
            downScroll = scrollX
            downPage = nearestPage()
            flung = false
        }
        val handled = super.dispatchTouchEvent(event)
        if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) &&
            !flung && scrollX != downScroll) selectCard(nearestPage())
        return handled
    }

    override fun fling(velocityX: Int) {
        flung = true
        val count = row?.childCount ?: return
        if (count == 0) return
        val nearest = nearestPage()
        val index = if (abs(velocityX) >= style.minFlingVelocity) {
            if (velocityX > 0) maxOf(nearest, downPage + 1) else minOf(nearest, downPage - 1)
        } else nearest
        val bounded = index.coerceIn(0, count - 1)
        val card = row?.getChildAt(bounded) ?: return
        val target = (card.left + card.right) / 2 - width / 2
        val duration = if (abs(velocityX) < style.minFlingVelocity) style.snapDuration else {
            val half = width / 2f
            val ratio = minOf(1f, abs(target - scrollX) / width.coerceAtLeast(1).toFloat())
            val distance = half + half * sin((ratio - 0.5f) * 0.47123894f)
            4 * (1000f * abs(distance / maxOf(style.minSnapVelocity, abs(velocityX)))).roundToInt()
        }
        settle(bounded, target, duration.toLong())
    }

    private fun nearestPage(): Int {
        val children = row ?: return 0
        return (0 until children.childCount).minByOrNull {
            val card = children.getChildAt(it)
            abs((card.left + card.right) / 2 - width / 2 - scrollX)
        } ?: 0
    }

    private fun settle(index: Int, target: Int, duration: Long) {
        if (targetPage == index && animation?.isRunning == true) return
        animation?.cancel()
        targetPage = index
        onPageSelected(index)
        if (target == scrollX) return
        if (duration == 0L) {
            scrollTo(target, 0)
            return
        }
        animation = ValueAnimator.ofInt(scrollX, target).apply {
            this.duration = duration
            setInterpolator { t -> val shifted = t - 1f; shifted * shifted * shifted * shifted * shifted + 1f }
            addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animation?.cancel()
        animation = null
        super.onDetachedFromWindow()
    }
}
