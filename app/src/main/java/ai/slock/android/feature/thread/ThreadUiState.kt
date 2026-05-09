package ai.slock.android.feature.thread

import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.withPreviewMembershipFallback
import ai.slock.android.core.model.withTitleFallback
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.data.repository.ThreadSnapshot
import ai.slock.android.feature.tasks.TasksUiState
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.state.ScreenLoadState

data class ThreadUiState(
    val loadState: ScreenLoadState<ThreadContent>,
    val composerState: ComposerState = ComposerState(),
    val selectedTab: ThreadTab = ThreadTab.Chat,
    val tasksState: TasksUiState? = null,
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
        .sortedBy { it.seq ?: Long.MAX_VALUE }
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

fun ThreadUiState.mergeRealtimeTaskEvent(event: RealtimeEvent): ThreadUiState =
    when (event) {
        is RealtimeEvent.TaskCreated -> mergeRealtimeTasks(event.tasks)
        is RealtimeEvent.TaskUpdated -> mergeRealtimeTasks(listOf(event.task))
        else -> this
    }

private fun ThreadUiState.mergeRealtimeTasks(tasks: List<TaskSummary>): ThreadUiState {
    if (tasks.isEmpty()) return this
    val contentState = loadState as? ScreenLoadState.Content ?: return this
    val content = contentState.data
    val updatedMessages = content.messages.map { message -> message.withRealtimeTask(tasks) }
    val updatedParentMessage = content.parentMessage?.withRealtimeTask(tasks)
    val updatedTasks =
        (content.tasks + tasks)
            .distinctBy { it.id }
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
    return copy(task = task)
}

private fun TaskSummary.matchesMessage(message: Message): Boolean =
    messageId == message.id ||
        parentMessageId == message.id ||
        id == message.task?.id

data class ThreadReplyHeaderContext(
    val parentChannelName: String?,
    val parentMessagePreview: String?,
    val parentMessage: Message? = null
)

data class ThreadParentJumpTarget(
    val parentMessageId: MessageId
)

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
    val errorCode: String? = null
)

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
    Tasks
}
