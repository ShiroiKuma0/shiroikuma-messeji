package org.fossify.messages.extensions

import android.widget.ImageView
import com.bumptech.glide.Glide
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.messages.R

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
