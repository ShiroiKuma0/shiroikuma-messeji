package org.fossify.messages.bridge

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import android.util.Log
import org.fossify.messages.extensions.config
import org.json.JSONArray
import org.json.JSONObject

/**
 * The SMS bridge: Messēji as the user-facing end of a message transport it knows nothing about.
 *
 * Two roles, both driven by ONE pinned peer app (自由作業盤) through `AutomationProvider.call()`:
 *
 * - **Pseudo-SIMs** (the carried, SIM-less phone). A *transport* is a fake subscription with a
 *   reserved NEGATIVE id (≤ [MAX_PSEUDO_SUB_ID]: −100, −101, …), a name, a badge and a colour.
 *   Messages on it live in the system SMS/MMS store like any other — the `sub_id` column carries the
 *   transport — so threads, contacts, search, notifications and backup all just work. Sending on a
 *   transport writes the row as OUTBOX and hands its id to the peer; the peer reports back.
 * - **Gateway** (the service phones, real SIMs). Every received SMS/MMS is journalled and the peer
 *   is told its id; the peer can send from a chosen SIM and is told how it went.
 *
 * Broadcasts carry **ids only**, never content: the content is pulled through the authenticated
 * provider, so a broadcast that reaches the wrong place leaks nothing.
 */
object Bridge {
    const val TAG = "MessejiBridge"

    /** Bump when a method, key or broadcast changes incompatibly. */
    const val CONTRACT = 1

    /** Real subscription ids are ≥ 0 and −1 means "unknown", so transports start well clear of both. */
    const val MAX_PSEUDO_SUB_ID = -100

    /** Transports are set with ids −100 … −109 only: at most ten pseudo-SIMs. */
    const val MIN_TRANSPORT_SUB_ID = -109
    const val MAX_TRANSPORTS = MAX_PSEUDO_SUB_ID - MIN_TRANSPORT_SUB_ID + 1

    /** A badge is cut to this many characters; up to three render inside the card everywhere. */
    const val BADGE_MAX_CHARS = 3

    /** Additive revisions within [CONTRACT]; 2 = badge text/border colours and ten transports. */
    const val CONTRACT_REV = 2

    const val ACTION_OUTGOING = "shiroikuma.messeji.bridge.OUTGOING"
    const val ACTION_RECEIVED = "shiroikuma.messeji.bridge.RECEIVED"
    const val ACTION_STATUS = "shiroikuma.messeji.bridge.STATUS"

    const val EXTRA_ID = "id"
    const val EXTRA_SEQ = "seq"
    const val EXTRA_SUB_ID = "sub_id"
    const val EXTRA_KIND = "kind"
    const val EXTRA_STATE = "state"
    const val EXTRA_REASON = "reason"

    const val STATE_QUEUED = "queued"
    const val STATE_SENT = "sent"
    const val STATE_DELIVERED = "delivered"
    const val STATE_FAILED = "failed"

    private const val PREF_TRANSPORTS = "bridge_transports"
    private const val PREF_TRANSPORT_PEER = "bridge_transport_peer"
    private const val PREF_GATEWAY_PEER = "bridge_gateway_peer"

    fun isPseudo(subId: Int?) = subId != null && subId <= MAX_PSEUDO_SUB_ID

    // ---- transports (carried phone) -------------------------------------------------------------

    data class Transport(
        val subId: Int,
        val name: String,
        val badge: String,
        val color: Int?,
        val textColor: Int? = null,
        val borderColor: Int? = null,
    )

