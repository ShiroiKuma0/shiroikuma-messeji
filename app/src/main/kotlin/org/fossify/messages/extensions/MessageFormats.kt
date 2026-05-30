package org.fossify.messages.extensions

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.StringRes
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.formatTime
import org.fossify.messages.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// How a message's time-of-day (today's messages, plus the time appended to older thread separators)
// is shown. JAPANESE (the default) renders the kanji clock reading; the rest are plain masks.
// The stored Config value is the entry's ordinal.
enum class MessageTimeFormat(@StringRes val labelRes: Int) {
    JAPANESE(R.string.format_time_japanese),
    SYSTEM(R.string.format_time_system),
    HOUR_24(R.string.format_time_24h),
    HOUR_12(R.string.format_time_12h),
}

fun messageTimeFormatOf(index: Int) = MessageTimeFormat.entries.getOrElse(index) { MessageTimeFormat.JAPANESE }

// Drop-in replacement for commons' Long.formatDateOrTime that keeps the same today→time /
// earlier→date branching but swaps in our configurable Japanese clock + imperial-era formats.
fun Long.formatMessageDateOrTime(
    context: Context,
    hideTimeOnOtherDays: Boolean,
    showCurrentYear: Boolean,
): String {
    if (DateUtils.isToday(this)) {
        return formatMessageTimeOfDay(context)
    }

    val datePart = formatMessageDatePart(context, showCurrentYear)
    return if (hideTimeOnOtherDays) datePart else "$datePart ${formatMessageTimeOfDay(context)}"
}

// The time-of-day half, honouring the configured MessageTimeFormat.
fun Long.formatMessageTimeOfDay(context: Context): String =
    when (messageTimeFormatOf(context.config.messageTimeFormat)) {
        MessageTimeFormat.JAPANESE -> toJapaneseClockString()
        MessageTimeFormat.SYSTEM -> formatTime(context)
        MessageTimeFormat.HOUR_24 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(this))
        MessageTimeFormat.HOUR_12 -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(this))
    }

// The date half for non-today timestamps: 和暦 when enabled, otherwise the stock date string.
private fun Long.formatMessageDatePart(context: Context, showCurrentYear: Boolean): String =
    if (context.config.useImperialDate) {
        toImperialDateString()
    } else {
        // Reuse commons for the stock date; hideTimeOnOtherDays = true makes it return the date alone.
        formatDateOrTime(context, hideTimeOnOtherDays = true, showCurrentYear = showCurrentYear)
    }
