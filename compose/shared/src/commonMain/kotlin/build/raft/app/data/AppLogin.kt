package build.raft.app.data

import build.raft.app.raftLogInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val RAFT_APP_LOGIN_MMKV_NAMESPACE = "slockAppLogin"

/** Pure browser login handoff; independent of provider OAuth and account linking. */
@Serializable
internal data class AppLoginPending(
    val generation: String,
    val requestId: String,
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
    private val store: RaftKeyValueStore,
    private val runtime: () -> AppLoginRuntime?,
    private val now: () -> Long = ::defaultNowMillis,
    private val logger: (String) -> Unit = {},
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
            returnUri !in APP_LOGIN_RETURN_URIS) return@withLock null
        val active = runtime() ?: return@withLock null
        val startedAt = now()
        val result = active.start(returnUri, challenge)
        if (result !is SharedAuthResult.Success || runtime()?.environment != active.environment) return@withLock null
        if (result.data.requestId.isBlank() || result.data.expiresAt.isBlank()) return@withLock null
        val url = result.data.loginUrl
        if (!isAllowedAppLoginUrl(url)) return@withLock null
        val pending = AppLoginPending(generation, result.data.requestId, verifier, returnUri, active.environment, startedAt)
        store.putString("pending", Json.encodeToString(AppLoginPending.serializer(), pending))
        url
    }

    /**
     * Whether [requestId] is the browser sign-in this app is waiting for: the one on file, not
     * expired. Read-only, so a host can ask before reopening a link that names a request.
     */
    fun isWaitingFor(requestId: String): Boolean {
        val pending = pending() ?: return false
        return requestId.isNotBlank() && pending.requestId == requestId && isFresh(pending)
    }

    private fun ageMs(pending: AppLoginPending): Long = now() - pending.startedAt
    private fun isFresh(pending: AppLoginPending): Boolean = ageMs(pending) in -60_000L until 600_000L

    suspend fun cancel(generation: String) = mutex.withLock {
        if (pending()?.generation == generation) clear()
    }

    suspend fun complete(returnUri: String, requestId: String, code: String, error: String): String = mutex.withLock {
        // One diagnostics line per exit: on a device the branches are otherwise indistinguishable.
        fun exit(result: String, reason: String): String {
            logger("app_login_complete result=$result reason=$reason")
            return result
        }
        val pending = pending() ?: return@withLock exit("ignored", "no_pending")
        if (pending.returnUri != returnUri) return@withLock exit("ignored", "return_uri_mismatch")
        // A callback for an earlier attempt (the user tapped sign-in again): keep the newer attempt, but say so,
        // or the login page sits there silently.
        if (pending.requestId != requestId) return@withLock exit(APP_LOGIN_STALE_CALLBACK, "request_id_mismatch")
        val active = runtime() ?: return@withLock exit("retry", "runtime_unavailable")
        val refusal =
            when {
                // The identity is key + API/socket/web addresses (no credentials): log both sides.
                active.environment != pending.environment ->
                    "environment_changed started=${pending.environment} callback=${active.environment}"
                !isFresh(pending) -> "expired ageMs=${ageMs(pending)}"
                !pkce.matches(pending.verifier) -> "verifier_invalid"
                else -> null
            }
        if (refusal != null) {
            clear()
            return@withLock exit("login_error", refusal)
        }
        // Remove before exchanging: duplicate callbacks and process restarts cannot reuse a consumed code.
        clear()
        if (error.isNotBlank()) return@withLock exit("login_error", "callback_error")
        if (code.isBlank()) return@withLock exit("login_error", "callback_no_code")
        val exchanged =
            runCatchingPreservingCancellation { active.complete(code, pending.verifier) }
                .onFailure { logger("app_login_complete exchange_threw type=${it::class.simpleName}") }
                .getOrDefault(false)
        if (exchanged) exit("app_shell", "exchanged") else exit("login_error", "exchange_failed")
    }
}

/** [AppLoginCoordinator.complete] result for a callback that belongs to an earlier, replaced attempt. */
internal const val APP_LOGIN_STALE_CALLBACK = "stale"

/**
 * [RaftWebLoginCompletion] status while an accepted callback is being exchanged: the sign-in page
 * keeps showing "Signing in…" instead of treating the return to the app as a cancel.
 */
internal const val APP_LOGIN_CALLBACK_RECEIVED = "callback_received"

/** [RaftWebLoginCompletion] status for a browser sheet that closed without a callback (iOS cancel). */
internal const val APP_LOGIN_BROWSER_CLOSED = "browser_closed"

