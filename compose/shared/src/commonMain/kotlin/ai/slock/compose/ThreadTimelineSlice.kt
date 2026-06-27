package ai.slock.compose

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
        messages = messages,
        originMessage = originMessage,
        focusedMessageId = focusedMessageId,
        canLoadMoreBefore = canLoadMoreBefore,
        canLoadMoreAfter = canLoadMoreAfter,
        olderMessagesStatus = olderMessagesStatus,
        newerMessagesStatus = newerMessagesStatus,
        emptyTitle = emptyTitle,
        emptyDetail = emptyDetail,
        sourceThreadId = sourceThreadId,
        alignOwnMessagesRight = alignOwnMessagesRight,
        showReplyActions = showReplyActions,
        showMessageSaveAction = showMessageSaveAction,
        showTaskOpenActions = showTaskOpenActions,
        showTaskStatusActions = showTaskStatusActions,
        canConvertMessagesToTasks = canConvertMessagesToTasks(),
        beginningReplyCountLabel = threadBeginningReplyCountLabel()
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
        append(latest.replyCount)
        append(':')
        append(latest.unreadReplyCount)
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
