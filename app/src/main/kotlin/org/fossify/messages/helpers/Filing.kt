package org.fossify.messages.helpers

import android.content.Context
import org.fossify.messages.extensions.config
import org.fossify.messages.models.Conversation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 白い熊 メッセージ filing: every conversation lives in one user tab (自分, 個人, 仕事, …), chosen by
 * its sender. A rule is "sender key → tab id"; a conversation whose sender has no rule is *unfiled*
 * and is shown in the 未分類 tab with a marker on its row until it is filed.
 *
 * What is stored, and where: the tabs and the rules are two JSON strings in the app's main prefs
 * ([FILING_TABS], [FILING_RULES]), so they travel with the settings export that 白い熊 応用管理
 * backs up. **The personal and work rules exist only on the phone** — this repository is public,
 * so the only rules built into the code are [BUILT_IN] commercial senders (alphanumeric names and
 * short codes), which apply when no rule of the user's says otherwise.
 *
 * A one-off bulk filing (the mapping 白い熊 approved) is delivered as [IMPORT_FILE_NAME] in the
 * app's external files directory and applied on the next start of the conversation list.
 */
@Suppress("TooManyFunctions")
object Filing {
    /** The fixed tabs after the user's own; never stored, never reordered or deleted. */
    const val TAB_UNFILED = -1
    const val TAB_SENT = -2
    const val TAB_ALL = -3

    // The default tabs, created on first use; their ids are what [BUILT_IN] points at.
    private const val SELF = 1
    private const val PERSONAL = 2
    private const val WORK = 3
    private const val SHOPPING = 4
    private const val CODES = 5
    private const val SERVICES = 6
    private const val ADS = 7

    private val DEFAULT_TABS = listOf(
        SELF to "自分", PERSONAL to "個人", WORK to "仕事", SHOPPING to "買物",
        CODES to "認証", SERVICES to "用事", ADS to "宣伝",
    )

    const val IMPORT_FILE_NAME = "filing-import.json"
    private const val IMPORT_DONE_SUFFIX = ".done"
    private const val SHORT_CODE_MAX_DIGITS = 8
    private const val NUMBER_KEY_DIGITS = 9

    data class Tab(val id: Int, val name: String)

    // Parsed caches, keyed by the raw pref string they were parsed from, so a settings import
    // (which rewrites the prefs underneath us) is picked up without any invalidation call.
    private var tabsRaw: String? = null
    private var tabsCache: List<Tab> = emptyList()
    private var rulesRaw: String? = null
    private var rulesCache: Map<String, Int> = emptyMap()

    // --- tabs ---

