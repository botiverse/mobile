package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ServerId
import build.raft.app.network.raftValidTraceIdOrNull
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

// Composer streaming voice dictation — thin-client protocol v2 HTTP permit API (`#wg-mobile-sync:#334`).
// Authoritative contract: `botiverse/slock/docs/contracts/voice-dictation-v2.md` (#17146 + #17149),
// `packages/shared/src/voiceDictation.ts`, `packages/server/src/routes/voiceDictation.ts`.
//
// Strictly v2 only: mobile never requests v1 permits, never renews permits every 30 s, and never
// reads or forwards v1 `context` or `ttlMs` fields.

/** Wire protocol version sent on `POST /api/voice/dictation/permits` and WS `start`. */
@RaftCoreInternalApi
const val VOICE_DICTATION_PROTOCOL_VERSION: Int = 2

/** Relative API path for minting a single-use dictation stream permit (`POST /api/voice/dictation/permits`). */
@RaftCoreInternalApi
const val VOICE_DICTATION_PERMITS_PATH: String = "voice/dictation/permits"

/**
 * ISO-639-1 language hints accepted by `POST /api/voice/dictation/permits`
 * (mirrors `VOICE_DICTATION_LANGUAGES` in `@botiverse/raft-shared`).
 */
@RaftCoreInternalApi
val VOICE_DICTATION_SUPPORTED_LANGUAGES: Set<String> =
    setOf("zh", "en", "ja", "ko", "es", "pt", "ru", "fr", "de")

private val VOICE_DICTATION_UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

private val voiceDictationJson = Json { ignoreUnknownKeys = true }

/**
 * Normalizes a BCP-47 or ISO-639-1 tag (e.g. `"zh-Hans-CN"`, `"en_US"`, `"JA"`) to the server's
 * strict [VOICE_DICTATION_SUPPORTED_LANGUAGES] allowlist, returning `null` when unsupported so the
 * client omits `language` instead of triggering `400 invalid_request`.
 */
@RaftCoreInternalApi
fun normalizeVoiceDictationLanguage(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val primary = trimmed.substringBefore('-').substringBefore('_').lowercase()
    return primary.takeIf { it in VOICE_DICTATION_SUPPORTED_LANGUAGES }
}

/**
 * Validates and trims an optional conversation UUID for `channelId`, returning `null` when blank or
 * not a canonical UUID so the strict server schema never rejects it with `400 invalid_request`.
 */
@RaftCoreInternalApi
fun normalizeVoiceDictationChannelId(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return trimmed.takeIf { VOICE_DICTATION_UUID_REGEX.matches(it) }
}

/**
 * Validates that [raw] is a well-formed WebSocket URL (`wss://` or `ws://` for local dev/test)
 * with a non-empty host and no whitespace or control characters.
 */
@RaftCoreInternalApi
fun isValidVoiceDictationStreamUrl(raw: String?): Boolean {
    val url = raw ?: return false
    if (url.isEmpty() || url.any { it <= ' ' }) return false
    val afterScheme =
        when {
            url.startsWith("wss://", ignoreCase = true) -> url.substring(6)
            url.startsWith("ws://", ignoreCase = true) -> url.substring(5)
            else -> return false
        }
    if (afterScheme.isEmpty()) return false
    val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) afterScheme.length else it }
    val authority = afterScheme.substring(0, authorityEnd)
    if (authority.isEmpty()) return false
    val host: String
    val portText: String?
    if (authority.startsWith("[")) {
        val closing = authority.indexOf(']')
        if (closing <= 1) return false
        host = authority.substring(1, closing)
        val remainder = authority.substring(closing + 1)
        portText =
            when {
                remainder.isEmpty() -> null
                remainder.startsWith(":") -> remainder.substring(1)
                else -> return false
            }
    } else {
        val colonIndex = authority.indexOf(':')
        if (colonIndex < 0) {
            host = authority
            portText = null
        } else {
            host = authority.substring(0, colonIndex)
            portText = authority.substring(colonIndex + 1)
        }
    }
    if (host.isEmpty() || host.startsWith('.')) return false
    if (portText != null) {
        val port = portText.toIntOrNull() ?: return false
        if (port !in 1..65_535) return false
    }
    return true
}

