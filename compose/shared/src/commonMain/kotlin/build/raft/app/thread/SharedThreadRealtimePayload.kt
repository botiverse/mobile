package build.raft.app.thread

import build.raft.app.RaftThreadSurfaceKind
import build.raft.app.ThreadRouteIdentity
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.isTerminalState
import build.raft.app.data.ThreadCandidateProofGate
import build.raft.app.data.ThreadIdentityProof
import build.raft.app.data.ThreadRouteId
import build.raft.app.data.map
import build.raft.app.data.toMessageMetadata
import build.raft.app.data.toThreadRouteId
import build.raft.app.messages
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.SyncResumeResponse
import build.raft.app.network.sync.CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND
import build.raft.app.network.sync.InlineThreadReplyPreviewLimit
import build.raft.app.network.sync.ReplyPreview
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.initialThreadRepliesReadModel
import build.raft.app.origin
import build.raft.app.resolve
import build.raft.app.network.sync.applyReplyFrame
import build.raft.app.network.sync.ThreadRepliesSyncWindowProducer
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The typed parent a reply-thread route names (task #168 step 3): its parent channel and root
 * message, read the same way [resolveThreadRouteIdentity] reads them. `null` for any other surface
 * or an incomplete reply identity, which has no durable thread scope.
 */
internal fun RaftThreadScreenPayload.durableReplyParentOrNull(): ThreadDurableReplyParent? {
    if (surfaceKind != RaftThreadSurfaceKind.ReplyThread) return null
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() } ?: return null
    val rootMessage =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
            ?: return null
    return ThreadDurableReplyParent(parentChannelId = parentChannel, rootMessageId = rootMessage)
}

/** The durable V2 scope kinds a route's surface may be shown from (task #168 step 3 adds `thread`). */
internal fun ThreadRouteIdentity.durableScopeKinds(): Set<String> =
    when (surfaceKind) {
        // Task v2: a task conversation renders on the channel surface (route channel kind `task`).
        RaftThreadSurfaceKind.Channel -> setOf("channel", "private", "joint", CANONICAL_MESSAGE_V2_TASK_SCOPE_KIND)
        RaftThreadSurfaceKind.DirectMessage -> setOf("dm")
        RaftThreadSurfaceKind.ReplyThread -> setOf("thread")
        else -> emptySet()
    }

internal fun Message.belongsToReplyThreadContent(threadChannelId: String): Boolean =
    matchesResolvedReplyThreadTimeline(
        resolvedThreadChannelId = threadChannelId,
        messageChannelId = channelId?.value,
        messageThreadId = threadId.value
    )

internal fun RaftThreadScreenPayload.isUsableCachedPayloadForRoute(route: RaftThreadScreenPayload): Boolean =
    ThreadCandidateProofGate
        .prove(route = route.toThreadRouteId(), candidate = toThreadRouteId())
        .let { it is ThreadIdentityProof.Accepted }

private fun RaftThreadScreenPayload.toThreadRouteId(serverId: ServerId? = null): ThreadRouteId {
    val parentChannel = parentChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val parentMessageStr =
        parentMessageId?.takeIf { it.isNotBlank() }
            ?: originParentMessageId?.takeIf { it.isNotBlank() }
    val parentMessage = parentMessageStr?.let(::MessageId)
    val stream = streamChannelId?.takeIf { it.isNotBlank() }?.let(::ChannelId)
    val source = sourceThreadId?.takeIf { it.isNotBlank() }?.let(::ThreadId)
    val isReplyThread =
        surfaceKind == RaftThreadSurfaceKind.ReplyThread ||
            (parentChannel != null && parentMessage != null)
    return when {
        isReplyThread && parentChannel != null && parentMessage != null -> {
            // v0.2 contract: ReplyThread requires explicit threadChannelId. If stream is
            // missing, we still construct ReplyThread (callers handle missing stream via
            // #275-style `activity_thread_channel_missing` internal state + refresh).
            val resolvedStream = stream ?: source?.let { ChannelId(it.value) }
            if (resolvedStream != null) {
                ThreadRouteId.ReplyThread(
                    parentChannelId = parentChannel,
                    parentMessageId = parentMessage,
                    threadChannelId = resolvedStream,
                    serverId = serverId
                )
            } else {
                // Fallback: synthesize from source threadId to keep v0.2 proof path alive.
                val synthesized = source?.let { ChannelId(it.value) } ?: parentChannel
                ThreadRouteId.ReplyThread(
                    parentChannelId = parentChannel,
                    parentMessageId = parentMessage,
                    threadChannelId = synthesized,
                    serverId = serverId
                )
            }
        }
        else -> ThreadRouteId.ChannelThread(
            channelId = source?.let { ChannelId(it.value) } ?: parentChannel ?: ChannelId(""),
            serverId = serverId
        )
    }
}

