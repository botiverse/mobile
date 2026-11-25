package build.raft.app

import build.raft.app.SharedThreadStore.LoadNewerState
import build.raft.app.SharedThreadStore.RealtimeGapRecoveryState
import build.raft.app.SharedThreadStore.ReplyCountRealtimeGap
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.InboxFetchReason
import build.raft.app.data.MviStaleToken
import build.raft.app.data.realtime.RealtimeVisibleStreamGapPolicy
import build.raft.app.data.realtime.RealtimeVisibleStreamGapRequest
import build.raft.app.model.ChannelId
import build.raft.app.network.sync.CanonicalMessageV2EnterBaseline
import build.raft.app.network.sync.ThreadDurablePageDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Newer-page loading and realtime gap recovery (heartbeat / reply-count gaps, resume catch-up with
 * its retry, the reply join-ack catch-up) for [SharedThreadStore].
 *
 * They move together because they share [realtimeGapRecoveryMutex] and the state it guards:
 * [loadNewerState] (incl. `pendingTargetSeq`, raised by a gap recovery while a newer page is in
 * flight) and [realtimeGapRecoveryState]. This class owns that Mutex and state. Realtime folding
 * ([ThreadRealtimeFolding]), and the join-ack check (`catchUpAfterThreadJoinConfirmed`) and refresh in
 * the store, read/write them through accessors, under the same Mutex as before.
 *
 * The store dispatches into it on its own scope and it starts no scope of its own: the catch-up job
 * and the fire-and-forget durable writes launch on the store's scope, as before. The store keeps
 * `realtimeGapRecoveryJob` (clear() cancels it) and its test hook; this class reads and writes them
 * through accessors. Store state is read only through [currentState]; writes go through the store's
 * `reducePayloadPatch` / `markConversationAccessLost`, and stale / route checks call the store's,
 * so they run at the same points as before.
 */
