package build.raft.app

import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SharedTasksDataStoreStatus
import build.raft.app.data.SharedTasksProjectionSlice
import build.raft.app.data.SharedTasksRouteIntent
import build.raft.app.data.TasksUiState
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary

internal data class ThreadLinkedTasksProjection(
    val authoritative: Boolean,
    val lookup: Map<String, TaskSummary>,
    /**
     * Task v2: the tasks listed under each message (by message id), in task-number order
     * ([threadSourceTaskLists]). A message here shows the list instead of its single task chip.
     */
    val sourceTaskLists: Map<String, List<TaskSummary>> = emptyMap(),
    /** The reader's user id: an assignee who is the reader reads "You" in those lists. */
    val viewerUserId: String? = null,
)

/**
 * Task v2 (slock web ChatPanel `tasksByMessageId` / `taskListsByMessageId`, ThreadPanel
 * `parentSourceTasks`): the tasks that hang under each message. A task hangs under its source
 * message (`sourceMessageId`) when task v2 is on for the server or the task was made the v2 way,
 * else under its pre-v2 host message; a channel-only task hangs under none. With the flag off only
 * a message with a v2 task under it keeps its list, so a pre-v2 task keeps its single chip.
 */
internal fun List<TaskSummary>.threadSourceTaskLists(taskV2Enabled: Boolean): Map<String, List<TaskSummary>> {
    val byMessage = LinkedHashMap<String, MutableList<TaskSummary>>()
    forEach { task ->
        if (task.isLegacy) return@forEach
        val source = task.sourceMessageId?.value?.trim()?.takeIf { it.isNotBlank() }
        val anchor =
            if ((taskV2Enabled || task.conversationAnchored) && source != null) {
                source
            } else {
                task.messageId?.value?.trim()?.takeIf { it.isNotBlank() && it != task.id.value }
            } ?: return@forEach
        byMessage.getOrPut(anchor) { mutableListOf() }.add(task)
    }
    return byMessage
        .filterValues { list -> taskV2Enabled || list.any { it.conversationAnchored } }
        .mapValues { (_, list) -> list.distinctBy { it.id }.sortedBy { it.taskNumber ?: Int.MAX_VALUE } }
}

/** The tasks listed under [message] (task v2), empty when it shows none. */
internal fun ThreadLinkedTasksProjection.sourceTasksFor(message: RaftThreadMessageItem): List<ThreadMessageSourceTaskItem> =
    sourceTaskLists[message.id].orEmpty().map { it.toThreadMessageSourceTaskItem(viewerUserId) }

/** A task listed under a message, looked up by id (the row's tap names the task). */
internal fun ThreadLinkedTasksProjection.sourceTaskById(taskId: String): TaskSummary? {
    val key = taskId.trim().takeIf { it.isNotBlank() } ?: return null
    return sourceTaskLists.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id.value == key } }
}

/**
 * What one row of the list under a message shows (slock web `TaskChipList`): the number, the
 * title, the size of the task's own conversation (none for a pre-v2 task, whose discussion is its
 * host message's thread), the assignee and the status.
 */
internal data class ThreadMessageSourceTaskItem(
    val taskId: String,
    val taskNumber: Int?,
    val title: String,
    val status: TaskStatus,
    val replyCount: Int,
    val assigneeId: String?,
    val assigneeKind: MemberKind?,
    val assigneeName: String?,
    /** The reader is the assignee (shown as "You"). */
    val assigneeIsViewer: Boolean,
)

internal fun TaskSummary.toThreadMessageSourceTaskItem(viewerUserId: String?): ThreadMessageSourceTaskItem {
    val person = assignee
    val assigneeId = person?.id?.value?.trim()?.takeIf { it.isNotBlank() }
    return ThreadMessageSourceTaskItem(
        taskId = id.value,
        taskNumber = taskNumber,
        title = title,
        status = status,
        replyCount = taskCardReplyCount() ?: 0,
        assigneeId = assigneeId,
        assigneeKind = person?.kind,
        assigneeName =
            person?.let { threadLinkedTaskAssigneeDisplayName() ?: it.displayName.trim().removePrefix("@").ifBlank { null } },
        assigneeIsViewer =
            person?.kind == MemberKind.Human && assigneeId != null && assigneeId == viewerUserId?.trim(),
    )
}

internal fun emptyThreadLinkedTasksProjection(): ThreadLinkedTasksProjection =
    ThreadLinkedTasksProjection(authoritative = false, lookup = emptyMap())

