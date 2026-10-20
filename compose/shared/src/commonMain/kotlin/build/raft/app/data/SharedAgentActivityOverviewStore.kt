package build.raft.app.data

import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AgentActivityOverviewStatus {
    data object Idle : AgentActivityOverviewStatus
    data object Loading : AgentActivityOverviewStatus
    data object Loaded : AgentActivityOverviewStatus

    /** The first load failed; a failed reload after a success keeps [Loaded] (web parity). */
    data class Error(val code: String) : AgentActivityOverviewStatus
}

sealed interface AgentActivityReplyState {
    data object Loading : AgentActivityReplyState
    data class Ready(val reply: AgentActivityTurnReply) : AgentActivityReplyState
}

data class AgentActivityOverviewState(
    val agentId: MemberId? = null,
    /** Newest first, as the server pages them. */
    val turns: List<AgentActivityTurn> = emptyList(),
    val next: String? = null,
    val status: AgentActivityOverviewStatus = AgentActivityOverviewStatus.Idle,
    val loadingMore: Boolean = false,
    val replies: Map<MessageId, AgentActivityReplyState> = emptyMap(),
)

sealed interface AgentActivityOverviewIntent {
    /** The overview is on screen for [agentId]; loads the latest page once per agent. */
    data class Open(val agentId: MemberId) : AgentActivityOverviewIntent

    /** Retry or pull-to-refresh: reload the latest page now. */
    data object Refresh : AgentActivityOverviewIntent

    /**
     * The agent's live activity moved (a finished turn arrives with it). Debounced and coalesced
     * like web `registerActivityTrajectoryLiveReload`.
     */
    data object LiveActivityChanged : AgentActivityOverviewIntent

    /** "Show Earlier Turns": the page after `next`. */
    data object LoadMore : AgentActivityOverviewIntent

    /** A turn's last reply is on screen. */
    data class LoadReply(val messageId: MessageId) : AgentActivityOverviewIntent
}

/**
 * MVI store for the agent Activity tab overview (web `AgentActivityOverview`).
 *
 * Confinement: [dispatch] and every launched job run on [scope]'s dispatcher (the UI scope in
 * production), so the private in-flight markers below are never touched concurrently.
 * Every response is fenced by the agent generation: switching agents drops late answers.
 */
