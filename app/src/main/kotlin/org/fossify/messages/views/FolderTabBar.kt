package org.fossify.messages.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlin.math.abs

/** One folder tab: [badge] is the unread count drawn in a circle after the title (0 = none). */
class FolderTabItem(val id: Int, val title: String, val badge: Int, val movable: Boolean)

/**
 * The folder-style tab bar of the conversation list, in the look of 白い熊 書類管理's file tabs and
 * 白い熊 応用管理's details tabs: tabs drawn as stacked paper folders — a border line runs under the
 * inactive tabs, turns up the left of the active folder, across its top, slants down its right and
 * continues under the rest; the inactive folders show their own shaded outlines.
 *
 * Gestures: a tap selects; long-press then drag reorders (only [FolderTabItem.movable] tabs, and only
 * among themselves — the fixed 未分類 / 送信 / 全部 tabs stay at the end); a long-press released
 * without moving opens the tab's menu.
 */
class FolderTabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {
    private val row = Row(context)

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    @Suppress("LongParameterList")
    fun setTabs(
        items: List<FolderTabItem>,
        selectedId: Int,
        ink: Int,
        background: Int,
        onSelect: (Int) -> Unit,
        onMenu: (id: Int, anchor: View) -> Unit,
        onReorder: (id: Int, toIndex: Int) -> Unit,
    ) {
        setBackgroundColor(background)
        val scrollBefore = scrollX
        row.rebuild(items, selectedId, ink, background, onSelect, onMenu, onReorder)
        post {
            val selected = row.selectedView ?: return@post
            val visible = selected.left >= scrollBefore && selected.right + row.slantPx <= scrollBefore + width
            if (visible) {
                scrollTo(scrollBefore, 0)
            } else {
                smoothScrollTo((selected.left - width / SCROLL_LEAD_FRACTION).coerceAtLeast(0), 0)
            }
        }
    }

    private class Row(context: Context) : LinearLayout(context) {
        private val density = resources.displayMetrics.density
        val slantPx = SLANT_DP * density
        private val activeTopMarginPx = (ACTIVE_TOP_DP * density).toInt()
        private val inactiveHeightPx = (INACTIVE_HEIGHT_DP * density).toInt()
        private val touchSlopPx = TOUCH_SLOP_DP * density
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = STROKE_DP * density
            pathEffect = CornerPathEffect(CORNER_DP * density)
        }
        private val shadedPaint = Paint(borderPaint)

        private var tabCount = 0
        private var movableCount = 0
        var selectedView: View? = null
            private set

        private var onMenu: (Int, View) -> Unit = { _, _ -> }
        private var onReorder: (Int, Int) -> Unit = { _, _ -> }

        private var draggedView: View? = null
        private var draggedId = 0
        private var draggedMovable = false
        private var dragStartIndex = 0
        private var dragDownRawX = 0f
        private var dragExceededSlop = false

        init {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            setWillNotDraw(false)
        }

