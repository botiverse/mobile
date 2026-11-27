package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

// Voice quality telemetry & user feedback proxy API (`#wg-call:#7`, parity with `botiverse/slock#17246`).
// Authoritative contracts:
// - `botiverse/slock/packages/shared/src/voiceFeedback.ts`
// - `botiverse/slock/packages/server/src/routes/voiceDictation.ts` (`POST /api/voice/dictation/feedback`)
// - `botiverse/slock/packages/server/src/routes/agents.ts` (`POST /api/agents/:id/voice/calls/:callId/feedback`)
//
// Security & privacy invariants:
// 1. Mobile ALWAYS calls Raft's proxy routes (NEVER `holt-live` directly).
// 2. Clients NEVER supply `traceId` or `streamUrlSource` in feedback payloads (server-authoritative).
// 3. Fail-closed under `RAFT_PRIVACY_MODE`: submission is blocked locally whenever
//    `RaftTelemetryPrivacy.state.value != RaftPrivacyMode.Off` (both `Unknown` and `On`).
// 4. `sample` is only serialized when `rating == VoiceFeedbackRating.Bad` and `sample.consent == true`.
//    `note` and `sample` text are never logged.

/** Relative API path for composer voice dictation feedback (`POST /api/voice/dictation/feedback`). */
@RaftCoreInternalApi
const val VOICE_DICTATION_FEEDBACK_PATH: String = "voice/dictation/feedback"

/** Max characters for optional user feedback note (`VOICE_FEEDBACK_NOTE_MAX_CHARS`). */
@RaftCoreInternalApi
const val VOICE_FEEDBACK_NOTE_MAX_CHARS: Int = 300

/** Max characters per sample field (`VOICE_FEEDBACK_SAMPLE_TEXT_MAX_CHARS`). */
@RaftCoreInternalApi
const val VOICE_FEEDBACK_SAMPLE_TEXT_MAX_CHARS: Int = 2_000

/** Max character length of server-minted `sessionId` (`permit.jti`). */
@RaftCoreInternalApi
const val VOICE_FEEDBACK_SESSION_ID_MAX_CHARS: Int = 128

/** Max integer value accepted for `dictatedChars`, `sentChars`, `editDistance`. */
@RaftCoreInternalApi
const val VOICE_FEEDBACK_MAX_CLIENT_CHARS: Int = 100_000

/** Max integer value accepted for `clientConnectMs` and `clientFirstPartialMs`. */
@RaftCoreInternalApi
const val VOICE_FEEDBACK_MAX_CLIENT_LATENCY_MS: Int = 600_000

private val voiceFeedbackJson = Json { ignoreUnknownKeys = true }

/** Closed rating enum matching `VOICE_FEEDBACK_RATINGS` in `@botiverse/raft-shared`. */
@RaftCoreInternalApi
enum class VoiceFeedbackRating(val wire: String) {
    Good("good"),
    Bad("bad"),
}

/** Closed tag enum matching `VOICE_FEEDBACK_TAGS` in `@botiverse/raft-shared`. */
@RaftCoreInternalApi
enum class VoiceFeedbackTag(val wire: String) {
    WrongWord("wrong_word"),
    BadRefine("bad_refine"),
    TooSlow("too_slow"),
    WrongAnswer("wrong_answer"),
    CutOff("cut_off"),
    Interrupted("interrupted"),
    Other("other"),
}

/** Max tags accepted per feedback request (`VOICE_FEEDBACK_MAX_TAGS`). */
@RaftCoreInternalApi
val VOICE_FEEDBACK_MAX_TAGS: Int = VoiceFeedbackTag.entries.size

/** Closed client platform enum matching `VOICE_FEEDBACK_CLIENT_PLATFORMS` in `@botiverse/raft-shared`. */
@RaftCoreInternalApi
enum class VoiceFeedbackClientPlatform(val wire: String) {
    Web("web"),
    Desktop("desktop"),
    Mobile("mobile"),
}

/** Closed stream route source enum matching `VOICE_STREAM_URL_SOURCES` in `@botiverse/raft-shared`. */
@RaftCoreInternalApi
enum class VoiceStreamUrlSource(val wire: String) {
    Direct("direct"),
    RaftEdge("raft_edge"),
    ;