/** Daily dictation quota snapshot returned by `POST /api/voice/dictation/permits` (`v: 2`). */
@RaftCoreInternalApi
data class VoiceDictationV2Quota(
    val usedSeconds: Int,
    val limitSeconds: Int,
    val remainingSeconds: Int,
    val resetsAtIso: String,
    val resetsAtEpochMs: Long? = null,
)

/** Parsed v2 dictation permit response (`201` / `200`). */
@RaftCoreInternalApi
data class VoiceDictationV2Permit(
    val permit: String,
    val url: String,
    val expiresAtIso: String,
    val expiresAtEpochMs: Long,
    val maxDurationSeconds: Int,
    val quota: VoiceDictationV2Quota,
    /** True when `holt-live` `DictationQuota` failed open under the 180 s cap (`quota_degraded: true`). */
    val quotaDegraded: Boolean = false,
    /** Server-authoritative dictation session ID (`permit.jti`) for feedback correlation (`slock#17246`). */
    val sessionId: String? = null,
    /** Server-generated 32-hex W3C trace ID forwarded via `?tp=` on `/v1/stream` (`slock#17246`). */
    val traceId: String? = null,
    /** Which stream route the server issued (`direct` vs `raft_edge`; `slock#17246`). */
    val streamUrlSource: VoiceStreamUrlSource? = null,
)

/** Typed refusals and transport/contract failures for `POST /api/voice/dictation/permits` (`v: 2`). */
@RaftCoreInternalApi
sealed interface VoiceDictationV2PermitFailure {
    val traceId: String?
    val serverMessage: String?

    /** `404 voice_dictation_disabled` (`voice_dictation_v1` off or guest caller). */
    data class Disabled(
        override val traceId: String? = null,
        override val serverMessage: String? = null,
    ) : VoiceDictationV2PermitFailure

    /** `429 dictation_rate_limited` (short-window burst guard exceeded). */
    data class RateLimited(
        val retryAfterSeconds: Int? = null,
        override val traceId: String? = null,
        override val serverMessage: String? = null,
    ) : VoiceDictationV2PermitFailure

    /** `429 dictation_quota_exceeded` (per-server daily UTC allowance exhausted). */
    data class QuotaExceeded(
        val usedSeconds: Int? = null,
        val limitSeconds: Int? = null,
        val resetsAtIso: String? = null,
        val retryAfterSeconds: Int? = null,
        override val traceId: String? = null,
        override val serverMessage: String? = null,
    ) : VoiceDictationV2PermitFailure

    /** `503 voice_dictation_unavailable` (server unconfigured or `holt-live` v2 unavailable). */
    data class Unavailable(
        override val traceId: String? = null,
        override val serverMessage: String? = null,
    ) : VoiceDictationV2PermitFailure

    /** `400 invalid_request` (strict request validation failure). */
    data class InvalidRequest(
        override val traceId: String? = null,
        override val serverMessage: String? = null,
    ) : VoiceDictationV2PermitFailure

    /** Unexpected HTTP status, malformed response body (fail-closed), or transport error. */
    data class Failed(
        val statusCode: Int? = null,
        val code: String? = null,
        override val traceId: String? = null,
        override val serverMessage: String? = null,
    ) : VoiceDictationV2PermitFailure
}

/** Default negative-cache TTL for servers that reject caller-context permit fields with HTTP 400 (5 minutes). */
@RaftCoreInternalApi
const val VOICE_DICTATION_CALLER_CONTEXT_FALLBACK_TTL_MS: Long = 300_000L

/** Result of requesting a v2 dictation permit. */
@RaftCoreInternalApi
sealed interface VoiceDictationV2PermitOutcome {
    data class Issued(
        val permit: VoiceDictationV2Permit,
        val traceId: String? = null,
        val usedCallerContextFallback: Boolean = false,
    ) : VoiceDictationV2PermitOutcome

    data class Refused(
        val failure: VoiceDictationV2PermitFailure,
    ) : VoiceDictationV2PermitOutcome
}

/**
 * Returns `true` when [draftBefore], [draftAfter], or [contactHints] carries at least one
 * non-empty normalized caller-context value to attach to an initial
 * `POST /api/voice/dictation/permits` request.
 */
@RaftCoreInternalApi
fun hasVoiceDictationPermitCallerContext(
    draftBefore: String? = null,
    draftAfter: String? = null,
    contactHints: List<String>? = null,
): Boolean =
    normalizeVoiceDictationDraftBefore(draftBefore) != null ||
        normalizeVoiceDictationDraftAfter(draftAfter) != null ||
        normalizeVoiceDictationContactHints(contactHints) != null

