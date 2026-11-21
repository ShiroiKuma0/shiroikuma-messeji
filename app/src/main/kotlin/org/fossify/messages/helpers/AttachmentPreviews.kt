package org.fossify.messages.helpers

import android.app.Activity
import android.net.Uri
import android.view.View
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.databinding.ItemAttachmentDocumentBinding
import org.fossify.messages.databinding.ItemAttachmentDocumentPreviewBinding
import org.fossify.messages.databinding.ItemAttachmentVcardBinding
import org.fossify.messages.databinding.ItemAttachmentVcardPreviewBinding
import org.fossify.messages.extensions.*
import org.fossify.messages.extensions.ThemeSlot
import org.fossify.messages.extensions.themeColor
import org.fossify.messages.extensions.frameBox
import org.fossify.commons.extensions.rippleDrawable
import org.fossify.commons.extensions.circleDrawable

private const val FRAME_RIPPLE_RADIUS_DP = 8f

fun ItemAttachmentDocumentPreviewBinding.setupDocumentPreview(
    uri: Uri,
    title: String,
    mimeType: String,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onRemoveButtonClicked: (() -> Unit)? = null
) {
    documentAttachmentHolder.setupDocumentPreview(uri, title, mimeType, onClick, onLongClick)
    removeAttachmentButtonHolder.removeAttachmentButton.apply {
        beVisible()
        background = circleDrawable(context.themeColor(ThemeSlot.ATTACH_PREVIEW_ICON))
        if (onRemoveButtonClicked != null) {
            setOnClickListener {
                onRemoveButtonClicked.invoke()
            }
        }
    }
}

fun ItemAttachmentDocumentBinding.setupDocumentPreview(
    uri: Uri,
    title: String,
    mimeType: String,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val context = root.context
    if (title.isNotEmpty()) {
        filename.text = title
    }

    ensureBackgroundThread {
        try {
            val size = context.getFileSizeFromUri(uri)
            root.post {
                fileSize.beVisible()
                fileSize.text = size.formatSize()
            }
        } catch (_: Exception) {
            root.post {
                fileSize.beGone()
            }
        }
    }

    val textColor = context.themeColor(ThemeSlot.ATTACH_PREVIEW_TEXT)
    val primaryColor = context.themeColor(ThemeSlot.ATTACH_PREVIEW_GROUND)

    filename.setTextColor(textColor)
    fileSize.setTextColor(textColor)

    icon.setImageResource(getIconResourceForMimeType(mimeType))
    icon.background = circleDrawable(context.themeColor(ThemeSlot.ATTACH_PREVIEW_ICON))
    root.background = context.frameBox(primaryColor)
    root.foreground = context.rippleDrawable(FRAME_RIPPLE_RADIUS_DP)

    root.setOnClickListener {
        onClick?.invoke()
    }

    root.setOnLongClickListener {
        onLongClick?.invoke()
        true
    }
}

fun ItemAttachmentVcardPreviewBinding.setupVCardPreview(
    activity: Activity,
    uri: Uri,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onRemoveButtonClicked: (() -> Unit)? = null,
) {
    vcardProgress.beVisible()
    vcardAttachmentHolder.setupVCardPreview(activity = activity, uri = uri, attachment = true, onClick = onClick, onLongClick = onLongClick) {
        vcardProgress.beGone()
        removeAttachmentButtonHolder.removeAttachmentButton.apply {
            beVisible()
            background = circleDrawable(activity.themeColor(ThemeSlot.ATTACH_PREVIEW_ICON))
            if (onRemoveButtonClicked != null) {
                setOnClickListener {
                    onRemoveButtonClicked.invoke()
                }
            }
        }
    }
}

fun ItemAttachmentVcardBinding.setupVCardPreview(
    activity: Activity,
    uri: Uri,
    attachment: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onVCardLoaded: (() -> Unit)? = null,
) {
    val context = root.context
    val textColor = activity.themeColor(ThemeSlot.ATTACH_PREVIEW_TEXT)
    val primaryColor = activity.themeColor(ThemeSlot.ATTACH_PREVIEW_GROUND)

    root.background = context.frameBox(primaryColor)
    root.foreground = context.rippleDrawable(FRAME_RIPPLE_RADIUS_DP)
    vcardTitle.setTextColor(textColor)
    vcardSubtitle.setTextColor(textColor)
    viewContactDetails.setTextColor(activity.themeColor(ThemeSlot.LINK))

    arrayOf<View>(vcardPhoto, vcardTitle, vcardSubtitle, viewContactDetails).forEach {
        it.beGone()
    }

    parseVCardFromUri(activity, uri) { vCards ->
        activity.runOnUiThread {
            if (vCards.isEmpty()) {
                vcardTitle.beVisible()
                vcardTitle.text = context.getString(org.fossify.commons.R.string.unknown_error_occurred)
                return@runOnUiThread
            }

            val title = vCards.firstOrNull()?.parseNameFromVCard()
            val imageIcon = if (title != null) {
                SimpleContactsHelper(activity).getContactLetterIcon(title)
            } else {
                null
            }

            arrayOf<View>(vcardPhoto, vcardTitle).forEach {
                it.beVisible()
            }

            vcardPhoto.setImageBitmap(imageIcon)
            vcardTitle.text = title

            if (vCards.size > 1) {
                vcardSubtitle.beVisible()
                val quantity = vCards.size - 1
                vcardSubtitle.text = context.resources.getQuantityString(R.plurals.and_other_contacts, quantity, quantity)
            } else {
                vcardSubtitle.beGone()
            }

            if (attachment) {
                onVCardLoaded?.invoke()
            } else {
                viewContactDetails.setTextColor(primaryColor)
                viewContactDetails.beVisible()
            }

            vcardAttachmentHolder.setOnClickListener {
                onClick?.invoke()
            }
            vcardAttachmentHolder.setOnLongClickListener {
                onLongClick?.invoke()
                true
            }
        }
    }
}

private fun getIconResourceForMimeType(mimeType: String) = when {
    mimeType.isAudioMimeType() -> R.drawable.ic_vector_audio_file
    mimeType.isCalendarMimeType() -> R.drawable.ic_calendar_month_vector
    mimeType.isPdfMimeType() -> R.drawable.ic_vector_pdf
    mimeType.isZipMimeType() -> R.drawable.ic_vector_folder_zip
    else -> R.drawable.ic_document_vector
}
