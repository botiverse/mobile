package ai.slock.compose.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SharedAgentPanelTabOrderState(
    val serverId: String? = null,
    val order: List<String> = emptyList(),
    val loaded: Boolean = false
)

internal class SharedAgentPanelTabOrderStore {
    private val _state = MutableStateFlow(SharedAgentPanelTabOrderState())
    val state: StateFlow<SharedAgentPanelTabOrderState> = _state.asStateFlow()

    fun bindServer(serverId: String?, initialOrder: List<String> = emptyList()) {
        val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
        val current = _state.value
        if (current.serverId != cleanServerId) {
            _state.value =
                SharedAgentPanelTabOrderState(
                    serverId = cleanServerId,
                    order = initialOrder,
                    loaded = initialOrder.isNotEmpty()
                )
            return
        }
        if (initialOrder.isNotEmpty() && current.order.isEmpty()) {
            _state.value = current.copy(order = initialOrder, loaded = true)
        }
    }

    fun applyLoaded(serverId: String?, order: List<String>) {
        val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
        val current = _state.value
        if (current.serverId != cleanServerId) {
            _state.value = SharedAgentPanelTabOrderState(serverId = cleanServerId, order = order, loaded = true)
        } else {
            _state.value = current.copy(order = order, loaded = true)
        }
    }

    fun applyOptimistic(serverId: String?, order: List<String>) {
        val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
        val current = _state.value
        if (current.serverId != cleanServerId) {
            _state.value = SharedAgentPanelTabOrderState(serverId = cleanServerId, order = order, loaded = true)
        } else {
            _state.value = current.copy(order = order, loaded = true)
        }
    }
}
