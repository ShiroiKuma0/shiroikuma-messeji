package org.fossify.messages.helpers

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.widget.ImageView
import android.widget.TextView
import org.fossify.commons.extensions.getContrastColor

/**
 * Fork: the SIM badge — a SIM-card shape (cut top-left corner) filled with [fill], optionally ringed
 * with [border], drawn to whatever bounds it gets. Unlike the fixed-aspect `ic_sim_vector` glyph it
 * stretches sideways, so a two- or three-character badge (“J0”, “S12”) still sits inside the card.
 */
class SimBadgeDrawable(fill: Int, border: Int?, private val strokePx: Float) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fill
    }
    private val borderPaint = border?.let {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeJoin = Paint.Join.ROUND
            color = it
        }
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val inset = if (borderPaint != null) strokePx / 2f else 0f
        val left = b.left + inset
        val top = b.top + inset
        val right = b.right - inset
        val bottom = b.bottom - inset
        val short = minOf(right - left, bottom - top)
        val cut = short * CUT_FRACTION
        val radius = short * RADIUS_FRACTION
        path.reset()
        path.moveTo(left + cut, top)
        path.lineTo(right - radius, top)
        path.quadTo(right, top, right, top + radius)
        path.lineTo(right, bottom - radius)
        path.quadTo(right, bottom, right - radius, bottom)
        path.lineTo(left + radius, bottom)
        path.quadTo(left, bottom, left, bottom - radius)
        path.lineTo(left, top + cut)
        path.close()
        canvas.drawPath(path, fillPaint)
        borderPaint?.let { canvas.drawPath(path, it) }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        borderPaint?.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        borderPaint?.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val CUT_FRACTION = 0.34f
        private const val RADIUS_FRACTION = 0.12f
    }
}

/** How one SIM or transport badge looks: text, card fill, text colour, optional ring. */
data class SimBadgeStyle(val text: String, val fill: Int, val textColor: Int? = null, val border: Int? = null)

/**
 * Draw [style] into an [icon] + [label] pair (the label laid over the icon, as in every SIM badge
 * layout): the card is [cardHeightPx] tall and at least SIM-shaped, widening to fit the text.
 * [padHPx]/[padVPx] are the icon's own padding around the card (touch target).
 */
fun showSimBadge(
    icon: ImageView,
    label: TextView,
    style: SimBadgeStyle,
    cardHeightPx: Int,
    textSizePx: Float,
    padHPx: Int = 0,
    padVPx: Int = 0,
) {
    val density = icon.resources.displayMetrics.density
    label.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
    label.text = style.text
    label.setTextColor(style.textColor ?: style.fill.getContrastColor())
    label.includeFontPadding = false
    val textWidth = label.paint.measureText(style.text)
    val cardWidth = maxOf(cardHeightPx * SIM_ASPECT, textWidth + cardHeightPx * TEXT_SIDE_ROOM).toInt()
    val stroke = maxOf(1.5f * density, cardHeightPx * STROKE_FRACTION)
    icon.clearColorFilter()
    icon.setImageDrawable(SimBadgeDrawable(style.fill, style.border, stroke))
    icon.scaleType = ImageView.ScaleType.FIT_XY
    icon.setPadding(padHPx, padVPx, padHPx, padVPx)
    icon.layoutParams = icon.layoutParams.apply {
        width = cardWidth + 2 * padHPx
        height = cardHeightPx + 2 * padVPx
    }
}

private const val SIM_ASPECT = 0.8f        // the stock glyph's width : height
private const val TEXT_SIDE_ROOM = 0.45f   // card width beyond the text, as a share of its height
private const val STROKE_FRACTION = 0.07f  // ring width, as a share of the card height