    companion object {
        fun fromWireOrNull(raw: String?): VoiceStreamUrlSource? =
            when (raw?.trim()) {
                Direct.wire -> Direct
                RaftEdge.wire -> RaftEdge
                else -> null
            }
    }
}

/** Resolves the closed client platform value for this KMP host (`"desktop"` on Desktop Compose, `"mobile"` on Android/iOS). */
@RaftCoreInternalApi
fun currentVoiceFeedbackClientPlatform(
    os: String = runCatching(::featureFlagClientOs).getOrDefault("mobile"),
): VoiceFeedbackClientPlatform =
    if (os.trim().equals("desktop", ignoreCase = true)) {
        VoiceFeedbackClientPlatform.Desktop
    } else {
        VoiceFeedbackClientPlatform.Mobile
    }

/**
 * Fail-closed `RAFT_PRIVACY_MODE` check for voice feedback and implicit dictation outcome metrics
 * (`slock#17246`, matching `agentIssueReportAllowed` / web `privacyAllowsSend()`): allowed ONLY
 * when client config has explicitly answered [RaftPrivacyMode.Off] (blocked while `Unknown` or `On`).
 */
@RaftCoreInternalApi
fun voiceFeedbackAllowed(mode: RaftPrivacyMode = RaftTelemetryPrivacy.state.value): Boolean =
    mode == RaftPrivacyMode.Off

/**
 * Numeric-only, zero-text client outcome metrics for composer voice dictation
 * (`voiceDictationClientMetricsSchema` in `@botiverse/raft-shared`).
 */
@RaftCoreInternalApi
data class VoiceDictationClientMetrics(
    val dictatedChars: Int? = null,
    val sentChars: Int? = null,
    val editDistance: Int? = null,
    val editRatio: Double? = null,
    val sentAsIs: Boolean? = null,
    val abandoned: Boolean? = null,
    val reRecorded: Boolean? = null,
    val sentBeforeRefineDone: Boolean? = null,
    val clientConnectMs: Int? = null,
    val clientFirstPartialMs: Int? = null,
    val clientPlatform: VoiceFeedbackClientPlatform? = null,
)

/**
 * Explicit opt-in bad-case dictation sample (`voiceFeedbackSampleSchema`, internal servers only).
 * [consent] must be `true` for the sample to be serialized or accepted by the server.
 */
@RaftCoreInternalApi
data class VoiceFeedbackSample(
    val consent: Boolean = true,
    val raw: String,
    val refined: String,
    val sent: String? = null,
)

/** Request payload for `POST /api/voice/dictation/feedback`. */
@RaftCoreInternalApi
data class VoiceDictationFeedbackRequest(
    val sessionId: String,
    val rating: VoiceFeedbackRating? = null,
    val tags: List<VoiceFeedbackTag> = emptyList(),
    val note: String? = null,
    val clientMetrics: VoiceDictationClientMetrics? = null,
    val sample: VoiceFeedbackSample? = null,
)

/** Request payload for `POST /api/agents/:id/voice/calls/:callId/feedback`. */
@RaftCoreInternalApi
data class AgentVoiceCallFeedbackRequest(
    val rating: VoiceFeedbackRating,
    val tags: List<VoiceFeedbackTag> = emptyList(),
    val note: String? = null,
)

/** Typed refusal/failure outcomes for Raft voice feedback proxy routes. */
@RaftCoreInternalApi
sealed interface VoiceFeedbackSubmitFailure {
    val traceId: String?
    val serverMessage: String?

    /** Blocked locally because `RAFT_PRIVACY_MODE` is [RaftPrivacyMode.On] or [RaftPrivacyMode.Unknown]. */
    data class PrivacyModeBlocked(
        val mode: RaftPrivacyMode,
    ) : VoiceFeedbackSubmitFailure {
        override val traceId: String? = null
        override val serverMessage: String? = null
    }

    /** `503 voice_feedback_unavailable` (including server-side `RAFT_PRIVACY_MODE` or unconfigured holt-live). */
    data class Unavailable(
        override val traceId: String? = null,
        override val serverMessage: String? = null,
    ) : VoiceFeedbackSubmitFailure

    /** `429 voice_feedback_rate_limited` (burst or per-session cap exceeded). */
    data class RateLimited(
        val retryAfterSeconds: Int? = null,
        override val traceId: String? = null,
        override val serverMessage: String? = null,
    ) : VoiceFeedbackSubmitFailure

