package org.fossify.messages.helpers

import org.fossify.messages.models.Events
import org.greenrobot.eventbus.EventBus
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import kotlin.math.abs
import kotlin.random.Random

const val THREAD_ID = "thread_id"
const val THREAD_TITLE = "thread_title"
const val THREAD_TEXT = "thread_text"
const val THREAD_NUMBER = "thread_number"
const val THREAD_ATTACHMENT_URI = "thread_attachment_uri"
const val THREAD_ATTACHMENT_URIS = "thread_attachment_uris"
const val SEARCHED_MESSAGE_ID = "searched_message_id"
const val USE_SIM_ID_PREFIX = "use_sim_id_"
const val NOTIFICATION_CHANNEL_ID = "fossify_messages"
const val SHOW_CHARACTER_COUNTER = "show_character_counter"
const val USE_SIMPLE_CHARACTERS = "use_simple_characters"
const val SEND_ON_ENTER = "send_on_enter"
const val LOCK_SCREEN_VISIBILITY = "lock_screen_visibility"
const val ENABLE_DELIVERY_REPORTS = "enable_delivery_reports"
const val SEND_LONG_MESSAGE_MMS = "send_long_message_mms"
const val SEND_GROUP_MESSAGE_MMS = "send_group_message_mms"
const val MMS_FILE_SIZE_LIMIT = "mms_file_size_limit"
const val PINNED_CONVERSATIONS = "pinned_conversations"
const val BLOCKED_KEYWORDS = "blocked_keywords"
const val LAST_BLOCKED_KEYWORD_EXPORT_PATH = "last_blocked_keyword_export_path"
const val EXPORT_SMS = "export_sms"
const val EXPORT_MMS = "export_mms"
const val JSON_FILE_EXTENSION = ".json"
const val JSON_MIME_TYPE = "application/json"
const val XML_MIME_TYPE = "text/xml"
const val TXT_MIME_TYPE = "text/plain"
const val IMPORT_SMS = "import_sms"
const val IMPORT_MMS = "import_mms"
const val WAS_DB_CLEARED = "was_db_cleared_4"
const val EXTRA_VCARD_URI = "vcard"
const val SCHEDULED_MESSAGE_ID = "scheduled_message_id"
const val SOFT_KEYBOARD_HEIGHT = "soft_keyboard_height"
const val IS_MMS = "is_mms"
const val MESSAGE_ID = "message_id"
const val USE_RECYCLE_BIN = "use_recycle_bin"
const val LAST_RECYCLE_BIN_CHECK = "last_recycle_bin_check"
const val IS_RECYCLE_BIN = "is_recycle_bin"
const val IS_ARCHIVE_AVAILABLE = "is_archive_available"
const val CUSTOM_NOTIFICATIONS = "custom_notifications"
const val IS_LAUNCHED_FROM_SHORTCUT = "is_launched_from_shortcut"
const val KEEP_CONVERSATIONS_ARCHIVED = "keep_conversations_archived"
const val THEME_V1_SEEDED = "theme_v1_seeded"
const val PURE_YELLOW_MIGRATED = "pure_yellow_migrated"

// Date & time display formats (白い熊 メッセージ UI page)
const val MESSAGE_TIME_FORMAT = "message_time_format" // Int, ordinal into MessageTimeFormat; 0 = Japanese kanji
const val USE_IMPERIAL_DATE = "use_imperial_date"     // Boolean, true = 和暦 (令和…（土曜日）) for earlier dates

// Export/Import: warning red for "no directory / no export yet" states
const val EXIM_WARN_COLOR = 0xFFFF5252.toInt()

// The 保存復元 state-export contract, for 白い熊 自由作業盤's one-run backup of every sister app
// (see receivers/StateExportReceiver). Three exported, token-gated actions: a headless run of the very
// same category ZIP export the Export/Import page writes, the category list the caller builds its
// picker from, and the cancel that stops a running export. The actions are spelled out rather than
// built from ${applicationId} so they stay the contract's literals in every build variant, matching
// the manifest's intent filters.
const val ACTION_EXPORT_STATE = "shiroikuma.messeji.action.EXPORT_STATE"
const val ACTION_LIST_CATEGORIES = "shiroikuma.messeji.action.LIST_CATEGORIES"
const val ACTION_CANCEL_EXPORT = "shiroikuma.messeji.action.CANCEL_EXPORT"

// Request extras (all String): the gate, the optional directory override and category subset, the
// optional progress channel, and where the one terminal reply goes.
const val EXTRA_AUTOMATION_TOKEN = "token"
const val EXTRA_BACKUP_PATH = "path"
const val EXTRA_EXPORT_ITEMS = "items"
const val EXTRA_PROGRESS_ACTION = "progress_action"
const val EXTRA_REPLY_ACTION = "reply_action"
const val EXTRA_REPLY_PACKAGE = "reply_package"
const val EXTRA_REPLY_ID = "reply_id"
const val EXTRA_REPLY_RESULT = "result"

// The data door's correlation id (AutomationProvider). Sent ALONGSIDE "reply_id" — never instead of
// it — on that door's progress and reply broadcasts, so one reader serves both doors.
const val EXTRA_JOB_ID = "job_id"

