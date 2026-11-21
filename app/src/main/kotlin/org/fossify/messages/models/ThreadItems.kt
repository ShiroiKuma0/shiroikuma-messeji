package org.fossify.messages.models

/**
 * Thread item representations for the main thread recyclerview. [Message] is also a [ThreadItem].
 * Fork: the only other row is the day heading — time, SIM badge and delivery status live in each
 * bubble's footer, so there are no separate status rows.
 */
sealed class ThreadItem {
    /** The heading above the first message of a day; [date] is that message's time in seconds. */
    data class ThreadDateTime(val date: Int) : ThreadItem()
}
