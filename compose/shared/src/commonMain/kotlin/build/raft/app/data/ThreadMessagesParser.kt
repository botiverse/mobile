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
import build.raft.app.commentHostRouteKindOrNull
import build.raft.app.commentHostTypeOrNull
import build.raft.app.matchesResolvedReplyThreadTimeline
import build.raft.app.memberKindWireValue
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageType
import build.raft.app.model.TaskStatus
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.model.providerLabel
import build.raft.app.replyThreadContentChannelId
import build.raft.app.strictMemberKindOrNull
import build.raft.app.strictMessageTypeOrNull
import build.raft.app.strictProjectionDiagnosticsLogger
import build.raft.app.strictTaskStatusOrNull

/**
 * Thread UI-item projections from parsed [ThreadMessagesPage] wire models into
 * [RaftThreadScreenPayload] / [RaftThreadMessageItem]. The pure wire structs and attachment/file
 * parsers live in `ThreadWireModels.kt`.
 */

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
        channelArchived = page.channelArchived ?: basePayload.channelArchived,
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
    // A missing/partial externalAuthor renders with a fallback author, never drops the reply.
    val resolvedExternalAuthor =
        if (external) {
            externalMessageAuthorOrFallback(externalAuthor, senderName, "thread_wire_sender", id, diagnostics)
        } else {
            null
        }
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
        authorId =
            if (resolvedExternalAuthor != null) {
                externalSenderIdOrSyntheticKey(senderId, resolvedExternalAuthor, id)
            } else {
                senderId.ifBlank { "unknown" }
            },
        authorName = resolvedName,
        authorDescription =
            if (resolvedExternalAuthor != null) {
                resolvedExternalAuthor.providerLabel()
            } else {
                senderDescription?.takeIf { it.isNotBlank() }
            },
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
        metadata = actionMetadata.toMessageItemMetadata(),
        body = content,
        randomId = randomId?.takeIf { it.isNotBlank() },
        threadChannelId = firstNonBlank(threadId, summary?.threadChannelId),
        replyCount = replyCount ?: summary?.replyCount ?: 0,
        unreadReplyCount = unreadReplyCount ?: threadUnreadCount ?: summary?.unreadCount ?: 0,
        firstUnreadReplyMessageId = firstNonBlank(firstUnreadReplyMessageId, summary?.firstUnreadMessageId),
        isSaved = isSaved,
        taskId = if (hasTaskProjection && resolvedTaskStatus != null) id else null,
        taskNumber = taskNumber?.takeIf { resolvedTaskStatus != null },
        // A status this build does not know is kept as the app's Unknown token, so every reader agrees.
        taskStatus = if (resolvedTaskStatus == TaskStatus.Unknown) TaskStatus.UNKNOWN_WIRE_VALUE else taskStatus?.takeIf { resolvedTaskStatus != null },
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
        resolvedQuotes =
            decodeResolvedInlineQuotes(
                element = resolvedQuotes,
                absentMeansNone = true,
                source = "thread_wire_resolved_quotes",
                diagnostics = diagnostics,
            ),
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