    /** `404` (`voice_dictation_disabled`, `voice_session_not_found`, or `agent_not_found`). */
    data class NotFound(
        val code: String? = null,
        override val traceId: String? = null,
        override val serverMessage: String? = null,
    ) : VoiceFeedbackSubmitFailure

    /** `400 invalid_request` or local contract validation failure before sending. */
    data class InvalidRequest(
        override val traceId: String? = null,
        override val serverMessage: String? = null,
    ) : VoiceFeedbackSubmitFailure

    /** Unexpected HTTP status or transport failure. */
    data class Failed(
        val statusCode: Int? = null,
        val code: String? = null,
        override val traceId: String? = null,
        override val serverMessage: String? = null,
    ) : VoiceFeedbackSubmitFailure
}

/** Result of submitting dictation or agent voice call feedback via Raft proxy. */
@RaftCoreInternalApi
sealed interface VoiceFeedbackSubmitOutcome {
    data object Submitted : VoiceFeedbackSubmitOutcome

    data class Refused(val failure: VoiceFeedbackSubmitFailure) : VoiceFeedbackSubmitOutcome
}

/**
 * Encodes `POST /api/voice/dictation/feedback` matching `voiceDictationFeedbackRequestSchema`
 * (`z.strictObject`). Returns `null` when required invariants fail (blank/oversized `sessionId` or
 * neither `rating` nor `clientMetrics` supplied).
 *
 * Omits absent optional fields (never emitting `null` for non-nullable Zod fields), omits `sample`
 * unless `rating == Bad && sample.consent == true`, and never emits `traceId` or `streamUrlSource`.
 */
@RaftCoreInternalApi
fun buildVoiceDictationFeedbackRequestBody(request: VoiceDictationFeedbackRequest): String? {
    val cleanSessionId = request.sessionId.trim()
    if (cleanSessionId.isEmpty() || cleanSessionId.length > VOICE_FEEDBACK_SESSION_ID_MAX_CHARS) return null
    if (request.rating == null && request.clientMetrics == null) return null

    val dedupedTags = request.tags.distinct().take(VOICE_FEEDBACK_MAX_TAGS)
    val cleanNote = request.note?.trim()?.takeIf { it.isNotEmpty() }?.take(VOICE_FEEDBACK_NOTE_MAX_CHARS)
    val includeSample = request.sample != null && request.sample.consent && request.rating == VoiceFeedbackRating.Bad

    return buildJsonObject {
        put("sessionId", cleanSessionId)
        request.rating?.let { put("rating", it.wire) }
        if (dedupedTags.isNotEmpty()) {
            put(
                "tags",
                buildJsonArray {
                    dedupedTags.forEach { tag -> add(JsonPrimitive(tag.wire)) }
                },
            )
        }
        cleanNote?.let { put("note", it) }
        request.clientMetrics?.let { metrics ->
            put(
                "clientMetrics",
                buildJsonObject {
                    metrics.dictatedChars?.let { put("dictatedChars", it.coerceIn(0, VOICE_FEEDBACK_MAX_CLIENT_CHARS)) }
                    metrics.sentChars?.let { put("sentChars", it.coerceIn(0, VOICE_FEEDBACK_MAX_CLIENT_CHARS)) }
                    metrics.editDistance?.let { put("editDistance", it.coerceIn(0, VOICE_FEEDBACK_MAX_CLIENT_CHARS)) }
                    metrics.editRatio?.takeIf(Double::isFinite)?.let { put("editRatio", it.coerceIn(0.0, 1.0)) }
                    metrics.sentAsIs?.let { put("sentAsIs", it) }
                    metrics.abandoned?.let { put("abandoned", it) }
                    metrics.reRecorded?.let { put("reRecorded", it) }
                    metrics.sentBeforeRefineDone?.let { put("sentBeforeRefineDone", it) }
                    metrics.clientConnectMs?.let {
                        put("clientConnectMs", it.coerceIn(0, VOICE_FEEDBACK_MAX_CLIENT_LATENCY_MS))
                    }
                    metrics.clientFirstPartialMs?.let {
                        put("clientFirstPartialMs", it.coerceIn(0, VOICE_FEEDBACK_MAX_CLIENT_LATENCY_MS))
                    }
                    metrics.clientPlatform?.let { put("clientPlatform", it.wire) }
                },
            )
        }
        if (includeSample) {
            val sample = request.sample!!
            put(
                "sample",
                buildJsonObject {
                    put("consent", true)
                    put("raw", sample.raw.take(VOICE_FEEDBACK_SAMPLE_TEXT_MAX_CHARS))
                    put("refined", sample.refined.take(VOICE_FEEDBACK_SAMPLE_TEXT_MAX_CHARS))
                    sample.sent?.let { put("sent", it.take(VOICE_FEEDBACK_SAMPLE_TEXT_MAX_CHARS)) }
                },
            )
        }
    }.toString()
}

