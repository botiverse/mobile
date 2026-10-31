package build.raft.app.shared

import android.content.Context
import build.raft.app.R
import build.raft.app.data.PushPreviewMode
import build.raft.app.data.raftNotificationTitleWithoutSubtitle
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

    /** Banner body keys of key set 3 (`push.body.*`): a full preview's fixed phrases. */
    const val BODY_PREFIX = "push.body."

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
            "dm", "direct_message" -> SENDER_ONLY_MESSAGE to HIDDEN_MESSAGE
            // channel / thread, the local request's channel_unread / general_unread, an older server's
            // RAFT_MESSAGE: "mentioned you" only when the message mentions me.
            else -> if (mentioned) SENDER_ONLY_MENTION to HIDDEN_MENTION else SENDER_ONLY_MESSAGE to HIDDEN_MESSAGE
        }
}

/** Resolves a [PushPreviewKeys] key to display text; null for an unknown key or a missing argument. */
internal fun interface PushPreviewStrings {
    fun resolve(key: String, args: List<String>): String?
}

/**
 * The banner copy keys of preview key set 3 (contract §1.5, slock `packages/shared/src/notificationCopy.ts`)
 * and the string resource of each, `{0}` / `{1}` = `%1$s` / `%2$s`. Every key the server can send to a
 * registration of [build.raft.app.data.RAFT_ANDROID_PUSH_PREVIEW_KEY_SET] must be here (or in
 * [PushPreviewKeys]); AndroidNotificationCopyVectorTest pins the list to the slock catalogue.
 */
internal object AndroidPushCopyKeys {
    /** The botiverse/slock commit whose notificationCopy.ts this table follows (scripts/check_notification_copy_vector.sh). */
    const val SLOCK_SHA = "fbc091209976f3d22d58f0cc65e330599c45bccf"

    /** key -> (string resource, number of positional args). */
    val resources: Map<String, Pair<Int, Int>> =
        mapOf(
            PushPreviewKeys.HIDDEN_TITLE to (R.string.push_preview_hidden_title to 0),
            PushPreviewKeys.HIDDEN_MESSAGE to (R.string.push_preview_hidden_message to 0),
            PushPreviewKeys.HIDDEN_MENTION to (R.string.push_preview_hidden_mention to 0),
            PushPreviewKeys.SENDER_ONLY_MESSAGE to (R.string.push_preview_sender_only_message to 1),
            PushPreviewKeys.SENDER_ONLY_MENTION to (R.string.push_preview_sender_only_mention to 1),
            PushPreviewKeys.SENDER_ONLY_TASK to (R.string.push_preview_sender_only_task to 1),
            PushPreviewKeys.SENDER_ONLY_AGENT to (R.string.push_preview_sender_only_agent to 1),
            PushPreviewKeys.SENDER_ONLY_REMINDER to (R.string.push_preview_sender_only_reminder to 1),
            PushPreviewKeys.SENDER_ONLY_NOTICE to (R.string.push_preview_sender_only_notice to 1),
            PushPreviewKeys.HIDDEN_TASK to (R.string.push_preview_hidden_task to 0),
            PushPreviewKeys.HIDDEN_AGENT to (R.string.push_preview_hidden_agent to 0),
            PushPreviewKeys.HIDDEN_REMINDER to (R.string.push_preview_hidden_reminder to 0),
            PushPreviewKeys.HIDDEN_NOTICE to (R.string.push_preview_hidden_notice to 0),
            // Titles (where a tap lands).
            "push.title.dm" to (R.string.push_title_dm to 0),
            "push.title.thread" to (R.string.push_title_thread to 0),
            "push.title.thread_in_dm" to (R.string.push_title_thread_in_dm to 0),
            "push.title.thread_in_channel" to (R.string.push_title_thread_in_channel to 1),
            TITLE_TASK to (R.string.push_title_task to 0),
            "push.title.task_in_dm" to (R.string.push_title_task_in_dm to 0),
            "push.title.task_in_channel" to (R.string.push_title_task_in_channel to 1),
            "push.title.task_assigned" to (R.string.push_title_task_assigned to 0),
            "push.title.agent_action_required" to (R.string.push_title_agent_action_required to 1),
            "push.title.agent_tool_stuck" to (R.string.push_title_agent_tool_stuck to 1),
            "push.title.reminder" to (R.string.push_title_reminder to 0),
            "push.title.agent_migration_review" to (R.string.push_title_agent_migration_review to 1),
            "push.title.test" to (R.string.push_title_test to 0),
            // Subtitles.
            "push.subtitle.dm" to (R.string.push_subtitle_dm to 0),
            "push.subtitle.dm_server" to (R.string.push_subtitle_dm_server to 1),
            // Bodies.
            "push.body.attachment" to (R.string.push_body_attachment to 0),
            "push.body.attachments" to (R.string.push_body_attachments to 1),
            "push.body.no_text" to (R.string.push_body_no_text to 0),
            "push.body.sender_attachment" to (R.string.push_body_sender_attachment to 1),
            "push.body.sender_attachments" to (R.string.push_body_sender_attachments to 2),
            "push.body.sender_no_text" to (R.string.push_body_sender_no_text to 1),
            "push.body.agent_migration_review" to (R.string.push_body_agent_migration_review to 1),
            "push.body.test" to (R.string.push_body_test to 0)
        )

