package build.raft.app.data

import build.raft.app.toRaftAvatarLabel
import build.raft.app.RaftThreadAttachmentItem
import build.raft.app.RaftThreadMessageCommentHostSource
import build.raft.app.RaftThreadMessageCommentRef
import build.raft.app.RaftThreadMessageItem
import build.raft.app.RaftThreadReactionItem
import build.raft.app.RaftThreadScreenPayload
import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.MemberKindWireContract
import build.raft.app.SharedThreadComposerAttachment
import build.raft.app.commentHostRouteKindOrNull
import build.raft.app.commentHostTypeOrNull
import build.raft.app.matchesResolvedReplyThreadTimeline
import build.raft.app.memberKindWireValue
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageType
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.providerLabel
import build.raft.app.optJsonArray
import build.raft.app.optJsonObject
import build.raft.app.optLong
import build.raft.app.optString
import build.raft.app.parseRaftJsonObject
import build.raft.app.replyThreadContentChannelId
import build.raft.app.strictMemberKindOrNull
import build.raft.app.strictMessageTypeOrNull
import build.raft.app.strictProjectionDiagnosticsLogger
import build.raft.app.strictTaskStatusOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Strict, React-aligned parsers for the thread message timeline + attachment
 * upload responses. The wire shapes mirror what `packages/server/src/routes`
 * actually emits:
 *
 *  - `messages.ts` GET `/api/messages`:
 *      `{ messages: Message[], historyLimited: boolean }`
 *  - `messages.ts` POST `/api/messages`:
 *      a single enriched `Message` row (same shape, no wrapper)
 *  - `attachments.ts` POST `/api/uploads`:
 *      `{ attachments: Attachment[] }`
 *
 * The enriched `Message` row is defined by `enrichWithSenderNames` in
 * `packages/server/src/services/messageService.ts` and the React mirror in
 * `packages/web/src/store/messageStore.ts`:
 *
 *   { id, seq?, channelId, senderType: "user"|"agent", senderId, senderName?,
 *     senderMembershipStatus?, messageType?: "chat"|"system", content,
 *     threadId?, createdAt, attachments?: MessageAttachment[],
 *     reactions?: MessageReaction[] }
 *
 * Message footer metadata mirrors React's `MessageItem`: task badge fields,
 * reply/new counts, and reactions are parsed here when the message route emits
 * them. Member-profile-only `avatarWireValue` and
 * system-detail fields (`systemEventType`, `metadata`) are still populated by
 * their dedicated upstream joins.
 */

private val threadMessagesJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
internal data class WireCommentHostSource(
    val type: String = "",
    val routeKind: String = "",
    val channelId: String = "",
    val parentMessageId: String? = null,
    val threadChannelId: String? = null,
    val rootThreadChannelId: String? = null,
)

@Serializable
internal data class WireCommentRef(
    val attachmentId: String = "",
    val filename: String = "",
    val hostMessageId: String? = null,
    val hostSource: WireCommentHostSource? = null,
    val anchorLabel: String? = null,
)

@Serializable
internal data class WireMessage(
    val id: String,
    val seq: Long? = null,
    val channelId: String? = null,
    val senderType: String = "",
    val senderId: String = "",
    val senderName: String? = null,
    val senderDescription: String? = null,
    val senderMembershipStatus: String? = null,
    val messageType: String? = null,
    val content: String = "",
    val randomId: String? = null,
    val threadId: String? = null,
    val createdAt: String? = null,
    val attachments: List<WireAttachment> = emptyList(),
    val reactions: List<WireReaction> = emptyList(),
    val threadSummary: WireThreadSummary? = null,
    val replyCount: Int? = null,
    val unreadReplyCount: Int? = null,
    val threadUnreadCount: Int? = null,
    val firstUnreadReplyMessageId: String? = null,
    val isSaved: Boolean = false,
    val taskStatus: String? = null,
    val taskNumber: Int? = null,
    val taskAssigneeType: String? = null,
    val taskAssigneeId: String? = null,
    val taskAssigneeName: String? = null,
    val claimedByName: String? = null,
    val taskClaimedByName: String? = null,
    val actionMetadata: WireActionCardMetadata? = null,
    val commentRef: WireCommentRef? = null,
    val forwardBundle: WireForwardMessageBundle? = null,
    /** Slack-bridge external sender attribution (present iff senderType=external_projection). */
    val externalAuthor: WireExternalAuthor? = null,
    /** RFC 077 `DelegatedByLabel | null`: the assistant that sent it for the sender. */
    val delegatedBy: WireDelegatedBy? = null,
    /**
     * RFC 077 PR 2b: set when the owner retracted the message; `content` is then the placeholder.
     * The server spreads the full `messages` row (`messageService.ts` `...visibleRow`), ISO or null.
     */
    val deletedAt: String? = null,
    /** Resolved mention facts; see [WireMessageMention]. */
    val mentions: List<WireMessageMention> = emptyList(),
)