/**
 * Encodes the strict JSON body for `POST /api/voice/dictation/permits`:
 * `{ "v": 2, "language"?: <iso639-1>, "channelId"?: <uuid>, "draftBefore"?: <string>, "draftAfter"?: <string>, "contactHints"?: [<string>, ...] }`.
 * Omits `draftBefore` / `draftAfter` when null/blank and omits `contactHints` when null/empty.
 */
@RaftCoreInternalApi
fun buildVoiceDictationV2PermitRequestBody(
    language: String? = null,
    channelId: String? = null,
    draftBefore: String? = null,
    draftAfter: String? = null,
    contactHints: List<String>? = null,
): String =
    buildJsonObject {
        put("v", VOICE_DICTATION_PROTOCOL_VERSION)
        normalizeVoiceDictationLanguage(language)?.let { put("language", it) }
        normalizeVoiceDictationChannelId(channelId)?.let { put("channelId", it) }
        normalizeVoiceDictationDraftBefore(draftBefore)?.let { put("draftBefore", it) }
        normalizeVoiceDictationDraftAfter(draftAfter)?.let { put("draftAfter", it) }
        normalizeVoiceDictationContactHints(contactHints)?.let { hints ->
            put(
                "contactHints",
                buildJsonArray {
                    hints.forEach { hint -> add(JsonPrimitive(hint)) }
                },
            )
        }
    }.toString()

/**
 * Strictly parses a v2 permit response (`201` / `200`), failing closed (`null`) if `permit` is
 * blank or contains whitespace, `url` is not a valid `wss://` or `ws://` URL, `expiresAt` is not
 * valid ISO-8601, `maxDurationSeconds <= 0`, or `quota` is missing/invalid.
 */
@RaftCoreInternalApi
fun parseVoiceDictationV2Permit(bodyText: String): VoiceDictationV2Permit? {
    val root = runCatching { voiceDictationJson.parseToJsonElement(bodyText) }.getOrNull() as? JsonObject ?: return null
    val permit = root.strictString("permit")?.takeIf { it.isNotBlank() && it.none(Char::isWhitespace) } ?: return null
    val url = root.strictString("url")?.takeIf(::isValidVoiceDictationStreamUrl) ?: return null
    val expiresAtIso = root.strictString("expiresAt")?.takeIf { it.isNotBlank() } ?: return null
    val expiresAtEpochMs = runCatching { Instant.parse(expiresAtIso).toEpochMilliseconds() }.getOrNull() ?: return null
    val maxDurationSeconds = root.strictInt("maxDurationSeconds")?.takeIf { it > 0 } ?: return null

    val quotaObj = root["quota"] as? JsonObject ?: return null
    val usedSeconds = quotaObj.strictInt("usedSeconds")?.takeIf { it >= 0 } ?: return null
    val limitSeconds = quotaObj.strictInt("limitSeconds")?.takeIf { it > 0 } ?: return null
    val remainingSeconds = quotaObj.strictInt("remainingSeconds")?.takeIf { it >= 0 } ?: return null
    val resetsAtIso = quotaObj.strictString("resetsAt")?.takeIf { it.isNotBlank() } ?: return null
    val resetsAtEpochMs = runCatching { Instant.parse(resetsAtIso).toEpochMilliseconds() }.getOrNull()

    // Prioritize snake_case `quota_degraded` per #17149, while tolerating camelCase `quotaDegraded`.
    val quotaDegraded =
        root.strictBoolean("quota_degraded")
            ?: root.strictBoolean("quotaDegraded")
            ?: false

    // Optional feedback & trace-stitching metadata introduced in slock#17246 (absent on older servers).
    val sessionId =
        root.strictString("sessionId")
            ?.takeIf { it.isNotBlank() && it.length <= VOICE_FEEDBACK_SESSION_ID_MAX_CHARS }
    val traceId = raftValidTraceIdOrNull(root.strictString("traceId"))
    val streamUrlSource = VoiceStreamUrlSource.fromWireOrNull(root.strictString("streamUrlSource"))

    return VoiceDictationV2Permit(
        permit = permit,
        url = url,
        expiresAtIso = expiresAtIso,
        expiresAtEpochMs = expiresAtEpochMs,
        maxDurationSeconds = maxDurationSeconds,
        quota =
            VoiceDictationV2Quota(
                usedSeconds = usedSeconds,
                limitSeconds = limitSeconds,
                remainingSeconds = remainingSeconds,
                resetsAtIso = resetsAtIso,
                resetsAtEpochMs = resetsAtEpochMs,
            ),
        quotaDegraded = quotaDegraded,
        sessionId = sessionId,
        traceId = traceId,
        streamUrlSource = streamUrlSource,
    )
}

