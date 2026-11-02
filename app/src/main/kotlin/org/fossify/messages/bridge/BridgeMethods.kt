package org.fossify.messages.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.Telephony
import android.util.Log
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Transaction
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.smsSender
import org.fossify.messages.extensions.subscriptionManagerCompat
import org.fossify.messages.messaging.getSendMessageSettings
import org.fossify.messages.receivers.MmsSentReceiver

/**
 * The bridge's half of `AutomationProvider.call()`. The caller has already been identified (exact
 * package, uid, pinned certificate) before anything here runs.
 *
 * Every answer is a flat Bundle: Strings, String[] and — for lists, under `items` — Bundle[] of
 * Strings, plus `status` = `OK` | `ERROR` and `error`. A descriptor, where one is returned, is under
 * `fd` at the top level.
 */
@Suppress("TooManyFunctions")
object BridgeMethods {

    const val EXTRA_BRIDGE_SEQ = "bridge_out_seq"

    val METHODS = listOf(
        "bridge_describe", "set_transports", "get_transports", "set_gateway",
        "inject_message", "outgoing_pending", "get_outgoing", "report_status",
        "get_message", "messages_since", "get_part", "send_message", "get_status",
    )

    /** @return the answer, or null when [method] is not a bridge method. */
    fun handle(context: Context, method: String, arg: String?, extras: Bundle?, caller: String): Bundle? {
        if (method !in METHODS) return null
        val e = extras ?: Bundle()
        return try {
            when (method) {
                "bridge_describe" -> describe(context)
                "set_transports" -> setTransports(context, e, caller)
                "get_transports" -> getTransports(context)
                "set_gateway" -> setGateway(context, arg ?: e.str("enabled"), caller)
                "inject_message" -> inject(context, e)
                "outgoing_pending" -> outgoingPending(context)
                "get_outgoing" -> getOutgoing(context, arg ?: e.str("id"))
                "report_status" -> reportStatus(context, arg ?: e.str("id"), e)
                "get_message" -> getMessage(context, arg ?: e.str("id"))
                "messages_since" -> messagesSince(context, arg ?: e.str("after"), e)
                "get_part" -> getPart(context, arg ?: e.str("id"), e)
                "send_message" -> sendMessage(context, e)
                "get_status" -> getStatus(context, arg ?: e.str("id"))
                else -> null
            }
        } catch (error: BridgeError) {
            fail(error.message.orEmpty())
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(Bridge.TAG, "$method failed", error)
            fail("${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        }
    }

    // ---- shared ------------------------------------------------------------------------------------

    fun ok(fill: Bundle.() -> Unit = {}) = Bundle().apply {
        putString("status", "OK")
        putString("error", "")
        putString("result", "OK")
        fill()
    }

    fun fail(why: String) = Bundle().apply {
        putString("status", "ERROR")
        putString("error", why)
        putString("result", "ERROR:$why")
    }

    /** Lenient read: a caller may send a number as a String or as a number. */
    private fun Bundle.str(key: String): String? = @Suppress("DEPRECATION") get(key)?.toString()

    private fun Bundle.strings(key: String): List<String> {
        getStringArray(key)?.let { return it.map { s -> s.orEmpty() } }
        getStringArrayList(key)?.let { return it.map { s -> s.orEmpty() } }
        return str(key)?.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
    }

    private fun Bundle.putMap(map: Map<String, String>) = map.forEach { (k, v) -> putString(k, v) }

    private fun subIdOf(e: Bundle): Int =
        (e.str("sub_id") ?: e.str("transport"))?.trim()?.toIntOrNull() ?: throw BridgeError("sub_id missing")

    private fun partsOf(context: Context, e: Bundle): List<PartInput> {
        val uris = e.strings("part_uris")
        val types = e.strings("part_types")
        val names = e.strings("part_names")
        return uris.mapIndexed { i, uri ->
            BridgeMessages.partFromUriString(context, uri, types.getOrNull(i).orEmpty(), names.getOrNull(i).orEmpty())
        }
    }

    private fun Bundle.putParts(context: Context, ref: String) {
        val parts = BridgeMessages.parts(context, ref)
        putString("part_count", parts.size.toString())
        putStringArray("part_types", parts.map { it.type }.toTypedArray())
        putStringArray("part_names", parts.map { it.name }.toTypedArray())
        putStringArray("part_sizes", parts.map { it.size.toString() }.toTypedArray())
    }

    private fun seqOf(id: String?, prefix: String): Long =
        id?.trim()?.removePrefix(prefix)?.toLongOrNull() ?: throw BridgeError("bad id: $id (want $prefix<n>)")

    // ---- discovery & configuration ---------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun describe(context: Context) = ok {
        putString("contract", Bridge.CONTRACT.toString())
        putString("contract_rev", Bridge.CONTRACT_REV.toString())
        putString("max_transports", Bridge.MAX_TRANSPORTS.toString())
        putString("transport_sub_id_range", "${Bridge.MIN_TRANSPORT_SUB_ID}..${Bridge.MAX_PSEUDO_SUB_ID}")
        putString("badge_max_chars", Bridge.BADGE_MAX_CHARS.toString())
        putString("app_id", context.packageName)
        putString(
            "default_sms_app",
            (Telephony.Sms.getDefaultSmsPackage(context) == context.packageName).toString()
        )
        putStringArray("methods", METHODS.toTypedArray())
        putString("transport_peer", Bridge.transportPeer(context).orEmpty())
        putString("gateway_peer", Bridge.gatewayPeer(context).orEmpty())
        putString("transport_count", Bridge.transports(context).size.toString())
        val subs = runCatching { context.subscriptionManagerCompat().activeSubscriptionInfoList }.getOrNull().orEmpty()
        putStringArray("sim_sub_ids", subs.map { it.subscriptionId.toString() }.toTypedArray())
        putStringArray("sim_slots", subs.map { it.simSlotIndex.toString() }.toTypedArray())
        putStringArray("sim_names", subs.map { it.displayName?.toString().orEmpty() }.toTypedArray())
        @Suppress("DEPRECATION")
        putStringArray("sim_numbers", subs.map { it.number.orEmpty() }.toTypedArray())
    }

    private fun setTransports(context: Context, e: Bundle, caller: String): Bundle {
        val ids = e.strings("sub_ids")
        val names = e.strings("names")
        val badges = e.strings("badges")
        val colors = e.strings("colors")
        val textColors = e.strings("text_colors")
        val borderColors = e.strings("border_colors")
        // One entry per transport, each a comma-separated list of E.164 DIDs (possibly empty).
        val didLists = e.strings("dids").map { entry ->
            entry.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }
        if (ids.size > Bridge.MAX_TRANSPORTS) throw BridgeError("at most ${Bridge.MAX_TRANSPORTS} transports")
        val list = ids.mapIndexed { i, raw ->
            val subId = raw.trim().toIntOrNull() ?: throw BridgeError("bad sub_id: $raw")
            if (subId !in Bridge.MIN_TRANSPORT_SUB_ID..Bridge.MAX_PSEUDO_SUB_ID) {
                throw BridgeError("transport sub_id must be ${Bridge.MIN_TRANSPORT_SUB_ID}…${Bridge.MAX_PSEUDO_SUB_ID}: $subId")
            }
            val name = names.getOrNull(i).orEmpty().ifEmpty { "Transport ${i + 1}" }
            Bridge.Transport(
                subId = subId,
                name = name,
                badge = badges.getOrNull(i).orEmpty().trim().ifEmpty { name.take(2) }.take(Bridge.BADGE_MAX_CHARS),
                color = Bridge.parseColor(colors.getOrNull(i)),
                textColor = Bridge.parseColor(textColors.getOrNull(i)),
                borderColor = Bridge.parseColor(borderColors.getOrNull(i)),
                dids = didLists.getOrNull(i).orEmpty(),
            )
        }
        if (list.map { it.subId }.toSet().size != list.size) throw BridgeError("duplicate sub_id")
        // A DID answers on exactly one line, or a reply "from" it would have to guess.
        list.flatMap { t -> t.dids.map { it to t.subId } }.groupBy({ it.first }, { it.second })
            .entries.firstOrNull { it.value.size > 1 }
            ?.let { (did, subs) -> throw BridgeError("DID $did is listed under more than one transport (${subs.joinToString(",")})") }
        Bridge.setTransports(context, list, caller)
        return getTransports(context)
    }

    private fun getTransports(context: Context): Bundle {
        val list = Bridge.transports(context)
        return ok {
            putString("peer", Bridge.transportPeer(context).orEmpty())
            putStringArray("sub_ids", list.map { it.subId.toString() }.toTypedArray())
            putStringArray("names", list.map { it.name }.toTypedArray())
            putStringArray("badges", list.map { it.badge }.toTypedArray())
            putStringArray("colors", list.map { Bridge.colorString(it.color) }.toTypedArray())
            putStringArray("text_colors", list.map { Bridge.colorString(it.textColor) }.toTypedArray())
            putStringArray("border_colors", list.map { Bridge.colorString(it.borderColor) }.toTypedArray())
            putStringArray("dids", list.map { it.dids.joinToString(",") }.toTypedArray())
        }
    }

    private fun setGateway(context: Context, enabled: String?, caller: String): Bundle {
        val on = when (enabled?.trim()?.lowercase()) {
            "on", "true", "1", "yes" -> true
            "off", "false", "0", "no" -> false
            else -> throw BridgeError("set_gateway wants arg on|off")
        }
        Bridge.setGatewayPeer(context, if (on) caller else null)
        return ok { putString("gateway_peer", Bridge.gatewayPeer(context).orEmpty()) }
    }

    // ---- carried phone: pseudo-SIM in and out ----------------------------------------------------

    private fun inject(context: Context, e: Bundle): Bundle {
        val subId = subIdOf(e)
        if (Bridge.transport(context, subId) == null) throw BridgeError("no such transport: $subId")
        val from = e.str("from")?.trim().orEmpty().ifEmpty { throw BridgeError("from missing") }
        if (!Bridge.isSenderAddress(from)) {
            throw BridgeError("from is not a sender address (a phone number, short code, sender name or e-mail): $from")
        }
        val remoteId = e.str("remote_id")?.trim().orEmpty().ifEmpty { throw BridgeError("remote_id missing") }
        val body = e.str("body").orEmpty()
        val timestamp = e.str("timestamp")?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: System.currentTimeMillis()
        val parts = partsOf(context, e)
        if (body.isEmpty() && parts.isEmpty()) throw BridgeError("nothing to store: no body and no parts")

        val (seq, duplicate, blocked) = BridgeMessages.inject(context, subId, from, body, timestamp, remoteId, parts)
        return ok {
            putString("id", if (seq > 0) "in:$seq" else "")
            putString("duplicate", duplicate.toString())
            putString("blocked", blocked.toString())
            if (seq > 0) BridgeStore.get(context).inbound(seq)?.let { putString("msg_ref", it.msgRef) }
        }
    }

    private fun outgoingPending(context: Context): Bundle {
        val rows = BridgeStore.get(context).outboundQueued(pseudoOnly = true)
        return ok {
            putStringArray("ids", rows.map { "out:${it.seq}" }.toTypedArray())
            putParcelableArray("items", rows.map { row ->
                Bundle().apply {
                    putString("id", "out:${row.seq}")
                    putString("sub_id", row.subId.toString())
                    putString("to", row.recipient)
                    putString("msg_ref", row.msgRef.orEmpty())
                    putString("created", row.created.toString())
                }
            }.toTypedArray())
        }
    }

    private fun getOutgoing(context: Context, id: String?): Bundle {
        val seq = seqOf(id, "out:")
        val row = BridgeStore.get(context).outbound(seq) ?: throw BridgeError("unknown id: $id")
        val ref = row.msgRef ?: throw BridgeError("no message for $id")
        return ok {
            putMap(BridgeMessages.describe(context, ref))
            putString("id", "out:$seq")
            putString("to", row.recipient)
            putString("state", row.state)
            putString("reason", row.reason)
            putParts(context, ref)
        }
    }

    private fun reportStatus(context: Context, id: String?, e: Bundle): Bundle {
        val seq = seqOf(id, "out:")
        val state = e.str("state")?.trim()?.lowercase().orEmpty()
        val known = listOf(Bridge.STATE_QUEUED, Bridge.STATE_SENT, Bridge.STATE_DELIVERED, Bridge.STATE_FAILED)
        if (state !in known) throw BridgeError("state must be one of ${known.joinToString("|")}")
        val row = BridgeStore.get(context).outbound(seq) ?: throw BridgeError("unknown id: $id")
        if (!Bridge.isPseudo(row.subId)) throw BridgeError("$id is a SIM send; its status comes from the radio")
        BridgeMessages.applyOutgoingStatus(context, seq, state, e.str("reason").orEmpty())
        return ok { putString("id", "out:$seq"); putString("state", state) }
    }

    // ---- service phones: receive, catch up, send -------------------------------------------------

    private fun getMessage(context: Context, id: String?): Bundle {
        val ref = BridgeMessages.resolveRef(context, id)
        return ok {
            putMap(BridgeMessages.describe(context, ref))
            putString("id", id.orEmpty())
            putParts(context, ref)
        }
    }

    private fun messagesSince(context: Context, after: String?, e: Bundle): Bundle {
        val afterSeq = after?.trim()?.removePrefix("in:")?.toLongOrNull() ?: 0L
        val limit = e.str("limit")?.toIntOrNull()?.coerceIn(1, 500) ?: 50
        val rows = BridgeStore.get(context).inboundSince(afterSeq, limit)
        val items = rows.mapNotNull { row ->
            runCatching {
                Bundle().apply {
                    putMap(BridgeMessages.describe(context, row.msgRef))
                    putString("id", "in:${row.seq}")
                    putString("seq", row.seq.toString())
                }
            }.getOrElse {
                Bundle().apply {
                    putString("id", "in:${row.seq}")
                    putString("seq", row.seq.toString())
                    putString("msg_ref", row.msgRef)
                    putString("gone", "true")
                }
            }
        }
        return ok {
            putParcelableArray("items", items.toTypedArray())
            putString("count", items.size.toString())
            putString("last_seq", (rows.lastOrNull()?.seq ?: afterSeq).toString())
        }
    }

    private fun getPart(context: Context, id: String?, e: Bundle): Bundle {
        val ref = BridgeMessages.resolveRef(context, id)
        val index = e.str("index")?.trim()?.toIntOrNull() ?: 0
        val part = BridgeMessages.parts(context, ref).getOrNull(index) ?: throw BridgeError("no part $index in $id")
        val fd: ParcelFileDescriptor = BridgeMessages.openPart(context, ref, index)
        return ok {
            putParcelable("fd", fd)
            putString("content_type", part.type)
            putString("name", part.name)
            putString("size", part.size.toString())
        }
    }

    /**
     * Send from a real SIM (`sub_id`, the service phones' gateway) or from a pseudo-SIM: `from_did`
     * names the line by one of its DIDs, or `sub_id` names a transport directly. A pseudo-SIM send is
     * exactly what the compose screen does on that transport — OUTBOX row, `queued`, OUTGOING hint
     * to the transport peer — and answers `id=out:<seq>`, `state=queued`.
     *
     * When both `from_did` and `sub_id` are given they must name the same line; a disagreement is an
     * error rather than a silent pick, since either one winning could send from the wrong number.
     */
    @SuppressLint("MissingPermission")
    private fun sendMessage(context: Context, e: Bundle): Bundle {
        val fromDid = e.str("from_did")?.trim().orEmpty()
        val subId = if (fromDid.isNotEmpty()) {
            val line = Bridge.transportForDid(context, fromDid) ?: throw BridgeError("no line for DID $fromDid")
            e.str("sub_id")?.trim()?.toIntOrNull()?.let { given ->
                if (given != line.subId) throw BridgeError("sub_id $given disagrees with from_did $fromDid (line ${line.subId})")
            }
            line.subId
        } else {
            subIdOf(e)
        }
        if (Bridge.isPseudo(subId)) return sendOnTransport(context, e, subId)
        val subs = context.subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()
        if (subs.none { it.subscriptionId == subId }) {
            throw BridgeError("no active SIM with sub_id $subId (have ${subs.joinToString(",") { it.subscriptionId.toString() }})")
        }
        val to = e.str("to")?.trim().orEmpty().ifEmpty { throw BridgeError("to missing") }
        val body = e.str("body").orEmpty()
        val parts = partsOf(context, e)
        if (body.isEmpty() && parts.isEmpty()) throw BridgeError("nothing to send: no body and no parts")
        val store = BridgeStore.get(context)

        if (parts.isEmpty()) {
            val threadId = context.getThreadId(to)
            val smsId = BridgeMessages.writeSimOutgoingSms(context, to, body, subId, threadId)
            val seq = store.addOutbound("sms:$smsId", subId, to, Bridge.STATE_QUEUED)
            try {
                context.smsSender.sendMessage(
                    subId = subId, destination = to, body = body, serviceCenter = null,
                    requireDeliveryReport = true,
                    messageUri = android.net.Uri.parse("${Telephony.Sms.CONTENT_URI}/$smsId"),
                )
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                context.contentResolver.update(
                    android.net.Uri.parse("${Telephony.Sms.CONTENT_URI}/$smsId"),
                    android.content.ContentValues().apply { put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED) },
                    null, null,
                )
                Bridge.onStatus(context, "sms:$smsId", Bridge.STATE_FAILED, error.message ?: error.javaClass.simpleName)
            }
            return ok { putString("id", "out:$seq"); putString("msg_ref", "sms:$smsId") }
        }

        val seq = store.addOutbound(null, subId, to, Bridge.STATE_QUEUED)
        val settings = context.getSendMessageSettings()
        settings.subscriptionId = subId
        val message = Message(body, arrayOf(to))
        parts.forEachIndexed { index, part ->
            val bytes = part.open()?.use { it.readBytes() } ?: throw BridgeError("cannot read part $index")
            val name = part.name.ifEmpty { "part_$index" }
            message.addMedia(bytes, part.type, name, name)
        }
        val transaction = Transaction(context, settings)
        transaction.setExplicitBroadcastForSentMms(
            Intent(context, MmsSentReceiver::class.java).putExtra(EXTRA_BRIDGE_SEQ, seq)
        )
        try {
            transaction.sendNewMessage(message)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            store.updateOutbound(seq, Bridge.STATE_FAILED, error.message ?: error.javaClass.simpleName)
            throw BridgeError("MMS send failed: ${error.message}")
        }
        return ok { putString("id", "out:$seq") }
    }