internal fun RaftThreadScreenPayload.visibleReadMarker(
    visibleTailIndex: Int? = null
): ThreadVisibleReadMarker? {
    val channelId = cacheThreadId() ?: return null
    // task #232: bound the read marker to the rendered visible tail when known,
    // so live appends while off-bottom never advance the shared read frontier.
    val markerCandidates =
        visibleTailIndex?.takeIf { it in messages.indices }?.let { tail ->
            messages.subList(0, tail + 1)
        } ?: messages
    val latest =
        markerCandidates
            .asSequence()
            .filter { message -> message.seq != null }
            .maxWithOrNull(
                compareBy<RaftThreadMessageItem> { message -> message.seq ?: Long.MIN_VALUE }
                    .thenBy(RaftThreadMessageItem::id)
            )
            ?: messages.lastOrNull()
            ?: return null
    return ThreadVisibleReadMarker(
        channelId = channelId,
        latestMessageId = latest.id,
        latestSeq = latest.seq
    )
}

internal fun RaftThreadScreenPayload.applyRealtimeEvent(
    event: RealtimeEvent,
    currentUserId: String?,
    activeReplyParentMessageId: MessageId? = null,
    shouldPreserveReactionsForRealtime: (Message, String?) -> Boolean = { _, _ -> false }
): RaftThreadScreenPayload =
    when (event) {
        is MessageNew ->
            applyRealtimeMessages(listOf(event.message), currentUserId, activeReplyParentMessageId)
        is MessageUpdated ->
            applyRealtimeMessageUpdate(
                message = event.message,
                currentUserId = currentUserId,
                activeReplyParentMessageId = activeReplyParentMessageId,
                preserveExistingReactions = shouldPreserveReactionsForRealtime(event.message, currentUserId)
            )
        is SyncResumeResponse ->
            applyRealtimeMessages(event.messages, currentUserId, activeReplyParentMessageId)
        is ThreadUpdated ->
            applyThreadUpdated(event)
        is MemberPresenceUpdated ->
            this
        // Also replayed onto a load that was in flight when the channel was (un)archived.
        is RealtimeRefreshRequired ->
            withChannelUpdateArchiveState(event)
        else -> this
    }

internal fun RaftThreadScreenPayload.realtimeNewerGapServerSeq(
    event: RealtimeEvent,
    activeReplyParentMessageId: MessageId? = null
): Long? {
    val currentMaxSeq = messages.mapNotNull { it.seq }.maxOrNull() ?: 0L
    if (currentMaxSeq <= 0L) return null
    val existingIds = messages.mapTo(mutableSetOf()) { it.id }
    val eventMessages =
        when (event) {
            is MessageNew -> listOf(event.message)
            is SyncResumeResponse -> event.messages
            else -> return null
        }
    return eventMessages
        .asSequence()
        .filter { message -> message.matchesSharedThread(this, activeReplyParentMessageId) }
        .filterNot { message -> message.id.value in existingIds }
        .mapNotNull { message -> message.seq }
        .filter { seq -> seq > currentMaxSeq + 1L }
        .maxOrNull()
}

private fun RaftThreadScreenPayload.applyRealtimeMessages(
    messages: List<Message>,
    currentUserId: String?,
    activeReplyParentMessageId: MessageId?
): RaftThreadScreenPayload {
    val currentMaxSeq = this.messages.mapNotNull { it.seq }.maxOrNull() ?: 0L
    val existingIds = this.messages.mapTo(mutableSetOf()) { it.id }
    val relevant =
        messages.filter { message ->
            message.matchesSharedThread(this, activeReplyParentMessageId)
        }.filter { message ->
            if (!canLoadMoreAfter) return@filter true
            if (message.id.value in existingIds) return@filter true
            val seq = message.seq ?: return@filter true
            currentMaxSeq <= 0L || seq <= currentMaxSeq + 1L
        }
    if (relevant.isEmpty()) return this
    val incoming = relevant.map { message -> message.toRaftThreadMessageItem(currentUserId) }
    val baseMessages =
        this.messages.absorbingCanonicalSendEchoes(incoming)
            .withIncomingDelegatedLabels(incoming)
            .withIncomingExternalMentionSpans(incoming)
            // RFC 077 PR 2b: a resumed retracted copy replaces the loaded copy's content.
            .withKnownRetractionsFrom(incoming)
    val merged =
        (baseMessages + incoming)
            .distinctBy { item -> item.id }
    return copy(
        status = "content",
        errorCode = null,
        errorServerMessage = null,
        errorTraceId = null,
        messages = merged.sortedByThreadTimeline()
    ).withOriginReplyCountAtLeastLoadedReplies()
        .asLoadedEmptyReplyThreadPayload()
}

