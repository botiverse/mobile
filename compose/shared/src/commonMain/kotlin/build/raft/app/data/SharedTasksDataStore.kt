package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
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
    val nextStatusMutationId: Long = 0L,
    val statusMutations: Map<SharedTasksTaskKey, SharedTasksStatusMutationPermit> = emptyMap(),
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

/**
 * A point-in-time owner fence for an in-flight request (task #345, round 5):
 * the owner identity AND the store's owner generation. The identity alone is
 * insufficient: the same principal logging out and back in restores an equal
 * identity string, so a page fetched before logout could otherwise pass the
 * identity-only fence and be folded into the new session. The generation
 * increments on every [SharedTasksDataStore.clearOwnerData] and therefore
 * distinguishes those two sessions.
 */
internal data class SharedTasksOwnerSnapshot(
    internal val identity: String?,
    internal val generation: Long
)

internal data class SharedTasksStatusMutationPermit(
    internal val id: Long,
    internal val ownerIdentity: String?,
    internal val ownerGeneration: Long,
    val serverId: String,
    val channelId: ChannelId,
    val taskId: TaskId,
    val previousTask: TaskSummary,
    val optimisticTask: TaskSummary,
    internal val mutationRevision: Long
)

private data class SharedTasksTaskUpsert(
    val state: SharedTasksDataStoreState,
    val projectedTask: TaskSummary?,
    val outcome: SharedTaskFactFoldOutcome
)

private data class SharedTasksProjectionRoute(
    val channelId: ChannelId?,
    val channelName: String?,
    val canClaim: Boolean,
    /** Server-local read-only authority (joint participant history); blocks mutation routes. */
    val readOnlyReason: String? = null
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
            channelName = incoming.channelName,
            canClaim = incoming.canClaim,
            readOnlyReason = incoming.readOnlyReason
        )
    val nextRoutes = taskProjectionRoutes + (key to incomingRoute)
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
            !currentFact.hasSameCanonicalTaskFact(incomingFact)

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
    copy(channelId = null, channelName = null, canClaim = true, readOnlyReason = null)

private fun TaskSummary.withTaskProjectionRoute(route: SharedTasksProjectionRoute?): TaskSummary =
    copy(
        channelId = route?.channelId,
        channelName = route?.channelName,
        canClaim = route?.canClaim ?: false,
        readOnlyReason = route?.readOnlyReason
    )

/**
 * Compares only revision-owned task data. Member presentation is enriched independently by each
 * Server and updatedAtLabel is a local rendering, so neither may turn one canonical revision into a
 * false conflict. Authorization remains projection-local via [SharedTasksProjectionRoute].
 */
private fun TaskSummary.hasSameCanonicalTaskFact(other: TaskSummary): Boolean =
    id == other.id &&
        title == other.title &&
        status == other.status &&
        taskNumber == other.taskNumber &&
        messageId == other.messageId &&
        parentMessageId == other.parentMessageId &&
        threadId == other.threadId &&
        isThreadTask == other.isThreadTask &&
        assignee.taskPrincipalIdentity() == other.assignee.taskPrincipalIdentity() &&
        creator.taskPrincipalIdentity() == other.creator.taskPrincipalIdentity() &&
        description == other.description &&
        revision == other.revision

private fun MemberProfile?.taskPrincipalIdentity(): Pair<MemberKind, String>? =
    this?.let { profile -> profile.kind to profile.id.value }

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