@Serializable
internal data class WireThreadSummary(
    val threadChannelId: String? = null,
    val replyCount: Int? = null,
    val unreadCount: Int? = null,
    val firstUnreadMessageId: String? = null,
)

@Serializable
internal data class WireAttachment(
    val id: String,
    val filename: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnailUrl: String? = null,
    val rasterPreviewUrl: String? = null,
    val commentCount: Int = 0,
)

@Serializable
internal data class WireReaction(
    val emoji: String,
    val count: Int = 0,
    val reactorIds: List<String> = emptyList(),
    val reactorNames: List<String> = emptyList(),
)

@Serializable
internal data class WireMessagesResponse(
    val messages: List<WireMessage> = emptyList(),
    val historyLimited: Boolean = false,
)

@Serializable
private data class WireAttachmentsResponse(
    val attachments: List<WireAttachment> = emptyList(),
)

internal fun parseSharedThreadUploadedAttachments(
    response: JsonObject?
): List<SharedThreadComposerAttachment> {
    val bodyText = response?.optString("bodyText").orEmpty()
    if (bodyText.isBlank()) return emptyList()
    return runCatching {
        threadMessagesJson
            .decodeFromString(WireAttachmentsResponse.serializer(), bodyText)
            .attachments
            .mapIndexed { index, att ->
                SharedThreadComposerAttachment(
                    id = att.id,
                    fileName = att.filename.ifBlank { "Attachment ${index + 1}" },
                    sizeLabel = "",
                    sizeBytes = att.sizeBytes,
                    mimeType = att.mimeType.ifBlank { null },
                    status = "ready"
                )
            }
            .distinctBy { it.id }
    }.getOrElse { emptyList() }
}

@OptIn(ExperimentalEncodingApi::class)
internal fun parseSharedThreadPickedPlatformFiles(
    response: JsonObject?
): List<PlatformFile> {
    val filesJson = response?.optJsonArray("files")
        ?: response?.optString("bodyText").orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let { bodyText -> runCatching { parseRaftJsonObject(bodyText).optJsonArray("files") }.getOrNull() }
        ?: return emptyList()
    return buildList {
        for (index in 0 until filesJson.size) {
            val fileJson = filesJson.optJsonObject(index) ?: continue
            val fileName = fileJson.optString("fileName").takeIf { it.isNotBlank() }
                ?: "attachment-${index + 1}"
            val fileRef = fileJson.optString("fileRef").takeIf { it.isNotBlank() }
            val encoded = fileJson.optString("bodyBase64").takeIf { it.isNotBlank() }
            val bytes = encoded?.let { runCatching { Base64.decode(it) }.getOrNull() }
            if (fileRef == null && bytes == null) continue
            add(
                PlatformFile(
                    id = fileJson.optString("id").takeIf { it.isNotBlank() }
                        ?: "picked-${index + 1}",
                    fileName = fileName,
                    mimeType = fileJson.optString("mimeType").takeIf { it.isNotBlank() },
                    sizeBytes = fileJson.optLong("sizeBytes", bytes?.size?.toLong() ?: 0L).takeIf { it > 0L },
                    previewUri = fileJson.optString("previewUri").takeIf { it.isNotBlank() },
                    bytes = bytes ?: ByteArray(0),
                    fileRef = fileRef,
                    source = fileJson.optString("source").takeIf { it.isNotBlank() } ?: "platform_picker"
                )
            )
        }
    }
}

