package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.InboxItem
import build.raft.app.data.ChannelFilesSnapshot
import build.raft.app.data.ThreadReplySummariesSnapshot
import build.raft.app.data.map
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.InlineThreadReplyPreviewLimit
import build.raft.app.network.sync.ThreadRepliesAcceptedProjection
import build.raft.app.network.sync.ThreadRepliesPageIdentity
import build.raft.app.network.sync.ThreadRepliesReadModel
import build.raft.app.network.sync.ThreadRepliesScopeIdentity
import build.raft.app.network.sync.initialThreadRepliesReadModel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

internal fun RaftThreadScreenPayload.preserveStableTimelineFrom(
    previous: RaftThreadScreenPayload,
    preserveSelectedReactions: Boolean = true,
    preserveAuxiliaryProjectionFromPrevious: Boolean = false,
    refreshObservedMessageIds: Set<String>? = null,
    sendEchoMatchPolicy: ThreadSendEchoMatchPolicy = ThreadSendEchoMatchPolicy.LegacyCompatible
): RaftThreadScreenPayload {
    // A previous payload with derived inline timeline gaps is a discontinuous multi-island
    // offline view, not a contiguous expanded window: merging a fresh canonical page into it
    // would glue separated islands together while dropping the gap rows.
    val visibleWindowMessages =
        if (
            previous.timelineGaps.isEmpty() &&
            (canLoadMoreBefore || previous.canLoadMoreBefore) &&
            messages.shouldRetainExpandedTimelineWindowFrom(previous.messages)
        ) {
            messages.mergeIntoExpandedTimelineWindow(previous.messages)
        } else {
            messages
        }
    val visibleAfterPreviousCanonicalEchoes =
        visibleWindowMessages.absorbingCanonicalSendEchoes(
            canonicalMessages = previous.messages,
            matchPolicy = sendEchoMatchPolicy
        )
    val previousAfterCanonicalEchoes =
        previous.messages.absorbingCanonicalSendEchoes(
            canonicalMessages = visibleAfterPreviousCanonicalEchoes,
            matchPolicy = sendEchoMatchPolicy
        )
    val timelineMessages =
        visibleAfterPreviousCanonicalEchoes
            .withRetainedTransientLocalSendingMessagesFrom(previousAfterCanonicalEchoes)
            .withRetainedCanonicalOwnSendsAfterRefreshFrom(
                previousMessages = previousAfterCanonicalEchoes,
                refreshObservedMessageIds = refreshObservedMessageIds
            )
    val stableMessages =
        timelineMessages
            .withRetainedLocalFailedMessagesFrom(previous.messages)
            // Feedback 03e9a2f6: a retained local shadow (sending OR failed) whose idempotency key
            // the server has already echoed on a canonical row in this timeline is delivered, not
            // pending. Retaining the failed copy beside it rendered a false "send failed" bubble
            // whose Retry invited a duplicate. Exact random-id proof only (no content heuristic).
            .let { messages ->
                messages.absorbingCanonicalSendEchoes(
                    canonicalMessages = messages,
                    matchPolicy = ThreadSendEchoMatchPolicy.ExactRandomId
                )
            }
            .withSelectedReactionsFrom(previous.messages, preserveSelectedReactions)
            .let { messages ->
                if (preserveAuxiliaryProjectionFromPrevious) {
                    messages.withAcceptedAuxiliaryProjectionFrom(previous.messages)
                } else {
                    messages
                }
            }
            // RFC 077: a refreshed / catch-up copy without `delegatedBy` keeps the known label.
            .withKnownDelegatedLabelsFrom(previous.messages + listOfNotNull(previous.originMessage))
            // RFC 077 PR 2b: a refreshed / restored copy never un-retracts a message.
            .withKnownRetractionsFrom(previous.messages + listOfNotNull(previous.originMessage))
            .preserveStableItemsFrom(previous.messages) { item -> item.id }
    val stableStatus =
        if (stableMessages.any { it.isTransientLocalSendingMessage() } && status == "empty") {
            "content"
        } else {
            status
        }
    val preservePreviousOrigin = shouldPreserveLoadedOriginFrom(previous)
    val stableOrigin =
        when {
            preservePreviousOrigin -> previous.originMessage
            originMessage != null && originMessage == previous.originMessage -> previous.originMessage
            else -> originMessage
        }
    val reactionStableOrigin =
        stableOrigin
            ?.withSelectedReactionsFrom(previous.originMessage, preserveSelectedReactions)
            ?.let { origin ->
                val acceptedOrigin = previous.originMessage
                if (preserveAuxiliaryProjectionFromPrevious && acceptedOrigin?.id == origin.id) {
                    origin.withAcceptedAuxiliaryProjectionFrom(acceptedOrigin)
                } else {
                    origin
                }
            }
            ?.let { origin ->
                val known = (previous.messages + listOfNotNull(previous.originMessage)).firstOrNull { it.id == origin.id }
                // RFC 077 PR 2b: whichever copy is known retracted (the kept one, the previous
                // one, or this refresh's) wins; a retraction is never undone.
                origin.withKnownDelegatedByFrom(known).withKnownRetractionFrom(known).withKnownRetractionFrom(originMessage)
            }
    val stableTasks =
        tasks
            .preserveStableItemsFrom(previous.tasks) { item -> item.id }
            .withReferencedThreadTasksFrom(
                previous = previous.tasks,
                messages = stableMessages,
                originMessage = reactionStableOrigin
            )
    val stableOriginParentMessageId =
        if (preservePreviousOrigin) {
            previous.originParentMessageId ?: previous.originMessage?.id ?: originParentMessageId
        } else {
            originParentMessageId
        }
    val stableOriginPreview =
        if (preservePreviousOrigin) previous.originPreview else originPreview
    val stableOriginStatus =
        if (preservePreviousOrigin) previous.originStatus.takeIf { it == "loaded" } ?: "loaded" else originStatus
    val stableOriginErrorCode =
        if (preservePreviousOrigin) previous.originErrorCode else originErrorCode
    if (
        stableMessages === messages &&
        stableTasks === tasks &&
        reactionStableOrigin === originMessage &&
        stableOriginParentMessageId == originParentMessageId &&
        stableOriginPreview == originPreview &&
        stableOriginStatus == originStatus &&
        stableOriginErrorCode == originErrorCode &&
        stableStatus == status
    ) {
        return this
    }
    return copy(
        status = stableStatus,
        messages = stableMessages,
        originMessage = reactionStableOrigin,
        originPreview = stableOriginPreview,
        originParentMessageId = stableOriginParentMessageId,
        originStatus = stableOriginStatus,
        originErrorCode = stableOriginErrorCode,
        tasks = stableTasks
    )
}

