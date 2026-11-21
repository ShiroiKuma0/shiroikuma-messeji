package org.fossify.messages.extensions

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Menu
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.withDialogBorder
import org.fossify.messages.helpers.DIALOG_BORDER_WIDTH_DEFAULT
import org.fossify.messages.helpers.EXIM_WARN_COLOR
import org.fossify.messages.R
import org.fossify.messages.helpers.PALETTE_BLACK
import org.fossify.messages.helpers.PALETTE_YELLOW
import org.fossify.messages.helpers.PALETTE_YELLOW_LEGACY
import org.fossify.messages.helpers.THEME_UNSET

private const val SECONDARY_TEXT_ALPHA = 0.6f
private const val SEARCH_HINT_ALPHA = 0.5f
private const val DIALOG_CORNER_DP = 16f
private const val SWITCH_OFF_BLEND = 0.55f // a switch that is off: this far from the background to the accent
private const val SETTINGS_NOTE_DIM_GOLD = 0xFFC8C800.toInt()
private const val ROUTE_RED = 0xFFFF0000.toInt()
private const val ROUTE_BLUE = 0xFF2962FF.toInt()
private const val MENU_GREEN = 0xFF00FF00.toInt()
private const val MENU_BLUE = 0xFF4D94FF.toInt() // pure blue vanishes as a thin glyph on black
private const val MENU_CYAN = 0xFF00FFFF.toInt()
private const val MENU_ORANGE = 0xFFFF8000.toInt()
private const val MENU_MAGENTA = 0xFFFF00FF.toInt()
private const val THREAD_DAY_DIVIDER_BLEND = 0.5f // the day divider: halfway from the background to the accent

// Granular, per-element theming for 白い熊 メッセージ.
//
// Each [ThemeSlot] is one customizable color. Foundation slots reuse the stock commons colors
// (background / primary / text); every other slot inherits from a foundation slot by default
// (two-tier), so the whole app stays coherent and a single foundation change cascades. A slot
// only diverges once the user gives it an explicit override (stored as an Int; THEME_UNSET means
// "follow the default"). The default look is seeded to black background + yellow text/accents.

// Top-level sections shown on the 白い熊 メッセージ UI page (accent header + divider).
enum class ThemeSection(@StringRes val labelRes: Int) {
    FOUNDATION(R.string.theme_section_foundation),
    CONVERSATIONS(R.string.theme_section_conversations),
    THREAD(R.string.theme_section_thread),
    SEARCH(R.string.theme_section_search),
    SETTINGS(R.string.theme_section_settings),
}

// Subgroups within a section. A subgroup header is only drawn when its section has more than one
// group; single-group sections render their rows directly under the section header.
enum class ThemeGroup(val section: ThemeSection, @StringRes val labelRes: Int) {
    FOUNDATION(ThemeSection.FOUNDATION, R.string.theme_section_foundation),
    CONVERSATION(ThemeSection.CONVERSATIONS, R.string.theme_section_conversations),
    THREAD_RECEIVED(ThemeSection.THREAD, R.string.theme_group_thread_received),
    THREAD_SENT(ThemeSection.THREAD, R.string.theme_group_thread_sent),
    THREAD_META(ThemeSection.THREAD, R.string.theme_group_thread_meta),
    ROUTES(ThemeSection.THREAD, R.string.call_routes),
    SEARCH(ThemeSection.SEARCH, R.string.theme_section_search),
    MENUS(ThemeSection.SEARCH, R.string.theme_group_menus),
    SETTINGS(ThemeSection.SETTINGS, R.string.theme_section_settings),
}

