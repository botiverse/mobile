package build.raft.app.data

import kotlin.concurrent.Volatile

/**
 * Narrow data-layer view of the process-wide [RaftSharedStoreProvider], exposing only data-layer
 * stores and operations needed by `data/` services (OAuth, push, notifications, telemetry, model
 * catalogs). Implemented by [RaftSharedStoreProvider] in `:shared` so `:shared-data` does not
 * depend on the UI composition root.
 */
internal interface RaftDataStoreProvider {
    val authStore: KeyValueAuthSessionStore
    val networkClient: RaftNetworkClient
    val serverFeatureFlags: SharedServerFeatureFlags
    val avatarCacheRuntime: RaftAvatarCacheRuntime
    val apiEnvironmentStore: SharedApiEnvironmentStore

    fun ensureServerModelLabelCatalog(serverId: String)

    fun refreshServerModelLabelCatalogAfterLiveDetect(serverId: String)

    fun createAuthDataSourceForCurrentEnvironment(
        avatarCacheNativeCache: RaftAvatarNativeCache = UnsupportedRaftAvatarNativeCache,
    ): SharedAuthDataSource
}

/**
 * Read-only lookup for the active [RaftDataStoreProvider], wired by [RaftSharedStoreRegistry] on
 * initialization. Returns `null` before the store provider has been created (or after test reset).
 */
internal object RaftDataStoreRegistry {
    @Volatile
    private var currentProvider: () -> RaftDataStoreProvider? = { null }

    fun bindCurrentProvider(provider: () -> RaftDataStoreProvider?) {
        currentProvider = provider
    }

    fun currentOrNull(): RaftDataStoreProvider? = currentProvider()
}
