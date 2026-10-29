package dev.steinhorst.backbuttonhero

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Android 13 style recents on one display: preview cards, sideways scroll, tap to open, swipe up to close. */
class SwitcherOverlay(
    private val service: AccessibilityService,
    private val source: AppSource,
    private val showAppsFromAllDisplays: () -> Boolean,
    private val onCloseApp: (String) -> Unit,
    private val onCloseAll: (packages: List<String>, bothScreens: Boolean) -> Unit,
    private val onScreenshot: (displayId: Int) -> Unit,
    private val beforeLaunch: (displayId: Int) -> Unit,
    private val onShown: (displayId: Int) -> Unit,
    private val onDismissed: () -> Unit,
) {
    private var root: View? = null
    private var recentsTheme = RecentsTheme(0, Color.WHITE, Color.WHITE, 0)
    private var memoryLabel: TextView? = null
    private var emptyLabel: TextView? = null
    private var taskStates = emptyList<RecentsTaskState>()
    private var badgeStyle: RecentsBadges? = null
    private val badgeViews = mutableMapOf<String, Pair<ImageView, ImageView>>()
    private var bulkClosing = false
    private var windowManager: WindowManager? = null
    private var shownApps = emptyList<RunningApp>()
    private var shownCards = emptyList<View>()
    private var shownDisplayId = Display.DEFAULT_DISPLAY
    private var selected = 0
    private var closeAllButton: View? = null
    private var screenshotButton: View? = null
    private var closeAllSelected = false
    private var screenshotSelected = false

    val isShowing get() = root != null

    fun toggle(display: Display) = if (root != null) dismiss() else show(display)

    fun dismiss() {
        if (root == null) return
        root?.let { runCatching { windowManager?.removeView(it) } }
        root = null
        shownApps = emptyList()
        shownCards = emptyList()
        badgeViews.clear()
        bulkClosing = false
        onDismissed()
    }

    fun refreshTheme() {
        val frame = root ?: return
        recentsTheme = RecentsTheme.load(frame.context)
        frame.setBackgroundColor(recentsTheme.background)
        memoryLabel?.setTextColor(recentsTheme.memory)
        emptyLabel?.setTextColor(recentsTheme.text)
        listOfNotNull(screenshotButton, closeAllButton).forEach { action ->
            (action as ViewGroup).let {
                val glyph = if (action === screenshotButton) R.drawable.ic_screenshot else R.drawable.ic_clear_all
                val name = if (action === screenshotButton) "ic_screenshot" else "ic_clear_all"
                (it.getChildAt(0) as ImageView).apply {
                    setImageDrawable(RecentsTheme.actionDrawable(context, name, glyph))
                    imageTintList = ColorStateList.valueOf(recentsTheme.text)
                }
                (it.getChildAt(1) as TextView).setTextColor(recentsTheme.text)
            }
        }
        badgeStyle = RecentsBadges.load(frame.context)
        updateTaskStates(taskStates)
        updateSelection(scrollToSelection = false)
    }

    fun representsTask(state: RecentsTaskState): Boolean {
        val current = taskStates.firstOrNull { it.packageName == state.packageName } ?: return false
        return current.taskId == state.taskId && current.lastActiveTime == state.lastActiveTime && current.displayId == state.displayId
    }

    fun previewPackages(): Set<String> = shownApps.map { it.packageName }.toSet()

    fun previewWidth(): Int = shownCards.firstOrNull()?.width?.takeIf { it > 0 }
        ?: shownCards.firstOrNull()?.layoutParams?.width?.coerceAtLeast(1) ?: 1

    fun updateThumbnail(packageName: String, bitmap: android.graphics.Bitmap) {
        val index = shownApps.indexOfFirst { it.packageName == packageName }
        if (index < 0 || shownApps[index].thumbnail === bitmap) return
        ((shownCards[index] as ViewGroup).getChildAt(1) as ImageView).setImageBitmap(bitmap)
        shownApps = shownApps.toMutableList().apply { set(index, get(index).copy(thumbnail = bitmap)) }
    }

    fun updateTaskStates(tasks: List<RecentsTaskState>) {
        taskStates = tasks
        memoryLabel?.let { it.text = memoryText(it.context) }
        badgeViews.forEach { (pkg, views) -> badgeStyle?.update(views, tasks.firstOrNull { it.packageName == pkg }) }
    }

    fun moveSelection(right: Boolean) {
        if (screenshotSelected || closeAllSelected) {
            screenshotSelected = !screenshotSelected
            closeAllSelected = !screenshotSelected
            updateSelection()
            (if (screenshotSelected) screenshotButton else closeAllButton)?.requestFocus()
            return
        }
        if (shownApps.isEmpty()) return
        closeAllSelected = false
        screenshotSelected = false
        selected = (selected + if (right) 1 else -1).coerceIn(0, shownApps.lastIndex)
        updateSelection()
    }

    fun moveSelectionDown() {
        if (closeAllButton == null || screenshotButton == null) return
        if (!screenshotSelected && !closeAllSelected) screenshotSelected = true
        updateSelection()
        (if (screenshotSelected) screenshotButton else closeAllButton)?.requestFocus()
    }

    fun moveSelectionUp() {
        if (screenshotSelected || closeAllSelected) {
            screenshotSelected = false
            closeAllSelected = false
            updateSelection()
            shownCards.getOrNull(selected)?.requestFocus()
        } else closeSelected()
    }

    fun activateSelection() {
        if (screenshotSelected) {
            onScreenshot(shownDisplayId)
            dismiss()
            return
        }
        if (closeAllSelected) {
            closeAllFromController()
            return
        }
        val app = shownApps.getOrNull(selected)
        if (app == null) dismiss() else { launch(app, shownDisplayId); dismiss() }
    }

    fun closeSelected() {
        if (closeAllSelected) {
            closeAllFromController()
            return
        }
        val app = shownApps.getOrNull(selected) ?: return
        shownCards.getOrNull(selected)?.let { closeCard(it, app) }
    }

    fun closeAllFromController() {
        val row = shownCards.firstOrNull()?.parent as? ViewGroup ?: return
        closeEverything(row, shownApps, bothScreens = showAppsFromAllDisplays())
    }

    private fun show(display: Display) {
        // A window context bound to the bottom display keeps the overlay off the top screen.
        val ctx = service.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val bounds = wm.currentWindowMetrics.bounds
        val showingAllApps = showAppsFromAllDisplays()
        val apps = if (showingAllApps) {
            (source.appsOnDisplay(display.displayId) + source.appsOnDisplay(Display.DEFAULT_DISPLAY))
                .distinctBy { it.packageName }
        } else {
            source.appsOnDisplay(display.displayId)
        }.reversed()
        shownApps = apps
        shownDisplayId = display.displayId
        // As in Quickstep, the newest task starts centered at the right-hand end of the row.
        selected = apps.lastIndex.coerceAtLeast(0)
        closeAllSelected = false
        screenshotSelected = false

        recentsTheme = RecentsTheme.load(ctx)
        taskStates = emptyList()
        emptyLabel = null
        val style = RecentsCardStyle.load(ctx)
        badgeStyle = RecentsBadges.load(ctx)
        badgeViews.clear()
        // Quickstep on the Thor uses a 58.125% display-sized snapshot. Reserve the icon,
        // footer and screen margins as well, so shorter displays never clip the actions.
        val availableHeight = (bounds.height() - style.iconSize - style.iconGap - dp(ctx, 132)).coerceAtLeast(1)
        val scale = minOf(0.58125f, availableHeight / bounds.height().toFloat())
        val cardW = (bounds.width() * scale).roundToInt().coerceAtLeast(1)
        val thumbH = (bounds.height() * scale).roundToInt().coerceAtLeast(1)
        val gap = style.pageGap

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            setPadding((bounds.width() - cardW - gap) / 2, 0, (bounds.width() - cardW - gap) / 2, 0)
        }
        val scroller = RecentsCarousel(ctx, style) { index ->
            selected = index
            closeAllSelected = false
            screenshotSelected = false
            updateSelection(scrollToSelection = false)
        }.apply {
            addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    removeOnLayoutChangeListener(this)
                    selectCard(selected, animate = false)
                }
            })
        }

        // Match the stock top-screen recents actions: memory above two borderless icon + text buttons.
        val screenshot = action(ctx, R.drawable.ic_screenshot, ctx.getString(R.string.switcher_screenshot)) {
            onScreenshot(display.displayId)
        }
        screenshotButton = screenshot
        val closeAll = action(ctx, R.drawable.ic_clear_all, ctx.getString(R.string.close_all)) {
            closeEverything(row, apps, bothScreens = showingAllApps)
        }
        closeAllButton = closeAll
        val buttonRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(screenshot)
            addView(closeAll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(ctx, 36) })
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(label(ctx, memoryText(ctx), 15f, recentsTheme.memory).apply { gravity = Gravity.CENTER; memoryLabel = this },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 24)).apply { topMargin = dp(ctx, 12) })
            addView(buttonRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val frame = FrameLayout(ctx).apply {
            setBackgroundColor(recentsTheme.background)
            setOnClickListener { dismiss() }
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
                .apply { topMargin = dp(ctx, 24) })
        }

        if (apps.isEmpty()) {
            frame.removeView(column)
            frame.addView(label(ctx, ctx.getString(R.string.no_recent_apps), 20f, recentsTheme.text).apply { gravity = Gravity.CENTER; emptyLabel = this },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val cards = apps.map { app ->
            val card = card(ctx, app, cardW, thumbH, display.displayId, style)
            row.addView(card, LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = gap / 2; marginEnd = gap - gap / 2
            })
            card
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
        shownCards = cards
        updateSelection()
        onShown(display.displayId)
    }

    private fun card(ctx: Context, app: RunningApp, w: Int, thumbH: Int, displayId: Int, style: RecentsCardStyle): View {
        val corner = style.cornerRadius
        val icon = ImageView(ctx).apply {
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val inset = ((style.iconSize - style.normalizedDrawableSize(app.icon)) / 2).coerceAtLeast(0)
            setPadding(inset, inset, inset, inset)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
            gravity = Gravity.CENTER_HORIZONTAL
            contentDescription = app.label
            val header = FrameLayout(ctx).apply {
                addView(icon, FrameLayout.LayoutParams(style.iconSize, style.iconSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            }
            badgeStyle?.let { badgeViews[app.packageName] = it.addTo(header) }
            addView(header, LinearLayout.LayoutParams(w, style.iconSize + style.iconGap))
            addView(thumb, LinearLayout.LayoutParams(w, thumbH))
            setOnTouchListener(CardTouch(ctx, thumbH) { closing ->
                if (closing) closeCard(this, app) else { launch(app, displayId); dismiss() }
            })
        }
    }

    private fun closeEverything(row: ViewGroup, apps: List<RunningApp>, bothScreens: Boolean) {
        if (bulkClosing) return
        val openedRoot = root ?: return
        bulkClosing = true
        val all = if (bothScreens) (apps + source.appsOnDisplay(Display.DEFAULT_DISPLAY)).distinctBy { it.packageName } else apps
        // This explicit Back Button Hero command overrides OEM locks. The badges still show
        // the shared launcher status; neither that status nor native Overview policy is changed.
        val packages = all.map { it.packageName }
        shownCards.forEach { card ->
            card.animate().translationY(-row.height.toFloat()).alpha(0f).setDuration(200).start()
        }
        row.postDelayed({
            if (root !== openedRoot) return@postDelayed
            packages.forEach(source::forget)
            onCloseAll(packages, bothScreens)
            dismiss()
        }, 220)
    }

    private fun closeCard(card: View, app: RunningApp) {
        card.animate().translationY(-card.height.toFloat() * 1.5f).alpha(0f).setDuration(180).withEndAction {
            val row = card.parent as? ViewGroup
            val lastCard = row?.childCount == 1
            row?.removeView(card)
            source.forget(app.packageName)
            onCloseApp(app.packageName)
            if (lastCard) {
                dismiss()
            } else {
                shownApps = shownApps.filterNot { it.packageName == app.packageName }
                shownCards = shownCards.filterNot { it == card }
                selected = selected.coerceAtMost(shownApps.lastIndex)
                updateSelection()
            }
        }.start()
    }

    private fun updateSelection(scrollToSelection: Boolean = true) {
        closeAllButton?.setBackgroundColor(if (closeAllSelected) recentsTheme.selection else Color.TRANSPARENT)
        screenshotButton?.setBackgroundColor(if (screenshotSelected) recentsTheme.selection else Color.TRANSPARENT)
        if (!scrollToSelection || screenshotSelected || closeAllSelected) return
        shownCards.getOrNull(selected)?.let { card ->
            val row = card.parent as? ViewGroup ?: return@let
            val scroller = row.parent as? RecentsCarousel ?: return@let
            scroller.post {
                if (card.parent == row && root != null) {
                    scroller.selectCard(selected)
                }
            }
        }
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

    private fun action(ctx: Context, icon: Int, text: String, onClick: () -> Unit) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 48)
            setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
            addView(ImageView(ctx).apply {
                setImageDrawable(RecentsTheme.actionDrawable(ctx, if (icon == R.drawable.ic_screenshot) "ic_screenshot" else "ic_clear_all", icon))
                imageTintList = ColorStateList.valueOf(recentsTheme.text)
            },
                LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)).apply { marginEnd = dp(ctx, 8) })
            addView(label(ctx, text, 14f, recentsTheme.text).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
            setOnClickListener { onClick() }
        }

    private fun memoryText(ctx: Context): String {
        val memory = RecentsMemory.read(ctx)
        return ctx.getString(
            R.string.recents_memory,
            Formatter.formatShortFileSize(ctx, memory.available),
            Formatter.formatShortFileSize(ctx, memory.total),
        )
    }

    private fun dp(ctx: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()
}
