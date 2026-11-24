package org.fossify.messages.helpers

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import org.fossify.commons.helpers.UiRole
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.helpers.uiColor
import org.fossify.messages.R

/**
 * The hint a long-press shows on an icon of a bar. The platform draws its tooltip from theme
 * attributes an app cannot set — a light grey box under our yellow text — so every view that carries
 * one hands its text over to a small popup of ours, in the toast roles: ground, frame and text.
 *
 * The views come and go (a menu is rebuilt, an action mode opens), so the window is looked through
 * again after every layout pass.
 */
object Tooltips {
    private const val SHOWN_MS = 2500L
    private const val TEXT_SP = 14f
    private const val PAD_H_DP = 12
    private const val PAD_V_DP = 6
    private const val GAP_DP = 4
    private const val RADIUS_DP = 8f
    private const val FRAME_DP = 1.25f

    fun watch(activity: Activity) {
        val decor = activity.window.decorView
        decor.viewTreeObserver.addOnGlobalLayoutListener { adopt(decor) }
    }

    private fun adopt(view: View) {
        val text = view.tooltipText
        if (text != null) {
            view.tooltipText = null
            val ours = view.getTag(R.id.own_tooltip_text) != null
            // a view with a long-press of its own keeps it, and simply has no hint
            if (ours || !(isRPlus() && view.hasOnLongClickListeners())) {
                view.setTag(R.id.own_tooltip_text, text)
                if (!ours) {
                    view.setOnLongClickListener {
                        (it.getTag(R.id.own_tooltip_text) as? CharSequence)?.let { hint -> show(it, hint) }
                        true
                    }
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) adopt(view.getChildAt(i))
        }
    }

    private fun show(anchor: View, text: CharSequence) {
        val context = anchor.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val hint = TextView(context).apply {
            this.text = text
            maxLines = 1
            setTextColor(context.uiColor(UiRole.TOAST_TEXT))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
            setPaddingRelative(dp(PAD_H_DP), dp(PAD_V_DP), dp(PAD_H_DP), dp(PAD_V_DP))
            background = GradientDrawable().apply {
                cornerRadius = RADIUS_DP * density
                setColor(context.uiColor(UiRole.TOAST_GROUND))
                setStroke((FRAME_DP * density).toInt().coerceAtLeast(1), context.uiColor(UiRole.TOAST_FRAME))
            }
        }
        hint.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        // a pure overlay: it takes no touches, and the platform keeps it on the screen
        val popup = PopupWindow(hint, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        popup.isTouchable = false
        val shown = runCatching { popup.showAsDropDown(anchor, (anchor.width - hint.measuredWidth) / 2, dp(GAP_DP)) }
        if (shown.isSuccess) {
            anchor.postDelayed({ if (popup.isShowing) runCatching { popup.dismiss() } }, SHOWN_MS)
        }
    }
}