internal fun ThreadLinkedTasksProjection.statusUpdateIntent(
    taskId: String,
    status: TaskStatus,
): SharedTasksRouteIntent.UpdateStatus? =
    taskById(taskId)?.let { task -> SharedTasksRouteIntent.UpdateStatus(task, status) }

// Task #258: thread inline task chip opens the combined task detail panel; the
// assignee row mutates through the same typed SharedTasksRouteIntent seam the
// board's assignee pill uses (AssignTask with null assignee = unassigned).
internal fun ThreadLinkedTasksProjection.assignUpdateIntent(
    taskId: String,
    assignee: MemberProfile?,
): SharedTasksRouteIntent.AssignTask? =
    taskById(taskId)?.let { task -> SharedTasksRouteIntent.AssignTask(task, assignee) }

internal data class ThreadLinkedTaskReferences(
    val messageIds: Set<String>,
    val taskIds: Set<String>,
    val taskNumbers: Set<Int>
) {
    val isEmpty: Boolean
        get() = messageIds.isEmpty() && taskIds.isEmpty() && taskNumbers.isEmpty()
}

internal fun RaftThreadMessageItem.withLinkedTask(linkedTask: TaskSummary?): RaftThreadMessageItem {
    linkedTask ?: return this
    return copy(
        taskId = linkedTask.id.value,
        taskNumber = linkedTask.taskNumber ?: taskNumber,
        taskStatus = linkedTask.status.toWireValue(),
        taskAssigneeKind = linkedTask.assignee?.kind?.memberKindWireValue(),
        taskAssigneeId = linkedTask.assignee?.id?.value,
        taskAssigneeName = linkedTask.threadLinkedTaskAssigneeDisplayName()
    )
}

internal fun RaftThreadMessageItem.withLinkedTaskProjection(
    projection: ThreadLinkedTasksProjection
): RaftThreadMessageItem {
    val linkedTask = projection.lookup.threadLinkedTaskForMessage(this)
    return when {
        linkedTask != null -> withLinkedTask(linkedTask)
        projection.authoritative -> withoutLinkedTask()
        else -> this
    }
}

internal fun RaftThreadMessageItem.withoutLinkedTask(): RaftThreadMessageItem =
    if (
        taskId == null &&
        taskNumber == null &&
        taskStatus == null &&
        taskAssigneeKind == null &&
        taskAssigneeId == null &&
        taskAssigneeName == null
    ) {
        this
    } else {
        copy(
            taskId = null,
            taskNumber = null,
            taskStatus = null,
            taskAssigneeKind = null,
            taskAssigneeId = null,
            taskAssigneeName = null
        )
    }

internal fun RaftThreadScreenPayload.threadLinkedTaskReferences(): ThreadLinkedTaskReferences {
    val messages = buildList {
        originMessage?.let(::add)
        addAll(this@threadLinkedTaskReferences.messages)
    }
    return ThreadLinkedTaskReferences(
        messageIds = messages.mapTo(mutableSetOf()) { it.id },
        taskIds =
            messages.mapNotNullTo(mutableSetOf()) { message ->
                message.taskId?.trim()?.takeIf { it.isNotBlank() }
            },
        taskNumbers = messages.mapNotNullTo(mutableSetOf()) { it.taskNumber }
    )
}

internal fun TasksUiState.threadLinkedTasksProjection(
    references: ThreadLinkedTaskReferences
): ThreadLinkedTasksProjection =
    when (val state = loadState) {
        is ScreenLoadState.Content ->
            ThreadLinkedTasksProjection(
                authoritative = true,
                lookup = state.data.tasks.threadLinkedTasksByMessageLookup(references)
            )
        is ScreenLoadState.Empty ->
            ThreadLinkedTasksProjection(authoritative = true, lookup = emptyMap())
        else ->
            ThreadLinkedTasksProjection(authoritative = false, lookup = emptyMap())
    }

internal fun SharedTasksProjectionSlice.threadLinkedTasksProjection(
    references: ThreadLinkedTaskReferences
): ThreadLinkedTasksProjection =
    when (status) {
        SharedTasksDataStoreStatus.Loaded ->
            ThreadLinkedTasksProjection(
                authoritative = true,
                lookup = tasks.threadLinkedTasksByMessageLookup(references)
            )
        SharedTasksDataStoreStatus.Partial ->
            ThreadLinkedTasksProjection(
                authoritative = false,
                lookup = tasks.threadLinkedTasksByMessageLookup(references)
            )
        SharedTasksDataStoreStatus.Idle ->
            ThreadLinkedTasksProjection(authoritative = false, lookup = emptyMap())
    }