/**
 * Saved membership and linked-task facts are auxiliary Store projections, not canonical facts from
 * the messages response. A primary timeline refresh therefore cannot replace an already accepted
 * projection with the response defaults while the Saved/Tasks authorities are still refreshing.
 *
 * This bridge is deliberately used only by the primary refresh commit. The later Saved lookup and
 * SharedTasksDataStore projection remain authoritative and may replace or remove these retained
 * facts. Identity is exact message id; unmatched rows never inherit another row's projection.
 */
private fun List<RaftThreadMessageItem>.withAcceptedAuxiliaryProjectionFrom(
    previous: List<RaftThreadMessageItem>
): List<RaftThreadMessageItem> {
    if (isEmpty() || previous.isEmpty()) return this
    val previousById = previous.associateBy { message -> message.id }
    return map { message ->
        previousById[message.id]
            ?.takeIf { accepted -> accepted.id.isNotBlank() }
            ?.let(message::withAcceptedAuxiliaryProjectionFrom)
            ?: message
    }
}

private fun RaftThreadMessageItem.withAcceptedAuxiliaryProjectionFrom(
    previous: RaftThreadMessageItem
): RaftThreadMessageItem {
    if (id.isBlank() || id != previous.id) return this
    val previousHasTaskProjection =
        previous.taskId != null ||
            previous.taskNumber != null ||
            previous.taskStatus != null ||
            previous.taskAssigneeKind != null ||
            previous.taskAssigneeId != null ||
            previous.taskAssigneeName != null
    return copy(
        isSaved = previous.isSaved,
        taskId = if (previousHasTaskProjection) previous.taskId else taskId,
        taskNumber = if (previousHasTaskProjection) previous.taskNumber else taskNumber,
        taskStatus = if (previousHasTaskProjection) previous.taskStatus else taskStatus,
        taskAssigneeKind = if (previousHasTaskProjection) previous.taskAssigneeKind else taskAssigneeKind,
        taskAssigneeId = if (previousHasTaskProjection) previous.taskAssigneeId else taskAssigneeId,
        taskAssigneeName = if (previousHasTaskProjection) previous.taskAssigneeName else taskAssigneeName
    )
}

