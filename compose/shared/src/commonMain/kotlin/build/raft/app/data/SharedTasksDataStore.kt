package build.raft.app.data

import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal enum class SharedTasksDataStoreStatus {
    Idle,
    Partial,
    Loaded
}

internal data class SharedTasksProjectionSlice(
    val status: SharedTasksDataStoreStatus = SharedTasksDataStoreStatus.Idle,
    val tasks: List<TaskSummary> = emptyList()
)

private data class SharedTasksChannelKey(
    val serverId: String,
    val channelId: String? = null,
    val channelName: String? = null
)

private data class SharedTasksDataStoreState(
    val serverSnapshots: Map<String, TaskSnapshot> = emptyMap(),
    val serverSnapshotStatuses: Map<String, SharedTasksDataStoreStatus> = emptyMap(),
    val channelSnapshots: Map<SharedTasksChannelKey, TaskSnapshot> = emptyMap(),
    val channelSnapshotStatuses: Map<SharedTasksChannelKey, SharedTasksDataStoreStatus> = emptyMap()
)

internal class SharedTasksDataStore {
    private val state = MutableStateFlow(SharedTasksDataStoreState())

    fun publishSnapshot(
        serverId: String,
        scope: SharedTasksScope,
        snapshot: TaskSnapshot
    ) {
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return
        state.value =
            when (scope) {
                SharedTasksScope.Server ->
                    state.value.copy(
                        serverSnapshots = state.value.serverSnapshots + (normalizedServerId to snapshot),
                        serverSnapshotStatuses =
                            state.value.serverSnapshotStatuses +
                                (normalizedServerId to SharedTasksDataStoreStatus.Loaded)
                    )
                is SharedTasksScope.Channel -> {
                    val channelKey =
                        SharedTasksChannelKey(
                            serverId = normalizedServerId,
                            channelId = scope.channelId.value.normalizedTasksStoreId()
                        )
                    state.value.copy(
                        channelSnapshots =
                            state.value.channelSnapshots +
                                (channelKey to snapshot),
                        channelSnapshotStatuses =
                            state.value.channelSnapshotStatuses +
                                (channelKey to SharedTasksDataStoreStatus.Loaded)
                    )
                }
            }
    }