/** Task #168 step 1: what the gap catch-up does with its latest page. */
internal enum class GapCatchUpDecision(val wireName: String) {
    /** The anchor is on the page: it overlaps the timeline, merge it at the newest edge. */
    MergeAtNewestEdge("merge"),

    /** The anchor is not on the page and the viewport follows the latest: show the page. */
    ReplaceWithLatestPage("replace"),

    /**
     * The anchor is not on the page and the user scrolled up (or the timeline is already a window
     * detached from the tail): keep the view, mark it detached from the tail.
     */
    DetachFromTail("detach"),
}

/**
 * Task #168 step 1: small vs large gap is decided by one explicit fact — is [anchorMessageId] (the
 * newest server-backed message the timeline had before the gap) one of the page's message ids. No
 * count or seq comparison takes part: seqs are server-global, and a page can be full or short for
 * reasons unrelated to the gap. No anchor at all means nothing on screen to keep.
 */
internal fun gapCatchUpDecision(
    anchorMessageId: String?,
    pageMessageIds: List<String>,
    timelineAtTail: Boolean,
    viewportFollowsLatest: Boolean,
): GapCatchUpDecision =
    when {
        anchorMessageId != null && anchorMessageId in pageMessageIds -> GapCatchUpDecision.MergeAtNewestEdge
        timelineAtTail && viewportFollowsLatest -> GapCatchUpDecision.ReplaceWithLatestPage
        else -> GapCatchUpDecision.DetachFromTail
    }

/** The newest server-backed message of the timeline (local sends and their shadows excluded). */
internal fun RaftThreadScreenPayload.gapCatchUpAnchorMessageId(): String? =
    messages.lastOrNull { message ->
        message.id.isNotBlank() &&
            message.seq != null &&
            message.pendingSendContext == null &&
            !message.isTransientLocalSendingMessage()
    }?.id

/** Hands 4b6ccb0d: the highest server seq on the timeline (local sends and their shadows excluded). */
internal fun RaftThreadScreenPayload.newestServerSeqOrNull(): Long? =
    messages
        .filter { message -> message.pendingSendContext == null && !message.isTransientLocalSendingMessage() }
        .mapNotNull { message -> message.seq }
        .maxOrNull()

/** Task #168 step 1: the gap catch-up page size — the enter snapshot's page, the server maximum. */
internal const val SharedThreadGapCatchUpPageLimit: Int = 200

internal fun RaftThreadScreenPayload.mergePagedMessages(
    pageMessages: List<RaftThreadMessageItem>,
    before: Boolean,
    pageSize: Int,
    rawServerRowCount: Int? = null,
): RaftThreadScreenPayload {
    // RFC 077: the loaded copy stays, but the page's explicit `delegatedBy` (newer) fills or
    // replaces its label; a page copy without one never clears a known label.
    // A retracted page copy likewise replaces the loaded copy's words, attachments and reactions.
    // #198 (web messageStore `mergeIncomingMessage`): the server's copy of a message already on
    // screen is newer than the loaded one, so it is merged in like a live update. An App card
    // rewritten while the app was in the background lands on the resume catch-up page; only an
    // older card revision is ignored (RFC 082), and local sends keep their own state.
    val baseMessages =
        messages.absorbingCanonicalSendEchoes(pageMessages)
            .withIncomingDelegatedLabels(pageMessages)
            .withIncomingExternalMentionSpans(pageMessages)
            .withKnownRetractionsFrom(pageMessages)
            .withNewerServerCopiesFrom(pageMessages)
    val incoming = pageMessages.filterNot { pageMessage -> baseMessages.any { it.id == pageMessage.id } }
    val mergedMessages =
        if (before) {
            (incoming + baseMessages).distinctBy { it.id }
        } else {
            (baseMessages + incoming).distinctBy { it.id }
        }.sortedByThreadTimeline()
    // Task #259 (AGENTS.md No silent drops): compare the raw server row count against the requested
    // limit, never the deduped/filtered kept count (a dropped row or an overlap must not end paging).
    val serverRowCount = rawServerRowCount ?: pageMessages.size
    val hasMore = serverRowCount >= SharedThreadPaging.resolve(pageSize)
    return copy(
        status = "content",
        errorCode = null,
        errorServerMessage = null,
        errorTraceId = null,
        messages = mergedMessages,
        canLoadMoreBefore = if (before) hasMore else canLoadMoreBefore,
        canLoadMoreAfter = if (before) canLoadMoreAfter else hasMore,
        olderMessagesStatus = if (before) "idle" else olderMessagesStatus,
        newerMessagesStatus = if (before) newerMessagesStatus else "idle"
    ).withOriginReplyCountAtLeastLoadedReplies()
        .asLoadedEmptyReplyThreadPayload()
}

