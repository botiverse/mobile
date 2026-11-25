package build.raft.app

import build.raft.app.ThreadRealtimeFolding.InFlightRealtimeFold
import build.raft.app.data.ConversationReadOnSight
import build.raft.app.data.raftConversationReadOnSight
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.appendSharedThreadSentMessageFromPage
import build.raft.app.data.RaftReadToggleNotice
import build.raft.app.data.MessageMutationSummary
import build.raft.app.data.InboxFetchReason
import build.raft.app.data.MviStaleToken
import build.raft.app.data.SharedForegroundLoadGate
import build.raft.app.data.SharedForegroundThreadKey
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadReplySummariesSnapshot
import build.raft.app.data.ThreadPendingSendDrainer
import build.raft.app.data.ThreadPendingSendJournal
import build.raft.app.data.ThreadPendingSendJournalDrainStore
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.withInboxFetchReason
import build.raft.app.data.map
import build.raft.app.data.realtime.RealtimeVisibleStreamGapRequest
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.RealtimeEvent
import build.raft.app.network.sync.CanonicalMessageV2ThreadJoinConfirmed
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.ThreadDurableEnterTicket
import build.raft.app.network.sync.ThreadDurableEnteredPage
import build.raft.app.network.sync.ThreadDurableRequestStamp
import build.raft.app.network.sync.ThreadRepliesAcceptedProjection
import build.raft.app.network.sync.ThreadRepliesAuthoritativeSnapshot
import build.raft.app.network.sync.ThreadRepliesPageIdentity
import build.raft.app.network.sync.ThreadRepliesScopeIdentity
import build.raft.app.network.sync.ThreadRepliesSyncHost
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

private const val SharedThreadFirstFrameDelayMillis = 16L

/**
 * Hands 4b6ccb0d: how long after a confirmed send the durable frame is checked for it. Long enough for the
 * realtime echo to reach a continuous window (then nothing is fetched), short enough to land before a page close.
 */

/**
 * Task #390: on a memory-miss entry the store waits at most this long for the entered-scope
 * baseline load ([SharedThreadStore.start] step 4) to finish before asking the durable
 * last-known reader for the first frame. When the load committed inside the window the disk
 * read returns the just-written snapshot and the first frame is the content itself instead of
 * a loading shell that is replaced a beat later. This is a wait bound only, not a requirement:
 * a timeout (or a load that committed nothing) falls back to today's behaviour — loading shell
 * or an older durable frame, then the refresh result. The load keeps running past the timeout;
 * nothing it wrote is rolled back.
 */
private const val ThreadInitialAccessLookupTimeoutMillis = 1_000L
/**
 * A reply thread whose parent write authority is still Resolving / Unknown this long after the
 * initial access pass reads the parent conversation itself (`GET /channels/:id`) instead of
 * waiting for a directory row that may never arrive (an agent's Lounge is in no list).
 */
private const val ReplyParentAuthorityFallbackDelayMillis = 4_000L

