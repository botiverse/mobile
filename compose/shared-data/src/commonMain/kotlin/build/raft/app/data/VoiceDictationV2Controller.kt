package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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

/** Strict fail-closed pre-ready PCM chunk cap (100 * 100 ms = 10 s; mirrors web MAX_PRE_READY_CHUNKS). */
@RaftCoreInternalApi
const val VOICE_DICTATION_MAX_PRE_READY_CHUNKS: Int = 100

/** Max time composer Send waits for a post-stop final snapshot before sending current text (1 s). */
@RaftCoreInternalApi
const val VOICE_DICTATION_DEFAULT_SUBMIT_FINAL_WAIT_MS: Long = 1_000L

/** Outcome of starting native microphone PCM16 capture. */
@RaftCoreInternalApi
sealed interface VoiceDictationAudioStartResult {
    data object Started : VoiceDictationAudioStartResult

    data object PermissionDenied : VoiceDictationAudioStartResult

    data object DeviceUnavailable : VoiceDictationAudioStartResult

    data class Failed(val reason: String? = null) : VoiceDictationAudioStartResult
}

/**
 * Platform audio capture seam (Android `AudioRecord`, iOS `AVAudioEngine`, Desktop `TargetDataLine`).
 * Implementations must deliver PCM16 little-endian mono frames at [sampleRateHz] and make [stop]
 * idempotent.
 */
@RaftCoreInternalApi
interface RaftVoiceDictationAudioRecorder {
    val sampleRateHz: Int
        get() = VOICE_DICTATION_DEFAULT_SAMPLE_RATE_HZ

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
     * Uncommitted live partial range `[stableEnd, textEnd)` inside [composerText] while listening,
     * or `null` when confirmed (`stableLen >= text.length`) or applied after `stop()`.
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

    /** Transitions the span into post-stop drain mode (still applies frames until user edits). */
    fun markStopping() {
        stopping = true
        lastProvisionalRange = null
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
            if (!stopping && stableEnd < textEnd) {
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
     * Uncommitted live partial UTF-16 range `[start, endExclusive)` in the active composer draft
     * while listening, or `null` when confirmed, idle, stopped, cancelled, errored, or detached by
     * a manual user edit. Mirrors web `VoiceDictationState.provisionalRange`.
     */
    val provisionalRange: VoiceDictationTextRange? = null,
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
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private inner class Session(
        val id: Long,
        val owner: VoiceDictationV2Owner,
        val language: String?,
        val recorder: RaftVoiceDictationAudioRecorder,
        val span: VoiceDictationComposerSpan,
        val onComposerUpdate: (VoiceDictationComposerUpdate) -> Unit,
    ) {
        val liveAudioChannel: Channel<ByteArray> = Channel(Channel.UNLIMITED)
        val readyReceived: CompletableDeferred<Unit> = CompletableDeferred()
        val recorderReleased: CompletableDeferred<Unit> = CompletableDeferred()

        // Protected by [stateLock].
        val preReadyQueue: ArrayDeque<ByteArray> = ArrayDeque()
        var aborted: Boolean = false
        var stopping: Boolean = false
        var transportReady: Boolean = false
        var startFrameSent: Boolean = false
        var stopDispatchStarted: Boolean = false
        var postStopFinalReceived: Boolean = false
        var recorderNeedsStop: Boolean = false
        var recorderStopped: Boolean = false
        var micFlushed: Boolean = false
        var connection: VoiceDictationV2StreamConnection? = null
        var connectionClosed: Boolean = false
        var runJob: Job? = null
        var senderJob: Job? = null
        var stopJob: Job? = null
        var drainTimeoutJob: Job? = null

        fun markRecorderNeedsStop() {
            synchronized(stateLock) { recorderNeedsStop = true }
        }

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
                    if (recorderNeedsStop && !recorderStopped) {
                        recorderStopped = true
                        recorder
                    } else {
                        null
                    }
                }
            if (target != null) {
                try {
                    runCatching { target.stop() }
                } finally {
                    recorderReleased.complete(Unit)
                }
            } else {
                val shouldComplete = synchronized(stateLock) { !recorderNeedsStop || recorderStopped }
                if (shouldComplete) {
                    recorderReleased.complete(Unit)
                }
            }
        }

        suspend fun cleanupResources() {
            val connectionToClose =
                synchronized(stateLock) {
                    preReadyQueue.clear()
                    if (!connectionClosed && connection != null) {
                        connectionClosed = true
                        connection
                    } else {
                        null
                    }
                }
            liveAudioChannel.close()
            stopRecorderOnce()
            if (connectionToClose != null) {
                runCatching { connectionToClose.close() }
            }
        }
    }

