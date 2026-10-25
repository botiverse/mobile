package build.raft.app.data

import build.raft.app.withKnownLatestDelegatedLabelsFrom
import build.raft.app.SharedThreadContentGeneration
import build.raft.app.SharedThreadFreshnessLedger
import build.raft.app.SharedThreadSummaryFact
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.PerReceiverReadStateScope
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.ServerDeleted
import build.raft.app.model.ServerId
import build.raft.app.model.ServerMembershipRemoved
import build.raft.app.model.ServerSummary
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskSummary
import build.raft.app.model.ThreadUpdated
import build.raft.app.data.realtime.PerReceiverReadStateFactResult
import build.raft.app.data.realtime.PerReceiverStateVersionStore
import build.raft.app.network.sync.ReadStateV1Fact
import build.raft.app.network.sync.ReadStateV1ProductionCanary
import build.raft.app.network.sync.ReadStateV1ProductionDisposition
import build.raft.app.network.sync.ReadStateV1ProductionOwner
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlin.time.TimeSource

private const val SharedActivityPageSize = 30
private const val SharedActivityRefreshPageLimit = 100
private const val SharedActivityRefreshContinuationSlack = 2
private const val SharedActivityRefreshMaxNoProgressPages = 2
private const val SharedActivityReplyPrewarmConcurrency = 2
private const val SharedActivityTailPrewarmDepthCap = 20

private data class ActivityRealtimeHighWater(
    val marker: MessageId?,
    val replyCount: Int,
    /** Channel/DM rows only: the `message:new` seq the row was patched to (thread rows keep null). */
    val seq: String? = null,
    /** Channel/DM rows only: the patch stops holding over older served rows after this time. */
    val holdUntilMillis: Long? = null
)

private data class ActivityRealtimeCatchUpTarget(val channelId: ChannelId, val seq: String)

private data class ActivityRealtimeReconcileResult(
    val items: List<InboxItem>,
    val preserveTotalUnreadFloor: Boolean = false
)

data class ActivityReplyThreadPrewarmKey(
    val serverId: String,
    val parentChannelId: String,
    val parentMessageId: String,
    val threadChannelId: String
)

data class ActivityReplyThreadPrewarmMarker(
    val key: ActivityReplyThreadPrewarmKey,
    // Owner-less content token: "did canonical content change since this marker was stamped".
    // The marker never selects a cached payload, so it carries no payload-cache owner.
    val contentGeneration: SharedThreadContentGeneration? = null
)

