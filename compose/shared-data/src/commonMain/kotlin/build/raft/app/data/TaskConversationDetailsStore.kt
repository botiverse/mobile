package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One read of the task head: not asked yet / in flight / answered / failed. */
@RaftCoreInternalApi sealed interface TaskDetailLoad<out T> {
    data object Idle : TaskDetailLoad<Nothing>

    data object Loading : TaskDetailLoad<Nothing>

    data class Loaded<T>(val value: T) : TaskDetailLoad<T>

    data class Failed(val traceId: String? = null) : TaskDetailLoad<Nothing>
}

@RaftCoreInternalApi val <T> TaskDetailLoad<T>.valueOrNull: T?
    get() = (this as? TaskDetailLoad.Loaded<T>)?.value

/**
 * The task head's reads beside the task (web `TaskConversationDialog`): the task's history, its
 * source message and a pre-v2 task's earlier discussion, each keyed by what it was read for, so a
 * late answer for another task or message is dropped.
 */
@RaftCoreInternalApi data class TaskConversationDetailsState(
    val taskId: TaskId? = null,
    /** The task revision the history was last asked for (a `task:updated` bumps it: read again). */
    val historyRevision: Long? = null,
    /**
     * What the history was last asked for when the task has no `revision` (an older server or
     * frame): its mutable facts. A change to any of them reads the history again.
     */
    val historyStamp: String? = null,
    val history: TaskDetailLoad<List<TaskHistoryEvent>> = TaskDetailLoad.Idle,
    val sourceMessageId: MessageId? = null,
    val source: TaskDetailLoad<TaskSourceMessagePreview> = TaskDetailLoad.Idle,
    val hostMessageId: MessageId? = null,
    val earlierDiscussion: TaskDetailLoad<TaskEarlierDiscussion> = TaskDetailLoad.Idle,
)

/** Web `taskSourceDisplay` (`packages/web/src/utils/taskConversation.ts`): Source row state. */
@RaftCoreInternalApi sealed interface TaskHeadSourceDisplay {
    data class Message(val messageId: MessageId) : TaskHeadSourceDisplay

    data object NotRecorded : TaskHeadSourceDisplay
}

/**
 * Web `taskSourceDisplay`: when the server reports `sourceRecorded`, show the source message when
 * recorded or `"Not recorded"` when not. For older servers lacking `sourceRecorded`, omit the row
 * when there is no source message or when a pre-v2 task's source IS its host (Earlier discussion
 * already leads there).
 */
@RaftCoreInternalApi fun TaskSummary.taskHeadSourceDisplay(): TaskHeadSourceDisplay? {
    if (sourceRecorded != null) {
        return if (sourceRecorded && sourceMessageId != null) {
            TaskHeadSourceDisplay.Message(sourceMessageId)
        } else {
            TaskHeadSourceDisplay.NotRecorded
        }
    }
    val hostId = hostMessageId ?: messageId
    val sourceId = sourceMessageId ?: return null
    if (sourceId == hostId) return null
    return TaskHeadSourceDisplay.Message(sourceId)
}

/**
 * Web `TaskSourceProperty`'s loadable source message id, or null when the Source row is omitted or
 * shows `"Not recorded"`.
 */
@RaftCoreInternalApi fun TaskSummary.taskHeadSourceMessageId(): MessageId? =
    (taskHeadSourceDisplay() as? TaskHeadSourceDisplay.Message)?.messageId

/** Web `TaskEarlierDiscussionProperty`: shown for a host message unless it is known to have no replies. */
@RaftCoreInternalApi fun TaskConversationDetailsState.earlierDiscussionVisible(): Boolean =
    hostMessageId != null && (earlierDiscussion.valueOrNull?.replyCount ?: 1) > 0

/**
 * A task without a `revision`: the facts a history event changes (status, assignee, title,
 * description) and its update time. Null when the task has a revision (that is compared instead).
 */
@RaftCoreInternalApi fun TaskSummary.taskHistoryStamp(): String? =
    if (revision != null) {
        null
    } else {
        listOf(status.toWireValue(), assignee?.kind?.name, assignee?.id?.value, title, description, updatedAtLabel)
            .joinToString("\u0001") { it.orEmpty() }
    }

@RaftCoreInternalApi sealed interface TaskConversationDetailsIntent {
    data class HistoryStarted(val taskId: TaskId, val revision: Long?, val stamp: String? = null) : TaskConversationDetailsIntent

    data class HistoryLoaded(val taskId: TaskId, val result: DataResult<List<TaskHistoryEvent>>) : TaskConversationDetailsIntent

    data class SourceStarted(val messageId: MessageId?) : TaskConversationDetailsIntent

    data class SourceLoaded(val messageId: MessageId, val result: DataResult<TaskSourceMessagePreview>) : TaskConversationDetailsIntent

    data class EarlierStarted(val hostMessageId: MessageId?) : TaskConversationDetailsIntent

    data class EarlierLoaded(val hostMessageId: MessageId, val result: DataResult<TaskEarlierDiscussion>) : TaskConversationDetailsIntent
}

