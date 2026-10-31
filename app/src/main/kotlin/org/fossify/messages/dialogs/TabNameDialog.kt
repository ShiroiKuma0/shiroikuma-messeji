package org.fossify.messages.dialogs

import android.app.Activity
import android.content.DialogInterface.BUTTON_POSITIVE
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.messages.databinding.DialogTabNameBinding

/** Name a new filing tab or rename one; [callback] returns false to keep the dialog open. */
class TabNameDialog(
    activity: Activity,
    titleRes: Int,
    current: String,
    callback: (name: String) -> Boolean,
) {
    init {
        val binding = DialogTabNameBinding.inflate(activity.layoutInflater)
        binding.tabNameEditText.setText(current)
        binding.tabNameEditText.setSelection(current.length)

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, titleRes) { alertDialog ->
                    alertDialog.showKeyboard(binding.tabNameEditText)
                    alertDialog.getButton(BUTTON_POSITIVE).setOnClickListener {
                        val name = binding.tabNameEditText.text.toString().trim()
                        if (name.isEmpty()) {
                            activity.toast(org.fossify.commons.R.string.empty_name)
                        } else if (callback(name)) {
                            alertDialog.dismiss()
                        }
                    }
                }
            }
    }
}
