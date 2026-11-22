package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.steinhorst.backbuttonhero.input.Stick
import dev.steinhorst.backbuttonhero.input.base
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The physical controls a binding group points at, in the coordinates of `docs/assets/thor-base.png`. */
enum class OverviewControl(val x: Float, val y: Float, val right: Boolean, val titleRes: Int) {
    L2(88f, 492f, false, R.string.shoulder_l2), L1(88f, 521f, false, R.string.shoulder_l1),
    SELECT(187f, 573f, false, R.string.button_select), LEFT_STICK(125f, 659f, false, R.string.stick_tab_left),
    DPAD(127.5f, 811f, false, R.string.overview_dpad), HOME(176f, 927f, false, R.string.button_home),
    R2(820f, 492f, true, R.string.shoulder_r2), R1(820f, 521f, true, R.string.shoulder_r1),
    START(721f, 573f, true, R.string.button_start), FACE(783f, 660f, true, R.string.overview_face),
    RIGHT_STICK(781f, 816f, true, R.string.stick_tab_right), BACK(733f, 925f, true, R.string.overview_back);

    /** The control in the same place on the other half; the right side's labels line up with it. */
    val mirror: OverviewControl get() = when (this) {
        L2 -> R2; L1 -> R1; SELECT -> START; LEFT_STICK -> FACE; DPAD -> RIGHT_STICK; HOME -> BACK
        R2 -> L2; R1 -> L1; START -> SELECT; FACE -> LEFT_STICK; RIGHT_STICK -> DPAD; BACK -> HOME
    }
}

/**
 * The parts of the drawing a finger can open a page from, as source-pixel bounds with the corner
 * radius of their outline; a radius of half the size makes a circle. Each shoulder knuckle carries
 * two controls. [stick] picks the stick tab the page should show.
 */
enum class OverviewArea(
    val section: SettingsSection, val controls: List<OverviewControl>,
    val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float, val stick: Stick? = null,
) {
    SHOULDERS_LEFT(SettingsSection.SHOULDERS, listOf(OverviewControl.L2, OverviewControl.L1), 30f, 478f, 146f, 535f, 22f),
    SHOULDERS_RIGHT(SettingsSection.SHOULDERS, listOf(OverviewControl.R2, OverviewControl.R1), 762f, 478f, 877f, 535f, 22f),
    LEFT_STICK(SettingsSection.STICKS, listOf(OverviewControl.LEFT_STICK), 57f, 591f, 193f, 727f, 68f, Stick.LEFT),
    RIGHT_STICK(SettingsSection.STICKS, listOf(OverviewControl.RIGHT_STICK), 713f, 748f, 849f, 884f, 68f, Stick.RIGHT),
    DPAD(SettingsSection.DPAD, listOf(OverviewControl.DPAD), 63.5f, 747f, 191.5f, 875f, 64f),
    FACE(SettingsSection.FACE, listOf(OverviewControl.FACE), 709f, 586f, 857f, 734f, 74f),
    // The small keys come last so they win where their enlarged touch targets meet a stick's.
    SELECT(SettingsSection.BASIC, listOf(OverviewControl.SELECT), 167f, 553f, 207f, 593f, 20f),
    START(SettingsSection.BASIC, listOf(OverviewControl.START), 701f, 553f, 741f, 593f, 20f),
    HOME(SettingsSection.BASIC, listOf(OverviewControl.HOME), 156f, 907f, 196f, 947f, 20f),
    BACK(SettingsSection.BASIC, listOf(OverviewControl.BACK), 713f, 905f, 753f, 945f, 20f),
}

/**
 * Every assigned binding of one control. [directions] (arrow to action, up/down/left/right order)
 * form a two-column grid; [rows] list prefixed actions underneath. A group whose only row has no
 * prefix shows its [title] in that row's prefix column; others put the title above their entries.
 */
data class OverviewGroup(
    val control: OverviewControl,
    val title: String,
    val rows: List<Pair<String, String>>,
    val section: SettingsSection,
    val directions: List<Pair<String, String>> = emptyList(),
)

