package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.data.realtime.RaftRealtimeConnectionCauses
import build.raft.app.model.AgentWorkersChanged
import build.raft.app.model.MemberId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One realtime `agent:workers:changed` nudge on [serverId]. */
@RaftCoreInternalApi data class RaftAgentWorkersChangedSignal(
    val serverId: String,
    val agentId: MemberId,
    val digest: String,
    val receivedAt: String,
)

/**
 * Process-wide `agent:workers:changed` nudges for open Agent Detail › Workers panels.
 * Mirrors [RaftAgentUpdatedSignals]: never suspends the publisher, drops oldest on burst.
 */
@RaftCoreInternalApi class RaftAgentWorkersChangedSignals {
    private val _events =
        MutableSharedFlow<RaftAgentWorkersChangedSignal>(
            extraBufferCapacity = 16,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val events: SharedFlow<RaftAgentWorkersChangedSignal> = _events.asSharedFlow()

    fun publish(serverId: String, event: AgentWorkersChanged) {
        val cleanServer = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val cleanAgent = event.agentId.value.trim().takeIf { it.isNotEmpty() } ?: return
        val cleanDigest = event.digest.trim().takeIf { it.isNotEmpty() } ?: return
        val cleanReceivedAt = event.receivedAt.trim().takeIf { it.isNotEmpty() } ?: return
        _events.tryEmit(
            RaftAgentWorkersChangedSignal(
                serverId = cleanServer,
                agentId = MemberId(cleanAgent),
                digest = cleanDigest,
                receivedAt = cleanReceivedAt,
            )
        )
    }
}

@RaftCoreInternalApi enum class AgentWorkersLoadStatus {
    Loading,
    Unknown,
    Known,
}

/**
 * One short line of feedback for a stop-this-Worker request; mirrors web `stopWorkerWithFeedback`
 * (`packages/web/src/components/agent/agentWorkers.ts`).
 */
@RaftCoreInternalApi sealed interface AgentWorkerStopFeedback {
    data class Stopped(val worker: String, val objectRef: String) : AgentWorkerStopFeedback
    data class NotRunning(val worker: String) : AgentWorkerStopFeedback
    data class ComputerOffline(val error: ScreenError) : AgentWorkerStopFeedback
    data class SnapshotStale(val error: ScreenError) : AgentWorkerStopFeedback
    data class UpgradeRequired(val error: ScreenError) : AgentWorkerStopFeedback
    data class Failed(val worker: String, val objectRef: String, val error: ScreenError?) : AgentWorkerStopFeedback
}

@RaftCoreInternalApi fun DataResult<AgentWorkerStopOutcome>.toAgentWorkerStopFeedback(
    worker: String,
    objectRef: String,
): AgentWorkerStopFeedback =
    when (this) {
        is DataResult.Content ->
            when (data) {
                AgentWorkerStopOutcome.Stopped -> AgentWorkerStopFeedback.Stopped(worker = worker, objectRef = objectRef)
                AgentWorkerStopOutcome.NotFound -> AgentWorkerStopFeedback.NotRunning(worker = worker)
            }
        is DataResult.Error ->
            when (error.serverCode) {
                AgentControlErrorCodes.ComputerOffline -> AgentWorkerStopFeedback.ComputerOffline(error)
                AgentControlErrorCodes.WorkersSnapshotStale -> AgentWorkerStopFeedback.SnapshotStale(error)
                AgentControlErrorCodes.ComputerUpgradeRequired -> AgentWorkerStopFeedback.UpgradeRequired(error)
                else -> AgentWorkerStopFeedback.Failed(worker = worker, objectRef = objectRef, error = error)
            }
        is DataResult.Empty -> AgentWorkerStopFeedback.Failed(worker = worker, objectRef = objectRef, error = null)
        is DataResult.NoPermission ->
            AgentWorkerStopFeedback.Failed(
                worker = worker,
                objectRef = objectRef,
                error =
                    ScreenError(
                        code = blocker.code,
                        retryable = false,
                        serverMessage = blocker.serverMessage,
                        serverCode = blocker.serverCode,
                        traceId = blocker.traceId,
                    ),
            )
    }

@RaftCoreInternalApi data class AgentWorkersState(
    val agentId: MemberId? = null,
    val enabled: Boolean = false,
    val status: AgentWorkersLoadStatus = AgentWorkersLoadStatus.Loading,
    /** Live rows from the latest read, excluding Workers already stopped locally since that read. */
    val workers: List<AgentWorkerRow> = emptyList(),
    /** True only during an explicit pull-to-refresh on a loaded/unknown panel. */
    val refreshing: Boolean = false,
    /** Wall clock when the latest read landed (`readAtMs` on web; moves `nowMillis` with every read). */
    val readAtMillis: Long = 0L,
    /** Server snapshot receive time projected onto this device's wall clock (`startedAt - ageMs`). */
    val receivedAtMillis: Long? = null,
    /** Server's own `stale` verdict from the latest read. */
    val serverStale: Boolean = false,
    /** Current wall clock as of the latest read or freshness tick (drives ages and local staleness). */
    val nowMillis: Long = 0L,
    /** Worker name whose Stop request is currently in flight (single-flight). */
    val stoppingWorker: String? = null,
    /** One-shot stop feedback for [feedbackAgentId]. */
    val feedback: AgentWorkerStopFeedback? = null,
    val feedbackAgentId: MemberId? = null,
) {
    /**
     * Exact web parity (`agentWorkers.ts`): stale is the server's `stale` from the latest read,
     * held until newer data arrives, OR (if reads stopped answering) rows older here than
     * `WORKERS_SNAPSHOT_STALE_MS + WORKERS_PANEL_REREAD_MS` (60 s).
     */
    val stale: Boolean
        get() =
            status == AgentWorkersLoadStatus.Known &&
                (
                    serverStale ||
                        (
                            receivedAtMillis != null &&
                                nowMillis - receivedAtMillis > AGENT_WORKERS_SNAPSHOT_STALE_MS + AGENT_WORKERS_PANEL_REREAD_MS
                            )
                    )
}

@RaftCoreInternalApi sealed interface AgentWorkersIntent {
    /** Bind the store to an agent + server with the evaluated `worker_v1` gate. */
    data class Bind(val serverId: String?, val agentId: MemberId, val enabled: Boolean) : AgentWorkersIntent