internal fun String?.toThreadRepliesParentScopeKind(): String =
    when (this?.trim()?.lowercase()) {
        "private" -> "private"
        "joint" -> "joint"
        "dm", "direct", "direct_message", "direct-message", "directmessage" -> "dm"
        else -> "channel"
    }

internal fun RaftThreadScreenPayload.threadRepliesScopeIdentities(
    page: ThreadRepliesPageIdentity,
    previous: RaftThreadScreenPayload,
): List<ThreadRepliesScopeIdentity> {
    val previousById =
        (previous.messages + listOfNotNull(previous.originMessage)).associateBy { it.id }
    return (messages + listOfNotNull(originMessage)).mapNotNull { message ->
        val threadChannelId =
            message.threadChannelId?.trim()?.takeIf { it.isNotBlank() }
                ?: previousById[message.id]?.threadChannelId?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
        ThreadRepliesScopeIdentity(
            page = page,
            parentMessageId = message.id,
            threadChannelId = threadChannelId,
        )
    }
}

internal fun RaftThreadScreenPayload.withAcceptedThreadRepliesProjection(
    accepted: Map<String, ThreadRepliesAcceptedProjection>,
    previous: RaftThreadScreenPayload,
): RaftThreadScreenPayload {
    val previousById =
        (previous.messages + listOfNotNull(previous.originMessage)).associateBy { it.id }

    fun RaftThreadMessageItem.project(): RaftThreadMessageItem {
        val projection = accepted[id]
        if (projection != null) {
            return copy(
                threadChannelId = projection.identity.threadChannelId,
                replyCount =
                    if (projection.fromLocalCache) {
                        maxOf(replyCount, projection.model.replyCount)
                    } else {
                        projection.model.replyCount
                    },
                inlineReplies = projection.model,
            )
        }
        val prior = previousById[id]
        val priorThreadChannelId = prior?.threadChannelId?.trim()?.takeIf { it.isNotBlank() }
        val incomingThreadChannelId = threadChannelId?.trim()?.takeIf { it.isNotBlank() }
        val sameScope =
            prior != null &&
                (priorThreadChannelId == null ||
                    incomingThreadChannelId == null ||
                    priorThreadChannelId == incomingThreadChannelId)
        if (!sameScope) return if (inlineReplies == null) this else copy(inlineReplies = null)
        val priorAccepted = prior?.inlineReplies ?: return if (inlineReplies == null) this else copy(inlineReplies = null)
        return copy(
            threadChannelId = incomingThreadChannelId ?: priorThreadChannelId,
            replyCount = priorAccepted.replyCount,
            inlineReplies = priorAccepted,
        )
    }

    val projectedMessages = messages.map { it.project() }
    val projectedOrigin = originMessage?.project()
    return if (projectedMessages == messages && projectedOrigin == originMessage) {
        this
    } else {
        copy(messages = projectedMessages, originMessage = projectedOrigin)
    }
}

/**
 * Applies the Saved/reply-summary result produced by [refreshSecondaryTimelineData] without
 * replacing canonical timeline state that committed while the auxiliary projection was in
 * flight. [previous] is the live payload observed immediately before the projection callback was
 * requested, so each auxiliary field can use a three-way merge: apply [patch] only while the live
 * field still equals that baseline; otherwise retain the newer live value.
 */
