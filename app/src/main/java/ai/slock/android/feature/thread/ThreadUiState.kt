package ai.slock.android.feature.thread

import ai.slock.android.core.data.repository.ChannelUnreadSnapshot
import ai.slock.android.core.data.repository.ChannelFileItem
import ai.slock.android.core.data.repository.ThreadSnapshot
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.DirectMessageCreated
import ai.slock.android.core.model.Heartbeat
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.MessageNew
import ai.slock.android.core.model.MessageUpdated
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.SyncResumeResponse
import ai.slock.android.core.model.TaskCreated
import ai.slock.android.core.model.TaskDeleted
import ai.slock.android.core.model.ThreadUpdated
import ai.slock.android.core.model.TaskUpdated
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.withUnreadReplyCount
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.tasks.TasksUiState
import ai.slock.compose.model.parseSlockTimestampEpochMillisOrNull
import ai.slock.compose.model.withPreviewMembershipFallback
import ai.slock.compose.model.withTitleFallback
import java.time.ZoneId

data class ThreadUiState(
    val loadState: ScreenLoadState<ThreadContent>,
    val serverId: ServerId? = null,
    val composerState: ComposerState = ComposerState(),
    val selectedTab: ThreadTab = ThreadTab.Chat,
    val tasksState: TasksUiState? = null,
    val filesState: ThreadFilesUiState? = null,
    val channelJoinStatus: ChannelJoinStatus = ChannelJoinStatus.Idle,
    val channelJoinErrorCode: String? = null
)

data class ThreadContent(
    val header: ConversationSummary,
    val messages: List<Message>,
    val tasks: List<TaskSummary> = emptyList(),
    val canLoadMoreBefore: Boolean = false,
    val canLoadMoreAfter: Boolean = false,
    val olderMessagesStatus: MessagePageStatus = MessagePageStatus.Idle,
    val olderMessagesErrorCode: String? = null,
    val newerMessagesStatus: MessagePageStatus = MessagePageStatus.Idle,
    val newerMessagesErrorCode: String? = null,
    val hasGap: Boolean = false,
    val historyLimited: Boolean = false,
    val newUpdateCount: Int = 0,
    val highlightedMessageId: MessageId? = null,
    val replyActionErrorCode: String? = null,
    val anchorMessageId: MessageId? = null,
    val anchorOffset: Int = 0,
    val refreshStatus: ThreadRefreshStatus = ThreadRefreshStatus.Idle,
    val refreshErrorCode: String? = null,
    val parentMessage: Message? = null
)

data class ThreadFilesUiState(
    val loadState: ScreenLoadState<ThreadFilesContent>,
    val loadingMore: Boolean = false,
    val nextCursor: String? = null
)

data class ThreadFilesContent(
    val items: List<ChannelFileItem>,
    val hasMore: Boolean = false
)

fun ThreadUiState.withThreadRefreshStatus(
    status: ThreadRefreshStatus,
    errorCode: String? = null
): ThreadUiState =
    copy(
        loadState = (loadState as? ScreenLoadState.Content)?.let { contentState ->
            ScreenLoadState.Content(
                contentState.data.copy(
                    refreshStatus = status,
                    refreshErrorCode = errorCode
                )
            )
        } ?: loadState
    )

fun ThreadUiState.mergeRefreshedThreadSnapshot(next: ThreadSnapshot): ThreadUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    return copy(
        loadState = ScreenLoadState.Content(
            contentState.data.mergeRefreshedThreadSnapshot(next)
        )
    )
}

fun ThreadContent.withRefreshStatus(
    status: ThreadRefreshStatus,
    errorCode: String? = null
): ThreadContent =
    copy(
        refreshStatus = status,
        refreshErrorCode = errorCode
    )

