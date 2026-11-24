package org.fossify.messages.extensions

import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import org.fossify.messages.R

// A message this many seconds older than the conversation's own date is not its newest one.
private const val NEWEST_SLACK_SECONDS = 60
private val NOT_AN_ATTACHMENT = setOf("application/smil", "text/plain")

/**
 * What the newest MMS of a conversation carries when it has no text — "Picture", "3 pictures",
 * "Video", "Audio", "Contact card", "File", "2 attachments" — for the preview line of the
 * conversation list. Empty when the newest message is not an MMS with attachments.
 * Reads the system store; call it off the main thread.
 */
fun Context.describeLatestAttachments(threadId: Long, conversationDate: Int): String {
    val newest = contentResolver.query(
        Mms.CONTENT_URI, arrayOf(Mms._ID, Mms.DATE), "${Mms.THREAD_ID} = ?", arrayOf(threadId.toString()), "${Mms.DATE} DESC"
    )?.use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null } ?: return ""
    if (newest.second < conversationDate - NEWEST_SLACK_SECONDS) return ""

    val types = ArrayList<String>()
    contentResolver.query(Uri.parse("content://mms/part"), arrayOf(Mms.Part.CONTENT_TYPE), "${Mms.Part.MSG_ID} = ?", arrayOf(newest.first.toString()), null)
        ?.use { while (it.moveToNext()) it.getString(0)?.lowercase()?.let(types::add) }
    val attachments = types.filter { it !in NOT_AN_ATTACHMENT }
    if (attachments.isEmpty()) return ""

    val count = attachments.size
    return when {
        attachments.all { it.isImageMimeType() } -> resources.getQuantityString(R.plurals.attachment_note_pictures, count, count)
        count > 1 -> resources.getQuantityString(R.plurals.attachment_note_attachments, count, count)
        attachments[0].isVideoMimeType() -> getString(R.string.attachment_note_video)
        attachments[0].isAudioMimeType() -> getString(R.string.attachment_note_audio)
        attachments[0].isVCardMimeType() -> getString(R.string.attachment_note_contact)
        else -> getString(R.string.attachment_note_file)
    }
}
