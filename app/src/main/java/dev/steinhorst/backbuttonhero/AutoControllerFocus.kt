package dev.steinhorst.backbuttonhero

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.util.UUID

/** A consumed touch selects Auto-lock's display without changing AYN's routing setting. */
class AutoControllerFocus(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var window: Pair<WindowManager, View>? = null
    @Volatile private var generation = 0

    fun run(displayId: Int, then: (Boolean) -> Unit) {
        dismiss()
        val session = generation
        val title = "BBHAutoFocus-${UUID.randomUUID()}"
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
            ?: return then(false)
        val ctx = context.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val view = object : View(ctx) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
            override fun performClick(): Boolean = super.performClick()
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val params = WindowManager.LayoutParams(48, 48,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER
            this.title = title
        }
        if (runCatching { wm.addView(view, params) }.isFailure) return then(false)
        window = wm to view
        view.requestFocus()
        // Wait for the input window to be registered. Target-UID injection fails closed if another
        // window covers this shield, so the synthetic touch can never reach a game or system control.
        handler.postDelayed({
            if (session != generation) return@postDelayed
            val xy = IntArray(2).also(view::getLocationOnScreen)
            Thread {
                if (session != generation) return@Thread
                val result = PService.runEntryPoint(context, "ControllerFocusTouch", displayId.toString(),
                    (xy[0] + 24).toString(), (xy[1] + 24).toString(), Process.myUid().toString(), title)
                Log.d("BackButtonHero", "Auto-lock focus display=$displayId: $result")
                handler.post {
                    if (session == generation) {
                        dismiss()
                        then(result?.startsWith("OK") == true)
                    }
                }
            }.apply { isDaemon = true }.start()
        }, 100)
        handler.postDelayed({
            if (session == generation) {
                dismiss()
                then(false)
            }
        }, 2000)
    }

    fun dismiss() {
        generation++
        handler.removeCallbacksAndMessages(null)
        window?.let { (wm, view) -> runCatching { wm.removeViewImmediate(view) } }
        window = null
    }

    companion object {
        fun focusedDisplay(output: String?): Int? = output?.let {
            Regex("(?m)^\\s*FocusedDisplayId: (\\d+)\\s*$").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}