/**
 * Encodes `POST /api/agents/:id/voice/calls/:callId/feedback` matching
 * `agentVoiceCallFeedbackRequestSchema` (`z.strictObject`).
 */
@RaftCoreInternalApi
fun buildAgentVoiceCallFeedbackRequestBody(request: AgentVoiceCallFeedbackRequest): String {
    val dedupedTags = request.tags.distinct().take(VOICE_FEEDBACK_MAX_TAGS)
    val cleanNote = request.note?.trim()?.takeIf { it.isNotEmpty() }?.take(VOICE_FEEDBACK_NOTE_MAX_CHARS)
    return buildJsonObject {
        put("rating", request.rating.wire)
        if (dedupedTags.isNotEmpty()) {
            put(
                "tags",
                buildJsonArray {
                    dedupedTags.forEach { tag -> add(JsonPrimitive(tag.wire)) }
                },
            )
        }
        cleanNote?.let { put("note", it) }
    }.toString()
}

/** Parses a non-2xx response from either Raft voice feedback proxy route into a typed failure. */
@RaftCoreInternalApi
fun parseVoiceFeedbackRefusal(response: HomeNetworkResponse): VoiceFeedbackSubmitFailure {
    val traceId = response.traceId
    val rawBody = response.errorBodyText?.takeIf { it.isNotBlank() } ?: response.bodyText
    val root =
        rawBody
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { voiceFeedbackJson.parseToJsonElement(it) }.getOrNull() as? JsonObject }

    val code =
        looseRefusalCode(
            code = root?.strictFeedbackString("code"),
            errorCode = root?.strictFeedbackString("errorCode"),
            error = root?.strictFeedbackString("error"),
        )?.lowercase()
    val serverMessage = usableServerRefusalMessage(root?.strictFeedbackString("error"))

    val bodyRetryAfter = root?.strictFeedbackInt("retryAfterSeconds")?.takeIf { it > 0 }
    val headerRetryAfter =
        response.retryAfterHeaderSeconds()
            ?.takeIf { it > 0L }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()
    val retryAfterSeconds = bodyRetryAfter ?: headerRetryAfter

    return when {
        code == "voice_feedback_unavailable" || response.code == 503 ->
            VoiceFeedbackSubmitFailure.Unavailable(
                traceId = traceId,
                serverMessage = serverMessage,
            )

        code == "voice_feedback_rate_limited" || response.code == 429 ->
            VoiceFeedbackSubmitFailure.RateLimited(
                retryAfterSeconds = retryAfterSeconds,
                traceId = traceId,
                serverMessage = serverMessage,
            )

        code == "voice_dictation_disabled" ||
            code == "voice_session_not_found" ||
            code == "agent_not_found" ||
            response.code == 404 ->
            VoiceFeedbackSubmitFailure.NotFound(
                code = code,
                traceId = traceId,
                serverMessage = serverMessage,
            )

        code == "invalid_request" || response.code == 400 ->
            VoiceFeedbackSubmitFailure.InvalidRequest(
                traceId = traceId,
                serverMessage = serverMessage,
            )

        else ->
            VoiceFeedbackSubmitFailure.Failed(
                statusCode = response.code,
                code = code,
                traceId = traceId,
                serverMessage = serverMessage,
            )
    }
}

/** Network contract for Raft's voice feedback proxy routes (dictation and agent voice calls). */
@RaftCoreInternalApi
interface VoiceFeedbackApi {
    suspend fun submitDictationFeedback(
        serverId: String,
        request: VoiceDictationFeedbackRequest,
    ): VoiceFeedbackSubmitOutcome

