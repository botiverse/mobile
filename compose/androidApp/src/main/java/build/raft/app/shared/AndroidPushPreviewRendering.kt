package build.raft.app.shared

import android.content.Context
import build.raft.app.R
import build.raft.app.data.PushPreviewMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Localization keys of a redacted push. The server sends them (FCM data `titleKey` / `bodyKey` /
 * `bodyArgs`, APNs `title-loc-key` / `loc-key` / `loc-args`) for a registration whose previewMode is
 * not `full`; `title` / `body` then hold a content-free English fallback. The same keys drive the
 * local redaction of a request built on this device (the no-GMS realtime path).
 */
internal object PushPreviewKeys {
    const val HIDDEN_TITLE = "push.preview.hidden.title"
    const val HIDDEN_MESSAGE = "push.preview.hidden.message"
    const val HIDDEN_MENTION = "push.preview.hidden.mention"
    const val SENDER_ONLY_MESSAGE = "push.preview.sender_only.message"
    const val SENDER_ONLY_MENTION = "push.preview.sender_only.mention"

    // Per-category wording of the other personal categories (slock docs/contracts/notifications.md
    // §1.5): sender_only takes `[sender]`, hidden takes no argument.
    const val SENDER_ONLY_TASK = "push.preview.sender_only.task"
    const val SENDER_ONLY_AGENT = "push.preview.sender_only.agent"
    const val SENDER_ONLY_REMINDER = "push.preview.sender_only.reminder"
    const val SENDER_ONLY_NOTICE = "push.preview.sender_only.notice"
    const val HIDDEN_TASK = "push.preview.hidden.task"
    const val HIDDEN_AGENT = "push.preview.hidden.agent"
    const val HIDDEN_REMINDER = "push.preview.hidden.reminder"
    const val HIDDEN_NOTICE = "push.preview.hidden.notice"

    private const val HIDDEN_PREFIX = "push.preview.hidden."
    private const val SENDER_ONLY_PREFIX = "push.preview.sender_only."

    /** How strict a key is, by its family; null for a key of neither family. */
    fun modeOf(key: String): PushPreviewMode? =
        when {
            key.startsWith(HIDDEN_PREFIX) -> PushPreviewMode.Hidden
            key.startsWith(SENDER_ONLY_PREFIX) -> PushPreviewMode.SenderOnly
            else -> null
        }

    /**
     * The (sender_only, hidden) body keys for a contract category, for a request this device redacts
     * itself: the same table the server uses. Only `mention` reads "mentioned you"; a DM never does.
     */
    fun bodyKeysFor(category: String?, mentioned: Boolean): Pair<String, String> =
        when (category) {
            "mention" -> SENDER_ONLY_MENTION to HIDDEN_MENTION
            "task_status", "task_assigned" -> SENDER_ONLY_TASK to HIDDEN_TASK
            "agent_attention" -> SENDER_ONLY_AGENT to HIDDEN_AGENT
            "reminder" -> SENDER_ONLY_REMINDER to HIDDEN_REMINDER
            "system_notice", "test" -> SENDER_ONLY_NOTICE to HIDDEN_NOTICE
            "dm", "channel", "thread", "direct_message", "channel_unread", "general_unread" -> SENDER_ONLY_MESSAGE to HIDDEN_MESSAGE
            else -> if (mentioned) SENDER_ONLY_MENTION to HIDDEN_MENTION else SENDER_ONLY_MESSAGE to HIDDEN_MESSAGE
        }
}

/** Resolves a [PushPreviewKeys] key to display text; null for an unknown key or a missing argument. */
internal fun interface PushPreviewStrings {
    fun resolve(key: String, args: List<String>): String?
}

internal class AndroidResourcePushPreviewStrings(context: Context) : PushPreviewStrings {
    private val appContext = context.applicationContext

    override fun resolve(key: String, args: List<String>): String? {
        val sender = args.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        return when (key) {
            PushPreviewKeys.HIDDEN_TITLE -> appContext.getString(R.string.push_preview_hidden_title)
            PushPreviewKeys.HIDDEN_MESSAGE -> appContext.getString(R.string.push_preview_hidden_message)
            PushPreviewKeys.HIDDEN_MENTION -> appContext.getString(R.string.push_preview_hidden_mention)
            PushPreviewKeys.SENDER_ONLY_MESSAGE ->
                sender?.let { appContext.getString(R.string.push_preview_sender_only_message, it) }
            PushPreviewKeys.SENDER_ONLY_MENTION ->
                sender?.let { appContext.getString(R.string.push_preview_sender_only_mention, it) }
            PushPreviewKeys.SENDER_ONLY_TASK -> sender?.let { appContext.getString(R.string.push_preview_sender_only_task, it) }
            PushPreviewKeys.SENDER_ONLY_AGENT -> sender?.let { appContext.getString(R.string.push_preview_sender_only_agent, it) }
            PushPreviewKeys.SENDER_ONLY_REMINDER ->
                sender?.let { appContext.getString(R.string.push_preview_sender_only_reminder, it) }
            PushPreviewKeys.SENDER_ONLY_NOTICE -> sender?.let { appContext.getString(R.string.push_preview_sender_only_notice, it) }
            PushPreviewKeys.HIDDEN_TASK -> appContext.getString(R.string.push_preview_hidden_task)
            PushPreviewKeys.HIDDEN_AGENT -> appContext.getString(R.string.push_preview_hidden_agent)
            PushPreviewKeys.HIDDEN_REMINDER -> appContext.getString(R.string.push_preview_hidden_reminder)
            PushPreviewKeys.HIDDEN_NOTICE -> appContext.getString(R.string.push_preview_hidden_notice)
            // A key this build does not know: the caller shows the payload's English fallback.
            else -> null
        }
    }
}

