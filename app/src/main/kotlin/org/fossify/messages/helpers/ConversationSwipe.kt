package org.fossify.messages.helpers

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.getContrastColor
import org.fossify.messages.R
import org.fossify.messages.adapters.ConversationsAdapter
import org.fossify.messages.extensions.ThemeSlot
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.themeColor
import org.fossify.messages.models.Conversation
import kotlin.math.abs
import kotlin.math.roundToInt

// What a horizontal swipe on a conversation-list row does. The stored Config value is the ordinal,
// so new entries go at the end.
enum class SwipeAction(@StringRes val labelRes: Int, @DrawableRes val iconRes: Int) {
    NONE(R.string.swipe_action_none, 0),
    DELETE(R.string.swipe_action_delete, org.fossify.commons.R.drawable.ic_delete_vector),
    TOGGLE_READ(R.string.swipe_action_toggle_read, org.fossify.commons.R.drawable.ic_mail_vector),
    ARCHIVE(R.string.swipe_action_archive, R.drawable.ic_archive_vector),
    TOGGLE_PIN(R.string.swipe_action_toggle_pin, R.drawable.ic_pin_vector),
}

fun swipeActionOf(index: Int) = SwipeAction.entries.getOrElse(index) { SwipeAction.NONE }

private const val SWIPE_ICON_SIZE_DP = 24
private const val SWIPE_LABEL_SIZE_SP = 15
private const val SWIPE_GAP_DP = 12

// How far a drag must travel before releasing it runs the action — a short flick, not a row's width.
private const val SWIPE_ARM_DP = 40
// Below the arm point the strip is drawn at this opacity, so the moment it will act is visible.
private const val SWIPE_UNARMED_ALPHA = 0x80
// After a release: the strip's run out to the row's end, and the spring back of an unarmed drag, in ms
// (ItemTouchHelper's defaults are ~250).
private const val SWIPE_FINISH_MS = 150L
private const val SWIPE_RETURN_MS = 120L
// How long the strip then stays across the full row before the action lands (row removed or put back);
// the same for every action.
private const val SWIPE_HOLD_MS = 200L

/**
 * Left / right swipe on the conversation list, each mapped to a [SwipeAction] picked on the UI page.
 * The row slides over a primary-colour strip that names the action. Releasing past [SWIPE_ARM_DP] lets
 * the strip run quickly out to the row's end, hold there for [SWIPE_HOLD_MS], and then runs the action.
 * A row the action keeps is put back by this callback itself: ItemTouchHelper would otherwise go on
 * drawing it swiped until the row leaves the screen, i.e. until the list refreshes. Releasing earlier
 * springs the row back. Swiping is off while a multi-selection is active, so it never fights the
 * contextual action bar.
 */
