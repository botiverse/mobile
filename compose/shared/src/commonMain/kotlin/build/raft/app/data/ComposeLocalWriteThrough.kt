package build.raft.app.data

import build.raft.app.KuiklyThreadSurfaceKind
import build.raft.app.normalizedThreadChannelKind
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.Heartbeat
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.Presence
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.ServerId
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.explicitMessageStreamChannelId
import build.raft.app.model.isGenericDisplayName
import build.raft.app.model.isSameConversationAs
import build.raft.app.model.messageStreamChannelId
import build.raft.app.model.nameIsBlankOrIdentity
import build.raft.app.model.safeDisplayNameOrNull
import build.raft.app.model.threadIdentity
import build.raft.app.model.withTitleFallback
import kotlin.time.TimeSource

class ComposeLocalWriteThrough(
    private val store: ComposeLocalDataStore,
    private val logger: (String) -> Unit = {}
) {
    suspend fun readHomeSnapshot(serverId: ServerId): HomeSnapshot? =
        null

    suspend fun writeHomeResult(result: DataResult<HomeSnapshot>, source: String): Boolean =
        false

    suspend fun readThreadSnapshot(threadId: ThreadId): ThreadSnapshot? {
        val startedAt = TimeSource.Monotonic.markNow()
        return store.readThread(threadId).also { snapshot ->
            logger(
                "store_hydrate domain=thread source=local_read threadId=${threadId.value} " +
                    "hit=${snapshot != null} messages=${snapshot?.messages?.size ?: 0} " +
                    "tasks=${snapshot?.tasks?.size ?: 0} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
        }
    }

    suspend fun writeThreadResult(
        result: DataResult<ThreadSnapshot>,
        source: String,
        fallbackThreadId: ThreadId? = null,
        fallbackHeader: ConversationSummary? = null,
        fallbackParentMessage: Message? = null
    ): Boolean {
        val snapshot = (result as? DataResult.Content)?.data
        if (snapshot == null) {
            logger(
                "write_thread_skip source=$source reason=${result.localWriteSkipReason()} fallbackThreadId=${fallbackThreadId?.value.orEmpty()}"
            )
            return false
        }
        return writeThreadSnapshot(
            snapshot = snapshot,
            source = source,
            fallbackThreadId = fallbackThreadId,
            fallbackHeader = fallbackHeader,
            fallbackParentMessage = fallbackParentMessage
        )
    }

    suspend fun writeReplyThreadResult(
        result: DataResult<ThreadSnapshot>,
        source: String,
        fallbackThreadId: ThreadId,
        fallbackHeader: ConversationSummary,
        fallbackParentMessage: Message
    ) {
        writeThreadResult(
            result = result,
            source = source,
            fallbackThreadId = fallbackThreadId,
            fallbackHeader = fallbackHeader,
            fallbackParentMessage = fallbackParentMessage
        )
    }

    suspend fun writeUnreadCounts(result: DataResult<ChannelUnreadSnapshot>, source: String) {
        val startedAt = TimeSource.Monotonic.markNow()
        val snapshot = (result as? DataResult.Content)?.data
        if (snapshot == null) {
            logger(
                "store_merge_skip domain=home_unread source=$source reason=${result.localWriteSkipReason()} " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
            return
        }
        val unreadWriteStartedAt = TimeSource.Monotonic.markNow()
        store.writeUnread(snapshot)
        val unreadWriteMs = unreadWriteStartedAt.elapsedNow().inWholeMilliseconds
        logger(
            "store_merge domain=home_unread source=$source serverId=${snapshot.server.id.value} " +
                "counts=${snapshot.unreadCounts.size} total=${snapshot.totalUnreadCount} " +
                "unreadWriteMs=$unreadWriteMs durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
        )
    }

    suspend fun clearConversationUnread(channelId: ChannelId, source: String, serverId: ServerId? = null) {
        val startedAt = TimeSource.Monotonic.markNow()
        store.clearChannelUnread(channelId)
        logger(
            "store_merge domain=home_unread source=$source operation=clear_channel channelId=${channelId.value} " +
                "serverId=${serverId?.value.orEmpty()} durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
        )
    }

    suspend fun clearServerUnread(serverId: ServerId, source: String) {
        val startedAt = TimeSource.Monotonic.markNow()
        store.readUnread(serverId)?.let { unread ->
            val updatedUnread =
                unread.copy(
                    unreadCounts = emptyMap(),
                    totalUnreadCount = 0
                )
            if (updatedUnread != unread) {
                store.writeUnread(updatedUnread)
            }
        }
        logger(
            "store_merge domain=home_unread source=$source operation=clear_server serverId=${serverId.value} " +
                "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
        )
    }

    suspend fun writeRealtimeEvent(
        serverId: ServerId,
        event: RealtimeEvent,
        activeConversationId: String? = null
    ): Boolean =
        writeRealtimeEvents(serverId, listOf(event), activeConversationId)

    suspend fun writeRealtimeEvents(
        serverId: ServerId,
        events: List<RealtimeEvent>,
        activeConversationId: String? = null
    ): Boolean {
        val startedAt = TimeSource.Monotonic.markNow()
        if (events.isEmpty()) return false
        val newMessages = mutableListOf<Message>()
        val updatedMessages = mutableListOf<Message>()
        var wrote = false
        events.forEach { event ->
            when (event) {
                is MemberPresenceUpdated -> Unit
                is MessageNew -> newMessages += event.message
                is MessageUpdated -> updatedMessages += event.message
                is SyncResumeResponse -> newMessages += event.messages
                is ThreadUpdated -> Unit
                is RealtimeRefreshRequired -> {
                    logger("write_realtime_refresh_required scope=${event.scope} reason=${event.reason} serverId=${event.serverId?.value.orEmpty()}")
                    wrote = true
                }
                is Heartbeat -> {
                    logger("write_realtime_skip type=heartbeat seq=${event.seq}")
                }
                else -> Unit
            }
        }
        if (newMessages.isNotEmpty()) {
            wrote = writeRealtimeMessages(serverId, newMessages, incrementUnread = true, activeConversationId) || wrote
        }
        if (updatedMessages.isNotEmpty()) {
            wrote = writeRealtimeMessages(serverId, updatedMessages, incrementUnread = false, activeConversationId) || wrote
        }
        logger(
            "store_merge domain=realtime source=events serverId=${serverId.value} events=${events.size} " +
                "newMessages=${newMessages.size} updatedMessages=${updatedMessages.size} wrote=$wrote " +
                "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
        )
        return wrote
    }

    private suspend fun writeThreadSnapshot(
        snapshot: ThreadSnapshot,
        source: String,
        fallbackThreadId: ThreadId? = null,
        fallbackHeader: ConversationSummary? = null,
        fallbackParentMessage: Message? = null
    ): Boolean {
        val startedAt = TimeSource.Monotonic.markNow()
        val snapshotStreamChannelId = snapshot.conversation.writeThroughCanonicalChannelId()
        val readStartedAt = TimeSource.Monotonic.markNow()
        val storedConversation =
            store.readThread(snapshot.conversation.threadId)?.conversation
                ?: fallbackThreadId
                    ?.takeIf { it == snapshot.conversation.threadId || it.value == snapshotStreamChannelId.value }
                    ?.let { store.readThread(it)?.conversation }
        val readMs = readStartedAt.elapsedNow().inWholeMilliseconds
        val fallbackProof =
            ThreadFallbackProof.resolve(
                snapshotConversation = snapshot.conversation,
                snapshotParentMessage = snapshot.parentMessage,
                storedConversation = storedConversation,
                fallbackThreadId = fallbackThreadId,
                fallbackHeader = fallbackHeader,
                fallbackParentMessage = fallbackParentMessage
            )
        if (fallbackProof.rejected) {
            logger(
                "write_thread_fallback_skip source=$source reason=${fallbackProof.rejectionReason ?: "unproven"} " +
                    "threadId=${snapshot.conversation.threadId.value} " +
                    "fallbackThreadId=${fallbackThreadId?.value.orEmpty()} " +
                    "fallbackHeader=${fallbackHeader?.threadId?.value.orEmpty()} " +
                    "fallbackParent=${fallbackParentMessage?.id?.value.orEmpty()}"
            )
        }
        val resolvedParentMessage = snapshot.parentMessage ?: fallbackProof.parentMessage
        val conversationWithResolvedMetadata =
            snapshot.conversation
                .withStoredConversationMetadata(storedConversation)
                .withResolvedReplyThreadMetadata(
                    storedConversation = storedConversation,
                    fallbackHeader = fallbackProof.header,
                    parentMessage = resolvedParentMessage,
                    fallbackThreadId = fallbackProof.aliasThreadId
                )
        val conversation =
            if (conversationWithResolvedMetadata.kind == ConversationKind.Thread) {
                conversationWithResolvedMetadata
            } else {
                conversationWithResolvedMetadata.withTitleFallback(fallbackProof.header)
            }
        val streamChannelId = conversation.writeThroughCanonicalChannelId()
        val messages =
            (listOfNotNull(resolvedParentMessage) + snapshot.messages)
                .map { message ->
                    if (message.channelId == null) message.copy(channelId = streamChannelId) else message
                }
                .dedupeAndSortMessages()
        val updatedSnapshot =
            snapshot.copy(
                conversation = conversation,
                messages = messages,
                parentMessage = resolvedParentMessage,
                lastSeq = snapshot.lastSeq.takeIf { it > 0L } ?: messages.latestSeq()
            )
        val aliasSnapshot =
            fallbackProof.aliasThreadId
                ?.takeIf { it != updatedSnapshot.conversation.threadId }
                ?.let { aliasThreadId ->
                    updatedSnapshot.copy(
                        conversation = updatedSnapshot.conversation.copy(threadId = aliasThreadId)
                    )
                }
        val wroteThread =
            writeThreadPatch(
                snapshot = updatedSnapshot,
                source = source,
                streamChannelId = streamChannelId,
                rawMessageCount = snapshot.messages.size,
                hasParent = resolvedParentMessage != null,
                alias = false
            )
        val wroteAlias =
            aliasSnapshot?.let { alias ->
                writeThreadPatch(
                    snapshot = alias,
                    source = source,
                    streamChannelId = streamChannelId,
                    rawMessageCount = snapshot.messages.size,
                    hasParent = resolvedParentMessage != null,
                    alias = true
                )
            } ?: false
        logger(
            "store_merge domain=thread source=$source threadId=${updatedSnapshot.conversation.threadId.value} " +
                "streamChannelId=${streamChannelId.value} messages=${updatedSnapshot.messages.size} " +
                "tasks=${updatedSnapshot.tasks.size} wrote=$wroteThread aliasWrote=$wroteAlias " +
                "readMs=$readMs durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
        )
        return wroteThread || wroteAlias
    }

    private suspend fun writeThreadPatch(
        snapshot: ThreadSnapshot,
        source: String,
        streamChannelId: ChannelId,
        rawMessageCount: Int,
        hasParent: Boolean,
        alias: Boolean = false
    ): Boolean {
        val startedAt = TimeSource.Monotonic.markNow()
        val readStartedAt = TimeSource.Monotonic.markNow()
        val existingSnapshot = store.readThread(snapshot.conversation.threadId)
        val readMs = readStartedAt.elapsedNow().inWholeMilliseconds
        val mergeStartedAt = TimeSource.Monotonic.markNow()
        val patch = snapshot.reduceThreadSnapshotFrom(existingSnapshot)
        val mergeMs = mergeStartedAt.elapsedNow().inWholeMilliseconds
        if (!patch.changed) {
            logger(
                "store_merge_skip domain=thread source=$source reason=unchanged threadId=${snapshot.conversation.threadId.value} " +
                    "selectorMessages=${patch.selector.messageIds.size} selectorTasks=${patch.selector.taskIds.size} " +
                    "lastSeq=${patch.selector.lastSeq} alias=$alias readMs=$readMs mergeMs=$mergeMs " +
                    "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
            )
            return false
        }
        val writeStartedAt = TimeSource.Monotonic.markNow()
        store.writeThread(patch.snapshot)
        val writeMs = writeStartedAt.elapsedNow().inWholeMilliseconds
        logger(
            "store_merge_patch domain=thread source=$source threadId=${snapshot.conversation.threadId.value} " +
                "streamChannelId=${streamChannelId.value} timelineChanged=${patch.timelineChanged} " +
                "changedMessages=${patch.changedMessageIds.size} changedMessageIds=${patch.changedMessageIds.logIds { it.value }} " +
                "changedTasks=${patch.changedTaskIds.size} changedTaskIds=${patch.changedTaskIds.logIds { it.value }} " +
                "originChanged=${patch.originChanged} selectorMessages=${patch.selector.messageIds.size} " +
                "selectorTasks=${patch.selector.taskIds.size} lastSeq=${patch.selector.lastSeq} " +
                "rawMessages=$rawMessageCount parent=$hasParent alias=$alias readMs=$readMs mergeMs=$mergeMs " +
                "writeMs=$writeMs durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
        )
        return true
    }

    private suspend fun writeRealtimeMessages(
        serverId: ServerId,
        messages: List<Message>,
        incrementUnread: Boolean,
        activeConversationId: String?
    ): Boolean {
        val startedAt = TimeSource.Monotonic.markNow()
        if (messages.isEmpty()) return false
        var wroteThread = false
        var streamCount = 0
        messages.groupBy { message -> message.channelId ?: ChannelId(message.threadId.value) }
            .forEach { (streamChannelId, streamMessages) ->
                streamCount += 1
                val threadId = ThreadId(streamChannelId.value)
                val existing = store.readThread(threadId)
                if (existing != null) {
                    val updated =
                        existing.copy(
                            messages = existing.messages.mergeRealtimeMessages(streamMessages),
                            lastSeq =
                                maxOf(
                                    existing.lastSeq,
                                    streamMessages.latestSeq()
                                )
                        )
                    wroteThread =
                        writeThreadPatch(
                            snapshot = updated,
                            source = "realtime_message",
                            streamChannelId = streamChannelId,
                            rawMessageCount = streamMessages.size,
                            hasParent = updated.parentMessage != null
                        ) || wroteThread
                }
                val activeThreadId =
                    activeConversationId
                        ?.takeIf { it.isNotBlank() && it != streamChannelId.value }
                        ?.let(::ThreadId)
                val activeExisting = activeThreadId?.let { store.readThread(it) }
                if (activeExisting?.parentMessage != null) {
                    val incomingById = streamMessages.associateBy { it.id }
                    val updatedParent =
                        incomingById[activeExisting.parentMessage.id]
                            ?.withStableRealtimeFieldsFrom(activeExisting.parentMessage)
                            ?: activeExisting.parentMessage
                    if (updatedParent != activeExisting.parentMessage) {
                        val updated = activeExisting.copy(parentMessage = updatedParent)
                        wroteThread =
                            writeThreadPatch(
                                snapshot = updated,
                                source = "realtime_active_parent_message",
                                streamChannelId = updated.conversation.messageStreamChannelId(),
                                rawMessageCount = streamMessages.size,
                                hasParent = true
                            ) || wroteThread
                    }
                }
            }
        logger(
            "store_merge domain=realtime_messages serverId=${serverId.value} streams=$streamCount " +
                "messages=${messages.size} incrementUnread=$incrementUnread wroteThread=$wroteThread " +
                "durationMs=${startedAt.elapsedNow().inWholeMilliseconds}"
        )
        return wroteThread
    }
}

private data class ThreadTimelineSelector(
    val messageIds: List<MessageId>,
    val taskIds: List<TaskId>,
    val parentMessageId: MessageId?,
    val lastSeq: Long
)

private data class ThreadSnapshotPatch(
    val snapshot: ThreadSnapshot,
    val selector: ThreadTimelineSelector,
    val changed: Boolean,
    val timelineChanged: Boolean,
    val changedMessageIds: List<MessageId>,
    val changedTaskIds: List<TaskId>,
    val originChanged: Boolean
)

private data class StableEntityMerge<T, Id>(
    val items: List<T>,
    val changedIds: List<Id>
)

private fun ThreadSnapshot.reduceThreadSnapshotFrom(previous: ThreadSnapshot?): ThreadSnapshotPatch {
    val selector = threadTimelineSelector()
    if (previous == null) {
        return ThreadSnapshotPatch(
            snapshot = this,
            selector = selector,
            changed = true,
            timelineChanged = true,
            changedMessageIds = messages.map { it.id },
            changedTaskIds = tasks.map { it.id },
            originChanged = parentMessage != null
        )
    }
    val previousSelector = previous.threadTimelineSelector()
    val stableMessages =
        messages.mergeStableThreadEntitiesFrom(previous.messages, keySelector = { message -> message.id })
    val stableTasks =
        tasks.mergeStableThreadEntitiesFrom(previous.tasks, keySelector = { task -> task.id })
    val stableParentMessage =
        when {
            parentMessage == null -> null
            parentMessage == previous.parentMessage -> previous.parentMessage
            else -> parentMessage
        }
    val stableSnapshot =
        copy(
            messages = stableMessages.items,
            parentMessage = stableParentMessage,
            tasks = stableTasks.items
        )
    val originChanged = parentMessage != previous.parentMessage
    val timelineChanged =
        selector.messageIds != previousSelector.messageIds ||
            selector.taskIds != previousSelector.taskIds ||
            selector.parentMessageId != previousSelector.parentMessageId
    return ThreadSnapshotPatch(
        snapshot = stableSnapshot,
        selector = selector,
        changed = stableSnapshot != previous,
        timelineChanged = timelineChanged,
        changedMessageIds = stableMessages.changedIds,
        changedTaskIds = stableTasks.changedIds,
        originChanged = originChanged
    )
}

private fun ThreadSnapshot.threadTimelineSelector(): ThreadTimelineSelector =
    ThreadTimelineSelector(
        messageIds = messages.map { it.id },
        taskIds = tasks.map { it.id },
        parentMessageId = parentMessage?.id,
        lastSeq = lastSeq
    )

private inline fun <T, Id> List<T>.mergeStableThreadEntitiesFrom(
    previous: List<T>,
    keySelector: (T) -> Id
): StableEntityMerge<T, Id> {
    if (isEmpty()) {
        return StableEntityMerge(
            items = this,
            changedIds = emptyList()
        )
    }
    val previousByKey = previous.associateBy(keySelector)
    val changedIds = mutableListOf<Id>()
    var reusedAnyReference = false
    val stableItems =
        map { incoming ->
            val id = keySelector(incoming)
            val previousItem = previousByKey[id]
            when {
                previousItem == null -> {
                    changedIds += id
                    incoming
                }
                previousItem == incoming -> {
                    reusedAnyReference = reusedAnyReference || previousItem !== incoming
                    previousItem
                }
                else -> {
                    changedIds += id
                    incoming
                }
            }
        }
    val fullyMatchesPrevious =
        stableItems.size == previous.size &&
            stableItems.indices.all { index -> stableItems[index] === previous[index] }
    return StableEntityMerge(
        items =
            when {
                fullyMatchesPrevious -> previous
                reusedAnyReference -> stableItems
                else -> this
            },
        changedIds = changedIds
    )
}

internal fun ConversationSummary.withResolvedReplyThreadMetadata(
    storedConversation: ConversationSummary? = null,
    fallbackHeader: ConversationSummary? = null,
    parentMessage: Message? = null,
    fallbackThreadId: ThreadId? = null
): ConversationSummary {
    val streamChannelId =
        explicitMessageStreamChannelId()
            ?: storedConversation?.explicitMessageStreamChannelId()
            ?: fallbackHeader?.explicitMessageStreamChannelId()
            ?: fallbackThreadId?.let { ChannelId(it.value) }
    val resolvedParentChannelId =
        parentMessage?.channelId
            ?: storedConversation?.thread?.parentChannelId
            ?: fallbackHeader?.thread?.parentChannelId
    if (parentMessage != null && resolvedParentChannelId != null) {
        val nameLooksLikeIdentity =
            nameIsBlankOrIdentity(
                additionalIdentityIds = setOfNotNull(streamChannelId?.value)
            )
        return copy(
            threadId = threadIdentity(resolvedParentChannelId, parentMessage.id),
            channelId = resolvedParentChannelId,
            thread =
                ThreadConversation(
                    parentChannelId = resolvedParentChannelId,
                    parentMessageId = parentMessage.id,
                    streamChannelId = parentMessage.threadChannelId ?: streamChannelId,
                    parentChannelKind = channelType.normalizedThreadChannelKind()
                ),
            kind = ConversationKind.Thread,
            name = if (nameLooksLikeIdentity) streamChannelId?.value ?: name else name
        )
    }
    val currentThread = thread
    if (kind == ConversationKind.Thread && currentThread != null) {
        return if (currentThread.streamChannelId == null && streamChannelId != null) {
            copy(thread = currentThread.copy(streamChannelId = streamChannelId))
        } else {
            this
        }
    }
    val threadedFallback =
        listOfNotNull(storedConversation, fallbackHeader).firstOrNull { candidate ->
            candidate.kind == ConversationKind.Thread &&
                candidate.thread != null &&
                (streamChannelId == null || candidate.messageStreamChannelId() == streamChannelId)
        }
    val threadedFallbackThread = threadedFallback?.thread
    return if (threadedFallback != null && threadedFallbackThread != null) {
        copy(
            threadId = threadedFallback.threadId,
            channelId = threadedFallback.channelId,
            thread = threadedFallbackThread,
            kind = ConversationKind.Thread,
            name = name
        )
    } else {
        this
    }
}

private fun ConversationSummary.withStoredConversationMetadata(stored: ConversationSummary?): ConversationSummary {
    if (stored == null || kind == ConversationKind.Thread) return this
    if (!isSameConversationAs(stored)) return this
    val storedDisplayName = stored.safeDisplayNameOrNull()
    val identityIds =
        setOfNotNull(
            stored.threadId.value,
            stored.channelId?.value,
            stored.thread?.streamChannelId?.value,
            threadId.value,
            channelId?.value,
            thread?.streamChannelId?.value
        )
    val nameLooksSparse =
        nameIsBlankOrIdentity(additionalIdentityIds = identityIds) ||
            isGenericDisplayName(additionalIdentityIds = identityIds)
    val resolvedKind =
        if (kind == ConversationKind.Channel &&
            (
                stored.kind == ConversationKind.Joint ||
                    stored.kind == ConversationKind.DirectMessage
                )
        ) {
            stored.kind
        } else {
            kind
        }
    val resolvedChannelType = channelType.withStoredChannelTypeFallback(stored.channelType)
    return copy(
        kind = resolvedKind,
        channelId = channelId ?: stored.channelId,
        channelType = resolvedChannelType,
        name = if (nameLooksSparse && storedDisplayName != null) stored.name else name,
        subtitle = subtitle ?: stored.subtitle,
        directMessagePeerType = directMessagePeerType ?: stored.directMessagePeerType,
        presenceMemberId = presenceMemberId ?: stored.presenceMemberId,
        memberCount = memberCount ?: stored.memberCount,
        jointChannelId = jointChannelId ?: stored.jointChannelId,
        jointRole = jointRole ?: stored.jointRole,
        jointPeerServerId = jointPeerServerId ?: stored.jointPeerServerId,
        jointPeerServerName = jointPeerServerName ?: stored.jointPeerServerName,
        jointPeerServerSlug = jointPeerServerSlug ?: stored.jointPeerServerSlug,
        jointPeerStatus = jointPeerStatus ?: stored.jointPeerStatus
    )
}

private fun String?.withStoredChannelTypeFallback(storedChannelType: String?): String? {
    val normalized = normalizedThreadChannelKind()
    val storedNormalized = storedChannelType.normalizedThreadChannelKind()
    return if (
        normalized == KuiklyThreadSurfaceKind.Channel &&
        storedNormalized != null &&
        storedNormalized != KuiklyThreadSurfaceKind.Channel
    ) {
        storedChannelType
    } else {
        this ?: storedChannelType
    }
}

internal fun HomeSnapshot.preserveStableRowsFrom(previous: HomeSnapshot?): HomeSnapshot {
    previous ?: return this
    val stableChannels = channels.preserveStableItemsFrom(previous.channels) { conversation -> conversation.homeRowKey() }
    val stableDirectMessages =
        directMessages.preserveStableItemsFrom(previous.directMessages) { conversation -> conversation.homeRowKey() }
    if (stableChannels === channels && stableDirectMessages === directMessages) {
        return this
    }
    return copy(channels = stableChannels, directMessages = stableDirectMessages)
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

private fun ConversationSummary.homeRowKey(): String =
    channelId?.value ?: threadId.value

private fun List<Message>.dedupeAndSortMessages(): List<Message> =
    distinctBy { it.id }.sortedWith(
        compareBy<Message> { it.timestampLabel.isBlank() }
            .thenBy { it.timestampLabel }
            .thenBy { it.seq ?: Long.MIN_VALUE }
            .thenBy { it.id.value }
    )

private fun List<Message>.mergeRealtimeMessages(incoming: List<Message>): List<Message> {
    val byId = associateBy { it.id }.toMutableMap()
    incoming.forEach { message ->
        val previous = byId[message.id]
        byId[message.id] =
            if (previous == null) {
                message
            } else {
                message.withStableRealtimeFieldsFrom(previous)
            }
    }
    return byId.values.toList().dedupeAndSortMessages()
}

private fun Message.withStableRealtimeFieldsFrom(previous: Message): Message =
    copy(
        channelId = channelId ?: previous.channelId,
        threadChannelId = threadChannelId ?: previous.threadChannelId,
        seq = seq ?: previous.seq,
        author = author.withStableRealtimeFieldsFrom(previous.author),
        timestampLabel = timestampLabel.ifBlank { previous.timestampLabel },
        metadata = if (metadata.isEmpty()) previous.metadata else metadata,
        attachments = if (attachments.isEmpty()) previous.attachments else attachments,
        task = task ?: previous.task,
        replyCount = if (replyCount == 0) previous.replyCount else replyCount,
        unreadReplyCount = if (unreadReplyCount == 0) previous.unreadReplyCount else unreadReplyCount,
        firstUnreadReplyMessageId = firstUnreadReplyMessageId ?: previous.firstUnreadReplyMessageId,
        isSaved = isSaved || previous.isSaved,
        parentMessageId = parentMessageId ?: previous.parentMessageId,
        updatedAtLabel = updatedAtLabel ?: previous.updatedAtLabel,
        reactions = reactions ?: previous.reactions,
        senderMembershipStatus = senderMembershipStatus ?: previous.senderMembershipStatus,
        commentRef = commentRef ?: previous.commentRef
    )

private fun MemberProfile.withStableRealtimeFieldsFrom(previous: MemberProfile): MemberProfile {
    val sparseDisplayName = displayName.isBlank() || displayName == id.value
    val resolvedDisplayName = if (sparseDisplayName) previous.displayName else displayName
    val sparseHandle = handle.isBlank() || handle == id.value
    val resolvedHandle = if (sparseHandle) previous.handle else handle
    return copy(
        displayName = resolvedDisplayName,
        handle = resolvedHandle,
        description = description ?: previous.description,
        roleDescription = roleDescription ?: previous.roleDescription,
        avatarUrl = avatarUrl ?: previous.avatarUrl,
        gravatarHash = gravatarHash ?: previous.gravatarHash,
        avatar = avatar ?: previous.avatar,
        avatarLabel = if (sparseDisplayName) previous.avatarLabel else avatarLabel,
        presence = if (presence == Presence.Offline) previous.presence else presence,
        agentStatus = agentStatus ?: previous.agentStatus,
        joinedAt = joinedAt ?: previous.joinedAt,
        membershipStatus = membershipStatus ?: previous.membershipStatus,
        agentDeletedAt = agentDeletedAt ?: previous.agentDeletedAt,
        createdAgents = if (createdAgents.isEmpty()) previous.createdAgents else createdAgents,
        permissionBadges = if (permissionBadges.isEmpty()) previous.permissionBadges else permissionBadges,
        serverId = serverId ?: previous.serverId,
        serverName = serverName ?: previous.serverName,
        serverSlug = serverSlug ?: previous.serverSlug
    )
}

private fun List<Message>.latestSeq(): Long =
    maxOfOrNull { it.seq ?: 0L } ?: 0L

private fun ConversationSummary.writeThroughCanonicalChannelId(): ChannelId =
    if (kind == ConversationKind.Thread) {
        messageStreamChannelId()
    } else {
        channelId ?: ChannelId(threadId.value)
    }

private inline fun <T> List<T>.logIds(crossinline value: (T) -> String): String =
    take(8).joinToString(prefix = "[", postfix = if (size > 8) ",…]" else "]") { value(it) }

private fun <T> DataResult<T>.localWriteSkipReason(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty:$reason"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }
