package org.fossify.messages.helpers

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.commons.helpers.ACCENT_COLOR
import org.fossify.commons.helpers.APP_ICON_COLOR
import org.fossify.commons.helpers.BACKGROUND_COLOR
import org.fossify.commons.helpers.COLOR_PICKER_RECENT_COLORS
import org.fossify.commons.helpers.FontHelper
import org.fossify.commons.helpers.IS_SYSTEM_THEME_ENABLED
import org.fossify.commons.helpers.PRIMARY_COLOR
import org.fossify.commons.helpers.TEXT_COLOR
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.helpers.isUpsideDownCakePlus
import org.fossify.messages.R
import org.fossify.messages.models.MessagesBackup
import org.fossify.messages.models.MmsBackup
import org.fossify.messages.models.SmsBackup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Progress as real counts, never a percentage — [current]/[total] of [unit], plus the ready-made
 * [text] line ("メッセージ 1234/8942") that both the in-app panel and the automation contract's
 * progress broadcasts display.
 */
typealias ProgressReporter = (current: Long, total: Long, unit: String, text: String) -> Unit

/**
 * Category export/import of every setting the app can persist (modelled on the Kōjiki fork's
 * exporter). The archive is a ZIP of one JSON file per category plus the imported font files;
 * every category is a slice of the single commons "Prefs" SharedPreferences file, split by key.
 * Prefs are serialized as a typed key→{t,v} map so all types round-trip, and import merges —
 * it never clears — so unrelated or device-local keys survive.
 *
 * [export] is the one export core: the Export/Import page and the headless automation receiver
 * ([org.fossify.messages.receivers.StateExportReceiver]) are two thin callers of it, so a backup
 * written by 自由作業盤 is byte-for-byte the same kind of archive as one written by hand.
 */
@Suppress("TooManyFunctions")
object SettingsEximport {
    private const val FORMAT = "shiroikuma-messeji-export"
    private const val FORMAT_VERSION = 1

    // The family's mandatory backup name: "<english-dash-separated-app-name>_<yyyy-MM-dd_HH-mm-ss>.zip"
    // — no version, no "-export" infix, no suffix, so every sister app's backups sort and read
    // uniformly in 白い熊's one backup directory. Older names ("shiroikuma-messeji-1.9.1-export_…")
    // still start with this prefix, so the "last export" query keeps recognising them.
    const val EXPORT_PREFIX = "shiroikuma-messeji"
    const val EXPORT_SUFFIX = ".zip"
    private const val FONTS_DIR_ENTRY = "fonts/"

    // Device-local prefs file for the export directory (its own file, so it never round-trips
    // through an export of the main "Prefs" file).
    private const val EXIMPORT_PREFS = "eximport"
    private const val KEY_DIR_URI = "dir_uri"

    /**
     * A selectable category; `id` is the JSON file name (`<id>.json`) inside the ZIP and, being
     * stable, is also what the automation contract's "items" extra accepts. [labelRes] is the
     * descriptive label the pickers show (in-app and in 自由作業盤), [shortLabelRes] the bare noun
     * used in progress lines ("区分 3/7 — 設定"). The list is flat: no category has sub-options.
     */
    enum class Category(val id: String, @StringRes val labelRes: Int, @StringRes val shortLabelRes: Int) {
        MESSAGES("messages", R.string.eim_cat_messages, R.string.eim_cat_messages_short),
        THEME("theme_colors", R.string.eim_cat_theme, R.string.eim_cat_theme_short),
        FONTS("fonts", R.string.eim_cat_fonts, R.string.eim_cat_fonts_short),
        FORMATS("formats", R.string.eim_cat_formats, R.string.eim_cat_formats_short),
        APP_SETTINGS("app_settings", R.string.eim_cat_app_settings, R.string.eim_cat_app_settings_short),
        CONVERSATIONS("conversations", R.string.eim_cat_conversations, R.string.eim_cat_conversations_short),
        BLOCKED("blocked_keywords", R.string.eim_cat_blocked, R.string.eim_cat_blocked_short);

        companion object {
            fun byId(id: String): Category? = entries.firstOrNull { it.id == id }
        }
    }

    // Device-local / runtime keys never worth exporting: migration flags, version counters,
    // storage paths & SAF grants, one-time dialog flags, lock-screen retry state, widget scratch.
    private val EXCLUDED_KEYS = setOf(
        THEME_V1_SEEDED, PURE_YELLOW_MIGRATED, WAS_DB_CLEARED, LAST_RECYCLE_BIN_CHECK,
        SOFT_KEYBOARD_HEIGHT, LAST_BLOCKED_KEYWORD_EXPORT_PATH,
        // the automation gate is device-local: the token must never travel in a backup, and a
        // restored archive must never silently switch this app's automation on
        AUTOMATION_ENABLED, AUTOMATION_TOKEN,
        "app_run_count", "last_version", "app_sideloading_status",
        "sd_card_path_2", "otg_real_path_2", "internal_storage_path", "otg_partition_2",
        "last_handled_shortcut_color", "last_icon_color", "last_export_path",
        "last_exported_settings_folder", "last_exported_settings_file",
        "password_retry_count", "password_count_down_start_ms", "last_unlock_timestamp_ms",
        "initial_widget_height", "widget_id_to_measure",
    )