/**
 * #198: each loaded message the page also carries takes the page's copy through
 * [mergeRealtimeUpdate] (the page is the newer read; an older card revision is ignored, sparse
 * fields keep the loaded value). A local send (pending or still sending) keeps its own row.
 */
internal fun List<RaftThreadMessageItem>.withNewerServerCopiesFrom(
    pageMessages: List<RaftThreadMessageItem>
): List<RaftThreadMessageItem> {
    if (isEmpty() || pageMessages.isEmpty()) return this
    val pageById = pageMessages.associateBy { it.id }
    var changed = false
    val merged = map { loaded ->
        val pageCopy = pageById[loaded.id]
        if (
            pageCopy == null ||
            loaded.id.isBlank() ||
            loaded.pendingSendContext != null ||
            loaded.isTransientLocalSendingMessage()
        ) {
            loaded
        } else {
            val next = loaded.mergeRealtimeUpdate(pageCopy)
            if (next != loaded) changed = true
            next
        }
    }
    return if (changed) merged else this
}

/**
 * Journal rows from different auth/selection generations can share a local id (before 1.19.2 the
 * local-send counter restarted in every process). Only one of them may render: two rows with the
 * same id crash the message list. Keep the newest by creation time; on a tie, the later row.
 */
internal fun List<RaftThreadMessageItem>.newestPerLocalId(): List<RaftThreadMessageItem> {
    if (size < 2 || mapTo(HashSet(size)) { it.id }.size == size) return this
    val keptIndexById = HashMap<String, Int>(size)
    forEachIndexed { index, message ->
        val keptIndex = keptIndexById[message.id]
        if (
            keptIndex == null ||
            (message.createdAtEpochMillis ?: Long.MIN_VALUE) >= (this[keptIndex].createdAtEpochMillis ?: Long.MIN_VALUE)
        ) {
            keptIndexById[message.id] = index
        }
    }
    return filterIndexed { index, message -> keptIndexById[message.id] == index }
}

internal fun RaftThreadScreenPayload.withLocalSendStarted(
    message: RaftThreadMessageItem
): RaftThreadScreenPayload {
    // A send (or a retry) starts after everything already on screen, even when this device's
    // clock is behind the server's: never earlier than the newest server-backed row
    // (web: optimisticDisplaySeq = lastSeq + 1).
    val newestServerBacked = messages.newestServerBackedCreatedAtEpochMillis()
    val sendingMessage =
        message.copy(
            deliveryState = ThreadMessageDeliverySending,
            pendingSendErrorCode = null,
            pendingSendErrorMessage = null,
            createdAtEpochMillis =
                message.createdAtEpochMillis?.let { createdAt ->
                    if (newestServerBacked != null && newestServerBacked > createdAt) newestServerBacked else createdAt
                }
        )
    val nextMessages =
        if (messages.any { it.id == sendingMessage.id }) {
            messages.map { existing -> if (existing.id == sendingMessage.id) sendingMessage else existing }
        } else {
            messages + sendingMessage
        }
    return copy(
        status = "content",
        errorCode = null,
        errorServerMessage = null,
        errorTraceId = null,
        messages = nextMessages.sortedByThreadTimeline()
    ).withOriginReplyCountAtLeastLoadedReplies()
}

internal fun RaftThreadScreenPayload.withLocalSendFailure(
    messageId: String,
    errorCode: String?,
    errorMessage: String?
): RaftThreadScreenPayload =
    copy(
        messages =
            messages.map { message ->
                if (message.id == messageId && message.pendingSendContext != null) {
                    message.copy(
                        deliveryState = ThreadMessageDeliveryFailed,
                        pendingSendErrorCode = errorCode,
                        pendingSendErrorMessage = errorMessage
                    )
                } else {
                    message
                }
            }
    )

