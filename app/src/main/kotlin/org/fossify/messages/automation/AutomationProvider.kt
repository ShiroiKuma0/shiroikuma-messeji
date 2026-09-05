package org.fossify.messages.automation

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import org.fossify.messages.extensions.config
import org.fossify.messages.helpers.EXTRA_JOB_ID
import org.fossify.messages.helpers.SettingsEximport
import org.json.JSONArray
import org.json.JSONObject

/**
 * The data door: export this app's own state, and put it back, for a caller we can identify.
 *
 * ## Why a provider and not the broadcast receiver next to it
 *
 * Two reasons, and the first is the whole point of the v2 redesign.
 *
 * **A broadcast cannot tell you who sent it.** The old contract's answer to that was a shared secret,
 * which cannot survive the wipe this feature exists to recover from. A provider gets the caller's
 * identity from the framework for free — see [AutomationCallers] for what is actually checked and why
 * a `shiroikuma.*` prefix would have been *weaker* than the token it replaced.
 *
 * **A list needs a synchronous answer.** 白い熊 応用管理 draws a row per installed app before any
 * export exists; a broadcast round trip per app to fill a list is the wrong shape entirely.
 *
 * ## What does NOT happen here
 *
 * The payload. [call] validates, starts a foreground service and returns — tens of megabytes of this
 * app's messages, over minutes, inside a binder call would block the caller, report no progress,
 * refuse cancellation and die silently if this process were killed. The bytes go through a file
 * descriptor the caller opened, and the terminal answer comes back on the broadcast the family
 * already proved on EMUI.
 *
 * ## Why a descriptor and not a path
 *
 * Because a backup is not a stable directory while it is being assembled. 応用管理 writes into a
 * temporary path and renames on commit; it encrypts and checksums **per file it knows about**. A file
 * this app dropped into that directory itself would be renamed out from under it, would sit in
 * plaintext inside an encrypted backup, and would be unverified rather than verified-and-failing. A
 * descriptor is also a capability that **expires when it is closed**.
 *
 * It also means this door needs no `MANAGE_EXTERNAL_STORAGE`; that permission is here only for the
 * receiver's absolute-`path` extra, which predates it.
 *
 * ## Why `import` lives ONLY here
 *
 * An import overwrites this app's data — its settings and, with the messages category, the SMS/MMS
 * store itself. The [org.fossify.messages.receivers.StateExportReceiver] beside it is
 * `exported="true"` with no permission, so an import action there would let any app on the phone
 * rewrite 白い熊's message history. This door knows who is calling; that one does not.
 */
