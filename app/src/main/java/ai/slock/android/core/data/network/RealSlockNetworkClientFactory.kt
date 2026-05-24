package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.android.core.data.auth.KtorAuthRepository
import ai.slock.compose.data.KtorSlockNetworkClient
import ai.slock.compose.data.SlockNetworkClient
import ai.slock.compose.network.AuthenticatedKtorTransport
import ai.slock.compose.network.AuthTokenProvider
import ai.slock.compose.network.AuthTokens
import ai.slock.compose.network.HttpClientConfig
import ai.slock.compose.network.SlockApiClient
import ai.slock.compose.network.SlockRequestScheduler
import ai.slock.compose.network.TimeoutConfig
import ai.slock.compose.network.createPriorityHttpClient

object RealSlockNetworkClientFactory {
    fun create(
        store: AuthSessionStore,
        baseUrl: String = KtorAuthRepository.BASE_URL,
        tokenRefresher: AuthTokenRefresher =
            AuthTokenRefresher(
                store = store,
                api = KtorAuthRepository.createAuthApi(baseUrl)
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
            createPriorityHttpClient(config = config, maxRequests = 12, maxRequestsPerHost = 12)
        val visibleHttpClient =
            createPriorityHttpClient(config = config, maxRequests = 8, maxRequestsPerHost = 8)
        val backgroundHttpClient =
            createPriorityHttpClient(config = config, maxRequests = 4, maxRequestsPerHost = 4)
        val client =
            SlockApiClient(
                baseUrl = baseUrl,
                httpClient = createPriorityHttpClient(config = config, maxRequests = 6, maxRequestsPerHost = 6),
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
                        maxForegroundRequests = 12,
                        maxVisibleRequests = 8,
                        maxBackgroundRequests = 4,
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
        tokenRefresher.refreshIfExpiringSoon(clearOnUnauthorized = false)?.toAuthTokens()

    override suspend fun refreshTokens(failedAccessToken: String): AuthTokens? =
        tokenRefresher
            .refreshIfCurrent(failedAccessToken = failedAccessToken, clearOnUnauthorized = false)
            ?.toAuthTokens()

    override suspend fun onUnauthorized(failedAccessToken: String?) {
        val current = store.readSession()
        if (failedAccessToken == null || current?.accessToken == failedAccessToken) {
            warningLogger("auth_unauthorized_clear_session")
            store.clearSession()
        }
    }

    private fun ai.slock.android.core.data.auth.AuthSession.toAuthTokens(): AuthTokens =
        AuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken.takeIf { it.isNotBlank() },
            selectedServerId = currentServerId
        )
}