    private val stateLock = SynchronizedObject()
    private var nextSessionId: Long = 0L
    private var activeSession: Session? = null
    private var backgroundSession: Session? = null
    private var pendingSubmitWait: CompletableDeferred<Boolean>? = null
    private var pendingRecorderCleanupJob: Job? = null

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
     * dispatching the message (either already waiting, or a background session has not yet
     * delivered a confirmed post-stop snapshot and the user has not edited the composer).
     */
    fun needsSubmitFinalWait(): Boolean =
        synchronized(stateLock) {
            if (pendingSubmitWait != null) return@synchronized true
            val bg = backgroundSession ?: return@synchronized false
            !bg.aborted && !bg.postStopFinalReceived && !bg.span.ignoreSubsequentText
        }

    /**
     * Starts a new v2 dictation session for [serverId] / [channelId] / [threadId], anchored at
     * `(baseText, insertStart, insertEnd)`. Any previously active or background session and any
     * pending submit wait are aborted cleanly first.
     */
    fun start(
        serverId: String,
        channelId: String? = null,
        threadId: String? = null,
        language: String? = null,
        baseText: String = "",
        insertStart: Int = baseText.length,
        insertEnd: Int = insertStart,
        onComposerUpdate: (VoiceDictationComposerUpdate) -> Unit,
    ) {
        val cleanServerId = serverId.trim()
        val cleanChannelId = channelId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanThreadId = threadId?.trim()?.takeIf { it.isNotEmpty() }
        val owner =
            VoiceDictationV2Owner(
                serverId = cleanServerId,
                channelId = cleanChannelId,
                threadId = cleanThreadId,
            )

        val previousCleanup = abortAllSessionsLocked(resetState = false)

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
            return
        }

