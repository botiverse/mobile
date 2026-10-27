package build.raft.app.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import build.raft.app.model.ChannelId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Task v2 status transitions, mirrored from web `taskStatusUi.ts` `VALID_TRANSITIONS` (itself the
 * mirror of slock `taskService.ts` `VALID_TRANSITIONS`), so the menu only offers what the server
 * accepts.
 */
internal val TaskStatusValidTransitions: Map<TaskStatus, List<TaskStatus>> =
    mapOf(
        TaskStatus.Todo to listOf(TaskStatus.InProgress, TaskStatus.Closed),
        TaskStatus.InProgress to listOf(TaskStatus.InReview, TaskStatus.Done, TaskStatus.Closed),
        TaskStatus.InReview to listOf(TaskStatus.Done, TaskStatus.InProgress, TaskStatus.Closed),
        TaskStatus.Done to listOf(TaskStatus.Todo, TaskStatus.InProgress, TaskStatus.InReview, TaskStatus.Closed),
        TaskStatus.Closed to listOf(TaskStatus.Todo, TaskStatus.InProgress),
    )

/** One row of the status menu. [reopensToTodo]: web labels Closed -> Todo "Reopen to Todo". */
internal data class TaskStatusOption(val status: TaskStatus, val reopensToTodo: Boolean = false)

/**
 * Web `getTaskStatusOptions`: members get the current status and its legal transitions; a server
 * owner or admin (`deleteAnyTask`) may force any status.
 */
internal fun taskStatusOptions(current: TaskStatus, canManageServer: Boolean): List<TaskStatusOption> {
    val allowed =
        if (canManageServer) {
            listOf(TaskStatus.Todo, TaskStatus.InProgress, TaskStatus.InReview, TaskStatus.Done, TaskStatus.Closed)
        } else {
            listOf(current) + TaskStatusValidTransitions.getValue(current)
        }
    return allowed.map { status ->
        TaskStatusOption(status = status, reopensToTodo = current == TaskStatus.Closed && status == TaskStatus.Todo)
    }
}

/** slock `serverPermissions.ts`: owners and admins hold `deleteAnyTask` (web `canManageServer`). */
internal fun taskServerRoleCanManage(role: String?): Boolean =
    role?.trim()?.lowercase().let { it == "owner" || it == "admin" }

private fun taskServerRoleIsGuest(role: String?): Boolean = role?.trim()?.lowercase() == "guest"

/** Web `canEditTaskStatus`: any non-guest who can see the task, unless it is read-only (historical joint). */
internal fun TaskSummary.canEditStatus(serverRole: String?): Boolean =
    readOnlyReason == null && !taskServerRoleIsGuest(serverRole)

/**
 * Web TaskProperties `canEditAssignee` (`authorizeTaskAction({action: "assign"})`): not a guest, not
 * read-only, and not done (the server freezes a completed task's assignee).
 */
internal fun TaskSummary.canEditAssignee(serverRole: String?): Boolean =
    status != TaskStatus.Done && readOnlyReason == null && !taskServerRoleIsGuest(serverRole)

internal enum class TaskConversationRefStatus {
    Loading,
    Resolved,

    /** The channel is not a task conversation (or the row is partial): no task bar. */
    NotTask,
    Failed,
}

/**
 * The task conversation page's own state beside the task itself (which lives in the shared task
 * projection of its channel): which task and channel the conversation belongs to, the reader's
 * follow overrides, and whether the task was deleted while the page was open.
 */
internal data class TaskConversationHeaderState(
    val conversationChannelId: ChannelId,
    val ref: TaskConversationChannelRef? = null,
    val refStatus: TaskConversationRefStatus = TaskConversationRefStatus.Loading,
    /**
     * A follow or unfollow done on this page: wins over the task DTO's `conversationFollowed` until
     * the page closes (web `taskFollowOverrides`).
     */
    val followOverride: Boolean? = null,
    val followPending: Boolean = false,
    /** The task was deleted (`task:deleted`, or it left its channel's loaded task list). */
    val removed: Boolean = false,
) {
    /** The reader follows the conversation: this page's override, else the DTO's value (null = unknown). */
    fun followed(taskFollowed: Boolean?): Boolean? = followOverride ?: taskFollowed
}

internal sealed interface TaskConversationHeaderIntent {
    data class RefLoaded(val result: DataResult<TaskConversationChannelRef>) : TaskConversationHeaderIntent

    data class FollowStarted(val follow: Boolean) : TaskConversationHeaderIntent

    data class FollowSettled(val follow: Boolean, val accepted: Boolean) : TaskConversationHeaderIntent

    /** `task:deleted` for [taskId] (and, from a v2 server, the conversation it deleted). */
    data class TaskDeleted(val taskId: TaskId, val conversationChannelId: ChannelId?) : TaskConversationHeaderIntent

    /** The task's channel list loaded without the task: it is gone. */
    data object TaskMissing : TaskConversationHeaderIntent
}

