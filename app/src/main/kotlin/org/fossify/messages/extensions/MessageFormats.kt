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

/** The local calendar day of an epoch-millis time, as a sortable key. */
fun Long.dayCode(): String = android.text.format.DateFormat.format("yyyyMMdd", this).toString()

/** A day heading: 今日, 昨日, then the date alone in the configured (和暦 or stock) form. */
fun Long.dayHeaderLabel(context: Context): String = when {
    DateUtils.isToday(this) -> context.getString(R.string.day_header_today)
    dayCode() == (System.currentTimeMillis() - DateUtils.DAY_IN_MILLIS).dayCode() ->
        context.getString(R.string.day_header_yesterday)
    context.config.useImperialDate -> toImperialDateString()
    else -> formatDateOrTime(context, hideTimeOnOtherDays = true, showCurrentYear = false)
}

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

/**
 * A phone number as 白い熊 reads it: grouped the way its country writes it, the groups joined by
 * dashes — +420-737-461-999. A string that is no number (a sender name, a short code) stays as it is.
 */
fun String.formatPhoneNumberDashed(): String {
    if (count { it.isDigit() } < MIN_FORMATTED_DIGITS || any { it.isLetter() }) return this
    val formatted = android.telephony.PhoneNumberUtils.formatNumber(this, Locale.getDefault().country) ?: return this
    return formatted.trim().replace(Regex("[\\s()]+"), "-").trim('-')
}

private const val MIN_FORMATTED_DIGITS = 7