class ConversationSwipeCallback(private val adapter: ConversationsAdapter) :
    ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val context = adapter.activity
    private val density = context.resources.displayMetrics.density
    private val armDistance = SWIPE_ARM_DP * density
    private val fillPaint = Paint()

    // Rows whose swipe has finished but which stay in the list: drawn back in place, without a strip.
    private val putBack = HashSet<RecyclerView.ViewHolder>()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, SWIPE_LABEL_SIZE_SP.toFloat(), context.resources.displayMetrics
        )
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun actionFor(direction: Int) = swipeActionOf(
        if (direction == ItemTouchHelper.RIGHT) context.config.swipeRightAction else context.config.swipeLeftAction
    )

    override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        if (adapter.isInActionMode() || viewHolder.bindingAdapterPosition == RecyclerView.NO_POSITION) {
            return 0
        }

        var dirs = 0
        if (actionFor(ItemTouchHelper.RIGHT) != SwipeAction.NONE) dirs = dirs or ItemTouchHelper.RIGHT
        if (actionFor(ItemTouchHelper.LEFT) != SwipeAction.NONE) dirs = dirs or ItemTouchHelper.LEFT
        return dirs
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ) = false

    // The arm point as a fraction of the row, which is how ItemTouchHelper measures it.
    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) =
        (armDistance / viewHolder.itemView.width.coerceAtLeast(1)).coerceAtMost(1f)

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val recyclerView = adapter.recyclerView
        val conversation = adapter.currentList.getOrNull(viewHolder.bindingAdapterPosition)
        val action = actionFor(direction)
        recyclerView.postDelayed({
            val removed = conversation != null && adapter.performSwipeAction(conversation, action)
            if (!removed) {
                putBack.add(viewHolder)
                recyclerView.invalidate()
            }
        }, SWIPE_HOLD_MS)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        putBack.remove(viewHolder)
    }

    override fun getAnimationDuration(recyclerView: RecyclerView, animationType: Int, animateDx: Float, animateDy: Float) =
        when (animationType) {
            ItemTouchHelper.ANIMATION_TYPE_SWIPE_SUCCESS -> SWIPE_FINISH_MS
            ItemTouchHelper.ANIMATION_TYPE_SWIPE_CANCEL -> SWIPE_RETURN_MS
            else -> super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy)
        }

    @Suppress("LongParameterList") // ItemTouchHelper.Callback's own signature
    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        if (isCurrentlyActive) {
            putBack.remove(viewHolder)
        } else if (viewHolder in putBack) {
            super.onChildDraw(c, recyclerView, viewHolder, 0f, dY, actionState, false)
            return
        }

        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
            drawStrip(c, viewHolder, dX)
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }

    private fun drawStrip(c: Canvas, viewHolder: RecyclerView.ViewHolder, dX: Float) {
        val row = viewHolder.itemView
        val toRight = dX > 0
        val action = actionFor(if (toRight) ItemTouchHelper.RIGHT else ItemTouchHelper.LEFT)
        if (action == SwipeAction.NONE) {
            return
        }

        val conversation = adapter.currentList.getOrNull(viewHolder.bindingAdapterPosition)
        val fill = context.themeColor(ThemeSlot.PRIMARY)
        val ink = fill.getContrastColor()

        fillPaint.color = fill
        if (abs(dX) < armDistance) {
            fillPaint.alpha = SWIPE_UNARMED_ALPHA
        }
        val left = if (toRight) row.left.toFloat() else row.right + dX
        val right = if (toRight) row.left + dX else row.right.toFloat()
        c.save()
        c.clipRect(left, row.top.toFloat(), right, row.bottom.toFloat())
        c.drawRect(left, row.top.toFloat(), right, row.bottom.toFloat(), fillPaint)

        val gap = (SWIPE_GAP_DP * density).roundToInt()
        val iconSize = (SWIPE_ICON_SIZE_DP * density).roundToInt()
        val centerY = (row.top + row.bottom) / 2
        val label = context.getString(labelFor(action, conversation))
        labelPaint.color = ink
        val labelWidth = labelPaint.measureText(label)

        // icon nearest the edge the row is leaving, label just inside it
        val iconLeft = if (toRight) row.left + gap * 2 else row.right - gap * 2 - iconSize
        iconFor(action, conversation)?.let { icon ->
            icon.setBounds(iconLeft, centerY - iconSize / 2, iconLeft + iconSize, centerY + iconSize / 2)
            icon.setTint(ink)
            icon.draw(c)
        }
        val labelX = if (toRight) iconLeft + iconSize + gap.toFloat() else iconLeft - gap - labelWidth
        val labelY = centerY - (labelPaint.descent() + labelPaint.ascent()) / 2
        if (abs(dX) > gap) {
            c.drawText(label, labelX, labelY, labelPaint)
        }
        c.restore()
    }

    // The toggles name the state they will produce, not both halves of the pair.
    private fun labelFor(action: SwipeAction, conversation: Conversation?) = when {
        action == SwipeAction.TOGGLE_READ && conversation != null ->
            if (conversation.read) R.string.mark_as_unread else R.string.mark_as_read

        action == SwipeAction.TOGGLE_PIN && conversation != null ->
            if (adapter.isPinned(conversation)) R.string.unpin_conversation else R.string.pin_conversation

        action == SwipeAction.ARCHIVE -> R.string.archive
        else -> action.labelRes
    }

    private fun iconFor(action: SwipeAction, conversation: Conversation?) =
        when {
            action == SwipeAction.TOGGLE_PIN && conversation != null && adapter.isPinned(conversation) ->
                R.drawable.ic_unpin_vector

            else -> action.iconRes
        }.takeIf { it != 0 }?.let { ContextCompat.getDrawable(context, it)?.mutate() }
}