        val now = nowMillis()
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
                try {
                    previousCleanup?.join()
                    if (!isActiveForeground(session)) return@launch

                    // Step 1: Acquire microphone first so OS permission prompts cannot expire the
                    // 60 s permit connect window and permission denial never hits /permits or WS.
                    val audioStart =
                        try {
                            session.recorder.start(
                                onPcmChunk = { bytes -> handleAudioChunk(session, bytes) },
                                onAudioLevel = { level -> updateAudioLevel(session, level) },
                            )
                        } catch (cancellation: CancellationException) {
                            session.markRecorderNeedsStop()
                            throw cancellation
                        } catch (error: Throwable) {
                            failSession(session, error = VoiceDictationV2Error.AudioCaptureFailed(error.message))
                            return@launch
                        }

                    when (audioStart) {
                        VoiceDictationAudioStartResult.Started -> {
                            session.markRecorderNeedsStop()
                            // Zero-wait instant start: transition to Listening immediately once mic
                            // capture is live while permit + WS connect run concurrently below.
                            val enteredListening =
                                synchronized(stateLock) {
                                    if (session.aborted || activeSession !== session) {
                                        false
                                    } else {
                                        stateFlow.value = stateFlow.value.copy(phase = VoiceDictationV2Phase.Listening)
                                        true
                                    }
                                }
                            if (!enteredListening) return@launch
                        }
                        VoiceDictationAudioStartResult.PermissionDenied -> {
                            failSession(session, error = VoiceDictationV2Error.PermissionDenied)
                            return@launch
                        }
                        VoiceDictationAudioStartResult.DeviceUnavailable -> {
                            failSession(session, error = VoiceDictationV2Error.DeviceUnavailable)
                            return@launch
                        }
                        is VoiceDictationAudioStartResult.Failed -> {
                            failSession(session, error = VoiceDictationV2Error.AudioCaptureFailed(audioStart.reason))
                            return@launch
                        }
                    }

                    // Step 2: Request single-use v2 permit from Raft server (runs concurrently with live mic capture).
                    val permitOutcome =
                        try {
                            permitApi.requestPermit(
                                serverId = session.owner.serverId,
                                language = session.language,
                                channelId = session.owner.channelId,
                            )
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            failSession(
                                session,
                                error =
                                    VoiceDictationV2Error.PermitRefused(
                                        VoiceDictationV2PermitFailure.Failed(
                                            code = "voice_dictation_network_error",
                                            serverMessage = error.message,
                                        ),
                                    ),
                            )
                            return@launch
                        }
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

                    // Step 3: Connect WebSocket with `Authorization: Bearer <permit>` and send `start`.
                    val connection =
                        try {
                            streamConnector.connect(url = permit.url, permit = permit.permit)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            failSession(session, error = VoiceDictationV2Error.ConnectionLost(error.message))
                            return@launch
                        }

                    if (!session.attachConnection(connection)) {
                        withContext(NonCancellable) { runCatching { connection.close() } }
                        return@launch
                    }

                    val startSent =
                        try {
                            connection.sendStart(sampleRateHz)
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
                                val ok = connection.sendAudio(chunk)
                                if (!ok) break
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
     * (`audioLevel = 0f`) while detaching the session into background drain — stopping the recorder,
     * flushing pre-stop audio, sending `{"deck":"stop"}`, and awaiting final/refined `text` + `done`
     * up to [stopDrainTimeoutMs] (10.5 s).
     *
     * If stopped before microphone acquisition even resolved, aborts cleanly with no background work.
     */
    fun stop() {
        var sessionToDrain: Session? = null
        var abortImmediately = false
        var previousBg: Session? = null
        var previousBgJobs: List<Job> = emptyList()
        var previousBgRunJob: Job? = null

        synchronized(stateLock) {
            val session = activeSession ?: return
            if (session.aborted || session.stopping) return
            if (!session.recorderNeedsStop) {
                abortImmediately = true
            } else {
                // Defensive: abort any lingering background session before attaching this one.
                val existingBg = backgroundSession
                if (existingBg != null && existingBg !== session) {
                    existingBg.aborted = true
                    previousBg = existingBg
                    previousBgRunJob = existingBg.runJob
                    previousBgJobs =
                        listOfNotNull(
                            existingBg.drainTimeoutJob,
                            existingBg.stopJob,
                            existingBg.senderJob,
                            existingBg.runJob,
                        )
                }

                session.stopping = true
                session.span.markStopping()
                activeSession = null
                backgroundSession = session
                sessionToDrain = session
                stateFlow.value =
                    stateFlow.value.copy(
                        phase = VoiceDictationV2Phase.Idle,
                        audioLevel = 0f,
                        error = null,
                        hasBackgroundDrain = true,
                        provisionalRange = null,
                    )
            }
        }

        previousBg?.let { stale ->
            stale.liveAudioChannel.close()
            previousBgJobs.forEach { it.cancel() }
            scheduleCleanup(stale, previousBgRunJob, trackRecorderCleanup = false)
        }

        if (abortImmediately) {
            cancel()
            return
        }

        val session = sessionToDrain ?: return

        val timeoutJob =
            scope.launch {
                delay(stopDrainTimeoutMs.coerceAtLeast(0L))
                completeSession(session)
            }
        val stopJob =
            scope.launch {
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
     * Waits briefly (up to [timeoutMs], default 1 s) before sending when a just-stopped background
     * session has not yet delivered its confirmed post-stop snapshot.
     *
     * - Returns `true` immediately when no background drain needs to wait or the user already edited.
     * - Returns `false` when a submit wait is already in progress (prevents duplicate sends) or if
     *   aborted by [start], [cancel], or [onOwnerChanged].
     * - Resolves `true` as soon as a confirmed post-stop `text` applies, `done`/close completes
     *   background drain, the user edits the composer, or [timeoutMs] elapses.
     */
    suspend fun awaitPendingFinalBeforeSend(timeoutMs: Long = submitFinalWaitMs): Boolean {
        val waitDeferred: CompletableDeferred<Boolean> =
            synchronized(stateLock) {
                if (pendingSubmitWait != null) return false
                val bg = backgroundSession ?: return true
                if (bg.aborted || bg.postStopFinalReceived || bg.span.ignoreSubsequentText) {
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
     */
    fun finishForSubmit() {
        abortAllSessionsLocked(resetState = true, clearQuotaAndOwner = false)
    }

    /**
     * Immediately aborts any active and/or background dictation session and cleans up recorder & WS.
     * When [discardProvisional] is `true` (e.g. pressing Escape during dictation), reverts any
     * uncommitted live partial tail (or restores the initial base draft if nothing was confirmed
     * yet) while preserving already-confirmed utterances.
     */
    fun cancel(discardProvisional: Boolean = false) {
        var discardCallback: Pair<(VoiceDictationComposerUpdate) -> Unit, VoiceDictationComposerUpdate>? = null
        abortAllSessionsLocked(
            resetState = true,
            clearQuotaAndOwner = false,
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
        synchronized(stateLock) {
            val active = activeSession?.takeIf { !it.aborted }
            if (active != null) {
                val wasIgnored = active.span.ignoreSubsequentText
                active.span.onUserEditedComposer(currentComposerText)
                if (active.span.ignoreSubsequentText && (!wasIgnored || stateFlow.value.provisionalRange != null)) {
                    stateFlow.value = stateFlow.value.copy(provisionalRange = null)
                }
            }
            val bg = backgroundSession?.takeIf { !it.aborted }
            if (bg != null) {
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
        val shouldAbort =
            synchronized(stateLock) {
                val active = activeSession
                val bg = backgroundSession
                val anySessionMismatch =
                    (active != null && active.owner != nextOwner) ||
                        (bg != null && bg.owner != nextOwner)
                if (anySessionMismatch) {
                    true
                } else if (active == null && bg == null) {
                    val current = stateFlow.value
                    if (
                        current.owner != nextOwner &&
                        (
                            current.error != null ||
                                current.lastUpdate != null ||
                                current.provisionalRange != null ||
                                current.quota != null ||
                                current.quotaExhausted ||
                                current.quotaExhaustedResetsAtIso != null
                        )
                    ) {
                        stateFlow.value = VoiceDictationV2State(phase = VoiceDictationV2Phase.Idle, owner = nextOwner)
                    }
                    false
                } else {
                    false
                }
            }
        if (shouldAbort) {
            abortAllSessionsLocked(resetState = true, clearQuotaAndOwner = true, nextOwner = nextOwner)
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
            if (session.aborted || session.recorderStopped || session.micFlushed || !isLiveLocked(session)) return
            if (session.transportReady) {
                session.liveAudioChannel.trySend(copy)
            } else if (session.preReadyQueue.size >= VOICE_DICTATION_MAX_PRE_READY_CHUNKS) {
                overflow = true
            } else {
                session.preReadyQueue.addLast(copy)
            }
        }
        if (overflow) {
            // Never drop opening words silently when server `ready` takes > 10 s: fail closed.
            failSession(
                session,
                error = VoiceDictationV2Error.ConnectionLost("Pre-ready audio buffer exceeded 10 s limit"),
            )
        }
    }

    private fun handleServerFrame(session: Session, frame: VoiceDictationV2ServerFrame) {
        when (frame) {
            VoiceDictationV2ServerFrame.Ready -> {
                var triggerStopSend = false
                synchronized(stateLock) {
                    if (session.aborted || !isLiveLocked(session) || session.transportReady) return
                    session.transportReady = true
                    while (session.preReadyQueue.isNotEmpty()) {
                        session.liveAudioChannel.trySend(session.preReadyQueue.removeFirst())
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
                val update =
                    synchronized(stateLock) {
                        if (session.aborted || !isLiveLocked(session)) {
                            null
                        } else {
                            val applied = session.span.applyFrame(frame)
                            if (applied != null) {
                                if (activeSession === session) {
                                    stateFlow.value =
                                        stateFlow.value.copy(
                                            lastUpdate = applied,
                                            provisionalRange = applied.provisionalRange,
                                        )
                                } else if (activeSession == null && backgroundSession === session) {
                                    stateFlow.value =
                                        stateFlow.value.copy(
                                            lastUpdate = applied,
                                            provisionalRange = null,
                                        )
                                }
                            }
                            if (
                                backgroundSession === session &&
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
                completeSession(session)
            }

            is VoiceDictationV2ServerFrame.Error -> {
                failSession(
                    session,
                    error = VoiceDictationV2Error.StreamError(code = frame.code, message = frame.message),
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
            failSession(session, error = VoiceDictationV2Error.ConnectionLost(closed.causeMessage))
        } else {
            completeSession(session)
        }
    }

    private suspend fun sendStopAfterSenderDrains(session: Session) {
        val sender = synchronized(stateLock) { session.senderJob }
        runCatching { sender?.join() }
        if (!isLive(session)) return
        val conn = synchronized(stateLock) { session.connection }
        conn?.sendStop()
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

    private fun completeSession(session: Session) {
        var releaseSubmitWait = false
        val (jobsToCancel, runJob) =
            synchronized(stateLock) {
                if (session.aborted) return
                session.aborted = true
                when {
                    activeSession === session -> {
                        activeSession = null
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
                        stateFlow.value =
                            stateFlow.value.copy(
                                hasBackgroundDrain = false,
                                provisionalRange = if (activeSession == null) null else stateFlow.value.provisionalRange,
                            )
                    }
                }
                collectSessionJobsLocked(session) to session.runJob
            }
        session.liveAudioChannel.close()
        jobsToCancel.forEach { it.cancel() }
        if (releaseSubmitWait) {
            resolveSubmitWait(target = null, proceed = true)
        }
        scheduleCleanup(session, runJob, trackRecorderCleanup = !session.recorderReleased.isCompleted)
    }

    private fun failSession(
        session: Session,
        error: VoiceDictationV2Error,
    ) {
        var releaseSubmitWait = false
        val (jobsToCancel, runJob) =
            synchronized(stateLock) {
                if (session.aborted) return
                session.aborted = true
                val wasActive = activeSession === session
                val wasBackground = backgroundSession === session
                val wasReady = session.transportReady
                if (wasActive) {
                    activeSession = null
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
                collectSessionJobsLocked(session) to session.runJob
            }
        session.liveAudioChannel.close()
        jobsToCancel.forEach { it.cancel() }
        if (releaseSubmitWait) {
            resolveSubmitWait(target = null, proceed = true)
        }
        scheduleCleanup(session, runJob, trackRecorderCleanup = !session.recorderReleased.isCompleted)
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
        onBeforeAbortActiveLocked: ((Session) -> VoiceDictationComposerUpdate?)? = null,
    ): Job? {
        val sessionsToClean = mutableListOf<Pair<Session, Job?>>()
        val jobsToCancel = mutableListOf<Job>()
        var existingRecorderCleanup: Job? = null
        var submitWaitToCancel: CompletableDeferred<Boolean>? = null

        synchronized(stateLock) {
            submitWaitToCancel = pendingSubmitWait
            pendingSubmitWait = null

            var discardUpdate: VoiceDictationComposerUpdate? = null
            val active = activeSession
            if (active != null) {
                if (!active.aborted && onBeforeAbortActiveLocked != null) {
                    discardUpdate = onBeforeAbortActiveLocked(active)
                }
                active.aborted = true
                activeSession = null
                jobsToCancel += collectSessionJobsLocked(active)
                sessionsToClean += active to active.runJob
            }
            val bg = backgroundSession
            if (bg != null) {
                bg.aborted = true
                backgroundSession = null
                jobsToCancel += collectSessionJobsLocked(bg)
                sessionsToClean += bg to bg.runJob
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

        submitWaitToCancel?.complete(false)
        if (sessionsToClean.isEmpty()) {
            return existingRecorderCleanup
        }
        sessionsToClean.forEach { (session, _) -> session.liveAudioChannel.close() }
        jobsToCancel.forEach { it.cancel() }

        var lastRecorderCleanup: Job? = existingRecorderCleanup
        sessionsToClean.forEach { (session, runJob) ->
            val needsRecorderWait = !session.recorderReleased.isCompleted
            val cleanup = scheduleCleanup(session, runJob, trackRecorderCleanup = needsRecorderWait)
            if (needsRecorderWait) {
                lastRecorderCleanup = cleanup
            }
        }
        return lastRecorderCleanup
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

    private fun collectSessionJobsLocked(session: Session): List<Job> =
        listOfNotNull(
            session.drainTimeoutJob,
            session.stopJob,
            session.senderJob,
            session.runJob,
        )

    private fun scheduleCleanup(
        session: Session,
        runJob: Job?,
        trackRecorderCleanup: Boolean,
    ): Job {
        val cleanupJob =
            scope.launch {
                withContext(NonCancellable) {
                    session.cleanupResources()
                    runCatching { runJob?.join() }
                    session.cleanupResources()
                }
            }
        if (trackRecorderCleanup) {
            synchronized(stateLock) {
                pendingRecorderCleanupJob = cleanupJob
            }
        }
        return cleanupJob
    }
}
