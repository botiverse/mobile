package build.raft.app.data

import build.raft.app.SharedCreateAgentMachineOption
import build.raft.app.isConversationAccessLost
import build.raft.app.model.ServerId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.TimeSource

internal enum class SharedComputersLoadStatus {
    Idle,
    InFlight,
    Loaded,
    Error
}

internal data class SharedComputersPickerSlice(
    val machines: List<SharedCreateAgentMachineOption> = emptyList(),
    val status: SharedComputersLoadStatus = SharedComputersLoadStatus.Idle,
    val hasSnapshot: Boolean = false,
    val refreshing: Boolean = false,
    val error: ScreenError? = null
) {
    val errorCode: String?
        get() = error?.code

    val initialLoadInFlight: Boolean
        get() = !hasSnapshot && status == SharedComputersLoadStatus.InFlight

    val initialLoadError: ScreenError?
        get() = error.takeIf { !hasSnapshot && status == SharedComputersLoadStatus.Error }
}

internal data class SharedComputersServerSlice(
    val machines: List<ComputerSummary> = emptyList(),
    val machineCount: Int = 0,
    val onlineCount: Int = 0,
    val latestDaemonVersion: String? = null,
    val latestComputerVersion: String? = null,
    val status: SharedComputersLoadStatus = SharedComputersLoadStatus.Idle,
    val hasSnapshot: Boolean = false,
    val refreshing: Boolean = false,
    val error: ScreenError? = null,
    /**
     * Painted from the durable row (cold start / first visit after a memory eviction): shown with
     * no spinner, but not yet validated, so the next non-forced ensure still goes to the network.
     */
    val seededFromDurable: Boolean = false,
    /** The last Add Computer gate the settings page computed for this server (first-frame seed). */
    val canAddComputer: Boolean? = null
) {
    val errorCode: String?
        get() = error?.code
}

internal data class SharedComputersOwner(
    val environmentId: String,
    val accountId: String,
    val sessionGeneration: String
)

/** The computers cache owner for [session]; tokens are not part of it (see [SharedAuthLoginGeneration]). */
internal fun sharedComputersOwner(
    session: StoredAuthSession,
    environmentId: String,
    loginGeneration: String
): SharedComputersOwner? {
    val cleanEnvironmentId = environmentId.trim().trimEnd('/')
    val accountId = session.user.id.trim()
    if (cleanEnvironmentId.isBlank() || accountId.isBlank()) return null
    return SharedComputersOwner(cleanEnvironmentId, accountId, loginGeneration)
}

internal data class SharedComputersState(
    val owner: SharedComputersOwner? = null,
    val serverSlices: Map<String, SharedComputersServerSlice> = emptyMap()
)

interface SharedSettingsComputersSource {
    suspend fun loadSettingsComputersForServer(
        serverId: String,
        force: Boolean = false
    ): DataResult<SettingsComputersSnapshot>

    suspend fun loadSettingsComputerSnapshotForServer(
        serverId: String,
        machineId: String,
        force: Boolean = false
    ): DataResult<SettingsComputerSnapshot>

    /**
     * Cache-first read for a page open: the in-memory (or durable, on a cold start) machine list of
     * [serverId] without any network call, or null when nothing is cached for this account.
     */
    suspend fun cachedSettingsComputersForServer(serverId: String): DataResult<SettingsComputersSnapshot>? = null

    /** Remembers the Add Computer gate so the next first frame starts with the right action. */
    fun recordAddComputerGate(serverId: String, canAddComputer: Boolean) = Unit

    /** The last machine page loaded for [machineId] on [serverId] under the current account. */
    fun cachedSettingsComputerDetail(serverId: String, machineId: String): SettingsComputerDetailSnapshot? = null

    /** Records (or, with null, forgets: deleted / lost access) one machine page. */
    fun recordSettingsComputerDetail(serverId: String, machineId: String, detail: SettingsComputerDetailSnapshot?) = Unit
}

internal fun SharedComputersState.selectComputerServerSlice(serverId: String?): SharedComputersServerSlice {
    val normalizedServerId = serverId.normalizedComputerServerId() ?: return SharedComputersServerSlice()
    return serverSlices[normalizedServerId] ?: SharedComputersServerSlice()
}

