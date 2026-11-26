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
import org.fossify.commons.extensions.darkenColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.withDialogBorder
import org.fossify.commons.helpers.UiRole
import org.fossify.commons.helpers.resetUiColor
import org.fossify.commons.helpers.setUiColor
import org.fossify.commons.helpers.uiColor
import org.fossify.messages.helpers.DIALOG_BORDER_WIDTH_DEFAULT
import org.fossify.messages.R
import org.fossify.messages.helpers.PALETTE_BLACK
import org.fossify.messages.helpers.PALETTE_YELLOW
import org.fossify.messages.helpers.PALETTE_YELLOW_LEGACY
import org.fossify.messages.helpers.THEME_UNSET

private const val SECONDARY_TEXT_ALPHA = 0.6f
private const val SEARCH_HINT_ALPHA = 0.5f
private const val DIALOG_CORNER_DP = 16f
private const val SWITCH_OFF_BLEND = 0.55f // a switch that is off: this far from the background to the accent
private const val ROW_DIVIDER_BLEND = 0.55f // the line between two rows of one day: dim, so the day's line stays the strong one
private const val SETTINGS_NOTE_DIM_GOLD = 0xFFC8C800.toInt()
private const val ROUTE_RED = 0xFFFF0000.toInt()
private const val ROUTE_BLUE = 0xFF2962FF.toInt()
// The menu icon defaults of 2026-11-23, off the colours 白い熊 cannot tell apart: no pure green, no
// cyan, no magenta, and the two blues far apart. The same values colour the section icons of the UI page.
private const val MENU_GREEN = 0xFF00A550.toInt() // heavy green
private const val MENU_BLUE = 0xFF0066FF.toInt() // heavy blue; pure blue vanishes as a thin glyph on black
private const val MENU_CYAN = 0xFF00B0FF.toInt() // light blue
private const val MENU_ORANGE = 0xFFFF8000.toInt()
private const val MENU_MAGENTA = 0xFFFF3D7F.toInt() // rose
private const val SECTION_RED = 0xFFFF0000.toInt()

// How much of the selection colour lies over a selected message: the text under it stays readable.
private const val SELECTION_VEIL_ALPHA = 0x48
private const val MENU_DIVIDER_BLEND = 0.7f // dividers: an opaque darker accent
private const val COMPOSE_BAND_BLEND = 0.11f // the compose band: a faint tint of the accent
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
    NEW_CONVERSATION(R.string.theme_section_new_conversation),
    SETTINGS(R.string.theme_section_settings),
    TEXTS(R.string.theme_section_texts),
    BUTTONS(R.string.theme_section_buttons),
    DIALOGS(R.string.theme_section_dialogs),
    SWITCHES(R.string.theme_section_switches),
    MENUS(R.string.theme_group_menus),
    LISTS(R.string.theme_section_lists),
    BARS(R.string.theme_section_bars),
}

