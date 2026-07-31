package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isRPlus
import org.fossify.messages.R
import org.fossify.messages.extensions.config
import org.fossify.messages.helpers.ACTION_CANCEL_EXPORT
import org.fossify.messages.helpers.ACTION_EXPORT_STATE
import org.fossify.messages.helpers.ACTION_LIST_CATEGORIES
import org.fossify.messages.helpers.EXTRA_AUTOMATION_TOKEN
import org.fossify.messages.helpers.EXTRA_BACKUP_PATH
import org.fossify.messages.helpers.EXTRA_EXPORT_ITEMS
import org.fossify.messages.helpers.EXTRA_PROGRESS_ACTION
import org.fossify.messages.helpers.EXTRA_PROGRESS_APP
import org.fossify.messages.helpers.EXTRA_PROGRESS_CURRENT
import org.fossify.messages.helpers.EXTRA_PROGRESS_TEXT
import org.fossify.messages.helpers.EXTRA_PROGRESS_TOTAL
import org.fossify.messages.helpers.EXTRA_PROGRESS_UNIT
import org.fossify.messages.helpers.EXTRA_REPLY_ACTION
import org.fossify.messages.helpers.EXTRA_REPLY_ID
import org.fossify.messages.helpers.EXTRA_REPLY_PACKAGE
import org.fossify.messages.helpers.EXTRA_REPLY_RESULT
import org.fossify.messages.helpers.PROGRESS_THROTTLE_MS
import org.fossify.messages.helpers.ProgressReporter
import org.fossify.messages.helpers.SettingsEximport

/**
 * The 保存復元 state-export contract, for 白い熊 自由作業盤's one-run backup of every sister app.
 *
 * Three exported, token-gated actions:
 *  - [ACTION_LIST_CATEGORIES] — instant; replies "OK:" plus one `id<TAB>label<TAB>parent<TAB>on|off`
 *    line per selectable category, the ids being exactly the ones "items" accepts (and the ZIP's entry
 *    names). This app's list is flat, so the parent field is always empty; the last field is the app's
 *    own answer to whether the item starts ticked in the caller's picker.
 *  - [ACTION_EXPORT_STATE] — runs the same category ZIP export as the Export/Import page, headlessly
 *    (no Activity, no interaction), and replies with the written path and its real size. Extras:
 *    "token", optional "path" (an absolute directory that OVERRIDES the configured export folder),
 *    optional "items" (comma-separated category ids; absent = everything), optional
 *    "progress_action", plus "reply_action"/"reply_package"/"reply_id".
 *  - [ACTION_CANCEL_EXPORT] — stops a running export: fire-and-forget, no reply of its own, and a
 *    silent no-op whenever there is nothing (of that "reply_id") to stop. See [cancel].
 *
 * Directory precedence: the "path" extra → the app's configured export folder → ERROR:no-directory.
 *
 * The reply is a plain broadcast carrying "reply_id" + "result" — the only channel that works on
 * 白い熊's EMUI, where the ordered-broadcast result is severed between third-party apps and a
 * Binder-bearing extra (ResultReceiver/PendingIntent/Messenger) may be dropped outright. Exactly one
 * terminal reply per request, guarded by an [AtomicBoolean] so an async success and a synchronous
 * error can never both fire.
 *
 * Progress is reported as real counts, never a percentage — "メッセージ 1234/8942", throttled to one
 * broadcast per [PROGRESS_THROTTLE_MS] with an unthrottled final one at completion.
 */
