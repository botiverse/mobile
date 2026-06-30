package build.raft.app.data

import build.raft.app.model.ServerId

internal class SlockNetworkAuthMiddleware(
    private val sessionStore: SlockAuthSessionStore,
    authApi: SlockAuthApi
) {
    private val tokenProvider =
        SharedAuthTokenProvider(
            sessionStore = sessionStore,
            authApi = authApi,
            clockMillis = { defaultNowMillis() }
        )

    suspend fun request(
        serverIdOverride: ServerId? = null,
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

        val refreshed = tokenProvider.refreshSession(session.accessToken)
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
        errorBodyText = errorBodyText.takeUnless { it.isNullOrBlank() } ?: SESSION_EXPIRED_ERROR
    )
