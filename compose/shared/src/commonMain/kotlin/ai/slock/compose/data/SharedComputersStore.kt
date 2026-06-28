package ai.slock.compose.data

import ai.slock.compose.SharedCreateAgentMachineOption
import ai.slock.compose.model.ServerId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    val errorCode: String? = null
)

internal data class SharedComputersServerSlice(
    val machines: List<ComputerSummary> = emptyList(),
    val machineCount: Int = 0,
    val onlineCount: Int = 0,
    val latestDaemonVersion: String? = null,
    val status: SharedComputersLoadStatus = SharedComputersLoadStatus.Idle,
    val errorCode: String? = null
)

internal data class SharedComputersState(
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
        errorCode = slice.errorCode
    )
}

internal sealed interface SharedComputersIntent {
    data class EnsureComputerPicker(val serverId: String) : SharedComputersIntent
    data class RefreshComputerPicker(val serverId: String) : SharedComputersIntent
    data object Clear : SharedComputersIntent
}

class SharedComputersStore internal constructor(
    private val computersApiClient: SlockComputersApiClient,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {}
) : SharedSettingsComputersSource {
    private val _state = MutableStateFlow(SharedComputersState())
    internal val state: StateFlow<SharedComputersState> = _state.asStateFlow()

    internal fun computerPickerSliceFlow(serverId: String?): Flow<SharedComputersPickerSlice> =
        state
            .map { state -> state.selectComputerPickerSlice(serverId) }
            .distinctUntilChanged()

    internal fun dispatch(intent: SharedComputersIntent) {
        when (intent) {
            is SharedComputersIntent.EnsureComputerPicker ->
                loadComputerPicker(serverId = intent.serverId, force = false)
            is SharedComputersIntent.RefreshComputerPicker ->
                loadComputerPicker(serverId = intent.serverId, force = true)
            SharedComputersIntent.Clear -> _state.value = SharedComputersState()
        }
    }

    private fun loadComputerPicker(serverId: String, force: Boolean) {
        scope.launch {
            ensureComputerPickerForServer(serverId = serverId, force = force)
        }
    }

    internal suspend fun ensureComputerPickerForServer(serverId: String, force: Boolean = false) {
        ensureComputersForServer(serverId = serverId, force = force)
    }

    override suspend fun loadSettingsComputersForServer(serverId: String, force: Boolean): DataResult<SettingsComputersSnapshot> {
        val normalizedServerId =
            serverId.normalizedComputerServerId()
                ?: return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        ensureComputersForServer(serverId = normalizedServerId, force = force)
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
        ensureComputersForServer(serverId = normalizedServerId, force = force)
        return _state.value
            .selectComputerServerSlice(normalizedServerId)
            .toSettingsComputerSnapshotResult(normalizedMachineId)
    }

    private suspend fun ensureComputersForServer(serverId: String, force: Boolean = false) {
        val startedAt = TimeSource.Monotonic.markNow()
        val normalizedServerId = serverId.normalizedComputerServerId()
        if (normalizedServerId == null) {
            logger("shared_computers_skip reason=server_id_missing")
            logger("store_load domain=computers serverId= result=skip reason=server_id_missing durationMs=${startedAt.elapsedNow().inWholeMilliseconds}")
            return
        }
        val current = _state.value.selectComputerServerSlice(normalizedServerId)
        if (!force &&
            (current.status == SharedComputersLoadStatus.Loaded ||
                current.status == SharedComputersLoadStatus.InFlight)
        ) {
            logger("shared_computers_skip serverId=$normalizedServerId reason=loaded_or_inflight")
            logger(
                "store_load domain=computers serverId=$normalizedServerId result=skip reason=loaded_or_inflight " +
                    "force=$force status=${current.status.name} machines=${current.machines.size} " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
            return
        }
        setServerSlice(
            serverId = normalizedServerId,
            slice = current.copy(status = SharedComputersLoadStatus.InFlight, errorCode = null)
        )
        val result =
            runCatchingPreservingCancellation {
                computersApiClient.machines(ServerId(normalizedServerId))
            }.getOrElse { throwable ->
                logger("shared_computers_result serverId=$normalizedServerId result=exception message=${throwable.message.orEmpty()}")
                logger(
                    "store_load domain=computers serverId=$normalizedServerId result=exception code=computers_exception " +
                        "force=$force durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
                setServerSlice(
                    serverId = normalizedServerId,
                    slice = SharedComputersServerSlice(status = SharedComputersLoadStatus.Error, errorCode = "computers_exception")
                )
                return
            }
        when (result) {
            is DataResult.Content -> {
                setServerSlice(
                    serverId = normalizedServerId,
                    slice =
                        SharedComputersServerSlice(
                            machines = result.data.machines,
                            machineCount = result.data.machineCount,
                            onlineCount = result.data.onlineCount,
                            latestDaemonVersion = result.data.latestDaemonVersion,
                            status = SharedComputersLoadStatus.Loaded,
                        )
                )
                logger(
                    "store_load domain=computers serverId=$normalizedServerId result=content machines=${result.data.machines.size} " +
                        "online=${result.data.onlineCount} force=$force durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
            is DataResult.Empty -> {
                setServerSlice(
                    serverId = normalizedServerId,
                    slice = SharedComputersServerSlice(status = SharedComputersLoadStatus.Loaded)
                )
                logger(
                    "store_load domain=computers serverId=$normalizedServerId result=empty code=${result.reason.name} " +
                        "force=$force durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
            is DataResult.Error -> {
                logger("shared_computers_result serverId=$normalizedServerId result=error code=${result.error.code}")
                logger(
                    "store_load domain=computers serverId=$normalizedServerId result=error code=${result.error.code} " +
                        "force=$force durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
                setServerSlice(
                    serverId = normalizedServerId,
                    slice = SharedComputersServerSlice(status = SharedComputersLoadStatus.Error, errorCode = result.error.code)
                )
            }
            is DataResult.NoPermission -> {
                logger("shared_computers_result serverId=$normalizedServerId result=no_permission code=${result.blocker.code}")
                logger(
                    "store_load domain=computers serverId=$normalizedServerId result=no_permission code=${result.blocker.code} " +
                        "force=$force durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
                setServerSlice(
                    serverId = normalizedServerId,
                    slice = SharedComputersServerSlice(status = SharedComputersLoadStatus.Error, errorCode = result.blocker.code)
                )
            }
        }
    }

    private fun setServerSlice(serverId: String, slice: SharedComputersServerSlice) {
        _state.value = _state.value.copy(serverSlices = _state.value.serverSlices + (serverId to slice))
    }
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
    when (status) {
        SharedComputersLoadStatus.Loaded ->
            DataResult.Content(
                SettingsComputersSnapshot(
                    computers = machines.map { it.toSettingsComputerSnapshot() },
                    canAddComputer = false,
                    addComputerUnavailableReason = null,
                    latestDaemonVersion = latestDaemonVersion
                )
            )
        SharedComputersLoadStatus.Error ->
            DataResult.Error(ScreenError(errorCode ?: "computers_unavailable", retryable = true))
        SharedComputersLoadStatus.Idle, SharedComputersLoadStatus.InFlight ->
            DataResult.Empty(EmptyReason.NoSettingsAvailable)
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