    suspend fun submitAgentVoiceCallFeedback(
        serverId: String,
        agentId: String,
        callId: String,
        request: AgentVoiceCallFeedbackRequest,
    ): VoiceFeedbackSubmitOutcome
}

/**
 * Shared `RaftNetworkClient` implementation for Raft's voice feedback proxy routes:
 * - `POST /api/voice/dictation/feedback`
 * - `POST /api/agents/:id/voice/calls/:callId/feedback`
 *
 * Enforces fail-closed `RAFT_PRIVACY_MODE` locally before touching the network, and always pins
 * `serverIdOverride = ServerId(serverId)` so `X-Server-Id` matches the originating session/call.
 */
@RaftCoreInternalApi
class RaftVoiceFeedbackNetworkApi(
    private val networkClient: RaftNetworkClient,
    private val privacyModeProvider: () -> RaftPrivacyMode = { RaftTelemetryPrivacy.state.value },
) : VoiceFeedbackApi {
    override suspend fun submitDictationFeedback(
        serverId: String,
        request: VoiceDictationFeedbackRequest,
    ): VoiceFeedbackSubmitOutcome {
        val privacyMode = privacyModeProvider()
        if (!voiceFeedbackAllowed(privacyMode)) {
            return VoiceFeedbackSubmitOutcome.Refused(
                VoiceFeedbackSubmitFailure.PrivacyModeBlocked(privacyMode),
            )
        }
        val cleanServerId = serverId.trim()
        if (cleanServerId.isEmpty()) {
            return VoiceFeedbackSubmitOutcome.Refused(VoiceFeedbackSubmitFailure.InvalidRequest())
        }
        val bodyText =
            buildVoiceDictationFeedbackRequestBody(request)
                ?: return VoiceFeedbackSubmitOutcome.Refused(VoiceFeedbackSubmitFailure.InvalidRequest())

        val priority =
            if (request.rating != null) {
                DataRequestPriority.Foreground
            } else {
                DataRequestPriority.Background
            }

        return postFeedback(
            priority = priority,
            path = VOICE_DICTATION_FEEDBACK_PATH,
            serverId = cleanServerId,
            bodyText = bodyText,
        )
    }

    override suspend fun submitAgentVoiceCallFeedback(
        serverId: String,
        agentId: String,
        callId: String,
        request: AgentVoiceCallFeedbackRequest,
    ): VoiceFeedbackSubmitOutcome {
        val privacyMode = privacyModeProvider()
        if (!voiceFeedbackAllowed(privacyMode)) {
            return VoiceFeedbackSubmitOutcome.Refused(
                VoiceFeedbackSubmitFailure.PrivacyModeBlocked(privacyMode),
            )
        }
        val cleanServerId = serverId.trim()
        val cleanAgentId = agentId.trim()
        val cleanCallId = callId.trim()
        if (cleanServerId.isEmpty() || cleanAgentId.isEmpty() || cleanCallId.isEmpty()) {
            return VoiceFeedbackSubmitOutcome.Refused(VoiceFeedbackSubmitFailure.InvalidRequest())
        }
        val bodyText = buildAgentVoiceCallFeedbackRequestBody(request)
        val path = "agents/${cleanAgentId.percentEncode()}/voice/calls/${cleanCallId.percentEncode()}/feedback"
        return postFeedback(
            priority = DataRequestPriority.Foreground,
            path = path,
            serverId = cleanServerId,
            bodyText = bodyText,
        )
    }

    private suspend fun postFeedback(
        priority: DataRequestPriority,
        path: String,
        serverId: String,
        bodyText: String,
    ): VoiceFeedbackSubmitOutcome =
        try {
            val response =
                networkClient.request(
                    priority = priority,
                    path = path,
                    method = "POST",
                    serverIdOverride = ServerId(serverId),
                    bodyText = bodyText,
                )
            if (response.isSuccessful) {
                VoiceFeedbackSubmitOutcome.Submitted
            } else {
                VoiceFeedbackSubmitOutcome.Refused(parseVoiceFeedbackRefusal(response))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            VoiceFeedbackSubmitOutcome.Refused(
                VoiceFeedbackSubmitFailure.Failed(code = "voice_feedback_network_error"),
            )
        }
}

private fun JsonObject.strictFeedbackString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun JsonObject.strictFeedbackInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
