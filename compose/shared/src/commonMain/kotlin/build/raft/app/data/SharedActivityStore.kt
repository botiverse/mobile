package build.raft.app.data

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
import build.raft.app.model.ServerId
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
    val replyCount: Int
)

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
    private var replyThreadFreshnessLedger: SharedThreadFreshnessLedger = SharedThreadFreshnessLedger(),
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
    private var onUnreadCountsChanged: () -> Unit = onUnreadCountsChanged
    private var onSummaryFact: (ActivityUnreadSummaryFact) -> Unit = onSummaryFact
    private var onSummaryInvalidated: (ActivityUnreadSummaryInvalidation) -> Unit = onSummaryInvalidated
    // Local read / Done overlays: keep a confirmed mutation applied while the
    // lagging Activity view still serves the click-time activity marker.
    private val readOverlay = ActivityLocalMutationOverlay(nowMillis, localMutationHoldMillis)
    private val doneOverlay = ActivityLocalMutationOverlay(nowMillis, localMutationHoldMillis)
    private var lastResetLoadStartedAtMillis: Long? = null
    /**
     * Local duplicate-request fence for Activity Follow/Unfollow actions.
     * The server remains the authority for the eventual boolean; this set only
     * prevents two taps from dispatching the same mutation concurrently.
     */
    private var pendingThreadFollowMutations: Set<String> = emptySet()
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
    private var tailPrewarmLoopJob: Job? = null
    private var tailInFlightJob: Job? = null
    private var tailDrainBarrier: Job? = null
    private var realtimeBackgroundReconcileJob: Job? = null
    private var realtimeBackgroundReconcileDirty = false
    // task #399: DM-parent thread rows whose peer could not be resolved from the
    // last DM list fetch; retried only when a NEW unresolved id appears.
    private var unresolvedDmPeerChannelIds: Set<ChannelId> = emptySet()
    private var dmPeerNamesFetchInFlight = false
    private val realtimeHighWater = mutableMapOf<String, ActivityRealtimeHighWater>()
    // One owner for live row patches, version-ledger changes, background reconcile scheduling,
    // and canonical load folds.
    private val realtimeOwnerLock = SynchronizedObject()

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
        replyThreadFreshnessLedger: SharedThreadFreshnessLedger? = null,
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
        if (replyThreadFreshnessLedger != null) {
            this.replyThreadFreshnessLedger = replyThreadFreshnessLedger
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
            SharedActivityIntent.ClearSummary -> clearSummary()
            SharedActivityIntent.LoadInitial -> load(refresh = true, intent = intent)
            SharedActivityIntent.Refresh -> load(refresh = true, pullRefresh = false, intent = intent)
            SharedActivityIntent.PullRefresh -> load(refresh = true, pullRefresh = true, intent = intent)
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

    suspend fun acceptRealtime(intent: SharedActivityIntent) {
        when (intent) {
            is SharedActivityIntent.ApplyRealtimeEvent -> {
                applyRealtimeEvent(intent)
                synchronized(realtimeOwnerLock) { realtimeBackgroundReconcileJob }?.join()
                awaitCurrentLoadIdle()
            }
            is SharedActivityIntent.ApplyRealtimeSummaryEvent ->
                applyRealtimeSummaryEvent(intent.event, intent.activeConversationId)
            else -> error("acceptRealtime only accepts realtime intents")
        }
    }

    fun clear() {
        val ownerServerId = synchronized(realtimeOwnerLock) {
            val owner = _state.value.ownerServerId
            loadGeneration += 1L
            loadJob?.cancel()
            loadJob = null
            realtimeBackgroundReconcileJob?.cancel()
            realtimeBackgroundReconcileJob = null
            realtimeBackgroundReconcileDirty = false
            realtimeHighWater.clear()
            pendingThreadFollowMutations = emptySet()
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
                try {
                    val result = replyPrewarmSemaphore.withPermit { prewarmReplyThread(marker) }
                    if (activeReplyPrewarmMarker(key)?.contentGeneration !== marker.contentGeneration) return@launch
                    when (result) {
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
                val cachedSnapshot = _state.value.filterSnapshots[filter]
                _state.value =
                    _state.value.copy(
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

    private fun clearSummary() {
        synchronized(realtimeOwnerLock) {
            realtimeHighWater.clear()
            _state.value =
                SharedActivityState(
                    selectedFilter = _state.value.selectedFilter,
                    ownerServerId = _state.value.ownerServerId,
                    ownerUserId = _state.value.ownerUserId
                )
        }
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
        if (!event.refreshesActivity()) return null
        when (event) {
            is MessageUpdated -> acceptMessageUpdatedFreshness(event.message)
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
        if (existingIndex < 0) return false
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
        val increment = event.activitySummaryUnreadIncrement(snapshot, activeConversationId, currentUserId)
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
            rememberUnreadCleared(overlayOwner(current), covered, confirmed = true)
            val coveredKeys = covered.mapTo(mutableSetOf(), InboxItem::key)
            val keepsRows = current.selectedFilter.keepsReadActivityRows()
            val items =
                if (keepsRows) {
                    snapshot.items.map { item ->
                        if (item.key in coveredKeys) {
                            item.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false)
                        } else {
                            item
                        }
                    }
                } else {
                    snapshot.items.filterNot { item -> item.key in coveredKeys }
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
                if (
                    refresh &&
                    !pullRefresh &&
                    intent == SharedActivityIntent.Refresh &&
                    inFlightLoad != null &&
                    (current.loading || current.refreshing)
                ) {
                    return@synchronized null
                }
                if (!refresh && (current.loading || current.refreshing || current.loadingMore || !current.hasMore)) {
                    return@synchronized null
                }
                val generation = ++loadGeneration
                if (refresh) lastResetLoadStartedAtMillis = nowMillis()
                loadJob?.cancel()
                scope.launch(start = CoroutineStart.LAZY) {
                    val startedAt = TimeSource.Monotonic.markNow()
                    val previous = _state.value
                    val filter = previous.selectedFilter
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
                                presentationStore.clear()
                            }
                            val stateForOwner =
                                if (ownerChanged) {
                                    SharedActivityState(selectedFilter = stateBeforeOwner.selectedFilter)
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
        val projectedContentData =
            (result as? DataResult.Content)?.data?.projectActivityFilter(current.selectedFilter)
        val owner = overlayOwner(current)
        val nextState =
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
                            realtimeReconcile.items.dedupeActivityItems()
                        }
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
                            loadMoreErrorCode = null
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
                                items = realtimeReconcile.items,
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
                            loadMoreErrorCode = null
                        )
                    nextState
                }
                is DataResult.Error ->
                    if (backgroundRefresh) {
                        current
                    } else {
                        current.copy(
                            loading = false,
                            refreshing = false,
                            pullRefreshing = false,
                            loadingMore = false,
                            errorCode = if (append) null else result.error.code,
                            loadMoreErrorCode = if (append) result.error.code else null
                        )
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
        acceptCanonicalReplyThreadFacts(nextState)
        reduceState(
            intent = intent,
            nextState = nextState,
            summaryAuthoritative =
                !append &&
                    current.selectedFilter == InboxFilter.All &&
                    (result is DataResult.Content || result is DataResult.Empty),
            summaryFactCounts =
                if (!append && current.selectedFilter == InboxFilter.All) {
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
        val canonicalKeys = canonicalItems.mapTo(mutableSetOf()) { item -> item.key }
        var preserveTotalUnreadFloor = false
        val reconciled =
            canonicalItems.map { canonical ->
                val localVersion = realtimeHighWater[canonical.key] ?: return@map canonical
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
            items = (missingCurrent + reconciled).dedupeActivityItems(),
            preserveTotalUnreadFloor = preserveTotalUnreadFloor
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
            replyCount = current.replyCount,
            unreadCount = current.unreadCount,
            firstUnreadMessageId = current.firstUnreadMessageId
        ).withActivityFollowStateProjection()

    private fun markAllRead() {
        val previous = _state.value
        val snapshot = previous.snapshot ?: return
        if (snapshot.totalUnreadCount <= 0) return
        val updatedSnapshot =
            snapshot.copy(
                items =
                    if (previous.selectedFilter.keepsReadActivityRows()) {
                        snapshot.items.map { it.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false) }
                    } else {
                        emptyList()
                    },
                totalCount = if (previous.selectedFilter.keepsReadActivityRows()) snapshot.totalCount else 0,
                totalUnreadCount = 0
            )
        val overlayOwner = overlayOwner(previous)
        val readGenerations = rememberUnreadCleared(overlayOwner, snapshot.items)
        reduceState(SharedActivityIntent.MarkAllRead, previous.copy(snapshot = updatedSnapshot, errorCode = null))
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
        }
    }

    private fun followThread(item: InboxItem) {
        if (!item.isThreadActivityItem() || !item.isUnfollowedActivityThread) return
        val currentItem = _state.value.items.firstOrNull { it.key == item.key } ?: return
        if (!currentItem.isUnfollowedActivityThread) return
        if (!beginThreadFollowMutation(item.key)) return
        val previous = _state.value
        updateThreadFollowProjection(item.key, following = true)
        scope.launch {
            val parentMessageId = currentItem.parentMessageId
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
            } else {
                refreshAfterFailure(previous, result.failureCodeOr("activity_follow_failed"))
            }
        }
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
        val snapshot = previous.snapshot ?: return
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
        val coordinator = readReceiptCoordinator
        if (owner != null && coordinator != null) {
            applyReadReceiptPrediction(coordinator.markReadAll(owner, targetChannelId))
            return
        }
        val key = item.key
        val overlayOwner = overlayOwner(previous)
        val readGenerations = rememberUnreadCleared(overlayOwner, listOf(item))
        val updatedItems =
            if (previous.selectedFilter.keepsReadActivityRows()) {
                snapshot.items.map { existing ->
                    if (existing.key == key) {
                        existing.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false)
                    } else {
                        existing
                    }
                }
            } else {
                snapshot.items.filterNot { it.key == key }
            }
        reduceState(
            SharedActivityIntent.MarkRead(item),
            previous.copy(
                snapshot =
                    snapshot.copy(
                        items = updatedItems,
                        totalCount = if (previous.selectedFilter.keepsReadActivityRows()) snapshot.totalCount else maxOf(0, snapshot.totalCount - 1),
                        totalUnreadCount = maxOf(0, snapshot.totalUnreadCount - item.unreadCount)
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
                if (current.selectedFilter.keepsReadActivityRows()) {
                    snapshot.items.map { item ->
                        if (item.key in matchingKeys) {
                            item.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false)
                        } else {
                            item
                        }
                    }
                } else {
                    snapshot.items.filterNot { item -> item.key in matchingKeys }
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
                errorCode = null
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
    val loadMoreErrorCode: String? = null
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

sealed interface SharedActivityIntent {
    data object ClearSummary : SharedActivityIntent
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
        SharedActivityIntent.ClearSummary -> "clear_summary"
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
        SharedActivityIntent.ClearSummary,
        SharedActivityIntent.LoadInitial,
        SharedActivityIntent.Refresh,
        SharedActivityIntent.PullRefresh,
        SharedActivityIntent.LoadMore,
        is SharedActivityIntent.SetFilter,
        is SharedActivityIntent.ApplyReadReceiptResult,
        is SharedActivityIntent.PrewarmVisibleReplyThreads,
        is SharedActivityIntent.PrewarmActivityTail,
        SharedActivityIntent.DetachPrewarmViewport -> false
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

private fun RealtimeEvent.activitySummaryUnreadIncrement(
    snapshot: InboxSnapshot,
    activeConversationId: String?,
    currentUserId: String
): Int =
    when (this) {
        is MessageNew -> snapshot.unreadIncrementForMessage(message, activeConversationId, currentUserId)
        is SyncResumeResponse ->
            messages
                .distinctBy { it.id }
                .sumOf { message -> snapshot.unreadIncrementForMessage(message, activeConversationId, currentUserId) }
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
    currentUserId: String
): Int {
    val channelId = message.channelId ?: message.threadChannelId ?: return 0
    if (message.author.id.value == currentUserId) return 0
    if (isActiveActivityConversation(channelId.value, activeConversationId, this)) return 0
    if (items.any { item ->
            item.isUnfollowedActivityThread &&
                (item.threadChannelId == channelId || item.channelId == channelId)
        }) return 0
    if (items.any { item -> item.latestActivityMessageId == message.id || item.lastMessageId == message.id }) return 0
    return 1
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
