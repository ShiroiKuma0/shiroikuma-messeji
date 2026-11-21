package org.fossify.messages.extensions

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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

private const val AVATAR_SOURCE_MAX_PX = 2048
private const val QUARTER_TURN = 90f

/**
 * A picked image read for positioning: scaled down to at most [AVATAR_SOURCE_MAX_PX] a side and
 * turned upright as its EXIF data says. Null when it cannot be read.
 */
fun Context.decodeAvatarSource(uri: Uri): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > AVATAR_SOURCE_MAX_PX) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    val orientation = contentResolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val turn = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(QUARTER_TURN)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(2 * QUARTER_TURN)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(3 * QUARTER_TURN)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
        }
    }
    if (decoded == null || turn.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, turn, true)
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}

/**
 * Store a positioned picture in the app's own storage and make it 白い熊's own picture. The file
 * name carries the time, so image caches never serve the previous picture. Returns false on failure.
 */
fun Context.saveOwnAvatar(picture: Bitmap): Boolean = try {
    val target = File(filesDir, "$OWN_AVATAR_PREFIX${System.currentTimeMillis()}")
    val written = target.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    if (written) {
        clearOwnAvatarFiles(except = target)
        config.ownAvatarPath = target.absolutePath
    } else {
        target.delete()
    }
    written
} catch (_: Exception) {
    false
}

private const val PNG_QUALITY = 100

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
