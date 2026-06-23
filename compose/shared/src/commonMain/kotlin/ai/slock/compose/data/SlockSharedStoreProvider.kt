package ai.slock.compose.data

import ai.slock.compose.base.BridgeModule
import ai.slock.compose.model.ServerId

internal class SlockSharedStoreProvider private constructor(
    private val sqlDatabase: SlockSqlDatabase,
    val authStore: KuiklyAuthSessionStore,
    val localDataStore: ComposeLocalDataStore,
    val realtimeStore: SharedRealtimeStore
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
            val realtimeWriteThrough =
                ComposeLocalWriteThrough(
                    localDataStore,
                    logger = { message -> bridge.logDebug("shared_realtime_cache $message") }
                )
            return SlockSharedStoreProvider(
                sqlDatabase = database,
                authStore = KuiklyAuthSessionStore(relationalStore),
                localDataStore = localDataStore,
                realtimeStore =
                    SharedRealtimeStore(
                        localWriteThrough = realtimeWriteThrough,
                        logger = { message -> bridge.logDebug(message) }
                    )
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
