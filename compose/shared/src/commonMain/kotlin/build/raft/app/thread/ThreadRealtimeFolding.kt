package build.raft.app

import build.raft.app.SharedThreadStore.LoadNewerState
import build.raft.app.SharedThreadStore.RealtimeGapRecoveryState
import build.raft.app.SharedThreadStore.ReplyCountRealtimeGap
import build.raft.app.data.MessageMutationSummary
import build.raft.app.data.MviStaleToken
import build.raft.app.data.realtime.RealtimeVisibleStreamGapRequest
import build.raft.app.data.realtime.isSyncHorizonReset
import build.raft.app.model.ChannelId
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.PerReceiverReadStateChanged
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.ThreadReplyUpdated
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ThreadRepliesAcceptedProjection
import build.raft.app.network.sync.ThreadRepliesPageIdentity
import build.raft.app.network.sync.ThreadRepliesSyncHost
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

internal data class PendingReactionRealtimeGuard(
    val messageId: String,
    val emoji: String,
    val selected: Boolean
)

/**
 * Realtime folding for [SharedThreadStore]: one live event onto the open page ([applyRealtimeEvent]),
 * the live events held for a refresh in flight ([InFlightRealtimeFold]) and the pending reaction guards.
 *
 * It owns [inFlightRealtimeFoldLock] with the folds it guards, and [pendingReactionRealtimeGuards]. The
 * linearizing Mutex and the gap state it guards stay owned by [ThreadRealtimeGapRecovery]: this class locks
 * that same Mutex instance, at the same points, through the accessors below.
 *
 * It runs on the caller's coroutine and thread (the realtime sink may call it off main), and adds no new
 * scope, Job or lock. Store state is read only through [currentState] and the other getters, at
 * each use; writes go through the store's `reducePayloadPatch`, and route checks call the store's.
 */