/** Parses a non-2xx response from `POST /api/voice/dictation/permits` into a typed failure. */
@RaftCoreInternalApi
fun parseVoiceDictationV2PermitRefusal(response: HomeNetworkResponse): VoiceDictationV2PermitFailure {
    val traceId = response.traceId
    val rawBody = response.errorBodyText?.takeIf { it.isNotBlank() } ?: response.bodyText
    val root =
        rawBody
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { voiceDictationJson.parseToJsonElement(it) }.getOrNull() as? JsonObject }

    val code =
        looseRefusalCode(
            code = root?.strictString("code"),
            errorCode = root?.strictString("errorCode"),
            error = root?.strictString("error"),
        )?.lowercase()
    val serverMessage = usableServerRefusalMessage(root?.strictString("error"))

    val bodyRetryAfter = root?.strictInt("retryAfterSeconds")?.takeIf { it > 0 }
    val headerRetryAfter =
        response.retryAfterHeaderSeconds()
            ?.takeIf { it > 0L }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()
    val retryAfterSeconds = bodyRetryAfter ?: headerRetryAfter

    val details = root?.get("details") as? JsonObject

    return when {
        code == "voice_dictation_disabled" ||
            (response.code == 404 && (code == null || code == "unsupported_capability")) ->
            VoiceDictationV2PermitFailure.Disabled(
                traceId = traceId,
                serverMessage = serverMessage,
            )

        code == "dictation_quota_exceeded" ->
            VoiceDictationV2PermitFailure.QuotaExceeded(
                usedSeconds =
                    (details?.strictInt("usedSeconds") ?: details?.strictInt("used_seconds"))
                        ?.takeIf { it >= 0 },
                limitSeconds =
                    (details?.strictInt("limitSeconds") ?: details?.strictInt("limit_seconds"))
                        ?.takeIf { it > 0 },
                resetsAtIso =
                    (details?.strictString("resetsAt") ?: details?.strictString("resets_at"))
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() },
                retryAfterSeconds = retryAfterSeconds,
                traceId = traceId,
                serverMessage = serverMessage,
            )

        code == "dictation_rate_limited" || response.code == 429 ->
            VoiceDictationV2PermitFailure.RateLimited(
                retryAfterSeconds = retryAfterSeconds,
                traceId = traceId,
                serverMessage = serverMessage,
            )

        code == "voice_dictation_unavailable" || response.code == 503 ->
            VoiceDictationV2PermitFailure.Unavailable(
                traceId = traceId,
                serverMessage = serverMessage,
            )

        code == "invalid_request" || response.code == 400 ->
            VoiceDictationV2PermitFailure.InvalidRequest(
                traceId = traceId,
                serverMessage = serverMessage,
            )

        else ->
            VoiceDictationV2PermitFailure.Failed(
                statusCode = response.code,
                code = code,
                traceId = traceId,
                serverMessage = serverMessage,
            )
    }
}

/** Network contract for minting thin-client v2 voice dictation permits. */
@RaftCoreInternalApi
interface VoiceDictationV2PermitApi {
    suspend fun requestPermit(
        serverId: String,
        language: String? = null,
        channelId: String? = null,
    ): VoiceDictationV2PermitOutcome

    /**
     * Initial-session permit request carrying optional normalized read-only [draftBefore] and
     * server-scoped [contactHints] (`#wg-call:#10`). Defaults to delegating to the 3-arg overload
     * so legacy fakes/implementations stay source-compatible without overload ambiguity.
     */
    suspend fun requestPermit(
        serverId: String,
        language: String?,
        channelId: String?,
        draftBefore: String?,
        contactHints: List<String>?,
    ): VoiceDictationV2PermitOutcome =
        requestPermit(
            serverId = serverId,
            language = language,
            channelId = channelId,
        )

