package build.raft.app.data

import build.raft.app.SharedCreateAgentMachineOption
import build.raft.app.model.ServerId
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val error: ScreenError? = null
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
}

internal fun SharedComputersState.selectComputerServerSlice(serverId: String?): SharedComputersServerSlice {
    val normalizedServerId = serverId.normalizedComputerServerId() ?: return SharedComputersServerSlice()
    return serverSlices[normalizedServerId] ?: SharedComputersServerSlice()
}

internal fun SharedComputersState.selectComputerPickerSlice(serverId: String?): SharedComputersPickerSlice {
    val slice = selectComputerServerSlice(serverId)
    return SharedComputersPickerSlice(
        machines = slice.machines.toCreateAgentMachineOptions(),
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
    }
) : SharedSettingsComputersSource {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SharedComputersState())
    internal val state: StateFlow<SharedComputersState> = _state.asStateFlow()
    private val loads = mutableMapOf<String, Deferred<DataResult<ComputerListSummary>>>()
    private val generations = mutableMapOf<String, Long>()
    private var currentOwner: SharedComputersOwner? = null
    private var nextGeneration: Long = 0L

    internal fun computerPickerSliceFlow(serverId: String?): Flow<SharedComputersPickerSlice> =
        state
            .map { state -> state.selectComputerPickerSlice(serverId) }
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
        val deferred = mutex.withLock {
            installOwnerLocked(owner)
            val current = _state.value.selectComputerServerSlice(normalizedServerId)
            if (!force && current.hasSnapshot) {
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
                    result = result
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
        result: DataResult<ComputerListSummary>
    ) {
        val publicationOwner =
            runCatchingPreservingCancellation { ownerProvider() }.getOrNull()
        mutex.withLock {
            if (currentOwner != owner || generations[serverId] != generation) {
                logger("shared_computers_drop serverId=$serverId reason=stale_generation generation=$generation")
                return@withLock
            }
            if (publicationOwner != owner) {
                if (publicationOwner == null) {
                    clearOwnerLocked()
                } else {
                    installOwnerLocked(publicationOwner)
                }
                logger("shared_computers_drop serverId=$serverId reason=owner_changed generation=$generation")
                return@withLock
            }
            loads.remove(serverId)
            val current = _state.value.selectComputerServerSlice(serverId)
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
                            hasSnapshot = true
                        )
                    is DataResult.Empty ->
                        SharedComputersServerSlice(
                            status = SharedComputersLoadStatus.Loaded,
                            hasSnapshot = true
                        )
                    is DataResult.Error -> current.withComputersFailure(result.error)
                    is DataResult.NoPermission ->
                        current.withComputersFailure(
                            ScreenError(
                                code = result.blocker.code,
                                retryable = false,
                                serverMessage = result.blocker.serverMessage
                            )
                        )
                }
            setServerSliceLocked(serverId, next)
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
        _state.value = SharedComputersState(owner = owner)
    }

    private fun clearOwnerLocked() {
        currentOwner = null
        loads.clear()
        generations.clear()
        _state.value = SharedComputersState()
    }

    private suspend fun clearForMissingOwner() {
        mutex.withLock { clearOwnerLocked() }
    }

    private suspend fun clear() {
        mutex.withLock { clearOwnerLocked() }
    }

    private fun setServerSliceLocked(serverId: String, slice: SharedComputersServerSlice) {
        _state.value = _state.value.copy(serverSlices = _state.value.serverSlices + (serverId to slice))
    }
}

private fun DataResult<ComputerListSummary>.logOutcome(): String =
    when (this) {
        is DataResult.Content -> "content machines=${data.machines.size} online=${data.onlineCount}"
        is DataResult.Empty -> "empty code=${reason.name}"
        is DataResult.Error -> "error code=${error.code}"
        is DataResult.NoPermission -> "no_permission code=${blocker.code}"
    }

private fun String?.normalizedComputerServerId(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }

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
            runtimes = machine.runtimes
        )
    }

private fun SharedComputersServerSlice.toSettingsComputersResult(): DataResult<SettingsComputersSnapshot> =
    if (hasSnapshot) {
        DataResult.Content(
            SettingsComputersSnapshot(
                computers = machines.map { it.toSettingsComputerSnapshot() },
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

private fun ComputerSummary.toSettingsComputerSnapshot(): SettingsComputerSnapshot =
    SettingsComputerSnapshot(
        id = id,
        name = name,
        daemonVersion = daemonVersion.orEmpty(),
        computerVersion = computerVersion,
        isComputer = isComputer,
        online = status == ComputerStatus.Online,
        hostname = hostname,
        platform = os,
        lastSeenLabel = lastHeartbeat,
        runtimes = runtimes,
        createdAt = createdAt,
        lastHeartbeat = lastHeartbeat,
        apiKeyPrefix = apiKeyPrefix,
        statusVersion = statusVersion
    )