internal class ThreadRealtimeFolding(
    private val dataSource: SharedThreadDataSource,
    private val diagnosticsLogger: ((String) -> Unit)?,
    private val threadRepliesSyncHost: ThreadRepliesSyncHost,
    private val readStateProjection: ThreadReadStateProjection,
    private val threadVisibleRead: ThreadVisibleRead,
    private val threadRealtimeGapRecovery: ThreadRealtimeGapRecovery,
    private val currentState: () -> SharedThreadState,
    private val currentRouteStaleToken: () -> MviStaleToken,
    private val currentVisiblePayloadOwnerScopeId: () -> String?,
    reducePayloadPatch: (
        cachePayload: Boolean,
        patch: (RaftThreadScreenPayload) -> RaftThreadScreenPayload
    ) -> Boolean,
    routeStillCurrent: (
        routePayload: RaftThreadScreenPayload,
        source: String,
        stage: String,
        currentPayload: RaftThreadScreenPayload
    ) -> Boolean,
    captureThreadRepliesPageIdentity: suspend (payload: RaftThreadScreenPayload) -> ThreadRepliesPageIdentity?,
    dispatch: (SharedThreadIntent) -> Unit,
) {
    private val storeReducePayloadPatch = reducePayloadPatch
    private val storeRouteStillCurrent = routeStillCurrent
    private val storeCaptureThreadRepliesPageIdentity = captureThreadRepliesPageIdentity
    private val storeDispatch = dispatch

    private val inFlightRealtimeFoldLock = SynchronizedObject()
    private val inFlightRealtimeFolds = mutableListOf<InFlightRealtimeFold>()

    /**
     * artin #110: the live message events a refresh in flight received for its route, in arrival
     * order. They are folded onto its answer when it lands (web merges its answer and the socket
     * events in one store), so an edit / delete / reaction that arrived mid-flight is not lost and
     * a generation moved only by them needs no second fetch. [complete] turns false when an event
     * could not be held (a seq gap or a sync horizon reset): the refresh then fetches again.
     */
    class InFlightRealtimeFold(val staleToken: MviStaleToken) {
        val events = mutableListOf<InFlightRealtimeEvent>()
        val messageIds = mutableSetOf<String>()
        var complete = true
    }

    class InFlightRealtimeEvent(
        val event: RealtimeEvent,
        val currentUserId: String?,
        val activeReplyParentMessageId: MessageId?,
    )

    fun beginInFlightRealtimeFold(staleToken: MviStaleToken): InFlightRealtimeFold =
        InFlightRealtimeFold(staleToken).also { fold ->
            synchronized(inFlightRealtimeFoldLock) { inFlightRealtimeFolds += fold }
        }

    fun endInFlightRealtimeFold(fold: InFlightRealtimeFold) {
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

    fun InFlightRealtimeFold.holdsAll(messageIds: Set<String>): Boolean =
        synchronized(inFlightRealtimeFoldLock) { complete && this.messageIds.containsAll(messageIds) }

    /** The held events replayed in order through the realtime reducer (idempotent by message id). */
    fun RaftThreadScreenPayload.withInFlightRealtimeFolded(buffer: InFlightRealtimeFold): RaftThreadScreenPayload {
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

    val pendingReactionRealtimeGuards = mutableMapOf<String, PendingReactionRealtimeGuard>()

    /** The store's route stale token, read at each use. */
    private val routeStaleToken: MviStaleToken
        get() = currentRouteStaleToken()

    /** The store's visible payload owner scope, read at each use. */
    private val visiblePayloadOwnerScopeId: String?
        get() = currentVisiblePayloadOwnerScopeId()

    private val realtimeGapRecoveryMutex: Mutex
        get() = threadRealtimeGapRecovery.realtimeGapRecoveryMutex
    private var realtimeGapRecoveryState: RealtimeGapRecoveryState?
        get() = threadRealtimeGapRecovery.realtimeGapRecoveryState
        set(value) {
            threadRealtimeGapRecovery.realtimeGapRecoveryState = value
        }
    private val loadNewerState: LoadNewerState?
        get() = threadRealtimeGapRecovery.loadNewerState
    private var nextRealtimeGapRecoveryId: Long
        get() = threadRealtimeGapRecovery.nextRealtimeGapRecoveryId
        set(value) {
            threadRealtimeGapRecovery.nextRealtimeGapRecoveryId = value
        }

    private fun reducePayloadPatch(
        cachePayload: Boolean = false,
        patch: (RaftThreadScreenPayload) -> RaftThreadScreenPayload
    ): Boolean = storeReducePayloadPatch(cachePayload, patch)

    private fun routeStillCurrent(
        routePayload: RaftThreadScreenPayload,
        source: String,
        stage: String,
        currentPayload: RaftThreadScreenPayload
    ): Boolean = storeRouteStillCurrent(routePayload, source, stage, currentPayload)

    private suspend fun captureThreadRepliesPageIdentity(payload: RaftThreadScreenPayload): ThreadRepliesPageIdentity? =
        storeCaptureThreadRepliesPageIdentity(payload)

    private fun dispatch(intent: SharedThreadIntent) = storeDispatch(intent)

    private fun resolvePendingMarkUnread(event: PerReceiverReadStateChanged, sessionServerId: String?) =
        threadVisibleRead.resolvePendingMarkUnread(event, sessionServerId)

    private suspend fun refreshLatestAfterVisibleMaxSeq() = threadRealtimeGapRecovery.refreshLatestAfterVisibleMaxSeq()

    private suspend fun recoverRealtimeGapFromVisibleTail(replyCountGap: ReplyCountRealtimeGap) =
        threadRealtimeGapRecovery.recoverRealtimeGapFromVisibleTail(replyCountGap)

    private fun launchRealtimeGapRecovery(
        initial: RaftThreadScreenPayload,
        initialRequest: RealtimeVisibleStreamGapRequest,
        staleToken: MviStaleToken,
        recoveryId: Long,
        requestSnapshot: RaftThreadScreenPayload,
    ) = threadRealtimeGapRecovery.launchRealtimeGapRecovery(initial, initialRequest, staleToken, recoveryId, requestSnapshot)

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

    suspend fun applyRealtimeEvent(
        event: RealtimeEvent,
        activeReplyParentMessageId: MessageId? = null
    ) {
        if (event.isSyncHorizonReset()) {
            recordInFlightRealtime(routeStaleToken, event = null)
            // A resume had too many late messages to send (slock #10982): re-read the latest page through
            // the existing gap catch-up, as on a foreground resume.
            diagnosticsLogger?.invoke("thread_sync_horizon_reset threadId=${currentState().payload.sourceThreadId.orEmpty()}")
            refreshLatestAfterVisibleMaxSeq()
            return
        }
        val acceptedPayload = currentState().payload
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
                val currentPayload = currentState().payload
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
                        owner != null && owner == readStateOwner && (event.serverId == null || event.serverId?.value == sessionServerId)) {
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
                        val folded =
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
                                )
                        val merged =
                            folded.copy(
                                canLoadMoreAfter = currentPayload.canLoadMoreAfter,
                                timelineGaps =
                                    if (currentPayload.timelineGaps.isNotEmpty()) {
                                        reconcileThreadTimelineGaps(
                                            existingGaps = currentPayload.timelineGaps,
                                            addedGaps = emptyList(),
                                            messages = folded.messages,
                                        )
                                    } else {
                                        folded.timelineGaps
                                    },
                            )
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
                val preserved =
                    merged.preserveStableTimelineFrom(
                        previous = currentPayload,
                        preserveSelectedReactions = false,
                    )
                val stableSelected =
                    if (currentPayload.timelineGaps.isNotEmpty()) {
                        preserved.copy(
                            timelineGaps =
                                reconcileThreadTimelineGaps(
                                    existingGaps = currentPayload.timelineGaps,
                                    addedGaps = emptyList(),
                                    messages = preserved.messages,
                                ),
                        )
                    } else {
                        preserved
                    }
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
                "thread_realtime_reply_count_gap_detected threadId=${currentState().payload.sourceThreadId.orEmpty()} " +
                    "visibleMaxSeq=${currentState().payload.messages.mapNotNull { it.seq }.maxOrNull() ?: 0L} " +
                    "loadedReplies=${currentState().payload.loadedReplyTimelineCount()} " +
                    "replyCount=${replyCountGap.replyCount}"
            )
            recoverRealtimeGapFromVisibleTail(replyCountGap)
        }
    }

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

    fun confirmReactionRealtimeGuards(summary: MessageMutationSummary, currentUserId: String?) {
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

    fun clearReactionRealtimeGuards(messageId: String) {
        val cleanMessageId = messageId.trim()
        if (cleanMessageId.isBlank()) return
        pendingReactionRealtimeGuards
            .values
            .filter { guard -> guard.messageId == cleanMessageId }
            .forEach { guard -> pendingReactionRealtimeGuards.remove(guard.key) }
    }
}

internal val PendingReactionRealtimeGuard.key: String
    get() = "$messageId\u0000$emoji"