internal fun RaftThreadScreenPayload.withSecondaryTimelineProjectionFrom(
    patch: RaftThreadScreenPayload,
    previous: RaftThreadScreenPayload,
): RaftThreadScreenPayload {
    val patchById =
        (patch.messages + listOfNotNull(patch.originMessage))
            .filter { message -> message.id.isNotBlank() }
            .associateBy { message -> message.id }
    val previousById =
        (previous.messages + listOfNotNull(previous.originMessage))
            .filter { message -> message.id.isNotBlank() }
            .associateBy { message -> message.id }
    fun <T> projectField(live: T, baseline: T, candidate: T): T =
        if (live == baseline) candidate else live

    fun RaftThreadMessageItem.project(): RaftThreadMessageItem {
        val candidate = patchById[id] ?: return this
        val baseline = previousById[id] ?: return this
        return copy(
            threadChannelId = projectField(threadChannelId, baseline.threadChannelId, candidate.threadChannelId),
            replyCount = projectField(replyCount, baseline.replyCount, candidate.replyCount),
            unreadReplyCount =
                projectField(unreadReplyCount, baseline.unreadReplyCount, candidate.unreadReplyCount),
            firstUnreadReplyMessageId =
                projectField(
                    firstUnreadReplyMessageId,
                    baseline.firstUnreadReplyMessageId,
                    candidate.firstUnreadReplyMessageId,
                ),
            isThreadFollowed =
                projectField(isThreadFollowed, baseline.isThreadFollowed, candidate.isThreadFollowed),
            inlineReplies = projectField(inlineReplies, baseline.inlineReplies, candidate.inlineReplies),
            isSaved = projectField(isSaved, baseline.isSaved, candidate.isSaved),
        )
    }

    val projectedMessages = messages.map { message -> message.project() }
    val projectedOrigin = originMessage?.project()
    val projectedAuthority =
        projectField(replySummariesAuthority, previous.replySummariesAuthority, patch.replySummariesAuthority)
    return if (
        projectedMessages == messages &&
        projectedOrigin == originMessage &&
        projectedAuthority == replySummariesAuthority
    ) {
        this
    } else {
        copy(
            messages = projectedMessages,
            originMessage = projectedOrigin,
            replySummariesAuthority = projectedAuthority,
        ).withOriginReplyCountAtLeastLoadedReplies()
    }
}

/**
 * Fills the replies block of messages that have none yet from windows seeded out of the durable
 * reply-window cache. A message whose own thread channel names a different scope keeps its state.
 */
internal fun RaftThreadScreenPayload.withSeededReplyWindows(
    seeded: Map<String, ThreadRepliesAcceptedProjection>,
): RaftThreadScreenPayload {
    if (seeded.isEmpty()) return this
    fun RaftThreadMessageItem.fill(): RaftThreadMessageItem {
        if (inlineReplies != null) return this
        val projection = seeded[id] ?: return this
        val ownThreadChannelId = threadChannelId?.trim()?.takeIf { it.isNotBlank() }
        if (ownThreadChannelId != null && ownThreadChannelId != projection.identity.threadChannelId) return this
        return copy(
            threadChannelId = ownThreadChannelId ?: projection.identity.threadChannelId,
            replyCount = maxOf(replyCount, projection.model.replyCount),
            inlineReplies = projection.model,
        )
    }
    val filledMessages = messages.map { it.fill() }
    val filledOrigin = originMessage?.fill()
    return if (filledMessages == messages && filledOrigin == originMessage) {
        this
    } else {
        copy(messages = filledMessages, originMessage = filledOrigin)
    }
}

/**
 * The replies window currently rendered under [event]'s parent, used to seed a reply scope the
 * sync host has not accepted yet (cold cache / last-known payload). A parent whose own thread
 * channel names a different scope contributes nothing.
 */
internal fun RaftThreadScreenPayload.inlineRepliesBaselineFor(event: ThreadUpdated): ThreadRepliesReadModel? =
    inlineRepliesBaselineFor(event.parentMessageId, event.threadChannelId)

internal fun RaftThreadScreenPayload.inlineRepliesBaselineFor(
    parentMessageId: MessageId,
    threadChannelId: ChannelId,
): ThreadRepliesReadModel? {
    val parent =
        (messages + listOfNotNull(originMessage)).firstOrNull { it.id == parentMessageId.value }
            ?: return null
    val ownThreadChannelId = parent.threadChannelId?.trim()?.takeIf { it.isNotBlank() }
    if (ownThreadChannelId != null && ownThreadChannelId != threadChannelId.value) return null
    return parent.inlineReplies?.takeIf { it.replies.isNotEmpty() }
}

