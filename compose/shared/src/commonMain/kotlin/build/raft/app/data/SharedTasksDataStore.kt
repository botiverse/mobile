package build.raft.app.data

import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

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
    val channelSnapshotStatuses: Map<SharedTasksChannelKey, SharedTasksDataStoreStatus> = emptyMap(),
    val mutationRevision: Long = 0L,
    val taskMutations: Map<SharedTasksTaskKey, SharedTasksTaskMutation> = emptyMap()
)

private data class SharedTasksTaskKey(
    val serverId: String,
    val taskId: TaskId
)

private data class SharedTasksTaskMutation(
    val revision: Long,
    val task: TaskSummary?
)

private data class SharedTasksSnapshotLoadKey(
    val serverId: String,
    val scope: SharedTasksScope
)

internal class SharedTasksDataStore(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val ownerIdentityProvider: (suspend () -> String?)? = null
) {
    private val state = MutableStateFlow(SharedTasksDataStoreState())
    private val loadMutex = Mutex()
    private val loadsInFlight =
        mutableMapOf<SharedTasksSnapshotLoadKey, Deferred<DataResult<TaskSnapshot>>>()
    private var ownerIdentityBound = false
    private var ownerIdentity: String? = null
    private var ownerGeneration: Long = 0L

    suspend fun bindCurrentOwnerIdentity() {
        val identityProvider = ownerIdentityProvider ?: return
        loadMutex.withLock {
            // Read under the same lock that installs the binding. Otherwise an
            // older A read can pause, B can bind, then A can resume and rebind
            // stale identity over B.
            val nextIdentity = identityProvider()
            if (ownerIdentityBound && ownerIdentity == nextIdentity) return@withLock
            clearOwnerDataLocked()
            ownerIdentity = nextIdentity
            ownerIdentityBound = true
        }
    }

    suspend fun clearOwnerData() {
        loadMutex.withLock {
            clearOwnerDataLocked()
            ownerIdentity = null
            ownerIdentityBound = false
        }
    }

    private fun clearOwnerDataLocked() {
        ownerGeneration += 1L
        loadsInFlight.values.forEach { it.cancel() }
        loadsInFlight.clear()
        state.value = SharedTasksDataStoreState()
    }

    fun publishSnapshot(
        serverId: String,
        scope: SharedTasksScope,
        snapshot: TaskSnapshot,
        requestRevision: Long? = null
    ): TaskSnapshot? {
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return null
        var committedSnapshot: TaskSnapshot? = null
        state.update { current ->
            val mergedSnapshot =
                if (requestRevision == null) {
                    snapshot
                } else {
                    current.mergeTaskMutationsAfter(
                        serverId = normalizedServerId,
                        scope = scope,
                        snapshot = snapshot,
                        requestRevision = requestRevision
                    )
                }
            committedSnapshot = mergedSnapshot
            when (scope) {
                SharedTasksScope.Server ->
                    current.copy(
                        serverSnapshots = current.serverSnapshots + (normalizedServerId to mergedSnapshot),
                        serverSnapshotStatuses =
                            current.serverSnapshotStatuses +
                                (normalizedServerId to SharedTasksDataStoreStatus.Loaded)
                    )
                is SharedTasksScope.Channel -> {
                    val channelKey =
                        SharedTasksChannelKey(
                            serverId = normalizedServerId,
                            channelId = scope.channelId.value.normalizedTasksStoreId()
                        )
                    current.copy(
                        channelSnapshots =
                            current.channelSnapshots +
                                (channelKey to mergedSnapshot),
                        channelSnapshotStatuses =
                            current.channelSnapshotStatuses +
                                (channelKey to SharedTasksDataStoreStatus.Loaded)
                    )
                }
            }
        }
        return committedSnapshot
    }

    suspend fun loadSnapshot(
        serverId: String,
        scope: SharedTasksScope,
        forceRefresh: Boolean = false,
        fetch: suspend () -> DataResult<TaskSnapshot>
    ): DataResult<TaskSnapshot> {
        bindCurrentOwnerIdentity()
        val normalizedServerId = serverId.normalizedTasksStoreId()
            ?: return DataResult.Error(ScreenError(code = "selected_server_missing", retryable = false))
        if (!forceRefresh) {
            projection(normalizedServerId, scope).loadedSnapshotOrNull()?.let { return DataResult.Content(it) }
        }
        val key = SharedTasksSnapshotLoadKey(normalizedServerId, scope)
        var created: Deferred<DataResult<TaskSnapshot>>? = null
        var immediate: TaskSnapshot? = null
        val deferred =
            loadMutex.withLock {
                loadsInFlight[key]
                    ?: if (!forceRefresh) {
                        projection(normalizedServerId, scope).loadedSnapshotOrNull()?.let { snapshot ->
                            immediate = snapshot
                            null
                        } ?: createSnapshotLoad(key, fetch).also { candidate ->
                            loadsInFlight[key] = candidate
                            created = candidate
                        }
                    } else {
                        createSnapshotLoad(key, fetch).also { candidate ->
                            loadsInFlight[key] = candidate
                            created = candidate
                        }
                    }
            }
        immediate?.let { return DataResult.Content(it) }
        val shared = deferred ?: error("shared tasks snapshot load missing")
        created?.start()
        return shared.await()
    }

    private fun createSnapshotLoad(
        key: SharedTasksSnapshotLoadKey,
        fetch: suspend () -> DataResult<TaskSnapshot>
    ): Deferred<DataResult<TaskSnapshot>> {
        val requestRevision = state.value.mutationRevision
        val requestOwnerGeneration = ownerGeneration
        return scope.async(start = CoroutineStart.LAZY) {
            try {
                val result = fetch()
                val currentJob = coroutineContext[Job]
                loadMutex.withLock {
                    if (
                        ownerGeneration != requestOwnerGeneration ||
                        loadsInFlight[key] !== currentJob
                    ) {
                        return@withLock result
                    }
                    when (result) {
                        is DataResult.Content -> {
                            val committed =
                                publishSnapshot(key.serverId, key.scope, result.data, requestRevision)
                                    ?: return@withLock result
                            if (committed.tasks.isEmpty()) {
                                DataResult.Empty(EmptyReason.NoTasks)
                            } else {
                                DataResult.Content(committed)
                            }
                        }
                        is DataResult.Empty -> {
                            val committed =
                                publishSnapshot(
                                    key.serverId,
                                    key.scope,
                                    TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap()),
                                    requestRevision
                                ) ?: return@withLock result
                            if (committed.tasks.isEmpty()) result else DataResult.Content(committed)
                        }
                        is DataResult.Error -> result
                        is DataResult.NoPermission -> result
                    }
                }
            } finally {
                val currentJob = coroutineContext[Job]
                loadMutex.withLock {
                    if (loadsInFlight[key] === currentJob) {
                        loadsInFlight.remove(key)
                    }
                }
            }
        }
    }

    fun upsertTask(serverId: String, task: TaskSummary) {
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return
        val taskChannelId = task.channelId?.value.normalizedTasksStoreId()
        val taskChannelName = task.channelName.normalizedTasksChannelName()
        state.update { current ->
            val nextRevision = current.mutationRevision + 1L
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
            current.copy(
                serverSnapshots =
                    current.serverSnapshots + (normalizedServerId to serverSnapshot),
                serverSnapshotStatuses =
                    current.serverSnapshotStatuses + (normalizedServerId to serverSnapshotStatus),
                channelSnapshots =
                    current.channelSnapshots
                        .mapValues { (key, snapshot) ->
                            if (key.serverId != normalizedServerId) {
                                return@mapValues snapshot
                            }
                            val isTargetChannel =
                                (taskChannelId != null && key.channelId == taskChannelId) ||
                                    (taskChannelName != null && key.channelName == taskChannelName)
                            when {
                                isTargetChannel -> snapshot.upsert(task)
                                snapshot.tasks.any { currentTask -> currentTask.id == task.id } ->
                                    snapshot.remove(task.id)
                                else -> snapshot
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
                        },
                mutationRevision = nextRevision,
                taskMutations =
                    current.taskMutations +
                        (SharedTasksTaskKey(normalizedServerId, task.id) to
                            SharedTasksTaskMutation(nextRevision, task))
            )
        }
    }

    fun removeTask(serverId: String, taskId: TaskId) {
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return
        state.update { current ->
            val nextRevision = current.mutationRevision + 1L
            current.copy(
                serverSnapshots =
                    current.serverSnapshots.mapValues { (snapshotServerId, snapshot) ->
                        if (snapshotServerId == normalizedServerId) snapshot.remove(taskId) else snapshot
                    },
                channelSnapshots =
                    current.channelSnapshots.mapValues { (key, snapshot) ->
                        if (key.serverId == normalizedServerId) snapshot.remove(taskId) else snapshot
                    },
                mutationRevision = nextRevision,
                taskMutations =
                    current.taskMutations +
                        (SharedTasksTaskKey(normalizedServerId, taskId) to
                            SharedTasksTaskMutation(nextRevision, null))
            )
        }
    }

    fun channelProjectionFlow(
        serverId: String?,
        channelId: String?,
        channelName: String?
    ): Flow<SharedTasksProjectionSlice> =
        flow {
            bindCurrentOwnerIdentity()
            emitAll(
                state
                    .map { current -> current.selectChannelProjection(serverId, channelId, channelName) }
                    .distinctUntilChanged()
            )
        }

    fun projectionFlow(
        serverId: String?,
        scope: SharedTasksScope
    ): Flow<SharedTasksProjectionSlice> =
        when (scope) {
            SharedTasksScope.Server ->
                flow {
                    bindCurrentOwnerIdentity()
                    emitAll(
                        state
                            .map { current -> current.selectServerProjection(serverId) }
                            .distinctUntilChanged()
                    )
                }
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

    private fun SharedTasksDataStoreState.mergeTaskMutationsAfter(
        serverId: String,
        scope: SharedTasksScope,
        snapshot: TaskSnapshot,
        requestRevision: Long
    ): TaskSnapshot =
        taskMutations
            .asSequence()
            .filter { (key, mutation) -> key.serverId == serverId && mutation.revision > requestRevision }
            .sortedBy { (_, mutation) -> mutation.revision }
            .fold(snapshot) { merged, (key, mutation) ->
                val task = mutation.task
                when {
                    task == null -> merged.remove(key.taskId)
                    scope == SharedTasksScope.Server -> merged.upsert(task)
                    scope is SharedTasksScope.Channel && task.channelId == scope.channelId -> merged.upsert(task)
                    else -> merged.remove(key.taskId)
                }
            }
}

private fun SharedTasksProjectionSlice.loadedSnapshotOrNull(): TaskSnapshot? =
    if (status == SharedTasksDataStoreStatus.Loaded) {
        TaskSnapshot(tasks = tasks, summaryCounts = tasks.summaryCounts())
    } else {
        null
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