    /**
     * Initial-session permit request carrying optional normalized read-only [draftBefore],
     * read-only [draftAfter] (`#wg-live:#21`), and server-scoped [contactHints] (`#wg-call:#10`).
     * Defaults to delegating to the 5-arg overload so legacy fakes/implementations stay
     * source-compatible without overload ambiguity.
     */
    suspend fun requestPermit(
        serverId: String,
        language: String?,
        channelId: String?,
        draftBefore: String?,
        draftAfter: String?,
        contactHints: List<String>?,
    ): VoiceDictationV2PermitOutcome =
        requestPermit(
            serverId = serverId,
            language = language,
            channelId = channelId,
            draftBefore = draftBefore,
            contactHints = contactHints,
        )
}

/**
 * Bounded, thread-safe short-TTL negative cache remembering server scopes whose strict permit
 * schema recently returned HTTP `400` (`invalid_request`) when caller-context fields
 * (`draftBefore`/`draftAfter`/`contactHints`) were sent, then succeeded on the stripped retry.
 *
 * Process-scoped by default ([Process]) because [RaftVoiceDictationV2PermitApi] is recreated per
 * composer screen; entries expire after [VOICE_DICTATION_CALLER_CONTEXT_FALLBACK_TTL_MS] (5 min)
 * so backend upgrades auto-heal without app restart.
 */
@RaftCoreInternalApi
class VoiceDictationCallerContextFallbackCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private data class ScopeKey(
        val environmentKey: String,
        val serverId: String,
    )

    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<ScopeKey, Long>()

    fun isActive(
        environmentKey: String,
        serverId: String,
        nowMs: Long,
    ): Boolean =
        synchronized(lock) {
            val key = ScopeKey(environmentKey = environmentKey, serverId = serverId)
            val expiresAt = entries[key] ?: return false
            if (nowMs >= expiresAt) {
                entries.remove(key)
                false
            } else {
                true
            }
        }

    fun recordFallback(
        environmentKey: String,
        serverId: String,
        nowMs: Long,
        ttlMs: Long,
    ) {
        synchronized(lock) {
            val key = ScopeKey(environmentKey = environmentKey, serverId = serverId)
            if (ttlMs <= 0L) {
                entries.remove(key)
                return
            }
            val expiredKeys = entries.entries.filter { it.value <= nowMs }.map { it.key }
            for (expiredKey in expiredKeys) {
                entries.remove(expiredKey)
            }
            entries.remove(key)
            val limit = maxEntries.coerceAtLeast(1)
            while (entries.size >= limit) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
            val expiresAt =
                if (nowMs > 0L && Long.MAX_VALUE - nowMs < ttlMs) {
                    Long.MAX_VALUE
                } else {
                    nowMs + ttlMs
                }
            entries[key] = expiresAt
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 32

        val Process: VoiceDictationCallerContextFallbackCache = VoiceDictationCallerContextFallbackCache()

        fun clear() {
            Process.clear()
        }
    }
}

/**
 * Shared `RaftNetworkClient` implementation for `POST /api/voice/dictation/permits` (`v: 2`).
 * Always passes `serverIdOverride = ServerId(serverId)` so `X-Server-Id` stays bound to the
 * dictation session's server even across concurrent server switches.
 *
 * Rollback compatibility (`#wg-call:#10`, `#wg-live:#21`): if an initial request carrying
 * `draftBefore`, `draftAfter`, and/or `contactHints` receives HTTP `400` (`invalid_request`) from
 * an older/rolled-back server whose strict permit schema does not yet accept those keys, silently
 * retries ONCE without them and records the `(environment, serverId)` scope in [fallbackCache] for
 * [fallbackTtlMs] (default 5 minutes) so subsequent dictation taps skip the wasted 400 RTT.
 */
