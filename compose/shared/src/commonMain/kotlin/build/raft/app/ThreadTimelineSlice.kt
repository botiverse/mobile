package build.raft.app

import build.raft.app.markdown.ThreadMarkdownTag
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.TaskSummary

internal data class ThreadReplyMetadata(
    val parentMessageId: String,
    val threadChannelId: String?,
    val replyCount: Int,
    val unreadReplyCount: Int,
    val firstUnreadReplyMessageId: String?,
    val isThreadFollowed: Boolean
)

internal data class ThreadTimelineSlice(
    val status: String,
    val originStatus: String,
    val messages: List<RaftThreadMessageItem>,
    val originMessage: RaftThreadMessageItem?,
    val focusedMessageId: String?,
    val focusedMessageRequestNonce: Int,
    val canLoadMoreBefore: Boolean,
    val canLoadMoreAfter: Boolean,
    val olderMessagesStatus: String,
    val newerMessagesStatus: String,
    val emptyTitle: String,
    val emptyDetail: String?,
    val sourceThreadId: String?,
    val alignOwnMessagesRight: Boolean,
    val compactConsecutiveMessages: Boolean,
    val showReplyActions: Boolean,
    val showMessageSaveAction: Boolean,
    val showReactionActions: Boolean,
    val showTaskOpenActions: Boolean,
    val showTaskStatusActions: Boolean,
    val canConvertMessagesToTasks: Boolean,
    val beginningReplyCountLabel: String?,
    /** The count behind [beginningReplyCountLabel], so the UI can render it from string resources. */
    val beginningReplyCount: Int? = null,
    /** Task #232 arrival provenance projected from the payload; see [RaftThreadScreenPayload.gapRefillMessageIds]. */
    val gapRefillMessageIds: Set<String> = emptySet(),
    /** Task #1125: the frame is the offline durable stand-in (see [RaftThreadScreenPayload.durableLastKnown]). */
    val durableLastKnown: Boolean = false,
    /** Task #168: the stand-in reaches the conversation's start (see [RaftThreadScreenPayload.durableReachedOldest]). */
    val durableReachedOldest: Boolean = false,
    /**
     * Web `showForwardAction` (ChatPanel / ThreadPanel): the viewer is not a guest and the
     * conversation resolves to a forward source channel. Set by the route from the session.
     */
    val showForwardAction: Boolean = false,
    /** The viewer's role on this conversation's server is `guest` (web `currentUserServerRole`). */
    val viewerIsGuest: Boolean = false
)

internal data class ThreadInlineSearchMatch(
    val messageId: String,
    val listItemIndex: Int
)

internal fun RaftThreadScreenPayload.toThreadTimelineSlice(): ThreadTimelineSlice =
    ThreadTimelineSlice(
        status = status,
        originStatus = originStatus,
        messages = messages.map { message -> message.withoutThreadReplyMetadata() },
        originMessage = originMessage?.withoutThreadReplyMetadata(),
        focusedMessageId = focusedMessageId,
        focusedMessageRequestNonce = focusedMessageRequestNonce,
        canLoadMoreBefore = canLoadMoreBefore,
        canLoadMoreAfter = canLoadMoreAfter,
        olderMessagesStatus = olderMessagesStatus,
        newerMessagesStatus = newerMessagesStatus,
        emptyTitle =
            if (surfaceKind == RaftThreadSurfaceKind.ReplyThread && status == "empty") {
                ThreadReplyEmptyTitle
            } else {
                emptyTitle
            },
        emptyDetail =
            if (surfaceKind == RaftThreadSurfaceKind.ReplyThread && status == "empty") {
                ThreadReplyEmptyDetail
            } else {
                emptyDetail
            },
        sourceThreadId = sourceThreadId,
        gapRefillMessageIds = gapRefillMessageIds,
        durableLastKnown = durableLastKnown,
        durableReachedOldest = durableReachedOldest,
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        showReplyActions = showReplyActions,
        showMessageSaveAction = showMessageSaveAction,
        showReactionActions = showReactionActions,
        showTaskOpenActions = showTaskOpenActions,
        showTaskStatusActions = showTaskStatusActions,
        canConvertMessagesToTasks = canConvertMessagesToTasks(),
        beginningReplyCountLabel = threadBeginningReplyCountLabel(),
        beginningReplyCount = threadBeginningReplyCount()
    )

