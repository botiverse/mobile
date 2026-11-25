package build.raft.app

import build.raft.app.data.RawMessagePage
import build.raft.app.network.sync.CanonicalMessageV2EnterBaseline
import build.raft.app.network.sync.CanonicalMessageV2ThreadJoinConfirmed
import build.raft.app.network.sync.RaftCachedThreadReplyWindow
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurableAccessEvictor
import build.raft.app.network.sync.ThreadDurableEnterTicket
import build.raft.app.network.sync.ThreadDurableEnteredPage
import build.raft.app.network.sync.ThreadDurableLastKnownReader
import build.raft.app.network.sync.ThreadDurablePageDirection
import build.raft.app.network.sync.ThreadDurablePageExtender
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.ThreadDurableRequestStamp
import build.raft.app.network.sync.ThreadDurableTimelineAnchor
import build.raft.app.network.sync.ThreadEnteredScopeSnapshotLoader
import build.raft.app.network.sync.ThreadRepliesAcceptedProjection
import build.raft.app.network.sync.ThreadRepliesPageIdentity
import build.raft.app.network.sync.toThreadMessageItem
import build.raft.app.network.sync.windowEnvelopes
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext

/**
 * The durable cache paths of [SharedThreadDataSource] (the `sync-core.messages.v2` first frame and
 * pages, the write-through of fetched pages, reply-preview windows, lost-access eviction and which
 * scopes they may use), moved out of it unchanged; the data source forwards to them. Holds no state
 * of its own; takes the lock owned by [ThreadReplyCacheProvenanceOwner] at the same point as before.
 */
