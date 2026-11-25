package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.TimeSource

@RaftCoreInternalApi enum class SharedComputersLoadStatus {
    Idle,
    InFlight,
    Loaded,
    Error
}

@RaftCoreInternalApi data class SharedComputersPickerSlice(
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

@RaftCoreInternalApi data class SharedComputersServerSlice(
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
    val canAddComputer: Boolean? = null,
    /**
     * When this session saw an online Computer go offline (`machineId -> epochMillis`, web
     * `machineStore.computerOfflineObservedAt`, slock #13076). Never set on initial load or
     * cleared when the machine stays offline; removed once the machine comes back online or leaves.
     */
    val offlineObservedAtMs: Map<String, Long> = emptyMap(),
) {
    val errorCode: String?
        get() = error?.code
}

@RaftCoreInternalApi data class SharedComputersOwner(
    val environmentId: String,
    val accountId: String,
    val sessionGeneration: String
)

/** The computers cache owner for [session]; tokens are not part of it (see [SharedAuthLoginGeneration]). */
@RaftCoreInternalApi fun sharedComputersOwner(
    session: StoredAuthSession,
    environmentId: String,
    loginGeneration: String
): SharedComputersOwner? {
    val cleanEnvironmentId = environmentId.trim().trimEnd('/')
    val accountId = session.user.id.trim()
    if (cleanEnvironmentId.isBlank() || accountId.isBlank()) return null
    return SharedComputersOwner(cleanEnvironmentId, accountId, loginGeneration)
}

@RaftCoreInternalApi data class SharedComputersState(
    val owner: SharedComputersOwner? = null,
    val serverSlices: Map<String, SharedComputersServerSlice> = emptyMap()
)

/**
 * Reactive snapshot emitted by [SharedSettingsComputersSource.observeSettingsComputersForServer]
 * whenever the canonical server slice changes (foreground catch-up, reconnect, realtime
 * `machine:*`, incremental sync `machine` rows, or explicit refresh).
 */
data class SettingsComputersLiveUpdate(
    val serverId: String,
    val snapshot: SettingsComputersSnapshot?,
    val hasSnapshot: Boolean,
    val seededFromDurable: Boolean,
    val refreshing: Boolean = false,
    val error: ScreenError? = null,
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

    /**
     * Reactive stream of canonical computer snapshots for [serverId] (#wg-mobile-feedback:#273).
     * Null for test doubles that do not maintain a reactive store.
     */
    fun observeSettingsComputersForServer(serverId: String): Flow<SettingsComputersLiveUpdate>? = null

    /** Synchronous latest in-memory machine snapshot for [machineId] on [serverId], or null. */
    fun currentSettingsComputerSnapshot(serverId: String, machineId: String): SettingsComputerSnapshot? = null

    /** Remembers the Add Computer gate so the next first frame starts with the right action. */
    fun recordAddComputerGate(serverId: String, canAddComputer: Boolean) = Unit

    /** The last machine page loaded for [machineId] on [serverId] under the current account. */
    fun cachedSettingsComputerDetail(serverId: String, machineId: String): SettingsComputerDetailSnapshot? = null

    /** Records (or, with null, forgets: deleted / lost access) one machine page. */
    fun recordSettingsComputerDetail(serverId: String, machineId: String, detail: SettingsComputerDetailSnapshot?) = Unit
}

@RaftCoreInternalApi fun SharedComputersState.selectComputerServerSlice(serverId: String?): SharedComputersServerSlice {
    val normalizedServerId = serverId.normalizedComputerServerId() ?: return SharedComputersServerSlice()
    return serverSlices[normalizedServerId] ?: SharedComputersServerSlice()
}

@RaftCoreInternalApi fun SharedComputersState.selectComputerPickerSlice(serverId: String?): SharedComputersPickerSlice {
    val slice = selectComputerServerSlice(serverId)
    return SharedComputersPickerSlice(
        machines = slice.machines.inComputerListOrder(owner?.accountId).toCreateAgentMachineOptions(owner?.accountId),
        status = slice.status,
        hasSnapshot = slice.hasSnapshot,
        refreshing = slice.refreshing,
        error = slice.error
    )
}

@RaftCoreInternalApi sealed interface SharedComputersIntent {
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

class SharedComputersStore @RaftCoreInternalApi constructor(
    private val computersApiClient: RaftComputersApiClient,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
    private val ownerProvider: suspend () -> SharedComputersOwner? = {
        SharedComputersOwner("test-environment", "test-account", "test-session")
    },
    /**
     * The owner as a typed account binding (architecture review B, site 9). Defaults to
     * [ownerProvider]: an owner is signed in, none is signed out.
     */
    private val ownerBinding: suspend () -> RaftAccountBinding<SharedComputersOwner> = {
        ownerProvider()?.let { RaftAccountBinding.SignedIn(it) } ?: RaftAccountBinding.SignedOut
    },
    /** Durable last-known machine lists (data-layer plan step 5); null keeps the store memory-only. */
    private val durableStore: RaftKeyedDurableSnapshotStore<RaftComputersDurableRecord>? = null,
    /** Per-store memory cap: least recently used servers leave first and re-seed from disk. */
    private val maxServers: Int = DefaultMaxServers
) : SharedSettingsComputersSource {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SharedComputersState())
    @RaftCoreInternalApi val state: StateFlow<SharedComputersState> = _state.asStateFlow()
    private val loads = mutableMapOf<String, InFlightLoad>()
    private val generations = mutableMapOf<String, Long>()
    private val lastPublishedSucceeded = mutableMapOf<String, Boolean>()
    private val serverRecency = mutableListOf<String>()
    private val observerLock = SynchronizedObject()
    private val activeObservers = mutableMapOf<String, Int>()

    private class InFlightLoad(
        val generation: Long,
        val deferred: Deferred<DataResult<ComputerListSummary>>,
    )

    private data class EnsureLoadOutcome(
        val result: DataResult<ComputerListSummary>?,
        val publishedSucceeded: Boolean,
    )

    /**
     * Servers whose machine list answered "no permission" (a guest's non-auth 403) for this owner.
     * An Ensure (a card naming a computer, a picker opening) never asks again for them while the
     * owner stays; only a forced refresh (the person's own pull) does. Cleared with the owner.
     */
    private val deniedServers = mutableSetOf<String>()

    // Memory-only machine pages (workspaces / agents sections), LRU, cleared with the owner.
    private val detailCache = linkedMapOf<Pair<String, String>, SettingsComputerDetailSnapshot>()
    private var detailCacheOwner: SharedComputersOwner? = null
    private var currentOwner: SharedComputersOwner? = null
    private var nextGeneration: Long = 0L

    @RaftCoreInternalApi fun computerPickerSliceFlow(serverId: String?): Flow<SharedComputersPickerSlice> =
        state
            .map { state -> state.selectComputerPickerSlice(serverId) }
            .distinctUntilChanged()

    /** The server's machines as the migration section names and targets them. */
    @RaftCoreInternalApi fun migrationComputersFlow(serverId: String?): Flow<List<build.raft.app.AgentMigrationComputer>> =
        state
            .map { state ->
                val userId = state.owner?.accountId
                state.selectComputerServerSlice(serverId).machines.map {
                    build.raft.app.AgentMigrationComputer(
                        id = it.id,
                        name = it.name,
                        isComputer = it.isComputer,
                        online = it.status == ComputerStatus.Online,
                        offline = it.status == ComputerStatus.Offline,
                        lastHeartbeat = it.lastHeartbeat,
                        agentCount = it.agentCount,
                        diskStatus = it.diskStatus,
                        memoryStatus = it.memoryStatus,
                        attachedByCurrentUser = it.attachedByCurrentUser,
                        creatorUserId = it.creatorUserId,
                        mine = it.isMyComputer(userId)
                    )
                }
            }
            .distinctUntilChanged()

    /** The server's machines as the migration log's places. */
    @RaftCoreInternalApi fun migrationLogMachinesFlow(serverId: String?): Flow<List<AgentMigrationLogMachine>> =
        state
            .map { state ->
                state.selectComputerServerSlice(serverId).machines.map {
                    AgentMigrationLogMachine(id = it.id, name = it.name, hostProvider = it.hostProvider, deviceClass = it.deviceClass)
                }
            }
            .distinctUntilChanged()

    override fun observeSettingsComputersForServer(serverId: String): Flow<SettingsComputersLiveUpdate>? {
        val normalizedServerId = serverId.normalizedComputerServerId() ?: return null
        return flow {
            addObserver(normalizedServerId)
            try {
                state
                    .map { it.toSettingsComputersLiveUpdate(normalizedServerId) }
                    .distinctUntilChanged()
                    .collect(::emit)
            } finally {
                removeObserver(normalizedServerId)
            }
        }
    }

    override fun currentSettingsComputerSnapshot(serverId: String, machineId: String): SettingsComputerSnapshot? {
        val normalizedServerId = serverId.normalizedComputerServerId() ?: return null
        val normalizedMachineId = machineId.trim().takeIf { it.isNotBlank() } ?: return null
        return (_state.value.selectComputerServerSlice(normalizedServerId)
            .takeIf { it.hasSnapshot }
            ?.toSettingsComputerSnapshotResult(normalizedMachineId) as? DataResult.Content)
            ?.data
    }

    @RaftCoreInternalApi fun hasActiveObservers(serverId: String): Boolean {
        val key = serverId.normalizedComputerServerId() ?: return false
        return synchronized(observerLock) { (activeObservers[key] ?: 0) > 0 }
    }

    @RaftCoreInternalApi fun hasLoadedOrObservedServer(serverId: String): Boolean {
        val key = serverId.normalizedComputerServerId() ?: return false
        if (hasActiveObservers(key)) return true
        return _state.value.selectComputerServerSlice(key).hasSnapshot
    }

    @RaftCoreInternalApi fun loadedOrObservedServerIds(): Set<String> {
        val observed = synchronized(observerLock) { activeObservers.keys.toSet() }
        val loaded =
            _state.value.serverSlices.entries.mapNotNullTo(LinkedHashSet()) { (id, slice) ->
                id.takeIf { slice.hasSnapshot }
            }
        return observed + loaded
    }

    private fun addObserver(serverId: String) {
        synchronized(observerLock) {
            activeObservers[serverId] = (activeObservers[serverId] ?: 0) + 1
        }
    }

    private fun removeObserver(serverId: String) {
        synchronized(observerLock) {
            val next = (activeObservers[serverId] ?: 0) - 1
            if (next <= 0) {
                activeObservers.remove(serverId)
            } else {
                activeObservers[serverId] = next
            }
        }
    }

    @RaftCoreInternalApi fun dispatch(intent: SharedComputersIntent) {
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

    @RaftCoreInternalApi suspend fun ensureComputerPickerForServer(
        serverId: String,
        force: Boolean = false,
        priority: DataRequestPriority = DataRequestPriority.Visible
    ) {
        ensureComputersForServer(serverId = serverId, force = force, priority = priority)
    }

    /** Typed owner read: a throwing read is [RaftAccountBinding.Unknown], never a sign-out. */
    private suspend fun readOwnerBinding(): RaftAccountBinding<SharedComputersOwner> =
        runCatchingPreservingCancellation { ownerBinding() }.getOrElse { RaftAccountBinding.Unknown }

    /**
     * Follows the signed-in owner. Only a sign-out clears the lists; a session that is not known
     * yet or cannot be read keeps what is shown (architecture review B, site 9).
     */
    @RaftCoreInternalApi suspend fun reconcileOwner() {
        when (val binding = readOwnerBinding()) {
            is RaftAccountBinding.SignedIn -> mutex.withLock { installOwnerLocked(binding.account) }
            RaftAccountBinding.SignedOut -> mutex.withLock { clearOwnerLocked() }
            RaftAccountBinding.Unknown, is RaftAccountBinding.Corrupt ->
                logger("shared_computers_owner_kept reason=${binding.unknownReason()}")
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
        val state = _state.value
        return state.selectComputerServerSlice(normalizedServerId).toSettingsComputersResult(state.owner?.accountId)
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
        priority: DataRequestPriority,
        supersedeInFlight: Boolean = false,
    ): DataResult<ComputerListSummary>? =
        ensureComputersForServerOutcome(
            serverId = serverId,
            force = force,
            priority = priority,
            supersedeInFlight = supersedeInFlight,
        ).result

    private suspend fun ensureComputersForServerOutcome(
        serverId: String,
        force: Boolean,
        priority: DataRequestPriority,
        supersedeInFlight: Boolean = false,
    ): EnsureLoadOutcome {
        val startedAt = TimeSource.Monotonic.markNow()
        val normalizedServerId = serverId.normalizedComputerServerId()
        if (normalizedServerId == null) {
            logger("shared_computers_skip reason=server_id_missing")
            logger("store_load domain=computers serverId= result=skip reason=server_id_missing durationMs=${startedAt.elapsedNow().inWholeMilliseconds}")
            return EnsureLoadOutcome(result = null, publishedSucceeded = false)
        }
        val owner =
            when (val binding = readOwnerBinding()) {
                is RaftAccountBinding.SignedIn -> binding.account
                RaftAccountBinding.SignedOut -> {
                    clearForMissingOwner()
                    logger("store_load domain=computers serverId=$normalizedServerId result=skip reason=owner_missing")
                    return EnsureLoadOutcome(result = null, publishedSucceeded = false)
                }
                RaftAccountBinding.Unknown, is RaftAccountBinding.Corrupt -> {
                    logger(
                        "store_load domain=computers serverId=$normalizedServerId result=skip " +
                            "reason=owner_unknown cause=${binding.unknownReason()}"
                    )
                    return EnsureLoadOutcome(result = null, publishedSucceeded = false)
                }
            }
        hydrateFromDurable(owner, normalizedServerId)
        val durableEpoch = durableStore?.epoch ?: 0L
        val initialFlight = mutex.withLock {
            installOwnerLocked(owner)
            val current = _state.value.selectComputerServerSlice(normalizedServerId)
            if (!force && normalizedServerId in deniedServers) {
                logger("shared_computers_skip serverId=$normalizedServerId reason=no_permission_remembered")
                return@withLock null
            }
            if (!force && current.hasSnapshot && !current.seededFromDurable) {
                logger("shared_computers_skip serverId=$normalizedServerId reason=snapshot_available")
                logger(
                    "store_load domain=computers serverId=$normalizedServerId result=skip reason=snapshot_available " +
                        "force=$force status=${current.status.name} machines=${current.machines.size} " +
                        "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
                return@withLock null
            }
            if (supersedeInFlight) {
                // Retire the older in-flight load and install its replacement atomically under the
                // same lock so callers waiting on the superseded load always find the replacement
                // instead of falling back to a stale pre-background _state (#wg-mobile-feedback:#273).
                supersedeLoadLocked(normalizedServerId)
            } else {
                loads[normalizedServerId]?.let { active ->
                    logger("shared_computers_join serverId=$normalizedServerId reason=inflight priority=${priority.name}")
                    return@withLock active
                }
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
            val deferred =
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
                }
            InFlightLoad(generation = generation, deferred = deferred).also {
                loads[normalizedServerId] = it
            }
        }
        var flight = initialFlight ?: return EnsureLoadOutcome(result = null, publishedSucceeded = false)
        while (true) {
            flight.deferred.start()
            val result = flight.deferred.await()
            val next =
                mutex.withLock {
                    if (currentOwner != owner) return@withLock null to false
                    if (generations[normalizedServerId] == flight.generation) {
                        val ownSucceeded = result is DataResult.Content || result is DataResult.Empty
                        return@withLock null to ownSucceeded
                    }
                    val replacement = loads[normalizedServerId]
                    if (replacement != null && replacement !== flight) {
                        replacement to false
                    } else {
                        // A newer generation already finished and published while this superseded
                        // flight was still in flight: report the winning generation's outcome,
                        // never the dropped flight's outcome.
                        null to (lastPublishedSucceeded[normalizedServerId] ?: false)
                    }
                }
            val replacement = next.first
            if (replacement == null) {
                return EnsureLoadOutcome(result = result, publishedSucceeded = next.second)
            }
            flight = replacement
        }
    }

    private suspend fun publishResult(
        owner: SharedComputersOwner,
        serverId: String,
        generation: Long,
        result: DataResult<ComputerListSummary>,
        durableEpoch: Long
    ) {
        val publicationBinding = readOwnerBinding()
        val publicationOwner = publicationBinding.accountOrNull
        val durableWrite = mutex.withLock {
            if (currentOwner != owner || generations[serverId] != generation) {
                logger("shared_computers_drop serverId=$serverId reason=stale_generation generation=$generation")
                return@withLock null
            }
            if (publicationOwner != owner) {
                when (publicationBinding) {
                    is RaftAccountBinding.SignedIn -> installOwnerLocked(publicationBinding.account)
                    RaftAccountBinding.SignedOut -> clearOwnerLocked()
                    // Not known / unreadable: the answer is not published, nothing is cleared.
                    RaftAccountBinding.Unknown, is RaftAccountBinding.Corrupt -> Unit
                }
                logger("shared_computers_drop serverId=$serverId reason=owner_changed generation=$generation")
                return@withLock null
            }
            if (loads[serverId]?.generation == generation) {
                loads.remove(serverId)
            }
            val current = _state.value.selectComputerServerSlice(serverId)
            if (result is DataResult.NoPermission && result.isConversationAccessLost()) {
                deniedServers += serverId
            } else if (result is DataResult.Content || result is DataResult.Empty) {
                deniedServers -= serverId
            }
            if (result.isConversationAccessLost()) {
                // Tenny rule 2: the account lost the server's machine list (non-auth 403 / 404).
                // Nothing cached may paint again; the page shows the terminal state.
                removeServerLocked(serverId)
                lastPublishedSucceeded[serverId] = false
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
                            canAddComputer = current.canAddComputer,
                            offlineObservedAtMs =
                                if (current.hasSnapshot) {
                                    reconcileComputerOfflineObservedAtMs(
                                        previousMachines = current.machines,
                                        nextMachines = result.data.machines,
                                        previousObserved = current.offlineObservedAtMs,
                                        nowMillis = defaultNowMillis(),
                                    )
                                } else {
                                    emptyMap()
                                },
                        )
                    is DataResult.Empty ->
                        SharedComputersServerSlice(
                            status = SharedComputersLoadStatus.Loaded,
                            hasSnapshot = true,
                            canAddComputer = current.canAddComputer,
                            offlineObservedAtMs = emptyMap(),
                        )
                    is DataResult.Error -> current.withComputersFailure(result.error)
                    is DataResult.NoPermission -> current.withComputersFailure(result.toComputersScreenError())
                }
            lastPublishedSucceeded[serverId] = result is DataResult.Content || result is DataResult.Empty
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
     * Incremental sync (`GET /api/sync/changes` `machine` rows, each a `machines` item of the list
     * endpoint): patch [serverId]'s machine list row by row instead of reloading it. A server whose
     * list is not loaded is left alone (its next load reads the server), unless its first load is in
     * flight: that one may have been read before these rows, so one more load follows it (Tenny
     * 10-27). Returns whether it changed.
     */
    @RaftCoreInternalApi suspend fun applySyncedMachineRows(
        serverId: String,
        upserts: List<ComputerSummary>,
        deletes: Set<String>
    ): Boolean {
        val key = serverId.normalizedComputerServerId() ?: return false
        if (upserts.isEmpty() && deletes.isEmpty()) return false
        val owner = runCatchingPreservingCancellation { ownerProvider() }.getOrNull() ?: return false
        val durableEpoch = durableStore?.epoch ?: 0L
        var firstLoadInFlight: Deferred<DataResult<ComputerListSummary>>? = null
        val record =
            mutex.withLock {
                if (currentOwner != owner) return@withLock null
                val current = _state.value.selectComputerServerSlice(key)
                if (!current.hasSnapshot) {
                    firstLoadInFlight = loads[key]?.deferred
                    return@withLock null
                }
                val releaseNotes = current.machines.firstNotNullOfOrNull { it.latestComputerReleaseNotes }
                val incoming =
                    upserts
                        .filter { it.id !in deletes }
                        .associateBy { it.id }
                        .mapValues { (_, machine) ->
                            val previous = current.machines.firstOrNull { it.id == machine.id }
                            machine.copy(
                                latestComputerVersion = current.latestComputerVersion,
                                latestComputerReleaseNotes = releaseNotes,
                                // A sync row never carries `attention` (the list computes it per viewer): keep the list's.
                                loginSteps = machine.loginSteps ?: previous?.loginSteps,
                                serverNeedsAttention = machine.serverNeedsAttention ?: previous?.serverNeedsAttention
                            )
                        }
                val kept = current.machines.filterNot { it.id in deletes }
                val nextMachines =
                    (kept.map { incoming[it.id] ?: it } + incoming.values.filter { new -> kept.none { it.id == new.id } })
                        .inComputerListOrder(owner.accountId)
                if (nextMachines == current.machines) return@withLock null
                // A load already in flight read the list without these rows: its answer is dropped.
                val superseded = supersedeLoadLocked(key)
                val next =
                    current.copy(
                        machines = nextMachines,
                        machineCount = nextMachines.size,
                        onlineCount = nextMachines.count { it.status == ComputerStatus.Online },
                        refreshing = if (superseded) false else current.refreshing,
                        offlineObservedAtMs =
                            reconcileComputerOfflineObservedAtMs(
                                previousMachines = current.machines,
                                nextMachines = nextMachines,
                                previousObserved = current.offlineObservedAtMs,
                                nowMillis = defaultNowMillis(),
                            ),
                    )
                setServerSliceLocked(key, next)
                lastPublishedSucceeded[key] = true
                logger("shared_computers_sync_apply serverId=$key upserts=${incoming.size} deletes=${deletes.size}")
                next.toDurableRecord()
            }
        if (record == null) {
            firstLoadInFlight?.let { first ->
                logger("shared_computers_sync_reload_after_first_load serverId=$key")
                scope.launch {
                    first.join()
                    ensureComputersForServer(serverId = key, force = true, priority = DataRequestPriority.Background)
                }
            }
            return false
        }
        val store = durableStore
        val durableKey = owner.durableKey(key)
        if (store != null && durableKey != null) store.write(durableKey, record, durableEpoch)
        return true
    }

    /**
     * Incremental sync's forced reload of [serverId]'s machine list. A plain forced load joins a load
     * already in flight, which may have been read before the change that asked for this one and would
     * then publish without it, the change never coming again (Tenny 10-27). The in-flight load's
     * generation is retired first (its answer is dropped), so this one reads the server again.
     */
    @RaftCoreInternalApi suspend fun reloadMachinesForSync(serverId: String): DataResult<SettingsComputersSnapshot> {
        val key = serverId.normalizedComputerServerId() ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        ensureComputersForServer(
            serverId = key,
            force = true,
            priority = DataRequestPriority.Visible,
            supersedeInFlight = true,
        )
        val state = _state.value
        return state.selectComputerServerSlice(key).toSettingsComputersResult(state.owner?.accountId)
    }

    /**
     * Same forced reload as [reloadMachinesForSync], returning whether the network fetch itself
     * succeeded (`Content` / `Empty`) rather than falling back to a stale cached snapshot on error.
     */
    @RaftCoreInternalApi suspend fun reloadMachinesForSyncSucceeded(serverId: String): Boolean {
        val key = serverId.normalizedComputerServerId() ?: return false
        return ensureComputersForServerOutcome(
            serverId = key,
            force = true,
            priority = DataRequestPriority.Visible,
            supersedeInFlight = true,
        ).publishedSucceeded
    }

    /** Retires [serverId]'s in-flight load, if any: true when there was one. */
    private fun supersedeLoadLocked(serverId: String): Boolean {
        loads.remove(serverId) ?: return false
        generations[serverId] = ++nextGeneration
        logger("shared_computers_supersede serverId=$serverId reason=sync_changes generation=${generations[serverId]}")
        return true
    }

    /** Machine id -> name of [serverId]'s loaded list (empty when not loaded). */
    @RaftCoreInternalApi fun currentMachineNames(serverId: String): Map<String, String> {
        val key = serverId.normalizedComputerServerId() ?: return emptyMap()
        return _state.value.selectComputerServerSlice(key).machines.associate { it.id to it.name }
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
                ?.toSettingsComputersResult(owner.accountId)
        }
    }

    /** Synchronous first-frame read: the machine list currently held in memory for [serverId]. */
    @RaftCoreInternalApi fun currentSettingsComputers(serverId: String?): SettingsComputersSnapshot? {
        val state = _state.value
        val slice = state.selectComputerServerSlice(serverId).takeIf { it.hasSnapshot } ?: return null
        return (slice.toSettingsComputersResult(state.owner?.accountId) as? DataResult.Content)?.data?.let { snapshot ->
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
    @RaftCoreInternalApi suspend fun dropServer(serverId: String) {
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
        lastPublishedSucceeded.remove(serverId)
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
        deniedServers.clear()
        loads.clear()
        generations.clear()
        lastPublishedSucceeded.clear()
        serverRecency.clear()
        _state.value = SharedComputersState(owner = owner)
    }

    private fun clearOwnerLocked() {
        currentOwner = null
        deniedServers.clear()
        loads.clear()
        generations.clear()
        lastPublishedSucceeded.clear()
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
            lastPublishedSucceeded.remove(victim)
            slices = slices - victim
        }
        _state.value = _state.value.copy(serverSlices = slices)
    }

    private sealed interface ComputersDurableWrite {
        data class Write(val record: RaftComputersDurableRecord) : ComputersDurableWrite

        data object Evict : ComputersDurableWrite
    }

    @RaftCoreInternalApi companion object {
        const val DefaultMaxServers = 16
        const val MaxCachedDetails = 32
    }
}

/**
 * Tracks when this session observed an online Computer go offline (web
 * `machineStore.computerOfflineObservedAt`, slock #13076): recorded on an `Online -> Offline`
 * transition, kept while it stays offline, and cleared once it returns online or leaves the list.
 */
@RaftCoreInternalApi fun reconcileComputerOfflineObservedAtMs(
    previousMachines: List<ComputerSummary>,
    nextMachines: List<ComputerSummary>,
    previousObserved: Map<String, Long>,
    nowMillis: Long,
): Map<String, Long> {
    val previousStatusById = previousMachines.associate { it.id to it.status }
    val nextObserved = LinkedHashMap<String, Long>()
    nextMachines.forEach { machine ->
        when (machine.status) {
            ComputerStatus.Offline -> {
                val prior = previousObserved[machine.id]
                when {
                    prior != null -> nextObserved[machine.id] = prior
                    previousStatusById[machine.id] == ComputerStatus.Online -> nextObserved[machine.id] = nowMillis
                }
            }
            ComputerStatus.Online, ComputerStatus.Unknown -> Unit
        }
    }
    return nextObserved
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
@RaftCoreInternalApi const val RaftComputersSnapshotScope = "raft_server_computers_v1"

/**
 * One server's machine list as persisted. Only the fields the list / detail header paint; upgrade
 * policy, version gates and release notes come back with the background refresh.
 */
@Serializable
@RaftCoreInternalApi data class RaftComputersDurableRecord(
    val machines: List<RaftDurableComputer>,
    val machineCount: Int = 0,
    val onlineCount: Int = 0,
    val latestDaemonVersion: String? = null,
    val latestComputerVersion: String? = null,
    val canAddComputer: Boolean? = null,
    val savedAtMs: Long = 0L
)

@Serializable
@RaftCoreInternalApi data class RaftDurableComputer(
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
    val osRelease: String? = null,
    /** Server `hostProvider`, so a cold start groups by Provider as the live list does. */
    val hostProvider: String? = null,
    /** Server `deviceClass` ("laptop", "server", ...), so a cold start draws the right device icon. */
    val deviceClass: String? = null,
    val runtimeVersions: Map<String, String> = emptyMap(),
    val description: String? = null,
    val lastHeartbeat: String? = null,
    val createdAt: String? = null,
    val creatorUserId: String? = null,
    val attachedByCurrentUser: Boolean = false,
    /** Server `controlledByCurrentUser` (slock #15002), so a cold start keeps the controller's rights. */
    val controlledByCurrentUser: Boolean? = null,
    val agentCount: Int = 0,
    val computerUpgradeAvailable: Boolean? = null,
    val remoteUpgradeSupported: Boolean? = null,
    val latestComputerVersion: String? = null,
    /** The managed Computer's creator, so a cold start still shows it on the detail page. */
    val creator: RaftDurableComputerCreator? = null,
    /** Computer owner v1: who Raft records as the owner (null = Public), so a cold start shows it too. */
    val ownerUserId: String? = null,
    val owner: RaftDurableComputerCreator? = null,
    /** Server `revocation` (slock #16468), so a cold start paints Key revoked instead of flashing Offline. */
    val revocation: RaftDurableComputerRevocation? = null,
    /** Server `versionRefusedAt` (slock #16514), for offline reinstall eligibility across cold starts. */
    val versionRefusedAt: String? = null
)

@Serializable
@RaftCoreInternalApi data class RaftDurableComputerRevocation(
    val at: String,
    val via: String? = null,
    val byUserName: String? = null,
    val subjectUserName: String? = null,
    val departureReason: String? = null
)

private fun SettingsComputerRevocationSnapshot.toDurableComputerRevocation(): RaftDurableComputerRevocation =
    RaftDurableComputerRevocation(
        at = at,
        via = via?.wire,
        byUserName = byUserName,
        subjectUserName = subjectUserName,
        departureReason = departureReason
    )

private fun RaftDurableComputerRevocation.toComputerRevocationSnapshot(): SettingsComputerRevocationSnapshot? {
    val cleanAt = at.trim().takeIf { it.isNotBlank() } ?: return null
    return SettingsComputerRevocationSnapshot(
        at = cleanAt,
        via = SettingsComputerRevokedVia.fromWire(via),
        byUserName = byUserName?.trim()?.takeIf { it.isNotBlank() },
        subjectUserName = subjectUserName?.trim()?.takeIf { it.isNotBlank() },
        departureReason = departureReason?.trim()?.takeIf { it == "left" || it == "removed" || it == "account_deleted" }
    )
}

@Serializable
@RaftCoreInternalApi data class RaftDurableComputerCreator(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val gravatarHash: String? = null
)

private fun SettingsComputerCreatorSnapshot.toDurableComputerCreator(): RaftDurableComputerCreator =
    RaftDurableComputerCreator(id = id, name = name, displayName = displayName, avatarUrl = avatarUrl, gravatarHash = gravatarHash)

private fun RaftDurableComputerCreator.toComputerCreatorSnapshot(): SettingsComputerCreatorSnapshot =
    SettingsComputerCreatorSnapshot(id = id, name = name, displayName = displayName, avatarUrl = avatarUrl, gravatarHash = gravatarHash)

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
                    osRelease = machine.osRelease,
                    hostProvider = machine.hostProvider,
                    deviceClass = machine.deviceClass,
                    runtimeVersions = machine.runtimeVersions,
                    description = machine.description,
                    lastHeartbeat = machine.lastHeartbeat,
                    createdAt = machine.createdAt,
                    creatorUserId = machine.creatorUserId,
                    attachedByCurrentUser = machine.attachedByCurrentUser,
                    controlledByCurrentUser = machine.controlledByCurrentUser,
                    agentCount = machine.agentCount,
                    computerUpgradeAvailable = machine.computerUpgradeAvailable,
                    remoteUpgradeSupported = machine.remoteUpgradeSupported,
                    latestComputerVersion = machine.latestComputerVersion,
                    creator = machine.creator?.toDurableComputerCreator(),
                    ownerUserId = machine.ownerUserId,
                    owner = machine.owner?.toDurableComputerCreator(),
                    revocation = machine.revocation?.toDurableComputerRevocation(),
                    versionRefusedAt = machine.versionRefusedAt
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
                    osRelease = machine.osRelease,
                    hostProvider = machine.hostProvider,
                    deviceClass = machine.deviceClass,
                    runtimeVersions = machine.runtimeVersions,
                    description = machine.description,
                    lastHeartbeat = machine.lastHeartbeat,
                    createdAt = machine.createdAt,
                    creatorUserId = machine.creatorUserId,
                    attachedByCurrentUser = machine.attachedByCurrentUser,
                    controlledByCurrentUser = machine.controlledByCurrentUser,
                    agentCount = machine.agentCount,
                    computerUpgradeAvailable = machine.computerUpgradeAvailable,
                    remoteUpgradeSupported = machine.remoteUpgradeSupported,
                    latestComputerVersion = machine.latestComputerVersion,
                    creator = machine.creator?.toComputerCreatorSnapshot(),
                    ownerUserId = machine.ownerUserId,
                    owner = machine.owner?.toComputerCreatorSnapshot(),
                    revocation = machine.revocation?.toComputerRevocationSnapshot(),
                    versionRefusedAt = machine.versionRefusedAt?.trim()?.takeIf { it.isNotBlank() }
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
 * The order of every computer list (web `orderSidebarMachineIds`, artin task #53, #triage:#332):
 * the viewer's own computers ([isMyComputer]: attached by [userId] or owned by them) first, then
 * everyone else's, each keeping the server's order. A phone is never a computer itself, so unlike
 * the desktop it pins none. The Members page orders its agent groups by this list too
 * ([build.raft.app.groupedAgentMembers]).
 */
@RaftCoreInternalApi fun List<ComputerSummary>.inComputerListOrder(userId: String?): List<ComputerSummary> =
    raftMineFirst { it.isMyComputer(userId) }

private fun List<ComputerSummary>.toCreateAgentMachineOptions(userId: String?): List<SharedCreateAgentMachineOption> =
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
            usageFactsKnown = true,
            mine = machine.isMyComputer(userId)
        )
    }

private fun SharedComputersState.toSettingsComputersLiveUpdate(serverId: String): SettingsComputersLiveUpdate {
    val slice = selectComputerServerSlice(serverId)
    val snapshot =
        if (slice.hasSnapshot) {
            (slice.toSettingsComputersResult(owner?.accountId) as? DataResult.Content)?.data?.let { raw ->
                val canAdd = slice.canAddComputer ?: false
                raw.copy(
                    canAddComputer = canAdd,
                    addComputerUnavailableReason = if (canAdd) null else "server_admin_required"
                )
            }
        } else {
            null
        }
    return SettingsComputersLiveUpdate(
        serverId = serverId,
        snapshot = snapshot,
        hasSnapshot = slice.hasSnapshot,
        seededFromDurable = slice.seededFromDurable,
        refreshing = slice.refreshing,
        error = slice.error,
    )
}

private fun SharedComputersServerSlice.toSettingsComputersResult(userId: String?): DataResult<SettingsComputersSnapshot> =
    if (hasSnapshot) {
        DataResult.Content(
            SettingsComputersSnapshot(
                computers = machines.inComputerListOrder(userId).map { it.toSettingsComputerSnapshot() },
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
    when (val result = toSettingsComputersResult(userId = null)) {
        is DataResult.Content ->
            result.data.computers.firstOrNull { it.id == machineId }
                ?.let { DataResult.Content(it) }
                ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)
        is DataResult.Empty -> DataResult.Empty(result.reason)
        is DataResult.Error -> DataResult.Error(result.error)
        is DataResult.NoPermission -> DataResult.NoPermission(result.blocker)
    }
