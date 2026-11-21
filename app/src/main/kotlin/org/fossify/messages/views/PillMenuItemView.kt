package org.fossify.messages.views

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.menu.ListMenuItemView
import androidx.appcompat.view.menu.MenuItemImpl
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import org.fossify.messages.extensions.pillBackground
import org.fossify.messages.helpers.MenuPills
import kotlin.math.roundToInt
import org.fossify.commons.R as CommonsR
import org.fossify.messages.extensions.ThemeSlot
import org.fossify.messages.extensions.themeColor

/**
 * The row of every AppCompat popup menu — the toolbar and action-bar overflows, PopupMenu, submenus.
 * A menu must show which lines can be pressed, so:
 * - an action row is an outlined pill spanning the menu, its icon inside at the left and a › at the
 *   right when it opens something;
 * - a destructive action takes the warning colour, a disabled one is dimmed;
 * - a checkable row stays plain and gets an outlined switch, not a checkbox;
 * - the disabled title line a menu opens with stays plain text.
 *
 * It is swapped in through the app's copies of abc_popup_menu_item_layout and
 * abc_cascading_menu_item_layout, so no call site changes; icons and flags come from [MenuPills].
 */
@SuppressLint("RestrictedApi")
class PillMenuItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.listMenuViewStyle,
) : ListMenuItemView(context, attrs, defStyleAttr) {
    private var content: LinearLayout? = null
    private var title: TextView? = null
    private var arrow: ImageView? = null
    private var padStart = 0
    private var padTop = 0
    private var padEnd = 0
    private var padBottom = 0
    private var contentHeight = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        content = findViewById(androidx.appcompat.R.id.content)
        title = findViewById(androidx.appcompat.R.id.title)
        arrow = findViewById(androidx.appcompat.R.id.submenuarrow)
        content?.let {
            padStart = it.paddingStart
            padTop = it.paddingTop
            padEnd = it.paddingEnd
            padBottom = it.paddingBottom
            contentHeight = it.layoutParams.height
        }
        // The stock group divider is all but invisible on a black menu: a clear line, as wide as the pills.
        findViewById<View>(androidx.appcompat.R.id.group_divider)?.let { divider ->
            divider.setBackgroundColor(MenuPills.divider(context))
            (divider.layoutParams as? MarginLayoutParams)?.let { params ->
                params.leftMargin = dp(MenuPills.SIDE_MARGIN_DP)
                params.rightMargin = dp(MenuPills.SIDE_MARGIN_DP)
                divider.layoutParams = params
            }
        }
    }

    override fun initialize(itemData: MenuItemImpl, menuType: Int) {
        // every row keeps the icon column, so the labels share one left edge
        setForceShowIcon(true)
        super.initialize(itemData, menuType)
        if (itemData.icon == null) {
            MenuPills.iconOf(itemData.itemId)?.let { setIcon(ContextCompat.getDrawable(context, it)) }
        }
        style(itemData)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The pill carries the ripple; the list's rectangular press highlight would paint over its round ends.
        (parent as? AbsListView)?.let { list ->
            if (list.selector !is ColorDrawable) {
                list.selector = ColorDrawable(Color.TRANSPARENT)
            }
        }
    }

    private fun style(item: MenuItemImpl) {
        val content = content ?: return
        val title = title ?: return
        val checkable = item.isCheckable
        val header = !item.isEnabled && item.itemId == 0 && !item.hasSubMenu()
        val pill = !checkable && !header
        val destructive = pill && MenuPills.isDestructive(item.itemId)

        var color = if (destructive) MenuPills.warning(context) else MenuPills.accent(context)
        if (!item.isEnabled && !header) {
            color = ColorUtils.setAlphaComponent(color, MenuPills.DISABLED_ALPHA)
        }
        // the frame has a role of its own; a destructive or disabled row lends it the row's colour
        val frame = if (destructive || !item.isEnabled) color else context.themeColor(ThemeSlot.MENU_FRAME)

        (content.layoutParams as MarginLayoutParams).let { params ->
            val side = dp(MenuPills.SIDE_MARGIN_DP)
            val vertical = if (pill) dp(MenuPills.PILL_MARGIN_DP) else 0
            // a checkable row has nothing to frame, so it is only as tall as its switch needs
            params.height = if (checkable) dp(MenuPills.TOGGLE_HEIGHT_DP) else contentHeight
            params.setMargins(side, vertical, side, vertical)
            content.layoutParams = params
        }
        content.background = if (pill) context.pillBackground(frame) else null
        // the switch of a checkable row ends where the pills end
        content.setPaddingRelative(padStart, padTop, if (checkable) 0 else padEnd, padBottom)

        // A plain string: the action-bar menus colour their titles with a span, which would win over this.
        title.text = item.title?.toString()
        title.setTextColor(color)

        arrow?.let { arrow ->
            val opens = pill && (
                item.hasSubMenu() || MenuPills.opens(item.itemId) || item.title?.toString()?.trim()?.endsWith("…") == true
                )
            if (opens) {
                arrow.setImageDrawable(
                    ContextCompat.getDrawable(context, CommonsR.drawable.ic_chevron_right_vector)?.mutate()?.apply { setTint(color) }
                )
            }
            arrow.visibility = if (opens) VISIBLE else GONE
        }

        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            if (child is CheckBox) {
                styleSwitch(child)
            } else if (child.id == androidx.appcompat.R.id.icon && child is ImageView) {
                // the icon alone carries the row's role colour; a disabled row dims it like the rest
                val role = MenuPills.iconColor(context, item.itemId)
                styleIcon(child, if (item.isEnabled) role else ColorUtils.setAlphaComponent(role, MenuPills.DISABLED_ALPHA))
            }
        }
    }

    private fun styleSwitch(box: CheckBox) {
        if (box.tag != null) {
            return
        }
        box.buttonDrawable = SwitchDrawable(
            on = context.themeColor(ThemeSlot.SWITCH_ON),
            off = MenuPills.switchOff(context),
            width = dp(SWITCH_WIDTH_DP),
            height = dp(SWITCH_HEIGHT_DP),
            strokeWidth = dp(SWITCH_STROKE_DP).toFloat(),
        )
        box.background = null
        // a long label stops short of the switch
        (box.layoutParams as? MarginLayoutParams)?.let { params ->
            params.marginStart = dp(MenuPills.SIDE_MARGIN_DP)
            box.layoutParams = params
        }
        box.tag = true
    }

    // Clear of the round end of the pill, and in the colour of what its row does.
    private fun styleIcon(icon: ImageView, color: Int) {
        (icon.layoutParams as? MarginLayoutParams)?.let { params ->
            if (params.marginStart != dp(ICON_START_DP)) {
                params.marginStart = dp(ICON_START_DP)
                params.marginEnd = -dp(ICON_END_PULL_DP)
                params.topMargin = dp(ICON_VERTICAL_DP)
                params.bottomMargin = dp(ICON_VERTICAL_DP)
                icon.layoutParams = params
            }
        }
        icon.imageTintList = ColorStateList.valueOf(color)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    /** The theme's outlined switch: a round outline with a solid dot — right and accent when on, left and darker when off. */
    private class SwitchDrawable(
        private val on: Int,
        private val off: Int,
        private val width: Int,
        private val height: Int,
        strokeWidth: Float,
    ) : Drawable() {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rect = RectF()
        private var checked = false

        override fun draw(canvas: Canvas) {
            val b = bounds
            val color = if (checked) on else off
            val inset = stroke.strokeWidth / 2f
            rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
            stroke.color = color
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, stroke)
            fill.color = color
            val cx = if (checked) b.right - b.height() / 2f else b.left + b.height() / 2f
            canvas.drawCircle(cx, b.exactCenterY(), b.height() * DOT_RADIUS_FRACTION, fill)
        }

        override fun isStateful() = true

        override fun onStateChange(state: IntArray): Boolean {
            val now = android.R.attr.state_checked in state
            if (now == checked) {
                return false
            }
            checked = now
            invalidateSelf()
            return true
        }

        override fun getIntrinsicWidth() = width
        override fun getIntrinsicHeight() = height
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val SWITCH_WIDTH_DP = 44
        const val SWITCH_HEIGHT_DP = 24
        const val SWITCH_STROKE_DP = 2
        const val DOT_RADIUS_FRACTION = 0.23f // the dot is 46 % of the track height
        const val ICON_START_DP = 14
        const val ICON_END_PULL_DP = 6
        const val ICON_VERTICAL_DP = 2
    }
}
