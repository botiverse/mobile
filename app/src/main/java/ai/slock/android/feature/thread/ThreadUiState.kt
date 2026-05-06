package ai.slock.android.feature.thread

import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.state.ScreenLoadState

data class ThreadUiState(
    val loadState: ScreenLoadState<ThreadContent>,
    val composerState: ComposerState = ComposerState(),
    val selectedTab: ThreadTab = ThreadTab.Chat
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
    val highlightedMessageId: MessageId? = null
)

enum class MessagePageStatus {
    Idle,
    Loading,
    Error,
    EndReached
}

data class ComposerState(
    val value: String = "",
    val enabled: Boolean = true,
    val status: ComposerStatus = ComposerStatus.IdleEmpty,
    val asTask: Boolean = false,
    val attachmentIds: List<AttachmentId> = emptyList(),
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
            status == ComposerStatus.Ready &&
            noPermissionCode == null &&
            (value.isNotBlank() || attachmentIds.isNotEmpty())
}

enum class ComposerStatus {
    IdleEmpty,
    Ready,
    Uploading,
    Sending,
    Failed,
    NoPermission
}

enum class ThreadTab {
    Chat,
    Tasks
}
