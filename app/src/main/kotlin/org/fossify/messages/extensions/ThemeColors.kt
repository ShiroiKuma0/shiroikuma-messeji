package org.fossify.messages.extensions

import android.content.Context
import androidx.annotation.StringRes
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.messages.R
import org.fossify.messages.helpers.PALETTE_BLACK
import org.fossify.messages.helpers.PALETTE_YELLOW
import org.fossify.messages.helpers.THEME_UNSET

private const val SECONDARY_TEXT_ALPHA = 0.6f
private const val SEARCH_HINT_ALPHA = 0.5f

// Granular, per-element theming for 白い熊 メッセージ.
//
// Each [ThemeSlot] is one customizable color. Foundation slots reuse the stock commons colors
// (background / primary / text); every other slot inherits from a foundation slot by default
// (two-tier), so the whole app stays coherent and a single foundation change cascades. A slot
// only diverges once the user gives it an explicit override (stored as an Int; THEME_UNSET means
// "follow the default"). The default look is seeded to black background + yellow text/accents.

enum class ThemeGroup(@StringRes val labelRes: Int) {
    FOUNDATION(R.string.theme_group_foundation),
    SEARCH(R.string.theme_group_search),
    CONVERSATIONS(R.string.theme_group_conversations),
    THREAD(R.string.theme_group_thread),
}

enum class ThemeSlot(
    val key: String,
    val group: ThemeGroup,
    @StringRes val labelRes: Int,
    val isFoundation: Boolean = false,
) {
    // Foundation — reuse the stock commons colors (editing these repaints the whole app)
    BACKGROUND("theme_background", ThemeGroup.FOUNDATION, R.string.theme_background, isFoundation = true),
    PRIMARY("theme_primary", ThemeGroup.FOUNDATION, R.string.theme_primary, isFoundation = true),
    TEXT("theme_text", ThemeGroup.FOUNDATION, R.string.theme_text, isFoundation = true),
    TEXT_SECONDARY("theme_text_secondary", ThemeGroup.FOUNDATION, R.string.theme_text_secondary),

    // Search bar
    SEARCH_FILL("theme_search_fill", ThemeGroup.SEARCH, R.string.theme_search_fill),
    SEARCH_TEXT("theme_search_text", ThemeGroup.SEARCH, R.string.theme_search_text),
    SEARCH_HINT("theme_search_hint", ThemeGroup.SEARCH, R.string.theme_search_hint),
    SEARCH_ICON("theme_search_icon", ThemeGroup.SEARCH, R.string.theme_search_icon),
    SEARCH_BORDER("theme_search_border", ThemeGroup.SEARCH, R.string.theme_search_border),

    // Conversation list
    CONVERSATION_NAME("theme_conversation_name", ThemeGroup.CONVERSATIONS, R.string.theme_conversation_name),
    CONVERSATION_SNIPPET("theme_conversation_snippet", ThemeGroup.CONVERSATIONS, R.string.theme_conversation_snippet),
    CONVERSATION_DATE("theme_conversation_date", ThemeGroup.CONVERSATIONS, R.string.theme_conversation_date),
    CONVERSATION_UNREAD("theme_conversation_unread", ThemeGroup.CONVERSATIONS, R.string.theme_conversation_unread),
    CONVERSATION_DRAFT("theme_conversation_draft", ThemeGroup.CONVERSATIONS, R.string.theme_conversation_draft),
    CONVERSATION_PIN("theme_conversation_pin", ThemeGroup.CONVERSATIONS, R.string.theme_conversation_pin),

    // Message thread (bubbles)
    THREAD_RECEIVED_BUBBLE("theme_thread_received_bubble", ThemeGroup.THREAD, R.string.theme_thread_received_bubble),
    THREAD_RECEIVED_TEXT("theme_thread_received_text", ThemeGroup.THREAD, R.string.theme_thread_received_text),
    THREAD_SENT_BUBBLE("theme_thread_sent_bubble", ThemeGroup.THREAD, R.string.theme_thread_sent_bubble),
    THREAD_SENT_TEXT("theme_thread_sent_text", ThemeGroup.THREAD, R.string.theme_thread_sent_text),
    THREAD_DATE("theme_thread_date", ThemeGroup.THREAD, R.string.theme_thread_date),
    THREAD_STATUS("theme_thread_status", ThemeGroup.THREAD, R.string.theme_thread_status),
}

