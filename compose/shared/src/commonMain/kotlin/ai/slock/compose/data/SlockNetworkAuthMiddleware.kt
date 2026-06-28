package ai.slock.compose.data

import ai.slock.compose.model.ServerId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SlockNetworkAuthMiddleware(
    private val sessionStore: SlockAuthSessionStore,
    private val authApi: SlockAuthApi
) {
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

        val refreshed = refreshSessionIfCurrent(session)
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

    private suspend fun refreshSessionIfCurrent(failedSession: StoredAuthSession): StoredAuthSession? =
        refreshMutex.withLock {
            val current = sessionStore.readSession() ?: return@withLock null
            if (current.accessToken != failedSession.accessToken) {
                return@withLock current.takeIf { it.accessToken.isNotBlank() }
            }
            val refreshToken = current.refreshToken.takeIf { it.isNotBlank() } ?: return@withLock null
            when (val refreshed = authApi.refresh(refreshToken)) {
                is AuthApiResult.Success -> {
                    val next =
                        current.copy(
                            accessToken = refreshed.data.accessToken,
                            refreshToken = refreshed.data.refreshToken
                        )
                    sessionStore.saveSession(next)
                    next
                }
                is AuthApiResult.Failure -> null
            }
        }

    private fun StoredAuthSession.toRequestContext(serverId: String): SlockAuthenticatedRequestContext =
        SlockAuthenticatedRequestContext(
            accessToken = accessToken,
            authorization = "Bearer $accessToken",
            serverId = serverId
        )

    private companion object {
        val refreshMutex = Mutex()
    }
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
