package org.fossify.messages.dialogs

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.messages.R
import org.fossify.messages.extensions.ThemeSlot
import org.fossify.messages.extensions.pillBackground
import org.fossify.messages.extensions.themeColor
import org.fossify.messages.databinding.DialogFontPickerBinding
import org.fossify.messages.databinding.ItemFontOptionBinding
import org.fossify.messages.extensions.availableFontOptions
import org.fossify.messages.extensions.fontTypeface

// Lists every available font with its name drawn in its own typeface, plus an "Add font…" action.
private const val ADD_ICON_DP = 20
private const val ADD_ICON_GAP_DP = 10
private const val ADD_MARGIN_DP = 8

class FontPickerDialog(
    private val activity: Activity,
    private val onAddFont: () -> Unit,
    private val onPick: (fileName: String) -> Unit,
) {
    private var dialog: AlertDialog? = null
    private val binding = DialogFontPickerBinding.inflate(activity.layoutInflater)

    init {
        val textColor = activity.themeColor(ThemeSlot.DIALOG_TEXT)
        val buttonText = activity.themeColor(ThemeSlot.BUTTON_TEXT)
        val buttonFrame = activity.themeColor(ThemeSlot.BUTTON_FRAME)

        activity.availableFontOptions().forEach { option ->
            val row = ItemFontOptionBinding.inflate(activity.layoutInflater, binding.fontPickerHolder, false)
            row.fontOptionLabel.text = option.displayName
            row.fontOptionLabel.setTextColor(textColor)
            row.fontOptionLabel.typeface = activity.fontTypeface(option.fileName)
            row.fontOptionLabel.setOnClickListener {
                dialog?.dismiss()
                onPick(option.fileName)
            }
            binding.fontPickerHolder.addView(row.root)
        }

        val addRow = ItemFontOptionBinding.inflate(activity.layoutInflater, binding.fontPickerHolder, false)
        addRow.fontOptionLabel.text = activity.getString(R.string.theme_add_font)
        addRow.fontOptionLabel.setTextColor(buttonText)
        // the one action in a list of choices: an outlined pill with its icon, in the import/share colour
        addRow.fontOptionLabel.apply {
            val density = resources.displayMetrics.density
            background = activity.pillBackground(buttonFrame)
            val icon = resources.getColoredDrawableWithColor(
                org.fossify.commons.R.drawable.ic_plus_vector, activity.themeColor(ThemeSlot.MENU_ICON_SHARE)
            )
            val size = (ADD_ICON_DP * density).toInt()
            icon.setBounds(0, 0, size, size)
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = (ADD_ICON_GAP_DP * density).toInt()
            (layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { params ->
                val margin = (ADD_MARGIN_DP * density).toInt()
                params.setMargins(margin, margin, margin, margin)
                layoutParams = params
            }
        }
        addRow.fontOptionLabel.setOnClickListener {
            dialog?.dismiss()
            onAddFont()
        }
        binding.fontPickerHolder.addView(addRow.root)

        activity.getAlertDialogBuilder()
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.theme_font) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }
}
