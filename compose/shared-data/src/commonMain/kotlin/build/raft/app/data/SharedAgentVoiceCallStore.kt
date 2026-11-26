package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// Raft Agent Voice call store (`#wg-mobile-sync:#95`), mirroring web
// `packages/web/src/features/agent/agentVoiceCallStore.ts`.
//
// Lifecycle:
//   Starting (POST …/voice/calls) → Connecting (native engine joining room) → Live ⇄ Reconnecting →
//   Ended (duration + reason: Left / Closed / Lost) or Failed (typed AgentVoiceCallProblem).
// Hanging up before the call goes live dismisses the card cleanly while still notifying the server
// if a callId was already minted. The server receives hang-up at most once, and never when the
// server itself closed the room (`SERVER_CLOSED`).

private const val CAPTION_LINES: Int = 2
private const val FINISHED_CAPTIONS_KEPT: Int = 200
private const val AGENT_VOICE_CALL_LIMIT_MARGIN_MS: Long = 60_000L
private const val DEFAULT_TOKEN_REFRESH_LEAD_MS: Long = 60_000L
private const val MIN_TOKEN_REFRESH_DELAY_MS: Long = 1_000L
private const val TOKEN_REFRESH_RETRY_DELAY_MS: Long = 5_000L

private val captionJson = Json { ignoreUnknownKeys = true }

/** Current phase of an agent voice call (mirrors web `AgentVoiceCallPhase`). */
@RaftCoreInternalApi
enum class AgentVoiceCallPhase {
    Starting,
    Connecting,
    Live,
    Reconnecting,
    Ended,
    Failed,
}

/** How a live call ended (mirrors web `@holtjs/live-client` `EndReason`). */
@RaftCoreInternalApi
enum class AgentVoiceCallEndReason {
    Left,
    Closed,
    Lost,
}

/** Identity of the agent being called, displayed in the floating call card. */
@RaftCoreInternalApi
data class AgentVoiceCallee(
    val id: String,
    val name: String,
    val avatarWireValue: String? = null,
)

/** One caption line delivered over the room data channel (mirrors web `AgentVoiceCaption`). */
@RaftCoreInternalApi
data class AgentVoiceCaption(
    val id: String,
    val mine: Boolean,
    val text: String,
    val final: Boolean,
)

/** Immutable snapshot of the single active/ended voice call (or null when idle). */
@RaftCoreInternalApi
data class AgentVoiceCallState(
    val agent: AgentVoiceCallee,
    val serverId: String,
    val callId: String? = null,
    val phase: AgentVoiceCallPhase = AgentVoiceCallPhase.Starting,
    /** Epoch millis when the call first entered [AgentVoiceCallPhase.Live]; null before live. */
    val connectedAtEpochMs: Long? = null,
    /** Total duration once [AgentVoiceCallPhase.Ended]. */
    val durationMs: Long? = null,
    val endReason: AgentVoiceCallEndReason? = null,
    /** True when the server closed the call within 60s of the 15-minute ceiling. */
    val limitReached: Boolean = false,
    val problem: AgentVoiceCallProblem? = null,
    /** Non-null when the microphone stopped sending mid-call (`permission`, `busy`, `none`, or custom). */
    val micBlockedReason: String? = null,
    val muted: Boolean = false,
    val speakerOn: Boolean = true,
    val agentSpeaking: Boolean = false,
    /** At most the last 2 live caption lines. */
    val captions: List<AgentVoiceCaption> = emptyList(),
    /** Incremented when a Call button is tapped while this call is already active, to focus the card. */
    val focusNonce: Long = 0L,
) {
    val isActive: Boolean
        get() = phase != AgentVoiceCallPhase.Ended && phase != AgentVoiceCallPhase.Failed
}

/** Formats a call duration as `m:ss` (and `mm:ss` from 10 minutes), matching web `formatCallClock`. */
@RaftCoreInternalApi
fun formatAgentVoiceCallClock(durationMs: Long): String {
    val seconds = (durationMs / 1000L).coerceAtLeast(0L)
    val minutes = seconds / 60L
    val remSeconds = (seconds % 60L).toString().padStart(2, '0')
    return "$minutes:$remSeconds"
}

