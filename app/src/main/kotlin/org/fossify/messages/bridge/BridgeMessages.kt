package org.fossify.messages.bridge

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.telephony.SubscriptionManager
import androidx.core.net.toUri
import com.google.android.mms.pdu_alt.PduHeaders
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.messages.extensions.getMMS
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.notificationHelper
import org.fossify.messages.extensions.subscriptionManagerCompat
import org.fossify.messages.helpers.ReceiverUtils.isMessageFilteredOut
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.Attachment
import org.fossify.messages.receivers.MmsReceiver
import org.fossify.messages.receivers.SmsReceiver
import java.io.File
import java.io.InputStream

/** A message part handed to the bridge: where to read it, what it is, what to call it. */
class PartInput(val type: String, val name: String, val open: () -> InputStream?)

/** One stored non-text part of an MMS, as the bridge exposes it. */
data class PartInfo(val uri: Uri, val type: String, val name: String, val size: Long)

/** Thrown with a message that goes back to the caller verbatim as `error`. */
class BridgeError(message: String) : Exception(message)

@Suppress("TooManyFunctions")
object BridgeMessages {

    private const val CHARSET_UTF8 = 106
    private const val INSERT_ADDRESS_TOKEN = "insert-address-token"

    // ---- ids ---------------------------------------------------------------------------------------

    /** `sms:<id>` / `mms:<id>` — a row in the system store. `in:<seq>` / `out:<seq>` resolve to one. */
    fun resolveRef(context: Context, id: String?): String {
        val value = id?.trim().orEmpty()
        val store = BridgeStore.get(context)
        return when {
            value.startsWith("sms:") || value.startsWith("mms:") -> value
            value.startsWith("in:") -> value.removePrefix("in:").toLongOrNull()?.let { store.inbound(it)?.msgRef }
            value.startsWith("out:") -> value.removePrefix("out:").toLongOrNull()?.let { store.outbound(it)?.msgRef }
            else -> null
        } ?: throw BridgeError("unknown id: $value")
    }

    private fun refId(ref: String) = ref.substringAfter(':').toLongOrNull() ?: throw BridgeError("bad id: $ref")

    private fun isMms(ref: String) = ref.startsWith("mms:")

    fun partFromUriString(context: Context, value: String, type: String, name: String): PartInput {
        val opener: () -> InputStream? = if (value.startsWith("/")) {
            { File(value).inputStream() }
        } else {
            val uri = value.toUri();
            { context.contentResolver.openInputStream(uri) }
        }
        return PartInput(type.ifEmpty { "application/octet-stream" }, name, opener)
    }

    // ---- inbound: a transport message becomes a received SMS/MMS --------------------------------

    /**
     * Store [body]/[parts] from [from] as a received message on transport [subId], exactly as a real
     * one is stored, threaded and notified. Idempotent on ([subId], [remoteId]): a repeat returns the
     * first call's id with `duplicate` set, and stores nothing.
     *
     * @return the inbound seq, whether it was a duplicate, and whether it was dropped as blocked.
     */
    @Synchronized
    fun inject(
        context: Context, subId: Int, from: String, body: String, timestamp: Long,
        remoteId: String, parts: List<PartInput>,
    ): Triple<Long, Boolean, Boolean> {
        val store = BridgeStore.get(context)
        store.inboundByRemote(subId, remoteId)?.let { return Triple(it.seq, true, false) }
        if (context.isNumberBlocked(from) || isMessageFilteredOut(context, body)) {
            return Triple(0L, false, true)
        }

        val ref = if (parts.isEmpty()) {
            val threadId = context.getThreadId(from)
            val id = SmsReceiver.handleMessageSync(
                context = context, address = from, subject = "", body = body, date = timestamp,
                threadId = threadId, subscriptionId = subId, status = Sms.STATUS_NONE,
            )
            if (id <= 0L) throw BridgeError("the SMS store refused the message (is Messēji the default SMS app?)")
            "sms:$id"
        } else {
            val threadId = context.getThreadId(from)
            val id = insertMms(
                context, box = Mms.MESSAGE_BOX_INBOX, messageType = PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF,
                subId = subId, threadId = threadId, dateMillis = timestamp, read = false,
                from = from, to = INSERT_ADDRESS_TOKEN, body = body, parts = parts,
            )
            val mms = context.getMMS("${Mms.CONTENT_URI}/$id".toUri())
            if (mms != null) MmsReceiver.handleMmsMessage(context, mms, from)
            "mms:$id"
        }
        val seq = store.addInbound(ref, subId, remoteId)
        return Triple(seq, false, false)
    }