internal class SharedThreadStore(
    private val dataSource: SharedThreadDataSource,
    private val scope: CoroutineScope,
    private val diagnosticsLogger: ((String) -> Unit)? = null,
    initialPayload: RaftThreadScreenPayload = RaftThreadScreenPayload(),
    private val beforeFocusedOriginCommit: (suspend (RaftThreadScreenPayload) -> Unit)? = null,
    private val afterRefreshLoadingStateApplied: (suspend (RaftThreadScreenPayload) -> Unit)? = null,
    private val threadRepliesSyncHost: ThreadRepliesSyncHost = ThreadRepliesSyncHost(),
    // task #243: the initial-access lookup deadline as an injectable SIGNAL rather than a bare
    // duration. Production keeps the wall clock; a test can hand in a barrier it completes
    // itself, so the timeout branch is reached because the test said so - not because a second
    // of real time happened to elapse on a loaded runner. A shorter duration was deliberately
    // NOT used: that shrinks the flake window instead of removing the nondeterminism.
    // ⚠️ Invoked once per resolution attempt (launchInitialAccessResolution cancels and relaunches),
    // so an injected implementation must be safe to call repeatedly. A one-shot CompletableDeferred
    // parks the first attempt and then completes instantly for every attempt after it.
    private val initialAccessLookupDeadline: suspend () -> Unit = {
        delay(ThreadInitialAccessLookupTimeoutMillis)
    },
    // How long a reply thread's parent write authority may stay undetermined (Resolving /
    // Unknown) before the store reads the parent itself. Injectable for the same reason as
    // [initialAccessLookupDeadline]; invoked once per route open.
    private val replyParentAuthorityFallbackDelay: suspend () -> Unit = {
        delay(ReplyParentAuthorityFallbackDelayMillis)
    },
    // task #41: the clock of the visible-read coalesce window. Its deferred retry is a `delay`, so a
    // virtual-time test hands in the scheduler's time source to keep the window and the retry on
    // one clock; production keeps the monotonic wall clock.
    private val readCoalesceTimeSource: TimeSource = TimeSource.Monotonic,
    /**
     * Where this page says which conversation it reads on sight (shown, at its live tail, reads
     * not held): Activity counts no unread for a live message only there.
     */
    private val readOnSight: ConversationReadOnSight = raftConversationReadOnSight,
    /**
     * #proj-mobile:#265: grace window before revealing [ThreadLoadingState] while an initial
     * load has no renderable content yet. Injectable for tests; production uses
     * [ThreadInitialLoadingRevealDelayMs].
     */
    private val initialLoadingRevealDelayMillis: Long = ThreadInitialLoadingRevealDelayMs,
) {
    private val readStateProjection = ThreadReadStateProjection()
    // task #387: seed the first frame from the in-memory reply cache synchronously, so a warm
    // channel entry renders content immediately instead of flashing the loading shell for the
    // ~one frame the async Start intent needs to reach the same cache. Only the memory cache is
    // consulted here (all synchronous); the durable last-known frame stays with start(), which
    // re-runs the full seed chain right after and converges to the same payload.
    private val _state =
        MutableStateFlow(SharedThreadState(payload = initialPayload).withSynchronizedTimelineSlice())
    val state: StateFlow<SharedThreadState> = _state.asStateFlow()
    /** Channel name/description updates from the thread page; see [ThreadChannelProfileUpdater]. */
    private val channelProfileUpdater =
        ThreadChannelProfileUpdater(
            dataSource = dataSource,
            diagnosticsLogger = diagnosticsLogger,
            currentState = { _state.value },
            currentRouteStaleToken = { routeStaleToken },
            reduceState = ::reduceState,
            reducePayloadPatch = ::reducePayloadPatch,
        )

    fun payloadFlow(): Flow<RaftThreadScreenPayload> =
        state
            .map { threadState -> threadState.payload.withoutThreadTimelineContent() }
            .distinctUntilChanged()

    fun renderSliceFlow(): Flow<SharedThreadRenderSlice> =
        state
            .map { threadState -> threadState.toRenderSlice() }
            .distinctUntilChanged()

    fun timelineSliceFlow(): Flow<ThreadTimelineSlice> =
        state
            .map { threadState -> threadState.timelineSlice }
            .distinctUntilChanged()

    /** Tenny rule 2 leave signal (see [SharedThreadState.conversationAccessLost]). */
    fun conversationAccessLostFlow(): Flow<Boolean> =
        state
            .map { threadState -> threadState.conversationAccessLost }
            .distinctUntilChanged()

    fun replyMetadataFlow(): Flow<Map<String, ThreadReplyMetadata>> =
        state
            .map { threadState -> threadState.replyMetadataByParentId }
            .distinctUntilChanged()

    fun highlightedMentionsFlow(): Flow<Set<String>> =
        state
            .map { threadState -> threadState.highlightedMentions }
            .distinctUntilChanged()

    fun unreadThreadParentsFlow(): Flow<Map<String, ThreadUnreadThreadParent>> =
        state
            .map { threadState -> threadState.unreadThreadParents }
            .distinctUntilChanged()

    fun unreadThreadJumpFocusNonceFlow(): Flow<Int> =
        state
            .map { threadState -> threadState.unreadThreadJumpFocusNonce }
            .distinctUntilChanged()

    fun unreadThreadJumpLoadingFlow(): Flow<Boolean> =
        state
            .map { threadState -> threadState.unreadThreadJumpLoading }
            .distinctUntilChanged()

    // Event-driven drainer for durably journaled pending sends. Resends reuse the journaled
    // randomId so a request that committed server-side before its response was lost is deduped
    // by the server rather than duplicated; row fencing stays with the journal's exact
    // provenance/route partition.
    private val pendingSendDrainer: ThreadPendingSendDrainer? =
        dataSource.pendingSendJournalOrNull()?.let { journal ->
            ThreadPendingSendDrainer(
                drainStore = ThreadPendingSendJournalDrainStore(journal),
                scope = scope,
                provenanceResolver = { dataSource.currentReplyCacheProvenance() },
                routeResolver = { _state.value.payload.resolveThreadRouteIdentity() },
                send = { context ->
                    dataSource.sendMessage(
                        context.channelId,
                        context.body,
                        context.asTask,
                        context.attachmentIds,
                        context.randomId,
                        context.mentions,
                    )
                },
                onSent = { localId, page, provenance, route ->
                    accept(
                        SharedThreadIntent.ReplaceLocalSendWithSentPage(
                            localId,
                            page,
                            provenance,
                            route,
                        )
                    )
                },
                onRowFailed = { localId, provenance, route, errorCode, errorMessage ->
                    emitLocalSendFailureProjection(
                        messageId = localId,
                        errorCode = errorCode,
                        errorMessage = errorMessage,
                        expectedProvenance = provenance,
                        expectedRoute = route,
                    )
                },
                logger = { message -> diagnosticsLogger?.invoke(message) },
            )
        }

    internal fun triggerPendingSendDrain(reason: String) {
        pendingSendDrainer?.trigger(reason)
    }

    private var loadJob: Job? = null
    // #proj-mobile:#265: grace-delay timer before revealing the loading indicator on initial opens.
    private val loadingRevealLock = SynchronizedObject()
    private var loadingRevealJob: Job? = null
    private var loadingRevealGeneration: Long = 0L
    private var loadingRevealElapsedGeneration: Long = -1L
    // Foreground priority lease for this page's first-content load (see [SharedForegroundLoadGate]).
    private val foregroundLoadLeaseLock = SynchronizedObject()
    private var foregroundLoadLease: SharedForegroundLoadGate.Lease? = null
    // The authority-verified producer context captured by this store's own [start], exposed for
    // frame-1 seeding when `ensureStarted()` completes inline (fail-closed null if it suspended).
    private val initialProducerContextLock = SynchronizedObject()
    private var initialProducerContextRef: ReplyCacheProducerContext? = null

    internal fun currentInitialProducerContext(): ReplyCacheProducerContext? =
        synchronized(initialProducerContextLock) { initialProducerContextRef }
    /** Message storage Phase 2b: the durable write of the last refresh's own page (a context page write waits for it). */
    private var threadJoinCatchUpJob: Job? = null
    private var secondaryTimelineRefreshJob: Job? = null
    private var focusedOriginHydrationJob: Job? = null
    private var initialAccessResolutionJob: Job? = null
    // The bounded parent-authority fallback of the current route (see [ThreadAccessResolver.launchReplyParentAuthorityFallback]).
    private var replyParentAuthorityFallbackJob: Job? = null
    // A positive parent-directory hint that arrived before the access passes it needs had run; it
    // is applied once they have (see [applyParentDirectoryHint]) instead of being lost.
    private var deferredParentDirectoryHintJob: Job? = null
    private var cappedRefreshFollowUpJob: Job? = null
    private var homeContextHydrationJob: Job? = null
    private var taskConversationTitleJob: Job? = null
    private var taskConversationTitleJobKey: String? = null
    private var backgroundMembersHydrationJob: Job? = null
    // Task #19: a newer marker coalesced away by the window is re-tried when the window ends.
    private var deferredReadJob: Job? = null
    private val _readToggleNotices = MutableSharedFlow<RaftReadToggleNotice>(extraBufferCapacity = 4)

    /** One-shot Mark as Unread notices (Nothing to mark), shown by the page as a toast. */
    val readToggleNotices: SharedFlow<RaftReadToggleNotice> = _readToggleNotices.asSharedFlow()

    /**
     * Visible read and Mark as Unread for this page; see [ThreadVisibleRead]. [deferredReadJob] stays
     * here (clear() cancels it with the other jobs); [readOnSight] is still owned by this store (it
     * stays the owner key).
     */
    private val threadVisibleRead =
        ThreadVisibleRead(
            dataSource = dataSource,
            scope = scope,
            diagnosticsLogger = diagnosticsLogger,
            readCoalesceTimeSource = readCoalesceTimeSource,
            readOnSight = readOnSight,
            readOnSightOwner = this,
            readToggleNotices = _readToggleNotices,
            currentState = { _state.value },
            currentVisiblePayloadOwnerScopeId = { visiblePayloadOwnerScopeId },
            getDeferredReadJob = { deferredReadJob },
            setDeferredReadJob = { deferredReadJob = it },
        )
    private val _jumpFailures = MutableSharedFlow<ThreadJumpFailure>(extraBufferCapacity = 4)

    /**
     * One-shot: a jump to a message that had to load the messages around it ([jumpToMessage]) could
     * not load them. The page shows the error toast (with the failed request's trace ID) and offers
     * the jump again. A jump replaced by a newer one, or by leaving the conversation, is not a failure.
     */
    val jumpFailures: SharedFlow<ThreadJumpFailure> = _jumpFailures.asSharedFlow()

    /** [jumpToLatest] is loading the canonical tail: a second call while it does starts no second request. */
    private var latestJumpInFlight = false

    /** The target of the non-pill jump loading around it now ([jumpToMessageOutsidePill]): a repeat tap is ignored. */
    private var messageJumpInFlightTarget: String? = null
    private var lastRoutePayload: RaftThreadScreenPayload? = null
    /**
     * Hands 7e198cab: the local frame an anchored open did not show because it lacks the anchor
     * (see `thread_start_anchor_pending`). Only a failed main load shows it, as before; any
     * canonical answer replaces it. Reset per route.
     */
    private var anchorPendingStandIn: RaftThreadScreenPayload? = null
    private var visiblePayloadOwnerScopeId: String? = null
    private var firstContentReadyRouteKey: String? = null
    private var pendingAuxiliaryHydration: PendingAuxiliaryHydration? = null
    private var pendingSecondaryTimelineApply: PendingSecondaryTimelineApply? = null
    private var routeStaleToken = MviStaleToken.Initial
    private var routeStaleTokenValue = MviStaleToken.Initial.value
    private var nextThreadRepliesRebaselineGeneration: Long = 0L
    /**
     * The open conversation's access; see [ThreadAccessResolver]. It owns [initialAccessStateMutex]; start,
     * refresh and the superseded content passes still lock that same Mutex here. The access jobs
     * stay here: start cancels them (and [clear] the resolution one).
     */
    private val threadAccessResolver =
        ThreadAccessResolver(
            dataSource = dataSource,
            scope = scope,
            diagnosticsLogger = diagnosticsLogger,
            initialAccessLookupDeadline = initialAccessLookupDeadline,
            replyParentAuthorityFallbackDelay = replyParentAuthorityFallbackDelay,
            storeState = _state,
            currentRouteStaleToken = { routeStaleToken },
            currentLastRoutePayload = { lastRoutePayload },
            currentVisiblePayloadOwnerScopeId = { visiblePayloadOwnerScopeId },
            getInitialAccessResolutionJob = { initialAccessResolutionJob },
            setInitialAccessResolutionJob = { initialAccessResolutionJob = it },
            getReplyParentAuthorityFallbackJob = { replyParentAuthorityFallbackJob },
            setReplyParentAuthorityFallbackJob = { replyParentAuthorityFallbackJob = it },
            getDeferredParentDirectoryHintJob = { deferredParentDirectoryHintJob },
            setDeferredParentDirectoryHintJob = { deferredParentDirectoryHintJob = it },
            reduceState = ::reduceState,
            staleTokenStillCurrent = ::staleTokenStillCurrent,
            routeStillCurrent = ::routeStillCurrent,
            launchBackgroundHomeContextHydration = ::launchBackgroundHomeContextHydration,
        )
    private val initialAccessStateMutex: Mutex
        get() = threadAccessResolver.initialAccessStateMutex
    // The catch-up launched off the realtime authority lock ([ThreadRealtimeGapRecovery.launchRealtimeGapRecovery]); cancelled by [clear].
    private var realtimeGapRecoveryJob: Job? = null
    /** Test injection point: sees every catch-up job [ThreadRealtimeGapRecovery.launchRealtimeGapRecovery] starts, in launch order. */
    internal var onRealtimeGapRecoveryLaunchedForTest: ((Job) -> Unit)? = null
    private var loadOlderState: LoadOlderState? = null
    /**
     * Older pages and inline timeline-gap fills; see [ThreadPaging]. [loadOlderState] stays here: start,
     * refresh and clear reset it. Newer paging is in [ThreadRealtimeGapRecovery]: it shares
     * [realtimeGapRecoveryMutex] and [loadNewerState] with realtime gap recovery.
     */
    private val threadPaging =
        ThreadPaging(
            dataSource = dataSource,
            scope = scope,
            diagnosticsLogger = diagnosticsLogger,
            currentState = { _state.value },
            currentRouteStaleToken = { routeStaleToken },
            currentVisiblePayloadOwnerScopeId = { visiblePayloadOwnerScopeId },
            getLoadOlderState = { loadOlderState },
            setLoadOlderState = { loadOlderState = it },
            reducePayloadPatch = ::reducePayloadPatch,
            staleTokenStillCurrent = ::staleTokenStillCurrent,
            routeStillCurrent = ::routeStillCurrent,
            launchSecondaryTimelineRefresh = ::launchSecondaryTimelineRefresh,
        )
    /**
     * Newer pages and realtime gap recovery (resume / heartbeat / join-ack catch-up); see
     * [ThreadRealtimeGapRecovery]. It owns [realtimeGapRecoveryMutex] and the state it guards
     * ([realtimeGapRecoveryState], [loadNewerState]). [ThreadRealtimeFolding] uses them directly; the
     * join-ack check and refresh still read them here, through the accessors below, under the same Mutex.
     * [realtimeGapRecoveryJob] stays here: [clear] cancels it.
     */
    private val threadRealtimeGapRecovery =
        ThreadRealtimeGapRecovery(
            dataSource = dataSource,
            scope = scope,
            diagnosticsLogger = diagnosticsLogger,
            threadPaging = threadPaging,
            currentState = { _state.value },
            currentRouteStaleToken = { routeStaleToken },
            currentVisiblePayloadOwnerScopeId = { visiblePayloadOwnerScopeId },
            currentViewportFollowsLatest = { viewportFollowsLatest },
            getRealtimeGapRecoveryJob = { realtimeGapRecoveryJob },
            setRealtimeGapRecoveryJob = { realtimeGapRecoveryJob = it },
            currentOnRealtimeGapRecoveryLaunchedForTest = { onRealtimeGapRecoveryLaunchedForTest },
            reducePayloadPatch = ::reducePayloadPatch,
            staleTokenStillCurrent = ::staleTokenStillCurrent,
            routeStillCurrent = ::routeStillCurrent,
            launchSecondaryTimelineRefresh = ::launchSecondaryTimelineRefresh,
            markConversationAccessLost = ::markConversationAccessLost,
        )
    private val realtimeGapRecoveryMutex: Mutex
        get() = threadRealtimeGapRecovery.realtimeGapRecoveryMutex
    private val realtimeGapRecoveryState: RealtimeGapRecoveryState?
        get() = threadRealtimeGapRecovery.realtimeGapRecoveryState
    private val loadNewerState: LoadNewerState?
        get() = threadRealtimeGapRecovery.loadNewerState
    private val gapCatchUpRetryableFailures: Long
        get() = threadRealtimeGapRecovery.gapCatchUpRetryableFailures
    /** See [setViewportFollowsLatest]. */
    private var viewportFollowsLatest: Boolean = true
    /**
     * Realtime folding; see [ThreadRealtimeFolding]. It owns the in-flight folds with their lock and
     * [pendingReactionRealtimeGuards] (armed and cleared here).
     */
    private val threadRealtimeFolding =
        ThreadRealtimeFolding(
            dataSource = dataSource,
            diagnosticsLogger = diagnosticsLogger,
            threadRepliesSyncHost = threadRepliesSyncHost,
            readStateProjection = readStateProjection,
            threadVisibleRead = threadVisibleRead,
            threadRealtimeGapRecovery = threadRealtimeGapRecovery,
            currentState = { _state.value },
            currentRouteStaleToken = { routeStaleToken },
            currentVisiblePayloadOwnerScopeId = { visiblePayloadOwnerScopeId },
            reducePayloadPatch = ::reducePayloadPatch,
            routeStillCurrent = ::routeStillCurrent,
            captureThreadRepliesPageIdentity = { payload -> captureThreadRepliesPageIdentity(payload) },
            dispatch = ::dispatch,
        )
    private val pendingReactionRealtimeGuards: MutableMap<String, PendingReactionRealtimeGuard>
        get() = threadRealtimeFolding.pendingReactionRealtimeGuards

    private fun beginInFlightRealtimeFold(staleToken: MviStaleToken): InFlightRealtimeFold =
        threadRealtimeFolding.beginInFlightRealtimeFold(staleToken)

    private fun endInFlightRealtimeFold(fold: InFlightRealtimeFold) = threadRealtimeFolding.endInFlightRealtimeFold(fold)

    private fun InFlightRealtimeFold.holdsAll(messageIds: Set<String>): Boolean =
        with(threadRealtimeFolding) { this@holdsAll.holdsAll(messageIds) }

    private fun RaftThreadScreenPayload.withInFlightRealtimeFolded(buffer: InFlightRealtimeFold): RaftThreadScreenPayload =
        with(threadRealtimeFolding) { this@withInFlightRealtimeFolded.withInFlightRealtimeFolded(buffer) }

    internal data class LoadOlderState(
        val id: Long,
        val routeIdentity: ThreadRouteIdentity,
        val staleToken: MviStaleToken,
    )

    internal data class RealtimeGapRecoveryState(
        val id: Long,
        val routeIdentity: ThreadRouteIdentity,
        val staleToken: MviStaleToken,
        var targetSeq: Long,
        var targetVersion: Long,
        /**
         * Set by a foreground resume (ad1df6b7): re-read the loaded parents' reply summaries after the
         * recovery even when it merged nothing. While the app was in the background the socket was down,
         * so replies posted meanwhile reached neither `ThreadUpdated` nor the top-level catch-up page.
         */
        var backfillAfterRecovery: Boolean = false,
    )

    internal data class LoadNewerState(
        val id: Long,
        val routeIdentity: ThreadRouteIdentity,
        val staleToken: MviStaleToken,
        var pendingTargetSeq: Long? = null,
    )

    private data class PendingAuxiliaryHydration(
        val routePayload: RaftThreadScreenPayload,
        val staleToken: MviStaleToken,
        val launchSecondaryTimelineRefresh: Boolean,
        val launchMembersHydration: Boolean
    )

    private data class PendingSecondaryTimelineApply(
        val routePayload: RaftThreadScreenPayload,
        val staleToken: MviStaleToken,
        val apply: suspend () -> Unit,
    )

    fun dispatch(intent: SharedThreadIntent) {
        when (intent) {
            is SharedThreadIntent.Start,
            SharedThreadIntent.Refresh -> {
                loadJob?.cancel()
                if (intent is SharedThreadIntent.Start) {
                    cancelLoadingRevealTimer()
                    _state.update { current ->
                        if (current.loadingIndicatorRevealed) {
                            current.copy(loadingIndicatorRevealed = false).withSynchronizedTimelineSlice()
                        } else {
                            current
                        }
                    }
                    initialAccessResolutionJob?.cancel()
                    initialAccessResolutionJob = null
                    replyParentAuthorityFallbackJob?.cancel()
                    replyParentAuthorityFallbackJob = null
                    deferredParentDirectoryHintJob?.cancel()
                    deferredParentDirectoryHintJob = null
                    cappedRefreshFollowUpJob?.cancel()
                    cappedRefreshFollowUpJob = null
                }
                secondaryTimelineRefreshJob?.cancel()
                secondaryTimelineRefreshJob = null
                pendingSecondaryTimelineApply = null
                focusedOriginHydrationJob?.cancel()
                focusedOriginHydrationJob = null
                homeContextHydrationJob?.cancel()
                homeContextHydrationJob = null
                backgroundMembersHydrationJob?.cancel()
                backgroundMembersHydrationJob = null
                pendingReactionRealtimeGuards.clear()
                loadJob =
                    scope.launch {
                        accept(intent)
                    }
            }
            SharedThreadIntent.RetryAccessResolution,
            is SharedThreadIntent.ApplyParentDirectoryHint -> {
                scope.launch { accept(intent) }
            }
            is SharedThreadIntent.AppendSentPage,
            is SharedThreadIntent.ApplyRouteIntent,
            is SharedThreadIntent.ApplyLocalSendFailure,
            is SharedThreadIntent.CancelLocalSend,
            is SharedThreadIntent.DismissLocalFailedSend,
            is SharedThreadIntent.ReplaceLocalSendWithSentPage,
            is SharedThreadIntent.ApplyMessageReactionSelection,
            is SharedThreadIntent.ApplyMessageReactions,
            is SharedThreadIntent.ApplyReplyThreadRead,
            is SharedThreadIntent.ApplyMessageThreadFollowed,
            is SharedThreadIntent.ApplyMessageMutation,
            is SharedThreadIntent.ApplyActionCard,
            is SharedThreadIntent.ApplyMessageSaved,
            is SharedThreadIntent.ApplyChannelJoined,
            is SharedThreadIntent.ApplyChannelLeft,
            is SharedThreadIntent.UpdateChannelProfile,
            SharedThreadIntent.ClearChannelProfileUpdateStatus,
            is SharedThreadIntent.ApplyChannelProfile,
            is SharedThreadIntent.SelectTab,
            is SharedThreadIntent.FocusMessage,
            is SharedThreadIntent.FocusHistoryStart,
            is SharedThreadIntent.ResolveUnreadThreadParents,
            is SharedThreadIntent.JumpToMessage,
            is SharedThreadIntent.ApplyMembersPatch,
            is SharedThreadIntent.SetAddMemberStatus,
            is SharedThreadIntent.SetRemoveMemberStatus,
            is SharedThreadIntent.SetPagingAvailability ->
                scope.launch { accept(intent) }
            is SharedThreadIntent.ApplyRealtimeEvent -> {
                // An accepted realtime frame (even one later dropped as stale) is an
                // authoritative online witness: let the pending-send drainer re-evaluate
                // journaled rows. Throttled inside the drainer.
                pendingSendDrainer?.onAuthoritativeOnline()
                scope.launch {
                    applyRealtimeEvent(
                        event = intent.event,
                        activeReplyParentMessageId = intent.activeReplyParentMessageId
                    )
                }
            }
            is SharedThreadIntent.MarkVisibleRead -> {
                // Opened here, not in the launched block: a VisibleReadHidden dispatched right after
                // must not be undone by this read starting later.
                threadVisibleRead.showVisibleRead()
                scope.launch { markVisibleRead(intent.visibleTailIndex) }
            }
            SharedThreadIntent.VisibleReadHidden -> hideVisibleRead()
            is SharedThreadIntent.MarkThreadUnread -> {
                scope.launch { markThreadUnread(intent.threadChannelId) }
            }
            SharedThreadIntent.MarkConversationUnread -> {
                scope.launch { markConversationUnread() }
            }
            SharedThreadIntent.FirstContentReady -> {
                scope.launch { markFirstContentReady() }
            }
            SharedThreadIntent.LoadMembers -> {
                scope.launch { loadMembers() }
            }
            SharedThreadIntent.LoadOlder -> {
                scope.launch { loadOlder() }
            }
            SharedThreadIntent.LoadNewer -> {
                scope.launch { loadNewer() }
            }
            is SharedThreadIntent.FillTimelineGap -> {
                scope.launch { fillTimelineGap(intent.afterSeq, intent.beforeSeq) }
            }
            SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq -> {
                scope.launch { refreshLatestAfterVisibleMaxSeq() }
            }
            is SharedThreadIntent.RecoverRealtimeGap -> {
                scope.launch { recoverRealtimeGap(intent.serverSeq) }
            }
        }
    }

    suspend fun accept(intent: SharedThreadIntent) {
        when (intent) {
            is SharedThreadIntent.Start -> start(intent.payload)
            is SharedThreadIntent.ApplyRouteIntent -> applyRouteIntent(intent.payload)
            SharedThreadIntent.Refresh -> {
                markLoadingRevealElapsedImmediately()
                refresh(
                    lastRoutePayload ?: _state.value.payload,
                    advanceRouteStaleToken(),
                    explicitRetry = true
                )
            }
            SharedThreadIntent.RetryAccessResolution -> retryAccessResolution()
            is SharedThreadIntent.AppendSentPage -> appendSentPage(intent.page)
            is SharedThreadIntent.ApplyLocalSendFailure ->
                applyLocalSendFailure(
                    intent.messageId,
                    intent.errorCode,
                    intent.errorMessage,
                    intent.expectedProvenance,
                    intent.expectedRoute,
                    intent.randomId,
                )
            is SharedThreadIntent.CancelLocalSend -> cancelLocalSend(intent.messageId)
            is SharedThreadIntent.DismissLocalFailedSend -> dismissLocalFailedSend(intent.messageId)
            is SharedThreadIntent.ReplaceLocalSendWithSentPage ->
                replaceLocalSendWithSentPage(
                    intent.messageId,
                    intent.page,
                    intent.expectedProvenance,
                    intent.expectedRoute
                )
            is SharedThreadIntent.ApplyMessageReactionSelection ->
                applyMessageReactionSelection(intent.messageId, intent.emoji, intent.selected)
            is SharedThreadIntent.ApplyMessageReactions ->
                applyMessageReactions(intent.messageId, intent.reactions)
            is SharedThreadIntent.ApplyReplyThreadRead ->
                applyReplyThreadRead(intent.parentMessageId, intent.threadChannelId)
            is SharedThreadIntent.ApplyMessageThreadFollowed ->
                applyMessageThreadFollowed(intent.parentMessageId, intent.threadChannelId, intent.followed, intent.replyCount)
            is SharedThreadIntent.ApplyMessageMutation -> applyMessageMutation(intent.summary)
            is SharedThreadIntent.ApplyActionCard -> applyActionCard(intent.messageId, intent.card)
            is SharedThreadIntent.ApplyMessageSaved -> applyMessageSaved(intent.messageId, intent.saved)
            is SharedThreadIntent.ApplyChannelJoined -> applyChannelJoined(intent.channelId)
            is SharedThreadIntent.ApplyChannelLeft -> applyChannelLeft(intent.channelId)
            is SharedThreadIntent.ApplyParentDirectoryHint -> applyParentDirectoryHint(intent)
            is SharedThreadIntent.UpdateChannelProfile -> updateChannelProfile(intent)
            SharedThreadIntent.ClearChannelProfileUpdateStatus -> clearChannelProfileUpdateStatus()
            is SharedThreadIntent.ApplyChannelProfile -> applyChannelProfile(intent)
            is SharedThreadIntent.SelectTab -> selectTab(intent.tab)
            is SharedThreadIntent.FocusMessage -> focusMessage(intent.messageId, intent.switchToTab)
            is SharedThreadIntent.FocusHistoryStart ->
                reducePayloadPatch {
                    it.copy(
                        selectedTab = intent.switchToTab?.takeIf { tab -> tab.isNotBlank() } ?: it.selectedTab,
                        historyStartFocusRequest = it.historyStartFocusRequest + 1
                    )
                }
            is SharedThreadIntent.ResolveUnreadThreadParents -> unreadThreadParentLookup.resolve(intent.threadChannelIds)
            is SharedThreadIntent.JumpToMessage -> {
                jumpToMessage(intent.messageId, intent.switchToTab, intent.unreadThreadPill)
                intent.prewarmThreadChannelId?.let { threadId -> prewarmUnreadThread(threadId, intent.messageId) }
            }
            is SharedThreadIntent.ApplyMembersPatch -> applyMembersPatch(intent)
            is SharedThreadIntent.SetAddMemberStatus -> setAddMemberStatus(intent.status, intent.errorCode)
            is SharedThreadIntent.SetRemoveMemberStatus ->
                setRemoveMemberStatus(intent.status, intent.removingMemberId, intent.errorCode)
            is SharedThreadIntent.SetPagingAvailability ->
                setPagingAvailability(intent.canLoadMoreBefore, intent.canLoadMoreAfter)
            is SharedThreadIntent.ApplyRealtimeEvent ->
                applyRealtimeEvent(
                    event = intent.event,
                    activeReplyParentMessageId = intent.activeReplyParentMessageId
                )
            is SharedThreadIntent.MarkVisibleRead -> {
                threadVisibleRead.showVisibleRead()
                markVisibleRead(intent.visibleTailIndex)
            }
            SharedThreadIntent.VisibleReadHidden -> hideVisibleRead()
            is SharedThreadIntent.MarkThreadUnread -> markThreadUnread(intent.threadChannelId)
            SharedThreadIntent.MarkConversationUnread -> markConversationUnread()
            SharedThreadIntent.FirstContentReady -> markFirstContentReady()
            SharedThreadIntent.LoadMembers -> loadMembers()
            SharedThreadIntent.LoadOlder -> loadOlder()
            SharedThreadIntent.LoadNewer -> loadNewer()
            is SharedThreadIntent.FillTimelineGap -> fillTimelineGap(intent.afterSeq, intent.beforeSeq)
            SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq -> refreshLatestAfterVisibleMaxSeq()
            is SharedThreadIntent.RecoverRealtimeGap -> recoverRealtimeGap(intent.serverSeq)
        }
    }

    fun clear() {
        readOnSight.hide(this)
        cancelLoadingRevealTimer()
        loadJob?.cancel()
        loadJob = null
        realtimeGapRecoveryJob?.cancel()
        realtimeGapRecoveryJob = null
        deferredReadJob?.cancel()
        deferredReadJob = null
        threadJoinCatchUpJob?.cancel()
        threadJoinCatchUpJob = null
        secondaryTimelineRefreshJob?.cancel()
        secondaryTimelineRefreshJob = null
        focusedOriginHydrationJob?.cancel()
        focusedOriginHydrationJob = null
        initialAccessResolutionJob?.cancel()
        initialAccessResolutionJob = null
        cappedRefreshFollowUpJob?.cancel()
        cappedRefreshFollowUpJob = null
        homeContextHydrationJob?.cancel()
        homeContextHydrationJob = null
        taskConversationTitleJob?.cancel()
        taskConversationTitleJob = null
        taskConversationTitleJobKey = null
        backgroundMembersHydrationJob?.cancel()
        backgroundMembersHydrationJob = null
        lastRoutePayload = null
        firstContentReadyRouteKey = null
        pendingAuxiliaryHydration = null
        pendingSecondaryTimelineApply = null
        releaseForegroundLoadLease(expected = null, reason = "cleared")
        loadOlderState = null
        advanceRouteStaleToken()
        val currentState = _state.value
        if (currentState.payload.newerMessagesStatus == "loading" || currentState.payload.olderMessagesStatus == "loading") {
            val patchedPayload =
                currentState.payload.copy(
                    olderMessagesStatus =
                        if (currentState.payload.olderMessagesStatus == "loading") "idle" else currentState.payload.olderMessagesStatus,
                    newerMessagesStatus =
                        if (currentState.payload.newerMessagesStatus == "loading") "idle" else currentState.payload.newerMessagesStatus,
                )
            val emitted =
                reduceStateInternal(
                    currentState.copy(payload = patchedPayload, loadingIndicatorRevealed = false),
                    manageLoadingTimer = false,
                )
            if (emitted) {
                dataSource.cachePayload(
                    _state.value.payload,
                    ownerScopeId = visiblePayloadOwnerScopeId.orEmpty()
                )
            }
        }
        cancelLoadingRevealTimer()
        visiblePayloadOwnerScopeId = null
        threadVisibleRead.resetReadMarkers()
        synchronized(initialProducerContextLock) { initialProducerContextRef = null }
    }

    private fun isLoadingRevealElapsedForCurrentGeneration(): Boolean =
        synchronized(loadingRevealLock) {
            loadingRevealElapsedGeneration == loadingRevealGeneration && loadingRevealGeneration > 0L
        }

    private fun cancelLoadingRevealTimer() {
        val jobToCancel =
            synchronized(loadingRevealLock) {
                loadingRevealGeneration += 1L
                loadingRevealElapsedGeneration = -1L
                loadingRevealJob.also { loadingRevealJob = null }
            }
        jobToCancel?.cancel()
    }

    private fun markLoadingRevealElapsedImmediately() {
        val jobToCancel =
            synchronized(loadingRevealLock) {
                val previous = loadingRevealJob
                loadingRevealJob = null
                loadingRevealGeneration += 1L
                loadingRevealElapsedGeneration = loadingRevealGeneration
                previous
            }
        jobToCancel?.cancel()
    }

    private fun launchLoadingRevealTimerFor(generation: Long) {
        val scheduled =
            scope.launch {
                delay(initialLoadingRevealDelayMillis)
                val stillCurrent =
                    synchronized(loadingRevealLock) {
                        if (loadingRevealGeneration != generation) {
                            false
                        } else {
                            loadingRevealElapsedGeneration = generation
                            loadingRevealJob = null
                            true
                        }
                    }
                if (!stillCurrent) return@launch
                _state.update { latest ->
                    val currentElapsed =
                        synchronized(loadingRevealLock) {
                            loadingRevealGeneration == generation && loadingRevealElapsedGeneration == generation
                        }
                    if (currentElapsed && latest.awaitsInitialLoadingIndicator() && !latest.loadingIndicatorRevealed) {
                        latest.copy(loadingIndicatorRevealed = true).withSynchronizedTimelineSlice()
                    } else {
                        latest
                    }
                }
            }
        val keep =
            synchronized(loadingRevealLock) {
                if (loadingRevealGeneration == generation && scheduled.isActive) {
                    loadingRevealJob = scheduled
                    true
                } else {
                    false
                }
            }
        if (!keep) scheduled.cancel()
    }

    private fun armLoadingRevealTimer() {
        val (jobToCancel, generation) =
            synchronized(loadingRevealLock) {
                val previous = loadingRevealJob
                loadingRevealJob = null
                loadingRevealGeneration += 1L
                loadingRevealElapsedGeneration = -1L
                previous to loadingRevealGeneration
            }
        jobToCancel?.cancel()
        _state.update { current ->
            if (current.loadingIndicatorRevealed) {
                current.copy(loadingIndicatorRevealed = false).withSynchronizedTimelineSlice()
            } else {
                current
            }
        }
        if (initialLoadingRevealDelayMillis <= 0L) {
            synchronized(loadingRevealLock) {
                if (loadingRevealGeneration == generation) {
                    loadingRevealElapsedGeneration = generation
                }
            }
            return
        }
        launchLoadingRevealTimerFor(generation)
    }

    private fun ensureLoadingRevealTimerArmed() {
        if (initialLoadingRevealDelayMillis <= 0L) return
        val generationToSchedule =
            synchronized(loadingRevealLock) {
                val active = loadingRevealJob?.isActive == true
                val elapsed = loadingRevealElapsedGeneration == loadingRevealGeneration && loadingRevealGeneration > 0L
                if (active || elapsed) {
                    null
                } else {
                    loadingRevealGeneration += 1L
                    loadingRevealElapsedGeneration = -1L
                    loadingRevealGeneration
                }
            } ?: return
        launchLoadingRevealTimerFor(generationToSchedule)
    }

    /**
     * Holds a foreground-load lease from Start until this page's first content is ready, the
     * start/refresh finishes (content, failure, or early return), the load is cancelled, or the
     * page is cleared. Background Activity prewarm does not start new loads while it is held.
     */
    private suspend fun start(initialPayload: RaftThreadScreenPayload) {
        armLoadingRevealTimer()
        val producerContext = dataSource.captureReplyCacheProducerContext()
        synchronized(initialProducerContextLock) { initialProducerContextRef = producerContext }
        val lease = acquireForegroundLoadLease(initialPayload, producerContext?.session?.currentServerId)
        try {
            startRoute(initialPayload, producerContext)
        } finally {
            releaseForegroundLoadLease(expected = lease, reason = "start_finished")
        }
    }

    private fun acquireForegroundLoadLease(
        initialPayload: RaftThreadScreenPayload,
        serverId: String?,
    ): SharedForegroundLoadGate.Lease {
        val threadId =
            initialPayload.sourceThreadId?.takeIf { it.isNotBlank() }
                ?: initialPayload.streamChannelId?.takeIf { it.isNotBlank() }
        val threadKey =
            serverId?.takeIf { it.isNotBlank() }?.let { server ->
                SharedForegroundThreadKey(
                    serverId = server,
                    threadId = threadId,
                    parentChannelId = initialPayload.parentChannelId?.takeIf { it.isNotBlank() },
                    parentMessageId = initialPayload.parentMessageId?.takeIf { it.isNotBlank() },
                )
            }
        val gate = dataSource.foregroundLoadGate
        val lease = gate.acquire(threadKey)
        val previous =
            synchronized(foregroundLoadLeaseLock) {
                val replaced = foregroundLoadLease
                foregroundLoadLease = lease
                replaced
            }
        if (previous?.release() == true) {
            diagnosticsLogger?.invoke(
                "thread_foreground_load_released reason=restarted heldMs=${previous.heldMillis()}"
            )
        }
        diagnosticsLogger?.invoke(
            "thread_foreground_load_acquired threadId=${threadId.orEmpty()} " +
                "activeForegroundLoads=${gate.activeCount()}"
        )
        return lease
    }

    /** Idempotent: [expected] null releases whatever lease this page holds. */
    private fun releaseForegroundLoadLease(expected: SharedForegroundLoadGate.Lease?, reason: String) {
        val lease =
            synchronized(foregroundLoadLeaseLock) {
                val current = foregroundLoadLease
                if (expected == null || current === expected) foregroundLoadLease = null
                expected ?: current
            } ?: return
        if (lease.release()) {
            diagnosticsLogger?.invoke(
                "thread_foreground_load_released reason=$reason heldMs=${lease.heldMillis()}"
            )
        }
    }

    private suspend fun startRoute(
        initialPayload: RaftThreadScreenPayload,
        producerContext: ReplyCacheProducerContext?,
    ) {
        focusedOriginHydrationJob?.cancel()
        focusedOriginHydrationJob = null
        val session = producerContext?.session
        val payloadOwnerScopeId = producerContext?.provenance?.threadPayloadOwnerScopeId
        val routeCandidatePayload = initialPayload.withoutRouteCandidateContent()
        val routeContextPayload =
            (producerContext
                ?.let { context ->
                    dataSource.withCachedReplyParentCandidate(routeCandidatePayload, context)
                }
                ?: routeCandidatePayload)
                .withoutTransientLocalSendingMessages()
        val rememberedAccessFailure =
            producerContext?.let { context ->
                dataSource.rememberedReplyThreadAccessFailure(routeContextPayload, context)
            }
        loadOlderState = null
        val staleToken = advanceRouteStaleToken()
        lastRoutePayload = routeContextPayload
        firstContentReadyRouteKey = null
        pendingAuxiliaryHydration = null
        pendingSecondaryTimelineApply = null
        val routeIdentity = routeContextPayload.resolveThreadRouteIdentity()
        val threadId = routeIdentity.timelineKey
        anchorPendingStandIn = null
        dataSource.onReplyCacheFirstFrameAdmissionForTest?.invoke()
        // Hands 7e198cab: a realtime write of this conversation was refused (`poisoned`), so later messages
        // may be missing from every local copy. Show none of them as complete; the refresh below decides.
        val staleConversationCause =
            threadId?.let { candidateThreadId ->
                producerContext?.let { context ->
                    dataSource.staleConversationCauseIfCurrent(candidateThreadId, context.provenance)
                }
            }
        if (staleConversationCause != null) {
            diagnosticsLogger?.invoke(
                "thread_start_stale_refetch threadId=${threadId.orEmpty()} cause=$staleConversationCause"
            )
        }
        val cached =
            threadId
                ?.takeIf { staleConversationCause == null }
                ?.let { candidateThreadId ->
                    producerContext?.let { context ->
                        dataSource.cachedPayloadIfCurrent(candidateThreadId, context.provenance)
                    }
                }
                ?.takeIf { routeIdentity.canRenderTimelineCandidates }
                ?.takeIf { it.isUsableCachedPayloadForRoute(routeContextPayload) }
                ?.withRouteContext(routeContextPayload)
                ?.withStoreResolvedReplyOriginFrom(routeContextPayload)
                ?.withoutInFlightPagingLoading()
        // A thread payload cache survives independently from the process-local member profile
        // projection. Re-seed an accepted cache hit before publishing the first frame so a server
        // switch or process restore cannot render complete history with placeholder-only avatars.
        cached?.let { accepted ->
            dataSource.seedThreadAuthorProfiles(
                serverId = session?.currentServerId,
                payload = accepted,
                source = "visible_cache"
            )
        }
        val current =
            if (
                routeIdentity.canRenderTimelineCandidates &&
                    payloadOwnerScopeId != null &&
                    visiblePayloadOwnerScopeId == payloadOwnerScopeId
            ) {
                _state.value.payload.takeIf {
                    it.isUsableCachedPayloadForRoute(routeContextPayload) && it.hasRenderableThreadContent()
                }?.withRouteContext(routeContextPayload)
                    ?.withStoreResolvedReplyOriginFrom(routeContextPayload)
                    ?.withoutInFlightPagingLoading()
            } else {
                null
            }
        // Hands 4b6ccb0d: an Activity summary (or any other content fact) arriving after the last commit
        // advances the memory entry's generation, so `cached` above misses even though the entry may hold
        // the newest content this process has seen (artin's own confirmed send). Keep it as a candidate
        // for the first frame; it is weighed against the durable frame below and the refresh still runs.
        val supersededMemory =
            if (cached == null && current == null && staleConversationCause == null) {
                threadId
                    ?.let { candidateThreadId ->
                        producerContext?.let { context ->
                            dataSource.supersededCachedPayloadIfCurrent(candidateThreadId, context.provenance)
                        }
                    }
                    ?.takeIf { routeIdentity.canRenderTimelineCandidates }
                    ?.takeIf { it.isUsableCachedPayloadForRoute(routeContextPayload) && it.hasRenderableThreadContent() }
                    ?.withRouteContext(routeContextPayload)
                    ?.withStoreResolvedReplyOriginFrom(routeContextPayload)
                    ?.withoutInFlightPagingLoading()
            } else {
                null
            }
        // artin 10-23: the row the user tapped in Activity already holds this conversation's latest
        // message (a thread row its latest replies). Read it whenever there is no live in-memory
        // candidate or an anchored open's hot memory cache lacks the focused message (#265 Phase 2),
        // so tapping an Activity row never flashes a blank loading screen while memory already holds
        // the conversation and Activity already holds the anchor.
        val activitySeed =
            if (current == null && producerContext != null && (cached == null || cached.lacksFocusedAnchor())) {
                dataSource.activityOpenSeedMessagesIfCurrent(routeIdentity, producerContext)
            } else {
                emptyList()
            }
        // Offline cache write entry (task #1124; message storage Phase 2b): the entry's own page request (the
        // refresh below) is written through to the durable partition, so there is no separate enter load to start
        // or wait for here. Task #14c: subscribed before that request can commit, so a late ack is not missed.
        if (producerContext != null) {
            watchThreadJoinConfirmations(routeIdentity, producerContext, routeContextPayload.durableReplyParentOrNull())
        }
        // #proj-mobile:#265: publish a verified in-memory frame synchronously on Frame 1 before
        // suspending on SQLite whenever either:
        // - Path 1: a superseded memory entry satisfies any focused anchor (alone or with Activity seed);
        // - Path 2: an anchored open's hot memory cache lacks the focused message, yet Activity already
        //   holds that newer message.
        // SQLite and network refresh still run right after and upgrade seamlessly if disk or server
        // holds newer messages or a richer anchored island.
        val immediateCachedAnchorSeedBodies =
            if (cached != null && cached.lacksFocusedAnchor() && activitySeed.isNotEmpty()) {
                activitySeed.withLocalFullMessages(cached.messages)
            } else {
                emptyList()
            }
        val immediateCachedAnchorCandidate =
            if (
                cached != null &&
                    current == null &&
                    cached.lacksFocusedAnchor() &&
                    immediateCachedAnchorSeedBodies.isNotEmpty() &&
                    rememberedAccessFailure == null &&
                    producerContext != null
            ) {
                cached
                    .withActivityOpenSeed(immediateCachedAnchorSeedBodies)
                    .takeUnless { it.lacksFocusedAnchor() }
                    ?.copy(
                        backgroundRefreshing = false,
                        errorCode = null,
                        errorServerMessage = null,
                        errorTraceId = null,
                    )
            } else {
                null
            }
        val immediateSupersededSeedBodies =
            if (supersededMemory != null) {
                activitySeed.withLocalFullMessages(supersededMemory.messages)
            } else {
                emptyList()
            }
        val immediateSupersededCandidate =
            if (
                cached == null &&
                    current == null &&
                    supersededMemory != null &&
                    rememberedAccessFailure == null &&
                    producerContext != null
            ) {
                supersededMemory
                    .withActivityOpenSeed(immediateSupersededSeedBodies)
                    .takeUnless { it.lacksFocusedAnchor() }
                    ?.copy(
                        backgroundRefreshing = false,
                        errorCode = null,
                        errorServerMessage = null,
                        errorTraceId = null,
                    )
            } else {
                null
            }
        val immediateFirstFrameFromCachedAnchor = immediateCachedAnchorCandidate != null
        val immediateSourceMemory = if (immediateFirstFrameFromCachedAnchor) cached else supersededMemory
        val immediateFirstFrameCandidate = immediateCachedAnchorCandidate ?: immediateSupersededCandidate
        val immediateFrameAddedActivitySeed =
            immediateFirstFrameCandidate != null &&
                immediateSourceMemory != null &&
                immediateFirstFrameCandidate.messages.size > immediateSourceMemory.messages.size
        var publishedImmediateFrame: RaftThreadScreenPayload? = null
        var immediateFrameKeptAccess = false
        if (immediateFirstFrameCandidate != null && producerContext != null) {
            if (dataSource.isCurrentReplyCacheProvenance(producerContext.provenance)) {
                dataSource.seedThreadAuthorProfiles(
                    serverId = session?.currentServerId,
                    payload = immediateFirstFrameCandidate,
                    source = if (immediateFirstFrameFromCachedAnchor) "cached_anchor_seed" else "superseded_cache",
                )
            }
            val immediateVisiblePayload =
                dataSource.restorePendingLocalSends(
                    provenance = producerContext.provenance,
                    route = routeIdentity,
                    canonicalPayload = immediateFirstFrameCandidate,
                )
            val keptAccess =
                threadId != null &&
                    routeIdentity.surfaceKind != RaftThreadSurfaceKind.ReplyThread &&
                    dataSource.hasResolvedThreadAccessIfCurrent(threadId, producerContext.provenance)
            var immediateStale = false
            initialAccessStateMutex.withLock {
                if (
                    !staleTokenStillCurrent(
                        staleToken,
                        "thread_start",
                        if (immediateFirstFrameFromCachedAnchor) "cached_anchor_first_frame" else "superseded_first_frame",
                        routeContextPayload,
                    )
                ) {
                    immediateStale = true
                    return@withLock
                }
                val admitted =
                    dataSource.withCurrentReplyCacheProvenance(producerContext.provenance) {
                        visiblePayloadOwnerScopeId = payloadOwnerScopeId
                        reduceState(
                            SharedThreadState(
                                payload = immediateVisiblePayload,
                                highlightedMentions = emptySet(),
                                accessResolution =
                                    if (keptAccess) ThreadAccessResolution.Resolved else ThreadAccessResolution.Loading,
                                canonicalAccessResolved = false,
                                initialHomeAccessLookupComplete = false,
                                initialHomeAccessCandidate = null,
                                writeActionsEnabled =
                                    keptAccess &&
                                        immediateVisiblePayload.hasWriteAuthorityForAccess(
                                            accessResolution = ThreadAccessResolution.Resolved,
                                            homeLookupComplete = false,
                                            homeAccessCandidate = null,
                                        ),
                                loadingIndicatorRevealed = false,
                            )
                        )
                        true
                    }
                if (admitted == true) {
                    publishedImmediateFrame = immediateVisiblePayload
                    immediateFrameKeptAccess = keptAccess
                }
            }
            if (immediateStale) return
            if (publishedImmediateFrame != null) {
                if (immediateFirstFrameFromCachedAnchor) {
                    diagnosticsLogger?.invoke(
                        "thread_start_cached_anchor_seed threadId=${threadId.orEmpty()} " +
                            "anchor=${routeContextPayload.focusedMessageId.orEmpty()} " +
                            "cachedMessages=${cached?.messages?.size ?: 0} " +
                            "seedAdded=${immediateFirstFrameCandidate.messages.size - (cached?.messages?.size ?: 0)}"
                    )
                }
                if (immediateFrameKeptAccess) {
                    diagnosticsLogger?.invoke("thread_start_access_kept threadId=${threadId.orEmpty()}")
                }
                diagnosticsLogger?.invoke(
                    "thread_visible_payload threadId=${threadId.orEmpty()} status=${immediateVisiblePayload.status} " +
                        "messages=${immediateVisiblePayload.messages.size} " + immediateVisiblePayload.originDiagnostics()
                )
            }
        }
        // Offline cache read entry (task #1123 / Phase 2d): when no in-memory candidate exists, or
        // an anchored open's in-memory cache lacks the target message, read the bounded durable window
        // (newest page or stored island around focusedMessageId).
        val needsDurableAnchoredLookup =
            cached != null && routeContextPayload.focusedMessageId?.isNotBlank() == true && cached.lacksFocusedAnchor()
        val durableLastKnownResult =
            if ((cached == null || needsDurableAnchoredLookup) && current == null && threadId != null && staleConversationCause == null) {
                producerContext?.let { context ->
                    dataSource.durableLastKnownResultIfCurrent(
                        routePayload = routeContextPayload,
                        routeIdentity = routeIdentity,
                        context = context,
                        allowNewestFallbackForMissingAnchor = !needsDurableAnchoredLookup,
                    )
                }
            } else {
                null
            }
        val durableLastKnown = durableLastKnownResult?.payload
        val durableLastKnownScope = durableLastKnownResult?.scope
        if (!staleTokenStillCurrent(staleToken, "thread_start", "after_durable_read", routeContextPayload)) {
            return
        }
        // Hands 4b6ccb0d: when no immediate superseded memory frame was already painted, superseded
        // memory wins when it holds a strictly newer server message than disk (or disk is empty).
        // Once Frame 1 already painted supersededMemory, disk only upgrades it when disk holds a
        // strictly newer server seq (equal seq keeps the richer memory payload and metadata intact).
        val memorySeq = supersededMemory?.newestServerSeqOrNull() ?: Long.MIN_VALUE
        val diskSeq = durableLastKnown?.newestServerSeqOrNull() ?: Long.MIN_VALUE
        val diskStrictlyNewerThanMemory =
            durableLastKnown != null && durableLastKnown.messages.isNotEmpty() && diskSeq > memorySeq
        val durableSatisfiesMissingMemoryAnchor =
            durableLastKnown != null &&
                routeContextPayload.focusedMessageId?.isNotBlank() == true &&
                !durableLastKnown.lacksFocusedAnchor() &&
                supersededMemory?.lacksFocusedAnchor() == true
        val supersededFirstFrame =
            supersededMemory?.takeIf { _ ->
                !durableSatisfiesMissingMemoryAnchor &&
                    (durableLastKnown == null ||
                        durableLastKnown.messages.isEmpty() ||
                        memorySeq > diskSeq)
            }
        val durableAnchoredOverCached =
            needsDurableAnchoredLookup &&
                durableLastKnown != null &&
                !durableLastKnown.lacksFocusedAnchor()
        val publishedImmediateFromCachedAnchor =
            publishedImmediateFrame != null && immediateFirstFrameFromCachedAnchor
        val shouldUpgradeImmediateFromDisk =
            when {
                publishedImmediateFromCachedAnchor -> durableAnchoredOverCached
                publishedImmediateFrame != null -> diskStrictlyNewerThanMemory
                else -> false
            }
        val shouldAdoptDurableFrame =
            when {
                publishedImmediateFrame != null -> shouldUpgradeImmediateFromDisk
                cached != null -> durableAnchoredOverCached
                else -> durableLastKnown != null && supersededFirstFrame == null
            }
        if (supersededMemory != null) {
            diagnosticsLogger?.invoke(
                "thread_start_superseded_memory threadId=${threadId.orEmpty()} " +
                    "memoryMessages=${supersededMemory.messages.size} " +
                    "memorySeq=${supersededMemory.newestServerSeqOrNull()} " +
                    "durableSeq=${durableLastKnown?.newestServerSeqOrNull()} " +
                    "chosen=${if (shouldAdoptDurableFrame) "durable" else "memory"}"
            )
        }
        if (supersededFirstFrame != null && publishedImmediateFrame == null) {
            dataSource.seedThreadAuthorProfiles(
                serverId = session?.currentServerId,
                payload = supersededFirstFrame,
                source = "superseded_cache"
            )
        }
        // Hands dd79da45: with no in-memory candidate (cold start), seed the reply-preview windows
        // persisted by the last session. The durable frame shows them on its first frame, and the
        // first network commit re-reads them from the sync host until the summaries confirm them.
        // Only await the (small) disk read when a durable frame actually wins the first frame; when
        // superseded memory won or there is no durable frame, seed beside the network fetch.
        val seededReplyWindows =
            if ((cached == null || durableAnchoredOverCached) && current == null && producerContext != null) {
                if (shouldAdoptDurableFrame) {
                    seedCachedReplyWindows(routeContextPayload, producerContext)
                } else {
                    scope.launch { seedCachedReplyWindows(routeContextPayload, producerContext) }
                    emptyMap()
                }
            } else {
                emptyMap()
            }
        if (!staleTokenStillCurrent(staleToken, "thread_start", "after_reply_windows_seed", routeContextPayload)) {
            return
        }
        val durableLastKnownWithReplies =
            durableLastKnown?.withSeededReplyWindows(seededReplyWindows)
        if (durableLastKnownWithReplies != null && shouldAdoptDurableFrame) {
            dataSource.seedThreadAuthorProfiles(
                serverId = session?.currentServerId,
                payload = durableLastKnownWithReplies,
                source = "durable_last_known"
            )
        }
        diagnosticsLogger?.invoke(
            "thread_start threadId=${threadId.orEmpty()} cachedMemory=${cached != null || current != null} " +
                "durableLastKnown=${durableLastKnown != null} " +
                "status=${routeContextPayload.status} messages=${routeContextPayload.messages.size} " +
                routeContextPayload.originDiagnostics()
        )
        // artin 10-26: a seed message whose Activity preview was cut shows the full copy when either
        // route-matched local frame already holds that message.
        val activitySeedBodies =
            activitySeed.withLocalFullMessages(
                listOfNotNull(cached, supersededMemory, durableLastKnownWithReplies).flatMap { it.messages }
            )
        if (publishedImmediateFrame != null) {
            var staleDropped = false
            var adoptedDurableScope: SyncScopeKey? = null
            initialAccessStateMutex.withLock {
                if (!staleTokenStillCurrent(staleToken, "thread_start", "post_durable_reconcile", routeContextPayload)) {
                    staleDropped = true
                    return@withLock
                }
                val admitted =
                    producerContext?.let { context ->
                        dataSource.withCurrentReplyCacheProvenance(context.provenance) {
                            val liveState = _state.value
                            val livePayload = liveState.payload
                            if (!shouldUpgradeImmediateFromDisk || durableLastKnownWithReplies == null) {
                                // Disk is equal or older than the already-rendered Frame-1 memory copy:
                                // keep the richer memory state intact (metadata, originMessage, older pages,
                                // and any live realtime/access updates) and only enrich truncated seeds.
                                val upgraded =
                                    durableLastKnownWithReplies?.let { livePayload.withActivitySeedBodiesFrom(it) }
                                        ?: livePayload
                                if (upgraded !== livePayload) {
                                    reduceState(liveState.copy(payload = upgraded))
                                }
                            } else {
                                val liveSeq = livePayload.newestServerSeqOrNull() ?: Long.MIN_VALUE
                                val diskAdvancesLive =
                                    diskSeq > liveSeq ||
                                        (immediateFrameAddedActivitySeed && livePayload.durableLastKnown && diskSeq >= liveSeq)
                                if (diskAdvancesLive) {
                                    // Merge disk timeline fields onto livePayload so ReplyThread originMessage,
                                    // header metadata (members/tasks/settingsActions), canonical access, and
                                    // live channelArchived updates are never wiped by a bare routePayload disk copy.
                                    val mergedFromDisk = livePayload.withDurableTimelineOverlay(durableLastKnownWithReplies)
                                    val upgradedCandidate = mergedFromDisk.withActivityOpenSeed(activitySeedBodies)
                                    val upgradedVisible =
                                        dataSource.restorePendingLocalSends(
                                            provenance = context.provenance,
                                            route = routeIdentity,
                                            canonicalPayload = upgradedCandidate,
                                        )
                                    if (!upgradedVisible.lacksFocusedAnchor()) {
                                        visiblePayloadOwnerScopeId = payloadOwnerScopeId
                                        adoptedDurableScope = durableLastKnownScope
                                        reduceState(
                                            liveState.copy(
                                                payload = upgradedVisible,
                                                loadingIndicatorRevealed = false,
                                            )
                                        )
                                        diagnosticsLogger?.invoke(
                                            "thread_visible_payload threadId=${threadId.orEmpty()} status=${upgradedVisible.status} " +
                                                "messages=${upgradedVisible.messages.size} " + upgradedVisible.originDiagnostics()
                                        )
                                    }
                                } else {
                                    val upgraded = livePayload.withActivitySeedBodiesFrom(durableLastKnownWithReplies)
                                    if (upgraded !== livePayload) {
                                        reduceState(liveState.copy(payload = upgraded))
                                    }
                                }
                            }
                            true
                        }
                    }
                if (admitted == null) {
                    visiblePayloadOwnerScopeId =
                        dataSource.currentReplyCacheProvenance()?.threadPayloadOwnerScopeId
                    reduceState(
                        SharedThreadState(
                            payload =
                                if (threadId == null) {
                                    routeContextPayload
                                } else {
                                    routeContextPayload.copy(
                                        status = "loading",
                                        messages = emptyList(),
                                        backgroundRefreshing = false,
                                        errorCode = null
                                    )
                                },
                            highlightedMentions = emptySet(),
                            accessResolution = ThreadAccessResolution.Loading,
                            canonicalAccessResolved = false,
                            initialHomeAccessLookupComplete = false,
                            initialHomeAccessCandidate = null,
                            writeActionsEnabled = false,
                            loadingIndicatorRevealed = isLoadingRevealElapsedForCurrentGeneration(),
                        )
                    )
                }
            }
            if (staleDropped) return
            adoptedDurableScope?.let { readScope ->
                scope.launch { dataSource.touchDurableUserAccess(readScope) }
            }
        } else {
            val durableWithMemoryMetadata =
                durableLastKnownWithReplies?.let { durable ->
                    supersededMemory?.withDurableTimelineOverlay(durable) ?: durable
                }
            val standInFirstFrame =
                supersededFirstFrame
                    ?: durableWithMemoryMetadata
                    ?: if (threadId == null) {
                        routeContextPayload
                    } else {
                        routeContextPayload.copy(status = "loading", errorCode = null, errorServerMessage = null, errorTraceId = null)
                    }
            val seededStandIn = standInFirstFrame.withActivityOpenSeed(activitySeedBodies)
            if (activitySeed.isNotEmpty()) {
                diagnosticsLogger?.invoke(
                    "thread_start_activity_seed threadId=${threadId.orEmpty()} seed=${activitySeed.size} " +
                        "truncated=${activitySeedBodies.count { it.activityPreviewTruncated }} " +
                        "standIn=${standInFirstFrame.status}/${standInFirstFrame.messages.size} " +
                        "added=${seededStandIn.messages.size - standInFirstFrame.messages.size}"
                )
            }
            // Hands 7e198cab: an open aimed at a message (Activity, a notification, a link) whose local frame
            // lacks that message would paint an older window as if it were the conversation and show the
            // message only after the network answered (or never, when the answer kept being superseded).
            // Such an open shows a loading state at the anchor instead (the Activity seed when it holds the
            // message); the candidate is kept only to stand in if the load fails.
            val durableAnchoredWinner =
                if (cached != null && durableAnchoredOverCached && durableLastKnownWithReplies != null) {
                    cached.withDurableTimelineOverlay(durableLastKnownWithReplies)
                } else {
                    null
                }
            val firstFrameCandidate = durableAnchoredWinner ?: cached ?: current ?: seededStandIn
            val anchorPending =
                current == null &&
                    firstFrameCandidate.hasRenderableThreadContent() &&
                    firstFrameCandidate.lacksFocusedAnchor()
            val anchoredFirstFrame =
                if (anchorPending) {
                    anchorPendingStandIn = firstFrameCandidate
                    val anchorId = routeContextPayload.focusedMessageId.orEmpty()
                    val loading =
                        routeContextPayload.copy(
                            status = if (threadId == null) routeContextPayload.status else "loading",
                            errorCode = null,
                            errorServerMessage = null,
                            errorTraceId = null,
                        )
                    val seedHoldsAnchor = activitySeedBodies.any { it.id == anchorId }
                    val candidateSource =
                        when {
                            cached != null -> "memory"
                            supersededFirstFrame != null -> "superseded_memory"
                            durableLastKnownWithReplies != null -> "durable"
                            else -> "activity_seed"
                        }
                    diagnosticsLogger?.invoke(
                        "thread_start_anchor_pending threadId=${threadId.orEmpty()} anchor=$anchorId " +
                            "candidate=$candidateSource candidateMessages=${firstFrameCandidate.messages.size} " +
                            "seed=$seedHoldsAnchor"
                    )
                    if (seedHoldsAnchor) loading.withActivityOpenSeed(activitySeedBodies) else loading
                } else {
                    firstFrameCandidate
                }
            val visiblePayload =
                anchoredFirstFrame.let { candidate ->
                    rememberedAccessFailure?.let { failure ->
                        candidate
                            .asRefreshFailed(failure.code)
                            .withTerminalAccessWriteAuthorityClosed(ThreadAccessResolution.Error)
                    } ?: candidate
                }.let { candidate ->
                    producerContext?.let { context ->
                        dataSource.restorePendingLocalSends(
                            provenance = context.provenance,
                            route = routeIdentity,
                            canonicalPayload = candidate,
                        )
                    } ?: candidate
                }
            diagnosticsLogger?.invoke(
                "thread_visible_payload threadId=${threadId.orEmpty()} status=${visiblePayload.status} " +
                    "messages=${visiblePayload.messages.size} " + visiblePayload.originDiagnostics()
            )
            // Hands 4cf4c781: a reopen that shows this route's memory frame (or a durable anchored
            // timeline overlaid onto that memory frame's verified metadata) keeps the access this
            // owner already resolved for it, so the composer / Join bar stays up while the refresh
            // runs. The refresh still applies the canonical access it returns (canonicalAccessResolved
            // stays false) and a terminal NoPermission closes it. Reply threads keep their parent-row lookup.
            val keptResolvedAccess =
                rememberedAccessFailure == null &&
                    threadId != null &&
                    !anchorPending &&
                    (cached ?: current ?: supersededMemory) != null &&
                    routeIdentity.surfaceKind != RaftThreadSurfaceKind.ReplyThread &&
                    producerContext?.provenance?.let { provenance ->
                        dataSource.hasResolvedThreadAccessIfCurrent(threadId, provenance)
                    } == true
            if (keptResolvedAccess) {
                diagnosticsLogger?.invoke("thread_start_access_kept threadId=${threadId.orEmpty()}")
            }
            val durableCandidateSelected =
                !anchorPending &&
                    rememberedAccessFailure == null &&
                    (durableAnchoredWinner != null ||
                        (cached == null && current == null && supersededFirstFrame == null && durableLastKnownWithReplies != null))
            var staleDropped = false
            var adoptedDurableScope: SyncScopeKey? = null
            initialAccessStateMutex.withLock {
                if (!staleTokenStillCurrent(staleToken, "thread_start", "initial_commit", routeContextPayload)) {
                    staleDropped = true
                    return@withLock
                }
                val admitted =
                    producerContext?.let { context ->
                        dataSource.withCurrentReplyCacheProvenance(context.provenance) {
                            visiblePayloadOwnerScopeId = payloadOwnerScopeId
                            if (durableCandidateSelected) {
                                adoptedDurableScope = durableLastKnownScope
                            }
                            reduceState(
                                SharedThreadState(
                                    payload = visiblePayload,
                                    highlightedMentions = emptySet(),
                                    accessResolution =
                                        when {
                                            rememberedAccessFailure != null -> ThreadAccessResolution.Error
                                            keptResolvedAccess -> ThreadAccessResolution.Resolved
                                            else -> ThreadAccessResolution.Loading
                                        },
                                    canonicalAccessResolved = false,
                                    initialHomeAccessLookupComplete = false,
                                    initialHomeAccessCandidate = null,
                                    writeActionsEnabled =
                                        keptResolvedAccess &&
                                            visiblePayload.hasWriteAuthorityForAccess(
                                                accessResolution = ThreadAccessResolution.Resolved,
                                                homeLookupComplete = false,
                                                homeAccessCandidate = null,
                                            ),
                                    loadingIndicatorRevealed = isLoadingRevealElapsedForCurrentGeneration(),
                                )
                            )
                        }
                    }
                if (admitted == null) {
                    visiblePayloadOwnerScopeId =
                        dataSource.currentReplyCacheProvenance()?.threadPayloadOwnerScopeId
                    reduceState(
                        SharedThreadState(
                            payload =
                                if (threadId == null) {
                                    routeContextPayload
                                } else {
                                    routeContextPayload.copy(
                                        status = "loading",
                                        messages = emptyList(),
                                        backgroundRefreshing = false,
                                        errorCode = null
                                    )
                                },
                            highlightedMentions = emptySet(),
                            accessResolution = ThreadAccessResolution.Loading,
                            canonicalAccessResolved = false,
                            initialHomeAccessLookupComplete = false,
                            initialHomeAccessCandidate = null,
                            writeActionsEnabled = false,
                            loadingIndicatorRevealed = isLoadingRevealElapsedForCurrentGeneration(),
                        )
                    )
                }
            }
            if (staleDropped) return
            adoptedDurableScope?.let { readScope ->
                scope.launch { dataSource.touchDurableUserAccess(readScope) }
            }
        }
        if (!staleTokenStillCurrent(staleToken, "thread_start", "before_refresh", routeContextPayload)) {
            return
        }
        delay(SharedThreadFirstFrameDelayMillis)
        if (
            staleTokenStillCurrent(staleToken, "thread_start", "highlight_mentions", routeContextPayload) &&
            (threadId == null || _state.value.payload.sourceThreadId == threadId) &&
            producerContext?.provenance?.let(dataSource::isCurrentReplyCacheProvenance) == true
        ) {
            reduceState(_state.value.copy(highlightedMentions = session.threadSelfMentionNames()))
        }
        // Process restart / page entry: adopt journaled sends whose owning attempt died and
        // resume automatic retries. Fire-and-forget; the drainer no-ops until provenance and a
        // send-valid route are available.
        pendingSendDrainer?.onStoreStarted()
        if (rememberedAccessFailure != null) {
            diagnosticsLogger?.invoke(
                "thread_start_access_failure_reused route=${routeIdentity.instanceKey} " +
                    "code=${rememberedAccessFailure.code}"
            )
            return
        }
        refresh(routeContextPayload, staleToken, reusePrewarm = true)
    }

    /**
     * Hands 4cf4c781: a task conversation's access resolves with its parent channel detail; its
     * title (the task row) is read beside the refresh when the conversation's task is already known
     * (a reopen), else right after the refresh commits, and patched in when it lands. One read per
     * route at a time; an equal title is a no-op.
     */
    private fun launchTaskConversationTitle(
        shown: RaftThreadScreenPayload,
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken,
    ) {
        if (!shown.isTaskConversationSurface()) return
        val conversationId = shown.resolveThreadRouteIdentity().timelineChannelId ?: return
        if (taskConversationTitleJob?.isActive == true && taskConversationTitleJobKey == conversationId) return
        taskConversationTitleJob?.cancel()
        taskConversationTitleJobKey = conversationId
        taskConversationTitleJob =
            scope.launch {
                val title =
                    dataSource.loadTaskConversationTitle(ChannelId(conversationId))
                        ?.takeIf { it.isNotBlank() }
                        ?: return@launch
                initialAccessStateMutex.withLock {
                    val emitted =
                        reducePayloadPatch(cachePayload = true) { current ->
                            if (
                                !staleTokenStillCurrent(staleToken, "thread_task_title", "apply", routePayload, current) ||
                                !routeStillCurrent(routePayload, "thread_task_title", "apply", current) ||
                                current.resolveThreadRouteIdentity().timelineChannelId != conversationId ||
                                current.title == title
                            ) {
                                current
                            } else {
                                current.copy(title = title)
                            }
                        }
                    if (emitted) {
                        diagnosticsLogger?.invoke("thread_task_title_applied threadId=$conversationId")
                    }
                }
            }
    }

    /** Hands 4cf4c781: see [SharedThreadPayloadStore.setResolvedAccess]. Reply threads are not kept. */
    private fun rememberRefreshedAccess(
        routePayload: RaftThreadScreenPayload,
        provenance: ReplyCacheProvenance,
        accessResolution: ThreadAccessResolution,
        canonicalAccessResolved: Boolean,
    ) {
        val routeIdentity = routePayload.resolveThreadRouteIdentity()
        if (routeIdentity.surfaceKind == RaftThreadSurfaceKind.ReplyThread) return
        val threadId = routeIdentity.timelineKey ?: return
        when {
            accessResolution == ThreadAccessResolution.Resolved && canonicalAccessResolved ->
                dataSource.rememberResolvedThreadAccess(threadId, provenance, resolved = true)
            accessResolution == ThreadAccessResolution.NoPermission ||
                accessResolution == ThreadAccessResolution.Error ->
                dataSource.rememberResolvedThreadAccess(threadId, provenance, resolved = false)
        }
    }

    private fun applyRouteIntent(routePayload: RaftThreadScreenPayload) {
        val routeCandidatePayload = routePayload.withoutRouteCandidateContent()
        val currentPayload = _state.value.payload
        val currentIdentity = currentPayload.resolveThreadRouteIdentity()
        val incomingIdentity = routeCandidatePayload.resolveThreadRouteIdentity()
        if (!currentIdentity.canRetainThreadPageFor(incomingIdentity)) {
            diagnosticsLogger?.invoke(
                "thread_route_intent_drop reason=route_changed " +
                    "current=${currentIdentity.instanceKey} incoming=${incomingIdentity.instanceKey}"
            )
            return
        }
        // Same-identity intent updates (tab/focus/nonce) do not invalidate the
        // in-flight canonical content/access generation. Content projection
        // deliberately does not own those intent fields, so the latest intent
        // remains intact when the current generation completes.
        lastRoutePayload =
            (lastRoutePayload ?: currentPayload)
                .withRouteIntentFrom(routeCandidatePayload)
                .withoutTransientLocalSendingMessages()
        reducePayloadPatch { currentPayload ->
            currentPayload.withRouteIntentFrom(routeCandidatePayload)
        }
    }

    private suspend fun seedCachedReplyWindows(
        routePayload: RaftThreadScreenPayload,
        context: ReplyCacheProducerContext,
    ): Map<String, ThreadRepliesAcceptedProjection> {
        val page = captureThreadRepliesPageIdentity(routePayload, context) ?: return emptyMap()
        val windows = dataSource.cachedReplyWindowsIfCurrent(page, context)
        val seeded = threadRepliesSyncHost.seedCachedWindows(page, windows)
        diagnosticsLogger?.invoke(
            "thread_reply_windows_seed threadId=${routePayload.sourceThreadId.orEmpty()} " +
                "cached=${windows.size} seeded=${seeded.size}"
        )
        return seeded
    }

    private suspend fun captureThreadRepliesPageIdentity(
        payload: RaftThreadScreenPayload,
    ): ThreadRepliesPageIdentity? {
        val context = dataSource.captureReplyCacheProducerContext() ?: return null
        return captureThreadRepliesPageIdentity(payload, context)
    }

    private fun captureThreadRepliesPageIdentity(
        payload: RaftThreadScreenPayload,
        context: ReplyCacheProducerContext,
    ): ThreadRepliesPageIdentity? {
        val parentScopeId =
            if (payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
                payload.parentChannelId
            } else {
                payload.sourceThreadId ?: payload.streamChannelId
            }?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val parentScopeKind =
            when (payload.surfaceKind) {
                RaftThreadSurfaceKind.ReplyThread -> payload.parentChannelKind ?: payload.parentSurfaceKind
                // Hands b6068b41: a DM route never carries a channel kind, so `channelKind` keeps its
                // "channel" default. The server's `syncCoreReplyWindow` names a DM parent scope "dm";
                // deriving "channel" here rejected every live reply frame on DM pages, so the inline
                // preview count moved (ThreadUpdated) while its rows stayed stale.
                RaftThreadSurfaceKind.DirectMessage -> RaftThreadSurfaceKind.DirectMessage
                else -> payload.channelKind.ifBlank { payload.surfaceKind }
            }.toThreadRepliesParentScopeKind()
        return ThreadRepliesPageIdentity(
            serverId = context.provenance.serverId,
            principalId = context.provenance.principalId,
            parentScopeKind = parentScopeKind,
            parentScopeId = parentScopeId,
        ).takeIf(ThreadRepliesPageIdentity::isValid)
    }

    private fun RaftThreadScreenPayload.withAcceptedThreadRepliesRebaseline(
        previous: RaftThreadScreenPayload,
        page: ThreadRepliesPageIdentity?,
        generation: Long?,
        expectedProvenance: ReplyCacheProvenance? = null,
    ): RaftThreadScreenPayload {
        val authorityAvailable =
            replySummariesAuthority == build.raft.app.data.ThreadReplySummariesAuthority.Embedded ||
                replySummariesAuthority == build.raft.app.data.ThreadReplySummariesAuthority.Targeted
        if (page == null || generation == null || !authorityAvailable) {
            if (page != null && generation != null) {
                threadRepliesSyncHost.abortAuthoritativeRebaseline(page, generation)
            }
            return withAcceptedThreadRepliesProjection(emptyMap(), previous)
                .copy(replySummariesAuthority = previous.replySummariesAuthority)
        }
        val previousById =
            (previous.messages + listOfNotNull(previous.originMessage)).associateBy { it.id }
        val snapshots =
            (messages + listOfNotNull(originMessage)).mapNotNull { message ->
                val previousMessage = previousById[message.id]
                val threadChannelId =
                    message.threadChannelId?.trim()?.takeIf { it.isNotBlank() }
                        ?: previousMessage?.threadChannelId?.trim()?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                val model = message.inlineReplies
                when {
                    model != null ->
                        ThreadRepliesAuthoritativeSnapshot(
                            identity =
                                ThreadRepliesScopeIdentity(
                                    page = page,
                                    parentMessageId = message.id,
                                    threadChannelId = threadChannelId,
                                ),
                            replies = model.replies,
                            replyCount = model.replyCount,
                        )
                    message.replyCount <= 0 ->
                        ThreadRepliesAuthoritativeSnapshot(
                            identity =
                                ThreadRepliesScopeIdentity(
                                    page = page,
                                    parentMessageId = message.id,
                                    threadChannelId = threadChannelId,
                                ),
                            replies = emptyList(),
                            replyCount = 0,
                        )
                    else -> null // malformed/missing summary for a positive-count parent: fail closed per scope
                }
            }
        val accepted =
            threadRepliesSyncHost.ingestAuthoritativeSnapshots(
                page = page,
                generation = generation,
                snapshots = snapshots,
                withCommitAdmission =
                    expectedProvenance?.let { provenance ->
                        { commit ->
                            dataSource.withCurrentReplyCacheProvenanceForLocalSend(provenance) {
                                commit()
                            }
                        }
                    },
            )
                ?: return withAcceptedThreadRepliesProjection(emptyMap(), previous)
                    .copy(replySummariesAuthority = previous.replySummariesAuthority)
        dataSource.recordReplyWindows(accepted.values)
        return withAcceptedThreadRepliesProjection(accepted, previous)
            .copy(replySummariesAuthority = previous.replySummariesAuthority)
    }

    private suspend fun refresh(
        basePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken = routeStaleToken,
        reusePrewarm: Boolean = false,
        explicitRetry: Boolean = false
    ) {
        val refreshStartedAt = TimeSource.Monotonic.markNow()
        val realtimeFold = beginInFlightRealtimeFold(staleToken)
        try {
            refreshOnce(basePayload, staleToken, reusePrewarm, explicitRetry, refreshStartedAt, realtimeFold)
        } catch (cancelled: CancellationException) {
            // Hands 7e198cab: a refresh cut short (the page was left, or a newer route replaced it) used to
            // leave no result line, so a log could not tell "still loading" from "abandoned".
            diagnosticsLogger?.invoke(
                "thread_refresh_result threadId=${basePayload.sourceThreadId.orEmpty()} result=cancelled " +
                    "durationMs=${refreshStartedAt.elapsedNow().inWholeMilliseconds}"
            )
            throw cancelled
        } finally {
            endInFlightRealtimeFold(realtimeFold)
        }
    }

    private suspend fun refreshOnce(
        basePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken,
        reusePrewarm: Boolean,
        explicitRetry: Boolean,
        refreshStartedAt: TimeSource.Monotonic.ValueTimeMark,
        realtimeFold: InFlightRealtimeFold,
    ) {
        val refreshProducerContext = dataSource.captureReplyCacheProducerContext() ?: return
        val refreshProvenance = refreshProducerContext.provenance
        if (
            explicitRetry &&
            !dataSource.clearRememberedReplyThreadAccessFailure(basePayload, refreshProducerContext)
        ) {
            return
        }
        var refreshStartState = _state.value
        val resolvingInitialAccessAtStart =
            refreshStartState.accessResolution != ThreadAccessResolution.Resolved
        val retryingReadOnlyParentAccess =
            refreshStartState.accessResolution == ThreadAccessResolution.Resolved &&
                !refreshStartState.writeActionsEnabled
        if (resolvingInitialAccessAtStart) {
            initialAccessStateMutex.withLock {
                val admitted =
                    dataSource.withCurrentReplyCacheProvenanceForLocalSend(refreshProvenance) {
                        val latest = _state.value
                        val preservePositiveReplyCandidate =
                            latest.payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
                                latest.replyParentAuthority == ReplyParentAuthorityStatus.Writable &&
                                latest.initialHomeAccessCandidate?.let { it.joined && it.permissions.canSend } == true
                        reduceState(
                            latest.copy(
                                accessResolution = ThreadAccessResolution.Loading,
                                canonicalAccessResolved = false,
                                provisionalCanonicalAccess = false,
                                initialHomeAccessLookupComplete =
                                    if (preservePositiveReplyCandidate) latest.initialHomeAccessLookupComplete else false,
                                initialHomeAccessCandidate =
                                    if (preservePositiveReplyCandidate) latest.initialHomeAccessCandidate else null
                            )
                        )
                        refreshStartState = _state.value
                    }
                if (admitted == null) {
                    return
                }
            }
            if (!dataSource.isCurrentReplyCacheProvenanceForLocalSend(refreshProvenance)) return
            launchInitialAccessResolution(basePayload, staleToken)
        } else if (retryingReadOnlyParentAccess) {
            // A readable reply timeline must stay terminal while Refresh retries
            // its auxiliary parent capability. Resetting canonical access here
            // would let a transient reply refresh error cover preserved history.
            launchInitialAccessResolution(basePayload, staleToken)
        }
        val existing = refreshStartState.payload
        if (!routeStillCurrent(basePayload, "thread_refresh", "start", existing)) {
            return
        }
        launchTaskConversationTitle(existing, basePayload, staleToken)
        val refreshObservedMessageIds = existing.messages.mapTo(mutableSetOf()) { message -> message.id }
        val repliesPageIdentity = captureThreadRepliesPageIdentity(basePayload, refreshProducerContext)
        val repliesRebaselineGeneration =
            repliesPageIdentity?.let { page ->
                val generation = ++nextThreadRepliesRebaselineGeneration
                generation.takeIf { threadRepliesSyncHost.beginAuthoritativeRebaseline(page, generation) }
            }
        fun abortRepliesRebaseline() {
            if (repliesPageIdentity != null && repliesRebaselineGeneration != null) {
                threadRepliesSyncHost.abortAuthoritativeRebaseline(
                    repliesPageIdentity,
                    repliesRebaselineGeneration,
                )
            }
        }
        fun dropForOwnerChange(stage: String, result: DataResult<RaftThreadScreenPayload>? = null) {
            abortRepliesRebaseline()
            diagnosticsLogger?.invoke(
                "thread_refresh_result threadId=${basePayload.sourceThreadId.orEmpty()} " +
                    "result=${result?.threadDiagnosticsResult().orEmpty()} ignored=true " +
                    "reason=owner_changed stage=$stage " +
                    "durationMs=${refreshStartedAt.elapsedNow().inWholeMilliseconds}"
            )
        }
        if (!dataSource.isCurrentReplyCacheProvenanceForLocalSend(refreshProvenance)) {
            dropForOwnerChange("before_loading")
            return
        }
        val hasRenderableContent = existing.hasRenderableThreadContent()
        diagnosticsLogger?.invoke(
            "thread_refresh_start threadId=${basePayload.sourceThreadId.orEmpty()} " +
                "existingStatus=${existing.status} existingMessages=${existing.messages.size} " +
                "hasRenderableContent=$hasRenderableContent"
        )
        val loadingBase =
            if (hasRenderableContent || existing.isDurableEmptyStandIn()) {
                val clearSupersededOlder =
                    existing.olderMessagesStatus == "loading" &&
                        loadOlderState?.staleToken?.isCurrent(staleToken) != true
                if (clearSupersededOlder) {
                    loadOlderState = null
                }
                val clearSupersededNewer =
                    existing.newerMessagesStatus == "loading" &&
                        loadNewerState?.staleToken?.isCurrent(staleToken) != true
                existing.withRouteContext(basePayload).copy(
                    backgroundRefreshing = true,
                    olderMessagesStatus = if (clearSupersededOlder) "idle" else existing.olderMessagesStatus,
                    newerMessagesStatus = if (clearSupersededNewer) "idle" else existing.newerMessagesStatus,
                )
            } else {
                basePayload.copy(status = "loading", backgroundRefreshing = false, errorCode = null, errorServerMessage = null, errorTraceId = null)
            }
        diagnosticsLogger?.invoke(
            "thread_loading_patch threadId=${basePayload.sourceThreadId.orEmpty()} status=${loadingBase.status} " +
                "messages=${loadingBase.messages.size} " + loadingBase.originDiagnostics()
        )
        val loadingAdmitted =
            dataSource.withCurrentReplyCacheProvenanceForLocalSend(refreshProvenance) {
                if (explicitRetry) {
                    markLoadingRevealElapsedImmediately()
                }
                reducePayloadPatch { currentPayload ->
                    if (!routeStillCurrent(basePayload, "thread_refresh", "loading_apply", currentPayload)) {
                        return@reducePayloadPatch currentPayload
                    }
                    currentPayload.withThreadContentProjectionFrom(loadingBase)
                }
            }
        if (loadingAdmitted == null) {
            dropForOwnerChange("loading_apply")
            return
        }
        afterRefreshLoadingStateApplied?.invoke(_state.value.payload)
        if (!hasRenderableContent) {
            delay(SharedThreadFirstFrameDelayMillis)
        }
        // Message storage Phase 2b: this request's own page is the durable write (no separate enter fetch). The ticket
        // (realtime baseline, partition generation, write revision) is taken BEFORE the request.
        val enteredRoute = basePayload.resolveThreadRouteIdentity()
        val enteredReplyParent = basePayload.durableReplyParentOrNull()
        val enteredTicket = dataSource.beginEnteredPageIfCurrent(enteredRoute, refreshProducerContext, enteredReplyParent)
        // A reply route whose stream this request resolves cannot take a ticket yet; the revision and generation are
        // still read now, before the request, for the ticket taken after it.
        val resolvesItsStream =
            enteredTicket == null && enteredReplyParent != null && enteredRoute.timelineChannelId == null &&
                dataSource.hasEnteredPageWriter
        val enteredStamp = if (resolvesItsStream) dataSource.durableRequestStampIfCurrent(refreshProducerContext) else null
        var enteredPage: ThreadDurableEnteredPage? = null
        // Capture the raw page only when it can be written (a writer that took a ticket, or will after the request).
        val onEnteredPage: ((ThreadDurableEnteredPage) -> Unit)? =
            if (enteredTicket != null || resolvesItsStream) { page -> enteredPage = page } else null
        var refetchCapped = false
        val result =
            try {
                dataSource.fetchThreadPayloadForContext(
                    basePayload = basePayload,
                    expectedContext = refreshProducerContext,
                    persistResult = true,
                    reusePrewarm = reusePrewarm,
                    replaceReplyAccessFailure = explicitRetry,
                    onFocusedContextPage = ::launchDurableContextPagePersist,
                    onEnteredPage = onEnteredPage,
                    onSupersededCanonicalResult = { superseded ->
                        applySupersededCanonicalAccess(basePayload, staleToken, refreshProvenance, superseded)
                        applySupersededActivitySeedBodies(basePayload, staleToken, refreshProvenance, superseded)
                        applySupersededAnchorContext(
                            basePayload,
                            staleToken,
                            refreshProvenance,
                            superseded,
                            refreshObservedMessageIds,
                        )
                    },
                    onCanonicalRefetchCapped = { refetchCapped = true },
                    foldsRealtimeIntoSupersededResult = { messageIds -> realtimeFold.holdsAll(messageIds) },
                )
            } finally {
                // Superseded content is not a durable baseline either.
                if (refetchCapped) enteredPage = null
                launchEnteredPagePersist(enteredTicket, enteredStamp, enteredPage, basePayload, refreshProducerContext)
            }
        val latest = _state.value.payload
        if (
            !dataSource.isCurrentReplyCacheProvenanceForLocalSend(refreshProvenance) ||
            (result is DataResult.Error && result.error.code == ReplyCacheProvenanceStaleCode)
        ) {
            dropForOwnerChange("after_fetch", result)
            return
        }
        if (
            !staleTokenStillCurrent(staleToken, "thread_refresh", "after_fetch", basePayload, latest) ||
            !routeStillCurrent(basePayload, "thread_refresh", "after_fetch", latest)
        ) {
            abortRepliesRebaseline()
            diagnosticsLogger?.invoke(
                "thread_refresh_result threadId=${basePayload.sourceThreadId.orEmpty()} " +
                    "result=${result.threadDiagnosticsResult()} ignored=true reason=stale_route " +
                    "durationMs=${refreshStartedAt.elapsedNow().inWholeMilliseconds}"
            )
            return
        }
        if (refetchCapped && result is DataResult.Content) {
            // The capped result is superseded: a delete or edit that landed mid-fetch may be missing
            // from it, and the stable-timeline merge would let it win over the live timeline. Keep the
            // visible content (the loading shell if there is none), commit and cache nothing, and
            // refresh once more after a pause; access may already be provisional from the first round.
            abortRepliesRebaseline()
            diagnosticsLogger?.invoke(
                "thread_refresh_result threadId=${basePayload.sourceThreadId.orEmpty()} " +
                    "result=${result.threadDiagnosticsResult()} ignored=true reason=superseded_capped " +
                    "durationMs=${refreshStartedAt.elapsedNow().inWholeMilliseconds}"
            )
            launchCappedRefreshFollowUp(basePayload, staleToken)
            return
        }
        val repliesProjectedResult =
            (result as? DataResult.Content)
                ?.data
                ?.withRouteContext(basePayload)
                ?.let { fetched ->
                    fetched.withAcceptedThreadRepliesRebaseline(
                        previous = latest,
                        page = repliesPageIdentity,
                        generation = repliesRebaselineGeneration,
                        expectedProvenance = refreshProvenance,
                    )
                }
        if (!dataSource.isCurrentReplyCacheProvenanceForLocalSend(refreshProvenance)) {
            dropForOwnerChange("after_rebaseline", result)
            return
        }
        val visiblePayload =
            repliesProjectedResult
                ?.preserveStableTimelineFrom(
                    previous = latest,
                    preserveAuxiliaryProjectionFromPrevious = true,
                )
                ?.asLoadedEmptyReplyThreadPayload()
        val errorBase = anchorPendingFallbackFor(latest) ?: latest
        val nextPayload =
            when (result) {
                is DataResult.Content ->
                    (visiblePayload ?: result.data.withRouteContext(basePayload).asLoadedEmptyReplyThreadPayload())
                        .copy(errorCode = null, errorServerMessage = null, errorTraceId = null)
                is DataResult.Empty ->
                    if (result.reason == EmptyReason.NoMessages) {
                        latest.asRefreshEmpty(basePayload)
                    } else {
                        latest.asRefreshFailed("empty_thread")
                    }
                is DataResult.Error ->
                    // Hands 7e198cab: an anchored open still waiting at its anchor falls back to the
                    // local frame it held back (offline keeps showing what this device has).
                    errorBase.asRefreshFailed(result.error.code, result.error.traceId)
                is DataResult.NoPermission -> {
                    // Canonical fetch says we can no longer read this thread:
                    // the cached first-frame candidate is unrecoverable-stale
                    // (permission/visibility change) — drop it. Transient
                    // network errors deliberately keep the warm cache.
                    latest.asRefreshNoPermission(
                        basePayload,
                        result.blocker.code,
                        result.blocker.serverMessage,
                        result.blocker.traceId
                    )
                }
            }.copy(
                backgroundRefreshing = false,
                // A durable last-known frame survives only a transient failure; any canonical answer
                // (content, empty, no permission) replaces it and clears the stand-in flag.
                durableLastKnown =
                    (if (result is DataResult.Error) errorBase else latest).durableLastKnown &&
                        when (result) {
                            is DataResult.Error -> true
                            is DataResult.Empty -> result.reason != EmptyReason.NoMessages
                            else -> false
                        },
                mainLoadFailed =
                    when (result) {
                        is DataResult.Error -> true
                        is DataResult.Empty -> result.reason != EmptyReason.NoMessages
                        else -> false
                    },
            )
        var committedPayload: RaftThreadScreenPayload? = null
        val committed =
            initialAccessStateMutex.withLock<Boolean?> {
                val identityPayload = _state.value.payload
                val replyIdentities =
                    repliesPageIdentity?.let { page ->
                        nextPayload.threadRepliesScopeIdentities(page, identityPayload)
                    }.orEmpty()
                fun commitWithAcceptedReplies(
                    acceptedReplies: Map<String, ThreadRepliesAcceptedProjection>,
                ): Boolean? =
                    dataSource.withCurrentReplyCacheProvenanceForLocalSend(refreshProvenance) {
                        val currentState = _state.value
                        val currentPayload = currentState.payload
                        if (
                            !staleTokenStillCurrent(
                                staleToken,
                                "thread_refresh",
                                "access_apply",
                                basePayload,
                                currentPayload,
                            ) ||
                            !routeStillCurrent(basePayload, "thread_refresh", "apply", currentPayload)
                        ) {
                            false
                        } else {
                            // A positive Home candidate may resolve the footer before the canonical
                            // fetch lands; the first canonical result still has to record itself.
                            val resolvingInitialAccess =
                                currentState.accessResolution != ThreadAccessResolution.Resolved ||
                                    !currentState.canonicalAccessResolved
                            val refreshStableNextPayload =
                                if (result is DataResult.Content) {
                                    // artin #110: the live events that arrived during the request, folded
                                    // onto its answer before it replaces the live timeline.
                                    nextPayload.withInFlightRealtimeFolded(realtimeFold).preserveStableTimelineFrom(
                                        previous = currentPayload,
                                        preserveAuxiliaryProjectionFromPrevious = true,
                                        refreshObservedMessageIds = refreshObservedMessageIds,
                                        sendEchoMatchPolicy = ThreadSendEchoMatchPolicy.ExactRandomId,
                                    )
                                } else {
                                    nextPayload
                                }
                            val commitNextPayload =
                                refreshStableNextPayload.withAcceptedThreadRepliesProjection(
                                    acceptedReplies,
                                    currentPayload,
                                )
                            val projectedPayload =
                                currentPayload
                                    .withThreadContentProjectionFrom(commitNextPayload)
                                    .let { payload ->
                                        // Web #16398 (bug #555): when the focused target returned a final
                                        // 404 / message_not_found and fell back quietly to the latest
                                        // messages / replies, clear the stale focusedMessageId so the
                                        // thread does not stay stuck waiting on a deleted message.
                                        if (commitNextPayload.focusedTargetMissing) {
                                            payload.copy(
                                                focusedMessageId = null,
                                                focusedMessageRequestNonce = 0,
                                                focusedTargetMissing = true,
                                            )
                                        } else {
                                            payload
                                        }
                                    }
                                    .let { payload ->
                                        if (
                                            resolvingInitialAccess &&
                                            result is DataResult.Content &&
                                            payload.surfaceKind != RaftThreadSurfaceKind.ReplyThread &&
                                            nextPayload.canonicalAccessKnown
                                        ) {
                                            payload.withCanonicalConversationAccessFrom(nextPayload)
                                        } else {
                                            payload
                                        }
                                    }
                                    .withInitialHomeConversationAccessFallbackUnlessCanonical(
                                        canonicalAccessResolved =
                                            currentState.canonicalAccessResolved ||
                                                currentState.provisionalCanonicalAccess ||
                                                (resolvingInitialAccess && result is DataResult.Content && nextPayload.canonicalAccessKnown),
                                        candidate = currentState.initialHomeAccessCandidate,
                                    )
                                    .withReadOnlyReplyAccessWhenHomeCandidateUnavailable(
                                        homeLookupComplete = currentState.initialHomeAccessLookupComplete,
                                        homeAccessCandidate = currentState.initialHomeAccessCandidate,
                                    )
                            // A reply thread's canonical stream carries synthetic `type = "thread"`
                            // details (`canonicalAccessKnown = false`) because write authority
                            // belongs to its parent channel in Home. Loading the reply stream
                            // still proves canonical read access for the reply surface.
                            val canonicalAccessFromResultKnown =
                                when (result) {
                                    is DataResult.Content ->
                                        nextPayload.canonicalAccessKnown ||
                                            projectedPayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread
                                    is DataResult.Empty -> !projectedPayload.isTaskConversationSurface()
                                    else -> true
                                }
                            val canonicalAccessResolved =
                                currentState.canonicalAccessResolved ||
                                    (resolvingInitialAccess && result.resolvesCanonicalConversationAccess() && canonicalAccessFromResultKnown)
                            // When a non-reply canonical response could not attest membership (e.g.
                            // task conversation whose parent channel detail fetch failed), keep the
                            // timeline renderable without overwriting already-known membership or
                            // falsely flashing "Join channel" (web tri-state parentJoined === undefined).
                            val unknownCanonicalContentAccess =
                                result is DataResult.Content &&
                                    !nextPayload.canonicalAccessKnown &&
                                    projectedPayload.surfaceKind != RaftThreadSurfaceKind.ReplyThread &&
                                    !canonicalAccessResolved
                            val projectedAccessResolution =
                                if (
                                    unknownCanonicalContentAccess &&
                                    currentState.accessResolution != ThreadAccessResolution.NoPermission &&
                                    currentState.accessResolution != ThreadAccessResolution.Error
                                ) {
                                    ThreadAccessResolution.Resolved
                                } else {
                                    currentState.accessResolution.afterRefreshResult(
                                        result = result,
                                        canonicalAccessResolved = canonicalAccessResolved,
                                        homeLookupComplete = currentState.initialHomeAccessLookupComplete,
                                        homeAccessCandidate = currentState.initialHomeAccessCandidate,
                                        requiresParentAccessCandidate =
                                            projectedPayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread,
                                        hasReadableCanonicalReplyContent =
                                            projectedPayload.hasReadableCanonicalReplyContent(),
                                    )
                                }
                            val authorityProjectedPayload =
                                projectedPayload.withTerminalAccessWriteAuthorityClosed(projectedAccessResolution)
                            // If membership was never attested on a non-reply page (e.g. unknown task
                            // parent followed by an offline DataResult.Error or empty task response),
                            // preserve the existing writeActionsEnabled rather than letting Resolved
                            // defaults turn writeActionsEnabled from false to true.
                            val preserveUnresolvedNonReplyWriteAuthority =
                                !canonicalAccessResolved &&
                                    !currentState.provisionalCanonicalAccess &&
                                    authorityProjectedPayload.surfaceKind != RaftThreadSurfaceKind.ReplyThread &&
                                    (result is DataResult.Error || (result is DataResult.Empty && !canonicalAccessFromResultKnown)) &&
                                    currentState.accessResolution == ThreadAccessResolution.Resolved &&
                                    projectedAccessResolution == ThreadAccessResolution.Resolved
                            val projectedWriteActionsEnabled =
                                when {
                                    unknownCanonicalContentAccess ->
                                        when {
                                            currentState.accessResolution == ThreadAccessResolution.Resolved ->
                                                currentState.writeActionsEnabled
                                            currentState.initialHomeAccessCandidate != null &&
                                                authorityProjectedPayload.acceptsInitialConversationAccessCandidate(
                                                    currentState.initialHomeAccessCandidate
                                                ) &&
                                                currentState.initialHomeAccessCandidate.joined &&
                                                currentState.initialHomeAccessCandidate.permissions.canSend ->
                                                true
                                            else -> false
                                        }
                                    preserveUnresolvedNonReplyWriteAuthority -> currentState.writeActionsEnabled
                                    else ->
                                        authorityProjectedPayload.hasWriteAuthorityForAccess(
                                            accessResolution = projectedAccessResolution,
                                            homeLookupComplete = currentState.initialHomeAccessLookupComplete,
                                            homeAccessCandidate = currentState.initialHomeAccessCandidate,
                                        )
                                }
                            val didEmit =
                                reduceState(
                                    currentState.copy(
                                        payload = authorityProjectedPayload,
                                        accessResolution = projectedAccessResolution,
                                        canonicalAccessResolved = canonicalAccessResolved,
                                        provisionalCanonicalAccess =
                                            currentState.provisionalCanonicalAccess && !canonicalAccessResolved,
                                        lastRefreshResultReason = result.diagnosticsResultReason(),
                                        conversationAccessLost =
                                            currentState.conversationAccessLost ||
                                                (hasRenderableContent && result.isConversationAccessLost()),
                                        channelAccessDenied =
                                            currentState.channelAccessDenied ||
                                                (hasRenderableContent && result.isChannelAccessDenial()),
                                        writeActionsEnabled = projectedWriteActionsEnabled,
                                    )
                                )
                            if (didEmit && visiblePayload != null) {
                                committedPayload = authorityProjectedPayload
                            }
                            rememberRefreshedAccess(
                                routePayload = basePayload,
                                provenance = refreshProvenance,
                                accessResolution = projectedAccessResolution,
                                canonicalAccessResolved = canonicalAccessResolved,
                            )
                            true
                        }
                    }
                if (replyIdentities.isEmpty()) {
                    commitWithAcceptedReplies(emptyMap())
                } else {
                    threadRepliesSyncHost.withCurrentAcceptedProjections(replyIdentities) { accepted ->
                        commitWithAcceptedReplies(accepted)
                    }
                }
            }
        if (committed != true) {
            if (committed == null) {
                dropForOwnerChange("commit", result)
            } else {
                abortRepliesRebaseline()
            }
            return
        }
        if (result !is DataResult.Error) anchorPendingStandIn = null
        if (committedPayload?.focusedTargetMissing == true) {
            lastRoutePayload = lastRoutePayload?.copy(focusedMessageId = null, focusedMessageRequestNonce = 0)
        }
        if (result is DataResult.Content) {
            basePayload.resolveThreadRouteIdentity().timelineChannelId?.let { conversationId ->
                dataSource.clearStaleConversation(conversationId, refreshProvenance)
            }
        }
        // The route may only learn it is a task conversation from this commit.
        launchTaskConversationTitle(_state.value.payload, basePayload, staleToken)
        val currentProvenance =
            dataSource.currentReplyCacheProvenanceForLocalSend(refreshProvenance)
        if (result.isConversationAccessLost()) {
            // Tenny rule 2: drop memory + durable cache for this conversation (a 404 keeps the
            // last content on screen as a failed refresh, so it must not be re-cached below).
            dataSource.evictLostConversationAccess(basePayload, refreshProvenance)
        } else if (result is DataResult.NoPermission) {
            currentProvenance?.let { dataSource.invalidateCachedPayload(basePayload, it) }
        } else {
            committedPayload?.let { payload ->
                currentProvenance?.let { current ->
                    dataSource.cachePayload(
                        payload = payload,
                        serverId = current.serverId,
                        ownerScopeId = current.threadPayloadOwnerScopeId.orEmpty(),
                        expectedProvenance = current,
                    )
                }
            }
        }
        if (currentProvenance == null) {
            return
        }
        diagnosticsLogger?.invoke(
            "thread_refresh_result threadId=${basePayload.sourceThreadId.orEmpty()} " +
                "result=${result.threadDiagnosticsResult()} messages=${nextPayload.messages.size} " +
                "durationMs=${refreshStartedAt.elapsedNow().inWholeMilliseconds} " +
                nextPayload.originDiagnostics()
        )
        launchReplyOriginHydrationIfNeeded(basePayload, staleToken)
        if (!resolvingInitialAccessAtStart && !retryingReadOnlyParentAccess) {
            launchBackgroundHomeContextHydration(basePayload)
        }
        queueAuxiliaryHydration(
            routePayload = basePayload,
            staleToken = staleToken,
            launchSecondaryTimelineRefresh =
                visiblePayload != null &&
                    nextPayload.hasRenderableThreadContent() &&
                    !basePayload.needsReplyThreadStreamResolution(),
            launchMembersHydration = nextPayload.hasRenderableThreadContent()
        )
    }

    /**
     * Task #14c late-ack catch-up: while this page shows reply thread [routeIdentity], every
     * [CanonicalMessageV2ThreadJoinConfirmed] of its thread scope runs ONE gap catch-up (the canary requests at
     * most one per ack and only after an enter, so nothing here loops). Replaced on the next route start, cancelled
     * by [clear]: a closed page never fetches. A failed catch-up is logged and not retried.
     */
    private fun watchThreadJoinConfirmations(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        replyParent: ThreadDurableReplyParent?,
    ) {
        threadJoinCatchUpJob?.cancel()
        threadJoinCatchUpJob = null
        val confirmations = dataSource.threadJoinConfirmationsIfCurrent(routeIdentity, context, replyParent) ?: return
        // UNDISPATCHED: subscribed before this returns, i.e. before the enter snapshot is launched.
        threadJoinCatchUpJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                confirmations.collect { confirmed -> catchUpAfterThreadJoinConfirmed(confirmed) }
            }
    }

    private suspend fun catchUpAfterThreadJoinConfirmed(confirmed: CanonicalMessageV2ThreadJoinConfirmed) {
        val label = "thread_join_catch_up threadId=${confirmed.threadScopeId} epoch=${confirmed.transportEpoch}"
        val initial = _state.value.payload
        val staleToken = routeStaleToken
        val shown = initial.resolveThreadRouteIdentity()
        if (shown.surfaceKind != RaftThreadSurfaceKind.ReplyThread || shown.timelineChannelId != confirmed.threadScopeId) {
            diagnosticsLogger?.invoke("$label outcome=skipped reason=route_changed")
            return
        }
        if (realtimeGapRecoveryMutex.withLock { realtimeGapRecoveryState != null || loadNewerState != null }) {
            // A catch-up in flight registered its baseline before this ack: it cannot be the continuous one.
            diagnosticsLogger?.invoke("$label outcome=skipped reason=catch_up_in_flight")
            return
        }
        val sinceSeq = initial.messages.mapNotNull { it.seq }.maxOrNull()?.coerceAtLeast(0L) ?: 0L
        val failuresBefore = gapCatchUpRetryableFailures
        diagnosticsLogger?.invoke("$label outcome=requested")
        recoverRealtimeGap(
            initial = initial,
            initialRequest =
                RealtimeVisibleStreamGapRequest(
                    channelId = ChannelId(confirmed.threadScopeId),
                    sinceSeq = sinceSeq,
                    serverSeq = sinceSeq + 1L,
                ),
            staleToken = staleToken,
        )
        val after = _state.value.payload
        if (gapCatchUpRetryableFailures != failuresBefore || after.newerMessagesStatus == "error") {
            // Escalated by this line only: the next enter of the thread is the next chance (no retry loop).
            diagnosticsLogger?.invoke("$label outcome=failed retry=none errorCode=${after.errorCode.orEmpty()}")
        } else {
            diagnosticsLogger?.invoke("$label outcome=done")
        }
    }

    private fun queueAuxiliaryHydration(
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken,
        launchSecondaryTimelineRefresh: Boolean,
        launchMembersHydration: Boolean
    ) {
        if (!launchSecondaryTimelineRefresh && !launchMembersHydration) {
            pendingAuxiliaryHydration = null
            return
        }
        if (launchSecondaryTimelineRefresh) {
            // The caller only sets this flag once the visible payload already has
            // renderable thread content, so the reply-summaries fetch does not
            // depend on FirstContentReady. Launch it immediately instead of
            // parking it behind the UI signal (~400ms) and let it race ahead of
            // the first frame.
            diagnosticsLogger?.invoke(
                "thread_auxiliary_hydration result=launched_immediate " +
                    "threadId=${routePayload.sourceThreadId.orEmpty()} " +
                    "secondary=true members=$launchMembersHydration"
            )
            launchSecondaryTimelineRefresh(routePayload, staleToken)
        }
        if (!launchMembersHydration) {
            pendingAuxiliaryHydration = null
            return
        }
        pendingAuxiliaryHydration =
            PendingAuxiliaryHydration(
                routePayload = routePayload,
                staleToken = staleToken,
                launchSecondaryTimelineRefresh = false,
                launchMembersHydration = launchMembersHydration
            )
        diagnosticsLogger?.invoke(
            "thread_auxiliary_hydration result=queued " +
                "threadId=${routePayload.sourceThreadId.orEmpty()} " +
                "secondary=false members=$launchMembersHydration"
        )
        launchPendingAuxiliaryHydrationIfReady()
    }

    private fun markFirstContentReady() {
        releaseForegroundLoadLease(expected = null, reason = "first_content_ready")
        val currentPayload = _state.value.payload
        val routeKey = currentPayload.resolveThreadRouteIdentity().instanceKey
        firstContentReadyRouteKey = routeKey
        diagnosticsLogger?.invoke(
            "thread_first_content_ready threadId=${currentPayload.sourceThreadId.orEmpty()} routeKey=$routeKey"
        )
        launchPendingAuxiliaryHydrationIfReady()
        launchPendingSecondaryTimelineApplyIfReady()
    }

    private fun launchPendingSecondaryTimelineApplyIfReady() {
        val pending = pendingSecondaryTimelineApply ?: return
        val pendingRouteKey = pending.routePayload.resolveThreadRouteIdentity().instanceKey
        if (firstContentReadyRouteKey != pendingRouteKey) {
            // The route changed before first content became ready; drop the
            // stashed apply so an old route's result can never resurface.
            pendingSecondaryTimelineApply = null
            return
        }
        if (!pending.staleToken.isCurrent(routeStaleToken)) {
            pendingSecondaryTimelineApply = null
            diagnosticsLogger?.invoke(
                "thread_secondary_refresh_result threadId=${pending.routePayload.sourceThreadId.orEmpty()} " +
                    "ignored=true reason=stale_route stage=pending_apply"
            )
            return
        }
        pendingSecondaryTimelineApply = null
        scope.launch { pending.apply() }
    }

    private fun launchPendingAuxiliaryHydrationIfReady() {
        val pending = pendingAuxiliaryHydration ?: return
        val pendingRouteKey = pending.routePayload.resolveThreadRouteIdentity().instanceKey
        if (firstContentReadyRouteKey != pendingRouteKey) return
        val currentPayload = _state.value.payload
        if (
            !pending.staleToken.isCurrent(routeStaleToken) ||
            !routeStillCurrent(
                pending.routePayload,
                "thread_auxiliary_hydration",
                "launch",
                currentPayload
            )
        ) {
            pendingAuxiliaryHydration = null
            return
        }
        pendingAuxiliaryHydration = null
        diagnosticsLogger?.invoke(
            "thread_auxiliary_hydration result=launched " +
                "threadId=${pending.routePayload.sourceThreadId.orEmpty()} " +
                "secondary=${pending.launchSecondaryTimelineRefresh} members=${pending.launchMembersHydration}"
        )
        if (pending.launchSecondaryTimelineRefresh) {
            launchSecondaryTimelineRefresh(pending.routePayload, pending.staleToken)
        }
        if (pending.launchMembersHydration) {
            launchBackgroundMembersHydration(pending.routePayload)
        }
    }

    private fun launchReplyOriginHydrationIfNeeded(
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken
    ) {
        val currentPayload = _state.value.payload
        if (
            currentPayload.surfaceKind != RaftThreadSurfaceKind.ReplyThread ||
            currentPayload.originStatus != "loading" ||
            currentPayload.parentChannelId.isNullOrBlank() ||
            (currentPayload.parentMessageId.isNullOrBlank() && currentPayload.originParentMessageId.isNullOrBlank())
        ) {
            return
        }
        val parentChannelId = currentPayload.parentChannelId.orEmpty()
        val parentMessageId =
            currentPayload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: currentPayload.originParentMessageId.orEmpty()
        val originStatusAtStart = currentPayload.originStatus
        val originMessageAtStart = currentPayload.originMessage
        focusedOriginHydrationJob?.cancel()
        focusedOriginHydrationJob =
            scope.launch {
                val originContext = dataSource.captureReplyCacheProducerContext() ?: return@launch
                val resolution =
                    dataSource.resolveReplyOrigin(
                        currentPayload,
                        expectedProvenance = originContext.provenance
                    )
                if (
                    resolution is SharedThreadOriginResolution.Error &&
                    resolution.code == "thread_reply_cache_provenance_stale"
                ) {
                    return@launch
                }
                val latest = _state.value.payload
                if (
                    !staleTokenStillCurrent(
                        staleToken,
                        "thread_focused_origin",
                        "after_fetch",
                        routePayload,
                        latest
                    ) ||
                    !routeStillCurrent(routePayload, "thread_focused_origin", "after_fetch", latest) ||
                    !latest.acceptsFocusedOriginHydrationCompletion(
                        parentChannelId = parentChannelId,
                        parentMessageId = parentMessageId,
                        originStatusAtStart = originStatusAtStart,
                        originMessageAtStart = originMessageAtStart
                    )
                ) {
                    return@launch
                }
                val payloadBeforeCommit = _state.value.payload
                beforeFocusedOriginCommit?.invoke(payloadBeforeCommit)
                var committedPayload: RaftThreadScreenPayload? = null
                var emitted = false
                dataSource.withCurrentReplyCacheProvenance(originContext.provenance) {
                    _state.update { currentState ->
                        emitted = false
                        committedPayload = null
                        val payload = currentState.payload
                        val nextPayload =
                            if (
                                !staleTokenStillCurrent(
                                    staleToken,
                                    "thread_focused_origin",
                                    "apply",
                                    routePayload,
                                    payload
                                ) ||
                                !routeStillCurrent(routePayload, "thread_focused_origin", "apply", payload) ||
                                !payload.acceptsFocusedOriginHydrationCompletion(
                                    parentChannelId = parentChannelId,
                                    parentMessageId = parentMessageId,
                                    originStatusAtStart = originStatusAtStart,
                                    originMessageAtStart = originMessageAtStart
                                )
                            ) {
                                payload
                            } else {
                                when (resolution) {
                                    is SharedThreadOriginResolution.Loaded ->
                                        payload.copy(
                                            originMessage = resolution.message,
                                            originParentMessageId = parentMessageId,
                                            originStatus = "loaded",
                                            originErrorCode = null
                                        )
                                    SharedThreadOriginResolution.Missing ->
                                        payload.copy(
                                            originMessage = null,
                                            originParentMessageId = parentMessageId,
                                            originStatus = "missing",
                                            originErrorCode = null
                                        )
                                    is SharedThreadOriginResolution.Error ->
                                        payload.copy(
                                            originMessage = null,
                                            originParentMessageId = parentMessageId,
                                            originStatus = "error",
                                            originErrorCode = resolution.code,
                                            originHiddenTarget = resolution.hiddenTarget
                                        )
                                }
                            }
                        val nextState = currentState.copy(payload = nextPayload).withSynchronizedTimelineSlice()
                        emitted = nextState != currentState
                        if (emitted) committedPayload = nextPayload
                        nextState
                    }
                }
                if (emitted) {
                    committedPayload?.let { payload ->
                        dataSource.cachePayload(
                            payload = payload,
                            serverId = originContext.provenance.serverId,
                            ownerScopeId = originContext.provenance.threadPayloadOwnerScopeId.orEmpty(),
                            expectedProvenance = originContext.provenance
                        )
                    }
                }
                diagnosticsLogger?.invoke(
                    "thread_focused_origin_result parentChannelId=$parentChannelId " +
                        "parentMessageId=$parentMessageId result=${resolution.focusedOriginDiagnosticsResult()} " +
                        "emitted=$emitted timelineMutation=false"
                )
            }
    }

    private fun launchInitialAccessResolution(routePayload: RaftThreadScreenPayload, staleToken: MviStaleToken) =
        threadAccessResolver.launchInitialAccessResolution(routePayload, staleToken)

    /** One more refresh after a capped (superseded) one; see [SharedThreadCappedRefreshFollowUpDelayMillis]. */
    private fun launchCappedRefreshFollowUp(basePayload: RaftThreadScreenPayload, staleToken: MviStaleToken) {
        cappedRefreshFollowUpJob?.cancel()
        cappedRefreshFollowUpJob =
            scope.launch {
                delay(SharedThreadCappedRefreshFollowUpDelayMillis)
                if (
                    staleTokenStillCurrent(staleToken, "thread_refresh", "capped_follow_up", basePayload) &&
                    routeStillCurrent(basePayload, "thread_refresh", "capped_follow_up")
                ) {
                    refresh(basePayload, staleToken)
                }
            }
    }

    private suspend fun applySupersededCanonicalAccess(
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken,
        refreshProvenance: ReplyCacheProvenance,
        result: DataResult<RaftThreadScreenPayload>,
    ) = threadAccessResolver.applySupersededCanonicalAccess(routePayload, staleToken, refreshProvenance, result)

    /**
     * Hands 7e198cab: the stand-in [startRoute] held back for an anchored open, while the page still
     * shows the loading state at that anchor (nothing else landed since).
     */
    private fun anchorPendingFallbackFor(latest: RaftThreadScreenPayload): RaftThreadScreenPayload? {
        val standIn = anchorPendingStandIn ?: return null
        if (latest.hasRenderableThreadContent()) return null
        return standIn.withRouteContext(latest)
    }

    /**
     * Hands 7e198cab: an anchored open whose page still lacks the anchor shows the first canonical result
     * even when its freshness generation moved during the request (so it is fetched again): it is the
     * newest server answer this page has, and holds the message the person tapped. Only when nothing newer
     * reached the page since the request began (no message appeared that the refresh did not see at its
     * start); nothing is cached or committed, and the refetched result replaces the timeline as usual.
     */
    private suspend fun applySupersededAnchorContext(
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken,
        refreshProvenance: ReplyCacheProvenance,
        result: DataResult<RaftThreadScreenPayload>,
        refreshObservedMessageIds: Set<String>,
    ) {
        val anchor = routePayload.focusedMessageId?.takeIf { it.isNotBlank() } ?: return
        val canonical = (result as? DataResult.Content)?.data ?: return
        if (canonical.messages.none { it.id == anchor }) return
        initialAccessStateMutex.withLock {
            dataSource.withCurrentReplyCacheProvenanceForLocalSend(refreshProvenance) {
                val currentPayload = _state.value.payload
                if (
                    !staleTokenStillCurrent(staleToken, "thread_superseded_anchor", "apply", routePayload, currentPayload) ||
                    !routeStillCurrent(routePayload, "thread_superseded_anchor", "apply", currentPayload)
                ) {
                    return@withCurrentReplyCacheProvenanceForLocalSend
                }
                if (currentPayload.hasRenderableThreadContent() && !currentPayload.lacksFocusedAnchor()) {
                    return@withCurrentReplyCacheProvenanceForLocalSend
                }
                if (currentPayload.messages.any { it.id !in refreshObservedMessageIds }) {
                    diagnosticsLogger?.invoke(
                        "thread_superseded_anchor_context threadId=${routePayload.sourceThreadId.orEmpty()} " +
                            "anchor=$anchor applied=false reason=newer_on_page"
                    )
                    return@withCurrentReplyCacheProvenanceForLocalSend
                }
                val shown =
                    canonical
                        .withRouteContext(routePayload)
                        .preserveStableTimelineFrom(
                            previous = currentPayload,
                            preserveAuxiliaryProjectionFromPrevious = true,
                            refreshObservedMessageIds = refreshObservedMessageIds,
                            sendEchoMatchPolicy = ThreadSendEchoMatchPolicy.ExactRandomId,
                        )
                        .copy(backgroundRefreshing = true, durableLastKnown = false, mainLoadFailed = false)
                diagnosticsLogger?.invoke(
                    "thread_superseded_anchor_context threadId=${routePayload.sourceThreadId.orEmpty()} " +
                        "anchor=$anchor applied=true messages=${shown.messages.size}"
                )
                reducePayloadPatch { latest -> latest.withThreadContentProjectionFrom(shown) }
            }
        }
    }

    /**
     * artin 11-12: a cut Activity seed (the tapped message, with a loading indicator under it) takes its
     * full text from the first canonical result even when that result is superseded and fetched again;
     * see [withActivitySeedBodiesFrom]. Nothing is cached, and the final result replaces the timeline.
     */
    private suspend fun applySupersededActivitySeedBodies(
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken,
        refreshProvenance: ReplyCacheProvenance,
        result: DataResult<RaftThreadScreenPayload>,
    ) {
        val canonical = (result as? DataResult.Content)?.data ?: return
        initialAccessStateMutex.withLock {
            dataSource.withCurrentReplyCacheProvenanceForLocalSend(refreshProvenance) {
                val currentPayload = _state.value.payload
                if (
                    !staleTokenStillCurrent(staleToken, "thread_superseded_seed", "apply", routePayload, currentPayload) ||
                    !routeStillCurrent(routePayload, "thread_superseded_seed", "apply", currentPayload)
                ) {
                    return@withCurrentReplyCacheProvenanceForLocalSend
                }
                val filled = currentPayload.withActivitySeedBodiesFrom(canonical)
                if (filled === currentPayload) return@withCurrentReplyCacheProvenanceForLocalSend
                diagnosticsLogger?.invoke(
                    "thread_superseded_activity_seed_bodies threadId=${routePayload.sourceThreadId.orEmpty()} " +
                        "stillCut=${filled.messages.count { it.activityPreviewTruncated }}"
                )
                reduceState(_state.value.copy(payload = filled))
            }
        }
    }

    private suspend fun applyParentDirectoryHint(hint: SharedThreadIntent.ApplyParentDirectoryHint) =
        threadAccessResolver.applyParentDirectoryHint(hint)

    private suspend fun retryAccessResolution() = threadAccessResolver.retryAccessResolution()

    private fun launchBackgroundHomeContextHydration(routePayload: RaftThreadScreenPayload) {
        val staleToken = routeStaleToken
        homeContextHydrationJob?.cancel()
        homeContextHydrationJob =
            scope.launch {
                val hydratedPayload =
                    try {
                        dataSource.withHomeConversationContext(routePayload)
                            .withoutTransientLocalSendingMessages()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        return@launch
                    }
                if (hydratedPayload == routePayload) {
                    return@launch
                }
                if (!staleTokenStillCurrent(staleToken, "thread_home_context", "after_fetch", routePayload)) {
                    return@launch
                }
                val emitted =
                    reducePayloadPatch { currentPayload ->
                        if (
                            !staleTokenStillCurrent(
                                staleToken,
                                "thread_home_context",
                                "apply",
                                routePayload,
                                currentPayload
                            ) ||
                            !routeStillCurrent(routePayload, "thread_home_context", "apply", currentPayload)
                        ) {
                            return@reducePayloadPatch currentPayload
                        }
                        currentPayload.withHomeContextProjectionFrom(hydratedPayload)
                    }
                if (emitted) {
                    diagnosticsLogger?.invoke(
                        "thread_home_context_applied threadId=${routePayload.sourceThreadId.orEmpty()} " +
                            "knownMarkdownChannels=${hydratedPayload.knownMarkdownChannelNames.size} " +
                            "accessMutation=false"
                    )
                }
            }
    }

    private fun launchSecondaryTimelineRefresh(
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken,
        fetchReason: InboxFetchReason = InboxFetchReason.Initial,
    ) {
        secondaryTimelineRefreshJob?.cancel()
        secondaryTimelineRefreshJob =
            scope.launch {
                refreshSecondaryTimelineData(routePayload, staleToken, fetchReason)
            }
    }

    private fun launchBackgroundMembersHydration(routePayload: RaftThreadScreenPayload) {
        backgroundMembersHydrationJob?.cancel()
        backgroundMembersHydrationJob =
            scope.launch {
                val latest = _state.value.payload
                if (!routeStillCurrent(routePayload, "thread_members_background_load", "start", latest)) {
                    return@launch
                }
                if (!latest.hasRenderableThreadContent()) {
                    return@launch
                }
                loadMembers(force = true)
            }
    }

    private suspend fun refreshSecondaryTimelineData(
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken,
        fetchReason: InboxFetchReason = InboxFetchReason.Initial,
    ) {
        val startedAt = TimeSource.Monotonic.markNow()
        val beforeFetch = _state.value.payload
        if (!routeStillCurrent(routePayload, "thread_secondary_refresh", "start", beforeFetch)) {
            return
        }
        if (!beforeFetch.hasRenderableThreadContent()) {
            return
        }
        val producerContext = dataSource.captureReplyCacheProducerContext() ?: return
        val expectedProvenance = producerContext.provenance
        val repliesPageIdentity =
            if (beforeFetch.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
                null
            } else {
                captureThreadRepliesPageIdentity(routePayload, producerContext)
            }
        val repliesRebaselineGeneration =
            repliesPageIdentity?.let { page ->
                val generation = ++nextThreadRepliesRebaselineGeneration
                generation.takeIf { threadRepliesSyncHost.beginAuthoritativeRebaseline(page, generation) }
            }
        fun abortRebaseline() {
            if (repliesPageIdentity != null && repliesRebaselineGeneration != null) {
                threadRepliesSyncHost.abortAuthoritativeRebaseline(
                    repliesPageIdentity,
                    repliesRebaselineGeneration,
                )
            }
        }
        fun dropForOwnerChange(stage: String) {
            abortRebaseline()
            diagnosticsLogger?.invoke(
                "thread_secondary_refresh_result threadId=${routePayload.sourceThreadId.orEmpty()} " +
                    "ignored=true reason=owner_changed stage=$stage " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
        }
        if (!dataSource.isCurrentReplyCacheProvenance(expectedProvenance)) {
            dropForOwnerChange("before_summary")
            return
        }
        var replySummaries: ThreadReplySummariesSnapshot? = null
        var replySummariesObservedAt: TimeSource.Monotonic.ValueTimeMark? = null
        val followedPayload =
            if (beforeFetch.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
                // ReplyThread loads are already scoped to a single canonical stream,
                // and RealConversationRepository.getReplyThread does not attach
                // followed-channel summaries. Re-running it here only duplicates the
                // complete messages/context request after the first result is visible.
                diagnosticsLogger?.invoke(
                    "thread_secondary_followed_result threadId=${routePayload.sourceThreadId.orEmpty()} " +
                        "result=skipped:reply_thread durationMs=0"
                )
                beforeFetch
            } else {
                val followedStartedAt = TimeSource.Monotonic.markNow()
                val followedResult =
                    withInboxFetchReason(fetchReason) {
                        dataSource.fetchFollowedThreadPayloadForContext(
                            basePayload = beforeFetch,
                            expectedContext = producerContext,
                        ) { snapshot, observedAt ->
                            replySummaries = snapshot
                            replySummariesObservedAt = observedAt
                        }
                    }
                diagnosticsLogger?.invoke(
                    "thread_secondary_followed_result threadId=${routePayload.sourceThreadId.orEmpty()} " +
                        "result=${followedResult.threadDiagnosticsResult()} " +
                        "durationMs=${followedStartedAt.elapsedNow().inWholeMilliseconds}"
                )
                when (followedResult) {
                    is DataResult.Content -> followedResult.data
                    is DataResult.Empty,
                    is DataResult.Error,
                    is DataResult.NoPermission -> beforeFetch
                }
            }
        if (!dataSource.isCurrentReplyCacheProvenance(expectedProvenance)) {
            dropForOwnerChange("after_summary")
            return
        }
        val enrichedResult = dataSource.enrichSavedStatus(followedPayload, producerContext)
        if (!dataSource.isCurrentReplyCacheProvenance(expectedProvenance)) {
            dropForOwnerChange("after_saved_status")
            return
        }
        val enriched =
            when (enrichedResult) {
                is DataResult.Content -> enrichedResult.data
                is DataResult.Empty,
                is DataResult.Error,
                is DataResult.NoPermission -> {
                    dropForOwnerChange("saved_status_rejected")
                    return
                }
            }
        val latest = _state.value.payload
        if (
            !staleTokenStillCurrent(staleToken, "thread_secondary_refresh", "after_fetch", routePayload, latest) ||
            !routeStillCurrent(routePayload, "thread_secondary_refresh", "after_fetch", latest)
        ) {
            abortRebaseline()
            diagnosticsLogger?.invoke(
                "thread_secondary_refresh_result threadId=${routePayload.sourceThreadId.orEmpty()} " +
                    "ignored=true reason=stale_route durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
            return
        }
        val replyProjectedEnriched =
            if (replySummaries != null) {
                enriched
                    .withRouteContext(routePayload)
                    .withAcceptedThreadRepliesRebaseline(
                        previous = latest,
                        page = repliesPageIdentity,
                        generation = repliesRebaselineGeneration,
                        expectedProvenance = expectedProvenance,
                    )
            } else {
                abortRebaseline()
                enriched
                    .withRouteContext(routePayload)
                    .withAcceptedThreadRepliesProjection(emptyMap(), latest)
            }
        if (!dataSource.isCurrentReplyCacheProvenance(expectedProvenance)) {
            dropForOwnerChange("after_rebaseline")
            return
        }
        val patch = replyProjectedEnriched.preserveStableTimelineFrom(latest)
        var emitted = false
        var committedPayload: RaftThreadScreenPayload? = null
        fun commitState(accepted: Map<String, ThreadRepliesAcceptedProjection>): Boolean {
            val currentPayload = _state.value.payload
            if (
                !staleTokenStillCurrent(
                    staleToken,
                    "thread_secondary_refresh",
                    "apply",
                    routePayload,
                    currentPayload,
                ) ||
                !routeStillCurrent(routePayload, "thread_secondary_refresh", "apply", currentPayload)
            ) {
                return false
            }
            val commitPatch = patch.withAcceptedThreadRepliesProjection(accepted, currentPayload)
            emitted = reducePayloadPatch(cachePayload = false) { applyingPayload ->
                if (
                    !staleTokenStillCurrent(
                        staleToken,
                        "thread_secondary_refresh",
                        "apply",
                        routePayload,
                        applyingPayload
                    ) ||
                    !routeStillCurrent(routePayload, "thread_secondary_refresh", "apply", applyingPayload)
                ) {
                    return@reducePayloadPatch applyingPayload
                }
                applyingPayload.withSecondaryTimelineProjectionFrom(
                    patch = commitPatch,
                    previous = latest,
                ).let { projected ->
                    // The secondary projection merges live/baseline/patch and
                    // can overwrite a newer realtime reply-count fact with the
                    // summary snapshot's stale value. Re-apply the fact ledger
                    // (same pass as the fetch path at :3587) before committing;
                    // a null result means the provenance rotated, so the caller
                    // keeps the unprojected payload and the commit drops stale.
                    routePayload.cacheThreadId()?.let { threadId ->
                        dataSource.applyReplyCountFacts(
                            threadId = threadId,
                            expected = expectedProvenance,
                            payload = projected,
                        )
                    } ?: projected
                }
            }
            if (emitted) committedPayload = _state.value.payload
            return true
        }
        fun commitSecondary(accepted: Map<String, ThreadRepliesAcceptedProjection>): Boolean? {
            val snapshot = replySummaries
            val observedAt = replySummariesObservedAt
            val threadId = routePayload.cacheThreadId()
            return if (snapshot != null && observedAt != null && threadId != null) {
                dataSource.cacheReplyCountProjection(
                    threadId = threadId,
                    snapshot = snapshot,
                    observedAt = observedAt,
                    serverId = expectedProvenance.serverId,
                    principalId = expectedProvenance.principalId,
                    expectedProvenance = expectedProvenance,
                ) {
                    commitState(accepted)
                }
            } else {
                dataSource.withCurrentReplyCacheProvenance(expectedProvenance) {
                    commitState(accepted)
                }
            }
        }
        suspend fun applyPatch() {
            val replyIdentities =
                repliesPageIdentity?.let { page -> patch.threadRepliesScopeIdentities(page, latest) }.orEmpty()
            val committed =
                if (replyIdentities.isEmpty()) {
                    commitSecondary(emptyMap())
                } else {
                    threadRepliesSyncHost.withCurrentAcceptedProjections(replyIdentities) { accepted ->
                        commitSecondary(accepted)
                    }
                }
            if (committed != true) {
                if (committed == null) {
                    dropForOwnerChange("commit")
                } else {
                    abortRebaseline()
                }
                return
            }
            committedPayload?.let { payload ->
                dataSource.cachePayload(
                    payload = payload,
                    ownerScopeId = expectedProvenance.threadPayloadOwnerScopeId.orEmpty(),
                    expectedProvenance = expectedProvenance,
                )
            }
            diagnosticsLogger?.invoke(
                "thread_secondary_refresh_result threadId=${routePayload.sourceThreadId.orEmpty()} " +
                    "emitted=$emitted messages=${patch.messages.size} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
        }
        if (firstContentReadyRouteKey == routePayload.resolveThreadRouteIdentity().instanceKey) {
            applyPatch()
        } else {
            // First frame is not up yet (or the route moved on): stash the
            // fully-built patch instead of painting the secondary timeline.
            // launchPendingSecondaryTimelineApplyIfReady() flushes or drops it.
            pendingSecondaryTimelineApply =
                PendingSecondaryTimelineApply(
                    routePayload = routePayload,
                    staleToken = staleToken,
                    apply = { applyPatch() },
                )
            diagnosticsLogger?.invoke(
                "thread_secondary_refresh_result threadId=${routePayload.sourceThreadId.orEmpty()} " +
                    "result=pending_first_content durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
        }
    }

    /**
     * Load the current thread's members, with idempotency guards.
     *
     * Behaviour:
     * - No-op when the current source thread is missing.
     * - No-op when the bound status is [LoadMembersStatus.InFlight] for
     *   the current thread key (concurrent taps coalesce to one request).
     * - No-op when the bound status is [LoadMembersStatus.Loaded] for the
     *   current thread key, unless [force] is true.
     * - Resets to [LoadMembersStatus.Idle] when the bound key changes
     *   (switching threads) so a previous thread's "Loaded" verdict
     *   never leaks into the next thread.
     */
    private suspend fun loadMembers(force: Boolean = false) {
        val membersStartedAt = TimeSource.Monotonic.markNow()
        val existing = _state.value.payload
        val basePayload = existing
        val threadKey = existing.sourceThreadId.orEmpty()
        if (threadKey.isBlank()) return
        val currentState = _state.value
        if (currentState.membersLoadKey != threadKey) {
            // Switched threads: reset status so the previous verdict does not leak.
            _state.value = currentState.copy(
                membersLoadKey = threadKey,
                membersLoadStatus = LoadMembersStatus.Idle
            )
        }
        val resolvedStatus = _state.value.membersLoadStatus
        if (!force && resolvedStatus == LoadMembersStatus.InFlight) {
            diagnosticsLogger?.invoke(
                "thread_members_load_skip threadId=$threadKey reason=in_flight"
            )
            return
        }
        if (!force && resolvedStatus == LoadMembersStatus.Loaded) {
            diagnosticsLogger?.invoke(
                "thread_members_load_skip threadId=$threadKey reason=already_loaded"
            )
            return
        }
        diagnosticsLogger?.invoke(
            "thread_members_load_start threadId=$threadKey " +
                "existingMembers=${existing.members.size} existingCandidates=${existing.memberCandidates.size}"
        )
        _state.value = _state.value.copy(membersLoadStatus = LoadMembersStatus.InFlight)
        when (val outcome = dataSource.enrichThreadMembers(existing)) {
            is SharedThreadDataSource.MembersEnrichOutcome.Updated -> {
                if (!routeStillCurrent(basePayload, "thread_members_load", "after_fetch")) {
                    return
                }
                reducePayloadPatch(cachePayload = true) { currentPayload ->
                    if (!routeStillCurrent(basePayload, "thread_members_load", "apply", currentPayload)) {
                        return@reducePayloadPatch currentPayload
                    }
                    currentPayload.withMembersPatchFrom(outcome.payload)
                }
                _state.value = _state.value.copy(membersLoadStatus = LoadMembersStatus.Loaded)
                diagnosticsLogger?.invoke(
                    "thread_members_load_result threadId=$threadKey " +
                        "result=changed members=${outcome.payload.members.size} " +
                        "candidates=${outcome.payload.memberCandidates.size} canAddMembers=${outcome.payload.canAddMembers} " +
                        "durationMs=${membersStartedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
            is SharedThreadDataSource.MembersEnrichOutcome.Unchanged -> {
                if (!routeStillCurrent(basePayload, "thread_members_load", "after_fetch")) {
                    return
                }
                _state.value = _state.value.copy(membersLoadStatus = LoadMembersStatus.Loaded)
                diagnosticsLogger?.invoke(
                    "thread_members_load_result threadId=$threadKey " +
                        "result=unchanged members=${existing.members.size} candidates=${existing.memberCandidates.size} " +
                        "durationMs=${membersStartedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
            is SharedThreadDataSource.MembersEnrichOutcome.Failed -> {
                if (!routeStillCurrent(basePayload, "thread_members_load", "after_fetch")) {
                    return
                }
                // Transient failure: keep status as Error so the next
                // modal open (or Refresh) can retry. Do not reduce payload.
                _state.value = _state.value.copy(membersLoadStatus = LoadMembersStatus.Error)
                diagnosticsLogger?.invoke(
                    "thread_members_load_result threadId=$threadKey " +
                        "result=failed reason=${outcome.reason} " +
                        "members=${existing.members.size} candidates=${existing.memberCandidates.size} " +
                        "durationMs=${membersStartedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
        }
    }

    private suspend fun applyRealtimeEvent(
        event: RealtimeEvent,
        activeReplyParentMessageId: MessageId? = null
    ) = threadRealtimeFolding.applyRealtimeEvent(event, activeReplyParentMessageId)

    internal data class ReplyCountRealtimeGap(
        val replyCount: Int
    )

    private fun hideVisibleRead() = threadVisibleRead.hideVisibleRead()

    private suspend fun markVisibleRead(visibleTailIndex: Int? = null) = threadVisibleRead.markVisibleRead(visibleTailIndex)

    private suspend fun markThreadUnread(threadChannelId: String) = threadVisibleRead.markThreadUnread(threadChannelId)

    private suspend fun markConversationUnread() = threadVisibleRead.markConversationUnread()

    private fun endConversationUnreadHoldOnSend(payload: RaftThreadScreenPayload) =
        threadVisibleRead.endConversationUnreadHoldOnSend(payload)

    private suspend fun loadOlder() = threadPaging.loadOlder()

    /**
     * Task #14 part C: persist the anchor context page a focused refresh (anchored open, [jumpToMessage])
     * fetched. Fire-and-forget on the store scope like [ThreadPaging.launchDurableWindowExtension]; it never touches the
     * visible payload. It first waits for this entry's enter snapshot: the enter commits a whole-page
     * snapshot, which would drop a context page written before it, and the context page can only join a
     * window that exists. Persisting does not depend on the route still being shown (the facts are the
     * identity's, re-checked by the writer); the identity gates are the writer's.
     */
    /**
     * Message storage Phase 2b: write the page a refresh fetched ([page]) under the ticket taken before its request,
     * or release the ticket when the request produced none. UNDISPATCHED and NonCancellable around the release, so
     * a baseline is always closed even when the scope is already cancelled.
     */
    private fun launchEnteredPagePersist(
        ticket: ThreadDurableEnterTicket?,
        stamp: ThreadDurableRequestStamp?,
        page: ThreadDurableEnteredPage?,
        basePayload: RaftThreadScreenPayload,
        context: ReplyCacheProducerContext,
    ) {
        if (ticket == null && page == null) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    if (page == null) {
                        ticket?.let { withContext(NonCancellable) { dataSource.abandonEnteredPage(it) } }
                        return@launch
                    }
                    // The route the page belongs to: a reply thread resolved by the request names its stream now.
                    val routeIdentity =
                        basePayload.copy(sourceThreadId = page.channelId, streamChannelId = page.channelId)
                            .takeIf { basePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread }
                            ?.resolveThreadRouteIdentity()
                            ?: basePayload.resolveThreadRouteIdentity()
                    dataSource.persistEnteredPageIfCurrent(
                        ticket = ticket?.takeIf { it.channelId == page.channelId },
                        stamp = stamp ?: ticket?.stamp,
                        page = page,
                        routeIdentity = routeIdentity,
                        context = context,
                        replyParent = basePayload.durableReplyParentOrNull(),
                    )
                    // Task #14c: a reply route resolved by this request subscribes to its thread's late join acks now
                    // (start() could not name the thread).
                    if (ticket == null && basePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
                        watchThreadJoinConfirmations(routeIdentity, context, basePayload.durableReplyParentOrNull())
                    }
                    if (ticket != null && ticket.channelId != page.channelId) {
                        withContext(NonCancellable) { dataSource.abandonEnteredPage(ticket) }
                    }
                } catch (cancellation: CancellationException) {
                    ticket?.let { withContext(NonCancellable) { dataSource.abandonEnteredPage(it) } }
                    throw cancellation
                } catch (throwable: Throwable) {
                    ticket?.let { withContext(NonCancellable) { dataSource.abandonEnteredPage(it) } }
                    diagnosticsLogger?.invoke(
                        "thread_durable_entered_page_failed reason=${throwable::class.simpleName ?: "unknown"}"
                    )
                }
            }
    }

    private fun launchDurableContextPagePersist(page: SharedThreadDurableContextPage) {
        // Phase 2c: no wait for the enter page. A page that does not move the head leaves its baseline alone
        // ([CanonicalMessageV2AdjacentPageWriter]), and each write's revAtRequest was read before its own fetch.
        scope.launch {
            try {
                dataSource.persistDurableContextPageIfCurrent(page)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                diagnosticsLogger?.invoke(
                    "thread_durable_context_page_failed anchor=${page.anchorMessageId} " +
                        "reason=${throwable::class.simpleName ?: "unknown"}"
                )
            }
        }
    }

    private suspend fun fillTimelineGap(afterSeq: Long, beforeSeq: Long) = threadPaging.fillTimelineGap(afterSeq, beforeSeq)

    suspend fun loadNewer(): ThreadNewerPageLoadOutcome = threadRealtimeGapRecovery.loadNewer()

    /**
     * Replaces a focused/windowed timeline with the canonical tail in one request.
     *
     * Back-to-bottom is an explicit destination intent, not incremental pagination:
     * walking `loadNewer()` one page at a time leaves the viewport at each rendered
     * window boundary and makes progress depend on another edge/drag callback.
     */
    suspend fun jumpToLatest(): ThreadLatestJumpOutcome {
        // One request per jump: further taps while it loads (the button shows it) join nothing and
        // restart nothing; restarting would cancel the request already on the way.
        if (latestJumpInFlight) {
            diagnosticsLogger?.invoke("thread_jump_to_latest result=ignored reason=in_flight")
            return ThreadLatestJumpOutcome.AlreadyLoading
        }
        latestJumpInFlight = true
        try {
            return jumpToLatestOnce()
        } finally {
            latestJumpInFlight = false
        }
    }

    private suspend fun jumpToLatestOnce(): ThreadLatestJumpOutcome {
        val initial = _state.value.payload
        val tailRoute =
            (lastRoutePayload ?: initial.withoutThreadTimelineContent())
                .withRouteIntentFrom(initial)
                .copy(focusedMessageId = null)
                .withoutTransientLocalSendingMessages()
        lastRoutePayload = tailRoute
        if (!initial.canLoadMoreAfter) {
            reducePayloadPatch { current -> current.copy(focusedMessageId = null) }
            diagnosticsLogger?.invoke(
                "thread_jump_to_latest result=already_at_tail messages=${initial.messages.size}"
            )
            return ThreadLatestJumpOutcome.Landed
        }

        // Retire the focused-window intent before the canonical tail fetch.
        // Timeline projection deliberately does not own route-intent fields, so
        // leaving the old focus on the visible payload would preserve it when
        // the fetched tail content is committed.
        reducePayloadPatch { current -> current.copy(focusedMessageId = null) }

        // Invalidate an older focused refresh or incremental newer-page request.
        // Their stale-token checks prevent a late page from replacing the tail.
        loadJob?.cancel()
        loadJob = null
        secondaryTimelineRefreshJob?.cancel()
        secondaryTimelineRefreshJob = null
        pendingSecondaryTimelineApply = null
        val staleToken = advanceRouteStaleToken()
        diagnosticsLogger?.invoke(
            "thread_jump_to_latest result=requested visibleMessages=${initial.messages.size} " +
                "visibleMaxSeq=${initial.messages.mapNotNull { it.seq }.maxOrNull() ?: 0L}"
        )
        refresh(basePayload = tailRoute, staleToken = staleToken)

        val latest = _state.value.payload
        val reachedCanonicalTail =
            latest.resolveThreadRouteIdentity().canRetainThreadPageFor(tailRoute.resolveThreadRouteIdentity()) &&
                latest.focusedMessageId == null &&
                !latest.canLoadMoreAfter
        // Another jump, a route change or an explicit refresh took the timeline meanwhile: this
        // jump was replaced, it did not fail.
        val outcome =
            when {
                reachedCanonicalTail -> ThreadLatestJumpOutcome.Landed
                !staleToken.isCurrent(routeStaleToken) -> ThreadLatestJumpOutcome.Superseded
                else -> ThreadLatestJumpOutcome.Failed
            }
        diagnosticsLogger?.invoke(
            "thread_jump_to_latest result=${outcome.diagnosticName} " +
                "messages=${latest.messages.size} canLoadMoreAfter=${latest.canLoadMoreAfter} " +
                "errorCode=${latest.errorCode.orEmpty()}"
        )
        return outcome
    }

    private suspend fun recoverRealtimeGap(serverSeq: Long) = threadRealtimeGapRecovery.recoverRealtimeGap(serverSeq)

    private suspend fun refreshLatestAfterVisibleMaxSeq() = threadRealtimeGapRecovery.refreshLatestAfterVisibleMaxSeq()

    private suspend fun recoverRealtimeGap(
        initial: RaftThreadScreenPayload,
        initialRequest: RealtimeVisibleStreamGapRequest,
        staleToken: MviStaleToken,
    ) = threadRealtimeGapRecovery.recoverRealtimeGap(initial, initialRequest, staleToken)

    /**
     * Viewport fact reported by the thread list (task #168 step 1): `true` while the list follows
     * the latest message (bottom auto-anchor owns the viewport), `false` once the user has scrolled
     * away from it. Only the large-gap catch-up reads it, to avoid yanking a reader's position.
     * Starts `true` (a route opens at its bottom).
     */
    fun setViewportFollowsLatest(followsLatest: Boolean) {
        viewportFollowsLatest = followsLatest
    }

    private suspend fun appendSentPage(page: ThreadMessagesPage) {
        if (page.messages.isEmpty()) return
        val routePayload = _state.value.payload
        val session = dataSource.readSession()
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            if (!routeStillCurrent(routePayload, "thread_send", "append_apply", payload)) {
                return@reducePayloadPatchAndCache payload
            }
            val appended =
                appendSharedThreadSentMessageFromPage(
                    basePayload = payload,
                    page = page,
                    session = session,
                    diagnostics = diagnosticsLogger ?: strictProjectionDiagnosticsLogger,
                )
            val appendedCanonicalMessages =
                appended.messages.filter { appendedMessage ->
                    payload.messages.none { currentMessage -> currentMessage.id == appendedMessage.id }
                }
            val selected =
                appended
                    .copy(messages = appended.messages.absorbingCanonicalSendEchoes(appendedCanonicalMessages))
                    .withRouteContext(payload)
                    .preserveStableTimelineFrom(payload)
            payload.withThreadContentProjectionFrom(selected)
        }
        dataSource.currentReplyCacheProvenance()?.let { provenance ->
            applySentReplyToParentConversation(page, provenance)
        }
    }

    /**
     * #proj-mobile #120 / #wg-mobile-sync #88: a reply this page sent also moves its parent message
     * in the parent conversation (reply count, inline preview), without waiting for a
     * `thread:updated` that a joint channel may deliver only to another server's socket.
     */
    private fun applySentReplyToParentConversation(page: ThreadMessagesPage, provenance: ReplyCacheProvenance) {
        val payload = _state.value.payload
        if (payload.surfaceKind != RaftThreadSurfaceKind.ReplyThread) return
        val parent = payload.durableReplyParentOrNull() ?: return
        val threadChannelId = payload.replyThreadContentChannelId() ?: return
        val sent =
            page.messages.lastOrNull { message ->
                message.id != parent.rootMessageId && (message.seq ?: 0L) > 0L
            } ?: return
        val senderType =
            if (sent.messageType.equals("system", ignoreCase = true)) "system" else sent.senderType.trim().lowercase()
        if (senderType !in build.raft.app.data.ReplyPreviewWireSenderTypes) return
        val reply =
            ReplyPreview(
                messageId = sent.id,
                seq = sent.seq ?: return,
                content = sent.content,
                senderId = sent.senderId.trim(),
                senderType = senderType,
                senderName = sent.senderName?.trim().orEmpty(),
                createdAt = sent.createdAt?.trim().orEmpty(),
            )
        val loadedReplies =
            if (payload.canLoadMoreBefore) {
                0
            } else {
                payload.messages.count { message ->
                    message.id != parent.rootMessageId && message.deliveryState == ThreadMessageDeliverySent
                }
            }
        val replyCountAtLeast = maxOf(payload.originMessage?.replyCount ?: 0, loadedReplies, 1)
        val patched =
            dataSource.applySentReplyToParentConversation(
                provenance = provenance,
                parentScopeId = parent.parentChannelId,
                parentMessageId = parent.rootMessageId,
                threadChannelId = threadChannelId,
                reply = reply,
                replyCountAtLeast = replyCountAtLeast,
            )
        diagnosticsLogger?.invoke(
            "thread_sent_reply_parent_patch parentMessageId=${parent.rootMessageId} " +
                "parentScopeId=${parent.parentChannelId} replyCountAtLeast=$replyCountAtLeast cachePatched=$patched"
        )
    }

    internal fun admitLocalSend(
        message: RaftThreadMessageItem,
        expectedProvenance: ReplyCacheProvenance,
        expectedRoute: ThreadRouteIdentity,
        composerSeed: ThreadLocalSendComposerSeed? = null
    ): Boolean =
        dataSource.withCurrentReplyCacheProvenance(expectedProvenance) {
            val current = _state.value
            if (
                !current.canAdmitLocalSend() ||
                expectedProvenance.threadPayloadOwnerScopeId == null ||
                !current.payload.resolveThreadRouteIdentity().isPendingSendEquivalentTo(expectedRoute)
            ) {
                false
            } else {
                val prepared =
                    current.payload.copy(
                        focusedMessageId = null,
                        canLoadMoreAfter = false,
                        newerMessagesStatus = "idle"
                    )
                val withLocalSend = prepared.withLocalSendStarted(message)
                val nextPayload =
                    composerSeed?.let { seed ->
                        withLocalSend.consumeComposerSeedForSend(
                            sentBody = seed.sentBody,
                            sentAttachmentIds = seed.sentAttachmentIds
                        )
                    } ?: withLocalSend
                val durableMessage = nextPayload.messages.firstOrNull { it.id == message.id } ?: return@withCurrentReplyCacheProvenance false
                if (!dataSource.persistPendingLocalSend(expectedProvenance, expectedRoute, durableMessage)) {
                    diagnosticsLogger?.invoke(
                        "thread_send_admission_rejected reason=pending_journal_write_failed localId=${message.id}"
                    )
                    return@withCurrentReplyCacheProvenance false
                }
                val emitted = reduceState(current.copy(payload = nextPayload))
                if (!emitted) {
                    when (
                        dataSource.clearPendingLocalSend(
                            expectedProvenance,
                            expectedRoute,
                            durableMessage.id
                        )
                    ) {
                        ThreadPendingSendJournal.ConsumeResult.Failed ->
                            diagnosticsLogger?.invoke(
                                "thread_send_admission_rollback_failed localId=${durableMessage.id} " +
                                    "serverId=${expectedProvenance.serverId} route=${expectedRoute.instanceKey}"
                            )
                        ThreadPendingSendJournal.ConsumeResult.Deleted,
                        ThreadPendingSendJournal.ConsumeResult.NotFound -> Unit
                    }
                }
                if (emitted) endConversationUnreadHoldOnSend(nextPayload)
                emitted
            }
        } ?: false

    private suspend fun applyLocalSendFailure(
        messageId: String,
        errorCode: String?,
        errorMessage: String?,
        expectedProvenance: ReplyCacheProvenance,
        expectedRoute: ThreadRouteIdentity,
        randomId: String? = null,
    ) {
        // proj-mobile#66: the durable failure belongs to the send's own server/account, whatever
        // is selected now. A server switch while the request was in flight advances the auth
        // revision and rehomes the row, so a write fenced on the current selection (or on the
        // admission generation) missed it and the failure was lost.
        val visibleMessage = _state.value.payload.messages.firstOrNull { message -> message.id == messageId }
        val sendRandomId =
            randomId?.trim()?.takeIf { it.isNotBlank() }
                ?: visibleMessage?.pendingSendContext?.randomId?.trim()?.takeIf { it.isNotBlank() }
        val durableFailure =
            dataSource.markPendingLocalSendFailedForSendOwner(
                sendProvenance = expectedProvenance,
                route = expectedRoute,
                localId = messageId,
                randomId = sendRandomId,
                errorCode = errorCode,
                errorMessage = errorMessage,
            ) || readmitLocalSendFailureIntoSuccessor(
                messageId = messageId,
                errorCode = errorCode,
                errorMessage = errorMessage,
                expectedProvenance = expectedProvenance,
                expectedRoute = expectedRoute,
                visibleMessage = visibleMessage,
            )
        if (!durableFailure) {
            diagnosticsLogger?.invoke(
                "thread_send_failure_projection_blocked reason=pending_journal_write_failed localId=$messageId"
            )
            return
        }
        if (emitLocalSendFailureProjection(
                messageId = messageId,
                errorCode = errorCode,
                errorMessage = errorMessage,
                expectedProvenance = expectedProvenance,
                expectedRoute = expectedRoute,
            )
        ) {
            // The failure just landed in the journal as durable: arm the drainer so the
            // automatic retry path owns it from here (backoff + online/foreground triggers).
            pendingSendDrainer?.onSendFailed()
        }
    }

    /**
     * The auth lifecycle may have retired the send's journal partition while the request was in
     * flight (same binding, token rotation). Re-admit the still-visible local intent into the
     * verified successor owner and mark it failed there, so an explicit retry stays durable.
     * Never crosses a server or account boundary: the live identity must be a successor.
     */
    private fun readmitLocalSendFailureIntoSuccessor(
        messageId: String,
        errorCode: String?,
        errorMessage: String?,
        expectedProvenance: ReplyCacheProvenance,
        expectedRoute: ThreadRouteIdentity,
        visibleMessage: RaftThreadMessageItem?,
    ): Boolean {
        val pendingMessage = visibleMessage ?: return false
        val currentProvenance =
            dataSource.currentReplyCacheProvenanceForLocalSend(expectedProvenance) ?: return false
        if (currentProvenance == expectedProvenance) return false
        if (!_state.value.payload.resolveThreadRouteIdentity().isPendingSendEquivalentTo(expectedRoute)) return false
        val rehomed =
            dataSource.persistPendingLocalSend(
                provenance = currentProvenance,
                route = expectedRoute,
                message = pendingMessage,
            ) &&
                dataSource.markPendingLocalSendFailedForLocalSend(
                    expectedProvenance = currentProvenance,
                    route = expectedRoute,
                    localId = messageId,
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                )
        if (rehomed) {
            when (dataSource.clearPendingLocalSendForLocalSend(expectedProvenance, expectedRoute, messageId)) {
                ThreadPendingSendJournal.ConsumeResult.Failed ->
                    diagnosticsLogger?.invoke(
                        "thread_pending_send_failure_old_owner_cleanup_failed localId=$messageId " +
                            "serverId=${expectedProvenance.serverId} route=${expectedRoute.instanceKey}"
                    )
                ThreadPendingSendJournal.ConsumeResult.Deleted,
                ThreadPendingSendJournal.ConsumeResult.NotFound -> Unit
            }
        }
        return rehomed
    }

    /**
     * Hot-updates the open page's payload for a pending-send row that just turned failed, so the
     * failure marker + retry affordance render without requiring a page re-entry. Used by both the
     * live send path ([applyLocalSendFailure]) and the drain path, whose terminal/adoption failures
     * previously only wrote the journal and left the open page stale until re-entry (#1134 cell 3
     * follow-up).
     *
     * @return true when the open payload carries this route and the projection was applied.
     */
    private fun emitLocalSendFailureProjection(
        messageId: String,
        errorCode: String?,
        errorMessage: String?,
        expectedProvenance: ReplyCacheProvenance,
        expectedRoute: ThreadRouteIdentity,
    ): Boolean {
        val currentProvenance =
            dataSource.currentReplyCacheProvenanceForLocalSend(expectedProvenance) ?: return false
        val emitted =
            dataSource.withCurrentReplyCacheProvenanceForLocalSend(expectedProvenance) { _ ->
                val payload = _state.value.payload
                if (!payload.resolveThreadRouteIdentity().isPendingSendEquivalentTo(expectedRoute)) {
                    false
                } else {
                    reducePayloadPatch { current ->
                        current.withLocalSendFailure(messageId, errorCode, errorMessage)
                            .withSendRefusedAsArchived(errorCode)
                    }
                }
            } ?: false
        if (emitted) {
            dataSource.cachePayload(
                payload = _state.value.payload,
                ownerScopeId = currentProvenance.threadPayloadOwnerScopeId.orEmpty(),
                expectedProvenance = currentProvenance
            )
        }
        return emitted
    }

    private suspend fun dismissLocalFailedSend(messageId: String) {
        val provenance = dataSource.currentReplyCacheProvenance() ?: return
        val route = _state.value.payload.resolveThreadRouteIdentity()
        val cleanup = dataSource.clearPendingLocalSend(provenance, route, messageId)
        if (dataSource.hasDurablePendingSendJournal &&
            cleanup == ThreadPendingSendJournal.ConsumeResult.Failed
        ) {
            diagnosticsLogger?.invoke(
                "thread_send_dismiss_blocked reason=pending_journal_cleanup_${cleanup.name.lowercase()} " +
                    "localId=$messageId"
            )
            return
        }
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            payload.withoutLocalFailedSendMessage(messageId)
        }
    }

    private suspend fun cancelLocalSend(messageId: String) {
        val provenance = dataSource.currentReplyCacheProvenance() ?: return
        val route = _state.value.payload.resolveThreadRouteIdentity()
        val cleanup = dataSource.clearPendingLocalSend(provenance, route, messageId)
        if (dataSource.hasDurablePendingSendJournal &&
            cleanup == ThreadPendingSendJournal.ConsumeResult.Failed
        ) {
            diagnosticsLogger?.invoke(
                "thread_send_cancel_blocked reason=pending_journal_cleanup_${cleanup.name.lowercase()} " +
                    "localId=$messageId"
            )
            return
        }
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            payload.withoutLocalSendMessage(messageId)
        }
    }

    private suspend fun replaceLocalSendWithSentPage(
        messageId: String,
        page: ThreadMessagesPage,
        expectedProvenance: ReplyCacheProvenance,
        expectedRoute: ThreadRouteIdentity
    ) {
        if (page.messages.isEmpty()) return
        val routePayload = _state.value.payload
        val session = dataSource.readSession()
        // Build the replacement from the latest payload inside the reducer.
        // Two send responses and a realtime agent reply may interleave while
        // readSession/network/cache work is suspended; projecting a snapshot
        // captured before that suspension would make the last completion win
        // and temporarily hide the other canonical message until reload.
        fun applyToCurrentPayload(): Boolean =
            reducePayloadPatch { payload ->
                if (!routeStillCurrent(routePayload, "thread_send", "replace_apply", payload)) {
                    return@reducePayloadPatch payload
                }
                if (!payload.resolveThreadRouteIdentity().isPendingSendEquivalentTo(expectedRoute)) {
                    return@reducePayloadPatch payload
                }
                val appended =
                    appendSharedThreadSentMessageFromPage(
                        basePayload = payload,
                        page = page,
                        session = session,
                        trustedReplyThreadChannelId =
                            payload
                                .messages
                                .firstOrNull { it.id == messageId }
                                ?.pendingSendContext
                                ?.streamChannelId,
                        diagnostics = diagnosticsLogger ?: strictProjectionDiagnosticsLogger,
                    )
                // The HTTP success is the terminal proof for [messageId] whatever state the open
                // row is in: the drainer flips an adopted orphan to failed before resending, so the
                // row may already read "failed" here. Drop it from the stability baseline too, or
                // the failed-row retention would resurrect it beside the delivered message even
                // when the canonical response carries no random-id echo (feedback 03e9a2f6).
                val selected =
                    appended
                        .withoutLocalSendMessage(messageId)
                        .withRouteContext(payload)
                        .preserveStableTimelineFrom(payload.withoutLocalSendMessage(messageId))
                payload.withThreadContentProjectionFrom(selected)
            }
        val terminalProjection =
            dataSource.withCurrentReplyCacheProvenanceForLocalSend(expectedProvenance) { current ->
                current to applyToCurrentPayload()
            } ?: return
        val currentProvenance = terminalProjection.first
        if (terminalProjection.second) {
            val cached = dataSource.cachePayload(
                payload = _state.value.payload,
                ownerScopeId = currentProvenance.threadPayloadOwnerScopeId.orEmpty(),
                expectedProvenance = currentProvenance
            )
            if (!cached) {
                diagnosticsLogger?.invoke(
                    "thread_pending_send_success_cache_skipped localId=$messageId " +
                    "serverId=${currentProvenance.serverId} route=${expectedRoute.instanceKey}"
                )
            }
        }
        applySentReplyToParentConversation(page, currentProvenance)
        // Realtime may have consumed the row first. The durable send is still terminal after the
        // HTTP success, so journal cleanup is independent of the optional payload-cache commit
        // and of whether this page is still mounted on the original route.
        when (
            dataSource.clearPendingLocalSendForLocalSend(expectedProvenance, expectedRoute, messageId)
        ) {
            ThreadPendingSendJournal.ConsumeResult.Failed ->
                diagnosticsLogger?.invoke(
                    "thread_pending_send_success_cleanup_failed localId=$messageId " +
                        "serverId=${expectedProvenance.serverId} route=${expectedRoute.instanceKey}"
                )
            ThreadPendingSendJournal.ConsumeResult.Deleted,
            ThreadPendingSendJournal.ConsumeResult.NotFound -> Unit
        }
    }

    private fun applyMessageReactionSelection(messageId: String, emoji: String, selected: Boolean) {
        val guard = PendingReactionRealtimeGuard(
            messageId = messageId.trim(),
            emoji = emoji,
            selected = selected
        )
        if (guard.messageId.isNotBlank() && guard.emoji.isNotBlank()) {
            pendingReactionRealtimeGuards[guard.key] = guard
        }
        reducePayloadPatch { payload ->
            payload.withMessageReaction(messageId, emoji, selected)
        }
    }

    private fun applyMessageReactions(messageId: String, reactions: List<RaftThreadReactionItem>) {
        clearReactionRealtimeGuards(messageId)
        reducePayloadPatch { payload ->
            payload.withMessageReactions(messageId, reactions)
        }
    }

    private fun applyReplyThreadRead(parentMessageId: String, threadChannelId: String?) {
        reducePayloadPatch(cachePayload = true) { payload ->
            payload.withReplyThreadRead(parentMessageId, threadChannelId)
        }
    }

    private suspend fun applyMessageThreadFollowed(
        parentMessageId: String,
        threadChannelId: String?,
        followed: Boolean,
        replyCount: Int
    ) {
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            payload.withMessageThreadFollowed(parentMessageId, threadChannelId, followed, replyCount)
        }
    }

    private suspend fun applyMessageMutation(summary: MessageMutationSummary) {
        val session = dataSource.readSession()
        val currentUserId = session?.user?.id
        val currentUserName = session?.user?.bestDisplayName
        confirmReactionRealtimeGuards(summary, currentUserId)
        val currentPayload = _state.value.payload
        val canonicalPayload = currentPayload.withMessageMutationSummary(summary, currentUserId, currentUserName)
        val selected =
            dataSource
                .cacheCanonicalPayload(canonicalPayload, visiblePayloadOwnerScopeId)
                .withRouteContext(currentPayload)
                .preserveStableTimelineFrom(currentPayload, preserveSelectedReactions = false)
        reducePayloadPatch { payload ->
            if (!routeStillCurrent(currentPayload, "thread_reaction", "mutation_apply", payload)) {
                return@reducePayloadPatch payload
            }
            payload.withThreadContentProjectionFrom(selected)
        }
    }

    private fun confirmReactionRealtimeGuards(summary: MessageMutationSummary, currentUserId: String?) =
        threadRealtimeFolding.confirmReactionRealtimeGuards(summary, currentUserId)

    private fun clearReactionRealtimeGuards(messageId: String) = threadRealtimeFolding.clearReactionRealtimeGuards(messageId)

    private suspend fun applyActionCard(messageId: String, card: ThreadActionCard) {
        reducePayloadPatchAndCache(cachePayload = true) { payload -> payload.withActionCard(messageId, card) }
    }

    private suspend fun applyMessageSaved(messageId: String, saved: Boolean) {
        reducePayloadPatchAndCache(cachePayload = true) { payload -> payload.withMessageSaved(messageId, saved) }
    }

    private suspend fun applyChannelJoined(channelId: String) {
        applyLocalChannelMembershipChange(channelId = channelId, joined = true)
    }

    private suspend fun applyChannelLeft(channelId: String) {
        applyLocalChannelMembershipChange(channelId = channelId, joined = false)
    }

    private suspend fun applyLocalChannelMembershipChange(channelId: String, joined: Boolean) =
        threadAccessResolver.applyLocalChannelMembershipChange(channelId, joined)

    private suspend fun applyMembersPatch(patch: SharedThreadIntent.ApplyMembersPatch) {
        reducePayloadPatchAndCache(cachePayload = true) { payload ->
            payload.copy(
                membersCount = patch.membersCount,
                members = patch.members,
                memberCandidates = patch.memberCandidates,
                canAddMembers = patch.canAddMembers,
                addMemberStatus = patch.addMemberStatus,
                addMemberErrorCode = patch.addMemberErrorCode,
                removeMemberStatus = patch.removeMemberStatus,
                removingMemberId = patch.removingMemberId,
                removeMemberErrorCode = patch.removeMemberErrorCode
            )
        }
    }

    private fun setAddMemberStatus(status: String, errorCode: String?) {
        reducePayloadPatch {
            it.copy(addMemberStatus = status, addMemberErrorCode = errorCode)
        }
    }

    private fun setRemoveMemberStatus(status: String, removingMemberId: String?, errorCode: String?) {
        reducePayloadPatch {
            it.copy(
                removeMemberStatus = status,
                removingMemberId = removingMemberId,
                removeMemberErrorCode = errorCode
            )
        }
    }

    private fun setPagingAvailability(canLoadMoreBefore: Boolean, canLoadMoreAfter: Boolean) {
        reducePayloadPatch {
            it.copy(
                canLoadMoreBefore = canLoadMoreBefore,
                canLoadMoreAfter = canLoadMoreAfter
            )
        }
    }

    private fun selectTab(tab: String) {
        val normalizedTab = tab.takeIf { it.isNotBlank() } ?: return
        reducePayloadPatch { it.copy(selectedTab = normalizedTab) }
    }

    private fun focusMessage(
        messageId: String,
        switchToTab: String?,
        nonce: Int = nextThreadFocusedMessageRequestNonce(),
    ) {
        val normalizedMessageId = messageId.takeIf { it.isNotBlank() } ?: return
        reducePayloadPatch {
            it.copy(
                selectedTab = switchToTab?.takeIf { tab -> tab.isNotBlank() } ?: it.selectedTab,
                focusedMessageId = normalizedMessageId,
                focusedMessageRequestNonce = nonce
            )
        }
    }

    /** Parents of unread threads outside the loaded window; see [ThreadUnreadThreadParents]. */
    private val unreadThreadParentLookup =
        ThreadUnreadThreadParents(
            dataSource = dataSource,
            scope = scope,
            diagnosticsLogger = diagnosticsLogger,
            currentState = { _state.value },
            reduceState = ::reduceState,
        )

    /** The one unread-thread jump prewarm of this page (concurrency 1) and the thread it warms. */
    private var unreadThreadPrewarmJob: Job? = null
    private var unreadThreadPrewarmThreadId: String? = null

    /**
     * artin: the unread-thread pill jumped to [parentMessageId]; warm its reply thread [threadChannelId]
     * so opening it next renders at once. Reuses the Activity reply prewarm
     * ([SharedThreadDataSource.prewarmReplyThread]): one `Background` request through the shared,
     * single-flight [SharedThreadPayloadStore] (an already-warm thread is not fetched again), whose
     * result the reply page consumes on open instead of fetching ([SharedThreadPayloadStore.joinOrConsumePrewarm]).
     * Runs after the jump's own (foreground) load. At most one per page: a jump to another thread
     * replaces it, a repeat jump to the same thread while it runs is a no-op, and leaving the page
     * (store scope cancelled) stops it.
     */
    private fun prewarmUnreadThread(threadChannelId: String, parentMessageId: String) {
        val threadId = threadChannelId.trim()
        val parentId = parentMessageId.trim()
        fun log(result: String, extra: String = "") =
            diagnosticsLogger?.invoke(
                "thread_prewarm reason=unread_jump result=$result thread=${threadId.take(8)} " +
                    "parent=${parentId.take(8)}$extra"
            )
        val routeIdentity = _state.value.payload.resolveThreadRouteIdentity()
        val parentChannelId =
            routeIdentity.timelineChannelId?.takeIf { routeIdentity.surfaceKind == RaftThreadSurfaceKind.Channel }
        if (threadId.isBlank() || parentId.isBlank() || parentChannelId == null) {
            log("skipped", " reason=identity_missing")
            return
        }
        if (unreadThreadPrewarmThreadId == threadId && unreadThreadPrewarmJob?.isActive == true) {
            log("skipped", " reason=in_flight")
            return
        }
        unreadThreadPrewarmJob?.takeIf { it.isActive }?.let { previous ->
            previous.cancel()
            diagnosticsLogger?.invoke(
                "thread_prewarm reason=unread_jump result=cancelled thread=${unreadThreadPrewarmThreadId?.take(8)} " +
                    "by=${threadId.take(8)}"
            )
        }
        unreadThreadPrewarmThreadId = threadId
        log("requested", " priority=background")
        unreadThreadPrewarmJob =
            scope.launch {
                val result = dataSource.prewarmReplyThreadOnCurrentServer(parentChannelId, parentId, threadId)
                log(
                    when (result) {
                        is DataResult.Content -> "success"
                        is DataResult.Empty -> "empty"
                        is DataResult.Error -> "error"
                        is DataResult.NoPermission -> "no_permission"
                    },
                    (result as? DataResult.Error)?.error?.code?.let { " code=$it" }.orEmpty()
                )
            }
    }

    /**
     * Web ChatPanel `jumpToMessage`. A loaded target is focused in place; otherwise, as
     * [jumpToLatest] replaces the window with the tail, the route intent takes the target as its
     * focus and one refresh loads the messages around it (the focused-route context load), after
     * which the page's focus anchor scrolls to and highlights it.
     */
    private suspend fun jumpToMessage(messageId: String, switchToTab: String?, unreadThreadPill: Boolean = true) {
        val target = messageId.trim().takeIf { it.isNotBlank() } ?: return
        if (!unreadThreadPill) {
            jumpToMessageOutsidePill(target, switchToTab)
            return
        }
        // Web: a second tap while the pill is busy starts no second jump.
        if (_state.value.unreadThreadJumpLoading) {
            diagnosticsLogger?.invoke("thread_jump_to_message result=ignored reason=busy target=${target.take(8)}")
            return
        }
        // Mark this focus request as the pill's before the payload carries it, so the page sees
        // the mark by the time its focus anchor runs for this nonce (lands on the replies block).
        val nonce = nextThreadFocusedMessageRequestNonce()
        // Keep the parents of the threads loaded now, before the jump may replace the window: the
        // pill's next tap must still reach them (see toLoadedThreadParents).
        val before = _state.value
        val remembered = loadedChannelThreadParents(before.payload).filterKeys { it !in before.unreadThreadParents }
        reduceState(
            before.copy(
                unreadThreadJumpFocusNonce = nonce,
                unreadThreadParents = if (remembered.isEmpty()) before.unreadThreadParents else before.unreadThreadParents + remembered,
            )
        )
        val initial = _state.value.payload
        if (initial.messages.any { it.id == target }) {
            focusMessage(target, switchToTab, nonce)
            return
        }
        val tab = switchToTab?.takeIf { it.isNotBlank() }
        val focusRoute =
            (lastRoutePayload ?: initial.withoutThreadTimelineContent())
                .withRouteIntentFrom(initial)
                .copy(
                    selectedTab = tab ?: initial.selectedTab,
                    focusedMessageId = target,
                    focusedMessageRequestNonce = nonce,
                )
                .withoutTransientLocalSendingMessages()
        lastRoutePayload = focusRoute
        reducePayloadPatch { current ->
            current.copy(
                selectedTab = tab ?: current.selectedTab,
                focusedMessageId = target,
                focusedMessageRequestNonce = nonce,
            )
        }
        // Invalidate an older focused refresh or incremental page request, as jumpToLatest does.
        loadJob?.cancel()
        loadJob = null
        secondaryTimelineRefreshJob?.cancel()
        secondaryTimelineRefreshJob = null
        pendingSecondaryTimelineApply = null
        val staleToken = advanceRouteStaleToken()
        diagnosticsLogger?.invoke(
            "thread_jump_to_message result=requested target=${target.take(8)} visibleMessages=${initial.messages.size}"
        )
        reduceState(_state.value.copy(unreadThreadJumpLoading = true))
        try {
            adoptDurableIslandForJumpIfAvailable(focusRoute = focusRoute, staleToken = staleToken)
            refresh(basePayload = focusRoute, staleToken = staleToken)
        } finally {
            // The parent landed, or the load ended without it: the pill can be pressed again.
            if (_state.value.unreadThreadJumpLoading) reduceState(_state.value.copy(unreadThreadJumpLoading = false))
        }
        val loaded = _state.value.payload.messages.any { it.id == target }
        val superseded = !loaded && !staleToken.isCurrent(routeStaleToken)
        diagnosticsLogger?.invoke(
            "thread_jump_to_message result=${if (loaded) "applied" else if (superseded) "superseded" else "failed"} " +
                "target=${target.take(8)} messages=${_state.value.payload.messages.size}"
        )
        if (!loaded && !superseded) {
            _jumpFailures.tryEmit(ThreadJumpFailure(messageId = target, switchToTab = switchToTab, unreadThreadPill = true))
        }
    }

    /**
     * A jump that is not the unread-thread pill's (the thread timeline): a loaded target is focused
     * in place; any other is loaded around first, as the pill's jump does, but top-aligned and
     * without the pill's busy state.
     */
    private suspend fun jumpToMessageOutsidePill(target: String, switchToTab: String?) {
        val initial = _state.value.payload
        if (initial.messages.any { it.id == target }) {
            focusMessage(target, switchToTab)
            return
        }
        // The same jump tapped again while its messages load: restarting it would cancel the request
        // on the way and start over (the page shows the load at the top meanwhile). Another target
        // replaces it, as a new search match or timeline row should.
        if (messageJumpInFlightTarget == target) {
            diagnosticsLogger?.invoke("thread_outline_jump result=ignored reason=in_flight target=${target.take(8)}")
            return
        }
        val nonce = nextThreadFocusedMessageRequestNonce()
        val tab = switchToTab?.takeIf { it.isNotBlank() }
        val focusRoute =
            (lastRoutePayload ?: initial.withoutThreadTimelineContent())
                .withRouteIntentFrom(initial)
                .copy(
                    selectedTab = tab ?: initial.selectedTab,
                    focusedMessageId = target,
                    focusedMessageRequestNonce = nonce,
                )
                .withoutTransientLocalSendingMessages()
        lastRoutePayload = focusRoute
        reducePayloadPatch { current ->
            current.copy(
                selectedTab = tab ?: current.selectedTab,
                focusedMessageId = target,
                focusedMessageRequestNonce = nonce,
            )
        }
        loadJob?.cancel()
        loadJob = null
        secondaryTimelineRefreshJob?.cancel()
        secondaryTimelineRefreshJob = null
        pendingSecondaryTimelineApply = null
        val staleToken = advanceRouteStaleToken()
        diagnosticsLogger?.invoke("thread_outline_jump result=requested target=${target.take(8)} visibleMessages=${initial.messages.size}")
        messageJumpInFlightTarget = target
        try {
            adoptDurableIslandForJumpIfAvailable(focusRoute = focusRoute, staleToken = staleToken)
            refresh(basePayload = focusRoute, staleToken = staleToken)
        } finally {
            if (messageJumpInFlightTarget == target) messageJumpInFlightTarget = null
        }
        val loaded = _state.value.payload.messages.any { it.id == target }
        val superseded = !loaded && !staleToken.isCurrent(routeStaleToken)
        diagnosticsLogger?.invoke(
            "thread_outline_jump result=${if (loaded) "applied" else if (superseded) "superseded" else "failed"} target=${target.take(8)}"
        )
        if (!loaded && !superseded) {
            _jumpFailures.tryEmit(ThreadJumpFailure(messageId = target, switchToTab = switchToTab, unreadThreadPill = false))
        }
    }

    /**
     * Phase 2d: when jumping to a message outside the currently loaded window, adopt any stored
     * SQLite island containing [RaftThreadScreenPayload.focusedMessageId] immediately so online
     * jumps paint the island on Frame 1 before the context refresh lands and offline jumps succeed
     * instead of failing.
     */
    private suspend fun adoptDurableIslandForJumpIfAvailable(
        focusRoute: RaftThreadScreenPayload,
        staleToken: MviStaleToken,
    ) {
        val context = dataSource.captureReplyCacheProducerContext() ?: return
        val ownerScopeId = context.provenance.threadPayloadOwnerScopeId ?: return
        if (visiblePayloadOwnerScopeId != null && visiblePayloadOwnerScopeId != ownerScopeId) return
        val routeIdentity = focusRoute.resolveThreadRouteIdentity()
        val staleConversationCause =
            routeIdentity.timelineKey?.let { timelineKey ->
                dataSource.staleConversationCauseIfCurrent(timelineKey, context.provenance)
            }
        if (staleConversationCause != null) return
        val islandResult =
            dataSource.durableLastKnownResultIfCurrent(
                routePayload = focusRoute,
                routeIdentity = routeIdentity,
                context = context,
                allowNewestFallbackForMissingAnchor = false,
            ) ?: return
        val island = islandResult.payload
        if (island.lacksFocusedAnchor()) return
        if (!staleTokenStillCurrent(staleToken, "thread_jump", "durable_island", focusRoute)) return
        val seededReplyWindows = seedCachedReplyWindows(focusRoute, context)
        if (!staleTokenStillCurrent(staleToken, "thread_jump", "durable_island_replies", focusRoute)) return
        val withReplies = island.withSeededReplyWindows(seededReplyWindows)
        if (!dataSource.isCurrentReplyCacheProvenance(context.provenance)) return
        dataSource.seedThreadAuthorProfiles(
            serverId = context.provenance.serverId,
            payload = withReplies,
            source = "durable_jump_island",
        )
        var applied = false
        dataSource.withCurrentReplyCacheProvenance(context.provenance) {
            if (!staleTokenStillCurrent(staleToken, "thread_jump", "durable_island_apply", focusRoute)) {
                return@withCurrentReplyCacheProvenance
            }
            if (visiblePayloadOwnerScopeId != null && visiblePayloadOwnerScopeId != ownerScopeId) {
                return@withCurrentReplyCacheProvenance
            }
            reducePayloadPatch(cachePayload = false) { currentPayload ->
                if (
                    !staleTokenStillCurrent(staleToken, "thread_jump", "durable_island_apply", focusRoute, currentPayload) ||
                    !routeStillCurrent(focusRoute, "thread_jump", "durable_island_apply", currentPayload)
                ) {
                    return@reducePayloadPatch currentPayload
                }
                applied = true
                val mergedCandidate =
                    currentPayload.withContentSliceFrom(withReplies).copy(
                        channelArchived = currentPayload.channelArchived,
                        channelCanUnarchive = currentPayload.channelCanUnarchive,
                        originMessage = withReplies.originMessage ?: currentPayload.originMessage,
                        originPreview = withReplies.originPreview ?: currentPayload.originPreview,
                        originParentMessageId = withReplies.originParentMessageId ?: currentPayload.originParentMessageId,
                        originStatus =
                            if (withReplies.originMessage != null) {
                                withReplies.originStatus
                            } else {
                                currentPayload.originStatus
                            },
                        originErrorCode =
                            if (withReplies.originMessage != null) {
                                withReplies.originErrorCode
                            } else {
                                currentPayload.originErrorCode
                            },
                        focusedMessageId = focusRoute.focusedMessageId,
                        focusedMessageRequestNonce = focusRoute.focusedMessageRequestNonce,
                        selectedTab = focusRoute.selectedTab,
                        backgroundRefreshing = true,
                    )
                dataSource.restorePendingLocalSends(
                    provenance = context.provenance,
                    route = routeIdentity,
                    canonicalPayload = mergedCandidate,
                )
            }
        }
        if (applied) {
            islandResult.scope?.let { readScope ->
                scope.launch { dataSource.touchDurableUserAccess(readScope) }
            }
        }
    }

    private fun applyChannelProfile(intent: SharedThreadIntent.ApplyChannelProfile) = channelProfileUpdater.applyChannelProfile(intent)

    private suspend fun updateChannelProfile(intent: SharedThreadIntent.UpdateChannelProfile) =
        channelProfileUpdater.updateChannelProfile(intent)

    private fun clearChannelProfileUpdateStatus() = channelProfileUpdater.clearChannelProfileUpdateStatus()

    private fun reduceState(nextState: SharedThreadState): Boolean =
        reduceStateInternal(nextState, manageLoadingTimer = true)

    private fun reduceStateInternal(
        nextState: SharedThreadState,
        manageLoadingTimer: Boolean,
    ): Boolean {
        val previousState = _state.value
        val projectedPayload =
            if (nextState.payload === previousState.payload) {
                nextState.payload
            } else {
                readStateProjection.project(nextState.payload, visiblePayloadOwnerScopeId)
            }
        val payloadUpdated =
            if (projectedPayload === nextState.payload) nextState else nextState.copy(payload = projectedPayload)
        val awaitsLoadingIndicator = payloadUpdated.awaitsInitialLoadingIndicator()
        val desiredLoadingIndicatorRevealed =
            when {
                !awaitsLoadingIndicator -> false
                initialLoadingRevealDelayMillis <= 0L -> true
                else -> payloadUpdated.loadingIndicatorRevealed || isLoadingRevealElapsedForCurrentGeneration()
            }
        val normalizedState =
            if (payloadUpdated.loadingIndicatorRevealed == desiredLoadingIndicatorRevealed) {
                payloadUpdated
            } else {
                payloadUpdated.copy(loadingIndicatorRevealed = desiredLoadingIndicatorRevealed)
            }
        val synchronizedState = normalizedState.withSynchronizedTimelineSlice()
        if (manageLoadingTimer && !awaitsLoadingIndicator) {
            cancelLoadingRevealTimer()
        }
        val emitted = synchronizedState != previousState
        if (!emitted) {
            if (manageLoadingTimer && awaitsLoadingIndicator && !synchronizedState.loadingIndicatorRevealed) {
                ensureLoadingRevealTimerArmed()
            }
            return false
        }
        _state.value = synchronizedState
        if (manageLoadingTimer && awaitsLoadingIndicator && !synchronizedState.loadingIndicatorRevealed) {
            ensureLoadingRevealTimerArmed()
        }
        if (
            synchronizedState.accessResolution.refusesReferenceTarget() &&
            !previousState.accessResolution.refusesReferenceTarget()
        ) {
            // This page could not be read (refused, or gone): a reference resolved to it, locally
            // or by an earlier fetch, must ask the server again on its next tap.
            val forgotten = dataSource.forgetReferenceTargetsOf(synchronizedState.payload)
            if (forgotten > 0) {
                diagnosticsLogger?.invoke(
                    "thread_markdown_ref_forget reason=${synchronizedState.accessResolution.name} count=$forgotten"
                )
            }
        }
        return true
    }

    private suspend fun reducePayloadPatchAndCache(
        cachePayload: Boolean = false,
        patch: (RaftThreadScreenPayload) -> RaftThreadScreenPayload
    ): Boolean {
        val nextPayload = patch(_state.value.payload)
        val emitted = reduceState(_state.value.copy(payload = nextPayload))
        if (emitted && cachePayload && !_state.value.payload.durableLastKnown) {
            dataSource.cachePayload(
                _state.value.payload,
                ownerScopeId = visiblePayloadOwnerScopeId.orEmpty()
            )
        }
        return emitted
    }

    private fun advanceRouteStaleToken(): MviStaleToken {
        routeStaleTokenValue += 1L
        routeStaleToken = MviStaleToken(routeStaleTokenValue)
        return routeStaleToken
    }

    private fun staleTokenStillCurrent(
        staleToken: MviStaleToken,
        source: String,
        stage: String,
        routePayload: RaftThreadScreenPayload,
        currentPayload: RaftThreadScreenPayload = _state.value.payload
    ): Boolean {
        if (staleToken.isCurrent(routeStaleToken)) {
            return true
        }
        diagnosticsLogger?.invoke(
            "${source}_stale_drop threadId=${routePayload.sourceThreadId.orEmpty()} " +
                "currentThreadId=${currentPayload.sourceThreadId.orEmpty()} stage=$stage " +
                "reason=stale_token token=${staleToken.value} currentToken=${routeStaleToken.value}"
        )
        return false
    }

    private fun routeStillCurrent(
        routePayload: RaftThreadScreenPayload,
        source: String,
        stage: String,
        currentPayload: RaftThreadScreenPayload = _state.value.payload
    ): Boolean {
        if (currentPayload.isUsableCachedPayloadForRoute(routePayload)) {
            return true
        }
        diagnosticsLogger?.invoke(
            "${source}_stale_drop threadId=${routePayload.sourceThreadId.orEmpty()} " +
                "currentThreadId=${currentPayload.sourceThreadId.orEmpty()} stage=$stage " +
                "routeSurface=${routePayload.surfaceKind} currentSurface=${currentPayload.surfaceKind} " +
                "routeParentMessageId=${routePayload.parentMessageId.orEmpty()} " +
                "currentParentMessageId=${currentPayload.parentMessageId.orEmpty()}"
        )
        return false
    }

    /**
     * A catch-up proved this account lost the open conversation (see [isConversationAccessLost]): the
     * page leaves ([SharedThreadState.conversationAccessLost]). The payload is not re-cached; the
     * caller evicts it.
     */
    private fun markConversationAccessLost(errorCode: String) {
        val current = _state.value
        reduceState(
            current.copy(
                payload = current.payload.copy(newerMessagesStatus = "error", errorCode = errorCode),
                conversationAccessLost = true,
                channelAccessDenied = true,
            )
        )
    }

    private fun RaftThreadScreenPayload.withoutInFlightPagingLoading(): RaftThreadScreenPayload =
        if (olderMessagesStatus == "loading" || newerMessagesStatus == "loading") {
            copy(
                olderMessagesStatus = if (olderMessagesStatus == "loading") "idle" else olderMessagesStatus,
                newerMessagesStatus = if (newerMessagesStatus == "loading") "idle" else newerMessagesStatus,
            )
        } else {
            this
        }

    private fun reducePayloadPatch(
        cachePayload: Boolean = false,
        patch: (RaftThreadScreenPayload) -> RaftThreadScreenPayload
    ): Boolean {
        val nextPayload = patch(_state.value.payload)
        val emitted = reduceState(_state.value.copy(payload = nextPayload))
        if (emitted && cachePayload && !_state.value.payload.durableLastKnown) {
            dataSource.cachePayload(
                _state.value.payload,
                ownerScopeId = visiblePayloadOwnerScopeId.orEmpty()
            )
        }
        return emitted
    }
}

internal class SharedThreadViewModel(
    private val dataSource: SharedThreadDataSource,
    private val initialPayload: RaftThreadScreenPayload = RaftThreadScreenPayload(),
    private val diagnosticsLogger: ((String) -> Unit)? = null,
    // Tests inject a plain scope: the default viewModelScope needs the Kuikly pager id, which is
    // only assigned after ViewModelStore.get() returns (the #3081 crash) and never in JUnit.
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val storeDelegate =
        lazy {
            SharedThreadStore(
                dataSource = dataSource,
                scope = scopeOverride ?: viewModelScope,
                diagnosticsLogger = diagnosticsLogger,
                initialPayload = initialPayload
            )
        }
    private val store: SharedThreadStore by storeDelegate

    private var startDispatched = false

    // task #387: dispatch Start once, but never from init — viewModelScope reads the provider-
    // assigned pagerId, which is only set after the viewModel() provider returns, so touching it
    // during construction crashes cold entry. The page calls this from composition right after
    // viewModel() returns; viewModelScope's ComposeDispatcher is then immediate on the Kuikly
    // page thread, so the store's warm path (in-memory identity capture + payload cache hit)
    // still completes inline — the first frame renders the cached timeline instead of flashing
    // the loading shell for one turn. Any genuinely suspending step (durable read, network)
    // yields and the shell renders exactly as before. This is the only Start dispatch for a
    // fresh page; the page's LaunchedEffect only records markers and handles retained-route
    // intent updates.
    fun ensureStarted() {
        if (startDispatched) return
        startDispatched = true
        dispatch(SharedThreadIntent.Start(initialPayload))
    }

    val state: StateFlow<SharedThreadState>
        get() = store.state
    val currentPayload: RaftThreadScreenPayload
        get() = store.state.value.payload.withoutThreadTimelineContent()
    val currentTimelineSlice: ThreadTimelineSlice
        get() = store.state.value.timelineSlice
    val currentReplyMetadataByParentId: Map<String, ThreadReplyMetadata>
        get() = store.state.value.replyMetadataByParentId
    val currentHighlightedMentions: Set<String>
        get() = store.state.value.highlightedMentions
    val currentRenderSlice: SharedThreadRenderSlice
        get() = store.state.value.toRenderSlice()
    internal val initialProducerContext: ReplyCacheProducerContext?
        get() = store.currentInitialProducerContext()

    fun payloadFlow(): Flow<RaftThreadScreenPayload> = store.payloadFlow()

    fun renderSliceFlow(): Flow<SharedThreadRenderSlice> = store.renderSliceFlow()

    fun conversationAccessLostFlow(): Flow<Boolean> = store.conversationAccessLostFlow()

    /** Mark as Unread notices from the thread's menus (Nothing to mark), shown as a toast. */
    fun readToggleNoticesFlow(): Flow<RaftReadToggleNotice> = store.readToggleNotices

    fun timelineSliceFlow(): Flow<ThreadTimelineSlice> = store.timelineSliceFlow()

    fun replyMetadataFlow(): Flow<Map<String, ThreadReplyMetadata>> = store.replyMetadataFlow()

    fun highlightedMentionsFlow(): Flow<Set<String>> = store.highlightedMentionsFlow()

    fun unreadThreadParentsFlow(): Flow<Map<String, ThreadUnreadThreadParent>> = store.unreadThreadParentsFlow()

    fun unreadThreadJumpFocusNonceFlow(): Flow<Int> = store.unreadThreadJumpFocusNonceFlow()
    fun unreadThreadJumpLoadingFlow(): Flow<Boolean> = store.unreadThreadJumpLoadingFlow()

    fun dispatch(intent: SharedThreadIntent) {
        store.dispatch(intent)
    }

    internal fun triggerPendingSendDrain(reason: String) {
        store.triggerPendingSendDrain(reason)
    }

    fun admitLocalSend(
        message: RaftThreadMessageItem,
        expectedProvenance: ReplyCacheProvenance,
        expectedRoute: ThreadRouteIdentity,
        composerSeed: ThreadLocalSendComposerSeed? = null
    ): Boolean =
        store.admitLocalSend(
            message = message,
            expectedProvenance = expectedProvenance,
            expectedRoute = expectedRoute,
            composerSeed = composerSeed
        )

    suspend fun accept(intent: SharedThreadIntent) {
        store.accept(intent)
    }

    suspend fun loadNewerPage(): ThreadNewerPageLoadOutcome = store.loadNewer()

    suspend fun jumpToLatest(): ThreadLatestJumpOutcome = store.jumpToLatest()

    fun jumpFailuresFlow(): Flow<ThreadJumpFailure> = store.jumpFailures

    fun setViewportFollowsLatest(followsLatest: Boolean) = store.setViewportFollowsLatest(followsLatest)

    override fun onCleared() {
        if (storeDelegate.isInitialized()) {
            store.clear()
        }
        super.onCleared()
    }
}

/**
 * #proj-mobile:#265: overlay a durable last-known timeline (newest page or anchored SQLite island)
 * onto an owner-verified in-memory payload so header metadata (title/subtitle/members/tasks/
 * settingsActions), live channelArchived state, membership/send capabilities, and ReplyThread
 * originMessage are never wiped by a bare routePayload disk copy.
 */
private fun RaftThreadScreenPayload.withDurableTimelineOverlay(
    durable: RaftThreadScreenPayload,
): RaftThreadScreenPayload =
    copy(
        status = durable.status,
        backgroundRefreshing = durable.backgroundRefreshing,
        durableLastKnown = durable.durableLastKnown,
        messages = durable.messages,
        timelineGaps = durable.timelineGaps,
        canLoadMoreBefore = durable.canLoadMoreBefore,
        durableReachedOldest = durable.durableReachedOldest,
        canLoadMoreAfter = durable.canLoadMoreAfter,
        olderMessagesStatus = durable.olderMessagesStatus,
        newerMessagesStatus = durable.newerMessagesStatus,
        replySummariesAuthority = durable.replySummariesAuthority,
        mainLoadFailed = false,
        errorCode = null,
        errorServerMessage = null,
        errorTraceId = null,
        originMessage = durable.originMessage ?: originMessage,
        originPreview = durable.originPreview ?: originPreview,
        originParentMessageId = durable.originParentMessageId ?: originParentMessageId,
        originStatus =
            if (durable.originMessage != null) {
                durable.originStatus
            } else {
                originStatus
            },
        originErrorCode =
            if (durable.originMessage != null) {
                durable.originErrorCode
            } else {
                originErrorCode
            },
    ).withOriginReplyCountAtLeastLoadedReplies()
