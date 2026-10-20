package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@RaftCoreInternalApi
data class SharedAgentPanelTabOrderState(
    val serverId: String? = null,
    val order: List<String> = emptyList(),
    val loaded: Boolean = false
)

@RaftCoreInternalApi
class SharedAgentPanelTabOrderStore {
    private val _state = MutableStateFlow(SharedAgentPanelTabOrderState())
    val state: StateFlow<SharedAgentPanelTabOrderState> = _state.asStateFlow()

    fun bindServer(
        serverId: String?,
        initialOrder: List<String> = emptyList(),
        source: String = "unknown",
        logger: ((String) -> Unit)? = null
    ) {
        val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
        val current = _state.value
        if (current.serverId != cleanServerId) {
            val next = SharedAgentPanelTabOrderState(
                serverId = cleanServerId,
                order = initialOrder,
                loaded = initialOrder.isNotEmpty()
            )
            _state.value = next
            logAgentPanelTabOrderWrite(
                logger = logger,
                operation = "bindServer",
                source = source,
                inputOrder = initialOrder,
                before = current,
                after = next
            )
            return
        }
        if (initialOrder.isNotEmpty() && current.order.isEmpty()) {
            val next = current.copy(order = initialOrder, loaded = true)
            _state.value = next
            logAgentPanelTabOrderWrite(
                logger = logger,
                operation = "bindServer",
                source = source,
                inputOrder = initialOrder,
                before = current,
                after = next
            )
        } else {
            logAgentPanelTabOrderWrite(
                logger = logger,
                operation = "bindServer",
                source = source,
                inputOrder = initialOrder,
                before = current,
                after = current
            )
        }
    }

    fun applyLoaded(
        serverId: String?,
        order: List<String>,
        source: String = "unknown",
        logger: ((String) -> Unit)? = null
    ) {
        val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
        val current = _state.value
        val next =
            if (current.serverId != cleanServerId) {
                SharedAgentPanelTabOrderState(serverId = cleanServerId, order = order, loaded = true)
            } else {
                current.copy(order = order, loaded = true)
            }
        _state.value = next
        logAgentPanelTabOrderWrite(
            logger = logger,
            operation = "applyLoaded",
            source = source,
            inputOrder = order,
            before = current,
            after = next
        )
    }

    fun applyOptimistic(
        serverId: String?,
        order: List<String>,
        source: String = "unknown",
        logger: ((String) -> Unit)? = null
    ) {
        val cleanServerId = serverId?.trim()?.takeIf { it.isNotBlank() }
        val current = _state.value
        val next =
            if (current.serverId != cleanServerId) {
                SharedAgentPanelTabOrderState(serverId = cleanServerId, order = order, loaded = true)
            } else {
                current.copy(order = order, loaded = true)
            }
        _state.value = next
        logAgentPanelTabOrderWrite(
            logger = logger,
            operation = "applyOptimistic",
            source = source,
            inputOrder = order,
            before = current,
            after = next
        )
    }
}

private fun logAgentPanelTabOrderWrite(
    logger: ((String) -> Unit)?,
    operation: String,
    source: String,
    inputOrder: List<String>,
    before: SharedAgentPanelTabOrderState,
    after: SharedAgentPanelTabOrderState
) {
    if (logger == null) return
    logger(
        "agent_panel_tab_order_write " +
            "operation=$operation " +
            "source=$source " +
            "changed=${before != after} " +
            "beforeServer=${before.serverId.toAgentPanelTabOrderLogToken()} " +
            "afterServer=${after.serverId.toAgentPanelTabOrderLogToken()} " +
            "beforeLoaded=${before.loaded} " +
            "afterLoaded=${after.loaded} " +
            "beforeOrder=${before.order.toAgentPanelTabOrderLogToken()} " +
            "inputOrder=${inputOrder.toAgentPanelTabOrderLogToken()} " +
            "afterOrder=${after.order.toAgentPanelTabOrderLogToken()}"
    )
}

private fun String?.toAgentPanelTabOrderLogToken(): String =
    this?.takeIf { it.isNotBlank() } ?: "none"

private fun List<String>.toAgentPanelTabOrderLogToken(): String =
    if (isEmpty()) {
        "[]"
    } else {
        joinToString(prefix = "[", postfix = "]", separator = ",") { value ->
            value.replace(",", "_").replace(" ", "_").replace("\n", "_")
        }
    }