internal fun RaftThreadScreenPayload.withAcceptedThreadRepliesProjection(
    accepted: ThreadRepliesAcceptedProjection?,
): RaftThreadScreenPayload {
    accepted ?: return this
    fun RaftThreadMessageItem.project(): RaftThreadMessageItem =
        if (id == accepted.identity.parentMessageId) {
            copy(
                threadChannelId = accepted.identity.threadChannelId,
                replyCount = accepted.model.replyCount,
                inlineReplies = accepted.model,
            )
        } else {
            this
        }
    val projectedMessages = messages.map { it.project() }
    val projectedOrigin = originMessage?.project()
    return if (projectedMessages == messages && projectedOrigin == originMessage) {
        this
    } else {
        copy(messages = projectedMessages, originMessage = projectedOrigin)
    }
}

private fun List<RaftThreadMessageItem>.shouldRetainExpandedTimelineWindowFrom(
    previous: List<RaftThreadMessageItem>
): Boolean {
    if (isEmpty() || previous.size <= size) return false
    val previousIds = previous.map { it.id }
    val previousIdSet = previousIds.toSet()
    val existingIncomingIds = map { it.id }.filter { it in previousIdSet }
    if (existingIncomingIds.isEmpty()) return false
    if (
        previous.size >= SharedThreadPaging.DefaultPageSize &&
        existingIncomingIds.size < SharedThreadPaging.DefaultPageSize / 2
    ) {
        return false
    }
    return existingIncomingIds == previousIds.takeLast(existingIncomingIds.size)
}

private fun List<RaftThreadMessageItem>.mergeIntoExpandedTimelineWindow(
    previous: List<RaftThreadMessageItem>
): List<RaftThreadMessageItem> {
    val incomingById = associateBy { it.id }
    val previousIds = previous.mapTo(mutableSetOf()) { it.id }
    return (
        previous.map { message -> incomingById[message.id]?.withKnownDelegatedByFrom(message)?.withKnownRetractionFrom(message) ?: message } +
            filterNot { message -> message.id in previousIds }
        ).sortedByThreadTimeline()
}

private fun RaftThreadScreenPayload.shouldPreserveLoadedOriginFrom(
    previous: RaftThreadScreenPayload
): Boolean {
    val previousOrigin = previous.originMessage ?: return false
    if (originMessage != null) return false
    if (surfaceKind != RaftThreadSurfaceKind.ReplyThread || previous.surfaceKind != RaftThreadSurfaceKind.ReplyThread) {
        return false
    }
    if (originStatus != "missing" && originStatus != "loading") return false
    val incomingOriginId =
        originParentMessageId?.takeIf { it.isNotBlank() }
            ?: parentMessageId?.takeIf { it.isNotBlank() }
            ?: return false
    val previousOriginId =
        previous.originParentMessageId?.takeIf { it.isNotBlank() }
            ?: previous.parentMessageId?.takeIf { it.isNotBlank() }
            ?: previousOrigin.id.takeIf { it.isNotBlank() }
    return previousOriginId == incomingOriginId && previousOrigin.id == incomingOriginId
}

private inline fun <T, K> List<T>.preserveStableItemsFrom(
    previous: List<T>,
    keySelector: (T) -> K
): List<T> {
    if (isEmpty() || previous.isEmpty()) return this
    val previousByKey = previous.associateBy(keySelector)
    var reusedAnyReference = false
    val stableItems =
        map { incoming ->
            val previousItem = previousByKey[keySelector(incoming)]
            if (previousItem != null && previousItem == incoming) {
                reusedAnyReference = reusedAnyReference || previousItem !== incoming
                previousItem
            } else {
                incoming
            }
        }
    val fullyMatchesPrevious =
        stableItems.size == previous.size &&
            stableItems.indices.all { index -> stableItems[index] === previous[index] }
    return when {
        fullyMatchesPrevious -> previous
        reusedAnyReference -> stableItems
        else -> this
    }
}