    private val THEME_KEYS = setOf(
        TEXT_COLOR, BACKGROUND_COLOR, PRIMARY_COLOR, ACCENT_COLOR, APP_ICON_COLOR,
        IS_SYSTEM_THEME_ENABLED, COLOR_PICKER_RECENT_COLORS,
    )

    private val FORMAT_KEYS = setOf(MESSAGE_TIME_FORMAT, USE_IMPERIAL_DATE)

    private val CONVERSATION_KEYS = setOf(PINNED_CONVERSATIONS, CUSTOM_NOTIFICATIONS)

    private val FONT_PREFIXES = listOf(FONT_FAMILY_PREFIX, FONT_WEIGHT_PREFIX, FONT_SIZE_PREFIX)

    /** Which category a prefs key belongs to; null = excluded from export altogether. */
    private fun categoryOf(key: String): Category? = when {
        key in EXCLUDED_KEYS -> null
        key.endsWith("tree_uri_2") -> null
        FONT_PREFIXES.any { key.startsWith(it) } -> Category.FONTS
        key in THEME_KEYS || key.startsWith("theme_") -> Category.THEME
        key in FORMAT_KEYS -> Category.FORMATS
        key in CONVERSATION_KEYS || key.startsWith(USE_SIM_ID_PREFIX) -> Category.CONVERSATIONS
        key == BLOCKED_KEYWORDS -> Category.BLOCKED
        else -> Category.APP_SETTINGS
    }

    fun getDirUri(context: Context): Uri? = context
        .getSharedPreferences(EXIMPORT_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_DIR_URI, null)
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun setDirUri(context: Context, uri: Uri) = context
        .getSharedPreferences(EXIMPORT_PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_DIR_URI, uri.toString()).apply()

    fun exportDir(context: Context): DocumentFile? = getDirUri(context)
        ?.let { runCatching { DocumentFile.fromTreeUri(context, it) }.getOrNull() }
        ?.takeIf { it.isDirectory }

