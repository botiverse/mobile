package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.model.MemberId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@RaftCoreInternalApi enum class AgentWorkerTimelineLoadStatus {
    Idle,
    Loading,
    Loaded,
    Unavailable,
    Error,
}

@RaftCoreInternalApi data class AgentWorkerTimelineState(
    val agentId: MemberId? = null,
    val open: AgentWorkerOpenTimeline? = null,
    val status: AgentWorkerTimelineLoadStatus = AgentWorkerTimelineLoadStatus.Idle,
    val events: List<AgentWorkerTimelineEvent> = emptyList(),
    val refreshing: Boolean = false,
    val error: ScreenError? = null,
)

@RaftCoreInternalApi sealed interface AgentWorkerTimelineIntent {
    /** Open one launch's timeline (`GET /api/agents/:id/workers/:launchId/timeline`). */
    data class Open(val agentId: MemberId, val open: AgentWorkerOpenTimeline) : AgentWorkerTimelineIntent

    /** Close the open timeline and cancel any in-flight read. */
    data object Close : AgentWorkerTimelineIntent

    /**
     * Web `useWorkersPanelTimeline` `refreshKey`: re-reads when the open launch's live row moves
     * on, disappears, or its end becomes known, keeping shown events until new ones land.
     */
    data class SyncRefreshKey(val refreshKey: String) : AgentWorkerTimelineIntent

    /** Manual pull-to-refresh or Retry tap. */
    data object Refresh : AgentWorkerTimelineIntent
}

/**
 * MVI store for one Worker launch's timeline (`GET /api/agents/:id/workers/:launchId/timeline`,
 * web `useWorkerTimeline` in `packages/web/src/components/agent/agentWorkers.ts`).
 *
 * - Keeps already-loaded events visible across live re-reads and refresh blips (matching web).
 * - Maps 404 (`worker_timeline_not_found`, `worker_v1_off`), 403, and malformed launch ids to
 *   [AgentWorkerTimelineLoadStatus.Unavailable].
 * - Preserves server/network errors with their [ScreenError.traceId] on initial load failure so
 *   the UI can offer Retry and `RaftTraceIdNote`.
 */