// The count is ContentProvider's own: query/insert/update/delete/getType must be overridden whether
// or not this door answers them, and they sit alongside the four the contract actually defines.
@Suppress("TooManyFunctions")
class AutomationProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /**
     * Every method answers a [Bundle] with [KEY_RESULT] — `OK…` or `ERROR:…`, the same vocabulary the
     * broadcast contract uses, so a caller has one grammar to parse rather than two.
     *
     * A refusal is returned, never thrown: an exception across a binder reaches the caller as a
     * `RuntimeException` with our stack trace in it, which tells 白い熊 nothing and tells a
     * misbehaving caller rather more than it should.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return fail("ERROR:not ready")

        // WHO, before WHAT. A caller we cannot identify gets the same answer whatever it asked for.
        when (val verdict = AutomationCallers.verify(ctx, callingPackage)) {
            is AutomationCallers.Verdict.Refused -> {
                Log.i(TAG, "$method refused: ${verdict.why}")
                return fail(verdict.why)
            }

            AutomationCallers.Verdict.Allowed -> Unit
        }
        // Then this app's own switches — the token is ignored unless this app is asking for one.
        ctx.config.automationRefusal(extras?.getString(KEY_TOKEN))?.let {
            Log.i(TAG, "$method refused: $it")
            return fail(it)
        }

        Log.i(TAG, "$method from $callingPackage")
        return when (method) {
            METHOD_DESCRIBE -> ok(describe(ctx))
            METHOD_EXPORT -> start(ctx, extras, importing = false)
            METHOD_IMPORT -> start(ctx, extras, importing = true)
            METHOD_CANCEL -> {
                AutomationJobs.cancel(extras?.getString(KEY_JOB_ID))
                ok("OK:cancelled")
            }

            else -> fail("ERROR:unknown method: $method")
        }
    }

    /**
     * What this app would export, answered without exporting anything.
     *
     * Returned from the call rather than written into the archive, deliberately: 応用管理 must draw a
     * row before an export exists, and at restore must judge compatibility **before** streaming tens
     * of megabytes into an app that would reject them — which it cannot do if the header is buried
     * inside an encrypted archive.
     *
     * `requires_launch_first` is false: every category here merges into SharedPreferences or the
     * system SMS/MMS store, neither of which needs this app to have been opened first.
     */
    @Suppress("DEPRECATION") // versionCode: the long form is API 28, and this app's minSdk is 26
    private fun describe(ctx: Context): String {
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val contains = SettingsEximport.Category.entries
            .filter { it.defaultOn }
            .map { ctx.getString(it.shortLabelRes) }
        val header = JSONObject()
            .put("app_id", ctx.packageName)
            .put("version_code", pkg.versionCode)
            .put("version_name", pkg.versionName.orEmpty())
            .put("format", FORMAT)
            .put("min_format_readable", MIN_FORMAT_READABLE)
            .put("requires_launch_first", false)
            .put("requires_permissions", JSONArray(IMPORT_PERMISSIONS))
            .put("contains", JSONArray(contains))
        return "OK:$header"
    }

    /**
     * Hand the descriptor to a foreground service and get out of the way.
     *
     * The descriptor is **duplicated** before it leaves this method. The one in [extras] belongs to
     * the binder transaction and is closed when `call()` returns; a service reading it afterwards
     * would find it shut. That is a bug you only see under load, so it is not left to the service to
     * remember.
     *
     * Starting the service is allowed to fail rather than throw: on API 31+ a background app may be
     * refused a foreground-service start outright, and a caller deserves that as a readable refusal
     * instead of our stack trace. The duplicate is closed on that path — a leaked descriptor holds
     * the caller's file open, and a caller cannot checksum or encrypt a file that is still open.
     */
    private fun start(ctx: Context, extras: Bundle?, importing: Boolean): Bundle {
        @Suppress("DEPRECATION")
        val fd = extras?.getParcelable<ParcelFileDescriptor>(KEY_FD)
            ?: return fail("ERROR:no descriptor")
        val dup = runCatching { fd.dup() }.getOrNull() ?: return fail("ERROR:descriptor unusable")
        val jobId = AutomationJobs.begin()
        return runCatching {
            AutomationDataService.start(ctx, jobId, dup, importing, extras)
            ok("OK:$jobId")
        }.getOrElse { e ->
            AutomationJobs.finish(jobId)
            runCatching { dup.close() }
            Log.w(TAG, "could not start the data service: $e")
            // Returned as this call's value, never broadcast: no OK:<job_id> was ever handed out for
            // a job that will not run, so a broadcast here would answer the caller twice. The
            // service's own promotion is the site where the OK has already gone and a broadcast IS
            // required — see AutomationDataService.onStartCommand.
            fail(AutomationDataService.startRefusal(ctx, e))
        }
    }

    private fun ok(result: String) = Bundle().apply { putString(KEY_RESULT, result) }

    private fun fail(why: String) = Bundle().apply { putString(KEY_RESULT, why) }

    // A provider that is only ever call()ed still has to answer these. Refusing loudly beats returning
    // an empty cursor, which reads downstream as "there is no data" rather than "wrong door".
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? =
        throw UnsupportedOperationException("automation is call() only")

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("automation is call() only")

    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException("automation is call() only")

    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int =
        throw UnsupportedOperationException("automation is call() only")

    companion object {
        const val TAG = "MessejiAutomation"

        const val METHOD_DESCRIBE = "describe"
        const val METHOD_EXPORT = "export"
        const val METHOD_IMPORT = "import"
        const val METHOD_CANCEL = "cancel"

        const val KEY_RESULT = "result"
        const val KEY_FD = "fd"
        const val KEY_TOKEN = "token"
        // one spelling for the correlation id, shared with the progress/reply extras
        const val KEY_JOB_ID = EXTRA_JOB_ID
        const val KEY_ITEMS = "items"
        const val KEY_REPLY_ACTION = "reply_action"
        const val KEY_REPLY_PACKAGE = "reply_package"
        const val KEY_PROGRESS_ACTION = "progress_action"

        /**
         * What an import needs **granted** before it can succeed — derived from what this app's
         * restore path actually writes, not from what kind of app it is. The restore order is
         * install → do not launch → import → force-stop, and a freshly installed app holds no
         * runtime permissions at all, so a caller that does not know this streams the whole archive
         * and only then collects a `SecurityException`.
         *
         * Every prefs-backed category here needs nothing. The **messages** category is the one that
         * does: `MessagesWriter` inserts SMS and MMS rows, MMS parts and addresses straight into the
         * system Telephony provider, and updates conversation dates there afterwards.
         *
         * **One caveat this array cannot express**, and the caller should not be misled by its
         * absence: the operative gate on that provider is being the **default SMS app**, a role
         * 白い熊 assigns rather than a permission any prompt can grant. Naming these two is still
         * strictly better than declaring `[]` — it moves the failure before the stream instead of
         * after it — but a granted pair is not by itself sufficient here.
         */
        private val IMPORT_PERMISSIONS = listOf(
            "android.permission.READ_SMS",
            "android.permission.WRITE_SMS",
        )

        /** This app's archive format — the FORMAT_VERSION SettingsEximport stamps into manifest.json. */
        const val FORMAT = 1

        /**
         * The oldest archive this build can still read.
         *
         * Version skew has a direction: old data into a newer app is normally fine, because an app
         * migrates its own storage; newer data into an older app is not. This field is what lets a
         * caller refuse the second case at discovery time, before anything is streamed.
         */
        const val MIN_FORMAT_READABLE = 1
    }
}