fun ThreadContent.mergeRefreshedThreadSnapshot(next: ThreadSnapshot): ThreadContent {
    val mergedMessages = (next.messages + messages)
        .distinctBy { it.id }
        .sortedForThreadTimeline()
    return copy(
        header = next.conversation.withTitleFallback(header).withPreviewMembershipFallback(header),
        messages = mergedMessages,
        tasks = if (next.tasks.isNotEmpty()) next.tasks else tasks,
        canLoadMoreBefore = next.canLoadMoreBefore,
        canLoadMoreAfter = next.canLoadMoreAfter,
        hasGap = next.hasGap,
        historyLimited = next.historyLimited,
        newUpdateCount = next.newUpdateCount,
        highlightedMessageId = next.highlightedMessageId ?: highlightedMessageId,
        replyActionErrorCode = replyActionErrorCode,
        anchorMessageId = anchorMessageId,
        anchorOffset = anchorOffset,
        refreshStatus = ThreadRefreshStatus.Idle,
        refreshErrorCode = null,
        parentMessage = next.parentMessage ?: parentMessage
    )
}

fun ThreadUiState.clearReplyUnread(channelId: ChannelId): ThreadUiState =
    updateReplyUnreadCounts { message ->
        if (message.threadChannelId == channelId) {
            message.withUnreadReplyCount(0)
        } else {
            message
        }
    }

fun ThreadUiState.applyReplyUnreadCounts(snapshot: ChannelUnreadSnapshot): ThreadUiState =
    updateReplyUnreadCounts { message ->
        val replyThreadChannelId = message.threadChannelId ?: return@updateReplyUnreadCounts message
        val count = snapshot.unreadCounts[replyThreadChannelId] ?: return@updateReplyUnreadCounts message
        message.withUnreadReplyCount(count)
    }

private inline fun ThreadUiState.updateReplyUnreadCounts(
    transform: (Message) -> Message
): ThreadUiState {
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    return copy(
        loadState =
            ScreenLoadState.Content(
                content.copy(
                    messages = content.messages.map(transform),
                    parentMessage = content.parentMessage?.let(transform)
                )
            )
    )
}

fun List<Message>.sortedForThreadTimeline(): List<Message> =
    sortedWith(MessageTimelineComparator)

private val MessageTimelineComparator =
    Comparator<Message> { left, right ->
        val leftTime = left.timestampLabel.toThreadMessageEpochMillisOrNull()
        val rightTime = right.timestampLabel.toThreadMessageEpochMillisOrNull()
        when {
            leftTime != null && rightTime != null ->
                compareValues(leftTime, rightTime).takeIf { it != 0 } ?: 0
            leftTime != null && rightTime == null -> -1
            leftTime == null && rightTime != null -> 1
            left.seq != null && right.seq != null ->
                compareValues(left.seq, right.seq).takeIf { it != 0 } ?: 0
            left.seq != null && right.seq == null -> -1
            left.seq == null && right.seq != null -> 1
            else -> 0
        }
    }

private fun String.toThreadMessageEpochMillisOrNull(): Long? {
    val trimmed = trim()
    if (trimmed.isBlank()) return null
    return parseThreadMessageTimeOrNull(trimmed)
}

private fun parseThreadMessageTimeOrNull(value: String): Long? {
    val offsetMinutes = ZoneId.systemDefault().currentOffsetMinutes()
    return parseSlockTimestampEpochMillisOrNull(value, offsetMinutes)
}

fun ThreadUiState.mergeRealtimeTaskEvent(event: RealtimeEvent): ThreadUiState =
    when (event) {
        is TaskCreated -> mergeRealtimeTasks(event.tasks)
        is TaskUpdated -> mergeRealtimeTasks(listOf(event.task))
        else -> this
    }

fun ThreadUiState.withTaskStatusUpdate(task: TaskSummary): ThreadUiState =
    mergeRealtimeTasks(listOf(task))

