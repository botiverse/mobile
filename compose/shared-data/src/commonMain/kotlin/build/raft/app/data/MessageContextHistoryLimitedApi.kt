package build.raft.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * `GET /api/messages/context/:messageId?channelId=` refusing a message behind the plan's history
 * window. Contract read from botiverse/slock@d1cdd247a (staging):
 * - packages/server/src/routes/messages.ts `messageRouter.get("/context/:messageId")`: a target this
 *   person may read but that sits behind the cutoff answers 404 (older clients read it as not found)
 *   with `{ error, code: "history_limited", channelId, targetMessageId, canonicalTarget?, hiddenTarget }`;
 *   any other miss, including a target the person may not read, is a plain 404 `{ error: "Message not found" }`.
 * - packages/server/src/services/historyWindow.ts `humanHistoryLimitedError`: `{ error, code: "history_limited" }`.
 * - packages/server/src/services/messageService.ts `HiddenTargetPreview` (`target`, `before`, `after`,
 *   `thread`, `replies`) and `HiddenHistoryPreviewMessage` (`id`, `senderType`, `senderId`,
 *   `senderName`, `senderAvatarUrl`, `createdAt`, `preview`; the target adds `seq` and its `preview`
 *   is its first HIDDEN_TARGET_PREVIEW_CHARS = 80 characters on one line, null when it has no text).
 * - Web reader: packages/web/src/store/hiddenHistoryStore.ts `readHistoryLimitedError` /
 *   `readHiddenTarget`; packages/web/src/features/thread/useThreadParent.ts names a thread's parent
 *   from `hiddenTarget.target` when its id is the parent's.
 * Only what the app reads is modelled; the rest of the body is ignored.
 */

/** The context route's `code` for a target behind the plan's history window. */
const val MessageContextHistoryLimitedCode: String = "history_limited"

@Serializable
private data class MessageContextHistoryLimitedWire(
    val code: String? = null,
    val hiddenTarget: MessageContextHiddenTargetWire? = null,
)

@Serializable
private data class MessageContextHiddenTargetWire(
    val target: MessageContextHiddenMessageWire? = null,
)

@Serializable
private data class MessageContextHiddenMessageWire(
    val id: String = "",
    val senderType: String = "",
    val senderId: String = "",
    val senderName: String? = null,
    val senderAvatarUrl: String? = null,
    val preview: String? = null,
    val seq: Long? = null,
    val createdAt: String? = null,
)

/**
 * The message a `history_limited` answer points at, as far as the server shows it: who sent it
 * ([senderName], null when the server does not name them; [senderAvatarUrl], optional avatar wire
 * URL), its first words ([preview], at most 80 characters on one line; null for a message with no
 * text) and when it was sent ([createdAt], the server's ISO time; null when the body has none).
 */
@Serializable
data class MessageContextHiddenTarget(
    val messageId: String,
    val senderType: String,
    val senderId: String,
    val senderName: String?,
    val preview: String?,
    val createdAt: String? = null,
    val senderAvatarUrl: String? = null,
)

private val messageContextHistoryLimitedJson = Json { ignoreUnknownKeys = true }

/**
 * The hidden target of a `messages/context` 404 body with `code: "history_limited"`; null for any
 * other body (a plain not found, another code, an unreadable `hiddenTarget`). Like the web's
 * `readHiddenTarget`, a target needs its `id`, `senderId` and `seq`.
 */
fun parseMessageContextHiddenTarget(bodyText: String?): MessageContextHiddenTarget? {
    val text = bodyText?.takeIf(String::isNotBlank) ?: return null
    val wire =
        runCatching { messageContextHistoryLimitedJson.decodeFromString(MessageContextHistoryLimitedWire.serializer(), text) }
            .getOrNull() ?: return null
    if (wire.code != MessageContextHistoryLimitedCode) return null
    val target = wire.hiddenTarget?.target ?: return null
    if (target.id.isBlank() || target.senderId.isBlank() || target.seq == null) return null
    return MessageContextHiddenTarget(
        messageId = target.id,
        senderType = target.senderType,
        senderId = target.senderId,
        senderName = target.senderName?.trim()?.takeIf(String::isNotBlank),
        preview = target.preview?.takeIf(String::isNotBlank),
        createdAt = target.createdAt?.trim()?.takeIf(String::isNotBlank),
        senderAvatarUrl = target.senderAvatarUrl?.trim()?.takeIf(String::isNotBlank),
    )
}