private fun List<RaftThreadMessageItem>.withSelectedReactionsFrom(
    previous: List<RaftThreadMessageItem>,
    enabled: Boolean
): List<RaftThreadMessageItem> {
    if (!enabled || isEmpty() || previous.isEmpty()) return this
    val previousById = previous.associateBy { it.id }
    var changed = false
    val merged =
        map { message ->
            val previousMessage = previousById[message.id] ?: return@map message
            val nextMessage = message.withSelectedReactionsFrom(previousMessage, enabled = true)
            changed = changed || nextMessage !== message
            nextMessage
        }
    return if (changed) merged else this
}

private fun RaftThreadMessageItem.withSelectedReactionsFrom(
    previous: RaftThreadMessageItem?,
    enabled: Boolean
): RaftThreadMessageItem {
    if (!enabled || previous == null) return this
    val selectedPrevious = previous.reactions.filter { it.selected && it.count > 0 }
    if (selectedPrevious.isEmpty()) return this
    val nextReactions = reactions.withSelectedReactionsFrom(selectedPrevious)
    val orderedReactions = nextReactions.orderedLike(previous.reactions)
    return if (orderedReactions === reactions) this else copy(reactions = orderedReactions)
}

private fun List<RaftThreadReactionItem>.withSelectedReactionsFrom(
    selectedPrevious: List<RaftThreadReactionItem>
): List<RaftThreadReactionItem> {
    var changed = false
    val byEmoji = associateBy { it.emoji }.toMutableMap()
    selectedPrevious.forEach { previousReaction ->
        val incoming = byEmoji[previousReaction.emoji]
        if (incoming == null || !incoming.selected || incoming.count <= 0) {
            byEmoji[previousReaction.emoji] =
                incoming?.copy(
                    count = maxOf(incoming.count, previousReaction.count),
                    selected = true,
                    reactorIds = (incoming.reactorIds + previousReaction.reactorIds).distinct(),
                    reactorNames = (incoming.reactorNames + previousReaction.reactorNames).distinct()
                )
                    ?: previousReaction
            changed = true
        }
    }
    return if (changed) byEmoji.values.toList() else this
}

internal fun List<RaftThreadReactionItem>.orderedLike(
    previous: List<RaftThreadReactionItem>
): List<RaftThreadReactionItem> {
    if (size <= 1 || previous.isEmpty()) return this
    val previousOrder = previous.map { it.emoji }.filter { it.isNotBlank() }.distinct()
    if (previousOrder.isEmpty()) return this
    val currentByEmoji = associateBy { it.emoji }
    val ordered =
        buildList(size) {
            previousOrder.forEach { emoji ->
                currentByEmoji[emoji]?.let(::add)
            }
            this@orderedLike.forEach { reaction ->
                if (reaction.emoji !in previousOrder) add(reaction)
            }
        }
    return if (ordered == this) this else ordered
}

private fun List<RaftThreadTaskItem>.withReferencedThreadTasksFrom(
    previous: List<RaftThreadTaskItem>,
    messages: List<RaftThreadMessageItem>,
    originMessage: RaftThreadMessageItem?
): List<RaftThreadTaskItem> {
    if (previous.isEmpty()) return this
    val referencedMessages = messages + listOfNotNull(originMessage)
    if (referencedMessages.none { it.hasThreadTaskReference() }) return this
    val existingIds = mapNotNull { item -> item.id.takeIf { it.isNotBlank() } }.toSet()
    val existingNumbers = mapNotNull { item -> item.taskNumber }.toSet()
    val retained =
        previous.filter { item ->
            val taskNumberIsSuperseded = item.taskNumber?.let { it in existingNumbers } == true
            item.matchesAnyThreadTaskReference(referencedMessages) &&
                item.id.takeIf { it.isNotBlank() } !in existingIds &&
                !taskNumberIsSuperseded
        }
    if (retained.isEmpty()) return this
    return this + retained
}

private fun RaftThreadMessageItem.hasThreadTaskReference(): Boolean =
    taskId?.isNotBlank() == true || taskNumber != null

private fun RaftThreadTaskItem.matchesAnyThreadTaskReference(
    messages: List<RaftThreadMessageItem>
): Boolean =
    messages.any { message ->
        val taskIdMatches =
            id.isNotBlank() &&
                message.taskId?.takeIf { it.isNotBlank() } == id
        val taskNumberMatches =
            taskNumber != null &&
                message.taskNumber == taskNumber
        taskIdMatches || taskNumberMatches
    }