internal object RaftAppLogin {
    private val coordinator by lazy {
        AppLoginCoordinator(
            RaftSharedKeyValueStoreProvider.create(RAFT_APP_LOGIN_MMKV_NAMESPACE, "App login").store,
            runtime = {
                RaftSharedStoreRegistry.currentOrNull()?.let { provider ->
                    createAppLoginRuntime(
                        provider.apiEnvironmentStore,
                        provider.authStore,
                        provider.avatarCacheRuntime,
                        diagnosticsLogger = ::logAppLogin,
                    )
                } ?: appScopeAppLoginRuntime()
            },
            logger = ::logAppLogin,
        )
    }

    private fun logAppLogin(message: String) = raftLogInfo("SlockAppLogin", message)

    /**
     * No shared store provider yet: the browser callback can arrive before the app shell is (re)created
     * (Android hands it to MainActivity in the browser's task, which restarts the shell). Exchange with the
     * same app-scope auth and environment storage the provider reads, as the Android social OAuth callback
     * already does; the login page picks the stored session up when it comes back.
     */
    private fun appScopeAppLoginRuntime(): AppLoginRuntime? =
        runCatching {
            val environments =
                SharedApiEnvironmentStore(
                    RaftSharedKeyValueStoreProvider.create(SharedApiEnvironmentStore.Namespace, "API environment").store
                )
            val sessions =
                KeyValueAuthSessionStore(
                    RaftSharedAuthSessionKeyValueStoreProvider.create().store,
                    diagnosticsLogger = ::logAppLogin,
                )
            logAppLogin("app_login_runtime source=app_scope")
            createAppLoginRuntime(environments, sessions, diagnosticsLogger = ::logAppLogin)
        }.onFailure { logAppLogin("app_login_app_scope_runtime_failed type=${it::class.simpleName}") }
            .getOrNull()
    suspend fun start(generation: String, verifier: String, challenge: String, returnUri: String) =
        coordinator.start(generation, verifier, challenge, returnUri)
    suspend fun cancel(generation: String) = coordinator.cancel(generation)
    fun isWaitingFor(requestId: String) = coordinator.isWaitingFor(requestId)
    suspend fun complete(returnUri: String, requestId: String, code: String, error: String) = coordinator.complete(returnUri, requestId, code, error)
}

internal fun appLoginEnvironmentIdentity(environment: SharedSettingsApiEnvironmentOption): String =
    listOf(environment.key, environment.apiBaseUrl, environment.socketBaseUrl, environment.webUrl)
        .joinToString("") { "${it.length}:$it" }

internal fun createAppLoginRuntime(
    environmentStore: SharedApiEnvironmentStore,
    authStore: RaftAuthSessionStore,
    avatarCacheRuntime: RaftAvatarCacheRuntime? = null,
    diagnosticsLogger: (String) -> Unit = {},
    transportFactory: (String) -> RaftAuthTransport = {
        SharedRaftAuthTransport(baseUrl = it, warningLogger = diagnosticsLogger)
    },
): AppLoginRuntime {
    val snapshot = environmentStore.current()
    val identity = appLoginEnvironmentIdentity(snapshot)
    // Capture the actual endpoints, not just "custom". Hydration must use the
    // same API as the exchange even if the environment changes while suspended.
    val auth = SharedAuthDataSource(transportFactory(snapshot.apiBaseUrl), authStore, diagnosticsLogger)
        .withAvatarCacheRuntime(avatarCacheRuntime, UnsupportedRaftAvatarNativeCache) { snapshot.apiBaseUrl }
    fun isCurrent() = appLoginEnvironmentIdentity(environmentStore.current()) == identity
    return object : AppLoginRuntime {
        override val environment = identity
        override suspend fun start(returnUri: String, challenge: String): SharedAuthResult<AuthAppLoginStartResponse> =
            if (isCurrent()) auth.startAppLogin(returnUri, challenge) else SharedAuthResult.Unauthenticated
        override suspend fun complete(code: String, verifier: String): Boolean {
            if (!isCurrent()) {
                diagnosticsLogger("app_login_exchange result=skipped reason=environment_changed")
                return false
            }
            val result = auth.completeAppLogin(code, verifier, ::isCurrent)
            diagnosticsLogger(
                "app_login_exchange result=" +
                    when (result) {
                        is SharedAuthResult.Success -> "success"
                        is SharedAuthResult.Failure -> "failure code=${result.code}"
                        SharedAuthResult.Unauthenticated -> "unauthenticated"
                    }
            )
            return result is SharedAuthResult.Success
        }
    }
}

private val LoopbackHttpUrl = Regex("^http://(localhost|127\\.0\\.0\\.1)(:\\d+)?/")

/**
 * The browser login page must be served over https; plain http only to this machine (a local
 * development server, e.g. raftdev, whose web app has no TLS).
 */
fun isAllowedAppLoginUrl(url: String): Boolean = url.startsWith("https://") || LoopbackHttpUrl.containsMatchIn(url)