class SharedActivityStore internal constructor(
    private var authStore: RaftAuthSessionStore,
    private var inboxRepository: InboxRepository,
    private var readReceiptRepository: ReadReceiptRepository,
    private var conversationRepository: ConversationRepository,
    private var memberRepository: MemberRepository? = null,
    private var serverMembersStore: SharedServerMembersStore? = null,
    private var tasksDataStore: SharedTasksDataStore? = null,
    // task #399: optional; resolves DM-parent thread rows' peer name from the
    // Home DM list (channels.name is not a reliable peer projection for DMs).
    private var channelRepository: ChannelRepository? = null,
    private val perReceiverStateVersionStore: PerReceiverStateVersionStore,
    private var readReceiptCoordinator: ReadReceiptCoordinator? = null,
    private var readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = null,
    private var readStateV1Canary: ReadStateV1ProductionCanary? = null,
    private var prewarmReplyThread: suspend (ActivityReplyThreadPrewarmMarker) -> DataResult<Unit> = {
        DataResult.Empty(EmptyReason.NoMessages)
    },
    /**
     * True when the shared thread cache already holds this reply thread's content at its current
     * freshness generation. Only then may a queued prewarm be dropped because the user opened it.
     */
    private var replyThreadContentCached: suspend (ActivityReplyThreadPrewarmMarker) -> Boolean = { false },
    private var replyThreadFreshnessLedger: SharedThreadFreshnessLedger = SharedThreadFreshnessLedger(),
    // Shared with every visible thread page (owned by SharedThreadPayloadStore): while one is loading
    // its first content, reply prewarm does not START new loads.
    private var foregroundLoadGate: SharedForegroundLoadGate = SharedForegroundLoadGate(),
    private val tailPrewarmDepthCap: Int = SharedActivityTailPrewarmDepthCap,
    private var persistSlices: List<MviPersistSlice<SharedActivityState, SharedActivityIntent>> = emptyList(),
    onUnreadCountsChanged: () -> Unit = {},
    onSummaryFact: (ActivityUnreadSummaryFact) -> Unit = {},
    onSummaryInvalidated: (ActivityUnreadSummaryInvalidation) -> Unit = {},
    private val scope: CoroutineScope,
    private var diagnosticsLogger: (String) -> Unit = {},
    /** Realtime-triggered refetch throttle window (leading + trailing); user loads are never throttled. */
    private val realtimeBackgroundRefreshIntervalMillis: Long = ActivityRealtimeBackgroundRefreshIntervalMillis,
    /** Monotonic clock for the throttle window and the local Done/read hold; injectable for virtual time. */
    private val nowMillis: () -> Long = activityMonotonicClock(),
    /** See [ActivityRefreshCoalesceWindowMillis]. */
    private val refreshCoalesceWindowMillis: Long = ActivityRefreshCoalesceWindowMillis,
    /** Task #164: the backoff of the stale-Activity recovery read; injectable for virtual time. */
    staleRecoveryDelayMillis: (attempt: Int, retryAfterMillis: Long?) -> Long =
        { attempt, retryAfter -> raftStaleRecoveryDelayMillis(attempt, retryAfter) },
    localMutationHoldMillis: Long = ActivityLocalMutationHoldMillis,
    private val beforeRealtimeHighWaterFold: () -> Unit = {},
    private val onRealtimePatchOwnerEntered: () -> Unit = {},
    private val onReadReceiptPredictionOwnerEntered: () -> Unit = {}
) {
    private val _state = raftStoreState(SharedActivityState(), "SharedActivityStore")
    val state: StateFlow<SharedActivityState> = _state.asStateFlow()
    internal val presentationStore = SharedActivityPresentationStore()

    private var loadJob: Job? = null
    private var loadGeneration: Long = 0L
    /** Task #164: the one pending recovery read while the shown Activity is stale. */
    private val staleRecovery =
        RaftStaleReadRecovery(
            scope = scope,
            label = "activity",
            diagnosticsLogger = { diagnosticsLogger(it) },
            delayMillis = staleRecoveryDelayMillis
        )
    /** Between [SharedActivityIntent.SurfaceHidden] and the next load the surface asks for. */
    private var activitySurfaceHidden = false
    private var onUnreadCountsChanged: () -> Unit = onUnreadCountsChanged
    private var onSummaryFact: (ActivityUnreadSummaryFact) -> Unit = onSummaryFact
    private var onSummaryInvalidated: (ActivityUnreadSummaryInvalidation) -> Unit = onSummaryInvalidated
    // Local read / Done overlays: keep a confirmed mutation applied while the
    // lagging Activity view still serves the click-time activity marker.
    private val readOverlay = ActivityLocalMutationOverlay(nowMillis, localMutationHoldMillis)
    private val doneOverlay = ActivityLocalMutationOverlay(nowMillis, localMutationHoldMillis)
    private var lastResetLoadStartedAtMillis: Long? = null
    /** Start time of [loadJob] when it is a foreground reset load (not LoadMore / background). */
    private var foregroundResetLoadStartedAtMillis: Long? = null
    /**
     * Local duplicate-request fence for Activity Follow/Unfollow actions.
     * The server remains the authority for the eventual boolean; this set only
     * prevents two taps from dispatching the same mutation concurrently.
     */
    private var pendingThreadFollowMutations: Set<String> = emptySet()
    /**
     * A Follow asked for while that row's Unfollow is still in flight (a swipe's Undo tapped right
     * away): run once the Unfollow settles, instead of being dropped by the fence.
     */
    private var deferredThreadFollows: Map<String, InboxItem> = emptyMap()
    private var readPredictionRevisions: Map<ChannelId, Long> = emptyMap()
    private var readPredictionItemKeys: Map<ChannelId, Set<String>> = emptyMap()
    /** Channel -> (registering prediction revision, pending read-overlay generations). */
    private var readPredictionHolds: Map<ChannelId, Pair<Long, Map<String, Long>>> = emptyMap()
    /**
     * Locally resolved mention-action rows. Never expire (pre-overlay mobile
     * behaviour): the resolution has no server write and no newer marker that
     * could retire it, so a timed hold would let the row come back.
     */
    private var resolvedMentionActionKeys: Map<ActivityOverlayOwner, Set<String>> = emptyMap()
    private val replyPrewarmSemaphore = Semaphore(SharedActivityReplyPrewarmConcurrency)
    // Activity intents can be dispatched from the Kuikly frame thread while app-scope prewarm
    // jobs complete on a coroutine worker. Keep every reply-prewarm collection behind one owner
    // lock so reset cannot iterate a HashMap while a job's finally block mutates it.
    private val replyPrewarmOwnerLock = SynchronizedObject()
    private var visibleReplyPrewarmMarkers: Map<ActivityReplyThreadPrewarmKey, ActivityReplyThreadPrewarmMarker> = emptyMap()
    private var tailReplyPrewarmMarkers: Map<ActivityReplyThreadPrewarmKey, ActivityReplyThreadPrewarmMarker> = emptyMap()
    private val completedReplyPrewarmGenerations =
        mutableMapOf<ActivityReplyThreadPrewarmKey, SharedThreadContentGeneration>()
    private val failedReplyPrewarmGenerations =
        mutableMapOf<ActivityReplyThreadPrewarmKey, SharedThreadContentGeneration>()
    private val replyPrewarmJobs = mutableMapOf<ActivityReplyThreadPrewarmKey, Job>()
    /** Prewarms past the semaphore and the foreground gate, i.e. actually loading. */
    private var replyPrewarmFetchingCount = 0
    private var tailPrewarmLoopJob: Job? = null
    private var tailInFlightJob: Job? = null
    private var tailDrainBarrier: Job? = null
    private var realtimeBackgroundReconcileJob: Job? = null
    /** Per channel: the in-flight readability check (single-flight) and the latest event generation. */
    private val channelAccessChecks = mutableMapOf<String, Job>()
    private val channelAccessGenerations = mutableMapOf<String, Long>()
    private var realtimeBackgroundReconcileDirty = false
    /**
     * #triage 84d44d75: the newest patchable `message:new` (channel + seq) seen since the inbox last
     * answered at or past it (guarded by realtimeOwnerLock). One target for the whole inbox; many
     * events raise it, they never add reads (web inboxStore `inboxCatchUpTarget`, slock #11208).
     */
    private var realtimeCatchUpTarget: ActivityRealtimeCatchUpTarget? = null
    /** Re-reads already spent on [realtimeCatchUpTarget]; bounded by [ActivityRealtimeCatchUpRetryDelaysMillis]. */
    private var realtimeCatchUpRetries = 0
    /** The one pending catch-up re-read. */
    private var realtimeCatchUpRetryJob: Job? = null
    // task #399: DM-parent thread rows whose peer could not be resolved from the
    // last DM list fetch; retried only when a NEW unresolved id appears.
    private var unresolvedDmPeerChannelIds: Set<ChannelId> = emptySet()
    private var dmPeerNamesFetchInFlight = false
    private val realtimeHighWater = mutableMapOf<String, ActivityRealtimeHighWater>()
    // One owner for live row patches, version-ledger changes, background reconcile scheduling,
    // and canonical load folds.
    private val realtimeOwnerLock = SynchronizedObject()
    // Seqs the summary already counted from realtime (guarded by realtimeOwnerLock). Exact catch-up
    // (slock #10982) can deliver a message twice — live, then in a `sync:resume` batch — and the
    // summary path never patches rows, so the rows alone cannot tell. Seqs are server-global.
    private val realtimeCountedUnreadSeqs = linkedSetOf<Long>()

    internal fun updateRuntimeIntegrations(
        authStore: RaftAuthSessionStore = this.authStore,
        inboxRepository: InboxRepository = this.inboxRepository,
        readReceiptRepository: ReadReceiptRepository = this.readReceiptRepository,
        conversationRepository: ConversationRepository = this.conversationRepository,
        memberRepository: MemberRepository? = this.memberRepository,
        serverMembersStore: SharedServerMembersStore? = this.serverMembersStore,
        tasksDataStore: SharedTasksDataStore? = this.tasksDataStore,
        channelRepository: ChannelRepository? = this.channelRepository,
        prewarmReplyThread: (suspend (ActivityReplyThreadPrewarmMarker) -> DataResult<Unit>)? = null,
        replyThreadContentCached: (suspend (ActivityReplyThreadPrewarmMarker) -> Boolean)? = null,
        replyThreadFreshnessLedger: SharedThreadFreshnessLedger? = null,
        foregroundLoadGate: SharedForegroundLoadGate? = null,
        persistSlices: List<MviPersistSlice<SharedActivityState, SharedActivityIntent>> = this.persistSlices,
        readReceiptCoordinator: ReadReceiptCoordinator? = this.readReceiptCoordinator,
        readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = this.readReceiptOwnerFactory,
        readStateV1Canary: ReadStateV1ProductionCanary? = this.readStateV1Canary,
        onUnreadCountsChanged: (() -> Unit)? = null,
        onSummaryFact: ((ActivityUnreadSummaryFact) -> Unit)? = null,
        onSummaryInvalidated: ((ActivityUnreadSummaryInvalidation) -> Unit)? = null,
        diagnosticsLogger: ((String) -> Unit)? = null
    ) {
        this.authStore = authStore
        this.inboxRepository = inboxRepository
        this.readReceiptRepository = readReceiptRepository
        this.conversationRepository = conversationRepository
        this.memberRepository = memberRepository
        this.serverMembersStore = serverMembersStore
        this.tasksDataStore = tasksDataStore
        this.channelRepository = channelRepository
        if (prewarmReplyThread != null) {
            this.prewarmReplyThread = prewarmReplyThread
        }
        if (replyThreadContentCached != null) {
            this.replyThreadContentCached = replyThreadContentCached
        }
        if (replyThreadFreshnessLedger != null) {
            this.replyThreadFreshnessLedger = replyThreadFreshnessLedger
        }
        if (foregroundLoadGate != null) {
            this.foregroundLoadGate = foregroundLoadGate
        }
        this.persistSlices = persistSlices
        this.readReceiptCoordinator = readReceiptCoordinator
        this.readReceiptOwnerFactory = readReceiptOwnerFactory
        this.readStateV1Canary = readStateV1Canary
        if (onUnreadCountsChanged != null) {
            this.onUnreadCountsChanged = onUnreadCountsChanged
        }
        if (onSummaryFact != null) {
            this.onSummaryFact = onSummaryFact
        }
        if (onSummaryInvalidated != null) {
            this.onSummaryInvalidated = onSummaryInvalidated
        }
        if (diagnosticsLogger != null) {
            this.diagnosticsLogger = diagnosticsLogger
        }
    }

    fun dispatch(intent: SharedActivityIntent) {
        when (intent) {
            // On the store scope: the shell dispatches from its UI thread, and the switch writes state.
            is SharedActivityIntent.ServerSelected -> scope.launch { selectServer(intent) }
            SharedActivityIntent.LoadInitial -> {
                activitySurfaceHidden = false
                load(refresh = true, intent = intent)
            }
            SharedActivityIntent.Refresh -> {
                activitySurfaceHidden = false
                load(refresh = true, pullRefresh = false, intent = intent)
            }
            SharedActivityIntent.PullRefresh -> {
                activitySurfaceHidden = false
                // Pull-to-refresh is the user moving on: the rows read this visit leave with it.
                releaseRetainedReadRows(intent)
                load(refresh = true, pullRefresh = true, intent = intent)
            }
            SharedActivityIntent.LoadMore -> load(refresh = false, intent = intent)
            SharedActivityIntent.MarkAllRead -> markAllRead()
            is SharedActivityIntent.SetFilter -> setFilter(intent.filter)
            is SharedActivityIntent.MarkDone -> markDone(intent.item)
            is SharedActivityIntent.UnfollowThread -> unfollowThread(intent.item)
            is SharedActivityIntent.FollowThread -> followThread(intent.item)
            is SharedActivityIntent.MarkRead -> markRead(intent.item)
            is SharedActivityIntent.MarkUnread -> markUnread(intent.item)
            is SharedActivityIntent.ApplyReadReceiptPrediction -> applyReadReceiptPrediction(intent.prediction)
            is SharedActivityIntent.ApplyReadReceiptFailure -> handleReadReceiptFailure(intent.request)
            is SharedActivityIntent.ApplyReadReceiptResult ->
                handleReadReceiptResult(intent.request, intent.succeeded)
            is SharedActivityIntent.PrewarmVisibleReplyThreads -> prewarmVisibleReplyThreads(intent.markers)
            is SharedActivityIntent.PrewarmActivityTail -> prewarmActivityTail(intent.orderedMarkers)
            SharedActivityIntent.DetachPrewarmViewport -> prewarmVisibleReplyThreads(emptySet())
            SharedActivityIntent.SurfaceHidden -> onSurfaceHidden()
            is SharedActivityIntent.RowOpened -> onRowOpened(intent)
            is SharedActivityIntent.ChannelAccessLost -> dropRetainedRows(intent) { item ->
                item.touchesChannel(intent.channelIds)
            }
            is SharedActivityIntent.ApplyRealtimeEvent -> {
                if (intent.event is PerReceiverReadStateChanged) {
                    scope.launch { applyPerReceiverReadStateChanged(intent.event) }
                } else {
                    applyRealtimeEventImmediately(intent)
                }
            }
            is SharedActivityIntent.ApplyRealtimeSummaryEvent ->
                scope.launch { acceptRealtime(intent) }
        }
    }

    /** Applies a realtime intent and waits until the reconcile / load it may have started has settled. */
    suspend fun acceptRealtime(intent: SharedActivityIntent) {
        applyLiveRealtime(intent)
        if (intent is SharedActivityIntent.ApplyRealtimeEvent) {
            synchronized(realtimeOwnerLock) { realtimeBackgroundReconcileJob }?.join()
            awaitCurrentLoadIdle()
        }
    }

    /**
     * Live realtime ingress for the AppShell domain sink. It applies the realtime patch and returns; a
     * background reconcile or Activity load keeps running in the store scope. The sink runs under the
     * realtime authority registry lock, so waiting for that network work here would hold every later
     * realtime event (feedback bc6e392e: up to 17 s).
     */
    suspend fun applyLiveRealtime(intent: SharedActivityIntent) {
        when (intent) {
            is SharedActivityIntent.ApplyRealtimeEvent -> applyRealtimeEvent(intent)
            is SharedActivityIntent.ApplyRealtimeSummaryEvent ->
                applyRealtimeSummaryEvent(intent.event, intent.activeConversationId)
            else -> error("applyLiveRealtime only accepts realtime intents")
        }
    }

    private fun onSurfaceHidden() {
        activitySurfaceHidden = true
        synchronized(realtimeOwnerLock) {
            foregroundResetLoadStartedAtMillis = null
        }
        // Leaving the Activity tab or route ends the visit: the rows read in it leave.
        releaseRetainedReadRows(SharedActivityIntent.SurfaceHidden)
    }

    private fun onRowOpened(intent: SharedActivityIntent.RowOpened) {
        synchronized(realtimeOwnerLock) {
            retainReadRowLocked(intent, intent.item)
        }
    }

    /**
     * Access loss (left or removed from a channel, channel deleted, server left or
     * deleted): the retained rows [matching] cannot stay as "read" rows. Each leaves the list and the
     * retained set at once. A row that was merely read keeps its place (see [withRetainedReadRows]).
     */
    private fun dropRetainedRows(intent: SharedActivityIntent, matching: (InboxItem) -> Boolean) {
        synchronized(realtimeOwnerLock) {
            val current = _state.value
            if (current.retainedReadRowKeys.isEmpty()) return@synchronized
            val next = current.withRetainedRowsDropped(matching)
            if (next !== current) reduceState(intent, next)
        }
    }

    /** True when the realtime [event] means the current user lost access to the whole Server. */
    private fun RealtimeEvent.dropsServerAccess(current: SharedActivityState): Boolean =
        when (this) {
            is ServerMembershipRemoved -> serverId.value == current.ownerServerId
            is ServerDeleted -> serverId.value == current.ownerServerId
            is RealtimeRefreshRequired ->
                scope == RealtimeRefreshScope.ServerMembers &&
                    (reason == RealtimeSocketEvents.ServerMemberLeft || reason == RealtimeSocketEvents.ServerMemberRemoved) &&
                    memberId?.value != null && memberId?.value == current.ownerUserId &&
                    (serverId == null || serverId?.value == current.ownerServerId)
            else -> false
        }

    /**
     * `channel:updated` / `channel:members-updated` never mean access loss by themselves (web
     * re-reads the channel on them). For a channel with retained rows, re-read `GET /channels/:id`:
     * the server answers 404 only when the user can no longer read the channel (removed from a
     * private channel, deleted, wrong server). Only a 404 drops that channel's retained rows; a 500,
     * a network failure or any other answer is not access loss. One request per channel at a time;
     * a later event for the channel bumps its generation, so a result that was in flight over it is
     * stale and the check runs again (a stale 404 never drops rows after access was regained).
     */
    private fun checkRetainedChannelReadableLocked(channelId: String) {
        val current = _state.value
        if (!current.hasRetainedRowsFor(channelId)) return
        channelAccessGenerations[channelId] = (channelAccessGenerations[channelId] ?: 0L) + 1L
        if (channelAccessChecks[channelId]?.isActive == true) return
        val ownerServerId = current.ownerServerId
        val ownerUserId = current.ownerUserId
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (true) {
                        val generation = synchronized(realtimeOwnerLock) { channelAccessGenerations[channelId] ?: 0L }
                        val result = conversationRepository.getConversationDetail(ChannelId(channelId), DataRequestPriority.Background)
                        val lost = result is DataResult.Error && result.error.code == "http_404"
                        val rerun =
                            synchronized(realtimeOwnerLock) {
                                val latest = _state.value
                                when {
                                    latest.ownerServerId != ownerServerId || latest.ownerUserId != ownerUserId -> false
                                    (channelAccessGenerations[channelId] ?: 0L) != generation -> latest.hasRetainedRowsFor(channelId)
                                    else -> {
                                        if (lost) {
                                            dropRetainedRows(SharedActivityIntent.ChannelAccessLost(setOf(channelId))) { item ->
                                                item.touchesChannel(setOf(channelId))
                                            }
                                        }
                                        false
                                    }
                                }
                            }
                        if (!rerun) break
                    }
                } finally {
                    synchronized(realtimeOwnerLock) {
                        if (channelAccessChecks[channelId] === coroutineContext[Job]) channelAccessChecks.remove(channelId)
                    }
                }
            }
        channelAccessChecks[channelId] = job
        job.start()
    }

    private fun SharedActivityState.hasRetainedRowsFor(channelId: String): Boolean =
        retainedReadRowKeys.isNotEmpty() &&
            items.any { item -> item.key in retainedReadRowKeys && item.touchesChannel(setOf(channelId)) }

    private fun RealtimeEvent.channelUpdatedId(): String? =
        (this as? RealtimeRefreshRequired)
            ?.takeIf { it.scope == RealtimeRefreshScope.Home || it.scope == RealtimeRefreshScope.ChannelMembers }
            ?.takeIf {
                it.reason == RealtimeSocketEvents.ChannelUpdated || it.reason == RealtimeSocketEvents.ChannelMembersUpdated
            }
            ?.channelId?.value?.takeIf { it.isNotBlank() }

    private fun releaseRetainedReadRows(intent: SharedActivityIntent) {
        synchronized(realtimeOwnerLock) {
            val current = _state.value
            if (current.retainedReadRowKeys.isEmpty()) return@synchronized
            reduceState(intent, current.withRetainedReadRowsReleased())
        }
    }

    /**
     * [item] joins the rows retained for this visit when this filter drops read rows and [item] is
     * still unread (it is about to be read). Rows retained earlier in the visit stay. Opening a row
     * that is already read, or already retained, changes nothing.
     */
    private fun retainReadRowLocked(intent: SharedActivityIntent, item: InboxItem) {
        val current = _state.value
        if (current.selectedFilter.keepsReadActivityRows() || item.unreadCount <= 0) return
        if (item.key in current.retainedReadRowKeys) return
        reduceState(intent, current.copy(retainedReadRowKeys = current.retainedReadRowKeys + item.key))
    }

    fun clear() {
        val ownerServerId = synchronized(realtimeOwnerLock) {
            val owner = _state.value.ownerServerId
            loadGeneration += 1L
            loadJob?.cancel()
            loadJob = null
            foregroundResetLoadStartedAtMillis = null
            realtimeBackgroundReconcileJob?.cancel()
            realtimeBackgroundReconcileJob = null
            realtimeBackgroundReconcileDirty = false
            staleRecovery.reset()
            channelAccessChecks.values.forEach(Job::cancel)
            channelAccessChecks.clear()
            channelAccessGenerations.clear()
            realtimeHighWater.clear()
            resetRealtimeCatchUpLocked()
            pendingThreadFollowMutations = emptySet()
            deferredThreadFollows = emptyMap()
            owner
        }
        ownerServerId?.let(replyThreadFreshnessLedger::invalidateServer)
        clearReplyPrewarmState()
    }

    fun reset() {
        clear()
        presentationStore.clear()
        synchronized(realtimeOwnerLock) {
            readOverlay.clear()
            doneOverlay.clear()
            lastResetLoadStartedAtMillis = null
            readPredictionRevisions = emptyMap()
            readPredictionItemKeys = emptyMap()
            readPredictionHolds = emptyMap()
            resolvedMentionActionKeys = emptyMap()
            pendingThreadFollowMutations = emptySet()
            deferredThreadFollows = emptyMap()
            // task #399 review: parked ids and the fetch flag belong to the
            // old owner/server; a stale park would suppress hydration for the
            // next session's DM rows.
            unresolvedDmPeerChannelIds = emptySet()
            dmPeerNamesFetchInFlight = false
            _state.value = SharedActivityState()
        }
    }

    private fun prewarmVisibleReplyThreads(markers: Set<ActivityReplyThreadPrewarmMarker>) {
        val nextVisible = markers.map(::stampReplyThreadPrewarmMarker).associateBy { it.key }
        val previousVisible =
            synchronized(replyPrewarmOwnerLock) {
                val previous = visibleReplyPrewarmMarkers
                visibleReplyPrewarmMarkers = nextVisible
                previous
            }

        val activeKeys =
            synchronized(replyPrewarmOwnerLock) { nextVisible.keys + tailReplyPrewarmMarkers.keys }
        cancelReplyPrewarmJobsNotIn(activeKeys)

        nextVisible.values.forEach { marker ->
            val key = marker.key
            val enteredViewport = key !in previousVisible
            val versionChanged = previousVisible[key]?.contentGeneration !== marker.contentGeneration
            val shouldLaunch =
                synchronized(replyPrewarmOwnerLock) {
                    if (completedReplyPrewarmGenerations[key] === marker.contentGeneration) return@synchronized false
                    if (replyPrewarmJobs[key]?.isCompleted == false) return@synchronized false
                    if (
                        !enteredViewport &&
                        !versionChanged &&
                        failedReplyPrewarmGenerations[key] === marker.contentGeneration
                    ) {
                        return@synchronized false
                    }
                    activeReplyPrewarmMarkerLocked(key)?.contentGeneration === marker.contentGeneration
                }
            if (!shouldLaunch) {
                return@forEach
            }
            launchReplyThreadPrewarm(marker)
        }
    }

    private fun launchReplyThreadPrewarm(marker: ActivityReplyThreadPrewarmMarker): Job {
        val key = marker.key
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val enqueuedAtMillis = foregroundLoadGate.nowMillis()
                try {
                    val result =
                        replyPrewarmSemaphore.withPermit {
                            awaitForegroundLoadIdle(key)
                            if (foregroundOpenOwnsLoad(marker, enqueuedAtMillis)) return@withPermit null
                            synchronized(replyPrewarmOwnerLock) { replyPrewarmFetchingCount += 1 }
                            try {
                                prewarmReplyThread(marker)
                            } finally {
                                synchronized(replyPrewarmOwnerLock) { replyPrewarmFetchingCount -= 1 }
                            }
                        }
                    if (activeReplyPrewarmMarker(key)?.contentGeneration !== marker.contentGeneration) return@launch
                    when (result) {
                        null -> {
                            // The user opened this thread while its prewarm was still queued and that
                            // open left the thread's current content in the shared cache, so a second
                            // identical request afterwards would be pure waste.
                            val accepted =
                                synchronized(replyPrewarmOwnerLock) {
                                    if (activeReplyPrewarmMarkerLocked(key)?.contentGeneration !== marker.contentGeneration) {
                                        return@synchronized false
                                    }
                                    marker.contentGeneration?.let { completedReplyPrewarmGenerations[key] = it }
                                    failedReplyPrewarmGenerations.remove(key)
                                    true
                                }
                            if (!accepted) return@launch
                            diagnosticsLogger(
                                "activity_reply_prewarm_result result=skipped reason=foreground_open_owns_load " +
                                    "serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                                    "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
                            )
                        }
                        is DataResult.Content -> {
                            val accepted =
                                synchronized(replyPrewarmOwnerLock) {
                                    if (activeReplyPrewarmMarkerLocked(key)?.contentGeneration !== marker.contentGeneration) {
                                        return@synchronized false
                                    }
                                    marker.contentGeneration?.let { completedReplyPrewarmGenerations[key] = it }
                                    failedReplyPrewarmGenerations.remove(key)
                                    true
                                }
                            if (!accepted) return@launch
                            diagnosticsLogger(
                                "activity_reply_prewarm_result result=success serverId=${key.serverId} " +
                                    "parentChannelId=${key.parentChannelId} parentMessageId=${key.parentMessageId} " +
                                    "threadChannelId=${key.threadChannelId}"
                            )
                        }
                        is DataResult.Empty -> {
                            val accepted =
                                synchronized(replyPrewarmOwnerLock) {
                                    if (activeReplyPrewarmMarkerLocked(key)?.contentGeneration !== marker.contentGeneration) {
                                        return@synchronized false
                                    }
                                    marker.contentGeneration?.let { failedReplyPrewarmGenerations[key] = it }
                                    true
                                }
                            if (!accepted) return@launch
                            diagnosticsLogger(
                                "activity_reply_prewarm_result result=empty reason=${result.reason.name} " +
                                    "serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                                    "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
                            )
                        }
                        is DataResult.Error -> {
                            val accepted =
                                synchronized(replyPrewarmOwnerLock) {
                                    if (activeReplyPrewarmMarkerLocked(key)?.contentGeneration !== marker.contentGeneration) {
                                        return@synchronized false
                                    }
                                    marker.contentGeneration?.let { failedReplyPrewarmGenerations[key] = it }
                                    true
                                }
                            if (!accepted) return@launch
                            diagnosticsLogger(
                                "activity_reply_prewarm_result result=error code=${result.error.code} " +
                                    "serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                                    "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
                            )
                        }
                        is DataResult.NoPermission -> {
                            val accepted =
                                synchronized(replyPrewarmOwnerLock) {
                                    if (activeReplyPrewarmMarkerLocked(key)?.contentGeneration !== marker.contentGeneration) {
                                        return@synchronized false
                                    }
                                    marker.contentGeneration?.let { failedReplyPrewarmGenerations[key] = it }
                                    true
                                }
                            if (!accepted) return@launch
                            diagnosticsLogger(
                                "activity_reply_prewarm_result result=no_permission code=${result.blocker.code} " +
                                    "serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                                    "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
                            )
                        }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    synchronized(replyPrewarmOwnerLock) {
                        if (activeReplyPrewarmMarkerLocked(key)?.contentGeneration === marker.contentGeneration) {
                            marker.contentGeneration?.let { failedReplyPrewarmGenerations[key] = it }
                        }
                    }
                    diagnosticsLogger(
                        "activity_reply_prewarm_result result=exception errorType=${throwable::class.simpleName.orEmpty()} " +
                            "serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                            "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
                    )
                } finally {
                    val currentJob = currentCoroutineContext()[Job]
                    val retryMarker =
                        synchronized(replyPrewarmOwnerLock) {
                            if (replyPrewarmJobs[key] !== currentJob) return@synchronized null
                            replyPrewarmJobs.remove(key)
                            visibleReplyPrewarmMarkers[key]
                                ?.takeIf { latest -> latest.contentGeneration !== marker.contentGeneration }
                        }
                    retryMarker?.let(::launchReplyThreadPrewarm)
                }
            }
        val incumbent =
            synchronized(replyPrewarmOwnerLock) {
                if (activeReplyPrewarmMarkerLocked(key)?.contentGeneration !== marker.contentGeneration) {
                    return@synchronized null
                }
                replyPrewarmJobs[key]
                    ?.takeUnless(Job::isCompleted)
                    ?: run {
                        replyPrewarmJobs[key] = job
                        job
                    }
            }
        if (incumbent !== job) {
            job.cancel()
            return incumbent ?: job
        }
        diagnosticsLogger(
            "activity_reply_prewarm_start serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
        )
        job.start()
        return job
    }

    /**
     * Foreground priority: a visible conversation / thread page that is loading its first content
     * must not compete with background prewarm. Prewarms already loading finish; this one waits
     * (bounded by the gate's lease timeout) before it starts.
     */
    private suspend fun awaitForegroundLoadIdle(key: ActivityReplyThreadPrewarmKey) {
        val gate = foregroundLoadGate
        val foregroundLoads = gate.activeCount()
        if (foregroundLoads == 0) return
        val startedAt = gate.nowMillis()
        diagnosticsLogger(
            "activity_reply_prewarm_deferred reason=foreground_open queued=${queuedReplyPrewarmCount()} " +
                "foregroundLoads=$foregroundLoads serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
        )
        val outcome = gate.awaitIdle()
        val reason = if (outcome == SharedForegroundGateWait.TimedOut) "timeout" else "released"
        diagnosticsLogger(
            "activity_reply_prewarm_resumed reason=$reason waitedMs=${gate.nowMillis() - startedAt} " +
                "queued=${queuedReplyPrewarmCount()} serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
        )
    }

    /**
     * Drop a queued prewarm only when BOTH hold: the user opened this thread after it was queued, and
     * the shared cache now has that thread's current content. An open that failed or closed before
     * loading leaves no current entry, so the prewarm runs normally.
     */
    private suspend fun foregroundOpenOwnsLoad(marker: ActivityReplyThreadPrewarmMarker, sinceMillis: Long): Boolean {
        val key = marker.key
        val opened =
            foregroundLoadGate.foregroundOpenedSince(
                sinceMillis = sinceMillis,
                serverId = key.serverId,
                threadId = key.threadChannelId,
                parentChannelId = key.parentChannelId,
                parentMessageId = key.parentMessageId,
            )
        return opened && replyThreadContentCached(marker)
    }

    /** Registered prewarms that have not started loading (waiting on the semaphore or the gate). */
    private fun queuedReplyPrewarmCount(): Int =
        synchronized(replyPrewarmOwnerLock) {
            (replyPrewarmJobs.values.count { job -> !job.isCompleted } - replyPrewarmFetchingCount).coerceAtLeast(0)
        }

    // Lower-priority click-progressive "tail": after row N is consumed, warm the remaining
    // eligible rows from N+1 top-first, reusing the exact
    // visible-prewarm machinery (same marker/key, same prewarmReplyThread, same replyPrewarmSemaphore,
    // same completed/active/failed dedup maps, same shared candidate cache). Visible keys are handled
    // by the visible flow and skipped here. A single serialized loop launches one tail item at a
    // time and awaits it, so
    // the tail never holds more than one permit — it can only run when the visible set is not
    // saturating the shared Semaphore, and total concurrency stays bounded by that Semaphore.
    private fun prewarmActivityTail(orderedMarkers: List<ActivityReplyThreadPrewarmMarker>) {
        val nextTail = LinkedHashMap<ActivityReplyThreadPrewarmKey, ActivityReplyThreadPrewarmMarker>()
        orderedMarkers.map(::stampReplyThreadPrewarmMarker).forEach { marker ->
            nextTail.getOrPut(marker.key) { marker }
        }
        synchronized(replyPrewarmOwnerLock) { tailReplyPrewarmMarkers = nextTail }

        tailPrewarmLoopJob?.cancel()
        tailPrewarmLoopJob = null
        // The tail is generation-owned: it holds at most ONE in-flight launched job at a time. That
        // job is launched in the store scope (a sibling of the loop), so cancelling the loop alone
        // would orphan it. On replan we therefore also cancel the still-alive tail job. A version
        // change keeps the same key in nextTail (so the key-based cancelReplyPrewarmJobsNotIn below
        // will NOT reap it), which is exactly the case that would otherwise leave A(v1) running
        // while A(v2) is skipped as "already active". Cancelling this Activity-level waiter only
        // unwinds the local await(); the shared app-scope canonical Deferred keeps running in the
        // pool for any other observers joined to it.
        //
        // Persistent drain barrier: rapid consecutive replans (v1 -> v2 -> v3 ...) can arrive while
        // an earlier cancelled job is still unwinding AND the superseded loop is still parked before
        // it published a fresh tailInFlightJob. A plain snapshot of tailInFlightJob would be null in
        // that window, letting the newest generation run alongside the still-draining job. Instead
        // we track the one job that must drain in tailDrainBarrier: the current in-flight job if any,
        // otherwise the barrier already in progress. Every generation cancels and re-joins that SAME
        // job before launching, so all replans serialize behind it and tail concurrency stays <= 1.
        val draining = tailInFlightJob ?: tailDrainBarrier
        draining?.cancel()
        tailDrainBarrier = draining
        tailInFlightJob = null
        val activeKeys =
            synchronized(replyPrewarmOwnerLock) { visibleReplyPrewarmMarkers.keys + nextTail.keys }
        cancelReplyPrewarmJobsNotIn(activeKeys)

        if (nextTail.isEmpty() && draining == null) return
        val ordered = nextTail.values.toList()
        tailPrewarmLoopJob =
            scope.launch {
                // Await the drain barrier (chained across generations) before evaluating this order,
                // so tail concurrency never exceeds one and a re-versioned item is re-launched (not
                // skipped because its old-version job still looked "active"). Clear the barrier by
                // identity only after it has actually completed.
                draining?.join()
                if (tailDrainBarrier === draining) tailDrainBarrier = null
                runReplyPrewarmTail(ordered)
            }
    }

    private suspend fun runReplyPrewarmTail(orderedMarkers: List<ActivityReplyThreadPrewarmMarker>) {
        var launched = 0
        for (marker in orderedMarkers) {
            if (launched >= tailPrewarmDepthCap) return
            if (!shouldLaunchTailPrewarm(marker)) continue
            launched++
            val job = launchReplyThreadPrewarm(marker)
            tailInFlightJob = job
            job.join()
            if (tailInFlightJob === job) tailInFlightJob = null
        }
    }

    private fun shouldLaunchTailPrewarm(marker: ActivityReplyThreadPrewarmMarker): Boolean {
        return synchronized(replyPrewarmOwnerLock) {
            val key = marker.key
            if (key in visibleReplyPrewarmMarkers) return@synchronized false
            if (completedReplyPrewarmGenerations[key] === marker.contentGeneration) return@synchronized false
            if (replyPrewarmJobs[key]?.isCompleted == false) return@synchronized false
            if (failedReplyPrewarmGenerations[key] === marker.contentGeneration) return@synchronized false
            activeReplyPrewarmMarkerLocked(key)?.contentGeneration === marker.contentGeneration
        }
    }

    private fun activeReplyPrewarmMarker(
        key: ActivityReplyThreadPrewarmKey
    ): ActivityReplyThreadPrewarmMarker? =
        synchronized(replyPrewarmOwnerLock) { activeReplyPrewarmMarkerLocked(key) }

    private fun activeReplyPrewarmMarkerLocked(
        key: ActivityReplyThreadPrewarmKey
    ): ActivityReplyThreadPrewarmMarker? =
        visibleReplyPrewarmMarkers[key] ?: tailReplyPrewarmMarkers[key]

    private fun stampReplyThreadPrewarmMarker(
        marker: ActivityReplyThreadPrewarmMarker
    ): ActivityReplyThreadPrewarmMarker {
        _state.value.items
            .firstOrNull { item -> item.replyThreadPrewarmKey(marker.key.serverId) == marker.key }
            ?.let { item ->
                replyThreadFreshnessLedger.acceptSummary(
                    marker.key.serverId,
                    marker.key.threadChannelId,
                    item.replyThreadSummaryFact()
                )
            }
        val generation = replyThreadFreshnessLedger.captureContent(marker.key.serverId, marker.key.threadChannelId)
        return marker.copy(contentGeneration = generation)
    }

    private fun advanceReplyThreadContent(existing: InboxItem, patched: InboxItem) {
        val serverId = _state.value.ownerServerId ?: return
        val key = existing.replyThreadPrewarmKey(serverId) ?: return
        replyThreadFreshnessLedger.acceptSummary(
            key.serverId,
            key.threadChannelId,
            patched.replyThreadSummaryFact()
        )
    }

    private fun acceptMessageUpdatedFreshness(message: Message) {
        val serverId = _state.value.ownerServerId ?: return
        val candidateThreadIds =
            setOfNotNull(
                message.channelId?.value,
                message.threadChannelId?.value,
                message.threadId.value
            )
        val item =
            _state.value.items.firstOrNull { candidate ->
                candidate.kind == InboxItemKind.Thread &&
                    candidateThreadIds.any { threadId ->
                        candidate.threadChannelId?.value == threadId || candidate.channelId.value == threadId
                    }
            } ?: return
        val key = item.replyThreadPrewarmKey(serverId) ?: return
        replyThreadFreshnessLedger.acceptMessageMutation(key.serverId, key.threadChannelId, message)
    }

    private fun cancelReplyPrewarmJobsNotIn(activeKeys: Set<ActivityReplyThreadPrewarmKey>) {
        val jobsToCancel =
            synchronized(replyPrewarmOwnerLock) {
                replyPrewarmJobs
                    .filterKeys { key -> key !in activeKeys }
                    .values
                    .toList()
            }
        jobsToCancel.forEach(Job::cancel)
    }

    private fun clearReplyPrewarmState() {
        tailPrewarmLoopJob?.cancel()
        tailPrewarmLoopJob = null
        tailInFlightJob?.cancel()
        tailInFlightJob = null
        tailDrainBarrier?.cancel()
        tailDrainBarrier = null
        val jobsToCancel =
            synchronized(replyPrewarmOwnerLock) {
                val jobs = replyPrewarmJobs.values.toList()
                replyPrewarmJobs.clear()
                visibleReplyPrewarmMarkers = emptyMap()
                tailReplyPrewarmMarkers = emptyMap()
                completedReplyPrewarmGenerations.clear()
                failedReplyPrewarmGenerations.clear()
                jobs
            }
        jobsToCancel.forEach(Job::cancel)
    }

    private fun setFilter(filter: InboxFilter) {
        val changed =
            synchronized(realtimeOwnerLock) {
                if (_state.value.selectedFilter == filter) return@synchronized false
                realtimeHighWater.clear()
                // Switching filter releases the rows read this visit: they leave the filter they
                // were read in, and that filter's cached page too.
                val released = _state.value.withRetainedReadRowsReleased()
                val leftFilterCache =
                    released.snapshot?.let { left -> released.filterSnapshots + (released.selectedFilter to left) }
                        ?: released.filterSnapshots
                val cachedSnapshot = leftFilterCache[filter]
                _state.value =
                    released.copy(
                        filterSnapshots = leftFilterCache,
                        selectedFilter = filter,
                        snapshot = cachedSnapshot,
                        loaded = cachedSnapshot != null,
                        loading = cachedSnapshot == null,
                        refreshing = false,
                        pullRefreshing = false,
                        loadingMore = false,
                        errorCode = null,
                        loadMoreErrorCode = null
                    )
                true
            }
        if (!changed) return
        load(refresh = true, intent = SharedActivityIntent.SetFilter(filter))
    }

    /**
     * The shell switched to [SharedActivityIntent.ServerSelected.serverId] (Hands 20db9f61).
     *
     * One store serves the shell across server switches and the panel's mount-time LoadInitial
     * does not run again, so the switch owns the feed: the rows cached for that server (persist
     * key = user + server + filter) show at once, or the loading state when there are none, and a
     * load always revalidates them.
     *
     * Repeated intents for the server the store already owns are no-ops; the load started here
     * is the single flight for the switch and a later Refresh joins it.
     */
    private fun selectServer(intent: SharedActivityIntent.ServerSelected) {
        val serverId = intent.serverId.trim().takeIf { it.isNotBlank() } ?: return
        val switched =
            synchronized(realtimeOwnerLock) {
                val current = _state.value
                if (current.ownerServerId == serverId) return@synchronized false
                // Never bound to a server and nothing loading: the first LoadInitial binds the store.
                if (current.ownerServerId == null && loadJob?.isActive != true) return@synchronized false
                current.ownerServerId?.let(replyThreadFreshnessLedger::invalidateServer)
                realtimeHighWater.clear()
                resetRealtimeCatchUpLocked()
                presentationStore.clear()
                // Web registerServerReset (slock 1bb70cbeb, inboxStore.ts:1854-1898) starts the
                // new server on All; the persist slice hydrates that server's cached All rows.
                reduceState(
                    intent,
                    SharedActivityState(
                        ownerServerId = serverId,
                        ownerUserId = current.ownerUserId,
                        loading = true
                    )
                )
                true
            }
        if (!switched) return
        diagnosticsLogger(
            "store_load domain=activity phase=server_selected serverId=$serverId " +
                "cached=${_state.value.snapshot != null}"
        )
        load(refresh = true, intent = SharedActivityIntent.LoadInitial)
    }

    private suspend fun applyRealtimeEvent(intent: SharedActivityIntent.ApplyRealtimeEvent) {
        if (intent.event is PerReceiverReadStateChanged) {
            applyPerReceiverReadStateChanged(intent.event)
            return
        }
        applyRealtimeEventImmediately(intent)
    }

    private fun applyRealtimeEventImmediately(intent: SharedActivityIntent.ApplyRealtimeEvent) {
        val reconcileJob = synchronized(realtimeOwnerLock) { applyRealtimeEventLocked(intent) }
        reconcileJob?.start()
        ensureDmPeerNames()
    }

    private fun applyRealtimeEventLocked(intent: SharedActivityIntent.ApplyRealtimeEvent): Job? {
        val event = intent.event
        if (event.dropsServerAccess(_state.value)) {
            dropRetainedRows(intent) { true }
        }
        event.channelUpdatedId()?.let(::checkRetainedChannelReadableLocked)
        if (!event.refreshesActivity()) return null
        when (event) {
            // Not past a mute (the shell's allowLocalPatch): the served row stops there.
            is MessageNew -> if (intent.allowLocalPatch) raiseRealtimeCatchUpTargetLocked(event.message)
            is MessageUpdated -> {
                acceptMessageUpdatedFreshness(event.message)
                applyChannelMessageUpdatedPatch(event.message, intent)
            }
            is SyncResumeResponse ->
                _state.value.ownerServerId?.let(replyThreadFreshnessLedger::invalidateServer)
            is RealtimeRefreshRequired ->
                _state.value.ownerServerId?.let { serverId ->
                    replyThreadFreshnessLedger.invalidateServer(serverId)
                }
            else -> Unit
        }
        // dm:new refreshes like web (channelRealtimeSync.ts dmNew -> scheduleInboxRefresh):
        // a brand-new DM row can only arrive through the canonical projection.
        if (event is MessageNew || event is SyncResumeResponse || event is ThreadUpdated || event is DirectMessageCreated) {
            // Only All has stable membership under a message-only payload. Unread
            // and Mentions depend on receiver-specific read/mention facts that are
            // not fully carried by every realtime frame; patching those filters
            // exposes a transient row that the background projection may remove.
            if (intent.allowLocalPatch && _state.value.selectedFilter == InboxFilter.All) {
                when (event) {
                    is MessageNew -> applyMessageNewPatch(event.message, intent)
                    is ThreadUpdated -> applyThreadUpdatedPatch(event, intent)
                    else -> Unit
                }
            }
            // Match Web's Activity ownership: realtime first applies every safe
            // narrow patch, then schedules one debounced background canonical
            // projection. This admits brand-new rows and filter membership
            // changes without showing foreground refresh chrome or requiring a
            // pull gesture. Read-state and broad identity-only events never use
            // this owner.
            return scheduleRealtimeBackgroundReconcileLocked()
        }
        // Activity is snapshot-on-entry and patch-while-visible. Events whose
        // identity-only payload cannot safely produce a narrow row patch are
        // folded by their domain owners. They must never take full-list refresh
        // ownership while the user is reading Activity.
        return null
    }

    private fun applyMessageNewPatch(
        message: Message,
        intent: SharedActivityIntent.ApplyRealtimeEvent
    ): Boolean {
        onRealtimePatchOwnerEntered()
        val current = _state.value
        val snapshot = current.snapshot ?: return false
        val streamChannelId = message.channelId ?: message.threadChannelId ?: return false
        val existingIndex =
            snapshot.items.indexOfFirst { item ->
                item.kind == InboxItemKind.Thread &&
                    (item.threadChannelId == streamChannelId || item.channelId == streamChannelId)
            }
        if (existingIndex < 0) return applyChannelMessageNewPatch(message, intent, current, snapshot)
        val existing = snapshot.items[existingIndex]
        if (existing.lastMessageId == message.id || existing.latestActivityMessageId == message.id) return true
        // Web receiveThreadReply (slock 1bb70cbeb, inboxStore.ts:1712-1777): a row
        // carrying a content frontier advances only on a strictly newer socket
        // seq (fail closed when the frame has none); the canonical refetch
        // supplies the row otherwise. Unread is never touched locally: the
        // server owns it and the scheduled reconcile brings it.
        val incomingSeq = message.seq?.takeIf { it >= 0L }?.toString()
        if (existing.latestActivitySeq != null && compareActivitySeq(incomingSeq, existing.latestActivitySeq) <= 0) {
            return true
        }

        val patched =
            existing.copy(
                latestActivityMessageId = message.id,
                latestActivityPreview = message.body,
                latestActivityAt = message.timestampLabel,
                latestActivitySenderName = message.author.displayName,
                latestActivitySenderId = message.author.id.value,
                latestActivitySenderKind = message.author.kind,
                latestActivitySenderAvatarWireValue = message.author.avatar?.wireValue ?: message.author.avatarUrl,
                // Web inboxStore receiveThreadReply: `latestDelegatedBy` follows the new message.
                latestDelegatedBy = message.delegatedBy,
                replyCount = existing.replyCount + 1,
                latestActivitySeq = incomingSeq ?: existing.latestActivitySeq,
                // Keep the Done/read overlay marker in step with the patched row
                // (legacy Done only; see advancedActivityFrontier).
                readStateLatestActivitySeq = existing.advancedActivityFrontier(message.seq)
            ).withActivityFollowStateProjection()
        advanceReplyThreadContent(existing, patched)
        rememberRealtimeHighWater(patched)
        applyPatchedActivityItem(patched, existingIndex, current, snapshot, intent)
        return true
    }

    /**
     * `message:new` on an ordinary/joint channel or a DM (#triage 84d44d75; web
     * `receiveChannelMessage`, slock #11208). `GET /channels/inbox` is a
     * warehouse view that lags the write by ~1-2 s, so the re-read right after the event is often
     * one message behind. The event moves the EXISTING row's preview, sender and time only, and
     * only on a strictly newer seq than the row's `latestActivitySeq` (fail closed when either side
     * has none, like [applyMessageNewPatch]'s thread rows). Unread, mention, mute and visibility stay
     * with the server answer, and so do the Done frontiers; a channel without a row gets none here.
     * Messages past the channel's Activity mute (a mention included) never arrive here: the shell
     * passes `allowLocalPatch = false` for them (SharedHomeShellStore.allowsLocalActivityPatch).
     */
    private fun applyChannelMessageNewPatch(
        message: Message,
        intent: SharedActivityIntent.ApplyRealtimeEvent,
        current: SharedActivityState,
        snapshot: InboxSnapshot
    ): Boolean {
        // A thread reply carries its thread channel as `channelId`, which never names a channel row.
        val channelId = message.channelId ?: return false
        val existingIndex =
            snapshot.items.indexOfFirst { item -> item.isChannelOrDmRow() && item.channelId == channelId }
        if (existingIndex < 0) return false
        val existing = snapshot.items[existingIndex]
        if (existing.lastMessageId == message.id) return true
        val incomingSeq = message.seq?.takeIf { it >= 0L }?.toString() ?: return true
        if (existing.latestActivitySeq == null || compareActivitySeq(incomingSeq, existing.latestActivitySeq) <= 0) {
            return true
        }
        val patched =
            existing.copy(
                lastMessageId = message.id,
                lastMessagePreview = message.body,
                lastMessageAt = message.timestampLabel,
                lastMessageSenderName = message.author.displayName,
                lastMessageSenderId = message.author.id.value,
                lastMessageSenderKind = message.author.kind,
                lastMessageSenderAvatarWireValue = message.author.avatar?.wireValue ?: message.author.avatarUrl,
                latestDelegatedBy = message.delegatedBy,
                latestActivitySeq = incomingSeq
            )
        realtimeHighWater[patched.key] =
            ActivityRealtimeHighWater(
                marker = patched.lastMessageId,
                replyCount = 0,
                seq = incomingSeq,
                holdUntilMillis = nowMillis() + ActivityLocalMutationHoldMillis
            )
        applyPatchedActivityItem(patched, existingIndex, current, snapshot, intent)
        return true
    }

    /**
     * `message:updated` for the message a channel/DM row shows (`lastMessageId`), usually one
     * [applyChannelMessageNewPatch] put there and the lagging inbox view is still behind. The row's
     * preview follows the updated body in place, like the patch derives it; the held fields
     * ([reconcileRealtimeChannelRow] copies them from the shown row) then keep the new text until
     * the server catches up. Sender, time, seq, counts and order stay: a message's author and
     * creation time never change. A blank body is a sparse update and keeps the preview (thread
     * page merge, `body.ifBlank { body }`), except on a retraction (RFC 077, `deletedAt` set): the
     * server's placeholder `content` is authoritative and the old text must not stay on the row
     * (web socketBridge.messageUpdated -> inboxStore.retractPreview). No filter/mute gate: the update moves no row in or out, it only corrects text already shown.
     */
    private fun applyChannelMessageUpdatedPatch(
        message: Message,
        intent: SharedActivityIntent.ApplyRealtimeEvent
    ) {
        val body = message.body.takeIf { it.isNotBlank() || message.deletedAt != null } ?: return
        val current = _state.value
        val snapshot = current.snapshot ?: return
        val existingIndex =
            snapshot.items.indexOfFirst { item -> item.isChannelOrDmRow() && item.lastMessageId == message.id }
        if (existingIndex < 0) return
        val existing = snapshot.items[existingIndex]
        if (existing.lastMessagePreview == body) return
        val items = snapshot.items.toMutableList().apply { set(existingIndex, existing.copy(lastMessagePreview = body)) }
        reduceState(intent, current.copy(snapshot = snapshot.copy(items = items)))
    }

    private fun applyThreadUpdatedPatch(
        event: ThreadUpdated,
        intent: SharedActivityIntent.ApplyRealtimeEvent
    ): Boolean {
        val current = _state.value
        val snapshot = current.snapshot ?: return false
        val existingIndex =
            snapshot.items.indexOfFirst { item ->
                item.kind == InboxItemKind.Thread &&
                    (item.threadChannelId == event.threadChannelId || item.channelId == event.threadChannelId)
            }
        if (existingIndex < 0) return false
        val existing = snapshot.items[existingIndex]
        if (event.replyCount < existing.replyCount) return true
        // Web updateThreadActivityMeta (inboxStore.ts:1779-1795) moves only the
        // reply count and time; the event's unread never overwrites the row.
        val patched =
            existing.copy(
                replyCount = event.replyCount,
                latestActivityAt =
                    if (event.replyCount > existing.replyCount && event.lastReplyAt != null) {
                        event.lastReplyAt
                    } else {
                        existing.latestActivityAt
                    },
                // The reply this event counts carries its seq: keep the overlay
                // marker in step so a Done on the patched row covers it.
                readStateLatestActivitySeq =
                    if (event.replyCount > existing.replyCount) {
                        existing.advancedActivityFrontier(event.latestReply?.seq)
                    } else {
                        existing.readStateLatestActivitySeq
                    }
            ).withActivityFollowStateProjection()
        if (patched == existing) return true
        advanceReplyThreadContent(existing, patched)
        rememberRealtimeHighWater(patched)
        applyPatchedActivityItem(patched, existingIndex, current, snapshot, intent)
        return true
    }

    /**
     * Advances the row's authority frontier with a realtime message seq, but only
     * when the row already carries one: a row served without `readState` (old
     * server) keeps its anchor/reply-count overlay comparison, and a socket seq
     * must not turn it into a half-seq marker the next served copy cannot match.
     */
    private fun InboxItem.advancedActivityFrontier(realtimeSeq: Long?): String? {
        val existing = readStateLatestActivitySeq ?: return null
        // A storage-frontier Done (`doneFrontierSeq` present) covers only the
        // served frontier, so the marker stays on the served row (web never
        // moves it on a socket patch); legacy Done covers latest-at-request.
        if (doneFrontierSeqPresent) return existing
        val candidate = realtimeSeq?.takeIf { it >= 0L }?.toString() ?: return existing
        return if (compareActivitySeq(candidate, existing) > 0) candidate else existing
    }

    private fun applyPatchedActivityItem(
        patched: InboxItem,
        existingIndex: Int,
        current: SharedActivityState,
        snapshot: InboxSnapshot,
        intent: SharedActivityIntent.ApplyRealtimeEvent
    ) {
        val previous = snapshot.items[existingIndex]
        val projected = patched.withActivityFollowStateProjection()
        val items = listOf(projected) + snapshot.items.filterIndexed { index, _ -> index != existingIndex }
        reduceState(
            intent,
            current.copy(
                snapshot =
                    snapshot.copy(
                        items = items,
                        totalUnreadCount =
                            maxOf(0, snapshot.totalUnreadCount + projected.unreadCount - previous.unreadCount)
                    ),
                errorCode = null
            )
        )
    }

    private fun rememberRealtimeHighWater(item: InboxItem) {
        val previous = realtimeHighWater[item.key]
        if (previous != null && item.replyCount < previous.replyCount) return
        realtimeHighWater[item.key] =
            ActivityRealtimeHighWater(
                marker = item.latestActivityMessageId,
                replyCount = item.replyCount
            )
    }

    private fun scheduleRealtimeBackgroundReconcileLocked(): Job {
        realtimeBackgroundReconcileDirty = true
        realtimeBackgroundReconcileJob?.let { return it }
        // Leading + trailing throttle (web requestInboxBackgroundRefresh): the
        // first request after a quiet window fetches immediately; requests inside
        // the window coalesce into one trailing fetch at its end, so the latest
        // state still arrives. The window is measured from the last reset load of
        // ANY kind, and user-initiated loads never wait on it.
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                while (true) {
                    val waitMillis = synchronized(realtimeOwnerLock) { realtimeBackgroundRefreshWaitMillisLocked() }
                    if (waitMillis > 0L) delay(waitMillis)
                    val shouldReconcile =
                        synchronized(realtimeOwnerLock) {
                            if (realtimeBackgroundReconcileDirty) {
                                realtimeBackgroundReconcileDirty = false
                                true
                            } else {
                                realtimeBackgroundReconcileJob = null
                                false
                            }
                        }
                    if (!shouldReconcile) return@launch
                    awaitCurrentLoadIdle()
                    val restartedWindow =
                        synchronized(realtimeOwnerLock) {
                            if (realtimeBackgroundRefreshWaitMillisLocked() > 0L) {
                                // A load started while we waited; fetch on that window's trailing edge.
                                realtimeBackgroundReconcileDirty = true
                                true
                            } else {
                                false
                            }
                        }
                    if (restartedWindow) continue
                    load(
                        refresh = true,
                        pullRefresh = false,
                        backgroundRefresh = true,
                        intent = SharedActivityIntent.Refresh
                    )
                    awaitCurrentLoadIdle()
                }
            }
        realtimeBackgroundReconcileJob = job
        return job
    }

    private fun realtimeBackgroundRefreshWaitMillisLocked(): Long {
        val lastStartedAt = lastResetLoadStartedAtMillis ?: return 0L
        return (lastStartedAt + realtimeBackgroundRefreshIntervalMillis - nowMillis()).coerceAtLeast(0L)
    }

    private suspend fun awaitCurrentLoadIdle() {
        while (true) {
            val observed = synchronized(realtimeOwnerLock) { loadJob }
            observed?.join()
            if (synchronized(realtimeOwnerLock) { loadJob === observed }) return
        }
    }

    private suspend fun applyRealtimeSummaryEvent(
        event: RealtimeEvent,
        activeConversationId: String?
    ) {
        if (event is PerReceiverReadStateChanged) {
            applyPerReceiverReadStateChanged(event)
            return
        }
        val current = _state.value
        val snapshot = current.snapshot ?: return
        val currentUserId = currentUserId() ?: return
        val increment =
            synchronized(realtimeOwnerLock) {
                event.activitySummaryUnreadIncrement(snapshot, activeConversationId, currentUserId) { seq ->
                    // false = already counted; else record it (oldest dropped past the cap).
                    realtimeCountedUnreadSeqs.add(seq).also {
                        while (realtimeCountedUnreadSeqs.size > ActivityRealtimeCountedSeqLimit) {
                            realtimeCountedUnreadSeqs.remove(realtimeCountedUnreadSeqs.first())
                        }
                    }
                }
            }
        if (increment <= 0) return
        reduceState(
            SharedActivityIntent.ApplyRealtimeSummaryEvent(event, activeConversationId),
            current.copy(
                snapshot =
                    snapshot.copy(
                        totalUnreadCount = snapshot.totalUnreadCount + increment
                ),
                errorCode = null
            )
        )
    }

    private suspend fun applyPerReceiverReadStateChanged(event: PerReceiverReadStateChanged) {
        val session = authStore.readSession() ?: return
        val serverId = session.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId) ?: return
        if (event.serverId != null && event.serverId != serverId) return
        val memberId = session.user.id.trim().takeIf { it.isNotBlank() }?.let(::MemberId) ?: return
        var acceptedHere = false
        val reconcileJobs = mutableListOf<Job>()
        event.scopes.forEach { readScope ->
            val productionOwner =
                readReceiptOwnerFactory
                    ?.invoke(serverId.value, memberId.value)
                    ?.toReadStateV1ProductionOwner()
            if (productionOwner != null) {
                val disposition =
                    readStateV1Canary?.applyOwnerRealtime(
                        owner = productionOwner,
                        channelId = readScope.scopeId.value,
                        readStateVersion = readScope.readStateVersion,
                        maxReadSeq = readScope.maxReadSeq,
                    )
                if (disposition != null && disposition !is ReadStateV1ProductionDisposition.Legacy) {
                    return@forEach
                }
            }
            synchronized(realtimeOwnerLock) {
                val maxReadSeq = readScope.maxReadSeq ?: return@forEach
                val result =
                    perReceiverStateVersionStore.applyOrObserveReadStateFact(
                        serverId = serverId,
                        memberId = memberId,
                        scopeId = readScope.scopeId,
                        incomingVersion = readScope.readStateVersion,
                        incomingMaxReadSeq = maxReadSeq
                    )
                diagnosticsLogger(
                    "activity_read_state_apply serverId=${serverId.value} scopeId=${readScope.scopeId.value} " +
                        "version=${readScope.readStateVersion} maxReadSeq=$maxReadSeq result=${result.name.lowercase()}"
                )
                if (result == PerReceiverReadStateFactResult.Accepted) {
                    acceptedHere = true
                    projectRemoteReadLocked(
                        channelId = readScope.scopeId.value,
                        maxReadSeq = maxReadSeq,
                        intent = SharedActivityIntent.ApplyRealtimeEvent(event)
                    )?.let(reconcileJobs::add)
                }
            }
        }
        reconcileJobs.forEach(Job::start)
        if (acceptedHere) onUnreadCountsChanged()
    }

    /**
     * Cross-device / cross-tab read (web applyReadStateUpdate ->
     * inboxStore.applyReadStateProjection, botiverse/slock 1bb70cbeb
     * applyReadStateUpdate.ts:8-30, inboxStore.ts:1666-1707). Web projects a
     * read only when it can prove what it covers; here that proof is the row's
     * own authority frontier (`readState.latestActivity.seq`, same read-state
     * domain as `maxReadSeq`). A covered row is cleared (and leaves Unread)
     * with a read hold so a lagging refresh cannot resurrect it; a row the read
     * may not fully cover gets a throttled canonical reconcile instead.
     */
    private fun projectRemoteReadLocked(
        channelId: String,
        maxReadSeq: Long,
        intent: SharedActivityIntent
    ): Job? {
        val current = _state.value
        val snapshot = current.snapshot ?: return null
        val matching =
            snapshot.items.filter { item -> item.unreadCount > 0 && item.readReceiptChannelId()?.value == channelId }
        if (matching.isEmpty()) return null
        val readSeq = maxReadSeq.takeIf { it >= 0L }?.toString()
        val (covered, uncovered) =
            matching.partition { item ->
                val frontier = item.readStateLatestActivitySeq?.takeIf(::isCanonicalActivitySeq)
                frontier != null && compareActivitySeq(readSeq, frontier) >= 0
            }
        if (covered.isNotEmpty()) {
            // The read is already persisted server-side: start the hold now.
            val remoteReadOwner = overlayOwner(current)
            readOverlay.confirmAll(remoteReadOwner, rememberUnreadCleared(remoteReadOwner, covered))
            val coveredKeys = covered.mapTo(mutableSetOf(), InboxItem::key)
            val keepsRows = current.selectedFilter.keepsReadActivityRows()
            val items =
                snapshot.items.mapNotNull { item ->
                    when {
                        item.key !in coveredKeys -> item
                        current.keepsReadRow(item.key) ->
                            item.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false)
                        else -> null
                    }
                }
            reduceState(
                intent,
                current.copy(
                    snapshot =
                        snapshot.copy(
                            items = items,
                            totalCount = if (keepsRows) snapshot.totalCount else maxOf(0, snapshot.totalCount - covered.size),
                            totalUnreadCount = maxOf(0, snapshot.totalUnreadCount - covered.sumOf(InboxItem::unreadCount))
                        )
                )
            )
        }
        diagnosticsLogger(
            "activity_read_state_patch scopeId=$channelId maxReadSeq=$maxReadSeq " +
                "projected=${covered.size} reconcile=${uncovered.isNotEmpty()}"
        )
        return if (uncovered.isNotEmpty()) scheduleRealtimeBackgroundReconcileLocked() else null
    }

    internal fun applyReadStateV1Projection(owner: ReadStateV1ProductionOwner, fact: ReadStateV1Fact) {
        var reconcileJob: Job? = null
        val accepted = synchronized(realtimeOwnerLock) {
            val current = _state.value
            val serverId = current.ownerServerId ?: return@synchronized false
            val userId = current.ownerUserId ?: return@synchronized false
            val currentOwner =
                readReceiptOwnerFactory
                    ?.invoke(serverId, userId)
                    ?.toReadStateV1ProductionOwner()
                    ?: return@synchronized false
            if (currentOwner != owner) return@synchronized false
            diagnosticsLogger(
                "activity_read_state_patch result=durable_v1_projection scopeId=${fact.channelId} " +
                    "version=${fact.readStateVersion} maxReadSeq=${fact.maxReadSeq}"
            )
            reconcileJob =
                projectRemoteReadLocked(
                    channelId = fact.channelId,
                    maxReadSeq = fact.maxReadSeq,
                    intent =
                        SharedActivityIntent.ApplyRealtimeEvent(
                            PerReceiverReadStateChanged(
                                serverId = ServerId(serverId),
                                scopes =
                                    listOf(
                                        PerReceiverReadStateScope(
                                            scopeId = ChannelId(fact.channelId),
                                            maxReadSeq = fact.maxReadSeq,
                                            readStateVersion = fact.readStateVersion
                                        )
                                    )
                            )
                        )
                )
            true
        }
        reconcileJob?.start()
        if (accepted) onUnreadCountsChanged()
    }

    internal fun requestReadStateV1Rebaseline(owner: ReadStateV1ProductionOwner, channelId: String) {
        val accepted = synchronized(realtimeOwnerLock) {
            val current = _state.value
            val serverId = current.ownerServerId ?: return@synchronized false
            val userId = current.ownerUserId ?: return@synchronized false
            val currentOwner =
                readReceiptOwnerFactory
                    ?.invoke(serverId, userId)
                    ?.toReadStateV1ProductionOwner()
                    ?: return@synchronized false
            if (currentOwner != owner) return@synchronized false
            diagnosticsLogger("activity_read_state_patch result=rebaseline_requested scopeId=$channelId")
            true
        }
        if (accepted) onUnreadCountsChanged()
    }

    private fun load(
        refresh: Boolean,
        pullRefresh: Boolean = false,
        backgroundRefresh: Boolean = false,
        intent: SharedActivityIntent
    ) {
        val job =
            synchronized(realtimeOwnerLock) {
                val current = _state.value
                val inFlightLoad = loadJob?.takeUnless { job -> job.isCompleted }
                if (backgroundRefresh && inFlightLoad != null) return@synchronized null
                if (refresh && !pullRefresh && intent == SharedActivityIntent.Refresh && inFlightLoad != null) {
                    // Keyed on the job, not on loading/refreshing: those flags are only set once
                    // the launched load has read the session, so a same-frame second Refresh used
                    // to cancel and restart the first.
                    val inFlightAgeMillis = foregroundResetLoadStartedAtMillis?.let { startedAt -> nowMillis() - startedAt }
                    if (activityRefreshJoinsInFlightLoad(inFlightAgeMillis, refreshCoalesceWindowMillis)) {
                        diagnosticsLogger(
                            "store_load domain=activity phase=coalesced intent=refresh " +
                                "generation=$loadGeneration inFlightAgeMs=$inFlightAgeMillis"
                        )
                        return@synchronized null
                    }
                    if (inFlightAgeMillis != null) {
                        diagnosticsLogger(
                            "store_load domain=activity phase=supersede_stale intent=refresh " +
                                "generation=$loadGeneration inFlightAgeMs=$inFlightAgeMillis"
                        )
                    }
                }
                if (!refresh && (current.loading || current.refreshing || current.loadingMore || !current.hasMore)) {
                    return@synchronized null
                }
                val generation = ++loadGeneration
                val startedAtMillis = nowMillis()
                if (refresh) lastResetLoadStartedAtMillis = startedAtMillis
                foregroundResetLoadStartedAtMillis = startedAtMillis.takeIf { refresh && !backgroundRefresh }
                loadJob?.cancel()
                scope.launch(start = CoroutineStart.LAZY) {
                    val startedAt = TimeSource.Monotonic.markNow()
                    val previous = _state.value
                    var filter = previous.selectedFilter
                    var refreshLimit = SharedActivityPageSize
                    try {
                        logActivityLoadStart(
                            refresh = refresh,
                            pullRefresh = pullRefresh,
                            backgroundRefresh = backgroundRefresh,
                            filter = filter,
                            generation = generation
                        )
                        val session = authStore.readSession()
                        if (session == null) {
                            synchronized(realtimeOwnerLock) {
                                if (!backgroundRefresh) setLoadError("unauthenticated", append = !refresh)
                            }
                            logActivityLoad(
                                result = "error",
                                code = "unauthenticated",
                                refresh = refresh,
                                append = !refresh,
                                filter = filter,
                                backgroundRefresh = backgroundRefresh,
                                durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                                generation = generation,
                                pullRefresh = pullRefresh
                            )
                            return@launch
                        }
                        val serverId = session.currentServerId?.let(::ServerId)
                        if (serverId == null) {
                            synchronized(realtimeOwnerLock) {
                                if (!backgroundRefresh) setLoadError("server_missing", append = !refresh)
                            }
                            logActivityLoad(
                                result = "error",
                                code = "server_missing",
                                refresh = refresh,
                                append = !refresh,
                                filter = filter,
                                backgroundRefresh = backgroundRefresh,
                                durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                                generation = generation,
                                pullRefresh = pullRefresh
                            )
                            return@launch
                        }
                        val userId = session.user.id
                        val server = session.servers.firstOrNull { it.id == serverId.value }?.toServerSummary()
                        if (server == null) {
                            synchronized(realtimeOwnerLock) {
                                if (!backgroundRefresh) setLoadError("server_missing", append = !refresh)
                            }
                            // task #436: this exit previously logged nothing. After a start
                            // line, silence is read as "never terminated" — the stuck
                            // signature — so an unlogged exit makes the diagnostic
                            // manufacture the very false positive it exists to rule out.
                            logActivityLoad(
                                result = "error",
                                code = "server_missing",
                                refresh = refresh,
                                append = !refresh,
                                filter = filter,
                                backgroundRefresh = backgroundRefresh,
                                durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                                generation = generation,
                                pullRefresh = pullRefresh
                            )
                            return@launch
                        }
                        synchronized(realtimeOwnerLock) {
                            val stateBeforeOwner = _state.value
                            val ownerChanged =
                                stateBeforeOwner.ownerServerId?.let { it != serverId.value } == true ||
                                    stateBeforeOwner.ownerUserId?.let { it != userId } == true
                            if (ownerChanged) {
                                stateBeforeOwner.ownerServerId?.let { previousServerId ->
                                    replyThreadFreshnessLedger.invalidateServer(previousServerId)
                                }
                                realtimeHighWater.clear()
                                resetRealtimeCatchUpLocked()
                                presentationStore.clear()
                            }
                            val stateForOwner =
                                if (ownerChanged) {
                                    // Web registerServerReset (slock 1bb70cbeb,
                                    // inboxStore.ts:1854-1898) starts the new server on All.
                                    filter = InboxFilter.All
                                    SharedActivityState()
                                } else {
                                    stateBeforeOwner
                                }
                            refreshLimit =
                                maxOf(
                                    SharedActivityPageSize,
                                    stateForOwner.nextOffset ?: 0,
                                    stateForOwner.items.size
                                )
                            reduceState(
                                intent,
                                stateForOwner.copy(
                                    ownerServerId = serverId.value,
                                    ownerUserId = userId
                                )
                            )
                            val stateBeforeRequest = _state.value
                            val stateWithLocalProfiles =
                                if (refresh && stateBeforeRequest.snapshot != null) {
                                    stateBeforeRequest.copy(
                                        senderProfiles = stateBeforeRequest.senderProfiles + currentSenderProfiles(serverId)
                                    )
                                } else {
                                    stateBeforeRequest
                                }
                            if (backgroundRefresh) {
                                reduceState(intent, stateWithLocalProfiles)
                            } else {
                                reduceState(
                                    intent,
                                    stateWithLocalProfiles.copy(
                                        loading = refresh && stateWithLocalProfiles.snapshot == null,
                                        refreshing = refresh && stateWithLocalProfiles.snapshot != null,
                                        pullRefreshing = refresh && pullRefresh && stateWithLocalProfiles.snapshot != null,
                                        loadingMore = !refresh,
                                        errorCode = null,
                                        loadMoreErrorCode = null
                                    )
                                )
                            }
                        }
                        val result =
                            if (refresh) {
                                loadCanonicalDepth(serverId, filter, refreshLimit)
                            } else {
                                inboxRepository.loadMore(
                                    serverId,
                                    filter,
                                    previous.nextOffset ?: previous.items.size,
                                    SharedActivityPageSize
                                )
                            }
                        // task #302: guard on identity (user + server), not the access
                        // token. A 401 inside loadCanonicalDepth triggers a token
                        // rotation and the retry already returns fresh data; comparing
                        // tokens here discards that successful result as stale_session
                        // and leaves the visible feed on the pre-refresh snapshot.
                        val currentSession = authStore.readSession()
                        val requestSessionStillCurrent =
                            currentSession != null &&
                                currentSession.user.id == userId &&
                                currentSession.currentServerId == serverId.value
                        if (!requestSessionStillCurrent) {
                            synchronized(realtimeOwnerLock) {
                                if (generation == loadGeneration && !backgroundRefresh) {
                                    val current = _state.value
                                    reduceState(
                                        intent,
                                        current.copy(
                                            loading = false,
                                            refreshing = false,
                                            pullRefreshing = false,
                                            loadingMore = false
                                        )
                                    )
                                }
                            }
                            logActivityLoad(
                                result = "stale_session",
                                code = null,
                                refresh = refresh,
                                append = !refresh,
                                filter = filter,
                                backgroundRefresh = backgroundRefresh,
                                durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                                generation = generation,
                                pullRefresh = pullRefresh
                            )
                            return@launch
                        }
                        val senderProfiles = loadSenderProfiles(serverId)
                        val applied =
                            synchronized(realtimeOwnerLock) {
                                if (generation != loadGeneration) {
                                    false
                                } else {
                                    applyLoadResult(
                                        result = result,
                                        server = server,
                                        append = !refresh,
                                        senderProfiles = senderProfiles,
                                        intent = intent,
                                        backgroundRefresh = backgroundRefresh
                                    )
                                    true
                                }
                            }
                        if (!applied) {
                            logActivityLoadAbandoned(
                                reason = "superseded_before_apply",
                                generation = generation,
                                pullRefresh = pullRefresh,
                                backgroundRefresh = backgroundRefresh,
                                durationMs = startedAt.elapsedNow().inWholeMilliseconds
                            )
                            return@launch
                        }
                        ensureDmPeerNames()
                        logActivityLoad(
                            result = result.activityDiagnosticsResult(),
                            code = null,
                            refresh = refresh,
                            append = !refresh,
                            filter = filter,
                            backgroundRefresh = backgroundRefresh,
                            durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                            generation = generation,
                            pullRefresh = pullRefresh
                        )
                    } catch (cancellation: CancellationException) {
                        logActivityLoadAbandoned(
                            reason = "cancelled",
                            generation = generation,
                            pullRefresh = pullRefresh,
                            backgroundRefresh = backgroundRefresh,
                            durationMs = startedAt.elapsedNow().inWholeMilliseconds
                        )
                        throw cancellation
                    } catch (_: Throwable) {
                        val handled =
                            synchronized(realtimeOwnerLock) {
                                if (generation != loadGeneration) {
                                    false
                                } else {
                                    if (!backgroundRefresh) setLoadError("activity_network", append = !refresh)
                                    // A failed read ends the realtime catch-up (web parity, slock #11208).
                                    if (refresh) resetRealtimeCatchUpLocked()
                                    true
                                }
                            }
                        if (!handled) {
                            logActivityLoadAbandoned(
                                reason = "superseded_before_error",
                                generation = generation,
                                pullRefresh = pullRefresh,
                                backgroundRefresh = backgroundRefresh,
                                durationMs = startedAt.elapsedNow().inWholeMilliseconds
                            )
                            return@launch
                        }
                        logActivityLoad(
                            result = "exception",
                            code = "activity_network",
                            refresh = refresh,
                            append = !refresh,
                            filter = filter,
                            backgroundRefresh = backgroundRefresh,
                            durationMs = startedAt.elapsedNow().inWholeMilliseconds,
                            generation = generation,
                            pullRefresh = pullRefresh
                        )
                    }
                }.also { loadJob = it }
            }
        job?.start()
    }

    private suspend fun loadCanonicalDepth(
        serverId: ServerId,
        filter: InboxFilter,
        depth: Int
    ): DataResult<InboxSnapshot> {
        val targetDepth = depth.coerceAtLeast(SharedActivityPageSize)
        val initial = inboxRepository.loadInitial(serverId, filter, minOf(targetDepth, SharedActivityRefreshPageLimit))
        if (initial !is DataResult.Content) return initial

        var combined = initial.data.copy(items = initial.data.items.dedupeActivityItems())
        val seenOffsets = mutableSetOf<Int>()
        val maxContinuationRequests = targetDepth.toLong() + SharedActivityRefreshContinuationSlack
        var continuationRequests = 0
        var consecutiveNoProgressPages = 0
        while (combined.items.size < targetDepth && combined.hasMore) {
            val offset = combined.nextOffset ?: combined.items.size
            if (!seenOffsets.add(offset) || continuationRequests.toLong() >= maxContinuationRequests) {
                return DataResult.Error(ScreenError("activity_pagination_stalled"))
            }
            continuationRequests += 1
            val remaining = targetDepth - combined.items.size
            when (
                val page = inboxRepository.loadMore(
                    serverId,
                    filter,
                    offset,
                    minOf(remaining, SharedActivityRefreshPageLimit)
                )
            ) {
                is DataResult.Content -> {
                    val inferredNextOffset = offset.toLong() + page.data.items.size.toLong()
                    val nextOffset = page.data.nextOffset ?: inferredNextOffset.takeIf { it <= Int.MAX_VALUE }?.toInt()
                    val mergedItems = (combined.items + page.data.items).dedupeActivityItems()
                    // A page can be empty after the repository projects an
                    // unfollowed row out of an attention filter.  The raw
                    // server cursor still advanced, so that page is real
                    // pagination progress even though no visible item was
                    // appended.
                    val rawPageAdvancedWithNoVisibleRows =
                        page.data.items.isEmpty() && nextOffset != null && nextOffset > offset
                    consecutiveNoProgressPages =
                        if (mergedItems.size == combined.items.size && !rawPageAdvancedWithNoVisibleRows) {
                            consecutiveNoProgressPages + 1
                        } else {
                            0
                        }
                    val invalidNonTerminalPage =
                        page.data.hasMore &&
                            (nextOffset == null || nextOffset <= offset)
                    val exhaustedNoProgressBudget =
                        page.data.hasMore &&
                            mergedItems.size < targetDepth &&
                            consecutiveNoProgressPages > SharedActivityRefreshMaxNoProgressPages
                    if (invalidNonTerminalPage || exhaustedNoProgressBudget) {
                        return DataResult.Error(ScreenError("activity_pagination_stalled"))
                    }
                    combined =
                        page.data.copy(
                            items = mergedItems,
                            nextOffset = nextOffset
                        )
                }
                is DataResult.Empty -> {
                    combined = combined.copy(hasMore = false, nextOffset = null)
                    break
                }
                is DataResult.Error -> return page
                is DataResult.NoPermission -> return page
            }
        }
        return DataResult.Content(combined)
    }

    private fun logActivityLoad(
        result: String,
        code: String?,
        refresh: Boolean,
        append: Boolean,
        filter: InboxFilter,
        backgroundRefresh: Boolean = false,
        durationMs: Long,
        generation: Long,
        pullRefresh: Boolean
    ) {
        diagnosticsLogger(
            "store_load domain=activity refresh=$refresh background=$backgroundRefresh append=$append filter=${filter.name} " +
                "result=$result code=${code.orEmpty()} durationMs=$durationMs " +
                "generation=$generation pullRefresh=$pullRefresh pullRefreshingAfter=${_state.value.pullRefreshing}"
        )
    }

    /**
     * task #436: a load that never reaches a terminal branch previously left no
     * trace at all, so "no line" could not be told apart from "never started".
     *
     * Every post-start exit must emit exactly one terminal or abandoned line.
     * An unlogged exit is worse than no instrumentation: after a start line,
     * silence is the stuck signature, so a silent return makes this diagnostic
     * manufacture the false positive it exists to rule out.
     *
     * These lines are positive evidence only. A missing line means "not
     * observed" — delivery crosses two bounded queues that drop silently — and
     * never proves the load failed to terminate.
     */
    private fun logActivityLoadStart(
        refresh: Boolean,
        pullRefresh: Boolean,
        backgroundRefresh: Boolean,
        filter: InboxFilter,
        generation: Long
    ) {
        diagnosticsLogger(
            "store_load domain=activity phase=start refresh=$refresh background=$backgroundRefresh " +
                "pullRefresh=$pullRefresh filter=${filter.name} generation=$generation " +
                "pullRefreshingBefore=${_state.value.pullRefreshing}"
        )
    }

    private fun logActivityLoadAbandoned(
        reason: String,
        generation: Long,
        pullRefresh: Boolean,
        backgroundRefresh: Boolean,
        durationMs: Long
    ) {
        diagnosticsLogger(
            "store_load domain=activity phase=abandoned reason=$reason generation=$generation " +
                "currentGeneration=$loadGeneration pullRefresh=$pullRefresh background=$backgroundRefresh " +
                "durationMs=$durationMs pullRefreshingAfter=${_state.value.pullRefreshing}"
        )
    }

    private fun applyLoadResult(
        result: DataResult<InboxSnapshot>,
        server: ServerSummary,
        append: Boolean,
        senderProfiles: Map<String, SharedActivitySenderProfile>,
        intent: SharedActivityIntent,
        backgroundRefresh: Boolean = false
    ) {
        val current = _state.value
        // Task #164: the server's remembered page (at most 5 minutes old) never replaces rows this
        // filter already shows; it only fills a list that has nothing yet.
        val keepShownOverStale =
            !append && result is DataResult.Content && result.data.staleReason != null && current.snapshot != null
        val projectedContentData =
            (result as? DataResult.Content)?.data?.projectActivityFilter(current.selectedFilter)
        val owner = overlayOwner(current)
        val nextState =
            when {
                keepShownOverStale ->
                    current.copy(
                        loaded = true,
                        loading = false,
                        refreshing = false,
                        pullRefreshing = false,
                        loadingMore = false,
                        errorCode = null,
                        loadMoreErrorCode = null,
                        stale = true
                    )
                else -> applyCanonicalLoadResult(
                    result = result,
                    current = current,
                    projectedContentData = projectedContentData,
                    owner = owner,
                    server = server,
                    append = append,
                    senderProfiles = senderProfiles,
                    backgroundRefresh = backgroundRefresh
                )
            }
        acceptCanonicalReplyThreadFacts(nextState)
        val freshAnswer = !keepShownOverStale && (result as? DataResult.Content)?.data?.staleReason == null
        reduceState(
            intent = intent,
            nextState = nextState,
            summaryAuthoritative =
                !append &&
                    freshAnswer &&
                    current.selectedFilter == InboxFilter.All &&
                    (result is DataResult.Content || result is DataResult.Empty),
            summaryFactCounts =
                if (!append && freshAnswer && current.selectedFilter == InboxFilter.All) {
                    when (result) {
                        // The app badge / Home summary gets exactly the counts the
                        // snapshot shows: overlay-suppressed rows subtracted and the
                        // realtime unread floor respected, so totals do not bounce.
                        is DataResult.Content -> nextState.totalUnreadCount to nextState.totalCount
                        is DataResult.Empty -> 0 to 0
                        is DataResult.Error,
                        is DataResult.NoPermission -> null
                    }
                } else {
                    null
                },
            summarySource = "activity_canonical:${current.selectedFilter.wireValue}"
        )
        if (!append) {
            settleStaleRecovery(nextState, result)
            settleRealtimeCatchUpLocked(nextState, result)
        }
    }

    private fun raiseRealtimeCatchUpTargetLocked(message: Message) {
        // Only a message on a channel/DM row this list shows. A thread or task reply carries its
        // own conversation as `channelId` (web skips channelType thread/task before
        // noteInboxCatchUpTarget, inboxStore.ts:2117-2122); as a target it would name no served
        // channel row, read as "not behind" and end the catch-up of the channel just before it.
        // A `task` conversation context parses to no kind here, so the row match is the guard.
        if (message.conversationContext?.channelKind == ConversationKind.Thread) return
        if (message.conversationContext?.parentMessageId != null || message.parentMessageId != null) return
        val channelId = message.channelId ?: return
        val shown = _state.value.snapshot?.items.orEmpty()
        if (shown.none { item -> item.isChannelOrDmRow() && item.channelId == channelId }) return
        val incoming = message.seq?.takeIf { it >= 0L }?.toString() ?: return
        val target = realtimeCatchUpTarget
        if (target != null && compareActivitySeq(incoming, target.seq) <= 0) return
        realtimeCatchUpTarget = ActivityRealtimeCatchUpTarget(channelId, incoming)
        realtimeCatchUpRetries = 0
    }

    private fun resetRealtimeCatchUpLocked() {
        realtimeCatchUpRetryJob?.cancel()
        realtimeCatchUpRetryJob = null
        realtimeCatchUpTarget = null
        realtimeCatchUpRetries = 0
    }

    /**
     * #triage 84d44d75 (web `settleInboxCatchUp`, slock #11208): `GET /channels/inbox` lags the
     * write by ~1-2 s, so the read a `message:new` triggers can still serve that channel's row one
     * message behind. While a clean answer serves the target channel's row behind the target seq,
     * the list is re-read after [ActivityRealtimeCatchUpRetryDelaysMillis] (at most twice), each
     * through the realtime throttle, then the catch-up stops. A row the answer does not contain is
     * not behind (the filter may leave it out). A stale (`X-Raft-Stale`) or failed answer, 503
     * `rw_overloaded` included, ends it: [settleStaleRecovery] then re-reads at Retry-After pace.
     */
    private fun settleRealtimeCatchUpLocked(nextState: SharedActivityState, result: DataResult<InboxSnapshot>) {
        val target = realtimeCatchUpTarget ?: return
        val servedRow =
            (result as? DataResult.Content)
                ?.takeIf { !nextState.stale }
                ?.data?.items
                ?.firstOrNull { item -> item.isChannelOrDmRow() && item.channelId == target.channelId }
        val servedSeq = servedRow?.latestActivitySeq
        val behind = servedRow != null && compareActivitySeq(servedSeq, target.seq) < 0
        if (!behind || realtimeCatchUpRetries >= ActivityRealtimeCatchUpRetryDelaysMillis.size) {
            if (behind || nextState.stale || result !is DataResult.Content) {
                diagnosticsLogger(
                    "store_load domain=activity phase=catch_up_end target=${target.seq} " +
                        "behind=$behind stale=${nextState.stale} retries=$realtimeCatchUpRetries"
                )
            }
            resetRealtimeCatchUpLocked()
            return
        }
        if (realtimeCatchUpRetryJob != null) return
        val waitMillis = ActivityRealtimeCatchUpRetryDelaysMillis[realtimeCatchUpRetries]
        realtimeCatchUpRetries += 1
        diagnosticsLogger(
            "store_load domain=activity phase=catch_up_scheduled target=${target.seq} served=$servedSeq " +
                "attempt=$realtimeCatchUpRetries waitMs=$waitMillis"
        )
        realtimeCatchUpRetryJob =
            scope.launch {
                delay(waitMillis)
                val self = currentCoroutineContext()[Job]
                val reconcileJob =
                    synchronized(realtimeOwnerLock) {
                        if (realtimeCatchUpRetryJob !== self) return@synchronized null
                        realtimeCatchUpRetryJob = null
                        if (activitySurfaceHidden) null else scheduleRealtimeBackgroundReconcileLocked()
                    }
                reconcileJob?.start()
            }
    }

    /**
     * Task #164: while the shown Activity is stale, one background recovery read is armed (backoff,
     * see [RaftStaleReadRecovery]); a fresh canonical answer (Content or Empty) disarms it. Other
     * failures change nothing: they are not the read warehouse.
     */
    private fun settleStaleRecovery(nextState: SharedActivityState, result: DataResult<InboxSnapshot>) {
        when {
            nextState.stale -> {
                val retryAfterMillis =
                    (result as? DataResult.Content)?.data?.staleRetryAfterMillis
                        ?: (result as? DataResult.Error)?.error?.rwRetryAfterMillis
                staleRecovery.schedule(retryAfterMillis) { recoverStaleActivity() }
            }
            result is DataResult.Content || result is DataResult.Empty -> staleRecovery.reset()
        }
    }

    /** Returns whether a load runs whose answer settles recovery. */
    private fun recoverStaleActivity(): Boolean {
        val current = _state.value
        if (!current.stale) return false
        if (activitySurfaceHidden) {
            // Entering Activity always refreshes; that read settles recovery.
            diagnosticsLogger("store_load domain=activity phase=stale_recovery_skip reason=surface_hidden")
            return false
        }
        // Single-flight: a background load joins nothing while another load runs (that one settles).
        load(refresh = true, pullRefresh = false, backgroundRefresh = true, intent = SharedActivityIntent.Refresh)
        return true
    }

    private fun applyCanonicalLoadResult(
        result: DataResult<InboxSnapshot>,
        current: SharedActivityState,
        projectedContentData: InboxSnapshot?,
        owner: ActivityOverlayOwner?,
        server: ServerSummary,
        append: Boolean,
        senderProfiles: Map<String, SharedActivitySenderProfile>,
        backgroundRefresh: Boolean
    ): SharedActivityState =
            when (result) {
                is DataResult.Content -> {
                    val projectedData = projectedContentData
                        ?: error("content projection missing")
                    val pageItems =
                        projectedData.items
                            .withActivityThreadTaskProjection(result.data.server.id.value, tasksDataStore)
                            .reconcileLocallyClearedUnreadItems(current.selectedFilter, owner)
                            .reconcileLocallyDoneItems(owner)
                    beforeRealtimeHighWaterFold()
                    val realtimeReconcile =
                        reconcileRealtimeHighWater(
                            canonicalItems = pageItems.items,
                            append = append,
                            preservePendingRealtimeUnread = backgroundRefresh
                        )
                    val items =
                        if (append) {
                            (current.items + realtimeReconcile.items).dedupeActivityItems()
                        } else {
                            realtimeReconcile.items.withRetainedReadRows(current).dedupeActivityItems()
                        }
                            // RFC 077: a re-fetched row of the same latest message without
                            // `latestDelegatedBy` keeps the known label.
                            .withKnownLatestDelegatedLabelsFrom(current.items)
                    val adjustedCanonicalUnreadCount =
                        maxOf(
                            0,
                            projectedData.totalUnreadCount -
                                pageItems.suppressedUnreadCount -
                                pageItems.suppressedDoneUnreadCount
                        )
                    val canonicalUnreadCount =
                        if (realtimeReconcile.preserveTotalUnreadFloor) {
                            maxOf(current.totalUnreadCount, adjustedCanonicalUnreadCount)
                        } else {
                            adjustedCanonicalUnreadCount
                        }
                    val nextState =
                        current.copy(
                            snapshot =
                                result.data.copy(
                                    items = items,
                                    totalCount =
                                        if (current.selectedFilter == InboxFilter.Unread) {
                                            maxOf(0, projectedData.totalCount - pageItems.removedUnreadRows - pageItems.removedDoneRows)
                                        } else {
                                            maxOf(0, projectedData.totalCount - pageItems.removedDoneRows)
                                        },
                                    totalUnreadCount =
                                        canonicalUnreadCount,
                                    hasMore = result.data.hasMore,
                                    nextOffset = result.data.nextOffset
                                ),
                            senderProfiles = if (append) current.senderProfiles + senderProfiles else senderProfiles,
                            loaded = true,
                            loading = false,
                            refreshing = false,
                            pullRefreshing = false,
                            loadingMore = false,
                            errorCode = null,
                            loadMoreErrorCode = null,
                            // A page appended after the first is never remembered by the server.
                            stale = if (append) current.stale else result.data.staleReason != null
                        )
                    nextState
                }
                is DataResult.Empty -> {
                    val realtimeReconcile =
                        if (!append) {
                            reconcileRealtimeHighWater(
                                canonicalItems = emptyList(),
                                append = false,
                                preservePendingRealtimeUnread = backgroundRefresh
                            )
                        } else {
                            ActivityRealtimeReconcileResult(items = emptyList())
                        }
                    if (!append && !realtimeReconcile.preserveTotalUnreadFloor) {
                        realtimeHighWater.clear()
                    }
                    val snapshot =
                        if (append) {
                            current.snapshot?.copy(hasMore = false, nextOffset = null)
                                ?: InboxSnapshot(server = server, items = emptyList())
                        } else {
                            InboxSnapshot(
                                server = server,
                                items = realtimeReconcile.items.withRetainedReadRows(current),
                                totalCount =
                                    if (realtimeReconcile.preserveTotalUnreadFloor) {
                                        maxOf(current.totalCount, realtimeReconcile.items.size)
                                    } else {
                                        0
                                    },
                                totalUnreadCount =
                                    if (realtimeReconcile.preserveTotalUnreadFloor) {
                                        current.totalUnreadCount
                                    } else {
                                        0
                                    }
                            )
                        }
                    val nextState =
                        current.copy(
                            snapshot = snapshot,
                            senderProfiles =
                                if (append || realtimeReconcile.preserveTotalUnreadFloor) {
                                    current.senderProfiles + senderProfiles
                                } else {
                                    senderProfiles
                                },
                            loaded = true,
                            loading = false,
                            refreshing = false,
                            pullRefreshing = false,
                            loadingMore = false,
                            errorCode = null,
                            loadMoreErrorCode = null,
                            stale = if (append) current.stale else false
                        )
                    nextState
                }
                is DataResult.Error -> {
                    // Task #164: an overload while rows are shown keeps them and marks them stale;
                    // with nothing shown the page keeps its load-failed state with Retry.
                    val stale = current.stale || (!append && result.error.isRwOverloaded && current.snapshot != null)
                    if (backgroundRefresh) {
                        current.copy(stale = stale)
                    } else {
                        current.copy(
                            loading = false,
                            refreshing = false,
                            pullRefreshing = false,
                            loadingMore = false,
                            errorCode = if (append) null else result.error.code,
                            loadMoreErrorCode = if (append) result.error.code else null,
                            stale = stale
                        )
                    }
                }
                is DataResult.NoPermission ->
                    if (backgroundRefresh) {
                        current
                    } else {
                        current.copy(
                            loading = false,
                            refreshing = false,
                            pullRefreshing = false,
                            loadingMore = false,
                            errorCode = if (append) null else result.blocker.code,
                            loadMoreErrorCode = if (append) result.blocker.code else null
                        )
                    }
            }

    private fun acceptCanonicalReplyThreadFacts(nextState: SharedActivityState) {
        val serverId = nextState.ownerServerId ?: return
        nextState.items.forEach { item ->
            val key = item.replyThreadPrewarmKey(serverId) ?: return@forEach
            replyThreadFreshnessLedger.acceptSummary(
                key.serverId,
                key.threadChannelId,
                item.replyThreadSummaryFact()
            )
        }
    }

    private fun reconcileRealtimeHighWater(
        canonicalItems: List<InboxItem>,
        append: Boolean,
        preservePendingRealtimeUnread: Boolean
    ): ActivityRealtimeReconcileResult {
        if (append || realtimeHighWater.isEmpty()) {
            return ActivityRealtimeReconcileResult(items = canonicalItems)
        }
        val currentThreads =
            _state.value.items
                .asSequence()
                .filter { item -> item.kind == InboxItemKind.Thread }
                .associateBy { item -> item.key }
        val currentChannels =
            _state.value.items
                .asSequence()
                .filter { item -> item.isChannelOrDmRow() }
                .associateBy { item -> item.key }
        val keptChannelKeys = mutableSetOf<String>()
        val canonicalKeys = canonicalItems.mapTo(mutableSetOf()) { item -> item.key }
        var preserveTotalUnreadFloor = false
        val reconciled =
            canonicalItems.map { canonical ->
                val localVersion = realtimeHighWater[canonical.key] ?: return@map canonical
                if (canonical.isChannelOrDmRow()) {
                    val reconciledRow = reconcileRealtimeChannelRow(canonical, localVersion, currentChannels[canonical.key])
                    if (reconciledRow !== canonical) keptChannelKeys += canonical.key
                    return@map reconciledRow
                }
                val current = currentThreads[canonical.key]
                if (current == null) {
                    realtimeHighWater.remove(canonical.key)
                    return@map canonical
                }
                // A canonical row that reached (or passed) the local patch wins
                // outright, unread included: patches never invent unread, so
                // there is no local unread to preserve (web takes the incoming
                // row, preserveNewerThreadActivity, inboxStore.ts:813-870).
                if (
                    canonical.replyCount > localVersion.replyCount ||
                    (
                        canonical.replyCount == localVersion.replyCount &&
                            canonical.latestActivityMessageId == localVersion.marker
                        )
                ) {
                    realtimeHighWater.remove(canonical.key)
                    return@map canonical
                }
                preserveTotalUnreadFloor = true
                canonical.withRealtimeActivityProjection(current)
            }
        val missingCurrent =
            if (preservePendingRealtimeUnread) {
                currentThreads.values.filter { current ->
                    current.key !in canonicalKeys && current.key in realtimeHighWater
                }
            } else {
                emptyList()
            }
        if (missingCurrent.isNotEmpty()) preserveTotalUnreadFloor = true
        realtimeHighWater.keys.toList().forEach { key ->
            if (
                key !in canonicalKeys &&
                (!preservePendingRealtimeUnread || key !in currentThreads)
            ) {
                realtimeHighWater.remove(key)
            }
        }
        return ActivityRealtimeReconcileResult(
            // A serving-row projection can lag the message event that produced
            // the local high-water. Keep that row in its current position until
            // a canonical response carries the same marker; otherwise the UI
            // exposes an appear→disappear flash between the two stages.
            items =
                (missingCurrent + reconciled).dedupeActivityItems().let { items ->
                    // A kept channel row holds the newest message, which the served order (the
                    // view's, without it) does not know: it stays on top instead of dropping back
                    // (web preserveNewerChannelActivity). Rows held from realtime (kept channel
                    // rows and missing thread rows) keep the order they are shown in now, so a
                    // stale answer never flips them.
                    if (keptChannelKeys.isEmpty()) {
                        items
                    } else {
                        val shownOrder = _state.value.items.withIndex().associate { (index, item) -> item.key to index }
                        val heldKeys = keptChannelKeys + missingCurrent.map(InboxItem::key)
                        val held = items.filter { it.key in heldKeys }.sortedBy { shownOrder[it.key] ?: Int.MAX_VALUE }
                        held + items.filterNot { it.key in heldKeys }
                    }
                },
            preserveTotalUnreadFloor = preserveTotalUnreadFloor
        )
    }

    /**
     * A channel/DM row patched from `message:new` keeps its newer message until the server's row
     * reaches that seq or the stale-list bound ([ActivityLocalMutationHoldMillis]) lapses (web
     * `preserveNewerChannelActivity`, slock #11208). Only the message fields come from the patch:
     * unread, mention and every other server fact are the answer's.
     */
    private fun reconcileRealtimeChannelRow(
        canonical: InboxItem,
        localVersion: ActivityRealtimeHighWater,
        current: InboxItem?
    ): InboxItem {
        val localSeq = localVersion.seq
        val holdLapsed = localVersion.holdUntilMillis?.let { until -> nowMillis() > until } ?: true
        if (
            current == null ||
            localSeq == null ||
            current.latestActivitySeq != localSeq ||
            holdLapsed ||
            canonical.latestActivitySeq == null ||
            compareActivitySeq(canonical.latestActivitySeq, localSeq) >= 0
        ) {
            realtimeHighWater.remove(canonical.key)
            return canonical
        }
        return canonical.copy(
            lastMessageId = current.lastMessageId,
            lastMessagePreview = current.lastMessagePreview,
            lastMessageAt = current.lastMessageAt,
            lastMessageSenderName = current.lastMessageSenderName,
            lastMessageSenderId = current.lastMessageSenderId,
            lastMessageSenderKind = current.lastMessageSenderKind,
            lastMessageSenderAvatarWireValue = current.lastMessageSenderAvatarWireValue,
            latestDelegatedBy = current.latestDelegatedBy,
            latestActivitySeq = current.latestActivitySeq
        )
    }

    private fun InboxItem.withRealtimeActivityProjection(current: InboxItem): InboxItem =
        // Until the canonical row reaches the same message/reply high-water, it
        // predates the local message fact. Preserve the locally-derived unread
        // range as part of that same atomic projection; otherwise the stale
        // snapshot briefly flips the row back to read before its successor.
        copy(
            latestActivityMessageId = current.latestActivityMessageId,
            latestActivityPreview = current.latestActivityPreview,
            latestActivityAt = current.latestActivityAt,
            latestActivitySenderName = current.latestActivitySenderName,
            latestActivitySenderId = current.latestActivitySenderId,
            latestActivitySenderKind = current.latestActivitySenderKind,
            latestActivitySenderAvatarWireValue = current.latestActivitySenderAvatarWireValue,
            latestDelegatedBy = current.latestDelegatedBy,
            replyCount = current.replyCount,
            unreadCount = current.unreadCount,
            firstUnreadMessageId = current.firstUnreadMessageId
        ).withActivityFollowStateProjection()

    private fun markAllRead() {
        val previous = _state.value
        val snapshot = previous.snapshot ?: return
        if (snapshot.totalUnreadCount <= 0) return
        val keepsRows = previous.selectedFilter.keepsReadActivityRows()
        val updatedSnapshot =
            snapshot.copy(
                // The list does not change: every listed row stays, shown read, until the user
                // moves on. In Unread they all join the rows retained for this visit (stdrc 10-23).
                items = snapshot.items.map { it.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false) },
                totalCount = if (keepsRows) snapshot.totalCount else 0,
                totalUnreadCount = 0
            )
        val retainedReadRowKeys =
            if (keepsRows) {
                previous.retainedReadRowKeys
            } else {
                snapshot.items.mapTo(previous.retainedReadRowKeys.toMutableSet(), InboxItem::key)
            }
        val overlayOwner = overlayOwner(previous)
        val readGenerations = rememberUnreadCleared(overlayOwner, snapshot.items)
        reduceState(
            SharedActivityIntent.MarkAllRead,
            previous.copy(snapshot = updatedSnapshot, errorCode = null, retainedReadRowKeys = retainedReadRowKeys)
        )
        scope.launch {
            withOverlaySettlement(readOverlay, overlayOwner, readGenerations) {
                val serverId = currentServerId()
                val result = serverId?.let { readReceiptRepository.markInboxReadAll(it) }
                    ?: DataResult.NoPermission(PermissionBlocker(code = "server_missing"))
                val owner =
                    readReceiptOwnerFactory
                        ?.invoke(previous.ownerServerId.orEmpty(), previous.ownerUserId.orEmpty())
                        ?.toReadStateV1ProductionOwner()
                var selected = false
                if (result is DataResult.Content && owner != null) {
                    result.data.scopes.forEach { ack ->
                        val disposition =
                            readStateV1Canary?.applyMutationAck(
                                owner = owner,
                                channelId = ack.channelId.value,
                                readStateVersion = ack.readStateVersion,
                                maxReadSeq = ack.maxReadSeq,
                            )
                        selected = selected || (disposition != null && disposition !is ReadStateV1ProductionDisposition.Legacy)
                    }
                }
                if (selected || result is DataResult.Content || result is DataResult.Empty) {
                    readOverlay.confirmAll(overlayOwner, readGenerations)
                    onUnreadCountsChanged()
                    // Web refreshes in the background after a successful read-all
                    // (slock 1bb70cbeb, inboxStore.ts:1405-1408); the read hold keeps a
                    // lagging copy from undoing the local clear.
                    load(refresh = true, pullRefresh = false, backgroundRefresh = true, intent = SharedActivityIntent.Refresh)
                } else {
                    readOverlay.forgetAll(overlayOwner, readGenerations)
                    onUnreadCountsChanged()
                    refreshAfterFailure(previous, "activity_mark_all_read_failed")
                }
            }
        }
    }

    private fun markDone(requestedItem: InboxItem) {
        val previous = _state.value
        if (previous.snapshot == null) return
        val overlayOwner = overlayOwner(previous)
        if (requestedItem.kind == InboxItemKind.MentionAction) {
            // Local-only resolution: there is no server write and no newer marker
            // that could ever retire it, so it lives outside the expiring overlay
            // (a 90s hold would let the unresolved row come back).
            if (overlayOwner != null) rememberResolvedMentionAction(overlayOwner, requestedItem.key)
            removeItemOptimistically(requestedItem)
            return
        }
        // Web inboxStore.markDone (slock 1bb70cbeb, inboxStore.ts:1417-1470):
        // bind Done to the row this store currently holds and name its
        // storage-space frontier. A row from a server that predates
        // `doneFrontierSeq` takes the legacy body; a present-but-unusable
        // frontier (or a row no longer held) refreshes and writes nothing.
        val item = previous.items.firstOrNull { it.key == requestedItem.key }
        val frontier = item?.activityDoneRequestFrontier()?.toInboxDoneFrontier()
        if (item == null || frontier == null) {
            diagnosticsLogger(
                "activity_mark_done result=refused reason=${if (item == null) "row_not_held" else "invalid_done_frontier"} " +
                    "itemKey=${requestedItem.key}"
            )
            load(refresh = true, pullRefresh = false, intent = SharedActivityIntent.Refresh)
            return
        }
        val targetChannelId = item.readReceiptChannelId()
        if (targetChannelId == null) {
            refreshAfterFailure(previous, "activity_thread_channel_missing")
            return
        }
        // The overlay entry is pending until the server confirms the Done;
        // confirmation starts this row's own hold.
        val doneGenerations =
            overlayOwner
                ?.let { owner -> doneOverlay.remember(owner, item) }
                ?.let { generation -> mapOf(item.key to generation) }
                .orEmpty()
        removeItemOptimistically(item)
        scope.launch {
            withOverlaySettlement(doneOverlay, overlayOwner, doneGenerations) {
                val result =
                    if (item.kind == InboxItemKind.Thread) {
                        conversationRepository.markThreadDone(targetChannelId, frontier)
                    } else {
                        inboxRepository.markDone(targetChannelId, frontier)
                    }
                if (result !is DataResult.Content && result !is DataResult.Empty) {
                    // 409/412 (frontier moved / space required) land here too: like
                    // web, drop the hold and take the refreshed row; never replay.
                    doneOverlay.forgetAll(overlayOwner, doneGenerations)
                    refreshAfterFailure(previous, "activity_mark_done_failed")
                } else {
                    doneOverlay.confirmAll(overlayOwner, doneGenerations)
                    onUnreadCountsChanged()
                    // Web refreshes after a confirmed Done with the hold still armed.
                    load(refresh = true, pullRefresh = false, backgroundRefresh = true, intent = SharedActivityIntent.Refresh)
                }
            }
        }
    }

    /**
     * Apply an Activity thread follow mutation through the existing shared
     * thread transport.  The optimistic projection is intentionally limited
     * to the server-owned boolean and attention clearing; it never invents
     * unread/mention state when a row is followed again.
     */
    private fun unfollowThread(item: InboxItem) {
        if (!item.isThreadActivityItem() || item.isUnfollowedActivityThread) return
        val currentItem = _state.value.items.firstOrNull { it.key == item.key } ?: return
        if (currentItem.isUnfollowedActivityThread) return
        if (!beginThreadFollowMutation(item.key)) return
        val previous = _state.value
        updateThreadFollowProjection(item.key, following = false)
        scope.launch {
            val threadChannelId = currentItem.threadChannelId
            val result = try {
                if (threadChannelId == null) {
                    DataResult.Error(ScreenError("activity_thread_channel_missing"))
                } else {
                    conversationRepository.unfollowThread(threadChannelId)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                DataResult.Error(ScreenError("activity_unfollow_failed", serverMessage = error.message))
            } finally {
                // A cancelled request must release the duplicate-request fence;
                // otherwise a transient scope cancellation disables the row's
                // next explicit action until the store is reset.
                finishThreadFollowMutation(item.key)
            }
            if (result.isAcceptedThreadFollowMutation()) {
                onUnreadCountsChanged()
                // The mutation response is the server acknowledgement for
                // this explicit user action.  A later Activity refresh (or
                // re-entry) remains the canonical reconciliation point; do
                // not issue a second request here that could race the action
                // and briefly resurrect a stale follow state.
            } else {
                refreshAfterFailure(previous, result.failureCodeOr("activity_unfollow_failed"))
            }
            takeDeferredThreadFollow(item.key)?.let(::followThread)
        }
    }

    /**
     * Follow an unfollowed thread row again (the menu's Follow, or Undo of a swiped Unfollow).
     *
     * A row this filter still lists is followed in place. A row the filter dropped when it was
     * unfollowed (Unread, Mentions) is followed from [item] and the list reloads in the background,
     * so the row comes back from the server rather than from a guessed position. A Follow that
     * arrives while the row's Unfollow is in flight runs once that settles.
     */
    private fun followThread(item: InboxItem) {
        if (!item.isThreadActivityItem() || !item.isUnfollowedActivityThread) return
        val currentItem = _state.value.items.firstOrNull { it.key == item.key }
        if (currentItem != null && !currentItem.isUnfollowedActivityThread) return
        if (!beginThreadFollowMutation(item.key)) {
            // Not deferred: the mutation in flight settled in between, so follow now.
            if (!deferThreadFollow(item)) followThread(item)
            return
        }
        val previous = _state.value
        if (currentItem != null) updateThreadFollowProjection(item.key, following = true)
        val target = currentItem ?: item
        scope.launch {
            val parentMessageId = target.parentMessageId
            val result = try {
                if (parentMessageId == null) {
                    DataResult.Error(ScreenError("activity_parent_message_missing"))
                } else {
                    conversationRepository.followThread(parentMessageId)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                DataResult.Error(ScreenError("activity_follow_failed", serverMessage = error.message))
            } finally {
                finishThreadFollowMutation(item.key)
            }
            if (result.isAcceptedThreadFollowMutation()) {
                onUnreadCountsChanged()
                if (currentItem == null) {
                    load(refresh = true, pullRefresh = false, backgroundRefresh = true, intent = SharedActivityIntent.Refresh)
                }
            } else {
                refreshAfterFailure(previous, result.failureCodeOr("activity_follow_failed"))
            }
            takeDeferredThreadFollow(item.key)?.let(::followThread)
        }
    }

    /** Parks [item]'s Follow behind the row's mutation in flight; false when none is in flight any more. */
    private fun deferThreadFollow(item: InboxItem): Boolean =
        synchronized(realtimeOwnerLock) {
            if (item.key !in pendingThreadFollowMutations) return@synchronized false
            deferredThreadFollows = deferredThreadFollows + (item.key to item)
            true
        }

    private fun takeDeferredThreadFollow(key: String): InboxItem? =
        synchronized(realtimeOwnerLock) {
            deferredThreadFollows[key]?.also { deferredThreadFollows = deferredThreadFollows - key }
        }

    private fun DataResult<ThreadActionResult>.failureCodeOr(fallback: String): String =
        when (this) {
            is DataResult.Error -> error.code.ifBlank { fallback }
            is DataResult.NoPermission -> blocker.code.ifBlank { fallback }
            is DataResult.Content,
            is DataResult.Empty -> fallback
        }

    /** A successful response with `ok=false` did not acknowledge the mutation. */
    private fun DataResult<ThreadActionResult>.isAcceptedThreadFollowMutation(): Boolean =
        when (this) {
            is DataResult.Content -> data.changed
            // Empty is the deployed legacy success shape for an idempotent
            // action; retain the optimistic state until the next refresh.
            is DataResult.Empty -> true
            is DataResult.Error,
            is DataResult.NoPermission -> false
        }

    private fun InboxItem.isThreadActivityItem(): Boolean =
        kind == InboxItemKind.Thread

    private fun beginThreadFollowMutation(key: String): Boolean =
        synchronized(realtimeOwnerLock) {
            if (key in pendingThreadFollowMutations) {
                false
            } else {
                pendingThreadFollowMutations += key
                true
            }
        }

    private fun finishThreadFollowMutation(key: String) {
        synchronized(realtimeOwnerLock) {
            pendingThreadFollowMutations -= key
        }
    }

    private fun updateThreadFollowProjection(key: String, following: Boolean) {
        synchronized(realtimeOwnerLock) {
            val current = _state.value
            val snapshot = current.snapshot ?: return@synchronized
            val updatedSnapshot =
                snapshot.projectActivityFollowMutation(
                    itemKey = key,
                    following = following,
                    filter = current.selectedFilter
                )
            reduceState(
                if (following) {
                    SharedActivityIntent.FollowThread(snapshot.items.firstOrNull { it.key == key } ?: return@synchronized)
                } else {
                    SharedActivityIntent.UnfollowThread(snapshot.items.firstOrNull { it.key == key } ?: return@synchronized)
                },
                current.copy(
                    snapshot = updatedSnapshot,
                    errorCode = null
                )
            )
        }
    }

    private fun markRead(item: InboxItem) {
        if (item.isUnfollowedActivityThread || item.unreadCount <= 0) return
        val previous = _state.value
        if (previous.snapshot == null) return
        val targetChannelId = item.readReceiptChannelId()
        if (targetChannelId == null) {
            refreshAfterFailure(previous, "activity_thread_channel_missing")
            return
        }
        val owner =
            _state.value.ownerServerId?.let { serverId ->
                _state.value.ownerUserId?.let { userId ->
                    readReceiptOwnerFactory?.invoke(serverId, userId)
                }
            }
        // Reading a row (opened or marked read) in Unread keeps it listed, read, until the user
        // moves on; rows read earlier in this visit stay too.
        synchronized(realtimeOwnerLock) { retainReadRowLocked(SharedActivityIntent.MarkRead(item), item) }
        val coordinator = readReceiptCoordinator
        if (owner != null && coordinator != null) {
            applyReadReceiptPrediction(coordinator.markReadAll(owner, targetChannelId))
            return
        }
        val key = item.key
        val retainedState = _state.value
        val retainedSnapshot = retainedState.snapshot ?: return
        val overlayOwner = overlayOwner(retainedState)
        val readGenerations = rememberUnreadCleared(overlayOwner, listOf(item))
        val keepsRow = retainedState.keepsReadRow(key)
        val updatedItems =
            if (keepsRow) {
                retainedSnapshot.items.map { existing ->
                    if (existing.key == key) {
                        existing.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false)
                    } else {
                        existing
                    }
                }
            } else {
                retainedSnapshot.items.filterNot { it.key == key }
            }
        reduceState(
            SharedActivityIntent.MarkRead(item),
            retainedState.copy(
                snapshot =
                    retainedSnapshot.copy(
                        items = updatedItems,
                        // The filter's count drops at once even while the read row stays listed.
                        totalCount =
                            if (retainedState.selectedFilter.keepsReadActivityRows()) {
                                retainedSnapshot.totalCount
                            } else {
                                maxOf(0, retainedSnapshot.totalCount - 1)
                            },
                        totalUnreadCount = maxOf(0, retainedSnapshot.totalUnreadCount - item.unreadCount)
                    ),
                errorCode = null
            )
        )
        scope.launch {
            withOverlaySettlement(readOverlay, overlayOwner, readGenerations) {
                val serverId = currentServerId()
                val result = readReceiptRepository.markChannelReadAll(targetChannelId)
                if (result !is DataResult.Content && result !is DataResult.Empty) {
                    readOverlay.forgetAll(overlayOwner, readGenerations)
                    onUnreadCountsChanged()
                    refreshAfterFailure(previous, "activity_mark_read_failed")
                } else {
                    readOverlay.confirmAll(overlayOwner, readGenerations)
                    onUnreadCountsChanged()
                }
            }
        }
    }

    internal fun applyReadReceiptPrediction(prediction: ReadReceiptPrediction) {
        synchronized(realtimeOwnerLock) {
            onReadReceiptPredictionOwnerEntered()
            val current = _state.value
            if (!current.matchesReadReceiptOwner(prediction.owner)) return@synchronized
            val previousRevision = readPredictionRevisions[prediction.channelId]
            if (previousRevision != null && previousRevision >= prediction.revision) return@synchronized
            val snapshot = current.snapshot ?: return@synchronized
            val matchingUnread =
                snapshot.items.filter { item ->
                    item.unreadCount > 0 && item.readReceiptChannelId() == prediction.channelId
                }
            readPredictionRevisions = readPredictionRevisions + (prediction.channelId to prediction.revision)
            if (matchingUnread.isEmpty()) return@synchronized
            // Pending until the coordinator reports this channel's write result
            // (handleReadReceiptResult); the hold starts at server confirmation.
            val generations = rememberUnreadCleared(overlayOwner(current), matchingUnread)
            if (generations.isNotEmpty()) {
                readPredictionHolds = readPredictionHolds + (prediction.channelId to (prediction.revision to generations))
            }
            val matchingKeys = matchingUnread.mapTo(mutableSetOf(), InboxItem::key)
            readPredictionItemKeys = readPredictionItemKeys + (prediction.channelId to matchingKeys)
            val removedUnreadCount = matchingUnread.sumOf(InboxItem::unreadCount)
            val updatedItems =
                snapshot.items.mapNotNull { item ->
                    when {
                        item.key !in matchingKeys -> item
                        current.keepsReadRow(item.key) ->
                            item.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false)
                        else -> null
                    }
                }
            reduceState(
                SharedActivityIntent.ApplyReadReceiptPrediction(prediction),
                current.copy(
                    snapshot =
                        snapshot.copy(
                            items = updatedItems,
                            totalCount =
                                if (current.selectedFilter.keepsReadActivityRows()) {
                                    snapshot.totalCount
                                } else {
                                    maxOf(0, snapshot.totalCount - matchingKeys.size)
                                },
                            totalUnreadCount = maxOf(0, snapshot.totalUnreadCount - removedUnreadCount)
                        ),
                    errorCode = null
                )
            )
        }
    }

    internal fun handleReadReceiptFailure(request: ReadReceiptRequest) {
        val shouldRefresh =
            synchronized(realtimeOwnerLock) {
                val current = _state.value
                if (!current.matchesReadReceiptOwner(request.owner)) return@synchronized false
                if (readPredictionRevisions[request.channelId] != request.revision) return@synchronized false
                readPredictionRevisions = readPredictionRevisions - request.channelId
                readPredictionItemKeys[request.channelId].orEmpty().forEach(::forgetUnreadCleared)
                readPredictionItemKeys = readPredictionItemKeys - request.channelId
                readPredictionHolds = readPredictionHolds - request.channelId
                true
            }
        if (shouldRefresh) {
            load(refresh = true, pullRefresh = false, intent = SharedActivityIntent.Refresh)
        }
    }

    /**
     * The coordinator finished a read write for ([ReadReceiptRequest.owner],
     * channel, revision). A success at or after the revision that registered the
     * channel's local read starts that hold now; a failure is left to
     * [handleReadReceiptFailure] (current revision) or to the superseding
     * request's own result. An entry no result ever reaches lapses at its
     * pending backstop.
     */
    internal fun handleReadReceiptResult(request: ReadReceiptRequest, succeeded: Boolean) {
        if (!succeeded) return
        synchronized(realtimeOwnerLock) {
            val current = _state.value
            if (!current.matchesReadReceiptOwner(request.owner)) return@synchronized
            val (revision, generations) = readPredictionHolds[request.channelId] ?: return@synchronized
            if (request.revision < revision) return@synchronized
            readOverlay.confirmAll(overlayOwner(current), generations)
            readPredictionHolds = readPredictionHolds - request.channelId
        }
    }

    private fun SharedActivityState.matchesReadReceiptOwner(owner: ReadReceiptOwner): Boolean {
        val serverId = ownerServerId ?: return false
        val accountId = ownerUserId ?: return false
        if (serverId != owner.serverId || accountId != owner.accountId) return false
        val ownerFactory = readReceiptOwnerFactory ?: return true
        return ownerFactory(serverId, accountId) == owner
    }

    private fun markUnread(item: InboxItem) {
        if (item.isUnfollowedActivityThread) return
        val previous = _state.value
        val snapshot = previous.snapshot ?: return
        val targetChannelId = item.readReceiptChannelId()
        if (targetChannelId == null) {
            refreshAfterFailure(previous, "activity_thread_channel_missing")
            return
        }
        val wasUnread = item.unreadCount > 0
        val optimisticUnreadCount = item.unreadCount.takeIf { it > 0 } ?: 1
        val key = item.key
        forgetUnreadCleared(key)
        val updatedItems =
            snapshot.items.map { existing ->
                if (existing.key == key) {
                    existing.copy(
                        unreadCount = optimisticUnreadCount,
                        firstUnreadMessageId = existing.firstUnreadMessageId ?: existing.activityUnreadAnchor()
                    )
                } else {
                    existing
                }
            }
        reduceState(
            SharedActivityIntent.MarkUnread(item),
            previous.copy(
                snapshot =
                    snapshot.copy(
                        items = updatedItems,
                        totalUnreadCount =
                            if (wasUnread) {
                                snapshot.totalUnreadCount
                            } else {
                                snapshot.totalUnreadCount + optimisticUnreadCount
                            }
                    ),
                errorCode = null
            )
        )
        scope.launch {
            val result = readReceiptRepository.markChannelUnread(targetChannelId)
            val owner =
                readReceiptOwnerFactory
                    ?.invoke(previous.ownerServerId.orEmpty(), currentUserId().orEmpty())
                    ?.toReadStateV1ProductionOwner()
            val disposition =
                if (result is DataResult.Content && owner != null) {
                    readStateV1Canary?.applyMutationAck(
                        owner = owner,
                        channelId = targetChannelId.value,
                        readStateVersion = result.data.readStateVersion,
                        maxReadSeq = result.data.maxReadSeq,
                    )
                } else {
                    null
                }
            if (disposition != null && disposition !is ReadStateV1ProductionDisposition.Legacy) {
                onUnreadCountsChanged()
            } else if (result is DataResult.Content || result is DataResult.Empty) {
                onUnreadCountsChanged()
            } else {
                onUnreadCountsChanged()
                refreshAfterFailure(previous, "activity_mark_unread_failed")
            }
        }
    }

    private fun removeItemOptimistically(item: InboxItem) {
        val current = _state.value
        val snapshot = current.snapshot ?: return
        reduceState(
            SharedActivityIntent.MarkDone(item),
            current.copy(
                snapshot =
                    snapshot.copy(
                        items = snapshot.items.filterNot { it.key == item.key },
                        totalCount = maxOf(0, snapshot.totalCount - 1),
                        totalUnreadCount = maxOf(0, snapshot.totalUnreadCount - item.unreadCount)
                    ),
                errorCode = null,
                // Done leaves at once, retained or not.
                retainedReadRowKeys = current.retainedReadRowKeys - item.key
            )
        )
    }

    private fun refreshAfterFailure(
        previous: SharedActivityState,
        code: String
    ) {
        _state.value = previous.copy(loading = false, refreshing = false, pullRefreshing = false, loadingMore = false, errorCode = code)
        load(refresh = true, pullRefresh = false, intent = SharedActivityIntent.Refresh)
    }

    private fun setLoadError(code: String, append: Boolean = false) {
        _state.value =
            _state.value.copy(
                loading = false,
                refreshing = false,
                pullRefreshing = false,
                loadingMore = false,
                errorCode = if (append) null else code,
                loadMoreErrorCode = if (append) code else null
            )
    }

    private fun reduceState(
        intent: SharedActivityIntent,
        nextState: SharedActivityState,
        summaryAuthoritative: Boolean = false,
        summaryFactCounts: Pair<Int, Int>? = null,
        summarySource: String = "activity:${intent.activitySummarySource()}"
    ): Boolean {
        val previousState = _state.value
        val stateWithFilterCache =
            if (nextState.snapshot != null) {
                val updatedMap = nextState.filterSnapshots.toMutableMap()
                updatedMap[nextState.selectedFilter] = nextState.snapshot
                if (intent is SharedActivityIntent.MarkRead ||
                    intent is SharedActivityIntent.MarkDone ||
                    intent is SharedActivityIntent.MarkAllRead ||
                    intent is SharedActivityIntent.ApplyReadReceiptPrediction
                ) {
                    // Invalidate other filter caches when mutations occur so switching to them re-fetches fresh data
                    val keysToRemove = updatedMap.keys.filter { it != nextState.selectedFilter }
                    keysToRemove.forEach { updatedMap.remove(it) }
                }
                nextState.copy(filterSnapshots = updatedMap)
            } else {
                nextState
            }
        val finalState =
            persistSlices.fold(stateWithFilterCache) { state, slice ->
                slice.reduce(previous = previousState, intent = intent, reduced = state)
            }
        if (finalState == previousState) return false
        _state.value = finalState
        persistSlices.forEach { slice ->
            slice.onTransition(previous = previousState, intent = intent, next = finalState)
        }
        emitSummarySignalIfChanged(
            intent = intent,
            previous = previousState,
            next = finalState,
            authoritative = summaryAuthoritative,
            source = summarySource,
            countOverride = summaryFactCounts
        )
        return true
    }

    private fun emitSummarySignalIfChanged(
        intent: SharedActivityIntent,
        previous: SharedActivityState,
        next: SharedActivityState,
        authoritative: Boolean,
        source: String,
        countOverride: Pair<Int, Int>?
    ) {
        val serverId = next.ownerServerId?.trim()?.takeIf { it.isNotBlank() } ?: return
        val accountId = next.ownerUserId?.trim()?.takeIf { it.isNotBlank() } ?: return
        val snapshot = next.snapshot ?: return
        val previousVisibleCounts = previous.snapshot?.activityVisibleSummaryCounts()
        val visibleCounts =
            snapshot.activityVisibleSummaryCounts(
                totalUnreadCountOverride = countOverride?.first,
                totalCountOverride = countOverride?.second
            )
        val summaryChanged =
            previous.ownerServerId != next.ownerServerId ||
                previous.ownerUserId != next.ownerUserId ||
                previousVisibleCounts != visibleCounts
        if (!summaryChanged && countOverride == null) return
        if (next.selectedFilter != InboxFilter.All) {
            if (!intent.invalidatesGlobalActivitySummary()) return
            runCatching {
                onSummaryInvalidated(
                    ActivityUnreadSummaryInvalidation(
                        accountId = accountId,
                        serverId = serverId,
                        source = source
                    )
                )
            }.onFailure { error ->
                diagnosticsLogger(
                    "activity_summary_invalidation result=failure serverId=$serverId source=$source " +
                        "errorType=${error::class.simpleName.orEmpty()}"
                )
            }
            return
        }
        runCatching {
            onSummaryFact(
                    ActivityUnreadSummaryFact(
                        accountId = accountId,
                        serverId = serverId,
                        totalUnreadCount = visibleCounts.first,
                        totalCount = visibleCounts.second,
                    authoritative = authoritative,
                    source = source
                )
            )
        }.onFailure { error ->
            diagnosticsLogger(
                "activity_summary_fact result=failure serverId=$serverId source=$source " +
                    "errorType=${error::class.simpleName.orEmpty()}"
            )
        }
    }

    private suspend fun currentServerId(): ServerId? =
        authStore.readSession()?.currentServerId?.takeIf { it.isNotBlank() }?.let(::ServerId)

    private suspend fun currentUserId(): String? =
        authStore.readSession()
            ?.user
            ?.id
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private suspend fun loadSenderProfiles(serverId: ServerId): Map<String, SharedActivitySenderProfile> =
        try {
            val cachedProfiles = currentSenderProfiles(serverId)
            val profilesById =
                serverMembersStore?.let { store ->
                    when (store.refreshMembers(serverId, DataRequestPriority.Visible)) {
                        is DataResult.Content -> store.currentKnownMemberProfilesById(serverId)
                        else -> emptyMap()
                    }
                } ?: when (val result = memberRepository?.getMembers(DataRequestPriority.Visible, serverId)) {
                    is DataResult.Content ->
                        result.data.members
                            .filter { it.id.value.trim().isNotBlank() }
                            .associateBy { it.id.value.trim() }
                    else -> emptyMap()
                }
            cachedProfiles + profilesById.toActivitySenderProfiles()
        } catch (_: Throwable) {
            currentSenderProfiles(serverId)
        }

    private fun currentSenderProfiles(serverId: ServerId): Map<String, SharedActivitySenderProfile> =
        serverMembersStore
            ?.currentKnownMemberProfilesById(serverId)
            ?.toActivitySenderProfiles()
            .orEmpty()

    private fun Map<String, MemberProfile>.toActivitySenderProfiles(): Map<String, SharedActivitySenderProfile> =
        mapValues { (_, profile) ->
            SharedActivitySenderProfile(
                displayName = profile.displayName.ifBlank { profile.handle.ifBlank { profile.id.value } }
            )
        }

    /**
     * task #399: hydrate peer display names for DM-parent thread rows. The
     * inbox row's `parentChannelName` is the raw channel name, which is not a
     * reliable peer projection for DMs — the Home `directMessages` snapshot is
     * the authority (web `itemFilterChannelLabel` joins the same way).
     * Runs after each canonical load and safe realtime patch; a channel that
     * stays missing after a fetch is parked so a flood of DM rows cannot
     * refetch the DM list per row.
     */
    private fun ensureDmPeerNames() {
        val repository = channelRepository ?: return
        val missing: Set<ChannelId> =
            synchronized(realtimeOwnerLock) {
                if (dmPeerNamesFetchInFlight) return
                val state = _state.value
                val alreadyResolved = state.dmPeerNames
                val unresolved =
                    dmParentThreadChannelIds(state)
                        .filter {
                            alreadyResolved[it.value].isNullOrBlank() && it !in unresolvedDmPeerChannelIds
                        }
                        .toSet()
                if (unresolved.isEmpty()) return
                dmPeerNamesFetchInFlight = true
                unresolved
            }
        scope.launch {
            // true only when the DM list actually came back; a failed fetch
            // must NOT park ids (Mahua review: one network blip would
            // otherwise disable hydration for those rows for the store's
            // lifetime) and must not tail-retry (that would hot-loop against
            // an unreachable server — the next load/patch trigger retries).
            var fetchSucceeded = false
            try {
                val serverId = currentServerId() ?: return@launch
                val resolved: Map<String, String>? =
                    when (val result = repository.getDirectMessages(serverId)) {
                        is DataResult.Content ->
                            result.data.directMessages.associate { row ->
                                row.channelId.value to row.name
                            }
                        else -> null
                    }
                if (resolved != null) {
                    fetchSucceeded = true
                    synchronized(realtimeOwnerLock) {
                        val current = _state.value
                        val merged = current.dmPeerNames + resolved
                        // Park only the ids a successful fetch could not
                        // resolve; ids that arrived while the fetch was in
                        // flight stay eligible for the tail-call ensure below.
                        unresolvedDmPeerChannelIds =
                            unresolvedDmPeerChannelIds +
                                missing.filter { merged[it.value].isNullOrBlank() }
                        if (merged != current.dmPeerNames) {
                            _state.value = current.copy(dmPeerNames = merged)
                        }
                    }
                }
            } finally {
                synchronized(realtimeOwnerLock) {
                    dmPeerNamesFetchInFlight = false
                }
            }
            if (fetchSucceeded) {
                ensureDmPeerNames()
            }
        }
    }

    private fun dmParentThreadChannelIds(state: SharedActivityState): Set<ChannelId> =
        buildSet {
            state.snapshot?.items?.let(::addAll)
            state.filterSnapshots.values.forEach { addAll(it.items) }
        }
            .asSequence()
            .filter {
                it.kind == InboxItemKind.Thread &&
                    it.parentChannelKind == ConversationKind.DirectMessage &&
                    it.parentChannelId != null
            }
            .mapNotNull { it.parentChannelId }
            .toSet()

    /**
     * Null only before the first load has resolved a server (no rows to hold yet).
     * An unknown account keys by server alone rather than disabling the overlay.
     */
    private fun overlayOwner(state: SharedActivityState = _state.value): ActivityOverlayOwner? {
        val serverId = state.ownerServerId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return ActivityOverlayOwner(serverId, state.ownerUserId?.trim()?.takeIf { it.isNotBlank() })
    }

    private fun rememberResolvedMentionAction(owner: ActivityOverlayOwner, key: String) {
        val keys = resolvedMentionActionKeys[owner].orEmpty()
        resolvedMentionActionKeys = resolvedMentionActionKeys + (owner to (keys + key))
    }

    /**
     * Runs a mutation's write and guarantees its pending overlay entries settle:
     * if [block] throws or is cancelled before it confirmed or forgot them, they
     * are forgotten (server truth shows on the next load) instead of staying
     * pending until the backstop.
     */
    private suspend inline fun withOverlaySettlement(
        overlay: ActivityLocalMutationOverlay,
        owner: ActivityOverlayOwner?,
        generations: Map<String, Long>,
        block: () -> Unit
    ) {
        var settled = false
        try {
            block()
            settled = true
        } finally {
            if (!settled) overlay.forgetAll(owner, generations)
        }
    }

    /** Records a local read for every unread row; returns key -> overlay generation. */
    private fun rememberUnreadCleared(
        owner: ActivityOverlayOwner?,
        items: List<InboxItem>
    ): Map<String, Long> {
        if (owner == null) return emptyMap()
        return items
            .filter { it.unreadCount > 0 }
            .mapNotNull { item -> readOverlay.remember(owner, item)?.let { item.key to it } }
            .toMap()
    }

    private fun ActivityLocalMutationOverlay.confirmAll(
        owner: ActivityOverlayOwner?,
        generations: Map<String, Long>
    ) {
        if (owner == null) return
        generations.forEach { (key, generation) -> confirm(owner, key, generation) }
    }

    private fun ActivityLocalMutationOverlay.forgetAll(
        owner: ActivityOverlayOwner?,
        generations: Map<String, Long>
    ) {
        if (owner == null) return
        generations.forEach { (key, generation) -> forget(owner, key, generation) }
    }

    private fun forgetUnreadCleared(key: String) {
        overlayOwner()?.let { owner -> readOverlay.forget(owner, key) }
    }

    private fun List<InboxItem>.reconcileLocallyClearedUnreadItems(
        filter: InboxFilter,
        owner: ActivityOverlayOwner?
    ): ActivityUnreadReconcileResult {
        if (owner == null || !readOverlay.hasEntries(owner)) {
            return ActivityUnreadReconcileResult(items = this)
        }
        val reconciled = mutableListOf<InboxItem>()
        var suppressedUnreadCount = 0
        var removedUnreadRows = 0
        for (item in this) {
            // An already-read row keeps its entry: a later stale copy may still
            // carry the pre-read unread state for the same activity marker.
            if (item.unreadCount <= 0 || !readOverlay.holds(owner, item)) {
                reconciled += item
                continue
            }
            suppressedUnreadCount += item.unreadCount
            val clearedItem = item.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false)
            if (filter.keepsReadActivityRows()) {
                reconciled += clearedItem
            } else {
                removedUnreadRows += 1
            }
        }
        return ActivityUnreadReconcileResult(
            items = reconciled,
            suppressedUnreadCount = suppressedUnreadCount,
            removedUnreadRows = removedUnreadRows
        )
    }

    private fun ActivityUnreadReconcileResult.reconcileLocallyDoneItems(
        owner: ActivityOverlayOwner?
    ): ActivityReconcileResult {
        val resolvedMentionActions = owner?.let { resolvedMentionActionKeys[it] }.orEmpty()
        if (owner == null || (resolvedMentionActions.isEmpty() && !doneOverlay.hasEntries(owner))) {
            return ActivityReconcileResult(
                items = items,
                suppressedUnreadCount = suppressedUnreadCount,
                removedUnreadRows = removedUnreadRows
            )
        }
        val reconciled = mutableListOf<InboxItem>()
        var suppressedDoneUnreadCount = 0
        var removedDoneRows = 0
        for (item in items) {
            if (item.key in resolvedMentionActions || doneOverlay.holds(owner, item)) {
                suppressedDoneUnreadCount += item.unreadCount
                removedDoneRows += 1
            } else {
                reconciled += item
            }
        }
        return ActivityReconcileResult(
            items = reconciled,
            suppressedUnreadCount = suppressedUnreadCount,
            removedUnreadRows = removedUnreadRows,
            suppressedDoneUnreadCount = suppressedDoneUnreadCount,
            removedDoneRows = removedDoneRows
        )
    }
}

