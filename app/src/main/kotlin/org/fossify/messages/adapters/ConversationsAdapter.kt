package org.fossify.messages.adapters

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import android.text.TextUtils
import com.google.android.material.snackbar.Snackbar
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.FeatureLockedDialog
import org.fossify.commons.extensions.addBlockedNumber
import org.fossify.commons.extensions.addLockedLabelIfNeeded
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.isOrWasThankYouInstalled
import org.fossify.commons.extensions.launchActivityIntent
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.KEY_PHONE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.messages.R
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.dialogs.RenameConversationDialog
import org.fossify.messages.extensions.ThemeSlot
import org.fossify.messages.extensions.colorMenuTitles
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.deleteConversation
import org.fossify.messages.extensions.dialNumber
import org.fossify.messages.extensions.launchConversationDetails
import org.fossify.messages.extensions.markThreadMessagesRead
import org.fossify.messages.extensions.markThreadMessagesUnread
import org.fossify.messages.extensions.renameConversation
import org.fossify.messages.extensions.themeColor
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.helpers.SwipeAction
import org.fossify.messages.helpers.UNDO_DELETE_MS
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.messaging.isShortCodeWithLetters
import org.fossify.messages.models.Conversation

class ConversationsAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    onRefresh: () -> Unit,
    itemClick: (Any) -> Unit
) : BaseConversationsAdapter(activity, recyclerView, onRefresh, itemClick) {
    private val pendingDeletes = LinkedHashMap<Long, Conversation>()
    private var undoBar: Snackbar? = null

    /** The view the undo bar sits just above (the new-conversation button), set by MainActivity. */
    var undoAnchor: View? = null

    override fun getActionMenuId() = R.menu.cab_conversations

    override fun prepareActionMode(menu: Menu) {
        val selectedItems = getSelectedItems()
        val isSingleSelection = isOneItemSelected()
        val selectedConversation = selectedItems.firstOrNull() ?: return
        val isGroupConversation = selectedConversation.isGroupConversation
        val archiveAvailable = activity.config.isArchiveAvailable

        menu.apply {
            findItem(R.id.cab_block_number).title =
                activity.addLockedLabelIfNeeded(org.fossify.commons.R.string.block_number)
            findItem(R.id.cab_add_number_to_contact).isVisible =
                isSingleSelection && !isGroupConversation
            findItem(R.id.cab_dial_number).isVisible =
                isSingleSelection && !isGroupConversation &&
                        !isShortCodeWithLetters(selectedConversation.phoneNumber)
            findItem(R.id.cab_copy_number).isVisible = isSingleSelection && !isGroupConversation
            findItem(R.id.cab_rename_conversation).isVisible =
                isSingleSelection && isGroupConversation
            findItem(R.id.cab_conversation_details).isVisible = isSingleSelection
            findItem(R.id.cab_mark_as_read).isVisible = selectedItems.any { !it.read }
            findItem(R.id.cab_mark_as_unread).isVisible = selectedItems.any { it.read }
            findItem(R.id.cab_archive).isVisible = archiveAvailable
            checkPinBtnVisibility(this)
            activity.colorMenuTitles(this)
        }
    }

    override fun actionItemPressed(id: Int) {
        val selectedItems = getSelectedItems()
        if (selectedItems.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_add_number_to_contact -> addNumberToContact()
            R.id.cab_block_number -> tryBlocking()
            R.id.cab_dial_number -> dialNumber()
            R.id.cab_copy_number -> copyNumberToClipboard()
            R.id.cab_delete -> askConfirmDelete()
            R.id.cab_archive -> askConfirmArchive()
            R.id.cab_rename_conversation -> renameConversation(selectedItems.first())
            R.id.cab_conversation_details ->
                activity.launchConversationDetails(selectedItems.first().threadId)

            R.id.cab_mark_as_read -> markAsRead()
            R.id.cab_mark_as_unread -> markAsUnread()
            R.id.cab_pin_conversation -> pinConversation(true)
            R.id.cab_unpin_conversation -> pinConversation(false)
            R.id.cab_select_all -> selectAll()
        }
    }

    fun isPinned(conversation: Conversation) =
        activity.config.pinnedConversations.contains(conversation.threadId.toString())

    /**
     * Run a conversation-list swipe. Returns whether the row left the list; a row that stays is put
     * back in place by the swipe callback.
     */
    fun performSwipeAction(conversation: Conversation, action: SwipeAction): Boolean = when (action) {
        SwipeAction.NONE -> false
        SwipeAction.DELETE -> swipeDelete(conversation)
        SwipeAction.ARCHIVE -> activity.config.isArchiveAvailable.also { available ->
            if (available) {
                removeSwiped(conversation) { activity.updateConversationArchivedStatus(it.threadId, true) }
            }
        }

        SwipeAction.TOGGLE_READ -> {
            ensureBackgroundThread {
                if (conversation.read) {
                    activity.markThreadMessagesUnread(conversation.threadId)
                } else {
                    activity.markThreadMessagesRead(conversation.threadId)
                    activity.notificationManager.cancel(conversation.threadId.hashCode())
                }
                activity.runOnUiThread { refreshConversations() }
            }
            false
        }

        SwipeAction.TOGGLE_PIN -> {
            if (isPinned(conversation)) {
                activity.config.removePinnedConversations(listOf(conversation))
            } else {
                activity.config.addPinnedConversations(listOf(conversation))
            }
            refreshConversations()
            false
        }
    }

    // Swipe-delete either asks first (the UI page's opt-in) or deletes behind an undo bar: the row leaves
    // the list at once, but the telephony rows are only removed once the bar times out or is dismissed,
    // because a deleted thread cannot be brought back.
    private fun swipeDelete(conversation: Conversation): Boolean {
        if (activity.config.swipeDeleteConfirm) {
            // the row goes back in place while asking, so a "No" leaves the list exactly as it was
            val items = resources.getQuantityString(R.plurals.delete_conversations, 1, 1)
            val question = String.format(resources.getString(org.fossify.commons.R.string.deletion_confirmation), items)
            ConfirmationDialog(activity, question) {
                removeSwiped(conversation) { activity.deleteConversation(it.threadId) }
            }
            return false
        }

        // one undo at a time: a second swipe-delete settles the first
        commitPendingDeletes()
        pendingDeletes[conversation.threadId] = conversation
        hiddenThreadIds.add(conversation.threadId)
        submitList(currentList.toMutableList().apply { remove(conversation) })

        val primary = activity.themeColor(ThemeSlot.PRIMARY)
        val density = resources.displayMetrics.density
        undoBar = Snackbar.make(recyclerView, R.string.conversation_deleted, UNDO_DELETE_MS).apply {
            setTextColor(primary)
            setActionTextColor(primary)
            // Material tints the bar's own background over anything set; drop the tint, then frame it
            view.backgroundTintList = null
            view.background = GradientDrawable().apply {
                cornerRadius = UNDO_BAR_CORNER_DP * density
                setColor(activity.themeColor(ThemeSlot.BACKGROUND))
                setStroke((UNDO_BAR_BORDER_DP * density).toInt(), primary)
            }
            // bottom right, just above the new-conversation button
            (view.layoutParams as? CoordinatorLayout.LayoutParams)?.let {
                it.width = ViewGroup.LayoutParams.WRAP_CONTENT
                it.gravity = Gravity.BOTTOM or Gravity.END
                view.layoutParams = it
            }
            undoAnchor?.let { anchorView = it }
            setAction(org.fossify.commons.R.string.undo) { undoDelete(conversation.threadId) }
            addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (event != DISMISS_EVENT_ACTION) {
                        commitDelete(conversation.threadId)
                    }
                }
            })
            show()
        }
        return true
    }

    private fun undoDelete(threadId: Long) {
        pendingDeletes.remove(threadId) ?: return
        hiddenThreadIds.remove(threadId)
        refreshConversations()
    }

    private fun commitDelete(threadId: Long) {
        pendingDeletes.remove(threadId) ?: return
        ensureBackgroundThread {
            activity.deleteConversation(threadId)
            activity.notificationManager.cancel(threadId.hashCode())
            activity.runOnUiThread {
                hiddenThreadIds.remove(threadId)
                refreshConversations()
            }
        }
    }

    /** Carry out every delete still waiting behind the undo bar (also when the list leaves the screen). */
    fun commitPendingDeletes() {
        pendingDeletes.keys.toList().forEach(::commitDelete)
        undoBar?.dismiss()
        undoBar = null
    }

    private fun removeSwiped(conversation: Conversation, operation: (Conversation) -> Unit) {
        submitList(currentList.toMutableList().apply { remove(conversation) })
        ensureBackgroundThread {
            operation(conversation)
            activity.notificationManager.cancel(conversation.threadId.hashCode())
            activity.runOnUiThread { refreshConversations() }
        }
    }

    private fun tryBlocking() {
        if (activity.isOrWasThankYouInstalled()) {
            askConfirmBlock()
        } else {
            FeatureLockedDialog(activity) { }
        }
    }

    private fun askConfirmBlock() {
        val numbers = getSelectedItems().distinctBy { it.phoneNumber }.map { it.phoneNumber }
        val numbersString = TextUtils.join(", ", numbers)
        val question = String.format(
            resources.getString(org.fossify.commons.R.string.block_confirmation),
            numbersString
        )

        ConfirmationDialog(activity, question) {
            blockNumbers()
        }
    }

    private fun blockNumbers() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val numbersToBlock = getSelectedItems()
        val newList = currentList.toMutableList().apply { removeAll(numbersToBlock) }

        ensureBackgroundThread {
            numbersToBlock.map { it.phoneNumber }.forEach { number ->
                activity.addBlockedNumber(number)
            }

            activity.runOnUiThread {
                submitList(newList)
                finishActMode()
            }
        }
    }

    private fun dialNumber() {
        val conversation = getSelectedItems().firstOrNull() ?: return
        activity.dialNumber(conversation.phoneNumber) {
            finishActMode()
        }
    }

    private fun copyNumberToClipboard() {
        val conversation = getSelectedItems().firstOrNull() ?: return
        activity.copyToClipboard(conversation.phoneNumber)
        finishActMode()
    }

    private fun askConfirmDelete() {
        val itemsCnt = selectedKeys.size
        val items = resources.getQuantityString(R.plurals.delete_conversations, itemsCnt, itemsCnt)

        val baseString = org.fossify.commons.R.string.deletion_confirmation
        val question = String.format(resources.getString(baseString), items)

        ConfirmationDialog(activity, question) {
            ensureBackgroundThread {
                deleteConversations()
            }
        }
    }

    private fun askConfirmArchive() {
        val itemsCnt = selectedKeys.size
        val items = resources.getQuantityString(R.plurals.delete_conversations, itemsCnt, itemsCnt)

        val baseString = R.string.archive_confirmation
        val question = String.format(resources.getString(baseString), items)

        ConfirmationDialog(activity, question) {
            ensureBackgroundThread {
                archiveConversations()
            }
        }
    }

    private fun archiveConversations() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val conversationsToRemove =
            currentList.filter { selectedKeys.contains(it.hashCode()) } as ArrayList<Conversation>
        conversationsToRemove.forEach {
            activity.updateConversationArchivedStatus(it.threadId, true)
            activity.notificationManager.cancel(it.threadId.hashCode())
        }

        val newList = try {
            currentList.toMutableList().apply { removeAll(conversationsToRemove) }
        } catch (ignored: Exception) {
            currentList.toMutableList()
        }

        activity.runOnUiThread {
            if (newList.none { selectedKeys.contains(it.hashCode()) }) {
                refreshConversations()
                finishActMode()
            } else {
                submitList(newList)
                if (newList.isEmpty()) {
                    refreshConversations()
                }
            }
        }
    }

    private fun deleteConversations() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val conversationsToRemove =
            currentList.filter { selectedKeys.contains(it.hashCode()) } as ArrayList<Conversation>
        conversationsToRemove.forEach {
            activity.deleteConversation(it.threadId)
            activity.notificationManager.cancel(it.threadId.hashCode())
        }

        val newList = try {
            currentList.toMutableList().apply { removeAll(conversationsToRemove) }
        } catch (ignored: Exception) {
            currentList.toMutableList()
        }

        activity.runOnUiThread {
            if (newList.none { selectedKeys.contains(it.hashCode()) }) {
                refreshConversations()
                finishActMode()
            } else {
                submitList(newList)
                if (newList.isEmpty()) {
                    refreshConversations()
                }
            }
        }
    }

    private fun renameConversation(conversation: Conversation) {
        RenameConversationDialog(activity, conversation) {
            ensureBackgroundThread {
                val updatedConv = activity.renameConversation(conversation, newTitle = it)
                activity.runOnUiThread {
                    finishActMode()
                    currentList.toMutableList().apply {
                        set(indexOf(conversation), updatedConv)
                        updateConversations(this as ArrayList<Conversation>)
                    }
                }
            }
        }
    }

    private fun markAsRead() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val conversationsMarkedAsRead =
            currentList.filter { selectedKeys.contains(it.hashCode()) } as ArrayList<Conversation>
        ensureBackgroundThread {
            conversationsMarkedAsRead.filter { conversation -> !conversation.read }.forEach {
                activity.markThreadMessagesRead(it.threadId)
            }

            refreshConversationsAndFinishActMode()
        }
    }

    private fun markAsUnread() {
        if (selectedKeys.isEmpty()) {
            return
        }

        val conversationsMarkedAsUnread =
            currentList.filter { selectedKeys.contains(it.hashCode()) } as ArrayList<Conversation>
        ensureBackgroundThread {
            conversationsMarkedAsUnread.filter { conversation -> conversation.read }.forEach {
                activity.markThreadMessagesUnread(it.threadId)
            }

            refreshConversationsAndFinishActMode()
        }
    }

    private fun addNumberToContact() {
        val conversation = getSelectedItems().firstOrNull() ?: return
        Intent().apply {
            action = Intent.ACTION_INSERT_OR_EDIT
            type = "vnd.android.cursor.item/contact"
            putExtra(KEY_PHONE, conversation.phoneNumber)
            activity.launchActivityIntent(this)
        }
    }

    private fun pinConversation(pin: Boolean) {
        val conversations = getSelectedItems()
        if (conversations.isEmpty()) {
            return
        }

        if (pin) {
            activity.config.addPinnedConversations(conversations)
        } else {
            activity.config.removePinnedConversations(conversations)
        }

        getSelectedItemPositions().forEach {
            notifyItemChanged(it)
        }
        refreshConversationsAndFinishActMode()
    }

    private fun checkPinBtnVisibility(menu: Menu) {
        val pinnedConversations = activity.config.pinnedConversations
        val selectedConversations = getSelectedItems()
        menu.findItem(R.id.cab_pin_conversation).isVisible =
            selectedConversations.any { !pinnedConversations.contains(it.threadId.toString()) }
        menu.findItem(R.id.cab_unpin_conversation).isVisible =
            selectedConversations.all { pinnedConversations.contains(it.threadId.toString()) }
    }

    private fun refreshConversationsAndFinishActMode() {
        activity.runOnUiThread {
            refreshConversations()
            finishActMode()
        }
    }

    companion object {
        private const val UNDO_BAR_CORNER_DP = 8f
        private const val UNDO_BAR_BORDER_DP = 2f
    }
}