internal val MemberProfile.memberKey: String
    get() = "${kind.name}:${id.value}"

internal fun ChannelFilesSnapshot.toRaftThreadFilesPayload(
    basePayload: RaftThreadScreenPayload,
    currentPayload: RaftThreadFilesPayload?,
    append: Boolean
): RaftThreadFilesPayload {
    val incoming = items.map { item ->
        val attachment = item.attachment
        RaftThreadFileItem(
            attachmentId = attachment.id.value,
            messageId = item.messageId?.value.orEmpty(),
            channelId = item.channelId?.value.orEmpty(),
            name = attachment.name,
            sizeBytes = attachment.sizeBytes,
            sizeLabel = attachment.sizeLabel,
            createdAtLabel = threadFileCreatedAtValue(item.createdAt),
            sourceLabel = item.filesSourceLabel(),
            sourceType = item.sourceType.orEmpty(),
            sourceChannelId = item.sourceChannelId?.value.orEmpty(),
            sourceParentMessageId = item.sourceParentMessageId?.value.orEmpty(),
            sourceParentMessageShortId = item.sourceParentMessageShortId.orEmpty(),
            mimeType = attachment.mimeType,
            thumbnailUrl = attachment.thumbnailUrl
        )
    }
    val merged =
        if (append) {
            currentPayload.orEmptyFilesPayload(basePayload).items + incoming
        } else {
            incoming
        }.distinctBy { item -> item.attachmentId }
    return RaftThreadFilesPayload(
        instanceId = basePayload.instanceId,
        status = if (merged.isEmpty()) "empty" else "content",
        items = merged,
        hasMore = hasMore,
        loadingMore = false,
        nextCursor = nextCursor,
        errorCode = null
    )
}

internal fun RaftThreadScreenPayload.withReplySummaries(
    snapshot: ThreadReplySummariesSnapshot,
    authority: build.raft.app.data.ThreadReplySummariesAuthority =
        build.raft.app.data.ThreadReplySummariesAuthority.Targeted,
): RaftThreadScreenPayload {
    if (snapshot.threads.isEmpty()) {
        return if (replySummariesAuthority == authority) this else copy(replySummariesAuthority = authority)
    }
    val summariesByParentMessageId =
        snapshot.threads
            .mapNotNull { summary -> summary.parentMessageId?.value?.let { it to summary } }
            .toMap()
    val summariesByThreadChannelId =
        snapshot.threads
            .flatMap { summary ->
                listOfNotNull(
                    summary.threadChannelId?.value?.let { it to summary },
                    summary.channelId.value.let { it to summary }
                )
            }
            .toMap()
    val updatedMessages =
        messages.map { message ->
            val summary =
                summariesByParentMessageId[message.id]
                    ?: message.threadChannelId?.let(summariesByThreadChannelId::get)
                    ?: return@map message
            message.withReplySummary(summary, snapshot.followedThreadKeys)
        }
    return if (updatedMessages == messages && replySummariesAuthority == authority) {
        this
    } else {
        copy(messages = updatedMessages, replySummariesAuthority = authority)
    }
}

