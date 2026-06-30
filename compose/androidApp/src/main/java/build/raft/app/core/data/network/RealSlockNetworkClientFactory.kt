package build.raft.app.core.data.network

import ai.slock.compose.data.SlockAuthSessionStore as AuthSessionStore
import build.raft.app.core.data.auth.AndroidAuthApiFactory
import ai.slock.compose.data.KtorSlockNetworkClient
import ai.slock.compose.data.SharedAuthTokenProvider
import ai.slock.compose.data.SharedKtorSlockAuthTransport
import ai.slock.compose.data.SlockAuthApiClient
import ai.slock.compose.data.SlockNetworkClient
import ai.slock.compose.network.AuthenticatedKtorTransport
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
        baseUrl: String = AndroidAuthApiFactory.BASE_URL
    ): SlockNetworkClient {
        val logger: (String) -> Unit = { message ->
            build.raft.app.core.data.diagnostics.SlockDiagnosticsLogger.d(TAG, message)
        }
        val warningLogger: (String) -> Unit = { message ->
            build.raft.app.core.data.diagnostics.SlockDiagnosticsLogger.w(TAG, message)
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
                            SharedAuthTokenProvider(
                                sessionStore = store,
                                authApi =
                                    SlockAuthApiClient(
                                        SharedKtorSlockAuthTransport(
                                            baseUrl = baseUrl,
                                            warningLogger = warningLogger
                                        )
                                    ),
                                clockMillis = { System.currentTimeMillis() },
                                logger = { message -> logger("auth_$message") },
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
