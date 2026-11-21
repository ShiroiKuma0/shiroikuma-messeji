package org.fossify.messages.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.widget.Toast
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.CallConfirmationDialog
import org.fossify.commons.extensions.isPackageInstalled
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.PERMISSION_CALL_PHONE
import org.fossify.commons.helpers.PERMISSION_READ_PHONE_STATE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.extensions.ThemeSlot
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.dialNumber
import org.fossify.messages.extensions.themeColor
import org.json.JSONArray
import org.json.JSONObject

// Fork: calling out of a conversation by a call route — a SIM, or an account of 白い熊 臨電話. Ported
// from 白い熊 連絡先 (helpers/Rindenwa.kt there); the route strings, the stored keys and Rindenwa's
// call door are the same, so the two apps mean the same thing by "sim1" or "rin:3". Keep the door's
// names in sync with Rindenwa.

/**
 * One SIP account of 白い熊 臨電話. [number] is the 臨 number 白い熊 gave it there (0–9, unique), or -1
 * when it has none; [isDefault] marks the account selected in Rindenwa. [badgeColor] and
 * [badgeTextColor] are that account's own badge colours (ARGB), null from an older Rindenwa.
 */
data class RinAccount(
    val number: Int,
    val identity: String,
    val name: String,
    val registered: Boolean,
    val isDefault: Boolean,
    val badgeColor: Int? = null,
    val badgeTextColor: Int? = null,
) {
    val badge: String get() = rinBadge(number)
    val label: String get() = name.ifEmpty { identity.removePrefix("sip:") }
}

/** The badge text of a 臨 account: 臨0 … 臨9, or a bare 臨 when the number is unknown. */
fun rinBadge(number: Int) = if (number >= 0) "$RIN_MARK$number" else RIN_MARK

const val RIN_MARK = "臨"
private const val RESULT_KEY = "result"
private const val RESULT_OK = "OK"
private const val RESULT_ERROR_PREFIX = "ERROR:"
private const val ACCOUNTS_KEY = "accounts"

/**
 * 白い熊 臨電話's call door (authority [RINDENWA_CALLS_AUTHORITY], ContentResolver.call()):
 *  - "accounts" → { result: "OK", accounts: JSON array of {number, identity, name, registered, default,
 *    badge_color, badge_text_color} }
 *  - "dial", extras { to, account? (a 臨 number as a string) } → { result: "OK" | "ERROR:<why>" }
 * Rindenwa admits only the packages on its own allow-list (its UI settings). Every call here crosses
 * processes and may have to start Rindenwa, so none of it runs on the main thread.
 */
object Rindenwa {
    private val callsUri: Uri = Uri.parse("content://$RINDENWA_CALLS_AUTHORITY")

    @Volatile
    private var memory: List<RinAccount>? = null

    /** True when Rindenwa answered the last question with "not allowed": メッセージ is not on its list of callers. */
    @Volatile
    var refused = false
        private set

    fun isInstalled(context: Context) = context.isPackageInstalled(RINDENWA_PACKAGE)

    /** The account list as last heard from Rindenwa — instantly, from memory or the stored copy. */
    fun cachedAccounts(context: Context): List<RinAccount> {
        memory?.let { return it }
        return parseAccounts(context.config.rinAccountsCache).also { memory = it }
    }

    /** Asks Rindenwa for its accounts. Blocking; null when it is missing, refuses us or does not answer. */
    fun refreshAccounts(context: Context): List<RinAccount>? {
        if (!isInstalled(context)) return null
        val bundle = call(context, "accounts", null) ?: return null
        val result = bundle.getString(RESULT_KEY)
        refused = result == RESULT_ERROR_PREFIX + "not allowed"
        if (result != RESULT_OK) return null
        val json = bundle.getString(ACCOUNTS_KEY) ?: return null
        val accounts = parseAccounts(json)
        memory = accounts
        context.config.rinAccountsCache = json
        return accounts
    }