internal fun RaftThreadScreenPayload.withoutLocalSendMessage(messageId: String): RaftThreadScreenPayload =
    copy(messages = messages.filterNot { it.id == messageId && it.pendingSendContext != null })

internal fun RaftThreadScreenPayload.withoutLocalFailedSendMessage(messageId: String): RaftThreadScreenPayload =
    copy(messages = messages.filterNot { it.id == messageId && it.isPersistentLocalFailedMessage() })

internal fun RaftThreadScreenPayload.withoutTransientLocalSendingMessages(): RaftThreadScreenPayload {
    val retainedMessages = messages.filterNot { it.isTransientLocalSendingMessage() }
    return if (retainedMessages.size == messages.size) this else copy(messages = retainedMessages)
}

internal fun RaftThreadScreenPayload.withoutLocalPendingSendMessages(): RaftThreadScreenPayload {
    val retainedMessages = messages.filterNot { it.pendingSendContext != null }
    return if (retainedMessages.size == messages.size) this else copy(messages = retainedMessages)
}

internal fun List<RaftThreadMessageItem>.withRetainedTransientLocalSendingMessagesFrom(
    previousMessages: List<RaftThreadMessageItem>
): List<RaftThreadMessageItem> {
    val existingIds = map { it.id }.toSet()
    val retainedSendingMessages =
        previousMessages.filter { message ->
            message.id !in existingIds && message.isTransientLocalSendingMessage()
        }
    return if (retainedSendingMessages.isEmpty()) this else (this + retainedSendingMessages).sortedByThreadTimeline()
}

internal fun List<RaftThreadMessageItem>.withRetainedCanonicalOwnSendsAfterRefreshFrom(
    previousMessages: List<RaftThreadMessageItem>,
    refreshObservedMessageIds: Set<String>?
): List<RaftThreadMessageItem> {
    refreshObservedMessageIds ?: return this
    val existingIds = mapTo(mutableSetOf()) { message -> message.id }
    val incomingRandomIds = mapNotNullTo(mutableSetOf()) { message -> message.randomId?.takeIf { it.isNotBlank() } }
    val retainedMessages =
        previousMessages.filter { message ->
            val randomId = message.randomId?.takeIf { it.isNotBlank() } ?: return@filter false
            message.id !in existingIds &&
                message.id !in refreshObservedMessageIds &&
                message.isCanonicalOwnSend() &&
                randomId !in incomingRandomIds
        }
    return if (retainedMessages.isEmpty()) this else (this + retainedMessages).sortedByThreadTimeline()
}

private fun RaftThreadMessageItem.isCanonicalOwnSend(): Boolean =
    isOwn &&
        pendingSendContext == null &&
        deliveryState.equals(ThreadMessageDeliverySent, ignoreCase = true) &&
        !randomId.isNullOrBlank()

internal fun List<RaftThreadMessageItem>.withRetainedLocalFailedMessagesFrom(
    previousMessages: List<RaftThreadMessageItem>
): List<RaftThreadMessageItem> {
    val existingIds = map { it.id }.toSet()
    val retainedFailedMessages =
        previousMessages.filter { message ->
            message.id !in existingIds && message.isPersistentLocalFailedMessage()
        }
    return if (retainedFailedMessages.isEmpty()) this else (this + retainedFailedMessages).sortedByThreadTimeline()
}

private fun RaftThreadMessageItem.isPersistentLocalFailedMessage(): Boolean =
    id.startsWith(ThreadTransientLocalSendIdPrefix) &&
        deliveryState.equals(ThreadMessageDeliveryFailed, ignoreCase = true) &&
        pendingSendContext != null

private fun RaftThreadScreenPayload.trimToLatestThreadWindow(limit: Int): RaftThreadScreenPayload {
    if (limit <= 0 || messages.isEmpty()) return withOriginReplyCountAtLeastLoadedReplies()
    val sortedMessages = messages.sortedByThreadTimeline()
    if (sortedMessages.size <= limit) {
        return (if (sortedMessages == messages) this else copy(messages = sortedMessages))
            .withOriginReplyCountAtLeastLoadedReplies()
    }
    val projectedOrigin =
        originMessage?.let { origin ->
            if (sortedMessages.size > origin.replyCount) {
                origin.copy(replyCount = sortedMessages.size)
            } else {
                origin
            }
        }
    return copy(
        status = "content",
        errorCode = null,
        errorServerMessage = null,
        errorTraceId = null,
        messages = sortedMessages.takeLast(limit),
        originMessage = projectedOrigin,
        canLoadMoreBefore = true,
        olderMessagesStatus = "idle"
    ).withOriginReplyCountAtLeastLoadedReplies()
}