// Progress-broadcast extras: real counts, never a percentage.
const val EXTRA_PROGRESS_APP = "app"
const val EXTRA_PROGRESS_TEXT = "text"
const val EXTRA_PROGRESS_CURRENT = "current"
const val EXTRA_PROGRESS_TOTAL = "total"
const val EXTRA_PROGRESS_UNIT = "unit"
const val PROGRESS_THROTTLE_MS = 500L

// The automation gate (contract v2): a master switch that ships ON, an opt-in token requirement that
// ships OFF, and the secret itself. All three are kept out of the export (EXCLUDED_KEYS in
// SettingsEximport) — the token never travels, and a restored archive never silently re-gates this app.
//
// The token became opt-in because a pasted secret cannot survive a wipe, and the case this surface now
// serves is 白い熊 応用管理 restoring apps AND their data onto a clean phone, where nothing has been
// configured and nobody has pasted anything. A gate that only works once the phone is already set up
// is no gate for setting the phone up.
const val AUTOMATION_ENABLED = "automation_enabled"
const val AUTOMATION_REQUIRE_TOKEN = "automation_require_token"
const val AUTOMATION_TOKEN = "automation_token"

// The two refusals, spelled once. They stay distinct because they debug differently: one is a switch
// 白い熊 turned off, the other a caller carrying the wrong secret.
const val AUTOMATION_REFUSAL_DISABLED = "ERROR:automation disabled"
const val AUTOMATION_REFUSAL_BAD_TOKEN = "ERROR:bad token"

// Granular theming
const val THEME_UNSET = Int.MIN_VALUE // a slot with this stored value follows its inherited default
const val PALETTE_BLACK = 0xFF000000.toInt()
const val PALETTE_YELLOW = 0xFFFFFF00.toInt()
const val PALETTE_YELLOW_LEGACY = 0xFFFFEB3B.toInt() // old material yellow, migrated once to PALETTE_YELLOW

// Per-element fonts: one entry per text slot, keyed by the slot key.
const val FONT_FAMILY_PREFIX = "font_family_" // String, "" = system/global default
const val FONT_WEIGHT_PREFIX = "font_weight_" // Int, 0 = default, else 100..900
const val FONT_SIZE_PREFIX = "font_size_"     // Int sp, 0 = default
const val MAX_FONT_SIZE_SP = 40

private const val PATH = "org.fossify.org.fossify.messages.action."
const val MARK_AS_READ = PATH + "mark_as_read"
const val REPLY = PATH + "reply"

// view types for the thread list view
const val THREAD_DATE_TIME = 1
const val THREAD_RECEIVED_MESSAGE = 2
const val THREAD_SENT_MESSAGE = 3
const val THREAD_SENT_MESSAGE_ERROR = 4
const val THREAD_SENT_MESSAGE_SENT = 5
const val THREAD_SENT_MESSAGE_SENDING = 6
const val THREAD_TYPE_BITS = 3
const val THREAD_KEY_BITS = Long.SIZE_BITS - THREAD_TYPE_BITS
const val THREAD_TYPE_SHIFT = THREAD_KEY_BITS
const val THREAD_KEY_MASK = (1L shl THREAD_KEY_BITS) - 1

// view types for attachment list
const val ATTACHMENT_DOCUMENT = 7
const val ATTACHMENT_MEDIA = 8
const val ATTACHMENT_VCARD = 9

// lock screen visibility constants
const val LOCK_SCREEN_SENDER_MESSAGE = 1
const val LOCK_SCREEN_SENDER = 2
const val LOCK_SCREEN_NOTHING = 3

const val FILE_SIZE_NONE = -1L
const val FILE_SIZE_100_KB = 102_400L
const val FILE_SIZE_200_KB = 204_800L
const val FILE_SIZE_300_KB = 307_200L
const val FILE_SIZE_600_KB = 614_400L
const val FILE_SIZE_1_MB = 1_048_576L
const val FILE_SIZE_2_MB = 2_097_152L

const val MESSAGES_LIMIT = 50
const val MAX_MESSAGE_LENGTH = 5000

// intent launch request codes
const val PICK_PHOTO_INTENT = 42
const val PICK_VIDEO_INTENT = 49
const val PICK_SAVE_FILE_INTENT = 43
const val CAPTURE_PHOTO_INTENT = 44
const val CAPTURE_VIDEO_INTENT = 45
const val CAPTURE_AUDIO_INTENT = 46
const val PICK_DOCUMENT_INTENT = 47
const val PICK_CONTACT_INTENT = 48
const val PICK_SAVE_DIR_INTENT = 50

const val BLOCKED_KEYWORDS_EXPORT_DELIMITER = ","
const val BLOCKED_KEYWORDS_EXPORT_EXTENSION = ".txt"

fun refreshMessages() {
    EventBus.getDefault().post(Events.RefreshMessages())
}

fun refreshConversations() {
    EventBus.getDefault().post(Events.RefreshConversations())
}

/** Not to be used with real messages persisted in the telephony db. This is for internal use only (e.g. scheduled messages, notification ids etc). */
fun generateRandomId(length: Int = 9): Long {
    val millis = DateTime.now(DateTimeZone.UTC).millis
    val random = abs(Random(millis).nextLong())
    return random.toString().takeLast(length).toLong()
}

fun generateStableId(type: Int, key: Long): Long {
    require(type in 0 until (1 shl THREAD_TYPE_BITS))
    return (type.toLong() shl THREAD_TYPE_SHIFT) or (key and THREAD_KEY_MASK)
}