    /**
     * Places a call to [to] through the 臨 account numbered [account], or through the one selected in
     * Rindenwa when [account] is negative. Blocking. Returns null when the call was placed, else the reason.
     */
    fun dial(context: Context, to: String, account: Int): String? {
        val extras = Bundle().apply {
            putString("to", to)
            if (account >= 0) putString("account", account.toString())
        }
        val result = call(context, "dial", extras)?.getString(RESULT_KEY) ?: return ""
        return if (result == RESULT_OK) null else result.removePrefix(RESULT_ERROR_PREFIX)
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a missing or refusing provider is an answer
    private fun call(context: Context, method: String, extras: Bundle?): Bundle? = try {
        context.contentResolver.call(callsUri, method, null, extras)
    } catch (ignored: Exception) {
        null
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun parseAccounts(json: String): List<RinAccount> = try {
        val array = JSONArray(json.ifEmpty { "[]" })
        (0 until array.length()).map { index ->
            val o: JSONObject = array.getJSONObject(index)
            RinAccount(
                number = o.optInt("number", -1),
                identity = o.optString("identity"),
                name = o.optString("name"),
                registered = o.optBoolean("registered"),
                isDefault = o.optBoolean("default"),
                // ARGB; read as a long so a value written unsigned still lands on the same int
                badgeColor = if (o.has("badge_color")) o.optLong("badge_color").toInt() else null,
                badgeTextColor = if (o.has("badge_text_color")) o.optLong("badge_text_color").toInt() else null,
            )
        }
    } catch (ignored: Exception) {
        emptyList()
    }
}

// ---- Call routes: sim1, sim2, rin (臨, through the default account), rin:N, off (nothing set) ----

fun isRinRoute(route: String) = route == ROUTE_RIN || route.startsWith(ROUTE_RIN_PREFIX)

/** The 臨 account a rin route dials through: its own N; negative for the plain route = the one selected in Rindenwa. */
fun rinAccountOf(route: String): Int =
    route.removePrefix(ROUTE_RIN_PREFIX).toIntOrNull() ?: RIN_ACCOUNT_SELECTED

/** The route 白い熊 chose for [number] in this app, or [ROUTE_OFF] when none was chosen. */
fun Context.ownRouteOf(number: String): String = when {
    config.getRinIsDefault(number) -> {
        val account = config.getRinAccount(number)
        if (account >= 0) ROUTE_RIN_PREFIX + account else ROUTE_RIN
    }

    config.getSimSlot(number) == 1 -> ROUTE_SIM1
    config.getSimSlot(number) == 2 -> ROUTE_SIM2
    else -> ROUTE_OFF
}

/** Stores [route] for [number]; [ROUTE_OFF] clears it, so the number follows the defaults again. */
fun Context.setOwnRoute(number: String, route: String) {
    val rin = isRinRoute(route)
    config.setSimSlot(
        number,
        when (route) {
            ROUTE_SIM1 -> 1
            ROUTE_SIM2 -> 2
            else -> 0
        }
    )
    config.setRinIsDefault(number, rin)
    config.setRinAccount(number, if (rin) route.removePrefix(ROUTE_RIN_PREFIX).toIntOrNull() ?: RIN_ACCOUNT_SELECTED else RIN_ACCOUNT_SELECTED)
}

// ---- The route door of 白い熊 連絡先 (from 1.6.0+133): one route per number, kept there ----
//
// ContentResolver.call() on its contacts provider: "get_route" (arg = the number) answers
// { result: "OK", route }, "set_route" (extras { number, route }) stores it; "off" is no route. An
// older 連絡先 answers null, a missing one throws: both mean "no door", and the routes kept in this
// app's own settings are used instead. Crosses processes: never on the main thread.

private const val DOOR_GET = "get_route"
private const val DOOR_SET = "set_route"
private const val DOOR_ROUTE = "route"
private const val DOOR_NUMBER = "number"

@Suppress("TooGenericExceptionCaught", "SwallowedException") // 連絡先 missing or older is an answer
private fun Context.doorCall(method: String, arg: String?, extras: Bundle?): Bundle? {
    RENRAKUSAKI_AUTHORITIES.forEach { authority ->
        val answer = try {
            contentResolver.call(Uri.parse("content://$authority"), method, arg, extras)
        } catch (ignored: Exception) {
            null
        }
        if (answer?.getString(RESULT_KEY) == RESULT_OK) return answer
    }
    return null
}

/** The route 連絡先 holds for [number] ([ROUTE_OFF] = none), or null when it has no route door. */
private fun Context.doorRouteOf(number: String): String? =
    doorCall(DOOR_GET, number, null)?.let { it.getString(DOOR_ROUTE) ?: ROUTE_OFF }

/** Stores [route] for [number] in 連絡先; false when it has no route door or refused. */
private fun Context.doorSetRoute(number: String, route: String): Boolean {
    val extras = Bundle().apply {
        putString(DOOR_NUMBER, number)
        putString(DOOR_ROUTE, route)
    }
    return doorCall(DOOR_SET, null, extras) != null
}

/**
 * The route chosen for [number] itself, [ROUTE_OFF] when none: the one 連絡先 holds, where it has the
 * route door; else the one kept here. A route set here before the door existed is handed to 連絡先
 * the first time the number is asked for and it has none, and is then dropped here.
 */
fun Context.contactRouteOf(number: String): String {
    val own = ownRouteOf(number)
    val shared = doorRouteOf(number) ?: return own
    if (shared == ROUTE_OFF && own != ROUTE_OFF && doorSetRoute(number, own)) {
        setOwnRoute(number, ROUTE_OFF)
        return own
    }
    if (own != ROUTE_OFF && shared != ROUTE_OFF) setOwnRoute(number, ROUTE_OFF) // 連絡先's word stands
    return shared
}

/** Stores the route chosen for [number]: in 連絡先 where it has the door, else here. */
fun Context.setContactRoute(number: String, route: String) {
    if (doorSetRoute(number, route)) setOwnRoute(number, ROUTE_OFF) else setOwnRoute(number, route)
}

/**
 * The route a tap on the call button uses for [number]: the contact's own ([contactRouteOf]); else,
 * from an older 連絡先 without the route door, the SIM it holds for the number; else the UI page's
 * default. A 臨 route without Rindenwa installed counts as not set. Asks another app: never on the main thread.
 */
fun Context.effectiveRouteOf(number: String): String {
    val candidates = listOf(contactRouteOf(number), renrakusakiSimRoute(number), config.defaultCallRoute)
    return candidates.firstOrNull { it != ROUTE_OFF && (!isRinRoute(it) || Rindenwa.isInstalled(this)) } ?: ROUTE_OFF
}

@Suppress("TooGenericExceptionCaught", "SwallowedException") // 連絡先 missing or older is an answer
private fun Context.renrakusakiSimRoute(number: String): String {
    RENRAKUSAKI_AUTHORITIES.forEach { authority ->
        val slot = try {
            contentResolver.query(Uri.parse("content://$authority/sim_slot"), null, null, arrayOf(number), null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            } ?: 0
        } catch (ignored: Exception) {
            0
        }
        if (slot == 1) return ROUTE_SIM1
        if (slot == 2) return ROUTE_SIM2
    }
    return ROUTE_OFF
}

/** How [route] is drawn as a badge: SIM 1 / SIM 2 as a numbered card, a 臨 account in its own colours; null for none. */
fun Context.routeBadgeStyle(route: String): SimBadgeStyle? = when {
    route == ROUTE_SIM1 -> SimBadgeStyle("1", themeColor(ThemeSlot.ROUTE_SIM1), themeColor(ThemeSlot.ROUTE_SIM_TEXT))
    route == ROUTE_SIM2 -> SimBadgeStyle("2", themeColor(ThemeSlot.ROUTE_SIM2), themeColor(ThemeSlot.ROUTE_SIM_TEXT))
    isRinRoute(route) -> {
        val account = rinAccountOf(route)
        val known = Rindenwa.cachedAccounts(this).firstOrNull { if (account >= 0) it.number == account else it.isDefault }
        SimBadgeStyle(
            text = rinBadge(if (account >= 0) account else known?.number ?: -1),
            fill = known?.badgeColor ?: themeColor(ThemeSlot.ROUTE_RIN),
            textColor = known?.badgeTextColor ?: themeColor(ThemeSlot.ROUTE_RIN_TEXT),
        )
    }

    else -> null
}

/** A line of text for [route]: “SIM 1”, “臨2 · name”, or [none] for no route. */
fun Context.routeLabel(route: String, none: String): String = when {
    route == ROUTE_SIM1 -> getString(R.string.sim_slot_1)
    route == ROUTE_SIM2 -> getString(R.string.sim_slot_2)
    route == ROUTE_RIN -> "$RIN_MARK · ${getString(R.string.route_rin_default_short)}"
    route.startsWith(ROUTE_RIN_PREFIX) -> {
        val account = rinAccountOf(route)
        val known = Rindenwa.cachedAccounts(this).firstOrNull { it.number == account }
        listOf(rinBadge(account), known?.label.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    else -> none
}

// ---- Placing the call ----

/** Calls [number] by [route], after the call confirmation when that is on. No route: the phone app decides. */
fun BaseSimpleActivity.callByRoute(number: String, name: String, route: String) {
    val place: () -> Unit = when {
        route == ROUTE_SIM1 -> { -> callWithSim(number, 0) }
        route == ROUTE_SIM2 -> { -> callWithSim(number, 1) }
        isRinRoute(route) -> { -> callViaRindenwa(number, rinAccountOf(route)) }
        else -> {
            dialNumber(number)
            return
        }
    }
    if (config.showCallConfirmation) CallConfirmationDialog(this, name) { place() } else place()
}

// The SIM at [index] among the call-capable accounts, in the order 電話 numbers them (1, 2).
@SuppressLint("MissingPermission")
private fun BaseSimpleActivity.simHandle(index: Int): PhoneAccountHandle? = try {
    getSystemService(TelecomManager::class.java).callCapablePhoneAccounts.getOrNull(index)
} catch (e: SecurityException) {
    null
}

@Suppress("TooGenericExceptionCaught")
private fun BaseSimpleActivity.callWithSim(number: String, index: Int) {
    handlePermission(PERMISSION_READ_PHONE_STATE) { canRead ->
        val handle = if (canRead) simHandle(index) else null
        handlePermission(PERMISSION_CALL_PHONE) { canCall ->
            Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).apply {
                handle?.let { putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
                try {
                    startActivity(this)
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }
        }
    }
}

private fun BaseSimpleActivity.callViaRindenwa(number: String, account: Int) {
    val appContext = applicationContext
    ensureBackgroundThread {
        val error = Rindenwa.dial(appContext, number, account)
        if (error != null) runOnUiThread { toast(rinErrorText(error, account), Toast.LENGTH_LONG) }
    }
}

private fun Context.rinErrorText(error: String, account: Int): String = when (error) {
    "" -> getString(R.string.rin_error_unavailable)
    "not allowed" -> getString(R.string.rin_error_not_allowed)
    "no such account" -> getString(R.string.rin_error_no_account, rinBadge(account))
    "not ready" -> getString(R.string.rin_error_not_ready)
    else -> getString(R.string.rin_error_other, error)
}
