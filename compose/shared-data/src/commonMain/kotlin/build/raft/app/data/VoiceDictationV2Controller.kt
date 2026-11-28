package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.raftLogInfo
import build.raft.realtime.socket.RaftWebSocketSession
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Composer streaming voice dictation — thin-client protocol v2 controller, audio recorder seam,
// and composer span helper (`#wg-mobile-sync:#334`).
// Authoritative contract: `botiverse/slock/docs/contracts/voice-dictation-v2.md` (#17146 + #17149),
// web parity: `packages/web/src/features/composer/voiceDictationController.ts`.

/** Default bounded wait after sending `stop` (slightly above holt-live's 10 s v2 stop timeout). */
@RaftCoreInternalApi
const val VOICE_DICTATION_DEFAULT_STOP_DRAIN_TIMEOUT_MS: Long = 10_500L

/** Default post-roll capture window after `stop()` before stopping the mic (captures release-tail / Bluetooth latency). */
@RaftCoreInternalApi
const val VOICE_DICTATION_DEFAULT_STOP_POST_ROLL_MS: Long = 300L

/** Strict fail-closed pre-ready PCM chunk cap (100 * 100 ms = 10 s; mirrors web MAX_PRE_READY_CHUNKS). */
@RaftCoreInternalApi
const val VOICE_DICTATION_MAX_PRE_READY_CHUNKS: Int = 100

/** Max time composer Send waits for a post-stop final snapshot before sending current text (1 s). */
@RaftCoreInternalApi
const val VOICE_DICTATION_DEFAULT_SUBMIT_FINAL_WAIT_MS: Long = 1_000L

/** Explicit short ASCII RFC 6455 close reasons sent with `NORMAL_CLOSURE` (1000) across the dictation lifecycle. */
@RaftCoreInternalApi
const val VOICE_DICTATION_CLOSE_REASON_DONE: String = "done"

@RaftCoreInternalApi
const val VOICE_DICTATION_CLOSE_REASON_USER_CANCEL: String = "user_cancel"

@RaftCoreInternalApi
const val VOICE_DICTATION_CLOSE_REASON_OWNER_CHANGED: String = "owner_changed"

@RaftCoreInternalApi
const val VOICE_DICTATION_CLOSE_REASON_DRAIN_TIMEOUT: String = "drain_timeout"

@RaftCoreInternalApi
const val VOICE_DICTATION_CLOSE_REASON_PRE_READY_OVERFLOW: String = "pre_ready_overflow"

@RaftCoreInternalApi
const val VOICE_DICTATION_CLOSE_REASON_SUBMIT: String = "submit"

@RaftCoreInternalApi
const val VOICE_DICTATION_CLOSE_REASON_SUPERSEDED: String = "superseded"

@RaftCoreInternalApi
const val VOICE_DICTATION_CLOSE_REASON_ERROR: String = "error"

/** Outcome of starting native microphone PCM16 capture. */
@RaftCoreInternalApi
sealed interface VoiceDictationAudioStartResult {
    data object Started : VoiceDictationAudioStartResult

    data object PermissionDenied : VoiceDictationAudioStartResult

    data object DeviceUnavailable : VoiceDictationAudioStartResult

    data class Failed(val reason: String? = null) : VoiceDictationAudioStartResult
}

/** Platform audio capture seam (Android `AudioRecord`, iOS `AVAudioEngine`, Desktop `TargetDataLine`).
 * Implementations must deliver PCM16 little-endian mono frames at [sampleRateHz] and make [stop]
 * idempotent.
 */
@RaftCoreInternalApi
interface RaftVoiceDictationAudioRecorder {
    val sampleRateHz: Int
        get() = VOICE_DICTATION_DEFAULT_SAMPLE_RATE_HZ

    /**
     * Returns `true` when OS microphone capture permission is already granted so the controller can
     * overlap `POST /api/voice/dictation/permits` with native audio hardware startup without
     * risking a permission prompt expiring the permit or hitting `/permits` on denied permission.
     */
    fun hasRecordAudioPermission(): Boolean = false

    suspend fun start(
        onPcmChunk: (ByteArray) -> Unit,
        onAudioLevel: (Float) -> Unit = {},
    ): VoiceDictationAudioStartResult

    suspend fun stop()
}

/** Process-wide registry for the platform [RaftVoiceDictationAudioRecorder] implementation. */
@RaftCoreInternalApi
object RaftVoiceDictationAudioRecorderRegistry {
    @Volatile
    private var current: RaftVoiceDictationAudioRecorder? = null

    fun register(recorder: RaftVoiceDictationAudioRecorder) {
        current = recorder
    }

    fun unregister() {
        current = null
    }

    fun currentOrNull(): RaftVoiceDictationAudioRecorder? = current

    fun isSupported(): Boolean = current != null
}

/**
 * Returns `true` when joining [left] and [right] requires a single ASCII space so two ASCII/Latin
 * tokens do not fuse (`#17145` parity with `voiceDictationTextHelpers.ts`):
 * - ASCII word char `[A-Za-z0-9]` followed by ASCII word char `[A-Za-z0-9]`, OR
 * - ASCII sentence punctuation `[.!?,;:]` followed by ASCII letter `[A-Za-z]` (keeps `3.14` intact).
 * Never inserts a space at CJK boundaries or when whitespace is already present.
 */
@RaftCoreInternalApi
fun voiceDictationNeedsBoundarySpace(left: String, right: String): Boolean {
    if (left.isEmpty() || right.isEmpty()) return false
    val leftChar = left.last()
    val rightChar = right.first()
    return (leftChar.isAsciiAlphaNumeric() && rightChar.isAsciiAlphaNumeric()) ||
        (leftChar.isAsciiSentencePunctuation() && rightChar.isAsciiLetter())
}

private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

private fun Char.isAsciiAlphaNumeric(): Boolean = isAsciiLetter() || this in '0'..'9'

private fun Char.isAsciiSentencePunctuation(): Boolean =
    this == '.' || this == '!' || this == '?' || this == ',' || this == ';' || this == ':'

/**
 * Half-open UTF-16 offset range `[start, endExclusive)` inside a composer draft identifying
 * uncommitted streaming voice dictation text (`0 <= start < endExclusive`).
 * Mirrors web `DictationRange { start, end }` (`voiceDictationTextHelpers.ts`).
 */
@RaftCoreInternalApi
data class VoiceDictationTextRange(
    val start: Int,
    val endExclusive: Int,
) {
    val length: Int
        get() = (endExclusive - start).coerceAtLeast(0)

    val isEmpty: Boolean
        get() = endExclusive <= start
}

/**
 * Clamps [range] safely into [text] and trims leading/trailing whitespace within the slice so only
 * non-whitespace uncommitted words receive the composing dotted underline (excluding joining spaces
 * inserted before or after the spoken phrase). Mirrors web `splitProvisionalText`.
 */
@RaftCoreInternalApi
fun trimVoiceDictationProvisionalRange(
    text: CharSequence,
    range: VoiceDictationTextRange?,
): VoiceDictationTextRange? {
    if (range == null || text.isEmpty()) return null
    val rawStart = range.start.coerceIn(0, text.length)
    val rawEnd = range.endExclusive.coerceIn(rawStart, text.length)
    if (rawEnd <= rawStart) return null
    var start = rawStart
    var end = rawEnd
    while (start < end && text[start].isWhitespace()) start += 1
    while (end > start && text[end - 1].isWhitespace()) end -= 1
    return if (end > start) VoiceDictationTextRange(start = start, endExclusive = end) else null
}

/** Result of applying one v2 `text` snapshot onto [VoiceDictationComposerSpan]. */
@RaftCoreInternalApi
data class VoiceDictationComposerUpdate(
    /** Complete next composer text with the dictated span replaced. */
    val composerText: String,
    /** Start UTF-16 offset of the inserted span (including any boundary join spaces). */
    val spanStart: Int,
    /** End UTF-16 offset of the inserted span (including any boundary join spaces). */
    val spanEnd: Int,
    /** Start UTF-16 offset of `frame.text` (after any left-boundary space). */
    val textStart: Int,
    /** UTF-16 offset marking the end of the confirmed (`stable_len`) prefix in [composerText]. */
    val stableEnd: Int,
    /** End UTF-16 offset of `frame.text` (before any right-boundary space). */
    val textEnd: Int,
    /** Selection start / collapsed caret offset recommended after applying this update. */
    val caretOffset: Int,
    /** Clamped `stable_len` relative to `frame.text` (`0..frame.text.length`). */
    val stableLen: Int,
    /** Whether `holt-live` marked this snapshot `refined: true`. */
    val refined: Boolean,
    /** Selection end offset (equals [caretOffset] unless restoring a non-collapsed initial selection). */
    val selectionEndOffset: Int = caretOffset,
    /**
     * Uncommitted provisional range `[stableEnd, textEnd)` inside [composerText] while listening
     * or while a stopped session is refining in the background, or `null` once confirmed
     * (`stableLen >= text.length`).
     */
    val provisionalRange: VoiceDictationTextRange? = null,
)

/**
 * Pure-Kotlin composer span helper for thin-client v2 dictation.
 *
 * - Captures `(baseText, insertStart, insertEnd)` when dictation begins and normalizes left/right
 *   boundary spacing cleanly (never inserting spaces at CJK boundaries).
 * - While active (and while draining in the background if untouched), replaces the dictated span
 *   with each whole-session `text` snapshot from `holt-live`, tracking `stableLen`, `refined`, and
 *   [lastProvisionalRange] (`[stableEnd, textEnd)` while listening and unconfirmed).
 * - Minimal client safety rule (`docs/contracts/voice-dictation-v2.md` §Client rendering rules):
 *   once the user edits the composer ([onUserEditedComposer]), latches [ignoreSubsequentText] to
 *   `true`, clears [lastProvisionalRange], and ignores ALL subsequent `text` frames so user edits
 *   are never overwritten.
 */
