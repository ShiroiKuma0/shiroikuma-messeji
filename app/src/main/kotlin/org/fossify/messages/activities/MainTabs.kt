package org.fossify.messages.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Telephony
import android.view.View
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.adapters.SearchResultsAdapter
import org.fossify.messages.databinding.ActivityMainBinding
import org.fossify.messages.dialogs.TabNameDialog
import org.fossify.messages.extensions.ThemeSlot
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.formatMessageDateOrTime
import org.fossify.messages.extensions.markThreadMessagesRead
import org.fossify.messages.extensions.markThreadMessagesUnread
import org.fossify.messages.extensions.showThemedPopupMenu
import org.fossify.messages.extensions.themeColor
import org.fossify.messages.helpers.Filing
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Message
import org.fossify.messages.models.SearchResult
import org.fossify.messages.views.DayHeaderDecoration
import org.fossify.messages.views.FolderTabItem

/**
 * The conversation list's 白い熊 filing tabs: the folder tab bar with its unread counts, the long-press
 * menu (new / rename / delete) and drag-reorder, the 送信 list, and the "only this tab" search label.
 * [onChanged] re-filters the list after anything that moves conversations between tabs.
 */
class MainTabs(
    private val activity: SimpleActivity,
    private val binding: ActivityMainBinding,
    private val onChanged: () -> Unit,
    private val openResult: (SearchResult) -> Unit,
    private val onSelected: () -> Unit,
) {
    private val config get() = activity.config

    // day headers over the 送信済み list, as over the conversations
    val sentDayHeaders = DayHeaderDecoration(activity) { position ->
        (binding.sentList.adapter as? SearchResultsAdapter)?.searchResults?.getOrNull(position)?.timestamp
    }.also { binding.sentList.addItemDecoration(it) }

    // what the bar was last drawn for, so the tab menu can mark a tab's conversations read
    private var shown: List<Conversation> = emptyList()

    private fun unreadIn(conversation: Conversation) =
        if (conversation.read) 0 else conversation.unreadCount.coerceAtLeast(1)

    /**
     * Draw the bar for [conversations] and return the ones the selected tab shows — or null on the
     * 送信 tab, which shows the sent-message list instead of conversations.
     */
    fun visible(conversations: List<Conversation>): List<Conversation>? {
        render(conversations)
        val selected = config.filingSelectedTab
        binding.sentList.beVisibleIf(selected == Filing.TAB_SENT)
        return when (selected) {
            Filing.TAB_SENT -> null.also { loadSent(conversations) }
            Filing.TAB_ALL -> conversations
            else -> conversations.filter { Filing.tabOf(activity, it) == selected }
        }
    }

    private fun render(conversations: List<Conversation>) {
        shown = conversations
        val tabs = Filing.tabs(activity)
        val byTab = conversations.groupBy { Filing.tabOf(activity, it) }
        var selected = config.filingSelectedTab
        val fixed = setOf(Filing.TAB_UNFILED, Filing.TAB_SENT, Filing.TAB_ALL)
        if (selected !in fixed && tabs.none { it.id == selected }) {
            selected = Filing.TAB_ALL
            config.filingSelectedTab = selected
        }

        val items = ArrayList<FolderTabItem>()
        tabs.forEach { tab ->
            items.add(FolderTabItem(tab.id, tab.name, byTab[tab.id].orEmpty().sumOf(::unreadIn), movable = true))
        }
        val unfiled = byTab[Filing.TAB_UNFILED].orEmpty()
        if (unfiled.isNotEmpty() || selected == Filing.TAB_UNFILED) {
            val name = activity.getString(R.string.tab_unfiled)
            items.add(FolderTabItem(Filing.TAB_UNFILED, name, unfiled.sumOf(::unreadIn), movable = false))
        }
        items.add(FolderTabItem(Filing.TAB_SENT, activity.getString(R.string.tab_sent), 0, movable = false))
        val allName = activity.getString(R.string.tab_all)
        items.add(FolderTabItem(Filing.TAB_ALL, allName, conversations.sumOf(::unreadIn), movable = false))

        binding.folderTabs.setTabs(
            items = items,
            selectedId = selected,
            ink = activity.themeColor(ThemeSlot.PRIMARY),
            textScale = config.tabTextScale / 100f,
            background = activity.themeColor(ThemeSlot.BACKGROUND),
            onSelect = { id ->
                if (id != config.filingSelectedTab) {
                    config.filingSelectedTab = id
                    onSelected()
                }
            },
            onMenu = ::showMenu,
            onReorder = { id, toIndex ->
                Filing.moveTab(activity, id, toIndex)
                onChanged()
            },
        )
    }

    // A long-press released without dragging: 新しいタブ always; rename / delete on the user's own tabs.
    private fun showMenu(id: Int, anchor: View) {
        val entries = ArrayList<Pair<String, () -> Unit>>()
        entries.add(activity.getString(R.string.new_tab) to {
            askName(R.string.new_tab, "") { name -> config.filingSelectedTab = Filing.createTab(activity, name).id }
        })
        val inTab = conversationsOf(id)
        val unread = inTab.filter { !it.read }
        if (unread.isNotEmpty()) {
            entries.add(activity.getString(R.string.mark_all_as_read) to { markAllRead(unread) })
        }
        val read = inTab.filter { it.read }
        if (read.isNotEmpty()) {
            entries.add(activity.getString(R.string.mark_all_as_unread) to { markAllUnread(read) })
        }
        val title = tabTitle(id)
        if (title.isNotEmpty()) {
            entries.add(activity.getString(R.string.look_up_tab_in_dictionary) to { lookUpInDictionary(title) })
        }
        if (id > 0) {
            val name = Filing.tabName(activity, id).orEmpty()
            entries.add(activity.getString(R.string.rename_tab) to {
                askName(R.string.rename_tab, name) { Filing.renameTab(activity, id, it) }
            })
            entries.add(activity.getString(R.string.delete_tab) to {
                ConfirmationDialog(activity, activity.getString(R.string.delete_tab_confirmation, name)) {
                    Filing.deleteTab(activity, id)
                    onChanged()
                }
            })
        }
        activity.showThemedPopupMenu(anchor, entries)
    }

    // The conversations a tab holds; the 送信済み tab holds none.
    private fun conversationsOf(id: Int): List<Conversation> = when (id) {
        Filing.TAB_SENT -> emptyList()
        Filing.TAB_ALL -> shown
        else -> shown.filter { Filing.tabOf(activity, it) == id }
    }

    private fun markAllUnread(conversations: List<Conversation>) {
        ensureBackgroundThread {
            conversations.forEach { activity.markThreadMessagesUnread(it.threadId) }
            activity.runOnUiThread { refreshConversations() }
        }
    }

    private fun markAllRead(conversations: List<Conversation>) {
        ensureBackgroundThread {
            conversations.forEach {
                activity.markThreadMessagesRead(it.threadId)
                activity.notificationManager.cancel(it.threadId.hashCode())
            }
            activity.runOnUiThread { refreshConversations() }
        }
    }

    private fun tabTitle(id: Int) = when (id) {
        Filing.TAB_UNFILED -> activity.getString(R.string.tab_unfiled)
        Filing.TAB_SENT -> activity.getString(R.string.tab_sent)
        Filing.TAB_ALL -> activity.getString(R.string.tab_all)
        else -> Filing.tabName(activity, id).orEmpty()
    }

    // 辞書で引く: open 白い熊 辞書's Japanese dictionary on the tab's name (its OPEN_DICTIONARY entry).
    private fun lookUpInDictionary(text: String) {
        val intent = Intent(JISHO_ACTION_OPEN_DICTIONARY).apply {
            setPackage(JISHO_PACKAGE)
            putExtra("language", "ja")
            putExtra("query", text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            activity.toast(R.string.dictionary_not_installed)
        }
    }

    private fun askName(titleRes: Int, current: String, onName: (String) -> Unit) {
        TabNameDialog(activity, titleRes, current) { name ->
            if (name != current && Filing.tabs(activity).any { it.name == name }) {
                activity.toast(R.string.tab_name_taken)
                return@TabNameDialog false
            }
            onName(name)
            onChanged()
            true
        }
    }

    /** Apply a dropped-in bulk filing ([Filing.IMPORT_FILE_NAME]) once, then re-filter. */
    fun applyImport() {
        ensureBackgroundThread {
            val applied = Filing.applyImportIfPresent(activity)
            if (applied >= 0) {
                activity.runOnUiThread {
                    activity.toast(activity.getString(R.string.filing_imported, applied))
                    onChanged()
                }
            }
        }
    }

    /** Narrow search hits to one tab (call off the main thread); [Filing.TAB_ALL] leaves them as they are. */
    fun narrowSearch(
        tab: Int,
        messages: List<Message>,
        conversations: List<Conversation>,
    ): Pair<List<Message>, List<Conversation>> = when (tab) {
        Filing.TAB_ALL -> messages to conversations
        Filing.TAB_SENT -> messages.filter { it.type == Telephony.Sms.MESSAGE_TYPE_SENT } to emptyList()
        else -> {
            val threads = (activity.conversationsDB.getNonArchived() + activity.conversationsDB.getAllArchived())
                .filter { Filing.tabOf(activity, it) == tab }
                .map { it.threadId }
                .toSet()
            messages.filter { it.threadId in threads } to conversations.filter { Filing.tabOf(activity, it) == tab }
        }
    }

    // --- the 送信 tab: every sent SMS straight from the telephony store, newest first ---

    private fun loadSent(conversations: List<Conversation>) {
        val byThread = conversations.associateBy { it.threadId }
        ensureBackgroundThread {
            val results = querySent(byThread)
            activity.runOnUiThread {
                if (config.filingSelectedTab != Filing.TAB_SENT) {
                    return@runOnUiThread
                }
                binding.conversationsFastscroller.beGone()
                binding.noConversationsPlaceholder2.beGone()
                binding.conversationsProgressBar.hide()
                binding.noConversationsPlaceholder.beVisibleIf(results.isEmpty())
                binding.noConversationsPlaceholder.text = activity.getString(R.string.no_sent_messages)
                val adapter = binding.sentList.adapter as? SearchResultsAdapter
                if (adapter == null) {
                    binding.sentList.adapter = SearchResultsAdapter(activity, results, binding.sentList, "") {
                        openResult(it as SearchResult)
                    }
                } else {
                    adapter.updateItems(results, "")
                }
            }
        }
    }

    private fun querySent(byThread: Map<Long, Conversation>): ArrayList<SearchResult> {
        val results = ArrayList<SearchResult>()
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE
        )
        val selection = "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_SENT}"
        runCatching {
            activity.contentResolver.query(
                Telephony.Sms.CONTENT_URI, projection, selection, null, "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val threadCol = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val addressCol = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyCol = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateCol = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                while (cursor.moveToNext()) {
                    val threadId = cursor.getLong(threadCol)
                    val conversation = byThread[threadId]
                    results.add(
                        SearchResult(
                            messageId = cursor.getLong(idCol),
                            title = conversation?.title ?: cursor.getString(addressCol).orEmpty(),
                            snippet = cursor.getString(bodyCol).orEmpty(),
                            date = cursor.getLong(dateCol).formatMessageDateOrTime(
                                context = activity, hideTimeOnOtherDays = true, showCurrentYear = true
                            ),
                            threadId = threadId,
                            photoUri = conversation?.photoUri.orEmpty(),
                            timestamp = cursor.getLong(dateCol),
                        )
                    )
                }
            }
        }
        return results
    }

    /** The search bar's "only this tab" box names the tab it narrows to; on 全部 there is nothing to narrow. */
    fun updateSearchLabel() {
        val tab = config.filingSelectedTab
        binding.searchThisTab.beGoneIf(tab == Filing.TAB_ALL)
        val name = when (tab) {
            Filing.TAB_UNFILED -> activity.getString(R.string.tab_unfiled)
            Filing.TAB_SENT -> activity.getString(R.string.tab_sent)
            else -> Filing.tabName(activity, tab).orEmpty()
        }
        binding.searchThisTab.text = activity.getString(R.string.search_this_tab_named, name)
    }

    private companion object {
        const val JISHO_PACKAGE = "shiroikuma.jisho"
        const val JISHO_ACTION_OPEN_DICTIONARY = "shiroikuma.jisho.action.OPEN_DICTIONARY"
    }
}
