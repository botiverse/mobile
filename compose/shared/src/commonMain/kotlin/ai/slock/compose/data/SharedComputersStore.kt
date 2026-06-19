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

internal data class SharedComputersState(
    val pickerSlices: Map<String, SharedComputersPickerSlice> = emptyMap()
)

internal fun SharedComputersState.selectComputerPickerSlice(serverId: String?): SharedComputersPickerSlice {
    val normalizedServerId = serverId.normalizedComputerServerId() ?: return SharedComputersPickerSlice()
    return pickerSlices[normalizedServerId] ?: SharedComputersPickerSlice()
}

internal sealed interface SharedComputersIntent {
    data class EnsureComputerPicker(val serverId: String) : SharedComputersIntent
    data class RefreshComputerPicker(val serverId: String) : SharedComputersIntent
    data object Clear : SharedComputersIntent
}

internal class SharedComputersStore(
    private val computersApiClient: SlockComputersApiClient,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow(SharedComputersState())
    val state: StateFlow<SharedComputersState> = _state.asStateFlow()

    fun computerPickerSliceFlow(serverId: String?): Flow<SharedComputersPickerSlice> =
        state
            .map { state -> state.selectComputerPickerSlice(serverId) }
            .distinctUntilChanged()

    fun dispatch(intent: SharedComputersIntent) {
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
        val normalizedServerId = serverId.normalizedComputerServerId()
        if (normalizedServerId == null) {
            logger("shared_computers_picker_skip reason=server_id_missing")
            return
        }
        val current = _state.value.selectComputerPickerSlice(normalizedServerId)
        if (!force &&
            (current.status == SharedComputersLoadStatus.Loaded ||
                current.status == SharedComputersLoadStatus.InFlight)
        ) {
            logger("shared_computers_picker_skip serverId=$normalizedServerId reason=loaded_or_inflight")
            return
        }
        setPickerSlice(
            serverId = normalizedServerId,
            slice = current.copy(status = SharedComputersLoadStatus.InFlight, errorCode = null)
        )
        val result =
            runCatchingPreservingCancellation {
                computersApiClient.machines(ServerId(normalizedServerId))
            }.getOrElse { throwable ->
                logger("shared_computers_picker_result serverId=$normalizedServerId result=exception message=${throwable.message.orEmpty()}")
                setPickerSlice(
                    serverId = normalizedServerId,
                    slice = SharedComputersPickerSlice(status = SharedComputersLoadStatus.Error, errorCode = "computers_exception")
                )
                return
            }
        when (result) {
            is DataResult.Content -> {
                setPickerSlice(
                    serverId = normalizedServerId,
                    slice =
                        SharedComputersPickerSlice(
                            machines = result.data.machines.toCreateAgentMachineOptions(),
                            status = SharedComputersLoadStatus.Loaded
                        )
                )
            }
            is DataResult.Empty -> {
                setPickerSlice(
                    serverId = normalizedServerId,
                    slice = SharedComputersPickerSlice(status = SharedComputersLoadStatus.Loaded)
                )
            }
            is DataResult.Error -> {
                logger("shared_computers_picker_result serverId=$normalizedServerId result=error code=${result.error.code}")
                setPickerSlice(
                    serverId = normalizedServerId,
                    slice = SharedComputersPickerSlice(status = SharedComputersLoadStatus.Error, errorCode = result.error.code)
                )
            }
            is DataResult.NoPermission -> {
                logger("shared_computers_picker_result serverId=$normalizedServerId result=no_permission code=${result.blocker.code}")
                setPickerSlice(
                    serverId = normalizedServerId,
                    slice = SharedComputersPickerSlice(status = SharedComputersLoadStatus.Error, errorCode = result.blocker.code)
                )
            }
        }
    }

    private fun setPickerSlice(serverId: String, slice: SharedComputersPickerSlice) {
        _state.value = _state.value.copy(pickerSlices = _state.value.pickerSlices + (serverId to slice))
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
