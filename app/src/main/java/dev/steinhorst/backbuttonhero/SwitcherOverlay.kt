package dev.steinhorst.backbuttonhero

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Android 13 style recents on one display: preview cards, sideways scroll, tap to open, swipe up to close. */
class SwitcherOverlay(
    private val service: AccessibilityService,
    private val source: AppSource,
    private val onCloseApp: (String) -> Unit,
    private val onCloseAll: (packages: List<String>, bothScreens: Boolean) -> Unit,
    private val beforeLaunch: (displayId: Int) -> Unit,
) {
    private var root: View? = null
    private var windowManager: WindowManager? = null

    val isShowing get() = root != null

    fun toggle(display: Display) = if (root != null) dismiss() else show(display)

    fun dismiss() {
        root?.let { runCatching { windowManager?.removeView(it) } }
        root = null
    }

    private fun show(display: Display) {
        // A window context bound to the bottom display keeps the overlay off the top screen.
        val ctx = service.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val bounds = wm.currentWindowMetrics.bounds
        val apps = source.appsOnDisplay(display.displayId)

        val cardW = (bounds.width() * 0.40f).toInt()
        val thumbH = (cardW * bounds.height() / bounds.width().toFloat()).toInt()
        val gap = dp(ctx, 20)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            setPadding((bounds.width() - cardW) / 2, 0, (bounds.width() - cardW) / 2, 0)
        }
        val scroller = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // Same look as the stock Android recents actions: borderless white icon + text, centered below the cards.
        val checkedStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val bothSwitch = Switch(ctx).apply {
            scaleX = 1.1f
            scaleY = 1.1f
            thumbTintList = android.content.res.ColorStateList(checkedStates, intArrayOf(Color.WHITE, Color.rgb(200, 200, 200)))
            trackTintList = android.content.res.ColorStateList(checkedStates, intArrayOf(Color.rgb(110, 160, 255), Color.rgb(100, 100, 100)))
        }
        val bothToggle = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 64)
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
            addView(bothSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(ctx, 4); marginEnd = dp(ctx, 10) })
            addView(label(ctx, ctx.getString(R.string.both_screens), 14f, Color.WHITE))
            setOnClickListener { bothSwitch.toggle() }
        }
        val closeAll = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 64)
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
            addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_clear_all) },
                LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)).apply { marginEnd = dp(ctx, 10) })
            addView(label(ctx, ctx.getString(R.string.close_all), 14f, Color.WHITE))
            setOnClickListener { closeEverything(row, apps, bothSwitch.isChecked) }
        }
        // Toggle on the left of the action; the pair is centered as one group.
        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(bothToggle)
            addView(closeAll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(ctx, 28) })
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(buttonRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 64) })
        }
        val frame = FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setOnClickListener { dismiss() }
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        if (apps.isEmpty()) {
            frame.removeView(column)
            frame.addView(label(ctx, ctx.getString(R.string.no_recent_apps), 20f, Color.WHITE).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        apps.forEach { app ->
            val card = card(ctx, app, cardW, thumbH, display.displayId)
            row.addView(card, LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = gap / 2; marginEnd = gap / 2
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        wm.addView(frame, params)
        windowManager = wm
        root = frame
    }

    private fun card(ctx: Context, app: RunningApp, w: Int, thumbH: Int, displayId: Int): View {
        val corner = dp(ctx, 20).toFloat()
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 4), 0, 0, dp(ctx, 10))
            addView(ImageView(ctx).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(dp(ctx, 32), dp(ctx, 32)))
            addView(label(ctx, app.label, 16f, Color.WHITE).apply { setPadding(dp(ctx, 10), 0, 0, 0); maxLines = 1 })
        }
        val thumb = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(Color.rgb(40, 40, 44)); cornerRadius = corner }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
            app.thumbnail?.let { setImageBitmap(it) }
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(thumb, LinearLayout.LayoutParams(w, thumbH))
            setOnTouchListener(CardTouch(ctx, thumbH) { closing ->
                if (closing) closeCard(this, app) else { launch(app, displayId); dismiss() }
            })
        }
    }

    private fun closeEverything(row: ViewGroup, apps: List<RunningApp>, bothScreens: Boolean) {
        val all = if (bothScreens) (apps + source.appsOnDisplay(Display.DEFAULT_DISPLAY)).distinctBy { it.packageName } else apps
        for (i in 0 until row.childCount) {
            row.getChildAt(i).animate().translationY(-row.height.toFloat()).alpha(0f).setDuration(200).start()
        }
        row.postDelayed({
            all.forEach { source.forget(it.packageName) }
            onCloseAll(all.map { it.packageName }, bothScreens)
            dismiss()
        }, 220)
    }

    private fun closeCard(card: View, app: RunningApp) {
        card.animate().translationY(-card.height.toFloat() * 1.5f).alpha(0f).setDuration(180).withEndAction {
            (card.parent as? ViewGroup)?.removeView(card)
            source.forget(app.packageName)
            onCloseApp(app.packageName)
        }.start()
    }

    private fun launch(app: RunningApp, displayId: Int) {
        val intent = service.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        beforeLaunch(displayId)
        service.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
    }

    private fun label(ctx: Context, text: String, sp: Float, color: Int) =
        TextView(ctx).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
        }

    private fun dp(ctx: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
}
