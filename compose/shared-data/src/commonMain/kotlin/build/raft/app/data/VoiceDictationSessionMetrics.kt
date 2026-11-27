package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Pure per-session pause, timing, and pre-ready audio buffer telemetry accumulator for composer
// streaming voice dictation v2 (`#wg-mobile-sync:#357`, parity with `botiverse/slock` #17140,
// #17157, and #17246: `voiceDictationSessionMetrics.ts` + `voiceDictationTrace.ts`).
//
// Emits one `slock.composer.voice_dictation_session` client trace event per ended dictation session
// containing ONLY closed-set enums and non-negative whole numbers — NEVER any transcript text,
// draft text, contact hints, channel/server/user IDs, or audio payload.

/** Canonical trace family name registered in `botiverse/slock` `TRACE_FAMILY_REGISTRY`. */
@RaftCoreInternalApi
const val VoiceDictationSessionEventName: String = "slock.composer.voice_dictation_session"

/** Closed gesture mode for a voice dictation session (`mode` attribute). */
@RaftCoreInternalApi
enum class VoiceDictationSessionMode(val wire: String) {
    Tap("tap"),
    Hold("hold"),
}

/** Closed stop reason for a voice dictation session (`stopped_by` attribute). */
@RaftCoreInternalApi
enum class VoiceDictationStopReason(val wire: String) {
    Button("button"),
    Esc("esc"),
    Blur("blur"),
    Error("error"),
    Server("server"),
    Submit("submit"),
    Reset("reset"),
}

/**
 * Closed error code set matching `VOICE_DICTATION_ERROR_CODES` in web `voiceDictationTransport.ts`.
 * Emitted on `error_code` strictly when `stopped_by == "error"`.
 */
@RaftCoreInternalApi
enum class VoiceDictationSessionErrorCode(val wire: String) {
    Disabled("disabled"),
    InvalidRequest("invalid_request"),
    QuotaExceeded("quota_exceeded"),
    SessionLimitReached("session_limit_reached"),
    Busy("busy"),
    RateLimited("rate_limited"),
    Unavailable("unavailable"),
    MicPermission("mic_permission"),
    MicBusy("mic_busy"),
    MicNone("mic_none"),
    MicUnsupported("mic_unsupported"),
    MicEnded("mic_ended"),
    ConnectionLost("connection_lost"),
    Failed("failed"),
}

/** Closed set of `client_scheme` values mirroring web `directPutClientScheme`. */
@RaftCoreInternalApi
val VoiceDictationAllowedClientSchemes: Set<String> = setOf("app", "https", "http", "other")

/** Classifies a typed [VoiceDictationV2Error] into the closed [VoiceDictationSessionErrorCode] set. */
@RaftCoreInternalApi
fun voiceDictationSessionErrorCode(error: VoiceDictationV2Error): VoiceDictationSessionErrorCode =
    when (error) {
        VoiceDictationV2Error.PermissionDenied -> VoiceDictationSessionErrorCode.MicPermission
        VoiceDictationV2Error.DeviceUnavailable -> VoiceDictationSessionErrorCode.MicNone
        is VoiceDictationV2Error.AudioCaptureFailed -> VoiceDictationSessionErrorCode.Failed
        is VoiceDictationV2Error.PermitRefused ->
            when (val failure = error.failure) {
                is VoiceDictationV2PermitFailure.Disabled -> VoiceDictationSessionErrorCode.Disabled
                is VoiceDictationV2PermitFailure.InvalidRequest -> VoiceDictationSessionErrorCode.InvalidRequest
                is VoiceDictationV2PermitFailure.QuotaExceeded -> VoiceDictationSessionErrorCode.QuotaExceeded
                is VoiceDictationV2PermitFailure.RateLimited -> VoiceDictationSessionErrorCode.RateLimited
                is VoiceDictationV2PermitFailure.Unavailable -> VoiceDictationSessionErrorCode.Unavailable
                is VoiceDictationV2PermitFailure.Failed ->
                    if (failure.code == "voice_dictation_network_error" || failure.statusCode == 0) {
                        VoiceDictationSessionErrorCode.ConnectionLost
                    } else {
                        VoiceDictationSessionErrorCode.Failed
                    }
            }
        is VoiceDictationV2Error.StreamError ->
            when (val code = error.code) {
                VoiceDictationV2ErrorCode.SessionLimitReached -> VoiceDictationSessionErrorCode.SessionLimitReached
                VoiceDictationV2ErrorCode.QuotaExceeded -> VoiceDictationSessionErrorCode.QuotaExceeded
                VoiceDictationV2ErrorCode.Busy -> VoiceDictationSessionErrorCode.Busy
                VoiceDictationV2ErrorCode.Unavailable -> VoiceDictationSessionErrorCode.Unavailable
                VoiceDictationV2ErrorCode.InvalidPermit,
                VoiceDictationV2ErrorCode.ProtocolError,
                -> VoiceDictationSessionErrorCode.ConnectionLost
                is VoiceDictationV2ErrorCode.Unknown -> {
                    val lower = code.raw.lowercase()
                    when {
                        "quota" in lower -> VoiceDictationSessionErrorCode.QuotaExceeded
                        "rate" in lower -> VoiceDictationSessionErrorCode.RateLimited
                        "unavailable" in lower || "upstream" in lower -> VoiceDictationSessionErrorCode.Unavailable
                        else -> VoiceDictationSessionErrorCode.ConnectionLost
                    }
                }
            }
        is VoiceDictationV2Error.ConnectionLost -> VoiceDictationSessionErrorCode.ConnectionLost
    }

