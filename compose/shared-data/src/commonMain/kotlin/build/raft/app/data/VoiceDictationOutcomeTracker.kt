package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

// Pure edit-distance computation and post-dictation outcome tracker (`#wg-call:#7`, parity with
// `botiverse/slock/packages/web/src/features/composer/voiceDictationOutcome.ts`).
//
// Automatic implicit outcomes carry ONLY numbers, booleans, and closed enums (zero transcript text)
// and strictly obey `RAFT_PRIVACY_MODE` (`voiceFeedbackAllowed()`). Explicit bad-case sample
// targets stay in memory until an internal user opts in via UI (Part 2).

/** Bounded character prefix for O(min(m, n))-memory Levenshtein DP (matches web MAX_EDIT_DISTANCE_CHARS). */
@RaftCoreInternalApi
const val MAX_DICTATION_EDIT_DISTANCE_CHARS: Int = 2_000

/** Starting another dictation within this window without sending counts as a re-record (matches web RE_RECORD_WINDOW_MS). */
@RaftCoreInternalApi
const val VOICE_DICTATION_RE_RECORD_WINDOW_MS: Long = 10_000L

/** Result of comparing dictated text against the final submitted or cleared draft. */
@RaftCoreInternalApi
data class DictationEditMetrics(
    val dictatedChars: Int,
    val sentChars: Int,
    val editDistance: Int,
    val editRatio: Double,
    val sentAsIs: Boolean,
)

/**
 * Bounded O(min(m, n))-memory Levenshtein distance over Unicode code points.
 * Compares trimmed strings so trailing dictation spacing does not inflate edits.
 */
@RaftCoreInternalApi
fun computeDictationEditMetrics(
    dictatedText: String,
    finalText: String,
): DictationEditMetrics {
    val aCodePoints = stringToUnicodeCodePoints(dictatedText.trim())
    val bCodePoints = stringToUnicodeCodePoints(finalText.trim())
    val dictatedChars = aCodePoints.size
    val sentChars = bCodePoints.size

    if (dictatedChars == 0 && sentChars == 0) {
        return DictationEditMetrics(
            dictatedChars = 0,
            sentChars = 0,
            editDistance = 0,
            editRatio = 0.0,
            sentAsIs = true,
        )
    }
    if (dictatedChars == 0 || sentChars == 0) {
        val dist = max(dictatedChars, sentChars)
        return DictationEditMetrics(
            dictatedChars = dictatedChars,
            sentChars = sentChars,
            editDistance = dist,
            editRatio = 1.0,
            sentAsIs = false,
        )
    }

    // Fast exact-match check before allocating DP rows.
    if (dictatedChars == sentChars && aCodePoints.contentEquals(bCodePoints)) {
        return DictationEditMetrics(
            dictatedChars = dictatedChars,
            sentChars = sentChars,
            editDistance = 0,
            editRatio = 0.0,
            sentAsIs = true,
        )
    }

    // Bound DP work for unusually long drafts while preserving exact tail length diff.
    val aLen = min(dictatedChars, MAX_DICTATION_EDIT_DISTANCE_CHARS)
    val bLen = min(sentChars, MAX_DICTATION_EDIT_DISTANCE_CHARS)
    val shortArr: IntArray
    val shortLen: Int
    val longArr: IntArray
    val longLen: Int
    if (aLen <= bLen) {
        shortArr = aCodePoints
        shortLen = aLen
        longArr = bCodePoints
        longLen = bLen
    } else {
        shortArr = bCodePoints
        shortLen = bLen
        longArr = aCodePoints
        longLen = aLen
    }

    var prev = IntArray(shortLen + 1) { it }
    var curr = IntArray(shortLen + 1)

    for (i in 1..longLen) {
        curr[0] = i
        val longCp = longArr[i - 1]
        for (j in 1..shortLen) {
            val cost = if (longCp == shortArr[j - 1]) 0 else 1
            val del = prev[j] + 1
            val ins = curr[j - 1] + 1
            val sub = prev[j - 1] + cost
            curr[j] = if (del < ins) (if (del < sub) del else sub) else (if (ins < sub) ins else sub)
        }
        val tmp = prev
        prev = curr
        curr = tmp
    }

    val tailDiff =
        abs(
            max(0, dictatedChars - MAX_DICTATION_EDIT_DISTANCE_CHARS) -
                max(0, sentChars - MAX_DICTATION_EDIT_DISTANCE_CHARS),
        )
    val editDistance = prev[shortLen] + tailDiff
    val denom = max(max(dictatedChars, sentChars), 1)
    val editRatio = min(1.0, round((editDistance.toDouble() / denom.toDouble()) * 10_000.0) / 10_000.0)
    return DictationEditMetrics(
        dictatedChars = dictatedChars,
        sentChars = sentChars,
        editDistance = editDistance,
        editRatio = editRatio,
        sentAsIs = editDistance == 0,
    )
}

