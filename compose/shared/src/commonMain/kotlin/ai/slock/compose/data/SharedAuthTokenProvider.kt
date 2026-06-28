package ai.slock.compose.data

import ai.slock.compose.network.AuthTokenProvider
import ai.slock.compose.network.AuthTokens
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

class SharedAuthTokenProvider(
    private val sessionStore: SlockAuthSessionStore,
    private val authApi: SlockAuthApi,
    private val refreshSoonThresholdMillis: Long = DEFAULT_REFRESH_SOON_THRESHOLD_MILLIS,
    private val clockMillis: () -> Long,
    private val logger: (String) -> Unit = {},
    private val warningLogger: (String) -> Unit = {}
) : AuthTokenProvider {
    suspend fun currentSession(refreshIfExpiringSoon: Boolean = true): StoredAuthSession? {
        val current = sessionStore.readSession() ?: return null
        if (!refreshIfExpiringSoon) return current
        val expiresAt = current.accessToken.jwtExpiresAtMillis()
        val shouldRefresh = expiresAt == null || expiresAt - clockMillis() <= refreshSoonThresholdMillis
        if (!shouldRefresh) return current
        logger("refresh_proactive_start reason=${if (expiresAt == null) "missing_exp" else "near_expiry"}")
        return refreshSession(failedAccessToken = current.accessToken)
    }

    suspend fun refreshSession(failedAccessToken: String?): StoredAuthSession? =
        processRefreshMutex.withLock {
            val current = sessionStore.readSession() ?: return@withLock null
            if (
                failedAccessToken != null &&
                failedAccessToken.isNotBlank() &&
                current.accessToken != failedAccessToken
            ) {
                logger("refresh_skip reason=token_already_updated")
                return@withLock current
            }
            val refreshToken = current.refreshToken.takeIf { it.isNotBlank() }
                ?: run {
                    warningLogger("refresh_skip reason=missing_refresh_token")
                    sessionStore.clearSession()
                    return@withLock null
                }

            logger("refresh_start")
            when (val response = authApi.refresh(refreshToken)) {
                is AuthApiResult.Success -> {
                    val updated =
                        current.copy(
                            accessToken = response.data.accessToken,
                            refreshToken = response.data.refreshToken
                        )
                    sessionStore.saveSession(updated)
                    logger("refresh_success")
                    updated
                }
                is AuthApiResult.Failure -> {
                    val code = response.code
                    warningLogger("refresh_failed status=$code")
                    if (code == 401 || code == 403) {
                        sessionStore.clearSession()
                        warningLogger("refresh_unauthorized_session_cleared")
                    }
                    null
                }
            }
        }

    override suspend fun currentTokens(): AuthTokens? =
        currentSession(refreshIfExpiringSoon = true)?.toNetworkAuthTokens()

    override suspend fun refreshTokens(failedAccessToken: String): AuthTokens? =
        refreshSession(failedAccessToken = failedAccessToken)?.toNetworkAuthTokens()

    override suspend fun onUnauthorized(failedAccessToken: String?) {
        warningLogger("auth_retry_unauthorized_session_preserved")
    }

    private fun StoredAuthSession.toNetworkAuthTokens(): AuthTokens =
        AuthTokens(
            accessToken = accessToken,
            refreshToken = refreshToken.takeIf { it.isNotBlank() },
            selectedServerId = currentServerId
        )

    private companion object {
        const val DEFAULT_REFRESH_SOON_THRESHOLD_MILLIS = 60_000L
        val processRefreshMutex = Mutex()
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun String.jwtExpiresAtMillis(): Long? {
    return try {
        val parts = split(".")
        if (parts.size != 3) return null
        val normalizedPayload =
            parts[1]
                .replace('-', '+')
                .replace('_', '/')
                .let { payload -> payload + "=".repeat((4 - payload.length % 4) % 4) }
        val payloadJson = Base64.decode(normalizedPayload).decodeToString()
        val root = Json.parseToJsonElement(payloadJson) as? JsonObject ?: return null
        root["exp"]?.jsonPrimitive?.longOrNull?.times(1000L)
    } catch (_: Exception) {
        null
    }
}
