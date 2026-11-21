package org.fossify.messages.extensions

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.widget.ImageView
import com.bumptech.glide.Glide
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.extensions.getTextSize
import org.fossify.messages.R
import java.io.File
import kotlin.math.roundToInt

// Show the contact's photo, or the brush-stroke 人 icon when the contact has no picture.
fun ImageView.loadContactPhotoOrUnknown(photoUri: String, name: String) {
    if (photoUri.isEmpty()) {
        // cancel any in-flight Glide request on this (recycled) view before setting the icon
        Glide.with(context).clear(this)
        setImageResource(R.drawable.ic_unknown_contact)
    } else {
        SimpleContactsHelper(context).loadContactImage(photoUri, this, name)
    }
}

/** The picture of the device owner's own profile ("Me" in the system contacts), or "" when there is none. */
fun Context.ownProfilePhotoUri(): String = try {
    contentResolver.query(
        ContactsContract.Profile.CONTENT_URI, arrayOf(ContactsContract.Profile.PHOTO_URI), null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
    }.orEmpty()
} catch (_: Exception) {
    ""
}

private const val OWN_AVATAR_PREFIX = "own_avatar_"

/** The picture 白い熊 picked for their own bubbles, or "" when none is picked or its file is gone. */
fun Context.ownAvatarPath(): String = config.ownAvatarPath.takeIf { it.isNotEmpty() && File(it).isFile }.orEmpty()

/**
 * Copy a picked image into the app's own storage and make it 白い熊's own picture. The file name
 * carries the time, so image caches never serve the previous picture. Returns false on failure.
 */
fun Context.importOwnAvatar(uri: Uri): Boolean = try {
    val target = File(filesDir, "$OWN_AVATAR_PREFIX${System.currentTimeMillis()}")
    val copied = contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { input.copyTo(it) }
    } != null
    if (copied) {
        clearOwnAvatarFiles(except = target)
        config.ownAvatarPath = target.absolutePath
    }
    copied
} catch (_: Exception) {
    false
}

fun Context.clearOwnAvatar() {
    config.ownAvatarPath = ""
    clearOwnAvatarFiles(except = null)
}

private fun Context.clearOwnAvatarFiles(except: File?) {
    filesDir.listFiles { file -> file.name.startsWith(OWN_AVATAR_PREFIX) && file != except }?.forEach { it.delete() }
}

private const val THREAD_TEXT_SHRINK = 0.88f

/**
 * One-time (1.9.1+052): received and sent message text get one size — 88 % of what received text
 * was — stored as ordinary per-element sizes, so the UI page shows and changes them as before.
 */
fun Context.alignThreadTextSizes() {
    if (config.threadSizesAligned) {
        bumpThreadTextSizes()
        return
    }
    config.threadSizesAligned = true
    val stored = config.getFontSize(ThemeSlot.THREAD_RECEIVED_TEXT.key)
    val receivedSp = if (stored > 0) stored.toFloat() else getTextSize() / resources.displayMetrics.scaledDensity
    val size = (receivedSp * THREAD_TEXT_SHRINK).roundToInt()
    config.setFontSize(ThemeSlot.THREAD_RECEIVED_TEXT.key, size)
    config.setFontSize(ThemeSlot.THREAD_SENT_TEXT.key, size)
    bumpThreadTextSizes()
}

/** One-time (1.9.1+053): both message texts one sp bigger; the footer's share of it grew in the adapter. */
private fun Context.bumpThreadTextSizes() {
    if (config.threadSizesBumped) return
    config.threadSizesBumped = true
    listOf(ThemeSlot.THREAD_RECEIVED_TEXT, ThemeSlot.THREAD_SENT_TEXT).forEach { slot ->
        val size = config.getFontSize(slot.key)
        if (size > 0) config.setFontSize(slot.key, size + 1)
    }
}
