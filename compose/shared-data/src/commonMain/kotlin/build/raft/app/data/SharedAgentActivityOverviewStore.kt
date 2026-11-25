package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import build.raft.app.data.realtime.RaftRealtimeConnectionCauses
import build.raft.app.model.MemberId
import build.raft.app.model.MessageId
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
    /**
     * What people did to the agent (and Raft's own restarts), newest first, as far back as the held
     * turns reach (all of them once no earlier page is left). Asked again after every load of the
     * turns; a failed ask keeps what was shown (an older server has no such route).
     */
    val operatorEvents: List<AgentOperatorEvent> = emptyList(),
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
     * Registers an active overview surface (Activity tab Overview or Profile Recent activity) as
     * observing [serverId] for store-owned freshness catch-up (#wg-mobile-feedback:#274 Phase 1).
     * Idempotent per [token]; pair with [StopObservingFreshness] on dispose.
     */
    data class ObserveFreshness(val token: Any, val serverId: String?) : AgentActivityOverviewIntent

    /** Unregisters the overview surface identified by [token]. */
    data class StopObservingFreshness(val token: Any) : AgentActivityOverviewIntent

    /** Whether the enclosing detail page is currently visible on screen (not covered). */
    data class SetPageVisible(val visible: Boolean) : AgentActivityOverviewIntent

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
 * Strict owner/server/agent cache identity for warm Agent Activity Overview snapshots.
 * Every dimension must be non-blank; switching environment, account, server, or agent never
 * reuses another subject's cached turns or replies.
 */
@RaftCoreInternalApi data class AgentActivityOverviewCacheKey(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
    val agentId: MemberId,
)

@RaftCoreInternalApi fun agentActivityOverviewCacheKey(
    environmentId: String?,
    accountId: String?,
    serverId: String?,
    agentId: MemberId?,
): AgentActivityOverviewCacheKey? {
    val cleanEnv = environmentId?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
    val cleanAccount = accountId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val cleanServer = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val cleanAgent = agentId?.value?.trim()?.takeIf { it.isNotEmpty() }?.let(::MemberId) ?: return null
    return AgentActivityOverviewCacheKey(
        environmentId = cleanEnv,
        accountId = cleanAccount,
        serverId = cleanServer,
        agentId = cleanAgent,
    )
}

/** Settled overview snapshot persisted in memory for warm Frame-1 rendering on revisit. */
@RaftCoreInternalApi data class AgentActivityOverviewCachedSnapshot(
    val agentId: MemberId,
    val turns: List<AgentActivityTurn>,
    val next: String?,
    val running: AgentActivityTurn?,
    val operatorEvents: List<AgentOperatorEvent>,
    val replies: Map<MessageId, AgentActivityReplyState.Ready>,
)

/**
 * Bounded, owner/server/agent-scoped LRU snapshot cache for [SharedAgentActivityOverviewStore].
 * Synchronous [peek] seeds Frame 1 on revisit while the store refreshes silently in the background;
 * cleared on sign-out / account switch, per-server on lost access, and per-agent on deletion.
 */
@RaftCoreInternalApi class AgentActivityOverviewSnapshotCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<AgentActivityOverviewCacheKey, AgentActivityOverviewCachedSnapshot>()

    fun peek(key: AgentActivityOverviewCacheKey): AgentActivityOverviewCachedSnapshot? =
        synchronized(lock) {
            val value = entries.remove(key) ?: return@synchronized null
            entries[key] = value
            value
        }

    fun put(key: AgentActivityOverviewCacheKey, snapshot: AgentActivityOverviewCachedSnapshot) {
        synchronized(lock) {
            entries.remove(key)
            entries[key] = snapshot
            val limit = maxEntries.coerceAtLeast(1)
            while (entries.size > limit) {
                val oldestKey = entries.entries.firstOrNull()?.key ?: break
                entries.remove(oldestKey)
            }
        }
    }

    fun evict(key: AgentActivityOverviewCacheKey) {
        synchronized(lock) { entries.remove(key) }
    }

    fun evictAgent(serverId: String, agentId: MemberId) {
        val cleanServer = serverId.trim()
        val cleanAgent = agentId.value.trim()
        synchronized(lock) {
            entries.keys.removeAll { it.serverId == cleanServer && it.agentId.value == cleanAgent }
        }
    }

    fun dropServer(serverId: String) {
        val cleanServer = serverId.trim()
        synchronized(lock) { entries.keys.removeAll { it.serverId == cleanServer } }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 16
    }
}