class SharedAgentActivityOverviewStore(
    private val repository: AgentActivityOverviewRepository,
    private val scope: CoroutineScope,
    private val liveReloadDebounceMillis: Long = LIVE_RELOAD_DEBOUNCE_MILLIS,
    private val logger: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(AgentActivityOverviewState())
    val state: StateFlow<AgentActivityOverviewState> = _state.asStateFlow()

    private var generation = 0L
    private var latestInFlight = false
    private var latestPending = false
    private var latestPendingPriority = DataRequestPriority.Background
    private var liveReloadJob: Job? = null

    fun dispatch(intent: AgentActivityOverviewIntent) {
        when (intent) {
            is AgentActivityOverviewIntent.Open -> open(intent.agentId)
            AgentActivityOverviewIntent.Refresh -> loadLatest(DataRequestPriority.Visible)
            AgentActivityOverviewIntent.LiveActivityChanged -> scheduleLiveReload()
            AgentActivityOverviewIntent.LoadMore -> loadMore()
            is AgentActivityOverviewIntent.LoadReply -> loadReply(intent.messageId)
        }
    }

    private fun open(agentId: MemberId) {
        val current = _state.value
        if (current.agentId == agentId && current.status != AgentActivityOverviewStatus.Idle) return
        generation += 1
        liveReloadJob?.cancel()
        liveReloadJob = null
        latestInFlight = false
        latestPending = false
        _state.value = AgentActivityOverviewState(agentId = agentId, status = AgentActivityOverviewStatus.Loading)
        loadLatest(DataRequestPriority.Visible)
    }

    private fun scheduleLiveReload() {
        if (_state.value.agentId == null) return
        liveReloadJob?.cancel()
        liveReloadJob = scope.launch {
            delay(liveReloadDebounceMillis)
            liveReloadJob = null
            // Content is already on screen: a silent refresh (Background lane).
            loadLatest(DataRequestPriority.Background)
        }
    }

    private fun loadLatest(priority: DataRequestPriority) {
        val agentId = _state.value.agentId ?: return
        if (latestInFlight) {
            // Coalesce: one more reload after the current one, at the most urgent priority asked.
            latestPending = true
            if (priority.ordinal < latestPendingPriority.ordinal) latestPendingPriority = priority
            return
        }
        latestInFlight = true
        val requestGeneration = generation
        scope.launch {
            val result = try {
                repository.loadTurns(priority, agentId)
            } finally {
                if (requestGeneration == generation) latestInFlight = false
            }
            if (requestGeneration != generation) return@launch
            applyLatest(result)
            if (latestPending) {
                latestPending = false
                val nextPriority = latestPendingPriority
                latestPendingPriority = DataRequestPriority.Background
                loadLatest(nextPriority)
            }
        }
    }

    private fun applyLatest(result: DataResult<AgentActivityTurnPage>) {
        when (result) {
            is DataResult.Content ->
                _state.update {
                    it.copy(
                        turns = result.data.turns,
                        next = result.data.next,
                        status = AgentActivityOverviewStatus.Loaded,
                        loadingMore = false,
                    )
                }
            is DataResult.Empty ->
                _state.update {
                    it.copy(turns = emptyList(), next = null, status = AgentActivityOverviewStatus.Loaded)
                }
            is DataResult.Error -> failLatest(result.error.code)
            is DataResult.NoPermission -> failLatest(result.blocker.code)
        }
    }

    private fun failLatest(code: String) {
        logger("agent_activity_overview_load_failed code=$code")
        _state.update {
            // Never clear what is already shown.
            if (it.status == AgentActivityOverviewStatus.Loaded) it else it.copy(status = AgentActivityOverviewStatus.Error(code))
        }
    }

    private fun loadMore() {
        val current = _state.value
        val agentId = current.agentId ?: return
        val cursor = current.next ?: return
        if (current.loadingMore || current.status != AgentActivityOverviewStatus.Loaded) return
        _state.update { it.copy(loadingMore = true) }
        val requestGeneration = generation
        scope.launch {
            val result = repository.loadTurns(DataRequestPriority.Visible, agentId, before = cursor)
            if (requestGeneration != generation) return@launch
            _state.update { state ->
                // A reload that landed meanwhile replaced the list; this page no longer follows it.
                if (state.next != cursor) return@update state.copy(loadingMore = false)
                when (result) {
                    is DataResult.Content -> {
                        val known = state.turns.mapTo(HashSet()) { it.key }
                        state.copy(
                            turns = state.turns + result.data.turns.filter { it.key !in known },
                            next = result.data.next,
                            loadingMore = false,
                        )
                    }
                    is DataResult.Empty -> state.copy(next = null, loadingMore = false)
                    // Web drops a failed "load earlier" silently; the button stays for another try.
                    is DataResult.Error, is DataResult.NoPermission -> state.copy(loadingMore = false)
                }
            }
        }
    }

    private fun loadReply(messageId: MessageId) {
        if (_state.value.agentId == null || messageId in _state.value.replies) return
        _state.update { it.copy(replies = it.replies + (messageId to AgentActivityReplyState.Loading)) }
        val requestGeneration = generation
        scope.launch {
            val reply = repository.loadReply(DataRequestPriority.Visible, messageId)
            if (requestGeneration != generation) return@launch
            _state.update { it.copy(replies = it.replies + (messageId to AgentActivityReplyState.Ready(reply))) }
        }
    }

    companion object {
        /** Web `registerActivityTrajectoryLiveReload` debounce. */
        const val LIVE_RELOAD_DEBOUNCE_MILLIS: Long = 250L
    }
}