/** The effective color for a slot: the user's override if set, otherwise its inherited default. */
fun Context.themeColor(slot: ThemeSlot): Int {
    val override = config.getThemeOverride(slot.key)
    return if (override != THEME_UNSET) override else themeDefault(slot)
}

// One readable mapping of every slot to its inherited default; the long `when` is intentional.
@Suppress("CyclomaticComplexMethod")
private fun Context.themeDefault(slot: ThemeSlot): Int = when (slot) {
    // Foundation reads the stock commons colors (seeded to black/yellow on first run)
    ThemeSlot.BACKGROUND -> getProperBackgroundColor()
    ThemeSlot.PRIMARY -> getProperPrimaryColor()
    ThemeSlot.TEXT -> getProperTextColor()
    ThemeSlot.TEXT_SECONDARY -> themeColor(ThemeSlot.TEXT).adjustAlpha(SECONDARY_TEXT_ALPHA)

    // Search bar: black fill, yellow text/icon/border by inheriting foundation
    ThemeSlot.SEARCH_FILL -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.SEARCH_TEXT -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.SEARCH_HINT -> themeColor(ThemeSlot.PRIMARY).adjustAlpha(SEARCH_HINT_ALPHA)
    ThemeSlot.SEARCH_ICON -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.SEARCH_BORDER -> themeColor(ThemeSlot.PRIMARY)

    // Conversation list
    ThemeSlot.CONVERSATION_NAME -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.CONVERSATION_SNIPPET -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.CONVERSATION_DATE -> themeColor(ThemeSlot.TEXT_SECONDARY)
    ThemeSlot.CONVERSATION_UNREAD -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.CONVERSATION_DRAFT -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.CONVERSATION_PIN -> themeColor(ThemeSlot.TEXT)

    // Message thread: sent bubble follows primary (contrast text), received bubble keeps the
    // subtle elevated surface; both texts inherit foundation by default.
    ThemeSlot.THREAD_RECEIVED_BUBBLE -> resources.getColor(org.fossify.commons.R.color.activated_item_foreground, theme)
    ThemeSlot.THREAD_RECEIVED_TEXT -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.THREAD_SENT_BUBBLE -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.THREAD_SENT_TEXT -> themeColor(ThemeSlot.THREAD_SENT_BUBBLE).getContrastColor()
    ThemeSlot.THREAD_DATE -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.THREAD_STATUS -> themeColor(ThemeSlot.TEXT)
}

/** Set an explicit override for a slot. Foundation slots write through to the stock commons colors. */
fun Context.setThemeColor(slot: ThemeSlot, color: Int) {
    when (slot) {
        ThemeSlot.PRIMARY -> {
            config.isSystemThemeEnabled = false
            config.primaryColor = color
        }

        ThemeSlot.BACKGROUND -> {
            config.isSystemThemeEnabled = false
            config.backgroundColor = color
        }

        ThemeSlot.TEXT -> {
            config.isSystemThemeEnabled = false
            config.textColor = color
        }

        else -> config.setThemeOverride(slot.key, color)
    }
}

/** Revert a slot to its default (palette for the editable foundation colors, inherited otherwise). */
fun Context.resetThemeColor(slot: ThemeSlot) {
    when (slot) {
        ThemeSlot.BACKGROUND -> setThemeColor(slot, PALETTE_BLACK)
        ThemeSlot.PRIMARY, ThemeSlot.TEXT -> setThemeColor(slot, PALETTE_YELLOW)
        else -> config.clearThemeOverride(slot.key)
    }
}

/** One-time seed of the default black/yellow look across the whole app (via the stock colors). */
fun Context.seedBlackYellowThemeIfNeeded() {
    if (config.themeV1Seeded) {
        return
    }

    config.isSystemThemeEnabled = false
    config.backgroundColor = PALETTE_BLACK
    config.textColor = PALETTE_YELLOW
    config.primaryColor = PALETTE_YELLOW
    config.accentColor = PALETTE_YELLOW
    config.themeV1Seeded = true
}
