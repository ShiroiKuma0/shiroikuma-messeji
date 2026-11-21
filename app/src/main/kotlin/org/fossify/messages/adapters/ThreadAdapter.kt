package org.fossify.messages.adapters

import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.extensions.markMessageUnread
import org.fossify.messages.extensions.markMessageRead
import org.fossify.messages.bridge.Bridge
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.provider.Telephony
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.load.resource.bitmap.FitCenter
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import org.fossify.commons.adapters.MyRecyclerViewListAdapter
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getTextSize
import org.fossify.commons.extensions.getTimeFormat
import org.fossify.commons.extensions.shareTextIntent
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.usableScreenSize
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.models.SimpleContact
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.messages.R
import org.fossify.messages.activities.NewConversationActivity
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.activities.ThreadActivity
import org.fossify.messages.activities.VCardViewerActivity
import org.fossify.messages.databinding.ItemAttachmentDocumentBinding
import org.fossify.messages.databinding.ItemAttachmentImageBinding
import org.fossify.messages.databinding.ItemAttachmentAudioBinding
import org.fossify.messages.databinding.ItemAttachmentVcardBinding
import org.fossify.messages.databinding.ItemMessageBinding
import org.fossify.messages.databinding.ItemThreadDateTimeBinding
import org.fossify.messages.dialogs.DeleteConfirmationDialog
import org.fossify.messages.dialogs.MessageDetailsDialog
import org.fossify.messages.dialogs.SelectTextDialog
import org.fossify.messages.extensions.ThemeSlot
import org.fossify.messages.extensions.applyThemeFont
import org.fossify.messages.extensions.colorMenuTitles
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.dayHeaderLabel
import org.fossify.messages.extensions.formatMessageTimeOfDay
import org.fossify.messages.extensions.getContactFromAddress
import org.fossify.messages.extensions.isImageMimeType
import org.fossify.messages.extensions.isPlayableAudioMimeType
import org.fossify.messages.extensions.isVCardMimeType
import org.fossify.messages.extensions.isVideoMimeType
import org.fossify.messages.extensions.launchViewIntent
import org.fossify.messages.extensions.ownAvatarPath
import org.fossify.messages.extensions.ownProfilePhotoUri
import org.fossify.messages.extensions.startContactDetailsIntent
import org.fossify.messages.extensions.subscriptionManagerCompat
import org.fossify.messages.extensions.themeColor
import org.fossify.messages.helpers.EXTRA_VCARD_URI
import org.fossify.messages.helpers.SimBadgeStyle
import org.fossify.messages.helpers.THREAD_DATE_TIME
import org.fossify.messages.helpers.THREAD_RECEIVED_MESSAGE
import org.fossify.messages.helpers.THREAD_SENT_MESSAGE
import org.fossify.messages.helpers.generateStableId
import org.fossify.messages.helpers.setupAudio
import org.fossify.messages.helpers.setupDocumentPreview
import org.fossify.messages.helpers.setupVCardPreview
import org.fossify.messages.helpers.showSimBadge
import org.fossify.messages.models.Attachment
import org.fossify.messages.models.Message
import org.fossify.messages.models.ThreadItem
import org.fossify.messages.models.ThreadItem.ThreadDateTime
import org.joda.time.DateTime
import org.fossify.commons.extensions.rowSelectorDrawable

class ThreadAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    itemClick: (Any) -> Unit,
    val isRecycleBin: Boolean,
    val deleteMessages: (messages: List<Message>, toRecycleBin: Boolean, fromRecycleBin: Boolean) -> Unit
) : MyRecyclerViewListAdapter<ThreadItem>(activity, recyclerView, ThreadItemDiffCallback(), itemClick) {
    private var fontSize = activity.getTextSize()

    @SuppressLint("MissingPermission")
    // Fork: bridge transports count as SIMs here, so their messages show which one they came over.
    private val hasMultipleSIMCards =
        (activity.subscriptionManagerCompat().activeSubscriptionInfoList?.size ?: 0) > 1 ||
            Bridge.transports(activity).isNotEmpty()
    private val density = activity.resources.displayMetrics.density

    // Fork: the bubble geometry. A bubble starts half an avatar in from its sender's edge of the
    // list (the avatar straddles that edge of the frame) and leaves FAR_SIDE_SHARE of the line free
    // on the other side; media inside it gets what is left between its paddings.
    private val lineWidth = activity.usableScreenSize.x - 2 * dp(HOLDER_PAD_DP)
    private val farMargin = (lineWidth * FAR_SIDE_SHARE).toInt()
    private val farPadding = dp(BUBBLE_PAD_H_DP)
    private var avatarDp = AVATAR_DP
    private var nearMargin = 0
    private var nearPadding = 0
    private var maxChatBubbleWidth = 0

    // The avatar's size is set on the UI page; everything that hangs on it follows.
    private fun readAvatarSize() {
        avatarDp = activity.config.threadAvatarSize.toFloat()
        nearMargin = dp(AVATAR_HALO_DP + avatarDp / 2)
        nearPadding = dp(avatarDp / 2 + AVATAR_GAP_DP)
        maxChatBubbleWidth = lineWidth - nearMargin - farMargin - nearPadding - farPadding
    }

    private fun dp(value: Float) = (value * density).toInt()

    // Fork: how each SIM's or transport's badge looks, by subscription id (-1 = unknown, "?").
    private var simStyles: Map<Int, SimBadgeStyle>? = null

    @SuppressLint("MissingPermission")
    private fun simStyle(subscriptionId: Int): SimBadgeStyle {
        val styles = simStyles ?: HashMap<Int, SimBadgeStyle>().also { map ->
            val fill = activity.themeColor(ThemeSlot.THREAD_DATE)
            map[-1] = SimBadgeStyle("?", fill)
            val sims = activity.subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()
            sims.forEachIndexed { index, info -> map[info.subscriptionId] = SimBadgeStyle("${index + 1}", fill) }
            // a transport's messages are labelled with its badge and coloured like it
            Bridge.transports(activity).forEachIndexed { index, transport ->
                map[transport.subId] = SimBadgeStyle(
                    text = transport.badge.ifEmpty { "${sims.size + index + 1}" },
                    fill = transport.color ?: fill,
                    textColor = transport.textColor,
                    border = transport.borderColor,
                )
            }
            simStyles = map
        }
        return styles[subscriptionId] ?: styles.getValue(-1)
    }

    // Fork: 白い熊's own picture for the sent bubbles — the one picked on the UI page, else the device
    // owner's profile photo, else the 人 icon.
    private var ownPhotoUri = ""

    // Fork: pictures of private contacts (連絡先). A message carries only the system contact's picture,
    // so a sender stored privately falls back to this list at bind time.
    private var privateContacts: List<SimpleContact> = emptyList()
    private val privatePhotoByNumber = HashMap<String, String>()

    fun setPrivateContacts(contacts: List<SimpleContact>) {
        val photos = contacts.filter { it.photoUri.isNotEmpty() }
        if (photos == privateContacts) return
        privateContacts = photos
        privatePhotoByNumber.clear()
        notifyItemRangeChanged(0, itemCount)
    }

    private fun senderPhotoUri(message: Message): String = message.senderPhotoUri.ifEmpty {
        if (privateContacts.isEmpty()) return@ifEmpty ""
        privatePhotoByNumber.getOrPut(message.senderPhoneNumber) {
            privateContacts.firstOrNull { it.doesHavePhoneNumber(message.senderPhoneNumber) }?.photoUri.orEmpty()
        }
    }

    companion object {
        private const val MAX_MEDIA_HEIGHT_RATIO = 3
        private const val SIM_BADGE_TEXT_SHARE = 0.62f // badge text size, as a share of the footer line height
        private const val FOOTER_TEXT_SHARE = 0.74f // footer text size, as a share of the message text size
        private const val DAY_HEADER_SP = 17f
        private const val HOLDER_PAD_DP = 6f // item_message's horizontal padding
        private const val AVATAR_DP = 56f // the default; the UI page sets the real one
        private const val AVATAR_TOP_DP = 8f // the avatar starts this far below the bubble's top edge
        private const val AVATAR_RING_PAD_DP = 5f // the picture's inset in its view: halo plus ring
        private const val AVATAR_HALO_DP = 4f // background-coloured ring that parts the avatar from the frame
        private const val AVATAR_RING_DP = 1.25f
        private const val AVATAR_RING_BLEND = 0.55f // ring colour: this far from the background to the frame
        private const val AVATAR_GAP_DP = 10f // between the avatar and the text beside it
        private const val BUBBLE_PAD_H_DP = 16f
        private const val BUBBLE_PAD_V_DP = 11f
        private const val BUBBLE_RADIUS_DP = 22f
        private const val FRAME_RECEIVED_DP = 3f
        private const val FRAME_SENT_DP = 1.25f
        private const val FRAME_PENDING_DP = 1.5f // dashed: still sending or scheduled; solid red: failed
        private const val DASH_DP = 9f
        private const val DASH_GAP_DP = 6f
        private const val FAR_SIDE_SHARE = 0.12f
        private const val FOOTER_GAP_DP = 6f
    }

    init {
        setupDragListener(true)
        setHasStableIds(true)
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        readAvatarSize()
        ownPhotoUri = activity.ownAvatarPath()
        if (ownPhotoUri.isEmpty()) {
            ensureBackgroundThread {
                val uri = activity.ownProfilePhotoUri()
                if (uri.isNotEmpty()) {
                    activity.runOnUiThread {
                        if (ownPhotoUri.isEmpty()) {
                            ownPhotoUri = uri
                            notifyItemRangeChanged(0, itemCount)
                        }
                    }
                }
            }
        }
    }

    override fun getActionMenuId() = R.menu.cab_thread

    override fun prepareActionMode(menu: Menu) {
        val isOneItemSelected = isOneItemSelected()
        val selectedMessages = getSelectedItems().filterIsInstance<Message>()
        val hasText = selectedMessages.any { it.body.isNotEmpty() }
        val showSaveAs = getSelectedItems().all {
            it is Message && (it.attachment?.attachments?.size ?: 0) > 0
        } && getSelectedAttachments().isNotEmpty()

        menu.apply {
            findItem(R.id.cab_copy_to_clipboard).isVisible = hasText
            findItem(R.id.cab_save_as).isVisible = showSaveAs
            findItem(R.id.cab_share).isVisible = isOneItemSelected && hasText
            findItem(R.id.cab_forward_message).isVisible = isOneItemSelected
            findItem(R.id.cab_select_text).isVisible = isOneItemSelected && hasText
            findItem(R.id.cab_properties).isVisible = isOneItemSelected
            findItem(R.id.cab_restore).isVisible = isRecycleBin
            val received = selectedMessages.filter { it.isReceivedMessage() }
            findItem(R.id.cab_mark_as_read).isVisible = !isRecycleBin && received.any { !it.read }
            findItem(R.id.cab_mark_as_unread).isVisible = !isRecycleBin && received.isNotEmpty()
            activity.colorMenuTitles(this)
        }
    }

    override fun actionItemPressed(id: Int) {
        if (selectedKeys.isEmpty()) {
            return
        }

        when (id) {
            R.id.cab_copy_to_clipboard -> copyToClipboard()
            R.id.cab_save_as -> saveAs()
            R.id.cab_share -> shareText()
            R.id.cab_forward_message -> forwardMessage()
            R.id.cab_select_text -> selectText()
            R.id.cab_delete -> askConfirmDelete()
            R.id.cab_restore -> askConfirmRestore()
            R.id.cab_select_all -> selectAll()
            R.id.cab_properties -> showMessageDetails()
            R.id.cab_mark_as_read -> markSelected(read = true)
            R.id.cab_mark_as_unread -> markSelected(read = false)
        }
    }

    /**
     * Fork: mark the selected received messages read or unread. Unread sends 白い熊 back to the list,
     * since staying in the conversation would mark it read again on the next resume.
     */
    private fun markSelected(read: Boolean) {
        val received = getSelectedItems().filterIsInstance<Message>().filter { it.isReceivedMessage() }
        if (received.isEmpty()) {
            return
        }
        ensureBackgroundThread {
            received.forEach {
                if (read) {
                    activity.markMessageRead(it.id, it.isMMS)
                } else {
                    activity.markMessageUnread(it.id, it.isMMS, it.threadId)
                }
            }
            val updated = currentList.map { item ->
                if (item is Message && received.any { it.id == item.id && it.isMMS == item.isMMS }) item.copy(read = read) else item
            }
            refreshConversations()
            activity.runOnUiThread {
                submitList(updated)
                finishActMode()
                if (!read) {
                    activity.finish()
                }
            }
        }
    }

    override fun getSelectableItemCount() = currentList.filterIsInstance<Message>().size

    override fun getIsItemSelectable(position: Int) = !isThreadDateTime(position)

    override fun getItemSelectionKey(position: Int): Int? {
        return (currentList.getOrNull(position) as? Message)?.getSelectionKey()
    }

    override fun getItemKeyPosition(key: Int): Int {
        return currentList.indexOfFirst { (it as? Message)?.getSelectionKey() == key }
    }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = when (viewType) {
            THREAD_DATE_TIME -> ItemThreadDateTimeBinding.inflate(layoutInflater, parent, false)
            else -> ItemMessageBinding.inflate(layoutInflater, parent, false)
        }

        return ThreadViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val isClickable = item is Message
        val isLongClickable = item is Message
        holder.bindView(item, isClickable, isLongClickable) { itemView, _ ->
            when (item) {
                is ThreadDateTime -> bindDayHeader(itemView, item.date, first = position == 0)
                is Message -> setupView(holder, itemView, item)
            }
        }
        bindViewHolder(holder)
    }

    override fun getItemId(position: Int): Long {
        return when (val item = getItem(position)) {
            is Message -> item.getStableId()
            is ThreadDateTime -> generateStableId(THREAD_DATE_TIME, item.date.toLong())
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = getItem(position)) {
            is ThreadDateTime -> THREAD_DATE_TIME
            is Message -> if (item.isReceivedMessage()) THREAD_RECEIVED_MESSAGE else THREAD_SENT_MESSAGE
        }
    }

    private fun copyToClipboard() {
        val selectedMessages = getSelectedItems().filterIsInstance<Message>()
        if (selectedMessages.isEmpty()) return

        val textToCopy = if (selectedMessages.size == 1) {
            selectedMessages.first().body
        } else {
            selectedMessages.filter { it.body.isNotEmpty() }.joinToString("\n\n") { message ->
                val format = "${activity.config.dateFormat}, ${activity.getTimeFormat()}"
                val dateTime = DateTime(message.millis()).toString(format)
                val sender = if (message.isReceivedMessage()) message.senderName else activity.getString(R.string.me)
                "[$dateTime] $sender: ${message.body}"
            }
        }

        if (textToCopy.isNotEmpty()) {
            activity.copyToClipboard(textToCopy)
        }
    }

    private fun getSelectedAttachments(): List<Attachment> {
        val selectedMessages = getSelectedItems().filterIsInstance<Message>()
        return selectedMessages.flatMap { it.attachment?.attachments.orEmpty() }
    }

    private fun saveAs() {
        val attachments = getSelectedAttachments()
        if (attachments.isNotEmpty()) {
            (activity as ThreadActivity).saveMMS(attachments)
        }
    }

    private fun shareText() {
        val firstItem = getSelectedItems().firstOrNull() as? Message ?: return
        activity.shareTextIntent(firstItem.body)
    }

    private fun selectText() {
        val firstItem = getSelectedItems().firstOrNull() as? Message ?: return
        if (firstItem.body.trim().isNotEmpty()) {
            SelectTextDialog(activity, firstItem.body)
        }
    }

    private fun showMessageDetails() {
        val message = getSelectedItems().firstOrNull() as? Message ?: return
        MessageDetailsDialog(activity, message)
    }

    private fun askConfirmDelete() {
        val itemsCnt = selectedKeys.size

        // not sure how we can get UnknownFormatConversionException here, so show the error and hope that someone reports it
        val items = try {
            resources.getQuantityString(R.plurals.delete_messages, itemsCnt, itemsCnt)
        } catch (e: Exception) {
            activity.showErrorToast(e)
            return
        }

        val baseString = if (activity.config.useRecycleBin && !isRecycleBin) {
            org.fossify.commons.R.string.move_to_recycle_bin_confirmation
        } else {
            org.fossify.commons.R.string.deletion_confirmation
        }
        val question = String.format(resources.getString(baseString), items)

        DeleteConfirmationDialog(activity, question, activity.config.useRecycleBin && !isRecycleBin) { skipRecycleBin ->
            ensureBackgroundThread {
                val messagesToRemove = getSelectedItems()
                if (messagesToRemove.isNotEmpty()) {
                    val toRecycleBin = !skipRecycleBin && activity.config.useRecycleBin && !isRecycleBin
                    deleteMessages(messagesToRemove.filterIsInstance<Message>(), toRecycleBin, false)
                }
            }
        }
    }

    private fun askConfirmRestore() {
        val itemsCnt = selectedKeys.size

        // not sure how we can get UnknownFormatConversionException here, so show the error and hope that someone reports it
        val items = try {
            resources.getQuantityString(R.plurals.delete_messages, itemsCnt, itemsCnt)
        } catch (e: Exception) {
            activity.showErrorToast(e)
            return
        }

        val baseString = R.string.restore_confirmation
        val question = String.format(resources.getString(baseString), items)

        ConfirmationDialog(activity, question) {
            ensureBackgroundThread {
                val messagesToRestore = getSelectedItems()
                if (messagesToRestore.isNotEmpty()) {
                    deleteMessages(messagesToRestore.filterIsInstance<Message>(), false, true)
                }
            }
        }
    }

    private fun forwardMessage() {
        val message = getSelectedItems().firstOrNull() as? Message ?: return
        val attachment = message.attachment?.attachments?.firstOrNull()
        Intent(activity, NewConversationActivity::class.java).apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, message.body)

            if (attachment != null) {
                putExtra(Intent.EXTRA_STREAM, attachment.getUri())
            }

            activity.startActivity(this)
        }
    }

    private fun getSelectedItems(): ArrayList<ThreadItem> {
        return currentList.filter {
            selectedKeys.contains((it as? Message)?.getSelectionKey() ?: 0)
        } as ArrayList<ThreadItem>
    }

    private fun isThreadDateTime(position: Int) = currentList.getOrNull(position) is ThreadDateTime

    fun updateMessages(
        newMessages: List<ThreadItem>,
        scrollPosition: Int = -1,
        smoothScroll: Boolean = false
    ) {
        val latestMessages = newMessages.toMutableList()
        submitList(latestMessages) {
            if (scrollPosition != -1) {
                if (smoothScroll) {
                    recyclerView.smoothScrollToPosition(scrollPosition)
                } else {
                    recyclerView.scrollToPosition(scrollPosition)
                }
            }
        }
    }

    private fun setupView(holder: ViewHolder, view: View, message: Message) {
        ItemMessageBinding.bind(view).apply {
            val received = message.isReceivedMessage()
            if (threadMessageHolder.foreground == null) threadMessageHolder.foreground = activity.rowSelectorDrawable()
            threadMessageHolder.isSelected = selectedKeys.contains(message.getSelectionKey())
            threadMessageBody.apply {
                text = message.body
                setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                beVisibleIf(message.body.isNotEmpty())
                setOnLongClickListener {
                    holder.viewLongClicked()
                    true
                }

                setOnClickListener {
                    holder.viewClicked(message)
                }
            }

            val state = bubbleState(message)
            val frameColor = when {
                state == BubbleState.FAILED -> activity.themeColor(ThemeSlot.THREAD_FAILED)
                received -> activity.themeColor(ThemeSlot.THREAD_RECEIVED_FRAME)
                else -> activity.themeColor(ThemeSlot.THREAD_SENT_FRAME)
            }
            setupBubble(binding = this, message = message, state = state, frameColor = frameColor)
            setupFooter(binding = this, message = message, state = state)
            setupAvatar(binding = this, message = message, frameColor = frameColor)

            threadMessageAttachmentsHolder.removeAllViews()
            if (message.attachment?.attachments?.isNotEmpty() == true) {
                threadMessageAttachmentsFrame.beVisible()
                for (attachment in message.attachment.attachments) {
                    val mimetype = attachment.mimetype
                    when {
                        mimetype.isImageMimeType() || mimetype.isVideoMimeType() -> setupImageView(holder, binding = this, message, attachment)
                        mimetype.isPlayableAudioMimeType() ->
                            setupAudioView(holder, threadMessageAttachmentsHolder, message, attachment)
                        mimetype.isVCardMimeType() -> setupVCardView(holder, threadMessageAttachmentsHolder, message, attachment)
                        else -> setupFileView(holder, threadMessageAttachmentsHolder, message, attachment)
                    }

                    threadMessagePlayOutline.beVisibleIf(mimetype.startsWith("video/"))
                }
            } else {
                threadMessageAttachmentsFrame.beGone()
                threadMessagePlayOutline.beGone()
            }
        }
    }

    private enum class BubbleState { RECEIVED, SENT, DELIVERED, PENDING, FAILED, OTHER }

    private fun bubbleState(message: Message) = when {
        message.isReceivedMessage() -> BubbleState.RECEIVED
        message.isScheduled -> BubbleState.PENDING
        else -> when (message.type) {
            Telephony.Sms.MESSAGE_TYPE_FAILED -> BubbleState.FAILED
            Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> BubbleState.PENDING
            Telephony.Sms.MESSAGE_TYPE_SENT ->
                if (message.status == Telephony.Sms.STATUS_COMPLETE) BubbleState.DELIVERED else BubbleState.SENT
            else -> BubbleState.OTHER
        }
    }

    /**
     * Fork: the bubble is a frame, never a fill — thick for received, thin for sent, dashed while a
     * message is still sending or scheduled, red when it failed. It hugs its sender's side of the
     * list, half an avatar in, and its text keeps clear of the avatar that straddles that edge.
     */
    private fun setupBubble(binding: ItemMessageBinding, message: Message, state: BubbleState, frameColor: Int) {
        val received = state == BubbleState.RECEIVED
        binding.threadMessageBubble.apply {
            updateLayoutParams<FrameLayout.LayoutParams> {
                gravity = (if (received) Gravity.START else Gravity.END) or Gravity.TOP
                marginStart = if (received) nearMargin else farMargin
                marginEnd = if (received) farMargin else nearMargin
            }
            val vertical = dp(BUBBLE_PAD_V_DP)
            // tall enough for the avatar on its edge
            minimumHeight = dp(avatarDp + 2 * AVATAR_TOP_DP)
            setPaddingRelative(
                if (received) nearPadding else farPadding, vertical, if (received) farPadding else nearPadding, vertical
            )
            background = GradientDrawable().apply {
                cornerRadius = BUBBLE_RADIUS_DP * density
                setColor(activity.themeColor(if (received) ThemeSlot.THREAD_RECEIVED_BUBBLE else ThemeSlot.THREAD_SENT_BUBBLE))
                when (state) {
                    BubbleState.RECEIVED -> setStroke(dp(FRAME_RECEIVED_DP), frameColor)
                    BubbleState.PENDING ->
                        setStroke(dp(FRAME_PENDING_DP), frameColor, DASH_DP * density, DASH_GAP_DP * density)
                    BubbleState.FAILED -> setStroke(dp(FRAME_PENDING_DP), frameColor)
                    else -> setStroke(maxOf(1, (FRAME_SENT_DP * density).toInt()), frameColor)
                }
            }
        }

        binding.threadMessageBody.apply {
            if (received) {
                setTextColor(activity.themeColor(ThemeSlot.THREAD_RECEIVED_TEXT))
                setLinkTextColor(activity.themeColor(ThemeSlot.LINK))
                applyThemeFont(ThemeSlot.THREAD_RECEIVED_TEXT)
            } else {
                val textColor = activity.themeColor(ThemeSlot.THREAD_SENT_TEXT)
                setTextColor(textColor)
                setLinkTextColor(textColor)
                applyThemeFont(ThemeSlot.THREAD_SENT_TEXT, if (message.isScheduled) Typeface.ITALIC else Typeface.NORMAL)
            }
        }
    }

    /**
     * Fork: the footer inside the bubble. Received: SIM badge, then the time, at the start. Sent:
     * the time (or "Sending…" / "Not sent"), the SIM badge, then the delivery mark, at the end.
     */
    private fun setupFooter(binding: ItemMessageBinding, message: Message, state: BubbleState) = binding.apply {
        val received = state == BubbleState.RECEIVED
        val failed = state == BubbleState.FAILED
        threadMessageTime.apply {
            // its own size when one is set on the UI page, else a share of this bubble's message text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, threadMessageBody.textSize * FOOTER_TEXT_SHARE)
            applyThemeFont(ThemeSlot.THREAD_DATE)
            text = when {
                failed -> activity.getString(R.string.message_not_sent_touch_retry)
                state == BubbleState.PENDING && !message.isScheduled -> activity.getString(R.string.sending)
                else -> message.millis().formatMessageTimeOfDay(activity)
            }
            setTextColor(activity.themeColor(if (failed) ThemeSlot.THREAD_FAILED else ThemeSlot.THREAD_DATE))
        }
        val lineHeight = threadMessageTime.lineHeight

        threadSimBadge.beVisibleIf(hasMultipleSIMCards)
        if (hasMultipleSIMCards) {
            showSimBadge(
                icon = threadSimIcon,
                label = threadSimNumber,
                style = simStyle(message.subscriptionId),
                cardHeightPx = lineHeight,
                textSizePx = lineHeight * SIM_BADGE_TEXT_SHARE,
            )
        }

        val statusIcon = when (state) {
            BubbleState.DELIVERED -> R.drawable.ic_check_double_vector
            BubbleState.SENT -> org.fossify.commons.R.drawable.ic_check_vector
            BubbleState.PENDING -> org.fossify.commons.R.drawable.ic_clock_vector
            else -> null
        }
        threadMessageStatus.beVisibleIf(statusIcon != null)
        if (statusIcon != null) {
            threadMessageStatus.setImageResource(statusIcon)
            threadMessageStatus.applyColorFilter(activity.themeColor(ThemeSlot.THREAD_STATUS))
            threadMessageStatus.updateLayoutParams<LinearLayout.LayoutParams> {
                width = lineHeight
                height = lineHeight
            }
        }

        val order = if (received) {
            listOf(threadSimBadge, threadMessageTime, threadMessageStatus)
        } else {
            listOf(threadMessageTime, threadSimBadge, threadMessageStatus)
        }
        if (threadMessageFooter.getChildAt(0) !== order.first()) {
            threadMessageFooter.removeAllViews()
            order.forEach { threadMessageFooter.addView(it) }
        }
        var leading = true
        order.forEach { child ->
            val shown = child.visibility != View.GONE
            child.updateLayoutParams<LinearLayout.LayoutParams> {
                marginStart = if (leading || !shown) 0 else dp(FOOTER_GAP_DP)
            }
            if (shown) leading = false
        }
        threadMessageFooter.updateLayoutParams<LinearLayout.LayoutParams> {
            gravity = if (received) Gravity.START else Gravity.END
        }
    }

    /**
     * Fork: the avatar sits half in, half out on its sender's side edge of the bubble — the
     * contact's on the left of a received one, 白い熊's own on the right of a sent one. A ring in the
     * background colour parts it from the frame it crosses.
     */
    private fun setupAvatar(binding: ItemMessageBinding, message: Message, frameColor: Int) {
        val received = message.isReceivedMessage()
        val background = activity.themeColor(ThemeSlot.AVATAR_HALO)
        binding.threadMessageSenderPhoto.apply {
            updateLayoutParams<FrameLayout.LayoutParams> {
                gravity = (if (received) Gravity.START else Gravity.END) or Gravity.TOP
                width = dp(avatarDp + 2 * AVATAR_HALO_DP)
                height = width
                topMargin = dp(AVATAR_TOP_DP - AVATAR_HALO_DP)
            }
            dp(AVATAR_RING_PAD_DP).let { setPadding(it, it, it, it) }
            val halo = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(background)
            }
            val ring = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(background)
                setStroke(
                    maxOf(1, (AVATAR_RING_DP * density).toInt()),
                    ColorUtils.blendARGB(background, frameColor, AVATAR_RING_BLEND)
                )
            }
            val inset = dp(AVATAR_HALO_DP)
            this.background = LayerDrawable(arrayOf(halo, ring)).apply { setLayerInset(1, inset, inset, inset, inset) }

            if (received) {
                setOnClickListener {
                    val contact = message.getSender() ?: return@setOnClickListener
                    val number = contact.phoneNumbers.firstOrNull()?.normalizedNumber ?: return@setOnClickListener
                    activity.getContactFromAddress(number) {
                        if (it != null) {
                            activity.startContactDetailsIntent(it)
                        }
                    }
                }
                loadAvatar(this, senderPhotoUri(message), message.senderName, activity.themeColor(ThemeSlot.AVATAR_GLYPH))
            } else {
                setOnClickListener(null)
                isClickable = false
                loadAvatar(this, ownPhotoUri, activity.getString(R.string.me), activity.themeColor(ThemeSlot.AVATAR_GLYPH))
            }
        }
    }

    private fun loadAvatar(view: ImageView, photoUri: String, name: String, glyphColor: Int) {
        if (photoUri.isEmpty()) {
            // Nobody's picture: the brush-stroke 人 alone, without the black square of the stock
            // image (which covered the ring), cut so its three tips touch the ring.
            Glide.with(activity).clear(view)
            view.setImageResource(R.drawable.ic_unknown_contact_round)
            view.applyColorFilter(glyphColor)
        } else if (!activity.isFinishing && !activity.isDestroyed) {
            view.clearColorFilter()
            val placeholder = SimpleContactsHelper(activity).getContactLetterIcon(name).toDrawable(activity.resources)
            val options = RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .error(placeholder)
                .centerCrop()

            Glide.with(activity)
                .load(photoUri)
                .placeholder(placeholder)
                .apply(options)
                .apply(RequestOptions.circleCropTransform())
                .into(view)
        }
    }

    private fun setupImageView(holder: ViewHolder, binding: ItemMessageBinding, message: Message, attachment: Attachment) = binding.apply {
        val mimetype = attachment.mimetype
        val uri = attachment.getUri()

        val imageView = ItemAttachmentImageBinding.inflate(layoutInflater)
        threadMessageAttachmentsHolder.addView(imageView.root)

        val placeholderDrawable = Color.TRANSPARENT.toDrawable()
        val options = RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .placeholder(placeholderDrawable)
            .transform(FitCenter())

        Glide.with(root.context)
            .load(uri)
            .apply(options)
            .dontAnimate()
            .override(maxChatBubbleWidth, maxChatBubbleWidth * MAX_MEDIA_HEIGHT_RATIO)
            .downsample(DownsampleStrategy.AT_MOST)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    threadMessagePlayOutline.beGone()
                    threadMessageAttachmentsHolder.removeView(imageView.root)
                    return false
                }

                override fun onResourceReady(dr: Drawable, a: Any, t: Target<Drawable>, d: DataSource, i: Boolean) = false
            })
            .into(imageView.attachmentImage)

        imageView.attachmentImage.updateLayoutParams<ViewGroup.LayoutParams> {
            width = maxChatBubbleWidth
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        imageView.attachmentImage.setOnClickListener {
            if (actModeCallback.isSelectable) {
                holder.viewClicked(message)
            } else {
                activity.launchViewIntent(uri, mimetype, attachment.filename)
            }
        }
        imageView.root.setOnLongClickListener {
            holder.viewLongClicked()
            true
        }
    }

    private fun setupAudioView(holder: ViewHolder, parent: LinearLayout, message: Message, attachment: Attachment) {
        val audioView = ItemAttachmentAudioBinding.inflate(layoutInflater).apply {
            setupAudio(
                uri = attachment.getUri(),
                onOpenWith = {
                    activity.launchViewIntent(attachment.getUri(), attachment.mimetype, attachment.filename)
                },
                onSelect = { holder.viewClicked(message) },
                onLongClick = { holder.viewLongClicked() },
                isSelecting = { actModeCallback.isSelectable }
            )
        }.root
        parent.addView(audioView)
    }

    private fun setupVCardView(holder: ViewHolder, parent: LinearLayout, message: Message, attachment: Attachment) {
        val uri = attachment.getUri()
        val vCardView = ItemAttachmentVcardBinding.inflate(layoutInflater).apply {
            setupVCardPreview(
                activity = activity,
                uri = uri,
                onClick = {
                    if (actModeCallback.isSelectable) {
                        holder.viewClicked(message)
                    } else {
                        val intent = Intent(activity, VCardViewerActivity::class.java).also {
                            it.putExtra(EXTRA_VCARD_URI, uri)
                        }
                        activity.startActivity(intent)
                    }
                },
                onLongClick = { holder.viewLongClicked() }
            )
        }.root

        parent.addView(vCardView)
    }

    private fun setupFileView(holder: ViewHolder, parent: LinearLayout, message: Message, attachment: Attachment) {
        val mimetype = attachment.mimetype
        val uri = attachment.getUri()
        val attachmentView = ItemAttachmentDocumentBinding.inflate(layoutInflater).apply {
            setupDocumentPreview(
                uri = uri,
                title = attachment.filename,
                mimeType = attachment.mimetype,
                onClick = {
                    if (actModeCallback.isSelectable) {
                        holder.viewClicked(message)
                    } else {
                        activity.launchViewIntent(uri, mimetype, attachment.filename)
                    }
                },
                onLongClick = { holder.viewLongClicked() }
            )
        }.root

        parent.addView(attachmentView)
    }

    /**
     * Fork: the day heading — a thin full-width divider, then the date in bold with a text-wide
     * underline. The [first] heading of the list sits under the header's own line and needs no divider.
     */
    private fun bindDayHeader(view: View, dateSeconds: Int, first: Boolean) {
        ItemThreadDateTimeBinding.bind(view).apply {
            val color = activity.themeColor(ThemeSlot.THREAD_DAY_DATE)
            threadDateTime.apply {
                text = (dateSeconds * 1000L).dayHeaderLabel(activity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, DAY_HEADER_SP)
                applyThemeFont(ThemeSlot.THREAD_DAY_DATE, Typeface.BOLD)
                setTextColor(color)
            }
            threadDayUnderline.setBackgroundColor(color)
            threadDayDivider.setBackgroundColor(activity.themeColor(ThemeSlot.THREAD_DAY_DIVIDER))
            threadDayDivider.visibility = if (first) View.INVISIBLE else View.VISIBLE
        }
    }

    // Re-bind every message so per-element theme/font changes from Settings take effect immediately.
    @SuppressLint("NotifyDataSetChanged")
    fun refreshTheme() {
        simStyles = null
        readAvatarSize()
        activity.ownAvatarPath().let { if (it.isNotEmpty() || ownPhotoUri.startsWith(activity.filesDir.path)) ownPhotoUri = it }
        notifyDataSetChanged()
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            val binding = (holder as ThreadViewHolder).binding
            if (binding is ItemMessageBinding) {
                Glide.with(activity).clear(binding.threadMessageSenderPhoto)
            }
        }
    }

    inner class ThreadViewHolder(val binding: ViewBinding) : ViewHolder(binding.root)
}

private class ThreadItemDiffCallback : DiffUtil.ItemCallback<ThreadItem>() {

    override fun areItemsTheSame(oldItem: ThreadItem, newItem: ThreadItem): Boolean {
        if (oldItem::class.java != newItem::class.java) return false
        return when (oldItem) {
            is Message -> Message.areItemsTheSame(oldItem, newItem as Message)
            is ThreadDateTime -> oldItem.date == (newItem as ThreadDateTime).date
        }
    }

    override fun areContentsTheSame(oldItem: ThreadItem, newItem: ThreadItem): Boolean {
        if (oldItem::class.java != newItem::class.java) return false
        return when (oldItem) {
            is ThreadDateTime -> oldItem == newItem
            is Message -> Message.areContentsTheSame(oldItem, newItem as Message)
        }
    }
}