@RaftCoreInternalApi fun TaskConversationDetailsState.reduce(intent: TaskConversationDetailsIntent): TaskConversationDetailsState =
    when (intent) {
        is TaskConversationDetailsIntent.HistoryStarted ->
            copy(
                taskId = intent.taskId,
                historyRevision = intent.revision,
                historyStamp = intent.stamp,
                // A refresh keeps the rows on screen; another task starts empty.
                history = if (taskId == intent.taskId && history is TaskDetailLoad.Loaded) history else TaskDetailLoad.Loading,
            )
        is TaskConversationDetailsIntent.HistoryLoaded ->
            if (intent.taskId != taskId) {
                this
            } else {
                when (val result = intent.result) {
                    is DataResult.Content -> copy(history = TaskDetailLoad.Loaded(result.data))
                    is DataResult.Empty -> copy(history = TaskDetailLoad.Loaded(emptyList()))
                    // A refresh that fails keeps what was shown (web shows the error only with nothing loaded).
                    is DataResult.Error,
                    is DataResult.NoPermission ->
                        if (history is TaskDetailLoad.Loaded) this else copy(history = TaskDetailLoad.Failed(result.failureTraceIdOrNull()))
                }
            }
        is TaskConversationDetailsIntent.SourceStarted ->
            copy(sourceMessageId = intent.messageId, source = if (intent.messageId == null) TaskDetailLoad.Idle else TaskDetailLoad.Loading)
        is TaskConversationDetailsIntent.SourceLoaded ->
            if (intent.messageId != sourceMessageId) {
                this
            } else {
                copy(
                    source =
                        (intent.result as? DataResult.Content)?.let { TaskDetailLoad.Loaded(it.data) }
                            ?: TaskDetailLoad.Failed(intent.result.failureTraceIdOrNull())
                )
            }
        is TaskConversationDetailsIntent.EarlierStarted ->
            copy(
                hostMessageId = intent.hostMessageId,
                earlierDiscussion = if (intent.hostMessageId == null) TaskDetailLoad.Idle else TaskDetailLoad.Loading,
            )
        is TaskConversationDetailsIntent.EarlierLoaded ->
            if (intent.hostMessageId != hostMessageId) {
                this
            } else {
                copy(
                    earlierDiscussion =
                        (intent.result as? DataResult.Content)?.let { TaskDetailLoad.Loaded(it.data) }
                            ?: TaskDetailLoad.Failed(intent.result.failureTraceIdOrNull())
                )
            }
    }

/**
 * Loads the task head's reads for the task the page shows ([onTask], every time the task changes):
 * the history once per task and again for each newer revision (web refreshes an open dialog's
 * history on `task:updated`), single-flight with one coalesced re-read; the source message and the
 * earlier discussion once per message.
 */
@RaftCoreInternalApi class TaskConversationDetailsViewModel(
    private val loadHistory: suspend (TaskId) -> DataResult<List<TaskHistoryEvent>>,
    private val loadSource: suspend (ChannelId, MessageId) -> DataResult<TaskSourceMessagePreview>,
    private val loadEarlierDiscussion: suspend (ChannelId, MessageId) -> DataResult<TaskEarlierDiscussion>,
    private val log: (String) -> Unit = {},
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope
        get() = scopeOverride ?: viewModelScope

    private val _state = MutableStateFlow(TaskConversationDetailsState())
    val state: StateFlow<TaskConversationDetailsState> = _state.asStateFlow()

    private var historyJob: Job? = null
    private var historyDirty = false

    private fun dispatch(intent: TaskConversationDetailsIntent) {
        _state.update { it.reduce(intent) }
    }

    fun onTask(task: TaskSummary) {
        val current = _state.value
        val channelId = task.channelId
        val stamp = task.taskHistoryStamp()
        val changed =
            if (task.revision != null) {
                current.historyRevision == null || task.revision > current.historyRevision
            } else {
                stamp != current.historyStamp
            }
        if (task.id != current.taskId || changed) {
            dispatch(TaskConversationDetailsIntent.HistoryStarted(task.id, task.revision, stamp))
            requestHistory(task.id)
        }
        val sourceId = task.taskHeadSourceMessageId()
        if (sourceId != current.sourceMessageId || (sourceId != null && current.source is TaskDetailLoad.Idle)) {
            dispatch(TaskConversationDetailsIntent.SourceStarted(sourceId))
            if (sourceId != null && channelId != null) {
                scope.launch {
                    val result = guarded("task_source_failed") { loadSource(channelId, sourceId) }
                    log("task_head_source messageId=${sourceId.value} result=${result::class.simpleName}")
                    dispatch(TaskConversationDetailsIntent.SourceLoaded(sourceId, result))
                }
            }
        }
        val hostId = task.hostMessageId
        if (hostId != current.hostMessageId || (hostId != null && current.earlierDiscussion is TaskDetailLoad.Idle)) {
            dispatch(TaskConversationDetailsIntent.EarlierStarted(hostId))
            if (hostId != null && channelId != null) {
                scope.launch {
                    val result = guarded("task_earlier_discussion_failed") { loadEarlierDiscussion(channelId, hostId) }
                    log("task_head_earlier_discussion hostMessageId=${hostId.value} result=${result::class.simpleName}")
                    dispatch(TaskConversationDetailsIntent.EarlierLoaded(hostId, result))
                }
            }
        }
    }

    /** Reads the history again after a failure (the History section's Retry). */
    fun retryHistory() {
        val current = _state.value
        val taskId = current.taskId ?: return
        dispatch(TaskConversationDetailsIntent.HistoryStarted(taskId, current.historyRevision, current.historyStamp))
        requestHistory(taskId)
    }

    private fun requestHistory(taskId: TaskId) {
        historyDirty = true
        if (historyJob?.isActive == true) return
        historyJob =
            scope.launch {
                while (historyDirty) {
                    historyDirty = false
                    val target = _state.value.taskId ?: taskId
                    val result = guarded("task_history_failed") { loadHistory(target) }
                    log("task_head_history taskId=${target.value} result=${result::class.simpleName}")
                    dispatch(TaskConversationDetailsIntent.HistoryLoaded(target, result))
                }
            }
    }

    private suspend fun <T> guarded(code: String, block: suspend () -> DataResult<T>): DataResult<T> =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DataResult.Error(ScreenError(code = code, retryable = true, traceId = null))
        }
}