internal fun RaftThreadScreenPayload.withOriginReplyCountAtLeastLoadedReplies(): RaftThreadScreenPayload {
    val origin = originMessage ?: return this
    val loadedReplyCount = messages.size
    if (loadedReplyCount <= origin.replyCount) return this
    return copy(originMessage = origin.copy(replyCount = loadedReplyCount))
}

/**
 * The thread timeline, oldest first.
 *
 * Server-backed rows keep [SharedThreadTimelineComparator]'s order. Local sends (sending / failed)
 * sit where they were sent: each one goes right after the last server-backed row created at or
 * before it, so messages that arrive later land below it (web `compareMessagesForDisplay`: a
 * local row sits where its server `seq` would land). A failed send from days ago therefore scrolls
 * up with the conversation instead of staying pinned under every newer message. A send that just
 * started is stamped no earlier than the newest row it was sent after ([withLocalSendStarted]),
 * so it is still the bottom row. A local row with no creation instant stays last.
 *
 * A merge rather than one comparator: the server order is by `seq`, the local placement by time,
 * and a single comparator mixing the two is not transitive when `seq` and time disagree.
 */
internal fun List<RaftThreadMessageItem>.sortedByThreadTimeline(): List<RaftThreadMessageItem> {
    if (none { it.hasLocalSendState() }) return sortedWith(SharedThreadTimelineComparator)
    val (localSends, serverBacked) = partition { it.hasLocalSendState() }
    val timeline = serverBacked.sortedWith(SharedThreadTimelineComparator)
    // Same comparator within the local lane: creation instant, then the local-send ordinal; an
    // unknown instant sorts last, so the insertion points below never go backwards.
    val locals = localSends.sortedWith(SharedThreadTimelineComparator)
    val merged = ArrayList<RaftThreadMessageItem>(size)
    var next = 0
    locals.forEach { local ->
        val createdAt = local.createdAtEpochMillis
        val insertAt =
            if (createdAt == null) {
                timeline.size
            } else {
                timeline.indexOfLast { row -> row.createdAtEpochMillis?.let { it <= createdAt } == true } + 1
            }
        while (next < insertAt) merged += timeline[next++]
        merged += local
    }
    while (next < timeline.size) merged += timeline[next++]
    return merged
}

/** The newest creation instant among server-backed rows; a send started now sorts no earlier. */
internal fun List<RaftThreadMessageItem>.newestServerBackedCreatedAtEpochMillis(): Long? =
    filterNot { it.hasLocalSendState() }.mapNotNull { it.createdAtEpochMillis }.maxOrNull()

/**
 * A single lexicographic total order for every message pair.
 *
 * Canonical server messages stay sequence-authoritative. A sent message whose
 * sequence has not arrived yet is kept in a separate fallback lane ordered by
 * its numeric creation instant. Optimistic/failed sends form their own lane,
 * ordered by creation instant plus a stable local-send ordinal;
 * [sortedByThreadTimeline] then places them among the server rows by time. No
 * branch reads the localized [RaftThreadMessageItem.timestampLabel].
 *
 * The old pair-dependent comparator used `seq` only when both operands had it,
 * then compared display strings for mixed pairs. That admitted cycles such as
 * A(seq=1,label=z) < B(seq=2,label=a) < M(seq=null,label=m) < A.
 */
internal val SharedThreadTimelineComparator =
    Comparator<RaftThreadMessageItem> { left, right ->
        compareValues(left.threadTimelineSortLane(), right.threadTimelineSortLane())
            .takeIf { it != 0 }
            ?: compareValues(left.threadTimelineSequenceKey(), right.threadTimelineSequenceKey())
                .takeIf { it != 0 }
            ?: compareValues(left.createdAtEpochMillis ?: Long.MAX_VALUE, right.createdAtEpochMillis ?: Long.MAX_VALUE)
                .takeIf { it != 0 }
            ?: compareValues(left.localSendOrder ?: Long.MAX_VALUE, right.localSendOrder ?: Long.MAX_VALUE)
                .takeIf { it != 0 }
            ?: left.id.compareTo(right.id)
    }