@RaftCoreInternalApi
class RaftVoiceDictationV2PermitApi(
    private val networkClient: RaftNetworkClient,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val fallbackCache: VoiceDictationCallerContextFallbackCache = VoiceDictationCallerContextFallbackCache.Process,
    private val fallbackTtlMs: Long = VOICE_DICTATION_CALLER_CONTEXT_FALLBACK_TTL_MS,
) : VoiceDictationV2PermitApi {
    override suspend fun requestPermit(
        serverId: String,
        language: String?,
        channelId: String?,
    ): VoiceDictationV2PermitOutcome =
        requestPermit(
            serverId = serverId,
            language = language,
            channelId = channelId,
            draftBefore = null,
            draftAfter = null,
            contactHints = null,
        )

    override suspend fun requestPermit(
        serverId: String,
        language: String?,
        channelId: String?,
        draftBefore: String?,
        contactHints: List<String>?,
    ): VoiceDictationV2PermitOutcome =
        requestPermit(
            serverId = serverId,
            language = language,
            channelId = channelId,
            draftBefore = draftBefore,
            draftAfter = null,
            contactHints = contactHints,
        )

    override suspend fun requestPermit(
        serverId: String,
        language: String?,
        channelId: String?,
        draftBefore: String?,
        draftAfter: String?,
        contactHints: List<String>?,
    ): VoiceDictationV2PermitOutcome {
        val cleanServerId = serverId.trim()
        if (cleanServerId.isEmpty()) {
            return VoiceDictationV2PermitOutcome.Refused(
                VoiceDictationV2PermitFailure.InvalidRequest(),
            )
        }
        val normalizedDraftBefore = normalizeVoiceDictationDraftBefore(draftBefore)
        val normalizedDraftAfter = normalizeVoiceDictationDraftAfter(draftAfter)
        val normalizedContactHints = normalizeVoiceDictationContactHints(contactHints)
        val hasCallerContext =
            normalizedDraftBefore != null ||
                normalizedDraftAfter != null ||
                normalizedContactHints != null
        val environmentKey =
            networkClient.currentApiBaseUrl()?.normalizedEnvironmentId()?.lowercase().orEmpty()

        if (hasCallerContext && fallbackCache.isActive(environmentKey, cleanServerId, nowMillis())) {
            val compatOutcome =
                executePermitRequest(
                    serverId = cleanServerId,
                    bodyText =
                        buildVoiceDictationV2PermitRequestBody(
                            language = language,
                            channelId = channelId,
                        ),
                )
            return when (compatOutcome) {
                is VoiceDictationV2PermitOutcome.Issued ->
                    compatOutcome.copy(usedCallerContextFallback = true)
                is VoiceDictationV2PermitOutcome.Refused -> compatOutcome
            }
        }

        val initialOutcome =
            executePermitRequest(
                serverId = cleanServerId,
                bodyText =
                    buildVoiceDictationV2PermitRequestBody(
                        language = language,
                        channelId = channelId,
                        draftBefore = normalizedDraftBefore,
                        draftAfter = normalizedDraftAfter,
                        contactHints = normalizedContactHints,
                    ),
            )
        if (
            hasCallerContext &&
            initialOutcome is VoiceDictationV2PermitOutcome.Refused &&
            initialOutcome.failure is VoiceDictationV2PermitFailure.InvalidRequest
        ) {
            val fallbackOutcome =
                executePermitRequest(
                    serverId = cleanServerId,
                    bodyText =
                        buildVoiceDictationV2PermitRequestBody(
                            language = language,
                            channelId = channelId,
                        ),
                )
            if (fallbackOutcome is VoiceDictationV2PermitOutcome.Issued) {
                fallbackCache.recordFallback(
                    environmentKey = environmentKey,
                    serverId = cleanServerId,
                    nowMs = nowMillis(),
                    ttlMs = fallbackTtlMs,
                )
                return fallbackOutcome.copy(usedCallerContextFallback = true)
            }
            return fallbackOutcome
        }
        return initialOutcome
    }

    private suspend fun executePermitRequest(
        serverId: String,
        bodyText: String,
    ): VoiceDictationV2PermitOutcome =
        try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = VOICE_DICTATION_PERMITS_PATH,
                    method = "POST",
                    serverIdOverride = ServerId(serverId),
                    bodyText = bodyText,
                )
            if (!response.isSuccessful) {
                VoiceDictationV2PermitOutcome.Refused(parseVoiceDictationV2PermitRefusal(response))
            } else {
                val parsed = parseVoiceDictationV2Permit(response.bodyText.orEmpty())
                if (parsed != null) {
                    VoiceDictationV2PermitOutcome.Issued(permit = parsed, traceId = response.traceId)
                } else {
                    VoiceDictationV2PermitOutcome.Refused(
                        VoiceDictationV2PermitFailure.Failed(
                            statusCode = response.code,
                            code = "voice_dictation_contract_mismatch",
                            traceId = response.traceId,
                        ),
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            VoiceDictationV2PermitOutcome.Refused(
                VoiceDictationV2PermitFailure.Failed(code = "voice_dictation_network_error"),
            )
        }
}

private fun JsonObject.strictString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun JsonObject.strictInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

private fun JsonObject.strictBoolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
