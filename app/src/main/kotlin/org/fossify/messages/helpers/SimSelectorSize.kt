package org.fossify.messages.helpers

import androidx.annotation.StringRes
import org.fossify.messages.R

/**
 * How big the compose bar's SIM selector is drawn (白い熊 メッセージ UI page). [glyphDp] is the SIM card
 * glyph's height; [FULL] matches the send button (arrow plus “SMS”), so the glyph spans the whole row.
 */
enum class SimSelectorSize(@StringRes val labelRes: Int, val glyphDp: Int, val badgeSp: Float) {
    SMALL(R.string.sim_selector_size_small, glyphDp = 24, badgeSp = 14f),
    MEDIUM(R.string.sim_selector_size_medium, glyphDp = 36, badgeSp = 17f),
    FULL(R.string.sim_selector_size_full, glyphDp = 48, badgeSp = 20f),
}

fun simSelectorSizeOf(index: Int) = SimSelectorSize.entries.getOrElse(index) { SimSelectorSize.FULL }