/** Immutable zero-text summary emitted once per ended composer voice dictation session. */
@RaftCoreInternalApi
data class VoiceDictationSessionSummary(
    val mode: VoiceDictationSessionMode,
    val stoppedBy: VoiceDictationStopReason,
    val durationMs: Long,
    val utterances: Int,
    val finalsCount: Int,
    val stopAfterFinalMs: Long?,
    val stopAfterLastPartialMs: Long?,
    val maxMidSentenceGapMs: Long?,
    val maxGapBetweenFinalsMs: Long?,
    val preReadyChunksQueued: Int,
    val preReadyChunksSent: Int,
    val micStartMs: Long?,
    val connectMs: Long?,
    val firstPartialMs: Long?,
    val finishingMs: Long?,
    val streamUrlSource: VoiceStreamUrlSource?,
    val errorCode: VoiceDictationSessionErrorCode?,
)

/**
 * Pure per-session accumulator mirroring `voiceDictationSessionMetrics.ts`.
 * Retains zero transcript text — incoming snapshot slices are inspected strictly for whitespace
 * emptiness and discarded immediately.
 */
@RaftCoreInternalApi
class VoiceDictationSessionMetrics(
    var mode: VoiceDictationSessionMode,
    val startedAtMs: Long,
) {
    var micReadyAtMs: Long? = null
    var connectedAtMs: Long? = null
    var firstPartialAtMs: Long? = null
    var streamUrlSource: VoiceStreamUrlSource? = null
    var stopRequestedAtMs: Long? = null
        private set
    var stoppedBy: VoiceDictationStopReason? = null
        private set
    var durationMs: Long? = null
        private set
    var stopAfterFinalMs: Long? = null
        private set
    var stopAfterLastPartialMs: Long? = null
        private set
    var utterances: Int = 0
        private set
    var finalsCount: Int = 0
        private set
    var utteranceActive: Boolean = false
        private set
    var lastUtterancePartialAtMs: Long? = null
        private set
    var lastFinalAtMs: Long? = null
        private set
    var lastSpeechAtMs: Long? = null
        private set
    var maxMidSentenceGapMs: Long? = null
        private set
    var maxGapBetweenFinalsMs: Long? = null
        private set
    var preReadyChunksQueued: Int = 0
    var preReadyChunksSent: Int = 0
    var lastSnapshotStableLen: Int = 0
        private set
    var emitted: Boolean = false
        private set

    fun recordPartial(
        text: String,
        timestampMs: Long,
        isActiveSession: Boolean,
    ) {
        val nonEmpty = text.trim().isNotEmpty()
        if (nonEmpty && firstPartialAtMs == null) {
            firstPartialAtMs = timestampMs
        }
        if (!isActiveSession || !nonEmpty) return
        if (!utteranceActive) {
            lastFinalAtMs?.let { lastFinal ->
                maxGapBetweenFinalsMs = maxNullable(maxGapBetweenFinalsMs, timestampMs - lastFinal)
            }
            utteranceActive = true
        } else {
            lastUtterancePartialAtMs?.let { lastPartial ->
                maxMidSentenceGapMs = maxNullable(maxMidSentenceGapMs, timestampMs - lastPartial)
            }
        }
        lastUtterancePartialAtMs = timestampMs
        lastSpeechAtMs = timestampMs
    }

    fun recordFinal(
        text: String,
        timestampMs: Long,
        isActiveSession: Boolean,
    ) {
        finalsCount += 1
        val nonEmpty = text.trim().isNotEmpty()
        if (nonEmpty) {
            utterances += 1
            if (firstPartialAtMs == null) {
                firstPartialAtMs = timestampMs
            }
        }
        if (!isActiveSession) return
        if (nonEmpty) {
            if (!utteranceActive) {
                lastFinalAtMs?.let { lastFinal ->
                    maxGapBetweenFinalsMs = maxNullable(maxGapBetweenFinalsMs, timestampMs - lastFinal)
                }
            }
            lastSpeechAtMs = timestampMs
        }
        utteranceActive = false
        lastUtterancePartialAtMs = null
        lastFinalAtMs = timestampMs
    }

    /** Records zero-text pause/utterance telemetry from a v2 `{ "deck": "text" }` snapshot. */
    fun recordSnapshot(
        text: String,
        stableLen: Int,
        refined: Boolean,
        timestampMs: Long,
        isActiveSession: Boolean,
    ) {
        if (refined) return
        val clampedStable = stableLen.coerceIn(0, text.length)
        val hasPartialTail = clampedStable < text.length
        if (!hasPartialTail && text.trim().isNotEmpty() && clampedStable > lastSnapshotStableLen) {
            lastSnapshotStableLen = clampedStable
            recordFinal(text = text, timestampMs = timestampMs, isActiveSession = isActiveSession)
            return
        }
        if (hasPartialTail) {
            if (clampedStable > lastSnapshotStableLen) {
                lastSnapshotStableLen = clampedStable
                recordFinal(
                    text = text.substring(0, clampedStable),
                    timestampMs = timestampMs,
                    isActiveSession = isActiveSession,
                )
            }
            recordPartial(
                text = text.substring(clampedStable),
                timestampMs = timestampMs,
                isActiveSession = isActiveSession,
            )
        }
    }

    /** Freezes duration and post-speech stop delays at the first stop request (idempotent). */
    fun freezeStop(
        stoppedBy: VoiceDictationStopReason,
        stopAtMs: Long,
    ) {
        if (stopRequestedAtMs != null) return
        stopRequestedAtMs = stopAtMs
        this.stoppedBy = stoppedBy
        durationMs = (stopAtMs - startedAtMs).coerceAtLeast(0L)
        stopAfterFinalMs = lastFinalAtMs?.let { (stopAtMs - it).coerceAtLeast(0L) }
        stopAfterLastPartialMs = lastSpeechAtMs?.let { (stopAtMs - it).coerceAtLeast(0L) }
    }

    /**
     * Builds the immutable session summary at most once per session; subsequent calls return
     * `null` so a session can never emit duplicate telemetry across concurrent teardown paths.
     */
    fun buildSummary(
        stoppedBy: VoiceDictationStopReason,
        finishingMs: Long?,
        errorCode: VoiceDictationSessionErrorCode?,
        nowMs: Long,
    ): VoiceDictationSessionSummary? {
        if (emitted) return null
        emitted = true
        val endAt = stopRequestedAtMs ?: nowMs
        val resolvedDurationMs = durationMs ?: (endAt - startedAtMs).coerceAtLeast(0L)
        return VoiceDictationSessionSummary(
            mode = mode,
            stoppedBy = stoppedBy,
            durationMs = resolvedDurationMs.coerceAtLeast(0L),
            utterances = utterances.coerceAtLeast(0),
            finalsCount = finalsCount.coerceAtLeast(0),
            stopAfterFinalMs = stopAfterFinalMs?.coerceAtLeast(0L),
            stopAfterLastPartialMs = stopAfterLastPartialMs?.coerceAtLeast(0L),
            maxMidSentenceGapMs = maxMidSentenceGapMs?.coerceAtLeast(0L),
            maxGapBetweenFinalsMs = maxGapBetweenFinalsMs?.coerceAtLeast(0L),
            preReadyChunksQueued = preReadyChunksQueued.coerceAtLeast(0),
            preReadyChunksSent = preReadyChunksSent.coerceAtLeast(0),
            micStartMs = micReadyAtMs?.let { (it - startedAtMs).coerceAtLeast(0L) },
            connectMs = connectedAtMs?.let { (it - startedAtMs).coerceAtLeast(0L) },
            firstPartialMs = firstPartialAtMs?.let { (it - startedAtMs).coerceAtLeast(0L) },
            finishingMs = finishingMs?.coerceAtLeast(0L),
            streamUrlSource = streamUrlSource,
            errorCode = errorCode,
        )
    }

    private fun maxNullable(current: Long?, candidate: Long): Long {
        val nonNeg = candidate.coerceAtLeast(0L)
        return if (current == null) nonNeg else maxOf(current, nonNeg)
    }
}

