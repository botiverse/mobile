package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskSummary
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
    val isExactScopeLoaded: Boolean = false,
    val tasks: List<TaskSummary> = emptyList(),
    val needsCanonicalRefetch: Boolean = false
)

internal data class SharedTaskNotificationRoute(
    val serverId: String,
    val channelId: ChannelId
)

/** One personal task fact keyed by its canonical message, with every local open route retained. */
internal data class SharedTaskNotificationProjection(
    val taskId: TaskId,
    val messageId: MessageId,
    val revision: Long?,
    val routes: List<SharedTaskNotificationRoute>
)

private data class SharedTasksChannelKey(
    val serverId: String,
    val channelId: String? = null,
    val channelName: String? = null
)

private data class SharedTasksDataStoreState(
    val ownerIdentityBound: Boolean = false,
    val ownerIdentity: String? = null,
    val serverSnapshots: Map<String, TaskSnapshot> = emptyMap(),
    val serverSnapshotStatuses: Map<String, SharedTasksDataStoreStatus> = emptyMap(),
    val serverSnapshotRevisions: Map<String, Long> = emptyMap(),
    val authoritativeServerSnapshotRevisions: Map<String, Long> = emptyMap(),
    val channelSnapshots: Map<SharedTasksChannelKey, TaskSnapshot> = emptyMap(),
    val channelSnapshotStatuses: Map<SharedTasksChannelKey, SharedTasksDataStoreStatus> = emptyMap(),
    val channelSnapshotRevisions: Map<SharedTasksChannelKey, Long> = emptyMap(),
    val projectionRevision: Long = 0L,
    val mutationRevision: Long = 0L,
    val taskMutations: Map<SharedTasksTaskKey, SharedTasksTaskMutation> = emptyMap(),
    val canonicalTaskFacts: Map<TaskId, TaskSummary> = emptyMap(),
    val taskProjectionRoutes: Map<SharedTasksTaskKey, SharedTasksProjectionRoute> = emptyMap(),
    val canonicalConflicts: Set<TaskId> = emptySet()
)

private data class SharedTasksTaskKey(
    val serverId: String,
    val taskId: TaskId
)

private data class SharedTasksTaskMutation(
    val revision: Long,
    val task: TaskSummary?
)

private data class SharedTasksProjectionRoute(
    val channelId: ChannelId?,
    val channelName: String?
)

internal enum class SharedTaskFactFoldOutcome {
    Accepted,
    StaleIgnored,
    Conflict
}

private data class SharedTasksSnapshotLoadKey(
    val serverId: String,
    val scope: SharedTasksScope
)

private data class SharedTaskFactFold(
    val state: SharedTasksDataStoreState,
    val projectedTask: TaskSummary?,
    val outcome: SharedTaskFactFoldOutcome
)

