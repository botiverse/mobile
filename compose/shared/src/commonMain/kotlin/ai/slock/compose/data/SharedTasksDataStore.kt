package ai.slock.compose.data

import ai.slock.compose.model.TaskSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal enum class SharedTasksDataStoreStatus {
    Idle,
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
    val channelSnapshots: Map<SharedTasksChannelKey, TaskSnapshot> = emptyMap()
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
                        serverSnapshots = state.value.serverSnapshots + (normalizedServerId to snapshot)
                    )
                is SharedTasksScope.Channel ->
                    state.value.copy(
                        channelSnapshots =
                            state.value.channelSnapshots +
                                (
                                    SharedTasksChannelKey(
                                        serverId = normalizedServerId,
                                        channelId = scope.channelId.value.normalizedTasksStoreId()
                                    ) to snapshot
                                )
                    )
            }
    }

    fun channelProjectionFlow(
        serverId: String?,
        channelId: String?,
        channelName: String?
    ): Flow<SharedTasksProjectionSlice> =
        state
            .map { current -> current.selectChannelProjection(serverId, channelId, channelName) }
            .distinctUntilChanged()

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
        if (exactChannelSnapshot != null) {
            return SharedTasksProjectionSlice(status = SharedTasksDataStoreStatus.Loaded, tasks = exactChannelSnapshot.tasks)
        }
        val serverSnapshot = serverSnapshots[normalizedServerId] ?: return SharedTasksProjectionSlice()
        val channelTasks =
            serverSnapshot.tasks.filter { task ->
                val taskChannelId = task.channelId?.value.normalizedTasksStoreId()
                val taskChannelName = task.channelName.normalizedTasksChannelName()
                (normalizedChannelId != null && taskChannelId == normalizedChannelId) ||
                    (normalizedChannelName != null && taskChannelName == normalizedChannelName)
            }
        return SharedTasksProjectionSlice(status = SharedTasksDataStoreStatus.Loaded, tasks = channelTasks)
    }
}

private fun String?.normalizedTasksStoreId(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }

private fun String?.normalizedTasksChannelName(): String? =
    this
        ?.trim()
        ?.removePrefix("#")
        ?.takeIf { it.isNotBlank() }