internal class ThreadDurableCacheGateway(
    private val provenanceOwner: ThreadReplyCacheProvenanceOwner,
    private val payloadCache: SharedThreadPayloadStore,
    private val durableLastKnownReader: ThreadDurableLastKnownReader?,
    private val enteredScopeSnapshotLoader: ThreadEnteredScopeSnapshotLoader?,
    private val durablePageExtender: ThreadDurablePageExtender?,
    private val durableAccessEvictor: ThreadDurableAccessEvictor?,
    private val durableDbDispatcher: CoroutineDispatcher,
    private val diagnosticsLogger: (String) -> Unit,
    private val readReplyResolutionScope: suspend () -> ReplyResolutionScope?,
    private val threadPageSize: (String?) -> Int,
) {
    private val replyCacheProvenanceLock: SynchronizedObject get() = provenanceOwner.replyCacheProvenanceLock
    private val replyCacheProvenance: StateFlow<ReplyCacheProvenance?> get() = provenanceOwner.replyCacheProvenanceState

    private fun currentReplyCacheProvenance(): ReplyCacheProvenance? = provenanceOwner.currentReplyCacheProvenance()

    private fun isCurrentReplyCacheProvenance(expected: ReplyCacheProvenance): Boolean =
        provenanceOwner.isCurrentReplyCacheProvenance(expected)

    /**
     * Phase 3b: the durable partition's head epoch, read BEFORE a page's HTTP request and handed to the durable
     * writer with that page: a horizonReset in between voids the page's head claim (never its facts).
     */
    internal fun durableHeadEpochOrNull(provenance: ReplyCacheProvenance): Long? {
        val partition = provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID) ?: return null
        return durablePageExtender?.headEpoch(partition) ?: enteredScopeSnapshotLoader?.headEpoch(partition)
    }

    /** Phase 2c: the durable write revision of [provenance]'s partition, read BEFORE a page fetch (`revAtRequest`). */
    internal suspend fun durableLocalRevOrNull(provenance: ReplyCacheProvenance): Long? {
        val partition = provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID) ?: return null
        return durablePageExtender?.currentLocalRev(partition)
    }

    /**
     * Durable last-known first frame for a top-level route (channel / DM) or, task #168 step 3, a
     * reply-thread route with its typed parent, read from the `sync-core.messages.v2` partition of
     * [context]'s identity. Returns `null` for every non-positive case (no reader, reply route
     * without its typed parent or whose stored parent differs, unrenderable route, incomplete identity,
     * identity moved during the disk read, flag off, no baseline, projection produced nothing) so the
     * caller falls through to the ordinary loading shell. The returned payload is a stand-in, not an
     * authority: paging is closed and `durableLastKnown` is set so the first network content slice
     * replaces it wholesale.
     */
    /**
     * Offline cache write entry (task #1124; message storage Phase 2b): called BEFORE the thread store's own page
     * request for [routeIdentity]. Same admission as [durableLastKnownPayloadIfCurrent] (renderable route, resolvable
     * channel id, an owning partition, current provenance). Returns the writer's ticket for
     * [persistEnteredPageIfCurrent], or `null` (nothing will be written for this request).
     */
    internal suspend fun beginEnteredPageIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        replyParent: ThreadDurableReplyParent? = null,
    ): ThreadDurableEnterTicket? {
        val loader = enteredScopeSnapshotLoader ?: return null
        if (!routeIdentity.canRenderTimelineCandidates) return null
        val admission = durableScopeAdmission(routeIdentity, replyParent) ?: return null
        val channelId = routeIdentity.timelineChannelId ?: return null
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return null
        if (!isCurrentReplyCacheProvenance(context.provenance)) return null
        return loader.beginEnter(partition, channelId, (admission as? DurableScopeAdmission.ReplyThread)?.parent)
    }

    /** Phase 2b: whether a durable page writer is wired (the write-through is attempted at all). */
    internal val hasEnteredPageWriter: Boolean get() = enteredScopeSnapshotLoader != null

    /** Phase 2b: the partition generation and write revision, read before a request that cannot take a ticket yet. */
    internal suspend fun durableRequestStampIfCurrent(context: ReplyCacheProducerContext): ThreadDurableRequestStamp? {
        val loader = enteredScopeSnapshotLoader ?: return null
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return null
        if (!isCurrentReplyCacheProvenance(context.provenance)) return null
        return loader.requestStamp(partition)
    }

    /**
     * Phase 2b: write the page the thread store fetched through to the durable partition. With [ticket] (taken
     * before the request) the page is that request's; without one (a reply thread whose stream the request itself
     * resolved) a ticket without a realtime baseline is taken now for [routeIdentity]'s resolved channel, from
     * [stamp] ([durableRequestStampIfCurrent], read before the request). Without a ticket and without a stamp of
     * the same partition nothing is written: a stamp read now, after the request, would miss a horizonReset during
     * it and let the page claim the head (#3875).
     */
    internal suspend fun persistEnteredPageIfCurrent(
        ticket: ThreadDurableEnterTicket?,
        stamp: ThreadDurableRequestStamp?,
        page: ThreadDurableEnteredPage,
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        replyParent: ThreadDurableReplyParent? = null,
    ) {
        val loader = enteredScopeSnapshotLoader ?: return
        val stillCurrent = { isCurrentReplyCacheProvenance(context.provenance) }
        val effective =
            ticket ?: run {
                // Never read at write time: the stamp must predate the request (see above).
                if (stamp == null) return
                if (!routeIdentity.canRenderTimelineCandidates) return
                val admission = durableScopeAdmission(routeIdentity, replyParent) ?: return
                val partition =
                    context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                        ?: return
                if (stamp.partition != partition) return
                if (!stillCurrent()) return
                loader.beginEnter(
                    partition,
                    page.channelId,
                    (admission as? DurableScopeAdmission.ReplyThread)?.parent,
                    registerBaseline = false,
                    stamp = stamp,
                ) ?: return
            }
        loader.persistEnteredPage(effective, page, stillCurrent)
    }

    /** Phase 2b: [ticket]'s request produced no page to write. */
    internal suspend fun abandonEnteredPage(ticket: ThreadDurableEnterTicket) {
        enteredScopeSnapshotLoader?.abandonEnter(ticket)
    }

    /**
     * Which durable V2 scope family a route may use (task #168 step 3): channel / DM routes the
     * top-level window; a reply-thread route its `thread` scope, and only with its typed parent
     * (parent channel + root message). Anything else has no durable scope (`null`).
     */
    private fun durableScopeAdmission(
        routeIdentity: ThreadRouteIdentity,
        replyParent: ThreadDurableReplyParent?,
    ): DurableScopeAdmission? =
        when (routeIdentity.surfaceKind) {
            RaftThreadSurfaceKind.Channel, RaftThreadSurfaceKind.DirectMessage -> DurableScopeAdmission.TopLevel
            RaftThreadSurfaceKind.ReplyThread ->
                replyParent
                    ?.takeIf { it.parentChannelId != routeIdentity.timelineChannelId }
                    ?.let(DurableScopeAdmission::ReplyThread)
            else -> null
        }

    private sealed interface DurableScopeAdmission {
        data object TopLevel : DurableScopeAdmission

        data class ReplyThread(val parent: ThreadDurableReplyParent) : DurableScopeAdmission
    }

    /**
     * Offline cache paging entry (task #168): hand a fetched older / newer page to the durable window
     * extender, which appends it only when it is exactly adjacent to the stored window. Same admission
     * as [beginEnteredPageIfCurrent]; `stillCurrent` is re-read by the extender after its own
     * network round trip. Returns whether the extender was asked at all (diagnostics only).
     */
    internal suspend fun extendDurableWindowIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        direction: ThreadDurablePageDirection,
        anchorSeq: Long,
        rawPage: RawMessagePage,
        replyParent: ThreadDurableReplyParent? = null,
        headEpochAtRequest: Long? = null,
        revAtRequest: Long? = null,
    ): Boolean {
        val extender = durablePageExtender ?: return false
        if (!routeIdentity.canRenderTimelineCandidates) return false
        val admission = durableScopeAdmission(routeIdentity, replyParent) ?: return false
        val channelId = routeIdentity.timelineChannelId ?: return false
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return false
        if (!isCurrentReplyCacheProvenance(context.provenance)) return false
        extender.extendWithAdjacentPage(
            partition = partition,
            channelId = channelId,
            direction = direction,
            anchorSeq = anchorSeq,
            rawMessages = rawPage.messages,
            requestedLimit = rawPage.requestedLimit,
            replyParent = (admission as? DurableScopeAdmission.ReplyThread)?.parent,
            requestHeadEpoch = headEpochAtRequest,
            messageWindow = rawPage.messageWindow,
            historyLimited = rawPage.historyLimited,
            revAtRequest = revAtRequest,
            replyCountByParentId = rawPage.replyCountsByParentId,
        ) { isCurrentReplyCacheProvenance(context.provenance) }
        return true
    }

    /**
     * Task #14 part C ("every fact source must not be wasted"): hand the anchor context page an anchored open
     * fetched to the durable writer, which joins it to the stored window when it provably can (see
     * [ThreadDurablePageExtender.persistContextPage]). Same admission as [extendDurableWindowIfCurrent]; a
     * refusal on this side logs one `canonical_v2_context_page outcome=skipped` line, the writer logs its own.
     * Returns whether the writer was asked at all (diagnostics only).
     */
    internal suspend fun persistDurableContextPageIfCurrent(page: SharedThreadDurableContextPage): Boolean {
        val extender = durablePageExtender ?: return false
        val routeIdentity = page.routeIdentity
        val channelId = routeIdentity.timelineChannelId
        fun skip(reason: String): Boolean {
            diagnosticsLogger(
                "canonical_v2_context_page server=${page.context.provenance.serverId} scope=${channelId.orEmpty()} " +
                    "anchor=${page.anchorMessageId} outcome=skipped reason=$reason facts=0"
            )
            return false
        }
        if (!routeIdentity.canRenderTimelineCandidates || channelId == null) return skip("not_renderable")
        val admission =
            durableScopeAdmission(routeIdentity, page.replyParent)
                ?: return skip(
                    if (routeIdentity.surfaceKind == RaftThreadSurfaceKind.ReplyThread) "no_reply_parent" else "not_top_level"
                )
        val partition =
            page.context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return skip("no_partition")
        if (!isCurrentReplyCacheProvenance(page.context.provenance)) return skip("identity_changed")
        // The scope key is the response's, which may be a thread of this route's channel; never the route's by default.
        val response = page.rawPage.context ?: return skip("no_response_channel")
        val routeParent = (admission as? DurableScopeAdmission.ReplyThread)?.parent
        val targetParent = response.threadParentChannelId?.let { parentChannelId ->
            response.threadRootMessageId?.let { root -> ThreadDurableReplyParent(parentChannelId, root) }
        }
        extender.persistContextPage(
            partition = partition,
            channelId = response.channelId,
            anchorMessageId = response.targetMessageId ?: page.anchorMessageId,
            rawMessages = page.rawPage.messages,
            hasOlder = page.hasOlder,
            hasNewer = page.hasNewer,
            replyParent = targetParent ?: routeParent?.takeIf { response.channelId == channelId },
            requestHeadEpoch = page.headEpochAtRequest,
            historyLimited = page.rawPage.historyLimited,
            revAtRequest = page.revAtRequest,
            replyCountByParentId = page.rawPage.replyCountsByParentId,
        ) { isCurrentReplyCacheProvenance(page.context.provenance) }
        return true
    }

    /**
     * Offline cache gap catch-up entry (task #168 step 1): hand the latest page fetched by the gap
     * catch-up to the durable writer. It extends the stored window's newest edge when the page
     * overlaps it (a stored fact is on the page) and otherwise replaces the window with the page
     * (`window_replaced`). Same admission as [extendDurableWindowIfCurrent]. Returns whether the
     * writer was asked at all (diagnostics only).
     */
    internal suspend fun applyDurableLatestPageIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        rawPage: RawMessagePage,
        replyParent: ThreadDurableReplyParent? = null,
        baseline: CanonicalMessageV2EnterBaseline? = null,
        headEpochAtRequest: Long? = null,
        revAtRequest: Long? = null,
    ): Boolean =
        try {
            applyDurableLatestPage(routeIdentity, context, rawPage, replyParent, baseline, headEpochAtRequest, revAtRequest)
        } finally {
            // No-op once the page committed under it; otherwise the frames it buffered were never written.
            baseline?.let { abandonDurableLatestPageBaseline(it) }
        }

    private suspend fun applyDurableLatestPage(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        rawPage: RawMessagePage,
        replyParent: ThreadDurableReplyParent?,
        baseline: CanonicalMessageV2EnterBaseline?,
        headEpochAtRequest: Long?,
        revAtRequest: Long?,
    ): Boolean {
        val extender = durablePageExtender ?: return false
        if (!routeIdentity.canRenderTimelineCandidates) return false
        val admission = durableScopeAdmission(routeIdentity, replyParent) ?: return false
        val channelId = routeIdentity.timelineChannelId ?: return false
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return false
        if (!isCurrentReplyCacheProvenance(context.provenance)) return false
        extender.applyLatestPageAfterBaseline(
            partition = partition,
            channelId = channelId,
            rawMessages = rawPage.messages,
            requestedLimit = rawPage.requestedLimit,
            replyParent = (admission as? DurableScopeAdmission.ReplyThread)?.parent,
            // The writer uses it only for this exact partition + top-level channel.
            baseline = baseline,
            messageWindow = rawPage.messageWindow,
            historyLimited = rawPage.historyLimited,
            requestHeadEpoch = headEpochAtRequest,
            revAtRequest = revAtRequest,
        ) { isCurrentReplyCacheProvenance(context.provenance) }
        return true
    }

    /**
     * Task #168 step 3: the realtime baseline of a gap catch-up of the viewed channel / DM, registered
     * BEFORE its latest-page fetch (frames of the channel during the fetch are buffered, then replayed when
     * the page commits). `null` = none (no durable scope, no identity, or the writer refused: not a
     * member, or the connection has not seen `rooms:joined`): the catch-up commits as before. Task #14c: a
     * reply-thread route with its typed parent registers its thread scope's baseline the same way.
     */
    /**
     * Message storage Phase 2b: the current identity's durable write revision, read BEFORE a page fetch and handed to
     * the page's writer as `revAtRequest`. `null` = no writer / no identity (the writer then reads it itself).
     */
    internal suspend fun durableLocalRevIfCurrent(): Long? {
        val extender = durablePageExtender ?: return null
        val resolution = readReplyResolutionScope() ?: return null
        val partition =
            resolution.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return null
        return extender.currentLocalRev(partition)
    }

    internal suspend fun beginDurableLatestPageBaselineIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        replyParent: ThreadDurableReplyParent? = null,
    ): CanonicalMessageV2EnterBaseline? {
        val extender = durablePageExtender ?: return null
        if (!routeIdentity.canRenderTimelineCandidates) return null
        val admission = durableScopeAdmission(routeIdentity, replyParent) ?: return null
        val channelId = routeIdentity.timelineChannelId ?: return null
        val resolution = readReplyResolutionScope() ?: return null
        val partition =
            resolution.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return null
        if (!isCurrentReplyCacheProvenance(resolution.provenance)) return null
        return extender.beginLatestPageBaseline(
            partition,
            channelId,
            (admission as? DurableScopeAdmission.ReplyThread)?.parent,
        )
    }

    /**
     * Task #14c: the late-ack catch-up requests for this reply-thread route's own thread scope (same admission and
     * partition as [beginEnteredPageIfCurrent]); `null` for any other route or without a writer.
     */
    internal fun threadJoinConfirmationsIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        replyParent: ThreadDurableReplyParent?,
    ): Flow<CanonicalMessageV2ThreadJoinConfirmed>? {
        val extender = durablePageExtender ?: return null
        if (!routeIdentity.canRenderTimelineCandidates) return null
        val admission = durableScopeAdmission(routeIdentity, replyParent) as? DurableScopeAdmission.ReplyThread ?: return null
        val channelId = routeIdentity.timelineChannelId ?: return null
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return null
        if (!isCurrentReplyCacheProvenance(context.provenance)) return null
        return extender.threadJoinConfirmations().filter { confirmed ->
            confirmed.partition == partition &&
                confirmed.threadScopeId == channelId &&
                confirmed.replyParentScopeId == admission.parent.parentChannelId
        }
    }

    internal suspend fun abandonDurableLatestPageBaseline(baseline: CanonicalMessageV2EnterBaseline) {
        withContext(NonCancellable) { durablePageExtender?.abandonLatestPageBaseline(baseline) }
    }

    /**
     * Last known reply-preview windows of [page] (Hands dd79da45), or empty. Same identity rule as
     * the durable frame: the provenance must still be current after the disk read.
     */
    internal suspend fun cachedReplyWindowsIfCurrent(
        page: ThreadRepliesPageIdentity,
        context: ReplyCacheProducerContext,
    ): Map<String, RaftCachedThreadReplyWindow> {
        val cache = durableLastKnownReader?.replyWindowCache ?: return emptyMap()
        if (!isCurrentReplyCacheProvenance(context.provenance)) return emptyMap()
        val windows = cache.load(page)
        if (!isCurrentReplyCacheProvenance(context.provenance)) return emptyMap()
        return windows
    }

    /** Persists accepted reply-preview windows for the next cold start. */
    internal fun recordReplyWindows(projections: Collection<ThreadRepliesAcceptedProjection>) {
        val cache = durableLastKnownReader?.replyWindowCache ?: return
        // Only the current principal's windows are written: a summary that lands after a sign-out
        // or account switch must not re-create content the auth cleanup just wiped.
        val provenance = currentReplyCacheProvenance() ?: return
        val current =
            projections.filter { projection ->
                projection.identity.page.principalId == provenance.principalId &&
                    projection.identity.page.serverId == provenance.serverId
            }
        cache.record(current)
    }

    internal suspend fun touchDurableUserAccess(scope: SyncScopeKey) {
        val reader = durableLastKnownReader ?: return
        withContext(durableDbDispatcher) {
            reader.touchUserAccess(scope)
        }
    }

    internal suspend fun durableLastKnownPayloadIfCurrent(
        routePayload: RaftThreadScreenPayload,
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        allowNewestFallbackForMissingAnchor: Boolean = true,
    ): RaftThreadScreenPayload? =
        durableLastKnownResultIfCurrent(
            routePayload = routePayload,
            routeIdentity = routeIdentity,
            context = context,
            allowNewestFallbackForMissingAnchor = allowNewestFallbackForMissingAnchor,
        )?.payload

    internal suspend fun durableLastKnownResultIfCurrent(
        routePayload: RaftThreadScreenPayload,
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        allowNewestFallbackForMissingAnchor: Boolean = true,
    ): SharedThreadDurableLastKnownResult? {
        val reader = durableLastKnownReader ?: return null
        if (!routeIdentity.canRenderTimelineCandidates) return null
        val admission = durableScopeAdmission(routeIdentity, routePayload.durableReplyParentOrNull()) ?: return null
        val allowedScopeKinds = routeIdentity.durableScopeKinds()
        val channelId = routeIdentity.timelineChannelId ?: return null
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return null
        if (!isCurrentReplyCacheProvenance(context.provenance)) return null
        val pageSize = threadPageSize(partition.serverId)
        val focusedId = routePayload.focusedMessageId?.trim()?.takeIf { it.isNotEmpty() }
        // Phase 2d: prefer bounded SQLite timeline reads (newest page or anchored island window),
        // falling back to newest / legacy `read` only when allowed (cold offline opens so
        // `anchorPendingStandIn` stays available when the target message is not stored yet).
        suspend fun readTimelineFor(anchor: ThreadDurableTimelineAnchor) =
            when (admission) {
                DurableScopeAdmission.TopLevel ->
                    reader.readTimeline(partition, channelId, anchor, touchUserAccess = false)
                is DurableScopeAdmission.ReplyThread ->
                    reader.readReplyThreadTimeline(partition, channelId, admission.parent, anchor, touchUserAccess = false)
            }
        val primaryAnchor =
            if (focusedId != null) {
                val beforeCount = (pageSize / 2).coerceAtLeast(1)
                ThreadDurableTimelineAnchor.AroundMessageId(
                    messageId = focusedId,
                    beforeCount = beforeCount,
                    afterCount = (pageSize - beforeCount).coerceAtLeast(1),
                )
            } else {
                ThreadDurableTimelineAnchor.Newest(limit = pageSize)
            }
        val anchoredTimelinePage = readTimelineFor(primaryAnchor)
        val fellBackToNewestForMissingAnchor =
            focusedId != null && anchoredTimelinePage == null && allowNewestFallbackForMissingAnchor
        val timelinePage =
            anchoredTimelinePage
                ?: if (fellBackToNewestForMissingAnchor) {
                    readTimelineFor(ThreadDurableTimelineAnchor.Newest(limit = pageSize))
                } else {
                    null
                }
        if (timelinePage != null) {
            if (!isCurrentReplyCacheProvenance(context.provenance)) return null
            if (timelinePage.scope.scopeKind !in allowedScopeKinds) {
                diagnosticsLogger(
                    "thread_durable_last_known threadId=$channelId outcome=surface_mismatch " +
                        "surface=${routeIdentity.surfaceKind} scopeKind=${timelinePage.scope.scopeKind}"
                )
                return null
            }
            if (timelinePage.envelopes.isEmpty()) {
                if (focusedId != null && !fellBackToNewestForMissingAnchor) return null
                diagnosticsLogger(
                    "thread_durable_last_known threadId=$channelId outcome=empty_frame scopeKind=${timelinePage.scope.scopeKind}"
                )
                return SharedThreadDurableLastKnownResult(
                    payload =
                        routePayload.asRefreshEmpty(routePayload).copy(
                            durableLastKnown = true,
                            durableReachedOldest = timelinePage.reachesOldest,
                            timelineGaps = emptyList(),
                        ),
                    scope = timelinePage.scope,
                )
            }
            val messages =
                timelinePage.envelopes
                    .mapNotNull { envelope ->
                        envelope.toThreadMessageItem(
                            currentUserId = context.provenance.principalId,
                            diagnostics = diagnosticsLogger,
                        )
                    }
                    .sortedByThreadTimeline()
            if (messages.isEmpty()) return null
            val gaps =
                reconcileThreadTimelineGaps(
                    existingGaps = emptyList(),
                    addedGaps = timelinePage.gaps.map { it.toThreadTimelineGap() },
                    messages = messages,
                )
            val canLoadMoreBefore = timelinePage.hasOlderStored || !timelinePage.reachesOldest
            val canLoadMoreAfter =
                if (focusedId != null && !fellBackToNewestForMissingAnchor) {
                    timelinePage.hasNewerStored || !timelinePage.reachesHead
                } else {
                    false
                }
            diagnosticsLogger(
                "thread_durable_last_known threadId=$channelId outcome=${if (focusedId != null && !fellBackToNewestForMissingAnchor) "anchored_frame" else "frame"} " +
                    "scopeKind=${timelinePage.scope.scopeKind} messages=${messages.size} gaps=${gaps.size} " +
                    "latestSeq=${timelinePage.cursor.latestSeq} canLoadMoreBefore=$canLoadMoreBefore canLoadMoreAfter=$canLoadMoreAfter"
            )
            return SharedThreadDurableLastKnownResult(
                payload =
                    routePayload.copy(
                        status = "content",
                        backgroundRefreshing = false,
                        durableLastKnown = true,
                        messages = messages,
                        timelineGaps = gaps,
                        canLoadMoreBefore = canLoadMoreBefore,
                        durableReachedOldest = !canLoadMoreBefore,
                        canLoadMoreAfter = canLoadMoreAfter,
                        olderMessagesStatus = "idle",
                        newerMessagesStatus = "idle",
                        errorCode = null,
                    ),
                scope = timelinePage.scope,
            )
        }
        if (reader.supportsTimelineReads || (focusedId != null && !allowNewestFallbackForMissingAnchor)) {
            return null
        }
        val frame =
            when (admission) {
                DurableScopeAdmission.TopLevel -> reader.read(partition, channelId)
                is DurableScopeAdmission.ReplyThread -> reader.readReplyThread(partition, channelId, admission.parent)
            } ?: return null
        // The disk read suspended; a sign-out / account switch in between must not let identity A's
        // rows act under identity B's route. Rendering is ALSO refused by the commit gate in start()
        // (`withCurrentReplyCacheProvenance`), but `seedThreadAuthorProfiles` runs upstream of that
        // gate: without this check A's author profiles would be written into the process-wide
        // `serverMembersStore` before the frame is rejected. This line guards the write, not the display.
        if (!isCurrentReplyCacheProvenance(context.provenance)) return null
        if (frame.scope.scopeKind !in allowedScopeKinds) {
            diagnosticsLogger(
                "thread_durable_last_known threadId=$channelId outcome=surface_mismatch " +
                    "surface=${routeIdentity.surfaceKind} scopeKind=${frame.scope.scopeKind}"
            )
            return null
        }
        if (frame.envelopes.isEmpty()) {
            // Task #1137: the channel was empty when last seen. Stand in with the ordinary empty
            // state (flagged as last-known) rather than falling through to "cannot load".
            diagnosticsLogger(
                "thread_durable_last_known threadId=$channelId outcome=empty_frame scopeKind=${frame.scope.scopeKind}"
            )
            return SharedThreadDurableLastKnownResult(
                payload = routePayload.asRefreshEmpty(routePayload).copy(durableLastKnown = true),
                scope = null,
            )
        }
        // Stand in with the newest page only, the same size the network's first page has: projecting,
        // laying out and drawing the whole stored scope (up to 500 facts) held the first frame behind a
        // loading state for over a second on a cold open (artin, Hands 2d15915b: 147 rows ⇒ ~1.3 s).
        val window = frame.windowEnvelopes()
        val shownEnvelopes = window.sortedByDescending { it.fact.seq }.take(pageSize)
        val messages =
            shownEnvelopes
                .mapNotNull { envelope ->
                    envelope.toThreadMessageItem(
                        currentUserId = context.provenance.principalId,
                        diagnostics = diagnosticsLogger,
                    )
                }
                .sortedByThreadTimeline()
        if (messages.isEmpty()) return null
        // Task #168 step 2b: older paging stays open while the window holds older facts than the ones
        // shown, or the window itself does not reach the channel's start (an online retry may still
        // fetch them). Loading older on the stand-in reads the window first (`durableOlderPageIfCurrent`).
        val canLoadMoreBefore = window.size > shownEnvelopes.size || !frame.cursor.hasLoadedOldest
        diagnosticsLogger(
            "thread_durable_last_known threadId=$channelId outcome=frame scopeKind=${frame.scope.scopeKind} " +
                "messages=${messages.size} stored=${frame.envelopes.size} latestSeq=${frame.cursor.latestSeq} " +
                "canLoadMoreBefore=$canLoadMoreBefore"
        )
        return SharedThreadDurableLastKnownResult(
            payload =
                routePayload.copy(
                    status = "content",
                    backgroundRefreshing = false,
                    durableLastKnown = true,
                    messages = messages,
                    timelineGaps = emptyList(),
                    canLoadMoreBefore = canLoadMoreBefore,
                    durableReachedOldest = !canLoadMoreBefore,
                    canLoadMoreAfter = false,
                    olderMessagesStatus = "idle",
                    newerMessagesStatus = "idle",
                    errorCode = null,
                ),
            scope = null,
        )
    }

    /**
     * Older page from the durable V2 timeline (Phase 2d):
     * - When [crossGaps] is `false` (online live timeline), serves strictly from inside a proven
     *   contiguous SQLite segment (`insideProvenSegment == true`) without touching the network.
     * - When [crossGaps] is `true` (offline stand-in), crosses into lower stored islands and returns
     *   derived [SharedThreadDurableOlderPage.gaps].
     */
    internal suspend fun durableOlderPageIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        beforeSeq: Long,
        expectedOwnerScopeId: String?,
        replyParent: ThreadDurableReplyParent? = null,
        crossGaps: Boolean = false,
        allowLegacyStandIn: Boolean = false,
    ): SharedThreadDurableOlderPage? {
        val channelId = routeIdentity.timelineChannelId
        fun refuse(reason: String): SharedThreadDurableOlderPage? {
            diagnosticsLogger(
                "thread_durable_older_page threadId=${channelId.orEmpty()} served=0 reason=$reason beforeSeq=$beforeSeq crossGaps=$crossGaps"
            )
            return null
        }
        val reader = durableLastKnownReader ?: return refuse("no_reader")
        val ownerScopeId = context.provenance.threadPayloadOwnerScopeId
        if (ownerScopeId == null || ownerScopeId != expectedOwnerScopeId) return refuse("owner_changed")
        if (!routeIdentity.canRenderTimelineCandidates) return refuse("not_renderable")
        val admission =
            durableScopeAdmission(routeIdentity, replyParent)
                ?: return refuse(
                    if (routeIdentity.surfaceKind == RaftThreadSurfaceKind.ReplyThread) "no_reply_parent" else "not_top_level"
                )
        val allowedScopeKinds = routeIdentity.durableScopeKinds()
        if (channelId == null) return refuse("no_channel")
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return refuse("no_partition")
        if (!isCurrentReplyCacheProvenance(context.provenance)) return refuse("identity_changed")
        val pageSize = threadPageSize(partition.serverId)
        val timelineAnchor =
            ThreadDurableTimelineAnchor.Before(
                beforeSeq = beforeSeq,
                limit = pageSize,
                crossGaps = crossGaps,
            )
        val timelinePage =
            when (admission) {
                DurableScopeAdmission.TopLevel ->
                    reader.readTimeline(partition, channelId, timelineAnchor, touchUserAccess = false)
                is DurableScopeAdmission.ReplyThread ->
                    reader.readReplyThreadTimeline(partition, channelId, admission.parent, timelineAnchor, touchUserAccess = false)
            }
        if (timelinePage != null) {
            if (!isCurrentReplyCacheProvenance(context.provenance)) return refuse("identity_changed")
            if (timelinePage.scope.scopeKind !in allowedScopeKinds) return refuse("surface_mismatch")
            if (!crossGaps && !timelinePage.insideProvenSegment) return refuse("segment_edge")
            val messages =
                timelinePage.envelopes.mapNotNull { envelope ->
                    envelope.toThreadMessageItem(
                        currentUserId = context.provenance.principalId,
                        diagnostics = diagnosticsLogger,
                    )
                }.sortedByThreadTimeline()
            if (messages.isEmpty()) {
                if (timelinePage.envelopes.isEmpty() && !crossGaps && timelinePage.insideProvenSegment && timelinePage.reachesOldest) {
                    diagnosticsLogger(
                        "thread_durable_older_page threadId=$channelId served=0 reason=proven_reached_oldest " +
                            "beforeSeq=$beforeSeq crossGaps=false scopeKind=${timelinePage.scope.scopeKind}"
                    )
                    return SharedThreadDurableOlderPage(
                        messages = emptyList(),
                        pageSize = pageSize,
                        canLoadMoreBefore = false,
                        reachedOldest = true,
                        gaps = emptyList(),
                        insideProvenSegment = true,
                        scope = timelinePage.scope,
                    )
                }
                return refuse(if (timelinePage.reachesOldest) "exhausted_at_oldest" else "exhausted")
            }
            val canLoadMoreBefore = timelinePage.hasOlderStored || !timelinePage.reachesOldest
            val gaps = if (crossGaps) timelinePage.gaps.map { it.toThreadTimelineGap() } else emptyList()
            diagnosticsLogger(
                "thread_durable_older_page threadId=$channelId served=${messages.size} reason=" +
                    (if (timelinePage.hasOlderStored) "more_stored" else if (timelinePage.reachesOldest) "reached_oldest" else "window_end") +
                    " beforeSeq=$beforeSeq crossGaps=$crossGaps gaps=${gaps.size} canLoadMoreBefore=$canLoadMoreBefore scopeKind=${timelinePage.scope.scopeKind}"
            )
            return SharedThreadDurableOlderPage(
                messages = messages,
                pageSize = pageSize,
                canLoadMoreBefore = canLoadMoreBefore,
                reachedOldest = !canLoadMoreBefore,
                gaps = gaps,
                insideProvenSegment = timelinePage.insideProvenSegment,
                scope = timelinePage.scope,
            )
        }
        if (reader.supportsTimelineReads) return refuse("no_frame")
        if (!crossGaps && !allowLegacyStandIn) return refuse("no_timeline_reader")
        val page =
            when (admission) {
                DurableScopeAdmission.TopLevel -> reader.readBefore(partition, channelId, beforeSeq, pageSize)
                is DurableScopeAdmission.ReplyThread ->
                    reader.readReplyThreadBefore(partition, channelId, admission.parent, beforeSeq, pageSize)
            } ?: return refuse("no_frame")
        if (!isCurrentReplyCacheProvenance(context.provenance)) return refuse("identity_changed")
        if (page.scope.scopeKind !in allowedScopeKinds) return refuse("surface_mismatch")
        val messages =
            page.envelopes.mapNotNull { envelope ->
                envelope.toThreadMessageItem(
                    currentUserId = context.provenance.principalId,
                    diagnostics = diagnosticsLogger,
                )
            }.sortedByThreadTimeline()
        if (messages.isEmpty()) {
            return refuse(if (page.hasLoadedOldest) "exhausted_at_oldest" else "exhausted")
        }
        // More stored below this page ⇒ keep paging from disk; window exhausted but it does not reach
        // the channel's start ⇒ keep paging open for an online retry; otherwise this is the beginning.
        val canLoadMoreBefore = page.hasOlderStored || !page.hasLoadedOldest
        diagnosticsLogger(
            "thread_durable_older_page threadId=$channelId served=${messages.size} reason=" +
                (if (page.hasOlderStored) "more_stored" else if (page.hasLoadedOldest) "reached_oldest" else "window_end") +
                " beforeSeq=$beforeSeq canLoadMoreBefore=$canLoadMoreBefore scopeKind=${page.scope.scopeKind}"
        )
        return SharedThreadDurableOlderPage(
            messages = messages,
            pageSize = pageSize,
            canLoadMoreBefore = canLoadMoreBefore,
            reachedOldest = !canLoadMoreBefore,
            insideProvenSegment = true,
        )
    }

    /**
     * Newer page from the durable V2 timeline (Phase 2d): symmetric mirror of [durableOlderPageIfCurrent].
     */
    internal suspend fun durableNewerPageIfCurrent(
        routeIdentity: ThreadRouteIdentity,
        context: ReplyCacheProducerContext,
        afterSeq: Long,
        expectedOwnerScopeId: String?,
        replyParent: ThreadDurableReplyParent? = null,
        crossGaps: Boolean = false,
    ): SharedThreadDurableNewerPage? {
        val channelId = routeIdentity.timelineChannelId
        fun refuse(reason: String): SharedThreadDurableNewerPage? {
            diagnosticsLogger(
                "thread_durable_newer_page threadId=${channelId.orEmpty()} served=0 reason=$reason afterSeq=$afterSeq crossGaps=$crossGaps"
            )
            return null
        }
        val reader = durableLastKnownReader ?: return refuse("no_reader")
        val ownerScopeId = context.provenance.threadPayloadOwnerScopeId
        if (ownerScopeId == null || ownerScopeId != expectedOwnerScopeId) return refuse("owner_changed")
        if (!routeIdentity.canRenderTimelineCandidates) return refuse("not_renderable")
        val admission =
            durableScopeAdmission(routeIdentity, replyParent)
                ?: return refuse(
                    if (routeIdentity.surfaceKind == RaftThreadSurfaceKind.ReplyThread) "no_reply_parent" else "not_top_level"
                )
        val allowedScopeKinds = routeIdentity.durableScopeKinds()
        if (channelId == null) return refuse("no_channel")
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return refuse("no_partition")
        if (!isCurrentReplyCacheProvenance(context.provenance)) return refuse("identity_changed")
        val pageSize = threadPageSize(partition.serverId)
        val timelineAnchor =
            ThreadDurableTimelineAnchor.After(
                afterSeq = afterSeq,
                limit = pageSize,
                crossGaps = crossGaps,
            )
        val timelinePage =
            when (admission) {
                DurableScopeAdmission.TopLevel ->
                    reader.readTimeline(partition, channelId, timelineAnchor, touchUserAccess = false)
                is DurableScopeAdmission.ReplyThread ->
                    reader.readReplyThreadTimeline(partition, channelId, admission.parent, timelineAnchor, touchUserAccess = false)
            } ?: return refuse("no_timeline_reader")
        if (!isCurrentReplyCacheProvenance(context.provenance)) return refuse("identity_changed")
        if (timelinePage.scope.scopeKind !in allowedScopeKinds) return refuse("surface_mismatch")
        if (!crossGaps && !timelinePage.insideProvenSegment) return refuse("segment_edge")
        val messages =
            timelinePage.envelopes.mapNotNull { envelope ->
                envelope.toThreadMessageItem(
                    currentUserId = context.provenance.principalId,
                    diagnostics = diagnosticsLogger,
                )
            }.sortedByThreadTimeline()
        if (messages.isEmpty()) {
            // Unlike `reachesOldest` (history start is immutable), `reachesHead` only records that
            // highSeq was head when last verified. Online (`!crossGaps`) always falls through to
            // the network when no newer messages exist locally so server messages posted since then
            // are never hidden.
            return refuse(if (timelinePage.reachesHead) "exhausted_at_head" else "exhausted")
        }
        val canLoadMoreAfter =
            if (crossGaps) {
                timelinePage.hasNewerStored || !timelinePage.reachesHead
            } else {
                true
            }
        val gaps = if (crossGaps) timelinePage.gaps.map { it.toThreadTimelineGap() } else emptyList()
        diagnosticsLogger(
            "thread_durable_newer_page threadId=$channelId served=${messages.size} afterSeq=$afterSeq " +
                "crossGaps=$crossGaps gaps=${gaps.size} canLoadMoreAfter=$canLoadMoreAfter scopeKind=${timelinePage.scope.scopeKind}"
        )
        return SharedThreadDurableNewerPage(
            messages = messages,
            pageSize = pageSize,
            canLoadMoreAfter = canLoadMoreAfter,
            reachedHead = if (crossGaps) !canLoadMoreAfter else false,
            gaps = gaps,
            insideProvenSegment = timelinePage.insideProvenSegment,
            scope = timelinePage.scope,
        )
    }

    /**
     * Tenny rule 2: a background validation of [payload]'s conversation proved the account lost
     * access (see [isConversationAccessLost]). Evicts the in-memory timeline under the requesting
     * account's key and, when wired, the conversation's durable V2 window. The account fence is
     * the same as a write's: another account's cache is never touched.
     */
    internal suspend fun evictLostConversationAccess(
        payload: RaftThreadScreenPayload,
        expectedProvenance: ReplyCacheProvenance,
    ): Boolean {
        val threadId = payload.resolveThreadRouteIdentity().timelineChannelId ?: return false
        val ownerScopeId = expectedProvenance.threadPayloadOwnerScopeId ?: return false
        val sameAccount =
            synchronized(replyCacheProvenanceLock) {
                replyCacheProvenance.value?.isSameTimelineCacheAccount(expectedProvenance) == true
            }
        if (!sameAccount) return false
        payloadCache.evictConversation(expectedProvenance.serverId, threadId, ownerScopeId)
        val evictor = durableAccessEvictor ?: return true
        val partition =
            expectedProvenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return true
        try {
            evictor.evictConversation(partition, threadId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            diagnosticsLogger(
                "thread_access_lost_durable_evict_failed server=${expectedProvenance.serverId} " +
                    "threadId=$threadId reason=${failure.message ?: failure::class.simpleName.orEmpty()}"
            )
        }
        return true
    }

    /**
     * Task #14b ("every authoritative page is persisted"): hand the reply page an Activity prewarm just
     * fetched to the durable V2 writer as the thread scope's window, so opening the thread later renders
     * `thread_durable_last_known` even after the in-memory prewarm was invalidated or the process restarted.
     * Same admission as [persistEnteredPageIfCurrent] for the prewarm's reply route (renderable reply
     * route with its typed parent, an owning partition, current provenance); additionally the page must be
     * the one the server resolved for exactly this thread. The parent named here is only the route's claim —
     * the writer has the server confirm it. Every refusal on this side logs one `canonical_v2_prewarm_page`
     * line; the writer logs its own.
     */
    internal suspend fun persistReplyThreadPrewarmPageIfCurrent(
        basePayload: RaftThreadScreenPayload,
        context: ReplyCacheProducerContext,
        resolvedThreadChannelId: String?,
        rawPage: RawMessagePage?,
        expectedCommitGeneration: Long,
        headEpochAtRequest: Long?,
        revAtRequest: Long?,
    ) {
        val loader = enteredScopeSnapshotLoader ?: return
        val threadChannelId = basePayload.streamChannelId.orEmpty()
        fun skip(reason: String) =
            diagnosticsLogger(
                "canonical_v2_prewarm_page server=${context.provenance.serverId} scope=$threadChannelId " +
                    "scopeKind=thread outcome=skipped reason=$reason facts=0"
            )
        if (rawPage == null) return skip("no_raw_page")
        if (resolvedThreadChannelId != threadChannelId) return skip("thread_mismatch")
        val routeIdentity = basePayload.resolveThreadRouteIdentity()
        if (!routeIdentity.canRenderTimelineCandidates || routeIdentity.timelineChannelId != threadChannelId) {
            return skip("not_renderable")
        }
        val replyParent =
            basePayload.durableReplyParentOrNull()
                ?: return skip("no_reply_parent")
        val admission =
            durableScopeAdmission(routeIdentity, replyParent) as? DurableScopeAdmission.ReplyThread
                ?: return skip("no_reply_parent")
        val partition =
            context.provenance.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
                ?: return skip("no_partition")
        if (!isCurrentReplyCacheProvenance(context.provenance)) return skip("identity_changed")
        loader.persistReplyThreadPrewarmPage(
            partition = partition,
            threadChannelId = threadChannelId,
            replyParent = admission.parent,
            rawMessages = rawPage.messages,
            requestedLimit = rawPage.requestedLimit,
            expectedCommitGeneration = expectedCommitGeneration,
            messageWindow = rawPage.messageWindow,
            historyLimited = rawPage.historyLimited,
            requestHeadEpoch = headEpochAtRequest,
            revAtRequest = revAtRequest,
        ) { isCurrentReplyCacheProvenance(context.provenance) }
    }
}