private fun ThreadUiState.mergeRealtimeTasks(tasks: List<TaskSummary>): ThreadUiState {
    if (tasks.isEmpty()) return this
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    val taskReferences =
        content.messages.mapNotNull { it.task } +
            listOfNotNull(content.parentMessage?.task) +
            content.tasks
    val mergedTasks =
        tasks.map { task ->
            taskReferences.firstOrNull { reference -> task.matchesTaskReference(reference) }
                ?.withMessageTaskReference(task)
                ?: task
        }
    val updatedMessages = content.messages.map { message -> message.withRealtimeTask(mergedTasks) }
    val updatedParentMessage = content.parentMessage?.withRealtimeTask(mergedTasks)
    val updatedTaskIds = mergedTasks.map { it.id }.toSet()
    val updatedTaskNumbers = mergedTasks.mapNotNull { it.taskNumber }.toSet()
    val updatedTasks =
        (content.tasks.filterNot {
            it.id in updatedTaskIds ||
                (it.taskNumber != null && it.taskNumber in updatedTaskNumbers)
        } + mergedTasks)
            .sortedWith(compareBy(nullsLast<Int>()) { it.taskNumber })
    return copy(
        loadState =
            ScreenLoadState.Content(
                content.copy(
                    messages = updatedMessages,
                    tasks = updatedTasks,
                    parentMessage = updatedParentMessage
                )
            )
    )
}

private fun Message.withRealtimeTask(tasks: List<TaskSummary>): Message {
    val task = tasks.firstOrNull { it.matchesMessage(this) } ?: return this
    return copy(task = task.withMessageTaskReference(task))
}

private fun TaskSummary.matchesMessage(message: Message): Boolean {
    val existingTask = message.task
    return messageId == message.id ||
            parentMessageId == message.id ||
            (existingTask != null && id.value == message.id.value) ||
            id == existingTask?.id ||
            (messageId != null && messageId == existingTask?.messageId) ||
            (parentMessageId != null && parentMessageId == existingTask?.parentMessageId) ||
            (taskNumber != null && taskNumber == existingTask?.taskNumber)
}

private fun TaskSummary.matchesTaskReference(referenceTask: TaskSummary): Boolean =
    id == referenceTask.id ||
        (messageId != null && messageId == referenceTask.messageId) ||
        (parentMessageId != null && parentMessageId == referenceTask.parentMessageId) ||
        (taskNumber != null && taskNumber == referenceTask.taskNumber) ||
        id.value == referenceTask.messageId?.value ||
        id.value == referenceTask.parentMessageId?.value

private fun TaskSummary.withMessageTaskReference(incomingTask: TaskSummary): TaskSummary =
    incomingTask.copy(
        title = incomingTask.title.ifBlank { title },
        taskNumber = incomingTask.taskNumber ?: taskNumber,
        messageId = incomingTask.messageId ?: messageId,
        parentMessageId = incomingTask.parentMessageId ?: parentMessageId,
        channelId = incomingTask.channelId ?: channelId,
        threadId = incomingTask.threadId ?: threadId,
        isThreadTask = incomingTask.isThreadTask || isThreadTask,
        assignee = incomingTask.assignee ?: assignee,
        channelName = incomingTask.channelName ?: channelName,
        updatedAtLabel = incomingTask.updatedAtLabel.ifBlank { updatedAtLabel },
        canClaim = incomingTask.canClaim
    )

data class ThreadReplyHeaderContext(
    val parentChannelName: String?,
    val parentMessagePreview: String?,
    val parentMessage: Message? = null,
    val parentMessageId: MessageId? = parentMessage?.id,
    val parentMessageRefreshing: Boolean = false,
    val parentMessageRefreshErrorCode: String? = null
)

data class ThreadParentJumpTarget(
    val parentMessageId: MessageId
)

enum class ThreadHeaderNavigationIcon {
    Back,
    Close
}

enum class MessagePageStatus {
    Idle,
    Loading,
    Error,
    EndReached
}

enum class ThreadRefreshStatus {
    Idle,
    Loading,
    Error
}

data class ComposerState(
    val value: String = "",
    val enabled: Boolean = true,
    val status: ComposerStatus = ComposerStatus.IdleEmpty,
    val asTask: Boolean = false,
    val attachmentIds: List<AttachmentId> = emptyList(),
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val errorCode: String? = null,
    val noPermissionCode: String? = null
) {
    val sending: Boolean
        get() = status == ComposerStatus.Sending

    val uploading: Boolean
        get() = status == ComposerStatus.Uploading

    val failed: Boolean
        get() = status == ComposerStatus.Failed

    val canSend: Boolean
        get() = enabled &&
            (status == ComposerStatus.Ready || status == ComposerStatus.Failed) &&
            noPermissionCode == null &&
            (value.isNotBlank() || attachmentIds.isNotEmpty() || pendingAttachments.isNotEmpty())
}

