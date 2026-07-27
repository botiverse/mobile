package build.raft.app.data

import build.raft.app.model.ServerId

/**
 * task #788: declared ceiling for how many times [SlockNetworkAuthMiddleware]
 * invokes the request block — the first attempt plus at most one retry after
 * a 401 refresh (no loop exists in `request`). The streaming-upload scheduler
 * backstop derives from this; a behavior test pins the implementation to it.
 */
internal const val SLOCK_AUTH_MAX_REQUEST_ATTEMPTS = 2

internal class SlockNetworkAuthMiddleware(
    private val sessionStore: SlockAuthSessionStore,
    authApi: SlockAuthApi,
    logger: (String) -> Unit = {},
    warningLogger: (String) -> Unit = {},
    criticalLogger: (String) -> Unit = warningLogger,
    private val terminalAuthLossReporter: suspend (StoredAuthSession, String) -> Unit = { _, _ -> }
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
        expectedAuthority: SlockExpectedAuthBinding? = null,
        caller: String = "shared_network",
        block: suspend (SlockAuthenticatedRequestContext) -> HomeNetworkResponse
    ): HomeNetworkResponse {
        val session = sessionStore.readSession()
            ?: return sessionExpiredResponse()
        val serverId = (serverIdOverride?.value ?: session.currentServerId).orEmpty()
        if (expectedAuthority != null && !session.matchesExpectedAuthority(expectedAuthority, serverId)) {
            return authorityChangedResponse()
        }
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
                is SharedAuthRefreshResult.Failure -> {
                    if (result.response.code == 401 || result.response.code == 403) {
                        terminalAuthLossReporter(session, caller)
                    } else {
                        return result.response.toNetworkResponse()
                    }
                    null
                }
                SharedAuthRefreshResult.Unavailable -> {
                    terminalAuthLossReporter(session, caller)
                    null
                }
            }
        if (refreshed == null) {
            return first.asSessionExpired()
        }
        val retryServerId = (serverIdOverride?.value ?: refreshed.currentServerId).orEmpty()
        if (expectedAuthority != null && !refreshed.matchesExpectedAuthority(expectedAuthority, retryServerId)) {
            return authorityChangedResponse()
        }
        if (refreshed.accessToken.isBlank() || retryServerId.isBlank()) {
            return first.asSessionExpired()
        }

        val retry = block(refreshed.toRequestContext(retryServerId))
        if (retry.requiresTokenRefresh()) {
            terminalAuthLossReporter(refreshed, caller)
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

/** Transient request witness. Never persist or log its token fields. */
internal data class SlockExpectedAuthBinding(
    val accountId: String,
    val accessToken: String,
    val refreshToken: String,
    val serverId: String
)

internal fun StoredAuthSession.matchesExpectedAuthority(
    expected: SlockExpectedAuthBinding,
    effectiveServerId: String
): Boolean =
    user.id == expected.accountId &&
        accessToken == expected.accessToken &&
        refreshToken == expected.refreshToken &&
        effectiveServerId == expected.serverId

private const val SESSION_EXPIRED_ERROR = """{"error":"session_expired"}"""
private const val AUTHORITY_CHANGED_ERROR = """{"error":"auth_authority_changed"}"""

private fun sessionExpiredResponse(): HomeNetworkResponse =
    HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = SESSION_EXPIRED_ERROR)

private fun authorityChangedResponse(): HomeNetworkResponse =
    HomeNetworkResponse(code = 409, bodyText = null, errorBodyText = AUTHORITY_CHANGED_ERROR)

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

private fun AuthApiResult.Failure.toNetworkResponse(): HomeNetworkResponse =
    HomeNetworkResponse(
        code = code,
        bodyText = null,
        errorBodyText = errorBodyText,
    )