private fun RaftThreadMessageItem.threadTimelineSortLane(): Int =
    when {
        hasLocalSendState() -> 2
        seq != null -> 0
        else -> 1
    }

private fun RaftThreadMessageItem.threadTimelineSequenceKey(): Long =
    if (threadTimelineSortLane() == 0) seq ?: Long.MAX_VALUE else Long.MAX_VALUE

private fun RaftThreadScreenPayload.applyRealtimeMessageUpdate(
    message: Message,
    currentUserId: String?,
    activeReplyParentMessageId: MessageId?,
    preserveExistingReactions: Boolean = false
): RaftThreadScreenPayload {
    val incoming = message.toRaftThreadMessageItem(currentUserId)
    val matchesTimeline = messages.any { it.id == incoming.id }
    val matchesOrigin = originMessage?.id == incoming.id
    val replyThreadContentChannelId = replyThreadContentChannelId()
    val matchesReplyOrigin =
        replyThreadContentChannelId != null &&
            (
                matchesOrigin ||
                    parentMessageId?.takeIf { it.isNotBlank() } == incoming.id ||
                    originParentMessageId?.takeIf { it.isNotBlank() } == incoming.id
                )
    val matchesThreadContent =
        if (matchesReplyOrigin) {
            false
        } else if (replyThreadContentChannelId != null) {
            message.belongsToReplyThreadContent(replyThreadContentChannelId)
        } else {
            message.matchesSharedThread(this, activeReplyParentMessageId)
        }
    val shouldUpdateTimeline = matchesTimeline || matchesThreadContent
    if (!matchesOrigin && !shouldUpdateTimeline) return this
    val reactionUpdate = message.reactions.toRealtimeThreadReactionItems(currentUserId, threadReactorNameById())
    val updatedMessages =
        if (shouldUpdateTimeline) {
            messages.map { item ->
                if (item.id == incoming.id) {
                    item.mergeRealtimeUpdate(
                        update = incoming,
                        reactionUpdate = reactionUpdate,
                        preserveExistingReactions = preserveExistingReactions
                    )
                } else {
                    item
                }
            }
        } else {
            messages
        }
    val mergedMessages =
        if (!shouldUpdateTimeline || updatedMessages.any { it.id == incoming.id }) {
            updatedMessages
        } else {
            updatedMessages + incoming
        }
    val updatedOrigin =
        if (matchesOrigin) {
            originMessage.mergeRealtimeUpdate(
                update = incoming,
                reactionUpdate = reactionUpdate,
                preserveExistingReactions = preserveExistingReactions
            )
        } else {
            originMessage
        }
    return copy(
        status = "content",
        errorCode = null,
        errorServerMessage = null,
        errorTraceId = null,
        messages = mergedMessages.distinctBy { it.id },
        originMessage = updatedOrigin
    ).withOriginReplyCountAtLeastLoadedReplies()
}

private fun RaftThreadScreenPayload.applyThreadUpdated(event: ThreadUpdated): RaftThreadScreenPayload {
    fun RaftThreadMessageItem.updateIfMatches(): RaftThreadMessageItem =
        if (id == event.parentMessageId.value) {
            copy(
                threadChannelId = event.threadChannelId.value,
                replyCount = event.replyCount,
                unreadReplyCount = event.unreadCount ?: unreadReplyCount,
                firstUnreadReplyMessageId =
                    when {
                        event.unreadCount == 0 -> null
                        event.firstUnreadMessageId != null -> event.firstUnreadMessageId?.value
                        else -> firstUnreadReplyMessageId
                    },
            )
        } else {
            this
        }
    val updatedMessages = messages.map { it.updateIfMatches() }
    val updatedOrigin = originMessage?.updateIfMatches()
    if (updatedMessages == messages && updatedOrigin == originMessage) return this
    return copy(messages = updatedMessages, originMessage = updatedOrigin)
        .withOriginReplyCountAtLeastLoadedReplies()
}

/**
 * The parent conversation's cached payload after a [ThreadUpdated] that arrived while another page
 * was open: the same count/unread patch the open page applies, plus the reply folded into the
 * parent's inline preview the way the replies sync host folds it on the page itself.
 */