/**
 * MVI store for the agent Activity tab overview (web `AgentActivityOverview`).
 *
 * Confinement: [dispatch] and every launched job run on [scope]'s dispatcher (the UI scope in
 * production), so the private in-flight markers below are never touched concurrently.
 * Every response is fenced by the agent generation: switching agents drops late answers.
 *
 * Freshness (#wg-mobile-feedback:#274 Phase 1): subscribes directly to [freshness] and uses an
 * outcome-tracked [RaftFreshnessGate] (`trackOutcome = true`) so foreground / reconnect /
 * network-restored catch-ups run only while an overview surface is actively observing, never burn
 * the 10 s coalescing window on a failed read, and keep existing shown turns on refresh or error.
 */
class SharedAgentActivityOverviewStore(
    private val repository: AgentActivityOverviewRepository,
    private val scope: CoroutineScope,
    private val liveReloadDebounceMillis: Long = LIVE_RELOAD_DEBOUNCE_MILLIS,
    private val freshness: Flow<RaftFreshnessEvent> = RaftFreshnessSignal.events,
    private val nowMillis: () -> Long = ::raftForegroundGateNowMillis,
    private val liveConnectionToken: (serverId: String) -> Long? = RaftRealtimeConnectionCauses::liveConnectionToken,
    private val logger: (String) -> Unit = {},
    private val snapshotCache: AgentActivityOverviewSnapshotCache? = null,
    private val cacheKeyFor: ((MemberId) -> AgentActivityOverviewCacheKey?)? = null,
    initialAgentId: MemberId? = null,
) {
    private val _state = MutableStateFlow(initialOverviewState(initialAgentId, snapshotCache, cacheKeyFor))
    val state: StateFlow<AgentActivityOverviewState> = _state.asStateFlow()

    private var seededFromCache = _state.value.status == AgentActivityOverviewStatus.Loaded
    private var generation = 0L
    private var latestInFlight = false
    private var latestPending = false
    private var latestPendingPriority = DataRequestPriority.Background
    private var liveReloadJob: Job? = null

    /** Active overview surface tokens -> serverId for store-owned freshness catch-up. */
    private val freshnessObservers = LinkedHashMap<Any, String?>()
    private var pageVisible = true
    private val freshnessGate =
        RaftFreshnessGate(
            nowMillis = nowMillis,
            trackOutcome = true,
            liveConnectionToken = liveConnectionToken,
        )
    private var freshnessCatchUpInFlight = false

    /** The `before` cursor of the "load earlier" request in flight; null when none is. */
    private var loadMoreCursor: String? = null

    /** Last live activity observed for the current agent; null until the first observation. */
    private var lastLiveActivity: String? = null

    /** Bumped per operator-events request: only the latest answer applies (web effect cleanup). */
    private var operatorEventsToken = 0L

    init {
        scope.launch {
            freshness.collect { event ->
                onFreshnessEvent(event)
            }
        }
    }

    fun dispatch(intent: AgentActivityOverviewIntent) {
        when (intent) {
            is AgentActivityOverviewIntent.Open -> open(intent.agentId)
            AgentActivityOverviewIntent.Refresh -> refresh()
            AgentActivityOverviewIntent.CatchUp -> catchUp()
            is AgentActivityOverviewIntent.ObserveFreshness -> observeFreshness(intent.token, intent.serverId)
            is AgentActivityOverviewIntent.StopObservingFreshness -> stopObservingFreshness(intent.token)
            is AgentActivityOverviewIntent.SetPageVisible -> setPageVisible(intent.visible)
            is AgentActivityOverviewIntent.LiveActivityObserved -> observeLiveActivity(intent.agentId, intent.activity)
            AgentActivityOverviewIntent.LoadMore -> loadMore()
            is AgentActivityOverviewIntent.LoadReply -> loadReply(intent.messageId)
        }
    }

    private fun observeFreshness(token: Any, serverId: String?) {
        freshnessObservers[token] = serverId?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun stopObservingFreshness(token: Any) {
        freshnessObservers.remove(token)
        if (freshnessObservers.isEmpty() && !freshnessCatchUpInFlight) {
            freshnessGate.reset()
        }
    }

    private fun setPageVisible(visible: Boolean) {
        val previous = pageVisible
        pageVisible = visible
        if (visible && !previous && freshnessObservers.isNotEmpty()) {
            val current = _state.value
            if (current.agentId != null && current.status != AgentActivityOverviewStatus.Idle) {
                freshnessGate.onVisibilityChanged(visible = true, serverId = activeFreshnessServerId())
                    ?.let(::startFreshnessCatchUp)
            }
        }
    }

    private fun activeFreshnessServerId(): String? =
        freshnessObservers.values.lastOrNull { !it.isNullOrBlank() }

    private fun isFreshnessVisible(): Boolean =
        freshnessObservers.isNotEmpty() && pageVisible

    private fun onFreshnessEvent(event: RaftFreshnessEvent) {
        if (freshnessObservers.isEmpty()) return
        val current = _state.value
        if (current.agentId == null || current.status == AgentActivityOverviewStatus.Idle) return
        val trigger = freshnessGate.onEvent(event, activeFreshnessServerId(), visible = pageVisible) ?: return
        startFreshnessCatchUp(trigger)
    }

    private fun startFreshnessCatchUp(trigger: RaftFreshnessTrigger) {
        when (_state.value.status) {
            AgentActivityOverviewStatus.Loaded -> {
                freshnessCatchUpInFlight = true
                liveReloadJob?.cancel()
                liveReloadJob = null
                loadLatest(DataRequestPriority.Background)
            }
            is AgentActivityOverviewStatus.Error -> {
                freshnessCatchUpInFlight = true
                refresh()
            }
            AgentActivityOverviewStatus.Loading -> {
                freshnessCatchUpInFlight = true
                if (latestInFlight) {
                    if (trigger == RaftFreshnessTrigger.Reconnect || trigger == RaftFreshnessTrigger.NetworkRestored) {
                        latestPending = true
                    }
                } else {
                    loadLatest(DataRequestPriority.Background)
                }
            }
            AgentActivityOverviewStatus.Idle -> {
                freshnessGate.onCatchUpCompleted(succeeded = false, visible = false)
            }
        }
    }

    private fun open(agentId: MemberId) {
        val current = _state.value
        if (current.agentId == agentId && current.status != AgentActivityOverviewStatus.Idle) {
            if (seededFromCache) {
                seededFromCache = false
                loadLatest(DataRequestPriority.Background)
                return
            }
            // Revisit: what is shown may be stale. Loading or Error already has its own path.
            if (current.status == AgentActivityOverviewStatus.Loaded) scheduleLiveReload()
            return
        }
        seededFromCache = false
        generation += 1
        liveReloadJob?.cancel()
        liveReloadJob = null
        latestInFlight = false
        latestPending = false
        latestPendingPriority = DataRequestPriority.Background
        freshnessCatchUpInFlight = false
        freshnessGate.reset()
        loadMoreCursor = null
        lastLiveActivity = null
        val cached = cacheKeyFor?.invoke(agentId)?.let { snapshotCache?.peek(it) }
        if (cached != null) {
            _state.value = cached.toLoadedState()
            loadLatest(DataRequestPriority.Background)
        } else {
            _state.value = AgentActivityOverviewState(agentId = agentId, status = AgentActivityOverviewStatus.Loading)
            loadLatest(DataRequestPriority.Visible)
        }
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
            var succeeded = false
            var completedNormally = false
            try {
                val result =
                    try {
                        repository.loadTurns(priority, agentId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        logger("agent_activity_overview_load_exception type=${error::class.simpleName ?: "unknown"}")
                        DataResult.Error(ScreenError("activity_summaries_exception", retryable = true))
                    }
                if (requestGeneration != generation) return@launch
                completedNormally = true
                succeeded = result is DataResult.Content || result is DataResult.Empty
                applyLatest(result)
                if (succeeded) loadOperatorEvents(priority)
            } finally {
                if (requestGeneration == generation) {
                    latestInFlight = false
                    if (!completedNormally) {
                        if (freshnessCatchUpInFlight) {
                            freshnessCatchUpInFlight = false
                            freshnessGate.onCatchUpCompleted(succeeded = false, visible = false)
                        }
                        if (!latestPending) _state.update { if (it.refreshing) it.copy(refreshing = false) else it }
                    }
                }
            }
            if (requestGeneration != generation) return@launch
            if (!latestPending) _state.update { if (it.refreshing) it.copy(refreshing = false) else it }
            if (latestPending) {
                latestPending = false
                val nextPriority = latestPendingPriority
                latestPendingPriority = DataRequestPriority.Background
                loadLatest(nextPriority)
            } else if (freshnessCatchUpInFlight) {
                freshnessCatchUpInFlight = false
                val followUp = freshnessGate.onCatchUpCompleted(succeeded = succeeded, visible = isFreshnessVisible())
                if (freshnessObservers.isEmpty()) {
                    freshnessGate.reset()
                } else if (followUp != null) {
                    startFreshnessCatchUp(followUp)
                }
            }
        }
    }

    private fun applyLatest(result: DataResult<AgentActivityTurnPage>) {
        when (result) {
            is DataResult.Content -> {
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
                persistSnapshotToCache()
            }
            is DataResult.Empty -> {
                _state.update {
                    it.copy(
                        turns = emptyList(),
                        next = null,
                        running = null,
                        status = AgentActivityOverviewStatus.Loaded,
                        loadingMore = loadMoreCursor != null,
                    )
                }
                persistSnapshotToCache()
            }
            is DataResult.Error -> failLatest(result.error.code)
            is DataResult.NoPermission -> {
                evictCurrentFromCache()
                failLatest(result.blocker.code)
            }
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
            var settled = false
            try {
                val result =
                    try {
                        repository.loadTurns(DataRequestPriority.Visible, agentId, before = cursor)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        logger("agent_activity_overview_load_more_exception type=${error::class.simpleName ?: "unknown"}")
                        DataResult.Error(ScreenError("activity_summaries_more_exception", retryable = true))
                    }
                if (requestGeneration != generation) return@launch
                loadMoreCursor = null
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
                settled = true
                // The held turns reach further back now: so do the events.
                if (result is DataResult.Content || result is DataResult.Empty) {
                    persistSnapshotToCache()
                    loadOperatorEvents(DataRequestPriority.Visible)
                }
            } finally {
                if (requestGeneration == generation) {
                    loadMoreCursor = null
                    if (!settled) _state.update { if (it.loadingMore) it.copy(loadingMore = false) else it }
                }
            }
        }
    }

    /**
     * Web `AgentActivityOverview`: the events since the oldest held turn's start while an earlier
     * page is left, all of them otherwise; reloaded with the turns, so a Stop or Restart that started
     * no turn shows too.
     */
    private fun loadOperatorEvents(priority: DataRequestPriority) {
        val current = _state.value
        val agentId = current.agentId ?: return
        val since = if (current.next != null) current.turns.minOfOrNull { it.startedAtEpochMillis } else null
        val token = ++operatorEventsToken
        val requestGeneration = generation
        scope.launch {
            val result =
                try {
                    repository.loadOperatorEvents(priority, agentId, since)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    logger("agent_activity_overview_operator_events_exception type=${error::class.simpleName ?: "unknown"}")
                    DataResult.Error(ScreenError("activity_operator_events_exception", retryable = true))
                }
            if (requestGeneration != generation || token != operatorEventsToken) return@launch
            when (result) {
                is DataResult.Content -> {
                    _state.update {
                        if (it.operatorEvents == result.data.events) it else it.copy(operatorEvents = result.data.events)
                    }
                    persistSnapshotToCache()
                }
                is DataResult.Empty -> Unit
                is DataResult.Error -> logger("agent_activity_overview_operator_events_failed code=${result.error.code}")
                is DataResult.NoPermission -> logger("agent_activity_overview_operator_events_failed code=${result.blocker.code}")
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
            var settled = false
            try {
                val reply =
                    try {
                        repository.loadReply(DataRequestPriority.Visible, messageId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        logger("agent_activity_overview_reply_exception type=${error::class.simpleName ?: "unknown"}")
                        AgentActivityTurnReply.Failed
                    }
                if (requestGeneration != generation) return@launch
                val replyState = if (reply == AgentActivityTurnReply.Failed) {
                    logger("agent_activity_overview_reply_failed")
                    AgentActivityReplyState.Failed
                } else {
                    AgentActivityReplyState.Ready(reply)
                }
                _state.update { it.copy(replies = it.replies + (messageId to replyState)) }
                settled = true
                if (replyState is AgentActivityReplyState.Ready) persistSnapshotToCache()
            } finally {
                if (!settled && requestGeneration == generation) {
                    _state.update { state ->
                        if (state.replies[messageId] == AgentActivityReplyState.Loading) {
                            state.copy(replies = state.replies - messageId)
                        } else {
                            state
                        }
                    }
                }
            }
        }
    }

    private fun persistSnapshotToCache() {
        val cache = snapshotCache ?: return
        val current = _state.value
        val agentId = current.agentId ?: return
        if (current.status != AgentActivityOverviewStatus.Loaded) return
        val key = cacheKeyFor?.invoke(agentId) ?: return
        val readyReplies = LinkedHashMap<MessageId, AgentActivityReplyState.Ready>()
        current.replies.forEach { (id, state) ->
            if (state is AgentActivityReplyState.Ready) readyReplies[id] = state
        }
        cache.put(
            key,
            AgentActivityOverviewCachedSnapshot(
                agentId = agentId,
                turns = current.turns,
                next = current.next,
                running = current.running,
                operatorEvents = current.operatorEvents,
                replies = readyReplies,
            ),
        )
    }

    private fun evictCurrentFromCache() {
        val cache = snapshotCache ?: return
        val agentId = _state.value.agentId ?: return
        val key = cacheKeyFor?.invoke(agentId) ?: return
        cache.evict(key)
    }

    companion object {
        /** Web `registerActivityTrajectoryLiveReload` debounce. */
        const val LIVE_RELOAD_DEBOUNCE_MILLIS: Long = 250L

        private fun initialOverviewState(
            initialAgentId: MemberId?,
            snapshotCache: AgentActivityOverviewSnapshotCache?,
            cacheKeyFor: ((MemberId) -> AgentActivityOverviewCacheKey?)?,
        ): AgentActivityOverviewState {
            if (initialAgentId == null || snapshotCache == null || cacheKeyFor == null) {
                return AgentActivityOverviewState()
            }
            val key = cacheKeyFor(initialAgentId) ?: return AgentActivityOverviewState()
            val cached = snapshotCache.peek(key) ?: return AgentActivityOverviewState()
            return cached.toLoadedState()
        }

        private fun AgentActivityOverviewCachedSnapshot.toLoadedState(): AgentActivityOverviewState =
            AgentActivityOverviewState(
                agentId = agentId,
                turns = turns,
                next = next,
                status = AgentActivityOverviewStatus.Loaded,
                running = running,
                operatorEvents = operatorEvents,
                replies = replies,
            )
    }
}

/** `working` and `thinking` are a turn in progress (web agent activity). */
@RaftCoreInternalApi fun isAgentActivityBusy(activity: String?): Boolean =
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
@RaftCoreInternalApi fun mergeAgentActivityHead(
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
