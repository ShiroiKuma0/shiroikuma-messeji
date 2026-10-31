package org.fossify.messages.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.messages.R
import org.fossify.messages.extensions.ThemeSlot
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.themeColor
import org.fossify.messages.extensions.themeTypeface
import org.fossify.messages.extensions.toImperialDateString

/**
 * Day headers between list rows — 今日, 昨日, then 令和八年十月二十九日（木曜日）— drawn exactly like
 * 白い熊 電話's call-log `item_recents_date` as 白い熊 has it set: a 2dp full-width divider, then (16dp in,
 * 12dp down) the date text in 27sp, a 2dp gap and a 1dp underline as wide as the text, and 12dp below.
 * [timeAt] gives a row's time in epoch millis, or null for a pinned conversation: those sit above the
 * dated rows under their own ピン留め header. Used by the conversation list and the 送信済み list.
 *
 * Drawn as a decoration rather than list rows so selection, swipe and the adapter's positions stay
 * one row per conversation; a swiped row slides out from under its header, which stays put.
 */
class DayHeaderDecoration(
    private val context: Context,
    private val timeAt: (position: Int) -> Long?,
) : RecyclerView.ItemDecoration() {
    private val density = context.resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint()
    private val underlinePaint = Paint()
    private var headerHeight = 0

    init {
        refresh()
    }

    /** Re-read colours, font and size (call after the theme may have changed). */
    fun refresh() {
        val config = context.config
        val slot = ThemeSlot.CONVERSATION_DAY_DATE.key
        // No font of its own yet → borrow the 設 launcher character's, the same brush 電話 uses for both.
        var family = config.getFontFamily(slot)
        var weight = config.getFontWeight(slot)
        if (family.isEmpty() && weight == 0) {
            family = config.getFontFamily(ThemeSlot.SETTINGS_BUTTON_LEFT.key)
            weight = config.getFontWeight(ThemeSlot.SETTINGS_BUTTON_LEFT.key)
        }
        textPaint.typeface = context.themeTypeface(family, weight)
        val sizeSp = config.getFontSize(slot)
        textPaint.textSize = if (sizeSp > 0) {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat(), context.resources.displayMetrics)
        } else {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DEFAULT_SIZE_SP, context.resources.displayMetrics)
        }
        textPaint.color = context.themeColor(ThemeSlot.CONVERSATION_DAY_DATE)
        dividerPaint.color = context.themeColor(ThemeSlot.CONVERSATION_DAY_DIVIDER)
        underlinePaint.color = context.themeColor(ThemeSlot.CONVERSATION_DATE_UNDERLINE)
        val metrics = textPaint.fontMetrics
        headerHeight = (dp(DIVIDER_DP + MARGIN_V_DP + GAP_DP + UNDERLINE_DP + MARGIN_V_DP) +
            (metrics.descent - metrics.ascent)).toInt()
    }

    private fun dp(value: Float) = value * density

    private fun groupAt(position: Int): String = timeAt(position)?.let(::dayCode) ?: PINNED

    // A header goes above the first row of each day (and of the pinned group).
    private fun headerAt(position: Int, itemCount: Int): Boolean = when {
        position !in 0..<itemCount -> false
        position == 0 -> true
        else -> groupAt(position) != groupAt(position - 1)
    }

    private fun itemCount(parent: RecyclerView) = parent.adapter?.itemCount ?: 0

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        outRect.top = if (headerAt(position, itemCount(parent))) headerHeight else 0
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = itemCount(parent)
        for (i in 0..<parent.childCount) {
            val child = parent.getChildAt(i)
            val position = parent.getChildAdapterPosition(child)
            if (!headerAt(position, count)) {
                continue
            }
            drawHeader(canvas, parent, child.top + child.translationY - headerHeight, label(timeAt(position)))
        }
    }

    private fun drawHeader(canvas: Canvas, parent: RecyclerView, top: Float, text: String) {
        val divider = dp(DIVIDER_DP)
        val right = (parent.width - parent.paddingRight).toFloat()
        canvas.drawRect(parent.paddingLeft.toFloat(), top, right, top + divider, dividerPaint)
        val left = parent.paddingLeft + dp(MARGIN_H_DP)
        val metrics = textPaint.fontMetrics
        val textTop = top + divider + dp(MARGIN_V_DP)
        canvas.drawText(text, left, textTop - metrics.ascent, textPaint)
        val lineTop = textTop + (metrics.descent - metrics.ascent) + dp(GAP_DP)
        canvas.drawRect(left, lineTop, left + textPaint.measureText(text), lineTop + dp(UNDERLINE_DP), underlinePaint)
    }

    private fun label(millis: Long?): String {
        val now = System.currentTimeMillis()
        return when {
            millis == null -> context.getString(R.string.day_header_pinned)
            DateUtils.isToday(millis) -> context.getString(R.string.day_header_today)
            dayCode(millis) == dayCode(now - DateUtils.DAY_IN_MILLIS) ->
                context.getString(R.string.day_header_yesterday)
            context.config.useImperialDate -> millis.toImperialDateString()
            else -> millis.formatDateOrTime(context, hideTimeOnOtherDays = true, showCurrentYear = false)
        }
    }

    private fun dayCode(millis: Long): String = android.text.format.DateFormat.format("yyyyMMdd", millis).toString()

    private companion object {
        const val PINNED = "pinned"
        const val DEFAULT_SIZE_SP = 27f // 白い熊's 電話 day-date size
        const val DIVIDER_DP = 2f
        const val MARGIN_H_DP = 16f
        const val MARGIN_V_DP = 12f
        const val GAP_DP = 2f
        const val UNDERLINE_DP = 1f
    }
}
