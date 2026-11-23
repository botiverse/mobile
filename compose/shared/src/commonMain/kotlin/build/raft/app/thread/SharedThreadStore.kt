package build.raft.app

import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.data.ConversationReadOnSight
import build.raft.app.data.raftConversationReadOnSight
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.appendSharedThreadSentMessageFromPage
import build.raft.app.data.MarkUnreadOutcome
import build.raft.app.data.leftMarkedUnread
import build.raft.app.data.RaftReadToggleNotice
import build.raft.app.data.markUnreadOutcome
import build.raft.app.data.MessageMutationSummary
import build.raft.app.data.MviStaleToken
import build.raft.app.data.SharedForegroundLoadGate
import build.raft.app.data.SharedForegroundThreadKey
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.ThreadReplySummariesSnapshot
import build.raft.app.data.ThreadPendingSendDrainer
import build.raft.app.data.ThreadPendingSendJournal
import build.raft.app.data.ThreadPendingSendJournalDrainStore
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.map
import build.raft.app.data.realtime.isSyncHorizonReset
import build.raft.app.data.realtime.RealtimeVisibleStreamGapPolicy
import build.raft.app.data.realtime.RealtimeVisibleStreamGapRequest
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.ThreadReplyUpdated
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.SyncResumeResponse
import build.raft.app.network.sync.CanonicalMessageV2EnterBaseline
import build.raft.app.network.sync.CanonicalMessageV2ThreadJoinConfirmed
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurablePageDirection
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private data class PendingReactionRealtimeGuard(
    val messageId: String,
    val emoji: String,
    val selected: Boolean
)

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
private const val ThreadVisibleReadCoalesceWindowMillis = 2_000L
private const val ThreadInitialAccessLookupTimeoutMillis = 1_000L
/**
 * A reply thread whose parent write authority is still Resolving / Unknown this long after the
 * initial access pass reads the parent conversation itself (`GET /channels/:id`) instead of
 * waiting for a directory row that may never arrive (an agent's Lounge is in no list).
 */
