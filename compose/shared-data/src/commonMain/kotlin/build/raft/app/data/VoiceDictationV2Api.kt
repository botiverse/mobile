package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ServerId
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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

/** Result of requesting a v2 dictation permit. */
@RaftCoreInternalApi
sealed interface VoiceDictationV2PermitOutcome {
    data class Issued(
        val permit: VoiceDictationV2Permit,
        val traceId: String? = null,
    ) : VoiceDictationV2PermitOutcome

    data class Refused(
        val failure: VoiceDictationV2PermitFailure,
    ) : VoiceDictationV2PermitOutcome
}

/**
 * Encodes the strict JSON body for `POST /api/voice/dictation/permits`:
 * `{ "v": 2, "language"?: <iso639-1>, "channelId"?: <uuid> }`.
 */
@RaftCoreInternalApi
fun buildVoiceDictationV2PermitRequestBody(
    language: String? = null,
    channelId: String? = null,
): String =
    buildJsonObject {
        put("v", VOICE_DICTATION_PROTOCOL_VERSION)
        normalizeVoiceDictationLanguage(language)?.let { put("language", it) }
        normalizeVoiceDictationChannelId(channelId)?.let { put("channelId", it) }
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
}

/**
 * Shared `RaftNetworkClient` implementation for `POST /api/voice/dictation/permits` (`v: 2`).
 * Always passes `serverIdOverride = ServerId(serverId)` so `X-Server-Id` stays bound to the
 * dictation session's server even across concurrent server switches.
 */
@RaftCoreInternalApi
class RaftVoiceDictationV2PermitApi(
    private val networkClient: RaftNetworkClient,
) : VoiceDictationV2PermitApi {
    override suspend fun requestPermit(
        serverId: String,
        language: String?,
        channelId: String?,
    ): VoiceDictationV2PermitOutcome {
        val cleanServerId = serverId.trim()
        if (cleanServerId.isEmpty()) {
            return VoiceDictationV2PermitOutcome.Refused(
                VoiceDictationV2PermitFailure.InvalidRequest(),
            )
        }
        return try {
            val response =
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = VOICE_DICTATION_PERMITS_PATH,
                    method = "POST",
                    serverIdOverride = ServerId(cleanServerId),
                    bodyText = buildVoiceDictationV2PermitRequestBody(language = language, channelId = channelId),
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
}

private fun JsonObject.strictString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun JsonObject.strictInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

private fun JsonObject.strictBoolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