internal fun RaftThreadScreenPayload.withOffPageThreadUpdated(event: ThreadUpdated): RaftThreadScreenPayload {
    val parentId = event.parentMessageId.value
    val reply = event.latestReply?.takeIf { event.syncCoreReplyWindow?.producer == ThreadRepliesSyncWindowProducer }
    val previousParent = (messages + listOfNotNull(originMessage)).firstOrNull { it.id == parentId } ?: return this
    val counted = applyThreadUpdated(event.copy(latestReply = null))
    reply ?: return counted
    val previousThreadChannelId = previousParent.threadChannelId?.trim()?.takeIf { it.isNotBlank() }
    val baseline =
        previousParent.inlineReplies
            ?.takeIf { previousThreadChannelId == null || previousThreadChannelId == event.threadChannelId.value }
            ?: initialThreadRepliesReadModel(emptyList(), 0, InlineThreadReplyPreviewLimit)
    val model = baseline.applyReplyFrame(reply, event.replyCount.coerceAtLeast(0), InlineThreadReplyPreviewLimit)
    fun RaftThreadMessageItem.fold(): RaftThreadMessageItem =
        if (id == parentId) copy(replyCount = model.replyCount, inlineReplies = model) else this
    return counted.copy(messages = counted.messages.map { it.fold() }, originMessage = counted.originMessage?.fold())
}

/**
 * The parent conversation's cached payload after the viewer's own reply under [parentMessageId] was
 * accepted by the server (see [SharedThreadDataSource.applySentReplyToParentConversation]). The reply
 * folds into the parent's inline preview; the count never goes down and only grows past what the
 * parent already showed when its inline window proves the reply was not counted yet (newer than
 * every reply it holds), so a `thread:updated` that already landed is not counted twice.
 */
internal fun RaftThreadScreenPayload.withSentReplyOnParent(
    parentMessageId: String,
    threadChannelId: String,
    reply: ReplyPreview,
    replyCountAtLeast: Int,
): RaftThreadScreenPayload {
    val previousParent = (messages + listOfNotNull(originMessage)).firstOrNull { it.id == parentMessageId } ?: return this
    val previousThreadChannelId = previousParent.threadChannelId?.trim()?.takeIf { it.isNotBlank() }
    val window =
        previousParent.inlineReplies
            ?.takeIf { previousThreadChannelId == null || previousThreadChannelId == threadChannelId }
    val replyIsUncounted =
        window != null &&
            window.replies.none { it.messageId == reply.messageId } &&
            reply.seq > window.snapshotSeq &&
            window.replies.all { it.seq < reply.seq }
    val parentCount = maxOf(previousParent.replyCount, window?.replyCount ?: 0)
    val replyCount =
        maxOf(
            parentCount + if (replyIsUncounted || parentCount == 0) 1 else 0,
            replyCountAtLeast,
        )
    val model =
        (window ?: initialThreadRepliesReadModel(emptyList(), 0, InlineThreadReplyPreviewLimit))
            .applyReplyFrame(reply, replyCount, InlineThreadReplyPreviewLimit)
    fun RaftThreadMessageItem.fold(): RaftThreadMessageItem =
        if (id != parentMessageId) {
            this
        } else {
            copy(
                threadChannelId = previousThreadChannelId ?: threadChannelId,
                replyCount = maxOf(replyCount, model.replyCount),
                inlineReplies = model,
            )
        }
    val nextMessages = messages.map { it.fold() }
    val nextOrigin = originMessage?.fold()
    if (nextMessages == messages && nextOrigin == originMessage) return this
    return copy(messages = nextMessages, originMessage = nextOrigin)
}

internal fun RaftThreadScreenPayload.withActionCard(messageId: String, card: ThreadActionCard): RaftThreadScreenPayload =
    copy(
        messages = messages.map { message ->
            if (message.id == messageId) message.withActionCard(card) else message
        },
        originMessage = originMessage?.let { message ->
            if (message.id == messageId) message.withActionCard(card) else message
        }
    )

private fun RaftThreadMessageItem.withActionCard(card: ThreadActionCard): RaftThreadMessageItem {
    // A card the client rebuilt after its execute/mark-executed call (no server `executedAt`) must
    // not replace a done card the server already sent (realtime `message_updated` can land before
    // the HTTP answer): that would drop the committed-by line and the result.
    val current = threadActionCard()
    if (current != null && current.isTerminalState && !card.isTerminalState) {
        return this
    }
    if (current != null && current.isExecuted && current.executedAt != null && card.isExecuted && card.executedAt == null) {
        return this
    }
    return copy(metadata = metadata.filterKeys { !it.startsWith("actionCard.") } + card.toMessageMetadata())
}

internal fun List<String>.cleanActionCardIds(): List<String> =
    map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

internal fun RaftThreadScreenPayload.isLoadedContentFrame(): Boolean = status == "content" || status == "empty"