internal fun buildSharedThreadPayloadFromMessagesPage(
    basePayload: RaftThreadScreenPayload,
    page: ThreadMessagesPage,
    session: StoredAuthSession?,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): RaftThreadScreenPayload {
    val currentUserId = session?.user?.id
    val messages =
        page.messages
            .filter { it.belongsToBasePayloadThread(basePayload) }
            .mapNotNull { it.toMessageItem(currentUserId, diagnostics) }
    return basePayload.copy(
        status = if (messages.isEmpty()) "empty" else "content",
        messages = messages,
        canLoadMoreBefore = basePayload.canLoadMoreBefore || (page.canLoadMoreBefore ?: false),
        canLoadMoreAfter = page.canLoadMoreAfter ?: basePayload.canLoadMoreAfter,
        errorCode = null
    )
}

internal fun appendSharedThreadSentMessageFromPage(
    basePayload: RaftThreadScreenPayload,
    page: ThreadMessagesPage,
    session: StoredAuthSession?,
    trustedReplyThreadChannelId: String? = null,
    diagnostics: (String) -> Unit = strictProjectionDiagnosticsLogger,
): RaftThreadScreenPayload {
    val trustSingleReplySend =
        page.messages.size == 1 &&
            basePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
            trustedReplyThreadChannelId
                ?.trim()
                ?.takeIf { it.isNotBlank() && it == basePayload.replyThreadContentChannelId() } != null
    val sent =
        page.messages
            .filter { it.belongsToBasePayloadThread(basePayload) || trustSingleReplySend }
            .mapNotNull { it.toMessageItem(session?.user?.id, diagnostics) }
            .filterNot { it.id in basePayload.messages.map { message -> message.id }.toSet() }

    return if (sent.isEmpty()) {
        basePayload.copy(errorCode = null)
    } else {
        basePayload.copy(
            status = "content",
            messages = basePayload.messages + sent,
            errorCode = null
        )
    }
}

private fun WireMessage.toMessageItem(
    currentUserId: String?,
    diagnostics: (String) -> Unit,
): RaftThreadMessageItem? {
    val resolvedMessageType = messageType.strictMessageTypeOrNull("thread_wire_message", diagnostics) ?: return null
    val senderKind =
        senderType.strictMemberKindOrNull(
            source = "thread_wire_sender",
            diagnostics = diagnostics,
            contract = MemberKindWireContract.MessageSender,
        ) ?: return null
    val external = senderKind == MemberKind.External
    if (external && externalAuthor == null) {
        diagnostics("projection_value_rejected source=thread_wire_sender field=externalAuthor value=missing messageId=$id")
        return null
    }
    val resolvedExternalAuthor =
        if (external) externalAuthor?.toExternalMessageAuthor(senderName) else null
    val authorKind = if (resolvedMessageType == MessageType.System) MemberKind.System else senderKind
    val hasTaskProjection = taskNumber != null || taskStatus != null
    val resolvedTaskStatus =
        if (hasTaskProjection) taskStatus.strictTaskStatusOrNull("thread_wire_task", diagnostics) else null
    val cleanTaskAssigneeId = taskAssigneeId?.trim()?.takeIf { it.isNotBlank() }
    val taskAssigneeKind =
        cleanTaskAssigneeId?.let {
            taskAssigneeType.strictMemberKindOrNull(
                source = "thread_wire_task_assignee",
                diagnostics = diagnostics,
                contract = MemberKindWireContract.InternalActor,
            )
        }
    val resolvedName = resolvedExternalAuthor?.displayName
        ?: senderName?.takeIf { it.isNotBlank() } ?: "Unknown"
    val summary = threadSummary
    return RaftThreadMessageItem(
        id = id,
        authorId = senderId.ifBlank { "unknown" },
        authorName = resolvedName,
        authorDescription =
            resolvedExternalAuthor?.providerLabel()
                ?: senderDescription?.takeIf { it.isNotBlank() },
        authorKind = authorKind.memberKindWireValue(),
        isOwn = currentUserId != null && senderId == currentUserId,
        avatarLabel = resolvedName.toRaftAvatarLabel(),
        avatarWireValue = resolvedExternalAuthor?.avatarUrl,
        externalAuthor = resolvedExternalAuthor,
        delegatedBy = delegatedBy.toMessageDelegatedBy(authorKind),
        deletedAt = deletedAt?.takeIf { it.isNotBlank() },
        senderMembershipStatus = senderMembershipStatus?.takeIf { it.isNotBlank() },
        timestampLabel = createdAt.orEmpty(),
        createdAtEpochMillis = parseRaftTimestampEpochMillisOrNull(createdAt),
        seq = seq,
        messageType = resolvedMessageType.name,
        systemEventType = null,
        metadata = actionMetadata.toThreadActionCard()?.toMessageMetadata().orEmpty(),
        body = content,
        randomId = randomId?.takeIf { it.isNotBlank() },
        threadChannelId = firstNonBlank(threadId, summary?.threadChannelId),
        replyCount = replyCount ?: summary?.replyCount ?: 0,
        unreadReplyCount = unreadReplyCount ?: threadUnreadCount ?: summary?.unreadCount ?: 0,
        firstUnreadReplyMessageId = firstNonBlank(firstUnreadReplyMessageId, summary?.firstUnreadMessageId),
        isSaved = isSaved,
        taskId = if (hasTaskProjection && resolvedTaskStatus != null) id else null,
        taskNumber = taskNumber?.takeIf { resolvedTaskStatus != null },
        taskStatus = taskStatus?.takeIf { resolvedTaskStatus != null },
        taskAssigneeKind = taskAssigneeKind?.memberKindWireValue(),
        taskAssigneeId = cleanTaskAssigneeId?.takeIf { taskAssigneeKind != null },
        taskAssigneeName =
            firstNonBlank(taskAssigneeName, claimedByName, taskClaimedByName)
                ?.takeIf { taskAssigneeKind != null },
        reactions = reactions.map { it.toReactionItem(currentUserId) }.filter { it.count > 0 },
        attachments = attachments.mapIndexed { i, a -> a.toAttachmentItem(i) },
        commentRef = commentRef?.toMessageCommentRef(),
        forwardBundle = forwardBundle.toForwardMessageBundle() ?: actionMetadata.toForwardMessageBundle(),
        mentions = mentions.toMessageMentions(),
    )
}