internal fun RaftThreadMessageItem.toThreadReplyMetadata(): ThreadReplyMetadata =
    ThreadReplyMetadata(
        parentMessageId = id,
        threadChannelId = threadChannelId?.takeIf { it.isNotBlank() },
        replyCount = replyCount,
        unreadReplyCount = unreadReplyCount,
        firstUnreadReplyMessageId = firstUnreadReplyMessageId?.takeIf { it.isNotBlank() },
        isThreadFollowed = isThreadFollowed
    )

internal fun RaftThreadScreenPayload.toThreadReplyMetadataMap(): Map<String, ThreadReplyMetadata> =
    buildMap {
        originMessage?.let { message ->
            put(message.id, message.toThreadReplyMetadata())
        }
        messages.forEach { message ->
            put(message.id, message.toThreadReplyMetadata())
        }
    }

internal fun Map<String, ThreadReplyMetadata>.threadReplyMetadataFor(
    message: RaftThreadMessageItem
): ThreadReplyMetadata =
    this[message.id] ?: message.toThreadReplyMetadata()

internal fun RaftThreadMessageItem.withoutThreadReplyMetadata(): RaftThreadMessageItem =
    if (
        threadChannelId == null &&
        replyCount == 0 &&
        unreadReplyCount == 0 &&
        firstUnreadReplyMessageId == null &&
        !isThreadFollowed
    ) {
        this
    } else {
        copy(
            threadChannelId = null,
            replyCount = 0,
            unreadReplyCount = 0,
            firstUnreadReplyMessageId = null,
            isThreadFollowed = false
        )
    }

internal fun RaftThreadScreenPayload.withoutThreadTimelineContent(): RaftThreadScreenPayload =
    copy(
        messages = emptyList(),
        originMessage = null,
        focusedMessageId = null,
        originStatus = "none",
        originErrorCode = null,
        canLoadMoreBefore = false,
        canLoadMoreAfter = false,
        olderMessagesStatus = "idle",
        newerMessagesStatus = "idle",
        emptyTitle = "",
        emptyDetail = ""
    )

internal fun ThreadTimelineSlice.hasThreadHistoryBoundaryItem(): Boolean =
    messages.isNotEmpty()

internal fun ThreadTimelineSlice.hasThreadOriginItem(): Boolean =
    originStatus != "none"

internal fun ThreadTimelineSlice.threadHistoryBoundaryListItemIndex(): Int =
    if (hasThreadOriginItem()) 1 else 0

internal fun ThreadTimelineSlice.firstMessageListItemIndex(): Int {
    var index = 0
    if (hasThreadOriginItem()) index += 1
    if (hasThreadHistoryBoundaryItem()) index += 1
    return index
}

internal fun ThreadTimelineSlice.threadMessageListItemIndex(messageId: String): Int {
    if (hasThreadOriginItem() && originMessage?.id == messageId) {
        return 0
    }
    val messageIndex = messages.indexOfFirst { it.id == messageId }
    if (messageIndex < 0) return -1
    return firstMessageListItemIndex() + messageIndex
}

internal fun ThreadTimelineSlice.threadInlineSearchMatches(query: String): List<ThreadInlineSearchMatch> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return emptyList()
    return (listOfNotNull(originMessage) + messages)
        .distinctBy { it.id }
        .filter { message -> message.matchesThreadInlineSearch(normalizedQuery) }
        .mapNotNull { message ->
            val index = threadMessageListItemIndex(message.id)
            if (index >= 0) ThreadInlineSearchMatch(message.id, index) else null
        }
}

private fun RaftThreadMessageItem.matchesThreadInlineSearch(normalizedQuery: String): Boolean =
    body.contains(normalizedQuery, ignoreCase = true) ||
        authorName.contains(normalizedQuery, ignoreCase = true) ||
        authorHandle.contains(normalizedQuery, ignoreCase = true) ||
        taskId.orEmpty().contains(normalizedQuery, ignoreCase = true) ||
        taskNumber?.toString().orEmpty().contains(normalizedQuery, ignoreCase = true)

