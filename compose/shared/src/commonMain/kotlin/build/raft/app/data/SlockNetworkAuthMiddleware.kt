package build.raft.app.data

import build.raft.app.model.ServerId

internal class SlockNetworkAuthMiddleware(
    private val sessionStore: SlockAuthSessionStore,
    authApi: SlockAuthApi,
    logger: (String) -> Unit = {},
    warningLogger: (String) -> Unit = {},
    criticalLogger: (String) -> Unit = warningLogger
) {
    private val refreshCoordinator =
        SharedAuthRefreshCoordinator(
            sessionStore = sessionStore,
            authApi = authApi,
            clockMillis = { defaultNowMillis() },
            logger = logger,
            warningLogger = warningLogger,
            criticalLogger = criticalLogger
        )

    suspend fun request(
        serverIdOverride: ServerId? = null,
        caller: String = "shared_network",
        block: suspend (SlockAuthenticatedRequestContext) -> HomeNetworkResponse
    ): HomeNetworkResponse {
        val session = sessionStore.readSession()
            ?: return sessionExpiredResponse()
        val serverId = (serverIdOverride?.value ?: session.currentServerId).orEmpty()
        if (session.accessToken.isBlank() || serverId.isBlank()) {
            return sessionExpiredResponse()
        }

        val first = block(session.toRequestContext(serverId))
        if (!first.requiresTokenRefresh()) return first

        val refreshed =
            when (
                val result =
                    refreshAfterUnauthorized(
                        failedSession = session,
                        clearOnUnauthorized = false,
                        reason = "http_401",
                        caller = caller
                    )
            ) {
                is SharedAuthRefreshResult.Success -> result.session
                is SharedAuthRefreshResult.Failure,
                SharedAuthRefreshResult.Unavailable -> null
            }
        if (refreshed == null) {
            return first.asSessionExpired()
        }
        val retryServerId = (serverIdOverride?.value ?: refreshed.currentServerId).orEmpty()
        if (refreshed.accessToken.isBlank() || retryServerId.isBlank()) {
            return first.asSessionExpired()
        }

        val retry = block(refreshed.toRequestContext(retryServerId))
        if (retry.requiresTokenRefresh()) {
            return retry.asSessionExpired()
        }
        return retry
    }

    internal suspend fun refreshAfterUnauthorized(
        failedSession: StoredAuthSession,
        clearOnUnauthorized: Boolean,
        reason: String,
        caller: String
    ): SharedAuthRefreshResult =
        refreshCoordinator.refreshSession(
            failedAccessToken = failedSession.accessToken,
            failedRefreshToken = failedSession.refreshToken,
            clearOnUnauthorized = clearOnUnauthorized,
            clearSource = "network_auth_middleware:${caller.toSafeAuthRefreshDiagnosticsToken()}",
            reason = reason,
            caller = caller
        )

    private fun StoredAuthSession.toRequestContext(serverId: String): SlockAuthenticatedRequestContext =
        SlockAuthenticatedRequestContext(
            accessToken = accessToken,
            authorization = "Bearer $accessToken",
            serverId = serverId
        )

}

internal data class SlockAuthenticatedRequestContext(
    val accessToken: String,
    val authorization: String,
    val serverId: String
)

private const val SESSION_EXPIRED_ERROR = """{"error":"session_expired"}"""

private fun sessionExpiredResponse(): HomeNetworkResponse =
    HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = SESSION_EXPIRED_ERROR)

private fun HomeNetworkResponse.requiresTokenRefresh(): Boolean =
    code == 401 || errorBodyText.isExpiredTokenText()

private fun String?.isExpiredTokenText(): Boolean {
    val normalized = this?.lowercase().orEmpty()
    return normalized.contains("expiredtoken") ||
        normalized.contains("expired_token") ||
        normalized.contains("token_expired") ||
        normalized.contains("jwt expired")
}

private fun HomeNetworkResponse.asSessionExpired(): HomeNetworkResponse =
    HomeNetworkResponse(
        code = 401,
        bodyText = null,
        errorBodyText = errorBodyText.takeUnless { it.isNullOrBlank() } ?: SESSION_EXPIRED_ERROR,
        transportDiagnostics = transportDiagnostics,
        responseHeaders = responseHeaders
    )