    /** Whether the Activity › Workers segment is currently selected on screen. */
    data class SetPanelOpen(val open: Boolean) : AgentWorkersIntent

    /** Whether the enclosing Agent Detail page is currently visible on screen (not covered). */
    data class SetPageVisible(val visible: Boolean) : AgentWorkersIntent

    /** Explicit refresh (pull-to-refresh or manual retry). */
    data object Refresh : AgentWorkersIntent

    /** Confirmed Stop of one Worker (`POST /api/agents/:id/workers/:worker/stop`). */
    data class Stop(val agentId: MemberId, val worker: String, val objectRef: String) : AgentWorkersIntent

    /** Clears one-shot stop feedback after the UI presents its toast. */
    data object ConsumeFeedback : AgentWorkersIntent
}

/**
 * MVI store for Agent Detail › Activity › Workers (web `useAgentWorkers` + `stopWorkerWithFeedback`,
 * `docs/contracts/worker-snapshot.md`).
 *
 * Confinement: [dispatch] and all launched jobs run on [scope]'s dispatcher.
 * - Flag off (`enabled = false`): reads nothing, listens to nothing, runs no timers.
 * - Agent or flag change: clears all previous agent data, digest, and pending reads.
 * - Realtime `agent:workers:changed`: re-reads only when `agentId` matches and `digest != lastDigest`.
 * - Store-owned freshness ([RaftFreshnessGate] with `trackOutcome = true`): catches up on
 *   foreground, socket reconnect, and network recovery.
 * - While open on screen with live rows: re-reads every [rereadIntervalMillis] (30 s) using
 *   [repeatWhileProcessForeground]; ticks [freshnessTickIntervalMillis] (5 s) to keep ages honest.
 * - After a successful Stop (`stopped` or `not_found`): removes the row immediately and schedules
 *   a re-read after [afterStopReadDelayMillis] (3 s).
 */
@RaftCoreInternalApi class AgentWorkersStore(
    private val listWorkers: suspend (MemberId, DataRequestPriority) -> DataResult<AgentWorkersReadSnapshot>,
    private val stopWorker: suspend (MemberId, String) -> DataResult<AgentWorkerStopOutcome>,
    private val scope: CoroutineScope,
    private val workersChangedSignals: Flow<RaftAgentWorkersChangedSignal> = emptyFlow(),
    private val freshness: Flow<RaftFreshnessEvent> = RaftFreshnessSignal.events,
    private val foreground: StateFlow<Boolean> = RaftProcessForegroundGate.foreground,
    private val wallNowMillis: () -> Long = ::defaultNowMillis,
    private val monotonicNowMillis: () -> Long = ::raftForegroundGateNowMillis,
    private val liveConnectionToken: (serverId: String) -> Long? = RaftRealtimeConnectionCauses::liveConnectionToken,
    private val rereadIntervalMillis: Long = AGENT_WORKERS_PANEL_REREAD_MS,
    private val freshnessTickIntervalMillis: Long = AGENT_WORKERS_FRESHNESS_TICK_MS,
    private val afterStopReadDelayMillis: Long = AGENT_WORKERS_AFTER_STOP_READ_MS,
) {
    private val _state = MutableStateFlow(AgentWorkersState())
    val state: StateFlow<AgentWorkersState> = _state.asStateFlow()

    private var boundServerId: String? = null
    private var boundAgentId: MemberId? = null
    private var boundEnabled: Boolean = false
    private var panelOpen: Boolean = false
    private var pageVisible: Boolean = true
    private var readSeq: Long = 0L
    private var lastDigest: String? = null
    private var rawWorkers: List<AgentWorkerRow> = emptyList()
    private var stoppedLocally: Set<String> = emptySet()

    private var readJob: Job? = null
    private var afterStopJob: Job? = null
    private var rereadPollJob: Job? = null
    private var freshnessTickJob: Job? = null
    private var freshnessCatchUpInFlight: Boolean = false
    private val freshnessGate =
        RaftFreshnessGate(
            nowMillis = monotonicNowMillis,
            trackOutcome = true,
            liveConnectionToken = liveConnectionToken,
        )

    init {
        scope.launch {
            workersChangedSignals.collect { signal ->
                onWorkersChangedSignal(signal)
            }
        }
        scope.launch {
            freshness.collect { event ->
                onFreshnessEvent(event)
            }
        }
    }

    fun dispatch(intent: AgentWorkersIntent) {
        when (intent) {
            is AgentWorkersIntent.Bind -> bind(intent.serverId, intent.agentId, intent.enabled)
            is AgentWorkersIntent.SetPanelOpen -> setPanelOpen(intent.open)
            is AgentWorkersIntent.SetPageVisible -> setPageVisible(intent.visible)
            AgentWorkersIntent.Refresh -> {
                if (!boundEnabled || boundAgentId == null) return
                _state.update {
                    if (it.status != AgentWorkersLoadStatus.Loading) it.copy(refreshing = true) else it
                }
                triggerRead(DataRequestPriority.Visible)
            }
            is AgentWorkersIntent.Stop -> stop(intent.agentId, intent.worker, intent.objectRef)
            AgentWorkersIntent.ConsumeFeedback -> {
                val current = _state.value
                if (current.feedback != null) {
                    _state.value = current.copy(feedback = null, feedbackAgentId = null)
                }
            }
        }
    }

    private fun bind(serverId: String?, agentId: MemberId, enabled: Boolean) {
        val cleanServer = serverId?.trim()?.takeIf { it.isNotEmpty() }
        if (boundServerId == cleanServer && boundAgentId == agentId && boundEnabled == enabled) {
            return
        }
        boundServerId = cleanServer
        boundAgentId = agentId
        boundEnabled = enabled
        resetForNewBinding(agentId, enabled)
        if (enabled) {
            triggerRead(DataRequestPriority.Visible)
            reconcilePeriodicJobs()
        }
    }

    private fun resetForNewBinding(agentId: MemberId, enabled: Boolean) {
        readSeq += 1L
        lastDigest = null
        rawWorkers = emptyList()
        stoppedLocally = emptySet()
        freshnessCatchUpInFlight = false
        freshnessGate.reset()
        readJob?.cancel()
        readJob = null
        afterStopJob?.cancel()
        afterStopJob = null
        rereadPollJob?.cancel()
        rereadPollJob = null
        freshnessTickJob?.cancel()
        freshnessTickJob = null
        val now = if (enabled) wallNowMillis() else 0L
        _state.value =
            AgentWorkersState(
                agentId = agentId,
                enabled = enabled,
                status = AgentWorkersLoadStatus.Loading,
                nowMillis = now,
            )
    }

    private fun setPanelOpen(open: Boolean) {
        if (panelOpen == open) return
        panelOpen = open
        if (open && boundEnabled && boundAgentId != null) {
            // Web AgentActivityLog `changeView`: opening the Workers view reads at once.
            triggerRead(DataRequestPriority.Visible)
        }
        reconcilePeriodicJobs()
    }

    private fun setPageVisible(visible: Boolean) {
        val previous = pageVisible
        pageVisible = visible
        if (visible && !previous && boundEnabled && boundAgentId != null) {
            freshnessGate.onVisibilityChanged(visible = true, serverId = boundServerId)?.let {
                freshnessCatchUpInFlight = true
                triggerRead(DataRequestPriority.Background)
            }
        }
        reconcilePeriodicJobs()
    }

    private fun onWorkersChangedSignal(signal: RaftAgentWorkersChangedSignal) {
        if (!boundEnabled) return
        val agentId = boundAgentId ?: return
        if (signal.agentId != agentId) return
        if (boundServerId != null && signal.serverId != boundServerId) return
        if (signal.digest == lastDigest) return
        triggerRead(DataRequestPriority.Background)
    }

    private fun onFreshnessEvent(event: RaftFreshnessEvent) {
        if (!boundEnabled || boundAgentId == null) return
        val trigger = freshnessGate.onEvent(event, boundServerId, visible = pageVisible) ?: return
        if (trigger == RaftFreshnessTrigger.Reconnect ||
            trigger == RaftFreshnessTrigger.Foreground ||
            trigger == RaftFreshnessTrigger.NetworkRestored
        ) {
            freshnessCatchUpInFlight = true
            triggerRead(DataRequestPriority.Background)
        }
    }

    private fun triggerRead(priority: DataRequestPriority) {
        val agentId = boundAgentId ?: return
        if (!boundEnabled) return
        val seq = ++readSeq
        val startedAtMillis = wallNowMillis()
        readJob?.cancel()
        readJob =
            scope.launch {
                val result =
                    try {
                        listWorkers(agentId, priority)
                    } catch (cancellation: CancellationException) {
                        if (seq == readSeq && freshnessCatchUpInFlight) {
                            freshnessCatchUpInFlight = false
                            freshnessGate.onCatchUpCompleted(succeeded = false, visible = false)
                        }
                        throw cancellation
                    } catch (_: Throwable) {
                        DataResult.Error(ScreenError(AgentControlErrorCodes.NetworkError))
                    }
                if (seq != readSeq || !boundEnabled || boundAgentId != agentId) return@launch
                val completedAtMillis = wallNowMillis()
                val succeeded = result is DataResult.Content
                when (result) {
                    is DataResult.Content ->
                        when (val snapshot = result.data) {
                            is AgentWorkersReadSnapshot.Known -> {
                                lastDigest = snapshot.digest
                                rawWorkers = snapshot.workers
                                stoppedLocally = emptySet()
                                val receivedAtMillis = startedAtMillis - snapshot.ageMs.coerceAtLeast(0L)
                                _state.update {
                                    it.copy(
                                        agentId = agentId,
                                        enabled = true,
                                        status = AgentWorkersLoadStatus.Known,
                                        workers = rawWorkers,
                                        refreshing = false,
                                        readAtMillis = completedAtMillis,
                                        receivedAtMillis = receivedAtMillis,
                                        serverStale = snapshot.stale,
                                        nowMillis = maxOf(it.nowMillis, completedAtMillis),
                                    )
                                }
                            }
                            AgentWorkersReadSnapshot.Unknown -> {
                                lastDigest = null
                                rawWorkers = emptyList()
                                stoppedLocally = emptySet()
                                _state.update {
                                    it.copy(
                                        agentId = agentId,
                                        enabled = true,
                                        status = AgentWorkersLoadStatus.Unknown,
                                        workers = emptyList(),
                                        refreshing = false,
                                        readAtMillis = completedAtMillis,
                                        receivedAtMillis = null,
                                        serverStale = false,
                                        nowMillis = maxOf(it.nowMillis, completedAtMillis),
                                    )
                                }
                            }
                        }
                    is DataResult.Empty,
                    is DataResult.Error,
                    is DataResult.NoPermission -> {
                        // Web `useAgentWorkers`: a read failure shows Unknown (never crashes or hides sub-agents).
                        lastDigest = null
                        rawWorkers = emptyList()
                        stoppedLocally = emptySet()
                        _state.update {
                            it.copy(
                                agentId = agentId,
                                enabled = true,
                                status = AgentWorkersLoadStatus.Unknown,
                                workers = emptyList(),
                                refreshing = false,
                                readAtMillis = completedAtMillis,
                                receivedAtMillis = null,
                                serverStale = false,
                                nowMillis = maxOf(it.nowMillis, completedAtMillis),
                            )
                        }
                    }
                }
                if (freshnessCatchUpInFlight) {
                    freshnessCatchUpInFlight = false
                    val nextTrigger = freshnessGate.onCatchUpCompleted(succeeded = succeeded, visible = pageVisible)
                    if (nextTrigger != null && boundEnabled && boundAgentId == agentId) {
                        freshnessCatchUpInFlight = true
                        triggerRead(DataRequestPriority.Background)
                        return@launch
                    }
                }
                reconcilePeriodicJobs()
            }
    }

    private fun stop(agentId: MemberId, worker: String, objectRef: String) {
        if (!boundEnabled || boundAgentId != agentId) return
        if (_state.value.stoppingWorker != null) return
        val cleanWorker = worker.trim()
        if (!AGENT_WORKER_NAME_PATTERN.matches(cleanWorker)) return
        _state.update { it.copy(stoppingWorker = cleanWorker, feedback = null, feedbackAgentId = null) }
        scope.launch {
            val result =
                try {
                    stopWorker(agentId, cleanWorker)
                } catch (cancellation: CancellationException) {
                    _state.update { it.copy(stoppingWorker = null) }
                    throw cancellation
                } catch (_: Throwable) {
                    DataResult.Error(ScreenError(AgentControlErrorCodes.NetworkError, traceId = null))
                }
            val feedback = result.toAgentWorkerStopFeedback(worker = cleanWorker, objectRef = objectRef)
            val stopped = feedback is AgentWorkerStopFeedback.Stopped || feedback is AgentWorkerStopFeedback.NotRunning
            if (stopped && boundEnabled && boundAgentId == agentId) {
                stoppedLocally = stoppedLocally + cleanWorker
                val visibleWorkers = rawWorkers.filterNot { it.worker in stoppedLocally }
                _state.update {
                    it.copy(
                        workers = visibleWorkers,
                        stoppingWorker = null,
                        feedback = feedback,
                        feedbackAgentId = agentId,
                    )
                }
                scheduleAfterStopRead(agentId)
                reconcilePeriodicJobs()
            } else {
                _state.update {
                    it.copy(
                        stoppingWorker = null,
                        feedback = feedback,
                        feedbackAgentId = agentId,
                    )
                }
            }
        }
    }

    private fun scheduleAfterStopRead(agentId: MemberId) {
        afterStopJob?.cancel()
        afterStopJob =
            scope.launch {
                delay(afterStopReadDelayMillis)
                if (boundEnabled && boundAgentId == agentId) {
                    triggerRead(DataRequestPriority.Background)
                }
            }
    }

    private fun reconcilePeriodicJobs() {
        val current = _state.value
        val shouldTick = boundEnabled && panelOpen && pageVisible && boundAgentId != null
        val shouldPoll = shouldTick && current.status == AgentWorkersLoadStatus.Known && rawWorkers.isNotEmpty()
        if (shouldPoll) {
            if (rereadPollJob?.isActive != true) {
                rereadPollJob =
                    scope.launch {
                        repeatWhileProcessForeground(
                            intervalMillis = rereadIntervalMillis,
                            foreground = foreground,
                            nowMillis = monotonicNowMillis,
                        ) {
                            if (boundEnabled && panelOpen && pageVisible && boundAgentId != null) {
                                triggerRead(DataRequestPriority.Background)
                            }
                        }
                    }
            }
        } else {
            rereadPollJob?.cancel()
            rereadPollJob = null
        }
        if (shouldTick) {
            if (freshnessTickJob?.isActive != true) {
                freshnessTickJob =
                    scope.launch {
                        repeatWhileProcessForeground(
                            intervalMillis = freshnessTickIntervalMillis,
                            foreground = foreground,
                            nowMillis = monotonicNowMillis,
                        ) {
                            if (boundEnabled && panelOpen && pageVisible) {
                                val now = wallNowMillis()
                                _state.update { it.copy(nowMillis = maxOf(now, it.readAtMillis)) }
                            }
                        }
                    }
            }
        } else {
            freshnessTickJob?.cancel()
            freshnessTickJob = null
        }
    }
}