internal fun ThreadTimelineSlice.latestMessageListItemIndex(): Int {
    if (messages.isEmpty()) return -1
    return firstMessageListItemIndex() + messages.lastIndex
}

internal fun ThreadTimelineSlice.bottomScrollPaddingListItemIndex(lazyItemCount: Int): Int =
    if (lazyItemCount > 0) lazyItemCount - 1 else latestMessageListItemIndex()

internal fun ThreadTimelineSlice.latestMessageLayoutKey(): String {
    val latest = messages.lastOrNull()
        ?: return "empty:$originStatus:$canLoadMoreBefore:$canLoadMoreAfter"
    return buildString {
        append(originStatus)
        append(':')
        append(hasThreadHistoryBoundaryItem())
        append(':')
        append(canLoadMoreAfter)
        append(':')
        append(messages.size)
        append(':')
        append(latest.id)
        append(':')
        append(latest.body.length)
        append(':')
        append(latest.attachments.size)
        append(':')
        append(latest.taskId.orEmpty())
        append(':')
        append(latest.threadReactionLayoutSignature())
    }
}

internal fun ThreadTimelineSlice.latestLocalSendBottomAnchorKey(): String? {
    val latest = messages.lastOrNull() ?: return null
    if (!latest.deliveryState.equals(ThreadMessageDeliverySending, ignoreCase = true) ||
        latest.pendingSendContext == null
    ) {
        return null
    }
    return buildString {
        append(latest.id)
        append(':')
        append(latest.body.length)
        append(':')
        append(latest.attachments.size)
        append(':')
        append(latest.pendingSendContext.attachmentIds.size)
    }
}

internal fun ThreadTimelineSlice.hasSendingLocalSendAnchor(anchorKey: String?): Boolean {
    val messageId = anchorKey?.substringBefore(':')?.takeIf { it.isNotBlank() } ?: return false
    return messages.any { message ->
        message.id == messageId &&
            message.deliveryState.equals(ThreadMessageDeliverySending, ignoreCase = true) &&
            message.pendingSendContext != null
    }
}

// task #232: viewport-aware variant. When the rendered visible tail is known,
// the read marker must not advance past what the user actually sees; otherwise
// live appends while off-bottom would zero the unread projection and the
// "↓ x new" pill could never appear.
internal fun ThreadTimelineSlice.visibleReadMarkerKey(
    sourceThreadId: String?,
    visibleTailIndex: Int?
): String? {
    val threadId = sourceThreadId?.takeIf { it.isNotBlank() } ?: return null
    if (status != "content") return null
    val latest =
        visibleTailIndex?.takeIf { it in messages.indices }?.let { messages[it] }
            ?: messages.lastOrNull()
            ?: return null
    return buildString {
        append(threadId)
        append(':')
        append(latest.seq?.toString() ?: "no-seq")
        append(':')
        append(latest.id)
    }
}

internal fun ThreadTimelineSlice.visibleReadMarkerKey(sourceThreadId: String?): String? {
    val threadId = sourceThreadId?.takeIf { it.isNotBlank() } ?: return null
    if (status != "content") return null
    val latest = messages.lastOrNull() ?: return null
    return buildString {
        append(threadId)
        append(':')
        append(latest.seq?.toString() ?: "no-seq")
        append(':')
        append(latest.id)
    }
}

internal fun ThreadTimelineSlice.threadMessageById(messageId: String): RaftThreadMessageItem? =
    messages.firstOrNull { it.id == messageId }
        ?: originMessage?.takeIf { originStatus != "none" && it.id == messageId }

internal fun ThreadTimelineSlice.threadActionMessage(
    messageId: String,
    linkedTasksByMessageLookup: Map<String, TaskSummary> = emptyMap()
): RaftThreadMessageItem? =
    threadMessageById(messageId)
        ?.let { message ->
            message.withLinkedTask(linkedTasksByMessageLookup.threadLinkedTaskForMessage(message))
        }

internal fun ThreadTimelineSlice.threadActionMessage(
    messageId: String,
    linkedTasksProjection: ThreadLinkedTasksProjection
): RaftThreadMessageItem? =
    threadMessageById(messageId)
        ?.withLinkedTaskProjection(linkedTasksProjection)

