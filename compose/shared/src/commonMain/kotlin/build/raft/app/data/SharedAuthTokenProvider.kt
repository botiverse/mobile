package build.raft.app.data

class SharedAuthTokenProvider(
    private val sessionStore: RaftAuthSessionStore,
    private val authApi: RaftAuthApi,
    private val clockMillis: () -> Long,
    private val logger: (String) -> Unit = {},
    private val warningLogger: (String) -> Unit = {},
    private val criticalLogger: (String) -> Unit = warningLogger
) {
    private val refreshCoordinator =
        SharedAuthRefreshCoordinator(
            sessionStore = sessionStore,
            authApi = authApi,
            clockMillis = clockMillis,
            logger = logger,
            warningLogger = warningLogger,
            criticalLogger = criticalLogger
        )

    suspend fun currentSession(): StoredAuthSession? = sessionStore.readSession()

    suspend fun refreshSession(
        failedAccessToken: String?,
        failedRefreshToken: String? = null,
        clearOnUnauthorized: Boolean = true,
        reason: String = "unknown",
        caller: String = "unknown"
    ): StoredAuthSession? =
        when (
            val result =
                refreshCoordinator.refreshSession(
                    failedAccessToken = failedAccessToken,
                    failedRefreshToken = failedRefreshToken,
                    clearOnUnauthorized = clearOnUnauthorized,
                    clearSource = "token_provider:${caller.toSafeAuthRefreshDiagnosticsToken()}",
                    reason = reason,
                    caller = caller
                )
        ) {
            is SharedAuthRefreshResult.Success -> result.session
            is SharedAuthRefreshResult.Failure,
            SharedAuthRefreshResult.Unavailable -> null
        }

}