internal fun TaskConversationHeaderState.reduce(intent: TaskConversationHeaderIntent): TaskConversationHeaderState =
    when (intent) {
        is TaskConversationHeaderIntent.RefLoaded ->
            when (val result = intent.result) {
                is DataResult.Content ->
                    if (result.data.conversationChannelId == conversationChannelId) {
                        copy(ref = result.data, refStatus = TaskConversationRefStatus.Resolved)
                    } else {
                        copy(refStatus = TaskConversationRefStatus.NotTask)
                    }
                is DataResult.Empty -> copy(refStatus = TaskConversationRefStatus.NotTask)
                is DataResult.Error,
                is DataResult.NoPermission -> copy(refStatus = TaskConversationRefStatus.Failed)
            }
        is TaskConversationHeaderIntent.FollowStarted -> copy(followPending = true)
        is TaskConversationHeaderIntent.FollowSettled ->
            copy(
                followPending = false,
                followOverride = if (intent.accepted) intent.follow else followOverride,
            )
        is TaskConversationHeaderIntent.TaskDeleted -> {
            val matches =
                ref?.taskId == intent.taskId || intent.conversationChannelId == conversationChannelId
            if (matches) copy(removed = true) else this
        }
        TaskConversationHeaderIntent.TaskMissing -> if (ref != null) copy(removed = true) else this
    }

internal sealed interface TaskConversationHeaderEvent {
    data class FollowFailed(val follow: Boolean, val code: String) : TaskConversationHeaderEvent
}

/**
 * Task v2 (web `TaskConversationBar` + `useTaskConversationFollow`): owns the task conversation's
 * parent context and its follow state. The task's status and assignee are the task projection's
 * ([SharedTasksViewModel] over the task's channel), so status/assignee writes and `task:updated`
 * folds share the Tasks board's path.
 */
internal class TaskConversationHeaderViewModel(
    conversationChannelId: ChannelId,
    private val loadRef: suspend (ChannelId) -> DataResult<TaskConversationChannelRef>,
    private val follow: suspend (ChannelId) -> DataResult<ThreadActionResult>,
    private val unfollow: suspend (ChannelId) -> DataResult<ThreadActionResult>,
    private val log: (String) -> Unit = {},
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope
        get() = scopeOverride ?: viewModelScope

    private val _state = MutableStateFlow(TaskConversationHeaderState(conversationChannelId))
    val state: StateFlow<TaskConversationHeaderState> = _state.asStateFlow()

    private val effects = MviEffectEmitter<TaskConversationHeaderEvent>()
    val events: Flow<TaskConversationHeaderEvent> = effects.effects

    private var refJob: Job? = null

    private fun dispatch(intent: TaskConversationHeaderIntent) {
        _state.update { it.reduce(intent) }
    }

    /** Resolves the conversation's task and channel once (again after a failure). */
    fun start() {
        val current = _state.value
        if (current.refStatus == TaskConversationRefStatus.Resolved || current.refStatus == TaskConversationRefStatus.NotTask) return
        if (refJob?.isActive == true) return
        refJob =
            scope.launch {
                val result = loadRef(current.conversationChannelId)
                log(
                    "task_conversation_header_ref conversationId=${current.conversationChannelId.value} " +
                        "result=${result::class.simpleName}"
                )
                dispatch(TaskConversationHeaderIntent.RefLoaded(result))
            }
    }

    /** Follows when [currentlyFollowed] is false, else unfollows; one request at a time. */
    fun toggleFollow(currentlyFollowed: Boolean) {
        val current = _state.value
        if (current.followPending || current.removed) return
        val next = !currentlyFollowed
        dispatch(TaskConversationHeaderIntent.FollowStarted(next))
        scope.launch {
            val conversation = current.conversationChannelId
            val result =
                try {
                    if (next) follow(conversation) else unfollow(conversation)
                } catch (cancellation: CancellationException) {
                    dispatch(TaskConversationHeaderIntent.FollowSettled(next, accepted = false))
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError(code = "task_conversation_follow_network", retryable = true))
                }
            val accepted = result is DataResult.Content || result is DataResult.Empty
            log(
                "task_conversation_follow conversationId=${conversation.value} follow=$next " +
                    "result=${result::class.simpleName}"
            )
            dispatch(TaskConversationHeaderIntent.FollowSettled(next, accepted))
            if (!accepted) {
                effects.emit(TaskConversationHeaderEvent.FollowFailed(next, result.failureCodeOrNull() ?: "task_conversation_follow_failed"))
            }
        }
    }

    fun onTaskDeleted(taskId: TaskId, conversationChannelId: ChannelId?) {
        dispatch(TaskConversationHeaderIntent.TaskDeleted(taskId, conversationChannelId))
    }

    fun onTaskMissing() {
        dispatch(TaskConversationHeaderIntent.TaskMissing)
    }
}

