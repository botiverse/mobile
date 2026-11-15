package build.raft.app

import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.runCatchingPreservingCancellation
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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

/** After a refresh that gave a new token, never refresh again sooner than this (or half its lifetime). */
internal const val SETTINGS_WEBVIEW_REFRESH_MIN_GAP_MS = 60_000L

/** The times a JWT access token carries, in epoch milliseconds (server clock). */
internal data class SettingsWebViewAccessTokenTimes(val expiresAtMs: Long, val issuedAtMs: Long?)

/** `exp` (and `iat`, when present) of a JWT access token; null when it is not a readable JWT with an `exp`. */
@OptIn(ExperimentalEncodingApi::class)
internal fun settingsWebViewAccessTokenTimes(accessToken: String): SettingsWebViewAccessTokenTimes? {
    val parts = accessToken.trim().split('.')
    if (parts.size != 3 || parts[1].isEmpty()) return null
    return runCatching {
        val payload =
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(parts[1]).decodeToString()
        val claims = Json.parseToJsonElement(payload) as? JsonObject ?: return@runCatching null
        val exp = claims["exp"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return@runCatching null
        val iat = claims["iat"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..exp }
        SettingsWebViewAccessTokenTimes(exp * 1_000L, iat?.times(1_000L))
    }.getOrNull()
}

/** The `exp` of a JWT access token, in epoch milliseconds; null when it is not a readable JWT. */
internal fun settingsWebViewAccessTokenExpiryMillis(accessToken: String): Long? =
    settingsWebViewAccessTokenTimes(accessToken)?.expiresAtMs

/**
 * The device clock against the server's (#300 review): the offset is learned from a token this
 * App was just given (its `iat` is the server's "now"), so a device clock that runs fast or slow
 * neither refreshes in a loop nor too late. Unknown (0) until a refresh with an `iat` was seen.
 */
internal class SettingsWebViewTokenClock {
    private val lock = SynchronizedObject()
    private var deviceMinusServerMs: Long = 0L

    fun serverNow(deviceNowMs: Long): Long = synchronized(lock) { deviceNowMs - deviceMinusServerMs }

    /** [token] was minted just now (the App's own refresh returned it at [deviceNowMs]). */
    fun learnFromFreshToken(token: String, deviceNowMs: Long) {
        val issuedAt = settingsWebViewAccessTokenTimes(token)?.issuedAtMs ?: return
        synchronized(lock) { deviceMinusServerMs = deviceNowMs - issuedAt }
    }

    /** How long [token] has left by the server's clock; null without an `exp`. */
    fun remainingMillis(token: String, deviceNowMs: Long): Long? =
        settingsWebViewAccessTokenTimes(token)?.let { it.expiresAtMs - serverNow(deviceNowMs) }
}

/** The process' clock estimate, shared by every signed-in WebView surface. */
internal val ProcessSettingsWebViewTokenClock = SettingsWebViewTokenClock()

/**
 * When a token first seen at [receivedAtDeviceMs] is due for refresh, on the device clock:
 * received + lifetime − lead, less the age it already had then. The lifetime is `exp − iat`
 * (without `iat`, what was left of it then); the age is read on the server's clock and kept within
 * the lifetime, so an unknown clock offset can make it due at once (fast clock: once, then the offset
 * is learned) or no later than a full lifetime after it was received (slow clock).
 */
internal data class SettingsWebViewTokenSchedule(val refreshAtDeviceMs: Long, val lifetimeMs: Long, val expiresAtMs: Long)

internal fun settingsWebViewTokenSchedule(
    token: String,
    receivedAtDeviceMs: Long,
    clock: SettingsWebViewTokenClock,
    leadMillis: Long = SETTINGS_WEBVIEW_REFRESH_LEAD_MS,
): SettingsWebViewTokenSchedule? {
    val times = settingsWebViewAccessTokenTimes(token) ?: return null
    val serverNow = clock.serverNow(receivedAtDeviceMs)
    val issuedAt = times.issuedAtMs
    val lifetime = (if (issuedAt != null) times.expiresAtMs - issuedAt else times.expiresAtMs - serverNow).coerceAtLeast(0L)
    val age = if (issuedAt != null) (serverNow - issuedAt).coerceIn(0L, lifetime) else 0L
    return SettingsWebViewTokenSchedule(receivedAtDeviceMs - age + lifetime - leadMillis, lifetime, times.expiresAtMs)
}

/**
 * The refresh-ahead loop for a signed-in WebView on screen. The caller runs it only while such a
 * surface is visible and the App is in the foreground (its coroutine is cancelled otherwise, and
 * started again on return, when an expired or near-expired token is refreshed at once).
 *
 * Each token is refreshed when [settingsWebViewTokenSchedule] says (received + lifetime − [leadMillis]),
 * through [refresh] (single-flight in the App's refresh coordinator; it gets the token it replaces,
 * so a token someone else already rotated is not refreshed twice). After a refresh that gave a new
 * token the next one waits at least [minGapMillis] (or half the new token's lifetime): a token that
 * is born inside the lead window never makes it spin. The new token reaches the page through the
 * App's own rotation event ([SettingsWebViewAccessTokenSyncEffect]). Returns when there is no
 * session or the token carries no `exp`.
 */
internal suspend fun runSettingsWebViewAccessTokenRefreshAhead(
    surface: String,
    readAccessToken: suspend () -> String?,
    nowMillis: () -> Long,
    refresh: suspend (reason: String, expiringAccessToken: String) -> Boolean,
    logger: (String) -> Unit = {},
    clock: SettingsWebViewTokenClock = ProcessSettingsWebViewTokenClock,
    leadMillis: Long = SETTINGS_WEBVIEW_REFRESH_LEAD_MS,
    retryMillis: Long = SETTINGS_WEBVIEW_REFRESH_RETRY_MS,
    minGapMillis: Long = SETTINGS_WEBVIEW_REFRESH_MIN_GAP_MS,
    maxStepMillis: Long = SETTINGS_WEBVIEW_REFRESH_MAX_STEP_MS,
) {
    var seenToken: String? = null
    var schedule: SettingsWebViewTokenSchedule? = null
    var notBeforeMs = Long.MIN_VALUE
    while (true) {
        val token = readAccessToken()?.takeIf { it.isNotBlank() } ?: return
        if (token != seenToken) {
            schedule = settingsWebViewTokenSchedule(token, nowMillis(), clock, leadMillis)
            seenToken = token
        }
        val due = schedule
        if (due == null) {
            logger("settings_webview_refresh_ahead surface=$surface outcome=no_expiry")
            return
        }
        val now = nowMillis()
        val dueAt = maxOf(due.refreshAtDeviceMs, notBeforeMs)
        if (now < dueAt) {
            delay(minOf(dueAt - now, maxStepMillis))
            continue
        }
        val expired = clock.serverNow(now) >= due.expiresAtMs
        val refreshed = refresh(if (expired) "webview_token_expired" else "webview_token_expiring", token)
        val next = readAccessToken()
        val rotated = refreshed && next != null && next != token
        logger(
            "settings_webview_refresh_ahead surface=$surface " +
                "outcome=${if (rotated) "refreshed" else "failed"} expired=$expired"
        )
        if (rotated) {
            val at = nowMillis()
            clock.learnFromFreshToken(next!!, at)
            val nextLifetime = settingsWebViewTokenSchedule(next, at, clock, leadMillis)?.lifetimeMs ?: 0L
            // Never sooner than the retry pause either, even for a token with no lifetime left.
            notBeforeMs = at + minOf(minGapMillis, nextLifetime / 2).coerceAtLeast(minOf(retryMillis, minGapMillis))
        } else {
            delay(retryMillis)
        }
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
