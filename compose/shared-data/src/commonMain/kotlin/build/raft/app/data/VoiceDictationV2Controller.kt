package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Composer streaming voice dictation — thin-client protocol v2 controller, audio recorder seam,
// and composer span helper (`#wg-mobile-sync:#334`).
// Authoritative contract: `botiverse/slock/docs/contracts/voice-dictation-v2.md` (#17146 + #17149).

/** Default bounded wait after sending `stop` in case the network drops before `done` arrives. */
@RaftCoreInternalApi
const val VOICE_DICTATION_DEFAULT_STOP_DRAIN_TIMEOUT_MS: Long = 5_000L

private const val VOICE_DICTATION_AUDIO_QUEUE_CAPACITY: Int = 128

/** Outcome of starting native microphone PCM16 capture. */
@RaftCoreInternalApi
sealed interface VoiceDictationAudioStartResult {
    data object Started : VoiceDictationAudioStartResult

    data object PermissionDenied : VoiceDictationAudioStartResult

    data object DeviceUnavailable : VoiceDictationAudioStartResult

    data class Failed(val reason: String? = null) : VoiceDictationAudioStartResult
}

/**
 * Platform audio capture seam (Android `AudioRecord` / iOS `AVAudioEngine` implemented in PR 2).
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
    /** Collapsed caret offset recommended after applying this update. */
    val caretOffset: Int,
    /** Clamped `stable_len` relative to `frame.text` (`0..frame.text.length`). */
    val stableLen: Int,
    /** Whether `holt-live` marked this snapshot `refined: true`. */
    val refined: Boolean,
)

/**
 * Pure-Kotlin composer span helper for thin-client v2 dictation.
 *
 * - Captures `(baseText, insertStart, insertEnd)` when dictation begins and normalizes left/right
 *   boundary spacing cleanly (never inserting spaces at CJK boundaries).
 * - While active (and while stopping if untouched), replaces the dictated span with each whole-
 *   session `text` snapshot from `holt-live`, tracking `stableLen` and `refined`.
 * - Minimal client safety rule (`docs/contracts/voice-dictation-v2.md` §Client rendering rules):
 *   once the user edits the composer ([onUserEditedComposer]), latches [ignoreSubsequentText] to
 *   `true` and ignores ALL subsequent `text` frames so user edits are never overwritten.
 */
@RaftCoreInternalApi
class VoiceDictationComposerSpan(
    baseText: String,
    insertStart: Int = baseText.length,
    insertEnd: Int = insertStart,
) {
    val start: Int = insertStart.coerceIn(0, baseText.length)
    val end: Int = insertEnd.coerceIn(start, baseText.length)

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

    /** Transitions the span into post-stop drain mode (still applies frames until user edits). */
    fun markStopping() {
        stopping = true
    }

    /**
     * Records a user edit in the composer. When [currentComposerText] is supplied and equals
     * [lastComposerText], ignores the programmatic write echo; otherwise latches
     * [ignoreSubsequentText] = `true` so no subsequent `text` snapshot can overwrite user edits.
     */
    fun onUserEditedComposer(currentComposerText: String? = null) {
        if (currentComposerText != null && currentComposerText == lastComposerText) {
            return
        }
        ignoreSubsequentText = true
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

        hasAppliedText = true
        lastText = text
        lastStableLen = clampedStableLen
        lastRefined = refined
        lastComposerText = nextComposerText

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
        )
    }
}

/** Lifecycle phase of [VoiceDictationV2Controller]. */
@RaftCoreInternalApi
enum class VoiceDictationV2Phase {
    Idle,
    Connecting,
    Listening,
    Stopping,
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
) {
    val isActive: Boolean
        get() = phase != VoiceDictationV2Phase.Idle
}

/**
 * Thin-client v2 composer dictation controller: single active session at a time, owner/server-
 * switch safe, race-guarded across mic start / permit fetch / WS connect, and protected by a
 * bounded post-stop drain timeout.
 */
