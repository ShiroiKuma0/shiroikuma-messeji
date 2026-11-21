package org.fossify.messages.dialogs

import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.extensions.ThemeSlot
import org.fossify.messages.extensions.decodeAvatarSource
import org.fossify.messages.extensions.saveOwnAvatar
import org.fossify.messages.extensions.themeColor
import org.fossify.messages.views.AvatarCropView

/**
 * A picked picture is placed in the avatar's circle — moved and zoomed — before it becomes
 * 白い熊's own picture. Nothing is stored until OK; [onSet] runs after the picture is saved.
 */
class AvatarCropDialog(private val activity: BaseSimpleActivity, uri: Uri, private val onSet: () -> Unit) {
    init {
        ensureBackgroundThread {
            val picture = activity.decodeAvatarSource(uri)
            activity.runOnUiThread {
                if (picture == null) {
                    activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                } else if (!activity.isFinishing && !activity.isDestroyed) {
                    show(picture)
                }
            }
        }
    }

    private fun show(picture: Bitmap) {
        val density = activity.resources.displayMetrics.density
        val cropView = AvatarCropView(
            context = activity,
            picture = picture,
            scrimColor = activity.themeColor(ThemeSlot.DIALOG_GROUND),
            ringColor = activity.themeColor(ThemeSlot.DIALOG_FRAME),
        )
        val hint = TextView(activity).apply {
            setText(R.string.own_avatar_position_hint)
            setTextColor(activity.themeColor(ThemeSlot.DIALOG_NOTE))
            textSize = HINT_SP
            val side = (HINT_SIDE_DP * density).toInt()
            setPadding(side, (HINT_TOP_DP * density).toInt(), side, 0)
        }
        val holder = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(cropView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok) { _, _ -> save(cropView.crop(AVATAR_PX)) }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply { activity.setupDialogStuff(holder, this, titleId = R.string.own_avatar_position, cancelOnTouchOutside = false) }
    }

    private fun save(cropped: Bitmap) {
        ensureBackgroundThread {
            val saved = activity.saveOwnAvatar(cropped)
            activity.runOnUiThread {
                if (saved) onSet() else activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
            }
        }
    }

    companion object {
        private const val AVATAR_PX = 720
        private const val HINT_SP = 13f
        private const val HINT_SIDE_DP = 24f
        private const val HINT_TOP_DP = 8f
    }
}
