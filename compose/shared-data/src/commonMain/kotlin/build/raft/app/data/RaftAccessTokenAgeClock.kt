package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The times a JWT access token carries, in epoch milliseconds (server clock). */
@RaftCoreInternalApi data class RaftAccessTokenTimes(
    val expiresAtMs: Long,
    val issuedAtMs: Long?,
)

/**
 * Decodes `exp` (and `iat`, when present and `<= exp`) of a 3-segment JWT access token in epoch
 * milliseconds. Returns null when [accessToken] is not a readable JWT with a positive `exp`.
 */
@OptIn(ExperimentalEncodingApi::class)
@RaftCoreInternalApi fun parseRaftAccessTokenTimes(accessToken: String): RaftAccessTokenTimes? {
    val parts = accessToken.trim().split('.')
    if (parts.size != 3 || parts[1].isEmpty()) return null
    return runCatching {
        val payload =
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(parts[1]).decodeToString()
        val claims = Json.parseToJsonElement(payload) as? JsonObject ?: return@runCatching null
        val exp = claims["exp"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return@runCatching null
        val iat = claims["iat"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..exp }
        RaftAccessTokenTimes(exp * 1_000L, iat?.times(1_000L))
    }.getOrNull()
}

/** The `exp` of a JWT access token, in epoch milliseconds; null when it is not a readable JWT. */
@RaftCoreInternalApi fun parseRaftAccessTokenExpiryMillis(accessToken: String): Long? =
    parseRaftAccessTokenTimes(accessToken)?.expiresAtMs

/**
 * Estimates the device clock's offset against the server's (#300, #proj-desktop:#54): the offset is
 * learned from a token this App was just minted (its `iat` is the server's "now"), so a device
 * clock that runs fast or slow neither refreshes in a loop nor rotates too late.
 *
 * Unknown until a refresh with an `iat` has been observed in this run: scheduling reads the device
 * clock as the server's ([serverNow]; a fast clock rotates once, which teaches the offset), while
 * evidence of expiry ([remainingMillis]) never trusts an uncalibrated device clock.
 */
@RaftCoreInternalApi class RaftAccessTokenClock(private val maxRemembered: Int = 8) {
    private val lock = SynchronizedObject()
    private var deviceMinusServerMs: Long? = null
    private val firstSeenAt = LinkedHashMap<String, Long>()

    /** Whether the device-server offset was learned in this run. */
    val offsetKnown: Boolean get() = synchronized(lock) { deviceMinusServerMs != null }

    /** The server's time at [deviceNowMs]; the device's own while the offset is unknown (scheduling only). */
    fun serverNow(deviceNowMs: Long): Long = synchronized(lock) { deviceNowMs - (deviceMinusServerMs ?: 0L) }

    /** When [token] was first seen in this run (now, [deviceNowMs], if never before): when the App observed it. */
    fun noteSeen(token: String, deviceNowMs: Long): Long =
        synchronized(lock) {
            firstSeenAt[token] ?: deviceNowMs.also {
                firstSeenAt[token] = it
                while (firstSeenAt.size > maxRemembered) firstSeenAt.remove(firstSeenAt.keys.first())
            }
        }

    /** [token] was minted just now (the App's own refresh returned it at [deviceNowMs]). */
    fun learnFromFreshToken(token: String, deviceNowMs: Long) {
        noteSeen(token, deviceNowMs)
        val issuedAt = parseRaftAccessTokenTimes(token)?.issuedAtMs ?: return
        synchronized(lock) { deviceMinusServerMs = deviceNowMs - issuedAt }
    }

    /**
     * How long [token] has left, as evidence that it expired (the viewer's sign-out check); null:
     * unknown. With the offset known: by the server's clock. Unknown: the device clock is not
     * trusted against the server's (a fast one would read every fresh token as expired), so the
     * token lasts its lifetime (`exp - iat`) from when this run first saw it; without `iat`, unknown.
     */
    fun remainingMillis(token: String, deviceNowMs: Long): Long? {
        val times = parseRaftAccessTokenTimes(token) ?: return null
        val seenAt = noteSeen(token, deviceNowMs)
        val offset = synchronized(lock) { deviceMinusServerMs }
        if (offset != null) return times.expiresAtMs - (deviceNowMs - offset)
        val issuedAt = times.issuedAtMs ?: return null
        return seenAt + (times.expiresAtMs - issuedAt) - deviceNowMs
    }
}

/** Process-wide access-token clock estimate shared by background age rotation and WebView refresh-ahead. */
@RaftCoreInternalApi val ProcessRaftAccessTokenClock = RaftAccessTokenClock()

/**
 * When a token first observed at [receivedAtDeviceMs] is due for age-based rotation on the device
 * clock (`min(maxAgeMillis, lifetime - expiryLeadMillis)` after `iat`).
 */
@RaftCoreInternalApi data class RaftAccessTokenAgeSchedule(
    val rotateAtDeviceMs: Long,
    val lifetimeMs: Long,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
)

/**
 * Computes when [token] should be rotated by age (`serverNow - iat >= maxAgeMillis`), clamped by
 * `lifetime - expiryLeadMillis` so shorter server TTLs still rotate ahead of `exp`.
 *
 * Returns null if [token] is not a readable JWT or omits `iat` (callers must never force-rotate or
 * spin on opaque/no-`iat` tokens). Clamping `initialAge` to `0..lifetime` ensures a slow uncalibrated
 * device clock still rotates within `targetAge` of observation, while a fast uncalibrated device
 * clock rotates once and calibrates [clock] via [RaftAccessTokenClock.learnFromFreshToken].
 */
@RaftCoreInternalApi fun raftAccessTokenAgeSchedule(
    token: String,
    receivedAtDeviceMs: Long,
    clock: RaftAccessTokenClock,
    maxAgeMillis: Long = RAFT_ACCESS_TOKEN_ROTATE_AGE_MS,
    expiryLeadMillis: Long = RAFT_ACCESS_TOKEN_EXPIRY_LEAD_MS,
): RaftAccessTokenAgeSchedule? {
    val times = parseRaftAccessTokenTimes(token) ?: return null
    val issuedAt = times.issuedAtMs ?: return null
    val lifetime = (times.expiresAtMs - issuedAt).coerceAtLeast(0L)
    val targetAge = minOf(maxAgeMillis, (lifetime - expiryLeadMillis).coerceAtLeast(0L))
    val serverNow = clock.serverNow(receivedAtDeviceMs)
    val initialAge = (serverNow - issuedAt).coerceIn(0L, lifetime)
    return RaftAccessTokenAgeSchedule(
        rotateAtDeviceMs = receivedAtDeviceMs - initialAge + targetAge,
        lifetimeMs = lifetime,
        issuedAtMs = issuedAt,
        expiresAtMs = times.expiresAtMs,
    )
}
