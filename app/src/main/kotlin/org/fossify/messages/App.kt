package org.fossify.messages

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import org.fossify.commons.FossifyApp
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.migratePureYellowIfNeeded
import org.fossify.messages.extensions.seedDialogStyleIfNeeded
import org.fossify.messages.extensions.syncFamilyLook
import org.fossify.messages.extensions.rescheduleAllScheduledMessages
import org.fossify.messages.extensions.seedBlackYellowThemeIfNeeded
import org.fossify.messages.helpers.Diag
import org.fossify.messages.helpers.MessagingCache
import org.fossify.messages.extensions.migrateSlotsToUiRolesIfNeeded

class App : FossifyApp() {
    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        super.onCreate()
        // Apply the default black/yellow look once, before any activity themes itself.
        seedBlackYellowThemeIfNeeded()
        // Rewrite any persisted legacy material yellow to the pure yellow palette, once.
        migratePureYellowIfNeeded()
        // Frame every dialog in yellow (Commons' opt-in dialog border), once.
        seedDialogStyleIfNeeded()
        // Pill dialog buttons, outlined switches and floating button from Commons, in our colour roles.
        migrateSlotsToUiRolesIfNeeded()
        syncFamilyLook()
        // Diagnostics: load the switches before the first receiver may want to write a line.
        Diag.init(this)
        if (hasPermission(PERMISSION_READ_CONTACTS)) {
            listOf(
                ContactsContract.Contacts.CONTENT_URI,
                ContactsContract.Data.CONTENT_URI,
                ContactsContract.DisplayPhoto.CONTENT_URI
            ).forEach {
                try {
                    contentResolver.registerContentObserver(it, true, contactsObserver)
                } catch (_: Exception) {
                }
            }
        }

        ensureBackgroundThread {
            rescheduleAllScheduledMessages()
        }
    }

    private val contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            MessagingCache.namePhoto.evictAll()
            MessagingCache.participantsCache.evictAll()
        }
    }
}