/** True when a server-closed call lasted within 60 seconds of [AGENT_VOICE_CALL_MAX_MINUTES]. */
@RaftCoreInternalApi
fun isAgentVoiceCallLimitReached(
    endReason: AgentVoiceCallEndReason?,
    durationMs: Long?,
): Boolean =
    endReason == AgentVoiceCallEndReason.Closed &&
        (durationMs ?: 0L) >= AGENT_VOICE_CALL_MAX_MINUTES * 60_000L - AGENT_VOICE_CALL_LIMIT_MARGIN_MS

/** Maps a native mic error reason string to the typed join-failure [AgentVoiceCallProblem]. */
@RaftCoreInternalApi
fun agentVoiceMicProblemOf(reason: String): AgentVoiceCallProblem =
    when (reason.trim().lowercase()) {
        "permission", "mic_permission", "denied" -> AgentVoiceCallProblem.MicPermission
        "busy", "mic_busy", "in_use" -> AgentVoiceCallProblem.MicBusy
        "none", "mic_none", "unavailable", "disconnected" -> AgentVoiceCallProblem.MicNone
        else -> AgentVoiceCallProblem.MicPermission
    }

/**
 * Parses a caption JSON payload and maintains the bounded last-2-lines buffer for one call.
 *
 * Contract (holt-live / web `captionOf` + `withCaption`):
 * - JSON `{ "type": "caption", "role": "user" | "agent", "text": string, "final": boolean, "id": string }`
 *   (also tolerates an optional `{ "topic": "caption", "data": { ... } }` envelope; any non-`"caption"` topic is ignored).
 * - Late non-final updates for a caption ID that already finalized are ignored (bounded set of 200 IDs).
 * - A final caption with blank text withdraws the existing line for that ID; a non-final blank caption changes nothing.
 * - At most the last 2 lines are retained.
 */