internal fun ThreadTimelineSlice.findTaskParentMessage(
    taskId: String,
    linkedTasksProjection: ThreadLinkedTasksProjection = emptyThreadLinkedTasksProjection()
): RaftThreadMessageItem? {
    val taskKey = taskId.trim().takeIf { it.isNotBlank() } ?: return null
    return messages.firstOrNull { message ->
        message.id == taskKey ||
            message.taskId == taskKey ||
            linkedTasksProjection.lookup.threadLinkedTaskForMessage(message)?.id?.value == taskKey
    } ?: originMessage?.takeIf { message ->
        message.id == taskKey ||
            message.taskId == taskKey ||
            linkedTasksProjection.lookup.threadLinkedTaskForMessage(message)?.id?.value == taskKey
    }
}

internal fun ThreadTimelineSlice.findMarkdownTaskId(
    tag: ThreadMarkdownTag,
    linkedTasksProjection: ThreadLinkedTasksProjection = emptyThreadLinkedTasksProjection()
): String? {
    val key = tag.label.removePrefix("#").trim()
    val taskNumber = key.toIntOrNull()
    fun RaftThreadMessageItem.markdownTaskMatch(): String? {
        val linkedTask = linkedTasksProjection.lookup.threadLinkedTaskForMessage(this)
        return when {
            linkedTask?.id?.value?.equals(key, ignoreCase = true) == true -> linkedTask.id.value
            taskNumber != null && linkedTask?.taskNumber == taskNumber -> linkedTask.id.value
            taskId?.equals(key, ignoreCase = true) == true -> taskId
            taskNumber != null && this.taskNumber == taskNumber -> taskId
            else -> null
        }
    }
    return messages.firstNotNullOfOrNull { message -> message.markdownTaskMatch() }
        ?: originMessage?.markdownTaskMatch()
}

internal fun ThreadTimelineSlice.findThreadAuthorProfile(memberId: String): MemberProfile? =
    (listOfNotNull(originMessage) + messages)
        .firstOrNull { it.authorId == memberId }
        ?.toThreadAuthorProfile()

private fun RaftThreadMessageItem.toThreadAuthorProfile(): MemberProfile? {
    val kind = authorKind.projectedMemberKindOrNull() ?: return null
    return MemberProfile(
        id = MemberId(authorId),
        displayName = authorName.ifBlank { authorId },
        handle = authorHandle.ifBlank { authorName.ifBlank { authorId } },
        kind = kind,
        avatar = avatarWireValue?.takeIf { it.isNotBlank() }?.let(AvatarReference::fromWireValue),
        avatarLabel = avatarLabel.ifBlank { authorName.ifBlank { authorId }.take(1).uppercase() }
    )
}

internal fun ThreadTimelineSlice.findThreadImageAttachmentGroup(attachmentId: String): ThreadImageAttachmentGroup? {
    val messageCandidates = (listOfNotNull(originMessage) + messages).distinctBy { it.id }
    messageCandidates.forEach { message ->
        val images = message.attachments.filter { it.isImageAttachment() }
        val currentIndex = images.indexOfFirst { it.id == attachmentId }
        if (currentIndex >= 0) {
            return ThreadImageAttachmentGroup(images = images, currentIndex = currentIndex)
        }
    }
    return null
}

internal fun ThreadTimelineSlice.findThreadAttachment(attachmentId: String): RaftThreadAttachmentItem? =
    (listOfNotNull(originMessage) + messages)
        .asSequence()
        .flatMap { it.attachments.asSequence() }
        .firstOrNull { it.id == attachmentId }

internal fun ThreadTimelineSlice.findAttachmentCommentTarget(attachmentId: String): ThreadAttachmentCommentTarget? {
    val cleanAttachmentId = attachmentId.trim()
    if (cleanAttachmentId.isBlank()) return null
    val allMessages = listOfNotNull(originMessage) + messages
    allMessages.forEach { message ->
        val attachment = message.attachments.firstOrNull { it.id == cleanAttachmentId }
        if (attachment != null) {
            return ThreadAttachmentCommentTarget(attachment = attachment, parentMessage = message)
        }
    }
    return null
}