    /** The generic task title: sender_only stands it in for a task title (content) this device redacts. */
    const val TITLE_TASK = "push.title.task"

    /** Bodies `{0}: ...` whose first arg is the sender. */
    val SENDER_BODY_KEYS = setOf("push.body.sender_attachment", "push.body.sender_attachments", "push.body.sender_no_text")
}

internal class AndroidResourcePushPreviewStrings(context: Context) : PushPreviewStrings {
    private val appContext = context.applicationContext

    override fun resolve(key: String, args: List<String>): String? {
        // A key this build does not know: the caller shows the payload's English fallback.
        val (resource, argCount) = AndroidPushCopyKeys.resources[key] ?: return null
        if (argCount == 0) return appContext.getString(resource)
        val used = args.take(argCount).map { it.trim() }
        // A missing or empty name: the English text the server rendered reads better than a gap.
        if (used.size < argCount || used.any { it.isEmpty() }) return null
        return appContext.getString(resource, *used.toTypedArray())
    }
}

/**
 * What a tray notification shows: [title] (the conversation, where a tap lands), [subtitle] (the
 * context line, `setSubText`; null when there is none) and [body] for the expanded and collapsed
 * views, [sender] (the MessagingStyle person; null when unknown or redacted), and a content-free
 * [publicTitle] / [publicBody] for the lock screen and the hidden preview.
 */
internal data class UnreadNotificationText(
    val title: String,
    val body: String,
    val publicTitle: String,
    val publicBody: String,
    val subtitle: String? = null,
    val sender: String? = null
)

/**
 * The text of one unread notification.
 *
 * Banner copy (contract §1.5): the title is where a tap lands, the subtitle the context (server
 * label for a receiver of 2+ servers), the body `Sender: text` (a DM: the text). Each part with a
 * key (`titleKey` / `subtitleKey` / `bodyKey` and their JSON-array args; the socket's
 * `localization`, flattened the same way) is shown in the device language, falling back to the
 * payload's English for a key this build does not know. A title still joined with its subtitle
 * (`title · subtitle`: the socket, an older server) is split.
 *
 * The stricter of [devicePreviewMode] and the push's own redaction wins (full < sender_only <
 * hidden). The server's mode can lag the device: a re-registration in flight, an offline failure, a
 * pending retry, or the no-GMS realtime path that never goes through the server's redaction.
 * When the device is stricter it redacts locally the same way the server would: `hidden` replaces
 * title and body and drops the subtitle, `sender_only` keeps the title (a task title, which is
 * content, becomes "Task") and shows only the sender.
 */
