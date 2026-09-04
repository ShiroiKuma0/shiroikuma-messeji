package org.fossify.messages.helpers

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.Closeable
import java.util.Timer
import java.util.TimerTask
import org.fossify.messages.R

/**
 * The one progress sender both automation doors use — the [org.fossify.messages.receivers.StateExportReceiver]
 * broadcasts and the [org.fossify.messages.automation.AutomationProvider] data door alike.
 *
 * It is one implementation on purpose. Both callers treat a progress broadcast as a **heartbeat** and
 * presume an app silent for two minutes to be dead, so this is a watchdog — and two implementations of
 * the same watchdog drift, with the one that drifts always being the one nobody is looking at. What
 * differs between the doors is only the correlation id: the receiver answers a caller's "reply_id",
 * the data door its own "job_id", which it sends in *both* extras so a single reader on the caller's
 * side serves either.
 *
 * Real counts, never a percentage; at most one broadcast per [PROGRESS_THROTTLE_MS].
 *
 * ## A throttle is not a heartbeat
 *
 * A throttle only ever *withholds* messages, so an export that stops reporting stops broadcasting —
 * and "our export is fast" does not save us on the data door. There the destination is a descriptor
 * **the caller opened, which may be a pipe**: one write then blocks for exactly as long as 応用管理 is
 * slow to drain it, and this app's messages category carries every MMS attachment through it. The
 * export core reports a line before each category and per message inside one, so the last line is
 * always current when a write blocks — and [Channel] re-sends it every [HEARTBEAT_MS] until the
 * numbers move again. Nothing is invented: a heartbeat repeats the truth rather than fabricating
 * progress, which is what makes it honest to hold a caller's slot with.
 *
 * A caller that passed no progress action gets nothing, so every part of this is additive.
 */
object AutomationProgress {

    /**
     * §3 gives up on an app silent for 30 s. Beating at two thirds of that leaves room for one lost or
     * delayed broadcast before the caller starts counting us out.
     */
    private const val HEARTBEAT_MS = 20_000L

    private const val TAG = "MessejiAutomation"

    fun channel(
        context: Context,
        progressAction: String,
        replyPackage: String,
        replyId: String,
        jobId: String? = null,
    ): Channel {
        val appContext = context.applicationContext
        return Channel(
            context = appContext,
            progressAction = progressAction,
            replyPackage = replyPackage,
            replyId = replyId,
            jobId = jobId,
            appLabel = appContext.getString(R.string.app_launcher_name),
            unitCategory = appContext.getString(R.string.state_progress_unit_category),
        )
    }

    /**
     * The throttled progress channel, the unthrottled completion broadcast, and the heartbeat behind
     * both. **[close] it in a `finally`** — the timer thread is a daemon, so a leaked one cannot hold
     * the process up, but it would go on broadcasting a finished export's last line.
     */
    // A broadcast that cannot be sent must never take the export down with it: whatever the platform
    // throws here becomes a log line, not a failed backup.
    @Suppress("TooGenericExceptionCaught", "LongParameterList")
    class Channel internal constructor(
        private val context: Context,
        private val progressAction: String,
        private val replyPackage: String,
        private val replyId: String,
        private val jobId: String?,
        private val appLabel: String,
        private val unitCategory: String,
    ) : Closeable {

        private class Line(val current: Long, val total: Long, val unit: String, val text: String)

        // Written from the export thread, read from the heartbeat's.
        @Volatile
        private var lastSentAt = 0L

        @Volatile
        private var lastLine: Line? = null

        private val heartbeat: Timer? = if (progressAction.isEmpty()) {
            null
        } else {
            Timer("automation-progress-heartbeat", true).apply {
                schedule(
                    object : TimerTask() {
                        override fun run() = beat()
                    },
                    HEARTBEAT_MS,
                    HEARTBEAT_MS,
                )
            }
        }

        /** What the export core reports into. Throttled; the heartbeat covers what it withholds. */
        val reporter: ProgressReporter = { current, total, unit, text ->
            lastLine = Line(current, total, unit, text)
            if (progressAction.isNotEmpty() && System.currentTimeMillis() - lastSentAt >= PROGRESS_THROTTLE_MS) {
                send(current, total, unit, text)
            }
        }

        /** The mandatory final message, unthrottled — [categories] of [categories], done. */
        fun complete(categories: Long) {
            if (progressAction.isNotEmpty()) {
                send(categories, categories, unitCategory, "$unitCategory $categories/$categories")
            }
        }

        override fun close() {
            heartbeat?.cancel()
        }

        /**
         * Repeat the last line if the real reporter has genuinely gone quiet. Nothing is repeated
         * before the export has said something once: an invented number would be worse than silence,
         * and the export core always reports before the first write that could block.
         */
        private fun beat() {
            val line = lastLine ?: return
            if (System.currentTimeMillis() - lastSentAt < HEARTBEAT_MS) {
                return
            }
            Log.i(TAG, "heartbeat → ${line.text}")
            send(line.current, line.total, line.unit, line.text)
        }

        private fun send(current: Long, total: Long, unit: String, text: String) {
            lastSentAt = System.currentTimeMillis()
            try {
                val intent = Intent(progressAction)
                    .setPackage(replyPackage.ifEmpty { null })
                    .putExtra(EXTRA_REPLY_ID, replyId)
                    .putExtra(EXTRA_PROGRESS_APP, appLabel)
                    .putExtra(EXTRA_PROGRESS_TEXT, text)
                    .putExtra(EXTRA_PROGRESS_CURRENT, current)
                    .putExtra(EXTRA_PROGRESS_TOTAL, total)
                    .putExtra(EXTRA_PROGRESS_UNIT, unit)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                if (jobId != null) {
                    intent.putExtra(EXTRA_JOB_ID, jobId)
                }
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                Log.w(TAG, "progress broadcast failed: $e")
            }
        }
    }
}