enum class ThemeSlot(
    val key: String,
    val group: ThemeGroup,
    @StringRes val labelRes: Int,
    val isFoundation: Boolean = false,
    // hasFont = true for concrete text views (family / weight / size are configurable per element)
    val hasFont: Boolean = false,
) {
    // Foundation — reuse the stock commons colors (editing these repaints the whole app)
    BACKGROUND("theme_background", ThemeGroup.FOUNDATION, R.string.theme_background, isFoundation = true),
    PRIMARY("theme_primary", ThemeGroup.FOUNDATION, R.string.theme_primary, isFoundation = true),
    TEXT("theme_text", ThemeGroup.FOUNDATION, R.string.theme_text, isFoundation = true),
    TEXT_SECONDARY("theme_text_secondary", ThemeGroup.FOUNDATION, R.string.theme_text_secondary),

    // The dim gold of explanations and of the rule beside them; a switch that is off (opaque, never faded)
    SETTINGS_NOTE("theme_settings_note", ThemeGroup.FOUNDATION, R.string.theme_settings_note),
    SWITCH_OFF("theme_switch_off", ThemeGroup.FOUNDATION, R.string.theme_switch_off),

    // Conversation list
    CONVERSATION_NAME(
        "theme_conversation_name", ThemeGroup.CONVERSATION, R.string.theme_conversation_name, hasFont = true
    ),
    CONVERSATION_SNIPPET(
        "theme_conversation_snippet", ThemeGroup.CONVERSATION, R.string.theme_conversation_snippet, hasFont = true
    ),
    CONVERSATION_DATE(
        "theme_conversation_date", ThemeGroup.CONVERSATION, R.string.theme_conversation_date, hasFont = true
    ),
    CONVERSATION_UNREAD("theme_conversation_unread", ThemeGroup.CONVERSATION, R.string.theme_conversation_unread),
    CONVERSATION_DRAFT(
        "theme_conversation_draft", ThemeGroup.CONVERSATION, R.string.theme_conversation_draft, hasFont = true
    ),
    CONVERSATION_PIN("theme_conversation_pin", ThemeGroup.CONVERSATION, R.string.theme_conversation_pin),

    // Day headers between the conversations (今日 / 昨日 / 令和…), as in 白い熊 電話's call log
    CONVERSATION_DAY_DATE(
        "theme_conversation_day_date", ThemeGroup.CONVERSATION, R.string.theme_conversation_day_date, hasFont = true
    ),
    CONVERSATION_DAY_DIVIDER(
        "theme_conversation_day_divider", ThemeGroup.CONVERSATION, R.string.theme_conversation_day_divider
    ),
    CONVERSATION_DATE_UNDERLINE(
        "theme_conversation_date_underline", ThemeGroup.CONVERSATION, R.string.theme_conversation_date_underline
    ),

    // Message thread — received bubbles
    THREAD_RECEIVED_BUBBLE(
        "theme_thread_received_bubble", ThemeGroup.THREAD_RECEIVED, R.string.theme_thread_received_bubble
    ),
    THREAD_RECEIVED_FRAME(
        "theme_thread_received_frame", ThemeGroup.THREAD_RECEIVED, R.string.theme_thread_received_frame
    ),
    THREAD_RECEIVED_TEXT(
        "theme_thread_received_text", ThemeGroup.THREAD_RECEIVED, R.string.theme_thread_received_text, hasFont = true
    ),

    // Message thread — sent bubbles
    THREAD_SENT_BUBBLE("theme_thread_sent_bubble", ThemeGroup.THREAD_SENT, R.string.theme_thread_sent_bubble),
    THREAD_SENT_FRAME("theme_thread_sent_frame", ThemeGroup.THREAD_SENT, R.string.theme_thread_sent_frame),
    THREAD_SENT_TEXT(
        "theme_thread_sent_text", ThemeGroup.THREAD_SENT, R.string.theme_thread_sent_text, hasFont = true
    ),

    // Message thread — status & time
    THREAD_DATE("theme_thread_date", ThemeGroup.THREAD_META, R.string.theme_thread_date, hasFont = true),
    THREAD_STATUS("theme_thread_status", ThemeGroup.THREAD_META, R.string.theme_thread_status, hasFont = true),
    THREAD_DAY_DATE(
        "theme_thread_day_date", ThemeGroup.THREAD_META, R.string.theme_thread_day_date, hasFont = true
    ),
    THREAD_DAY_DIVIDER("theme_thread_day_divider", ThemeGroup.THREAD_META, R.string.theme_thread_day_divider),

    // Call routes: the badges on the conversation's call button and in the route picker. A 臨 account
    // wears its own colours from 臨電話 when it sends them; these two are the fallback.
    ROUTE_SIM1("theme_route_sim1", ThemeGroup.ROUTES, R.string.route_sim1_color),
    ROUTE_SIM2("theme_route_sim2", ThemeGroup.ROUTES, R.string.route_sim2_color),
    ROUTE_SIM_TEXT("theme_route_sim_text", ThemeGroup.ROUTES, R.string.route_sim_text_color),
    ROUTE_RIN("theme_route_rin", ThemeGroup.ROUTES, R.string.route_rin_color),
    ROUTE_RIN_TEXT("theme_route_rin_text", ThemeGroup.ROUTES, R.string.route_rin_text_color),

    // Search bar
    SEARCH_FILL("theme_search_fill", ThemeGroup.SEARCH, R.string.theme_search_fill),
    SEARCH_TEXT("theme_search_text", ThemeGroup.SEARCH, R.string.theme_search_text, hasFont = true),
    SEARCH_HINT("theme_search_hint", ThemeGroup.SEARCH, R.string.theme_search_hint),
    SEARCH_ICON("theme_search_icon", ThemeGroup.SEARCH, R.string.theme_search_icon),
    SEARCH_BORDER("theme_search_border", ThemeGroup.SEARCH, R.string.theme_search_border),
    SEARCH_MENU_ICON("theme_search_menu", ThemeGroup.SEARCH, R.string.theme_search_menu),
    MENU_BACKGROUND("theme_menu_background", ThemeGroup.MENUS, R.string.theme_menu_background),
    MENU_TEXT("theme_menu_text", ThemeGroup.MENUS, R.string.theme_menu_text),
    MENU_WARNING("theme_menu_warning", ThemeGroup.MENUS, R.string.theme_menu_warning),

    // What a row does, told by the colour of its icon — in menus, panels and on the UI page alike.
    // Deleting rows use MENU_WARNING.
    MENU_ICON_CALL("theme_menu_icon_call", ThemeGroup.MENUS, R.string.menu_icon_call),
    MENU_ICON_MESSAGE("theme_menu_icon_message", ThemeGroup.MENUS, R.string.menu_icon_message),
    MENU_ICON_CONTACT("theme_menu_icon_contact", ThemeGroup.MENUS, R.string.menu_icon_contact),
    MENU_ICON_SHARE("theme_menu_icon_share", ThemeGroup.MENUS, R.string.menu_icon_share),
    MENU_ICON_VIEW("theme_menu_icon_view", ThemeGroup.MENUS, R.string.menu_icon_view),
    MENU_ICON_BLOCK("theme_menu_icon_block", ThemeGroup.MENUS, R.string.menu_icon_block),

    // Settings screen chrome
    SETTINGS_TITLE("theme_settings_title", ThemeGroup.SETTINGS, R.string.theme_settings_title),
    SETTINGS_BACK("theme_settings_back", ThemeGroup.SETTINGS, R.string.theme_settings_back),
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
    ThemeSlot.SETTINGS_NOTE -> SETTINGS_NOTE_DIM_GOLD
    ThemeSlot.SWITCH_OFF -> ColorUtils.blendARGB(
        themeColor(ThemeSlot.BACKGROUND), themeColor(ThemeSlot.PRIMARY), SWITCH_OFF_BLEND
    )

    // Search bar: black fill, yellow text/icon/border by inheriting foundation
    ThemeSlot.SEARCH_FILL -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.SEARCH_TEXT -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.SEARCH_HINT -> themeColor(ThemeSlot.PRIMARY).adjustAlpha(SEARCH_HINT_ALPHA)
    ThemeSlot.SEARCH_ICON -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.SEARCH_BORDER -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.SEARCH_MENU_ICON -> themeColor(ThemeSlot.PRIMARY)
    // Overflow menu popup: background follows the app background, text follows the app text colour
    ThemeSlot.MENU_BACKGROUND -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.MENU_TEXT -> themeColor(ThemeSlot.TEXT)
    // Destructive menu rows (delete, empty): frame, icon and label
    ThemeSlot.MENU_WARNING -> EXIM_WARN_COLOR
    ThemeSlot.MENU_ICON_CALL -> MENU_GREEN
    ThemeSlot.MENU_ICON_MESSAGE -> MENU_BLUE
    ThemeSlot.MENU_ICON_CONTACT -> MENU_CYAN
    ThemeSlot.MENU_ICON_SHARE -> MENU_ORANGE
    ThemeSlot.MENU_ICON_VIEW -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.MENU_ICON_BLOCK -> MENU_MAGENTA

    // Settings screen: the toolbar sits on the primary colour, so title/arrow contrast it by default
    ThemeSlot.SETTINGS_TITLE -> themeColor(ThemeSlot.PRIMARY).getContrastColor()
    ThemeSlot.SETTINGS_BACK -> themeColor(ThemeSlot.PRIMARY).getContrastColor()

    // Conversation list
    ThemeSlot.CONVERSATION_NAME -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.CONVERSATION_SNIPPET -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.CONVERSATION_DATE -> themeColor(ThemeSlot.TEXT_SECONDARY)
    ThemeSlot.CONVERSATION_UNREAD -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.CONVERSATION_DRAFT -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.CONVERSATION_PIN -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.CONVERSATION_DAY_DATE -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.CONVERSATION_DAY_DIVIDER -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.CONVERSATION_DATE_UNDERLINE -> themeColor(ThemeSlot.PRIMARY)

    // Message thread: bubbles are traced, never filled — the frame follows primary, the inside the
    // background, and both texts inherit foundation by default.
    ThemeSlot.THREAD_RECEIVED_BUBBLE -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.THREAD_RECEIVED_FRAME -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.THREAD_RECEIVED_TEXT -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.THREAD_SENT_BUBBLE -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.THREAD_SENT_FRAME -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.THREAD_SENT_TEXT -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.THREAD_DATE -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.THREAD_STATUS -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.ROUTE_SIM1 -> ROUTE_RED
    ThemeSlot.ROUTE_SIM2 -> ROUTE_BLUE
    ThemeSlot.ROUTE_SIM_TEXT -> PALETTE_YELLOW
    ThemeSlot.ROUTE_RIN -> PALETTE_YELLOW
    ThemeSlot.ROUTE_RIN_TEXT -> ROUTE_BLUE
    ThemeSlot.THREAD_DAY_DATE -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.THREAD_DAY_DIVIDER -> ColorUtils.blendARGB(
        themeColor(ThemeSlot.BACKGROUND), themeColor(ThemeSlot.PRIMARY), THREAD_DAY_DIVIDER_BLEND
    )
}

