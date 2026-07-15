package build.raft.app.data

import build.raft.app.SharedThreadFreshnessGeneration
import build.raft.app.SharedThreadFreshnessLedger
import build.raft.app.SharedThreadSummaryFact
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskCreated
import build.raft.app.model.TaskDeleted
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadUpdated
import build.raft.app.data.realtime.PerReceiverReadStateCheckpoint
import build.raft.app.data.realtime.PerReceiverReadStateFactResult
import build.raft.app.data.realtime.PerReceiverStateVersionStore
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
private const val SharedActivityReplyPrewarmConcurrency = 2
private const val SharedActivityTailPrewarmDepthCap = 20
private const val SharedActivityRealtimeReconcileDelayMillis = 150L

private data class ActivityRealtimeHighWater(
    val marker: MessageId?,
    val replyCount: Int
)

private data class ActivityRealtimeReconcileResult(
    val items: List<InboxItem>
)

data class ActivityReplyThreadPrewarmKey(
    val serverId: String,
    val parentChannelId: String,
    val parentMessageId: String,
    val threadChannelId: String
)

data class ActivityReplyThreadPrewarmMarker(
    val key: ActivityReplyThreadPrewarmKey,
    val contentGeneration: SharedThreadFreshnessGeneration? = null
)

class SharedActivityStore internal constructor(
    private var authStore: SlockAuthSessionStore,
    private var inboxRepository: InboxRepository,
    private var readReceiptRepository: ReadReceiptRepository,
    private var conversationRepository: ConversationRepository,
    private var memberRepository: MemberRepository? = null,
    private var serverMembersStore: SharedServerMembersStore? = null,
    private var tasksDataStore: SharedTasksDataStore? = null,
    private val perReceiverStateVersionStore: PerReceiverStateVersionStore,
    private var readReceiptCoordinator: ReadReceiptCoordinator? = null,
    private var readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = null,
    private var prewarmReplyThread: suspend (ActivityReplyThreadPrewarmMarker) -> DataResult<Unit> = {
        DataResult.Empty(EmptyReason.NoMessages)
    },
    private var replyThreadFreshnessLedger: SharedThreadFreshnessLedger = SharedThreadFreshnessLedger(),
    private val tailPrewarmDepthCap: Int = SharedActivityTailPrewarmDepthCap,
    private var persistSlices: List<MviPersistSlice<SharedActivityState, SharedActivityIntent>> = emptyList(),
    onUnreadCountsChanged: () -> Unit = {},
    private val scope: CoroutineScope,
    private var diagnosticsLogger: (String) -> Unit = {},
    private val realtimeReconcileDelayMillis: Long = SharedActivityRealtimeReconcileDelayMillis,
    private val afterRealtimeReconcileTerminalDecision: suspend () -> Unit = {},
    private val beforeRealtimeHighWaterFold: () -> Unit = {},
    private val onRealtimePatchOwnerEntered: () -> Unit = {},
    private val onReadReceiptPredictionOwnerEntered: () -> Unit = {}
) {
    private val _state = MutableStateFlow(SharedActivityState())
    val state: StateFlow<SharedActivityState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadGeneration: Long = 0L
    private var onUnreadCountsChanged: () -> Unit = onUnreadCountsChanged
    private var unreadClearOverrides: Map<String, ActivityUnreadClearOverride> = emptyMap()
    private var doneOverrides: Map<String, ActivityDoneOverride> = emptyMap()
    private var pendingReadStateFacts: Map<ActivityReadStateProjectionKey, PerReceiverReadStateCheckpoint> = emptyMap()
    private var reconciledReadStateFacts: Map<ActivityReadStateProjectionKey, PerReceiverReadStateCheckpoint> = emptyMap()
    private var readPredictionRevisions: Map<ChannelId, Long> = emptyMap()
    private var readPredictionItemKeys: Map<ChannelId, Set<String>> = emptyMap()
    private val replyPrewarmSemaphore = Semaphore(SharedActivityReplyPrewarmConcurrency)
    private var visibleReplyPrewarmMarkers: Map<ActivityReplyThreadPrewarmKey, ActivityReplyThreadPrewarmMarker> = emptyMap()
    private var tailReplyPrewarmMarkers: Map<ActivityReplyThreadPrewarmKey, ActivityReplyThreadPrewarmMarker> = emptyMap()
    private val completedReplyPrewarmGenerations =
        mutableMapOf<ActivityReplyThreadPrewarmKey, SharedThreadFreshnessGeneration>()
    private val failedReplyPrewarmGenerations =
        mutableMapOf<ActivityReplyThreadPrewarmKey, SharedThreadFreshnessGeneration>()
    private val replyPrewarmJobs = mutableMapOf<ActivityReplyThreadPrewarmKey, Job>()
    private var tailPrewarmLoopJob: Job? = null
    private var tailInFlightJob: Job? = null
    private var tailDrainBarrier: Job? = null
    private var realtimeReconcileJob: Job? = null
    private var realtimeReconcileDirty = false
    private val realtimeHighWater = mutableMapOf<String, ActivityRealtimeHighWater>()
    // One owner for live row patches, version ledger changes, load folds, and scheduler lifecycle.
    private val realtimeOwnerLock = SynchronizedObject()

    internal fun updateRuntimeIntegrations(
        authStore: SlockAuthSessionStore = this.authStore,
        inboxRepository: InboxRepository = this.inboxRepository,
        readReceiptRepository: ReadReceiptRepository = this.readReceiptRepository,
        conversationRepository: ConversationRepository = this.conversationRepository,
        memberRepository: MemberRepository? = this.memberRepository,
        serverMembersStore: SharedServerMembersStore? = this.serverMembersStore,
        tasksDataStore: SharedTasksDataStore? = this.tasksDataStore,
        prewarmReplyThread: (suspend (ActivityReplyThreadPrewarmMarker) -> DataResult<Unit>)? = null,
        replyThreadFreshnessLedger: SharedThreadFreshnessLedger? = null,
        persistSlices: List<MviPersistSlice<SharedActivityState, SharedActivityIntent>> = this.persistSlices,
        readReceiptCoordinator: ReadReceiptCoordinator? = this.readReceiptCoordinator,
        readReceiptOwnerFactory: ((String, String) -> ReadReceiptOwner)? = this.readReceiptOwnerFactory,
        onUnreadCountsChanged: (() -> Unit)? = null,
        diagnosticsLogger: ((String) -> Unit)? = null
    ) {
        this.authStore = authStore
        this.inboxRepository = inboxRepository
        this.readReceiptRepository = readReceiptRepository
        this.conversationRepository = conversationRepository
        this.memberRepository = memberRepository
        this.serverMembersStore = serverMembersStore
        this.tasksDataStore = tasksDataStore
        if (prewarmReplyThread != null) {
            this.prewarmReplyThread = prewarmReplyThread
        }
        if (replyThreadFreshnessLedger != null) {
            this.replyThreadFreshnessLedger = replyThreadFreshnessLedger
        }
        this.persistSlices = persistSlices
        this.readReceiptCoordinator = readReceiptCoordinator
        this.readReceiptOwnerFactory = readReceiptOwnerFactory
        if (onUnreadCountsChanged != null) {
            this.onUnreadCountsChanged = onUnreadCountsChanged
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
            is SharedActivityIntent.MarkRead -> markRead(intent.item)
            is SharedActivityIntent.MarkUnread -> markUnread(intent.item)
            is SharedActivityIntent.ApplyReadReceiptPrediction -> applyReadReceiptPrediction(intent.prediction)
            is SharedActivityIntent.ApplyReadReceiptFailure -> handleReadReceiptFailure(intent.request)
            is SharedActivityIntent.PrewarmVisibleReplyThreads -> prewarmVisibleReplyThreads(intent.markers)
            is SharedActivityIntent.PrewarmActivityTail -> prewarmActivityTail(intent.orderedMarkers)
            is SharedActivityIntent.ApplyRealtimeEvent -> {
                if (intent.event is PerReceiverReadStateChanged) {
                    scope.launch { applyPerReceiverReadStateChanged(intent.event) }
                } else if (applyRealtimeEventImmediately(intent)) {
                    load(refresh = true, pullRefresh = false, intent = SharedActivityIntent.Refresh)
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
                synchronized(realtimeOwnerLock) { realtimeReconcileJob }?.join()
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
            realtimeReconcileJob?.cancel()
            realtimeReconcileJob = null
            realtimeReconcileDirty = false
            realtimeHighWater.clear()
            pendingReadStateFacts = emptyMap()
            owner
        }
        ownerServerId?.let(replyThreadFreshnessLedger::invalidateServer)
        clearReplyPrewarmState()
    }

    fun reset() {
        clear()
        synchronized(realtimeOwnerLock) {
            unreadClearOverrides = emptyMap()
            doneOverrides = emptyMap()
            readPredictionRevisions = emptyMap()
            readPredictionItemKeys = emptyMap()
            reconciledReadStateFacts = emptyMap()
            _state.value = SharedActivityState()
        }
    }

    private fun prewarmVisibleReplyThreads(markers: Set<ActivityReplyThreadPrewarmMarker>) {
        val nextVisible = markers.map(::stampReplyThreadPrewarmMarker).associateBy { it.key }
        val previousVisible = visibleReplyPrewarmMarkers
        visibleReplyPrewarmMarkers = nextVisible

        cancelReplyPrewarmJobsNotIn(nextVisible.keys + tailReplyPrewarmMarkers.keys)

        nextVisible.values.forEach { marker ->
            val key = marker.key
            val enteredViewport = key !in previousVisible
            val versionChanged = previousVisible[key]?.contentGeneration !== marker.contentGeneration
            if (completedReplyPrewarmGenerations[key] === marker.contentGeneration) return@forEach
            if (replyPrewarmJobs[key]?.isActive == true) return@forEach
            if (!enteredViewport && !versionChanged && failedReplyPrewarmGenerations[key] === marker.contentGeneration) {
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
                            marker.contentGeneration?.let { completedReplyPrewarmGenerations[key] = it }
                            failedReplyPrewarmGenerations.remove(key)
                            diagnosticsLogger(
                                "activity_reply_prewarm_result result=success serverId=${key.serverId} " +
                                    "parentChannelId=${key.parentChannelId} parentMessageId=${key.parentMessageId} " +
                                    "threadChannelId=${key.threadChannelId}"
                            )
                        }
                        is DataResult.Empty -> {
                            marker.contentGeneration?.let { failedReplyPrewarmGenerations[key] = it }
                            diagnosticsLogger(
                                "activity_reply_prewarm_result result=empty reason=${result.reason.name} " +
                                    "serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                                    "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
                            )
                        }
                        is DataResult.Error -> {
                            marker.contentGeneration?.let { failedReplyPrewarmGenerations[key] = it }
                            diagnosticsLogger(
                                "activity_reply_prewarm_result result=error code=${result.error.code} " +
                                    "serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                                    "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
                            )
                        }
                        is DataResult.NoPermission -> {
                            marker.contentGeneration?.let { failedReplyPrewarmGenerations[key] = it }
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
                    if (activeReplyPrewarmMarker(key)?.contentGeneration === marker.contentGeneration) {
                        marker.contentGeneration?.let { failedReplyPrewarmGenerations[key] = it }
                    }
                    diagnosticsLogger(
                        "activity_reply_prewarm_result result=exception errorType=${throwable::class.simpleName.orEmpty()} " +
                            "serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                            "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
                    )
                } finally {
                    val currentJob = currentCoroutineContext()[Job]
                    if (replyPrewarmJobs[key] === currentJob) {
                        replyPrewarmJobs.remove(key)
                        visibleReplyPrewarmMarkers[key]
                            ?.takeIf { latest -> latest.contentGeneration !== marker.contentGeneration }
                            ?.let(::launchReplyThreadPrewarm)
                    }
                }
            }
        replyPrewarmJobs[key] = job
        diagnosticsLogger(
            "activity_reply_prewarm_start serverId=${key.serverId} parentChannelId=${key.parentChannelId} " +
                "parentMessageId=${key.parentMessageId} threadChannelId=${key.threadChannelId}"
        )
        job.start()
        return job
    }

    // Lower-priority "tail": warm the rest of the activity list top-first, reusing the exact
    // visible-prewarm machinery (same marker/key, same prewarmReplyThread, same replyPrewarmSemaphore,
    // same completed/active/failed dedup maps, same shared candidate cache). The ordered list is
    // visible-first then below-the-fold top-to-bottom; visible keys are handled by the visible flow
    // and skipped here. A single serialized loop launches one tail item at a time and awaits it, so
    // the tail never holds more than one permit — it can only run when the visible set is not
    // saturating the shared Semaphore, and total concurrency stays bounded by that Semaphore.
    private fun prewarmActivityTail(orderedMarkers: List<ActivityReplyThreadPrewarmMarker>) {
        val nextTail = LinkedHashMap<ActivityReplyThreadPrewarmKey, ActivityReplyThreadPrewarmMarker>()
        orderedMarkers.map(::stampReplyThreadPrewarmMarker).forEach { marker ->
            nextTail.getOrPut(marker.key) { marker }
        }
        tailReplyPrewarmMarkers = nextTail

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
        cancelReplyPrewarmJobsNotIn(visibleReplyPrewarmMarkers.keys + nextTail.keys)

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
        val key = marker.key
        if (key in visibleReplyPrewarmMarkers) return false
        if (completedReplyPrewarmGenerations[key] === marker.contentGeneration) return false
        if (replyPrewarmJobs[key]?.isActive == true) return false
        if (failedReplyPrewarmGenerations[key] === marker.contentGeneration) return false
        return true
    }

    private fun activeReplyPrewarmMarker(
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
        val generation = replyThreadFreshnessLedger.capture(marker.key.serverId, marker.key.threadChannelId)
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
        replyPrewarmJobs
            .filterKeys { key -> key !in activeKeys }
            .values
            .toList()
            .forEach(Job::cancel)
    }

    private fun clearReplyPrewarmState() {
        tailPrewarmLoopJob?.cancel()
        tailPrewarmLoopJob = null
        tailInFlightJob?.cancel()
        tailInFlightJob = null
        tailDrainBarrier?.cancel()
        tailDrainBarrier = null
        replyPrewarmJobs.values.toList().forEach(Job::cancel)
        replyPrewarmJobs.clear()
        visibleReplyPrewarmMarkers = emptyMap()
        tailReplyPrewarmMarkers = emptyMap()
        completedReplyPrewarmGenerations.clear()
        failedReplyPrewarmGenerations.clear()
    }

    private fun setFilter(filter: InboxFilter) {
        val changed =
            synchronized(realtimeOwnerLock) {
                if (_state.value.selectedFilter == filter) return@synchronized false
                realtimeHighWater.clear()
                _state.value =
                    SharedActivityState(
                        selectedFilter = filter,
                        loading = true
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
                    selectedFilter = _state.value.selectedFilter
                )
        }
    }

    private suspend fun applyRealtimeEvent(intent: SharedActivityIntent.ApplyRealtimeEvent) {
        if (intent.event is PerReceiverReadStateChanged) {
            applyPerReceiverReadStateChanged(intent.event)
            return
        }
        val shouldRefresh = applyRealtimeEventImmediately(intent)
        if (shouldRefresh) {
            load(refresh = true, pullRefresh = false, intent = SharedActivityIntent.Refresh)
            awaitCurrentLoadIdle()
        }
    }

    private fun applyRealtimeEventImmediately(intent: SharedActivityIntent.ApplyRealtimeEvent): Boolean {
        val (shouldRefresh, reconcileJobToStart) =
            synchronized(realtimeOwnerLock) {
                applyRealtimeEventLocked(intent) to
                    realtimeReconcileJob?.takeIf { job -> !job.isActive && !job.isCompleted }
            }
        reconcileJobToStart?.start()
        return shouldRefresh
    }

    private fun applyRealtimeEventLocked(intent: SharedActivityIntent.ApplyRealtimeEvent): Boolean {
        val event = intent.event
        if (!event.refreshesActivity()) return false
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
        if (event is MessageNew || event is SyncResumeResponse || event is ThreadUpdated) {
            if (intent.allowLocalPatch && _state.value.selectedFilter != InboxFilter.Mentions) {
                when (event) {
                    is MessageNew -> applyMessageNewPatch(event.message, intent)
                    is ThreadUpdated -> applyThreadUpdatedPatch(event, intent)
                    else -> Unit
                }
            }
            scheduleRealtimeReconcileLocked()
            return false
        }
        val current = _state.value
        return !current.loading && !current.refreshing && (!current.loaded || current.snapshot != null)
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

        val patched =
            existing.copy(
                latestActivityMessageId = message.id,
                latestActivityPreview = message.body,
                latestActivityAt = message.timestampLabel,
                latestActivitySenderName = message.author.displayName,
                latestActivitySenderId = message.author.id.value,
                latestActivitySenderKind = message.author.kind,
                latestActivitySenderAvatarWireValue = message.author.avatar?.wireValue ?: message.author.avatarUrl,
                replyCount = existing.replyCount + 1
            )
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
        val patched =
            existing.copy(
                replyCount = event.replyCount,
                latestActivityAt =
                    if (event.replyCount > existing.replyCount && event.lastReplyAt != null) {
                        event.lastReplyAt
                    } else {
                        existing.latestActivityAt
                    }
            )
        if (patched == existing) return true
        advanceReplyThreadContent(existing, patched)
        rememberRealtimeHighWater(patched)
        applyPatchedActivityItem(patched, existingIndex, current, snapshot, intent)
        return true
    }

    private fun applyPatchedActivityItem(
        patched: InboxItem,
        existingIndex: Int,
        current: SharedActivityState,
        snapshot: InboxSnapshot,
        intent: SharedActivityIntent.ApplyRealtimeEvent
    ) {
        val previous = snapshot.items[existingIndex]
        val items = listOf(patched) + snapshot.items.filterIndexed { index, _ -> index != existingIndex }
        reduceState(
            intent,
            current.copy(
                snapshot =
                    snapshot.copy(
                        items = items,
                        totalUnreadCount = maxOf(0, snapshot.totalUnreadCount + patched.unreadCount - previous.unreadCount)
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

    private fun scheduleRealtimeReconcileLocked() {
        realtimeReconcileDirty = true
        if (realtimeReconcileJob != null) return
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(realtimeReconcileDelayMillis.coerceAtLeast(0L))
                while (true) {
                    val shouldReconcile =
                        synchronized(realtimeOwnerLock) {
                            if (realtimeReconcileDirty) {
                                realtimeReconcileDirty = false
                                true
                            } else {
                                realtimeReconcileJob = null
                                false
                            }
                        }
                    if (!shouldReconcile) {
                        afterRealtimeReconcileTerminalDecision()
                        return@launch
                    }
                    awaitCurrentLoadIdle()
                    load(refresh = true, pullRefresh = false, intent = SharedActivityIntent.Refresh)
                    awaitCurrentLoadIdle()
                }
            }
        realtimeReconcileJob = job
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
        var shouldReconcile = false
        var acceptedHere = false
        synchronized(realtimeOwnerLock) {
            event.scopes.forEach { readScope ->
                val maxReadSeq = readScope.maxReadSeq ?: return@forEach
                val projectionKey =
                    ActivityReadStateProjectionKey(
                        serverId = serverId,
                        memberId = memberId,
                        scopeId = readScope.scopeId
                    )
                val checkpoint =
                    PerReceiverReadStateCheckpoint(
                        version = readScope.readStateVersion,
                        maxReadSeq = maxReadSeq
                    )
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
                val alreadyScheduledOrReconciled =
                    pendingReadStateFacts[projectionKey] == checkpoint ||
                        reconciledReadStateFacts[projectionKey] == checkpoint
                if (result != PerReceiverReadStateFactResult.Stale && !alreadyScheduledOrReconciled) {
                    pendingReadStateFacts = pendingReadStateFacts + (projectionKey to checkpoint)
                    shouldReconcile = true
                }
                acceptedHere = acceptedHere || result == PerReceiverReadStateFactResult.Accepted
            }
        }
        if (shouldReconcile) {
            diagnosticsLogger("activity_read_state_reconcile reason=canonical_projection_required")
            if (acceptedHere) onUnreadCountsChanged()
            load(refresh = true, pullRefresh = false, intent = SharedActivityIntent.Refresh)
            awaitCurrentLoadIdle()
        }
    }

    private fun load(
        refresh: Boolean,
        pullRefresh: Boolean = false,
        intent: SharedActivityIntent
    ) {
        val job =
            synchronized(realtimeOwnerLock) {
                val current = _state.value
                if (!refresh && (current.loading || current.refreshing || current.loadingMore || !current.hasMore)) {
                    return@synchronized null
                }
                val generation = ++loadGeneration
                loadJob?.cancel()
                scope.launch(start = CoroutineStart.LAZY) {
                    val startedAt = TimeSource.Monotonic.markNow()
                    val previous = _state.value
                    val filter = previous.selectedFilter
                    try {
                        val session = authStore.readSession()
                        if (session == null) {
                            synchronized(realtimeOwnerLock) {
                                setLoadError("unauthenticated", append = !refresh)
                            }
                            logActivityLoad(
                                result = "error",
                                code = "unauthenticated",
                                refresh = refresh,
                                append = !refresh,
                                filter = filter,
                                durationMs = startedAt.elapsedNow().inWholeMilliseconds
                            )
                            return@launch
                        }
                        val serverId = session.currentServerId?.let(::ServerId)
                        if (serverId == null) {
                            synchronized(realtimeOwnerLock) {
                                setLoadError("server_missing", append = !refresh)
                            }
                            logActivityLoad(
                                result = "error",
                                code = "server_missing",
                                refresh = refresh,
                                append = !refresh,
                                filter = filter,
                                durationMs = startedAt.elapsedNow().inWholeMilliseconds
                            )
                            return@launch
                        }
                        val userId = session.user.id
                        val server = session.servers.firstOrNull { it.id == serverId.value }?.toServerSummary()
                        if (server == null) {
                            synchronized(realtimeOwnerLock) {
                                setLoadError("server_missing", append = !refresh)
                            }
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
                            }
                            val stateForOwner =
                                if (ownerChanged) {
                                    SharedActivityState(selectedFilter = stateBeforeOwner.selectedFilter)
                                } else {
                                    stateBeforeOwner
                                }
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
                        val result =
                            if (refresh) {
                                inboxRepository.loadInitial(serverId, filter, SharedActivityPageSize)
                            } else {
                                inboxRepository.loadMore(
                                    serverId,
                                    filter,
                                    previous.nextOffset ?: previous.items.size,
                                    SharedActivityPageSize
                                )
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
                                        intent = intent
                                    )
                                    completeReadStateReconcileLocked(
                                        successful = result is DataResult.Content || result is DataResult.Empty
                                    )
                                    true
                                }
                            }
                        if (!applied) return@launch
                        logActivityLoad(
                            result = result.activityDiagnosticsResult(),
                            code = null,
                            refresh = refresh,
                            append = !refresh,
                            filter = filter,
                            durationMs = startedAt.elapsedNow().inWholeMilliseconds
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        val handled =
                            synchronized(realtimeOwnerLock) {
                                if (generation != loadGeneration) {
                                    false
                                } else {
                                    completeReadStateReconcileLocked(successful = false)
                                    setLoadError("activity_network", append = !refresh)
                                    true
                                }
                            }
                        if (!handled) return@launch
                        logActivityLoad(
                            result = "exception",
                            code = "activity_network",
                            refresh = refresh,
                            append = !refresh,
                            filter = filter,
                            durationMs = startedAt.elapsedNow().inWholeMilliseconds
                        )
                    }
                }.also { loadJob = it }
            }
        job?.start()
    }

    private fun completeReadStateReconcileLocked(successful: Boolean) {
        if (pendingReadStateFacts.isEmpty()) return
        if (successful) {
            reconciledReadStateFacts = reconciledReadStateFacts + pendingReadStateFacts
        }
        pendingReadStateFacts = emptyMap()
    }

    private fun logActivityLoad(
        result: String,
        code: String?,
        refresh: Boolean,
        append: Boolean,
        filter: InboxFilter,
        durationMs: Long
    ) {
        diagnosticsLogger(
            "store_load domain=activity refresh=$refresh append=$append filter=${filter.name} " +
                "result=$result code=${code.orEmpty()} durationMs=$durationMs"
        )
    }

    private fun applyLoadResult(
        result: DataResult<InboxSnapshot>,
        server: ServerSummary,
        append: Boolean,
        senderProfiles: Map<String, SharedActivitySenderProfile>,
        intent: SharedActivityIntent
    ) {
        val current = _state.value
        val nextState =
            when (result) {
                is DataResult.Content -> {
                    val pageItems =
                        result.data.items
                            .withActivityThreadTaskProjection(result.data.server.id.value, tasksDataStore)
                            .reconcileLocallyClearedUnreadItems(current.selectedFilter)
                            .reconcileLocallyDoneItems()
                    beforeRealtimeHighWaterFold()
                    val realtimeReconcile = reconcileRealtimeHighWater(pageItems.items, append)
                    val items =
                        if (append) {
                            (current.items + realtimeReconcile.items).dedupeActivityItems()
                        } else {
                            realtimeReconcile.items.dedupeActivityItems()
                        }
                    val canonicalUnreadCount =
                        maxOf(
                            0,
                            result.data.totalUnreadCount -
                                pageItems.suppressedUnreadCount -
                                pageItems.suppressedDoneUnreadCount
                        )
                    val nextState =
                        current.copy(
                            snapshot =
                                result.data.copy(
                                    items = items,
                                    totalCount =
                                        if (current.selectedFilter == InboxFilter.Unread) {
                                            maxOf(0, result.data.totalCount - pageItems.removedUnreadRows - pageItems.removedDoneRows)
                                        } else {
                                            maxOf(0, result.data.totalCount - pageItems.removedDoneRows)
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
                    if (!append) realtimeHighWater.clear()
                    val snapshot =
                        if (append) {
                            current.snapshot?.copy(hasMore = false, nextOffset = null)
                                ?: InboxSnapshot(server = server, items = emptyList())
                        } else {
                            InboxSnapshot(server = server, items = emptyList(), totalCount = 0, totalUnreadCount = 0)
                        }
                    val nextState =
                        current.copy(
                            snapshot = snapshot,
                            senderProfiles = if (append) current.senderProfiles else senderProfiles,
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
                    current.copy(
                        loading = false,
                        refreshing = false,
                        pullRefreshing = false,
                        loadingMore = false,
                        errorCode = if (append) null else result.error.code,
                        loadMoreErrorCode = if (append) result.error.code else null
                    )
                is DataResult.NoPermission ->
                    current.copy(
                        loading = false,
                        refreshing = false,
                        pullRefreshing = false,
                        loadingMore = false,
                        errorCode = if (append) null else result.blocker.code,
                        loadMoreErrorCode = if (append) result.blocker.code else null
                    )
            }
        acceptCanonicalReplyThreadFacts(nextState)
        reduceState(intent, nextState)
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
        append: Boolean
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
        val reconciled =
            canonicalItems.map { canonical ->
                val localVersion = realtimeHighWater[canonical.key] ?: return@map canonical
                val current = currentThreads[canonical.key]
                if (current == null) {
                    realtimeHighWater.remove(canonical.key)
                    return@map canonical
                }
                if (canonical.replyCount > localVersion.replyCount) {
                    rememberRealtimeHighWater(canonical)
                    return@map canonical
                }
                if (
                    canonical.replyCount == localVersion.replyCount &&
                    canonical.latestActivityMessageId == localVersion.marker
                ) {
                    return@map canonical
                }
                canonical.withRealtimeActivityContent(current)
            }
        realtimeHighWater.keys.toList().forEach { key ->
            if (key !in canonicalKeys) realtimeHighWater.remove(key)
        }
        return ActivityRealtimeReconcileResult(
            items = reconciled
        )
    }

    private fun InboxItem.withRealtimeActivityContent(current: InboxItem): InboxItem =
        // Realtime high-water owns only live activity content. Read/mention/task state stays canonical.
        copy(
            latestActivityMessageId = current.latestActivityMessageId,
            latestActivityPreview = current.latestActivityPreview,
            latestActivityAt = current.latestActivityAt,
            latestActivitySenderName = current.latestActivitySenderName,
            latestActivitySenderId = current.latestActivitySenderId,
            latestActivitySenderKind = current.latestActivitySenderKind,
            latestActivitySenderAvatarWireValue = current.latestActivitySenderAvatarWireValue,
            replyCount = current.replyCount
        )

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
        rememberUnreadCleared(snapshot.items)
        reduceState(SharedActivityIntent.MarkAllRead, previous.copy(snapshot = updatedSnapshot, errorCode = null))
        scope.launch {
            val serverId = currentServerId()
            val result = serverId?.let { readReceiptRepository.markInboxReadAll(it) }
                ?: DataResult.NoPermission(PermissionBlocker(code = "server_missing"))
            if (result is DataResult.Content || result is DataResult.Empty) {
                onUnreadCountsChanged()
                load(refresh = true, pullRefresh = false, intent = SharedActivityIntent.Refresh)
            } else {
                forgetUnreadCleared(snapshot.items)
                onUnreadCountsChanged()
                refreshAfterFailure(previous, "activity_mark_all_read_failed")
            }
        }
    }

    private fun markDone(item: InboxItem) {
        val previous = _state.value
        if (previous.snapshot == null) return
        rememberDone(item)
        if (item.kind == InboxItemKind.MentionAction) {
            removeItemOptimistically(item)
            return
        }
        val targetChannelId = item.readReceiptChannelId()
        if (targetChannelId == null) {
            forgetDone(item.key)
            refreshAfterFailure(previous, "activity_thread_channel_missing")
            return
        }
        removeItemOptimistically(item)
        scope.launch {
            val result =
                if (item.kind == InboxItemKind.Thread) {
                    conversationRepository.markThreadDone(targetChannelId)
                } else {
                    inboxRepository.markDone(targetChannelId)
                }
            if (result !is DataResult.Content && result !is DataResult.Empty) {
                forgetDone(item.key)
                refreshAfterFailure(previous, "activity_mark_done_failed")
            } else {
                onUnreadCountsChanged()
            }
        }
    }

    private fun markRead(item: InboxItem) {
        if (item.unreadCount <= 0) return
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
        rememberUnreadCleared(item)
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
            val serverId = currentServerId()
            val result = readReceiptRepository.markChannelReadAll(targetChannelId)
            if (result !is DataResult.Content && result !is DataResult.Empty) {
                forgetUnreadCleared(key)
                onUnreadCountsChanged()
                refreshAfterFailure(previous, "activity_mark_read_failed")
            } else {
                onUnreadCountsChanged()
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
            rememberUnreadCleared(matchingUnread)
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
                true
            }
        if (shouldRefresh) {
            load(refresh = true, pullRefresh = false, intent = SharedActivityIntent.Refresh)
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
            if (result is DataResult.Content || result is DataResult.Empty) {
                onUnreadCountsChanged()
                load(refresh = true, pullRefresh = false, intent = SharedActivityIntent.Refresh)
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

    private fun reduceState(intent: SharedActivityIntent, nextState: SharedActivityState): Boolean {
        val previousState = _state.value
        val finalState =
            persistSlices.fold(nextState) { state, slice ->
                slice.reduce(previous = previousState, intent = intent, reduced = state)
            }
        if (finalState == previousState) return false
        _state.value = finalState
        persistSlices.forEach { slice ->
            slice.onTransition(previous = previousState, intent = intent, next = finalState)
        }
        return true
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
                        is DataResult.Content -> store.currentServerMemberProfilesById(serverId)
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
            ?.currentServerMemberProfilesById(serverId)
            ?.toActivitySenderProfiles()
            .orEmpty()

    private fun Map<String, MemberProfile>.toActivitySenderProfiles(): Map<String, SharedActivitySenderProfile> =
        mapValues { (_, profile) ->
            SharedActivitySenderProfile(
                displayName = profile.displayName.ifBlank { profile.handle.ifBlank { profile.id.value } }
            )
        }

    private fun rememberUnreadCleared(items: List<InboxItem>) {
        val unreadItems = items.filter { it.unreadCount > 0 }
        if (unreadItems.isEmpty()) return
        unreadClearOverrides =
            unreadClearOverrides +
                unreadItems.associate { item ->
                    item.key to ActivityUnreadClearOverride(item.activityUnreadAnchor())
                }
    }

    private fun rememberUnreadCleared(item: InboxItem) {
        if (item.unreadCount <= 0) return
        unreadClearOverrides =
            unreadClearOverrides +
                (item.key to ActivityUnreadClearOverride(item.activityUnreadAnchor()))
    }

    private fun forgetUnreadCleared(items: List<InboxItem>) {
        if (unreadClearOverrides.isEmpty()) return
        val keys = items.mapTo(mutableSetOf()) { it.key }
        unreadClearOverrides = unreadClearOverrides.filterKeys { it !in keys }
    }

    private fun forgetUnreadCleared(key: String) {
        if (key in unreadClearOverrides) {
            unreadClearOverrides = unreadClearOverrides - key
        }
    }

    private fun rememberDone(item: InboxItem) {
        doneOverrides =
            doneOverrides +
                (item.key to ActivityDoneOverride(item.activityUnreadAnchor()))
    }

    private fun forgetDone(key: String) {
        if (key in doneOverrides) {
            doneOverrides = doneOverrides - key
        }
    }

    private fun List<InboxItem>.reconcileLocallyClearedUnreadItems(filter: InboxFilter): ActivityUnreadReconcileResult {
        if (unreadClearOverrides.isEmpty()) {
            return ActivityUnreadReconcileResult(items = this)
        }
        val nextOverrides = unreadClearOverrides.toMutableMap()
        val reconciled = mutableListOf<InboxItem>()
        var suppressedUnreadCount = 0
        var removedUnreadRows = 0
        for (item in this) {
            val override = nextOverrides[item.key]
            if (override == null) {
                reconciled += item
                continue
            }
            if (item.unreadCount <= 0) {
                nextOverrides.remove(item.key)
                reconciled += item
                continue
            }
            val incomingAnchor = item.activityUnreadAnchor()
            val staleUnread =
                override.unreadAnchor == null ||
                    incomingAnchor == override.unreadAnchor
            if (staleUnread) {
                suppressedUnreadCount += item.unreadCount
                val clearedItem = item.copy(unreadCount = 0, firstUnreadMessageId = null, hasMention = false)
                if (filter.keepsReadActivityRows()) {
                    reconciled += clearedItem
                } else {
                    removedUnreadRows += 1
                }
            } else {
                nextOverrides.remove(item.key)
                reconciled += item
            }
        }
        unreadClearOverrides = nextOverrides
        return ActivityUnreadReconcileResult(
            items = reconciled,
            suppressedUnreadCount = suppressedUnreadCount,
            removedUnreadRows = removedUnreadRows
        )
    }

    private fun ActivityUnreadReconcileResult.reconcileLocallyDoneItems(): ActivityReconcileResult {
        if (doneOverrides.isEmpty()) {
            return ActivityReconcileResult(
                items = items,
                suppressedUnreadCount = suppressedUnreadCount,
                removedUnreadRows = removedUnreadRows
            )
        }
        val nextOverrides = doneOverrides.toMutableMap()
        val reconciled = mutableListOf<InboxItem>()
        var suppressedDoneUnreadCount = 0
        var removedDoneRows = 0
        for (item in items) {
            val override = nextOverrides[item.key]
            if (override == null) {
                reconciled += item
                continue
            }
            val incomingAnchor = item.activityUnreadAnchor()
            val staleDone =
                override.activityAnchor == null ||
                    incomingAnchor == override.activityAnchor
            if (staleDone) {
                suppressedDoneUnreadCount += item.unreadCount
                removedDoneRows += 1
            } else {
                nextOverrides.remove(item.key)
                reconciled += item
            }
        }
        doneOverrides = nextOverrides
        return ActivityReconcileResult(
            items = reconciled,
            suppressedUnreadCount = suppressedUnreadCount,
            removedUnreadRows = removedUnreadRows,
            suppressedDoneUnreadCount = suppressedDoneUnreadCount,
            removedDoneRows = removedDoneRows
        )
    }
}

private data class ActivityUnreadClearOverride(
    val unreadAnchor: MessageId?
)

private data class ActivityReadStateProjectionKey(
    val serverId: ServerId,
    val memberId: MemberId,
    val scopeId: ChannelId
)

private data class ActivityDoneOverride(
    val activityAnchor: MessageId?
)

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
    val senderProfiles: Map<String, SharedActivitySenderProfile> = emptyMap(),
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

private fun InboxFilter.keepsReadActivityRows(): Boolean =
    this == InboxFilter.All

sealed interface SharedActivityIntent {
    data object ClearSummary : SharedActivityIntent
    data object LoadInitial : SharedActivityIntent
    data object Refresh : SharedActivityIntent
    data object PullRefresh : SharedActivityIntent
    data object LoadMore : SharedActivityIntent
    data object MarkAllRead : SharedActivityIntent
    data class SetFilter(val filter: InboxFilter) : SharedActivityIntent
    data class MarkDone(val item: InboxItem) : SharedActivityIntent
    data class MarkRead(val item: InboxItem) : SharedActivityIntent
    data class MarkUnread(val item: InboxItem) : SharedActivityIntent
    data class ApplyReadReceiptPrediction(val prediction: ReadReceiptPrediction) : SharedActivityIntent
    data class ApplyReadReceiptFailure(val request: ReadReceiptRequest) : SharedActivityIntent
    data class PrewarmVisibleReplyThreads(
        val markers: Set<ActivityReplyThreadPrewarmMarker>
    ) : SharedActivityIntent
    data class PrewarmActivityTail(
        val orderedMarkers: List<ActivityReplyThreadPrewarmMarker>
    ) : SharedActivityIntent
    data class ApplyRealtimeEvent(
        val event: RealtimeEvent,
        val allowLocalPatch: Boolean = true
    ) : SharedActivityIntent
    data class ApplyRealtimeSummaryEvent(
        val event: RealtimeEvent,
        val activeConversationId: String?
    ) : SharedActivityIntent
}

private fun RealtimeEvent.refreshesActivity(): Boolean =
    when (this) {
        is MessageNew,
        is MessageUpdated,
        is DirectMessageCreated,
        is ThreadUpdated,
        is TaskCreated,
        is TaskUpdated,
        is TaskDeleted,
        is SyncResumeResponse -> true
        is RealtimeRefreshRequired ->
            scope == RealtimeRefreshScope.Home ||
                scope == RealtimeRefreshScope.ChannelMembers ||
                scope == RealtimeRefreshScope.ServerMembers ||
                scope == RealtimeRefreshScope.Agents ||
                scope == RealtimeRefreshScope.Machines ||
                scope == RealtimeRefreshScope.Settings
        else -> false
    }

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
    val tasksByMessageId =
        projection.tasks
            .flatMap { task ->
                listOfNotNull(task.messageId, task.parentMessageId)
                    .mapNotNull { messageId -> messageId.value.trim().takeIf { it.isNotBlank() } }
                    .distinct()
                    .map { messageId -> messageId to task }
            }
            .toMap()
    if (tasksByMessageId.isEmpty()) return this
    return map { item -> item.withActivityThreadTaskProjection(tasksByMessageId) }
}

private fun InboxItem.withActivityThreadTaskProjection(tasksByMessageId: Map<String, TaskSummary>): InboxItem {
    if (kind != InboxItemKind.Thread) return this
    if (taskNumber != null && taskStatus != null) return this
    val parentMessageKey = parentMessageId?.value?.trim()?.takeIf { it.isNotBlank() } ?: return this
    val task = tasksByMessageId[parentMessageKey] ?: return this
    return copy(
        taskNumber = taskNumber ?: task.taskNumber,
        taskStatus = taskStatus ?: task.status,
        taskClaimedByName = taskClaimedByName ?: task.assignee?.displayName?.takeIf { it.isNotBlank() }
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
                ?.takeIf { it > 0 && !isActiveActivityConversation(threadChannelId.value, activeConversationId, snapshot) }
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
