package build.raft.app

import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.runCatchingPreservingCancellation
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * How long before the access token expires a signed-in WebView refreshes it (#300). The server
 * signs access tokens for 15 minutes (botiverse/slock `packages/server/src/middleware/auth.ts`
 * `signAccessToken`, `expiresIn: "15m"`); the WebView holds only that token (its refresh token is
 * stripped, [settingsWebViewAuthStorageJavaScript]), and when one of its requests is refused the
 * web client cannot refresh and signs out to `/` (slock `packages/web/src/api/client.ts`
 * `clearAuthAndRedirect`). So the App refreshes ahead and pushes the new token into the page.
 */
internal const val SETTINGS_WEBVIEW_REFRESH_LEAD_MS = 2 * 60_000L

/** After a refresh that did not give a new token (offline, refused): try again this much later. */
internal const val SETTINGS_WEBVIEW_REFRESH_RETRY_MS = 30_000L

/**
 * The longest single wait. A wait is re-measured against the wall clock after each step, so a
 * device that slept (a coroutine delay may not count deep sleep) still refreshes on time.
 */
internal const val SETTINGS_WEBVIEW_REFRESH_MAX_STEP_MS = 60_000L

/** The `exp` of a JWT access token, in epoch milliseconds; null when it is not a readable JWT. */
@OptIn(ExperimentalEncodingApi::class)
internal fun settingsWebViewAccessTokenExpiryMillis(accessToken: String): Long? {
    val parts = accessToken.trim().split('.')
    if (parts.size != 3 || parts[1].isEmpty()) return null
    return runCatching {
        val payload =
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(parts[1]).decodeToString()
        val exp = (Json.parseToJsonElement(payload) as? JsonObject)?.get("exp")?.jsonPrimitive?.longOrNull
        exp?.takeIf { it > 0 }?.times(1_000L)
    }.getOrNull()
}

/**
 * The refresh-ahead loop for a signed-in WebView on screen. The caller runs it only while such a
 * surface is visible and the App is in the foreground (its coroutine is cancelled otherwise, and
 * started again on return, when an expired or near-expired token is refreshed at once).
 *
 * Waits until [leadMillis] before the current token's `exp`, then asks [refresh] for a new one
 * (single-flight in the App's refresh coordinator; [refresh] gets the token it replaces, so a token
 * someone else already rotated is not refreshed twice). The new token reaches the page through the
 * App's own rotation event ([SettingsWebViewAccessTokenSyncEffect]). Returns when there is no
 * session or the token carries no `exp`.
 */
internal suspend fun runSettingsWebViewAccessTokenRefreshAhead(
    surface: String,
    readAccessToken: suspend () -> String?,
    nowMillis: () -> Long,
    refresh: suspend (reason: String, expiringAccessToken: String) -> Boolean,
    logger: (String) -> Unit = {},
    leadMillis: Long = SETTINGS_WEBVIEW_REFRESH_LEAD_MS,
    retryMillis: Long = SETTINGS_WEBVIEW_REFRESH_RETRY_MS,
    maxStepMillis: Long = SETTINGS_WEBVIEW_REFRESH_MAX_STEP_MS,
) {
    while (true) {
        val token = readAccessToken()?.takeIf { it.isNotBlank() } ?: return
        val expiresAt = settingsWebViewAccessTokenExpiryMillis(token)
        if (expiresAt == null) {
            logger("settings_webview_refresh_ahead surface=$surface outcome=no_expiry")
            return
        }
        val wait = expiresAt - leadMillis - nowMillis()
        if (wait > 0) {
            delay(minOf(wait, maxStepMillis))
            continue
        }
        val expired = expiresAt <= nowMillis()
        val refreshed = refresh(if (expired) "webview_token_expired" else "webview_token_expiring", token)
        val next = readAccessToken()
        val rotated = refreshed && next != null && next != token
        logger(
            "settings_webview_refresh_ahead surface=$surface " +
                "outcome=${if (rotated) "refreshed" else "failed"} expired=$expired"
        )
        if (!rotated) delay(retryMillis)
    }
}

/** The refresh a signed-in WebView surface uses: the shell's session store and its auth data source. */
internal class SettingsWebViewAccessTokenRefresher(
    private val sessionStore: RaftAuthSessionStore,
    private val authDataSource: SharedAuthDataSource,
) {
    suspend fun currentAccessToken(): String? =
        runCatchingPreservingCancellation { sessionStore.readSession()?.accessToken }.getOrNull()

    /**
     * Refreshes (or finds already rotated) the token [expiringAccessToken]; null refreshes whatever
     * is stored. False when the refresh failed or threw.
     */
    suspend fun refresh(reason: String, expiringAccessToken: String?): Boolean =
        runCatchingPreservingCancellation {
            authDataSource.refreshWebViewAccessToken(reason, expiringAccessToken) is SharedAuthResult.Success
        }.getOrDefault(false)
}
