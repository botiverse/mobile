package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.InboxFetchReason
import build.raft.app.data.MviStaleToken
import build.raft.app.data.SharedForegroundLoadGate
import build.raft.app.data.ThreadReplySummariesSnapshot
import build.raft.app.data.withInboxFetchReason
import build.raft.app.network.sync.ThreadRepliesAcceptedProjection
import build.raft.app.network.sync.ThreadRepliesPageIdentity
import build.raft.app.network.sync.ThreadRepliesSyncHost
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What [SharedThreadStore] loads after the main content: the secondary timeline (reply summaries and
 * saved status), members, the home conversation context and a reply thread's origin message, plus the
 * first-content gate that the first two wait for.
 *
 * It owns [firstContentReadyRouteKey], [pendingAuxiliaryHydration] and [pendingSecondaryTimelineApply]; the
 * store resets them through accessors, at the same points as before.
 *
 * Its jobs launch on the store's scope, as before, and the store keeps their fields (it cancels them);
 * this class reads and writes them through accessors. [storeState] is the store's own flow: the members
 * status and the origin commit still write it directly, exactly where the store did; other writes go
 * through the store's `reducePayloadPatch`. Other store fields are read through getters, at each use.
 */
internal class ThreadSecondaryHydration(
    private val dataSource: SharedThreadDataSource,
    private val scope: CoroutineScope,
    private val diagnosticsLogger: ((String) -> Unit)?,
    private val beforeFocusedOriginCommit: (suspend (RaftThreadScreenPayload) -> Unit)?,
    private val threadRepliesSyncHost: ThreadRepliesSyncHost,
    private val storeState: MutableStateFlow<SharedThreadState>,
    private val currentRouteStaleToken: () -> MviStaleToken,
    private val getSecondaryTimelineRefreshJob: () -> Job?,
    private val setSecondaryTimelineRefreshJob: (Job?) -> Unit,
    private val getFocusedOriginHydrationJob: () -> Job?,
    private val setFocusedOriginHydrationJob: (Job?) -> Unit,
    private val getHomeContextHydrationJob: () -> Job?,
    private val setHomeContextHydrationJob: (Job?) -> Unit,
    private val getBackgroundMembersHydrationJob: () -> Job?,
    private val setBackgroundMembersHydrationJob: (Job?) -> Unit,
    private val getNextThreadRepliesRebaselineGeneration: () -> Long,
    private val setNextThreadRepliesRebaselineGeneration: (Long) -> Unit,
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
    releaseForegroundLoadLease: (expected: SharedForegroundLoadGate.Lease?, reason: String) -> Unit,
    captureThreadRepliesPageIdentity: (
        payload: RaftThreadScreenPayload,
        context: ReplyCacheProducerContext
    ) -> ThreadRepliesPageIdentity?,
    withAcceptedThreadRepliesRebaseline: (
        payload: RaftThreadScreenPayload,
        previous: RaftThreadScreenPayload,
        page: ThreadRepliesPageIdentity?,
        generation: Long?,
        expectedProvenance: ReplyCacheProvenance?
    ) -> RaftThreadScreenPayload,
) {
    private val storeReducePayloadPatch = reducePayloadPatch
    private val storeStaleTokenStillCurrent = staleTokenStillCurrent
    private val storeRouteStillCurrent = routeStillCurrent
    private val storeReleaseForegroundLoadLease = releaseForegroundLoadLease
    private val storeCaptureThreadRepliesPageIdentity = captureThreadRepliesPageIdentity
    private val storeWithAcceptedThreadRepliesRebaseline = withAcceptedThreadRepliesRebaseline

    var firstContentReadyRouteKey: String? = null
    var pendingAuxiliaryHydration: PendingAuxiliaryHydration? = null
    var pendingSecondaryTimelineApply: PendingSecondaryTimelineApply? = null

    data class PendingAuxiliaryHydration(
        val routePayload: RaftThreadScreenPayload,
        val staleToken: MviStaleToken,
        val launchSecondaryTimelineRefresh: Boolean,
        val launchMembersHydration: Boolean
    )

    data class PendingSecondaryTimelineApply(
        val routePayload: RaftThreadScreenPayload,
        val staleToken: MviStaleToken,
        val apply: suspend () -> Unit,
    )

    /** The store's route stale token, read at each use. */
    private val routeStaleToken: MviStaleToken
        get() = currentRouteStaleToken()

    /** The store's field, read and written at each use. */
    private var secondaryTimelineRefreshJob: Job?
        get() = getSecondaryTimelineRefreshJob()
        set(value) {
            setSecondaryTimelineRefreshJob(value)
        }

    /** The store's field, read and written at each use. */
    private var focusedOriginHydrationJob: Job?
        get() = getFocusedOriginHydrationJob()
        set(value) {
            setFocusedOriginHydrationJob(value)
        }

    /** The store's field, read and written at each use. */
    private var homeContextHydrationJob: Job?
        get() = getHomeContextHydrationJob()
        set(value) {
            setHomeContextHydrationJob(value)
        }

    /** The store's field, read and written at each use. */
    private var backgroundMembersHydrationJob: Job?
        get() = getBackgroundMembersHydrationJob()
        set(value) {
            setBackgroundMembersHydrationJob(value)
        }

    /** The store's counter (refresh uses it too), read and written at each use. */
    private var nextThreadRepliesRebaselineGeneration: Long
        get() = getNextThreadRepliesRebaselineGeneration()
        set(value) {
            setNextThreadRepliesRebaselineGeneration(value)
        }

    private fun reducePayloadPatch(
        cachePayload: Boolean = false,
        patch: (RaftThreadScreenPayload) -> RaftThreadScreenPayload
    ): Boolean = storeReducePayloadPatch(cachePayload, patch)

    private fun staleTokenStillCurrent(
        staleToken: MviStaleToken,
        source: String,
        stage: String,
        routePayload: RaftThreadScreenPayload,
        currentPayload: RaftThreadScreenPayload = storeState.value.payload
    ): Boolean = storeStaleTokenStillCurrent(staleToken, source, stage, routePayload, currentPayload)

    private fun routeStillCurrent(
        routePayload: RaftThreadScreenPayload,
        source: String,
        stage: String,
        currentPayload: RaftThreadScreenPayload = storeState.value.payload
    ): Boolean = storeRouteStillCurrent(routePayload, source, stage, currentPayload)

    private fun releaseForegroundLoadLease(expected: SharedForegroundLoadGate.Lease?, reason: String) =
        storeReleaseForegroundLoadLease(expected, reason)

    private fun captureThreadRepliesPageIdentity(
        payload: RaftThreadScreenPayload,
        context: ReplyCacheProducerContext,
    ): ThreadRepliesPageIdentity? = storeCaptureThreadRepliesPageIdentity(payload, context)

    private fun RaftThreadScreenPayload.withAcceptedThreadRepliesRebaseline(
        previous: RaftThreadScreenPayload,
        page: ThreadRepliesPageIdentity?,
        generation: Long?,
        expectedProvenance: ReplyCacheProvenance? = null,
    ): RaftThreadScreenPayload =
        storeWithAcceptedThreadRepliesRebaseline(this, previous, page, generation, expectedProvenance)

    fun queueAuxiliaryHydration(
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

    fun markFirstContentReady() {
        releaseForegroundLoadLease(expected = null, reason = "first_content_ready")
        val currentPayload = storeState.value.payload
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
        val currentPayload = storeState.value.payload
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

    fun launchReplyOriginHydrationIfNeeded(
        routePayload: RaftThreadScreenPayload,
        staleToken: MviStaleToken
    ) {
        val currentPayload = storeState.value.payload
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
                val latest = storeState.value.payload
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
                val payloadBeforeCommit = storeState.value.payload
                beforeFocusedOriginCommit?.invoke(payloadBeforeCommit)
                var committedPayload: RaftThreadScreenPayload? = null
                var emitted = false
                dataSource.withCurrentReplyCacheProvenance(originContext.provenance) {
                    storeState.update { currentState ->
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

    fun launchBackgroundHomeContextHydration(routePayload: RaftThreadScreenPayload) {
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

    fun launchSecondaryTimelineRefresh(
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
                val latest = storeState.value.payload
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
        val beforeFetch = storeState.value.payload
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
        val latest = storeState.value.payload
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
            val currentPayload = storeState.value.payload
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
            if (emitted) committedPayload = storeState.value.payload
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
    suspend fun loadMembers(force: Boolean = false) {
        val membersStartedAt = TimeSource.Monotonic.markNow()
        val existing = storeState.value.payload
        val basePayload = existing
        val threadKey = existing.sourceThreadId.orEmpty()
        if (threadKey.isBlank()) return
        val currentState = storeState.value
        if (currentState.membersLoadKey != threadKey) {
            // Switched threads: reset status so the previous verdict does not leak.
            storeState.value = currentState.copy(
                membersLoadKey = threadKey,
                membersLoadStatus = LoadMembersStatus.Idle
            )
        }
        val resolvedStatus = storeState.value.membersLoadStatus
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
        storeState.value = storeState.value.copy(membersLoadStatus = LoadMembersStatus.InFlight)
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
                storeState.value = storeState.value.copy(membersLoadStatus = LoadMembersStatus.Loaded)
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
                storeState.value = storeState.value.copy(membersLoadStatus = LoadMembersStatus.Loaded)
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
                storeState.value = storeState.value.copy(membersLoadStatus = LoadMembersStatus.Error)
                diagnosticsLogger?.invoke(
                    "thread_members_load_result threadId=$threadKey " +
                        "result=failed reason=${outcome.reason} " +
                        "members=${existing.members.size} candidates=${existing.memberCandidates.size} " +
                        "durationMs=${membersStartedAt.elapsedNow().inWholeMilliseconds}"
                )
            }
        }
    }
}
