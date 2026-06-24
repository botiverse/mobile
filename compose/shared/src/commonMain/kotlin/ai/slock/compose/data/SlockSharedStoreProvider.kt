package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule
import ai.slock.compose.data.realtime.RealtimeRoutePolicy
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.ServerId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class SlockSharedStoreProvider private constructor(
    private val sqlDatabase: SlockSqlDatabase,
    val authStore: KuiklyAuthSessionStore,
    val localDataStore: ComposeLocalDataStore,
    val agentDetailStore: SharedAgentDetailStore,
    val tasksDataStore: SharedTasksDataStore,
    val realtimeDomainEventOwner: SharedRealtimeDomainEventOwner
) {
    fun createThreadLocalDataStore(currentServerId: suspend () -> ServerId?): ComposeLocalDataStore =
        SqlDelightComposeLocalDataStore(
            database = sqlDatabase,
            fallback = localDataStore,
            currentServerId = currentServerId
        )

    companion object {
        fun create(bridge: BridgeModule): SlockSharedStoreProvider {
            val database = createSlockSqlDatabase(bridge)
            val relationalStore = SqlDelightRelationalStore(database)
            val localDataStore = RelationalComposeLocalDataStore(relationalStore)
            return SlockSharedStoreProvider(
                sqlDatabase = database,
                authStore = KuiklyAuthSessionStore(relationalStore),
                localDataStore = localDataStore,
                agentDetailStore = SharedAgentDetailStore(
                    logger = { message -> bridge.log("shared_agent_detail_store $message") }
                ),
                tasksDataStore = SharedTasksDataStore(),
                realtimeDomainEventOwner = SharedRealtimeDomainEventOwner()
            )
        }
    }
}

internal data class ThreadRealtimeProjectionSignal(
    val sequence: Long,
    val serverId: String,
    val event: RealtimeEvent
)

internal interface RealtimeDomainEventOwner {
    val threadProjections: StateFlow<ThreadRealtimeProjectionSignal?>

    fun accept(serverId: String, event: RealtimeEvent)
}

internal class SharedRealtimeDomainEventOwner : RealtimeDomainEventOwner {
    private var nextSequence = 0L
    private val _threadProjections = MutableStateFlow<ThreadRealtimeProjectionSignal?>(null)
    override val threadProjections: StateFlow<ThreadRealtimeProjectionSignal?> = _threadProjections

    override fun accept(serverId: String, event: RealtimeEvent) {
        val resolvedServerId = serverId.takeIf { it.isNotBlank() } ?: return
        if (!event.shouldProjectToThread()) return
        nextSequence += 1
        _threadProjections.value =
            ThreadRealtimeProjectionSignal(
                sequence = nextSequence,
                serverId = resolvedServerId,
                event = event
            )
    }
}

private fun RealtimeEvent.shouldProjectToThread(): Boolean =
    this is MemberPresenceUpdated || RealtimeRoutePolicy.shouldForwardToActiveThread(this)

internal object SlockSharedStoreRegistry {
    private var provider: SlockSharedStoreProvider? = null

    fun get(bridge: BridgeModule): SlockSharedStoreProvider {
        provider?.let { return it }
        return SlockSharedStoreProvider.create(bridge).also { provider = it }
    }
}