    fun exportFileName(): String = EXPORT_PREFIX + "_" +
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date()) + EXPORT_SUFFIX

    /** (message, isWarning) describing the newest export in the configured directory. */
    fun lastExportStatus(context: Context): Pair<String, Boolean> {
        val dir = exportDir(context) ?: return context.getString(R.string.eim_warn_nodir) to true
        val newest = runCatching {
            dir.listFiles().filter {
                it.isFile && it.name?.startsWith(EXPORT_PREFIX) == true &&
                    it.name?.endsWith(EXPORT_SUFFIX) == true
            }.maxByOrNull { it.lastModified() }
        }.getOrNull() ?: return context.getString(R.string.eim_warn_none) to true
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .format(Date(newest.lastModified()))
        return context.getString(R.string.eim_last, timestamp) to false
    }

    /**
     * The export core, callable headlessly — no Activity, no user interaction. Writes exactly one ZIP
     * of the selected categories to [out] and reports real counts through [onProgress]: the category
     * being written ("区分 3/7 — 設定"), and inside the messages category (the only slow one — the
     * prefs slices are instant) the messages themselves ("メッセージ 1234/8942"). Unthrottled; a
     * caller that broadcasts progress throttles it.
     */
    fun export(
        context: Context,
        categories: Set<Category>,
        out: OutputStream,
        onProgress: ProgressReporter = { _, _, _, _ -> },
    ) {
        // Declaration order, not the caller's, so a ZIP's contents don't depend on how the set was built.
        val ordered = Category.entries.filter { it in categories }
        require(ordered.isNotEmpty()) { "nothing selected" }
        val unit = context.getString(R.string.state_progress_unit_category)
        val total = ordered.size.toLong()
        val slices = prefsSlices(context.getSharedPrefs())

        ZipOutputStream(out).use { zip ->
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("version", FORMAT_VERSION)
                .put("app", context.packageName)
                .put("createdTs", System.currentTimeMillis())
                .put("categories", JSONArray(ordered.map { it.id }))
            writeEntry(zip, "manifest.json", manifest.toString(2).toByteArray())

            ordered.forEachIndexed { index, category ->
                val done = (index + 1).toLong()
                val label = context.getString(category.shortLabelRes)
                onProgress(done, total, unit, "$unit $done/$total — $label")
                if (category == Category.MESSAGES) {
                    writeEntry(zip, "${category.id}.json", messagesJson(context, onProgress))
                    return@forEachIndexed
                }
                writeEntry(zip, "${category.id}.json", (slices[category] ?: JSONObject()).toString(2).toByteArray())
                if (category == Category.FONTS) {
                    exportFonts(context, zip)
                }
            }
        }
    }

    /**
     * Merge the selected categories of an exported archive into the live prefs (plus font files).
     * Returns a per-category "Label: n" summary of applied keys; categories absent from the
     * archive are skipped. An empty summary means the file contained none of the selected
     * categories, i.e. it is not one of our exports.
     */
    fun import(
        context: Context,
        bytes: ByteArray,
        categories: Set<Category>,
        onProgress: ProgressReporter = { _, _, _, _ -> },
    ): String {
        val entries = readZip(bytes.inputStream())
        val summary = StringBuilder()
        categories.forEach { category ->
            val json = entries["${category.id}.json"] ?: return@forEach
            var count = if (category == Category.MESSAGES) {
                importMessages(context, String(json), onProgress)
            } else {
                importPrefs(context.getSharedPrefs(), String(json), category)
            }
            if (category == Category.FONTS) {
                count += importFonts(context, entries)
            }
            if (summary.isNotEmpty()) {
                summary.append('\n')
            }
            summary.append(context.getString(category.labelRes)).append(": ").append(count)
        }
        return summary.toString()
    }

    /** Every exportable prefs key, sliced into its category's typed key→{t,v} JSON object. */
    private fun prefsSlices(prefs: SharedPreferences): Map<Category, JSONObject> {
        val slices = HashMap<Category, JSONObject>()
        prefs.all.forEach { (key, value) ->
            val category = categoryOf(key) ?: return@forEach
            val entry = typedEntry(value) ?: return@forEach
            slices.getOrPut(category) { JSONObject() }.put(key, entry)
        }
        return slices
    }

    private fun typedEntry(value: Any?): JSONObject? = when (value) {
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is String -> JSONObject().put("t", "s").put("v", value)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
        else -> null
    }

    // Merge a category's typed JSON into prefs. Only keys that (still) classify into the imported
    // category are applied, so a crafted archive can't smuggle keys into other categories.
    private fun importPrefs(prefs: SharedPreferences, json: String, category: Category): Int {
        val obj = JSONObject(json)
        val editor = prefs.edit()
        var count = 0
        obj.keys().forEach { key ->
            if (categoryOf(key) != category) {
                return@forEach
            }
            val entry = obj.optJSONObject(key) ?: return@forEach
            when (entry.optString("t")) {
                "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                "i" -> editor.putInt(key, entry.optInt("v"))
                "l" -> editor.putLong(key, entry.optLong("v"))
                "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                "s" -> editor.putString(key, entry.optString("v"))
                "ss" -> {
                    val array = entry.optJSONArray("v") ?: return@forEach
                    editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                }

                else -> return@forEach
            }
            count++
        }
        editor.apply()
        return count
    }

    // The messages themselves, serialized exactly like the stock backup (kotlinx JSON of
    // SmsBackup/MmsBackup with defaults), so archives stay interchangeable with stock exports.
    private fun messagesJson(context: Context, onProgress: ProgressReporter): ByteArray {
        val unit = context.getString(Category.MESSAGES.shortLabelRes)
        var messages: List<MessagesBackup> = emptyList()
        MessagesReader(context).getMessagesToExport(
            getSms = true,
            getMms = true,
            onProgress = { done, total -> onProgress(done.toLong(), total.toLong(), unit, "$unit $done/$total") },
        ) { messages = it }
        return Json { encodeDefaults = true }.encodeToString(messages).toByteArray()
    }

    private fun importMessages(
        context: Context,
        json: String,
        onProgress: ProgressReporter,
    ): Int {
        val backup = Json.decodeFromString<List<MessagesBackup>>(json)
        val messages = if (isUpsideDownCakePlus()) {
            // same workaround as the stock importer: Android 14 rejects foreign subscription ids
            backup.map { message ->
                when (message) {
                    is SmsBackup -> message.copy(subscriptionId = -1)
                    is MmsBackup -> message.copy(subscriptionId = -1)
                }
            }
        } else {
            backup
        }

        val writer = MessagesWriter(context)
        val unit = context.getString(Category.MESSAGES.shortLabelRes)
        val total = messages.size.toLong()
        var count = 0
        messages.forEachIndexed { index, message ->
            runCatching {
                when (message) {
                    is SmsBackup -> writer.writeSmsMessage(message)
                    is MmsBackup -> writer.writeMmsMessage(message)
                }
                count++
            }
            val done = (index + 1).toLong()
            onProgress(done, total, unit, "$unit $done/$total")
        }
        writer.fixConversationDates()
        refreshConversations()
        return count
    }

    private fun exportFonts(context: Context, zip: ZipOutputStream) {
        FontHelper.getFontsDir(context).listFiles()?.filter { it.isFile }?.forEach { file ->
            writeEntry(zip, FONTS_DIR_ENTRY + file.name, file.readBytes())
        }
    }

    private fun importFonts(context: Context, entries: Map<String, ByteArray>): Int {
        var count = 0
        entries.forEach { (name, bytes) ->
            if (name.startsWith(FONTS_DIR_ENTRY) && name != FONTS_DIR_ENTRY) {
                val safeName = File(name).name // basename only — no path traversal
                if (FontHelper.saveFontData(context, bytes, safeName)) {
                    count++
                }
            }
        }
        return count
    }

    // ---------------------------------------------------------------------------------------------
    // HEADLESS DESTINATION (automation)
    // ---------------------------------------------------------------------------------------------

    /** A resolved headless export destination: where to write, what to call it, and how big it ended up. */
    class Target(val displayPath: String, val open: () -> OutputStream, val size: () -> Long)

    /**
     * Resolve where a headless export writes. Directory precedence, per the automation contract:
     * [pathOverride] (an absolute directory, created if missing) → the configured export folder →
     * null, which the caller reports as "no-directory".
     */
    fun headlessTarget(context: Context, pathOverride: String): Target? {
        val name = exportFileName()
        if (pathOverride.isNotEmpty()) {
            // /sdcard is a symlink; normalize so the MediaStore path checks inside the writer match.
            val primary = Environment.getExternalStorageDirectory().absolutePath
            val file = File(pathOverride.replaceFirst(Regex("^/sdcard"), primary), name)
            file.parentFile?.mkdirs()
            return Target(
                displayPath = file.absolutePath,
                open = { openAbsolute(context, file) },
                size = { file.length() },
            )
        }

        val dir = exportDir(context) ?: return null
        val file = dir.createFile("application/zip", name) ?: error("cannot create a file in ${dir.name}")
        return Target(
            displayPath = displayPathOf(file.uri),
            open = { context.contentResolver.openOutputStream(file.uri) ?: error("cannot open ${file.uri}") },
            size = { file.length() },
        )
    }

    /**
     * Writer for an absolute path, most-capable first: MediaStore (Download/ and Documents/ take
     * non-media files from any app with no permission), then a plain file. The direct path is what
     * lets an arbitrary location work — on API 30+ that needs All-files access, so name the remedy
     * instead of letting the write fail silently.
     */
    private fun openAbsolute(context: Context, file: File): OutputStream {
        mediaStoreStream(context, file)?.let { return it }

        val primary = Environment.getExternalStorageDirectory().absolutePath
        if (isRPlus() && file.absolutePath.startsWith("$primary/") && !Environment.isExternalStorageManager()) {
            error(
                "no permission to write ${file.absolutePath} — grant “All files access” to 白い熊 メッセージ " +
                    "(UI page → Export / Import → Automation), or export under Download/ or Documents/"
            )
        }
        file.parentFile?.mkdirs()
        return FileOutputStream(file)
    }

    // Each early return is one reason MediaStore cannot take this file, answered as soon as it is known.
    @Suppress("ReturnCount")
    private fun mediaStoreStream(context: Context, file: File): OutputStream? {
        val primary = Environment.getExternalStorageDirectory().absolutePath
        val parent = file.parentFile?.absolutePath ?: return null
        if (!parent.startsWith("$primary/")) {
            return null
        }

        val relativePath = parent.removePrefix("$primary/").trimEnd('/')
        val topDir = relativePath.substringBefore('/')
        if (topDir != Environment.DIRECTORY_DOWNLOADS && topDir != Environment.DIRECTORY_DOCUMENTS) {
            return null
        }

        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // Rewrite our own earlier file of the same name instead of piling up "name (1).zip" copies.
        runCatching {
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val args = arrayOf("$relativePath/", file.name)
            context.contentResolver
                .query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                        return context.contentResolver.openOutputStream(uri, "wt")
                    }
                }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$relativePath/")
        }
        val uri = context.contentResolver.insert(collection, values) ?: return null
        return context.contentResolver.openOutputStream(uri, "wt")
    }

    /**
     * Best-effort filesystem path for a SAF document ("primary:〇/x.zip" → "/storage/emulated/0/〇/x.zip"),
     * so the automation reply names a path 白い熊 can actually open. Falls back to the URI.
     */
    private fun displayPathOf(uri: Uri): String {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return uri.toString()
        val volume = docId.substringBefore(':', "")
        val relative = docId.substringAfter(':', "")
        if (volume.isEmpty() || relative.isEmpty()) {
            return uri.toString()
        }
        val root = if (volume == "primary") {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        return "$root/$relative"
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readZip(input: InputStream): Map<String, ByteArray> {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return entries
    }
}