    fun upsertTask(serverId: String, task: TaskSummary) {
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return
        val taskChannelId = task.channelId?.value.normalizedTasksStoreId()
        val taskChannelName = task.channelName.normalizedTasksChannelName()
        val current = state.value
        val serverSnapshot =
            (current.serverSnapshots[normalizedServerId] ?: TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap()))
                .upsert(task)
        val serverSnapshotStatus =
            current.serverSnapshotStatuses[normalizedServerId]
                ?: SharedTasksDataStoreStatus.Partial
        val taskChannelKey =
            when {
                taskChannelId != null ->
                    SharedTasksChannelKey(serverId = normalizedServerId, channelId = taskChannelId)
                taskChannelName != null ->
                    SharedTasksChannelKey(serverId = normalizedServerId, channelName = taskChannelName)
                else -> null
            }
        state.value =
            current.copy(
                serverSnapshots =
                    current.serverSnapshots + (normalizedServerId to serverSnapshot),
                serverSnapshotStatuses =
                    current.serverSnapshotStatuses + (normalizedServerId to serverSnapshotStatus),
                channelSnapshots =
                    current.channelSnapshots
                        .mapValues { (key, snapshot) ->
                            if (
                                key.serverId == normalizedServerId &&
                                (
                                    (taskChannelId != null && key.channelId == taskChannelId) ||
                                        (taskChannelName != null && key.channelName == taskChannelName) ||
                                        snapshot.tasks.any { currentTask -> currentTask.id == task.id }
                                    )
                            ) {
                                snapshot.upsert(task)
                            } else {
                                snapshot
                            }
                        }
                        .let { snapshots ->
                            if (
                                taskChannelKey == null ||
                                snapshots.containsKey(taskChannelKey) ||
                                serverSnapshotStatus == SharedTasksDataStoreStatus.Loaded
                            ) {
                                snapshots
                            } else {
                                snapshots + (
                                    taskChannelKey to
                                        TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap()).upsert(task)
                                    )
                            }
                        },
                channelSnapshotStatuses =
                    current.channelSnapshotStatuses
                        .let { statuses ->
                            if (
                                taskChannelKey == null ||
                                statuses.containsKey(taskChannelKey) ||
                                serverSnapshotStatus == SharedTasksDataStoreStatus.Loaded
                            ) {
                                statuses
                            } else {
                                statuses + (taskChannelKey to serverSnapshotStatus)
                            }
                        }
            )
    }

    fun removeTask(serverId: String, taskId: TaskId) {
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return
        val current = state.value
        state.value =
            current.copy(
                serverSnapshots =
                    current.serverSnapshots.mapValues { (snapshotServerId, snapshot) ->
                        if (snapshotServerId == normalizedServerId) snapshot.remove(taskId) else snapshot
                    },
                channelSnapshots =
                    current.channelSnapshots.mapValues { (key, snapshot) ->
                        if (key.serverId == normalizedServerId) snapshot.remove(taskId) else snapshot
                    }
            )
    }

    fun channelProjectionFlow(
        serverId: String?,
        channelId: String?,
        channelName: String?
    ): Flow<SharedTasksProjectionSlice> =
        state
            .map { current -> current.selectChannelProjection(serverId, channelId, channelName) }
            .distinctUntilChanged()

    fun projectionFlow(
        serverId: String?,
        scope: SharedTasksScope
    ): Flow<SharedTasksProjectionSlice> =
        when (scope) {
            SharedTasksScope.Server ->
                state
                    .map { current -> current.selectServerProjection(serverId) }
                    .distinctUntilChanged()
            is SharedTasksScope.Channel ->
                channelProjectionFlow(
                    serverId = serverId,
                    channelId = scope.channelId.value,
                    channelName = null
                )
        }

    fun projection(
        serverId: String?,
        scope: SharedTasksScope
    ): SharedTasksProjectionSlice =
        when (scope) {
            SharedTasksScope.Server -> state.value.selectServerProjection(serverId)
            is SharedTasksScope.Channel ->
                state.value.selectChannelProjection(
                    serverId = serverId,
                    channelId = scope.channelId.value,
                    channelName = null
                )
        }

    private fun SharedTasksDataStoreState.selectServerProjection(serverId: String?): SharedTasksProjectionSlice {
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return SharedTasksProjectionSlice()
        val snapshot = serverSnapshots[normalizedServerId] ?: return SharedTasksProjectionSlice()
        return SharedTasksProjectionSlice(
            status = serverSnapshotStatuses[normalizedServerId] ?: SharedTasksDataStoreStatus.Partial,
            tasks = snapshot.tasks
        )
    }

    private fun SharedTasksDataStoreState.selectChannelProjection(
        serverId: String?,
        channelId: String?,
        channelName: String?
    ): SharedTasksProjectionSlice {
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return SharedTasksProjectionSlice()
        val normalizedChannelId = channelId.normalizedTasksStoreId()
        val normalizedChannelName = channelName.normalizedTasksChannelName()
        val exactChannelSnapshot =
            channelSnapshots[
                SharedTasksChannelKey(
                    serverId = normalizedServerId,
                    channelId = normalizedChannelId
                )
            ]
        val exactChannelKey =
            SharedTasksChannelKey(
                serverId = normalizedServerId,
                channelId = normalizedChannelId
            )
        val exactChannelStatus = channelSnapshotStatuses[exactChannelKey]
        if (exactChannelSnapshot != null) {
            if (exactChannelStatus == SharedTasksDataStoreStatus.Loaded) {
                return SharedTasksProjectionSlice(
                    status = SharedTasksDataStoreStatus.Loaded,
                    tasks = exactChannelSnapshot.tasks
                )
            }
        }
        val serverSnapshot = serverSnapshots[normalizedServerId]
        val serverStatus = serverSnapshotStatuses[normalizedServerId]
        if (serverSnapshot == null) {
            return if (exactChannelSnapshot == null) {
                SharedTasksProjectionSlice()
            } else {
                SharedTasksProjectionSlice(
                    status = exactChannelStatus ?: SharedTasksDataStoreStatus.Partial,
                    tasks = exactChannelSnapshot.tasks
                )
            }
        }
        val channelTasks =
            serverSnapshot.tasks.filter { task ->
                val taskChannelId = task.channelId?.value.normalizedTasksStoreId()
                val taskChannelName = task.channelName.normalizedTasksChannelName()
                (normalizedChannelId != null && taskChannelId == normalizedChannelId) ||
                    (normalizedChannelName != null && taskChannelName == normalizedChannelName)
            }
        val projectedTasks =
            exactChannelSnapshot?.tasks?.fold(channelTasks) { tasks, task ->
                TaskSnapshot(tasks = tasks, summaryCounts = emptyMap()).upsert(task).tasks
            } ?: channelTasks
        return SharedTasksProjectionSlice(
            status = serverStatus ?: SharedTasksDataStoreStatus.Partial,
            tasks = projectedTasks
        )
    }
}

private fun String?.normalizedTasksStoreId(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }

private fun String?.normalizedTasksChannelName(): String? =
    this
        ?.trim()
        ?.removePrefix("#")
        ?.takeIf { it.isNotBlank() }

private fun TaskSnapshot.upsert(task: TaskSummary): TaskSnapshot {
    val nextTasks =
        if (tasks.any { it.id == task.id }) {
            tasks.map { current -> if (current.id == task.id) task else current }
        } else {
            tasks + task
        }
    return copy(tasks = nextTasks, summaryCounts = nextTasks.summaryCounts())
}

private fun TaskSnapshot.remove(taskId: TaskId): TaskSnapshot {
    val nextTasks = tasks.filterNot { task -> task.id == taskId }
    if (nextTasks.size == tasks.size) return this
    return copy(tasks = nextTasks, summaryCounts = nextTasks.summaryCounts())
}
