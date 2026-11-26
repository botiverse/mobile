package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Acting on selected tasks at once (the Tasks page's selection bar; web `tasksBatch.ts`,
 * botiverse/slock #13852). Calls the per-task APIs a few in flight ([TASKS_BATCH_CONCURRENCY]);
 * there is no batch endpoint. Tasks the viewer may not change are skipped, and any the server
 * refuses stay selected so the viewer can try again.
 */

/** Maximum number of per-task writes in flight at once (web `BATCH_CONCURRENCY`). */
@RaftCoreInternalApi const val TASKS_BATCH_CONCURRENCY = 4

@RaftCoreInternalApi sealed interface TasksBatchAction {
    data class Status(val status: TaskStatus) : TasksBatchAction

    data class Assignee(val assignee: MemberProfile?) : TasksBatchAction

    data object Follow : TasksBatchAction

    data object Delete : TasksBatchAction
}

/**
 * Who is acting, for batch action permissions (web `useBatchViewer.ts` / `BatchViewer`).
 * - [canAssign]: server capability `assignTasks`, or a Guest (who assigns in their own channel).
 * - [canDeleteAny]: server capability `deleteAnyTask` (`owner` or `admin`).
 * - [canWriteChannel]: whether the viewer may change tasks in a channel ([canWriteTasksInChannel]).
 */
@RaftCoreInternalApi data class TasksBatchViewer(
    val userId: String?,
    val canAssign: Boolean = true,
    val canDeleteAny: Boolean = false,
    val canWriteChannel: (ChannelId?) -> Boolean = { true },
)

/**
 * Builds [TasksBatchViewer] from the current user id, server role, and channel write check
 * (web `useBatchViewer.ts` + `serverPermissions.ts`).
 */
@RaftCoreInternalApi fun tasksBatchViewer(
    userId: String?,
    serverRole: String?,
    canWriteChannel: (ChannelId?) -> Boolean = { true },
): TasksBatchViewer {
    val normalizedRole = serverRole?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val normalizedUserId = userId?.trim()?.takeIf { it.isNotEmpty() }
    val canAssign =
        normalizedRole == null ||
            normalizedRole == "owner" ||
            normalizedRole == "admin" ||
            normalizedRole == "member" ||
            normalizedRole == "guest"
    val canDeleteAny = normalizedRole == "owner" || normalizedRole == "admin"
    return TasksBatchViewer(
        userId = normalizedUserId,
        canAssign = canAssign,
        canDeleteAny = canDeleteAny,
        canWriteChannel = canWriteChannel,
    )
}

@RaftCoreInternalApi data class TasksBatchOutcome(
    /** Tasks the action reached (or that already were in the requested state). */
    val done: Int,
    /** Tasks it left: not the viewer's to change, or refused by the server. */
    val skipped: Int,
    /** Tasks the server refused or that failed on the way: they stay selected to try again. */
    val failedIds: List<String>,
    /** First server/network refusal encountered, for trace IDs and error banners. */
    val firstError: ScreenError? = null,
)

/**
 * Whether [action] may be applied to [task] by [viewer]: mirrors web `canApplyBatchAction`
 * (`tasksBatch.ts`), matching the rule of the same single-task action and the server:
 * - Status: anyone who may write the task (`canEditStatus`), except moving an unassigned Todo to
 *   In Progress (which on a single task is the mover's claim; a batch claims none of them).
 * - Assignee: [TasksBatchViewer.canAssign] on a task not done and writable (`canEditAssignee`).
 * - Delete: its human creator, or [TasksBatchViewer.canDeleteAny], on a writable non-read-only task.
 * - Follow: anyone who sees it.
 * Status, assignee and delete all require write access to the task's channel. Never a legacy task.
 */
@RaftCoreInternalApi fun canApplyTasksBatchAction(
    action: TasksBatchAction,
    task: TaskSummary,
    viewer: TasksBatchViewer,
): Boolean {
    if (task.isLegacy) return false
    if (action is TasksBatchAction.Follow) return true
    if (!viewer.canWriteChannel(task.channelId)) return false
    return when (action) {
        is TasksBatchAction.Status -> {
            if (action.status == TaskStatus.InProgress && task.status == TaskStatus.Todo && task.assignee == null) {
                false
            } else {
                task.canEditStatus(canWriteChannel = true)
            }
        }
        is TasksBatchAction.Assignee -> viewer.canAssign && task.canEditAssignee(canWriteChannel = true)
        TasksBatchAction.Delete -> {
            val viewerId = viewer.userId?.trim()?.takeIf { it.isNotEmpty() }
            val isCreator =
                viewerId != null &&
                    task.creator?.kind == MemberKind.Human &&
                    task.creator.id.value == viewerId
            task.readOnlyReason == null && (isCreator || viewer.canDeleteAny)
        }
        TasksBatchAction.Follow -> true
    }
}

/** Whether [task] is already in the state [action] would set: counts as done without a write. */
@RaftCoreInternalApi fun isTasksBatchNoop(action: TasksBatchAction, task: TaskSummary): Boolean =
    when (action) {
        is TasksBatchAction.Status -> task.status == action.status
        is TasksBatchAction.Assignee -> {
            val target = action.assignee
            if (target == null) {
                task.assignee == null
            } else {
                task.assignee?.kind == target.kind && task.assignee?.id == target.id
            }
        }
        TasksBatchAction.Follow,
        TasksBatchAction.Delete -> false
    }

/**
 * Direct conversation channel id to follow without calling `POST /tasks/:id/conversation` first
 * (web `directTaskConversationChannelId`): joint channels resolve their local projection via
 * `POST /tasks/:id/conversation`, so this returns null for them.
 */
@RaftCoreInternalApi fun TaskSummary.directTaskConversationChannelIdOrNull(): ChannelId? {
    if (channelType.equals("joint", ignoreCase = true)) return null
    return conversationChannelId?.takeIf { it.value.isNotBlank() }
}

/**
 * Applies [action] to [tasks]: the ones [viewer] may change, each with its own request (up to
 * [concurrency] at a time); the rest, and any the server refuses, are skipped.
 * [perform] returns `null` on success or a [ScreenError] on failure/refusal.
 */
@RaftCoreInternalApi suspend fun runTasksBatch(
    action: TasksBatchAction,
    tasks: List<TaskSummary>,
    viewer: TasksBatchViewer,
    concurrency: Int = TASKS_BATCH_CONCURRENCY,
    perform: suspend (TasksBatchAction, TaskSummary) -> ScreenError?,
): TasksBatchOutcome {
    if (tasks.isEmpty()) {
        return TasksBatchOutcome(done = 0, skipped = 0, failedIds = emptyList())
    }
    val permitted = tasks.mapIndexedNotNull { index, task ->
        if (canApplyTasksBatchAction(action, task, viewer)) index to task else null
    }
    if (permitted.isEmpty()) {
        return TasksBatchOutcome(done = 0, skipped = tasks.size, failedIds = emptyList())
    }
    val lock = Mutex()
    var next = 0
    var done = 0
    val failed = mutableListOf<Pair<Int, String>>()
    var firstError: ScreenError? = null
    val workerCount = minOf(concurrency.coerceAtLeast(1), permitted.size)
    coroutineScope {
        List(workerCount) {
            async {
                while (true) {
                    val entry =
                        lock.withLock {
                            if (next < permitted.size) permitted[next++] else null
                        } ?: break
                    val (originalIndex, task) = entry
                    if (isTasksBatchNoop(action, task)) {
                        lock.withLock { done += 1 }
                        continue
                    }
                    val error =
                        try {
                            perform(action, task)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Throwable) {
                            ScreenError(code = "tasks_batch_network", retryable = true)
                        }
                    lock.withLock {
                        if (error == null) {
                            done += 1
                        } else {
                            failed += originalIndex to task.id.value
                            if (firstError == null) firstError = error
                        }
                    }
                }
            }
        }.awaitAll()
    }
    val orderedFailedIds = failed.sortedBy { it.first }.map { it.second }
    return TasksBatchOutcome(
        done = done,
        skipped = tasks.size - permitted.size + orderedFailedIds.size,
        failedIds = orderedFailedIds,
        firstError = firstError,
    )
}
