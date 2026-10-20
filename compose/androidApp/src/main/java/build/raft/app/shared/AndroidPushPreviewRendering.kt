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
    val mentioned =
        if (bodyKey != null) {
            bodyKey == PushPreviewKeys.HIDDEN_MENTION || bodyKey == PushPreviewKeys.SENDER_ONLY_MENTION
        } else {
            request.optBoolean("mentioned", false) || request.optString("category") == "mention"
        }
    val publicTitle = strings.resolve(PushPreviewKeys.HIDDEN_TITLE, emptyList()) ?: defaultTitle
    val publicBody =
        strings.resolve(if (mentioned) PushPreviewKeys.HIDDEN_MENTION else PushPreviewKeys.HIDDEN_MESSAGE, emptyList())
            ?: defaultBody
    val pushMode = pushRedactionMode(bodyKey, request.optString("previewMode"))
    if (bodyKey != null && pushMode.ordinal >= devicePreviewMode.ordinal) {
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
                sender?.let {
                    strings.resolve(
                        if (mentioned) PushPreviewKeys.SENDER_ONLY_MENTION else PushPreviewKeys.SENDER_ONLY_MESSAGE,
                        listOf(it)
                    )
                } ?: publicBody // No sender known: show nothing about the message rather than its text.
            UnreadNotificationText(rawTitle, body, publicTitle, publicBody)
        }
    }
}

/**
 * How strictly the push itself is redacted. Entry order of [PushPreviewMode] is the strictness order
 * (full < sender_only < hidden). Without `bodyKey` the push carries raw content. With one, the key
 * and the `previewMode` field are both honoured and the stricter counts; an unknown key from a newer
 * server without a usable `previewMode` is treated as hidden, since its text is a redacted fallback.
 */
private fun pushRedactionMode(bodyKey: String?, previewMode: String?): PushPreviewMode {
    bodyKey ?: return PushPreviewMode.Full
    val fromKey =
        when (bodyKey) {
            PushPreviewKeys.HIDDEN_MESSAGE, PushPreviewKeys.HIDDEN_MENTION -> PushPreviewMode.Hidden
            PushPreviewKeys.SENDER_ONLY_MESSAGE, PushPreviewKeys.SENDER_ONLY_MENTION -> PushPreviewMode.SenderOnly
            else -> null
        }
    val fromField = PushPreviewMode.fromWire(previewMode?.trim())
    return listOfNotNull(fromKey, fromField).maxByOrNull { it.ordinal } ?: PushPreviewMode.Hidden
}

/** `bodyArgs` is a JSON array of strings on the FCM wire; anything else means no arguments. */
private fun parseBodyArgs(raw: String?): List<String> {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    return runCatching {
        val array = JSONArray(text)
        (0 until array.length()).map { index -> array.optString(index) }
    }.getOrDefault(emptyList())
}