private fun stringToUnicodeCodePoints(text: String): IntArray {
    if (text.isEmpty()) return IntArray(0)
    val out = IntArray(text.length)
    var count = 0
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            val high = ch.code - 0xD800
            val low = text[i + 1].code - 0xDC00
            out[count++] = 0x10000 + (high shl 10) + low
            i += 2
        } else {
            out[count++] = ch.code
            i += 1
        }
    }
    return if (count == out.size) out else out.copyOf(count)
}

/** Numeric-only implicit dictation outcome report ready to forward to `POST /api/voice/dictation/feedback`. */
@RaftCoreInternalApi
data class VoiceDictationOutcomeReport(
    val serverId: String,
    val sessionId: String,
    val clientMetrics: VoiceDictationClientMetrics,
) {
    fun toFeedbackRequest(): VoiceDictationFeedbackRequest =
        VoiceDictationFeedbackRequest(
            sessionId = sessionId,
            clientMetrics = clientMetrics,
        )
}

/**
 * In-memory snapshot of the most recent completed dictation for internal-only opt-in bad-case
 * reporting (`voice_feedback_sample_v0`, `#wg-call:#7`). Never sent unless the user explicitly
 * submits negative feedback with consent checked.
 */
@RaftCoreInternalApi
data class VoiceDictationFeedbackTarget(
    val serverId: String,
    val sessionId: String,
    val raw: String,
    val refined: String,
    val sent: String? = null,
)

/** Per-session v2 transcript snapshots accumulated for [VoiceDictationOutcomeTracker.capture]. */
@RaftCoreInternalApi
data class DictationTranscriptCapture(
    val v2LastRawText: String = "",
    val v2LastRefinedText: String? = null,
) {
    fun recordSnapshot(text: String, refined: Boolean): DictationTranscriptCapture =
        when {
            refined -> copy(v2LastRefinedText = text)
            text.trim().isNotEmpty() -> copy(v2LastRawText = text)
            else -> this
        }
}

/** Input captured when a dictation session completes or is ended for submit. */
@RaftCoreInternalApi
data class VoiceDictationOutcomeCaptureInput(
    val serverId: String,
    val sessionId: String?,
    val composerText: String,
    val transcript: DictationTranscriptCapture,
    val startedAtMs: Long,
    val connectedAtMs: Long? = null,
    val firstPartialAtMs: Long? = null,
    val sentBeforeRefineDone: Boolean = false,
)

/**
 * Tracks post-dictation implicit outcomes (`sentAsIs`, `editRatio`, `abandoned`, `reRecorded`,
 * `sentBeforeRefineDone`, `clientConnectMs`, `clientFirstPartialMs`) and internal feedback target
 * snapshots. Strictly obeys `RAFT_PRIVACY_MODE` (emits zero reports while privacy mode is
 * [RaftPrivacyMode.On] or [RaftPrivacyMode.Unknown]).
 */
