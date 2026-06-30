package build.raft.app.core.data.network

import build.raft.app.data.SlockAuthSessionStore as AuthSessionStore
import build.raft.app.core.data.auth.AndroidAuthApiFactory
import build.raft.app.data.KtorSlockNetworkClient
import build.raft.app.data.SharedAuthTokenProvider
import build.raft.app.data.SharedKtorSlockAuthTransport
import build.raft.app.data.SlockAuthApiClient
import build.raft.app.data.SlockNetworkClient
import build.raft.app.network.AuthenticatedKtorTransport
import build.raft.app.network.HttpClientConfig
import build.raft.app.network.SLOCK_DEFAULT_MAX_BACKGROUND_REQUESTS
import build.raft.app.network.SLOCK_DEFAULT_MAX_FOREGROUND_REQUESTS
import build.raft.app.network.SLOCK_DEFAULT_MAX_VISIBLE_REQUESTS
import build.raft.app.network.SlockApiClient
import build.raft.app.network.SlockRequestScheduler
import build.raft.app.network.TimeoutConfig
import build.raft.app.network.createPriorityHttpClient

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
