package org.fossify.messages.helpers

import android.content.Context
import androidx.core.graphics.ColorUtils
import org.fossify.messages.R
import org.fossify.messages.extensions.ThemeSlot
import org.fossify.messages.extensions.themeColor
import org.fossify.commons.R as CommonsR

/**
 * What a menu row needs to know to be drawn as a pill (see views/PillMenuItemView). Menu items carry
 * no "opens something" or "destructive" flag, and the overflow-only ones no icon, so these tables
 * supply them, keyed by item id.
 */
object MenuPills {
    const val DISABLED_ALPHA = 97 // 38 %
    const val TOGGLE_HEIGHT_DP = 36 // a checkable row: the switch and little more
    const val PILL_MARGIN_DP = 3 // above and below a pill
    const val SIDE_MARGIN_DP = 8 // between a pill and the menu's edge
    private const val DIVIDER_BLEND = 0.7f

    private val icons = mapOf(
        R.id.empty_archive to CommonsR.drawable.ic_delete_vector,
        R.id.cab_mark_as_read to R.drawable.ic_check_double_vector,
        R.id.cab_mark_as_unread to CommonsR.drawable.ic_mail_vector,
        R.id.mark_as_unread to CommonsR.drawable.ic_mail_vector,
        R.id.cab_add_number_to_contact to CommonsR.drawable.ic_add_person_vector,
        R.id.add_number_to_contact to CommonsR.drawable.ic_add_person_vector,
        R.id.cab_copy_number to CommonsR.drawable.ic_copy_vector,
        R.id.copy_number to CommonsR.drawable.ic_copy_vector,
        R.id.cab_rename_conversation to CommonsR.drawable.ic_rename_vector,
        R.id.rename_conversation to CommonsR.drawable.ic_rename_vector,
        R.id.cab_conversation_details to CommonsR.drawable.ic_info_vector,
        R.id.conversation_details to CommonsR.drawable.ic_info_vector,
        R.id.cab_block_number to CommonsR.drawable.ic_block_vector,
        R.id.block_number to CommonsR.drawable.ic_block_vector,
        R.id.cab_restore to CommonsR.drawable.ic_undo_vector,
        R.id.restore to CommonsR.drawable.ic_undo_vector,
        R.id.cab_forward_message to CommonsR.drawable.ic_arrow_right_vector,
        R.id.cab_select_text to R.drawable.ic_document_vector,
        R.id.manage_people to CommonsR.drawable.ic_people_vector,
        R.id.import_blocked_keywords to R.drawable.ic_menu_import_vector,
        R.id.export_blocked_keywords to R.drawable.ic_menu_export_vector,
    )

    // rows that open a screen, a dialog or a picker: they end in a ›
    private val opens = setOf(
        R.id.cab_add_number_to_contact, R.id.add_number_to_contact,
        R.id.cab_rename_conversation, R.id.rename_conversation,
        R.id.cab_conversation_details, R.id.conversation_details,
        R.id.cab_forward_message, R.id.cab_select_text, R.id.cab_properties, R.id.cab_save_as, R.id.cab_share,
        R.id.manage_people, R.id.cab_move_to_tab, R.id.cab_dial_number, R.id.dial_number,
        R.id.import_blocked_keywords, R.id.export_blocked_keywords, R.id.add_blocked_keyword,
    )

    private val destructive = setOf(R.id.cab_delete, R.id.delete, R.id.empty_archive, R.id.empty_recycle_bin)

    // What a row does, told by its icon's colour; anything not listed is plain viewing (the accent).
    private val roles: Map<Int, ThemeSlot> = HashMap<Int, ThemeSlot>().apply {
        listOf(R.id.cab_dial_number, R.id.dial_number).forEach { put(it, ThemeSlot.MENU_ICON_CALL) }
        listOf(
            R.id.cab_mark_as_read, R.id.cab_mark_as_unread, R.id.mark_as_unread, R.id.cab_forward_message,
        ).forEach { put(it, ThemeSlot.MENU_ICON_MESSAGE) }
        listOf(
            R.id.cab_add_number_to_contact, R.id.add_number_to_contact, R.id.add_contact, R.id.manage_people,
        ).forEach { put(it, ThemeSlot.MENU_ICON_CONTACT) }
        listOf(
            R.id.cab_copy_number, R.id.copy_number, R.id.cab_copy_keyword, R.id.cab_copy_to_clipboard, R.id.cab_share,
            R.id.cab_save_as, R.id.import_blocked_keywords, R.id.export_blocked_keywords,
        ).forEach { put(it, ThemeSlot.MENU_ICON_SHARE) }
        listOf(
            R.id.cab_block_number, R.id.block_number, R.id.add_blocked_keyword, R.id.cab_archive, R.id.archive,
        ).forEach { put(it, ThemeSlot.MENU_ICON_BLOCK) }
    }

    /** The colour of the icon of menu item [id]: red when it deletes, else its role's. */
    fun iconColor(context: Context, id: Int): Int = when {
        id in destructive -> warning(context)
        else -> context.themeColor(roles[id] ?: ThemeSlot.MENU_ICON_VIEW)
    }

    fun iconOf(id: Int): Int? = icons[id]
    fun opens(id: Int) = id in opens
    fun isDestructive(id: Int) = id in destructive

    fun accent(context: Context) = context.themeColor(ThemeSlot.MENU_TEXT)
    fun warning(context: Context) = context.themeColor(ThemeSlot.MENU_WARNING)

    /** The line between two groups: an opaque darker accent, quieter than a pill frame. */
    fun divider(context: Context) = ColorUtils.blendARGB(
        context.themeColor(ThemeSlot.MENU_BACKGROUND), accent(context), DIVIDER_BLEND
    )

    /** A switch that is off: its own role, an opaque darker accent by default, never a faded one. */
    fun switchOff(context: Context) = context.themeColor(ThemeSlot.SWITCH_OFF)
}