/**
 * A line drawing of the Thor's lower half with a leader line from each assigned control to its
 * labels. Labels form a mirrored table on both sides, flush with the edge the lines leave from, so
 * even a fully assigned device reads as two tidy columns. The drawing is ours, traced from the
 * measured geometry of the project's own device outline, so it stays sharp and follows the theme.
 */
class BindingsOverview(context: Context) : ViewGroup(context) {
    /** Opens the page of a tapped part of the drawing. */
    var onOpen: (OverviewArea) -> Unit = {}
    // Settings pages are always built by the activity, whose theme the colours come from.
    private val activity = context as Activity
    private val primary = activity.themedColor(android.R.attr.textColorPrimary, Color.BLACK)
    private val secondary = activity.themedColor(android.R.attr.textColorSecondary, Color.DKGRAY)
    private val accent = activity.themedColor(android.R.attr.colorAccent, Color.parseColor("#6750A4"))
    /** Arrows and press names: bold and nearly as dark as the actions, so they read as the table's key. */
    private val key = (primary and 0x00FFFFFF) or 0xB3000000.toInt()
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = (primary and 0x00FFFFFF) or 0x99000000.toInt()
    }
    private val leader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = px(1f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = (secondary and 0x00FFFFFF) or 0xCC000000.toInt()
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val path = Path()
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (accent and 0x00FFFFFF) or 0x40000000 }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = outline.color
    }
    /** One line under the drawing that says how to read and change the labels. */
    private val hint = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        tag = "settings_overview_hint"
    }
    /**
     * A tappable part of the drawing. A faint tint marks it as one; pressing or focusing it deepens
     * the tint. The touch target grows to a finger's size around keys the drawing shows smaller.
     */
    private class AreaView(context: Context) : View(context) {
        val shape = RectF()
        var radius = 0f
        var tint = 0
        var strong = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            paint.color = if (isPressed || isFocused) strong else tint
            canvas.drawRoundRect(shape, radius, radius, paint)
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }
    }
    private val areas = OverviewArea.entries.associateWith { area ->
        AreaView(context).apply {
            tag = "settings_overview_area_${area.name}"
            isClickable = true
            isFocusable = true
            // A dark page swallows a faint tint, so it gets a little more.
            val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            tint = (accent and 0x00FFFFFF) or (if (night) 0x33000000 else 0x1A000000)
            strong = (accent and 0x00FFFFFF) or (if (night) 0x66000000 else 0x4D000000)
            setOnClickListener { onOpen(area) }
        }
    }
    /** The on/off shortcut, centred under the drawing; it belongs to no one control, so it has no line. */
    private val toggle = LinearLayout(context).apply {
        tag = "settings_overview_toggle"
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    /** The toggle's height with its gap to the drawing, or 0 without a shortcut. */
    private var toggleSpace = 0
    private var groups = emptyList<OverviewGroup>()
    private val views = mutableListOf<LinearLayout>()
    /** Every action label, so a narrow column can cap them and ellipsize instead of wrapping. */
    private val actions = mutableListOf<Pair<TextView, Int>>()
    /**
     * A direction grid: its two label columns and the width everything else in a line takes. When the
     * labels would be cut short side by side, [stacked] lists one direction per line instead.
     */
    private class Grid(
        val first: List<TextView>, val second: List<TextView>, val overhead: Int,
        val paired: List<View>, val stacked: List<View>,
    )
    private val grids = mutableListOf<Grid>()
    /** The drawing's top-left corner and scale, from source pixels to view pixels. */
    private var originX = 0f
    private var originY = 0f
    private var scale = 1f
    private var wide = true

    init {
        setWillNotDraw(false)
        tag = "settings_overview"
        hint.setTextColor(secondary)
        addView(hint)
        addView(toggle)
        areas.values.forEach(::addView)
        describeAreas()
    }

    /** Each part says what its controls do, so TalkBack and a controller learn it before choosing it. */
    private fun describeAreas() = areas.forEach { (area, view) ->
        view.contentDescription = activity.getString(R.string.overview_group_description, area.controls.joinToString("; ") { control ->
            val group = groups.firstOrNull { it.control == control }
            val bindings = group?.let { (it.directions + it.rows).joinToString(", ") { (prefix, action) -> if (prefix.isEmpty()) action else "$prefix: $action" } }
            "${activity.getString(control.titleRes)}: ${bindings ?: activity.getString(R.string.shoulder_none)}"
        })
    }

    fun setHint(value: String) {
        hint.text = value
        requestLayout()
    }

    /** Shows "[title]: [action]" under the drawing; null hides it. */
    fun setToggle(title: String, action: String?) {
        toggle.removeAllViews()
        toggle.visibility = if (action == null) View.GONE else View.VISIBLE
        if (action == null) return requestLayout()
        toggle.addView(text(title, key, bold = true), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = activity.dp(SPACING_DP) })
        toggle.addView(text(action, primary, bold = false), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        toggle.contentDescription = "$title: $action"
        requestLayout()
    }

    fun setGroups(value: List<OverviewGroup>) {
        if (value == groups) return
        groups = value
        views.forEach(::removeView)
        views.clear()
        actions.clear()
        grids.clear()
        value.forEach { group -> addView(groupView(group).also(views::add)) }
        describeAreas()
        requestLayout()
        invalidate()
    }

    private fun px(dp: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    private fun text(value: String, color: Int, bold: Boolean) = TextView(activity).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun groupView(group: OverviewGroup): LinearLayout {
        // Labels on the left mirror those on the right: everything is flush with the edge its line leaves from.
        val left = !group.control.right
        val flush = if (left) Gravity.END else Gravity.START
        val gap = activity.dp(6)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = flush
            setPadding(activity.dp(6), activity.dp(2), activity.dp(6), activity.dp(2))
            tag = "settings_overview_${group.control.name}"
            // The drawing's parts open the pages and carry these words for TalkBack; the labels only read.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val reserve = column.paddingLeft + column.paddingRight
        fun line() = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or flush
            setPadding(0, activity.dp(1), 0, activity.dp(1))
        }
        /** One prefix and its action, mirrored on the left; [width] keeps a prefix column aligned. */
        fun cell(prefix: TextView, width: Int, action: TextView, into: LinearLayout, share: Int, trailing: Int = 0) {
            prefix.gravity = flush
            val prefixParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            val actionParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (left) {
                into.addView(action, actionParams.apply { marginEnd = gap })
                into.addView(prefix, prefixParams.apply { marginEnd = trailing })
            } else {
                into.addView(prefix, prefixParams)
                into.addView(action, actionParams.apply { marginStart = gap; marginEnd = trailing })
            }
            actions += action to (reserve + share)
        }
        fun widest(views: List<TextView>) = views.maxOfOrNull { kotlin.math.ceil(it.paint.measureText(it.text.toString())).toInt() } ?: 0

        val inline = group.directions.isEmpty() && group.rows.size == 1 && group.rows[0].first.isEmpty()
        if (inline) {
            val title = text(group.title, accent, bold = true)
            column.addView(line().also { cell(title, widest(listOf(title)), text(group.rows[0].second, primary, false), it, widest(listOf(title)) + gap) })
            return column
        }
        column.addView(text(group.title, accent, bold = true).apply {
            gravity = flush
            setPadding(0, activity.dp(1), 0, activity.dp(1))
        })
        if (group.directions.isNotEmpty()) {
            // Up/down and left/right pair up, so four directions take two lines instead of four.
            val arrows = group.directions.map { text(it.first, key, true) }
            val arrowWidth = widest(arrows)
            val labels = group.directions.map { text(it.second, primary, false) }
            val inner = activity.dp(14)
            val first = labels.filterIndexed { i, _ -> i % 2 == 0 }
            val second = labels.filterIndexed { i, _ -> i % 2 == 1 }
            val paired = group.directions.indices.chunked(2).map { pair ->
                line().also { row ->
                    pair.forEachIndexed { k, i ->
                        val label = labels[i]
                        if (k == 0 && pair.size == 2) label.gravity = flush
                        cell(arrows[i], arrowWidth, label, row, 0, trailing = if (k == 0 && pair.size == 2) inner else 0)
                        actions.removeAt(actions.lastIndex)
                    }
                }
            }
            val stacked = group.directions.map { (arrow, label) ->
                line().also { cell(text(arrow, key, true), arrowWidth, text(label, primary, false), it, arrowWidth + gap) }
                    .apply { visibility = View.GONE }
            }
            (paired + stacked).forEach(column::addView)
            grids += Grid(first, second, reserve + 2 * (arrowWidth + gap) + inner, paired, stacked)
        }
        val prefixes = group.rows.map { text(it.first, key, true) }
        val prefixWidth = widest(prefixes)
        group.rows.zip(prefixes).forEach { (row, prefix) ->
            column.addView(line().also { cell(prefix, prefixWidth, text(row.second, primary, false), it, prefixWidth + gap) })
        }
        return column
    }

    private fun natural(view: TextView) = kotlin.math.ceil(view.paint.measureText(view.text.toString())).toInt()

    /** A grid in [stack] whose pairs would be cut short lists one direction per line instead. */
    private fun measureGroups(column: Int, stack: Set<Grid>) {
        actions.forEach { (view, reserved) -> view.maxWidth = (column - reserved).coerceAtLeast(0) }
        // A grid's narrow column keeps its natural width so the other one gets the rest of the line.
        grids.forEach { grid ->
            val room = (column - grid.overhead).coerceAtLeast(0)
            val wantFirst = grid.first.maxOf(::natural)
            val wantSecond = grid.second.maxOfOrNull(::natural) ?: 0
            val stacked = grid in stack && grid.second.isNotEmpty() && wantFirst + wantSecond > room
            grid.paired.forEach { it.visibility = if (stacked) View.GONE else View.VISIBLE }
            grid.stacked.forEach { it.visibility = if (stacked) View.VISIBLE else View.GONE }
            val firstWidth = min(wantFirst, max(room - wantSecond, room / 2))
            grid.first.forEach { it.maxWidth = firstWidth; it.minWidth = if (grid.second.isEmpty()) 0 else firstWidth }
            // Equal widths in both columns keep every arrow of a grid in line, whichever edge the rows are flush with.
            val secondWidth = min(wantSecond, (room - firstWidth).coerceAtLeast(0))
            grid.second.forEach { it.maxWidth = secondWidth; it.minWidth = secondWidth }
        }
        views.forEach { it.measure(MeasureSpec.makeMeasureSpec(column, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        wide = width >= activity.dp(WIDE_DP)
        val stub = activity.dp(STUB_DP)
        val drawing = if (wide) {
            // The labels take what they need; the drawing grows into the rest, within readable bounds.
            measureGroups(Int.MAX_VALUE / 4, emptySet())
            val need = views.maxOfOrNull { it.measuredWidth } ?: 0
            (width - 2f * (need + stub)).coerceIn(min(px(MIN_DRAWING_DP), width * 0.4f), min(px(MAX_DRAWING_DP), width * 0.6f))
        } else {
            min(width.toFloat(), px(MAX_DRAWING_DP))
        }
        scale = drawing / CROP.width()
        originX = (width - drawing) / 2 - CROP.left * scale
        val drawingHeight = CROP.height() * scale
        hint.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
        val hintSpace = if (hint.text.isNullOrEmpty()) 0 else hint.measuredHeight + activity.dp(SPACING_DP)
        toggle.measure(MeasureSpec.makeMeasureSpec(drawing.roundToInt(), MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
        toggleSpace = if (toggle.visibility == View.VISIBLE) toggle.measuredHeight + activity.dp(TOGGLE_GAP_DP) else 0
        val height = if (wide) {
            val column = ((width - drawing) / 2 - stub).roundToInt().coerceAtLeast(0)
            // Only a known page height can be centred in or checked for room; an open-ended pass gets the compact size.
            val room = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) - hintSpace else 0
            // One direction per line beats a cut-short pair, but not a page that has to scroll: each grid
            // stacks only while everything still fits.
            val stack = mutableSetOf<Grid>()
            if (room > 0) grids.forEach { grid ->
                stack += grid
                measureGroups(column, stack)
                if (settle() + px(HEADER_INSET_DP) > room) stack -= grid
            }
            measureGroups(column, stack)
            val content = settle()
            // Given more height than it needs, the picture centres in it; it always keeps clear of the
            // chevron and service chip that float over the page's top corners where the header is hidden.
            val offset = max(px(HEADER_INSET_DP), (room - content) / 2)
            originY += offset
            max(room.toFloat(), offset + content) + hintSpace
        } else {
            measureGroups(width, grids.toSet())
            originY = -CROP.top * scale
            drawingHeight + toggleSpace + hintSpace + activity.dp(STUB_DP) + views.sumOf { it.measuredHeight + activity.dp(SPACING_DP) }
        }
        setMeasuredDimension(width, height.roundToInt())
    }

    /** Places the labels around the drawing and returns the height both take together. */
    private fun settle(): Float {
        // Labels settle around their controls with the drawing at 0; whatever reaches above it shifts everything down.
        originY = -CROP.top * scale
        val placed = Side.entries.flatMap { place(it) }
        val top = min(0f, placed.minOfOrNull { it.second } ?: 0f)
        originY -= top
        val labels = placed.maxOfOrNull { it.second - top + it.third.measuredHeight } ?: 0f
        return max(labels, originY + CROP.bottom * scale + toggleSpace)
    }

    private enum class Side { LEFT, RIGHT }

    /**
     * Top positions for one side's groups. Both halves are placed together: a control and its mirror
     * share one slot, so their labels' first rows sit level and both leader lines take the same shape.
     * Each slot wants its first row level with its control; slots that would overlap merge into a
     * block centred on their wishes, so labels stay near their lines.
     */
    private fun place(side: Side): List<Triple<OverviewGroup, Float, View>> {
        val spacing = activity.dp(SPACING_DP).toFloat()
        class Slot(val members: List<Int>, val lead: Float, val height: Float, val wish: Float)
        val slots = OverviewControl.entries.filter { !it.right }.sortedBy { it.y }.mapNotNull { left ->
            val members = groups.indices.filter { groups[it].control == left || groups[it].control == left.mirror }
            if (members.isEmpty()) return@mapNotNull null
            // The left control sets the level when it is shown, the right one when it stands alone.
            val control = groups[members.firstOrNull { groups[it].control == left } ?: members.first()].control
            val lead = members.maxOf { firstRowCenter(views[it]) }
            val height = members.maxOf { lead - firstRowCenter(views[it]) + views[it].measuredHeight }
            Slot(members, lead, height, originY + control.y * scale - lead)
        }
        class Block(val first: Int, var count: Int, var height: Float, var wish: Float) {
            val top get() = wish / count
        }
        val blocks = mutableListOf<Block>()
        slots.forEachIndexed { k, slot ->
            var block = Block(k, 1, slot.height, slot.wish)
            while (blocks.isNotEmpty() && blocks.last().let { it.top + it.height + spacing > block.top }) {
                val previous = blocks.removeAt(blocks.lastIndex)
                // Every member of the later block now sits below the earlier one, so its wish moves up by that much.
                block = Block(previous.first, previous.count + block.count, previous.height + spacing + block.height,
                    previous.wish + block.wish - block.count * (previous.height + spacing))
            }
            blocks += block
        }
        return blocks.flatMap { block ->
            var top = block.top
            (block.first until block.first + block.count).flatMap { k ->
                val slot = slots[k]
                slot.members.filter { groups[it].control.right == (side == Side.RIGHT) }
                    .map { i -> Triple(groups[i], top + slot.lead - firstRowCenter(views[i]), views[i] as View) }
                    .also { top += slot.height + spacing }
            }
        }
    }

    private fun firstRowCenter(view: View): Float {
        val row = (view as ViewGroup).getChildAt(0)
        return view.paddingTop + row.measuredHeight / 2f
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        // Below everything in the wide layout, so no leader line ever runs through it; under the drawing otherwise.
        val drawingBottom = (originY + CROP.bottom * scale).roundToInt()
        val toggleTop = drawingBottom + activity.dp(TOGGLE_GAP_DP)
        val toggleLeft = (originX + (CROP.left + CROP.right) / 2 * scale - toggle.measuredWidth / 2f).roundToInt()
        toggle.layout(toggleLeft, toggleTop, toggleLeft + toggle.measuredWidth, toggleTop + toggle.measuredHeight)
        val hintTop = if (wide) b - t - hint.measuredHeight else drawingBottom + toggleSpace + activity.dp(SPACING_DP)
        val hintLeft = (width - hint.measuredWidth) / 2
        hint.layout(hintLeft, hintTop, hintLeft + hint.measuredWidth, hintTop + hint.measuredHeight)
        val target = px(TOUCH_DP)
        areas.forEach { (area, view) ->
            val left = originX + area.left * scale
            val top = originY + area.top * scale
            val right = originX + area.right * scale
            val bottom = originY + area.bottom * scale
            val padX = max(0f, (target - (right - left)) / 2)
            val padY = max(0f, (target - (bottom - top)) / 2)
            view.layout((left - padX).roundToInt(), (top - padY).roundToInt(), (right + padX).roundToInt(), (bottom + padY).roundToInt())
            view.shape.set(left - view.left, top - view.top, right - view.left, bottom - view.top)
            view.radius = area.radius * scale
        }
        if (wide) {
            val stub = activity.dp(STUB_DP)
            val drawingLeft = originX + CROP.left * scale
            val drawingRight = originX + CROP.right * scale
            Side.entries.forEach { side ->
                place(side).forEach { (_, top, view) ->
                    val left = if (side == Side.LEFT) (drawingLeft - stub - view.measuredWidth).roundToInt() else (drawingRight + stub).roundToInt()
                    view.layout(left, top.roundToInt(), left + view.measuredWidth, top.roundToInt() + view.measuredHeight)
                }
            }
        } else {
            var top = hint.bottom + activity.dp(STUB_DP)
            views.forEach { view ->
                view.layout(0, top, min(width, view.measuredWidth), top + view.measuredHeight)
                top += view.measuredHeight + activity.dp(SPACING_DP)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(originX, originY)
        canvas.scale(scale, scale)
        outline.strokeWidth = px(1.25f) / scale
        drawDevice(canvas)
        canvas.restore()
        if (!wide) return
        groups.forEachIndexed { i, group ->
            val view = views[i]
            val y = view.top + firstRowCenter(view)
            val right = group.control.right
            val start = if (right) view.left + view.paddingLeft - px(4f) else view.right - view.paddingRight + px(4f)
            val elbow = originX + (if (right) CROP.right else CROP.left) * scale
            val x = originX + group.control.x * scale
            val anchorY = originY + group.control.y * scale
            path.rewind()
            path.moveTo(start, y)
            path.lineTo(elbow, y)
            path.lineTo(x, anchorY)
            canvas.drawPath(path, leader)
            canvas.drawCircle(x, anchorY, px(5f), halo)
            canvas.drawCircle(x, anchorY, px(2.5f), dot)
        }
    }

    private fun drawDevice(canvas: Canvas) {
        fun round(l: Float, t: Float, r: Float, b: Float, radius: Float) = canvas.drawRoundRect(RectF(l, t, r, b), radius, radius, outline)
        // Hinge: the two shoulder knuckles (L2 over L1, R2 over R1) and the barrel between them.
        round(30f, 478f, 146f, 535f, 22f)
        round(762f, 478f, 877f, 535f, 22f)
        canvas.drawLine(40f, 506.5f, 136f, 506.5f, outline)
        canvas.drawLine(772f, 506.5f, 867f, 506.5f, outline)
        round(146f, 482f, 762f, 531f, 6f)
        // Body, bottom screen and the dock bay at the lower edge.
        round(30f, 540f, 877f, 986f, 40f)
        round(232f, 563f, 680f, 918f, 10f)
        canvas.drawPath(Path().apply {
            moveTo(298f, 986f); lineTo(298f, 940f); lineTo(610f, 940f); lineTo(610f, 986f)
        }, outline)
        // Sticks: base ring, well and cap.
        listOf(125f to 659f, 781f to 816f).forEach { (x, y) ->
            canvas.drawCircle(x, y, 68f, outline)
            canvas.drawCircle(x, y, 47f, outline)
            canvas.drawCircle(x, y, 36f, outline)
        }
        // D-pad in its round housing.
        canvas.drawCircle(127.5f, 811f, 64f, outline)
        val arm = 20f
        val reach = 53f
        canvas.drawPath(Path().apply {
            val cx = 127.5f
            val cy = 811f
            moveTo(cx - arm, cy - reach); lineTo(cx + arm, cy - reach); lineTo(cx + arm, cy - arm)
            lineTo(cx + reach, cy - arm); lineTo(cx + reach, cy + arm); lineTo(cx + arm, cy + arm)
            lineTo(cx + arm, cy + reach); lineTo(cx - arm, cy + reach); lineTo(cx - arm, cy + arm)
            lineTo(cx - reach, cy + arm); lineTo(cx - reach, cy - arm); lineTo(cx - arm, cy - arm); close()
        }, outline)
        // Face buttons with their printed letters (X top, A right, as on the Thor).
        canvas.drawCircle(783f, 660f, 74f, outline)
        letter.textSize = 22f
        listOf("X" to (783f to 617f), "Y" to (738f to 660f), "A" to (828f to 660f), "B" to (783f to 703f)).forEach { (name, at) ->
            canvas.drawCircle(at.first, at.second, 21f, outline)
            canvas.drawText(name, at.first, at.second + 8f, letter)
        }
        // Select, Start, Home and Back with their small glyphs.
        listOf(187f to 573f, 721f to 573f, 176f to 927f, 733f to 925f).forEach { (x, y) -> canvas.drawCircle(x, y, 13f, outline) }
        round(182f, 568f, 192f, 578f, 1.5f)
        canvas.drawPath(Path().apply { moveTo(717f, 567f); lineTo(727f, 573f); lineTo(717f, 579f); close() }, outline)
        canvas.drawPath(Path().apply { moveTo(170f, 928f); lineTo(176f, 921f); lineTo(182f, 928f); moveTo(176f, 922f); lineTo(176f, 934f) }, outline)
        // Speaker grilles.
        val grille = outline.strokeWidth
        outline.strokeWidth = grille * 3f
        canvas.drawLine(70f, 925f, 132f, 925f, outline)
        canvas.drawLine(776f, 925f, 838f, 925f, outline)
        outline.strokeWidth = grille
    }

    private companion object {
        /** The lower half of the outline, hinge included: everything that carries a control. */
        val CROP = RectF(26f, 472f, 881f, 990f)
        /** The floating header's height less the page padding above the overview. */
        const val HEADER_INSET_DP = 24f
        /** The smallest touch target a part of the drawing gets. */
        const val TOUCH_DP = 44f
        const val TEXT_SP = 12f
        const val WIDE_DP = 560
        const val STUB_DP = 18
        const val SPACING_DP = 6
        /** Room between the drawing's lower edge and the on/off shortcut under it. */
        const val TOGGLE_GAP_DP = 14
        const val MIN_DRAWING_DP = 250f
        const val MAX_DRAWING_DP = 440f
    }
}