    // ---- outbound on a pseudo-SIM: write the row, hand its id to the peer -------------------------

    /**
     * The send-path branch for a pseudo subscription. Writes one OUTBOX row per recipient (a group
     * send over a transport is split, since the far end sends plain per-number SMS anyway), journals
     * it as `queued`, and tells the transport's peer. Synchronous on purpose: the compose screen reads
     * the new row back from the store straight after this returns.
     */
    fun queueOutgoing(
        context: Context, text: String, addresses: List<String>, subId: Int,
        attachments: List<Attachment>, resendId: Long?,
    ): List<Long> {
        val peer = Bridge.transportPeer(context)
        val store = BridgeStore.get(context)
        val seqs = ArrayList<Long>()
        for (address in addresses) {
            val threadId = context.getThreadId(address)
            val ref = if (attachments.isEmpty()) {
                "sms:${writeOutgoingSms(context, address, text, subId, threadId, resendId)}"
            } else {
                val parts = attachments.map { a ->
                    PartInput(a.mimetype, a.filename.ifEmpty { a.getUri().lastPathSegment.orEmpty() }) {
                        context.contentResolver.openInputStream(a.getUri())
                    }
                }
                val id = insertMms(
                    context, box = Mms.MESSAGE_BOX_OUTBOX, messageType = PduHeaders.MESSAGE_TYPE_SEND_REQ,
                    subId = subId, threadId = threadId, dateMillis = System.currentTimeMillis(), read = true,
                    from = INSERT_ADDRESS_TOKEN, to = address, body = text, parts = parts,
                )
                "mms:$id"
            }
            val seq = store.addOutbound(ref, subId, address, Bridge.STATE_QUEUED)
            seqs.add(seq)
            Bridge.hint(context, peer, Bridge.ACTION_OUTGOING) {
                putExtra(Bridge.EXTRA_ID, "out:$seq")
                putExtra(Bridge.EXTRA_SUB_ID, subId.toString())
            }
        }
        refreshMessages()
        refreshConversations()
        return seqs
    }

    /** An OUTBOX row for a real-SIM gateway send (service phones). */
    fun writeSimOutgoingSms(context: Context, address: String, text: String, subId: Int, threadId: Long) =
        writeOutgoingSms(context, address, text, subId, threadId, resendId = null)

    private fun writeOutgoingSms(
        context: Context, address: String, text: String, subId: Int, threadId: Long, resendId: Long?,
    ): Long {
        val values = ContentValues().apply {
            put(Sms.ADDRESS, address)
            put(Sms.BODY, text)
            put(Sms.DATE, System.currentTimeMillis())
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
            put(Sms.TYPE, Sms.MESSAGE_TYPE_OUTBOX)
            put(Sms.STATUS, Sms.STATUS_PENDING)
            put(Sms.SUBSCRIPTION_ID, subId)
            put(Sms.THREAD_ID, threadId)
        }
        if (resendId != null &&
            context.contentResolver.update(Sms.CONTENT_URI, values, "${Sms._ID} = ?", arrayOf(resendId.toString())) > 0
        ) {
            return resendId
        }
        val uri = context.contentResolver.insert(Sms.CONTENT_URI, values)
        return uri?.lastPathSegment?.toLongOrNull()
            ?: throw BridgeError("the SMS store refused the message (is Messēji the default SMS app?)")
    }

