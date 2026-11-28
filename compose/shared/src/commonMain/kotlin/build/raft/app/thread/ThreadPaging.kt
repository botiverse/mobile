package build.raft.app.thread

import build.raft.app.thread.SharedThreadStore.LoadOlderState
import build.raft.app.thread.resolveThreadRouteIdentity
import build.raft.app.threadPayloadOwnerScopeId
import build.raft.app.traceId
import build.raft.app.data.DataResult
import build.raft.app.data.InboxFetchReason
import build.raft.app.data.MviStaleToken
import build.raft.app.get
import build.raft.app.messages
import build.raft.app.network.sync.ThreadDurablePageDirection
import build.raft.app.resolveThreadRouteIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Older-page loading (network, offline stand-in and proven contiguous SQLite segment) and the
 * durable window extension of an applied page, for [SharedThreadStore].
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
            // Task #168 step 2b / Phase 2d / #proj-mobile:#388:
            // - A timeline on screen is ALWAYS one single contiguous slice of messages: local paging
            //   serves strictly from inside a proven contiguous SQLite segment (never bridging gaps).
            // - While offline (`durableLastKnown && mainLoadFailed`), hitting the contiguous segment
            //   edge closes older paging cleanly (`canLoadMoreBefore = false`, preserving
            //   `durableReachedOldest`) so the single outer boundary row shows "Older messages are not
            //   available offline" instead of hitting network or inserting mid-timeline gap rows.
            // - While online (or while initial refresh is still in flight), hitting the segment edge
            //   fetches `before=beforeSeq` from the server seamlessly.
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
                        val merged =
                            latest
                                .mergePagedMessages(
                                    pageMessages = result.data.payload.messages,
                                    before = true,
                                    pageSize = result.data.requestedPageSize,
                                    rawServerRowCount = result.data.rawServerRowCount,
                                )
                                .preserveStableTimelineFrom(latest)
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
                settled = false
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
     * Task #168 step 2b / #proj-mobile:#388: serve one older page of the durable stand-in strictly
     * from inside its proven contiguous SQLite segment (or legacy window). While offline
     * (`mainLoadFailed`), reaching the segment edge closes older paging cleanly without bridging
     * disconnected islands or hitting the network so the single outer boundary row renders
     * "Older messages are not available offline". While online (`!mainLoadFailed`), a segment edge
     * returns [LocalOlderPageResult.NotServed] so [loadOlder] fetches from the server seamlessly.
     */
    private suspend fun loadOlderFromDurableStandIn(
        existing: RaftThreadScreenPayload,
        beforeSeq: Long,
        staleToken: MviStaleToken,
    ): LocalOlderPageResult {
        val context =
            dataSource.captureReplyCacheProducerContext()
                ?: return if (existing.mainLoadFailed) LocalOlderPageResult.Dropped else LocalOlderPageResult.NotServed
        val expectedOwnerScopeId = visiblePayloadOwnerScopeId
        val page =
            dataSource.durableOlderPageIfCurrent(
                routeIdentity = existing.resolveThreadRouteIdentity(),
                context = context,
                beforeSeq = beforeSeq,
                // The stand-in on screen belongs to the owner it was read for; another owner never pages it.
                expectedOwnerScopeId = expectedOwnerScopeId,
                replyParent = existing.durableReplyParentOrNull(),
                allowLegacyStandIn = true,
            )
        if (page == null) {
            if (context.provenance.threadPayloadOwnerScopeId != expectedOwnerScopeId) {
                return LocalOlderPageResult.Dropped
            }
            if (!existing.mainLoadFailed) return LocalOlderPageResult.NotServed
            var closed = false
            reducePayloadPatch { currentPayload ->
                closed = false
                if (
                    !currentPayload.durableLastKnown ||
                    !currentPayload.mainLoadFailed ||
                    !staleTokenStillCurrent(staleToken, "thread_page", "durable_older_segment_edge", existing, currentPayload) ||
                    !routeStillCurrent(existing, "thread_page", "durable_older_segment_edge", currentPayload)
                ) {
                    return@reducePayloadPatch currentPayload
                }
                closed = true
                currentPayload.copy(
                    canLoadMoreBefore = false,
                    durableHasOlderStored = false,
                    olderMessagesStatus = "idle",
                )
            }
            return if (closed) LocalOlderPageResult.Applied else LocalOlderPageResult.Dropped
        }
        if (!page.insideProvenSegment) return LocalOlderPageResult.NotServed
        var applied = false
        reducePayloadPatch { currentPayload ->
            applied = false
            if (
                !currentPayload.durableLastKnown ||
                !staleTokenStillCurrent(staleToken, "thread_page", "durable_older_apply", existing, currentPayload) ||
                !routeStillCurrent(existing, "thread_page", "durable_older_apply", currentPayload)
            ) {
                return@reducePayloadPatch currentPayload
            }
            applied = true
            val nextCanLoadMoreBefore =
                if (currentPayload.mainLoadFailed) {
                    page.hasOlderStored
                } else {
                    page.canLoadMoreBefore
                }
            if (page.messages.isEmpty()) {
                currentPayload.copy(
                    canLoadMoreBefore = nextCanLoadMoreBefore,
                    durableReachedOldest = page.reachedOldest,
                    durableHasOlderStored = page.hasOlderStored,
                    olderMessagesStatus = "idle",
                )
            } else {
                val merged =
                    currentPayload.mergePagedMessages(
                        pageMessages = page.messages,
                        before = true,
                        pageSize = page.pageSize,
                    )
                merged.copy(
                    canLoadMoreBefore = nextCanLoadMoreBefore,
                    durableReachedOldest = page.reachedOldest,
                    durableHasOlderStored = page.hasOlderStored,
                    olderMessagesStatus = "idle",
                )
            }
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
     * Phase 2d: online older paging inside a proven contiguous SQLite segment.
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
            ) ?: return LocalOlderPageResult.NotServed
        if (!page.insideProvenSegment) return LocalOlderPageResult.NotServed
        var applied = false
        var committedForCache: RaftThreadScreenPayload? = null
        reducePayloadPatch(cachePayload = false) { currentPayload ->
            applied = false
            committedForCache = null
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
                        durableHasOlderStored = false,
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
                        durableHasOlderStored = page.hasOlderStored,
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
}