private fun SharedTasksDataStoreState.projectTaskMutation(
    serverId: String,
    projectedTask: TaskSummary
): SharedTasksDataStoreState {
    val taskChannelId = projectedTask.channelId?.value.normalizedTasksStoreId()
    val taskChannelName = projectedTask.channelName.normalizedTasksChannelName()
    val nextProjectionRevision = projectionRevision + 1L
    val nextMutationRevision = mutationRevision + 1L
    val previousServerRevision = serverSnapshotRevisions[serverId] ?: 0L
    val serverSnapshot =
        (serverSnapshots[serverId] ?: TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap()))
            .upsert(projectedTask)
    val serverSnapshotStatus =
        serverSnapshotStatuses[serverId]
            ?: SharedTasksDataStoreStatus.Partial
    val taskChannelKey =
        when {
            taskChannelId != null ->
                SharedTasksChannelKey(serverId = serverId, channelId = taskChannelId)
            taskChannelName != null ->
                SharedTasksChannelKey(serverId = serverId, channelName = taskChannelName)
            else -> null
        }
    return copy(
        serverSnapshots = serverSnapshots + (serverId to serverSnapshot),
        serverSnapshotStatuses = serverSnapshotStatuses + (serverId to serverSnapshotStatus),
        serverSnapshotRevisions = serverSnapshotRevisions + (serverId to nextProjectionRevision),
        channelSnapshots =
            channelSnapshots
                .mapValues { (key, snapshot) ->
                    if (key.serverId != serverId) return@mapValues snapshot
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
            channelSnapshotStatuses.let { statuses ->
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
            channelSnapshotRevisions
                .mapValues { (key, revision) ->
                    val isTargetChannel =
                        (taskChannelId != null && key.channelId == taskChannelId) ||
                            (taskChannelName != null && key.channelName == taskChannelName)
                    if (
                        key.serverId == serverId &&
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
        mutationRevision = nextMutationRevision,
        taskMutations =
            taskMutations +
                (SharedTasksTaskKey(serverId, projectedTask.id) to
                    SharedTasksTaskMutation(nextMutationRevision, projectedTask))
    )
}

private fun SharedTasksDataStoreState.upsertTaskState(
    serverId: String,
    task: TaskSummary,
    supersedeStatusMutation: Boolean
): SharedTasksTaskUpsert {
    val key = SharedTasksTaskKey(serverId, task.id)
    val fold = foldCanonicalTask(serverId, task)
    var nextState = fold.state
    val projectedTask =
        if (supersedeStatusMutation && fold.outcome == SharedTaskFactFoldOutcome.StaleIgnored) {
            statusMutations[key]?.optimisticTask ?: fold.projectedTask
        } else {
            fold.projectedTask
        }
    if (projectedTask != null) {
        nextState = nextState.projectTaskMutation(serverId, projectedTask)
    }
    if (supersedeStatusMutation && fold.outcome != SharedTaskFactFoldOutcome.StaleIgnored) {
        nextState = nextState.copy(statusMutations = nextState.statusMutations - key)
    }
    return SharedTasksTaskUpsert(
        state = nextState,
        projectedTask = projectedTask,
        outcome = fold.outcome
    )
}

private fun SharedTasksDataStoreState.commitStatusMutationResponse(
    permit: SharedTasksStatusMutationPermit,
    task: TaskSummary
): SharedTasksTaskUpsert {
    val key = SharedTasksTaskKey(permit.serverId, permit.taskId)
    val route = taskProjectionRoutes[key]
        ?: return SharedTasksTaskUpsert(this, null, SharedTaskFactFoldOutcome.Conflict)
    val currentFact = canonicalTaskFacts[permit.taskId]
    val committedRevision =
        if (task.revision.compareTaskRevision(currentFact?.revision) < 0) {
            currentFact?.revision
        } else {
            task.revision
        }
    val committedFact = task.copy(revision = committedRevision).withoutTaskProjectionRoute()
    val canonicalState =
        copy(
            canonicalTaskFacts = canonicalTaskFacts + (permit.taskId to committedFact),
            canonicalConflicts = canonicalConflicts - permit.taskId,
            serverSnapshots = serverSnapshots.reprojectServerTask(committedFact, taskProjectionRoutes),
            channelSnapshots = channelSnapshots.reprojectChannelTask(committedFact, taskProjectionRoutes)
        )
    val projectedTask = committedFact.withTaskProjectionRoute(route)
    return SharedTasksTaskUpsert(
        state = canonicalState.projectTaskMutation(permit.serverId, projectedTask),
        projectedTask = projectedTask,
        outcome = SharedTaskFactFoldOutcome.Accepted
    )
}

private fun SharedTasksDataStoreState.statusMutationProjection(
    serverId: String,
    taskId: TaskId,
    expectedRevision: Long?
): TaskSummary? {
    if (taskId in canonicalConflicts) return null
    val key = SharedTasksTaskKey(serverId, taskId)
    val route = taskProjectionRoutes[key] ?: return null
    if (route.channelId == null) return null
    // Read-only projections (historical joint tasks on a participant) have no write target.
    if (route.readOnlyReason != null) return null
    val task =
        statusMutations[key]?.optimisticTask
            ?: canonicalTaskFacts[taskId]?.withTaskProjectionRoute(route)
            ?: return null
    if (task.revision != null && expectedRevision != task.revision) return null
    return task.withTaskProjectionRoute(route)
}

private fun SharedTasksDataStoreState.removeTaskState(
    serverId: String,
    taskId: TaskId
): SharedTasksDataStoreState {
    val nextProjectionRevision = projectionRevision + 1L
    val nextMutationRevision = mutationRevision + 1L
    val previousServerRevision = serverSnapshotRevisions[serverId] ?: 0L
    return copy(
        serverSnapshots = serverSnapshots.removeTaskFromAll(taskId),
        channelSnapshots = channelSnapshots.removeTaskFromAll(taskId),
        serverSnapshotRevisions =
            serverSnapshotRevisions.let { revisions ->
                if (revisions.containsKey(serverId)) {
                    revisions + (serverId to nextProjectionRevision)
                } else {
                    revisions
                }
            },
        channelSnapshotRevisions =
            channelSnapshotRevisions.mapValues { (key, revision) ->
                if (key.serverId == serverId && revision >= previousServerRevision) {
                    nextProjectionRevision
                } else {
                    revision
                }
            },
        projectionRevision = nextProjectionRevision,
        mutationRevision = nextMutationRevision,
        taskMutations =
            taskMutations +
                (SharedTasksTaskKey(serverId, taskId) to
                    SharedTasksTaskMutation(nextMutationRevision, null)),
        statusMutations = statusMutations - SharedTasksTaskKey(serverId, taskId),
        canonicalTaskFacts = canonicalTaskFacts - taskId,
        taskProjectionRoutes = taskProjectionRoutes.filterKeys { key -> key.taskId != taskId },
        canonicalConflicts = canonicalConflicts - taskId
    )
}

internal class SharedTasksDataStore(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val ownerIdentityProvider: (suspend () -> String?)? = null,
    private val currentServerIdProvider: (suspend () -> String?)? = null
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

    /**
     * The owner identity the store is currently bound to (server + API base +
     * principal, per [ownerIdentityProvider]), or null when unbound. LoadMore
     * fences a page request against this so a page fetched for one owner is
     * never folded into a different owner's freshly cleared store (task #345).
     */
    suspend fun currentOwnerIdentity(): String? {
        bindCurrentOwnerIdentity()
        return ownerIdentity
    }

    /**
     * Capture the owner identity together with the current owner generation so a
     * request can be fenced against BOTH. Prefer this over [currentOwnerIdentity]
     * for any request that may outlive a logout/login of the same principal.
     */
    suspend fun currentOwnerSnapshot(): SharedTasksOwnerSnapshot {
        bindCurrentOwnerIdentity()
        return SharedTasksOwnerSnapshot(identity = ownerIdentity, generation = ownerGeneration)
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
                    val mutationKey = SharedTasksTaskKey(normalizedServerId, task.id)
                    val activeMutation = fold.state.statusMutations[mutationKey]
                    val authoritativeResponseSupersedesMutation =
                        activeMutation != null &&
                            fold.outcome != SharedTaskFactFoldOutcome.StaleIgnored &&
                            (requestRevision == null || activeMutation.mutationRevision <= requestRevision)
                    base =
                        if (authoritativeResponseSupersedesMutation) {
                            fold.state.copy(statusMutations = fold.state.statusMutations - mutationKey)
                        } else {
                            fold.state
                        }
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

    /**
     * Synchronous fixture seam for stores without an owner provider. Production stores fail closed
     * and must use one of the suspend owner-bound upsert methods below.
     */
    fun upsertTask(serverId: String, task: TaskSummary): SharedTaskFactFoldOutcome {
        if (ownerIdentityProvider != null) return SharedTaskFactFoldOutcome.Conflict
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return SharedTaskFactFoldOutcome.Conflict
        var outcome = SharedTaskFactFoldOutcome.Accepted
        state.update { current ->
            val upsert = current.upsertTaskState(normalizedServerId, task, supersedeStatusMutation = true)
            outcome = upsert.outcome
            upsert.state
        }
        return outcome
    }

    suspend fun upsertTaskForCurrentOwner(
        serverId: String,
        task: TaskSummary
    ): SharedTaskFactFoldOutcome? {
        bindCurrentOwnerIdentity()
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return null
        return loadMutex.withLock {
            val upsert = state.value.upsertTaskState(normalizedServerId, task, supersedeStatusMutation = true)
            state.value = upsert.state
            upsert.outcome
        }
    }

    suspend fun upsertTaskForCurrentServer(
        serverId: String,
        task: TaskSummary
    ): SharedTaskFactFoldOutcome? {
        bindCurrentOwnerIdentity()
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return null
        return loadMutex.withLock {
            if (!isCurrentServerLocked(normalizedServerId)) return@withLock null
            val upsert = state.value.upsertTaskState(normalizedServerId, task, supersedeStatusMutation = true)
            state.value = upsert.state
            upsert.outcome
        }
    }

    suspend fun beginStatusMutation(
        serverId: String,
        scope: SharedTasksScope,
        taskId: TaskId,
        expectedRevision: Long?,
        status: TaskStatus
    ): SharedTasksStatusMutationPermit? {
        bindCurrentOwnerIdentity()
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return null
        return loadMutex.withLock {
            if (!isCurrentServerLocked(normalizedServerId)) return@withLock null
            val current = state.value
            val previousTask =
                current.statusMutationProjection(normalizedServerId, taskId, expectedRevision)
                    ?: return@withLock null
            val channelId = previousTask.channelId ?: return@withLock null
            if (scope is SharedTasksScope.Channel && scope.channelId != channelId) return@withLock null
            val optimisticTask = previousTask.copy(status = status)
            beginTaskMutationLocked(current, normalizedServerId, channelId, taskId, previousTask, optimisticTask)
        }
    }

    /**
     * Assign/unassign twin of [beginStatusMutation]: snapshots the projected task
     * and projects the optimistic assignee copy. Shares the same one-pending-
     * mutation-per-task invariant (same permit table), so an in-flight status
     * write blocks an assign on the same task and vice versa.
     */
    suspend fun beginAssignMutation(
        serverId: String,
        scope: SharedTasksScope,
        taskId: TaskId,
        expectedRevision: Long?,
        assignee: MemberProfile?
    ): SharedTasksStatusMutationPermit? {
        bindCurrentOwnerIdentity()
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return null
        return loadMutex.withLock {
            if (!isCurrentServerLocked(normalizedServerId)) return@withLock null
            val current = state.value
            val previousTask =
                current.statusMutationProjection(normalizedServerId, taskId, expectedRevision)
                    ?: return@withLock null
            val channelId = previousTask.channelId ?: return@withLock null
            if (scope is SharedTasksScope.Channel && scope.channelId != channelId) return@withLock null
            val optimisticTask = previousTask.copy(assignee = assignee)
            beginTaskMutationLocked(current, normalizedServerId, channelId, taskId, previousTask, optimisticTask)
        }
    }

    /** Permit-table insert shared by every task mutation kind. Caller holds [loadMutex]. */
    private fun beginTaskMutationLocked(
        current: SharedTasksDataStoreState,
        normalizedServerId: String,
        channelId: ChannelId,
        taskId: TaskId,
        previousTask: TaskSummary,
        optimisticTask: TaskSummary
    ): SharedTasksStatusMutationPermit {
        val projected = current.projectTaskMutation(normalizedServerId, optimisticTask)
        val permit =
            SharedTasksStatusMutationPermit(
                id = current.nextStatusMutationId + 1L,
                ownerIdentity = ownerIdentity,
                ownerGeneration = ownerGeneration,
                serverId = normalizedServerId,
                channelId = channelId,
                taskId = taskId,
                previousTask = previousTask,
                optimisticTask = optimisticTask,
                mutationRevision = projected.mutationRevision
            )
        state.value =
            projected.copy(
                nextStatusMutationId = permit.id,
                statusMutations = projected.statusMutations + (SharedTasksTaskKey(normalizedServerId, taskId) to permit)
            )
        return permit
    }

    suspend fun completeStatusMutation(
        permit: SharedTasksStatusMutationPermit,
        task: TaskSummary
    ): TaskSummary? {
        bindCurrentOwnerIdentity()
        return loadMutex.withLock {
            if (task.id != permit.taskId || !isStatusMutationCurrentLocked(permit)) return@withLock null
            val key = SharedTasksTaskKey(permit.serverId, permit.taskId)
            val upsert = state.value.commitStatusMutationResponse(permit, task)
            state.value = upsert.state.copy(statusMutations = upsert.state.statusMutations - key)
            upsert.projectedTask
        }
    }

    suspend fun completeStatusMutationWithoutProjection(
        permit: SharedTasksStatusMutationPermit
    ): TaskSummary? {
        bindCurrentOwnerIdentity()
        return loadMutex.withLock {
            if (!isStatusMutationCurrentLocked(permit)) return@withLock null
            val key = SharedTasksTaskKey(permit.serverId, permit.taskId)
            val upsert = state.value.commitStatusMutationResponse(permit, permit.optimisticTask)
            state.value = upsert.state.copy(statusMutations = upsert.state.statusMutations - key)
            upsert.projectedTask
        }
    }

    suspend fun rollbackStatusMutation(
        permit: SharedTasksStatusMutationPermit
    ): TaskSummary? {
        bindCurrentOwnerIdentity()
        return loadMutex.withLock {
            if (!isStatusMutationCurrentLocked(permit)) return@withLock null
            val key = SharedTasksTaskKey(permit.serverId, permit.taskId)
            val restored = state.value.projectTaskMutation(permit.serverId, permit.previousTask)
            state.value = restored.copy(statusMutations = restored.statusMutations - key)
            permit.previousTask
        }
    }

    private suspend fun isCurrentServerLocked(serverId: String): Boolean {
        val provider = currentServerIdProvider ?: return true
        return provider().normalizedTasksStoreId() == serverId
    }

    private suspend fun isStatusMutationCurrentLocked(permit: SharedTasksStatusMutationPermit): Boolean {
        if (
            ownerGeneration != permit.ownerGeneration ||
            ownerIdentity != permit.ownerIdentity
        ) {
            return false
        }
        val current = state.value
        val key = SharedTasksTaskKey(permit.serverId, permit.taskId)
        if (current.statusMutations[key] != permit) return false
        if (
            !isCurrentServerLocked(permit.serverId) ||
            current.taskProjectionRoutes[key]?.channelId != permit.channelId
        ) {
            retireStatusMutationLocked(permit)
            return false
        }
        return true
    }

    private fun retireStatusMutationLocked(permit: SharedTasksStatusMutationPermit) {
        val current = state.value
        val key = SharedTasksTaskKey(permit.serverId, permit.taskId)
        if (current.statusMutations[key] != permit) return
        val route = current.taskProjectionRoutes[key]
        val canonicalTask = current.canonicalTaskFacts[permit.taskId]?.withTaskProjectionRoute(route)
        val retired =
            if (canonicalTask == null) {
                current
            } else {
                current.projectTaskMutation(permit.serverId, canonicalTask)
            }
        state.value = retired.copy(statusMutations = retired.statusMutations - key)
    }

    /**
     * Synchronous fixture seam for stores without an owner provider. Production realtime deletion
     * uses [removeTaskForCurrentOwner] so an old-account callback cannot clear current data.
     */
    fun removeTask(serverId: String, taskId: TaskId) {
        if (ownerIdentityProvider != null) return
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return
        state.update { current ->
            current.removeTaskState(normalizedServerId, taskId)
        }
    }

    suspend fun removeTaskForCurrentOwner(serverId: String, taskId: TaskId): Boolean {
        bindCurrentOwnerIdentity()
        val normalizedServerId = serverId.normalizedTasksStoreId() ?: return false
        return loadMutex.withLock {
            state.value = state.value.removeTaskState(normalizedServerId, taskId)
            true
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
        return state.value.statusMutationProjection(normalizedServerId, taskId, expectedRevision)
    }

    /**
     * Personal Activity/notification identity is canonical-message scoped, while opening remains
     * Server-local. Returning one projection with N routes prevents host/peer duplicates without
     * inventing a canonical channel target.
     */
    fun notificationProjection(taskId: TaskId): SharedTaskNotificationProjection? =
        notificationProjections(listOf(taskId))[taskId]

    /**
     * Batch form of [notificationProjection]. Routes are grouped in one pass over the route
     * table, so projecting N tasks costs O(N + routes) instead of O(N * routes). Activity
     * refreshes project every task bound to the visible rows; on a Server with thousands of
     * tasks the per-task scan took seconds while the Activity store held its state lock.
     */
    fun notificationProjections(taskIds: Collection<TaskId>): Map<TaskId, SharedTaskNotificationProjection> {
        if (taskIds.isEmpty()) return emptyMap()
        val current = state.value
        val wanted =
            taskIds.filterTo(mutableSetOf()) { taskId ->
                taskId !in current.canonicalConflicts && current.canonicalTaskFacts[taskId]?.messageId != null
            }
        if (wanted.isEmpty()) return emptyMap()
        val routesByTaskId = mutableMapOf<TaskId, MutableSet<SharedTaskNotificationRoute>>()
        current.taskProjectionRoutes.forEach { (key, route) ->
            if (key.taskId !in wanted) return@forEach
            val channelId = route.channelId ?: return@forEach
            routesByTaskId
                .getOrPut(key.taskId) { mutableSetOf() }
                .add(SharedTaskNotificationRoute(serverId = key.serverId, channelId = channelId))
        }
        val projections = mutableMapOf<TaskId, SharedTaskNotificationProjection>()
        routesByTaskId.forEach { (taskId, routes) ->
            val fact = current.canonicalTaskFacts[taskId] ?: return@forEach
            val messageId = fact.messageId ?: return@forEach
            projections[taskId] =
                SharedTaskNotificationProjection(
                    taskId = taskId,
                    messageId = messageId,
                    revision = fact.revision,
                    routes = routes.sortedWith(compareBy(SharedTaskNotificationRoute::serverId, { it.channelId.value }))
                )
        }
        return projections
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
