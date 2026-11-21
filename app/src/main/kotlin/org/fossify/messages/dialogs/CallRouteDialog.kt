package org.fossify.messages.dialogs

import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.extensions.ThemeSlot
import org.fossify.messages.extensions.themeColor
import org.fossify.messages.helpers.MenuPills
import org.fossify.messages.helpers.ROUTE_OFF
import org.fossify.messages.helpers.ROUTE_RIN
import org.fossify.messages.helpers.ROUTE_RIN_PREFIX
import org.fossify.messages.helpers.ROUTE_SIM1
import org.fossify.messages.helpers.ROUTE_SIM2
import org.fossify.messages.helpers.Rindenwa
import org.fossify.messages.helpers.routeBadgeStyle
import org.fossify.messages.helpers.showSimBadge

private const val PADDING_DP = 20
private const val TITLE_GAP_DP = 12
private const val ROW_PAD_DP = 8
private const val GAP_DP = 12
private const val TITLE_SP = 21f
private const val OPTION_SP = 22f
private const val NOTE_SP = 13f
private const val MARK_COLUMN_DP = 64
private const val BADGE_HEIGHT_DP = 34
private const val BADGE_TEXT_SHARE = 0.6f
private const val NONE_ICON_DP = 30
private const val DIVIDER_GAP_DP = 6

/**
 * Picks a call route (helpers/Rindenwa: sim1, sim2, rin, rin:N, off): SIM 1, SIM 2, 白い熊 臨電話 through
 * its default account or through one 臨N, or none — whose line reads [noneLabel]. The 臨 lines appear
 * when Rindenwa is installed; its account list is asked afresh, and the last one it gave is offered
 * when it does not answer. One column of marks — each route's own badge — one label edge, and a clear
 * line between the SIMs, the 臨 accounts and the last line. Ported from 白い熊 連絡先.
 */
fun BaseSimpleActivity.pickCallRoute(title: String, current: String, noneLabel: String, callback: (route: String) -> Unit) {
    val appContext = applicationContext
    ensureBackgroundThread {
        val hasRin = Rindenwa.isInstalled(appContext)
        if (hasRin) Rindenwa.refreshAccounts(appContext)
        val accounts = if (hasRin) Rindenwa.cachedAccounts(appContext).filter { it.number >= 0 }.sortedBy { it.number } else emptyList()
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val groups = ArrayList<List<RouteLine>>()
            groups.add(
                listOf(
                    RouteLine(ROUTE_SIM1, getString(R.string.sim_slot_1)),
                    RouteLine(ROUTE_SIM2, getString(R.string.sim_slot_2)),
                )
            )
            if (hasRin) {
                val rin = ArrayList<RouteLine>()
                rin.add(RouteLine(ROUTE_RIN, getString(R.string.route_rin_default_short)))
                accounts.forEach { rin.add(RouteLine(ROUTE_RIN_PREFIX + it.number, it.label)) }
                // an account that is set but no longer listed stays visible, so it can be seen and changed
                if (current.startsWith(ROUTE_RIN_PREFIX) && rin.none { it.route == current }) rin.add(RouteLine(current, ""))
                groups.add(rin)
            }
            groups.add(listOf(RouteLine(ROUTE_OFF, noneLabel)))
            // Rindenwa turned us away: say why its accounts are missing instead of showing a short list
            val note = if (hasRin && Rindenwa.refused) getString(R.string.rin_accounts_refused) else null
            CallRouteDialog(this, title, groups, current, note, callback)
        }
    }
}

private class RouteLine(val route: String, val label: String)

private class CallRouteDialog(
    val activity: BaseSimpleActivity,
    title: String,
    groups: List<List<RouteLine>>,
    val current: String,
    note: String?,
    val callback: (route: String) -> Unit,
) {
    private val density = activity.resources.displayMetrics.density
    private var dialog: AlertDialog? = null

    init {
        val pad = dp(PADDING_DP)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(activity).apply {
                text = title
                setTextColor(activity.getProperTextColor())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(TITLE_GAP_DP))
            })
            groups.forEachIndexed { index, lines ->
                if (index > 0) addView(divider())
                lines.forEach { addView(buildRow(it)) }
            }
            if (note != null) {
                addView(TextView(activity).apply {
                    text = note
                    setTextColor(activity.themeColor(ThemeSlot.SETTINGS_NOTE))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, NOTE_SP)
                    setPadding(0, dp(TITLE_GAP_DP), 0, 0)
                })
            }
        }
        val scroll = ScrollView(activity).apply { addView(container) }
        activity.getAlertDialogBuilder().apply {
            activity.setupDialogStuff(scroll, this) { alertDialog -> dialog = alertDialog }
        }
    }

    private fun dp(value: Int) = (value * density).toInt()

    // The family's group divider: a clear 1 dp line in an opaque darker accent.
    private fun divider() = View(activity).apply {
        setBackgroundColor(MenuPills.divider(activity))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1)).apply {
            topMargin = dp(DIVIDER_GAP_DP)
            bottomMargin = dp(DIVIDER_GAP_DP)
        }
    }

    private fun buildRow(line: RouteLine): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ROW_PAD_DP), 0, dp(ROW_PAD_DP))
            setBackgroundResource(org.fossify.commons.R.drawable.ripple_background)
            isClickable = true
            setOnClickListener {
                callback(line.route)
                dialog?.dismiss()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val radio = (activity.layoutInflater.inflate(org.fossify.commons.R.layout.radio_button, row, false) as RadioButton).apply {
            text = ""
            isChecked = line.route == current
            isClickable = false
            isFocusable = false
        }
        // radio_button.xml is width=match_parent; force wrap so the mark and label have room beside it
        row.addView(radio, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // one column for every mark, so the labels share one left edge
        val markColumn = FrameLayout(activity)
        markColumn.addView(
            mark(line.route),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        row.addView(
            markColumn,
            LinearLayout.LayoutParams(dp(MARK_COLUMN_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(GAP_DP) }
        )

        row.addView(
            TextView(activity).apply {
                text = line.label
                setTextColor(activity.getProperTextColor())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, OPTION_SP)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(GAP_DP) }
        )
        return row
    }

    // The route's own badge — the numbered SIM card, the 臨N in its account's colours — or a crossed circle for none.
    private fun mark(route: String): View {
        val style = activity.routeBadgeStyle(route) ?: return ImageView(activity).apply {
            setImageResource(org.fossify.commons.R.drawable.ic_block_vector)
            applyColorFilter(activity.themeColor(ThemeSlot.MENU_ICON_BLOCK))
            layoutParams = ViewGroup.LayoutParams(dp(NONE_ICON_DP), dp(NONE_ICON_DP))
        }
        val icon = ImageView(activity)
        val label = TextView(activity).apply { gravity = Gravity.CENTER }
        return FrameLayout(activity).apply {
            addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            showSimBadge(icon, label, style, dp(BADGE_HEIGHT_DP), dp(BADGE_HEIGHT_DP) * BADGE_TEXT_SHARE)
        }
    }
}