private data class ActivityUnreadReconcileResult(
    val items: List<InboxItem>,
    val suppressedUnreadCount: Int = 0,
    val removedUnreadRows: Int = 0
)

private data class ActivityReconcileResult(
    val items: List<InboxItem>,
    val suppressedUnreadCount: Int = 0,
    val removedUnreadRows: Int = 0,
    val suppressedDoneUnreadCount: Int = 0,
    val removedDoneRows: Int = 0
)

internal fun List<InboxItem>.dedupeActivityItems(): List<InboxItem> =
    if (size <= 1) {
        this
    } else {
        distinctBy { it.key }
    }

data class SharedActivityState(
    val selectedFilter: InboxFilter = InboxFilter.All,
    val snapshot: InboxSnapshot? = null,
    val filterSnapshots: Map<InboxFilter, InboxSnapshot> = emptyMap(),
    val senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap(),
    /** task #399: DM channel id → peer display name, hydrated from the Home DM
     * list for `parentChannelKind == DirectMessage` thread rows. */
    val dmPeerNames: Map<String, String> = emptyMap(),
    val ownerServerId: String? = null,
    val ownerUserId: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val pullRefreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val errorCode: String? = null,
    val loadMoreErrorCode: String? = null,
    /**
     * The rows the user read during this visit to a filter that drops read rows (Unread): opened,
     * marked read, or listed at "Mark all read". They stay listed, shown read, until the user moves
     * on (switches filter, leaves Activity, pulls to refresh, switches server). Background loads,
     * reconciles and realtime keep them; Done removes a row at once. Gmail / Apple Mail unread view
     * (stdrc 10-23).
     */
    val retainedReadRowKeys: Set<String> = emptySet(),
    /**
     * Task #164: the rows and counts shown may be old because the read warehouse is behind: the
     * last canonical read was the server's remembered body (`X-Raft-Stale`) or a `503
     * rw_overloaded` while rows were shown. The list keeps its last good rows; the page shows the
     * stale notice until a fresh read clears it.
     */
    val stale: Boolean = false
) {
    val items: List<InboxItem>
        get() = snapshot?.items.orEmpty()
    val totalCount: Int
        get() = snapshot?.totalCount ?: 0
    val totalUnreadCount: Int
        get() = snapshot?.totalUnreadCount ?: 0
    val hasMore: Boolean
        get() = snapshot?.hasMore == true
    val nextOffset: Int?
        get() = snapshot?.nextOffset
}