internal fun SharedComputersState.selectComputerPickerSlice(serverId: String?): SharedComputersPickerSlice {
    val slice = selectComputerServerSlice(serverId)
    return SharedComputersPickerSlice(
        machines = slice.machines.inComputerListOrder().toCreateAgentMachineOptions(),
        status = slice.status,
        hasSnapshot = slice.hasSnapshot,
        refreshing = slice.refreshing,
        error = slice.error
    )
}

internal sealed interface SharedComputersIntent {
    data class EnsureComputerPicker(
        val serverId: String,
        val priority: DataRequestPriority = DataRequestPriority.Visible
    ) : SharedComputersIntent

    data class RefreshComputerPicker(
        val serverId: String,
        val priority: DataRequestPriority = DataRequestPriority.Visible
    ) : SharedComputersIntent

    data object Clear : SharedComputersIntent
}

class SharedComputersStore internal constructor(
    private val computersApiClient: RaftComputersApiClient,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
    private val ownerProvider: suspend () -> SharedComputersOwner? = {
        SharedComputersOwner("test-environment", "test-account", "test-session")
    },
    /** Durable last-known machine lists (data-layer plan step 5); null keeps the store memory-only. */
    private val durableStore: RaftKeyedDurableSnapshotStore<RaftComputersDurableRecord>? = null,
    /** Per-store memory cap: least recently used servers leave first and re-seed from disk. */
    private val maxServers: Int = DefaultMaxServers
) : SharedSettingsComputersSource {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SharedComputersState())
    internal val state: StateFlow<SharedComputersState> = _state.asStateFlow()
    private val loads = mutableMapOf<String, Deferred<DataResult<ComputerListSummary>>>()
    private val generations = mutableMapOf<String, Long>()
    private val serverRecency = mutableListOf<String>()

    // Memory-only machine pages (workspaces / agents sections), LRU, cleared with the owner.
    private val detailCache = linkedMapOf<Pair<String, String>, SettingsComputerDetailSnapshot>()
    private var detailCacheOwner: SharedComputersOwner? = null
    private var currentOwner: SharedComputersOwner? = null
    private var nextGeneration: Long = 0L

    internal fun computerPickerSliceFlow(serverId: String?): Flow<SharedComputersPickerSlice> =
        state
            .map { state -> state.selectComputerPickerSlice(serverId) }
            .distinctUntilChanged()

    /** The server's machines as the migration section names and targets them. */
    internal fun migrationComputersFlow(serverId: String?): Flow<List<build.raft.app.AgentMigrationComputer>> =
        state
            .map { state ->
                state.selectComputerServerSlice(serverId).machines.map {
                    build.raft.app.AgentMigrationComputer(id = it.id, name = it.name, isComputer = it.isComputer)
                }
            }
            .distinctUntilChanged()

    internal fun dispatch(intent: SharedComputersIntent) {
        when (intent) {
            is SharedComputersIntent.EnsureComputerPicker ->
                loadComputerPicker(serverId = intent.serverId, force = false, priority = intent.priority)
            is SharedComputersIntent.RefreshComputerPicker ->
                loadComputerPicker(serverId = intent.serverId, force = true, priority = intent.priority)
            SharedComputersIntent.Clear -> scope.launch { clear() }
        }
    }

    private fun loadComputerPicker(
        serverId: String,
        force: Boolean,
        priority: DataRequestPriority
    ) {
        scope.launch {
            ensureComputerPickerForServer(serverId = serverId, force = force, priority = priority)
        }
    }

    internal suspend fun ensureComputerPickerForServer(
        serverId: String,
        force: Boolean = false,
        priority: DataRequestPriority = DataRequestPriority.Visible
    ) {
        ensureComputersForServer(serverId = serverId, force = force, priority = priority)
    }

    internal suspend fun reconcileOwner() {
        val owner = ownerProvider()
        mutex.withLock {
            if (owner == null) clearOwnerLocked() else installOwnerLocked(owner)
        }
    }

    override suspend fun loadSettingsComputersForServer(serverId: String, force: Boolean): DataResult<SettingsComputersSnapshot> {
        val normalizedServerId =
            serverId.normalizedComputerServerId()
                ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        ensureComputersForServer(
            serverId = normalizedServerId,
            force = force,
            priority = DataRequestPriority.Visible
        )
        return _state.value.selectComputerServerSlice(normalizedServerId).toSettingsComputersResult()
    }

    override suspend fun loadSettingsComputerSnapshotForServer(
        serverId: String,
        machineId: String,
        force: Boolean
    ): DataResult<SettingsComputerSnapshot> {
        val normalizedServerId =
            serverId.normalizedComputerServerId()
                ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        val normalizedMachineId = machineId.trim()
        if (normalizedMachineId.isBlank()) return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        ensureComputersForServer(
            serverId = normalizedServerId,
            force = force,
            priority = DataRequestPriority.Visible
        )
        return _state.value
            .selectComputerServerSlice(normalizedServerId)
            .toSettingsComputerSnapshotResult(normalizedMachineId)
    }

    private suspend fun ensureComputersForServer(
        serverId: String,
        force: Boolean,
        priority: DataRequestPriority
    ) {
        val startedAt = TimeSource.Monotonic.markNow()
        val normalizedServerId = serverId.normalizedComputerServerId()
        if (normalizedServerId == null) {
            logger("shared_computers_skip reason=server_id_missing")
            logger("store_load domain=computers serverId= result=skip reason=server_id_missing durationMs=${startedAt.elapsedNow().inWholeMilliseconds}")
            return
        }
        val owner = ownerProvider()
        if (owner == null) {
            clearForMissingOwner()
            logger("store_load domain=computers serverId=$normalizedServerId result=skip reason=owner_missing")
            return
        }
        hydrateFromDurable(owner, normalizedServerId)
        val durableEpoch = durableStore?.epoch ?: 0L
        val deferred = mutex.withLock {
            installOwnerLocked(owner)
            val current = _state.value.selectComputerServerSlice(normalizedServerId)
            if (!force && current.hasSnapshot && !current.seededFromDurable) {
                logger("shared_computers_skip serverId=$normalizedServerId reason=snapshot_available")
                logger(
                    "store_load domain=computers serverId=$normalizedServerId result=skip reason=snapshot_available " +
                        "force=$force status=${current.status.name} machines=${current.machines.size} " +
                        "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
                return@withLock null
            }
            loads[normalizedServerId]?.let { active ->
                logger("shared_computers_join serverId=$normalizedServerId reason=inflight priority=${priority.name}")
                return@withLock active
            }
            val generation = ++nextGeneration
            generations[normalizedServerId] = generation
            setServerSliceLocked(
                serverId = normalizedServerId,
                slice =
                    current.copy(
                        status =
                            if (current.hasSnapshot) {
                                SharedComputersLoadStatus.Loaded
                            } else {
                                SharedComputersLoadStatus.InFlight
                            },
                        refreshing = true,
                        error = null
                    )
            )
            scope.async(start = CoroutineStart.LAZY) {
                val result =
                    runCatchingPreservingCancellation {
                        computersApiClient.machines(ServerId(normalizedServerId), priority)
                    }.getOrElse { throwable ->
                        logger(
                            "shared_computers_result serverId=$normalizedServerId result=exception " +
                                "message=${throwable.message.orEmpty()}"
                        )
                        DataResult.Error(
                            ScreenError(
                                code = "computers_exception",
                                retryable = true,
                                serverMessage = throwable.message?.takeIf { it.isNotBlank() }
                            )
                        )
                    }
                publishResult(
                    owner = owner,
                    serverId = normalizedServerId,
                    generation = generation,
                    result = result,
                    durableEpoch = durableEpoch
                )
                logger(
                    "store_load domain=computers serverId=$normalizedServerId result=${result.logOutcome()} " +
                        "priority=${priority.name} force=$force durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
                result
            }.also { loads[normalizedServerId] = it }
        }
        deferred?.start()
        deferred?.await()
    }

    private suspend fun publishResult(
        owner: SharedComputersOwner,
        serverId: String,
        generation: Long,
        result: DataResult<ComputerListSummary>,
        durableEpoch: Long
    ) {
        val publicationOwner =
            runCatchingPreservingCancellation { ownerProvider() }.getOrNull()
        val durableWrite = mutex.withLock {
            if (currentOwner != owner || generations[serverId] != generation) {
                logger("shared_computers_drop serverId=$serverId reason=stale_generation generation=$generation")
                return@withLock null
            }
            if (publicationOwner != owner) {
                if (publicationOwner == null) {
                    clearOwnerLocked()
                } else {
                    installOwnerLocked(publicationOwner)
                }
                logger("shared_computers_drop serverId=$serverId reason=owner_changed generation=$generation")
                return@withLock null
            }
            loads.remove(serverId)
            val current = _state.value.selectComputerServerSlice(serverId)
            if (result.isConversationAccessLost()) {
                // Tenny rule 2: the account lost the server's machine list (non-auth 403 / 404).
                // Nothing cached may paint again; the page shows the terminal state.
                removeServerLocked(serverId)
                setServerSliceLocked(
                    serverId,
                    SharedComputersServerSlice().withComputersFailure(result.toComputersScreenError())
                )
                logger("shared_computers_result serverId=$serverId result=access_lost")
                return@withLock ComputersDurableWrite.Evict
            }
            val next =
                when (result) {
                    is DataResult.Content ->
                        SharedComputersServerSlice(
                            machines = result.data.machines,
                            machineCount = result.data.machineCount,
                            onlineCount = result.data.onlineCount,
                            latestDaemonVersion = result.data.latestDaemonVersion,
                            latestComputerVersion = result.data.latestComputerVersion,
                            status = SharedComputersLoadStatus.Loaded,
                            hasSnapshot = true,
                            canAddComputer = current.canAddComputer
                        )
                    is DataResult.Empty ->
                        SharedComputersServerSlice(
                            status = SharedComputersLoadStatus.Loaded,
                            hasSnapshot = true,
                            canAddComputer = current.canAddComputer
                        )
                    is DataResult.Error -> current.withComputersFailure(result.error)
                    is DataResult.NoPermission -> current.withComputersFailure(result.toComputersScreenError())
                }
            setServerSliceLocked(serverId, next)
            if (next.hasSnapshot && (result is DataResult.Content || result is DataResult.Empty)) {
                ComputersDurableWrite.Write(next.toDurableRecord())
            } else {
                null
            }
        }
        val store = durableStore ?: return
        val key = owner.durableKey(serverId) ?: return
        when (durableWrite) {
            is ComputersDurableWrite.Write -> store.write(key, durableWrite.record, durableEpoch)
            ComputersDurableWrite.Evict -> store.evict(key)
            null -> Unit
        }
    }

    /**
     * Cold start / first visit: paint [serverId]'s machines from the durable row when memory has
     * none. The seeded slice is Loaded (no spinner) but [SharedComputersServerSlice.seededFromDurable],
     * so the next ensure still validates it once against the network.
     */
    private suspend fun hydrateFromDurable(owner: SharedComputersOwner, serverId: String) {
        val store = durableStore ?: return
        if (_state.value.owner == owner && _state.value.selectComputerServerSlice(serverId).hasSnapshot) return
        val key = owner.durableKey(serverId) ?: return
        val record = store.read(key) ?: return
        mutex.withLock {
            installOwnerLocked(owner)
            if (_state.value.selectComputerServerSlice(serverId).hasSnapshot) return@withLock
            setServerSliceLocked(serverId, record.toServerSlice())
            logger("shared_computers_durable_seed serverId=$serverId machines=${record.machines.size}")
        }
    }

    override suspend fun cachedSettingsComputersForServer(serverId: String): DataResult<SettingsComputersSnapshot>? {
        val normalizedServerId = serverId.normalizedComputerServerId() ?: return null
        val owner = ownerProvider() ?: return null
        hydrateFromDurable(owner, normalizedServerId)
        return mutex.withLock {
            if (currentOwner != owner) return@withLock null
            _state.value.selectComputerServerSlice(normalizedServerId)
                .takeIf { it.hasSnapshot }
                ?.toSettingsComputersResult()
        }
    }

    /** Synchronous first-frame read: the machine list currently held in memory for [serverId]. */
    internal fun currentSettingsComputers(serverId: String?): SettingsComputersSnapshot? {
        val slice = _state.value.selectComputerServerSlice(serverId).takeIf { it.hasSnapshot } ?: return null
        return (slice.toSettingsComputersResult() as? DataResult.Content)?.data?.let { snapshot ->
            val canAdd = slice.canAddComputer ?: false
            snapshot.copy(
                canAddComputer = canAdd,
                addComputerUnavailableReason = if (canAdd) null else "server_admin_required"
            )
        }
    }

    override fun recordAddComputerGate(serverId: String, canAddComputer: Boolean) {
        val normalizedServerId = serverId.normalizedComputerServerId() ?: return
        _state.update { state ->
            val current = state.serverSlices[normalizedServerId]
            if (current == null || current.canAddComputer == canAddComputer) {
                state
            } else {
                state.copy(serverSlices = state.serverSlices + (normalizedServerId to current.copy(canAddComputer = canAddComputer)))
            }
        }
    }

    override fun cachedSettingsComputerDetail(serverId: String, machineId: String): SettingsComputerDetailSnapshot? {
        val key = detailKey(serverId, machineId) ?: return null
        return synchronizedDetail {
            if (detailCacheOwner == null || detailCacheOwner != _state.value.owner) return@synchronizedDetail null
            detailCache.remove(key)?.also { detailCache[key] = it }
        }
    }

    override fun recordSettingsComputerDetail(serverId: String, machineId: String, detail: SettingsComputerDetailSnapshot?) {
        val key = detailKey(serverId, machineId) ?: return
        synchronizedDetail {
            val owner = _state.value.owner
            if (detailCacheOwner != owner) {
                detailCache.clear()
                detailCacheOwner = owner
            }
            detailCache.remove(key)
            if (detail != null && owner != null && detail.computer.id.trim() == key.second) {
                detailCache[key] = detail
                while (detailCache.size > MaxCachedDetails) {
                    detailCache.remove(detailCache.keys.first())
                }
            }
        }
    }

    private fun detailKey(serverId: String, machineId: String): Pair<String, String>? {
        val server = serverId.normalizedComputerServerId() ?: return null
        val machine = machineId.trim().takeIf { it.isNotBlank() } ?: return null
        return server to machine
    }

    private val detailLock = SynchronizedObject()

    private fun <R> synchronizedDetail(block: () -> R): R = synchronized(detailLock) { block() }

    /**
     * Lost access to [serverId] (removed from the server / server deleted): its machines leave
     * memory and the account's durable row.
     */
    internal suspend fun dropServer(serverId: String) {
        val normalizedServerId = serverId.normalizedComputerServerId() ?: return
        val owner = mutex.withLock {
            removeServerLocked(normalizedServerId)
            currentOwner
        }
        durableStore?.evictServer(normalizedServerId, owner?.accountId)
    }

    private fun removeServerLocked(serverId: String) {
        synchronizedDetail { detailCache.keys.removeAll { it.first == serverId } }
        loads.remove(serverId)
        generations.remove(serverId)
        serverRecency.remove(serverId)
        if (serverId in _state.value.serverSlices) {
            _state.value = _state.value.copy(serverSlices = _state.value.serverSlices - serverId)
        }
    }

    private fun SharedComputersServerSlice.withComputersFailure(error: ScreenError): SharedComputersServerSlice =
        copy(
            status =
                if (hasSnapshot) {
                    SharedComputersLoadStatus.Loaded
                } else {
                    SharedComputersLoadStatus.Error
                },
            refreshing = false,
            error = error
        )

    private fun installOwnerLocked(owner: SharedComputersOwner) {
        if (currentOwner == owner) return
        currentOwner = owner
        loads.clear()
        generations.clear()
        serverRecency.clear()
        _state.value = SharedComputersState(owner = owner)
    }

    private fun clearOwnerLocked() {
        currentOwner = null
        loads.clear()
        generations.clear()
        serverRecency.clear()
        _state.value = SharedComputersState()
    }

    private suspend fun clearForMissingOwner() {
        mutex.withLock { clearOwnerLocked() }
    }

    private suspend fun clear() {
        mutex.withLock { clearOwnerLocked() }
    }

    private fun setServerSliceLocked(serverId: String, slice: SharedComputersServerSlice) {
        serverRecency.remove(serverId)
        serverRecency += serverId
        var slices = _state.value.serverSlices + (serverId to slice)
        val cap = maxServers.coerceAtLeast(1)
        while (serverRecency.size > cap) {
            val victim = serverRecency.firstOrNull { it != serverId && it !in loads } ?: break
            serverRecency.remove(victim)
            generations.remove(victim)
            slices = slices - victim
        }
        _state.value = _state.value.copy(serverSlices = slices)
    }

    private sealed interface ComputersDurableWrite {
        data class Write(val record: RaftComputersDurableRecord) : ComputersDurableWrite

        data object Evict : ComputersDurableWrite
    }

    internal companion object {
        const val DefaultMaxServers = 16
        const val MaxCachedDetails = 32
    }
}

private fun DataResult<*>.toComputersScreenError(): ScreenError =
    when (this) {
        is DataResult.Error -> error
        is DataResult.NoPermission ->
            ScreenError(code = blocker.code, retryable = false, serverMessage = blocker.serverMessage)
        else -> ScreenError(code = "computers_unavailable", retryable = true)
    }

private fun SharedComputersOwner.durableKey(serverId: String): RaftDurableSnapshotKey? =
    RaftDurableSnapshotKey(environmentId = environmentId, accountId = accountId, serverId = serverId)
        .takeIf { it.rowId != null }

/** Persisted scope name: keep the spelling stable across releases. */
internal const val RaftComputersSnapshotScope = "raft_server_computers_v1"

/**
 * One server's machine list as persisted. Only the fields the list / detail header paint; upgrade
 * policy, version gates and release notes come back with the background refresh.
 */
@Serializable
internal data class RaftComputersDurableRecord(
    val machines: List<RaftDurableComputer>,
    val machineCount: Int = 0,
    val onlineCount: Int = 0,
    val latestDaemonVersion: String? = null,
    val latestComputerVersion: String? = null,
    val canAddComputer: Boolean? = null,
    val savedAtMs: Long = 0L
)

@Serializable
internal data class RaftDurableComputer(
    val id: String,
    val name: String,
    val status: String = "",
    val statusVersion: Long = 0L,
    val apiKeyPrefix: String? = null,
    val runtimes: List<String> = emptyList(),
    val daemonVersion: String? = null,
    val computerVersion: String? = null,
    val isComputer: Boolean = false,
    val hostname: String? = null,
    val os: String? = null,
    val description: String? = null,
    val lastHeartbeat: String? = null,
    val createdAt: String? = null,
    val creatorUserId: String? = null,
    val attachedByCurrentUser: Boolean = false,
    val agentCount: Int = 0,
    val computerUpgradeAvailable: Boolean? = null,
    val remoteUpgradeSupported: Boolean? = null,
    val latestComputerVersion: String? = null,
    /** The managed Computer's creator, so a cold start still shows it on the detail page. */
    val creator: RaftDurableComputerCreator? = null
)

@Serializable
internal data class RaftDurableComputerCreator(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val gravatarHash: String? = null
)

private fun SharedComputersServerSlice.toDurableRecord(): RaftComputersDurableRecord =
    RaftComputersDurableRecord(
        machines =
            machines.map { machine ->
                RaftDurableComputer(
                    id = machine.id,
                    name = machine.name,
                    status = machine.status.wire,
                    statusVersion = machine.statusVersion,
                    apiKeyPrefix = machine.apiKeyPrefix,
                    runtimes = machine.runtimes,
                    daemonVersion = machine.daemonVersion,
                    computerVersion = machine.computerVersion,
                    isComputer = machine.isComputer,
                    hostname = machine.hostname,
                    os = machine.os,
                    description = machine.description,
                    lastHeartbeat = machine.lastHeartbeat,
                    createdAt = machine.createdAt,
                    creatorUserId = machine.creatorUserId,
                    attachedByCurrentUser = machine.attachedByCurrentUser,
                    agentCount = machine.agentCount,
                    computerUpgradeAvailable = machine.computerUpgradeAvailable,
                    remoteUpgradeSupported = machine.remoteUpgradeSupported,
                    latestComputerVersion = machine.latestComputerVersion,
                    creator =
                        machine.creator?.let { creator ->
                            RaftDurableComputerCreator(
                                id = creator.id,
                                name = creator.name,
                                displayName = creator.displayName,
                                avatarUrl = creator.avatarUrl,
                                gravatarHash = creator.gravatarHash
                            )
                        }
                )
            },
        machineCount = machineCount,
        onlineCount = onlineCount,
        latestDaemonVersion = latestDaemonVersion,
        latestComputerVersion = latestComputerVersion,
        canAddComputer = canAddComputer,
        savedAtMs = defaultNowMillis()
    )

private fun RaftComputersDurableRecord.toServerSlice(): SharedComputersServerSlice =
    SharedComputersServerSlice(
        machines =
            machines.map { machine ->
                ComputerSummary(
                    id = machine.id,
                    name = machine.name,
                    status = ComputerStatus.fromWire(machine.status),
                    statusVersion = machine.statusVersion,
                    apiKeyPrefix = machine.apiKeyPrefix,
                    runtimes = machine.runtimes,
                    daemonVersion = machine.daemonVersion,
                    computerVersion = machine.computerVersion,
                    isComputer = machine.isComputer,
                    hostname = machine.hostname,
                    os = machine.os,
                    description = machine.description,
                    lastHeartbeat = machine.lastHeartbeat,
                    createdAt = machine.createdAt,
                    creatorUserId = machine.creatorUserId,
                    attachedByCurrentUser = machine.attachedByCurrentUser,
                    agentCount = machine.agentCount,
                    computerUpgradeAvailable = machine.computerUpgradeAvailable,
                    remoteUpgradeSupported = machine.remoteUpgradeSupported,
                    latestComputerVersion = machine.latestComputerVersion,
                    creator =
                        machine.creator?.let { creator ->
                            SettingsComputerCreatorSnapshot(
                                id = creator.id,
                                name = creator.name,
                                displayName = creator.displayName,
                                avatarUrl = creator.avatarUrl,
                                gravatarHash = creator.gravatarHash
                            )
                        }
                )
            },
        machineCount = machineCount,
        onlineCount = onlineCount,
        latestDaemonVersion = latestDaemonVersion,
        latestComputerVersion = latestComputerVersion,
        status = SharedComputersLoadStatus.Loaded,
        hasSnapshot = true,
        seededFromDurable = true,
        canAddComputer = canAddComputer
    )

private fun DataResult<ComputerListSummary>.logOutcome(): String =
    when (this) {
        is DataResult.Content -> "content machines=${data.machines.size} online=${data.onlineCount}"
        is DataResult.Empty -> "empty code=${reason.name}"
        is DataResult.Error -> "error code=${error.code}"
        is DataResult.NoPermission -> "no_permission code=${blocker.code}"
    }

private fun String?.normalizedComputerServerId(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }

/**
 * The order of every computer list (web `orderSidebarMachineIds`, artin task #53): the computers
 * the current user attached first, then everyone else's, each keeping the server's order. A phone
 * is never a computer itself, so unlike the desktop it pins none. The Members page orders its
 * agent groups by this list too ([build.raft.app.groupedAgentMembers]).
 */
internal fun List<ComputerSummary>.inComputerListOrder(): List<ComputerSummary> =
    sortedBy { if (it.attachedByCurrentUser) 0 else 1 }

private fun List<ComputerSummary>.toCreateAgentMachineOptions(): List<SharedCreateAgentMachineOption> =
    map { machine ->
        SharedCreateAgentMachineOption(
            id = machine.id,
            name = machine.name,
            status =
                when (machine.status) {
                    ComputerStatus.Online -> "online"
                    ComputerStatus.Offline -> "offline"
                    ComputerStatus.Unknown -> "unknown"
                },
            runtimes = machine.runtimes,
            attachedByCurrentUser = machine.attachedByCurrentUser,
            creatorId = machine.creator?.id,
            usageFactsKnown = true
        )
    }

private fun SharedComputersServerSlice.toSettingsComputersResult(): DataResult<SettingsComputersSnapshot> =
    if (hasSnapshot) {
        DataResult.Content(
            SettingsComputersSnapshot(
                computers = machines.inComputerListOrder().map { it.toSettingsComputerSnapshot() },
                canAddComputer = false,
                addComputerUnavailableReason = null,
                latestDaemonVersion = latestDaemonVersion,
                latestComputerVersion = latestComputerVersion
            )
        )
    } else {
        when (status) {
            SharedComputersLoadStatus.Error ->
                DataResult.Error(error ?: ScreenError("computers_unavailable", retryable = true))
            SharedComputersLoadStatus.Idle,
            SharedComputersLoadStatus.InFlight,
            SharedComputersLoadStatus.Loaded ->
                DataResult.Empty(EmptyReason.NoSettingsAvailable)
        }
    }

private fun SharedComputersServerSlice.toSettingsComputerSnapshotResult(machineId: String): DataResult<SettingsComputerSnapshot> =
    when (val result = toSettingsComputersResult()) {
        is DataResult.Content ->
            result.data.computers.firstOrNull { it.id == machineId }
                ?.let { DataResult.Content(it) }
                ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)
        is DataResult.Empty -> DataResult.Empty(result.reason)
        is DataResult.Error -> DataResult.Error(result.error)
        is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
    }
