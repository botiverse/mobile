package build.raft.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val SLOCK_APP_LOGIN_MMKV_NAMESPACE = "slockAppLogin"

/** Pure browser login handoff; independent of provider OAuth and account linking. */
@Serializable
internal data class AppLoginPending(
    val generation: String,
    val verifier: String,
    val returnUri: String,
    val environment: String,
    val startedAt: Long,
)

internal interface AppLoginRuntime {
    val environment: String
    suspend fun start(returnUri: String, challenge: String): SharedAuthResult<AuthAppLoginStartResponse>
    suspend fun complete(code: String, verifier: String): Boolean
}

internal class AppLoginCoordinator(
    private val store: SlockKeyValueStore,
    private val runtime: () -> AppLoginRuntime?,
    private val now: () -> Long = ::defaultNowMillis,
) {
    private val mutex = Mutex()
    private val pkce = Regex("^[A-Za-z0-9_-]{43,128}$")
    private fun pending(): AppLoginPending? = runCatching {
        Json.decodeFromString(AppLoginPending.serializer(), store.getString("pending").orEmpty())
    }.getOrNull()
    private fun clear() = store.putString("pending", null)

    suspend fun start(generation: String, verifier: String, challenge: String, returnUri: String): String? = mutex.withLock {
        clear()
        if (generation.isBlank() || !pkce.matches(verifier) || !pkce.matches(challenge) ||
            returnUri !in setOf("raft://login/callback", "raft-debug://login/callback")) return@withLock null
        val active = runtime() ?: return@withLock null
        val pending = AppLoginPending(generation, verifier, returnUri, active.environment, now())
        val result = active.start(returnUri, challenge)
        if (result !is SharedAuthResult.Success || runtime()?.environment != active.environment) return@withLock null
        if (result.data.requestId.isBlank() || result.data.expiresAt.isBlank()) return@withLock null
        val url = result.data.loginUrl
        if (!url.startsWith("https://")) return@withLock null
        store.putString("pending", Json.encodeToString(AppLoginPending.serializer(), pending))
        url
    }

    suspend fun cancel(generation: String) = mutex.withLock {
        if (pending()?.generation == generation) clear()
    }

    suspend fun complete(returnUri: String, code: String, error: String): String = mutex.withLock {
        val pending = pending() ?: return@withLock "ignored"
        if (pending.returnUri != returnUri) return@withLock "ignored"
        val active = runtime() ?: return@withLock "retry"
        if (active.environment != pending.environment || now() - pending.startedAt !in -60_000L until 600_000L ||
            !pkce.matches(pending.verifier)) {
            clear()
            return@withLock "login_error"
        }
        // Remove before exchanging: duplicate callbacks and process restarts cannot reuse a consumed code.
        clear()
        if (error.isNotBlank() || code.isBlank()) return@withLock "login_error"
        if (runCatchingPreservingCancellation { active.complete(code, pending.verifier) }.getOrDefault(false)) "app_shell"
        else "login_error"
    }
}

internal object SlockAppLogin {
    private val coordinator by lazy {
        AppLoginCoordinator(
            SlockSharedKeyValueStoreProvider.create(SLOCK_APP_LOGIN_MMKV_NAMESPACE, "App login").store,
            runtime = {
                SlockSharedStoreRegistry.currentOrNull()?.let { provider ->
                    val auth = provider.createAuthDataSourceForCurrentEnvironment()
                    val environmentKey = provider.apiEnvironmentStore.current().key
                    object : AppLoginRuntime {
                        override val environment = environmentKey
                        override suspend fun start(returnUri: String, challenge: String) = auth.startAppLogin(returnUri, challenge)
                        override suspend fun complete(code: String, verifier: String): Boolean =
                            auth.completeAppLogin(code, verifier) {
                                provider.apiEnvironmentStore.current().key == environmentKey
                            } is SharedAuthResult.Success
                    }
                }
            },
        )
    }
    suspend fun start(generation: String, verifier: String, challenge: String, returnUri: String) =
        coordinator.start(generation, verifier, challenge, returnUri)
    suspend fun cancel(generation: String) = coordinator.cancel(generation)
    suspend fun complete(returnUri: String, code: String, error: String) = coordinator.complete(returnUri, code, error)
}