@RaftCoreInternalApi class AgentWorkerTimelineStore(
    private val getTimeline: suspend (MemberId, String, DataRequestPriority) -> DataResult<AgentWorkerTimelineSnapshot>,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AgentWorkerTimelineState())
    val state: StateFlow<AgentWorkerTimelineState> = _state.asStateFlow()

    private var readSeq: Long = 0L
    private var readJob: Job? = null
    private var lastRefreshKey: String? = null

    fun dispatch(intent: AgentWorkerTimelineIntent) {
        when (intent) {
            is AgentWorkerTimelineIntent.Open -> open(intent.agentId, intent.open)
            AgentWorkerTimelineIntent.Close -> close()
            is AgentWorkerTimelineIntent.SyncRefreshKey -> syncRefreshKey(intent.refreshKey)
            AgentWorkerTimelineIntent.Refresh -> refresh()
        }
    }

    private fun open(agentId: MemberId, target: AgentWorkerOpenTimeline) {
        val current = _state.value
        if (current.agentId == agentId && current.open?.launchId == target.launchId &&
            current.status != AgentWorkerTimelineLoadStatus.Idle
        ) {
            if (current.open != target) {
                _state.update { it.copy(open = target) }
            }
            return
        }
        readJob?.cancel()
        readJob = null
        readSeq += 1L
        lastRefreshKey = null
        val cleanLaunchId = target.launchId.trim()
        if (!AGENT_WORKER_LAUNCH_ID_PATTERN.matches(cleanLaunchId)) {
            _state.value =
                AgentWorkerTimelineState(
                    agentId = agentId,
                    open = target,
                    status = AgentWorkerTimelineLoadStatus.Unavailable,
                )
            return
        }
        _state.value =
            AgentWorkerTimelineState(
                agentId = agentId,
                open = target.copy(launchId = cleanLaunchId),
                status = AgentWorkerTimelineLoadStatus.Loading,
            )
        triggerRead(agentId, cleanLaunchId, DataRequestPriority.Visible, showRefreshing = false)
    }

    private fun close() {
        readJob?.cancel()
        readJob = null
        readSeq += 1L
        lastRefreshKey = null
        _state.value = AgentWorkerTimelineState()
    }

    private fun syncRefreshKey(refreshKey: String) {
        val current = _state.value
        val agentId = current.agentId ?: return
        val launchId = current.open?.launchId ?: return
        if (current.status == AgentWorkerTimelineLoadStatus.Idle) return
        if (lastRefreshKey == null) {
            lastRefreshKey = refreshKey
            return
        }
        if (refreshKey == lastRefreshKey) return
        lastRefreshKey = refreshKey
        if (!AGENT_WORKER_LAUNCH_ID_PATTERN.matches(launchId)) return
        triggerRead(agentId, launchId, DataRequestPriority.Background, showRefreshing = false)
    }

    private fun refresh() {
        val current = _state.value
        val agentId = current.agentId ?: return
        val launchId = current.open?.launchId ?: return
        if (current.status == AgentWorkerTimelineLoadStatus.Idle || current.status == AgentWorkerTimelineLoadStatus.Loading) {
            return
        }
        if (!AGENT_WORKER_LAUNCH_ID_PATTERN.matches(launchId)) {
            _state.update { it.copy(status = AgentWorkerTimelineLoadStatus.Unavailable, refreshing = false, error = null) }
            return
        }
        val showRefreshing = current.status == AgentWorkerTimelineLoadStatus.Loaded
        if (showRefreshing) {
            _state.update { it.copy(refreshing = true) }
        } else {
            _state.update { it.copy(status = AgentWorkerTimelineLoadStatus.Loading, error = null, refreshing = false) }
        }
        triggerRead(agentId, launchId, DataRequestPriority.Visible, showRefreshing = showRefreshing)
    }

    private fun triggerRead(
        agentId: MemberId,
        launchId: String,
        priority: DataRequestPriority,
        showRefreshing: Boolean,
    ) {
        val seq = ++readSeq
        readJob?.cancel()
        readJob =
            scope.launch {
                val result =
                    try {
                        getTimeline(agentId, launchId, priority)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        DataResult.Error(ScreenError(AgentControlErrorCodes.NetworkError, traceId = null))
                    }
                if (seq != readSeq) return@launch
                val current = _state.value
                if (current.agentId != agentId || current.open?.launchId != launchId) return@launch
                when (result) {
                    is DataResult.Content -> {
                        _state.update {
                            it.copy(
                                status = AgentWorkerTimelineLoadStatus.Loaded,
                                events = result.data.events,
                                refreshing = false,
                                error = null,
                            )
                        }
                    }
                    is DataResult.Empty,
                    is DataResult.NoPermission -> {
                        if (current.status == AgentWorkerTimelineLoadStatus.Loaded) {
                            if (showRefreshing || current.refreshing) {
                                _state.update { it.copy(refreshing = false) }
                            }
                        } else {
                            _state.update {
                                it.copy(
                                    status = AgentWorkerTimelineLoadStatus.Unavailable,
                                    events = emptyList(),
                                    refreshing = false,
                                    error = null,
                                )
                            }
                        }
                    }
                    is DataResult.Error -> {
                        if (current.status == AgentWorkerTimelineLoadStatus.Loaded) {
                            if (showRefreshing || current.refreshing) {
                                _state.update { it.copy(refreshing = false) }
                            }
                        } else if (result.error.isWorkerTimelineUnavailable()) {
                            _state.update {
                                it.copy(
                                    status = AgentWorkerTimelineLoadStatus.Unavailable,
                                    events = emptyList(),
                                    refreshing = false,
                                    error = null,
                                )
                            }
                        } else {
                            _state.update {
                                it.copy(
                                    status = AgentWorkerTimelineLoadStatus.Error,
                                    events = emptyList(),
                                    refreshing = false,
                                    error = result.error,
                                )
                            }
                        }
                    }
                }
            }
    }
}

private fun ScreenError.isWorkerTimelineUnavailable(): Boolean =
    serverCode == AgentControlErrorCodes.WorkerTimelineNotFound ||
        serverCode == AgentControlErrorCodes.WorkerV1Off ||
        code == AgentControlErrorCodes.WorkerTimelineNotFound ||
        code == AgentControlErrorCodes.WorkerV1Off ||
        code == "http_404" ||
        code == "http_403"