private fun SharedTasksDataStoreState.foldCanonicalTask(
    serverId: String,
    incoming: TaskSummary,
    authoritativeRefresh: Boolean = false
): SharedTaskFactFold {
    val key = SharedTasksTaskKey(serverId, incoming.id)
    val incomingRoute =
        SharedTasksProjectionRoute(
            channelId = incoming.channelId,
            channelName = incoming.channelName
        )
    val nextRoutes =
        if (incomingRoute.channelId != null || !incomingRoute.channelName.isNullOrBlank()) {
            taskProjectionRoutes + (key to incomingRoute)
        } else {
            taskProjectionRoutes
        }
    val route = nextRoutes[key]
    val incomingFact = incoming.withoutTaskProjectionRoute()
    val currentFact = canonicalTaskFacts[incoming.id]
    val currentlyConflicted = incoming.id in canonicalConflicts
    val revisionOrder = incomingFact.revision.compareTaskRevision(currentFact?.revision)
    val currentServerHasRoute = key in taskProjectionRoutes
    val comparableRevisions = currentFact?.revision != null && incomingFact.revision != null
    val equalRevisionConflict =
        currentFact != null &&
            revisionOrder == 0 &&
            (comparableRevisions || !currentServerHasRoute) &&
            currentFact != incomingFact

    if (equalRevisionConflict && !(authoritativeRefresh && currentlyConflicted)) {
        val affectedServerIds =
            serverSnapshots
                .filterValues { snapshot -> snapshot.tasks.any { task -> task.id == incoming.id } }
                .keys
        val affectedChannelKeys =
            channelSnapshots
                .filterValues { snapshot -> snapshot.tasks.any { task -> task.id == incoming.id } }
                .keys
        val conflictState =
            copy(
                taskProjectionRoutes = nextRoutes,
                canonicalConflicts = canonicalConflicts + incoming.id,
                serverSnapshots = serverSnapshots.removeTaskFromAll(incoming.id),
                channelSnapshots = channelSnapshots.removeTaskFromAll(incoming.id),
                serverSnapshotStatuses =
                    serverSnapshotStatuses.mapValues { (serverId, status) ->
                        if (serverId in affectedServerIds) SharedTasksDataStoreStatus.Partial else status
                    },
                channelSnapshotStatuses =
                    channelSnapshotStatuses.mapValues { (channelKey, status) ->
                        if (channelKey in affectedChannelKeys) SharedTasksDataStoreStatus.Partial else status
                    }
            )
        return SharedTaskFactFold(conflictState, projectedTask = null, outcome = SharedTaskFactFoldOutcome.Conflict)
    }

    val acceptsIncoming =
        currentFact == null ||
            revisionOrder > 0 ||
            (currentFact.revision == null && incomingFact.revision == null && currentServerHasRoute) ||
            (authoritativeRefresh && currentlyConflicted && revisionOrder == 0)
    if (currentlyConflicted && !acceptsIncoming) {
        return SharedTaskFactFold(
            state = copy(taskProjectionRoutes = nextRoutes),
            projectedTask = null,
            outcome = SharedTaskFactFoldOutcome.Conflict
        )
    }
    val selectedFact = if (acceptsIncoming) incomingFact else currentFact
    val nextFacts = if (acceptsIncoming) canonicalTaskFacts + (incoming.id to selectedFact) else canonicalTaskFacts
    val nextConflicts = if (acceptsIncoming) canonicalConflicts - incoming.id else canonicalConflicts
    val reprojectedServerSnapshots =
        if (acceptsIncoming) serverSnapshots.reprojectServerTask(selectedFact, nextRoutes) else serverSnapshots
    val reprojectedChannelSnapshots =
        if (acceptsIncoming) channelSnapshots.reprojectChannelTask(selectedFact, nextRoutes) else channelSnapshots
    val nextState =
        copy(
            canonicalTaskFacts = nextFacts,
            taskProjectionRoutes = nextRoutes,
            canonicalConflicts = nextConflicts,
            serverSnapshots = reprojectedServerSnapshots,
            channelSnapshots = reprojectedChannelSnapshots
        )
    return SharedTaskFactFold(
        state = nextState,
        projectedTask = selectedFact.withTaskProjectionRoute(route),
        outcome = if (acceptsIncoming) SharedTaskFactFoldOutcome.Accepted else SharedTaskFactFoldOutcome.StaleIgnored
    )
}

private fun Long?.compareTaskRevision(existing: Long?): Int =
    when {
        existing == null && this == null -> 0
        existing == null -> 1
        this == null -> -1
        else -> compareTo(existing)
    }

private fun TaskSummary.withoutTaskProjectionRoute(): TaskSummary =
    copy(channelId = null, channelName = null)

private fun TaskSummary.withTaskProjectionRoute(route: SharedTasksProjectionRoute?): TaskSummary =
    copy(
        channelId = route?.channelId,
        channelName = route?.channelName
    )

private fun Map<String, TaskSnapshot>.reprojectServerTask(
    fact: TaskSummary,
    routes: Map<SharedTasksTaskKey, SharedTasksProjectionRoute>
): Map<String, TaskSnapshot> =
    mapValues { (serverId, snapshot) ->
        if (snapshot.tasks.none { it.id == fact.id }) {
            snapshot
        } else {
            snapshot.upsert(fact.withTaskProjectionRoute(routes[SharedTasksTaskKey(serverId, fact.id)]))
        }
    }