/**
 * Paint every item title of a contextual-action-bar menu in the menu-text colour. Commons draws that
 * bar's overflow popup black with a yellow frame but leaves the titles in the theme's white, and the
 * popup's text colour has no runtime hook — a span on the title is the one thing it honours.
 */
fun Context.colorMenuTitles(menu: Menu) {
    val color = themeColor(ThemeSlot.MENU_TEXT)
    for (i in 0 until menu.size()) {
        val item = menu.getItem(i)
        val title = item.title?.toString() ?: continue
        item.title = SpannableString(title).apply {
            setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
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
    syncFamilyLook()
}

/** Revert a slot to its default (palette for the editable foundation colors, inherited otherwise). */
fun Context.resetThemeColor(slot: ThemeSlot) {
    when (slot) {
        ThemeSlot.BACKGROUND -> setThemeColor(slot, PALETTE_BLACK)
        ThemeSlot.PRIMARY, ThemeSlot.TEXT -> setThemeColor(slot, PALETTE_YELLOW)
        else -> config.clearThemeOverride(slot.key)
    }
    syncFamilyLook()
}

/**
 * The black-yellow look Commons draws for us (sk12–sk17): dialog buttons as outlined pills, the
 * outlined switch, the outlined floating button. Commons keeps its own copies of two of our roles —
 * the red of a destructive button and the colour of a switch that is off — so they are written
 * through here, on every launch and after every colour change.
 */
fun Context.syncFamilyLook() {
    config.pillDialogButtons = true
    config.dialogWarningColor = themeColor(ThemeSlot.MENU_WARNING)
    config.switchOffColor = themeColor(ThemeSlot.SWITCH_OFF)
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

/**
 * One-time seed of Commons' dialog accent border (sk3): a 2 dp yellow frame round every dialog, so a
 * black dialog stands out from the black list behind it. The keys live in Commons' BaseConfig, read by
 * its patched setupDialogStuff; written once so existing installs pick them up too.
 */
fun Context.seedDialogStyleIfNeeded() {
    if (config.dialogStyleSeeded) {
        return
    }

    config.dialogBorderColor = PALETTE_YELLOW
    config.dialogBorderWidth = DIALOG_BORDER_WIDTH_DEFAULT
    config.dialogStyleSeeded = true
}

/**
 * Give a platform dialog that bypasses Commons' setupDialogStuff (the date and time pickers) the same
 * window as every other dialog: theme background, rounded corners and the accent border. Call after show().
 */
fun Dialog.applyDialogBorder() {
    val background = GradientDrawable().apply {
        cornerRadius = DIALOG_CORNER_DP * context.resources.displayMetrics.density
        setColor(context.getProperBackgroundColor())
    }
    window?.setBackgroundDrawable(context.withDialogBorder(background))
}

/** [PALETTE_YELLOW] with [color]'s alpha if its RGB is the legacy material yellow, else null. */
private fun toPureYellowOrNull(color: Int): Int? = if (color and 0xFFFFFF == PALETTE_YELLOW_LEGACY and 0xFFFFFF) {
    (color and 0xFF000000.toInt()) or (PALETTE_YELLOW and 0xFFFFFF)
} else {
    null
}

/** One-time rewrite of every persisted legacy material yellow to the pure [PALETTE_YELLOW]. */
fun Context.migratePureYellowIfNeeded() {
    if (config.pureYellowMigrated) {
        return
    }

    toPureYellowOrNull(config.backgroundColor)?.let { config.backgroundColor = it }
    toPureYellowOrNull(config.textColor)?.let { config.textColor = it }
    toPureYellowOrNull(config.primaryColor)?.let { config.primaryColor = it }
    toPureYellowOrNull(config.accentColor)?.let { config.accentColor = it }
    ThemeSlot.entries.forEach { slot ->
        val override = config.getThemeOverride(slot.key)
        if (override != THEME_UNSET) {
            toPureYellowOrNull(override)?.let { config.setThemeOverride(slot.key, it) }
        }
    }
    config.pureYellowMigrated = true
}