internal fun threadLinkedTasksProjection(
    channelProjectionSlice: SharedTasksProjectionSlice,
    serverProjectionSlice: SharedTasksProjectionSlice,
    references: ThreadLinkedTaskReferences
): ThreadLinkedTasksProjection {
    val channelProjection = channelProjectionSlice.threadLinkedTasksProjection(references)
    if (channelProjectionSlice.isExactScopeLoaded) return channelProjection
    val unscopedServerLookup =
        serverProjectionSlice.tasks
            .filter { task -> task.channelId == null && task.channelName.isNullOrBlank() }
            .threadLinkedTasksByMessageLookup(references)
    return channelProjection.copy(
        lookup = unscopedServerLookup + channelProjection.lookup
    )
}

internal fun threadLinkedTasksLookup(
    scopedProjection: ThreadLinkedTasksProjection,
    sharedStoreProjection: ThreadLinkedTasksProjection
): ThreadLinkedTasksProjection =
    when {
        scopedProjection.authoritative && scopedProjection.lookup.isNotEmpty() ->
            scopedProjection
        sharedStoreProjection.authoritative && sharedStoreProjection.lookup.isNotEmpty() ->
            sharedStoreProjection
        scopedProjection.authoritative ->
            scopedProjection
        sharedStoreProjection.authoritative ->
            sharedStoreProjection
        else ->
            ThreadLinkedTasksProjection(authoritative = false, lookup = emptyMap())
    }

internal fun TasksUiState.threadLinkedTasksByMessageId(): Map<String, TaskSummary> {
    val content = (loadState as? ScreenLoadState.Content)?.data ?: return emptyMap()
    return content.tasks.threadLinkedTasksByMessageId()
}

internal fun List<TaskSummary>.threadLinkedTasksByMessageId(): Map<String, TaskSummary> =
    mapNotNull { task ->
        val messageId = task.messageId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        messageId to task
    }.toMap()

private const val ThreadTaskIdLookupPrefix = "task-id:"
private const val ThreadTaskNumberLookupPrefix = "task-number:"

internal fun ThreadLinkedTasksProjection.taskById(taskId: String): TaskSummary? {
    val key = taskId.trim().takeIf { it.isNotBlank() } ?: return null
    return lookup[ThreadTaskIdLookupPrefix + key]
        ?: lookup[key]?.takeIf { task -> task.id.value == key }
}

internal enum class ThreadTaskOpenRecoveryReason(
    val diagnosticValue: String,
) {
    ParentOutsideLoadedWindow("parent_outside_loaded_window"),
    StaleProvenance("stale_provenance"),
    TaskIdentityUnavailable("task_identity_unavailable"),
}

internal sealed interface ThreadTaskOpenArbitration {
    data object UseLoadedParent : ThreadTaskOpenArbitration

    data class OpenCanonical(
        val task: TaskSummary,
        val reason: ThreadTaskOpenRecoveryReason,
    ) : ThreadTaskOpenArbitration

    data class FailClosed(
        val reason: ThreadTaskOpenRecoveryReason,
    ) : ThreadTaskOpenArbitration
}

internal fun arbitrateThreadTaskOpen(
    loadedParentAvailable: Boolean,
    loadedParentProvenanceAccepted: Boolean,
    canonicalTask: TaskSummary?,
): ThreadTaskOpenArbitration {
    val recoveryReason =
        when {
            !loadedParentAvailable -> ThreadTaskOpenRecoveryReason.ParentOutsideLoadedWindow
            !loadedParentProvenanceAccepted -> ThreadTaskOpenRecoveryReason.StaleProvenance
            else -> return ThreadTaskOpenArbitration.UseLoadedParent
        }
    return canonicalTask
        ?.let { task -> ThreadTaskOpenArbitration.OpenCanonical(task, recoveryReason) }
        ?: ThreadTaskOpenArbitration.FailClosed(
            if (recoveryReason == ThreadTaskOpenRecoveryReason.ParentOutsideLoadedWindow) {
                ThreadTaskOpenRecoveryReason.TaskIdentityUnavailable
            } else {
                recoveryReason
            }
        )
}

