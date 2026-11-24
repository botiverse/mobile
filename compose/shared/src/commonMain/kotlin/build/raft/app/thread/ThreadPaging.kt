package build.raft.app

import build.raft.app.SharedThreadStore.LoadOlderState
import build.raft.app.data.DataResult
import build.raft.app.data.InboxFetchReason
import build.raft.app.data.MviStaleToken
import build.raft.app.network.sync.ThreadDurablePageDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Older-page loading (network, offline stand-in and proven SQLite segment), inline timeline-gap
 * fills and the durable window extension of an applied page, for [SharedThreadStore].
 *
 * The store dispatches into it on its own scope and it starts no scope or Job of its own (the
 * fire-and-forget durable writes launch on the store's scope, as before). Store state is read and
 * written only through the accessors (the store's `_state`, route stale token, visible payload
 * owner, `reducePayloadPatch` and stale/route checks), so stale checks run at the same points as
 * before. [LoadOlderState] stays owned by the store (start, refresh and clear reset it); this class
 * reads and writes it through [getLoadOlderState] / [setLoadOlderState]. It takes no lock: newer
 * paging stays in the store because it shares `realtimeGapRecoveryMutex` and `loadNewerState` with
 * realtime gap recovery and realtime folding.
 */
internal class ThreadPaging(
    private val dataSource: SharedThreadDataSource,
    private val scope: CoroutineScope,
    private val diagnosticsLogger: ((String) -> Unit)?,
    private val currentState: () -> SharedThreadState,
    private val currentRouteStaleToken: () -> MviStaleToken,
    private val currentVisiblePayloadOwnerScopeId: () -> String?,
    private val getLoadOlderState: () -> LoadOlderState?,
    private val setLoadOlderState: (LoadOlderState?) -> Unit,
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
) {
    private val storeReducePayloadPatch = reducePayloadPatch
    private val storeStaleTokenStillCurrent = staleTokenStillCurrent
    private val storeRouteStillCurrent = routeStillCurrent
    private val storeLaunchSecondaryTimelineRefresh = launchSecondaryTimelineRefresh

    private var nextLoadOlderId: Long = 0L

    private enum class LocalOlderPageResult { NotServed, Applied, Dropped }

    /** The store's route stale token, read at each use. */
    private val routeStaleToken: MviStaleToken
        get() = currentRouteStaleToken()

    /** The store's visible payload owner scope, read at each use. */
    private val visiblePayloadOwnerScopeId: String?
        get() = currentVisiblePayloadOwnerScopeId()

    /** The store's field, read and written at each use. */
    private var loadOlderState: LoadOlderState?
        get() = getLoadOlderState()
        set(value) {
            setLoadOlderState(value)
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
        fetchReason: InboxFetchReason
    ) = storeLaunchSecondaryTimelineRefresh(routePayload, staleToken, fetchReason)

    suspend fun loadOlder() {
        val existing = currentState().payload
        if (!existing.canLoadMoreBefore || existing.olderMessagesStatus == "loading") return
        val staleToken = routeStaleToken
        val beforeSeq = existing.messages.mapNotNull { it.seq }.minOrNull()
        if (beforeSeq == null) {
            reducePayloadPatch { currentPayload ->
                currentPayload.copy(olderMessagesStatus = "error", errorCode = "missing_before_seq")
            }
            return
        }
        val loadId = ++nextLoadOlderId
        loadOlderState =
            LoadOlderState(
                id = loadId,
                routeIdentity = existing.resolveThreadRouteIdentity(),
                staleToken = staleToken,
            )
        var settled = false
        reducePayloadPatch { currentPayload -> currentPayload.copy(olderMessagesStatus = "loading", errorCode = null) }
        try {
            // Task #168 step 2b / Phase 2d:
            // - While the offline stand-in is shown, older pages come from SQLite (crossing into lower islands
            //   with derived gaps) before asking the network.
            // - Online, older pages inside a proven contiguous SQLite segment are served directly from disk
            //   with zero network RTT; at a segment edge we fetch `before=beforeSeq` from the server.
            if (existing.durableLastKnown) {
                when (loadOlderFromDurableStandIn(existing, beforeSeq, staleToken)) {
                    LocalOlderPageResult.Applied -> {
                        settled = true
                        return
                    }
                    LocalOlderPageResult.Dropped -> return
                    LocalOlderPageResult.NotServed -> Unit
                }
            } else {
                when (loadOlderFromProvenLocalSegment(existing, beforeSeq, staleToken)) {
                    LocalOlderPageResult.Applied -> {
                        settled = true
                        return
                    }
                    LocalOlderPageResult.Dropped -> return
                    LocalOlderPageResult.NotServed -> Unit
                }
            }
            val result = dataSource.fetchOlderThreadPayload(existing, beforeSeq)
            val latest = currentState().payload
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
                                    pageSize = result.data.requestedPageSize,
                                    rawServerRowCount = result.data.rawServerRowCount,
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
                settled = true
                currentPayload.withThreadContentProjectionFrom(nextPayload)
            }
            val applied = currentState().payload
            if (
                settled &&
                result is DataResult.Content &&
                staleToken.isCurrent(routeStaleToken) &&
                routeStillCurrent(existing, "thread_page", "older_secondary", applied)
            ) {
                launchSecondaryTimelineRefresh(applied, staleToken, fetchReason = InboxFetchReason.Manual)
                launchDurableWindowExtension(existing, result.data, ThreadDurablePageDirection.Older, beforeSeq)
            }
        } finally {
            // Task #259: a dropped, superseded, or cancelled older-page request must never leave
            // `olderMessagesStatus == "loading"` stuck on the current route.
            val stillOwner = loadOlderState?.id == loadId
            if (stillOwner) {
                loadOlderState = null
            }
            if (!settled && stillOwner) {
                reducePayloadPatch { currentPayload ->
                    if (
                        currentPayload.olderMessagesStatus == "loading" &&
                        currentPayload.isUsableCachedPayloadForRoute(existing)
                    ) {
                        currentPayload.copy(olderMessagesStatus = "idle")
                    } else {
                        currentPayload
                    }
                }
            }
        }
    }

    /**
     * Offline cache paging entry (task #168): offer an applied older / newer page to the durable V2
     * window. Fire-and-forget on the store scope like the enter snapshot: it never touches the
     * visible payload, and a failure is only logged.
     */
    fun launchDurableWindowExtension(
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
    ): LocalOlderPageResult {
        val context = dataSource.captureReplyCacheProducerContext() ?: return LocalOlderPageResult.NotServed
        val page =
            dataSource.durableOlderPageIfCurrent(
                routeIdentity = existing.resolveThreadRouteIdentity(),
                context = context,
                beforeSeq = beforeSeq,
                // The stand-in on screen belongs to the owner it was read for; another owner never pages it.
                expectedOwnerScopeId = visiblePayloadOwnerScopeId,
                replyParent = existing.durableReplyParentOrNull(),
                crossGaps = true,
            ) ?: return LocalOlderPageResult.NotServed
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
            return LocalOlderPageResult.Applied
        } else {
            diagnosticsLogger?.invoke(
                "thread_durable_older_page threadId=${existing.sourceThreadId.orEmpty()} " +
                    "served=0 reason=stand_in_replaced"
            )
            return LocalOlderPageResult.Dropped
        }
    }

    /**
     * Phase 2d: online older paging inside a proven contiguous SQLite segment (`crossGaps = false`).
     * Returns [LocalOlderPageResult.Applied] when disk satisfied the page (or proved the channel's oldest
     * boundary) without a network request, [LocalOlderPageResult.Dropped] when the route became stale or
     * replaced while reading, or [LocalOlderPageResult.NotServed] at a segment edge so [loadOlder] fetches
     * `before=beforeSeq`.
     */
    private suspend fun loadOlderFromProvenLocalSegment(
        existing: RaftThreadScreenPayload,
        beforeSeq: Long,
        staleToken: MviStaleToken,
    ): LocalOlderPageResult {
        val context = dataSource.captureReplyCacheProducerContext() ?: return LocalOlderPageResult.NotServed
        val page =
            dataSource.durableOlderPageIfCurrent(
                routeIdentity = existing.resolveThreadRouteIdentity(),
                context = context,
                beforeSeq = beforeSeq,
                expectedOwnerScopeId = visiblePayloadOwnerScopeId,
                replyParent = existing.durableReplyParentOrNull(),
                crossGaps = false,
            ) ?: return LocalOlderPageResult.NotServed
        if (!page.insideProvenSegment) return LocalOlderPageResult.NotServed
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
            return LocalOlderPageResult.Applied
        } else {
            diagnosticsLogger?.invoke(
                "thread_durable_older_page threadId=${existing.sourceThreadId.orEmpty()} " +
                    "served=0 reason=replaced_or_stale"
            )
            return LocalOlderPageResult.Dropped
        }
    }

    /**
     * Phase 2d: fetch older messages from the server to bridge or shrink an inline timeline gap
     * `(afterSeq, beforeSeq)` and persist the fetched page to SQLite so segments coalesce.
     */
    suspend fun fillTimelineGap(afterSeq: Long, beforeSeq: Long) {
        val existing = currentState().payload
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
        var applied = false
        var committedForCache: RaftThreadScreenPayload? = null
        try {
            val result = dataSource.fetchOlderThreadPayload(existing, beforeSeq)
            val latest = currentState().payload
            if (
                !staleTokenStillCurrent(staleToken, "thread_page", "gap_fill_after_fetch", existing, latest) ||
                !routeStillCurrent(existing, "thread_page", "gap_fill_after_fetch", latest)
            ) {
                return
            }
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
                                rawServerRowCount = result.data.rawServerRowCount,
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
            val appliedPayload = currentState().payload
            if (
                applied &&
                result is DataResult.Content &&
                staleToken.isCurrent(routeStaleToken) &&
                routeStillCurrent(existing, "thread_page", "gap_fill_secondary", appliedPayload)
            ) {
                launchSecondaryTimelineRefresh(appliedPayload, staleToken, fetchReason = InboxFetchReason.Manual)
                launchDurableWindowExtension(existing, result.data, ThreadDurablePageDirection.Older, beforeSeq)
            }
        } finally {
            if (!applied) {
                reducePayloadPatch { currentPayload ->
                    if (
                        currentPayload.isUsableCachedPayloadForRoute(existing) &&
                        currentPayload.timelineGaps.any { it.afterSeq == afterSeq && it.beforeSeq == beforeSeq && it.status == "loading" }
                    ) {
                        currentPayload.copy(
                            timelineGaps =
                                currentPayload.timelineGaps.map { gap ->
                                    if (gap.afterSeq == afterSeq && gap.beforeSeq == beforeSeq && gap.status == "loading") {
                                        gap.copy(status = "idle")
                                    } else {
                                        gap
                                    }
                                },
                        )
                    } else {
                        currentPayload
                    }
                }
            }
        }
    }}
