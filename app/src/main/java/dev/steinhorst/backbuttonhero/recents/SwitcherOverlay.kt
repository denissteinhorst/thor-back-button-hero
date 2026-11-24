package dev.steinhorst.backbuttonhero.recents

import android.accessibilityservice.AccessibilityService
import android.content.Context
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
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.settings.dp
import kotlin.math.roundToInt

/** Android 13 style recents on one display: preview cards, sideways scroll, tap to open, swipe up to close. */
class SwitcherOverlay(
    private val service: AccessibilityService,
    private val source: AppSource,
    private val showAppsFromAllDisplays: () -> Boolean,
    private val onCloseApp: (String) -> Unit,
    private val onCloseAll: (packages: List<String>, bothScreens: Boolean) -> Unit,
    private val onScreenshot: (displayId: Int) -> Unit,
    private val onLaunch: (packageName: String, displayId: Int) -> Unit,
    private val onShown: (displayId: Int) -> Unit,
    private val onDismissed: () -> Unit,
    private val onPanelLaunch: (Int) -> Unit = {},
    private val onCoveringClose: () -> Unit = {},
) {
    private var root: View? = null
    private var recentsTheme = RecentsTheme(0, Color.WHITE, Color.WHITE, 0)
    private var memoryLabel: TextView? = null
    private var emptyView: RecentsEmptyView? = null
    private var taskStates = emptyList<RecentsTaskState>()
    private var badgeStyle: RecentsBadges? = null
    private val badgeViews = mutableMapOf<String, Pair<ImageView, ImageView>>()
    private var bulkClosing = false
    private var windowManager: WindowManager? = null
    /** The displayed cards in row order; each app travels with the views built for it. */
    private var shown = emptyList<ShownCard>()
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
        shown = emptyList()
        badgeViews.clear()
        // Any one of these would keep the whole detached tree alive through its parents.
        memoryLabel = null
        emptyView = null
        closeAllButton = null
        screenshotButton = null
        bulkClosing = false
        onDismissed()
    }

    fun refreshTheme() {
        val frame = root ?: return
        recentsTheme = RecentsTheme.load(frame.context)
        frame.setBackgroundColor(recentsTheme.background)
        memoryLabel?.setTextColor(recentsTheme.memory)
        emptyView?.refreshTheme()
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
        if (shown.none { it.app.panel == null && it.app.packageName == state.packageName }) return false
        val current = taskFor(state.packageName) ?: return false
        return current.taskId == state.taskId && current.lastActiveTime == state.lastActiveTime && current.displayId == state.displayId
    }

    /** A frontend can own a task on each screen; its card stands for the one on this switcher's screen. */
    fun taskFor(packageName: String, tasks: List<RecentsTaskState> = taskStates): RecentsTaskState? {
        val own = tasks.filter { it.packageName == packageName }
        return own.firstOrNull { it.displayId == shownDisplayId } ?: own.firstOrNull()
    }

    fun previewPackages(): Set<String> = shown.filter { it.app.panel == null }.map { it.app.packageName }.toSet()

    fun previewWidth(): Int = shown.firstOrNull()?.card?.width?.takeIf { it > 0 }
        ?: shown.firstOrNull()?.card?.layoutParams?.width?.coerceAtLeast(1) ?: 1

    fun updateThumbnail(packageName: String, bitmap: android.graphics.Bitmap) {
        val index = shown.indexOfFirst { it.app.panel == null && it.app.packageName == packageName }
        if (index < 0 || shown[index].app.thumbnail === bitmap) return
        shown[index].thumb?.setImageBitmap(bitmap)
        shown = shown.toMutableList().apply { set(index, get(index).let { it.copy(app = it.app.copy(thumbnail = bitmap)) }) }
    }

    fun updateTaskStates(tasks: List<RecentsTaskState>) {
        taskStates = tasks
        memoryLabel?.let { it.text = memoryText(it.context) }
        badgeViews.forEach { (key, views) ->
            val panel = shown.firstOrNull { it.app.key == key }?.app?.panel
            badgeStyle?.update(views, if (panel == null) taskFor(key, tasks)
                else RecentsTaskState(panel.packageName, panel.displayId, false))
            if (panel != null) views.first.visibility = View.INVISIBLE
        }
    }

    /** Remove expired cards before the source recycles their accessibility thumbnail bitmaps. */
    fun reconcileTaskStates(tasks: List<RecentsTaskState>): Boolean {
        if (bulkClosing) return false
        val packages = tasks.map { it.packageName }.toSet()
        val selectedKey = shown.getOrNull(selected)?.app?.key
        if (removeCards { it.app.panel == null && it.app.packageName !in packages }) {
            keepSelection(selectedKey)
            if (shown.isEmpty()) dismiss() else updateSelection()
        }
        updateTaskStates(tasks)
        return true
    }

    /** Panel lifetime is independent of task metadata; disappearing windows expire only their cards. */
    fun reconcilePanels(panels: List<RunningApp>) {
        if (!isShowing || bulkClosing) return
        val current = panels.associateBy { it.key }
        val selectedKey = shown.getOrNull(selected)?.app?.key
        val expired = removeCards { it.app.panel != null && it.app.key !in current }
        shown = shown.map { entry -> current[entry.app.key]?.let { entry.copy(app = it) } ?: entry }
        shown.forEach { entry -> if (entry.app.panel != null) entry.thumb?.setImageBitmap(entry.app.thumbnail) }
        keepSelection(selectedKey)
        // Only an expired last card closes the switcher; the empty state itself must stay open.
        if (shown.isEmpty() && expired) dismiss() else updateSelection(scrollToSelection = false)
    }

    /** Detaches the matching cards (stopping their animations, dropping their badges); true if any went. */
    private fun removeCards(expired: (ShownCard) -> Boolean): Boolean {
        val (gone, kept) = shown.partition(expired)
        gone.forEach { entry ->
            entry.card.animate().cancel()
            (entry.card.parent as? ViewGroup)?.removeView(entry.card)
            badgeViews.remove(entry.app.key)
        }
        shown = kept
        return gone.isNotEmpty()
    }

    /** The previously selected card keeps the selection; if it went, the same position does. */
    private fun keepSelection(selectedKey: String?) {
        selected = shown.indexOfFirst { it.app.key == selectedKey }.takeIf { it >= 0 }
            ?: selected.coerceAtMost(shown.lastIndex).coerceAtLeast(0)
    }

    fun moveSelection(right: Boolean) {
        if (screenshotSelected || closeAllSelected) {
            screenshotSelected = !screenshotSelected
            closeAllSelected = !screenshotSelected
            updateSelection()
            (if (screenshotSelected) screenshotButton else closeAllButton)?.requestFocus()
            return
        }
        if (shown.isEmpty()) return
        closeAllSelected = false
        screenshotSelected = false
        selected = (selected + if (right) 1 else -1).coerceIn(0, shown.lastIndex)
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
            shown.getOrNull(selected)?.card?.requestFocus()
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
        val app = shown.getOrNull(selected)?.app
        if (app == null) dismiss() else { launch(app, shownDisplayId); dismiss() }
    }

    fun closeSelected() {
        if (closeAllSelected) {
            closeAllFromController()
            return
        }
        val entry = shown.getOrNull(selected) ?: return
        closeCard(entry.card, entry.app)
    }

    fun closeAllFromController() {
        val row = shown.firstOrNull()?.card?.parent as? ViewGroup ?: return
        closeEverything(row, shown.map { it.app }, bothScreens = showAppsFromAllDisplays())
    }

    private fun show(display: Display) {
        // A window context bound to the bottom display keeps the overlay off the top screen.
        val ctx = service.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val bounds = wm.currentWindowMetrics.bounds
        val showingAllApps = showAppsFromAllDisplays()
        val regularApps = if (showingAllApps) {
            (source.appsOnDisplay(display.displayId) + source.appsOnDisplay(Display.DEFAULT_DISPLAY))
                .distinctBy { it.packageName }
        } else {
            source.appsOnDisplay(display.displayId)
        }
        val apps = (source.panelsOnDisplay(display.displayId) + regularApps).distinctBy { it.key }.reversed()
        shown = emptyList()
        shownDisplayId = display.displayId
        // As in Quickstep, the newest task starts centered at the right-hand end of the row.
        selected = apps.lastIndex.coerceAtLeast(0)
        closeAllSelected = false
        screenshotSelected = false

        recentsTheme = RecentsTheme.load(ctx)
        taskStates = emptyList()
        emptyView = null
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
            closeEverything(row, shown.map { it.app }, bothScreens = showingAllApps)
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
            // Quickstep retains the RAM row, hides unavailable actions and centers its empty
            // illustration within the task-card area, rather than the entire screen.
            val cardHeight = style.iconSize + style.iconGap + thumbH
            scroller.layoutParams.height = cardHeight
            scroller.visibility = View.INVISIBLE
            buttonRow.visibility = View.GONE
            screenshotButton = null
            closeAllButton = null
            emptyView = RecentsEmptyView(ctx).also {
                frame.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardHeight, Gravity.TOP)
                    .apply { topMargin = dp(ctx, 24) })
            }
        }
        val cards = apps.map { app ->
            val entry = card(ctx, app, cardW, thumbH, display.displayId, style)
            row.addView(entry.card, LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = gap / 2; marginEnd = gap - gap / 2
            })
            entry
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
        shown = cards
        updateSelection()
        onShown(display.displayId)
    }

    private fun card(ctx: Context, app: RunningApp, w: Int, thumbH: Int, displayId: Int, style: RecentsCardStyle): ShownCard {
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
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            contentDescription = app.label
            val header = FrameLayout(ctx).apply {
                addView(icon, FrameLayout.LayoutParams(style.iconSize, style.iconSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            }
            badgeStyle?.let { badgeViews[app.key] = it.addTo(header) }
            addView(header, LinearLayout.LayoutParams(w, style.iconSize + style.iconGap))
            addView(thumb, LinearLayout.LayoutParams(w, thumbH))
            setOnTouchListener(CardTouch(ctx, thumbH) { closing ->
                if (closing) closeCard(this, app) else { launch(app, displayId); dismiss() }
            })
        }
        return ShownCard(app, card, thumb)
    }

    private fun closeEverything(row: ViewGroup, apps: List<RunningApp>, bothScreens: Boolean) {
        if (bulkClosing) return
        val openedRoot = root ?: return
        bulkClosing = true
        // A covering panel belongs to AYN's system assistant, which must never be force-stopped.
        val closable = apps.filterNot { it.panel?.covering == true }
        val all = if (bothScreens) (closable + source.appsOnDisplay(Display.DEFAULT_DISPLAY)).distinctBy { it.packageName } else closable
        // This explicit Back Button Hero command overrides OEM locks. The badges still show
        // the shared launcher status; neither that status nor native Overview policy is changed.
        val packages = all.map { it.packageName }.distinct()
        val closesCovering = apps.any { it.panel?.covering == true }
        if (packages.isEmpty() && !closesCovering) { bulkClosing = false; return }
        shown.forEach { (_, card) ->
            card.animate().translationY(-row.height.toFloat()).alpha(0f).setDuration(200).start()
        }
        row.postDelayed({
            if (root !== openedRoot) return@postDelayed
            // First, so closing Recents does not bring the panel back before AYN closes it.
            if (closesCovering) onCoveringClose()
            packages.forEach(source::forget)
            if (packages.isNotEmpty()) onCloseAll(packages, bothScreens)
            dismiss()
        }, 220)
    }

    private fun closeCard(card: View, app: RunningApp) {
        val covering = app.panel?.covering == true
        if (app.panel != null && !covering) {
            // Dismissing the return card must not kill the owner's top-screen Activity.
            card.animate().translationY(0f).alpha(1f).setDuration(150).start()
            return
        }
        card.animate().translationY(-card.height.toFloat() * 1.5f).alpha(0f).setDuration(180).withEndAction {
            val row = card.parent as? ViewGroup
            val lastCard = row?.childCount == 1
            row?.removeView(card)
            if (covering) onCoveringClose() else {
                source.forget(app.packageName)
                onCloseApp(app.packageName)
            }
            if (lastCard) {
                dismiss()
            } else {
                removeCards { it.card === card }
                selected = selected.coerceAtMost(shown.lastIndex)
                updateSelection()
            }
        }.start()
    }

    private fun updateSelection(scrollToSelection: Boolean = true) {
        closeAllButton?.setBackgroundColor(if (closeAllSelected) recentsTheme.selection else Color.TRANSPARENT)
        screenshotButton?.setBackgroundColor(if (screenshotSelected) recentsTheme.selection else Color.TRANSPARENT)
        if (!scrollToSelection || screenshotSelected || closeAllSelected) return
        shown.getOrNull(selected)?.card?.let { card ->
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
        val panel = app.panel
        if (panel == null) onLaunch(app.packageName, displayId) else onPanelLaunch(panel.id)
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

/** One displayed Recents card: its app or panel, the card view and its thumbnail view (null only in tests). */
internal data class ShownCard(val app: RunningApp, val card: View, val thumb: ImageView?)
