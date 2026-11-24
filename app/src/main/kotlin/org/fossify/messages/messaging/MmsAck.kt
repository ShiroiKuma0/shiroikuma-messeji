package org.fossify.messages.messaging

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import com.google.android.mms.pdu_alt.AcknowledgeInd
import com.google.android.mms.pdu_alt.GenericPdu
import com.google.android.mms.pdu_alt.NotifyRespInd
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.RetrieveConf
import com.klinker.android.send_message.MmsReceivedReceiver
import org.fossify.commons.helpers.isSPlus
import org.fossify.messages.helpers.Diag
import org.fossify.messages.receivers.MmsAckReceiver
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One finished MMS download, as the library's result broadcast describes it: which notification it
 * answers ([transactionId], [location]), on which SIM ([subscriptionId]), whether the push started it
 * by itself ([byPush]) or 白い熊 asked for it, and the downloaded M-Retrieve.conf ([pdu]) — read before
 * the library stores and deletes it.
 */
class MmsDownload(
    val transactionId: String?,
    val location: String?,
    val subscriptionId: Int,
    val byPush: Boolean,
    private val pdu: ByteArray?,
) {
    val size get() = pdu?.size ?: -1

    /** The transaction id of the M-Retrieve.conf itself; null when the MMSC set none. */
    val retrieveTransactionId: ByteArray? by lazy {
        runCatching { (PduParser(pdu, true).parse() as? RetrieveConf)?.transactionId }.getOrNull()
    }

    private val acknowledged = AtomicBoolean()

    /** True the first time only: a download is acknowledged once. */
    fun claimAcknowledgement() = acknowledged.compareAndSet(false, true)

    companion object {
        fun from(intent: Intent): MmsDownload {
            val path = intent.getStringExtra(MmsReceivedReceiver.EXTRA_FILE_PATH)
            return MmsDownload(
                transactionId = intent.getStringExtra(MmsReceivedReceiver.EXTRA_TRANSACTION_ID),
                location = intent.getStringExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL),
                subscriptionId = intent.getIntExtra(
                    MmsReceivedReceiver.SUBSCRIPTION_ID,
                    com.klinker.android.send_message.Settings.DEFAULT_SUBSCRIPTION_ID
                ),
                byPush = intent.getBooleanExtra(MmsReceivedReceiver.EXTRA_TRIGGER_PUSH, false),
                pdu = runCatching { File(path!!).readBytes() }.getOrNull(),
            )
        }
    }
}

/**
 * Tells the operator's MMSC that a downloaded MMS has arrived. Neither the platform's
 * `downloadMultimediaMessage` nor the MMS library does: left unanswered, the MMSC takes the message for
 * undelivered — O2 parks it in its “MMS Album” and sends an SMS with a link for every one, again and
 * again.
 *
 * What is sent is what the MMS encapsulation asks for: `M-NotifyResp.ind` (status Retrieved, the
 * notification's transaction id) after a download the push started by itself, `M-Acknowledge.ind` (the
 * transaction id of the M-Retrieve.conf) after one that was asked for later. It goes out through the
 * platform's own `sendMultimediaMessage`, on the subscription the notification came in on — so over
 * that operator's MMS connection, WiFi or not, with no APN data of ours.
 *
 * Every step is written to the “MMS transport” diagnostic; the result arrives in [MmsAckReceiver].
 */
object MmsAck {
    const val EXTRA_FILE = "file"
    const val EXTRA_WHAT = "what"

    private const val AUTHORITY_SUFFIX = ".MmsFileProvider" // the library's provider: files of the cache directory
    private const val HEX_BYTE_MASK = 0xFF

    fun send(context: Context, download: MmsDownload, reason: String) {
        if (!download.claimAcknowledgement()) return
        val what = StringBuilder()
        try {
            val retrieveId = download.retrieveTransactionId
            val notificationId = download.transactionId
            val pdu: GenericPdu = when {
                !download.byPush && retrieveId != null -> {
                    what.append("M-Acknowledge.ind tid=${printable(retrieveId)}")
                    AcknowledgeInd(PduHeaders.CURRENT_MMS_VERSION, retrieveId)
                }

                !notificationId.isNullOrEmpty() -> {
                    val id = notificationId.toByteArray(Charsets.ISO_8859_1)
                    what.append("M-NotifyResp.ind (Retrieved) tid=${printable(id)}")
                    NotifyRespInd(PduHeaders.CURRENT_MMS_VERSION, id, PduHeaders.STATUS_RETRIEVED)
                }

                else -> {
                    Diag.log(Diag.Kind.MMS) { "ack NOT sent ($reason): the download carries no transaction id" }
                    return
                }
            }
            what.append(" sub=${download.subscriptionId}")
            val bytes = PduComposer(context, pdu).make()
            if (bytes == null) {
                Diag.log(Diag.Kind.MMS) { "ack NOT sent ($reason): $what could not be composed" }
                return
            }

            val name = "ack.${UUID.randomUUID()}.dat"
            val file = File(context.cacheDir, name)
            file.writeBytes(bytes)
            val uri = Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(context.packageName + AUTHORITY_SUFFIX)
                .path(name)
                .build()

            val manager = getSmsManager(download.subscriptionId)
            // Some operators want the answer at the address the message was fetched from, not at the MMSC.
            val toLocation = runCatching {
                manager.carrierConfigValues?.getBoolean(SmsManager.MMS_CONFIG_NOTIFY_WAP_MMSC_ENABLED, false)
            }.getOrNull() == true
            if (toLocation) what.append(" to=${download.location}")

            // Mutable, so that the platform can add the HTTP status; one of its own per acknowledgement.
            val result = Intent(context, MmsAckReceiver::class.java)
                .setData(Uri.parse("mmsack://$name"))
                .putExtra(EXTRA_FILE, file.path)
                .putExtra(EXTRA_WHAT, what.toString())
            val flags = PendingIntent.FLAG_ONE_SHOT or (if (isSPlus()) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, 0, result, flags)

            manager.sendMultimediaMessage(context, uri, if (toLocation) download.location else null, null, pending)
            Diag.log(Diag.Kind.MMS) { "ack queued ($reason): $what, ${bytes.size} bytes" }
        } catch (e: Exception) {
            Diag.log(Diag.Kind.MMS) { "ack FAILED before sending ($reason): $what — $e" }
        }
    }

    /** A transaction id for the trail: as text when it is text, otherwise in hex. */
    fun printable(id: ByteArray?): String = when {
        id == null -> "none"
        id.all { it in 0x20..0x7E } -> String(id, Charsets.ISO_8859_1)
        else -> id.joinToString("") { "%02x".format(it.toInt() and HEX_BYTE_MASK) }
    }
}