@RaftCoreInternalApi
class AgentVoiceCaptionBuffer(
    private val maxLines: Int = CAPTION_LINES,
    private val maxFinishedIds: Int = FINISHED_CAPTIONS_KEPT,
) {
    private val finishedIds = LinkedHashSet<String>()
    private var lines: List<AgentVoiceCaption> = emptyList()

    val currentLines: List<AgentVoiceCaption>
        get() = lines

    val finishedCount: Int
        get() = finishedIds.size

    fun parseAndApply(jsonText: String): List<AgentVoiceCaption>? {
        val parsed = parseCaptionJson(jsonText) ?: return null
        return apply(parsed)
    }

    fun apply(line: AgentVoiceCaption): List<AgentVoiceCaption>? {
        if (!line.final && line.id in finishedIds) return null
        if (line.final) {
            finishedIds.add(line.id)
            while (finishedIds.size > maxFinishedIds) {
                val oldest = finishedIds.iterator().next()
                finishedIds.remove(oldest)
            }
        }
        val next = withCaption(lines, line, maxLines)
        lines = next
        return next
    }

    fun clear() {
        finishedIds.clear()
        lines = emptyList()
    }

    companion object {
        fun parseCaptionJson(jsonText: String): AgentVoiceCaption? {
            val root = runCatching { captionJson.parseToJsonElement(jsonText) }.getOrNull() as? JsonObject ?: return null
            val payload =
                if ("topic" in root || "data" in root) {
                    val topic = (root["topic"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    if (topic != null && topic != "caption") return null
                    (root["data"] as? JsonObject) ?: return null
                } else {
                    root
                }
            val type = (payload["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (type != "caption") return null
            val role = (payload["role"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val mine =
                when (role) {
                    "user" -> true
                    "agent" -> false
                    else -> return null
                }
            val textPrimitive = payload["text"] as? JsonPrimitive ?: return null
            if (!textPrimitive.isString) return null
            val text = textPrimitive.content
            val finalPrimitive = payload["final"] as? JsonPrimitive ?: return null
            if (finalPrimitive.isString) return null
            val final = finalPrimitive.booleanOrNull ?: return null
            val idPrimitive = payload["id"] as? JsonPrimitive ?: return null
            if (!idPrimitive.isString) return null
            val id = idPrimitive.content
            return AgentVoiceCaption(id = id, mine = mine, text = text, final = final)
        }

        fun withCaption(
            lines: List<AgentVoiceCaption>,
            line: AgentVoiceCaption,
            maxLines: Int = CAPTION_LINES,
        ): List<AgentVoiceCaption> {
            if (line.text.isBlank()) {
                return if (line.final) lines.filterNot { it.id == line.id } else lines
            }
            val index = lines.indexOfFirst { it.id == line.id }
            val updated =
                if (index == -1) {
                    lines + line
                } else {
                    lines.mapIndexed { i, existing -> if (i == index) line else existing }
                }
            return if (updated.size > maxLines) updated.takeLast(maxLines) else updated
        }
    }
}

/**
 * App-wide single-active-call store bridging the Raft voice call HTTP API and the registered
 * platform LiveKit [RaftAgentVoiceCallEngine].
 */
@RaftCoreInternalApi
class SharedAgentVoiceCallStore(
    private val api: AgentVoiceCallApi,
    private val scope: CoroutineScope,
    private val engineProvider: () -> RaftAgentVoiceCallEngine? = RaftAgentVoiceCallEngineRegistry::currentOrNull,
    private val nowEpochMillis: () -> Long = ::raftNowEpochMillis,
    private val tokenRefreshLeadMs: Long = DEFAULT_TOKEN_REFRESH_LEAD_MS,
) {
    private class Session(
        val agent: AgentVoiceCallee,
        val serverId: String,
        val engine: RaftAgentVoiceCallEngine,
        val captions: AgentVoiceCaptionBuffer = AgentVoiceCaptionBuffer(),
        var callId: String? = null,
        var startJob: Job? = null,
        var renewTimerJob: Job? = null,
        var renewInFlightJob: Job? = null,
        var muted: Boolean = false,
        var speakerOn: Boolean = true,
        var connected: Boolean = false,
        var done: Boolean = false,
        var hungUpServer: Boolean = false,
    )

    private val stateLock = SynchronizedObject()
    private var activeSession: Session? = null
    private val callState = MutableStateFlow<AgentVoiceCallState?>(null)

    /** Current call state, or `null` when idle. */
    val call: StateFlow<AgentVoiceCallState?> = callState.asStateFlow()

    /**
     * Starts a voice call to [agent] on [serverId]. If another call is already active, keeps it
     * running and increments [AgentVoiceCallState.focusNonce] so the floating card draws attention.
     */
    fun startCall(agent: AgentVoiceCallee, serverId: String) {
        val cleanServerId = serverId.trim()
        val cleanAgentId = agent.id.trim()
        if (cleanServerId.isEmpty() || cleanAgentId.isEmpty()) return

        val normalizedAgent = agent.copy(id = cleanAgentId, name = agent.name.trim().ifEmpty { cleanAgentId })
        val sessionToStart: Session? =
            synchronized(stateLock) {
                val current = callState.value
                if (current != null && current.isActive) {
                    callState.value = current.copy(focusNonce = current.focusNonce + 1L)
                    return
                }
                val engine = engineProvider()?.takeIf { it.isSupported }
                if (engine == null) {
                    activeSession = null
                    callState.value =
                        AgentVoiceCallState(
                            agent = normalizedAgent,
                            serverId = cleanServerId,
                            phase = AgentVoiceCallPhase.Failed,
                            problem = AgentVoiceCallProblem.Unavailable,
                        )
                    return
                }
                val next = Session(agent = normalizedAgent, serverId = cleanServerId, engine = engine)
                activeSession = next
                callState.value =
                    AgentVoiceCallState(
                        agent = normalizedAgent,
                        serverId = cleanServerId,
                        phase = AgentVoiceCallPhase.Starting,
                    )
                next
            }
        if (sessionToStart != null) {
            beginSession(sessionToStart)
        }
    }

    fun toggleMute() {
        val (engine, nextMuted) =
            synchronized(stateLock) {
                val session = activeSession ?: return
                if (session.done) return
                session.muted = !session.muted
                val current = callState.value
                if (current != null) {
                    callState.value = current.copy(muted = session.muted)
                }
                session.engine to session.muted
            }
        runCatching { engine.setMuted(nextMuted) }
    }

    fun toggleSpeaker() {
        val (engine, nextSpeaker) =
            synchronized(stateLock) {
                val session = activeSession ?: return
                if (session.done) return
                session.speakerOn = !session.speakerOn
                val current = callState.value
                if (current != null) {
                    callState.value = current.copy(speakerOn = session.speakerOn)
                }
                session.engine to session.speakerOn
            }
        runCatching { engine.setSpeakerOn(nextSpeaker) }
    }

    fun hangUp() {
        val (session, dismissBeforeLive) =
            synchronized(stateLock) {
                val session = activeSession ?: return
                val current = callState.value ?: return
                session to (current.connectedAtEpochMs == null)
            }
        finishSession(
            session = session,
            endReason = AgentVoiceCallEndReason.Left,
            problem = null,
            dismiss = dismissBeforeLive,
        )
    }

    fun dismissEnded() {
        synchronized(stateLock) {
            val current = callState.value ?: return
            if (!current.isActive) {
                activeSession = null
                callState.value = null
            }
        }
    }

    fun retry() {
        val target =
            synchronized(stateLock) {
                val current = callState.value ?: return
                if (current.isActive) return
                val pair = current.agent to current.serverId
                activeSession = null
                callState.value = null
                pair
            }
        startCall(target.first, target.second)
    }

    /** Tears down any in-progress call on sign-out and dismisses the card. */
    fun resetOnSignOut() {
        val session = synchronized(stateLock) { activeSession }
        if (session != null) {
            finishSession(session = session, endReason = AgentVoiceCallEndReason.Left, problem = null, dismiss = true)
        } else {
            synchronized(stateLock) { callState.value = null }
        }
    }

    private fun beginSession(session: Session) {
        val job =
            scope.launch {
                val outcome =
                    try {
                        api.startCall(session.agent.id, session.serverId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        AgentVoiceCallStartOutcome.Refused(AgentVoiceCallProblem.Failed)
                    }

                when (outcome) {
                    is AgentVoiceCallStartOutcome.Refused -> {
                        val shouldFinish =
                            synchronized(stateLock) {
                                if (session.done || activeSession !== session) {
                                    false
                                } else {
                                    session.done = true
                                    callState.value =
                                        callState.value?.copy(
                                            phase = AgentVoiceCallPhase.Failed,
                                            problem = outcome.problem,
                                            agentSpeaking = false,
                                            micBlockedReason = null,
                                        )
                                    true
                                }
                            }
                        if (!shouldFinish) return@launch
                    }
                    is AgentVoiceCallStartOutcome.Started -> {
                        val started = outcome.result
                        var hungUpWhileStarting = false
                        var initialMuted = false
                        var initialSpeakerOn = true
                        synchronized(stateLock) {
                            session.callId = started.callId
                            if (session.done || activeSession !== session) {
                                hungUpWhileStarting = true
                            } else {
                                initialMuted = session.muted
                                initialSpeakerOn = session.speakerOn
                                callState.value =
                                    callState.value?.copy(
                                        callId = started.callId,
                                        phase = AgentVoiceCallPhase.Connecting,
                                    )
                            }
                        }
                        if (hungUpWhileStarting) {
                            tellServerHangUpOnce(session)
                            return@launch
                        }
                        scheduleTokenRenewal(session, started.room.expiresAtEpochMs)
                        val listener = createListener(session)
                        val connectFailure =
                            runCatching {
                                session.engine.connect(
                                    callId = started.callId,
                                    url = started.room.url,
                                    token = started.room.token,
                                    muted = initialMuted,
                                    speakerOn = initialSpeakerOn,
                                    listener = listener,
                                )
                            }.exceptionOrNull()
                        if (connectFailure != null) {
                            finishSession(
                                session = session,
                                endReason = null,
                                problem = AgentVoiceCallProblem.Failed,
                                dismiss = false,
                            )
                        }
                    }
                }
            }
        synchronized(stateLock) {
            if (session.done) {
                job.cancel()
            } else {
                session.startJob = job
            }
        }
    }

    private fun createListener(session: Session): RaftAgentVoiceEngineListener =
        object : RaftAgentVoiceEngineListener {
            override fun onConnectionState(state: String) {
                synchronized(stateLock) {
                    if (session.done || activeSession !== session) return
                    val current = callState.value ?: return
                    when (state) {
                        RaftAgentVoiceEngineState.CONNECTING -> {
                            if (current.phase == AgentVoiceCallPhase.Starting) {
                                callState.value = current.copy(phase = AgentVoiceCallPhase.Connecting)
                            }
                        }
                        RaftAgentVoiceEngineState.CONNECTED -> {
                            session.connected = true
                            callState.value =
                                current.copy(
                                    phase = AgentVoiceCallPhase.Live,
                                    connectedAtEpochMs = current.connectedAtEpochMs ?: nowEpochMillis(),
                                )
                        }
                        RaftAgentVoiceEngineState.RECONNECTING -> {
                            callState.value = current.copy(phase = AgentVoiceCallPhase.Reconnecting)
                        }
                        RaftAgentVoiceEngineState.DISCONNECTED -> {
                            // Authoritative end reason arrives in onDisconnected(reason, detail).
                        }
                    }
                }
            }

            override fun onAgentSpeaking(speaking: Boolean) {
                synchronized(stateLock) {
                    if (session.done || activeSession !== session) return
                    val current = callState.value ?: return
                    if (current.agentSpeaking != speaking) {
                        callState.value = current.copy(agentSpeaking = speaking)
                    }
                }
            }

            override fun onCaptionData(jsonText: String) {
                synchronized(stateLock) {
                    if (session.done || activeSession !== session) return
                    val nextCaptions = session.captions.parseAndApply(jsonText) ?: return
                    val current = callState.value ?: return
                    callState.value = current.copy(captions = nextCaptions)
                }
            }

            override fun onMicError(reason: String) {
                val trimmed = reason.trim()
                val failAtJoin =
                    synchronized(stateLock) {
                        if (session.done || activeSession !== session) return
                        val current = callState.value ?: return
                        if (trimmed.isEmpty()) {
                            callState.value = current.copy(micBlockedReason = null)
                            false
                        } else if (current.connectedAtEpochMs == null && !session.connected) {
                            true
                        } else {
                            callState.value = current.copy(micBlockedReason = trimmed)
                            false
                        }
                    }
                if (failAtJoin) {
                    finishSession(
                        session = session,
                        endReason = null,
                        problem = agentVoiceMicProblemOf(trimmed),
                        dismiss = false,
                    )
                }
            }

            override fun onDisconnected(reason: String, detail: String?) {
                val (endReason, problem) =
                    when (reason) {
                        RaftAgentVoiceDisconnectReason.CLIENT_INITIATED -> AgentVoiceCallEndReason.Left to null
                        RaftAgentVoiceDisconnectReason.SERVER_CLOSED -> AgentVoiceCallEndReason.Closed to null
                        else -> {
                            val wasLive = synchronized(stateLock) { callState.value?.connectedAtEpochMs != null }
                            if (wasLive) {
                                AgentVoiceCallEndReason.Lost to null
                            } else {
                                null to AgentVoiceCallProblem.Failed
                            }
                        }
                    }
                finishSession(
                    session = session,
                    endReason = endReason,
                    problem = problem,
                    dismiss = false,
                )
            }

            override fun onTokenRefreshNeeded() {
                triggerTokenRenewal(session)
            }
        }

    private fun scheduleTokenRenewal(session: Session, expiresAtEpochMs: Long) {
        val now = nowEpochMillis()
        val remaining = expiresAtEpochMs - now
        val delayMs =
            if (remaining > tokenRefreshLeadMs) {
                remaining - tokenRefreshLeadMs
            } else {
                (remaining / 2L).coerceAtLeast(MIN_TOKEN_REFRESH_DELAY_MS)
            }
        val timerJob =
            scope.launch {
                delay(delayMs)
                triggerTokenRenewal(session)
            }
        synchronized(stateLock) {
            session.renewTimerJob?.cancel()
            if (session.done || activeSession !== session) {
                timerJob.cancel()
            } else {
                session.renewTimerJob = timerJob
            }
        }
    }

    private fun triggerTokenRenewal(session: Session) {
        val callId =
            synchronized(stateLock) {
                if (session.done || activeSession !== session) return
                if (session.renewInFlightJob?.isActive == true) return
                session.callId ?: return
            }
        val renewJob =
            scope.launch {
                val renewed =
                    try {
                        api.renewToken(session.agent.id, callId, session.serverId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        null
                    }
                val stillActive = synchronized(stateLock) { !session.done && activeSession === session }
                if (!stillActive) return@launch
                if (renewed != null) {
                    runCatching { session.engine.updateToken(renewed.room.token) }
                    scheduleTokenRenewal(session, renewed.room.expiresAtEpochMs)
                } else {
                    val retryJob =
                        scope.launch {
                            delay(TOKEN_REFRESH_RETRY_DELAY_MS)
                            triggerTokenRenewal(session)
                        }
                    synchronized(stateLock) {
                        session.renewTimerJob?.cancel()
                        if (session.done || activeSession !== session) {
                            retryJob.cancel()
                        } else {
                            session.renewTimerJob = retryJob
                        }
                    }
                }
            }
        synchronized(stateLock) {
            if (session.done || activeSession !== session) {
                renewJob.cancel()
            } else {
                session.renewInFlightJob = renewJob
            }
        }
    }

    private fun finishSession(
        session: Session,
        endReason: AgentVoiceCallEndReason?,
        problem: AgentVoiceCallProblem?,
        dismiss: Boolean,
    ) {
        val (shouldDisconnect, shouldNotifyServer) =
            synchronized(stateLock) {
                if (session.done) return
                session.done = true
                // Do not cancel startJob: if POST /voice/calls is in flight and mints a callId, let it
                // settle so tellServerHangUpOnce can close the server-side room cleanly (web parity).
                session.renewTimerJob?.cancel()
                session.renewInFlightJob?.cancel()
                if (dismiss) {
                    if (activeSession === session) {
                        activeSession = null
                        callState.value = null
                    }
                } else if (activeSession === session) {
                    val current = callState.value
                    if (current != null) {
                        val liveSince = current.connectedAtEpochMs
                        if (liveSince != null && problem == null) {
                            val duration = (nowEpochMillis() - liveSince).coerceAtLeast(0L)
                            callState.value =
                                current.copy(
                                    phase = AgentVoiceCallPhase.Ended,
                                    durationMs = duration,
                                    endReason = endReason,
                                    limitReached = isAgentVoiceCallLimitReached(endReason, duration),
                                    problem = null,
                                    micBlockedReason = null,
                                    agentSpeaking = false,
                                )
                        } else {
                            callState.value =
                                current.copy(
                                    phase = AgentVoiceCallPhase.Failed,
                                    endReason = endReason,
                                    limitReached = false,
                                    problem = problem ?: AgentVoiceCallProblem.Failed,
                                    micBlockedReason = null,
                                    agentSpeaking = false,
                                )
                        }
                    }
                }
                true to (endReason != AgentVoiceCallEndReason.Closed)
            }
        if (shouldDisconnect) {
            runCatching { session.engine.disconnect() }
        }
        if (shouldNotifyServer) {
            tellServerHangUpOnce(session)
        }
    }

    private fun tellServerHangUpOnce(session: Session) {
        val callId =
            synchronized(stateLock) {
                if (session.hungUpServer) return
                val id = session.callId ?: return
                session.hungUpServer = true
                id
            }
        scope.launch {
            runCatching { api.hangUp(session.agent.id, callId, session.serverId) }
        }
    }
}
