package org.fossify.messages

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import org.fossify.commons.FossifyApp
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.SIDELOADING_FALSE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.rescheduleAllScheduledMessages
import org.fossify.messages.extensions.seedBlackYellowThemeIfNeeded
import org.fossify.messages.helpers.MessagingCache

class App : FossifyApp() {
    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        super.onCreate()
        // Fossify Commons' sideloading detection (isAppSideloaded) probes for a Commons drawable
        // that resource shrinking strips from our custom-signed build, then shows a blocking
        // "fake/corrupt version" dialog (via checkAppSideloading() in BaseSplashActivity and
        // editor/viewer screens). Mark the app as not sideloaded here — before ANY activity runs
        // its check, on every entry point — clearing any persisted verdict. res/raw/keep.xml also
        // keeps the probed drawable so the probe itself succeeds.
        baseConfig.appSideloadingStatus = SIDELOADING_FALSE
        // Apply the default black/yellow look once, before any activity themes itself.
        seedBlackYellowThemeIfNeeded()
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
