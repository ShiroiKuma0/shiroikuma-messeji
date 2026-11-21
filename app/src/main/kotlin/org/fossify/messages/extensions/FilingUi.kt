package org.fossify.messages.extensions

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import androidx.annotation.DrawableRes
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.withDialogBorder
import org.fossify.messages.databinding.ItemOverflowMenuBinding
import org.fossify.messages.helpers.Filing

private const val POPUP_CORNER_DP = 16f // matches withDialogBorder's ring
private const val POPUP_ELEVATION_DP = 8f
private const val POPUP_PADDING_DP = 5

/**
 * One action row of a themed popup menu. [opens] marks a row that opens a screen, a dialog or a
 * picker (it ends in a ›); [destructive] draws it in the menu's warning colour. A menu whose rows
 * all lack an [icon] — a list of pure choices — is drawn without the icon column.
 */
class MenuEntry(
    val title: String,
    @DrawableRes val icon: Int? = null,
    val opens: Boolean = false,
    val destructive: Boolean = false,
    /** What the row does, shown by its icon's colour: one of the MENU_ICON_* roles (plain viewing by default). */
    val role: ThemeSlot = ThemeSlot.MENU_ICON_VIEW,
    val action: () -> Unit,
)

/**
 * A small menu in the app's own menu colours, dropped below [anchor] (the main overflow uses it too).
 * Every row is an outlined pill as wide as the menu. Where the room below the anchor is too short the
 * menu is shifted up by what is missing, so it never scrolls while the screen has room.
 */
fun Activity.showThemedPopupMenu(anchor: View, entries: List<MenuEntry>) {
    val density = resources.displayMetrics.density
    val pad = (POPUP_PADDING_DP * density).toInt()
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, pad, 0, pad)
    }
    val scroller = ScrollView(this).apply { addView(content) }
    val popup = PopupWindow(scroller, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    val accent = themeColor(ThemeSlot.MENU_TEXT)
    val warning = themeColor(ThemeSlot.MENU_WARNING)
    val withIcons = entries.any { it.icon != null }
    entries.forEach { entry ->
        val color = if (entry.destructive) warning else accent
        val item = ItemOverflowMenuBinding.inflate(layoutInflater, content, false)
        item.overflowMenuLabel.text = entry.title
        item.overflowMenuLabel.setTextColor(color)
        item.overflowMenuIcon.beGoneIf(!withIcons)
        entry.icon?.let { item.overflowMenuIcon.setImageResource(it) }
        // one coloured thing per row: the icon tells what the row does, frame and label stay in the row's colour
        item.overflowMenuIcon.imageTintList = ColorStateList.valueOf(if (entry.destructive) warning else themeColor(entry.role))
        item.overflowMenuChevron.beGoneIf(!entry.opens)
        item.overflowMenuChevron.imageTintList = ColorStateList.valueOf(color)
        item.root.background = pillBackground(color)
        item.root.setOnClickListener {
            popup.dismiss()
            entry.action()
        }
        content.addView(item.root)
    }
    popup.setBackgroundDrawable(
        withDialogBorder(
            GradientDrawable().apply {
                cornerRadius = POPUP_CORNER_DP * density
                setColor(themeColor(ThemeSlot.MENU_BACKGROUND))
            }
        )
    )
    popup.elevation = POPUP_ELEVATION_DP * density

    // the room below the anchor against what the menu needs
    scroller.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
    val frame = Rect().also { anchor.getWindowVisibleDisplayFrame(it) }
    val location = IntArray(2).also { anchor.getLocationOnScreen(it) }
    val base = location[1] + anchor.height
    val missing = scroller.measuredHeight + 2 * pad - (frame.bottom - base)
    val offset = if (missing > 0) -missing.coerceAtMost(base - frame.top) else 0
    popup.showAsDropDown(anchor, 0, offset)
}

/** The one-tap filing menu: every user tab except [currentTab]; [onPick] gets the chosen tab id. */
fun Activity.showTabPicker(anchor: View, currentTab: Int?, onPick: (Int) -> Unit) {
    val entries = Filing.tabs(this)
        .filter { it.id != currentTab }
        .map { tab -> MenuEntry(tab.name) { onPick(tab.id) } }
    showThemedPopupMenu(anchor, entries)
}