internal inline fun ThreadTaskOpenArbitration.dispatch(
    onUseLoadedParent: () -> Unit,
    onOpenCanonical: (TaskSummary, ThreadTaskOpenRecoveryReason) -> Unit,
    onFailClosed: (ThreadTaskOpenRecoveryReason) -> Unit,
) {
    when (this) {
        ThreadTaskOpenArbitration.UseLoadedParent -> onUseLoadedParent()
        is ThreadTaskOpenArbitration.OpenCanonical -> onOpenCanonical(task, reason)
        is ThreadTaskOpenArbitration.FailClosed -> onFailClosed(reason)
    }
}

private fun <K, V> MutableMap<K, V>.putLinkedTaskIfAbsent(key: K, value: V) {
    if (!containsKey(key)) {
        put(key, value)
    }
}

internal fun List<TaskSummary>.threadLinkedTasksByMessageLookup(): Map<String, TaskSummary> =
    buildMap {
        this@threadLinkedTasksByMessageLookup.forEach { task ->
            task.messageId?.value?.trim()?.takeIf { it.isNotBlank() }?.let { messageId ->
                put(messageId, task)
            }
            task.id.value.trim().takeIf { it.isNotBlank() }?.let { taskId ->
                putLinkedTaskIfAbsent(taskId, task)
                putLinkedTaskIfAbsent(ThreadTaskIdLookupPrefix + taskId, task)
            }
            task.taskNumber?.let { taskNumber ->
                putLinkedTaskIfAbsent(ThreadTaskNumberLookupPrefix + taskNumber, task)
            }
        }
    }

internal fun List<TaskSummary>.threadLinkedTasksByMessageLookup(
    references: ThreadLinkedTaskReferences
): Map<String, TaskSummary> {
    if (references.isEmpty) return emptyMap()
    return filter { task -> task.matchesThreadLinkedTaskReferences(references) }
        .threadLinkedTasksByMessageLookup()
}

private fun TaskSummary.matchesThreadLinkedTaskReferences(references: ThreadLinkedTaskReferences): Boolean =
    messageId?.value?.trim()?.takeIf { it.isNotBlank() }?.let { it in references.messageIds } == true ||
        id.value.trim().takeIf { it.isNotBlank() }?.let { it in references.messageIds } == true ||
        id.value.trim().takeIf { it.isNotBlank() }?.let { it in references.taskIds } == true ||
        taskNumber?.let { it in references.taskNumbers } == true

internal fun Map<String, TaskSummary>.threadLinkedTaskForMessage(message: RaftThreadMessageItem): TaskSummary? =
    get(message.id) ?: message.taskId?.trim()?.takeIf { it.isNotBlank() }?.let { taskId ->
        get(ThreadTaskIdLookupPrefix + taskId)
    } ?: message.taskNumber?.let { taskNumber ->
        get(ThreadTaskNumberLookupPrefix + taskNumber)
    }

/**
 * Thread-wide task status by task number for inline `#N` chips in this thread's Markdown
 * (web MessageItem `taskByNumber`): server tasks, then channel tasks (channel wins). The message's
 * own linked task is layered on per message body by `RaftMarkdownOwnTaskStatusScope`, as on web.
 *
 * Returns `null` until the channel or the server task slice has loaded, so task refs keep the
 * legacy chip instead of flashing the grey "unknown task" badge before turning status-coloured.
 */
internal fun threadMarkdownTaskStatusesByNumber(
    channelTasks: List<TaskSummary>,
    serverTasks: List<TaskSummary>,
    loaded: Boolean,
): Map<Int, TaskStatus>? {
    if (!loaded) return null
    val statuses = LinkedHashMap<Int, TaskStatus>()
    serverTasks.forEach { task -> task.taskNumber?.takeIf { it > 0 }?.let { statuses[it] = task.status } }
    channelTasks.forEach { task -> task.taskNumber?.takeIf { it > 0 }?.let { statuses[it] = task.status } }
    return statuses
}

/**
 * Either task slice feeding the thread's inline task chips has loaded at least once. A slice that
 * was demoted to [SharedTasksDataStoreStatus.Partial] (canonical conflict, refetch pending) but
 * still holds tasks counts as loaded, so chips do not flip back to the legacy style meanwhile.
 */
internal fun threadMarkdownTaskSourceLoaded(
    channelProjectionSlice: SharedTasksProjectionSlice,
    serverProjectionSlice: SharedTasksProjectionSlice,
): Boolean = channelProjectionSlice.hasUsableTasks() || serverProjectionSlice.hasUsableTasks()

private fun SharedTasksProjectionSlice.hasUsableTasks(): Boolean =
    status == SharedTasksDataStoreStatus.Loaded ||
        (status == SharedTasksDataStoreStatus.Partial && tasks.isNotEmpty())