private const val ReplyParentAuthorityFallbackDelayMillis = 4_000L
/** Upper bound of a task conversation's late initial-access lookup (after the 1 s first pass). */
private const val ThreadInitialAccessLateLookupTimeoutMillis = 15_000L

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
    // The bounded parent-authority fallback of the current route (see [launchReplyParentAuthorityFallback]).
    private var replyParentAuthorityFallbackJob: Job? = null
    // A positive parent-directory hint that arrived before the access passes it needs had run; it
    // is applied once they have (see [applyParentDirectoryHint]) instead of being lost.
    private var deferredParentDirectoryHintJob: Job? = null
    private var cappedRefreshFollowUpJob: Job? = null
    private var homeContextHydrationJob: Job? = null
    private var taskConversationTitleJob: Job? = null
    private var taskConversationTitleJobKey: String? = null
    private var backgroundMembersHydrationJob: Job? = null
    private var readInFlightMarker: ThreadVisibleReadMarker? = null
    private var pendingReadMarker: ThreadVisibleReadMarker? = null
    // Task #19: a newer marker coalesced away by the window is re-tried when the window ends.
    private var deferredReadJob: Job? = null
    private var lastCompletedReadMarker: ThreadVisibleReadMarker? = null
    private var lastCompletedReadAt: TimeMark? = null
    // fb-4440a18c (artin, 1.22.0): whether the page still shows this conversation. The page's
    // visible-read gate only covers its own dispatch; the deferred retry and the queued in-flight
    // follow-up run later, after the page may have been left, covered or backgrounded, and must not
    // mark anything read then. MarkVisibleRead opens it, [SharedThreadIntent.VisibleReadHidden] shuts it.
    private var visibleReadShown: Boolean = true
    // botiverse/slock #11687: the thread this page marked unread. Its visible reads are held while
    // the page shows it (web threadUnreadHold / autoReadHold), so the open page does not read it
    // again at once; leaving the page (a new store) or showing another thread ends the hold, and so
    // does sending in a channel or DM marked here (#16250, [endConversationUnreadHoldOnSend]).
    private var markedUnreadHoldChannelId: String? = null
    // #proj-mobile:#70: the thread whose Mark as Unread was answered 202 (admitted, result unknown). The
    // `read_state:updated` for it decides; it says so (the nothing toast) only while the hold is still on.
    private var pendingMarkUnreadChannelId: String? = null
    private val _readToggleNotices = MutableSharedFlow<RaftReadToggleNotice>(extraBufferCapacity = 4)

    /** One-shot Mark as Unread notices (Nothing to mark), shown by the page as a toast. */
    val readToggleNotices: SharedFlow<RaftReadToggleNotice> = _readToggleNotices.asSharedFlow()
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
    private val initialAccessStateMutex = Mutex()
    private val realtimeGapRecoveryMutex = Mutex()
    private val inFlightRealtimeFoldLock = SynchronizedObject()
    private val inFlightRealtimeFolds = mutableListOf<InFlightRealtimeFold>()

    /**
     * artin #110: the live message events a refresh in flight received for its route, in arrival
     * order. They are folded onto its answer when it lands (web merges its answer and the socket
     * events in one store), so an edit / delete / reaction that arrived mid-flight is not lost and
     * a generation moved only by them needs no second fetch. [complete] turns false when an event
     * could not be held (a seq gap or a sync horizon reset): the refresh then fetches again.
     */
    private class InFlightRealtimeFold(val staleToken: MviStaleToken) {
        val events = mutableListOf<InFlightRealtimeEvent>()
        val messageIds = mutableSetOf<String>()
        var complete = true
    }

    private class InFlightRealtimeEvent(
        val event: RealtimeEvent,
        val currentUserId: String?,
        val activeReplyParentMessageId: MessageId?,
    )

    private fun beginInFlightRealtimeFold(staleToken: MviStaleToken): InFlightRealtimeFold =
        InFlightRealtimeFold(staleToken).also { fold ->
            synchronized(inFlightRealtimeFoldLock) { inFlightRealtimeFolds += fold }
        }

    private fun endInFlightRealtimeFold(fold: InFlightRealtimeFold) {
        synchronized(inFlightRealtimeFoldLock) { inFlightRealtimeFolds.remove(fold) }
    }

    private fun recordInFlightRealtime(
        staleToken: MviStaleToken,
        event: RealtimeEvent?,
        messageIds: Collection<String> = emptyList(),
        currentUserId: String? = null,
        activeReplyParentMessageId: MessageId? = null,
    ) {
        synchronized(inFlightRealtimeFoldLock) {
            inFlightRealtimeFolds
                .filter { fold -> fold.staleToken == staleToken }
                .forEach { fold ->
                    if (event == null) {
                        fold.complete = false
                    } else {
                        fold.events += InFlightRealtimeEvent(event, currentUserId, activeReplyParentMessageId)
                        fold.messageIds += messageIds
                    }
                }
        }
    }

    private fun InFlightRealtimeFold.holdsAll(messageIds: Set<String>): Boolean =
        synchronized(inFlightRealtimeFoldLock) { complete && this.messageIds.containsAll(messageIds) }

    /** The held events replayed in order through the realtime reducer (idempotent by message id). */
    private fun RaftThreadScreenPayload.withInFlightRealtimeFolded(buffer: InFlightRealtimeFold): RaftThreadScreenPayload {
        val events = synchronized(inFlightRealtimeFoldLock) { buffer.events.toList() }
        return events.fold(this) { payload, held ->
            payload.applyRealtimeEvent(
                event = held.event,
                currentUserId = held.currentUserId,
                activeReplyParentMessageId = held.activeReplyParentMessageId,
                shouldPreserveReactionsForRealtime = { message, userId ->
                    shouldPreserveReactionsForRealtime(message, userId)
                },
            )
        }
    }
    private var realtimeGapRecoveryState: RealtimeGapRecoveryState? = null
    // The catch-up launched off the realtime authority lock ([launchRealtimeGapRecovery]); cancelled by [clear].
    private var realtimeGapRecoveryJob: Job? = null
    /** Test injection point: sees every catch-up job [launchRealtimeGapRecovery] starts, in launch order. */
    internal var onRealtimeGapRecoveryLaunchedForTest: ((Job) -> Unit)? = null
    private var loadNewerState: LoadNewerState? = null
    private var nextRealtimeGapRecoveryId: Long = 0L
    // Feedback 4409cbc5: a resume catch-up that fails was never asked again, so the thread kept showing its
    // cached messages until the user left. Counts retryable catch-up failures; a fresh resume bumps the generation.
    private var gapCatchUpRetryableFailures: Long = 0L
    private var resumeCatchUpGeneration: Long = 0L
    private var nextLoadNewerId: Long = 0L
    /** See [setViewportFollowsLatest]. */
    private var viewportFollowsLatest: Boolean = true
    private val pendingReactionRealtimeGuards = mutableMapOf<String, PendingReactionRealtimeGuard>()

    private data class RealtimeGapRecoveryState(
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

    private data class LoadNewerState(
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

    private data class RealtimeEventDecision(
        val source: String,
        val emitted: Boolean = false,
        val messageCount: Int = 0,
        val recoveryId: Long? = null,
        val recoveryInitial: RaftThreadScreenPayload? = null,
        val recoveryRequest: RealtimeVisibleStreamGapRequest? = null,
        val replyCountGap: ReplyCountRealtimeGap? = null,
        val dropped: Boolean = false,
        /** An id-only `channel:updated` named this page's channel: re-read it (archive state included). */
        val reloadForChannelUpdate: Boolean = false,
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
                visibleReadShown = true
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
                visibleReadShown = true
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
        advanceRouteStaleToken()
        val currentState = _state.value
        if (currentState.payload.newerMessagesStatus == "loading") {
            val patchedPayload = currentState.payload.copy(newerMessagesStatus = "idle")
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
        readInFlightMarker = null
        pendingReadMarker = null
        lastCompletedReadMarker = null
        lastCompletedReadAt = null
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
                        reduceState(
                            latest.copy(
                                accessResolution = ThreadAccessResolution.Loading,
                                canonicalAccessResolved = false,
                                provisionalCanonicalAccess = false,
                                initialHomeAccessLookupComplete = false,
                                initialHomeAccessCandidate = null
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
                existing.withRouteContext(basePayload).copy(backgroundRefreshing = true)
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
                                        if (
                                            resolvingInitialAccess &&
                                            result is DataResult.Content &&
                                            payload.surfaceKind != RaftThreadSurfaceKind.ReplyThread
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
                                                (resolvingInitialAccess && result is DataResult.Content),
                                        candidate = currentState.initialHomeAccessCandidate,
                                    )
                                    .withReadOnlyReplyAccessWhenHomeCandidateUnavailable(
                                        homeLookupComplete = currentState.initialHomeAccessLookupComplete,
                                        homeAccessCandidate = currentState.initialHomeAccessCandidate,
                                    )
                            val canonicalAccessResolved =
                                currentState.canonicalAccessResolved ||
                                    (resolvingInitialAccess && result.resolvesCanonicalConversationAccess())
                            val projectedAccessResolution =
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
                            val authorityProjectedPayload =
                                projectedPayload.withTerminalAccessWriteAuthorityClosed(projectedAccessResolution)
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
                                        writeActionsEnabled =
                                            authorityProjectedPayload.hasWriteAuthorityForAccess(
                                                accessResolution = projectedAccessResolution,
                                                homeLookupComplete = currentState.initialHomeAccessLookupComplete,
                                                homeAccessCandidate = currentState.initialHomeAccessCandidate,
                                            ),
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

    /**
     * task #243: race the lookup against an injectable deadline SIGNAL.
     *
     * `withTimeoutOrNull(1_000L)` tied the timeout branch to a real second of wall clock, which is
     * unreachable deterministically from a test - `commonTest` avoids
     * `kotlinx-coroutines-test`, so virtual time is not available here. Handing the
     * deadline in as a suspend function lets a test complete it explicitly (the same explicit-barrier
     * shape already used in SharedReplyThreadAccessIntegrationTest) while production keeps `delay`.
     *
     * The block still runs, so callers that count downstream requests keep observing them.
     */
    private suspend fun <T> raceInitialAccessLookupDeadline(block: suspend () -> T?): T? =
        coroutineScope {
            val outcome = CompletableDeferred<T?>()
            val work = launch {
                try {
                    outcome.complete(block())
                } catch (cancellation: CancellationException) {
                    // The body rethrows CancellationException on purpose (see the call site and
                    // initialConversationAccessResolution). A cancelled child does NOT fail its
                    // parent, so without this the exception would be swallowed, nothing would
                    // complete `outcome`, and the resolution would sit here until the deadline
                    // fired - turning "abandon the lookup" into "wait, then take the timeout
                    // branch" and running the whole projection with a null candidate. Hand it to
                    // the awaiter so it keeps escaping exactly as it did under withTimeoutOrNull.
                    outcome.completeExceptionally(cancellation)
                    throw cancellation
                }
            }
            val deadline = launch {
                initialAccessLookupDeadline()
                outcome.complete(null)
            }
            try {
                outcome.await()
            } finally {
                work.cancel()
                deadline.cancel()
            }
        }

    private fun launchInitialAccessResolution(
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken
    ) {
        initialAccessResolutionJob?.cancel()
        replyParentAuthorityFallbackJob?.cancel()
        replyParentAuthorityFallbackJob = null
        initialAccessResolutionJob =
            scope.launch {
                suspend fun applyResolution(
                    homeResolution: ThreadHomeContextResolution?,
                    late: Boolean,
                    lateProvenance: ReplyCacheProvenance? = null,
                ) {
                    if (homeResolution?.ownerCurrent == false) {
                        diagnosticsLogger?.invoke(
                            "thread_access_resolution_stale_drop " +
                                "threadId=${routePayload.sourceThreadId.orEmpty()} reason=owner_changed"
                        )
                        return
                    }
                    val candidate = homeResolution?.accessCandidate
                    // A late lookup only adds a positive candidate the deadline cut off; it never
                    // re-runs the terminal (no candidate) projection the timed-out pass already applied.
                    if (late && (candidate == null || !candidate.joined || !candidate.permissions.canSend)) return
                    val launchBackgroundHomeContext =
                        homeResolution?.requiresBackgroundHomeContextHydration
                            ?: (routePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread)
                    initialAccessStateMutex.withLock {
                        val currentState = _state.value
                        val currentPayload = currentState.payload
                        if (
                            !staleTokenStillCurrent(
                                staleToken,
                                "thread_access_resolution",
                                "apply",
                                routePayload,
                                currentPayload
                            ) ||
                            !routeStillCurrent(routePayload, "thread_access_resolution", "apply", currentPayload)
                        ) {
                            return@withLock
                        }
                        // The late pass re-checks everything that may have changed while the ref was
                        // in flight: the session owner, the route (above), and any terminal answer.
                        if (
                            late &&
                            (
                                lateProvenance == null ||
                                    !dataSource.isCurrentReplyCacheProvenanceForLocalSend(lateProvenance) ||
                                    currentState.accessResolution != ThreadAccessResolution.Loading ||
                                    currentState.conversationAccessLost ||
                                    currentPayload.status == "error"
                            )
                        ) {
                            return@withLock
                        }
                        val projectedPayload =
                            currentPayload
                                .let { payload ->
                                    homeResolution?.payload?.let { homePayload ->
                                        payload.withHomeContextProjectionFrom(homePayload)
                                    } ?: payload
                                }
                                .withInitialHomeConversationAccessFallbackUnlessCanonical(
                                    canonicalAccessResolved =
                                        currentState.canonicalAccessResolved || currentState.provisionalCanonicalAccess,
                                    candidate = candidate,
                                )
                                .withReadOnlyReplyAccessWhenHomeCandidateUnavailable(
                                    homeLookupComplete = true,
                                    homeAccessCandidate = candidate
                                )
                        val nextResolution =
                            when {
                                currentState.accessResolution == ThreadAccessResolution.NoPermission ->
                                    ThreadAccessResolution.NoPermission
                                currentState.accessResolution == ThreadAccessResolution.Error ->
                                    ThreadAccessResolution.Error
                                currentState.accessResolution == ThreadAccessResolution.Resolved &&
                                    currentState.canonicalAccessResolved ->
                                    ThreadAccessResolution.Resolved
                                projectedPayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
                                    currentState.canonicalAccessResolved &&
                                    (candidate != null || projectedPayload.hasReadableCanonicalReplyContent()) ->
                                    ThreadAccessResolution.Resolved
                                projectedPayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread &&
                                    currentState.canonicalAccessResolved ->
                                    ThreadAccessResolution.Error
                                currentState.canonicalAccessResolved ->
                                    ThreadAccessResolution.Resolved
                                // A superseded canonical result already attested write authority.
                                currentState.provisionalCanonicalAccess &&
                                    (currentPayload.status == "content" || currentPayload.status == "empty") ->
                                    ThreadAccessResolution.Resolved
                                // Task #1141 + reply-thread parity: a POSITIVE access candidate
                                // (joined + canSend) is the same write authority the canonical fetch
                                // would confirm — for Channel/DM it is the conversation's own row, and
                                // for a ReplyThread it is the exact parent row, which is the only write
                                // authority the reply surface ever consults
                                // (hasWriteAuthorityForAccess + withInitialHomeConversationAccessFallback).
                                // Accept it for renderable cached content just as it is accepted for
                                // "empty", so the composer mounts when the directory answers instead of
                                // after the first network refresh. For reply threads the canonical reply
                                // stream never attests parent write authority anyway, so waiting for it
                                // added latency without adding information. A negative candidate (stale
                                // unjoined row) keeps waiting for the canonical fetch, which must still
                                // win; a later canonical NoPermission still closes access.
                                candidate != null &&
                                    candidate.joined &&
                                    candidate.permissions.canSend &&
                                    currentPayload.status == "content" ->
                                    ThreadAccessResolution.Resolved
                                candidate != null && currentPayload.status == "empty" ->
                                    ThreadAccessResolution.Resolved
                                currentPayload.status == "error" ->
                                    ThreadAccessResolution.Error
                                currentPayload.status == "empty" ->
                                    ThreadAccessResolution.Error
                                else -> ThreadAccessResolution.Loading
                            }
                        val authorityProjectedPayload =
                            projectedPayload.withTerminalAccessWriteAuthorityClosed(nextResolution)
                        reduceState(
                            currentState.copy(
                                payload = authorityProjectedPayload,
                                accessResolution = nextResolution,
                                initialHomeAccessLookupComplete = true,
                                initialHomeAccessCandidate = candidate,
                                replyParentAuthority =
                                    resolveReplyParentAuthorityStatus(
                                        accessResolution = nextResolution,
                                        payload = authorityProjectedPayload,
                                        candidate = candidate,
                                    ),
                                writeActionsEnabled =
                                    authorityProjectedPayload.hasWriteAuthorityForAccess(
                                        accessResolution = nextResolution,
                                        homeLookupComplete = true,
                                        homeAccessCandidate = candidate
                                    )
                            )
                        )
                    }
                    if (
                        !late &&
                        launchBackgroundHomeContext &&
                        staleTokenStillCurrent(
                            staleToken,
                            "thread_access_home_context",
                            "launch",
                            routePayload,
                        ) &&
                        routeStillCurrent(
                            routePayload,
                            "thread_access_home_context",
                            "launch",
                        )
                    ) {
                        launchBackgroundHomeContextHydration(routePayload)
                    }
                }
                suspend fun lookup(): ThreadHomeContextResolution? =
                    try {
                        dataSource.initialConversationAccessResolution(routePayload)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        null
                    }
                if (!routePayload.isTaskConversationSurface()) {
                    applyResolution(raceInitialAccessLookupDeadline { lookup() }, late = false)
                    if (routePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
                        launchReplyParentAuthorityFallback(routePayload, staleToken)
                    }
                    return@launch
                }
                // A task conversation's lookup waits on a `GET /channels/:id` ref fetch. The deadline
                // only bounds how long the first pass waits; it must not cancel the lookup (that
                // left access Loading although the ref landed ~1 s later, and the composer waited
                // for the whole canonical load). The lookup is a child of this job, so a newer
                // resolution or a route change still cancels it.
                val lookupProvenance = dataSource.captureReplyCacheProducerContext()?.provenance
                val pendingLookup = async { lookup() }
                val homeResolution = raceInitialAccessLookupDeadline { pendingLookup.await() }
                applyResolution(homeResolution, late = false)
                if (homeResolution == null) {
                    // Bounded: a ref that has not answered by then is left to the canonical load.
                    val lateResolution =
                        withTimeoutOrNull(ThreadInitialAccessLateLookupTimeoutMillis) { pendingLookup.await() }
                    pendingLookup.cancel()
                    diagnosticsLogger?.invoke(
                        "thread_initial_task_access_late threadId=${routePayload.sourceThreadId.orEmpty()} " +
                            "candidate=${lateResolution?.accessCandidate != null}"
                    )
                    applyResolution(lateResolution, late = true, lateProvenance = lookupProvenance)
                }
            }
    }

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

    /**
     * The first canonical result of a refresh whose freshness generation moved while it was
     * fetched: the data source refetches it, so its content is not final and is NOT shown. Its
     * conversation access is server truth all the same (realtime message traffic moves the
     * generation, not the conversation's write authority), so a positive answer records canonical
     * access now instead of after the refetch rounds settle. A negative answer waits for the final
     * result, which still records itself; a later NoPermission still closes access.
     */
    private suspend fun applySupersededCanonicalAccess(
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken,
        refreshProvenance: ReplyCacheProvenance,
        result: DataResult<RaftThreadScreenPayload>,
    ) {
        val canonical = (result as? DataResult.Content)?.data ?: return
        // A reply thread's canonical stream never attests its parent's write authority.
        if (routePayload.surfaceKind == RaftThreadSurfaceKind.ReplyThread) return
        if (canonical.surfaceKind == RaftThreadSurfaceKind.ReplyThread) return
        if (!canonical.joined || !canonical.canSend) return
        initialAccessStateMutex.withLock {
            dataSource.withCurrentReplyCacheProvenanceForLocalSend(refreshProvenance) {
                val currentState = _state.value
                val currentPayload = currentState.payload
                if (
                    !staleTokenStillCurrent(staleToken, "thread_superseded_access", "apply", routePayload, currentPayload) ||
                    !routeStillCurrent(routePayload, "thread_superseded_access", "apply", currentPayload)
                ) {
                    return@withCurrentReplyCacheProvenanceForLocalSend
                }
                if (
                    currentState.canonicalAccessResolved ||
                    currentState.provisionalCanonicalAccess ||
                    currentState.conversationAccessLost ||
                    currentState.accessResolution == ThreadAccessResolution.NoPermission ||
                    currentState.accessResolution == ThreadAccessResolution.Error
                ) {
                    return@withCurrentReplyCacheProvenanceForLocalSend
                }
                val projectedPayload = currentPayload.withCanonicalConversationAccessFrom(canonical)
                // Like a positive Home candidate: it resolves the footer over renderable content; the
                // final result still records itself (canonicalAccessResolved stays false).
                val nextResolution =
                    if (currentPayload.status == "content" || currentPayload.status == "empty") {
                        ThreadAccessResolution.Resolved
                    } else {
                        currentState.accessResolution
                    }
                diagnosticsLogger?.invoke(
                    "thread_superseded_canonical_access threadId=${routePayload.sourceThreadId.orEmpty()} " +
                        "accessResolution=$nextResolution"
                )
                reduceState(
                    currentState.copy(
                        payload = projectedPayload,
                        accessResolution = nextResolution,
                        provisionalCanonicalAccess = true,
                        writeActionsEnabled =
                            projectedPayload.hasWriteAuthorityForAccess(
                                accessResolution = nextResolution,
                                homeLookupComplete = currentState.initialHomeAccessLookupComplete,
                                homeAccessCandidate = currentState.initialHomeAccessCandidate,
                            ),
                    )
                )
            }
        }
    }

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

    /**
     * Event-driven re-evaluation of reply-thread parent authority (task #228
     * successor direction A). The Home directory is the same authority source
     * the initial lookup consults; when its row for the exact parent appears
     * or changes while the thread is open, this applies it in place - no
     * retry loop, no network. Only upgrades [ReplyParentAuthorityStatus.Unknown]
     * or recomputes a candidate-derived [ReplyParentAuthorityStatus.Denied];
     * terminal NoPermission/Error and non-reply surfaces are untouched.
     * A network-authoritative hint with `present = false` attests the parent
     * channel was deleted and lands in [ReplyParentAuthorityStatus.ParentDeleted];
     * a later `present = true` hint (recreated/restored row) recomputes from there.
     */
    private suspend fun applyParentDirectoryHint(hint: SharedThreadIntent.ApplyParentDirectoryHint) {
        val state = _state.value
        val payload = state.payload
        if (payload.surfaceKind != RaftThreadSurfaceKind.ReplyThread) return
        if (state.accessResolution == ThreadAccessResolution.NoPermission ||
            state.accessResolution == ThreadAccessResolution.Error
        ) {
            return
        }
        if (!state.canonicalAccessResolved || !state.initialHomeAccessLookupComplete) {
            deferParentDirectoryHint(hint)
            return
        }
        val parentChannelId = payload.parentChannelId?.takeIf { it.isNotBlank() } ?: return
        if (parentChannelId != hint.parentChannelId) return
        val parentMessageId =
            payload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: payload.originParentMessageId?.takeIf { it.isNotBlank() }
                ?: return
        if (parentMessageId != hint.parentMessageId) return
        val routeIdentity = payload.resolveThreadRouteIdentity()
        if (routeIdentity.parentKey != "$parentChannelId:$parentMessageId") return
        if (!hint.present) {
            if (!hint.authoritative) {
                // Row absent in a non-authoritative snapshot: nothing new to
                // learn - the state stays Unknown/Resolving.
                return
            }
            initialAccessStateMutex.withLock {
                val current = _state.value
                // Re-check under the lock: a route/account switch may have
                // landed while the hint was queued on the dispatcher.
                if (current.payload.parentChannelId != parentChannelId ||
                    current.payload.sourceThreadId != payload.sourceThreadId ||
                    current.accessResolution == ThreadAccessResolution.NoPermission ||
                    current.accessResolution == ThreadAccessResolution.Error
                ) {
                    return
                }
                // A network-authoritative directory read that no longer lists
                // the parent means the channel was deleted.
                diagnosticsLogger?.invoke(
                    "thread_parent_authority_hint_applied threadId=${current.payload.sourceThreadId.orEmpty()} " +
                        "authority=${ReplyParentAuthorityStatus.ParentDeleted} present=false authoritative=true"
                )
                reduceState(
                    current.copy(
                        replyParentAuthority = ReplyParentAuthorityStatus.ParentDeleted,
                        writeActionsEnabled = false,
                    )
                )
            }
            return
        }
        if (state.replyParentAuthority == ReplyParentAuthorityStatus.Writable &&
            payload.joined == hint.joined && payload.canSend == hint.canSend
        ) {
            return
        }
        val conversationKind =
            routeIdentity.parentConversationKind ?: ConversationKind.Channel
        val candidate =
            ConversationAccessCandidate(
                ids = setOf(parentChannelId),
                conversationKind = conversationKind,
                surfaceKind = "channel",
                joined = hint.joined,
                archived = false,
                permissions =
                    ConversationPermissions(
                        canRead = true,
                        canSend = hint.canSend,
                        canAttach = hint.canSend,
                        canCreateTask = hint.canSend,
                    ),
            )
        if (!payload.acceptsInitialConversationAccessCandidate(candidate)) return
        initialAccessStateMutex.withLock {
            val current = _state.value
            // Re-check under the lock: a route/account switch may have landed
            // while the hint was queued on the dispatcher.
            if (current.payload.parentChannelId != parentChannelId ||
                current.payload.sourceThreadId != payload.sourceThreadId ||
                current.accessResolution == ThreadAccessResolution.NoPermission ||
                current.accessResolution == ThreadAccessResolution.Error
            ) {
                return
            }
            val projected =
                current.payload
                    .withInitialHomeConversationAccessFallbackUnlessCanonical(
                        canonicalAccessResolved = current.canonicalAccessResolved,
                        candidate = candidate,
                    )
                    .withTerminalAccessWriteAuthorityClosed(current.accessResolution)
            val nextAuthority =
                if (hint.joined && hint.canSend) {
                    ReplyParentAuthorityStatus.Writable
                } else {
                    ReplyParentAuthorityStatus.Denied
                }
            val nextWriteActions =
                projected.hasWriteAuthorityForAccess(
                    accessResolution = current.accessResolution,
                    homeLookupComplete = true,
                    homeAccessCandidate = candidate
                )
            diagnosticsLogger?.invoke(
                "thread_parent_authority_hint_applied threadId=${current.payload.sourceThreadId.orEmpty()} " +
                    "authority=$nextAuthority joined=${hint.joined} canSend=${hint.canSend}"
            )
            reduceState(
                current.copy(
                    payload = projected,
                    initialHomeAccessCandidate = candidate,
                    replyParentAuthority = nextAuthority,
                    writeActionsEnabled = nextWriteActions,
                )
            )
        }
    }

    /**
     * A positive parent row that arrived before the canonical reply load and the initial access
     * pass had both run used to be dropped, and the page only sends it again when the directory or
     * the known Lounges change - which for a Lounge (in no list) may be never, leaving the thread
     * on "checking permissions" for good. Keep the latest such hint and apply it once both passes
     * ran, only while they left the parent authority undetermined (a decided authority stays
     * theirs). An absent-row hint is not kept: a parent deletion is only read from a hint that
     * arrives after the passes, as before.
     */
    private fun deferParentDirectoryHint(hint: SharedThreadIntent.ApplyParentDirectoryHint) {
        if (!hint.present) return
        val payload = _state.value.payload
        if (payload.parentChannelId != hint.parentChannelId) return
        val parentMessageId =
            payload.parentMessageId?.takeIf { it.isNotBlank() }
                ?: payload.originParentMessageId?.takeIf { it.isNotBlank() }
        if (parentMessageId != hint.parentMessageId) return
        val sourceThreadId = payload.sourceThreadId
        diagnosticsLogger?.invoke(
            "thread_parent_authority_hint_deferred threadId=${sourceThreadId.orEmpty()} " +
                "joined=${hint.joined} canSend=${hint.canSend}"
        )
        deferredParentDirectoryHintJob?.cancel()
        deferredParentDirectoryHintJob =
            scope.launch {
                val ready =
                    _state.first { state ->
                        state.payload.sourceThreadId != sourceThreadId ||
                            state.payload.parentChannelId != hint.parentChannelId ||
                            state.accessResolution == ThreadAccessResolution.NoPermission ||
                            state.accessResolution == ThreadAccessResolution.Error ||
                            (state.canonicalAccessResolved && state.initialHomeAccessLookupComplete)
                    }
                if (ready.payload.sourceThreadId != sourceThreadId) return@launch
                if (!ready.replyParentAuthority.isUndeterminedReplyParentAuthority()) return@launch
                diagnosticsLogger?.invoke(
                    "thread_parent_authority_hint_replayed threadId=${sourceThreadId.orEmpty()} " +
                        "authority=${ready.replyParentAuthority}"
                )
                applyParentDirectoryHint(hint)
            }
    }

    /**
     * Bounded fallback (fb artin / tygg, 1.23): a reply thread whose parent write authority is
     * still undetermined [replyParentAuthorityFallbackDelay] after the initial access pass reads
     * the parent itself instead of waiting forever for a directory row. Runs once per access
     * pass; a decided authority, a terminal access answer or a route change ends it. The answer
     * goes through [applyParentDirectoryHint], so it obeys the same route and terminal fences
     * (and waits for the canonical reply load if that has not landed yet).
     */
    private fun launchReplyParentAuthorityFallback(
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken,
    ) {
        replyParentAuthorityFallbackJob?.cancel()
        replyParentAuthorityFallbackJob =
            scope.launch {
                replyParentAuthorityFallbackDelay()
                val threadId = routePayload.sourceThreadId.orEmpty()
                val waited = _state.value
                if (
                    !staleTokenStillCurrent(staleToken, "thread_parent_authority_fallback", "start", routePayload) ||
                    !routeStillCurrent(routePayload, "thread_parent_authority_fallback", "start", waited.payload)
                ) {
                    return@launch
                }
                // ParentDeleted too: a Lounge this client did not know yet is absent from every
                // directory snapshot, which reads as "deleted"; only a Lounge answer may lift it.
                val waitedParentDeleted = waited.replyParentAuthority == ReplyParentAuthorityStatus.ParentDeleted
                if (!waited.replyParentAuthority.isUndeterminedReplyParentAuthority() && !waitedParentDeleted) {
                    return@launch
                }
                if (
                    waited.accessResolution == ThreadAccessResolution.NoPermission ||
                    waited.accessResolution == ThreadAccessResolution.Error ||
                    waited.conversationAccessLost
                ) {
                    return@launch
                }
                val reason =
                    when (waited.replyParentAuthority) {
                        ReplyParentAuthorityStatus.Unknown -> "authority_unknown_timeout"
                        ReplyParentAuthorityStatus.ParentDeleted -> "authority_parent_deleted_timeout"
                        else -> "authority_resolving_timeout"
                    }
                diagnosticsLogger?.invoke(
                    "thread_parent_authority_fallback_start threadId=$threadId reason=$reason " +
                        "canonical=${waited.canonicalAccessResolved} lookupComplete=${waited.initialHomeAccessLookupComplete}"
                )
                val result =
                    try {
                        dataSource.replyParentAccessFallback(waited.payload)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (throwable: Throwable) {
                        ReplyParentAccessFallbackResult.Failed(
                            "exception_${throwable::class.simpleName.orEmpty()}"
                        )
                    }
                when (result) {
                    is ReplyParentAccessFallbackResult.Resolved -> {
                        val candidate = result.candidate
                        if (waitedParentDeleted && !result.lounge) {
                            // An ordinary parent keeps what its directory said.
                            diagnosticsLogger?.invoke(
                                "thread_parent_authority_fallback_result threadId=$threadId reason=$reason " +
                                    "result=ignored_not_lounge"
                            )
                            return@launch
                        }
                        diagnosticsLogger?.invoke(
                            "thread_parent_authority_fallback_result threadId=$threadId reason=$reason " +
                                "result=resolved lounge=${result.lounge} joined=${candidate.joined} " +
                                "canSend=${candidate.permissions.canSend}"
                        )
                        val parentChannelId = waited.payload.parentChannelId?.takeIf { it.isNotBlank() } ?: return@launch
                        val parentMessageId =
                            waited.payload.parentMessageId?.takeIf { it.isNotBlank() }
                                ?: waited.payload.originParentMessageId?.takeIf { it.isNotBlank() }
                                ?: return@launch
                        applyParentDirectoryHint(
                            SharedThreadIntent.ApplyParentDirectoryHint(
                                parentChannelId = parentChannelId,
                                parentMessageId = parentMessageId,
                                present = true,
                                joined = candidate.joined,
                                canSend = candidate.permissions.canSend && !candidate.archived,
                                authoritative = true,
                            )
                        )
                    }
                    is ReplyParentAccessFallbackResult.Failed ->
                        diagnosticsLogger?.invoke(
                            "thread_parent_authority_fallback_result threadId=$threadId reason=$reason " +
                                "result=failed code=${result.reason}"
                        )
                    is ReplyParentAccessFallbackResult.Unavailable ->
                        diagnosticsLogger?.invoke(
                            "thread_parent_authority_fallback_result threadId=$threadId reason=$reason " +
                                "result=unavailable code=${result.reason}"
                        )
                }
            }
    }

    private suspend fun retryAccessResolution() {
        val routePayload = lastRoutePayload ?: _state.value.payload
        initialAccessStateMutex.withLock {
            val currentState = _state.value
            if (
                currentState.accessResolution != ThreadAccessResolution.Error ||
                !currentState.canonicalAccessResolved
            ) {
                return
            }
            diagnosticsLogger?.invoke(
                "thread_access_retry_start threadId=${routePayload.sourceThreadId.orEmpty()} " +
                    "canonicalAccessResolved=${currentState.canonicalAccessResolved}"
            )
            reduceState(
                currentState.copy(
                    accessResolution = ThreadAccessResolution.Loading,
                    initialHomeAccessLookupComplete = false,
                    initialHomeAccessCandidate = null
                )
            )
        }
        launchInitialAccessResolution(routePayload, routeStaleToken)
    }

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
        staleToken: MviStaleToken
    ) {
        secondaryTimelineRefreshJob?.cancel()
        secondaryTimelineRefreshJob =
            scope.launch {
                refreshSecondaryTimelineData(routePayload, staleToken)
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
        staleToken: MviStaleToken
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
                    dataSource.fetchFollowedThreadPayloadForContext(
                        basePayload = beforeFetch,
                        expectedContext = producerContext,
                    ) { snapshot, observedAt ->
                        replySummaries = snapshot
                        replySummariesObservedAt = observedAt
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
    ) {
        if (event.isSyncHorizonReset()) {
            recordInFlightRealtime(routeStaleToken, event = null)
            // A resume had too many late messages to send (slock #10982): re-read the latest page through
            // the existing gap catch-up, as on a foreground resume.
            diagnosticsLogger?.invoke("thread_sync_horizon_reset threadId=${_state.value.payload.sourceThreadId.orEmpty()}")
            refreshLatestAfterVisibleMaxSeq()
            return
        }
        val acceptedPayload = _state.value.payload
        val eventStaleToken = routeStaleToken
        val eventRouteIdentity = acceptedPayload.realtimeGapRouteIdentity()
        val currentSession = dataSource.readSession()
        val currentUserId = currentSession?.user?.id
        if (event is PerReceiverReadStateChanged) resolvePendingMarkUnread(event, currentSession?.currentServerId)
        val readStateOwner = if (event is PerReceiverReadStateChanged) {
            dataSource.currentReplyCacheProvenance()?.threadPayloadOwnerScopeId
        } else null
        val repliesPageIdentity = captureThreadRepliesPageIdentity(acceptedPayload)
        val decision =
            realtimeGapRecoveryMutex.withLock {
                val currentPayload = _state.value.payload
                if (
                    !eventStaleToken.isCurrent(routeStaleToken) ||
                    currentPayload.realtimeGapRouteIdentity() != eventRouteIdentity ||
                    !routeStillCurrent(
                        acceptedPayload,
                        "thread_realtime_event",
                        "linearize",
                        currentPayload,
                    )
                ) {
                    return@withLock RealtimeEventDecision(
                        source = "stale",
                        messageCount = currentPayload.messages.size,
                        dropped = true,
                    )
                }
                if (event is PerReceiverReadStateChanged) {
                    val sessionServerId = currentSession?.currentServerId
                    val owner = visiblePayloadOwnerScopeId
                    if (!sessionServerId.isNullOrBlank() && !currentUserId.isNullOrBlank() &&
                        owner != null && owner == readStateOwner && (event.serverId == null || event.serverId.value == sessionServerId)) {
                        readStateProjection.observe(currentPayload, owner, event.scopes)
                        val emitted = reducePayloadPatch(cachePayload = true) { payload ->
                            readStateProjection.project(payload, owner)
                        }
                        return@withLock RealtimeEventDecision(source = "read_state", emitted = emitted,
                            messageCount = currentPayload.messages.size)
                    }
                    return@withLock RealtimeEventDecision(source = "read_state_identity_mismatch",
                        messageCount = currentPayload.messages.size, dropped = true)
                }
                if (event is RealtimeRefreshRequired) {
                    if (!currentPayload.followsChannelUpdate(event)) {
                        return@withLock RealtimeEventDecision(source = "none", messageCount = currentPayload.messages.size)
                    }
                    val archived = event.channelArchived
                        ?: return@withLock RealtimeEventDecision(
                            source = "channel_updated_reload",
                            messageCount = currentPayload.messages.size,
                            reloadForChannelUpdate = true,
                        )
                    // Held for a load in flight too: its answer may predate the archive.
                    recordInFlightRealtime(
                        staleToken = eventStaleToken,
                        event = event,
                        currentUserId = currentUserId,
                        activeReplyParentMessageId = activeReplyParentMessageId,
                    )
                    val emitted = reducePayloadPatch(cachePayload = true) { payload ->
                        payload.withChannelUpdateArchiveState(event)
                    }
                    diagnosticsLogger?.invoke(
                        "thread_channel_archive_live channelId=${event.channelId?.value.orEmpty()} " +
                            "archived=$archived emitted=$emitted surface=${currentPayload.surfaceKind}"
                    )
                    return@withLock RealtimeEventDecision(
                        source = "channel_archive",
                        emitted = emitted,
                        messageCount = currentPayload.messages.size,
                    )
                }
                dataSource.seedRealtimeThreadAuthorProfiles(
                    serverId = currentSession?.currentServerId,
                    event = event,
                    currentUserId = currentUserId
                )
                val acceptedRepliesProjection =
                    if (event is ThreadUpdated && repliesPageIdentity != null) {
                        threadRepliesSyncHost.ingestFrame(
                            page = repliesPageIdentity,
                            event = event,
                            baseline = currentPayload.inlineRepliesBaselineFor(event),
                        )
                    } else if (event is ThreadReplyUpdated && repliesPageIdentity != null) {
                        // An edited (Slack-bridged) reply: rewrite its inline row under the parent.
                        threadRepliesSyncHost.ingestReplyUpdate(
                            page = repliesPageIdentity,
                            event = event,
                            baseline =
                                currentPayload.inlineRepliesBaselineFor(event.parentMessageId, event.threadChannelId),
                        )
                    } else {
                        null
                    }
                if (event is ThreadUpdated) {
                    // Hands 4155f83a: the inline summary only moves when the replies host accepts
                    // the frame; a rejected frame was invisible in diagnostics. Ids/kinds only.
                    val envelope = event.syncCoreReplyWindow
                    diagnosticsLogger?.invoke(
                        "thread_replies_frame parentMessageId=${event.parentMessageId.value} " +
                            "accepted=${acceptedRepliesProjection != null} " +
                            "hasLatestReply=${event.latestReply != null} " +
                            "page=${repliesPageIdentity?.let { "${it.serverId}:${it.parentScopeKind}:${it.parentScopeId}" }.orEmpty()} " +
                            "envelope=${envelope?.let { "${it.parentScopeServerId}:${it.parentScopeKind}:${it.parentScopeId}" }.orEmpty()}"
                    )
                }
                if (event is ThreadReplyUpdated && acceptedRepliesProjection != null) {
                    dataSource.recordReplyWindows(listOf(acceptedRepliesProjection))
                }
                if (event is ThreadUpdated && acceptedRepliesProjection != null) {
                    dataSource.recordReplyWindows(listOf(acceptedRepliesProjection))
                    event.latestReply?.let { reply ->
                        dataSource.seedAcceptedRealtimeThreadReplyAuthorProfile(
                            serverId = currentSession?.currentServerId,
                            reply = reply
                        )
                    }
                }
                if (event is ThreadUpdated) {
                    readStateProjection.contentChanged(event.threadChannelId.value, event.latestReply?.seq)
                    currentPayload.cacheThreadId()?.let { factThreadId ->
                        dataSource.recordReplyCountRealtimeFact(
                            threadId = factThreadId,
                            parentMessageId = event.parentMessageId.value,
                            fact =
                                SharedThreadReplyCountFact(
                                    threadChannelId = event.threadChannelId.value,
                                    replyCount = event.replyCount,
                                    observedAt = TimeSource.Monotonic.markNow()
                                )
                        )
                    }
                    // Hands bc6e392e: a parent in another conversation (a DM reply arriving while a
                    // thread is open) also lands on that conversation, not only on this page.
                    val openPageThreadId = currentPayload.cacheThreadId()
                    dataSource.applyThreadUpdatedToParentConversation(event, openPageThreadId)?.let { patched ->
                        diagnosticsLogger?.invoke(
                            "thread_reply_parent_scope parentMessageId=${event.parentMessageId.value} " +
                                "parentScopeId=${event.syncCoreReplyWindow?.parentScopeId.orEmpty()} " +
                                "openPage=${openPageThreadId.orEmpty()} cachePatched=$patched"
                        )
                    }
                    dataSource.rememberReplyParentReplyCount(
                        parentChannelId = currentPayload.threadUpdatedParentChannelId(event),
                        parentMessageId = event.parentMessageId.value,
                        replyCount = event.replyCount
                    )
                }
                val storeEvent =
                    if (event is ThreadUpdated) event.copy(latestReply = null) else event
                recordInFlightRealtime(
                    staleToken = eventStaleToken,
                    event = storeEvent,
                    messageIds =
                        when (event) {
                            is MessageNew -> listOf(event.message.id.value)
                            is MessageUpdated -> listOf(event.message.id.value)
                            is SyncResumeResponse -> event.messages.map { message -> message.id.value }
                            is ThreadUpdated -> listOfNotNull(event.latestReply?.messageId)
                            else -> emptyList()
                        },
                    currentUserId = currentUserId,
                    activeReplyParentMessageId = activeReplyParentMessageId,
                )
                val eventIdentity =
                    when (event) {
                        is MessageNew -> {
                            val message = event.message
                            "type=MessageNew messageId=${message.id.value} " +
                                "threadId=${message.threadId.value} channelId=${message.channelId?.value.orEmpty()} " +
                                "threadChannelId=${message.threadChannelId?.value.orEmpty()} " +
                                "matches=${message.matchesSharedThread(currentPayload, activeReplyParentMessageId)}"
                        }
                        is ThreadUpdated -> {
                            val parentId = event.parentMessageId.value
                            "type=ThreadUpdated parentMessageId=$parentId " +
                                "threadMatch=${currentPayload.messages.any { it.id == parentId } || currentPayload.originMessage?.id == parentId}"
                        }
                        else -> "type=${event::class.simpleName.orEmpty()}"
                    }
                diagnosticsLogger?.invoke(
                    "thread_realtime_identity threadId=${currentPayload.sourceThreadId.orEmpty()} " +
                        "cacheThreadId=${currentPayload.cacheThreadId().orEmpty()} " +
                        "originParentMessageId=${currentPayload.originParentMessageId.orEmpty()} " +
                        "messagesBefore=${currentPayload.messages.size} $eventIdentity"
                )
                val realtimeGapServerSeq =
                    currentPayload.realtimeNewerGapServerSeq(
                        event = event,
                        activeReplyParentMessageId = activeReplyParentMessageId,
                    )
                if (realtimeGapServerSeq != null) {
                    // Messages are missing before this one: an answer in flight cannot be completed
                    // from the held events alone.
                    recordInFlightRealtime(eventStaleToken, event = null)
                    val matchingRecovery =
                        realtimeGapRecoveryState?.takeIf { state ->
                            state.staleToken == eventStaleToken && state.routeIdentity == eventRouteIdentity
                        }
                    val matchingPagination =
                        loadNewerState?.takeIf { state ->
                            state.staleToken == eventStaleToken && state.routeIdentity == eventRouteIdentity
                        }
                    if (matchingRecovery != null || matchingPagination != null) {
                        val missingThroughSeq = (realtimeGapServerSeq - 1L).coerceAtLeast(0L)
                        if (matchingRecovery != null && missingThroughSeq > matchingRecovery.targetSeq) {
                            matchingRecovery.targetSeq = missingThroughSeq
                            matchingRecovery.targetVersion += 1L
                        }
                        if (
                            matchingPagination != null &&
                            missingThroughSeq > (matchingPagination.pendingTargetSeq ?: 0L)
                        ) {
                            matchingPagination.pendingTargetSeq = missingThroughSeq
                        }
                        val merged =
                            currentPayload
                                .copy(canLoadMoreAfter = false)
                                .applyRealtimeEvent(
                                    event = storeEvent,
                                    currentUserId = currentUserId,
                                    activeReplyParentMessageId = activeReplyParentMessageId,
                                    shouldPreserveReactionsForRealtime = { message, userId ->
                                        shouldPreserveReactionsForRealtime(message, userId)
                                    },
                                )
                                .withAcceptedThreadRepliesProjection(acceptedRepliesProjection)
                                .preserveStableTimelineFrom(
                                    previous = currentPayload,
                                    preserveSelectedReactions = false,
                                ).copy(canLoadMoreAfter = currentPayload.canLoadMoreAfter)
                        fun commitGapMerged(accepted: Map<String, ThreadRepliesAcceptedProjection>): Boolean {
                            val commitMerged =
                                merged.withAcceptedThreadRepliesProjection(
                                    acceptedRepliesProjection?.let { projection ->
                                        accepted[projection.identity.parentMessageId]
                                    },
                                )
                            return reducePayloadPatch(cachePayload = !currentPayload.durableLastKnown) { latestPayload ->
                                latestPayload.withThreadContentProjectionFrom(commitMerged)
                            }
                        }
                        val emitted =
                            if (acceptedRepliesProjection == null) {
                                commitGapMerged(emptyMap())
                            } else {
                                threadRepliesSyncHost.withCurrentAcceptedProjections(
                                    listOf(acceptedRepliesProjection.identity),
                                ) { accepted -> commitGapMerged(accepted) }
                            }
                        return@withLock RealtimeEventDecision(
                            source = "in_flight_gap",
                            emitted = emitted,
                            messageCount = merged.messages.size,
                        )
                    }
                    val streamChannelId =
                        currentPayload.sourceThreadId
                            ?.takeIf { it.isNotBlank() }
                            ?.let(::ChannelId)
                            ?: currentPayload.streamChannelId
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::ChannelId)
                            ?: return@withLock RealtimeEventDecision(
                                source = "none",
                                messageCount = currentPayload.messages.size,
                            )
                    val recoveryId = ++nextRealtimeGapRecoveryId
                    realtimeGapRecoveryState =
                        RealtimeGapRecoveryState(
                            id = recoveryId,
                            routeIdentity = eventRouteIdentity,
                            staleToken = eventStaleToken,
                            targetSeq = realtimeGapServerSeq,
                            targetVersion = 1L,
                        )
                    reducePayloadPatch(cachePayload = !currentPayload.durableLastKnown) { latestPayload ->
                        latestPayload.copy(newerMessagesStatus = "loading", errorCode = null)
                    }
                    return@withLock RealtimeEventDecision(
                        source = "gap",
                        messageCount = currentPayload.messages.size,
                        recoveryId = recoveryId,
                        recoveryInitial = currentPayload,
                        recoveryRequest =
                            RealtimeVisibleStreamGapRequest(
                                channelId = streamChannelId,
                                sinceSeq = currentPayload.messages.mapNotNull { it.seq }.maxOrNull() ?: 0L,
                                serverSeq = realtimeGapServerSeq,
                            ),
                    )
                }

                val merged =
                    currentPayload.applyRealtimeEvent(
                        event = storeEvent,
                        currentUserId = currentUserId,
                        activeReplyParentMessageId = activeReplyParentMessageId,
                        shouldPreserveReactionsForRealtime = { message, userId ->
                            shouldPreserveReactionsForRealtime(message, userId)
                        },
                    ).withAcceptedThreadRepliesProjection(acceptedRepliesProjection)
                val replyCountGap =
                    merged.realtimeReplyCountGap(
                        event = event,
                        previous = currentPayload,
                        activeReplyParentMessageId = activeReplyParentMessageId,
                    )
                if (replyCountGap != null) recordInFlightRealtime(eventStaleToken, event = null)
                diagnosticsLogger?.invoke(
                    "thread_realtime_fold threadId=${currentPayload.sourceThreadId.orEmpty()} " +
                        "messagesBefore=${currentPayload.messages.size} messagesAfter=${merged.messages.size} " +
                        "samePayload=${merged === currentPayload} replyCountGap=${replyCountGap != null}"
                )
                if (merged === currentPayload) {
                    return@withLock RealtimeEventDecision(
                        source = "none",
                        messageCount = currentPayload.messages.size,
                        replyCountGap = replyCountGap,
                    )
                }
                val stableSelected =
                    merged.preserveStableTimelineFrom(
                        previous = currentPayload,
                        preserveSelectedReactions = false,
                    )
                // task #562 (agreed with KMP-专家/HanXin, adjusting #231's A'): the
                // first-frame candidate is a read-only SNAPSHOT of the live store's
                // merged state — a single merge algorithm, written through after the
                // reduce. Every open still forces a refresh, so any snapshot drift
                // lives only in the first-frame window and is corrected by the
                // canonical fetch through this same reducer. Raw events are never
                // written; durable local-store never rides this path.
                fun commitStableSelected(accepted: Map<String, ThreadRepliesAcceptedProjection>): Boolean {
                    val commitSelected =
                        stableSelected.withAcceptedThreadRepliesProjection(
                            acceptedRepliesProjection?.let { projection ->
                                accepted[projection.identity.parentMessageId]
                            },
                        )
                    return reducePayloadPatch(cachePayload = !currentPayload.durableLastKnown) { latestPayload ->
                        latestPayload.withThreadContentProjectionFrom(commitSelected)
                    }
                }
                val emitted =
                    if (acceptedRepliesProjection == null) {
                        commitStableSelected(emptyMap())
                    } else {
                        threadRepliesSyncHost.withCurrentAcceptedProjections(
                            listOf(acceptedRepliesProjection.identity),
                        ) { accepted -> commitStableSelected(accepted) }
                    }
                RealtimeEventDecision(
                    source = "direct_latest",
                    emitted = emitted,
                    messageCount = stableSelected.messages.size,
                    replyCountGap = replyCountGap,
                )
            }

        if (decision.dropped) return
        if (decision.reloadForChannelUpdate) {
            diagnosticsLogger?.invoke(
                "thread_channel_updated_reload threadId=${acceptedPayload.sourceThreadId.orEmpty()} reason=id_only_frame"
            )
            dispatch(SharedThreadIntent.Refresh)
            return
        }
        val recoveryId = decision.recoveryId
        val recoveryInitial = decision.recoveryInitial
        val recoveryRequest = decision.recoveryRequest
        if (recoveryId != null && recoveryInitial != null && recoveryRequest != null) {
            diagnosticsLogger?.invoke(
                "thread_realtime_gap_detected threadId=${recoveryInitial.sourceThreadId.orEmpty()} " +
                    "visibleMaxSeq=${recoveryRequest.sinceSeq} serverSeq=${recoveryRequest.serverSeq}"
            )
            // The realtime sink calls this under the process-wide realtime authority lock: the gap is
            // registered above (so later live events join it through `in_flight_gap`), and the catch-up
            // fetch runs on the store scope (Hands bc6e392e: an inline fetch held the lock ~6 s).
            launchRealtimeGapRecovery(
                initial = recoveryInitial,
                initialRequest = recoveryRequest,
                staleToken = eventStaleToken,
                recoveryId = recoveryId,
                requestSnapshot = recoveryInitial,
            )
            return
        }
        if (decision.source == "none" && decision.replyCountGap == null) {
            diagnosticsLogger?.invoke(
                "thread_realtime_apply_result threadId=${acceptedPayload.sourceThreadId.orEmpty()} " +
                    "source=none emitted=false messages=${decision.messageCount} reason=no_projection"
            )
            return
        }
        diagnosticsLogger?.invoke(
            "thread_realtime_apply_result threadId=${acceptedPayload.sourceThreadId.orEmpty()} " +
                "source=${decision.source} emitted=${decision.emitted} " +
                "messages=${decision.messageCount} cacheWarmed=${decision.emitted}"
        )
        val replyCountGap = decision.replyCountGap
        if (replyCountGap != null) {
            diagnosticsLogger?.invoke(
                "thread_realtime_reply_count_gap_detected threadId=${_state.value.payload.sourceThreadId.orEmpty()} " +
                    "visibleMaxSeq=${_state.value.payload.messages.mapNotNull { it.seq }.maxOrNull() ?: 0L} " +
                    "loadedReplies=${_state.value.payload.loadedReplyTimelineCount()} " +
                    "replyCount=${replyCountGap.replyCount}"
            )
            recoverRealtimeGapFromVisibleTail(replyCountGap)
        }
    }

    private data class ReplyCountRealtimeGap(
        val replyCount: Int
    )

    private fun RaftThreadScreenPayload.realtimeReplyCountGap(
        event: RealtimeEvent,
        previous: RaftThreadScreenPayload,
        activeReplyParentMessageId: MessageId? = null
    ): ReplyCountRealtimeGap? {
        if (event !is ThreadUpdated) return null
        if (surfaceKind != RaftThreadSurfaceKind.ReplyThread) return null
        if (canLoadMoreAfter || newerMessagesStatus == "loading") return null
        val visibleMaxSeq = messages.mapNotNull { it.seq }.maxOrNull() ?: 0L
        if (visibleMaxSeq <= 0L) return null
        val replyThreadContentChannelId = replyThreadContentChannelId() ?: return null
        val originParentId = originParentMessageId?.takeIf { it.isNotBlank() }?.let(::MessageId)
        val matchesThread =
            event.threadChannelId.value == replyThreadContentChannelId &&
                (
                    originParentId == null ||
                        event.parentMessageId == originParentId ||
                        event.parentMessageId == activeReplyParentMessageId
                    )
        if (!matchesThread) return null
        val loadedReplies = loadedReplyTimelineCount()
        val previousLoadedReplies = previous.loadedReplyTimelineCount()
        val loadedReplyDelta = (loadedReplies - previousLoadedReplies).coerceAtLeast(0)
        val previousReplyCount =
            previous.knownReplyCountFor(event, activeReplyParentMessageId)
                ?: previousLoadedReplies
        val replyDelta = event.replyCount - previousReplyCount
        if (replyDelta <= 0 || loadedReplyDelta >= replyDelta) return null
        return ReplyCountRealtimeGap(replyCount = event.replyCount)
    }

    /** fb-4440a18c: the page stopped showing this conversation; nothing it queued may be marked now. */
    private fun hideVisibleRead() {
        visibleReadShown = false
        readOnSight.hide(this)
        val hadDeferred = deferredReadJob?.isActive == true
        deferredReadJob?.cancel()
        deferredReadJob = null
        val dropped = pendingReadMarker
        pendingReadMarker = null
        if (hadDeferred || dropped != null) {
            diagnosticsLogger?.invoke(
                "thread_read_skip channelId=${dropped?.channelId.orEmpty()} reason=not_shown " +
                    "deferred=$hadDeferred pending=${dropped?.latestMessageId.orEmpty()}"
            )
        }
    }

    private suspend fun markVisibleRead(visibleTailIndex: Int? = null) {
        // Shown again below only once this read is known to go out for the live tail.
        readOnSight.hide(this)
        if (!visibleReadShown) {
            diagnosticsLogger?.invoke("thread_read_skip reason=not_shown")
            return
        }
        val authorityState = _state.value
        if (
            authorityState.accessResolution != ThreadAccessResolution.Resolved ||
            !authorityState.writeActionsEnabled
        ) {
            diagnosticsLogger?.invoke("thread_read_skip reason=write_authority_closed")
            return
        }
        val expectedProvenance = dataSource.currentReplyCacheProvenance() ?: return
        if (visiblePayloadOwnerScopeId != expectedProvenance.threadPayloadOwnerScopeId) return
        val expectedRoute = authorityState.payload.resolveThreadRouteIdentity()
        fun authorityStillCurrent(): Boolean {
            val currentState = _state.value
            return currentState.accessResolution == ThreadAccessResolution.Resolved &&
                currentState.writeActionsEnabled &&
                currentState.payload.resolveThreadRouteIdentity() == expectedRoute &&
                dataSource.isCurrentReplyCacheProvenance(expectedProvenance)
        }
        if (!authorityStillCurrent()) return
        val marker = authorityState.payload.visibleReadMarker(visibleTailIndex) ?: return
        if (marker.channelId == markedUnreadHoldChannelId) {
            diagnosticsLogger?.invoke(
                "thread_read_skip channelId=${marker.channelId} reason=marked_unread_hold latest=${marker.latestMessageId}"
            )
            return
        }
        // Reading another thread here means the person left the one they marked: its hold ends.
        markedUnreadHoldChannelId = null
        pendingMarkUnreadChannelId = null
        // Read on sight only at the live tail: scrolled up, a live append is not read (task #232),
        // so Activity must count it as the server does.
        if (visibleTailIndex == null || authorityState.payload.visibleReadMarker(null) == marker) {
            readOnSight.show(this, marker.channelId)
        }
        if (marker.wasRecentlyMarkedRead()) {
            // Task #19 (Hands 2aa74345): the window only coalesces. A marker NEWER than the completed one
            // (a message that arrived, or a stand-in replaced by the network page, right after the last
            // read) must still be marked once the window ends: nothing else re-runs the visible read while
            // the user just stays on the page, and the conversation kept one unread forever.
            val deferMs = if (marker != lastCompletedReadMarker) readCoalesceRemainingMillis() else null
            diagnosticsLogger?.invoke(
                "thread_read_skip channelId=${marker.channelId} reason=recently_marked latest=${marker.latestMessageId}" +
                    (deferMs?.let { " deferredMs=$it" } ?: "")
            )
            if (deferMs != null) {
                deferredReadJob?.cancel()
                deferredReadJob =
                    scope.launch {
                        delay(deferMs)
                        // Only the marker the user was shown: anything that changed since (a newer message
                        // while the page went to the background, older rows shifting the index) is owned by
                        // the page's own next visible-read dispatch, never marked from here unseen.
                        if (_state.value.payload.visibleReadMarker(visibleTailIndex) == marker) {
                            markVisibleRead(visibleTailIndex)
                        } else {
                            diagnosticsLogger?.invoke(
                                "thread_read_skip channelId=${marker.channelId} reason=deferred_marker_changed " +
                                    "latest=${marker.latestMessageId}"
                            )
                        }
                    }
            }
            return
        }
        if (readInFlightMarker != null) {
            pendingReadMarker = marker
            diagnosticsLogger?.invoke(
                "thread_read_skip channelId=${marker.channelId} reason=in_flight latest=${marker.latestMessageId}"
            )
            return
        }

        var nextMarker: ThreadVisibleReadMarker? = marker
        while (nextMarker != null) {
            // A follow-up queued while the previous read was in flight goes out only if the page still
            // shows the conversation (fb-4440a18c: it was sent after the person had left).
            if (!authorityStillCurrent() || !visibleReadShown) {
                pendingReadMarker = null
                return
            }
            val current = nextMarker
            pendingReadMarker = null
            readInFlightMarker = current
            val startedAt = TimeSource.Monotonic.markNow()
            diagnosticsLogger?.invoke(
                "thread_read_start channelId=${current.channelId} latest=${current.latestMessageId}"
            )
            try {
                val result =
                    dataSource.markVisibleChannelRead(
                        current.channelId,
                        current.latestSeq,
                        expectedProvenance = expectedProvenance
                    )
                if (
                    authorityStillCurrent() &&
                    (result is DataResult.Content || result is DataResult.Empty)
                ) {
                    lastCompletedReadMarker = current
                    lastCompletedReadAt = readCoalesceTimeSource.markNow()
                }
                diagnosticsLogger?.invoke(
                    "thread_read_result channelId=${current.channelId} result=${result.threadDiagnosticsResult()} " +
                        "mode=${if (current.latestSeq != null && current.latestSeq > 0L) "seq" else "all"} " +
                        "seq=${current.latestSeq ?: -1} latest=${current.latestMessageId} " +
                        "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
                )
            } finally {
                readInFlightMarker = null
            }
            val pending = pendingReadMarker
            nextMarker =
                if (pending != null && pending != current && pending != lastCompletedReadMarker) {
                    pending
                } else {
                    pendingReadMarker = null
                    null
                }
        }
    }

    /**
     * botiverse/slock #11687 (web threadUnreadHold.markOpenThreadUnread): the hold starts before the
     * write, so no visible read of this page goes out after it; a read still queued is dropped. The
     * answer is read as the Activity row reads it ([markUnreadOutcome]): Counted / Marked keep the
     * hold; Nothing to mark says so (the Activity toast) and, like a failed write, ends the hold, as
     * nothing was marked. A 202 (Pending, #proj-mobile:#70) keeps the hold with no toast until the
     * `read_state:updated` for the thread answers ([resolvePendingMarkUnread]).
     */
    private suspend fun markThreadUnread(threadChannelId: String) {
        val channelId = threadChannelId.trim().takeIf { it.isNotBlank() } ?: return
        // botiverse/slock #14222: a followed thread marked from a channel's list is not the one this
        // page shows: the same write with no hold (web markThreadUnreadFromThread), so the page's
        // own reads (and a hold on its conversation) go on as before.
        val holds = channelId == _state.value.payload.cacheThreadId()
        if (holds) {
            markedUnreadHoldChannelId = channelId
            readOnSight.hide(this)
            pendingMarkUnreadChannelId = null
            deferredReadJob?.cancel()
            deferredReadJob = null
            if (pendingReadMarker?.channelId == channelId) pendingReadMarker = null
            lastCompletedReadMarker = null
            lastCompletedReadAt = null
        }
        val result = dataSource.markThreadChannelUnread(channelId)
        val outcome = (result as? DataResult.Content)?.data?.markUnreadOutcome()
        diagnosticsLogger?.invoke(
            "thread_mark_unread channelId=$channelId result=${result.threadDiagnosticsResult()} " +
                "outcome=${outcome?.name ?: "none"}"
        )
        if (outcome == MarkUnreadOutcome.Counted || outcome == MarkUnreadOutcome.Marked) return
        if (outcome == MarkUnreadOutcome.Pending) {
            if (markedUnreadHoldChannelId == channelId) pendingMarkUnreadChannelId = channelId
            return
        }
        if (markedUnreadHoldChannelId == channelId) markedUnreadHoldChannelId = null
        if (outcome == MarkUnreadOutcome.NothingToMark) {
            _readToggleNotices.tryEmit(RaftReadToggleNotice.MarkUnreadNothing)
        }
    }

    /**
     * botiverse/slock #14222: the conversation this page shows (its timeline channel), marked unread
     * and held as [markThreadUnread] holds an open thread (web messageStore.markUnread keeps it
     * unread while the person stays in it).
     */
    private suspend fun markConversationUnread() {
        val payload = _state.value.payload
        if (payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread) return
        val channelId = payload.cacheThreadId() ?: return
        markThreadUnread(channelId)
    }

    /**
     * botiverse/slock #16250 (web messageStore.sendMessage forgetManualUnread): a message the person
     * sends from this page into the channel or DM they marked unread here ends that hold. It ends as
     * the send starts, whatever its answer, as web forgets the mark before its request: their own
     * message reads the conversation and later ones are read on sight again. A reply thread's hold
     * stays, as web's thread hold (threadUnreadHold) is not ended by a reply.
     */
    private fun endConversationUnreadHoldOnSend(payload: RaftThreadScreenPayload) {
        if (payload.surfaceKind == RaftThreadSurfaceKind.ReplyThread) return
        val channelId = markedUnreadHoldChannelId?.takeIf { it == payload.cacheThreadId() } ?: return
        markedUnreadHoldChannelId = null
        pendingMarkUnreadChannelId = null
        diagnosticsLogger?.invoke("thread_mark_unread_hold_end channelId=$channelId reason=sent")
    }

    /**
     * #proj-mobile:#70 (Tenny): the `read_state:updated` that answers a 202 Mark as Unread. Marked (a
     * count or the mark) keeps the hold; not marked reads as a 200 nothing to mark (the toast, the
     * hold ends), but only while the person is still on the thread (the hold is on). Once they left,
     * the event only updates the state, with no toast.
     */
    private fun resolvePendingMarkUnread(event: PerReceiverReadStateChanged, sessionServerId: String?) {
        val channelId = pendingMarkUnreadChannelId ?: return
        if (event.serverId != null && !sessionServerId.isNullOrBlank() && event.serverId.value != sessionServerId) return
        val scope = event.scopes.lastOrNull { it.scopeId.value == channelId } ?: return
        pendingMarkUnreadChannelId = null
        val marked = scope.leftMarkedUnread()
        val stillThere = markedUnreadHoldChannelId == channelId
        diagnosticsLogger?.invoke(
            "thread_mark_unread_pending_resolved channelId=$channelId marked=$marked stillThere=$stillThere"
        )
        if (marked || !stillThere) return
        markedUnreadHoldChannelId = null
        _readToggleNotices.tryEmit(RaftReadToggleNotice.MarkUnreadNothing)
    }

    /** Time left in the coalesce window of the last completed read (at least 1 ms). */
    private fun readCoalesceRemainingMillis(): Long {
        val ageMs = lastCompletedReadAt?.elapsedNow()?.inWholeMilliseconds ?: 0L
        return (ThreadVisibleReadCoalesceWindowMillis - ageMs).coerceAtLeast(1L)
    }

    private fun ThreadVisibleReadMarker.wasRecentlyMarkedRead(): Boolean {
        val completed = lastCompletedReadMarker ?: return false
        if (completed.channelId != channelId) return false
        if (completed == this) return true
        val completedAt = lastCompletedReadAt ?: return false
        val ageMs = completedAt.elapsedNow().inWholeMilliseconds
        return ageMs in 0L until ThreadVisibleReadCoalesceWindowMillis
    }

    private suspend fun loadOlder() {
        val existing = _state.value.payload
        if (!existing.canLoadMoreBefore || existing.olderMessagesStatus == "loading") return
        val staleToken = routeStaleToken
        val beforeSeq = existing.messages.mapNotNull { it.seq }.minOrNull()
        if (beforeSeq == null) {
            reducePayloadPatch { currentPayload ->
                currentPayload.copy(olderMessagesStatus = "error", errorCode = "missing_before_seq")
            }
            return
        }
        reducePayloadPatch { currentPayload -> currentPayload.copy(olderMessagesStatus = "loading", errorCode = null) }
        // Task #168 step 2b / Phase 2d:
        // - While the offline stand-in is shown, older pages come from SQLite (crossing into lower islands
        //   with derived gaps) before asking the network.
        // - Online, older pages inside a proven contiguous SQLite segment are served directly from disk
        //   with zero network RTT; at a segment edge we fetch `before=beforeSeq` from the server.
        if (existing.durableLastKnown && loadOlderFromDurableStandIn(existing, beforeSeq, staleToken)) return
        if (!existing.durableLastKnown && loadOlderFromProvenLocalSegment(existing, beforeSeq, staleToken)) return
        val result = dataSource.fetchOlderThreadPayload(existing, beforeSeq)
        val latest = _state.value.payload
        if (
            !staleTokenStillCurrent(staleToken, "thread_page", "older_after_fetch", existing, latest) ||
            !routeStillCurrent(existing, "thread_page", "older_after_fetch", latest)
        ) {
            return
        }
        val nextPayload =
            when (result) {
                is DataResult.Content -> {
                    val mergedRaw =
                        latest
                        .mergePagedMessages(
                            pageMessages = result.data.payload.messages,
                            before = true,
                            pageSize = result.data.requestedPageSize
                        )
                        .preserveStableTimelineFrom(latest)
                    val merged =
                        mergedRaw.copy(
                            timelineGaps =
                                reconcileThreadTimelineGaps(
                                    existingGaps = mergedRaw.timelineGaps,
                                    addedGaps = emptyList(),
                                    messages = mergedRaw.messages,
                                ),
                        )
                    // A stand-in (durable rows) is never committed to the memory payload cache: a memory
                    // candidate outranks the durable frame on the next entry as "network-fresh".
                    (if (latest.durableLastKnown) merged else dataSource.cacheCanonicalPayload(merged, visiblePayloadOwnerScopeId))
                        .withRouteContext(existing)
                        .preserveStableTimelineFrom(latest)
                }
                is DataResult.Empty ->
                    latest.copy(canLoadMoreBefore = false, olderMessagesStatus = "idle")
                is DataResult.Error ->
                    latest.copy(olderMessagesStatus = "error", errorCode = result.error.code, errorTraceId = result.error.traceId)
                is DataResult.NoPermission ->
                    latest.copy(olderMessagesStatus = "error", errorCode = result.blocker.code, errorTraceId = result.blocker.traceId)
            }
        reducePayloadPatch { currentPayload ->
            if (
                !staleTokenStillCurrent(staleToken, "thread_page", "older_apply", existing, currentPayload) ||
                !routeStillCurrent(existing, "thread_page", "older_apply", currentPayload)
            ) {
                return@reducePayloadPatch currentPayload
            }
            currentPayload.withThreadContentProjectionFrom(nextPayload)
        }
        val applied = _state.value.payload
        if (
            result is DataResult.Content &&
            staleToken.isCurrent(routeStaleToken) &&
            routeStillCurrent(existing, "thread_page", "older_secondary", applied)
        ) {
            launchSecondaryTimelineRefresh(applied, staleToken)
            launchDurableWindowExtension(existing, result.data, ThreadDurablePageDirection.Older, beforeSeq)
        }
    }

    /**
     * Task #14 part C: persist the anchor context page a focused refresh (anchored open, [jumpToMessage])
     * fetched. Fire-and-forget on the store scope like [launchDurableWindowExtension]; it never touches the
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

    /**
     * Offline cache paging entry (task #168): offer an applied older / newer page to the durable V2
     * window. Fire-and-forget on the store scope like the enter snapshot: it never touches the
     * visible payload, and a failure is only logged.
     */
    private fun launchDurableWindowExtension(
        routePayload: RaftThreadScreenPayload,
        fetched: SharedThreadPageFetchResult,
        direction: ThreadDurablePageDirection,
        anchorSeq: Long,
    ) {
        val durablePage = fetched.durablePage ?: return
        val routeIdentity = routePayload.resolveThreadRouteIdentity()
        val replyParent = routePayload.durableReplyParentOrNull()
        scope.launch {
            try {
                dataSource.extendDurableWindowIfCurrent(
                    routeIdentity = routeIdentity,
                    context = durablePage.context,
                    direction = direction,
                    anchorSeq = anchorSeq,
                    rawPage = durablePage.rawPage,
                    replyParent = replyParent,
                    headEpochAtRequest = durablePage.headEpochAtRequest,
                    revAtRequest = durablePage.revAtRequest,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                diagnosticsLogger?.invoke(
                    "thread_durable_page_extend_failed direction=${direction.wireName} " +
                        "reason=${throwable::class.simpleName ?: "unknown"}"
                )
            }
        }
    }

    /**
     * Task #168 step 2b: serve one older page of the offline stand-in from the durable window. Returns
     * `true` when the request is handled here (served, or dropped because the stand-in was replaced
     * meanwhile), `false` when the window has nothing to give and the caller should ask the network.
     * Reads only; the merged payload is not cached, and the online refresh still replaces the whole
     * stand-in including these pages.
     */
    private suspend fun loadOlderFromDurableStandIn(
        existing: RaftThreadScreenPayload,
        beforeSeq: Long,
        staleToken: MviStaleToken,
    ): Boolean {
        val context = dataSource.captureReplyCacheProducerContext() ?: return false
        val page =
            dataSource.durableOlderPageIfCurrent(
                routeIdentity = existing.resolveThreadRouteIdentity(),
                context = context,
                beforeSeq = beforeSeq,
                // The stand-in on screen belongs to the owner it was read for; another owner never pages it.
                expectedOwnerScopeId = visiblePayloadOwnerScopeId,
                replyParent = existing.durableReplyParentOrNull(),
                crossGaps = true,
            ) ?: return false
        var applied = false
        reducePayloadPatch { currentPayload ->
            if (
                !currentPayload.durableLastKnown ||
                !staleTokenStillCurrent(staleToken, "thread_page", "durable_older_apply", existing, currentPayload) ||
                !routeStillCurrent(existing, "thread_page", "durable_older_apply", currentPayload)
            ) {
                return@reducePayloadPatch currentPayload
            }
            applied = true
            val merged =
                currentPayload.mergePagedMessages(
                    pageMessages = page.messages,
                    before = true,
                    pageSize = page.pageSize,
                )
            merged.copy(
                canLoadMoreBefore = page.canLoadMoreBefore,
                durableReachedOldest = page.reachedOldest,
                timelineGaps =
                    reconcileThreadTimelineGaps(
                        existingGaps = currentPayload.timelineGaps,
                        addedGaps = page.gaps,
                        messages = merged.messages,
                    ),
            )
        }
        if (applied) {
            page.scope?.let { readScope ->
                scope.launch { dataSource.touchDurableUserAccess(readScope) }
            }
        } else {
            diagnosticsLogger?.invoke(
                "thread_durable_older_page threadId=${existing.sourceThreadId.orEmpty()} " +
                    "served=0 reason=stand_in_replaced"
            )
        }
        return true
    }

    /**
     * Phase 2d: online older paging inside a proven contiguous SQLite segment (`crossGaps = false`).
     * Returns `true` when disk satisfied the page (or proved the channel's oldest boundary, or the
     * route became stale/replaced while reading) without a network request; returns `false` at a
     * segment edge so [loadOlder] fetches `before=beforeSeq`.
     */
    private suspend fun loadOlderFromProvenLocalSegment(
        existing: RaftThreadScreenPayload,
        beforeSeq: Long,
        staleToken: MviStaleToken,
    ): Boolean {
        val context = dataSource.captureReplyCacheProducerContext() ?: return false
        val page =
            dataSource.durableOlderPageIfCurrent(
                routeIdentity = existing.resolveThreadRouteIdentity(),
                context = context,
                beforeSeq = beforeSeq,
                expectedOwnerScopeId = visiblePayloadOwnerScopeId,
                replyParent = existing.durableReplyParentOrNull(),
                crossGaps = false,
            ) ?: return false
        if (!page.insideProvenSegment) return false
        var applied = false
        var committedForCache: RaftThreadScreenPayload? = null
        reducePayloadPatch(cachePayload = false) { currentPayload ->
            if (
                currentPayload.durableLastKnown ||
                !staleTokenStillCurrent(staleToken, "thread_page", "proven_older_apply", existing, currentPayload) ||
                !routeStillCurrent(existing, "thread_page", "proven_older_apply", currentPayload)
            ) {
                return@reducePayloadPatch currentPayload
            }
            applied = true
            val merged =
                if (page.messages.isEmpty()) {
                    currentPayload.copy(
                        canLoadMoreBefore = false,
                        durableReachedOldest = true,
                        olderMessagesStatus = "idle",
                    )
                } else {
                    val combined =
                        currentPayload
                            .mergePagedMessages(
                                pageMessages = page.messages,
                                before = true,
                                pageSize = page.pageSize,
                            )
                            .preserveStableTimelineFrom(currentPayload)
                    combined.copy(
                        canLoadMoreBefore = page.canLoadMoreBefore,
                        durableReachedOldest = page.reachedOldest,
                        timelineGaps =
                            reconcileThreadTimelineGaps(
                                existingGaps = currentPayload.timelineGaps,
                                addedGaps = emptyList(),
                                messages = combined.messages,
                            ),
                    )
                }
            committedForCache = merged
            merged
                .withRouteContext(existing)
                .preserveStableTimelineFrom(currentPayload)
        }
        if (applied) {
            page.scope?.let { readScope ->
                scope.launch { dataSource.touchDurableUserAccess(readScope) }
            }
            committedForCache?.let { committed ->
                dataSource.cacheCanonicalPayload(committed, visiblePayloadOwnerScopeId)
            }
            // Proven-segment local paging is intentionally zero-network-RTT: secondary reply/task/saved
            // refreshes run on conversation refresh or when paging hits a network boundary.
        } else {
            diagnosticsLogger?.invoke(
                "thread_durable_older_page threadId=${existing.sourceThreadId.orEmpty()} " +
                    "served=0 reason=replaced_or_stale"
            )
        }
        return true
    }

    /**
     * Phase 2d: fetch older messages from the server to bridge or shrink an inline timeline gap
     * `(afterSeq, beforeSeq)` and persist the fetched page to SQLite so segments coalesce.
     */
    private suspend fun fillTimelineGap(afterSeq: Long, beforeSeq: Long) {
        val existing = _state.value.payload
        val targetGap =
            existing.timelineGaps.firstOrNull { it.afterSeq == afterSeq && it.beforeSeq == beforeSeq }
                ?: return
        if (targetGap.status == "loading") return
        val staleToken = routeStaleToken
        reducePayloadPatch { currentPayload ->
            if (
                !staleTokenStillCurrent(staleToken, "thread_page", "gap_fill_start", existing, currentPayload) ||
                !routeStillCurrent(existing, "thread_page", "gap_fill_start", currentPayload)
            ) {
                return@reducePayloadPatch currentPayload
            }
            currentPayload.copy(
                timelineGaps =
                    currentPayload.timelineGaps.map { gap ->
                        if (gap.afterSeq == afterSeq && gap.beforeSeq == beforeSeq) {
                            gap.copy(status = "loading")
                        } else {
                            gap
                        }
                    },
            )
        }
        val result = dataSource.fetchOlderThreadPayload(existing, beforeSeq)
        val latest = _state.value.payload
        if (
            !staleTokenStillCurrent(staleToken, "thread_page", "gap_fill_after_fetch", existing, latest) ||
            !routeStillCurrent(existing, "thread_page", "gap_fill_after_fetch", latest)
        ) {
            return
        }
        var applied = false
        var committedForCache: RaftThreadScreenPayload? = null
        reducePayloadPatch(cachePayload = false) { currentPayload ->
            if (
                !staleTokenStillCurrent(staleToken, "thread_page", "gap_fill_apply", existing, currentPayload) ||
                !routeStillCurrent(existing, "thread_page", "gap_fill_apply", currentPayload)
            ) {
                return@reducePayloadPatch currentPayload
            }
            applied = true
            when (result) {
                is DataResult.Content -> {
                    val pageMessages = result.data.payload.messages
                    val baseMessages =
                        currentPayload.messages
                            .absorbingCanonicalSendEchoes(pageMessages)
                            .withIncomingDelegatedLabels(pageMessages)
                            .withKnownRetractionsFrom(pageMessages)
                            .withNewerServerCopiesFrom(pageMessages)
                    val incoming = pageMessages.filterNot { candidate -> baseMessages.any { it.id == candidate.id } }
                    val mergedMessages = (baseMessages + incoming).distinctBy { it.id }.sortedByThreadTimeline()
                    val nextGaps =
                        reconcileThreadTimelineGapsAfterFill(
                            existingGaps = currentPayload.timelineGaps,
                            filledAfterSeq = afterSeq,
                            filledBeforeSeq = beforeSeq,
                            fetchedPageMessages = pageMessages,
                            requestedPageSize = result.data.requestedPageSize,
                            mergedMessages = mergedMessages,
                        )
                    val updated =
                        currentPayload
                            .copy(
                                status = "content",
                                messages = mergedMessages,
                                timelineGaps = nextGaps,
                            )
                            .preserveStableTimelineFrom(currentPayload)
                    if (currentPayload.durableLastKnown) {
                        updated
                    } else {
                        committedForCache = updated
                        updated
                            .withRouteContext(existing)
                            .preserveStableTimelineFrom(currentPayload)
                    }
                }
                is DataResult.Empty ->
                    currentPayload.copy(
                        timelineGaps =
                            currentPayload.timelineGaps.filterNot {
                                it.afterSeq == afterSeq && it.beforeSeq == beforeSeq
                            },
                    )
                is DataResult.Error,
                is DataResult.NoPermission ->
                    currentPayload.copy(
                        timelineGaps =
                            currentPayload.timelineGaps.map { gap ->
                                if (gap.afterSeq == afterSeq && gap.beforeSeq == beforeSeq) {
                                    gap.copy(status = "error")
                                } else {
                                    gap
                                }
                            },
                    )
            }
        }
        if (applied) {
            committedForCache?.let { committed ->
                dataSource.cacheCanonicalPayload(committed, visiblePayloadOwnerScopeId)
            }
        }
        val appliedPayload = _state.value.payload
        if (
            applied &&
            result is DataResult.Content &&
            staleToken.isCurrent(routeStaleToken) &&
            routeStillCurrent(existing, "thread_page", "gap_fill_secondary", appliedPayload)
        ) {
            launchSecondaryTimelineRefresh(appliedPayload, staleToken)
            launchDurableWindowExtension(existing, result.data, ThreadDurablePageDirection.Older, beforeSeq)
        }
    }

    suspend fun loadNewer(): ThreadNewerPageLoadOutcome {
        val initial = _state.value.payload
        if (!initial.canLoadMoreAfter || initial.newerMessagesStatus == "loading") {
            return ThreadNewerPageLoadOutcome.Ignored
        }
        val afterSeq = initial.messages.mapNotNull { it.seq }.maxOrNull()
        if (afterSeq == null) {
            reducePayloadPatch { currentPayload ->
                currentPayload.copy(newerMessagesStatus = "error", errorCode = "missing_after_seq")
            }
            return ThreadNewerPageLoadOutcome.Failed
        }
        val staleToken = routeStaleToken
        val routeIdentity = initial.realtimeGapRouteIdentity()
        val loadId =
            realtimeGapRecoveryMutex.withLock {
                val currentPayload = _state.value.payload
                val active = loadNewerState
                val activeRecovery = realtimeGapRecoveryState
                if (
                    !staleToken.isCurrent(routeStaleToken) ||
                    currentPayload.realtimeGapRouteIdentity() != routeIdentity ||
                    (
                        active != null &&
                            active.routeIdentity == routeIdentity &&
                            active.staleToken == staleToken
                    ) ||
                    (
                        activeRecovery != null &&
                            activeRecovery.routeIdentity == routeIdentity &&
                            activeRecovery.staleToken == staleToken
                    )
                ) {
                    null
                } else {
                    val id = ++nextLoadNewerId
                    loadNewerState =
                        LoadNewerState(
                            id = id,
                            routeIdentity = routeIdentity,
                            staleToken = staleToken,
                        )
                    reducePayloadPatch(cachePayload = !currentPayload.durableLastKnown) { latestPayload ->
                        latestPayload.copy(newerMessagesStatus = "loading", errorCode = null)
                    }
                    id
                }
            } ?: return ThreadNewerPageLoadOutcome.Ignored
        var successorRecovery: Pair<RaftThreadScreenPayload, RealtimeVisibleStreamGapRequest>? = null
        var outcome: ThreadNewerPageLoadOutcome = ThreadNewerPageLoadOutcome.Ignored
        try {
            val localContext = dataSource.captureReplyCacheProducerContext()
            val localNewerPage =
                localContext?.let { context ->
                    dataSource.durableNewerPageIfCurrent(
                        routeIdentity = initial.resolveThreadRouteIdentity(),
                        context = context,
                        afterSeq = afterSeq,
                        expectedOwnerScopeId = visiblePayloadOwnerScopeId,
                        replyParent = initial.durableReplyParentOrNull(),
                        crossGaps = initial.durableLastKnown,
                    )
                }?.takeIf { initial.durableLastKnown || it.insideProvenSegment }
            if (localNewerPage != null) {
                realtimeGapRecoveryMutex.withLock {
                    val active =
                        loadNewerState
                            ?.takeIf { state -> state.id == loadId }
                            ?: return@withLock
                    val currentPayload = _state.value.payload
                    if (
                        !staleTokenStillCurrent(staleToken, "thread_page", "newer_local_apply", initial, currentPayload) ||
                        !routeStillCurrent(initial, "thread_page", "newer_local_apply", currentPayload) ||
                        currentPayload.durableLastKnown != initial.durableLastKnown
                    ) {
                        loadNewerState = null
                        return@withLock
                    }
                    val existingIds = currentPayload.messages.mapTo(mutableSetOf()) { it.id }
                    var appliedToCurrentRoute = false
                    var committedForCache: RaftThreadScreenPayload? = null
                    reducePayloadPatch(cachePayload = false) { latestPayload ->
                        if (
                            !staleTokenStillCurrent(staleToken, "thread_page", "newer_local_commit", initial, latestPayload) ||
                            !routeStillCurrent(initial, "thread_page", "newer_local_commit", latestPayload)
                        ) {
                            return@reducePayloadPatch latestPayload
                        }
                        appliedToCurrentRoute = true
                        if (localNewerPage.messages.isEmpty()) {
                            latestPayload.copy(
                                canLoadMoreAfter = localNewerPage.canLoadMoreAfter,
                                newerMessagesStatus = "idle",
                            )
                        } else {
                            val merged =
                                latestPayload
                                    .mergePagedMessages(
                                        pageMessages = localNewerPage.messages,
                                        before = false,
                                        pageSize = localNewerPage.pageSize,
                                    )
                                    .preserveStableTimelineFrom(latestPayload)
                            val withGaps =
                                merged.copy(
                                    canLoadMoreAfter = localNewerPage.canLoadMoreAfter,
                                    timelineGaps =
                                        reconcileThreadTimelineGaps(
                                            existingGaps = latestPayload.timelineGaps,
                                            addedGaps = localNewerPage.gaps,
                                            messages = merged.messages,
                                        ),
                                )
                            if (latestPayload.durableLastKnown) {
                                withGaps
                            } else {
                                committedForCache = withGaps
                                withGaps
                                    .withRouteContext(initial)
                                    .preserveStableTimelineFrom(latestPayload)
                            }
                        }
                    }
                    if (appliedToCurrentRoute) {
                        localNewerPage.scope?.let { readScope ->
                            scope.launch { dataSource.touchDurableUserAccess(readScope) }
                        }
                        committedForCache?.let { committed ->
                            dataSource.cacheCanonicalPayload(committed, visiblePayloadOwnerScopeId)
                        }
                        val appliedIds = _state.value.payload.messages.mapTo(mutableSetOf()) { it.id }
                        val addedMessageIds =
                            localNewerPage.messages
                                .map { it.id }
                                .filter { id -> id.isNotBlank() && id !in existingIds && id in appliedIds }
                                .distinct()
                        outcome =
                            if (addedMessageIds.isEmpty()) {
                                ThreadNewerPageLoadOutcome.Empty
                            } else {
                                ThreadNewerPageLoadOutcome.Applied(addedMessageIds)
                            }
                    }
                    loadNewerState = null
                    val pageMaxSeq = localNewerPage.messages.mapNotNull { it.seq }.maxOrNull() ?: afterSeq
                    val pendingTarget = active.pendingTargetSeq
                    if (appliedToCurrentRoute && pendingTarget != null && pendingTarget > pageMaxSeq) {
                        val streamChannelId =
                            currentPayload.sourceThreadId
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::ChannelId)
                                ?: currentPayload.streamChannelId
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(::ChannelId)
                        if (streamChannelId != null) {
                            successorRecovery =
                                _state.value.payload to
                                    RealtimeVisibleStreamGapRequest(
                                        channelId = streamChannelId,
                                        sinceSeq = pageMaxSeq,
                                        serverSeq = pendingTarget,
                                    )
                        }
                    }
                }
            } else {
                val result = dataSource.fetchNewerThreadPayload(initial, afterSeq)
                realtimeGapRecoveryMutex.withLock {
                    val active =
                        loadNewerState
                            ?.takeIf { state -> state.id == loadId }
                            ?: return@withLock
                    val currentPayload = _state.value.payload
                    if (
                        !staleTokenStillCurrent(
                            staleToken,
                            "thread_page",
                            "newer_after_fetch",
                            initial,
                            currentPayload,
                        ) ||
                        !routeStillCurrent(
                            initial,
                            "thread_page",
                            "newer_after_fetch",
                            currentPayload,
                        )
                    ) {
                        loadNewerState = null
                        return@withLock
                    }
                    val pageMaxSeq =
                        when (result) {
                            is DataResult.Content ->
                                result.data.payload.messages.mapNotNull { message -> message.seq }.maxOrNull() ?: afterSeq
                            else -> afterSeq
                        }
                    val nextPayload =
                        when (result) {
                            is DataResult.Content -> {
                                val mergedRaw =
                                    currentPayload
                                        .mergePagedMessages(
                                            pageMessages = result.data.payload.messages,
                                            before = false,
                                            pageSize = result.data.requestedPageSize
                                        )
                                        .preserveStableTimelineFrom(currentPayload)
                                val merged =
                                    mergedRaw.copy(
                                        timelineGaps =
                                            reconcileThreadTimelineGaps(
                                                existingGaps = mergedRaw.timelineGaps,
                                                addedGaps = emptyList(),
                                                messages = mergedRaw.messages,
                                            ),
                                    )
                                (if (currentPayload.durableLastKnown) merged else dataSource.cacheCanonicalPayload(merged, visiblePayloadOwnerScopeId))
                                    .withRouteContext(initial)
                                    .preserveStableTimelineFrom(currentPayload)
                            }
                            is DataResult.Empty ->
                                currentPayload.copy(canLoadMoreAfter = false, newerMessagesStatus = "idle")
                            is DataResult.Error ->
                                currentPayload.copy(newerMessagesStatus = "error", errorCode = result.error.code, errorTraceId = result.error.traceId)
                            is DataResult.NoPermission ->
                                currentPayload.copy(newerMessagesStatus = "error", errorCode = result.blocker.code, errorTraceId = result.blocker.traceId)
                        }
                    var appliedToCurrentRoute = false
                    reducePayloadPatch(cachePayload = !currentPayload.durableLastKnown) { latestPayload ->
                        if (
                            !staleTokenStillCurrent(
                                staleToken,
                                "thread_page",
                                "newer_apply",
                                initial,
                                latestPayload,
                            ) ||
                            !routeStillCurrent(
                                initial,
                                "thread_page",
                                "newer_apply",
                                latestPayload,
                            )
                        ) {
                            return@reducePayloadPatch latestPayload
                        }
                        appliedToCurrentRoute = true
                        latestPayload.withThreadContentProjectionFrom(nextPayload)
                    }
                    if (appliedToCurrentRoute && result is DataResult.Content) {
                        launchDurableWindowExtension(initial, result.data, ThreadDurablePageDirection.Newer, afterSeq)
                    }
                    if (appliedToCurrentRoute) {
                        outcome =
                            when (result) {
                                is DataResult.Content -> {
                                    val existingIds = currentPayload.messages.mapTo(mutableSetOf()) { it.id }
                                    val appliedIds = _state.value.payload.messages.mapTo(mutableSetOf()) { it.id }
                                    val addedMessageIds =
                                        result.data.payload.messages
                                            .map { it.id }
                                            .filter { id -> id.isNotBlank() && id !in existingIds && id in appliedIds }
                                            .distinct()
                                    if (addedMessageIds.isEmpty()) {
                                        ThreadNewerPageLoadOutcome.Empty
                                    } else {
                                        ThreadNewerPageLoadOutcome.Applied(addedMessageIds)
                                    }
                                }
                                is DataResult.Empty -> ThreadNewerPageLoadOutcome.Empty
                                is DataResult.Error,
                                is DataResult.NoPermission -> ThreadNewerPageLoadOutcome.Failed
                            }
                    }
                    loadNewerState = null
                    val pendingTarget = active.pendingTargetSeq
                    if (pendingTarget != null && pendingTarget > pageMaxSeq) {
                        val streamChannelId =
                            currentPayload.sourceThreadId
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::ChannelId)
                                ?: currentPayload.streamChannelId
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(::ChannelId)
                        if (streamChannelId != null) {
                            successorRecovery =
                                _state.value.payload to
                                    RealtimeVisibleStreamGapRequest(
                                        channelId = streamChannelId,
                                        sinceSeq = pageMaxSeq,
                                        serverSeq = pendingTarget,
                                    )
                        }
                    }
                }
            }
        } finally {
            realtimeGapRecoveryMutex.withLock {
                val active = loadNewerState?.takeIf { state -> state.id == loadId }
                if (active != null) {
                    loadNewerState = null
                    val currentPayload = _state.value.payload
                    if (
                        active.staleToken == routeStaleToken &&
                        active.routeIdentity == currentPayload.realtimeGapRouteIdentity() &&
                        currentPayload.newerMessagesStatus == "loading"
                    ) {
                        reducePayloadPatch(cachePayload = !currentPayload.durableLastKnown) { latestPayload ->
                            latestPayload.copy(newerMessagesStatus = "idle")
                        }
                    }
                }
            }
        }
        successorRecovery?.let { (successorInitial, request) ->
            recoverRealtimeGap(successorInitial, request, staleToken)
        }
        return outcome
    }

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

    private suspend fun recoverRealtimeGap(serverSeq: Long) {
        val initial = _state.value.payload
        val staleToken = routeStaleToken
        val streamChannelId =
            initial.sourceThreadId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ChannelId)
                ?: initial.streamChannelId
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::ChannelId)
        val nextRequest =
            RealtimeVisibleStreamGapPolicy.syncRequest(
                channelId = streamChannelId,
                visibleMaxSeq = initial.messages.mapNotNull { it.seq }.maxOrNull() ?: 0L,
                serverSeq = serverSeq,
                loading = false,
            ) ?: return
        // Heartbeats arrive through the realtime sink, under the realtime authority lock.
        recoverRealtimeGap(initial, nextRequest, staleToken, launchCatchUp = true)
    }

    private suspend fun recoverRealtimeGapFromVisibleTail(replyCountGap: ReplyCountRealtimeGap) {
        val initial = _state.value.payload
        val staleToken = routeStaleToken
        val streamChannelId =
            initial.sourceThreadId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ChannelId)
                ?: initial.streamChannelId
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::ChannelId)
                ?: return
        val sinceSeq = initial.messages.mapNotNull { it.seq }.maxOrNull()?.takeIf { it > 0L } ?: return
        if (initial.newerMessagesStatus == "loading") return
        recoverRealtimeGap(
            initial = initial,
            initialRequest =
                RealtimeVisibleStreamGapRequest(
                    channelId = streamChannelId,
                    sinceSeq = sinceSeq,
                    serverSeq = sinceSeq + replyCountGap.replyCount.toLong().coerceAtLeast(1L)
            ),
            staleToken = staleToken,
            // Reached from [applyRealtimeEvent], under the realtime authority lock.
            launchCatchUp = true,
        )
    }

    private suspend fun refreshLatestAfterVisibleMaxSeq(retryAttempt: Int = 0) {
        val generation = if (retryAttempt == 0) ++resumeCatchUpGeneration else resumeCatchUpGeneration
        val initial = _state.value.payload
        val staleToken = routeStaleToken
        val streamChannelId =
            initial.sourceThreadId
                ?.takeIf { it.isNotBlank() }
                ?.let(::ChannelId)
                ?: initial.streamChannelId
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::ChannelId)
                ?: return
        val sinceSeq = initial.messages.mapNotNull { it.seq }.maxOrNull()?.takeIf { it > 0L } ?: return
        if (initial.newerMessagesStatus == "loading") return
        val failuresBefore = gapCatchUpRetryableFailures
        recoverRealtimeGap(
            initial = initial,
            initialRequest =
                RealtimeVisibleStreamGapRequest(
                    channelId = streamChannelId,
                    sinceSeq = sinceSeq,
                    serverSeq = sinceSeq + 1L
                ),
            staleToken = staleToken,
            // Replies posted while backgrounded are not top-level messages, so the sync above can come
            // back empty while reply counts are stale (ad1df6b7): always re-read the reply summaries.
            backfillAfterRecovery = true,
        )
        if (gapCatchUpRetryableFailures == failuresBefore || retryAttempt >= RESUME_CATCH_UP_MAX_RETRIES) return
        if (!staleToken.isCurrent(routeStaleToken)) return
        diagnosticsLogger?.invoke(
            "thread_resume_catchup_retry_scheduled threadId=${initial.sourceThreadId.orEmpty()} " +
                "attempt=${retryAttempt + 1}"
        )
        scope.launch {
            delay(resumeCatchUpRetryDelayMs(retryAttempt))
            // A newer resume already asked again; the route changing is checked by the refresh itself.
            if (generation != resumeCatchUpGeneration || !staleToken.isCurrent(routeStaleToken)) return@launch
            refreshLatestAfterVisibleMaxSeq(retryAttempt + 1)
        }
    }

    private suspend fun recoverRealtimeGap(
        initial: RaftThreadScreenPayload,
        initialRequest: RealtimeVisibleStreamGapRequest,
        staleToken: MviStaleToken,
        backfillAfterRecovery: Boolean = false,
        /**
         * `true` when called under the realtime authority lock (the realtime sink): the recovery is
         * registered here and its catch-up fetch runs on the store scope, so the lock is not held for the
         * network round trip. `false` (resume) awaits the catch-up, which the retry accounting needs.
         */
        launchCatchUp: Boolean = false,
    ) {
        // The page is leaving: a catch-up already proved this account can no longer read it (web
        // #12696 stops the heartbeat's sync for a channel it was removed from).
        if (_state.value.conversationAccessLost) return
        val routeIdentity = initial.realtimeGapRouteIdentity()
        var requestSnapshot: RaftThreadScreenPayload? = null
        val recoveryId =
            realtimeGapRecoveryMutex.withLock {
                val currentPayload = _state.value.payload
                if (
                    !staleToken.isCurrent(routeStaleToken) ||
                    currentPayload.realtimeGapRouteIdentity() != routeIdentity ||
                    !routeStillCurrent(
                        initial,
                        "thread_realtime_gap",
                        "start",
                        currentPayload,
                    )
                ) {
                    return@withLock null
                }
                val active = realtimeGapRecoveryState
                val pagination = loadNewerState
                if (
                    active != null &&
                    active.routeIdentity == routeIdentity &&
                    active.staleToken == staleToken
                ) {
                    if (initialRequest.serverSeq > active.targetSeq) {
                        active.targetSeq = initialRequest.serverSeq
                        active.targetVersion += 1L
                    }
                    if (backfillAfterRecovery) active.backfillAfterRecovery = true
                    null
                } else if (
                    pagination != null &&
                    pagination.routeIdentity == routeIdentity &&
                    pagination.staleToken == staleToken
                ) {
                    if (initialRequest.serverSeq > (pagination.pendingTargetSeq ?: 0L)) {
                        pagination.pendingTargetSeq = initialRequest.serverSeq
                    }
                    null
                } else {
                    val id = ++nextRealtimeGapRecoveryId
                    realtimeGapRecoveryState =
                        RealtimeGapRecoveryState(
                            id = id,
                            routeIdentity = routeIdentity,
                            staleToken = staleToken,
                            targetSeq = initialRequest.serverSeq,
                            targetVersion = 1L,
                            backfillAfterRecovery = backfillAfterRecovery,
                        )
                    requestSnapshot = currentPayload
                    reducePayloadPatch(cachePayload = !currentPayload.durableLastKnown) { latestPayload ->
                        latestPayload.copy(newerMessagesStatus = "loading", errorCode = null)
                    }
                    id
                }
            } ?: return
        val snapshot = requestSnapshot ?: return
        if (launchCatchUp) {
            launchRealtimeGapRecovery(initial, initialRequest, staleToken, recoveryId, snapshot)
        } else {
            runRealtimeGapRecovery(initial, initialRequest, staleToken, recoveryId, snapshot)
        }
    }

    /**
     * Runs [runRealtimeGapRecovery] on the store scope instead of the caller. Used where the caller holds
     * the process-wide realtime authority lock (Hands bc6e392e): the recovery is already registered in
     * [realtimeGapRecoveryState], so live events applied while the fetch is in flight join it through the
     * `in_flight_gap` branch of [applyRealtimeEvent], and the result merges under the same recovery
     * mutex and stale-token / route fences as before.
     */
    private fun launchRealtimeGapRecovery(
        initial: RaftThreadScreenPayload,
        initialRequest: RealtimeVisibleStreamGapRequest,
        staleToken: MviStaleToken,
        recoveryId: Long,
        requestSnapshot: RaftThreadScreenPayload,
    ) {
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    runRealtimeGapRecovery(initial, initialRequest, staleToken, recoveryId, requestSnapshot)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    // Inline, this propagated into the realtime sink; on the store scope it would reach the
                    // uncaught handler. The recovery's finally has already released its state.
                    diagnosticsLogger?.invoke(
                        "thread_realtime_gap_recovery_failed threadId=${initial.sourceThreadId.orEmpty()} " +
                            "reason=${throwable::class.simpleName ?: "unknown"}"
                    )
                }
            }
        realtimeGapRecoveryJob = job
        onRealtimeGapRecoveryLaunchedForTest?.invoke(job)
        job.start()
    }

    /**
     * Gap catch-up (task #168 step 1, product decision "option A"): at most TWO latest-page requests
     * ([SharedThreadDataSource.fetchGapCatchUpLatestPage]) — the thread page size first, the 200 page
     * only when the anchor is not on it — and never a loop. Small vs large gap is the explicit FACT
     * "does the page contain the anchor message" — the newest server-backed message this timeline had
     * when the request was made ([gapCatchUpAnchorMessageId]). Seqs are server-global and never decide
     * it. The page that ended the decision is the one merged / shown / offered to the durable window.
     *
     * - Anchor on the page (small gap): the page overlaps the timeline, so it is merged as before
     *   (no hole). The durable window is offered the page and extends its newest edge.
     * - Anchor absent (large gap): the missing span is wider than one page. When the viewport
     *   follows the latest message the timeline is replaced by the page the way channel open does
     *   (older paging fills the rest); when the user has scrolled up, the visible timeline is kept
     *   and only marked as detached from the tail (`canLoadMoreAfter`), so the existing ↓ button
     *   ([jumpToLatest]) or newer paging takes them forward without yanking the viewport. The
     *   durable window is replaced by the page either way (`window_replaced`).
     *
     * The final response either commits under the recovery mutex or nothing changes: a failure at
     * either step, a stale route or a cancellation leaves the timeline (and the durable window) as it was.
     */
    private suspend fun runRealtimeGapRecovery(
        initial: RaftThreadScreenPayload,
        @Suppress("UNUSED_PARAMETER") initialRequest: RealtimeVisibleStreamGapRequest,
        staleToken: MviStaleToken,
        recoveryId: Long,
        /**
         * The timeline as it was when this recovery was registered. The request (anchor, known ids) is
         * built from it rather than from the payload at fetch start: once the fetch runs off the realtime
         * lock, live events may land in between through `in_flight_gap`, and a newer live message must
         * not become the anchor (it would hide the hole below it) nor count as "known before the fetch".
         */
        requestSnapshot: RaftThreadScreenPayload,
    ) {
        // Set when the response merged (or replaced in) messages the timeline did not have. Resume
        // arrivals land here, but nothing else re-triggers the secondary (thread summary / followed)
        // refresh that the initial route load performs (task #317).
        var mergedNewArrivals = false
        // Copied from this recovery's state while it is live: a resume that coalesced into it may have set it.
        var backfillRequested = false
        var durableOffer: SharedThreadPageFetchResult? = null
        // Task #168 step 3: registered before the first fetch; handed to the durable write or abandoned.
        var durableBaseline: CanonicalMessageV2EnterBaseline? = null
        // Message storage Phase 2b: the durable write revision before the catch-up's fetch (its page's revAtRequest).
        var durableRevAtRequest: Long? = null
        var recoveryCompleted = false
        // Set when the catch-up answered that this account lost the conversation (removed from the
        // channel: web's exact denials, see [isChannelAccessDenial]); the page then leaves, as after refresh.
        var accessLost = false
        var accessProvenance: ReplyCacheProvenance? = null
        try {
            val requestPayload =
                realtimeGapRecoveryMutex.withLock {
                    val active =
                        realtimeGapRecoveryState
                            ?.takeIf { state -> state.id == recoveryId }
                            ?: return@withLock null
                    val currentPayload = _state.value.payload
                    if (
                        !staleToken.isCurrent(routeStaleToken) ||
                        active.routeIdentity != currentPayload.realtimeGapRouteIdentity() ||
                        !routeStillCurrent(
                            initial,
                            "thread_realtime_gap",
                            "before_fetch",
                            currentPayload,
                        )
                    ) {
                        realtimeGapRecoveryState = null
                        return@withLock null
                    }
                    requestSnapshot
                } ?: return
            accessProvenance = dataSource.currentReplyCacheProvenance()
            val anchorMessageId = requestPayload.gapCatchUpAnchorMessageId()
            val requestMessageIds = requestPayload.messages.mapTo(mutableSetOf()) { it.id }
            // Two steps, one outcome (nothing is committed before the decision is final): the cheap page
            // first; only when the anchor is not on it, the enter snapshot's page. Either step failing
            // ends the recovery with no timeline change.
            durableBaseline =
                dataSource.beginDurableLatestPageBaselineIfCurrent(
                    initial.resolveThreadRouteIdentity(),
                    initial.durableReplyParentOrNull(),
                )
            durableRevAtRequest = dataSource.durableLocalRevIfCurrent()
            val narrow = dataSource.fetchGapCatchUpLatestPage(requestPayload, wide = false)
            val anchorOnNarrowPage =
                narrow is DataResult.Content &&
                    anchorMessageId != null &&
                    narrow.data.payload.messages.any { message -> message.id == anchorMessageId }
            val result =
                if (narrow is DataResult.Content && !anchorOnNarrowPage && narrow.data.payload.messages.isNotEmpty()) {
                    dataSource.fetchGapCatchUpLatestPage(requestPayload, wide = true)
                } else {
                    narrow
                }
            val catchUpSteps = if (result === narrow) 1 else 2
            realtimeGapRecoveryMutex.withLock {
                val recovery =
                    realtimeGapRecoveryState
                        ?.takeIf { state -> state.id == recoveryId }
                        ?: return@withLock
                // Read before any branch below clears the state; a resume that coalesced mid-fetch set it.
                if (recovery.backfillAfterRecovery) backfillRequested = true
                realtimeGapRecoveryState = null
                val currentPayload = _state.value.payload
                if (
                    !staleTokenStillCurrent(
                        staleToken,
                        "thread_realtime_gap",
                        "after_fetch",
                        initial,
                        currentPayload,
                    ) ||
                    !routeStillCurrent(
                        initial,
                        "thread_realtime_gap",
                        "after_fetch",
                        currentPayload,
                    )
                ) {
                    return@withLock
                }
                // An empty latest page answers nothing about the gap (it carries no anchor to test and no
                // message to show): it is handled like the repository's `Empty`, the timeline is kept.
                val catchUpResult =
                    if (result is DataResult.Content && result.data.payload.messages.isEmpty()) {
                        DataResult.Empty(EmptyReason.NoMessages)
                    } else {
                        result
                    }
                when (catchUpResult) {
                    is DataResult.Content -> {
                        val pageMessages = catchUpResult.data.payload.messages
                        val decision =
                            gapCatchUpDecision(
                                anchorMessageId = anchorMessageId,
                                pageMessageIds = pageMessages.map { it.id },
                                // A focused / context window (`canLoadMoreAfter`) is already detached
                                // from the tail; a large gap never swaps it for the latest page.
                                timelineAtTail = !requestPayload.canLoadMoreAfter,
                                viewportFollowsLatest = viewportFollowsLatest,
                            )
                        val currentIds = currentPayload.messages.mapTo(mutableSetOf()) { it.id }
                        val next =
                            when (decision) {
                                GapCatchUpDecision.MergeAtNewestEdge ->
                                    currentPayload
                                        .mergePagedMessages(
                                            pageMessages = pageMessages,
                                            before = false,
                                            pageSize = catchUpResult.data.requestedPageSize
                                        )
                                        .preserveStableTimelineFrom(currentPayload)
                                        .copy(
                                            canLoadMoreAfter = false,
                                            newerMessagesStatus = "idle",
                                        )
                                GapCatchUpDecision.ReplaceWithLatestPage -> {
                                    // Messages that reached this timeline by realtime WHILE the request
                                    // was in flight (not on screen when it was made, not on the page)
                                    // are newer than the page snapshot: keep them on top of it.
                                    val arrivedDuringFetch =
                                        currentPayload.messages.filter { message ->
                                            message.id !in requestMessageIds &&
                                                message.seq != null &&
                                                pageMessages.none { it.id == message.id }
                                        }
                                    catchUpResult.data.payload
                                        .copy(
                                            messages =
                                                (pageMessages + arrivedDuringFetch)
                                                    .distinctBy { it.id }
                                                    .sortedByThreadTimeline(),
                                            status = "content",
                                            errorCode = null,
                                            errorServerMessage = null,
                                            errorTraceId = null,
                                            canLoadMoreAfter = false,
                                            newerMessagesStatus = "idle",
                                            olderMessagesStatus = "idle",
                                        )
                                        .withRouteContext(initial)
                                        .preserveStableTimelineFrom(currentPayload)
                                }
                                GapCatchUpDecision.DetachFromTail ->
                                    currentPayload.copy(
                                        canLoadMoreAfter = true,
                                        newerMessagesStatus = "idle",
                                    )
                            }
                        val selected =
                            (if (next.durableLastKnown) next else dataSource.cacheCanonicalPayload(next, visiblePayloadOwnerScopeId))
                                .withRouteContext(initial)
                                .preserveStableTimelineFrom(currentPayload)
                        var applied = false
                        reducePayloadPatch { latestPayload ->
                            if (
                                !staleTokenStillCurrent(
                                    staleToken,
                                    "thread_realtime_gap",
                                    "apply",
                                    initial,
                                    latestPayload,
                                ) ||
                                !routeStillCurrent(
                                    initial,
                                    "thread_realtime_gap",
                                    "apply",
                                    latestPayload,
                                )
                            ) {
                                return@reducePayloadPatch latestPayload
                            }
                            applied = true
                            latestPayload.withThreadContentProjectionFrom(selected)
                        }
                        val added = pageMessages.count { message -> message.id !in currentIds }
                        diagnosticsLogger?.invoke(
                            "thread_gap_catchup threadId=${initial.sourceThreadId.orEmpty()} " +
                                "decision=${decision.wireName} anchorOnPage=${anchorMessageId != null && pageMessages.any { it.id == anchorMessageId }} " +
                                "steps=$catchUpSteps pageMessages=${pageMessages.size} added=$added applied=$applied"
                        )
                        if (!applied) return@withLock
                        if (decision != GapCatchUpDecision.DetachFromTail && added > 0) {
                            mergedNewArrivals = true
                        }
                        // The durable window is offered the page whenever it brought something the
                        // timeline lacked (an all-known page — the common heartbeat case — writes nothing).
                        // With a realtime baseline registered the page is always offered: committing it is
                        // what makes the viewed channel's window continuous again (task #168 step 3).
                        if (added > 0 || durableBaseline != null) durableOffer = catchUpResult.data
                    }
                    is DataResult.Empty -> {
                        reducePayloadPatch(cachePayload = !currentPayload.durableLastKnown) { latestPayload ->
                            latestPayload.copy(
                                canLoadMoreAfter = false,
                                newerMessagesStatus = "idle",
                            )
                        }
                    }
                    is DataResult.Error -> {
                        if (catchUpResult.isChannelAccessDenial()) {
                            accessLost = true
                            markConversationAccessLost(catchUpResult.error.code)
                        } else {
                            if (catchUpResult.error.retryable) gapCatchUpRetryableFailures += 1L
                            reducePayloadPatch(cachePayload = !currentPayload.durableLastKnown) { latestPayload ->
                                latestPayload.copy(
                                    newerMessagesStatus = "error",
                                    errorCode = catchUpResult.error.code,
                                    errorTraceId = catchUpResult.error.traceId,
                                )
                            }
                        }
                    }
                    is DataResult.NoPermission -> {
                        if (catchUpResult.isChannelAccessDenial()) {
                            accessLost = true
                            markConversationAccessLost(catchUpResult.blocker.code)
                        } else {
                            reducePayloadPatch(cachePayload = !currentPayload.durableLastKnown) { latestPayload ->
                                latestPayload.copy(
                                    newerMessagesStatus = "error",
                                    errorCode = catchUpResult.blocker.code,
                                    errorTraceId = catchUpResult.blocker.traceId,
                                )
                            }
                        }
                    }
                }
                if (accessLost) {
                    diagnosticsLogger?.invoke(
                        "thread_gap_catchup_access_lost threadId=${initial.sourceThreadId.orEmpty()} " +
                            "result=${catchUpResult.diagnosticsResultReason()}"
                    )
                }
            }
            recoveryCompleted = true
        } finally {
            // A failure or cancellation never reaches the durable write below.
            if (!recoveryCompleted) durableBaseline?.let { dataSource.abandonDurableLatestPageBaseline(it) }
            // NonCancellable: a cancelled launched recovery ([clear]) must still release its state, or a
            // leaked owner would park later gaps and the join catch-up (`catch_up_in_flight`).
            withContext(NonCancellable) {
                realtimeGapRecoveryMutex.withLock {
                    val active = realtimeGapRecoveryState?.takeIf { state -> state.id == recoveryId }
                    if (active?.backfillAfterRecovery == true) backfillRequested = true
                    if (active != null) {
                        realtimeGapRecoveryState = null
                        val currentPayload = _state.value.payload
                        if (
                            active.staleToken == routeStaleToken &&
                            active.routeIdentity == currentPayload.realtimeGapRouteIdentity() &&
                            currentPayload.newerMessagesStatus == "loading"
                        ) {
                            reducePayloadPatch(cachePayload = !currentPayload.durableLastKnown) { latestPayload ->
                                latestPayload.copy(newerMessagesStatus = "idle")
                            }
                        }
                    }
                }
            }
        }
        val offer = durableOffer
        if (offer != null) {
            launchDurableLatestPageWrite(initial, offer, durableBaseline, durableRevAtRequest)
        } else {
            durableBaseline?.let { dataSource.abandonDurableLatestPageBaseline(it) }
        }
        if (accessLost) {
            // Same as refresh: drop memory + durable cache for this conversation; no backfill follows.
            accessProvenance?.let { provenance -> dataSource.evictLostConversationAccess(initial, provenance) }
            return
        }
        if (!mergedNewArrivals && !backfillRequested) return
        val latest = _state.value.payload
        if (
            !staleTokenStillCurrent(
                staleToken,
                "thread_secondary_backfill",
                "after_gap_recovery",
                initial,
                latest,
            ) ||
            !routeStillCurrent(
                initial,
                "thread_secondary_backfill",
                "after_gap_recovery",
                latest,
            )
        ) {
            return
        }
        diagnosticsLogger?.invoke(
            "thread_secondary_backfill threadId=${initial.sourceThreadId.orEmpty()} " +
                "trigger=${if (mergedNewArrivals) "resume_gap" else "resume"}"
        )
        launchSecondaryTimelineRefresh(initial, staleToken)
    }

    /**
     * Offline cache gap catch-up entry (task #168 step 1): offer the applied catch-up page to the
     * durable V2 window. Fire-and-forget on the store scope like the paging extender: it never
     * touches the visible payload, and a failure is only logged.
     */
    private fun launchDurableLatestPageWrite(
        routePayload: RaftThreadScreenPayload,
        fetched: SharedThreadPageFetchResult,
        baseline: CanonicalMessageV2EnterBaseline? = null,
        revAtRequest: Long? = null,
    ) {
        val routeIdentity = routePayload.resolveThreadRouteIdentity()
        val replyParent = routePayload.durableReplyParentOrNull()
        // With a baseline, UNDISPATCHED: the body (and its finally) always runs, so the baseline is always
        // closed even when the scope is already cancelled. Without one, unchanged.
        scope.launch(start = if (baseline != null) CoroutineStart.UNDISPATCHED else CoroutineStart.DEFAULT) {
            try {
                val durablePage = fetched.durablePage
                if (durablePage == null) {
                    baseline?.let { dataSource.abandonDurableLatestPageBaseline(it) }
                    return@launch
                }
                dataSource.applyDurableLatestPageIfCurrent(
                    routeIdentity = routeIdentity,
                    context = durablePage.context,
                    rawPage = durablePage.rawPage,
                    replyParent = replyParent,
                    baseline = baseline,
                    headEpochAtRequest = durablePage.headEpochAtRequest,
                    revAtRequest = revAtRequest,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                diagnosticsLogger?.invoke(
                    "thread_durable_latest_page_failed reason=${throwable::class.simpleName ?: "unknown"}"
                )
            }
        }
    }

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
                !current.writeActionsEnabled ||
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

    private fun shouldPreserveReactionsForRealtime(message: Message, currentUserId: String?): Boolean {
        val messageId = message.id.value
        if (message.reactions == null || pendingReactionRealtimeGuards.none { it.value.messageId == messageId }) {
            return false
        }
        val incomingReactions = message.reactions.toRealtimeThreadReactionItems(currentUserId).orEmpty()
        var preserveReactions = false
        pendingReactionRealtimeGuards
            .values
            .filter { guard -> guard.messageId == messageId }
            .forEach { guard ->
                val incomingSelected = incomingReactions.selectedForEmoji(guard.emoji)
                if (incomingSelected == guard.selected) {
                    pendingReactionRealtimeGuards.remove(guard.key)
                } else {
                    preserveReactions = true
                    pendingReactionRealtimeGuards.remove(guard.key)
                    diagnosticsLogger?.invoke(
                        "thread_realtime_reaction_frame_stale messageId=$messageId emoji=${guard.emoji} " +
                            "expectedSelected=${guard.selected} incomingSelected=$incomingSelected"
                    )
                }
            }
        return preserveReactions
    }

    private fun confirmReactionRealtimeGuards(summary: MessageMutationSummary, currentUserId: String?) {
        val messageId = summary.messageId.trim()
        if (messageId.isBlank()) return
        val canonicalReactions = summary.reactions.toThreadReactionItems(currentUserId)
        pendingReactionRealtimeGuards
            .values
            .filter { guard -> guard.messageId == messageId }
            .forEach { guard ->
                if (canonicalReactions.selectedForEmoji(guard.emoji) != guard.selected) {
                    pendingReactionRealtimeGuards.remove(guard.key)
                }
            }
    }

    private fun clearReactionRealtimeGuards(messageId: String) {
        val cleanMessageId = messageId.trim()
        if (cleanMessageId.isBlank()) return
        pendingReactionRealtimeGuards
            .values
            .filter { guard -> guard.messageId == cleanMessageId }
            .forEach { guard -> pendingReactionRealtimeGuards.remove(guard.key) }
    }

    private suspend fun applyActionCard(messageId: String, card: ThreadActionCard) {
        reducePayloadPatchAndCache(cachePayload = true) { payload -> payload.withActionCard(messageId, card) }
    }

    private suspend fun applyMessageSaved(messageId: String, saved: Boolean) {
        reducePayloadPatchAndCache(cachePayload = true) { payload -> payload.withMessageSaved(messageId, saved) }
    }

    private suspend fun applyChannelJoined(channelId: String) {
        reducePayloadPatchAndCache(cachePayload = true) { payload -> payload.withChannelJoined(channelId) }
    }

    private suspend fun applyChannelLeft(channelId: String) {
        reducePayloadPatchAndCache(cachePayload = true) { payload -> payload.withChannelLeft(channelId) }
    }

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

private val PendingReactionRealtimeGuard.key: String
    get() = "$messageId\u0000$emoji"

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