internal fun resolveUnreadNotificationText(
    request: JSONObject,
    devicePreviewMode: PushPreviewMode,
    strings: PushPreviewStrings,
    defaultTitle: String,
    defaultBody: String
): UnreadNotificationText {
    val rawSubtitle = request.optString("subtitle").takeIf { it.isNotBlank() }
    val rawTitle =
        request.optString("title").takeIf { it.isNotBlank() }
            ?.let { raftNotificationTitleWithoutSubtitle(it, rawSubtitle) }
            ?: defaultTitle
    val rawBody = request.optString("body").takeIf { it.isNotBlank() } ?: defaultBody
    val titleKey = request.optString("titleKey").takeIf { it.isNotBlank() }
    val subtitleKey = request.optString("subtitleKey").takeIf { it.isNotBlank() }
    val bodyKey = request.optString("bodyKey").takeIf { it.isNotBlank() }
    val bodyArgs = parseArgs(request.optString("bodyArgs"))
    val category = request.optString("category").trim().takeIf { it.isNotEmpty() }
    val mentioned =
        if (bodyKey != null && PushPreviewKeys.modeOf(bodyKey) != null) {
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
    val title = titleKey?.let { strings.resolve(it, parseArgs(request.optString("titleArgs"))) } ?: rawTitle
    val subtitle = subtitleKey?.let { strings.resolve(it, parseArgs(request.optString("subtitleArgs"))) } ?: rawSubtitle
    val body = bodyKey?.let { strings.resolve(it, bodyArgs) } ?: rawBody
    val pushMode = pushRedactionMode(bodyKey, request.optString("previewMode"))
    if (pushMode.strictness >= devicePreviewMode.strictness) {
        return when (pushMode) {
            PushPreviewMode.Full ->
                UnreadNotificationText(
                    title = title,
                    body = body,
                    publicTitle = publicTitle,
                    publicBody = publicBody,
                    subtitle = subtitle,
                    sender = unreadNotificationSender(request, category, rawTitle, rawBody, bodyKey, bodyArgs)
                )
            // Already redacted by the server: its title and subtitle stand (hidden sends none).
            PushPreviewMode.SenderOnly -> UnreadNotificationText(title, body, publicTitle, publicBody, subtitle = subtitle)
            PushPreviewMode.Hidden -> UnreadNotificationText(title, body, publicTitle, publicBody)
        }
    }
    // The device is stricter than what arrived (raw content, or a sender_only push on a hidden device).
    return when (devicePreviewMode) {
        PushPreviewMode.Full -> UnreadNotificationText(title, body, publicTitle, publicBody, subtitle = subtitle)
        PushPreviewMode.Hidden -> UnreadNotificationText(publicTitle, publicBody, publicTitle, publicBody)
        PushPreviewMode.SenderOnly -> {
            // Only reached for raw content (a sender_only or hidden push is at least as strict).
            val sender = request.optString("senderName").trim().takeIf { it.isNotEmpty() }
            val redactedBody =
                sender?.let { strings.resolve(senderOnlyKey, listOf(it)) }
                    ?: publicBody // No sender known: show nothing about the message rather than its text.
            // A task title (`#12 Fix login`) is content; a keyed title is a fixed phrase and stays.
            val redactedTitle =
                if (titleKey == null && isTaskSurface(request, category)) {
                    strings.resolve(AndroidPushCopyKeys.TITLE_TASK, emptyList()) ?: "Task"
                } else {
                    title
                }
            UnreadNotificationText(redactedTitle, redactedBody, publicTitle, publicBody, subtitle = subtitle)
        }
    }
}

/**
 * Who sent the message of a full-content notification, for its MessagingStyle line: the request's
 * `senderName` (the socket), the sender arg of a keyed `{0}: ...` body, a DM's title (a DM is titled
 * by its sender), else the `Sender` of the server's `Sender: text` body (FCM carries no sender
 * field). Null for the notices whose body is not a message (task, agent, reminder, account, test).
 */
internal fun unreadNotificationSender(
    request: JSONObject,
    category: String?,
    rawTitle: String,
    rawBody: String,
    bodyKey: String?,
    bodyArgs: List<String>
): String? {
    if (category in NON_MESSAGE_CATEGORIES) return null
    request.optString("senderName").trim().takeIf { it.isNotEmpty() }?.let { return it }
    if (bodyKey in AndroidPushCopyKeys.SENDER_BODY_KEYS) return bodyArgs.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    if (isDirectMessage(request, category)) return rawTitle.trim().takeIf { it.isNotEmpty() }
    if (bodyKey != null) return null
    val separator = rawBody.indexOf(": ")
    return if (separator > 0) rawBody.substring(0, separator).trim().takeIf { it.isNotEmpty() } else null
}

private val NON_MESSAGE_CATEGORIES =
    setOf("task_status", "task_assigned", "agent_attention", "reminder", "system_notice", "test")

internal fun isDirectMessage(request: JSONObject, category: String?): Boolean =
    category == "dm" || category == "direct_message" || request.optString("kind").trim() == "dm"

/** A notification titled by a task (`#12 Fix login`): a task notice, or a message of a task conversation. */
private fun isTaskSurface(request: JSONObject, category: String?): Boolean =
    category == "task_status" ||
        category == "task_assigned" ||
        request.optString("taskId").isNotBlank() ||
        runCatching { JSONObject(request.optString("route")).optString("type") == "task" }.getOrDefault(false)

/**
 * How strictly the push itself is redacted, by [PushPreviewMode.strictness]
 * (full < sender_only < hidden). Without `bodyKey` the push carries raw content. With one, the key
 * and the `previewMode` field are both honoured and the stricter counts; an unknown key from a newer
 * server without a usable `previewMode` is treated as hidden, since its text is a redacted fallback.
 */
private fun pushRedactionMode(bodyKey: String?, previewMode: String?): PushPreviewMode {
    bodyKey ?: return PushPreviewMode.Full
    val fromField = PushPreviewMode.fromWire(previewMode?.trim())
    // A banner body key of a full preview (`push.body.*`, key set 3): the server sends `previewMode`
    // only for a redacted one.
    if (bodyKey.startsWith(PushPreviewKeys.BODY_PREFIX)) return fromField ?: PushPreviewMode.Full
    val fromKey = PushPreviewKeys.modeOf(bodyKey)
    return when {
        fromKey != null && fromField != null -> PushPreviewMode.stricterOf(fromKey, fromField)
        else -> fromKey ?: fromField ?: PushPreviewMode.Hidden
    }
}

/** `titleArgs` / `subtitleArgs` / `bodyArgs` are JSON arrays of strings on the FCM wire; anything else means no arguments. */
private fun parseArgs(raw: String?): List<String> {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
    return runCatching {
        val array = JSONArray(text)
        (0 until array.length()).map { index -> array.optString(index) }
    }.getOrDefault(emptyList())
}
