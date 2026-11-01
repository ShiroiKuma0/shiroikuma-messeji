package org.fossify.messages.receivers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.RemoteInput
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.bridge.Bridge
import org.fossify.messages.extensions.*
import org.fossify.messages.helpers.REPLY
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_NUMBER
import org.fossify.messages.messaging.sendMessageCompat

class DirectReplyReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(THREAD_NUMBER)
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        var body = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY)?.toString() ?: return

        body = context.removeDiacriticsIfNeeded(body)

        if (address != null) {
            var subscriptionId: Int? = null
            val availableSIMs = context.subscriptionManagerCompat().activeSubscriptionInfoList
            val preferred = context.config.getUseSIMIdAtNumber(address)
            if (Bridge.transport(context, preferred) != null) {
                // Fork: the thread is set to a bridge transport — reply over it, not the radio.
                subscriptionId = preferred
            } else if (Bridge.transports(context).isNotEmpty() && (availableSIMs?.size ?: 0) == 0) {
                // No SIM at all, and no per-number choice yet: go back the way the thread came in.
                subscriptionId = context.getMessages(threadId = threadId, includeScheduledMessages = false, limit = 1)
                    .lastOrNull()?.subscriptionId?.takeIf { Bridge.isPseudo(it) }
                    ?: Bridge.transports(context).first().subId
            } else if ((availableSIMs?.size ?: 0) > 1) {
                // Fork: the per-number preference is a SUBSCRIPTION ID (ThreadActivity saves
                // `currentSIMCard.subscriptionId`), not an index into this list — reading it as an
                // index sent inline replies from the wrong SIM, or the default one.
                val preferredSubscriptionId = context.config.getUseSIMIdAtNumber(address)
                val wanted = availableSIMs?.firstOrNull { it.subscriptionId == preferredSubscriptionId }
                if (wanted != null) {
                    subscriptionId = wanted.subscriptionId
                }
            }

            ensureBackgroundThread {
                var messageId = 0L
                try {
                    context.sendMessageCompat(body, listOf(address), subscriptionId, emptyList())
                    val message = context.getMessages(
                        threadId = threadId, includeScheduledMessages = false, limit = 1
                    ).lastOrNull()
                    if (message != null) {
                        context.messagesDB.insertOrUpdate(message)
                        messageId = message.id

                        context.updateLastConversationMessage(threadId)
                    }
                } catch (e: Exception) {
                    context.showErrorToast(e)
                }

                val photoUri = SimpleContactsHelper(context).getPhotoUriFromPhoneNumber(address)
                val bitmap = context.getNotificationBitmap(photoUri)
                Handler(Looper.getMainLooper()).post {
                    context.notificationHelper.showMessageNotification(
                        messageId = messageId,
                        address = address,
                        body = body,
                        threadId = threadId,
                        bitmap = bitmap,
                        sender = null,
                        alertOnlyOnce = true
                    )
                }

                context.markThreadMessagesRead(threadId)
                context.conversationsDB.markRead(threadId)
            }
        }
    }
}
