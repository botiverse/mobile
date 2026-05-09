package ai.slock.android.core.data.auth

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64

class AuthTokenRefresher(
    private val store: AuthSessionStore,
    private val api: AuthApi = RetrofitAuthRepository.createAuthApi(),
    private val refreshSoonThresholdMillis: Long = DEFAULT_REFRESH_SOON_THRESHOLD_MILLIS,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val logger: (String) -> Unit = { message -> ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(TAG, message) },
    private val warningLogger: (String) -> Unit = { message -> ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(TAG, message) }
) {
    private val gson = Gson()
    private val mutex = Mutex()

    suspend fun refreshIfExpiringSoon(clearOnUnauthorized: Boolean = true): AuthSession? {
        val current = store.readSession() ?: return null
        val expiresAt = current.accessToken.jwtExpiresAtMillis()
        val shouldRefresh = expiresAt == null || expiresAt - clockMillis() <= refreshSoonThresholdMillis
        if (!shouldRefresh) return current
        logger(
            "refresh_proactive_start reason=${if (expiresAt == null) "missing_exp" else "near_expiry"}"
        )
        return refreshIfCurrent(current.accessToken, clearOnUnauthorized = clearOnUnauthorized)
    }

    suspend fun refreshIfCurrent(
        failedAccessToken: String?,
        clearOnUnauthorized: Boolean = true
    ): AuthSession? =
        mutex.withLock {
            val current = store.readSession() ?: return@withLock null
            if (
                failedAccessToken != null &&
                failedAccessToken.isNotBlank() &&
                current.accessToken != failedAccessToken
            ) {
                logger("refresh_skip reason=token_already_updated")
                return@withLock current
            }
            if (current.refreshToken.isBlank()) {
                warningLogger("refresh_skip reason=missing_refresh_token")
                store.clearSession()
                return@withLock null
            }

            try {
                logger("refresh_start")
                val response = api.refresh(jsonBody(RefreshRequest(current.refreshToken)))
                if (!response.isSuccessful) {
                    val code = response.code()
                    warningLogger("refresh_failed status=$code")
                    if (code == 401 || code == 403) {
                        if (clearOnUnauthorized) {
                            store.clearSession()
                        } else {
                            warningLogger("refresh_unauthorized_session_preserved")
                        }
                    }
                    return@withLock null
                }
                val rawBody = response.body()?.string().orEmpty()
                if (rawBody.isBlank()) {
                    warningLogger("refresh_failed reason=empty_body")
                    return@withLock null
                }
                val refreshed = gson.fromJson(rawBody, RefreshResponse::class.java)
                val updated = current.copy(
                    accessToken = refreshed.accessToken,
                    refreshToken = refreshed.refreshToken
                )
                store.saveSession(updated)
                logger("refresh_success")
                updated
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: IOException) {
                warningLogger("refresh_failed reason=network")
                null
            } catch (error: Exception) {
                warningLogger("refresh_failed reason=${error::class.java.simpleName}")
                null
            }
        }

    private fun jsonBody(value: Any) =
        gson.toJson(value).toRequestBody(JSON)

    private fun String.jwtExpiresAtMillis(): Long? {
        return try {
            val parts = split(".")
            if (parts.size != 3) return null
            val normalizedPayload = parts[1]
                .replace('-', '+')
                .replace('_', '/')
                .let { payload -> payload + "=".repeat((4 - payload.length % 4) % 4) }
            val payloadJson = normalizedPayload.decodeBase64()?.utf8() ?: return null
            val root = gson.fromJson(payloadJson, JsonObject::class.java)
            root.get("exp")?.takeIf { it.isJsonPrimitive }?.asLong?.times(1000L)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val TAG = "SlockAuth"
        const val DEFAULT_REFRESH_SOON_THRESHOLD_MILLIS = 60_000L
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