        @Suppress("LongParameterList")
        fun rebuild(
            items: List<FolderTabItem>,
            selectedId: Int,
            ink: Int,
            background: Int,
            onSelect: (Int) -> Unit,
            onMenu: (Int, View) -> Unit,
            onReorder: (Int, Int) -> Unit,
        ) {
            endDrag()
            this.onMenu = onMenu
            this.onReorder = onReorder
            removeAllViews()
            tabCount = items.size
            movableCount = items.count { it.movable }
            selectedView = null
            borderPaint.color = ink
            shadedPaint.color = ColorUtils.setAlphaComponent(ink, SHADED_ALPHA)
            val dim = ColorUtils.setAlphaComponent(ink, DIM_ALPHA)

            items.forEach { item ->
                val active = item.id == selectedId
                val tab = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER
                    val padH = (TAB_PADDING_DP * density).toInt()
                    setPadding(padH, 0, padH, 0)
                }
                tab.addView(TextView(context).apply {
                    text = item.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
                    setTextColor(if (active) ink else dim)
                    setTypeface(typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
                    maxLines = 1
                })
                if (item.badge > 0) {
                    tab.addView(badgeView(item.badge, ink, background), LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = (BADGE_GAP_DP * density).toInt() })
                }
                tab.setOnClickListener { onSelect(item.id) }
                tab.setOnLongClickListener { beginDrag(it, item.id, item.movable) }
                tab.setOnTouchListener { view, event -> onDragTouch(view, event) }
                val params = LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    if (active) LayoutParams.MATCH_PARENT else inactiveHeightPx
                )
                params.marginEnd = slantPx.toInt()
                if (active) {
                    params.topMargin = activeTopMarginPx
                    selectedView = tab
                }
                addView(tab, params)
            }
            invalidate()
        }

        // The unread count: bold, in a filled circle of the tab colour (a pill once it has 2+ digits).
        private fun badgeView(count: Int, ink: Int, background: Int) = TextView(context).apply {
            text = if (count > MAX_BADGE) "$MAX_BADGE+" else count.toString()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BADGE_SP)
            setTypeface(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            setTextColor(background)
            gravity = Gravity.CENTER
            val size = (BADGE_SIZE_DP * density).toInt()
            minWidth = size
            minHeight = size
            val badgePad = (BADGE_PADDING_DP * density).toInt()
            setPadding(badgePad, 0, badgePad, 0)
            this.background = GradientDrawable().apply {
                cornerRadius = size / 2f
                setColor(ink)
            }
        }

        // --- long-press: drag to reorder, or (released without moving) the tab's menu ---

        private fun beginDrag(view: View, id: Int, movable: Boolean): Boolean {
            draggedView = view
            draggedId = id
            draggedMovable = movable
            dragStartIndex = indexOfChild(view)
            dragExceededSlop = false
            if (movable) {
                view.alpha = DRAG_ALPHA
                view.translationZ = DRAG_LIFT_DP * density
            }
            parent?.requestDisallowInterceptTouchEvent(true)
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            return true
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun onDragTouch(view: View, event: MotionEvent): Boolean {
            if (draggedView !== view) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    dragDownRawX = event.rawX
                }
                return false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragDownRawX
                    if (abs(dx) > touchSlopPx) {
                        dragExceededSlop = true
                    }
                    if (draggedMovable) {
                        view.translationX = dx
                        updateDragShifts(view)
                        invalidate()
                    }
                }

                MotionEvent.ACTION_UP -> {
                    val targetIndex = dragTargetIndex(view)
                    val moved = draggedMovable && dragExceededSlop && targetIndex != dragStartIndex
                    val menu = !dragExceededSlop
                    val id = draggedId
                    endDrag()
                    when {
                        moved -> onReorder(id, targetIndex)
                        menu -> onMenu(id, view)
                    }
                }

                MotionEvent.ACTION_CANCEL -> endDrag()
            }
            return true
        }

        private fun dragTargetIndex(dragged: View): Int {
            val draggedCenter = dragged.left + dragged.width / 2f + dragged.translationX
            var index = 0
            for (i in 0..<movableCount) {
                val child = getChildAt(i)
                if (child === dragged) {
                    continue
                }
                if ((child.left + child.right) / 2f < draggedCenter) {
                    index++
                }
            }
            return index.coerceAtMost((movableCount - 1).coerceAtLeast(0))
        }

        private fun updateDragShifts(dragged: View) {
            val targetIndex = dragTargetIndex(dragged)
            val shiftPx = (dragged.width + slantPx).let { if (targetIndex >= dragStartIndex) -it else it }
            for (i in 0..<movableCount) {
                val child = getChildAt(i)
                if (child === dragged) {
                    continue
                }
                val between = if (targetIndex >= dragStartIndex) {
                    i in (dragStartIndex + 1)..targetIndex
                } else {
                    i in targetIndex..<dragStartIndex
                }
                val translation = if (between) shiftPx else 0f
                if (child.translationX != translation) {
                    child.animate().translationX(translation).setDuration(SHIFT_MS).start()
                }
            }
        }

        private fun endDrag() {
            val view = draggedView ?: return
            draggedView = null
            view.alpha = 1f
            view.translationZ = 0f
            for (i in 0..<childCount) {
                val child = getChildAt(i)
                child.animate().cancel()
                child.translationX = 0f
            }
            invalidate()
        }

        // --- the folder outlines ---

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bottom = height - borderPaint.strokeWidth / 2
            val selected = selectedView

            for (i in 0..<tabCount) {
                val child = getChildAt(i)
                if (child == null || child === selected) {
                    continue
                }
                canvas.drawPath(folderPath(child, bottom), shadedPaint)
            }

            // the border line, wrapping around the active folder
            val path = if (selected != null) {
                folderPath(selected, bottom, fromLeftEdge = true)
            } else {
                Path().apply { moveTo(0f, bottom) }
            }
            path.lineTo(width.toFloat(), bottom)
            canvas.drawPath(path, borderPaint)
        }

        private fun folderPath(child: View, bottom: Float, fromLeftEdge: Boolean = false): Path {
            val left = child.left + child.translationX
            val right = child.right + child.translationX
            val top = child.top.toFloat() + borderPaint.strokeWidth / 2
            return Path().apply {
                if (fromLeftEdge) {
                    moveTo(0f, bottom)
                    lineTo(left, bottom)
                } else {
                    moveTo(left, bottom)
                }
                lineTo(left, top)
                lineTo(right, top)
                lineTo(right + slantPx, bottom)
            }
        }
    }

    private companion object {
        const val SHADED_ALPHA = 0x55
        const val DIM_ALPHA = 0x8C
        const val DRAG_ALPHA = 0.7f
        const val TITLE_SP = 15f
        const val BADGE_SP = 12f
        const val MAX_BADGE = 99
        const val SHIFT_MS = 100L
        const val SCROLL_LEAD_FRACTION = 4
        const val SLANT_DP = 10f
        const val ACTIVE_TOP_DP = 4f
        const val INACTIVE_HEIGHT_DP = 34f
        const val TOUCH_SLOP_DP = 8f
        const val STROKE_DP = 1.5f
        const val CORNER_DP = 8f
        const val TAB_PADDING_DP = 14f
        const val BADGE_GAP_DP = 6f
        const val BADGE_SIZE_DP = 20f
        const val BADGE_PADDING_DP = 5f
        const val DRAG_LIFT_DP = 4f
    }
}