// Subgroups within a section. A subgroup header is only drawn when its section has more than one
// group; single-group sections render their rows directly under the section header.
enum class ThemeGroup(val section: ThemeSection, @StringRes val labelRes: Int) {
    FOUNDATION(ThemeSection.FOUNDATION, R.string.theme_section_foundation),
    CONVERSATION(ThemeSection.CONVERSATIONS, R.string.theme_section_conversations),
    THREAD_RECEIVED(ThemeSection.THREAD, R.string.theme_group_thread_received),
    THREAD_SENT(ThemeSection.THREAD, R.string.theme_group_thread_sent),
    THREAD_META(ThemeSection.THREAD, R.string.theme_group_thread_meta),
    THREAD_HEADER(ThemeSection.THREAD, R.string.theme_group_thread_header),
    THREAD_AVATAR(ThemeSection.THREAD, R.string.theme_group_thread_avatar),
    THREAD_COMPOSE(ThemeSection.THREAD, R.string.compose_section),
    THREAD_ATTACH(ThemeSection.THREAD, R.string.theme_group_thread_attach),
    ROUTES(ThemeSection.THREAD, R.string.call_routes),
    SEARCH(ThemeSection.SEARCH, R.string.theme_section_search),
    NEW_CONVERSATION(ThemeSection.NEW_CONVERSATION, R.string.theme_section_new_conversation),
    SETTINGS(ThemeSection.SETTINGS, R.string.theme_section_settings),
    UI_PAGE(ThemeSection.SETTINGS, R.string.theme_group_ui_page),
    SECTION_ICONS(ThemeSection.SETTINGS, R.string.theme_sub_section_icons),
    TEXTS(ThemeSection.TEXTS, R.string.theme_section_texts),
    BUTTONS(ThemeSection.BUTTONS, R.string.theme_section_buttons),
    DIALOGS(ThemeSection.DIALOGS, R.string.theme_section_dialogs),
    SWITCHES(ThemeSection.SWITCHES, R.string.theme_section_switches),
    MENUS(ThemeSection.MENUS, R.string.theme_group_menus),
    LISTS(ThemeSection.LISTS, R.string.theme_section_lists),
    BARS(ThemeSection.BARS, R.string.theme_section_bars),
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
    SETTINGS_NOTE("theme_settings_note", ThemeGroup.UI_PAGE, R.string.theme_settings_note),
    SWITCH_OFF("theme_switch_off", ThemeGroup.SWITCHES, R.string.theme_switch_off),

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
    // The thin line between two conversations of one day, as between two calls in 白い熊 臨電話
    CONVERSATION_ROW_DIVIDER(
        "theme_conversation_row_divider", ThemeGroup.CONVERSATION, R.string.theme_conversation_row_divider
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
    SEARCH_GROUND("theme_search_ground", ThemeGroup.SEARCH, R.string.theme_search_ground),
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

    // One role per item 白い熊 can see as a separate thing; each inherits a foundation colour until set.
    BAR_GROUND("theme_bar_ground", ThemeGroup.BARS, R.string.theme_bar_ground),
    STATUS_BAR("theme_status_bar", ThemeGroup.BARS, R.string.theme_status_bar),
    NAV_BAR("theme_nav_bar", ThemeGroup.BARS, R.string.theme_nav_bar),
    TAB_INK("theme_tab_ink", ThemeGroup.BARS, R.string.theme_tab_ink),
    TAB_GROUND("theme_tab_ground", ThemeGroup.BARS, R.string.theme_tab_ground),
    PROGRESS("theme_progress", ThemeGroup.BARS, R.string.theme_progress),
    NOTIFICATION_ACCENT("theme_notification_accent", ThemeGroup.BARS, R.string.theme_notification_accent),
    HEADING("theme_heading", ThemeGroup.TEXTS, R.string.theme_heading),
    HEADING_UNDERLINE("theme_heading_underline", ThemeGroup.TEXTS, R.string.theme_heading_underline),
    LINK("theme_link", ThemeGroup.TEXTS, R.string.theme_link),
    PLACEHOLDER("theme_placeholder", ThemeGroup.TEXTS, R.string.theme_placeholder),
    INPUT_TEXT("theme_input_text", ThemeGroup.TEXTS, R.string.theme_input_text),
    INPUT_HINT("theme_input_hint", ThemeGroup.TEXTS, R.string.theme_input_hint),
    INPUT_FRAME("theme_input_frame", ThemeGroup.TEXTS, R.string.theme_input_frame),
    CURSOR("theme_cursor", ThemeGroup.TEXTS, R.string.theme_cursor),
    BUTTON_TEXT("theme_button_text", ThemeGroup.BUTTONS, R.string.theme_button_text),
    BUTTON_FRAME("theme_button_frame", ThemeGroup.BUTTONS, R.string.theme_button_frame),
    BUTTON_GROUND("theme_button_ground", ThemeGroup.BUTTONS, R.string.theme_button_ground),
    BUTTON_WARNING("theme_button_warning", ThemeGroup.BUTTONS, R.string.theme_button_warning),
    FAB_ICON("theme_fab_icon", ThemeGroup.BUTTONS, R.string.theme_fab_icon),
    FAB_FRAME("theme_fab_frame", ThemeGroup.BUTTONS, R.string.theme_fab_frame),
    FAB_GROUND("theme_fab_ground", ThemeGroup.BUTTONS, R.string.theme_fab_ground),
    DIALOG_GROUND("theme_dialog_ground", ThemeGroup.DIALOGS, R.string.theme_dialog_ground),
    DIALOG_FRAME("theme_dialog_frame", ThemeGroup.DIALOGS, R.string.theme_dialog_frame),
    DIALOG_TITLE("theme_dialog_title", ThemeGroup.DIALOGS, R.string.theme_dialog_title),
    DIALOG_TEXT("theme_dialog_text", ThemeGroup.DIALOGS, R.string.theme_dialog_text),
    DIALOG_NOTE("theme_dialog_note", ThemeGroup.DIALOGS, R.string.theme_dialog_note),
    DIALOG_WARNING("theme_dialog_warning", ThemeGroup.DIALOGS, R.string.theme_dialog_warning),
    SWITCH_ON("theme_switch_on", ThemeGroup.SWITCHES, R.string.theme_switch_on),
    CHECKBOX("theme_checkbox", ThemeGroup.SWITCHES, R.string.theme_checkbox),
    RADIO("theme_radio", ThemeGroup.SWITCHES, R.string.theme_radio),
    MENU_FRAME("theme_menu_frame", ThemeGroup.MENUS, R.string.theme_menu_frame),
    MENU_DIVIDER("theme_menu_divider", ThemeGroup.MENUS, R.string.theme_menu_divider),
    LIST_TEXT("theme_list_text", ThemeGroup.LISTS, R.string.theme_list_text),
    LIST_SECONDARY("theme_list_secondary", ThemeGroup.LISTS, R.string.theme_list_secondary),
    LIST_ICON("theme_list_icon", ThemeGroup.LISTS, R.string.theme_list_icon),
    LIST_DIVIDER("theme_list_divider", ThemeGroup.LISTS, R.string.theme_list_divider),
    LIST_SELECTION("theme_list_selection", ThemeGroup.LISTS, R.string.theme_list_selection),
    FASTSCROLL_TEXT("theme_fastscroll_text", ThemeGroup.LISTS, R.string.theme_fastscroll_text),
    FASTSCROLL_THUMB("theme_fastscroll_thumb", ThemeGroup.LISTS, R.string.theme_fastscroll_thumb),
    FASTSCROLL_THUMB_TEXT("theme_fastscroll_thumb_text", ThemeGroup.LISTS, R.string.theme_fastscroll_thumb_text),
    CONVERSATION_UNREAD_TEXT("theme_conversation_unread_text", ThemeGroup.CONVERSATION, R.string.theme_conversation_unread_text),
    UNDO_TEXT("theme_undo_text", ThemeGroup.CONVERSATION, R.string.theme_undo_text),
    UNDO_FRAME("theme_undo_frame", ThemeGroup.CONVERSATION, R.string.theme_undo_frame),
    UNDO_GROUND("theme_undo_ground", ThemeGroup.CONVERSATION, R.string.theme_undo_ground),
    UNFILED_MARK("theme_unfiled_mark", ThemeGroup.CONVERSATION, R.string.theme_unfiled_mark),
    SWIPE_FILL("theme_swipe_fill", ThemeGroup.CONVERSATION, R.string.theme_swipe_fill),
    SWIPE_INK("theme_swipe_ink", ThemeGroup.CONVERSATION, R.string.theme_swipe_ink),
    HEADER_GROUND("theme_header_ground", ThemeGroup.THREAD_HEADER, R.string.theme_header_ground),
    HEADER_FRAME("theme_header_frame", ThemeGroup.THREAD_HEADER, R.string.theme_header_frame),
    HEADER_NAME("theme_header_name", ThemeGroup.THREAD_HEADER, R.string.theme_header_name),
    HEADER_NUMBER("theme_header_number", ThemeGroup.THREAD_HEADER, R.string.theme_header_number),
    HEADER_ICON("theme_header_icon", ThemeGroup.THREAD_HEADER, R.string.theme_header_icon),
    AVATAR_HALO("theme_avatar_halo", ThemeGroup.THREAD_AVATAR, R.string.theme_avatar_halo),
    AVATAR_GLYPH("theme_avatar_glyph", ThemeGroup.THREAD_AVATAR, R.string.theme_avatar_glyph),
    THREAD_FAILED("theme_thread_failed", ThemeGroup.THREAD_META, R.string.theme_thread_failed),
    COMPOSE_BAND("theme_compose_band", ThemeGroup.THREAD_COMPOSE, R.string.theme_compose_band),
    COMPOSE_LINE("theme_compose_line", ThemeGroup.THREAD_COMPOSE, R.string.theme_compose_line),
    COMPOSE_FIELD_GROUND("theme_compose_field_ground", ThemeGroup.THREAD_COMPOSE, R.string.theme_compose_field_ground),
    COMPOSE_FIELD_FRAME("theme_compose_field_frame", ThemeGroup.THREAD_COMPOSE, R.string.theme_compose_field_frame),
    COMPOSE_TEXT("theme_compose_text", ThemeGroup.THREAD_COMPOSE, R.string.theme_compose_text),
    COMPOSE_HINT("theme_compose_hint", ThemeGroup.THREAD_COMPOSE, R.string.theme_compose_hint),
    COMPOSE_ICON("theme_compose_icon", ThemeGroup.THREAD_COMPOSE, R.string.theme_compose_icon),
    COMPOSE_SEND("theme_compose_send", ThemeGroup.THREAD_COMPOSE, R.string.theme_compose_send),
    COMPOSE_COUNTER("theme_compose_counter", ThemeGroup.THREAD_COMPOSE, R.string.theme_compose_counter),
    ATTACH_GROUND("theme_attach_ground", ThemeGroup.THREAD_ATTACH, R.string.theme_attach_ground),
    ATTACH_LABEL("theme_attach_label", ThemeGroup.THREAD_ATTACH, R.string.theme_attach_label),
    ATTACH_TILE_GROUND("theme_attach_tile_ground", ThemeGroup.THREAD_ATTACH, R.string.theme_attach_tile_ground),
    ATTACH_PREVIEW_GROUND("theme_attach_preview_ground", ThemeGroup.THREAD_ATTACH, R.string.theme_attach_preview_ground),
    ATTACH_PREVIEW_TEXT("theme_attach_preview_text", ThemeGroup.THREAD_ATTACH, R.string.theme_attach_preview_text),
    ATTACH_PREVIEW_ICON("theme_attach_preview_icon", ThemeGroup.THREAD_ATTACH, R.string.theme_attach_preview_icon),
    SCHEDULED_GROUND("theme_scheduled_ground", ThemeGroup.THREAD_ATTACH, R.string.theme_scheduled_ground),
    SCHEDULED_TEXT("theme_scheduled_text", ThemeGroup.THREAD_ATTACH, R.string.theme_scheduled_text),
    SIM_CHOICE_TEXT("theme_sim_choice_text", ThemeGroup.THREAD_ATTACH, R.string.theme_sim_choice_text),
    SIM_CHOICE_CURRENT("theme_sim_choice_current", ThemeGroup.THREAD_ATTACH, R.string.theme_sim_choice_current),
    NEWCONV_LABEL("theme_newconv_label", ThemeGroup.NEW_CONVERSATION, R.string.theme_newconv_label),
    NEWCONV_CONFIRM("theme_newconv_confirm", ThemeGroup.NEW_CONVERSATION, R.string.theme_newconv_confirm),
    NEWCONV_NAME("theme_newconv_name", ThemeGroup.NEW_CONVERSATION, R.string.theme_newconv_name),
    NEWCONV_NUMBER("theme_newconv_number", ThemeGroup.NEW_CONVERSATION, R.string.theme_newconv_number),
    SELECTED_CHIP_GROUND("theme_selected_chip_ground", ThemeGroup.NEW_CONVERSATION, R.string.theme_selected_chip_ground),
    SELECTED_CHIP_TEXT("theme_selected_chip_text", ThemeGroup.NEW_CONVERSATION, R.string.theme_selected_chip_text),
    ROW_LABEL("theme_row_label", ThemeGroup.UI_PAGE, R.string.theme_row_label),
    ROW_VALUE("theme_row_value", ThemeGroup.UI_PAGE, R.string.theme_row_value),
    CHIP_TEXT("theme_chip_text", ThemeGroup.UI_PAGE, R.string.theme_chip_text),
    CHIP_FRAME("theme_chip_frame", ThemeGroup.UI_PAGE, R.string.theme_chip_frame),
    SECTION_DIVIDER("theme_section_divider", ThemeGroup.UI_PAGE, R.string.theme_section_divider),

    // The icon on each heading and chip of the UI page, coloured by what kind of thing its section is.
    SECTION_BASE("theme_section_base", ThemeGroup.SECTION_ICONS, R.string.theme_section_base),
    SECTION_CONTROLS("theme_section_controls", ThemeGroup.SECTION_ICONS, R.string.theme_section_controls),
    SECTION_WORDS("theme_section_words", ThemeGroup.SECTION_ICONS, R.string.theme_section_words),
    SECTION_FRAME("theme_section_frame", ThemeGroup.SECTION_ICONS, R.string.theme_section_frame),
    SECTION_REACH("theme_section_reach", ThemeGroup.SECTION_ICONS, R.string.theme_section_reach),
    SECTION_WARNING("theme_section_warning", ThemeGroup.SECTION_ICONS, R.string.theme_section_warning),
    SECTION_FLASH("theme_section_flash", ThemeGroup.SECTION_ICONS, R.string.theme_section_flash),

    // What lies over a selected message of a conversation, and behind selected text: see-through, never a slab.
    SELECTION_VEIL("theme_selection_veil", ThemeGroup.LISTS, R.string.theme_selection_veil),
}


/**
 * The commons role a slot stands for. Widgets and dialogs the commons draws read its roles (UiRole,
 * stored under "ui_role_…"); a slot that names the same item keeps no value of its own and reads and
 * writes that role, so one item has one colour whoever paints it.
 */
val ThemeSlot.uiRole: UiRole?
    get() = when (this) {
        ThemeSlot.LINK -> UiRole.TEXT_LINK
        ThemeSlot.INPUT_TEXT -> UiRole.FIELD_TEXT
        ThemeSlot.INPUT_HINT -> UiRole.FIELD_HINT
        ThemeSlot.INPUT_FRAME -> UiRole.FIELD_LINE
        ThemeSlot.CURSOR -> UiRole.FIELD_CURSOR
        ThemeSlot.BUTTON_TEXT -> UiRole.BUTTON_TEXT
        ThemeSlot.BUTTON_FRAME -> UiRole.BUTTON_FRAME
        ThemeSlot.BUTTON_GROUND -> UiRole.BUTTON_GROUND
        ThemeSlot.BUTTON_WARNING -> UiRole.BUTTON_DESTRUCTIVE
        ThemeSlot.FAB_ICON -> UiRole.FAB_ICON
        ThemeSlot.FAB_FRAME -> UiRole.FAB_FRAME
        ThemeSlot.FAB_GROUND -> UiRole.FAB_GROUND
        ThemeSlot.DIALOG_GROUND -> UiRole.DIALOG_GROUND
        ThemeSlot.DIALOG_FRAME -> UiRole.DIALOG_FRAME
        ThemeSlot.DIALOG_TITLE -> UiRole.DIALOG_TITLE
        ThemeSlot.DIALOG_TEXT -> UiRole.DIALOG_TEXT
        ThemeSlot.SWITCH_ON -> UiRole.SWITCH_ON_OUTLINE
        ThemeSlot.SWITCH_OFF -> UiRole.SWITCH_OFF_OUTLINE
        ThemeSlot.CHECKBOX -> UiRole.CHECK_ON
        ThemeSlot.RADIO -> UiRole.RADIO_ON
        ThemeSlot.MENU_BACKGROUND -> UiRole.MENU_GROUND
        ThemeSlot.MENU_TEXT -> UiRole.MENU_LABEL
        ThemeSlot.MENU_FRAME -> UiRole.MENU_ROW_FRAME
        ThemeSlot.MENU_DIVIDER -> UiRole.MENU_DIVIDER
        ThemeSlot.MENU_WARNING -> UiRole.MENU_DESTRUCTIVE
        ThemeSlot.MENU_ICON_VIEW -> UiRole.MENU_ICON_VIEW
        ThemeSlot.MENU_ICON_SHARE -> UiRole.MENU_ICON_SHARE
        ThemeSlot.MENU_ICON_BLOCK -> UiRole.MENU_ICON_BLOCK
        ThemeSlot.LIST_DIVIDER -> UiRole.LIST_DIVIDER
        ThemeSlot.LIST_SELECTION -> UiRole.LIST_SELECTED
        ThemeSlot.FASTSCROLL_THUMB -> UiRole.SCROLL_THUMB
        ThemeSlot.FASTSCROLL_THUMB_TEXT -> UiRole.SCROLL_BUBBLE_TEXT
        ThemeSlot.STATUS_BAR -> UiRole.STATUS_BAR
        ThemeSlot.NAV_BAR -> UiRole.NAVIGATION_BAR
        ThemeSlot.BAR_GROUND -> UiRole.TOOLBAR_GROUND
        ThemeSlot.SETTINGS_TITLE -> UiRole.TOOLBAR_TITLE
        ThemeSlot.SETTINGS_BACK -> UiRole.TOOLBAR_ICON
        ThemeSlot.SEARCH_FILL -> UiRole.SEARCH_GROUND
        ThemeSlot.SEARCH_BORDER -> UiRole.SEARCH_FRAME
        ThemeSlot.SEARCH_TEXT -> UiRole.SEARCH_TEXT
        ThemeSlot.SEARCH_HINT -> UiRole.SEARCH_HINT
        ThemeSlot.SEARCH_ICON -> UiRole.SEARCH_ICON
        else -> null
    }

/** Commons roles no slot stands for; the UI page lists them beside the slots of their surface. */
fun unmappedUiRoles(): List<UiRole> {
    val mapped = ThemeSlot.entries.mapNotNull { it.uiRole }.toSet()
    return UiRole.entries.filter { !it.isFoundation && it !in mapped }
}

/**
 * One-time: a colour 白い熊 set on a slot before it became a commons role moves to that role. The key
 * carries a number that goes up whenever further slots are mapped, so the move runs once more for them
 * (2: the menu icons for view, share and block and the destructive menu row, Commons 7.1.3-sk4).
 */
fun Context.migrateSlotsToUiRolesIfNeeded() {
    if (config.getThemeOverride(UI_ROLES_MIGRATED_KEY) != THEME_UNSET) return
    ThemeSlot.entries.forEach { slot ->
        val role = slot.uiRole ?: return@forEach
        val old = config.getThemeOverride(slot.key)
        if (old != THEME_UNSET) {
            setUiColor(role, old)
            config.clearThemeOverride(slot.key)
        }
    }
    config.setThemeOverride(UI_ROLES_MIGRATED_KEY, 1)
}

const val UI_ROLES_MIGRATED_KEY = "ui_roles_migrated_2"
/** The effective color for a slot: the user's override if set, otherwise its inherited default. */
fun Context.themeColor(slot: ThemeSlot): Int {
    if (!slot.isFoundation) slot.uiRole?.let { return uiColor(it) }
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
    ThemeSlot.MENU_WARNING -> uiColor(UiRole.BUTTON_DESTRUCTIVE)
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
    ThemeSlot.CONVERSATION_ROW_DIVIDER -> ColorUtils.blendARGB(
        themeColor(ThemeSlot.BACKGROUND), themeColor(ThemeSlot.PRIMARY), ROW_DIVIDER_BLEND
    )

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

    ThemeSlot.BAR_GROUND -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.SEARCH_GROUND -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.STATUS_BAR -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.NAV_BAR -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.TAB_INK -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.TAB_GROUND -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.PROGRESS -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.NOTIFICATION_ACCENT -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.HEADING -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.HEADING_UNDERLINE -> themeColor(ThemeSlot.HEADING)
    ThemeSlot.LINK -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.PLACEHOLDER -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.INPUT_TEXT -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.INPUT_HINT -> themeColor(ThemeSlot.TEXT).adjustAlpha(SEARCH_HINT_ALPHA)
    ThemeSlot.INPUT_FRAME -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.CURSOR -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.BUTTON_TEXT -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.BUTTON_FRAME -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.BUTTON_GROUND -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.BUTTON_WARNING -> themeColor(ThemeSlot.MENU_WARNING)
    ThemeSlot.FAB_ICON -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.FAB_FRAME -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.FAB_GROUND -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.DIALOG_GROUND -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.DIALOG_FRAME -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.DIALOG_TITLE -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.DIALOG_TEXT -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.DIALOG_NOTE -> themeColor(ThemeSlot.SETTINGS_NOTE)
    ThemeSlot.DIALOG_WARNING -> themeColor(ThemeSlot.MENU_WARNING)
    ThemeSlot.SWITCH_ON -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.CHECKBOX -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.RADIO -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.MENU_FRAME -> themeColor(ThemeSlot.MENU_TEXT)
    ThemeSlot.MENU_DIVIDER -> ColorUtils.blendARGB(themeColor(ThemeSlot.MENU_BACKGROUND), themeColor(ThemeSlot.MENU_TEXT), MENU_DIVIDER_BLEND)
    ThemeSlot.LIST_TEXT -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.LIST_SECONDARY -> themeColor(ThemeSlot.TEXT_SECONDARY)
    ThemeSlot.LIST_ICON -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.LIST_DIVIDER -> ColorUtils.blendARGB(themeColor(ThemeSlot.BACKGROUND), themeColor(ThemeSlot.PRIMARY), MENU_DIVIDER_BLEND)
    ThemeSlot.LIST_SELECTION -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.FASTSCROLL_TEXT -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.FASTSCROLL_THUMB -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.FASTSCROLL_THUMB_TEXT -> themeColor(ThemeSlot.FASTSCROLL_THUMB).getContrastColor()
    ThemeSlot.CONVERSATION_UNREAD_TEXT -> themeColor(ThemeSlot.CONVERSATION_UNREAD).getContrastColor()
    ThemeSlot.UNDO_TEXT -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.UNDO_FRAME -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.UNDO_GROUND -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.UNFILED_MARK -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.SWIPE_FILL -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.SWIPE_INK -> themeColor(ThemeSlot.SWIPE_FILL).getContrastColor()
    ThemeSlot.HEADER_GROUND -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.HEADER_FRAME -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.HEADER_NAME -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.HEADER_NUMBER -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.HEADER_ICON -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.AVATAR_HALO -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.AVATAR_GLYPH -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.THREAD_FAILED -> themeColor(ThemeSlot.MENU_WARNING)
    ThemeSlot.COMPOSE_BAND -> ColorUtils.blendARGB(themeColor(ThemeSlot.BACKGROUND), themeColor(ThemeSlot.PRIMARY), COMPOSE_BAND_BLEND)
    ThemeSlot.COMPOSE_LINE -> ColorUtils.blendARGB(themeColor(ThemeSlot.BACKGROUND), themeColor(ThemeSlot.PRIMARY), MENU_DIVIDER_BLEND)
    ThemeSlot.COMPOSE_FIELD_GROUND -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.COMPOSE_FIELD_FRAME -> themeColor(ThemeSlot.COMPOSE_LINE)
    ThemeSlot.COMPOSE_TEXT -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.COMPOSE_HINT -> themeColor(ThemeSlot.TEXT).adjustAlpha(SEARCH_HINT_ALPHA)
    ThemeSlot.COMPOSE_ICON -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.COMPOSE_SEND -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.COMPOSE_COUNTER -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.ATTACH_GROUND -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.ATTACH_LABEL -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.ATTACH_TILE_GROUND -> themeColor(ThemeSlot.BACKGROUND)
    ThemeSlot.ATTACH_PREVIEW_GROUND -> themeColor(ThemeSlot.PRIMARY).darkenColor()
    ThemeSlot.ATTACH_PREVIEW_TEXT -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.ATTACH_PREVIEW_ICON -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.SCHEDULED_GROUND -> themeColor(ThemeSlot.PRIMARY).darkenColor()
    ThemeSlot.SCHEDULED_TEXT -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.SIM_CHOICE_TEXT -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.SIM_CHOICE_CURRENT -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.NEWCONV_LABEL -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.NEWCONV_CONFIRM -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.NEWCONV_NAME -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.NEWCONV_NUMBER -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.SELECTED_CHIP_GROUND -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.SELECTED_CHIP_TEXT -> themeColor(ThemeSlot.SELECTED_CHIP_GROUND).getContrastColor()
    ThemeSlot.ROW_LABEL -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.ROW_VALUE -> themeColor(ThemeSlot.TEXT)
    ThemeSlot.CHIP_TEXT -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.CHIP_FRAME -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.SECTION_BASE -> themeColor(ThemeSlot.PRIMARY)
    ThemeSlot.SECTION_CONTROLS -> MENU_GREEN
    ThemeSlot.SECTION_WORDS -> MENU_CYAN
    ThemeSlot.SECTION_FRAME -> MENU_BLUE
    ThemeSlot.SECTION_REACH -> MENU_ORANGE
    ThemeSlot.SECTION_WARNING -> SECTION_RED
    ThemeSlot.SECTION_FLASH -> MENU_MAGENTA
    ThemeSlot.SELECTION_VEIL -> ColorUtils.setAlphaComponent(themeColor(ThemeSlot.PRIMARY), SELECTION_VEIL_ALPHA)
    ThemeSlot.SECTION_DIVIDER -> ColorUtils.blendARGB(themeColor(ThemeSlot.BACKGROUND), themeColor(ThemeSlot.PRIMARY), MENU_DIVIDER_BLEND)
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
            config.primaryColor = color
        }

        ThemeSlot.BACKGROUND -> {
            config.backgroundColor = color
        }

        ThemeSlot.TEXT -> {
            config.textColor = color
        }

        else -> slot.uiRole?.let { setUiColor(it, color) } ?: config.setThemeOverride(slot.key, color)
    }
    syncFamilyLook()
}

/** Revert a slot to its default (palette for the editable foundation colors, inherited otherwise). */
fun Context.resetThemeColor(slot: ThemeSlot) {
    when (slot) {
        ThemeSlot.BACKGROUND -> resetUiColor(UiRole.BACKGROUND)
        ThemeSlot.PRIMARY -> resetUiColor(UiRole.ACCENT)
        ThemeSlot.TEXT -> resetUiColor(UiRole.TEXT)
        else -> slot.uiRole?.let { resetUiColor(it) } ?: config.clearThemeOverride(slot.key)
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
}

/** One-time seed of the default black/yellow look across the whole app (via the stock colors). */
fun Context.seedBlackYellowThemeIfNeeded() {
    if (config.themeV1Seeded) {
        return
    }
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
        setColor(context.themeColor(ThemeSlot.DIALOG_GROUND))
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