    /** The peer's report on a pseudo-SIM send, mapped onto the row so the usual ticks show it. */
    fun applyOutgoingStatus(context: Context, seq: Long, state: String, reason: String) {
        val store = BridgeStore.get(context)
        val row = store.outbound(seq) ?: throw BridgeError("unknown id: out:$seq")
        val ref = row.msgRef ?: throw BridgeError("no message for out:$seq")
        val id = refId(ref)
        val resolver = context.contentResolver
        if (isMms(ref)) {
            val box = when (state) {
                Bridge.STATE_SENT, Bridge.STATE_DELIVERED -> Mms.MESSAGE_BOX_SENT
                Bridge.STATE_FAILED -> Mms.MESSAGE_BOX_FAILED
                else -> Mms.MESSAGE_BOX_OUTBOX
            }
            resolver.update("${Mms.CONTENT_URI}/$id".toUri(), ContentValues().apply { put(Mms.MESSAGE_BOX, box) }, null, null)
        } else {
            val values = ContentValues()
            when (state) {
                Bridge.STATE_SENT -> {
                    values.put(Sms.TYPE, Sms.MESSAGE_TYPE_SENT)
                    values.put(Sms.STATUS, Sms.STATUS_PENDING)
                }
                Bridge.STATE_DELIVERED -> {
                    values.put(Sms.TYPE, Sms.MESSAGE_TYPE_SENT)
                    values.put(Sms.STATUS, Sms.STATUS_COMPLETE)
                    values.put(Sms.DATE_SENT, System.currentTimeMillis())
                }
                Bridge.STATE_FAILED -> {
                    values.put(Sms.TYPE, Sms.MESSAGE_TYPE_FAILED)
                    values.put(Sms.STATUS, Sms.STATUS_FAILED)
                }
                else -> {
                    values.put(Sms.TYPE, Sms.MESSAGE_TYPE_OUTBOX)
                    values.put(Sms.STATUS, Sms.STATUS_PENDING)
                }
            }
            resolver.update("${Sms.CONTENT_URI}/$id".toUri(), values, null, null)
            values.getAsInteger(Sms.TYPE)?.let { context.messagesDB.updateType(id, it) }
            values.getAsInteger(Sms.STATUS)?.let { context.messagesDB.updateStatus(id, it) }
        }
        store.updateOutbound(seq, state, reason)
        if (state == Bridge.STATE_FAILED) {
            val name = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
                context.getNameFromAddress(row.recipient, it)
            }
            context.notificationHelper.showSendingFailedNotification(name, context.getThreadId(row.recipient))
        }
        refreshMessages()
        refreshConversations()
    }

    // ---- writing an MMS row ----------------------------------------------------------------------

    @SuppressLint("NewApi")
    @Suppress("LongParameterList")
    private fun insertMms(
        context: Context, box: Int, messageType: Int, subId: Int, threadId: Long, dateMillis: Long,
        read: Boolean, from: String, to: String, body: String, parts: List<PartInput>,
    ): Long {
        val resolver = context.contentResolver
        val seconds = dateMillis / 1000
        val values = ContentValues().apply {
            put(Mms.THREAD_ID, threadId)
            put(Mms.DATE, seconds)
            put(Mms.DATE_SENT, seconds)
            put(Mms.READ, if (read) 1 else 0)
            put(Mms.SEEN, if (read) 1 else 0)
            put(Mms.MESSAGE_BOX, box)
            put(Mms.MESSAGE_TYPE, messageType)
            put(Mms.MMS_VERSION, PduHeaders.CURRENT_MMS_VERSION)
            put(Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
            put(Mms.SUBSCRIPTION_ID, subId)
            put(Mms.TEXT_ONLY, if (parts.isEmpty()) 1 else 0)
        }
        val id = resolver.insert(Mms.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
            ?: throw BridgeError("the MMS store refused the message (is Messēji the default SMS app?)")

        val addrUri = "content://mms/$id/addr".toUri()
        listOf(from to PduHeaders.FROM, to to PduHeaders.TO).forEach { (address, type) ->
            resolver.insert(addrUri, ContentValues().apply {
                put(Mms.Addr.MSG_ID, id)
                put(Mms.Addr.ADDRESS, address)
                put(Mms.Addr.TYPE, type)
                put(Mms.Addr.CHARSET, CHARSET_UTF8)
            })
        }

        val partUri = "content://mms/$id/part".toUri()
        var seq = 0
        if (body.isNotEmpty()) {
            resolver.insert(partUri, ContentValues().apply {
                put(Mms.Part.MSG_ID, id)
                put(Mms.Part.SEQ, seq++)
                put(Mms.Part.CONTENT_TYPE, "text/plain")
                put(Mms.Part.CHARSET, CHARSET_UTF8)
                put(Mms.Part.CONTENT_ID, "<text_0>")
                put(Mms.Part.CONTENT_LOCATION, "text_0.txt")
                put(Mms.Part.TEXT, body)
            })
        }
        parts.forEachIndexed { index, part ->
            val name = part.name.ifEmpty { "part_$index" }
            val stored = resolver.insert(partUri, ContentValues().apply {
                put(Mms.Part.MSG_ID, id)
                put(Mms.Part.SEQ, seq++)
                put(Mms.Part.CONTENT_TYPE, part.type)
                put(Mms.Part.NAME, name)
                put(Mms.Part.FILENAME, name)
                put(Mms.Part.CONTENT_ID, "<$name>")
                put(Mms.Part.CONTENT_LOCATION, name)
            }) ?: throw BridgeError("the MMS store refused part $index")
            val input = part.open() ?: throw BridgeError("cannot read part $index")
            input.use { src ->
                resolver.openOutputStream(stored)?.use { dst -> src.copyTo(dst) }
                    ?: throw BridgeError("cannot write part $index")
            }
        }
        return id
    }

    // ---- reading -----------------------------------------------------------------------------------

    /** A stored message as flat strings — what `get_message` / `get_outgoing` return. */
    @SuppressLint("MissingPermission")
    fun describe(context: Context, ref: String): Map<String, String> {
        val id = refId(ref)
        val out = LinkedHashMap<String, String>()
        out["msg_ref"] = ref
        if (isMms(ref)) {
            context.contentResolver.query(
                "${Mms.CONTENT_URI}/$id".toUri(),
                arrayOf(Mms.DATE, Mms.MESSAGE_BOX, Mms.SUBSCRIPTION_ID, Mms.THREAD_ID, Mms.READ),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) throw BridgeError("message gone: $ref")
                out["kind"] = "mms"
                out["timestamp"] = (c.getLong(0) * 1000).toString()
                out["box"] = c.getInt(1).toString()
                out["sub_id"] = c.getInt(2).toString()
                out["thread_id"] = c.getLong(3).toString()
                out["read"] = c.getInt(4).toString()
            } ?: throw BridgeError("message gone: $ref")
            val addresses = mmsAddresses(context, id)
            out["from"] = addresses.firstOrNull { it.second == PduHeaders.FROM }?.first.orEmpty()
                .takeIf { it != INSERT_ADDRESS_TOKEN }.orEmpty()
            out["to"] = addresses.filter { it.second == PduHeaders.TO }.map { it.first }
                .filter { it != INSERT_ADDRESS_TOKEN }.joinToString(",")
            out["body"] = mmsText(context, id)
            out["part_count"] = parts(context, ref).size.toString()
        } else {
            context.contentResolver.query(
                "${Sms.CONTENT_URI}/$id".toUri(),
                arrayOf(Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.TYPE, Sms.SUBSCRIPTION_ID, Sms.THREAD_ID, Sms.READ, Sms.STATUS),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) throw BridgeError("message gone: $ref")
                val address = c.getString(0).orEmpty()
                val type = c.getInt(3)
                out["kind"] = "sms"
                out["from"] = if (type == Sms.MESSAGE_TYPE_INBOX) address else ""
                out["to"] = if (type == Sms.MESSAGE_TYPE_INBOX) "" else address
                out["body"] = c.getString(1).orEmpty()
                out["timestamp"] = c.getLong(2).toString()
                out["box"] = type.toString()
                out["sub_id"] = c.getInt(4).toString()
                out["thread_id"] = c.getLong(5).toString()
                out["read"] = c.getInt(6).toString()
                out["sms_status"] = c.getInt(7).toString()
                out["part_count"] = "0"
            } ?: throw BridgeError("message gone: $ref")
        }
        val subId = out["sub_id"]?.toIntOrNull() ?: -1
        out["slot"] = slotOf(context, subId)
        Bridge.transport(context, subId)?.let { out["transport_name"] = it.name }
        return out
    }

    @SuppressLint("MissingPermission")
    fun slotOf(context: Context, subId: Int): String {
        if (subId < 0 || subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return ""
        return runCatching {
            context.subscriptionManagerCompat().getActiveSubscriptionInfo(subId)?.simSlotIndex?.toString()
        }.getOrNull().orEmpty()
    }

    private fun mmsAddresses(context: Context, id: Long): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        context.contentResolver.query(
            "content://mms/$id/addr".toUri(), arrayOf(Mms.Addr.ADDRESS, Mms.Addr.TYPE), null, null, null,
        )?.use { c -> while (c.moveToNext()) out.add(c.getString(0).orEmpty() to c.getInt(1)) }
        return out
    }

    private fun mmsText(context: Context, id: Long): String {
        val texts = ArrayList<String>()
        context.contentResolver.query(
            "content://mms/part".toUri(), arrayOf(Mms.Part.CONTENT_TYPE, Mms.Part.TEXT),
            "${Mms.Part.MSG_ID} = ?", arrayOf(id.toString()), "${Mms.Part.SEQ} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(0) == "text/plain") c.getString(1)?.let { texts.add(it) }
            }
        }
        return texts.joinToString("\n")
    }

    /** The non-text, non-SMIL parts of a message, in order. An SMS has none. */
    fun parts(context: Context, ref: String): List<PartInfo> {
        if (!isMms(ref)) return emptyList()
        val id = refId(ref)
        val out = ArrayList<PartInfo>()
        context.contentResolver.query(
            "content://mms/part".toUri(),
            arrayOf(Mms.Part._ID, Mms.Part.CONTENT_TYPE, Mms.Part.NAME, Mms.Part.FILENAME, Mms.Part.CONTENT_LOCATION),
            "${Mms.Part.MSG_ID} = ?", arrayOf(id.toString()), "${Mms.Part.SEQ} ASC, ${Mms.Part._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getString(1).orEmpty()
                if (type == "text/plain" || type == "application/smil") continue
                val uri = "content://mms/part/${c.getLong(0)}".toUri()
                val name = listOf(c.getString(2), c.getString(3), c.getString(4))
                    .firstOrNull { !it.isNullOrBlank() }.orEmpty()
                val size = runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                }.getOrNull() ?: -1L
                out.add(PartInfo(uri, type, name, size))
            }
        }
        return out
    }

    fun openPart(context: Context, ref: String, index: Int): ParcelFileDescriptor {
        val part = parts(context, ref).getOrNull(index) ?: throw BridgeError("no part $index in $ref")
        return context.contentResolver.openFileDescriptor(part.uri, "r")
            ?: throw BridgeError("cannot open part $index of $ref")
    }
}
