package ai.slock.compose

import ai.slock.compose.data.ScreenLoadState
import ai.slock.compose.data.SharedTasksDataStoreStatus
import ai.slock.compose.data.SharedTasksProjectionSlice
import ai.slock.compose.data.TasksUiState
import ai.slock.compose.model.TaskSummary

internal data class ThreadLinkedTasksProjection(
    val authoritative: Boolean,
    val lookup: Map<String, TaskSummary>
)

internal data class ThreadLinkedTaskReferences(
    val messageIds: Set<String>,
    val taskIds: Set<String>,
    val taskNumbers: Set<Int>
) {
    val isEmpty: Boolean
        get() = messageIds.isEmpty() && taskIds.isEmpty() && taskNumbers.isEmpty()
}

internal fun KuiklyThreadMessageItem.withLinkedTask(linkedTask: TaskSummary?): KuiklyThreadMessageItem {
    linkedTask ?: return this
    return copy(
        taskId = linkedTask.id.value,
        taskNumber = linkedTask.taskNumber ?: taskNumber,
        taskStatus = linkedTask.status.name,
        taskAssigneeId = linkedTask.assignee?.id?.value,
        taskAssigneeName = linkedTask.threadLinkedTaskAssigneeDisplayName()
    )
}

internal fun KuiklyThreadMessageItem.withoutLinkedTask(): KuiklyThreadMessageItem =
    if (taskId == null && taskNumber == null && taskStatus == null && taskAssigneeId == null && taskAssigneeName == null) {
        this
    } else {
        copy(
            taskId = null,
            taskNumber = null,
            taskStatus = null,
            taskAssigneeId = null,
            taskAssigneeName = null
        )
    }

internal fun KuiklyThreadScreenPayload.threadLinkedTaskReferences(): ThreadLinkedTaskReferences {
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
        SharedTasksDataStoreStatus.Idle ->
            ThreadLinkedTasksProjection(authoritative = false, lookup = emptyMap())
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

internal fun Map<String, TaskSummary>.threadLinkedTaskForMessage(message: KuiklyThreadMessageItem): TaskSummary? =
    get(message.id) ?: message.taskId?.trim()?.takeIf { it.isNotBlank() }?.let { taskId ->
        get(ThreadTaskIdLookupPrefix + taskId)
    } ?: message.taskNumber?.let { taskNumber ->
        get(ThreadTaskNumberLookupPrefix + taskNumber)
    }

internal fun KuiklyThreadScreenPayload.withThreadLinkedTasksProjection(
    projection: ThreadLinkedTasksProjection
): KuiklyThreadScreenPayload {
    if (!projection.authoritative && projection.lookup.isEmpty()) return this
    val linkedTasksByMessageLookup = projection.lookup
    val projectedMessages =
        messages.map { message ->
            val linkedTask = linkedTasksByMessageLookup.threadLinkedTaskForMessage(message)
            when {
                linkedTask != null -> message.withLinkedTask(linkedTask)
                projection.authoritative -> message.withoutLinkedTask()
                else -> message
            }
        }
    val projectedOriginMessage =
        originMessage?.let { message ->
            val linkedTask = linkedTasksByMessageLookup.threadLinkedTaskForMessage(message)
            when {
                linkedTask != null -> message.withLinkedTask(linkedTask)
                projection.authoritative -> message.withoutLinkedTask()
                else -> message
            }
        }
    return copy(
        messages = projectedMessages,
        originMessage = projectedOriginMessage
    )
}