    @Synchronized
    fun tabs(context: Context): List<Tab> {
        val config = context.config
        var raw = config.filingTabs
        if (raw.isEmpty()) {
            raw = JSONArray().apply {
                DEFAULT_TABS.forEach { (id, name) -> put(JSONObject().put("id", id).put("name", name)) }
            }.toString()
            config.filingTabs = raw
        }
        if (raw != tabsRaw) {
            tabsCache = runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).map {
                    val o = array.getJSONObject(it)
                    Tab(o.getInt("id"), o.getString("name"))
                }
            }.getOrDefault(emptyList())
            tabsRaw = raw
        }
        return tabsCache
    }

    @Synchronized
    private fun saveTabs(context: Context, tabs: List<Tab>) {
        context.config.filingTabs = JSONArray().apply {
            tabs.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        }.toString()
    }

    fun tabName(context: Context, id: Int): String? = tabs(context).firstOrNull { it.id == id }?.name

    fun createTab(context: Context, name: String): Tab {
        val tabs = tabs(context)
        val tab = Tab((tabs.maxOfOrNull { it.id } ?: 0).coerceAtLeast(ADS) + 1, name)
        saveTabs(context, tabs + tab)
        return tab
    }

    fun renameTab(context: Context, id: Int, name: String) {
        saveTabs(context, tabs(context).map { if (it.id == id) it.copy(name = name) else it })
    }

    /** Deleting a tab unfiles its conversations: their rules go with it. */
    fun deleteTab(context: Context, id: Int) {
        saveTabs(context, tabs(context).filter { it.id != id })
        saveRules(context, rules(context).filterValues { it != id })
        if (context.config.filingSelectedTab == id) {
            context.config.filingSelectedTab = TAB_ALL
        }
    }

    fun moveTab(context: Context, id: Int, toIndex: Int) {
        val tabs = tabs(context).toMutableList()
        val tab = tabs.firstOrNull { it.id == id } ?: return
        tabs.remove(tab)
        tabs.add(toIndex.coerceIn(0, tabs.size), tab)
        saveTabs(context, tabs)
    }

    // --- rules ---

    /** The sender key: lower-cased for a named sender, the last nine digits for a phone number. */
    fun senderKey(address: String): String {
        val trimmed = address.trim()
        if (trimmed.any { it.isLetter() }) {
            return trimmed.lowercase()
        }
        val digits = trimmed.filter { it.isDigit() }
        return if (digits.length >= NUMBER_KEY_DIGITS) digits.takeLast(NUMBER_KEY_DIGITS) else digits
    }

    @Synchronized
    private fun rules(context: Context): Map<String, Int> {
        val raw = context.config.filingRules
        if (raw != rulesRaw) {
            rulesCache = runCatching {
                val o = JSONObject(raw.ifEmpty { "{}" })
                o.keys().asSequence().associateWith { o.getInt(it) }
            }.getOrDefault(emptyMap())
            rulesRaw = raw
        }
        return rulesCache
    }

    @Synchronized
    private fun saveRules(context: Context, rules: Map<String, Int>) {
        context.config.filingRules = JSONObject(rules as Map<*, *>).toString()
    }

    /** The tab a sender is filed in, or [TAB_UNFILED]. */
    fun tabOf(context: Context, address: String): Int {
        val key = senderKey(address)
        if (key.isEmpty()) {
            return TAB_UNFILED
        }
        val tabIds = tabs(context).map { it.id }.toSet()
        rules(context)[key]?.let { if (it in tabIds) return it }
        BUILT_IN[key]?.let { if (it in tabIds) return it }
        return TAB_UNFILED
    }

    fun tabOf(context: Context, conversation: Conversation) = tabOf(context, conversation.phoneNumber)

    fun file(context: Context, addresses: Collection<String>, tabId: Int) {
        val rules = rules(context).toMutableMap()
        addresses.map(::senderKey).filter { it.isNotEmpty() }.forEach { rules[it] = tabId }
        saveRules(context, rules)
    }

    // --- new messages ---

    private val CODE_WORDS = Regex(
        "(?i)(k[oó]d|code|ov[eě][rř]ovac|verifica|passcode|password|heslo|\\bpin\\b|otp|コード|認証)"
    )
    private val CODE_DIGITS = Regex("(?<![\\d.,])\\d{4,8}(?![\\d.,])|\\b(?=[A-Z0-9]*\\d)[A-Z0-9]{4,10}\\b")

    /**
     * Called for every incoming SMS. A sender nothing is known about, who is not one of the
     * contacts, and whose message reads like a one-time code is filed under 認証; anything else
     * new stays unfiled for 白い熊 to file with one tap.
     */
    fun onIncoming(context: Context, address: String, body: String, isContact: Boolean) {
        if (isContact || tabOf(context, address) != TAB_UNFILED) {
            return
        }
        if (tabs(context).none { it.id == CODES }) {
            return
        }
        if (CODE_WORDS.containsMatchIn(body) && CODE_DIGITS.containsMatchIn(body)) {
            file(context, listOf(address), CODES)
        }
    }

    // --- the one-off bulk filing ---

    /**
     * Apply [IMPORT_FILE_NAME] if it was dropped into the external files directory: `{"tabs": [names
     * in order], "rules": {"<address>": "<tab name>"}}`. A tab named there and missing here is
     * created. The file is renamed to `*.done` afterwards, so it is applied once. Returns how many
     * rules were applied, or -1 when there was no file.
     */
    fun applyImportIfPresent(context: Context): Int {
        val file = File(context.getExternalFilesDir(null) ?: return -1, IMPORT_FILE_NAME)
        if (!file.isFile) {
            return -1
        }
        val count = runCatching {
            val json = JSONObject(file.readText())
            json.optJSONArray("tabs")?.let { names ->
                val wanted = (0 until names.length()).map { names.getString(it) }
                wanted.forEach { name -> if (tabs(context).none { it.name == name }) createTab(context, name) }
                val ordered = wanted.mapNotNull { name -> tabs(context).firstOrNull { it.name == name } }
                saveTabs(context, ordered + tabs(context).filter { it !in ordered })
            }
            var applied = 0
            json.optJSONObject("rules")?.let { rules ->
                val byName = HashMap<String, MutableList<String>>()
                rules.keys().forEach { address ->
                    byName.getOrPut(rules.getString(address)) { mutableListOf() }.add(address)
                }
                byName.forEach { (name, addresses) ->
                    val tab = tabs(context).firstOrNull { it.name == name } ?: createTab(context, name)
                    file(context, addresses, tab.id)
                    applied += addresses.size
                }
            }
            applied
        }.getOrDefault(0)
        file.renameTo(File(file.parentFile, file.name + IMPORT_DONE_SUFFIX))
        return count
    }

    private fun isShortCode(key: String) = key.all { it.isDigit() } && key.length <= SHORT_CODE_MAX_DIGITS

    /**
     * Commercial senders seen on 白い熊's phone: named senders and short codes only — never a
     * person's number, since this file is public.
     */
    private val BUILT_IN: Map<String, Int> = mapOf(
        "55156" to ADS,
        "90303" to ADS,
        "999030" to ADS,
        "999163" to ADS,
        "allwyn" to ADS,
        "osend" to ADS,
        "smsinfo" to ADS,
        "srovnejto" to ADS,
        "1115" to SERVICES,
        "1511" to SERVICES,
        "156" to SERVICES,
        "1992116" to SERVICES,
        "2903" to SERVICES,
        "2939" to SERVICES,
        "4220" to SERVICES,
        "4222" to SERVICES,
        "456" to SERVICES,
        "90202" to SERVICES,
        "90206" to SERVICES,
        "90206024" to SERVICES,
        "90206031" to SERVICES,
        "90206032" to SERVICES,
        "90733" to SERVICES,
        "999079" to SERVICES,
        "999555" to SERVICES,
        "9996237" to SERVICES,
        "anytime" to SERVICES,
        "cd a.s." to SERVICES,
        "cgmeska" to SERVICES,
        "colosseum" to SERVICES,
        "csob" to SERVICES,
        "doklad-info" to SERVICES,
        "global 1220" to SERVICES,
        "hertz" to SERVICES,
        "info" to SERVICES,
        "mup_rh" to SERVICES,
        "mypillow.cz" to SERVICES,
        "mzcr-rez" to SERVICES,
        "o2 roaming" to SERVICES,
        "odlekare" to SERVICES,
        "pruzkum" to SERVICES,
        "restu.cz" to SERVICES,
        "rixo.cz" to SERVICES,
        "ryanair" to SERVICES,
        "sdelenimzcr" to SERVICES,
        "uniqa" to SERVICES,
        "32858" to CODES,
        "4603" to CODES,
        "60030" to CODES,
        "90733119" to CODES,
        "999037" to CODES,
        "amazon" to CODES,
        "anytimecar" to CODES,
        "authmsg" to CODES,
        "bazos.cz" to CODES,
        "cloudotp" to CODES,
        "clubhouse" to CODES,
        "cz.nic" to CODES,
        "facebook" to CODES,
        "finapp" to CODES,
        "google" to CODES,
        "gopay" to CODES,
        "gosms" to CODES,
        "hyponamiru" to CODES,
        "info sms" to CODES,
        "is-uk" to CODES,
        "mastercard" to CODES,
        "microsoft" to CODES,
        "notify" to CODES,
        "o2cz" to CODES,
        "odorik.cz" to CODES,
        "pizzahut" to CODES,
        "prove" to CODES,
        "rb epodpis" to CODES,
        "seznam.cz" to CODES,
        "signal" to CODES,
        "5030" to SHOPPING,
        "alza.cz" to SHOPPING,
        "balikovna" to SHOPPING,
        "dhl" to SHOPPING,
        "dhl express" to SHOPPING,
        "dhlexpress" to SHOPPING,
        "dodo" to SHOPPING,
        "dr.max" to SHOPPING,
        "fedex" to SHOPPING,
        "ikea" to SHOPPING,
        "info-sms" to SHOPPING,
        "jysk" to SHOPPING,
        "kfcrozvoz" to SHOPPING,
        "liftago" to SHOPPING,
        "ppl" to SHOPPING,
        "vas luxor" to SHOPPING
    ).filterKeys { key -> key.any { it.isLetter() } || isShortCode(key) }
}