    private fun sendOnTransport(context: Context, e: Bundle, subId: Int): Bundle {
        if (Bridge.transport(context, subId) == null) throw BridgeError("no such transport: $subId")
        // Passed through as the compose path would take it: no country code is invented.
        val to = e.str("to")?.trim().orEmpty().ifEmpty { throw BridgeError("to missing") }
        val body = e.str("body").orEmpty()
        if (e.strings("part_uris").isNotEmpty()) throw BridgeError("parts are not supported on a pseudo-SIM send")
        if (body.isEmpty()) throw BridgeError("nothing to send: no body")
        val seq = BridgeMessages.queueOutgoing(context, body, listOf(to), subId, emptyList(), resendId = null).single()
        val row = BridgeStore.get(context).outbound(seq)
        return ok {
            putString("id", "out:$seq")
            putString("state", Bridge.STATE_QUEUED)
            putString("sub_id", subId.toString())
            putString("msg_ref", row?.msgRef.orEmpty())
        }
    }

    private fun getStatus(context: Context, id: String?): Bundle {
        val seq = seqOf(id, "out:")
        val row = BridgeStore.get(context).outbound(seq) ?: throw BridgeError("unknown id: $id")
        return ok {
            putString("id", "out:$seq")
            putString("state", row.state)
            putString("reason", row.reason)
            putString("msg_ref", row.msgRef.orEmpty())
            putString("sub_id", row.subId.toString())
            putString("to", row.recipient)
            putString("updated", row.updated.toString())
        }
    }
}