internal class ThreadRealtimeGapRecovery(
    private val dataSource: SharedThreadDataSource,
    private val scope: CoroutineScope,
    private val diagnosticsLogger: ((String) -> Unit)?,
    private val threadPaging: ThreadPaging,
    private val currentState: () -> SharedThreadState,
    private val currentRouteStaleToken: () -> MviStaleToken,
    private val currentVisiblePayloadOwnerScopeId: () -> String?,
    private val currentViewportFollowsLatest: () -> Boolean,
    private val getRealtimeGapRecoveryJob: () -> Job?,
    private val setRealtimeGapRecoveryJob: (Job?) -> Unit,
    private val currentOnRealtimeGapRecoveryLaunchedForTest: () -> ((Job) -> Unit)?,
    reducePayloadPatch: (
        cachePayload: Boolean,
        patch: (RaftThreadScreenPayload) -> RaftThreadScreenPayload
    ) -> Boolean,
    staleTokenStillCurrent: (
        staleToken: MviStaleToken,
        source: String,
        stage: String,
        routePayload: RaftThreadScreenPayload,
        currentPayload: RaftThreadScreenPayload
    ) -> Boolean,
    routeStillCurrent: (
        routePayload: RaftThreadScreenPayload,
        source: String,
        stage: String,
        currentPayload: RaftThreadScreenPayload
    ) -> Boolean,
    launchSecondaryTimelineRefresh: (
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken,
        fetchReason: InboxFetchReason
    ) -> Unit,
    markConversationAccessLost: (errorCode: String) -> Unit,
) {
    private val storeReducePayloadPatch = reducePayloadPatch
    private val storeStaleTokenStillCurrent = staleTokenStillCurrent
    private val storeRouteStillCurrent = routeStillCurrent
    private val storeLaunchSecondaryTimelineRefresh = launchSecondaryTimelineRefresh
    private val storeMarkConversationAccessLost = markConversationAccessLost

    val realtimeGapRecoveryMutex = Mutex()
    var realtimeGapRecoveryState: RealtimeGapRecoveryState? = null
    var loadNewerState: LoadNewerState? = null
        private set
    var nextRealtimeGapRecoveryId: Long = 0L
    // Feedback 4409cbc5: a resume catch-up that fails was never asked again, so the thread kept showing its
    // cached messages until the user left. Counts retryable catch-up failures; a fresh resume bumps the generation.
    var gapCatchUpRetryableFailures: Long = 0L
        private set
    private var resumeCatchUpGeneration: Long = 0L
    private var nextLoadNewerId: Long = 0L

    /** The store's route stale token, read at each use. */
    private val routeStaleToken: MviStaleToken
        get() = currentRouteStaleToken()

    /** The store's visible payload owner scope, read at each use. */
    private val visiblePayloadOwnerScopeId: String?
        get() = currentVisiblePayloadOwnerScopeId()

    /** The store's viewport fact ([SharedThreadStore.setViewportFollowsLatest]), read at each use. */
    private val viewportFollowsLatest: Boolean
        get() = currentViewportFollowsLatest()

    /** The store's field ([SharedThreadStore.clear] cancels it), read and written at each use. */
    private var realtimeGapRecoveryJob: Job?
        get() = getRealtimeGapRecoveryJob()
        set(value) {
            setRealtimeGapRecoveryJob(value)
        }

    /** The store's test hook, read at each use. */
    private val onRealtimeGapRecoveryLaunchedForTest: ((Job) -> Unit)?
        get() = currentOnRealtimeGapRecoveryLaunchedForTest()

    private fun reducePayloadPatch(
        cachePayload: Boolean = false,
        patch: (RaftThreadScreenPayload) -> RaftThreadScreenPayload
    ): Boolean = storeReducePayloadPatch(cachePayload, patch)

    private fun staleTokenStillCurrent(
        staleToken: MviStaleToken,
        source: String,
        stage: String,
        routePayload: RaftThreadScreenPayload,
        currentPayload: RaftThreadScreenPayload
    ): Boolean = storeStaleTokenStillCurrent(staleToken, source, stage, routePayload, currentPayload)

    private fun routeStillCurrent(
        routePayload: RaftThreadScreenPayload,
        source: String,
        stage: String,
        currentPayload: RaftThreadScreenPayload
    ): Boolean = storeRouteStillCurrent(routePayload, source, stage, currentPayload)

    private fun launchSecondaryTimelineRefresh(
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken,
        fetchReason: InboxFetchReason = InboxFetchReason.Initial,
    ) = storeLaunchSecondaryTimelineRefresh(routePayload, staleToken, fetchReason)

    private fun markConversationAccessLost(errorCode: String) = storeMarkConversationAccessLost(errorCode)

    suspend fun loadNewer(): ThreadNewerPageLoadOutcome {
        val initial = currentState().payload
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
                val currentPayload = currentState().payload
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
                    val currentPayload = currentState().payload
                    if (
                        !staleTokenStillCurrent(staleToken, "thread_page", "newer_local_apply", initial, currentPayload) ||
                        !routeStillCurrent(initial, "thread_page", "newer_local_apply", currentPayload) ||
                        currentPayload.durableLastKnown != initial.durableLastKnown
                    ) {
                        if (
                            currentPayload.newerMessagesStatus == "loading" &&
                            currentPayload.isUsableCachedPayloadForRoute(initial)
                        ) {
                            reducePayloadPatch(cachePayload = !currentPayload.durableLastKnown) { latestPayload ->
                                if (latestPayload.newerMessagesStatus == "loading") {
                                    latestPayload.copy(newerMessagesStatus = "idle")
                                } else {
                                    latestPayload
                                }
                            }
                        }
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
                        val appliedIds = currentState().payload.messages.mapTo(mutableSetOf()) { it.id }
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
                                currentState().payload to
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
                    val currentPayload = currentState().payload
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
                        if (
                            currentPayload.newerMessagesStatus == "loading" &&
                            currentPayload.isUsableCachedPayloadForRoute(initial)
                        ) {
                            reducePayloadPatch(cachePayload = !currentPayload.durableLastKnown) { latestPayload ->
                                if (latestPayload.newerMessagesStatus == "loading") {
                                    latestPayload.copy(newerMessagesStatus = "idle")
                                } else {
                                    latestPayload
                                }
                            }
                        }
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
                                            pageSize = result.data.requestedPageSize,
                                            rawServerRowCount = result.data.rawServerRowCount,
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
                        threadPaging.launchDurableWindowExtension(initial, result.data, ThreadDurablePageDirection.Newer, afterSeq)
                    }
                    if (appliedToCurrentRoute) {
                        outcome =
                            when (result) {
                                is DataResult.Content -> {
                                    val existingIds = currentPayload.messages.mapTo(mutableSetOf()) { it.id }
                                    val appliedIds = currentState().payload.messages.mapTo(mutableSetOf()) { it.id }
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
                                currentState().payload to
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
                    val currentPayload = currentState().payload
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

    suspend fun recoverRealtimeGap(serverSeq: Long) {
        val initial = currentState().payload
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

    suspend fun recoverRealtimeGapFromVisibleTail(replyCountGap: ReplyCountRealtimeGap) {
        val initial = currentState().payload
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

    suspend fun refreshLatestAfterVisibleMaxSeq(retryAttempt: Int = 0) {
        val generation = if (retryAttempt == 0) ++resumeCatchUpGeneration else resumeCatchUpGeneration
        val initial = currentState().payload
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

    suspend fun recoverRealtimeGap(
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
        if (currentState().conversationAccessLost) return
        val routeIdentity = initial.realtimeGapRouteIdentity()
        var requestSnapshot: RaftThreadScreenPayload? = null
        val recoveryId =
            realtimeGapRecoveryMutex.withLock {
                val currentPayload = currentState().payload
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
    fun launchRealtimeGapRecovery(
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
                    val currentPayload = currentState().payload
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
                val currentPayload = currentState().payload
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
                        val currentPayload = currentState().payload
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
        val latest = currentState().payload
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
        launchSecondaryTimelineRefresh(initial, staleToken, fetchReason = InboxFetchReason.Visibility)
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
}