private fun WireMessage.belongsToBasePayloadThread(basePayload: RaftThreadScreenPayload): Boolean {
    if (basePayload.surfaceKind != RaftThreadSurfaceKind.ReplyThread) return true
    val selectedThreadId = basePayload.replyThreadContentChannelId() ?: return true
    return matchesResolvedReplyThreadTimeline(
        resolvedThreadChannelId = selectedThreadId,
        messageChannelId = channelId,
        messageThreadId = threadId
    )
}

private fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()

private fun WireReaction.toReactionItem(currentUserId: String?): RaftThreadReactionItem =
    RaftThreadReactionItem(
        emoji = emoji,
        count = count,
        selected = currentUserId != null && currentUserId in reactorIds,
        reactorIds = reactorIds,
        reactorNames = reactorNames
    )

private fun WireAttachment.toAttachmentItem(index: Int): RaftThreadAttachmentItem =
    RaftThreadAttachmentItem(
        id = id,
        name = filename.ifBlank { "Attachment ${index + 1}" },
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        sizeLabel = "",
        thumbnailUrl = thumbnailUrl,
        commentCount = commentCount.coerceAtLeast(0)
    )

private fun WireCommentRef.toMessageCommentRef(): RaftThreadMessageCommentRef? {
    val cleanAttachmentId = attachmentId.takeIf { it.isNotBlank() } ?: return null
    val cleanFilename = filename.takeIf { it.isNotBlank() } ?: return null
    return RaftThreadMessageCommentRef(
        attachmentId = cleanAttachmentId,
        filename = cleanFilename,
        hostMessageId = hostMessageId?.takeIf { it.isNotBlank() },
        hostSource = hostSource?.toMessageCommentHostSource(),
        anchorLabel = anchorLabel?.takeIf { it.isNotBlank() }
    )
}

private fun WireCommentHostSource.toMessageCommentHostSource(): RaftThreadMessageCommentHostSource? {
    val cleanType = type.commentHostTypeOrNull() ?: return null
    val cleanRouteKind = routeKind.commentHostRouteKindOrNull() ?: return null
    val cleanChannelId = channelId.takeIf { it.isNotBlank() } ?: return null
    return RaftThreadMessageCommentHostSource(
        type = cleanType,
        routeKind = cleanRouteKind,
        channelId = cleanChannelId,
        parentMessageId = parentMessageId?.takeIf { it.isNotBlank() },
        threadChannelId = threadChannelId?.takeIf { it.isNotBlank() },
        rootThreadChannelId = rootThreadChannelId?.takeIf { it.isNotBlank() }
    )
}