@RaftCoreInternalApi
class VoiceDictationComposerSpan(
    baseText: String,
    insertStart: Int = baseText.length,
    insertEnd: Int = insertStart,
) {
    val start: Int = insertStart.coerceIn(0, baseText.length)
    val end: Int = insertEnd.coerceIn(start, baseText.length)

    private val initialBaseText: String = baseText
    private val prefix: String = baseText.substring(0, start)
    private val suffix: String = baseText.substring(end)

    var hasAppliedText: Boolean = false
        private set

    var stopping: Boolean = false
        private set

    var ignoreSubsequentText: Boolean = false
        private set

    var lastText: String = ""
        private set

    var lastStableLen: Int = 0
        private set

    var lastRefined: Boolean = false
        private set

    var lastComposerText: String = baseText
        private set

    var lastProvisionalRange: VoiceDictationTextRange? = null
        private set

    /**
     * Transitions the span into post-stop drain mode (still applies frames and retains any
     * unfinalized [lastProvisionalRange] until a settled snapshot arrives, drain ends, or the
     * user edits the composer).
     */
    fun markStopping() {
        stopping = true
        if (ignoreSubsequentText) {
            lastProvisionalRange = null
        }
    }

    /**
     * Records a user edit in the composer. When [currentComposerText] is supplied and equals
     * [lastComposerText], ignores the programmatic write echo; otherwise latches
     * [ignoreSubsequentText] = `true` and clears [lastProvisionalRange] so no subsequent `text`
     * snapshot can overwrite user edits.
     */
    fun onUserEditedComposer(currentComposerText: String? = null) {
        if (currentComposerText != null && currentComposerText == lastComposerText) {
            return
        }
        ignoreSubsequentText = true
        lastProvisionalRange = null
    }

    /** Convenience overload applying a decoded [VoiceDictationV2ServerFrame.Text]. */
    fun applyFrame(frame: VoiceDictationV2ServerFrame.Text): VoiceDictationComposerUpdate? =
        applyText(text = frame.text, stableLen = frame.stableLen, refined = frame.refined)

    /**
     * Applies a whole-session transcript snapshot to the recorded span. Returns `null` when
     * [ignoreSubsequentText] is latched or when an empty snapshot arrives before any text has been
     * applied (preserving an initial non-collapsed selection).
     */
    fun applyText(
        text: String,
        stableLen: Int = text.length,
        refined: Boolean = false,
    ): VoiceDictationComposerUpdate? {
        if (ignoreSubsequentText) return null
        if (!hasAppliedText && text.isEmpty()) return null

        val clampedStableLen = stableLen.coerceIn(0, text.length)
        val leadingSpace = if (voiceDictationNeedsBoundarySpace(prefix, text)) " " else ""
        val trailingSpace = if (voiceDictationNeedsBoundarySpace(text, suffix)) " " else ""
        val inserted = "$leadingSpace$text$trailingSpace"
        val nextComposerText = "$prefix$inserted$suffix"

        val spanStart = prefix.length
        val textStart = spanStart + leadingSpace.length
        val stableEnd = textStart + clampedStableLen
        val textEnd = textStart + text.length
        val spanEnd = spanStart + inserted.length
        val provisionalRange =
            if (stableEnd < textEnd) {
                VoiceDictationTextRange(start = stableEnd, endExclusive = textEnd)
            } else {
                null
            }

        hasAppliedText = true
        lastText = text
        lastStableLen = clampedStableLen
        lastRefined = refined
        lastComposerText = nextComposerText
        lastProvisionalRange = provisionalRange

        return VoiceDictationComposerUpdate(
            composerText = nextComposerText,
            spanStart = spanStart,
            spanEnd = spanEnd,
            textStart = textStart,
            stableEnd = stableEnd,
            textEnd = textEnd,
            caretOffset = textEnd,
            stableLen = clampedStableLen,
            refined = refined,
            selectionEndOffset = textEnd,
            provisionalRange = provisionalRange,
        )
    }

    /**
     * Discards any uncommitted live partial tail (used on explicit Escape cancel, matching web
     * `finishV2SnapshotState(..., keepProvisional = false)`):
     * - If nothing has been confirmed yet (`lastStableLen == 0`), restores the initial base draft
     *   and original selection `(start, end)`.
     * - If earlier utterances were confirmed (`lastStableLen > 0`), keeps the confirmed prefix and
     *   removes only the uncommitted tail.
     * Returns `null` when no uncommitted partial is active or the user already edited the composer.
     */
    fun discardProvisional(): VoiceDictationComposerUpdate? {
        if (ignoreSubsequentText || !hasAppliedText || lastProvisionalRange == null) return null
        val stableLen = lastStableLen.coerceIn(0, lastText.length)
        if (stableLen <= 0) {
            hasAppliedText = false
            lastText = ""
            lastStableLen = 0
            lastRefined = false
            lastComposerText = initialBaseText
            lastProvisionalRange = null
            return VoiceDictationComposerUpdate(
                composerText = initialBaseText,
                spanStart = start,
                spanEnd = end,
                textStart = start,
                stableEnd = start,
                textEnd = start,
                caretOffset = start,
                stableLen = 0,
                refined = false,
                selectionEndOffset = end,
                provisionalRange = null,
            )
        }
        val confirmedPrefix = lastText.substring(0, stableLen)
        return applyText(text = confirmedPrefix, stableLen = confirmedPrefix.length, refined = false)
    }
}

/** Foreground lifecycle phase of [VoiceDictationV2Controller]. */
@RaftCoreInternalApi
enum class VoiceDictationV2Phase {
    /** Idle (not capturing mic; a just-stopped session may still be draining in background). */
    Idle,

    /** Mic acquisition in flight (active yellow circle + static resting halo, zero blocking overlay). */
    Connecting,

    /** Mic capture is live; audio-reactive halo breathes while permit/WS connect & stream run. */
    Listening,
}

/** Conversation ownership key used to abort dictation cleanly on server/channel/thread switch. */
@RaftCoreInternalApi
data class VoiceDictationV2Owner(
    val serverId: String,
    val channelId: String? = null,
    val threadId: String? = null,
)

/** Typed errors surfaced in [VoiceDictationV2State.error]. */
@RaftCoreInternalApi
sealed interface VoiceDictationV2Error {
    data object PermissionDenied : VoiceDictationV2Error

    data object DeviceUnavailable : VoiceDictationV2Error

    data class AudioCaptureFailed(val reason: String? = null) : VoiceDictationV2Error

    data class PermitRefused(val failure: VoiceDictationV2PermitFailure) : VoiceDictationV2Error

    data class StreamError(
        val code: VoiceDictationV2ErrorCode,
        val message: String? = null,
    ) : VoiceDictationV2Error

    data class ConnectionLost(val reason: String? = null) : VoiceDictationV2Error
}

/** Immutable UI-observable snapshot emitted by [VoiceDictationV2Controller.state]. */
@RaftCoreInternalApi
data class VoiceDictationV2State(
    val phase: VoiceDictationV2Phase = VoiceDictationV2Phase.Idle,
    val owner: VoiceDictationV2Owner? = null,
    /** Normalized microphone input level in `0f..1f`. */
    val audioLevel: Float = 0f,
    /** Latest daily quota summary returned by `POST /api/voice/dictation/permits`. */
    val quota: VoiceDictationV2Quota? = null,
    /** True when `holt-live` failed open under the 180 s cap (`quota_degraded: true`). */
    val quotaDegraded: Boolean = false,
    /** Maximum duration in seconds granted for the active/last session. */
    val maxDurationSeconds: Int? = null,
    /** Active failure (cleared on the next [VoiceDictationV2Controller.start] or [VoiceDictationV2Controller.clearError]). */
    val error: VoiceDictationV2Error? = null,
    /** Most recent composer span update applied during the current/last session. */
    val lastUpdate: VoiceDictationComposerUpdate? = null,
    /** True while a stopped session is draining post-stop final/refined snapshots in the background. */
    val hasBackgroundDrain: Boolean = false,
    /** True while composer send is briefly waiting (<= 1 s) for a post-stop final snapshot. */
    val awaitingSubmitFinal: Boolean = false,
    /** True when daily dictation quota was exhausted in this owner (persists across [VoiceDictationV2Controller.clearError]). */
    val quotaExhausted: Boolean = false,
    /** ISO reset timestamp for the persistent quota-exhausted state (persists across [VoiceDictationV2Controller.clearError]). */
    val quotaExhaustedResetsAtIso: String? = null,
    /** Epoch millis parsed from [quotaExhaustedResetsAtIso] when available. */
    val quotaExhaustedResetsAtEpochMs: Long? = null,
    /**
     * Uncommitted provisional UTF-16 range `[start, endExclusive)` in the active composer draft
     * while listening or while a stopped session is refining in the background, or `null` once
     * confirmed (`stableLen >= text.length`), background drain ends, cancelled, errored, submitted,
     * or detached by a manual user edit. Mirrors web `VoiceDictationState.provisionalRange`.
     */
    val provisionalRange: VoiceDictationTextRange? = null,
    /**
     * Most recent completed dictation snapshot eligible for internal-only opt-in bad-case reporting
     * (`voice_feedback_sample_v0`, `#wg-call:#7`). Mirrors web `VoiceDictationState.recentFeedbackTarget`.
     */
    val recentFeedbackTarget: VoiceDictationFeedbackTarget? = null,
) {
    val isActive: Boolean
        get() = phase == VoiceDictationV2Phase.Connecting || phase == VoiceDictationV2Phase.Listening

    /**
     * Returns true while daily quota is exhausted and [nowEpochMs] has not yet reached the reset
     * instant. Persists even after dismissing the error banner so the Mic stays disabled until reset.
     */
    fun isQuotaExhaustedAt(nowEpochMs: Long): Boolean {
        val exhausted =
            quotaExhausted ||
                (quota != null && quota.limitSeconds > 0 && quota.remainingSeconds <= 0)
        if (!exhausted) return false
        val resetsAtMs = quotaExhaustedResetsAtEpochMs ?: quota?.resetsAtEpochMs
        if (resetsAtMs != null && nowEpochMs >= resetsAtMs) return false
        return true
    }
}

/**
 * Thin-client v2 composer dictation controller:
 * - Zero-wait instant start (`Connecting -> Listening` immediately once mic starts; permit + WS
 *   connect run concurrently in the background).
 * - Strict fail-closed 10 s pre-ready audio buffer (`VOICE_DICTATION_MAX_PRE_READY_CHUNKS = 100`).
 * - Non-blocking `stop()` that returns foreground to `Idle` immediately while draining final/refined
 *   snapshots in the background up to `10.5 s`.
 * - Stop-then-Send `<= 1 s` wait (`awaitPendingFinalBeforeSend`) with deduplication, instant
 *   release on user edit, and cancellation on start/cancel/owner-switch.
 * - Persistent quota-exhausted state surviving banner dismissal until `resetsAt` or owner switch.
 */