/**
 * Web `isUnreadInboxFilter` (slock 1bb70cbeb, inboxStore.ts:124): only Unread
 * drops a row once it is read. The server keeps read rows in Mentions (an
 * independent `message_mentions` EXISTS, channelService.ts:13136-13168), so a
 * read / mark-all-read clears the row's attention there instead of removing it.
 */
private fun InboxFilter.keepsReadActivityRows(): Boolean =
    this != InboxFilter.Unread

/** A read row stays listed (shown read) when the filter keeps read rows or it was read this visit. */
private fun SharedActivityState.keepsReadRow(key: String): Boolean =
    selectedFilter.keepsReadActivityRows() || key in retainedReadRowKeys

/**
 * Forgets the rows retained for this visit; the read ones leave the list, as they no longer match
 * the filter (they were read in a filter that drops read rows). A retained row that turned unread
 * again stays. The counts already left them when they were read, so they stay.
 */
internal fun SharedActivityState.withRetainedReadRowsReleased(): SharedActivityState {
    if (retainedReadRowKeys.isEmpty()) return this
    val current = snapshot
    val leaving =
        if (current == null || selectedFilter.keepsReadActivityRows()) {
            emptyList()
        } else {
            current.items.filter { item -> item.key in retainedReadRowKeys && item.unreadCount <= 0 }
        }
    return copy(
        retainedReadRowKeys = emptySet(),
        snapshot = if (leaving.isEmpty()) current else current?.copy(items = current.items - leaving.toSet())
    )
}