    fun transports(context: Context): List<Transport> = runCatching {
        val raw = context.config.getBridgeString(PREF_TRANSPORTS) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Transport(
                subId = o.getInt("sub_id"),
                name = o.optString("name"),
                badge = o.optString("badge"),
                color = if (o.has("color")) o.getInt("color") else null,
                textColor = if (o.has("text_color")) o.getInt("text_color") else null,
                borderColor = if (o.has("border_color")) o.getInt("border_color") else null,
            )
        }
    }.getOrDefault(emptyList())

    fun transport(context: Context, subId: Int) = transports(context).firstOrNull { it.subId == subId }

    fun setTransports(context: Context, list: List<Transport>, peer: String?) {
        val array = JSONArray()
        list.forEach { t ->
            array.put(JSONObject().apply {
                put("sub_id", t.subId)
                put("name", t.name)
                put("badge", t.badge)
                t.color?.let { put("color", it) }
                t.textColor?.let { put("text_color", it) }
                t.borderColor?.let { put("border_color", it) }
            })
        }
        context.config.setBridgeStrings(
            PREF_TRANSPORTS to array.toString(),
            PREF_TRANSPORT_PEER to if (list.isEmpty()) null else peer,
        )
    }

    fun transportPeer(context: Context): String? =
        context.config.getBridgeString(PREF_TRANSPORT_PEER)?.takeIf { it.isNotEmpty() }

    // What a received message's `from` may be. Anything else — a display line, a header with the SIM
    // and a timestamp in it — would become a conversation (and a filing rule) of its own.
    private val PHONE_SENDER = Regex("^\\+?[0-9][0-9 ()./-]{1,30}$")
    private val NAMED_SENDER = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N} .&'_-]{0,19}$")
    private val EMAIL_SENDER = Regex("^[^\\s@]{1,64}@[^\\s@]{1,190}\\.[^\\s@]{2,}$")

    fun isSenderAddress(value: String): Boolean =
        PHONE_SENDER.matches(value) || NAMED_SENDER.matches(value) || EMAIL_SENDER.matches(value)

    fun parseColor(value: String?): Int? = value?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { Color.parseColor(it) }.getOrNull()
    }

    fun colorString(color: Int?) = color?.let { String.format("#%06X", it and 0xFFFFFF) }.orEmpty()

    // ---- gateway (service phones) ---------------------------------------------------------------

    fun gatewayPeer(context: Context): String? =
        context.config.getBridgeString(PREF_GATEWAY_PEER)?.takeIf { it.isNotEmpty() }

    fun setGatewayPeer(context: Context, peer: String?) {
        context.config.setBridgeStrings(PREF_GATEWAY_PEER to peer)
    }

    // ---- hints -------------------------------------------------------------------------------------

    fun hint(context: Context, peer: String?, action: String, fill: Intent.() -> Unit) {
        peer ?: return
        runCatching {
            val intent = Intent(action).setPackage(peer).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            intent.fill()
            context.sendBroadcast(intent)
        }.onFailure { Log.w(TAG, "hint $action to $peer failed: $it") }
    }

    /**
     * Gateway hook: a real SMS/MMS was just stored. Journalled only when a gateway peer is set, and
     * never for a pseudo-SIM message (that would loop an injected message straight back out).
     */
    fun onReceived(context: Context, msgRef: String, subId: Int, kind: String) {
        if (isPseudo(subId)) return
        val peer = gatewayPeer(context) ?: return
        runCatching {
            val seq = BridgeStore.get(context).addInbound(msgRef, subId, remoteId = null)
            hint(context, peer, ACTION_RECEIVED) {
                putExtra(EXTRA_ID, "in:$seq")
                putExtra(EXTRA_SEQ, seq.toString())
                putExtra(EXTRA_SUB_ID, subId.toString())
                putExtra(EXTRA_KIND, kind)
            }
        }.onFailure { Log.w(TAG, "journal $msgRef failed: $it") }
    }

    /** Status hook for gateway sends, called by the SMS/MMS sent and delivered receivers. */
    fun onStatus(context: Context, msgRef: String, state: String, reason: String) {
        val store = BridgeStore.get(context)
        val row = store.outboundByRef(msgRef) ?: return
        store.updateOutbound(row.seq, state, reason)
        hint(context, gatewayPeer(context), ACTION_STATUS) {
            putExtra(EXTRA_ID, "out:${row.seq}")
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_REASON, reason)
        }
    }
}

/**
 * Bridge bookkeeping that the system store has no column for: which provider row an id stands for,
 * the transport's own message id (for idempotent injection), and outbound state with its reason.
 *
 * A separate plain SQLite file rather than a Room entity: it is not a cache of the provider, it must
 * not be wiped by a cache rebuild, and keeping it out of `MessagesDatabase` keeps that schema's
 * migrations untouched.
 */
class BridgeStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "bridge.db", null, 1) {