/**
 * What a tray notification shows: [title] / [body] for the expanded and collapsed views, and a
 * content-free [publicTitle] / [publicBody] for the lock screen (`setPublicVersion`).
 */
internal data class UnreadNotificationText(
    val title: String,
    val body: String,
    val publicTitle: String,
    val publicBody: String
)

/**
 * The text of one unread notification.
 *
 * The stricter of [devicePreviewMode] and the push's own redaction wins (full < sender_only <
 * hidden). The server's mode can lag the device: a re-registration in flight, an offline failure, a
 * pending retry, or the no-GMS realtime path that never goes through the server's redaction.
 *
 * - When the push is at least as strict as the device (it carries `bodyKey`), it is rendered from
 *   its keys in the device language, falling back to its `title` / `body` for an unknown key.
 * - When the device is stricter, the device choice redacts locally the same way the server would:
 *   `hidden` replaces title and body, `sender_only` keeps the title and shows only the sender.
 */
internal fun resolveUnreadNotificationText(
    request: JSONObject,
    devicePreviewMode: PushPreviewMode,
    strings: PushPreviewStrings,
    defaultTitle: String,
    defaultBody: String
): UnreadNotificationText {
    val rawTitle = request.optString("title").takeIf { it.isNotBlank() } ?: defaultTitle
    val rawBody = request.optString("body").takeIf { it.isNotBlank() } ?: defaultBody
    val bodyKey = request.optString("bodyKey").takeIf { it.isNotBlank() }
    val category = request.optString("category").trim().takeIf { it.isNotEmpty() }
    val mentioned =
        if (bodyKey != null) {
            bodyKey == PushPreviewKeys.HIDDEN_MENTION || bodyKey == PushPreviewKeys.SENDER_ONLY_MENTION
        } else {
            // A DM is never "mentioned you" (contract §1.5).
            category != "dm" && category != "direct_message" &&
                (request.optBoolean("mentioned", false) || category == "mention")
        }
    // The wording family of this notification: the server's own key when it sent one, else the
    // category's (a request this device builds or redacts itself).
    val categoryKeys = PushPreviewKeys.bodyKeysFor(category, mentioned)
    val keyMode = bodyKey?.let(PushPreviewKeys::modeOf)
    val senderOnlyKey = bodyKey?.takeIf { keyMode == PushPreviewMode.SenderOnly } ?: categoryKeys.first
    val hiddenKey = bodyKey?.takeIf { keyMode == PushPreviewMode.Hidden } ?: categoryKeys.second
    val publicTitle = strings.resolve(PushPreviewKeys.HIDDEN_TITLE, emptyList()) ?: defaultTitle
    val publicBody =
        strings.resolve(hiddenKey, emptyList())
            ?: strings.resolve(if (mentioned) PushPreviewKeys.HIDDEN_MENTION else PushPreviewKeys.HIDDEN_MESSAGE, emptyList())
            ?: defaultBody
    val pushMode = pushRedactionMode(bodyKey, request.optString("previewMode"))
    if (bodyKey != null && pushMode.strictness >= devicePreviewMode.strictness) {
        val title =
            request.optString("titleKey").takeIf { it.isNotBlank() }
                ?.let { titleKey -> strings.resolve(titleKey, emptyList()) }
                ?: rawTitle
        val body = strings.resolve(bodyKey, parseBodyArgs(request.optString("bodyArgs"))) ?: rawBody
        return UnreadNotificationText(title, body, publicTitle, publicBody)
    }
    // The device is stricter than what arrived (raw content, or a sender_only push on a hidden device).
    return when (devicePreviewMode) {
        PushPreviewMode.Full -> UnreadNotificationText(rawTitle, rawBody, publicTitle, publicBody)
        PushPreviewMode.Hidden -> UnreadNotificationText(publicTitle, publicBody, publicTitle, publicBody)
        PushPreviewMode.SenderOnly -> {
            // Only reached for raw content (a sender_only or hidden push is at least as strict).
            val sender = request.optString("senderName").trim().takeIf { it.isNotEmpty() }
            val body =
                sender?.let { strings.resolve(senderOnlyKey, listOf(it)) }
                    ?: publicBody // No sender known: show nothing about the message rather than its text.
            UnreadNotificationText(rawTitle, body, publicTitle, publicBody)
        }
    }
}

/**
 * How strictly the push itself is redacted, by [PushPreviewMode.strictness]
 * (full < sender_only < hidden). Without `bodyKey` the push carries raw content. With one, the key
 * and the `previewMode` field are both honoured and the stricter counts; an unknown key from a newer
 * server without a usable `previewMode` is treated as hidden, since its text is a redacted fallback.
 */
private fun pushRedactionMode(bodyKey: String?, previewMode: String?): PushPreviewMode {
    bodyKey ?: return PushPreviewMode.Full
    val fromKey = PushPreviewKeys.modeOf(bodyKey)
    val fromField = PushPreviewMode.fromWire(previewMode?.trim())
    return when {
        fromKey != null && fromField != null -> PushPreviewMode.stricterOf(fromKey, fromField)
        else -> fromKey ?: fromField ?: PushPreviewMode.Hidden
    }
}

/** `bodyArgs` is a JSON array of strings on the FCM wire; anything else means no arguments. */
private fun parseBodyArgs(raw: String?): List<String> {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    return runCatching {
        val array = JSONArray(text)
        (0 until array.length()).map { index -> array.optString(index) }
    }.getOrDefault(emptyList())
}