private fun InboxItem.touchesChannel(channelIds: Set<String>): Boolean =
    channelId.value in channelIds ||
        threadChannelId?.value in channelIds ||
        parentChannelId?.value in channelIds

/**
 * Drops the retained rows [matching] from the list and from the retained set; the other retained rows
 * stay. A read row already left the counts; a retained row that turned unread leaves them now. Returns this when nothing is retained and matches.
 */
internal fun SharedActivityState.withRetainedRowsDropped(matching: (InboxItem) -> Boolean): SharedActivityState {
    val current = snapshot
    val leaving = current?.items.orEmpty().filter { item -> item.key in retainedReadRowKeys && matching(item) }
    val leavingKeys = leaving.mapTo(mutableSetOf(), InboxItem::key)
    if (leavingKeys.isEmpty()) return this
    return copy(
        retainedReadRowKeys = retainedReadRowKeys - leavingKeys,
        snapshot =
            current?.copy(
                items = current.items.filterNot { it.key in leavingKeys },
                totalCount = (current.totalCount - leaving.count { it.unreadCount > 0 }).coerceAtLeast(0),
                totalUnreadCount = (current.totalUnreadCount - leaving.sumOf { it.unreadCount }).coerceAtLeast(0)
            )
    )
}

/**
 * A canonical page in a filter that drops read rows no longer lists the retained rows once they
 * are read; each is put back, read, where it stood (before the first row that followed it and is
 * still on the page). Background loads and reconciles never drop a row read this visit.
 */