// Broad catches throughout are deliberate: whatever a request or a storage backend throws must become
// one ERROR: reply line, never a crash in the app that happens to host the receiver.
@Suppress("TooGenericExceptionCaught")
class StateExportReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "MessejiStateExport"
        private const val KILO = 1024.0

        /**
         * The one export in flight, if any. The contract forbids two at once, so a single slot is all
         * [ACTION_CANCEL_EXPORT] needs to find the run it means — written on the receiver thread, read
         * and flipped from another.
         */
        @Volatile
        private var running: RunningExport? = null
    }

    /** An export in flight: the request it answers, and the flag its write loop polls. */
    private class RunningExport(val replyId: String) {
        @Volatile
        var cancelled = false
    }

    /** What a parsed request turned out to be: already answerable, or an export to run. */
    private sealed class Request {
        class Done(val result: String) : Request()
        class Export(val cats: Set<SettingsEximport.Category>, val path: String) : Request()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_EXPORT_STATE && action != ACTION_LIST_CATEGORIES && action != ACTION_CANCEL_EXPORT) {
            return
        }

        val appContext = context.applicationContext
        if (action == ACTION_CANCEL_EXPORT) {
            cancel(appContext, intent)
            return
        }

        // goAsync() holds the broadcast open until finish(); the guard makes finishWith idempotent so
        // the async success path and any synchronous error path can't double-finish (and a dropped
        // path can't leave the caller waiting forever).
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        val replyAction = intent.getStringExtra(EXTRA_REPLY_ACTION)?.trim().orEmpty()
        val replyPackage = intent.getStringExtra(EXTRA_REPLY_PACKAGE)?.trim().orEmpty()
        val replyId = intent.getStringExtra(EXTRA_REPLY_ID)?.trim().orEmpty()
        val progressAction = intent.getStringExtra(EXTRA_PROGRESS_ACTION)?.trim().orEmpty()

        fun finishWith(result: String) {
            if (!finished.compareAndSet(false, true)) {
                return
            }
            Log.i(TAG, "result → $result")
            if (replyAction.isNotEmpty() && replyId.isNotEmpty()) {
                try {
                    appContext.sendBroadcast(
                        Intent(replyAction)
                            .setPackage(replyPackage.ifEmpty { null })
                            .putExtra(EXTRA_REPLY_ID, replyId)
                            .putExtra(EXTRA_REPLY_RESULT, result)
                            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    )
                    Log.i(TAG, "reply broadcast sent → $replyAction ($replyPackage, id=$replyId)")
                } catch (e: Exception) {
                    Log.w(TAG, "reply broadcast failed: $e")
                }
            }
            pending.setResultData(result)
            pending.finish()
        }

        val request = try {
            parse(appContext, intent, action)
        } catch (e: Exception) {
            Request.Done("ERROR:${reason(e)}")
        }

        when (request) {
            is Request.Done -> finishWith(request.result)
            is Request.Export -> {
                val progress = throttledProgress(appContext, progressAction, replyPackage, replyId)
                val job = RunningExport(replyId)
                running = job
                ensureBackgroundThread {
                    try {
                        finishWith(export(appContext, request.cats, request.path, progress, job))
                    } finally {
                        // only clear the slot while it is still ours — a later request owns it otherwise
                        if (running === job) {
                            running = null
                        }
                    }
                }
            }
        }
    }

    /**
     * [ACTION_CANCEL_EXPORT]: the same token gate as the others, then flip the running export's flag
     * and return. It sends no reply of its own — the export thread does the rest, deleting the partial
     * file and sending "ERROR:cancelled" as the original request's one terminal reply. Safe to send at
     * any time: with nothing running, or with a "reply_id" that is not the running one, it is a silent
     * no-op — not an error, not a reply, not a crash.
     */
    private fun cancel(context: Context, intent: Intent) {
        val config = context.config
        val token = intent.getStringExtra(EXTRA_AUTOMATION_TOKEN)
        if (!config.automationEnabled || !config.isAutomationTokenValid(token)) {
            Log.i(TAG, "cancel refused: enabled=${config.automationEnabled}, tokenLen=${token?.length ?: 0}")
            return
        }

        val wanted = intent.getStringExtra(EXTRA_REPLY_ID)?.trim().orEmpty()
        val export = running
        if (export == null || (wanted.isNotEmpty() && wanted != export.replyId)) {
            Log.i(TAG, "cancel: nothing to stop (id=$wanted)")
            return
        }
        export.cancelled = true
        Log.i(TAG, "cancel: stopping export ${export.replyId}")
    }

    /**
     * Decide the request without doing any work: the gate first (the switch and the token report
     * distinctly, since they debug differently), then the instant category list, then the export's own
     * validation — so a malformed request is answered before anything is written.
     */
    private fun parse(context: Context, intent: Intent, action: String?): Request {
        val config = context.config
        val token = intent.getStringExtra(EXTRA_AUTOMATION_TOKEN)
        val itemsRaw = intent.getStringExtra(EXTRA_EXPORT_ITEMS)?.trim().orEmpty()
        val path = intent.getStringExtra(EXTRA_BACKUP_PATH)?.trim().orEmpty()
        val cats = parseItems(itemsRaw)
        Log.i(
            TAG,
            "received $action: enabled=${config.automationEnabled}, tokenLen=${token?.length ?: 0}, " +
                "items=$itemsRaw, path=$path"
        )

        return when {
            !config.automationEnabled -> Request.Done("ERROR:automation disabled")
            !config.isAutomationTokenValid(token) -> Request.Done("ERROR:bad token")
            action == ACTION_LIST_CATEGORIES -> Request.Done(categoryList(context))
            cats == null -> Request.Done("ERROR:unknown category in items: $itemsRaw")
            path.isNotEmpty() && !path.startsWith("/") ->
                Request.Done("ERROR:$EXTRA_BACKUP_PATH must be an absolute directory")

            else -> Request.Export(cats, path)
        }
    }

    /**
     * "OK:" plus one `id<TAB>label<TAB>parent<TAB>on|off` line per category — the ids are exactly the
     * ones "items" accepts, and the names its data carries inside the ZIP. The parent field is empty
     * on every line (the list is flat) and the last one states whether the item starts ticked, so the
     * caller's picker opens on the same selection as this app's own.
     */
    private fun categoryList(context: Context): String =
        SettingsEximport.Category.entries.joinToString(separator = "\n", prefix = "OK:") {
            "${it.id}\t${context.getString(it.labelRes)}\t\t${if (it.defaultOn) "on" else "off"}"
        }

    /**
     * The requested categories, or null when [itemsRaw] names an id we do not export. Absent or empty
     * means everything.
     */
    private fun parseItems(itemsRaw: String): Set<SettingsEximport.Category>? {
        val ids = itemsRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) {
            return SettingsEximport.Category.entries.toSet()
        }
        val cats = ids.mapNotNull { SettingsEximport.Category.byId(it) }.toSet()
        return cats.takeIf { it.size == ids.distinct().size }
    }

    /** Runs on a background thread; returns the single result line and never throws. */
    private fun export(
        context: Context,
        cats: Set<SettingsEximport.Category>,
        path: String,
        progress: ThrottledProgress,
        job: RunningExport,
    ): String {
        val target = try {
            SettingsEximport.headlessTarget(context, path) ?: return "ERROR:no-directory"
        } catch (e: Exception) {
            return storageError(path, e)
        }

        return try {
            // The count is a fallback for a destination we cannot stat; it is final once export()
            // returns, which is after the ZIP's central directory has been flushed.
            val counting = CountingOutputStream(target.open())
            counting.use { SettingsEximport.export(context, cats, it, progress.reporter) { job.cancelled } }
            val bytes = target.size().takeIf { it > 0 } ?: counting.count
            progress.final(cats.size.toLong())
            "OK:${target.displayPath}|$bytes|${humanSize(bytes)}|${cats.size} categories"
        } catch (e: SettingsEximport.ExportCancelledException) {
            // the whole point of the cancel: leave the backup directory exactly as it was found
            Log.i(TAG, "export cancelled — removing the partial ${target.displayPath} ($e)")
            target.delete()
            "ERROR:cancelled"
        } catch (e: Exception) {
            target.delete()
            storageError(path, e)
        }
    }

    // An absolute path we were told to write but cannot needs All-files access; name that specifically,
    // since it is the one failure 白い熊 fixes with a toggle rather than a code change.
    private fun storageError(path: String, e: Exception): String {
        val noAllFiles = isRPlus() && !Environment.isExternalStorageManager()
        return if (path.isNotEmpty() && noAllFiles) "ERROR:no-storage-access" else "ERROR:${reason(e)}"
    }

    private fun reason(e: Throwable): String =
        (e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName).replace('\n', ' ')

    /** Display size for the reply line — the caller cannot stat the file, so we compute both forms. */
    private fun humanSize(bytes: Long): String = when {
        bytes < KILO -> "$bytes B"
        bytes < KILO * KILO -> "%.1f KB".format(Locale.ROOT, bytes / KILO)
        bytes < KILO * KILO * KILO -> "%.1f MB".format(Locale.ROOT, bytes / (KILO * KILO))
        else -> "%.2f GB".format(Locale.ROOT, bytes / (KILO * KILO * KILO))
    }

    private fun throttledProgress(
        context: Context,
        progressAction: String,
        replyPackage: String,
        replyId: String,
    ): ThrottledProgress {
        val appLabel = context.getString(R.string.app_launcher_name)
        val unitCategory = context.getString(R.string.state_progress_unit_category)

        fun send(current: Long, total: Long, unit: String, text: String) {
            try {
                context.sendBroadcast(
                    Intent(progressAction)
                        .setPackage(replyPackage.ifEmpty { null })
                        .putExtra(EXTRA_REPLY_ID, replyId)
                        .putExtra(EXTRA_PROGRESS_APP, appLabel)
                        .putExtra(EXTRA_PROGRESS_TEXT, text)
                        .putExtra(EXTRA_PROGRESS_CURRENT, current)
                        .putExtra(EXTRA_PROGRESS_TOTAL, total)
                        .putExtra(EXTRA_PROGRESS_UNIT, unit)
                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                )
            } catch (e: Exception) {
                Log.w(TAG, "progress broadcast failed: $e")
            }
        }

        var lastSent = 0L
        return ThrottledProgress(
            reporter = { current, total, unit, text ->
                val now = System.currentTimeMillis()
                if (progressAction.isNotEmpty() && now - lastSent >= PROGRESS_THROTTLE_MS) {
                    lastSent = now
                    send(current, total, unit, text)
                }
            },
            final = { categories ->
                if (progressAction.isNotEmpty()) {
                    send(categories, categories, unitCategory, "$unitCategory $categories/$categories")
                }
            },
        )
    }

    /** The throttled progress channel plus the unthrottled completion broadcast. */
    private class ThrottledProgress(val reporter: ProgressReporter, val final: (Long) -> Unit)

    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()

        override fun close() = out.close()
    }
}