@RaftCoreInternalApi
class VoiceDictationOutcomeTracker(
    private val nowMillis: () -> Long = ::raftNowEpochMillis,
    private val clientPlatformProvider: () -> VoiceFeedbackClientPlatform = ::currentVoiceFeedbackClientPlatform,
    private val privacyModeProvider: () -> RaftPrivacyMode = { RaftTelemetryPrivacy.state.value },
    private val onOutcomeReport: (VoiceDictationOutcomeReport) -> Unit = {},
) {
    private data class PendingOutcome(
        val serverId: String,
        val sessionId: String,
        val endedAtMs: Long,
        val dictatedText: String,
        val rawText: String,
        val refinedText: String,
        val clientConnectMs: Int?,
        val clientFirstPartialMs: Int?,
        val sentBeforeRefineDone: Boolean,
    )

    private val lock = SynchronizedObject()
    private var pending: PendingOutcome? = null

    fun hasPendingOutcome(): Boolean = synchronized(lock) { pending != null }

    /**
     * Called right when a new dictation session starts: any unsent pending outcome from the
     * previous dictation is flushed as abandoned (`reRecorded = true` if within
     * [VOICE_DICTATION_RE_RECORD_WINDOW_MS]).
     */
    fun onStartNewSession(nowMs: Long = nowMillis()) {
        val current = synchronized(lock) { pending } ?: return
        val elapsedMs = (nowMs - current.endedAtMs).coerceAtLeast(0L)
        flushAbandoned(reRecorded = elapsedMs <= VOICE_DICTATION_RE_RECORD_WINDOW_MS)
    }

    /**
     * Captures a completed dictation session's text & timing for subsequent submit/clear/re-record
     * tracking, returning an in-memory [VoiceDictationFeedbackTarget] (or `null` if `sessionId` is
     * missing, no text was produced, or [VoiceDictationOutcomeCaptureInput.sentBeforeRefineDone] is
     * `true`).
     */
    fun capture(input: VoiceDictationOutcomeCaptureInput): VoiceDictationFeedbackTarget? {
        val cleanSessionId = input.sessionId?.trim()?.takeIf { it.isNotEmpty() && it.length <= VOICE_FEEDBACK_SESSION_ID_MAX_CHARS }
            ?: return null
        val cleanServerId = input.serverId.trim().takeIf { it.isNotEmpty() } ?: return null

        val rawText = input.transcript.v2LastRawText.ifEmpty { input.composerText }
        val refinedText = input.transcript.v2LastRefinedText ?: rawText
        val dictatedText = if (input.composerText.trim().isNotEmpty()) input.composerText else refinedText
        if (dictatedText.trim().isEmpty()) return null

        val connectMs =
            input.connectedAtMs
                ?.let { (it - input.startedAtMs).coerceIn(0L, VOICE_FEEDBACK_MAX_CLIENT_LATENCY_MS.toLong()).toInt() }
        val firstPartialMs =
            input.firstPartialAtMs
                ?.let { (it - input.startedAtMs).coerceIn(0L, VOICE_FEEDBACK_MAX_CLIENT_LATENCY_MS.toLong()).toInt() }
        val trimmedRaw = rawText.trim()
        val trimmedRefined = refinedText.trim()

        synchronized(lock) {
            pending =
                PendingOutcome(
                    serverId = cleanServerId,
                    sessionId = cleanSessionId,
                    endedAtMs = nowMillis(),
                    dictatedText = dictatedText,
                    rawText = trimmedRaw,
                    refinedText = trimmedRefined,
                    clientConnectMs = connectMs,
                    clientFirstPartialMs = firstPartialMs,
                    sentBeforeRefineDone = input.sentBeforeRefineDone,
                )
        }
        if (input.sentBeforeRefineDone) return null
        return VoiceDictationFeedbackTarget(
            serverId = cleanServerId,
            sessionId = cleanSessionId,
            raw = trimmedRaw.take(VOICE_FEEDBACK_SAMPLE_TEXT_MAX_CHARS),
            refined = trimmedRefined.take(VOICE_FEEDBACK_SAMPLE_TEXT_MAX_CHARS),
        )
    }

    /**
     * Records that the user submitted [sentText], computing edit metrics and emitting a numeric-only
     * outcome report when [voiceFeedbackAllowed] permits it. Returns an updated
     * [VoiceDictationFeedbackTarget] containing the submitted text (bounded to 2,000 chars).
     */
    fun reportSubmitted(sentText: String): VoiceDictationFeedbackTarget? {
        val current =
            synchronized(lock) {
                val p = pending ?: return null
                pending = null
                p
            }
        val metrics = computeDictationEditMetrics(current.dictatedText, sentText)
        emitIfAllowed(
            VoiceDictationOutcomeReport(
                serverId = current.serverId,
                sessionId = current.sessionId,
                clientMetrics =
                    VoiceDictationClientMetrics(
                        dictatedChars = metrics.dictatedChars,
                        sentChars = metrics.sentChars,
                        editDistance = metrics.editDistance,
                        editRatio = metrics.editRatio,
                        sentAsIs = metrics.sentAsIs,
                        abandoned = false,
                        reRecorded = false,
                        sentBeforeRefineDone = current.sentBeforeRefineDone,
                        clientConnectMs = current.clientConnectMs,
                        clientFirstPartialMs = current.clientFirstPartialMs,
                        clientPlatform = clientPlatformProvider(),
                    ),
            ),
        )
        return VoiceDictationFeedbackTarget(
            serverId = current.serverId,
            sessionId = current.sessionId,
            raw = current.rawText.take(VOICE_FEEDBACK_SAMPLE_TEXT_MAX_CHARS),
            refined = current.refinedText.take(VOICE_FEEDBACK_SAMPLE_TEXT_MAX_CHARS),
            sent = sentText.trim().take(VOICE_FEEDBACK_SAMPLE_TEXT_MAX_CHARS),
        )
    }

    /** Call when the user edits the composer after dictation: clearing it to blank flushes abandoned. */
    fun onComposerTextChanged(composerText: String) {
        if (composerText.trim().isEmpty() && hasPendingOutcome()) {
            flushAbandoned(reRecorded = false)
        }
    }

    /** Flushes any pending outcome as abandoned (`sentChars = 0, editRatio = 1.0, abandoned = true`). */
    fun flushAbandoned(reRecorded: Boolean) {
        val current =
            synchronized(lock) {
                val p = pending ?: return
                pending = null
                p
            }
        val metrics = computeDictationEditMetrics(current.dictatedText, "")
        emitIfAllowed(
            VoiceDictationOutcomeReport(
                serverId = current.serverId,
                sessionId = current.sessionId,
                clientMetrics =
                    VoiceDictationClientMetrics(
                        dictatedChars = metrics.dictatedChars,
                        sentChars = 0,
                        editDistance = metrics.editDistance,
                        editRatio = 1.0,
                        sentAsIs = false,
                        abandoned = true,
                        reRecorded = reRecorded,
                        sentBeforeRefineDone = current.sentBeforeRefineDone,
                        clientConnectMs = current.clientConnectMs,
                        clientFirstPartialMs = current.clientFirstPartialMs,
                        clientPlatform = clientPlatformProvider(),
                    ),
            ),
        )
    }

    /** Clears any pending outcome without emitting a report (e.g. explicit cancel or owner change). */
    fun clear() {
        synchronized(lock) { pending = null }
    }

    private fun emitIfAllowed(report: VoiceDictationOutcomeReport) {
        if (!voiceFeedbackAllowed(privacyModeProvider())) return
        runCatching { onOutcomeReport(report) }
    }
}
