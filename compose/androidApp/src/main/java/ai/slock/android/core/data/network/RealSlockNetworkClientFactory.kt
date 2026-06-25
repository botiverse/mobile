package ai.slock.android.core.data.network

import ai.slock.compose.data.SlockAuthSessionStore as AuthSessionStore
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.android.core.data.auth.AndroidAuthApiFactory
import ai.slock.compose.data.KtorSlockNetworkClient
import ai.slock.compose.data.SlockNetworkClient
import ai.slock.compose.network.AuthenticatedKtorTransport
import ai.slock.compose.network.AuthTokenProvider
import ai.slock.compose.network.AuthTokens
import ai.slock.compose.network.HttpClientConfig
import ai.slock.compose.network.SLOCK_DEFAULT_MAX_BACKGROUND_REQUESTS
import ai.slock.compose.network.SLOCK_DEFAULT_MAX_FOREGROUND_REQUESTS
import ai.slock.compose.network.SLOCK_DEFAULT_MAX_VISIBLE_REQUESTS
import ai.slock.compose.network.SlockApiClient
import ai.slock.compose.network.SlockRequestScheduler
import ai.slock.compose.network.TimeoutConfig
import ai.slock.compose.network.createPriorityHttpClient

object RealSlockNetworkClientFactory {
    fun create(
        store: AuthSessionStore,
        baseUrl: String = AndroidAuthApiFactory.BASE_URL,
        tokenRefresher: AuthTokenRefresher =
            AuthTokenRefresher(
                store = store,
                api = AndroidAuthApiFactory.createAuthApi(baseUrl)
            )
    ): SlockNetworkClient {
        val logger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(TAG, message)
        }
        val warningLogger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(TAG, message)
        }
        val config = HttpClientConfig(timeout = TimeoutConfig())
        val foregroundHttpClient =
            createPriorityHttpClient(
                config = config,
                maxRequests = SLOCK_DEFAULT_MAX_FOREGROUND_REQUESTS,
                maxRequestsPerHost = SLOCK_DEFAULT_MAX_FOREGROUND_REQUESTS
            )
        val visibleHttpClient =
            createPriorityHttpClient(
                config = config,
                maxRequests = SLOCK_DEFAULT_MAX_VISIBLE_REQUESTS,
                maxRequestsPerHost = SLOCK_DEFAULT_MAX_VISIBLE_REQUESTS
            )
        val backgroundHttpClient =
            createPriorityHttpClient(
                config = config,
                maxRequests = SLOCK_DEFAULT_MAX_BACKGROUND_REQUESTS,
                maxRequestsPerHost = SLOCK_DEFAULT_MAX_BACKGROUND_REQUESTS
            )
        val client =
            SlockApiClient(
                baseUrl = baseUrl,
                httpClient =
                    createPriorityHttpClient(
                        config = config,
                        maxRequests = SLOCK_DEFAULT_MAX_FOREGROUND_REQUESTS,
                        maxRequestsPerHost = SLOCK_DEFAULT_MAX_FOREGROUND_REQUESTS
                    ),
                foregroundHttpClient = foregroundHttpClient,
                visibleHttpClient = visibleHttpClient,
                backgroundHttpClient = backgroundHttpClient,
                authTransport =
                    AuthenticatedKtorTransport(
                        tokenProvider =
                            AndroidAuthTokenProvider(
                                store = store,
                                tokenRefresher = tokenRefresher,
                                warningLogger = warningLogger
                            ),
                        logger = { message -> logger("compose_$message") }
                    ),
                requestScheduler =
                    SlockRequestScheduler(
                        maxForegroundRequests = SLOCK_DEFAULT_MAX_FOREGROUND_REQUESTS,
                        maxVisibleRequests = SLOCK_DEFAULT_MAX_VISIBLE_REQUESTS,
                        maxBackgroundRequests = SLOCK_DEFAULT_MAX_BACKGROUND_REQUESTS,
                        logger = { message -> logger("request_pool_$message") }
                    ),
                warningLogger = warningLogger
            )
        return KtorSlockNetworkClient(client)
    }

    private const val TAG = "SlockNetworkClient"
}

private class AndroidAuthTokenProvider(
    private val store: AuthSessionStore,
    private val tokenRefresher: AuthTokenRefresher,
    private val warningLogger: (String) -> Unit
) : AuthTokenProvider {
    override suspend fun currentTokens(): AuthTokens? =
        tokenRefresher.refreshIfExpiringSoon(clearOnUnauthorized = false)?.toNetworkAuthTokens()

    override suspend fun refreshTokens(failedAccessToken: String): AuthTokens? =
        tokenRefresher
            .refreshIfCurrent(failedAccessToken = failedAccessToken, clearOnUnauthorized = false)
            ?.toNetworkAuthTokens()

    override suspend fun onUnauthorized(failedAccessToken: String?) {
        val current = store.readSession()
        if (failedAccessToken == null || current?.accessToken == failedAccessToken) {
            warningLogger("auth_unauthorized_clear_session")
            store.clearSession()
        }
    }

    private fun ai.slock.compose.data.StoredAuthSession.toNetworkAuthTokens(): AuthTokens =
        AuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken.takeIf { it.isNotBlank() },
            selectedServerId = currentServerId
        )
}
