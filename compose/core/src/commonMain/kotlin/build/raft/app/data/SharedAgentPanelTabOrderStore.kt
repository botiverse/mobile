package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Last known agent-panel tab order per server (the `agentPanelTabOrder` field of
 * `GET/PATCH /servers/:id/sidebar-order`), kept on device so an agent profile can draw the
 * user's order on its first frame instead of the default order followed by a reorder once the
 * server answers (task #22). `null` from [readOrder] means "never seen"; an empty list is a
 * known default order.
 */
@RaftCoreInternalApi
interface SharedAgentPanelTabOrderCache {
    fun readOrder(serverId: String): List<String>?
    fun writeOrder(serverId: String, order: List<String>)
}

@RaftCoreInternalApi
data class SharedAgentPanelTabOrderState(
    val serverId: String? = null,
    val order: List<String> = emptyList(),
    /** The server answered (or failed) for [serverId]; no further load is needed. */
    val loaded: Boolean = false,
    /** [order] came from the on-device cache and still awaits the server's answer. */
    val cached: Boolean = false
) {
    /** [order] is the user's order (server-confirmed or last known), safe to render. */
    val orderKnown: Boolean
        get() = loaded || cached
}

@RaftCoreInternalApi
class SharedAgentPanelTabOrderStore(
    private val cache: SharedAgentPanelTabOrderCache? = null
) {
    private val _state = MutableStateFlow(SharedAgentPanelTabOrderState())
    val state: StateFlow<SharedAgentPanelTabOrderState> = _state.asStateFlow()

    /**
     * The order to render for [serverId] right now, or `null` while it is unknown: the store's
     * order when it is bound to that server and known, otherwise the last order persisted on
     * device. Synchronous so a page can read it during its first composition, before any
     * effect has bound the store.
     */
    fun knownOrderFor(serverId: String?): List<String>? {
        val cleanServerId = serverId.cleanAgentPanelTabOrderServerId() ?: return null
        val current = _state.value
        if (current.serverId == cleanServerId && current.orderKnown) return current.order
        return readCachedOrder(cleanServerId)
    }

    fun bindServer(
        serverId: String?,
        initialOrder: List<String> = emptyList(),
        source: String = "unknown",
        logger: ((String) -> Unit)? = null
    ) {
        val cleanServerId = serverId.cleanAgentPanelTabOrderServerId()
        val current = _state.value
        if (cleanServerId == null) {
            // A page that has not resolved its server yet must not wipe the order another page
            // (or the cache) already provided for the current server.
            logAgentPanelTabOrderWrite(
                logger = logger,
                operation = "bindServer",
                source = source,
                inputOrder = initialOrder,
                before = current,
                after = current
            )
            return
        }
        if (current.serverId != cleanServerId) {
            val cachedOrder = if (initialOrder.isEmpty()) readCachedOrder(cleanServerId) else null
            val next = SharedAgentPanelTabOrderState(
                serverId = cleanServerId,
                order = if (initialOrder.isNotEmpty()) initialOrder else cachedOrder.orEmpty(),
                loaded = initialOrder.isNotEmpty(),
                cached = cachedOrder != null
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
        if (initialOrder.isNotEmpty() && current.order.isEmpty() && !current.loaded) {
            val next = current.copy(order = initialOrder, loaded = true, cached = false)
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
        val cleanServerId = serverId.cleanAgentPanelTabOrderServerId()
        val current = _state.value
        val next =
            if (current.serverId != cleanServerId) {
                SharedAgentPanelTabOrderState(serverId = cleanServerId, order = order, loaded = true)
            } else {
                current.copy(order = order, loaded = true, cached = false)
            }
        _state.value = next
        if (cleanServerId != null) writeCachedOrder(cleanServerId, order)
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
        val cleanServerId = serverId.cleanAgentPanelTabOrderServerId()
        val current = _state.value
        val next =
            if (current.serverId != cleanServerId) {
                SharedAgentPanelTabOrderState(serverId = cleanServerId, order = order, loaded = true)
            } else {
                current.copy(order = order, loaded = true, cached = false)
            }
        _state.value = next
        if (cleanServerId != null) writeCachedOrder(cleanServerId, order)
        logAgentPanelTabOrderWrite(
            logger = logger,
            operation = "applyOptimistic",
            source = source,
            inputOrder = order,
            before = current,
            after = next
        )
    }

    /**
     * The server could not answer. Keep the last known order for [serverId] (cache or earlier
     * load) instead of falling back to the default order, and stop asking until the next bind.
     */
    fun applyLoadFailed(
        serverId: String?,
        source: String = "unknown",
        logger: ((String) -> Unit)? = null
    ) {
        val cleanServerId = serverId.cleanAgentPanelTabOrderServerId()
        val current = _state.value
        val next =
            if (current.serverId != cleanServerId) {
                val cachedOrder = cleanServerId?.let(::readCachedOrder)
                SharedAgentPanelTabOrderState(serverId = cleanServerId, order = cachedOrder.orEmpty(), loaded = true)
            } else {
                current.copy(loaded = true, cached = false)
            }
        _state.value = next
        logAgentPanelTabOrderWrite(
            logger = logger,
            operation = "applyLoadFailed",
            source = source,
            inputOrder = emptyList(),
            before = current,
            after = next
        )
    }

    private fun readCachedOrder(serverId: String): List<String>? =
        runCatching { cache?.readOrder(serverId) }.getOrNull()

    private fun writeCachedOrder(serverId: String, order: List<String>) {
        runCatching { cache?.writeOrder(serverId, order) }
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
            "afterCached=${after.cached} " +
            "beforeOrder=${before.order.toAgentPanelTabOrderLogToken()} " +
            "inputOrder=${inputOrder.toAgentPanelTabOrderLogToken()} " +
            "afterOrder=${after.order.toAgentPanelTabOrderLogToken()}"
    )
}

private fun String?.cleanAgentPanelTabOrderServerId(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }

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