@RaftCoreInternalApi
class VoiceDictationV2Controller(
    private val permitApi: VoiceDictationV2PermitApi,
    private val streamConnector: VoiceDictationV2StreamConnector,
    private val scope: CoroutineScope,
    private val audioRecorderProvider: () -> RaftVoiceDictationAudioRecorder? =
        RaftVoiceDictationAudioRecorderRegistry::currentOrNull,
    private val stopDrainTimeoutMs: Long = VOICE_DICTATION_DEFAULT_STOP_DRAIN_TIMEOUT_MS,
    private val submitFinalWaitMs: Long = VOICE_DICTATION_DEFAULT_SUBMIT_FINAL_WAIT_MS,
    private val stopPostRollMs: Long = VOICE_DICTATION_DEFAULT_STOP_POST_ROLL_MS,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onOutcomeReport: ((VoiceDictationOutcomeReport) -> Unit)? = null,
    private val privacyModeProvider: () -> RaftPrivacyMode = { RaftTelemetryPrivacy.state.value },
    private val clientPlatformProvider: () -> VoiceFeedbackClientPlatform = ::currentVoiceFeedbackClientPlatform,
    private val onSessionTelemetry: ((serverId: String, summary: VoiceDictationSessionSummary) -> Unit)? = null,
    private val onDiagnosticLog: (tag: String, message: String) -> Unit = { tag, message ->
        raftLogInfo(tag, message)
    },
) {
    private class QueuedAudioChunk(
        val bytes: ByteArray,
        val preReady: Boolean,
    )

    private data class SessionTimingSnapshot(
        val permitMs: Long? = null,
        val wsConnectMs: Long? = null,
        val readyWaitMs: Long? = null,
        val parallelPermit: Boolean = false,
        val callerContextFallback: Boolean = false,
    )

    private inner class Session(
        val id: Long,
        val owner: VoiceDictationV2Owner,
        val language: String?,
        val recorder: RaftVoiceDictationAudioRecorder,
        val span: VoiceDictationComposerSpan,
        val startedAtMs: Long,
        mode: VoiceDictationSessionMode,
        deferStartMode: Boolean,
        val onComposerUpdate: (VoiceDictationComposerUpdate) -> Unit,
    ) {
        val liveAudioChannel: Channel<QueuedAudioChunk> = Channel(Channel.UNLIMITED)
        val readyReceived: CompletableDeferred<Unit> = CompletableDeferred()
        val recorderReleased: CompletableDeferred<Unit> = CompletableDeferred()
        val startModeDeferred: CompletableDeferred<VoiceDictationSessionMode> =
            CompletableDeferred<VoiceDictationSessionMode>().apply {
                if (!deferStartMode) {
                    complete(mode)
                }
            }

        // Protected by [stateLock].
        val preReadyQueue: ArrayDeque<ByteArray> = ArrayDeque()
        val metrics: VoiceDictationSessionMetrics =
            VoiceDictationSessionMetrics(
                mode = mode,
                startedAtMs = startedAtMs,
            )
        var aborted: Boolean = false
        var stopping: Boolean = false
        var transportReady: Boolean = false
        var startFrameSent: Boolean = false
        var stopDispatchStarted: Boolean = false
        var stopFrameSent: Boolean = false
        var postStopFinalReceived: Boolean = false
        var recorderNeedsStop: Boolean = false
        var recorderStopStarted: Boolean = false
        var recorderStopped: Boolean = false
        var micFlushed: Boolean = false
        var sessionId: String? = null
        var traceId: String? = null
        var streamUrlSource: VoiceStreamUrlSource? = null
        var connectedAtMs: Long? = null
        var firstPartialAtMs: Long? = null
        var permitStartedAtMs: Long? = null
        var permitFinishedAtMs: Long? = null
        var wsConnectStartedAtMs: Long? = null
        var wsConnectedAtMs: Long? = null
        var parallelPermit: Boolean = false
        var callerContextFallback: Boolean = false
        var transcriptCapture: DictationTranscriptCapture = DictationTranscriptCapture()
        var closeReason: String = VOICE_DICTATION_CLOSE_REASON_DONE
        var closeReasonLatched: Boolean = false
        var connection: VoiceDictationV2StreamConnection? = null
        var connectionClosed: Boolean = false
        var runJob: Job? = null
        var senderJob: Job? = null
        var stopJob: Job? = null
        var drainTimeoutJob: Job? = null

        fun timingSnapshotLocked(): SessionTimingSnapshot =
            SessionTimingSnapshot(
                permitMs =
                    permitStartedAtMs?.let { start ->
                        permitFinishedAtMs?.let { finish -> (finish - start).coerceAtLeast(0L) }
                    },
                wsConnectMs =
                    wsConnectStartedAtMs?.let { start ->
                        wsConnectedAtMs?.let { finish -> (finish - start).coerceAtLeast(0L) }
                    },
                readyWaitMs =
                    wsConnectedAtMs?.let { wsConnected ->
                        metrics.connectedAtMs?.let { readyAt -> (readyAt - wsConnected).coerceAtLeast(0L) }
                    },
                parallelPermit = parallelPermit,
                callerContextFallback = callerContextFallback,
            )

        fun markRecorderNeedsStop() {
            synchronized(stateLock) { recorderNeedsStop = true }
        }

        fun recordCloseReasonLocked(reason: String) {
            if (!closeReasonLatched) {
                closeReasonLatched = true
                closeReason = reason
            }
        }

        fun resolvedCloseReason(): String = synchronized(stateLock) { closeReason }

        fun attachConnection(conn: VoiceDictationV2StreamConnection): Boolean =
            synchronized(stateLock) {
                if (aborted || connectionClosed || !isLiveLocked(this)) {
                    false
                } else {
                    connection = conn
                    true
                }
            }

        suspend fun stopRecorderOnce() {
            val target =
                synchronized(stateLock) {
                    if (recorderNeedsStop && !recorderStopStarted) {
                        recorderStopStarted = true
                        recorder
                    } else {
                        null
                    }
                }
            if (target != null) {
                withContext(NonCancellable) {
                    try {
                        runCatching { target.stop() }
                    } finally {
                        synchronized(stateLock) {
                            recorderStopped = true
                        }
                        recorderReleased.complete(Unit)
                    }
                }
            } else {
                val shouldComplete = synchronized(stateLock) { !recorderNeedsStop || recorderStopped }
                if (shouldComplete) {
                    recorderReleased.complete(Unit)
                } else {
                    recorderReleased.await()
                }
            }
        }

        suspend fun closeConnectionOnce() {
            val (connectionToClose, reason) =
                synchronized(stateLock) {
                    if (!connectionClosed && connection != null) {
                        connectionClosed = true
                        connection to closeReason
                    } else {
                        null to closeReason
                    }
                }
            if (connectionToClose != null) {
                runCatching {
                    connectionToClose.close(
                        code = RaftWebSocketSession.NORMAL_CLOSURE,
                        reason = reason,
                    )
                }
            }
        }

        suspend fun cleanupResources() {
            synchronized(stateLock) {
                preReadyQueue.clear()
            }
            liveAudioChannel.close()
            stopRecorderOnce()
            closeConnectionOnce()
        }
    }

    private val stateLock = SynchronizedObject()
    private var nextSessionId: Long = 0L
    private var activeSession: Session? = null
    private var backgroundSession: Session? = null
    private var pendingSubmitWait: CompletableDeferred<Boolean>? = null
    private var pendingRecorderCleanupJob: Job? = null

    private val outcomeTracker =
        VoiceDictationOutcomeTracker(
            nowMillis = nowMillis,
            clientPlatformProvider = clientPlatformProvider,
            privacyModeProvider = privacyModeProvider,
            onOutcomeReport = { report -> onOutcomeReport?.invoke(report) },
        )

    private val stateFlow = MutableStateFlow(VoiceDictationV2State())

    val state: StateFlow<VoiceDictationV2State> = stateFlow.asStateFlow()

    /** Current clock instant in epoch milliseconds (exposed for UI reset-time checks). */
    fun currentEpochMillis(): Long = nowMillis()

    /** Returns true while daily quota is currently exhausted (not yet past `resetsAt`). */
    fun isQuotaExhausted(): Boolean = stateFlow.value.isQuotaExhaustedAt(nowMillis())

    /** Clears expired `quotaExhausted*` fields once the local clock reaches `resetsAt`. */
    fun refreshQuotaExhaustedState() {
        val now = nowMillis()
        synchronized(stateLock) {
            val current = stateFlow.value
            if (current.quotaExhausted && !current.isQuotaExhaustedAt(now)) {
                stateFlow.value =
                    current.copy(
                        quotaExhausted = false,
                        quotaExhaustedResetsAtIso = null,
                        quotaExhaustedResetsAtEpochMs = null,
                    )
            }
        }
    }

    /** True while composer send is waiting on a post-stop final snapshot. */
    fun isAwaitingSubmitFinal(): Boolean = synchronized(stateLock) { pendingSubmitWait != null }

    /**
     * True when composer Send should call [awaitPendingFinalBeforeSend] before reading text and
     * dispatching the message:
     * - Already waiting on a live-mic submit flush (`pendingSubmitWait != null`), OR
     * - An active listening session whose mic is still live (`recorderNeedsStop`) and whose
     *   composer has not been manually edited.
     *
     * When dictation is already stopped (idle or refining in the background), returns `false` so
     * Send dispatches visible composer text immediately (WYSIWYG — "所见即所发").
     */
    fun needsSubmitFinalWait(): Boolean =
        synchronized(stateLock) {
            if (pendingSubmitWait != null) return@synchronized true
            val active = activeSession ?: return@synchronized false
            !active.aborted && active.recorderNeedsStop && !active.span.ignoreSubsequentText
        }

    /** Updates and settles the active foreground session's gesture mode (`Tap` or `Hold`). */
    fun setSessionMode(mode: VoiceDictationSessionMode) {
        val deferredToComplete =
            synchronized(stateLock) {
                val active = activeSession?.takeIf { !it.aborted } ?: return
                active.metrics.mode = mode
                active.startModeDeferred
            }
        deferredToComplete.complete(mode)
    }

    /**
     * Starts a new v2 dictation session for [serverId] / [channelId] / [threadId], anchored at
     * `(baseText, insertStart, insertEnd)`. Any previously active or background session and any
     * pending submit wait are aborted cleanly first.
     *
     * When [deferStartMode] is `true` (Option A gesture-settled startup), microphone capture + PCM
     * buffering, permit fetch, and WebSocket connect all start immediately in parallel, while only
     * the v2 `start` frame waits until [setSessionMode] (or [stop]) settles `"tap"` vs `"hold"`.
     */
    fun start(
        serverId: String,
        channelId: String? = null,
        threadId: String? = null,
        language: String? = null,
        baseText: String = "",
        insertStart: Int = baseText.length,
        insertEnd: Int = insertStart,
        draftBefore: String? = null,
        draftAfter: String? = null,
        contactHints: List<String>? = null,
        mode: VoiceDictationSessionMode = VoiceDictationSessionMode.Tap,
        deferStartMode: Boolean = false,
        onComposerUpdate: (VoiceDictationComposerUpdate) -> Unit,
    ) {
        val cleanServerId = serverId.trim()
        val cleanChannelId = channelId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanThreadId = threadId?.trim()?.takeIf { it.isNotEmpty() }
        // Normalize once at session start; pass transiently to Step 2 permit request only (never
        // retained in VoiceDictationV2State, outcome tracker, feedback target, logs, or traces).
        val normalizedDraftBefore = normalizeVoiceDictationDraftBefore(draftBefore)
        val normalizedDraftAfter = normalizeVoiceDictationDraftAfter(draftAfter)
        val normalizedContactHints = normalizeVoiceDictationContactHints(contactHints)
        val owner =
            VoiceDictationV2Owner(
                serverId = cleanServerId,
                channelId = cleanChannelId,
                threadId = cleanThreadId,
            )

        val now = nowMillis()
        outcomeTracker.onStartNewSession(now)
        val previousCleanup =
            abortAllSessionsLocked(
                resetState = false,
                closeReason = VOICE_DICTATION_CLOSE_REASON_SUPERSEDED,
                activeStopReason = VoiceDictationStopReason.Reset,
            )

        if (cleanServerId.isEmpty()) {
            synchronized(stateLock) {
                stateFlow.value =
                    VoiceDictationV2State(
                        phase = VoiceDictationV2Phase.Idle,
                        owner = null,
                        error = VoiceDictationV2Error.PermitRefused(VoiceDictationV2PermitFailure.InvalidRequest()),
                    )
            }
            return
        }

        val recorder = audioRecorderProvider()
        if (recorder == null) {
            synchronized(stateLock) {
                stateFlow.value =
                    VoiceDictationV2State(
                        phase = VoiceDictationV2Phase.Idle,
                        owner = owner,
                        error = VoiceDictationV2Error.DeviceUnavailable,
                    )
            }
            emitImmediateStartFailureTelemetry(
                serverId = cleanServerId,
                mode = mode,
                startedAtMs = now,
                errorCode = VoiceDictationSessionErrorCode.MicNone,
            )
            return
        }

        val sampleRateHz = recorder.sampleRateHz
        if (sampleRateHz !in VOICE_DICTATION_MIN_SAMPLE_RATE_HZ..VOICE_DICTATION_MAX_SAMPLE_RATE_HZ) {
            synchronized(stateLock) {
                stateFlow.value =
                    VoiceDictationV2State(
                        phase = VoiceDictationV2Phase.Idle,
                        owner = owner,
                        error = VoiceDictationV2Error.AudioCaptureFailed("Unsupported sample rate: $sampleRateHz"),
                    )
            }
            emitImmediateStartFailureTelemetry(
                serverId = cleanServerId,
                mode = mode,
                startedAtMs = now,
                errorCode = VoiceDictationSessionErrorCode.MicUnsupported,
            )
            return
        }

        val session =
            synchronized(stateLock) {
                nextSessionId += 1L
                val created =
                    Session(
                        id = nextSessionId,
                        owner = owner,
                        language = language,
                        recorder = recorder,
                        span = VoiceDictationComposerSpan(baseText = baseText, insertStart = insertStart, insertEnd = insertEnd),
                        startedAtMs = now,
                        mode = mode,
                        deferStartMode = deferStartMode,
                        onComposerUpdate = onComposerUpdate,
                    )
                val prev = stateFlow.value
                val keepExhausted = prev.owner == owner && prev.isQuotaExhaustedAt(now)
                activeSession = created
                stateFlow.value =
                    VoiceDictationV2State(
                        phase = VoiceDictationV2Phase.Connecting,
                        owner = owner,
                        audioLevel = 0f,
                        quota = null,
                        quotaDegraded = false,
                        maxDurationSeconds = null,
                        error = null,
                        lastUpdate = null,
                        hasBackgroundDrain = false,
                        awaitingSubmitFinal = false,
                        quotaExhausted = keepExhausted,
                        quotaExhaustedResetsAtIso = if (keepExhausted) prev.quotaExhaustedResetsAtIso else null,
                        quotaExhaustedResetsAtEpochMs = if (keepExhausted) prev.quotaExhaustedResetsAtEpochMs else null,
                    )
                created
            }

        val job =
            scope.launch {
                var parallelPermitDeferred: Deferred<VoiceDictationV2PermitOutcome>? = null
                try {
                    previousCleanup?.join()
                    if (!isActiveForeground(session)) return@launch

                    val preAuthorized =
                        runCatching { session.recorder.hasRecordAudioPermission() }.getOrDefault(false)
                    if (preAuthorized) {
                        synchronized(stateLock) {
                            session.parallelPermit = true
                        }
                        parallelPermitDeferred =
                            async {
                                requestPermitSafely(
                                    session = session,
                                    draftBefore = normalizedDraftBefore,
                                    draftAfter = normalizedDraftAfter,
                                    contactHints = normalizedContactHints,
                                )
                            }
                    }

                    // Step 1: Acquire microphone first so OS permission prompts cannot expire the
                    // 60 s permit connect window and permission denial never hits /permits or WS.
                    // When RECORD_AUDIO is already granted, permit fetch overlaps with mic startup above.
                    val audioStart =
                        try {
                            session.recorder.start(
                                onPcmChunk = { bytes -> handleAudioChunk(session, bytes) },
                                onAudioLevel = { level -> updateAudioLevel(session, level) },
                            )
                        } catch (cancellation: CancellationException) {
                            parallelPermitDeferred?.cancel()
                            session.markRecorderNeedsStop()
                            throw cancellation
                        } catch (error: Throwable) {
                            parallelPermitDeferred?.cancel()
                            failSession(session, error = VoiceDictationV2Error.AudioCaptureFailed(error.message))
                            return@launch
                        }

                    when (audioStart) {
                        VoiceDictationAudioStartResult.Started -> {
                            session.markRecorderNeedsStop()
                            val micReadyAt = nowMillis()
                            // Zero-wait instant start: transition to Listening immediately once mic
                            // capture is live while permit + WS connect run concurrently below.
                            val enteredListening =
                                synchronized(stateLock) {
                                    if (session.aborted || activeSession !== session) {
                                        false
                                    } else {
                                        if (session.metrics.micReadyAtMs == null) {
                                            session.metrics.micReadyAtMs = micReadyAt
                                        }
                                        stateFlow.value = stateFlow.value.copy(phase = VoiceDictationV2Phase.Listening)
                                        true
                                    }
                                }
                            if (!enteredListening) {
                                parallelPermitDeferred?.cancel()
                                return@launch
                            }
                        }
                        VoiceDictationAudioStartResult.PermissionDenied -> {
                            parallelPermitDeferred?.cancel()
                            failSession(session, error = VoiceDictationV2Error.PermissionDenied)
                            return@launch
                        }
                        VoiceDictationAudioStartResult.DeviceUnavailable -> {
                            parallelPermitDeferred?.cancel()
                            failSession(session, error = VoiceDictationV2Error.DeviceUnavailable)
                            return@launch
                        }
                        is VoiceDictationAudioStartResult.Failed -> {
                            parallelPermitDeferred?.cancel()
                            failSession(session, error = VoiceDictationV2Error.AudioCaptureFailed(audioStart.reason))
                            return@launch
                        }
                    }

                    // Step 2: Obtain single-use v2 permit from Raft server (awaiting parallel fetch if
                    // RECORD_AUDIO was already granted, otherwise running concurrently with live mic capture).
                    val permitOutcome =
                        parallelPermitDeferred?.await()
                            ?: requestPermitSafely(
                                session = session,
                                draftBefore = normalizedDraftBefore,
                                draftAfter = normalizedDraftAfter,
                                contactHints = normalizedContactHints,
                            )
                    if (!isLive(session)) return@launch

                    val permit =
                        when (permitOutcome) {
                            is VoiceDictationV2PermitOutcome.Issued -> permitOutcome.permit
                            is VoiceDictationV2PermitOutcome.Refused -> {
                                failSession(session, error = VoiceDictationV2Error.PermitRefused(permitOutcome.failure))
                                return@launch
                            }
                        }

                    val updatedPermitState =
                        synchronized(stateLock) {
                            if (session.aborted || !isLiveLocked(session)) {
                                false
                            } else {
                                session.sessionId = permit.sessionId
                                session.traceId = permit.traceId
                                session.streamUrlSource = permit.streamUrlSource
                                session.metrics.streamUrlSource = permit.streamUrlSource
                                if (activeSession === session || (activeSession == null && backgroundSession === session)) {
                                    val quotaAvailable = permit.quota.remainingSeconds > 0
                                    stateFlow.value =
                                        stateFlow.value.copy(
                                            quota = permit.quota,
                                            quotaDegraded = permit.quotaDegraded,
                                            maxDurationSeconds = permit.maxDurationSeconds,
                                            quotaExhausted = if (quotaAvailable) false else stateFlow.value.quotaExhausted,
                                            quotaExhaustedResetsAtIso =
                                                if (quotaAvailable) null else stateFlow.value.quotaExhaustedResetsAtIso,
                                            quotaExhaustedResetsAtEpochMs =
                                                if (quotaAvailable) null else stateFlow.value.quotaExhaustedResetsAtEpochMs,
                                        )
                                }
                                true
                            }
                        }
                    if (!updatedPermitState) return@launch

                    // Step 3: Connect WebSocket with `Authorization: Bearer <permit>` (and optional `?tp=`) and send `start`.
                    val wsConnectStartedAt = nowMillis()
                    synchronized(stateLock) {
                        if (session.wsConnectStartedAtMs == null) {
                            session.wsConnectStartedAtMs = wsConnectStartedAt
                        }
                    }
                    val connection =
                        try {
                            streamConnector.connect(
                                url = permit.url,
                                permit = permit.permit,
                                traceId = permit.traceId,
                            )
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            failSession(session, error = VoiceDictationV2Error.ConnectionLost(error.message))
                            return@launch
                        }
                    val wsConnectedAt = nowMillis()
                    synchronized(stateLock) {
                        if (session.wsConnectedAtMs == null) {
                            session.wsConnectedAtMs = wsConnectedAt
                        }
                    }

                    if (!session.attachConnection(connection)) {
                        withContext(NonCancellable) {
                            runCatching {
                                connection.close(
                                    code = RaftWebSocketSession.NORMAL_CLOSURE,
                                    reason = session.resolvedCloseReason(),
                                )
                            }
                        }
                        return@launch
                    }

                    // Option A gesture-settled startup: mic capture (PCM buffered in preReadyQueue),
                    // permit fetch, and WS connect run immediately in parallel; only the v2 `start`
                    // frame waits until press-vs-hold settles (<= 350 ms; 0 ms when already settled).
                    val resolvedMode = session.startModeDeferred.await()
                    if (!isLive(session)) return@launch

                    val startSent =
                        try {
                            connection.sendStart(sampleRateHz = sampleRateHz, mode = resolvedMode)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            failSession(session, error = VoiceDictationV2Error.ConnectionLost(error.message))
                            return@launch
                        }
                    if (!startSent) {
                        failSession(session, error = VoiceDictationV2Error.ConnectionLost("Failed to send start frame"))
                        return@launch
                    }

                    val markedStartSent =
                        synchronized(stateLock) {
                            if (session.aborted || !isLiveLocked(session)) {
                                false
                            } else {
                                session.startFrameSent = true
                                true
                            }
                        }
                    if (!markedStartSent) return@launch

                    // Step 4: Drain FIFO pre-ready buffer and live PCM16 frames strictly after `ready`.
                    val sender =
                        scope.launch {
                            session.readyReceived.await()
                            for (chunk in session.liveAudioChannel) {
                                if (!isLive(session)) break
                                val ok = connection.sendAudio(chunk.bytes)
                                if (!ok) break
                                if (chunk.preReady) {
                                    synchronized(stateLock) {
                                        session.metrics.preReadyChunksSent += 1
                                    }
                                }
                            }
                        }
                    val continueAfterSenderAttach =
                        synchronized(stateLock) {
                            if (session.aborted || !isLiveLocked(session)) {
                                sender.cancel()
                                false
                            } else {
                                session.senderJob = sender
                                true
                            }
                        }
                    if (!continueAfterSenderAttach) return@launch

                    // Step 5: Collect closed-set v2 server frames until `done`, `error`, or close.
                    connection.events.collect { event ->
                        if (!isLive(session)) return@collect
                        when (event) {
                            is VoiceDictationV2StreamEvent.Frame -> handleServerFrame(session, event.frame)
                            is VoiceDictationV2StreamEvent.Closed -> handleStreamClosed(session, event)
                        }
                    }
                } finally {
                    parallelPermitDeferred?.cancel()
                    withContext(NonCancellable) {
                        session.cleanupResources()
                    }
                }
            }

        synchronized(stateLock) {
            if (session.aborted || activeSession !== session) {
                job.cancel()
            } else {
                session.runJob = job
            }
        }
    }

    /**
     * Non-blocking stop: immediately returns foreground [state] to [VoiceDictationV2Phase.Idle]
     * (`audioLevel = 0f`) while retaining any uncommitted [VoiceDictationV2State.provisionalRange]
     * underline during background drain — capturing the optional 300 ms button post-roll, stopping
     * the recorder, flushing pre-stop audio, sending `{"deck":"stop"}`, and awaiting final/refined
     * `text` + `done` up to [stopDrainTimeoutMs] (10.5 s).
     *
     * If stopped before microphone acquisition even resolved, aborts cleanly with no background work.
     */
    fun stop(stoppedBy: VoiceDictationStopReason = VoiceDictationStopReason.Button) {
        val effectivePostRollMs =
            if (stoppedBy == VoiceDictationStopReason.Button) stopPostRollMs else 0L
        stopInternal(stoppedBy = stoppedBy, postRollMs = effectivePostRollMs)
    }

    private fun stopInternal(
        stoppedBy: VoiceDictationStopReason,
        postRollMs: Long,
    ) {
        var sessionToDrain: Session? = null
        var abortImmediately = false
        var modeToSettle: Pair<CompletableDeferred<VoiceDictationSessionMode>, VoiceDictationSessionMode>? = null
        var previousBg: Session? = null
        var previousBgSummary: VoiceDictationSessionSummary? = null
        var previousBgTiming = SessionTimingSnapshot()
        var previousBgHelperJobs: List<Job> = emptyList()
        var previousBgRunJob: Job? = null
        var previousBgHasConnection = false
        val stopAt = nowMillis()

        synchronized(stateLock) {
            val session = activeSession ?: return
            if (session.aborted || session.stopping) return
            if (!session.recorderNeedsStop) {
                abortImmediately = true
            } else {
                // Defensive: abort any lingering background session before attaching this one.
                val existingBg = backgroundSession
                if (existingBg != null && existingBg !== session) {
                    existingBg.recordCloseReasonLocked(VOICE_DICTATION_CLOSE_REASON_SUPERSEDED)
                    existingBg.aborted = true
                    val bgReason = existingBg.metrics.stoppedBy ?: VoiceDictationStopReason.Button
                    val bgFinishingMs = existingBg.metrics.stopRequestedAtMs?.let { (stopAt - it).coerceAtLeast(0L) }
                    previousBgSummary =
                        existingBg.metrics.buildSummary(
                            stoppedBy = bgReason,
                            finishingMs = bgFinishingMs,
                            errorCode = null,
                            nowMs = stopAt,
                        )
                    previousBgTiming = existingBg.timingSnapshotLocked()
                    previousBg = existingBg
                    previousBgRunJob = existingBg.runJob
                    previousBgHasConnection = existingBg.connection != null
                    previousBgHelperJobs = collectHelperJobsLocked(existingBg)
                }

                session.stopping = true
                session.metrics.freezeStop(stoppedBy = stoppedBy, stopAtMs = stopAt)
                session.span.markStopping()
                // Never hang waiting for press-vs-hold settlement if stopped while pending.
                modeToSettle = session.startModeDeferred to session.metrics.mode
                activeSession = null
                backgroundSession = session
                sessionToDrain = session
                stateFlow.value =
                    stateFlow.value.copy(
                        phase = VoiceDictationV2Phase.Idle,
                        audioLevel = 0f,
                        error = null,
                        hasBackgroundDrain = true,
                        provisionalRange = if (session.span.ignoreSubsequentText) null else session.span.lastProvisionalRange,
                    )
            }
        }

        modeToSettle?.let { (deferred, mode) -> deferred.complete(mode) }

        previousBg?.let { stale ->
            emitSessionTelemetrySafely(stale.owner.serverId, previousBgSummary, previousBgTiming)
            stale.liveAudioChannel.close()
            previousBgHelperJobs.forEach { it.cancel() }
            if (!previousBgHasConnection) {
                previousBgRunJob?.cancel()
            }
            scheduleCleanup(
                session = stale,
                runJob = previousBgRunJob,
                cancelRunJobAfterClose = previousBgHasConnection,
                trackRecorderCleanup = false,
            )
        }

        if (abortImmediately) {
            cancel(discardProvisional = false, stoppedBy = stoppedBy)
            return
        }

        val session = sessionToDrain ?: return

        val timeoutJob =
            scope.launch {
                delay(stopDrainTimeoutMs.coerceAtLeast(0L))
                completeSession(session, closeReason = VOICE_DICTATION_CLOSE_REASON_DRAIN_TIMEOUT)
            }
        val stopJob =
            scope.launch {
                val postRoll = postRollMs.coerceAtLeast(0L)
                if (postRoll > 0L) {
                    delay(postRoll)
                }
                if (!isLive(session)) return@launch
                session.stopRecorderOnce()
                val shouldSendStopNow =
                    synchronized(stateLock) {
                        if (session.aborted || backgroundSession !== session) {
                            false
                        } else {
                            session.micFlushed = true
                            if (session.transportReady) {
                                session.liveAudioChannel.close()
                                if (!session.stopDispatchStarted) {
                                    session.stopDispatchStarted = true
                                    true
                                } else {
                                    false
                                }
                            } else {
                                false
                            }
                        }
                    }
                if (shouldSendStopNow) {
                    sendStopAfterSenderDrains(session)
                }
            }

        synchronized(stateLock) {
            if (session.aborted || backgroundSession !== session) {
                timeoutJob.cancel()
                stopJob.cancel()
            } else {
                session.drainTimeoutJob = timeoutJob
                session.stopJob = stopJob
            }
        }
    }

    /**
     * Split submit pre-check matching `#wg-call:a1bba3db` (`slock#17357`):
     * - **Already stopped (idle or post-stop background refinement):** returns `true` immediately
     *   with zero wait (WYSIWYG — "所见即所发") so the caller dispatches visible composer text right
     *   away and aborts in-flight refinement via [finishForSubmit].
     * - **Mic still live (`activeSession` with `recorderNeedsStop` and untouched composer):**
     *   immediately stops it with 0 ms post-roll (`VoiceDictationStopReason.Submit`) so native tail
     *   audio flushes and `{"deck":"stop"}` sends right away, then waits up to [timeoutMs] (1 s)
     *   for trailing words (applying raw post-stop snapshots live into the composer while waiting).
     * - Returns `false` when a submit wait is already in progress (prevents duplicate sends) or if
     *   aborted by [start], [cancel], or [onOwnerChanged].
     * - Resolves `true` as soon as a confirmed post-stop `text` (`stable_len >= text.length` after
     *   `stop` was dispatched) applies, `done`/close completes drain, the user edits the composer, or
     *   [timeoutMs] elapses.
     */
    suspend fun awaitPendingFinalBeforeSend(timeoutMs: Long = submitFinalWaitMs): Boolean {
        val activeToStop: Session =
            synchronized(stateLock) {
                if (pendingSubmitWait != null) return false
                val active = activeSession
                if (active == null || active.aborted || !active.recorderNeedsStop || active.span.ignoreSubsequentText) {
                    return true
                }
                active
            }
        stopInternal(stoppedBy = VoiceDictationStopReason.Submit, postRollMs = 0L)

        val waitDeferred: CompletableDeferred<Boolean> =
            synchronized(stateLock) {
                if (pendingSubmitWait != null) return false
                val bg = backgroundSession
                if (bg !== activeToStop || bg.aborted || bg.postStopFinalReceived || bg.span.ignoreSubsequentText) {
                    return true
                }
                val deferred = CompletableDeferred<Boolean>()
                pendingSubmitWait = deferred
                stateFlow.value = stateFlow.value.copy(awaitingSubmitFinal = true)
                deferred
            }

        val timeoutJob =
            scope.launch {
                delay(timeoutMs.coerceAtLeast(0L))
                resolveSubmitWait(target = waitDeferred, proceed = true)
            }

        return try {
            waitDeferred.await()
        } catch (cancellation: CancellationException) {
            resolveSubmitWait(target = waitDeferred, proceed = false)
            throw cancellation
        } finally {
            timeoutJob.cancel()
            synchronized(stateLock) {
                if (pendingSubmitWait === waitDeferred) {
                    pendingSubmitWait = null
                }
                if (pendingSubmitWait == null && stateFlow.value.awaitingSubmitFinal) {
                    stateFlow.value = stateFlow.value.copy(awaitingSubmitFinal = false)
                }
            }
        }
    }

    /**
     * Ends any active or background dictation session right before sending a composer message so
     * late post-stop frames never write into the freshly cleared composer, while preserving
     * already-landed text and quota state.
     *
     * When an active or background-draining session is still open at submit time, captures its
     * pending outcome with `sentBeforeRefineDone = true` so a subsequent [reportComposerSubmitted]
     * records accurate edit metrics.
     */
    fun finishForSubmit() {
        val sessionToCapture =
            synchronized(stateLock) {
                activeSession?.takeIf { !it.aborted } ?: backgroundSession?.takeIf { !it.aborted }
            }
        if (sessionToCapture != null) {
            captureSessionOutcome(sessionToCapture, sentBeforeRefineDone = true)
        }
        abortAllSessionsLocked(
            resetState = true,
            clearQuotaAndOwner = false,
            closeReason = VOICE_DICTATION_CLOSE_REASON_SUBMIT,
            activeStopReason = VoiceDictationStopReason.Submit,
        )
    }

    /**
     * Records that the user submitted [sentText] in the composer, emitting numeric-only implicit
     * edit metrics (when `RAFT_PRIVACY_MODE` is off) and returning the updated feedback target.
     */
    fun reportComposerSubmitted(sentText: String): VoiceDictationFeedbackTarget? {
        val updatedTarget = outcomeTracker.reportSubmitted(sentText) ?: return null
        synchronized(stateLock) {
            stateFlow.value = stateFlow.value.copy(recentFeedbackTarget = updatedTarget)
        }
        return updatedTarget
    }

    /** Flushes any pending post-dictation outcome as abandoned (`reRecorded = false`) when the composer is cleared. */
    fun onComposerCleared() {
        outcomeTracker.flushAbandoned(reRecorded = false)
    }

    /** Clears the in-memory [VoiceDictationV2State.recentFeedbackTarget] (e.g. after dismissal or submission). */
    fun clearRecentFeedbackTarget() {
        synchronized(stateLock) {
            if (stateFlow.value.recentFeedbackTarget != null) {
                stateFlow.value = stateFlow.value.copy(recentFeedbackTarget = null)
            }
        }
    }

    /** Alias for [clearRecentFeedbackTarget], matching web `dismissFeedbackTarget()`. */
    fun dismissFeedbackTarget() {
        clearRecentFeedbackTarget()
    }

    /**
     * Immediately aborts any active and/or background dictation session and cleans up recorder & WS.
     * When [discardProvisional] is `true` (e.g. pressing Escape during dictation), reverts any
     * uncommitted live partial tail (or restores the initial base draft if nothing was confirmed
     * yet) while preserving already-confirmed utterances.
     */
    fun cancel(
        discardProvisional: Boolean = false,
        stoppedBy: VoiceDictationStopReason =
            if (discardProvisional) VoiceDictationStopReason.Esc else VoiceDictationStopReason.Reset,
    ) {
        outcomeTracker.clear()
        var discardCallback: Pair<(VoiceDictationComposerUpdate) -> Unit, VoiceDictationComposerUpdate>? = null
        abortAllSessionsLocked(
            resetState = true,
            clearQuotaAndOwner = false,
            closeReason = VOICE_DICTATION_CLOSE_REASON_USER_CANCEL,
            activeStopReason = stoppedBy,
            onBeforeAbortActiveLocked =
                if (discardProvisional) {
                    { active ->
                        val update = active.span.discardProvisional()
                        if (update != null) {
                            discardCallback = active.onComposerUpdate to update
                        }
                        update
                    }
                } else {
                    null
                },
        )
        discardCallback?.let { (callback, update) -> callback(update) }
    }

    /**
     * Notifies both active and background sessions that the user edited the composer text. Latches
     * `ignoreSubsequentText = true` so subsequent `text` snapshots (including post-stop refines)
     * never overwrite user edits, clears any live [VoiceDictationV2State.provisionalRange], and
     * immediately releases any pending submit wait so the user's current text sends without delay.
     */
    fun onUserEditedComposer(currentComposerText: String? = null) {
        var releaseSubmitWait = false
        var hasLiveSession = false
        synchronized(stateLock) {
            val active = activeSession?.takeIf { !it.aborted }
            if (active != null) {
                hasLiveSession = true
                val wasIgnored = active.span.ignoreSubsequentText
                active.span.onUserEditedComposer(currentComposerText)
                if (active.span.ignoreSubsequentText && (!wasIgnored || stateFlow.value.provisionalRange != null)) {
                    stateFlow.value = stateFlow.value.copy(provisionalRange = null)
                }
            }
            val bg = backgroundSession?.takeIf { !it.aborted }
            if (bg != null) {
                hasLiveSession = true
                val wasIgnored = bg.span.ignoreSubsequentText
                bg.span.onUserEditedComposer(currentComposerText)
                if (!wasIgnored && bg.span.ignoreSubsequentText) {
                    releaseSubmitWait = true
                }
                if (bg.span.ignoreSubsequentText && activeSession == null && stateFlow.value.provisionalRange != null) {
                    stateFlow.value = stateFlow.value.copy(provisionalRange = null)
                }
            }
        }
        if (releaseSubmitWait) {
            resolveSubmitWait(target = null, proceed = true)
        }
        if (!hasLiveSession && currentComposerText != null) {
            outcomeTracker.onComposerTextChanged(currentComposerText)
        }
    }

    /**
     * Aborts any active or background dictation session (and cancels any pending submit wait) when
     * the user switches server, channel, or thread, clearing per-owner quota and exhausted state.
     */
    fun onOwnerChanged(
        serverId: String?,
        channelId: String? = null,
        threadId: String? = null,
    ) {
        val nextOwner =
            serverId?.trim()?.takeIf { it.isNotEmpty() }?.let { cleanServer ->
                VoiceDictationV2Owner(
                    serverId = cleanServer,
                    channelId = channelId?.trim()?.takeIf { it.isNotEmpty() },
                    threadId = threadId?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        var ownerChanged = false
        val shouldAbort =
            synchronized(stateLock) {
                val active = activeSession
                val bg = backgroundSession
                val anySessionMismatch =
                    (active != null && active.owner != nextOwner) ||
                        (bg != null && bg.owner != nextOwner)
                if (anySessionMismatch) {
                    ownerChanged = true
                    true
                } else if (active == null && bg == null) {
                    val current = stateFlow.value
                    if (current.owner != nextOwner) {
                        ownerChanged = true
                        if (
                            current.error != null ||
                            current.lastUpdate != null ||
                            current.provisionalRange != null ||
                            current.quota != null ||
                            current.quotaExhausted ||
                            current.quotaExhaustedResetsAtIso != null ||
                            current.recentFeedbackTarget != null
                        ) {
                            stateFlow.value = VoiceDictationV2State(phase = VoiceDictationV2Phase.Idle, owner = nextOwner)
                        }
                    }
                    false
                } else {
                    false
                }
            }
        if (ownerChanged) {
            outcomeTracker.clear()
        }
        if (shouldAbort) {
            abortAllSessionsLocked(
                resetState = true,
                clearQuotaAndOwner = true,
                nextOwner = nextOwner,
                closeReason = VOICE_DICTATION_CLOSE_REASON_OWNER_CHANGED,
                activeStopReason = VoiceDictationStopReason.Reset,
            )
        }
    }

    /**
     * Clears the currently displayed error banner while preserving other state — notably keeping
     * persistent `quotaExhausted*` intact until `resetsAt` passes or the owner changes.
     */
    fun clearError() {
        val now = nowMillis()
        synchronized(stateLock) {
            val current = stateFlow.value
            val clearExpiredExhausted = current.quotaExhausted && !current.isQuotaExhaustedAt(now)
            if (current.error != null || clearExpiredExhausted) {
                stateFlow.value =
                    current.copy(
                        error = null,
                        quotaExhausted = if (clearExpiredExhausted) false else current.quotaExhausted,
                        quotaExhaustedResetsAtIso = if (clearExpiredExhausted) null else current.quotaExhaustedResetsAtIso,
                        quotaExhaustedResetsAtEpochMs = if (clearExpiredExhausted) null else current.quotaExhaustedResetsAtEpochMs,
                    )
            }
        }
    }

    private fun handleAudioChunk(session: Session, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val copy = bytes.copyOf()
        var overflow = false
        synchronized(stateLock) {
            if (session.aborted || session.micFlushed || !isLiveLocked(session)) return
            if (session.transportReady) {
                session.liveAudioChannel.trySend(QueuedAudioChunk(copy, preReady = false))
            } else if (session.preReadyQueue.size >= VOICE_DICTATION_MAX_PRE_READY_CHUNKS) {
                overflow = true
            } else {
                session.preReadyQueue.addLast(copy)
                session.metrics.preReadyChunksQueued += 1
            }
        }
        if (overflow) {
            // Never drop opening words silently when server `ready` takes > 10 s: fail closed.
            failSession(
                session,
                error = VoiceDictationV2Error.ConnectionLost("Pre-ready audio buffer exceeded 10 s limit"),
                closeReason = VOICE_DICTATION_CLOSE_REASON_PRE_READY_OVERFLOW,
            )
        }
    }

    private fun handleServerFrame(session: Session, frame: VoiceDictationV2ServerFrame) {
        when (frame) {
            VoiceDictationV2ServerFrame.Ready -> {
                var triggerStopSend = false
                val readyAt = nowMillis()
                synchronized(stateLock) {
                    if (session.aborted || !isLiveLocked(session) || session.transportReady) return
                    session.transportReady = true
                    if (session.connectedAtMs == null) {
                        session.connectedAtMs = readyAt
                    }
                    if (session.metrics.connectedAtMs == null) {
                        session.metrics.connectedAtMs = readyAt
                    }
                    while (session.preReadyQueue.isNotEmpty()) {
                        session.liveAudioChannel.trySend(
                            QueuedAudioChunk(session.preReadyQueue.removeFirst(), preReady = true),
                        )
                    }
                    if (session.micFlushed) {
                        session.liveAudioChannel.close()
                        if (backgroundSession === session && !session.stopDispatchStarted) {
                            session.stopDispatchStarted = true
                            triggerStopSend = true
                        }
                    }
                    if (activeSession === session && stateFlow.value.phase == VoiceDictationV2Phase.Connecting) {
                        stateFlow.value = stateFlow.value.copy(phase = VoiceDictationV2Phase.Listening)
                    }
                }
                session.readyReceived.complete(Unit)
                if (triggerStopSend) {
                    val stopDispatchJob = scope.launch { sendStopAfterSenderDrains(session) }
                    synchronized(stateLock) {
                        if (session.aborted || backgroundSession !== session) {
                            stopDispatchJob.cancel()
                        } else {
                            session.stopJob = stopDispatchJob
                        }
                    }
                }
            }

            is VoiceDictationV2ServerFrame.Text -> {
                var releaseSubmitWait = false
                val textAt = nowMillis()
                val update =
                    synchronized(stateLock) {
                        if (session.aborted || !isLiveLocked(session)) {
                            null
                        } else {
                            if (frame.text.isNotBlank() && session.firstPartialAtMs == null) {
                                session.firstPartialAtMs = textAt
                            }
                            session.metrics.recordSnapshot(
                                text = frame.text,
                                stableLen = frame.stableLen,
                                refined = frame.refined,
                                timestampMs = textAt,
                                isActiveSession = activeSession === session,
                            )
                            session.transcriptCapture = session.transcriptCapture.recordSnapshot(frame.text, frame.refined)
                            val applied = session.span.applyFrame(frame)
                            if (applied != null) {
                                if (activeSession === session || (activeSession == null && backgroundSession === session)) {
                                    stateFlow.value =
                                        stateFlow.value.copy(
                                            lastUpdate = applied,
                                            provisionalRange = applied.provisionalRange,
                                        )
                                }
                            } else if (
                                activeSession == null &&
                                backgroundSession === session &&
                                session.span.lastProvisionalRange == null &&
                                stateFlow.value.provisionalRange != null
                            ) {
                                stateFlow.value = stateFlow.value.copy(provisionalRange = null)
                            }
                            if (
                                backgroundSession === session &&
                                session.stopFrameSent &&
                                !session.span.ignoreSubsequentText &&
                                frame.text.isNotBlank() &&
                                frame.stableLen >= frame.text.length
                            ) {
                                session.postStopFinalReceived = true
                                releaseSubmitWait = true
                            }
                            applied
                        }
                    }
                if (update != null) {
                    session.onComposerUpdate(update)
                }
                if (releaseSubmitWait) {
                    resolveSubmitWait(target = null, proceed = true)
                }
            }

            VoiceDictationV2ServerFrame.Done -> {
                completeSession(session, closeReason = VOICE_DICTATION_CLOSE_REASON_DONE)
            }

            is VoiceDictationV2ServerFrame.Error -> {
                failSession(
                    session,
                    error = VoiceDictationV2Error.StreamError(code = frame.code, message = frame.message),
                    closeReason = VOICE_DICTATION_CLOSE_REASON_ERROR,
                )
            }
        }
    }

    private fun handleStreamClosed(session: Session, closed: VoiceDictationV2StreamEvent.Closed) {
        val shouldFail =
            synchronized(stateLock) {
                if (session.aborted || !isLiveLocked(session)) return
                when {
                    activeSession === session -> closed.unexpected
                    backgroundSession === session -> closed.unexpected && !session.transportReady
                    else -> false
                }
            }
        if (shouldFail) {
            failSession(
                session,
                error = VoiceDictationV2Error.ConnectionLost(closed.causeMessage),
                closeReason = VOICE_DICTATION_CLOSE_REASON_ERROR,
            )
        } else {
            completeSession(session, closeReason = VOICE_DICTATION_CLOSE_REASON_DONE)
        }
    }

    private suspend fun sendStopAfterSenderDrains(session: Session) {
        val sender = synchronized(stateLock) { session.senderJob }
        runCatching { sender?.join() }
        val conn =
            synchronized(stateLock) {
                if (!isLiveLocked(session)) return
                val connection = session.connection ?: return
                session.stopFrameSent = true
                connection
            }
        conn.sendStop()
    }

    private fun updateAudioLevel(session: Session, level: Float) {
        val clamped = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        synchronized(stateLock) {
            if (session.aborted || session.stopping || activeSession !== session) return
            val current = stateFlow.value
            if (current.phase == VoiceDictationV2Phase.Connecting || current.phase == VoiceDictationV2Phase.Listening) {
                stateFlow.value = current.copy(audioLevel = clamped)
            }
        }
    }

    private fun isLiveLocked(session: Session): Boolean =
        !session.aborted && (activeSession === session || backgroundSession === session)

    private fun isLive(session: Session): Boolean = synchronized(stateLock) { isLiveLocked(session) }

    private fun isActiveForeground(session: Session): Boolean =
        synchronized(stateLock) { !session.aborted && activeSession === session }

    private fun completeSession(
        session: Session,
        closeReason: String = VOICE_DICTATION_CLOSE_REASON_DONE,
    ) {
        var releaseSubmitWait = false
        var summary: VoiceDictationSessionSummary? = null
        var timing = SessionTimingSnapshot()
        val completedAt = nowMillis()
        val termination =
            synchronized(stateLock) {
                if (session.aborted) return
                session.recordCloseReasonLocked(closeReason)
                session.aborted = true
                when {
                    activeSession === session -> {
                        activeSession = null
                        session.metrics.freezeStop(VoiceDictationStopReason.Server, completedAt)
                        summary =
                            session.metrics.buildSummary(
                                stoppedBy = VoiceDictationStopReason.Server,
                                finishingMs = 0L,
                                errorCode = null,
                                nowMs = completedAt,
                            )
                        timing = session.timingSnapshotLocked()
                        stateFlow.value =
                            stateFlow.value.copy(
                                phase = VoiceDictationV2Phase.Idle,
                                audioLevel = 0f,
                                error = null,
                                provisionalRange = null,
                            )
                    }
                    backgroundSession === session -> {
                        backgroundSession = null
                        releaseSubmitWait = true
                        val reason = session.metrics.stoppedBy ?: VoiceDictationStopReason.Button
                        val finishingMs = session.metrics.stopRequestedAtMs?.let { (completedAt - it).coerceAtLeast(0L) }
                        summary =
                            session.metrics.buildSummary(
                                stoppedBy = reason,
                                finishingMs = finishingMs,
                                errorCode = null,
                                nowMs = completedAt,
                            )
                        timing = session.timingSnapshotLocked()
                        stateFlow.value =
                            stateFlow.value.copy(
                                hasBackgroundDrain = false,
                                provisionalRange = if (activeSession == null) null else stateFlow.value.provisionalRange,
                            )
                    }
                }
                Triple(collectHelperJobsLocked(session), session.runJob, session.connection != null)
            }
        emitSessionTelemetrySafely(session.owner.serverId, summary, timing)
        val feedbackTarget = captureSessionOutcome(session, sentBeforeRefineDone = false)
        if (feedbackTarget != null) {
            synchronized(stateLock) {
                stateFlow.value = stateFlow.value.copy(recentFeedbackTarget = feedbackTarget)
            }
        }
        val (helperJobs, runJob, hasConnection) = termination
        session.liveAudioChannel.close()
        helperJobs.forEach { it.cancel() }
        if (!hasConnection) {
            runJob?.cancel()
        }
        if (releaseSubmitWait) {
            resolveSubmitWait(target = null, proceed = true)
        }
        scheduleCleanup(
            session = session,
            runJob = runJob,
            cancelRunJobAfterClose = hasConnection,
            trackRecorderCleanup = !session.recorderReleased.isCompleted,
        )
    }

    private fun failSession(
        session: Session,
        error: VoiceDictationV2Error,
        closeReason: String = VOICE_DICTATION_CLOSE_REASON_ERROR,
    ) {
        var releaseSubmitWait = false
        var summary: VoiceDictationSessionSummary? = null
        var timing = SessionTimingSnapshot()
        val failedAt = nowMillis()
        val termination =
            synchronized(stateLock) {
                if (session.aborted) return
                session.recordCloseReasonLocked(closeReason)
                session.aborted = true
                val wasActive = activeSession === session
                val wasBackground = backgroundSession === session
                val wasReady = session.transportReady
                if (wasActive) {
                    activeSession = null
                    session.metrics.freezeStop(VoiceDictationStopReason.Error, failedAt)
                    summary =
                        session.metrics.buildSummary(
                            stoppedBy = VoiceDictationStopReason.Error,
                            finishingMs = null,
                            errorCode = voiceDictationSessionErrorCode(error),
                            nowMs = failedAt,
                        )
                    timing = session.timingSnapshotLocked()
                    stateFlow.value =
                        applyErrorToStateLocked(
                            base =
                                stateFlow.value.copy(
                                    phase = VoiceDictationV2Phase.Idle,
                                    audioLevel = 0f,
                                    provisionalRange = null,
                                ),
                            error = error,
                            surfaceErrorBanner = true,
                        )
                } else if (wasBackground) {
                    backgroundSession = null
                    releaseSubmitWait = true
                    summary =
                        if (!wasReady) {
                            session.metrics.buildSummary(
                                stoppedBy = VoiceDictationStopReason.Error,
                                finishingMs = null,
                                errorCode = voiceDictationSessionErrorCode(error),
                                nowMs = failedAt,
                            )
                        } else {
                            val reason = session.metrics.stoppedBy ?: VoiceDictationStopReason.Button
                            val finishingMs = session.metrics.stopRequestedAtMs?.let { (failedAt - it).coerceAtLeast(0L) }
                            session.metrics.buildSummary(
                                stoppedBy = reason,
                                finishingMs = finishingMs,
                                errorCode = null,
                                nowMs = failedAt,
                            )
                        }
                    timing = session.timingSnapshotLocked()
                    val surfaceBanner = !wasReady && activeSession == null
                    stateFlow.value =
                        applyErrorToStateLocked(
                            base =
                                stateFlow.value.copy(
                                    hasBackgroundDrain = false,
                                    provisionalRange = if (activeSession == null) null else stateFlow.value.provisionalRange,
                                ),
                            error = error,
                            surfaceErrorBanner = surfaceBanner,
                        )
                }
                Triple(collectHelperJobsLocked(session), session.runJob, session.connection != null)
            }
        emitSessionTelemetrySafely(session.owner.serverId, summary, timing)
        val (helperJobs, runJob, hasConnection) = termination
        session.liveAudioChannel.close()
        helperJobs.forEach { it.cancel() }
        if (!hasConnection) {
            runJob?.cancel()
        }
        if (releaseSubmitWait) {
            resolveSubmitWait(target = null, proceed = true)
        }
        scheduleCleanup(
            session = session,
            runJob = runJob,
            cancelRunJobAfterClose = hasConnection,
            trackRecorderCleanup = !session.recorderReleased.isCompleted,
        )
    }

    private fun captureSessionOutcome(
        session: Session,
        sentBeforeRefineDone: Boolean,
    ): VoiceDictationFeedbackTarget? {
        val input =
            synchronized(stateLock) {
                val sid = session.sessionId ?: return null
                val hasSpokenContent =
                    session.span.hasAppliedText ||
                        session.transcriptCapture.v2LastRawText.isNotBlank() ||
                        !session.transcriptCapture.v2LastRefinedText.isNullOrBlank()
                if (!hasSpokenContent) return null
                VoiceDictationOutcomeCaptureInput(
                    serverId = session.owner.serverId,
                    sessionId = sid,
                    composerText = session.span.lastComposerText,
                    transcript = session.transcriptCapture,
                    startedAtMs = session.startedAtMs,
                    connectedAtMs = session.connectedAtMs,
                    firstPartialAtMs = session.firstPartialAtMs,
                    sentBeforeRefineDone = sentBeforeRefineDone,
                )
            }
        return outcomeTracker.capture(input)
    }

    private fun applyErrorToStateLocked(
        base: VoiceDictationV2State,
        error: VoiceDictationV2Error,
        surfaceErrorBanner: Boolean,
    ): VoiceDictationV2State {
        val quotaExceededResetsAtIso: String? =
            when {
                error is VoiceDictationV2Error.PermitRefused && error.failure is VoiceDictationV2PermitFailure.QuotaExceeded ->
                    error.failure.resetsAtIso ?: base.quota?.resetsAtIso
                error is VoiceDictationV2Error.StreamError && error.code == VoiceDictationV2ErrorCode.QuotaExceeded ->
                    base.quota?.resetsAtIso
                else -> null
            }
        val isQuotaExceeded =
            (error is VoiceDictationV2Error.PermitRefused && error.failure is VoiceDictationV2PermitFailure.QuotaExceeded) ||
                (error is VoiceDictationV2Error.StreamError && error.code == VoiceDictationV2ErrorCode.QuotaExceeded)

        val nextQuota =
            if (isQuotaExceeded) {
                base.quota?.copy(remainingSeconds = 0)
            } else {
                base.quota
            }
        val nextExhaustedResetsAtEpochMs =
            if (isQuotaExceeded) {
                quotaExceededResetsAtIso?.let { iso ->
                    runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()
                } ?: base.quota?.resetsAtEpochMs
            } else {
                base.quotaExhaustedResetsAtEpochMs
            }

        return base.copy(
            quota = nextQuota,
            error = if (surfaceErrorBanner) error else base.error,
            quotaExhausted = if (isQuotaExceeded) true else base.quotaExhausted,
            quotaExhaustedResetsAtIso = if (isQuotaExceeded) quotaExceededResetsAtIso else base.quotaExhaustedResetsAtIso,
            quotaExhaustedResetsAtEpochMs = nextExhaustedResetsAtEpochMs,
        )
    }

    private fun abortAllSessionsLocked(
        resetState: Boolean,
        clearQuotaAndOwner: Boolean = false,
        nextOwner: VoiceDictationV2Owner? = null,
        closeReason: String = VOICE_DICTATION_CLOSE_REASON_USER_CANCEL,
        activeStopReason: VoiceDictationStopReason = VoiceDictationStopReason.Reset,
        onBeforeAbortActiveLocked: ((Session) -> VoiceDictationComposerUpdate?)? = null,
    ): Job? {
        val sessionsToClean = mutableListOf<Triple<Session, Job?, Boolean>>()
        val helperJobsToCancel = mutableListOf<Job>()
        val immediateRunJobsToCancel = mutableListOf<Job>()
        val telemetryToEmit = mutableListOf<Triple<String, VoiceDictationSessionSummary, SessionTimingSnapshot>>()
        var existingRecorderCleanup: Job? = null
        var submitWaitToCancel: CompletableDeferred<Boolean>? = null

        synchronized(stateLock) {
            submitWaitToCancel = pendingSubmitWait
            pendingSubmitWait = null

            val abortAt = if (activeSession != null || backgroundSession != null) nowMillis() else 0L
            var discardUpdate: VoiceDictationComposerUpdate? = null
            val active = activeSession
            if (active != null) {
                if (!active.aborted) {
                    if (onBeforeAbortActiveLocked != null) {
                        discardUpdate = onBeforeAbortActiveLocked(active)
                    }
                    active.metrics.freezeStop(activeStopReason, abortAt)
                    active.metrics.buildSummary(
                        stoppedBy = activeStopReason,
                        finishingMs = null,
                        errorCode = null,
                        nowMs = abortAt,
                    )?.let { summary ->
                        telemetryToEmit += Triple(active.owner.serverId, summary, active.timingSnapshotLocked())
                    }
                }
                active.recordCloseReasonLocked(closeReason)
                active.aborted = true
                activeSession = null
                helperJobsToCancel += collectHelperJobsLocked(active)
                val hasConn = active.connection != null
                if (!hasConn) {
                    active.runJob?.let { immediateRunJobsToCancel += it }
                }
                sessionsToClean += Triple(active, active.runJob, hasConn)
            }
            val bg = backgroundSession
            if (bg != null) {
                if (!bg.aborted) {
                    val bgReason = bg.metrics.stoppedBy ?: VoiceDictationStopReason.Button
                    val bgFinishingMs = bg.metrics.stopRequestedAtMs?.let { (abortAt - it).coerceAtLeast(0L) }
                    bg.metrics.buildSummary(
                        stoppedBy = bgReason,
                        finishingMs = bgFinishingMs,
                        errorCode = null,
                        nowMs = abortAt,
                    )?.let { summary ->
                        telemetryToEmit += Triple(bg.owner.serverId, summary, bg.timingSnapshotLocked())
                    }
                }
                bg.recordCloseReasonLocked(closeReason)
                bg.aborted = true
                backgroundSession = null
                helperJobsToCancel += collectHelperJobsLocked(bg)
                val hasConn = bg.connection != null
                if (!hasConn) {
                    bg.runJob?.let { immediateRunJobsToCancel += it }
                }
                sessionsToClean += Triple(bg, bg.runJob, hasConn)
            }

            if (resetState) {
                stateFlow.value =
                    if (clearQuotaAndOwner) {
                        VoiceDictationV2State(phase = VoiceDictationV2Phase.Idle, owner = nextOwner)
                    } else {
                        stateFlow.value.copy(
                            phase = VoiceDictationV2Phase.Idle,
                            audioLevel = 0f,
                            error = null,
                            lastUpdate = discardUpdate ?: stateFlow.value.lastUpdate,
                            hasBackgroundDrain = false,
                            awaitingSubmitFinal = false,
                            provisionalRange = null,
                        )
                    }
            } else if (
                submitWaitToCancel != null ||
                stateFlow.value.awaitingSubmitFinal ||
                stateFlow.value.hasBackgroundDrain ||
                stateFlow.value.provisionalRange != null
            ) {
                stateFlow.value =
                    stateFlow.value.copy(
                        hasBackgroundDrain = false,
                        awaitingSubmitFinal = false,
                        provisionalRange = null,
                    )
            }
            existingRecorderCleanup = pendingRecorderCleanupJob
        }

        telemetryToEmit.forEach { (serverId, summary, timing) ->
            emitSessionTelemetrySafely(serverId, summary, timing)
        }
        submitWaitToCancel?.complete(false)
        if (sessionsToClean.isEmpty()) {
            return existingRecorderCleanup
        }
        sessionsToClean.forEach { (session, _, _) -> session.liveAudioChannel.close() }
        helperJobsToCancel.forEach { it.cancel() }
        immediateRunJobsToCancel.forEach { it.cancel() }

        var lastRecorderCleanup: Job? = existingRecorderCleanup
        sessionsToClean.forEach { (session, runJob, hasConnection) ->
            val needsRecorderWait = !session.recorderReleased.isCompleted
            val cleanup =
                scheduleCleanup(
                    session = session,
                    runJob = runJob,
                    cancelRunJobAfterClose = hasConnection,
                    trackRecorderCleanup = needsRecorderWait,
                )
            if (needsRecorderWait) {
                lastRecorderCleanup = cleanup
            }
        }
        return lastRecorderCleanup
    }

    private suspend fun requestPermitSafely(
        session: Session,
        draftBefore: String?,
        draftAfter: String?,
        contactHints: List<String>?,
    ): VoiceDictationV2PermitOutcome {
        val startedAt = nowMillis()
        synchronized(stateLock) {
            if (session.permitStartedAtMs == null) {
                session.permitStartedAtMs = startedAt
            }
        }
        val outcome =
            try {
                permitApi.requestPermit(
                    serverId = session.owner.serverId,
                    language = session.language,
                    channelId = session.owner.channelId,
                    draftBefore = draftBefore,
                    draftAfter = draftAfter,
                    contactHints = contactHints,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                VoiceDictationV2PermitOutcome.Refused(
                    VoiceDictationV2PermitFailure.Failed(
                        code = "voice_dictation_network_error",
                        serverMessage = error.message,
                    ),
                )
            }
        val finishedAt = nowMillis()
        synchronized(stateLock) {
            if (session.permitFinishedAtMs == null) {
                session.permitFinishedAtMs = finishedAt
            }
            if (outcome is VoiceDictationV2PermitOutcome.Issued && outcome.usedCallerContextFallback) {
                session.callerContextFallback = true
            }
        }
        return outcome
    }

    private fun emitImmediateStartFailureTelemetry(
        serverId: String,
        mode: VoiceDictationSessionMode,
        startedAtMs: Long,
        errorCode: VoiceDictationSessionErrorCode,
    ) {
        val metrics = VoiceDictationSessionMetrics(mode = mode, startedAtMs = startedAtMs)
        metrics.freezeStop(stoppedBy = VoiceDictationStopReason.Error, stopAtMs = startedAtMs)
        val summary =
            metrics.buildSummary(
                stoppedBy = VoiceDictationStopReason.Error,
                finishingMs = null,
                errorCode = errorCode,
                nowMs = startedAtMs,
            )
        emitSessionTelemetrySafely(serverId, summary)
    }

    private fun emitSessionTelemetrySafely(
        serverId: String,
        summary: VoiceDictationSessionSummary?,
        timing: SessionTimingSnapshot = SessionTimingSnapshot(),
    ) {
        if (summary == null) return
        runCatching {
            onDiagnosticLog(
                VOICE_DICTATION_DIAGNOSTIC_LOG_TAG,
                formatVoiceDictationSessionTimingLog(summary = summary, timing = timing),
            )
        }
        val callback = onSessionTelemetry ?: return
        runCatching {
            callback(serverId, summary)
        }
    }

    private fun formatVoiceDictationSessionTimingLog(
        summary: VoiceDictationSessionSummary,
        timing: SessionTimingSnapshot,
    ): String =
        buildString {
            append("voice_dictation_session_timing")
            append(" mode=").append(summary.mode.wire)
            append(" stoppedBy=").append(summary.stoppedBy.wire)
            append(" durationMs=").append(summary.durationMs.coerceAtLeast(0L))
            append(" micStartMs=").append(summary.micStartMs?.coerceAtLeast(0L)?.toString() ?: "none")
            append(" permitMs=").append(timing.permitMs?.coerceAtLeast(0L)?.toString() ?: "none")
            append(" wsConnectMs=").append(timing.wsConnectMs?.coerceAtLeast(0L)?.toString() ?: "none")
            append(" readyWaitMs=").append(timing.readyWaitMs?.coerceAtLeast(0L)?.toString() ?: "none")
            append(" connectMs=").append(summary.connectMs?.coerceAtLeast(0L)?.toString() ?: "none")
            append(" firstPartialMs=").append(summary.firstPartialMs?.coerceAtLeast(0L)?.toString() ?: "none")
            append(" finishingMs=").append(summary.finishingMs?.coerceAtLeast(0L)?.toString() ?: "none")
            append(" parallelPermit=").append(timing.parallelPermit)
            append(" callerContextFallback=").append(timing.callerContextFallback)
            append(" preReadyQueued=").append(summary.preReadyChunksQueued.coerceAtLeast(0))
            append(" preReadySent=").append(summary.preReadyChunksSent.coerceAtLeast(0))
            append(" streamSource=").append(summary.streamUrlSource?.wire ?: "none")
            append(" errorCode=").append(summary.errorCode?.wire ?: "none")
        }

    private companion object {
        const val VOICE_DICTATION_DIAGNOSTIC_LOG_TAG: String = "RaftVoiceDictation"
    }

    private fun resolveSubmitWait(target: CompletableDeferred<Boolean>?, proceed: Boolean) {
        val deferred =
            synchronized(stateLock) {
                val current = pendingSubmitWait ?: return
                if (target != null && current !== target) return
                pendingSubmitWait = null
                if (stateFlow.value.awaitingSubmitFinal) {
                    stateFlow.value = stateFlow.value.copy(awaitingSubmitFinal = false)
                }
                current
            }
        deferred.complete(proceed)
    }

    private fun collectHelperJobsLocked(session: Session): List<Job> =
        listOfNotNull(
            session.drainTimeoutJob,
            session.stopJob,
            session.senderJob,
        )

    private fun scheduleCleanup(
        session: Session,
        runJob: Job?,
        cancelRunJobAfterClose: Boolean,
        trackRecorderCleanup: Boolean,
    ): Job {
        val cleanupJob =
            scope.launch {
                withContext(NonCancellable) {
                    session.stopRecorderOnce()
                    // Flush WebSocket close(1000, reason) BEFORE cancelling runJob so the reader
                    // loop and Ktor session complete normal close instead of aborting with 1011.
                    session.closeConnectionOnce()
                    if (cancelRunJobAfterClose) {
                        runJob?.cancel()
                    }
                    runCatching { runJob?.join() }
                    session.cleanupResources()
                }
            }
        if (cancelRunJobAfterClose && cleanupJob.isCancelled) {
            // Fallback when `scope` itself was already cancelled: cancel runJob so its
            // NonCancellable finally block still flushes close(1000, reason).
            runJob?.cancel()
        }
        if (trackRecorderCleanup) {
            synchronized(stateLock) {
                pendingRecorderCleanupJob = cleanupJob
            }
        }
        return cleanupJob
    }
}