private fun Map<SharedTasksChannelKey, TaskSnapshot>.reprojectChannelTask(
    fact: TaskSummary,
    routes: Map<SharedTasksTaskKey, SharedTasksProjectionRoute>
): Map<SharedTasksChannelKey, TaskSnapshot> =
    mapValues { (key, snapshot) ->
        if (snapshot.tasks.none { it.id == fact.id }) {
            snapshot
        } else {
            snapshot.upsert(fact.withTaskProjectionRoute(routes[SharedTasksTaskKey(key.serverId, fact.id)]))
        }
    }

private fun <K> Map<K, TaskSnapshot>.removeTaskFromAll(taskId: TaskId): Map<K, TaskSnapshot> =
    mapValues { (_, snapshot) -> snapshot.remove(taskId) }

private fun SharedTasksDataStoreState.reconcileProjectionRoutes(
    serverId: String,
    scope: SharedTasksScope,
    taskIds: Set<TaskId>
): SharedTasksDataStoreState {
    val retainedRoutes =
        taskProjectionRoutes.filter { (key, route) ->
            when {
                key.serverId != serverId -> true
                key.taskId in taskIds -> true
                scope == SharedTasksScope.Server -> false
                scope is SharedTasksScope.Channel -> route.channelId != scope.channelId
                else -> true
            }
        }
    if (retainedRoutes.size == taskProjectionRoutes.size) return this
    val routedTaskIds = retainedRoutes.keys.mapTo(mutableSetOf()) { key -> key.taskId }
    return copy(
        taskProjectionRoutes = retainedRoutes,
        canonicalTaskFacts = canonicalTaskFacts.filterKeys { taskId -> taskId in routedTaskIds },
        canonicalConflicts = canonicalConflicts.filterTo(mutableSetOf()) { taskId -> taskId in routedTaskIds }
    )
}

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
            state.value =
                state.value.copy(
                    ownerIdentityBound = true,
                    ownerIdentity = nextIdentity
                )
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
        requestRevision: Long? = null,
        requestServerSnapshotRevision: Long? = null
    ): TaskSnapshot? {
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return null
        var committedSnapshot: TaskSnapshot? = null
        state.update { current ->
            var base =
                current.reconcileProjectionRoutes(
                    serverId = normalizedServerId,
                    scope = scope,
                    taskIds = snapshot.tasks.mapTo(mutableSetOf()) { task -> task.id }
                )
            val canonicalTasks =
                snapshot.tasks.mapNotNull { task ->
                    val fold = base.foldCanonicalTask(normalizedServerId, task, authoritativeRefresh = true)
                    base = fold.state
                    fold.projectedTask
                }
            val canonicalSnapshot =
                snapshot.copy(
                    tasks = canonicalTasks,
                    summaryCounts = canonicalTasks.summaryCounts()
                )
            val nextProjectionRevision = base.projectionRevision + 1L
            val mergedSnapshot =
                if (requestRevision == null) {
                    canonicalSnapshot
                } else {
                    base.mergeTaskMutationsAfter(
                        serverId = normalizedServerId,
                        scope = scope,
                        snapshot = canonicalSnapshot,
                        requestRevision = requestRevision
                    )
                }
            when (scope) {
                SharedTasksScope.Server -> {
                    committedSnapshot = mergedSnapshot
                    base.copy(
                        serverSnapshots = base.serverSnapshots + (normalizedServerId to mergedSnapshot),
                        serverSnapshotStatuses =
                            base.serverSnapshotStatuses +
                                (normalizedServerId to SharedTasksDataStoreStatus.Loaded),
                        serverSnapshotRevisions =
                            base.serverSnapshotRevisions + (normalizedServerId to nextProjectionRevision),
                        authoritativeServerSnapshotRevisions =
                            base.authoritativeServerSnapshotRevisions +
                                (normalizedServerId to nextProjectionRevision),
                        projectionRevision = nextProjectionRevision
                    )
                }
                is SharedTasksScope.Channel -> {
                    val channelKey =
                        SharedTasksChannelKey(
                            serverId = normalizedServerId,
                            channelId = scope.channelId.value.normalizedTasksStoreId()
                        )
                    val hasNewerAuthoritativeServerSnapshot =
                        requestServerSnapshotRevision != null &&
                            (base.authoritativeServerSnapshotRevisions[normalizedServerId] ?: 0L) >
                                requestServerSnapshotRevision &&
                            base.serverSnapshotStatuses[normalizedServerId] == SharedTasksDataStoreStatus.Loaded
                    val snapshotToCommit =
                        if (hasNewerAuthoritativeServerSnapshot) {
                            base.serverSnapshots[normalizedServerId]
                                ?.forChannel(scope.channelId)
                                ?: mergedSnapshot
                        } else {
                            mergedSnapshot
                        }
                    val hasUnroutableMutationAfterRequest =
                        requestRevision?.let { revision ->
                            base.hasUnroutableTaskMutationAfter(normalizedServerId, revision)
                        } == true
                    val channelSnapshotRevision =
                        if (hasUnroutableMutationAfterRequest) {
                            requestServerSnapshotRevision ?: 0L
                        } else {
                            nextProjectionRevision
                        }
                    val serverSnapshot =
                        (base.serverSnapshots[normalizedServerId]
                            ?: TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap()))
                            .replaceChannel(scope.channelId, snapshotToCommit)
                    val serverSnapshotStatus =
                        base.serverSnapshotStatuses[normalizedServerId]
                            ?: SharedTasksDataStoreStatus.Partial
                    // Exact channel responses feed every task surface. Do not promote a response
                    // whose request predates a mutation that cannot be attributed to this channel.
                    val reconciledServerSnapshots =
                        if (hasUnroutableMutationAfterRequest) {
                            base.serverSnapshots
                        } else {
                            base.serverSnapshots + (normalizedServerId to serverSnapshot)
                        }
                    val reconciledServerSnapshotStatuses =
                        if (hasUnroutableMutationAfterRequest) {
                            base.serverSnapshotStatuses
                        } else {
                            base.serverSnapshotStatuses + (normalizedServerId to serverSnapshotStatus)
                        }
                    val reconciledServerSnapshotRevisions =
                        if (hasUnroutableMutationAfterRequest) {
                            base.serverSnapshotRevisions
                        } else {
                            base.serverSnapshotRevisions + (normalizedServerId to nextProjectionRevision)
                        }
                    committedSnapshot = snapshotToCommit
                    base.copy(
                        serverSnapshots = reconciledServerSnapshots,
                        serverSnapshotStatuses = reconciledServerSnapshotStatuses,
                        serverSnapshotRevisions = reconciledServerSnapshotRevisions,
                        channelSnapshots =
                            base.channelSnapshots +
                                (channelKey to snapshotToCommit),
                        channelSnapshotStatuses =
                            base.channelSnapshotStatuses +
                                (channelKey to SharedTasksDataStoreStatus.Loaded),
                        channelSnapshotRevisions =
                            base.channelSnapshotRevisions + (channelKey to channelSnapshotRevision),
                        projectionRevision = nextProjectionRevision
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
        val requestServerSnapshotRevision =
            state.value.authoritativeServerSnapshotRevisions[key.serverId] ?: 0L
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
                                publishSnapshot(
                                    key.serverId,
                                    key.scope,
                                    result.data,
                                    requestRevision,
                                    requestServerSnapshotRevision
                                )
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
                                    requestRevision,
                                    requestServerSnapshotRevision
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

    fun upsertTask(serverId: String, task: TaskSummary): SharedTaskFactFoldOutcome {
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return SharedTaskFactFoldOutcome.Conflict
        var outcome = SharedTaskFactFoldOutcome.Accepted
        state.update { current ->
            val fold = current.foldCanonicalTask(normalizedServerId, task)
            outcome = fold.outcome
            val projectedTask = fold.projectedTask ?: return@update fold.state
            val base = fold.state
            val taskChannelId = projectedTask.channelId?.value.normalizedTasksStoreId()
            val taskChannelName = projectedTask.channelName.normalizedTasksChannelName()
            val nextProjectionRevision = base.projectionRevision + 1L
            val nextRevision = base.mutationRevision + 1L
            val previousServerRevision = base.serverSnapshotRevisions[normalizedServerId] ?: 0L
            val serverSnapshot =
                (base.serverSnapshots[normalizedServerId] ?: TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap()))
                    .upsert(projectedTask)
            val serverSnapshotStatus =
                base.serverSnapshotStatuses[normalizedServerId]
                    ?: SharedTasksDataStoreStatus.Partial
            val taskChannelKey =
                when {
                    taskChannelId != null ->
                        SharedTasksChannelKey(serverId = normalizedServerId, channelId = taskChannelId)
                    taskChannelName != null ->
                        SharedTasksChannelKey(serverId = normalizedServerId, channelName = taskChannelName)
                    else -> null
                }
            base.copy(
                serverSnapshots = base.serverSnapshots + (normalizedServerId to serverSnapshot),
                serverSnapshotStatuses = base.serverSnapshotStatuses + (normalizedServerId to serverSnapshotStatus),
                serverSnapshotRevisions = base.serverSnapshotRevisions + (normalizedServerId to nextProjectionRevision),
                channelSnapshots =
                    base.channelSnapshots
                        .mapValues { (key, snapshot) ->
                            if (key.serverId != normalizedServerId) return@mapValues snapshot
                            val isTargetChannel =
                                (taskChannelId != null && key.channelId == taskChannelId) ||
                                    (taskChannelName != null && key.channelName == taskChannelName)
                            when {
                                isTargetChannel -> snapshot.upsert(projectedTask)
                                snapshot.tasks.any { currentTask -> currentTask.id == projectedTask.id } ->
                                    snapshot.remove(projectedTask.id)
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
                                        TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap()).upsert(projectedTask)
                                    )
                            }
                        },
                channelSnapshotStatuses =
                    base.channelSnapshotStatuses.let { statuses ->
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
                channelSnapshotRevisions =
                    base.channelSnapshotRevisions
                        .mapValues { (key, revision) ->
                            val isTargetChannel =
                                (taskChannelId != null && key.channelId == taskChannelId) ||
                                    (taskChannelName != null && key.channelName == taskChannelName)
                            if (
                                key.serverId == normalizedServerId &&
                                (taskChannelId != null || isTargetChannel) &&
                                revision >= previousServerRevision
                            ) {
                                nextProjectionRevision
                            } else {
                                revision
                            }
                        }
                        .let { revisions ->
                            if (
                                taskChannelKey == null ||
                                revisions.containsKey(taskChannelKey) ||
                                serverSnapshotStatus == SharedTasksDataStoreStatus.Loaded
                            ) {
                                revisions
                            } else {
                                revisions + (taskChannelKey to nextProjectionRevision)
                            }
                        },
                projectionRevision = nextProjectionRevision,
                mutationRevision = nextRevision,
                taskMutations =
                    base.taskMutations +
                        (SharedTasksTaskKey(normalizedServerId, projectedTask.id) to
                            SharedTasksTaskMutation(nextRevision, projectedTask))
            )
        }
        return outcome
    }

    fun removeTask(serverId: String, taskId: TaskId) {
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return
        state.update { current ->
            val nextProjectionRevision = current.projectionRevision + 1L
            val nextRevision = current.mutationRevision + 1L
            val previousServerRevision = current.serverSnapshotRevisions[normalizedServerId] ?: 0L
            current.copy(
                serverSnapshots = current.serverSnapshots.removeTaskFromAll(taskId),
                channelSnapshots = current.channelSnapshots.removeTaskFromAll(taskId),
                serverSnapshotRevisions =
                    current.serverSnapshotRevisions.let { revisions ->
                        if (revisions.containsKey(normalizedServerId)) {
                            revisions + (normalizedServerId to nextProjectionRevision)
                        } else {
                            revisions
                        }
                    },
                channelSnapshotRevisions =
                    current.channelSnapshotRevisions.mapValues { (key, revision) ->
                        if (
                            key.serverId == normalizedServerId &&
                            revision >= previousServerRevision
                        ) {
                            nextProjectionRevision
                        } else {
                            revision
                        }
                    },
                projectionRevision = nextProjectionRevision,
                mutationRevision = nextRevision,
                taskMutations =
                    current.taskMutations +
                        (SharedTasksTaskKey(normalizedServerId, taskId) to
                            SharedTasksTaskMutation(nextRevision, null)),
                canonicalTaskFacts = current.canonicalTaskFacts - taskId,
                taskProjectionRoutes = current.taskProjectionRoutes.filterKeys { key -> key.taskId != taskId },
                canonicalConflicts = current.canonicalConflicts - taskId
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

    /**
     * Resolves a write target only from the currently bound Server's local projection.
     * Canonical facts never carry a routable channel id; a stale revision, a conflicted
     * fact, or a missing local route therefore fails closed before any network call.
     */
    fun mutationProjection(
        serverId: String?,
        taskId: TaskId,
        expectedRevision: Long?
    ): TaskSummary? {
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return null
        val current = state.value
        if (taskId in current.canonicalConflicts) return null
        val fact = current.canonicalTaskFacts[taskId] ?: return null
        if (fact.revision != null && expectedRevision != fact.revision) return null
        val route = current.taskProjectionRoutes[SharedTasksTaskKey(normalizedServerId, taskId)] ?: return null
        if (route.channelId == null) return null
        return fact.withTaskProjectionRoute(route)
    }

    /**
     * Personal Activity/notification identity is canonical-message scoped, while opening remains
     * Server-local. Returning one projection with N routes prevents host/peer duplicates without
     * inventing a canonical channel target.
     */
    fun notificationProjection(taskId: TaskId): SharedTaskNotificationProjection? {
        val current = state.value
        if (taskId in current.canonicalConflicts) return null
        val fact = current.canonicalTaskFacts[taskId] ?: return null
        val messageId = fact.messageId ?: return null
        val routes =
            current.taskProjectionRoutes
                .mapNotNull { (key, route) ->
                    if (key.taskId != taskId) return@mapNotNull null
                    val channelId = route.channelId ?: return@mapNotNull null
                    SharedTaskNotificationRoute(serverId = key.serverId, channelId = channelId)
                }
                .distinct()
                .sortedWith(compareBy(SharedTaskNotificationRoute::serverId, { it.channelId.value }))
        if (routes.isEmpty()) return null
        return SharedTaskNotificationProjection(
            taskId = taskId,
            messageId = messageId,
            revision = fact.revision,
            routes = routes
        )
    }

    /**
     * Synchronous first-frame projection for an already-bound Store owner. Production Compose
     * consumers use this as collectAsState's initial value so a warm accepted task status does not
     * briefly fall back to stale message payload data before the owner-bound Flow emits.
     *
     * A missing/unbound/mismatched owner returns null. This prevents a prior principal or API
     * environment from becoming launch state while the asynchronous owner bind is still pending.
     */
    fun projectionForOwner(
        expectedOwnerIdentity: String?,
        serverId: String?,
        scope: SharedTasksScope
    ): SharedTasksProjectionSlice? {
        val normalizedOwner = expectedOwnerIdentity?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val current = state.value
        if (!current.ownerIdentityBound || current.ownerIdentity != normalizedOwner) return null
        return when (scope) {
            SharedTasksScope.Server -> current.selectServerProjection(serverId)
            is SharedTasksScope.Channel ->
                current.selectChannelProjection(
                    serverId = serverId,
                    channelId = scope.channelId.value,
                    channelName = null
                )
        }
    }

    private fun SharedTasksDataStoreState.selectServerProjection(serverId: String?): SharedTasksProjectionSlice {
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return SharedTasksProjectionSlice()
        val snapshot = serverSnapshots[normalizedServerId] ?: return SharedTasksProjectionSlice()
        return SharedTasksProjectionSlice(
            status = serverSnapshotStatuses[normalizedServerId] ?: SharedTasksDataStoreStatus.Partial,
            isExactScopeLoaded = serverSnapshotStatuses[normalizedServerId] == SharedTasksDataStoreStatus.Loaded,
            tasks = snapshot.tasks,
            needsCanonicalRefetch = hasCanonicalConflictForServer(normalizedServerId)
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
        val exactChannelRevision = channelSnapshotRevisions[exactChannelKey] ?: 0L
        val serverRevision = serverSnapshotRevisions[normalizedServerId] ?: 0L
        // Loaded describes completeness at capture time. Revision ordering decides whether that
        // exact channel capture is still authoritative relative to later server/realtime facts.
        if (
            exactChannelSnapshot != null &&
            exactChannelStatus == SharedTasksDataStoreStatus.Loaded &&
            exactChannelRevision >= serverRevision
        ) {
            return SharedTasksProjectionSlice(
                status = SharedTasksDataStoreStatus.Loaded,
                isExactScopeLoaded = true,
                tasks = exactChannelSnapshot.tasks,
                needsCanonicalRefetch = hasCanonicalConflictForServer(normalizedServerId)
            )
        }
        val serverSnapshot = serverSnapshots[normalizedServerId]
        val serverStatus = serverSnapshotStatuses[normalizedServerId]
        if (serverSnapshot == null) {
            return if (exactChannelSnapshot == null) {
                SharedTasksProjectionSlice()
            } else {
                SharedTasksProjectionSlice(
                    status = exactChannelStatus ?: SharedTasksDataStoreStatus.Partial,
                    isExactScopeLoaded = exactChannelStatus == SharedTasksDataStoreStatus.Loaded,
                    tasks = exactChannelSnapshot.tasks,
                    needsCanonicalRefetch = hasCanonicalConflictForServer(normalizedServerId)
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
            if (exactChannelStatus == SharedTasksDataStoreStatus.Partial) {
                exactChannelSnapshot?.tasks?.fold(channelTasks) { tasks, task ->
                    TaskSnapshot(tasks = tasks, summaryCounts = emptyMap()).upsert(task).tasks
                } ?: channelTasks
            } else {
                channelTasks
            }
        return SharedTasksProjectionSlice(
            status = serverStatus ?: SharedTasksDataStoreStatus.Partial,
            isExactScopeLoaded = false,
            tasks = projectedTasks,
            needsCanonicalRefetch = hasCanonicalConflictForServer(normalizedServerId)
        )
    }

    private fun SharedTasksDataStoreState.hasCanonicalConflictForServer(serverId: String): Boolean =
        taskProjectionRoutes.keys.any { key ->
            key.serverId == serverId && key.taskId in canonicalConflicts
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

    private fun SharedTasksDataStoreState.hasUnroutableTaskMutationAfter(
        serverId: String,
        requestRevision: Long
    ): Boolean =
        taskMutations.any { (key, mutation) ->
            key.serverId == serverId &&
                mutation.revision > requestRevision &&
                mutation.task?.channelId == null
        }
}

private fun SharedTasksProjectionSlice.loadedSnapshotOrNull(): TaskSnapshot? =
    if (status == SharedTasksDataStoreStatus.Loaded && isExactScopeLoaded && !needsCanonicalRefetch) {
        TaskSnapshot(tasks = tasks, summaryCounts = tasks.summaryCounts())
    } else {
        null
    }

private fun TaskSnapshot.replaceChannel(channelId: ChannelId, replacement: TaskSnapshot): TaskSnapshot {
    val retained = tasks.filterNot { task -> task.channelId == channelId }
    val merged = (replacement.tasks + retained).distinctBy { task -> task.id }
    return copy(tasks = merged, summaryCounts = merged.summaryCounts())
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

private fun TaskSnapshot.forChannel(channelId: ChannelId): TaskSnapshot =
    TaskSnapshot(
        tasks = tasks.filter { task -> task.channelId == channelId },
        summaryCounts = emptyMap()
    )

private fun TaskSnapshot.remove(taskId: TaskId): TaskSnapshot {
    val nextTasks = tasks.filterNot { task -> task.id == taskId }
    if (nextTasks.size == tasks.size) return this
    return copy(tasks = nextTasks, summaryCounts = nextTasks.summaryCounts())
}
