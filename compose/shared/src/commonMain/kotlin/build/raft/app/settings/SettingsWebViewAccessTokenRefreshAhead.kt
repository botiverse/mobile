package build.raft.app.settings

import build.raft.app.data.ProcessRaftAccessTokenClock
import build.raft.app.data.RaftAccessTokenClock
import build.raft.app.data.RaftAccessTokenTimes
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.SharedAuthDataSource
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.parseRaftAccessTokenExpiryMillis
import build.raft.app.data.parseRaftAccessTokenTimes
import build.raft.app.data.runCatchingPreservingCancellation
import kotlinx.coroutines.delay

/**
 * How long before the access token expires a signed-in WebView refreshes it (#300). Under 15m
 * tokens this refreshes at 13m (`exp - 2m`); under longer TTLs (up to 24h, #proj-desktop:#54) the
 * app-wide foreground age rotator rotates every ~13m and pushes each fresh token into open WebViews
 * via [SettingsWebViewAccessTokenSyncEffect], while this loop stays an expiry safety net.
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
internal typealias SettingsWebViewAccessTokenTimes = RaftAccessTokenTimes

/** `exp` (and `iat`, when present) of a JWT access token; null when it is not a readable JWT with an `exp`. */
internal fun settingsWebViewAccessTokenTimes(accessToken: String): SettingsWebViewAccessTokenTimes? =
    parseRaftAccessTokenTimes(accessToken)

/** The `exp` of a JWT access token, in epoch milliseconds; null when it is not a readable JWT. */
internal fun settingsWebViewAccessTokenExpiryMillis(accessToken: String): Long? =
    parseRaftAccessTokenExpiryMillis(accessToken)

/**
 * Shared device-vs-server token clock ([RaftAccessTokenClock]), reused by both WebView refresh-ahead
 * and the process-wide foreground token-age rotator (#proj-desktop:#54).
 */
internal typealias SettingsWebViewTokenClock = RaftAccessTokenClock

/** The process' clock estimate, shared by every signed-in WebView surface and background age rotation. */
internal val ProcessSettingsWebViewTokenClock: SettingsWebViewTokenClock = ProcessRaftAccessTokenClock

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
            val receivedAt = nowMillis()
            clock.noteSeen(token, receivedAt)
            schedule = settingsWebViewTokenSchedule(token, receivedAt, clock, leadMillis)
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