enum class ComposerStatus {
    IdleEmpty,
    Ready,
    Uploading,
    Sending,
    Failed,
    NoPermission
}

data class PendingAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String? = null,
    val sizeLabel: String = "",
    val status: PendingAttachmentStatus = PendingAttachmentStatus.Ready,
    val previewUri: String? = null
) {
    val isImage: Boolean
        get() = mimeType?.startsWith("image/") == true
}

enum class PendingAttachmentStatus {
    Ready,
    Uploading,
    Failed
}

internal val ComposerState.activityLabel: String?
    get() {
        val attachmentCount =
            pendingAttachments.size.takeIf { it > 0 }
                ?: attachmentIds.size.takeIf { it > 0 }
        if (attachmentCount != null) {
            val attachmentLabel = if (attachmentCount == 1) "attachment" else "attachments"
            return when (status) {
                ComposerStatus.IdleEmpty, ComposerStatus.Ready ->
                    "$attachmentCount $attachmentLabel ready"
                ComposerStatus.Uploading -> "Uploading $attachmentCount $attachmentLabel"
                ComposerStatus.Sending -> "Sending $attachmentCount $attachmentLabel"
                ComposerStatus.Failed -> "Send failed: ${errorCode ?: "retry"}"
                ComposerStatus.NoPermission ->
                    "No permission: ${noPermissionCode ?: "channel_readonly"}"
            }
        }
        return when (status) {
            ComposerStatus.Uploading -> "Uploading attachment"
            ComposerStatus.Sending -> "Sending message"
            ComposerStatus.Failed -> "Send failed: ${errorCode ?: "retry"}"
            ComposerStatus.NoPermission ->
                "No permission: ${noPermissionCode ?: "channel_readonly"}"
            ComposerStatus.IdleEmpty, ComposerStatus.Ready -> null
        }
    }

data class AttachmentOpenUiState(
    val status: AttachmentOpenStatus = AttachmentOpenStatus.Idle,
    val errorCode: String? = null
)

enum class AttachmentOpenStatus {
    Idle,
    Loading,
    Error
}

data class AttachmentPreviewUiState(
    val status: AttachmentPreviewStatus = AttachmentPreviewStatus.Idle,
    val attachmentId: AttachmentId? = null,
    val name: String? = null,
    val url: String? = null,
    val thumbnailUrl: String? = null,
    val mimeType: String? = null,
    val textContent: String? = null,
    val errorCode: String? = null
) {
    val isTextPreview: Boolean
        get() = textContent != null
}

enum class AttachmentPreviewStatus {
    Idle,
    Loading,
    Showing,
    Error
}

enum class ChannelJoinStatus {
    Idle,
    Joining,
    Failed,
    NoPermission
}

data class ThreadMembersPanelState(
    val loadState: ScreenLoadState<ThreadMembersPanelContent> = ScreenLoadState.Loading,
    val candidatesLoadState: ScreenLoadState<ThreadMembersPickerContent> = ScreenLoadState.Loading,
    val fallbackMemberCount: Int? = null,
    val fallbackMemberCountKnown: Boolean = false,
    val addStatus: ThreadMemberAddStatus = ThreadMemberAddStatus.Idle,
    val addErrorCode: String? = null,
    val removeStatus: ThreadMemberRemoveStatus = ThreadMemberRemoveStatus.Idle,
    val removingMemberId: MemberId? = null,
    val removeErrorCode: String? = null
)

data class ThreadMembersPanelContent(
    val members: List<MemberProfile>,
    val canAddMembers: Boolean = true
)

data class ThreadMembersPickerContent(
    val candidates: List<MemberProfile>
)

enum class ThreadMemberAddStatus {
    Idle,
    Adding,
    Failed,
    NoPermission
}

enum class ThreadMemberRemoveStatus {
    Idle,
    Removing,
    Failed,
    NoPermission
}

enum class ThreadTab {
    Chat,
    Tasks,
    Files
}