private fun List<InboxItem>.withRetainedReadRows(current: SharedActivityState): List<InboxItem> {
    val keys = current.retainedReadRowKeys
    if (keys.isEmpty()) return this
    val pageKeys = mapTo(mutableSetOf(), InboxItem::key)
    if (current.items.none { item -> item.key in keys && item.key !in pageKeys }) return this
    // Anchor each missing retained row to the next row after it that the page still lists (null:
    // the end), walking the previous list backwards so rows sharing an anchor keep their order.
    val anchored = linkedMapOf<String?, MutableList<InboxItem>>()
    var anchor: String? = null
    for (item in current.items.asReversed()) {
        if (item.key in pageKeys) {
            anchor = item.key
        } else if (item.key in keys) {
            anchored.getOrPut(anchor) { mutableListOf() }
                .add(0, item.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false))
        }
    }
    return buildList(size + anchored.values.sumOf { it.size }) {
        for (item in this@withRetainedReadRows) {
            anchored[item.key]?.let(::addAll)
            add(item)
        }
        anchored[null]?.let(::addAll)
    }
}

sealed interface SharedActivityIntent {
    /** The shell switched server: show [serverId]'s cached feed and revalidate it. */
    data class ServerSelected(val serverId: String) : SharedActivityIntent
    data object LoadInitial : SharedActivityIntent
    data object Refresh : SharedActivityIntent
    data object PullRefresh : SharedActivityIntent
    data object LoadMore : SharedActivityIntent
    data object MarkAllRead : SharedActivityIntent
    data class SetFilter(val filter: InboxFilter) : SharedActivityIntent
    data class MarkDone(val item: InboxItem) : SharedActivityIntent
    data class UnfollowThread(val item: InboxItem) : SharedActivityIntent
    data class FollowThread(val item: InboxItem) : SharedActivityIntent
    data class MarkRead(val item: InboxItem) : SharedActivityIntent
    data class MarkUnread(val item: InboxItem) : SharedActivityIntent
    data class ApplyReadReceiptPrediction(val prediction: ReadReceiptPrediction) : SharedActivityIntent
    data class ApplyReadReceiptFailure(val request: ReadReceiptRequest) : SharedActivityIntent
    /** A coordinator read write completed; a success starts the local read hold. */
    data class ApplyReadReceiptResult(val request: ReadReceiptRequest, val succeeded: Boolean) : SharedActivityIntent
    data class PrewarmVisibleReplyThreads(
        val markers: Set<ActivityReplyThreadPrewarmMarker>
    ) : SharedActivityIntent
    data class PrewarmActivityTail(
        val orderedMarkers: List<ActivityReplyThreadPrewarmMarker>
    ) : SharedActivityIntent
    data object DetachPrewarmViewport : SharedActivityIntent
    /**
     * The Activity surface stopped being visible (another tab, a pushed page, the app went to the
     * background). A load already in flight then is never joined by the next entry's Refresh: it
     * may have hung across the background, and the monotonic clock does not advance while the
     * device sleeps, so its age alone cannot tell.
     */
    data object SurfaceHidden : SharedActivityIntent

