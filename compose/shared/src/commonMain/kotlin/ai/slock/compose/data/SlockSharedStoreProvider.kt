package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule
import ai.slock.compose.model.ServerId

internal class SlockSharedStoreProvider private constructor(
    private val sqlDatabase: SlockSqlDatabase,
    val authStore: KuiklyAuthSessionStore,
    val localDataStore: ComposeLocalDataStore,
    val agentDetailStore: SharedAgentDetailStore,
    val tasksDataStore: SharedTasksDataStore
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
                tasksDataStore = SharedTasksDataStore()
            )
        }
    }
}

internal object SlockSharedStoreRegistry {
    private var provider: SlockSharedStoreProvider? = null

    fun get(bridge: BridgeModule): SlockSharedStoreProvider {
        provider?.let { return it }
        return SlockSharedStoreProvider.create(bridge).also { provider = it }
    }
}