@RaftCoreInternalApi
class VoiceDictationV2Controller(
    private val permitApi: VoiceDictationV2PermitApi,
    private val streamConnector: VoiceDictationV2StreamConnector,
    private val scope: CoroutineScope,
    private val audioRecorderProvider: () -> RaftVoiceDictationAudioRecorder? =
        RaftVoiceDictationAudioRecorderRegistry::currentOrNull,
    private val stopDrainTimeoutMs: Long = VOICE_DICTATION_DEFAULT_STOP_DRAIN_TIMEOUT_MS,
) {
    private inner class Session(
        val id: Long,
        val owner: VoiceDictationV2Owner,
        val language: String?,
        val recorder: RaftVoiceDictationAudioRecorder,
        val span: VoiceDictationComposerSpan,
        val onComposerUpdate: (VoiceDictationComposerUpdate) -> Unit,
    ) {
        val audioChannel: Channel<ByteArray> =
            Channel(
                capacity = VOICE_DICTATION_AUDIO_QUEUE_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        val readyReceived: CompletableDeferred<Unit> = CompletableDeferred()

        // Protected by [stateLock].
        var aborted: Boolean = false
        var stopping: Boolean = false
        var startFrameSent: Boolean = false
        var recorderNeedsStop: Boolean = false
        var recorderStopped: Boolean = false
        var connection: VoiceDictationV2StreamConnection? = null
        var connectionClosed: Boolean = false
        var runJob: Job? = null
        var senderJob: Job? = null
        var stopJob: Job? = null
        var drainTimeoutJob: Job? = null

        fun enqueueAudio(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            val allow = synchronized(stateLock) { !aborted && !stopping && activeSession === this }
            if (allow) {
                audioChannel.trySend(bytes.copyOf())
            }
        }

        fun markRecorderNeedsStop() {
            synchronized(stateLock) { recorderNeedsStop = true }
        }

        fun attachConnection(conn: VoiceDictationV2StreamConnection): Boolean =
            synchronized(stateLock) {
                if (aborted || connectionClosed || activeSession !== this) {
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
                runCatching { target.stop() }
            }
        }

        suspend fun cleanupResources() {
            val connectionToClose =
                synchronized(stateLock) {
                    if (!connectionClosed && connection != null) {
                        connectionClosed = true
                        connection
                    } else {
                        null
                    }
                }
            audioChannel.close()
            stopRecorderOnce()
            if (connectionToClose != null) {
                runCatching { connectionToClose.close() }
            }
        }
    }

    private val stateLock = SynchronizedObject()
    private var nextSessionId: Long = 0L
    private var activeSession: Session? = null
    private var pendingCleanupJob: Job? = null

    private val stateFlow = MutableStateFlow(VoiceDictationV2State())

    val state: StateFlow<VoiceDictationV2State> = stateFlow.asStateFlow()

    /**
     * Starts a new v2 dictation session for [serverId] / [channelId] / [threadId], anchored at
     * `(baseText, insertStart, insertEnd)`. Any previously active session is aborted cleanly first.
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

        val previousCleanup = abortActiveSessionLockedOrNull(resetState = false)

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
                    )
                created
            }

        val job =
            scope.launch {
                try {
                    previousCleanup?.join()
                    if (!isCurrent(session)) return@launch

                    // Step 1: Acquire microphone first so OS permission prompts cannot expire the
                    // 60 s permit connect window and permission denial never hits /permits or WS.
                    val audioStart =
                        try {
                            session.recorder.start(
                                onPcmChunk = { bytes -> session.enqueueAudio(bytes) },
                                onAudioLevel = { level -> updateAudioLevel(session, level) },
                            )
                        } catch (cancellation: CancellationException) {
                            session.markRecorderNeedsStop()
                            throw cancellation
                        } catch (error: Throwable) {
                            finishSession(session, error = VoiceDictationV2Error.AudioCaptureFailed(error.message))
                            return@launch
                        }

                    when (audioStart) {
                        VoiceDictationAudioStartResult.Started -> session.markRecorderNeedsStop()
                        VoiceDictationAudioStartResult.PermissionDenied -> {
                            finishSession(session, error = VoiceDictationV2Error.PermissionDenied)
                            return@launch
                        }
                        VoiceDictationAudioStartResult.DeviceUnavailable -> {
                            finishSession(session, error = VoiceDictationV2Error.DeviceUnavailable)
                            return@launch
                        }
                        is VoiceDictationAudioStartResult.Failed -> {
                            finishSession(session, error = VoiceDictationV2Error.AudioCaptureFailed(audioStart.reason))
                            return@launch
                        }
                    }
                    if (!isCurrent(session)) return@launch

                    // Step 2: Request single-use v2 permit from Raft server.
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
                            finishSession(
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
                    if (!isCurrent(session)) return@launch

                    val permit =
                        when (permitOutcome) {
                            is VoiceDictationV2PermitOutcome.Issued -> permitOutcome.permit
                            is VoiceDictationV2PermitOutcome.Refused -> {
                                finishSession(session, error = VoiceDictationV2Error.PermitRefused(permitOutcome.failure))
                                return@launch
                            }
                        }

                    val updatedPermitState =
                        synchronized(stateLock) {
                            if (session.aborted || activeSession !== session) {
                                false
                            } else {
                                stateFlow.value =
                                    stateFlow.value.copy(
                                        quota = permit.quota,
                                        quotaDegraded = permit.quotaDegraded,
                                        maxDurationSeconds = permit.maxDurationSeconds,
                                    )
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
                            finishSession(session, error = VoiceDictationV2Error.ConnectionLost(error.message))
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
                            finishSession(session, error = VoiceDictationV2Error.ConnectionLost(error.message))
                            return@launch
                        }
                    if (!startSent) {
                        finishSession(session, error = VoiceDictationV2Error.ConnectionLost("Failed to send start frame"))
                        return@launch
                    }

                    val markedStartSent =
                        synchronized(stateLock) {
                            if (session.aborted || activeSession !== session) {
                                false
                            } else {
                                session.startFrameSent = true
                                true
                            }
                        }
                    if (!markedStartSent) return@launch

                    // Step 4: Drain buffered/live PCM16 frames strictly after `ready` arrives.
                    val sender =
                        scope.launch {
                            session.readyReceived.await()
                            for (chunk in session.audioChannel) {
                                if (!isCurrent(session)) break
                                val ok = connection.sendAudio(chunk)
                                if (!ok) break
                            }
                        }
                    val continueAfterSenderAttach =
                        synchronized(stateLock) {
                            if (session.aborted || activeSession !== session) {
                                sender.cancel()
                                false
                            } else if (session.stopping) {
                                sender.cancel()
                                true
                            } else {
                                session.senderJob = sender
                                true
                            }
                        }
                    if (!continueAfterSenderAttach) return@launch

                    // Step 5: Collect closed-set v2 server frames until `done`, `error`, or close.
                    connection.events.collect { event ->
                        if (!isCurrent(session)) return@collect
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
     * Gracefully ends recording, flushes pre-stop audio, sends `{"deck":"stop"}`, and waits up to
     * [stopDrainTimeoutMs] for final refined `text` snapshots and `done`. If `start` has not been
     * sent yet, aborts cleanly to [VoiceDictationV2Phase.Idle].
     */
    fun stop() {
        val action: Pair<Session, Boolean> =
            synchronized(stateLock) {
                val session = activeSession ?: return
                if (session.aborted || session.stopping) return
                if (!session.startFrameSent) {
                    session to false
                } else {
                    session.stopping = true
                    session.span.markStopping()
                    stateFlow.value =
                        stateFlow.value.copy(
                            phase = VoiceDictationV2Phase.Stopping,
                            audioLevel = 0f,
                        )
                    session to true
                }
            }

        val (session, gracefulDrain) = action
        if (!gracefulDrain) {
            cancel()
            return
        }

        val timeoutJob =
            scope.launch {
                delay(stopDrainTimeoutMs.coerceAtLeast(0L))
                finishSession(session, error = null)
            }
        val stopJob =
            scope.launch {
                session.stopRecorderOnce()
                session.audioChannel.close()
                val sender = synchronized(stateLock) { session.senderJob }
                if (session.readyReceived.isCompleted) {
                    runCatching { sender?.join() }
                } else {
                    sender?.cancel()
                }
                if (!isCurrent(session)) return@launch
                val conn = synchronized(stateLock) { session.connection }
                conn?.sendStop()
            }

        synchronized(stateLock) {
            if (session.aborted || activeSession !== session) {
                timeoutJob.cancel()
                stopJob.cancel()
            } else {
                session.drainTimeoutJob = timeoutJob
                session.stopJob = stopJob
            }
        }
    }

    /** Immediately aborts any in-flight or active dictation session and cleans up recorder & WS. */
    fun cancel() {
        abortActiveSessionLockedOrNull(resetState = true)
    }

    /**
     * Notifies the active session that the user edited the composer text. Latches
     * `ignoreSubsequentText = true` so subsequent `text` snapshots (including post-stop refines)
     * never overwrite user edits.
     */
    fun onUserEditedComposer(currentComposerText: String? = null) {
        synchronized(stateLock) {
            val session = activeSession ?: return
            if (session.aborted) return
            session.span.onUserEditedComposer(currentComposerText)
        }
    }

    /**
     * Aborts any in-flight or active dictation session when the user switches server, channel, or
     * thread, ensuring the new composer is never touched and recorder/socket are cleaned up.
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
                if (active != null) {
                    active.owner != nextOwner
                } else {
                    val current = stateFlow.value
                    if (current.owner != nextOwner && (current.error != null || current.lastUpdate != null || current.quota != null)) {
                        stateFlow.value = VoiceDictationV2State(phase = VoiceDictationV2Phase.Idle, owner = nextOwner)
                    }
                    false
                }
            }
        if (shouldAbort) {
            abortActiveSessionLockedOrNull(resetState = true, clearQuotaAndOwner = true, nextOwner = nextOwner)
        }
    }

    /** Clears the currently displayed error while preserving other state. */
    fun clearError() {
        synchronized(stateLock) {
            val current = stateFlow.value
            if (current.error != null) {
                stateFlow.value = current.copy(error = null)
            }
        }
    }

    private fun handleServerFrame(session: Session, frame: VoiceDictationV2ServerFrame) {
        when (frame) {
            VoiceDictationV2ServerFrame.Ready -> {
                synchronized(stateLock) {
                    if (session.aborted || activeSession !== session) return
                    if (!session.stopping && stateFlow.value.phase == VoiceDictationV2Phase.Connecting) {
                        stateFlow.value = stateFlow.value.copy(phase = VoiceDictationV2Phase.Listening)
                    }
                }
                session.readyReceived.complete(Unit)
            }
            is VoiceDictationV2ServerFrame.Text -> {
                val update =
                    synchronized(stateLock) {
                        if (session.aborted || activeSession !== session) {
                            null
                        } else {
                            session.span.applyFrame(frame)?.also { applied ->
                                stateFlow.value = stateFlow.value.copy(lastUpdate = applied)
                            }
                        }
                    }
                if (update != null) {
                    session.onComposerUpdate(update)
                }
            }
            VoiceDictationV2ServerFrame.Done -> {
                finishSession(session, error = null)
            }
            is VoiceDictationV2ServerFrame.Error -> {
                finishSession(
                    session,
                    error = VoiceDictationV2Error.StreamError(code = frame.code, message = frame.message),
                )
            }
        }
    }

    private fun handleStreamClosed(session: Session, closed: VoiceDictationV2StreamEvent.Closed) {
        val error =
            synchronized(stateLock) {
                if (session.aborted || activeSession !== session) return
                if (closed.unexpected && !(session.stopping && session.span.hasAppliedText)) {
                    VoiceDictationV2Error.ConnectionLost(closed.causeMessage)
                } else {
                    null
                }
            }
        finishSession(session, error = error)
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

    private fun isCurrent(session: Session): Boolean =
        synchronized(stateLock) { !session.aborted && activeSession === session }

    private fun finishSession(
        session: Session,
        error: VoiceDictationV2Error?,
    ) {
        val (jobsToCancel, runJob) =
            synchronized(stateLock) {
                if (session.aborted) return
                session.aborted = true
                if (activeSession === session) {
                    activeSession = null
                    stateFlow.value =
                        stateFlow.value.copy(
                            phase = VoiceDictationV2Phase.Idle,
                            audioLevel = 0f,
                            error = error,
                        )
                }
                listOfNotNull(
                    session.drainTimeoutJob,
                    session.stopJob,
                    session.senderJob,
                    session.runJob,
                ) to session.runJob
            }
        session.audioChannel.close()
        jobsToCancel.forEach { it.cancel() }
        scheduleCleanup(session, runJob)
    }

    private fun abortActiveSessionLockedOrNull(
        resetState: Boolean,
        clearQuotaAndOwner: Boolean = false,
        nextOwner: VoiceDictationV2Owner? = null,
    ): Job? {
        var previous: Session? = null
        var jobsToCancel: List<Job> = emptyList()
        var previousRunJob: Job? = null
        var existingCleanup: Job? = null
        synchronized(stateLock) {
            val current = activeSession
            if (current == null) {
                if (resetState) {
                    stateFlow.value =
                        if (clearQuotaAndOwner) {
                            VoiceDictationV2State(phase = VoiceDictationV2Phase.Idle, owner = nextOwner)
                        } else {
                            stateFlow.value.copy(
                                phase = VoiceDictationV2Phase.Idle,
                                audioLevel = 0f,
                                error = null,
                            )
                        }
                }
                existingCleanup = pendingCleanupJob
            } else {
                current.aborted = true
                activeSession = null
                if (resetState) {
                    stateFlow.value =
                        if (clearQuotaAndOwner) {
                            VoiceDictationV2State(phase = VoiceDictationV2Phase.Idle, owner = nextOwner)
                        } else {
                            stateFlow.value.copy(
                                phase = VoiceDictationV2Phase.Idle,
                                audioLevel = 0f,
                                error = null,
                            )
                        }
                }
                previous = current
                previousRunJob = current.runJob
                jobsToCancel =
                    listOfNotNull(
                        current.drainTimeoutJob,
                        current.stopJob,
                        current.senderJob,
                        current.runJob,
                    )
            }
        }
        val toClean = previous ?: return existingCleanup
        toClean.audioChannel.close()
        jobsToCancel.forEach { it.cancel() }
        return scheduleCleanup(toClean, previousRunJob)
    }

    private fun scheduleCleanup(session: Session, runJob: Job?): Job {
        val cleanupJob =
            scope.launch {
                withContext(NonCancellable) {
                    session.cleanupResources()
                    runCatching { runJob?.join() }
                    session.cleanupResources()
                }
            }
        synchronized(stateLock) {
            pendingCleanupJob = cleanupJob
        }
        return cleanupJob
    }
}