    /**
     * The user opened [item] (a row pick, or the wide layout reopening it beside the list). In a
     * filter that drops read rows, an unread [item] joins the rows retained for this visit, so
     * reading it keeps it in place; rows retained earlier stay.
     */
    data class RowOpened(val item: InboxItem) : SharedActivityIntent
    /**
     * The user lost access to [channelIds] (left or deleted through the app; an archived channel is still readable).
     * Retained rows of those channels, their threads, or threads under them leave at once.
     */
    data class ChannelAccessLost(val channelIds: Set<String>) : SharedActivityIntent
    data class ApplyRealtimeEvent(
        val event: RealtimeEvent,
        val allowLocalPatch: Boolean = true
    ) : SharedActivityIntent
    data class ApplyRealtimeSummaryEvent(
        val event: RealtimeEvent,
        val activeConversationId: String?
    ) : SharedActivityIntent
}

private fun SharedActivityIntent.activitySummarySource(): String =
    when (this) {
        is SharedActivityIntent.ServerSelected -> "server_selected"
        SharedActivityIntent.LoadInitial -> "load_initial"
        SharedActivityIntent.Refresh -> "refresh"
        SharedActivityIntent.PullRefresh -> "pull_refresh"
        SharedActivityIntent.LoadMore -> "load_more"
        SharedActivityIntent.MarkAllRead -> "mark_all_read"
        is SharedActivityIntent.SetFilter -> "set_filter:${filter.wireValue}"
        is SharedActivityIntent.MarkDone -> "mark_done"
        is SharedActivityIntent.UnfollowThread -> "unfollow_thread"
        is SharedActivityIntent.FollowThread -> "follow_thread"
        is SharedActivityIntent.MarkRead -> "mark_read"
        is SharedActivityIntent.MarkUnread -> "mark_unread"
        is SharedActivityIntent.ApplyReadReceiptPrediction -> "read_prediction"
        is SharedActivityIntent.ApplyReadReceiptFailure -> "read_failure"
        is SharedActivityIntent.ApplyReadReceiptResult -> "read_result"
        is SharedActivityIntent.PrewarmVisibleReplyThreads -> "prewarm_visible"
        is SharedActivityIntent.PrewarmActivityTail -> "prewarm_tail"
        SharedActivityIntent.DetachPrewarmViewport -> "prewarm_detach"
        SharedActivityIntent.SurfaceHidden -> "surface_hidden"
        is SharedActivityIntent.RowOpened -> "row_opened"
        is SharedActivityIntent.ChannelAccessLost -> "channel_access_lost"
        is SharedActivityIntent.ApplyRealtimeEvent -> "realtime"
        is SharedActivityIntent.ApplyRealtimeSummaryEvent -> "realtime_summary"
    }

