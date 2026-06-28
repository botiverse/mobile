package ai.slock.compose

import ai.slock.compose.markdown.ThreadMarkdownTag
import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.TaskSummary

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
    val messages: List<KuiklyThreadMessageItem>,
    val originMessage: KuiklyThreadMessageItem?,
    val focusedMessageId: String?,
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
    val showTaskOpenActions: Boolean,
    val showTaskStatusActions: Boolean,
    val canConvertMessagesToTasks: Boolean,
    val beginningReplyCountLabel: String?
)

internal fun KuiklyThreadScreenPayload.toThreadTimelineSlice(): ThreadTimelineSlice =
    ThreadTimelineSlice(
        status = status,
        originStatus = originStatus,
        messages = messages.map { message -> message.withoutThreadReplyMetadata() },
        originMessage = originMessage?.withoutThreadReplyMetadata(),
        focusedMessageId = focusedMessageId,
        canLoadMoreBefore = canLoadMoreBefore,
        canLoadMoreAfter = canLoadMoreAfter,
        olderMessagesStatus = olderMessagesStatus,
        newerMessagesStatus = newerMessagesStatus,
        emptyTitle = emptyTitle,
        emptyDetail = emptyDetail,
        sourceThreadId = sourceThreadId,
        alignOwnMessagesRight = alignOwnMessagesRight,
        compactConsecutiveMessages = compactConsecutiveMessages,
        showReplyActions = showReplyActions,
        showMessageSaveAction = showMessageSaveAction,
        showTaskOpenActions = showTaskOpenActions,
        showTaskStatusActions = showTaskStatusActions,
        canConvertMessagesToTasks = canConvertMessagesToTasks(),
        beginningReplyCountLabel = threadBeginningReplyCountLabel()
    )

internal fun KuiklyThreadMessageItem.toThreadReplyMetadata(): ThreadReplyMetadata =
    ThreadReplyMetadata(
        parentMessageId = id,
        threadChannelId = threadChannelId?.takeIf { it.isNotBlank() },
        replyCount = replyCount,
        unreadReplyCount = unreadReplyCount,
        firstUnreadReplyMessageId = firstUnreadReplyMessageId?.takeIf { it.isNotBlank() },
        isThreadFollowed = isThreadFollowed
    )

internal fun KuiklyThreadScreenPayload.toThreadReplyMetadataMap(): Map<String, ThreadReplyMetadata> =
    buildMap {
        originMessage?.let { message ->
            put(message.id, message.toThreadReplyMetadata())
        }
        messages.forEach { message ->
            put(message.id, message.toThreadReplyMetadata())
        }
    }

internal fun Map<String, ThreadReplyMetadata>.threadReplyMetadataFor(
    message: KuiklyThreadMessageItem
): ThreadReplyMetadata =
    this[message.id] ?: message.toThreadReplyMetadata()

internal fun KuiklyThreadMessageItem.withoutThreadReplyMetadata(): KuiklyThreadMessageItem =
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

internal fun KuiklyThreadScreenPayload.withoutThreadTimelineContent(): KuiklyThreadScreenPayload =
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
    val messageIndex = messages.indexOfFirst { it.id == messageId }
    if (messageIndex < 0) return -1
    return firstMessageListItemIndex() + messageIndex
}

internal fun ThreadTimelineSlice.latestMessageListItemIndex(): Int {
    if (messages.isEmpty()) return -1
    return firstMessageListItemIndex() + messages.lastIndex
}

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

internal fun ThreadTimelineSlice.visibleReadAllMarkerKey(sourceThreadId: String?): String? {
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

internal fun ThreadTimelineSlice.threadMessageById(messageId: String): KuiklyThreadMessageItem? =
    messages.firstOrNull { it.id == messageId }
        ?: originMessage?.takeIf { originStatus != "none" && it.id == messageId }

internal fun ThreadTimelineSlice.threadActionMessage(
    messageId: String,
    linkedTasksByMessageLookup: Map<String, TaskSummary> = emptyMap()
): KuiklyThreadMessageItem? =
    threadMessageById(messageId)
        ?.let { message ->
            message.withLinkedTask(linkedTasksByMessageLookup.threadLinkedTaskForMessage(message))
        }

internal fun ThreadTimelineSlice.threadActionMessage(
    messageId: String,
    linkedTasksProjection: ThreadLinkedTasksProjection
): KuiklyThreadMessageItem? =
    threadMessageById(messageId)
        ?.withLinkedTaskProjection(linkedTasksProjection)

internal fun ThreadTimelineSlice.findTaskParentMessage(
    taskId: String,
    linkedTasksProjection: ThreadLinkedTasksProjection = emptyThreadLinkedTasksProjection()
): KuiklyThreadMessageItem? {
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
    fun KuiklyThreadMessageItem.markdownTaskMatch(): String? {
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

private fun KuiklyThreadMessageItem.toThreadAuthorProfile(): MemberProfile =
    MemberProfile(
        id = MemberId(authorId),
        displayName = authorName.ifBlank { authorId },
        handle = authorHandle.ifBlank { authorName.ifBlank { authorId } },
        kind = if (authorKind.equals("agent", ignoreCase = true)) MemberKind.Agent else MemberKind.Human,
        gravatarHash = gravatarHash,
        avatar = avatarWireValue?.takeIf { it.isNotBlank() }?.let(AvatarReference::fromWireValue),
        avatarLabel = avatarLabel.ifBlank { authorName.ifBlank { authorId }.take(1).uppercase() },
        agentStatus = AgentStatus.fromWireValue(authorAgentStatus)
    )

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

internal fun ThreadTimelineSlice.findThreadAttachment(attachmentId: String): KuiklyThreadAttachmentItem? =
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

internal fun ThreadTimelineSlice.focusedMessageIndex(): Int =
    focusedMessageId
        ?.takeIf { it.isNotBlank() }
        ?.let { targetMessageId -> messages.indexOfFirst { it.id == targetMessageId } }
        ?: -1

internal fun ThreadTimelineSlice.initialMarkdownPreparseCandidateSignature(contentExtrasVisible: Boolean): String {
    if (!contentExtrasVisible || messages.isEmpty()) return ""
    return messages
        .takeLast(ThreadInitialMarkdownPreparseMessageCount)
        .joinToString(separator = "|") { message ->
            "${message.id}:${message.body.length}:${message.body.hashCode()}"
        }
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
