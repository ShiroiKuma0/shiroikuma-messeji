package org.fossify.messages.extensions

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import org.fossify.commons.extensions.withDialogBorder
import org.fossify.messages.databinding.ItemOverflowMenuBinding
import org.fossify.messages.helpers.Filing

private const val POPUP_CORNER_DP = 16f // matches withDialogBorder's ring
private const val POPUP_ELEVATION_DP = 8f

/** A small menu in the app's own menu colours, dropped below [anchor] (the main overflow uses it too). */
fun Activity.showThemedPopupMenu(anchor: View, entries: List<Pair<String, () -> Unit>>) {
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    val menuTextColor = themeColor(ThemeSlot.MENU_TEXT)
    entries.forEach { (title, action) ->
        val item = ItemOverflowMenuBinding.inflate(layoutInflater, content, false)
        item.overflowMenuItem.text = title
        item.overflowMenuItem.setTextColor(menuTextColor)
        item.overflowMenuItem.setOnClickListener {
            popup.dismiss()
            action()
        }
        content.addView(item.root)
    }
    val density = resources.displayMetrics.density
    popup.setBackgroundDrawable(
        withDialogBorder(
            GradientDrawable().apply {
                cornerRadius = POPUP_CORNER_DP * density
                setColor(themeColor(ThemeSlot.MENU_BACKGROUND))
            }
        )
    )
    popup.elevation = POPUP_ELEVATION_DP * density
    popup.showAsDropDown(anchor)
}

/** The one-tap filing menu: every user tab except [currentTab]; [onPick] gets the chosen tab id. */
fun Activity.showTabPicker(anchor: View, currentTab: Int?, onPick: (Int) -> Unit) {
    val entries = Filing.tabs(this)
        .filter { it.id != currentTab }
        .map { tab -> tab.name to { onPick(tab.id) } }
    showThemedPopupMenu(anchor, entries)
}