private fun RaftThreadMessageItem.withReplySummary(
    summary: InboxItem,
    followedThreadKeys: Set<String>
): RaftThreadMessageItem {
    val summaryThreadChannelId = summary.threadChannelId ?: summary.channelId
    val nextThreadChannelId = threadChannelId ?: summaryThreadChannelId.value
    val nextReplyCount = summary.replyCount.coerceAtLeast(0)
    val nextUnreadReplyCount = maxOf(unreadReplyCount, summary.unreadCount)
    val nextFirstUnreadReplyMessageId = firstUnreadReplyMessageId ?: summary.firstUnreadMessageId?.value
    val nextFollowed = isThreadFollowed || summary.key in followedThreadKeys
    val incomingInlineReplies =
        initialThreadRepliesReadModel(
            replies = summary.latestReplies,
            replyCount = summary.replyCount,
            limit = InlineThreadReplyPreviewLimit,
        )
    // The window shown under this row is this thread's, unless the row names another thread.
    val knownInlineReplies =
        inlineReplies?.takeIf { threadChannelId == null || threadChannelId == summaryThreadChannelId.value }
    val nextInlineReplies = incomingInlineReplies.orNewerKnownWindow(knownInlineReplies)
    if (
        nextThreadChannelId == threadChannelId &&
        nextReplyCount == replyCount &&
        nextUnreadReplyCount == unreadReplyCount &&
        nextFirstUnreadReplyMessageId == firstUnreadReplyMessageId &&
        nextFollowed == isThreadFollowed &&
        nextInlineReplies == inlineReplies
    ) {
        return this
    }
    return copy(
        threadChannelId = nextThreadChannelId,
        replyCount = nextReplyCount,
        unreadReplyCount = nextUnreadReplyCount,
        firstUnreadReplyMessageId = nextFirstUnreadReplyMessageId,
        isThreadFollowed = nextFollowed,
        inlineReplies = nextInlineReplies,
    )
}

/**
 * Web `threadStore.hydrateSummariesWithReplyScopes`: a summary whose newest reply is older than the
 * replies window a message already shows never replaces that window; only the count moves (up).
 * A summary with no previews at all is the common case: the followed-threads list carries none,
 * and it is all [build.raft.app.data.ConversationRepository.getThreadReplySummaries] returns when
 * the channel's own summaries request fails. Taking its empty window emptied the inline replies of
 * every followed parent, and the replies sync host then rebaselined on that empty window (artin,
 * #wg-mobile-sync: "after a message is sent, all the inline thread reply previews are gone").
 */
private fun ThreadRepliesReadModel.orNewerKnownWindow(known: ThreadRepliesReadModel?): ThreadRepliesReadModel {
    known ?: return this
    val knownNewestSeq = known.replies.fold(known.snapshotSeq) { newest, reply -> maxOf(newest, reply.seq) }
    if (knownNewestSeq <= snapshotSeq) return this
    return if (replyCount <= known.replyCount) known else known.copy(replyCount = replyCount)
}

private fun RaftThreadFilesPayload?.orEmptyFilesPayload(basePayload: RaftThreadScreenPayload): RaftThreadFilesPayload =
    this ?: RaftThreadFilesPayload(instanceId = basePayload.instanceId)

private fun build.raft.app.data.ChannelFileItem.filesSourceLabel(): String =
    sourceParentMessageShortId
        ?.takeIf { it.isNotBlank() }
        ?.let { "Thread #$it" }
        ?: sourceType
            ?.takeIf { it.isNotBlank() }
            ?.replaceFirstChar { char -> char.uppercaseChar() }
            .orEmpty()

/**
 * artin #111 (candidate-snapshot rule): a warmed channel / DM page is the answer to this route only when
 * it holds what the route asks for: the focused message for an anchored open, the newest messages
 * (nothing after the page) otherwise. A miss returns null, so the open fetches its own answer. Reply
 * threads keep their existing reuse unchanged.
 */
internal fun DataResult<RaftThreadScreenPayload>.takeUnlessWarmedConversationMisses(
    route: RaftThreadScreenPayload,
): DataResult<RaftThreadScreenPayload>? {
    val warmed = (this as? DataResult.Content)?.data ?: return this
    if (warmed.surfaceKind == RaftThreadSurfaceKind.ReplyThread || route.surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
        return this
    }
    val anchor = route.focusedMessageId?.takeIf { it.isNotBlank() }
    val answersRoute =
        if (anchor != null) {
            warmed.messages.any { message -> message.id == anchor }
        } else {
            !warmed.canLoadMoreAfter
        }
    return this.takeIf { answersRoute }
}

internal fun RaftThreadScreenPayload.hasRenderableThreadContent(): Boolean =
    messages.isNotEmpty() || originMessage != null || tasks.isNotEmpty()

/** Task #1137: the offline stand-in for a channel that was empty when last seen. */
internal fun RaftThreadScreenPayload.isDurableEmptyStandIn(): Boolean =
    durableLastKnown && status == "empty"

internal fun RaftThreadScreenPayload.cacheThreadId(): String? =
    resolveThreadRouteIdentity().timelineChannelId