internal fun ThreadTimelineSlice.threadReactionToggleTarget(
    messageId: String,
    emoji: String
): ThreadReactionToggleTarget? {
    val cleanMessageId = messageId.trim()
    val cleanEmoji = emoji.trim()
    if (cleanMessageId.isBlank() || cleanEmoji.isBlank()) return null
    val candidates =
        (messages + listOfNotNull(originMessage))
            .filter { message -> message.id == cleanMessageId && !message.isSystemMessage() }
    if (candidates.isEmpty()) return null
    val selectedCandidate =
        candidates.firstOrNull { message ->
            message.reactions.any { reaction -> reaction.emoji == cleanEmoji && reaction.selected }
        }
    val message = selectedCandidate ?: candidates.first()
    return ThreadReactionToggleTarget(
        message = message,
        nextSelected = selectedCandidate == null
    )
}

internal fun ThreadTimelineSlice.messageIndexSignature(): String =
    messages.joinToString(separator = "|") { message -> message.id }

internal fun ThreadTimelineSlice.focusedMessageListItemIndex(): Int =
    focusedMessageId
        ?.takeIf { it.isNotBlank() }
        ?.takeUnless(::isFocusedOriginMessage)
        ?.let(::threadMessageListItemIndex)
        ?: -1

internal fun ThreadTimelineSlice.isFocusedOriginMessage(messageId: String): Boolean =
    hasThreadOriginItem() && originMessage?.id == messageId

internal fun ThreadTimelineSlice.focusedMessageTargetSignature(): String =
    buildString {
        append(focusedMessageId.orEmpty())
        append(':')
        val focusedTargetIsOrigin =
            !focusedMessageId.isNullOrBlank() &&
                (originMessage?.id == focusedMessageId || messages.none { message -> message.id == focusedMessageId })
        if (focusedTargetIsOrigin) {
            append(originStatus)
            append(':')
            append(originMessage?.id.orEmpty())
            append(':')
        }
        append(messageIndexSignature())
    }

internal fun ThreadTimelineSlice.initialMarkdownPreparseCandidateSignature(contentExtrasVisible: Boolean): String {
    if (!contentExtrasVisible || (messages.isEmpty() && originMessage == null)) return ""
    return buildList {
        messages.asReversed().forEach { message ->
            add("${message.threadMessageRenderIdentity()}:${message.body.length}:${message.body.hashCode()}")
        }
        originMessage?.let { origin ->
            add("origin:${origin.threadMessageRenderIdentity()}:${origin.body.length}:${origin.body.hashCode()}")
        }
    }.joinToString(separator = "|")
}

internal fun ThreadTimelineSlice.threadLinkedTaskReferences(): ThreadLinkedTaskReferences {
    val timelineMessages = buildList {
        originMessage?.let(::add)
        addAll(messages)
    }
    return ThreadLinkedTaskReferences(
        messageIds = timelineMessages.mapTo(mutableSetOf()) { it.id },
        taskIds =
            timelineMessages.mapNotNullTo(mutableSetOf()) { message ->
                message.taskId?.trim()?.takeIf { it.isNotBlank() }
            },
        taskNumbers = timelineMessages.mapNotNullTo(mutableSetOf()) { it.taskNumber }
    )
}

internal fun ThreadTimelineSlice.withThreadLinkedTasksProjection(
    projection: ThreadLinkedTasksProjection
): ThreadTimelineSlice {
    if (projection.lookup.isEmpty() && !projection.authoritative) return this
    val projectedMessages =
        messages.map { message ->
            val linkedTask = projection.lookup.threadLinkedTaskForMessage(message)
            when {
                linkedTask != null -> message.withLinkedTask(linkedTask)
                projection.authoritative -> message.withoutLinkedTask()
                else -> message
            }
        }
    val projectedOriginMessage =
        originMessage?.let { message ->
            val linkedTask = projection.lookup.threadLinkedTaskForMessage(message)
            when {
                linkedTask != null -> message.withLinkedTask(linkedTask)
                projection.authoritative -> message.withoutLinkedTask()
                else -> message
            }
        }
    return copy(messages = projectedMessages, originMessage = projectedOriginMessage)
}
