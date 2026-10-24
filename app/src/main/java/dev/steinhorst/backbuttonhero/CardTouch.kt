package dev.steinhorst.backbuttonhero

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Tap -> callback(false); vertical swipe up past a threshold -> callback(true). Horizontal drags go to the scroller. */
internal class CardTouch(ctx: Context, private val threshold: Int, private val done: (closing: Boolean) -> Unit) : View.OnTouchListener {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - downY
                if (!dragging && dy < -slop && abs(dy) > abs(e.rawX - downX)) {
                    dragging = true
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    v.translationY = dy.coerceAtMost(0f)
                    v.alpha = (1f + dy / (threshold * 1.5f)).coerceIn(0.2f, 1f)
                }
            }
            MotionEvent.ACTION_UP -> {
                val dy = e.rawY - downY
                if (dragging) {
                    if (dy < -threshold * 0.3f) done(true)
                    else v.animate().translationY(0f).alpha(1f).setDuration(150).start()
                } else if (abs(dy) < slop && abs(e.rawX - downX) < slop) { v.performClick(); done(false) }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                v.animate().translationY(0f).alpha(1f).setDuration(150).start()
                dragging = false
            }
        }
        return true
    }
}
