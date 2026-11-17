package org.fossify.messages.extensions

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

const val PILL_MAIN_STROKE_DP = 3f
const val PILL_REGULAR_STROKE_DP = 1.25f
private const val PILL_RADIUS_DP = 100f
private const val PILL_RIPPLE_ALPHA = 0.3f

/**
 * The black-yellow pill: a fully round outline in [color] with the background showing through —
 * never filled — and a ripple of the same colour inside it. [main] is the 3 dp frame of a block's
 * main action; every other action takes the thin 1.25 dp one.
 */
fun Context.pillBackground(color: Int, main: Boolean = false): Drawable {
    val density = resources.displayMetrics.density
    val radius = PILL_RADIUS_DP * density
    val strokeDp = if (main) PILL_MAIN_STROKE_DP else PILL_REGULAR_STROKE_DP
    val frame = GradientDrawable().apply {
        cornerRadius = radius
        setColor(Color.TRANSPARENT)
        setStroke((strokeDp * density).roundToInt().coerceAtLeast(1), color)
    }
    val mask = GradientDrawable().apply {
        cornerRadius = radius
        setColor(Color.WHITE)
    }
    val ripple = ColorUtils.setAlphaComponent(color, (Color.alpha(color) * PILL_RIPPLE_ALPHA).roundToInt())
    return RippleDrawable(ColorStateList.valueOf(ripple), frame, mask)
}
