package org.fossify.messages.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils

/**
 * The compose bar's ground: a faintly accent-tinted band under a thin line in the darker accent, so the bar reads
 * as a control strip docked to the bottom of the screen and never as one more message bubble. The
 * same line, alone, closes the header at the top of a conversation ([lineOnly]).
 */
class ComposeBarBackground(
    context: Context,
    background: Int,
    accent: Int,
    line: Int,
    private val lineOnly: Boolean = false,
) : Drawable() {
    private val lineHeight = LINE_DP * context.resources.displayMetrics.density
    private val bandPaint = Paint().apply { color = ColorUtils.blendARGB(background, accent, BAND_BLEND) }
    private val linePaint = Paint().apply { color = line }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (!lineOnly) {
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), bandPaint)
        }
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + lineHeight, linePaint)
    }

    override fun setAlpha(alpha: Int) {
        bandPaint.alpha = alpha
        linePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bandPaint.colorFilter = colorFilter
        linePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val LINE_DP = 1.25f // as thin, and in the same darker accent, as the text field's frame
        private const val BAND_BLEND = 0.11f // how far the band is from the background towards the accent
        private const val FIELD_RADIUS_DP = 5f
        private const val FIELD_FRAME_DP = 1.25f

        /**
         * The text field inside the band: the background colour in a small-radius box with a thin
         * [frame] — squared where the bubbles are round. It ignores colour filters, because the edit
         * text's own theming tints its background wholesale.
         */
        fun fieldBox(context: Context, background: Int, frame: Int): Drawable {
            val density = context.resources.displayMetrics.density
            return object : GradientDrawable() {
                override fun setColorFilter(colorFilter: ColorFilter?) = Unit
            }.apply {
                cornerRadius = FIELD_RADIUS_DP * density
                setColor(background)
                setStroke((FIELD_FRAME_DP * density).toInt().coerceAtLeast(1), frame)
            }
        }
    }
}
