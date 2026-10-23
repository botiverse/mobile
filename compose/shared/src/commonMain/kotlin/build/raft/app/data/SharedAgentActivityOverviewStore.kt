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

    /**
     * The request failed for a passing reason (network, 5xx). Shown like an unreadable reply, but
     * never cached: the next [AgentActivityOverviewIntent.LoadReply] or a Refresh asks again.
     */
    data object Failed : AgentActivityReplyState
}

data class AgentActivityOverviewState(
    val agentId: MemberId? = null,
    /** Newest first, as the server pages them. */
    val turns: List<AgentActivityTurn> = emptyList(),
    val next: String? = null,
    val status: AgentActivityOverviewStatus = AgentActivityOverviewStatus.Idle,
    val loadingMore: Boolean = false,
    val replies: Map<MessageId, AgentActivityReplyState> = emptyMap(),
    /**
     * A [AgentActivityOverviewIntent.Refresh] of the loaded overview is running (pull-to-refresh
     * holds its indicator on it). Silent live reloads do not set it.
     */
    val refreshing: Boolean = false,
    /** The turn still running (the newest page's `current`); null when none is or the server is older. */
    val running: AgentActivityTurn? = null,
) {
    /**
     * Finished turns to show, newest first. The empty wake a running turn took over (same row id)
     * shows only as that turn (web `finished`).
     */
    val finishedTurns: List<AgentActivityTurn>
        get() {
            val runningId = running?.id ?: return turns
            return turns.filter { it.id != runningId }
        }
}

sealed interface AgentActivityOverviewIntent {
    /**
     * The overview is on screen for [agentId]. The first Open loads the latest page; a repeated
     * Open for the same agent once it is loaded (the tab was left and revisited) schedules a
     * debounced silent reload, since live changes were not observed while the tab was away.
     */
    data class Open(val agentId: MemberId) : AgentActivityOverviewIntent

    /** Retry or pull-to-refresh: reload the latest page now. */
    data object Refresh : AgentActivityOverviewIntent

    /**
     * The agent's live activity as the UI sees it now (`working`, `thinking`, `online`, ...). The
     * store keeps the last one per agent; a turn finishes only when the agent leaves a busy state,
     * so only that edge schedules a reload (debounced and coalesced like web
     * `registerActivityTrajectoryLiveReload`). working <-> thinking is the same turn: no reload.
     */
    data class LiveActivityObserved(val agentId: MemberId, val activity: String?) : AgentActivityOverviewIntent

    /**
     * The app returned to the foreground or the realtime socket reconnected: the agent's
     * activity pushes of that time were missed. A loaded overview reloads its latest page silently
     * (Background, coalesced with a reload in flight); a failed one retries.
     */
    data object CatchUp : AgentActivityOverviewIntent

    /** "Show Earlier Turns": the page after `next`. */
    data object LoadMore : AgentActivityOverviewIntent

    /** A turn's last reply, or one of the messages it sent, is on screen. */
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

    /** The `before` cursor of the "load earlier" request in flight; null when none is. */
    private var loadMoreCursor: String? = null

    /** Last live activity observed for the current agent; null until the first observation. */
    private var lastLiveActivity: String? = null

    fun dispatch(intent: AgentActivityOverviewIntent) {
        when (intent) {
            is AgentActivityOverviewIntent.Open -> open(intent.agentId)
            AgentActivityOverviewIntent.Refresh -> refresh()
            AgentActivityOverviewIntent.CatchUp -> catchUp()
            is AgentActivityOverviewIntent.LiveActivityObserved -> observeLiveActivity(intent.agentId, intent.activity)
            AgentActivityOverviewIntent.LoadMore -> loadMore()
            is AgentActivityOverviewIntent.LoadReply -> loadReply(intent.messageId)
        }
    }

    private fun open(agentId: MemberId) {
        val current = _state.value
        if (current.agentId == agentId && current.status != AgentActivityOverviewStatus.Idle) {
            // Revisit: what is shown may be stale. Loading or Error already has its own path.
            if (current.status == AgentActivityOverviewStatus.Loaded) scheduleLiveReload()
            return
        }
        generation += 1
        liveReloadJob?.cancel()
        liveReloadJob = null
        latestInFlight = false
        latestPending = false
        latestPendingPriority = DataRequestPriority.Background
        loadMoreCursor = null
        lastLiveActivity = null
        _state.value = AgentActivityOverviewState(agentId = agentId, status = AgentActivityOverviewStatus.Loading)
        loadLatest(DataRequestPriority.Visible)
    }

