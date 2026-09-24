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
    private val terminalAuthLossReporter: suspend (StoredAuthSession, String) -> Unit = { _, _ -> },
    private val environmentIdProvider: () -> String = { "" },
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
        if (
            expectedAuthority != null &&
                !session.matchesExpectedAuthority(
                    expectedAuthority,
                    serverId,
                    environmentIdProvider(),
                )
        ) {
            return authorityChangedResponse()
        }
        if (session.accessToken.isBlank() || serverId.isBlank()) {
            return sessionExpiredResponse()
        }

        val first = block(session.toRequestContext(serverId))
        if (expectedAuthority != null && !expectedAuthority.isCurrent(serverIdOverride)) {
            return authorityChangedResponse()
        }
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
        if (
            expectedAuthority != null &&
                !refreshed.matchesExpectedAuthority(
                    expectedAuthority,
                    retryServerId,
                    environmentIdProvider(),
                )
        ) {
            return authorityChangedResponse()
        }
        if (refreshed.accessToken.isBlank() || retryServerId.isBlank()) {
            return first.asSessionExpired()
        }

        val retry = block(refreshed.toRequestContext(retryServerId))
        if (expectedAuthority != null && !expectedAuthority.isCurrent(serverIdOverride)) {
            return authorityChangedResponse()
        }
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

    private suspend fun SlockExpectedAuthBinding.isCurrent(
        serverIdOverride: ServerId?,
    ): Boolean {
        val current = sessionStore.readSession() ?: return false
        val currentServerId = (serverIdOverride?.value ?: current.currentServerId).orEmpty()
        return current.matchesExpectedAuthority(this, currentServerId, environmentIdProvider())
    }

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
    val serverId: String,
    /**
     * The API environment captured with the session witness.  The empty default is retained only
     * for old non-route unit fixtures; production capture/bound transport rejects it.  A bound
     * operation must always carry a normalized, non-blank environment id.
     */
    val environmentId: String = "",
)

internal fun StoredAuthSession.matchesExpectedAuthority(
    expected: SlockExpectedAuthBinding,
    effectiveServerId: String,
    effectiveEnvironmentId: String? = null,
): Boolean =
    user.id.trim() == expected.accountId.trim() &&
        accessToken == expected.accessToken &&
        refreshToken == expected.refreshToken &&
        currentServerId?.trim() == expected.serverId.trim() &&
        effectiveServerId.trim() == expected.serverId.trim() &&
        expected.environmentId.normalizedEnvironmentId().let { expectedEnvironment ->
            val effectiveEnvironment = effectiveEnvironmentId?.normalizedEnvironmentId().orEmpty()
            // A production/bound witness must carry an environment and must be compared against
            // one at every request boundary.  The blank/blank arm is retained solely for legacy
            // unbound unit fixtures which predate environment-aware authority; a missing live
            // environment never satisfies a non-blank witness.
            if (expectedEnvironment.isBlank()) {
                effectiveEnvironment.isBlank()
            } else {
                effectiveEnvironment == expectedEnvironment
            }
        }

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