    data class Inbound(val seq: Long, val msgRef: String, val subId: Int, val remoteId: String?, val time: Long)
    data class Outbound(
        val seq: Long, val msgRef: String?, val subId: Int, val recipient: String,
        val state: String, val reason: String, val created: Long, val updated: Long,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE inbound (seq INTEGER PRIMARY KEY AUTOINCREMENT, msg_ref TEXT NOT NULL, " +
                "sub_id INTEGER NOT NULL, remote_id TEXT, time INTEGER NOT NULL, UNIQUE(sub_id, remote_id))"
        )
        db.execSQL("CREATE INDEX inbound_ref ON inbound(msg_ref)")
        db.execSQL(
            "CREATE TABLE outbound (seq INTEGER PRIMARY KEY AUTOINCREMENT, msg_ref TEXT, " +
                "sub_id INTEGER NOT NULL, recipient TEXT NOT NULL, state TEXT NOT NULL, reason TEXT NOT NULL, " +
                "created INTEGER NOT NULL, updated INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX outbound_ref ON outbound(msg_ref)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun addInbound(msgRef: String, subId: Int, remoteId: String?): Long =
        writableDatabase.insertOrThrow("inbound", null, ContentValues().apply {
            put("msg_ref", msgRef)
            put("sub_id", subId)
            put("remote_id", remoteId)
            put("time", System.currentTimeMillis())
        })

    fun inboundByRemote(subId: Int, remoteId: String): Inbound? =
        queryInbound("sub_id = ? AND remote_id = ?", arrayOf(subId.toString(), remoteId)).firstOrNull()

    fun inbound(seq: Long): Inbound? = queryInbound("seq = ?", arrayOf(seq.toString())).firstOrNull()

    fun inboundSince(afterSeq: Long, limit: Int): List<Inbound> =
        queryInbound("seq > ? AND sub_id > ${Bridge.MAX_PSEUDO_SUB_ID}", arrayOf(afterSeq.toString()), limit)

    private fun queryInbound(selection: String, args: Array<String>, limit: Int? = null): List<Inbound> {
        val out = ArrayList<Inbound>()
        readableDatabase.query(
            "inbound", null, selection, args, null, null, "seq ASC", limit?.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Inbound(
                        seq = c.getLong(c.getColumnIndexOrThrow("seq")),
                        msgRef = c.getString(c.getColumnIndexOrThrow("msg_ref")),
                        subId = c.getInt(c.getColumnIndexOrThrow("sub_id")),
                        remoteId = c.getString(c.getColumnIndexOrThrow("remote_id")),
                        time = c.getLong(c.getColumnIndexOrThrow("time")),
                    )
                )
            }
        }
        return out
    }

    @Synchronized
    fun addOutbound(msgRef: String?, subId: Int, recipient: String, state: String): Long {
        val now = System.currentTimeMillis()
        return writableDatabase.insertOrThrow("outbound", null, ContentValues().apply {
            put("msg_ref", msgRef)
            put("sub_id", subId)
            put("recipient", recipient)
            put("state", state)
            put("reason", "")
            put("created", now)
            put("updated", now)
        })
    }

    @Synchronized
    fun setOutboundRef(seq: Long, msgRef: String) {
        writableDatabase.update(
            "outbound", ContentValues().apply { put("msg_ref", msgRef) }, "seq = ?", arrayOf(seq.toString())
        )
    }

    @Synchronized
    fun updateOutbound(seq: Long, state: String, reason: String) {
        writableDatabase.update("outbound", ContentValues().apply {
            put("state", state)
            put("reason", reason)
            put("updated", System.currentTimeMillis())
        }, "seq = ?", arrayOf(seq.toString()))
    }

    fun outbound(seq: Long): Outbound? = queryOutbound("seq = ?", arrayOf(seq.toString())).firstOrNull()

    fun outboundByRef(msgRef: String): Outbound? =
        queryOutbound("msg_ref = ?", arrayOf(msgRef)).lastOrNull()

    fun outboundQueued(pseudoOnly: Boolean): List<Outbound> {
        val sub = if (pseudoOnly) " AND sub_id <= ${Bridge.MAX_PSEUDO_SUB_ID}" else ""
        return queryOutbound("state = ?$sub", arrayOf(Bridge.STATE_QUEUED))
    }

    private fun queryOutbound(selection: String, args: Array<String>): List<Outbound> {
        val out = ArrayList<Outbound>()
        readableDatabase.query("outbound", null, selection, args, null, null, "seq ASC").use { c ->
            while (c.moveToNext()) {
                out.add(
                    Outbound(
                        seq = c.getLong(c.getColumnIndexOrThrow("seq")),
                        msgRef = c.getString(c.getColumnIndexOrThrow("msg_ref")),
                        subId = c.getInt(c.getColumnIndexOrThrow("sub_id")),
                        recipient = c.getString(c.getColumnIndexOrThrow("recipient")),
                        state = c.getString(c.getColumnIndexOrThrow("state")),
                        reason = c.getString(c.getColumnIndexOrThrow("reason")),
                        created = c.getLong(c.getColumnIndexOrThrow("created")),
                        updated = c.getLong(c.getColumnIndexOrThrow("updated")),
                    )
                )
            }
        }
        return out
    }

    companion object {
        @Volatile
        private var instance: BridgeStore? = null

        fun get(context: Context): BridgeStore =
            instance ?: synchronized(this) { instance ?: BridgeStore(context).also { instance = it } }
    }
}