private fun SharedActivityIntent.invalidatesGlobalActivitySummary(): Boolean =
    when (this) {
        SharedActivityIntent.MarkAllRead,
        is SharedActivityIntent.MarkDone,
        is SharedActivityIntent.UnfollowThread,
        is SharedActivityIntent.FollowThread,
        is SharedActivityIntent.MarkRead,
        is SharedActivityIntent.MarkUnread,
        is SharedActivityIntent.ApplyReadReceiptPrediction,
        is SharedActivityIntent.ApplyReadReceiptFailure,
        is SharedActivityIntent.ApplyRealtimeEvent,
        is SharedActivityIntent.ApplyRealtimeSummaryEvent -> true
        is SharedActivityIntent.ServerSelected,
        SharedActivityIntent.LoadInitial,
        SharedActivityIntent.Refresh,
        SharedActivityIntent.PullRefresh,
        SharedActivityIntent.LoadMore,
        is SharedActivityIntent.SetFilter,
        is SharedActivityIntent.ApplyReadReceiptResult,
        is SharedActivityIntent.PrewarmVisibleReplyThreads,
        is SharedActivityIntent.PrewarmActivityTail,
        SharedActivityIntent.DetachPrewarmViewport,
        SharedActivityIntent.SurfaceHidden,
        is SharedActivityIntent.RowOpened,
        is SharedActivityIntent.ChannelAccessLost -> false
    }

private fun RealtimeEvent.refreshesActivity(): Boolean = affectsActivityContent()

private fun InboxItem.replyThreadPrewarmKey(serverId: String): ActivityReplyThreadPrewarmKey? {
    if (kind != InboxItemKind.Thread) return null
    val normalizedServerId = serverId.trim().takeIf { it.isNotBlank() } ?: return null
    val normalizedParentChannelId = parentChannelId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedParentMessageId = parentMessageId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedThreadChannelId = threadChannelId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    if (normalizedThreadChannelId == normalizedParentChannelId) return null
    return ActivityReplyThreadPrewarmKey(
        serverId = normalizedServerId,
        parentChannelId = normalizedParentChannelId,
        parentMessageId = normalizedParentMessageId,
        threadChannelId = normalizedThreadChannelId
    )
}

private fun InboxItem.replyThreadSummaryFact(): SharedThreadSummaryFact =
    SharedThreadSummaryFact(
        replyCount = replyCount,
        latestActivityMessageId = latestActivityMessageId?.value,
        latestActivityPreview = latestActivityPreview,
        latestActivityAt = latestActivityAt,
        lastMessageId = lastMessageId?.value,
        lastMessagePreview = lastMessagePreview,
        lastMessageAt = lastMessageAt
    )

private fun List<InboxItem>.withActivityThreadTaskProjection(
    serverId: String,
    tasksDataStore: SharedTasksDataStore?
): List<InboxItem> {
    tasksDataStore ?: return this
    val projection = tasksDataStore.projection(serverId, SharedTasksScope.Server)
    if (projection.status == SharedTasksDataStoreStatus.Idle || projection.tasks.isEmpty()) return this
    // Only tasks bound to a Thread row on this page can change it. Filtering first keeps
    // the work proportional to the page instead of the Server's whole task list.
    val pageParentMessageIds =
        mapNotNullTo(mutableSetOf()) { item ->
            item.parentMessageId?.value?.trim()?.takeIf { item.kind == InboxItemKind.Thread && it.isNotBlank() }
        }
    if (pageParentMessageIds.isEmpty()) return this
    val pageTasks =
        projection.tasks.filter { task ->
            listOfNotNull(task.messageId, task.parentMessageId).any { messageId ->
                messageId.value.trim() in pageParentMessageIds
            }
        }
    if (pageTasks.isEmpty()) return this
    val notificationProjections = tasksDataStore.notificationProjections(pageTasks.map { it.id })
    val taskBindingsByMessageId =
        pageTasks
            .flatMap { task ->
                val notificationProjection = notificationProjections[task.id]
                val localRoute =
                    notificationProjection
                        ?.routes
                        ?.singleOrNull { route -> route.serverId == serverId }
                listOfNotNull(task.messageId, task.parentMessageId)
                    .mapNotNull { messageId -> messageId.value.trim().takeIf { it.isNotBlank() } }
                    .distinct()
                    .map { messageId ->
                        messageId to
                            ActivityTaskNotificationBinding(
                                task = task,
                                canonicalMessageId = notificationProjection?.messageId,
                                localRoute = localRoute
                            )
                    }
            }
            .toMap()
    if (taskBindingsByMessageId.isEmpty()) return this

    // The Server normally persists one personal inbox fact for one canonical
    // message. Keep the client fail-safe too: if host + peer candidates are
    // both present during a refresh/realtime overlap, retain one row and
    // prefer the row already carrying this Server's local parent route.
    val preferredIndexByCanonicalMessage = mutableMapOf<MessageId, Int>()
    forEachIndexed { index, item ->
        val binding = item.activityTaskNotificationBinding(taskBindingsByMessageId) ?: return@forEachIndexed
        val canonicalMessageId = binding.canonicalMessageId ?: return@forEachIndexed
        val currentPreferred = preferredIndexByCanonicalMessage[canonicalMessageId]
        if (currentPreferred == null) {
            preferredIndexByCanonicalMessage[canonicalMessageId] = index
        } else {
            val preferredItem = this[currentPreferred]
            val localChannelId = binding.localRoute?.channelId
            val preferredIsLocal = preferredItem.parentChannelId == localChannelId
            val incomingIsLocal = item.parentChannelId == localChannelId
            if (!preferredIsLocal && incomingIsLocal) {
                preferredIndexByCanonicalMessage[canonicalMessageId] = index
            }
        }
    }
    return mapIndexedNotNull { index, item ->
        val binding = item.activityTaskNotificationBinding(taskBindingsByMessageId)
        val canonicalMessageId = binding?.canonicalMessageId
        if (canonicalMessageId != null && preferredIndexByCanonicalMessage[canonicalMessageId] != index) {
            null
        } else {
            item.withActivityThreadTaskProjection(binding)
        }
    }
}

private data class ActivityTaskNotificationBinding(
    val task: TaskSummary,
    val canonicalMessageId: MessageId?,
    val localRoute: SharedTaskNotificationRoute?
)

private fun InboxItem.activityTaskNotificationBinding(
    bindingsByMessageId: Map<String, ActivityTaskNotificationBinding>
): ActivityTaskNotificationBinding? {
    if (kind != InboxItemKind.Thread) return null
    val parentMessageKey = parentMessageId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return bindingsByMessageId[parentMessageKey]
}

private fun InboxItem.withActivityThreadTaskProjection(binding: ActivityTaskNotificationBinding?): InboxItem {
    if (kind != InboxItemKind.Thread || binding == null) return this
    val task = binding.task
    return copy(
        parentChannelId = binding.localRoute?.channelId ?: parentChannelId,
        taskNumber = task.taskNumber ?: taskNumber,
        taskStatus = task.status,
        taskClaimedByName = task.assignee?.displayName?.takeIf { it.isNotBlank() }
    )
}

private const val ActivityRealtimeCountedSeqLimit = 512

/**
 * [markCounted] records a counted message seq and answers false when it was counted before: a message
 * delivered live and again in a `sync:resume` batch is one unread.
 */
private fun RealtimeEvent.activitySummaryUnreadIncrement(
    snapshot: InboxSnapshot,
    activeConversationId: String?,
    currentUserId: String,
    markCounted: (seq: Long) -> Boolean,
): Int =
    when (this) {
        is MessageNew -> snapshot.unreadIncrementForMessage(message, activeConversationId, currentUserId, markCounted)
        is SyncResumeResponse ->
            messages
                .distinctBy { it.id }
                .sumOf { message ->
                    snapshot.unreadIncrementForMessage(message, activeConversationId, currentUserId, markCounted)
                }
        is ThreadUpdated ->
            unreadCount
                ?.takeIf {
                    it > 0 &&
                        !isActiveActivityConversation(threadChannelId.value, activeConversationId, snapshot) &&
                        snapshot.items.none {
                            it.isUnfollowedActivityThread &&
                                (it.threadChannelId == threadChannelId || it.channelId == threadChannelId)
                        }
                }
                ?: 0
        else -> 0
    }

private fun InboxSnapshot.unreadIncrementForMessage(
    message: Message,
    activeConversationId: String?,
    currentUserId: String,
    markCounted: (seq: Long) -> Boolean,
): Int {
    val channelId = message.channelId ?: message.threadChannelId ?: return 0
    if (message.author.id.value == currentUserId) return 0
    if (isActiveActivityConversation(channelId.value, activeConversationId, this)) return 0
    if (items.any { item ->
            item.isUnfollowedActivityThread &&
                (item.threadChannelId == channelId || item.channelId == channelId)
        }) return 0
    if (items.any { item -> item.latestActivityMessageId == message.id || item.lastMessageId == message.id }) return 0
    val seq = message.seq ?: return 1
    return if (markCounted(seq)) 1 else 0
}

private fun isActiveActivityConversation(
    streamChannelId: String,
    activeConversationId: String?,
    snapshot: InboxSnapshot
): Boolean {
    if (activeConversationId.isNullOrBlank()) return false
    if (streamChannelId == activeConversationId) return true
    return snapshot.items.any { item ->
        item.channelId.value == streamChannelId &&
            (
                item.key == activeConversationId ||
                    item.channelId.value == activeConversationId ||
                    item.threadChannelId?.value == activeConversationId ||
                    item.parentMessageId?.value == activeConversationId
                )
    }
}

private fun DataResult<InboxSnapshot>.activityDiagnosticsResult(): String =
    when (this) {
        is DataResult.Content -> "content items=${data.items.size} unread=${data.totalUnreadCount}"
        is DataResult.Empty -> "empty code=${reason.name}"
        is DataResult.Error -> "error code=${error.code}"
        is DataResult.NoPermission -> "no_permission code=${blocker.code}"
    }

@Serializable
data class SharedActivitySenderProfile(
    val displayName: String
)

internal fun InboxItem.readReceiptChannelId(): ChannelId? =
    if (kind == InboxItemKind.Thread) threadChannelId else channelId

private fun InboxItem.activityUnreadAnchor(): MessageId? =
    firstUnreadMessageId ?: latestActivityMessageId ?: lastMessageId ?: parentMessageId

private fun InboxItem.isChannelOrDmRow(): Boolean =
    kind == InboxItemKind.Channel || kind == InboxItemKind.DirectMessage