/**
 * Serializes [summary] into closed-set / whole-number snake_case attributes for
 * `slock.composer.voice_dictation_session`.
 *
 * Follows mobile `RaftClientTraceEvents` conventions (`RaftAttachmentUploadTelemetry.kt`,
 * `ActivityUnreadFlipTrace.kt`):
 * - Absent nullable values are omitted (never written as `0` or `"null"`).
 * - `error_code` is emitted strictly when `stopped_by == "error"` (defaulting to `"failed"` if null).
 * - `client_scheme` is omitted by default on mobile (`null`), where `RaftClientTraceEventSender`
 *   identifies clients via `clientPlatform` (`android` / `ios` / `desktop`) and `appVersion`.
 */
@RaftCoreInternalApi
fun voiceDictationSessionTraceAttrs(
    summary: VoiceDictationSessionSummary,
    clientScheme: String? = null,
): JsonObject =
    buildJsonObject {
        put("mode", summary.mode.wire)
        put("stopped_by", summary.stoppedBy.wire)
        put("duration_ms", summary.durationMs.coerceAtLeast(0L))
        put("utterances", summary.utterances.coerceAtLeast(0))
        put("finals_count", summary.finalsCount.coerceAtLeast(0))
        summary.stopAfterFinalMs?.let { put("stop_after_final_ms", it.coerceAtLeast(0L)) }
        summary.stopAfterLastPartialMs?.let { put("stop_after_last_partial_ms", it.coerceAtLeast(0L)) }
        summary.maxMidSentenceGapMs?.let { put("max_midsentence_gap_ms", it.coerceAtLeast(0L)) }
        summary.maxGapBetweenFinalsMs?.let { put("max_gap_between_finals_ms", it.coerceAtLeast(0L)) }
        put("pre_ready_chunks_queued", summary.preReadyChunksQueued.coerceAtLeast(0))
        put("pre_ready_chunks_sent", summary.preReadyChunksSent.coerceAtLeast(0))
        summary.micStartMs?.let { put("mic_start_ms", it.coerceAtLeast(0L)) }
        summary.connectMs?.let { put("connect_ms", it.coerceAtLeast(0L)) }
        summary.firstPartialMs?.let { put("first_partial_ms", it.coerceAtLeast(0L)) }
        summary.finishingMs?.let { put("finishing_ms", it.coerceAtLeast(0L)) }
        summary.streamUrlSource?.let { put("stream_url_source", it.wire) }
        if (summary.stoppedBy == VoiceDictationStopReason.Error) {
            put("error_code", (summary.errorCode ?: VoiceDictationSessionErrorCode.Failed).wire)
        }
        clientScheme
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?.let { normalized ->
                put("client_scheme", if (normalized in VoiceDictationAllowedClientSchemes) normalized else "other")
            }
    }
