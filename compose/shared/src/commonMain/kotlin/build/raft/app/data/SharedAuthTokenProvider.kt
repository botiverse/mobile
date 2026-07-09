package build.raft.app.data

import build.raft.app.network.AuthTokenProvider
import build.raft.app.network.AuthTokens
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
    suspend fun currentSession(
        refreshIfExpiringSoon: Boolean = true,
        clearOnUnauthorized: Boolean = true
    ): StoredAuthSession? {
        val current = sessionStore.readSession() ?: return null
        if (!refreshIfExpiringSoon) return current
        val expiresAt = current.accessToken.jwtExpiresAtMillis()
        val shouldRefresh = expiresAt == null || expiresAt - clockMillis() <= refreshSoonThresholdMillis
        if (!shouldRefresh) return current
        val reason = if (expiresAt == null) "missing_exp" else "near_expiry"
        logger("refresh_proactive_start reason=$reason caller=current_session")
        return refreshSession(
            failedAccessToken = current.accessToken,
            clearOnUnauthorized = clearOnUnauthorized,
            reason = reason,
            caller = "current_session"
        )
    }

    suspend fun refreshSession(
        failedAccessToken: String?,
        clearOnUnauthorized: Boolean = true,
        reason: String = "unknown",
        caller: String = "unknown"
    ): StoredAuthSession? =
        processRefreshMutex.withLock {
            val safeReason = reason.toSafeAuthRefreshDiagnosticsToken()
            val safeCaller = caller.toSafeAuthRefreshDiagnosticsToken()
            val current = sessionStore.readSession() ?: return@withLock null
            if (
                failedAccessToken != null &&
                failedAccessToken.isNotBlank() &&
                current.accessToken != failedAccessToken
            ) {
                logger(
                    "refresh_skip reason=token_already_updated caller=$safeCaller requestReason=$safeReason " +
                        "failedAccess=${failedAccessToken.authTokenDiagnosticsFingerprint()} " +
                        "currentAccess=${current.accessToken.authTokenDiagnosticsFingerprint()}"
                )
                return@withLock current
            }
            val refreshToken = current.refreshToken.takeIf { it.isNotBlank() }
                ?: run {
                    warningLogger("refresh_skip reason=missing_refresh_token caller=$safeCaller requestReason=$safeReason")
                    if (clearOnUnauthorized) {
                        sessionStore.clearSession(
                            reason = "missing_refresh_token",
                            source = "token_provider:$safeCaller"
                        )
                    } else {
                        warningLogger(
                            "refresh_missing_refresh_token_session_preserved caller=$safeCaller requestReason=$safeReason"
                        )
                    }
                    return@withLock null
                }
            if (
                !clearOnUnauthorized &&
                recentPreservedUnauthorizedFailure?.matches(
                    current,
                    nowMillis = clockMillis()
                ) == true
            ) {
                warningLogger(
                    "refresh_skip reason=recent_unauthorized_session_preserved caller=$safeCaller requestReason=$safeReason"
                )
                return@withLock null
            }

            logger(
                "refresh_start reason=$safeReason caller=$safeCaller " +
                    "access=${current.accessToken.authTokenDiagnosticsFingerprint()} " +
                    "refresh=${refreshToken.authTokenDiagnosticsFingerprint()}"
            )
            when (val response = authApi.refresh(refreshToken)) {
                is AuthApiResult.Success -> {
                    val updated =
                        current.copy(
                            accessToken = response.data.accessToken,
                            refreshToken = response.data.refreshToken
                        )
                    sessionStore.saveSession(updated)
                    if (recentPreservedUnauthorizedFailure?.matchesTokens(current) == true) {
                        recentPreservedUnauthorizedFailure = null
                    }
                    logger(
                        "refresh_success reason=$safeReason caller=$safeCaller " +
                            "accessRotated=${current.accessToken != updated.accessToken} " +
                            "refreshRotated=${current.refreshToken != updated.refreshToken} " +
                            "oldAccess=${current.accessToken.authTokenDiagnosticsFingerprint()} " +
                            "newAccess=${updated.accessToken.authTokenDiagnosticsFingerprint()} " +
                            "oldRefresh=${current.refreshToken.authTokenDiagnosticsFingerprint()} " +
                            "newRefresh=${updated.refreshToken.authTokenDiagnosticsFingerprint()}"
                    )
                    updated
                }
                is AuthApiResult.Failure -> {
                    val code = response.code
                    warningLogger("refresh_failed status=$code reason=$safeReason caller=$safeCaller")
                    if (code == 401 || code == 403) {
                        if (clearOnUnauthorized) {
                            sessionStore.clearSession(
                                reason = "refresh_http_$code",
                                source = "token_provider:$safeCaller"
                            )
                            recentPreservedUnauthorizedFailure = null
                            warningLogger("refresh_unauthorized_session_cleared status=$code reason=$safeReason caller=$safeCaller")
                        } else {
                            recentPreservedUnauthorizedFailure =
                                PreservedUnauthorizedFailure(
                                    accessToken = current.accessToken,
                                    refreshToken = current.refreshToken,
                                    failedAtMillis = clockMillis()
                                )
                            warningLogger("refresh_unauthorized_session_preserved status=$code reason=$safeReason caller=$safeCaller")
                        }
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
        var recentPreservedUnauthorizedFailure: PreservedUnauthorizedFailure? = null
    }
}

private data class PreservedUnauthorizedFailure(
    val accessToken: String,
    val refreshToken: String,
    val failedAtMillis: Long
) {
    fun matches(session: StoredAuthSession, nowMillis: Long): Boolean =
        matchesTokens(session) &&
            nowMillis - failedAtMillis in 0..PRESERVED_UNAUTHORIZED_BACKOFF_MILLIS

    fun matchesTokens(session: StoredAuthSession): Boolean =
        accessToken == session.accessToken &&
            refreshToken == session.refreshToken
}

private const val PRESERVED_UNAUTHORIZED_BACKOFF_MILLIS = 30_000L

private fun String.authTokenDiagnosticsFingerprint(): String {
    if (isBlank()) return "blank"
    val tail = takeLast(4).toSafeAuthRefreshDiagnosticsToken()
    val hash = (hashCode().toLong() and 0xffffffffL).toString(16)
    return "len=${length}:tail4=$tail:hash=$hash"
}

private fun String.toSafeAuthRefreshDiagnosticsToken(): String =
    trim()
        .ifBlank { "unknown" }
        .replace(Regex("""[^A-Za-z0-9_.:/-]"""), "_")
        .take(96)

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