    private fun refresh() {
        if (_state.value.agentId == null) return
        // Retry from the error screen shows the loading state while it runs; a loaded overview
        // stays on screen and reports `refreshing` until the reload lands.
        _state.update {
            when (it.status) {
                is AgentActivityOverviewStatus.Error -> it.copy(status = AgentActivityOverviewStatus.Loading)
                AgentActivityOverviewStatus.Loaded -> it.copy(refreshing = true)
                else -> it
            }
        }
        loadLatest(DataRequestPriority.Visible)
        // Replies that failed for a passing reason are asked again.
        _state.value.replies.filterValues { it == AgentActivityReplyState.Failed }.keys.forEach(::loadReply)
    }

    private fun catchUp() {
        when (_state.value.status) {
            AgentActivityOverviewStatus.Loaded -> {
                // Replaces a pending debounced live reload: this one covers it.
                liveReloadJob?.cancel()
                liveReloadJob = null
                loadLatest(DataRequestPriority.Background)
            }
            is AgentActivityOverviewStatus.Error -> refresh()
            AgentActivityOverviewStatus.Idle,
            AgentActivityOverviewStatus.Loading -> Unit
        }
    }

    private fun observeLiveActivity(agentId: MemberId, activity: String?) {
        if (_state.value.agentId != agentId) return
        val previous = lastLiveActivity
        lastLiveActivity = activity
        if (isAgentActivityBusy(previous) && !isAgentActivityBusy(activity)) scheduleLiveReload()
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
            if (!latestPending) _state.update { if (it.refreshing) it.copy(refreshing = false) else it }
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
                    val head = if (it.status == AgentActivityOverviewStatus.Loaded) {
                        mergeAgentActivityHead(it.turns, it.next, result.data)
                    } else {
                        result.data
                    }
                    it.copy(
                        turns = head.turns,
                        next = head.next,
                        // Only the newest page carries it: every latest reload replaces it.
                        running = result.data.current,
                        status = AgentActivityOverviewStatus.Loaded,
                        // A "load earlier" still in flight keeps its spinner.
                        loadingMore = loadMoreCursor != null,
                    )
                }
            is DataResult.Empty ->
                _state.update {
                    it.copy(
                        turns = emptyList(),
                        next = null,
                        running = null,
                        status = AgentActivityOverviewStatus.Loaded,
                        loadingMore = loadMoreCursor != null,
                    )
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
        if (loadMoreCursor != null || current.status != AgentActivityOverviewStatus.Loaded) return
        loadMoreCursor = cursor
        _state.update { it.copy(loadingMore = true) }
        val requestGeneration = generation
        scope.launch {
            val result = try {
                repository.loadTurns(DataRequestPriority.Visible, agentId, before = cursor)
            } finally {
                if (requestGeneration == generation) loadMoreCursor = null
            }
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
        if (_state.value.agentId == null) return
        val known = _state.value.replies[messageId]
        if (known != null && known != AgentActivityReplyState.Failed) return
        _state.update { it.copy(replies = it.replies + (messageId to AgentActivityReplyState.Loading)) }
        val requestGeneration = generation
        scope.launch {
            val reply = repository.loadReply(DataRequestPriority.Visible, messageId)
            if (requestGeneration != generation) return@launch
            val replyState = if (reply == AgentActivityTurnReply.Failed) {
                logger("agent_activity_overview_reply_failed")
                AgentActivityReplyState.Failed
            } else {
                AgentActivityReplyState.Ready(reply)
            }
            _state.update { it.copy(replies = it.replies + (messageId to replyState)) }
        }
    }

    companion object {
        /** Web `registerActivityTrajectoryLiveReload` debounce. */
        const val LIVE_RELOAD_DEBOUNCE_MILLIS: Long = 250L
    }
}

/** `working` and `thinking` are a turn in progress (web agent activity). */
internal fun isAgentActivityBusy(activity: String?): Boolean =
    when (activity?.trim()?.lowercase()) {
        "working", "thinking" -> true
        else -> false
    }

/**
 * A reload's latest page laid over what is loaded: the new page, then the already-loaded older
 * turns past the new page's last row, deduplicated by key, keeping the deeper cursor. When the
 * new page does not reach any loaded row (more turns finished than a page holds), or it is the
 * whole history, the new page replaces the list: splicing would leave a gap.
 */
internal fun mergeAgentActivityHead(
    loaded: List<AgentActivityTurn>,
    loadedNext: String?,
    latest: AgentActivityTurnPage,
): AgentActivityTurnPage {
    val last = latest.turns.lastOrNull() ?: return latest
    if (latest.next == null) return latest
    val anchor = loaded.indexOfFirst { it.key == last.key }
    if (anchor < 0) return latest
    val newKeys = latest.turns.mapTo(HashSet()) { it.key }
    val older = loaded.drop(anchor + 1).filter { it.key !in newKeys }
    if (older.isEmpty()) return latest
    return AgentActivityTurnPage(turns = latest.turns + older, next = loadedNext, current = latest.current)
}
