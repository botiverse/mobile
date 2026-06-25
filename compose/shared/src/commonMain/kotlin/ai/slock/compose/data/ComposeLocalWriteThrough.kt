package ai.slock.compose.data

import ai.slock.compose.normalizedThreadChannelKind
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.Presence
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.RealtimeRefreshRequired
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.SyncResumeResponse
import ai.slock.compose.model.TaskCreated
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.TaskUpdated
import ai.slock.compose.model.ThreadConversation
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.ThreadUpdated
import ai.slock.compose.model.explicitMessageStreamChannelId
import ai.slock.compose.model.messageStreamChannelId
import ai.slock.compose.model.nameIsBlankOrIdentity
import ai.slock.compose.model.resolveMergedAvatarSlot
import ai.slock.compose.model.safeDisplayNameOrNull
import ai.slock.compose.model.threadIdentity
import ai.slock.compose.model.withTitleFallback

class ComposeLocalWriteThrough(
    private val store: ComposeLocalDataStore,
    private val logger: (String) -> Unit = {}
) {
    suspend fun readHomeSnapshot(serverId: ServerId): HomeSnapshot? =
        store.readHome(serverId).also { snapshot ->
            logger(
                "read_home serverId=${serverId.value} hit=${snapshot != null} channels=${snapshot?.channels?.size ?: 0} directMessages=${snapshot?.directMessages?.size ?: 0}"
            )
        }

    suspend fun readThreadSnapshot(threadId: ThreadId): ThreadSnapshot? =
        store.readThread(threadId).also { snapshot ->
            logger(
                "read_thread threadId=${threadId.value} hit=${snapshot != null} messages=${snapshot?.messages?.size ?: 0} tasks=${snapshot?.tasks?.size ?: 0}"
            )
        }

    suspend fun writeHomeResult(result: DataResult<HomeSnapshot>, source: String): Boolean {
        val snapshot = (result as? DataResult.Content)?.data
        if (snapshot == null) {
            logger("write_home_skip source=$source reason=${result.localWriteSkipReason()}")
            return false
        }
        val previous = store.readHome(snapshot.server.id)
        val updatedSnapshot =
            snapshot
                .withStoredDirectMessageMetadata(previous)
                .preserveStableRowsFrom(previous)
        if (updatedSnapshot == previous) {
            logger("write_home_skip source=$source reason=unchanged serverId=${snapshot.server.id.value}")
            return false
        }
        store.writeHome(updatedSnapshot)
        logger(
            "write_home source=$source serverId=${snapshot.server.id.value} channels=${snapshot.channels.size} directMessages=${snapshot.directMessages.size}"
        )
        return true
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
        val snapshot = (result as? DataResult.Content)?.data
        if (snapshot == null) {
            logger("write_home_unread_skip source=$source reason=${result.localWriteSkipReason()}")
            return
        }
        store.writeUnread(snapshot)
        store.readHome(snapshot.server.id)?.let { home ->
            val updatedHome =
                home.copy(
                    channels = home.channels.map { it.withUnreadCounts(snapshot.unreadCounts) },
                    directMessages =
                        home.directMessages.map { it.withUnreadCounts(snapshot.unreadCounts) },
                    unreadTotal = snapshot.totalUnreadCount
                ).preserveStableRowsFrom(home)
            if (updatedHome != home) {
                store.writeHome(updatedHome)
            }
        }
        logger(
            "write_home_unread source=$source serverId=${snapshot.server.id.value} counts=${snapshot.unreadCounts.size} total=${snapshot.totalUnreadCount}"
        )
    }

    suspend fun clearConversationUnread(channelId: ChannelId, source: String, serverId: ServerId? = null) {
        store.clearChannelUnread(channelId)
        serverId?.let { id ->
            store.readHome(id)?.let { home ->
                val updatedHome = home.clearConversationUnread(channelId)
                if (updatedHome != home) {
                    store.writeHome(updatedHome)
                }
            }
        }
        logger("clear_home_unread source=$source channelId=${channelId.value}")
    }

    suspend fun clearServerUnread(serverId: ServerId, source: String) {
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
        store.readHome(serverId)?.let { home ->
            val updatedHome = home.clearAllUnread()
            if (updatedHome != home) {
                store.writeHome(updatedHome)
            }
        }
        logger("clear_home_unread_all source=$source serverId=${serverId.value}")
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
        if (events.isEmpty()) return false
        val newMessages = mutableListOf<Message>()
        val updatedMessages = mutableListOf<Message>()
        val threadUpdates = mutableListOf<ThreadUpdated>()
        val taskPatches = mutableListOf<ai.slock.compose.model.TaskSummary>()
        var wrote = false
        events.forEach { event ->
            when (event) {
                is MemberPresenceUpdated -> Unit
                is MessageNew -> newMessages += event.message
                is MessageUpdated -> updatedMessages += event.message
                is SyncResumeResponse -> newMessages += event.messages
                is ThreadUpdated -> threadUpdates += event
                is TaskCreated -> taskPatches += event.tasks
                is TaskUpdated -> taskPatches += event.task
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
        latestThreadUpdates(threadUpdates).forEach { event ->
            wrote = writeThreadMetadata(serverId, event, activeConversationId) || wrote
        }
        if (newMessages.isNotEmpty()) {
            wrote = writeRealtimeMessages(serverId, newMessages, incrementUnread = true, activeConversationId) || wrote
        }
        if (updatedMessages.isNotEmpty()) {
            wrote = writeRealtimeMessages(serverId, updatedMessages, incrementUnread = false, activeConversationId) || wrote
        }
        val latestTasks = latestTasks(taskPatches)
        if (latestTasks.isNotEmpty()) {
            wrote = writeRealtimeTasks(latestTasks, activeConversationId) || wrote
        }
        return wrote
    }

    private suspend fun writeThreadSnapshot(
        snapshot: ThreadSnapshot,
        source: String,
        fallbackThreadId: ThreadId? = null,
        fallbackHeader: ConversationSummary? = null,
        fallbackParentMessage: Message? = null
    ): Boolean {
        val snapshotStreamChannelId = snapshot.conversation.messageStreamChannelId()
        val storedConversation =
            store.readThread(snapshot.conversation.threadId)?.conversation
                ?: fallbackThreadId
                    ?.takeIf { it == snapshot.conversation.threadId || it.value == snapshotStreamChannelId.value }
                    ?.let { store.readThread(it)?.conversation }
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
        val conversation =
            snapshot.conversation
                .withTitleFallback(fallbackProof.header)
                .withResolvedReplyThreadMetadata(
                    storedConversation = storedConversation,
                    fallbackHeader = fallbackProof.header,
                    parentMessage = resolvedParentMessage,
                    fallbackThreadId = fallbackProof.aliasThreadId
                )
        val streamChannelId = conversation.messageStreamChannelId()
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
        val existingSnapshot = store.readThread(snapshot.conversation.threadId)
        val patch = snapshot.reduceThreadSnapshotFrom(existingSnapshot)
        if (!patch.changed) {
            logger(
                "write_thread_skip source=$source reason=unchanged threadId=${snapshot.conversation.threadId.value} " +
                    "selectorMessages=${patch.selector.messageIds.size} selectorTasks=${patch.selector.taskIds.size} " +
                    "lastSeq=${patch.selector.lastSeq} alias=$alias"
            )
            return false
        }
        store.writeThread(patch.snapshot)
        logger(
            "thread_patch source=$source threadId=${snapshot.conversation.threadId.value} " +
                "streamChannelId=${streamChannelId.value} timelineChanged=${patch.timelineChanged} " +
                "changedMessages=${patch.changedMessageIds.size} changedMessageIds=${patch.changedMessageIds.logIds { it.value }} " +
                "changedTasks=${patch.changedTaskIds.size} changedTaskIds=${patch.changedTaskIds.logIds { it.value }} " +
                "originChanged=${patch.originChanged} selectorMessages=${patch.selector.messageIds.size} " +
                "selectorTasks=${patch.selector.taskIds.size} lastSeq=${patch.selector.lastSeq} " +
                "rawMessages=$rawMessageCount parent=$hasParent alias=$alias"
        )
        return true
    }

    private suspend fun writeRealtimeMessages(
        serverId: ServerId,
        messages: List<Message>,
        incrementUnread: Boolean,
        activeConversationId: String?
    ): Boolean {
        if (messages.isEmpty()) return false
        val avatarByMemberId = store.readHome(serverId)?.directMessageAvatarWireValuesByMemberId().orEmpty()
        val enrichedMessages =
            messages.map { message ->
                message.withHomeDirectMessageAvatar(avatarByMemberId[message.author.id.value])
            }
        var wroteHome = false
        var wroteThread = false
        enrichedMessages.groupBy { message -> message.channelId ?: ChannelId(message.threadId.value) }
            .forEach { (streamChannelId, streamMessages) ->
                val threadId = ThreadId(streamChannelId.value)
                val existing = store.readThread(threadId)
                val existingMessageIds = existing?.messages.orEmpty().map { it.id }.toSet()
                val unreadCandidateMessages =
                    streamMessages
                        .distinctBy { it.id }
                        .filterNot { it.id in existingMessageIds }
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
                wroteHome =
                    updateHomePreview(
                        serverId = serverId,
                        streamChannelId = streamChannelId,
                        messages = streamMessages,
                        unreadCandidateMessages = unreadCandidateMessages,
                        incrementUnread = incrementUnread,
                        activeConversationId = activeConversationId
                    ) || wroteHome
            }
        return wroteHome || wroteThread
    }

    private suspend fun writeThreadMetadata(
        serverId: ServerId,
        event: ThreadUpdated,
        activeConversationId: String?
    ): Boolean {
        val home = store.readHome(serverId)
        val wroteHome =
            if (home != null) {
                val updatedHome =
                    home.copy(
                        channels = home.channels.map { conversation -> conversation.withThreadMetadata(event) },
                        directMessages =
                            home.directMessages.map { conversation -> conversation.withThreadMetadata(event) }
                    )
                if (updatedHome != home) {
                    store.writeHome(updatedHome)
                    logger(
                        "write_thread_metadata parentMessageId=${event.parentMessageId.value} threadChannelId=${event.threadChannelId.value} replyCount=${event.replyCount}"
                    )
                    true
                } else {
                    false
                }
            } else {
                false
            }
        val threadIds =
            (
                home?.threadIdsForMetadata(event).orEmpty() +
                    listOfNotNull(activeConversationId?.takeIf { it.isNotBlank() }?.let(::ThreadId)) +
                    ThreadId(event.threadChannelId.value)
            ).distinct()
        var wroteThread = false
        threadIds.forEach { threadId ->
            val existing = store.readThread(threadId) ?: return@forEach
            val patched = existing.withThreadMetadata(event)
            val streamChannelId = patched.conversation.messageStreamChannelId()
            wroteThread =
                writeThreadPatch(
                    snapshot = patched,
                    source = "thread_metadata",
                    streamChannelId = streamChannelId,
                    rawMessageCount = patched.messages.size,
                    hasParent = patched.parentMessage != null
                ) || wroteThread
        }
        if (wroteThread && !wroteHome) {
            logger(
                "write_thread_metadata parentMessageId=${event.parentMessageId.value} threadChannelId=${event.threadChannelId.value} replyCount=${event.replyCount}"
            )
        }
        return wroteHome || wroteThread
    }

    private suspend fun writeRealtimeTasks(
        tasks: List<ai.slock.compose.model.TaskSummary>,
        activeConversationId: String?
    ): Boolean {
        if (tasks.isEmpty()) return false
        var wroteThread = false
        tasks.groupBy { task -> resolveRealtimeTaskThreadId(task, activeConversationId) }
            .forEach { (threadId, threadTasks) ->
                if (threadId == null) return@forEach
                val existing = store.readThread(threadId) ?: return@forEach
                val updated = existing.withRealtimeTasks(threadTasks)
                wroteThread =
                    writeThreadPatch(
                        snapshot = updated,
                        source = "realtime_task",
                        streamChannelId = updated.conversation.messageStreamChannelId(),
                        rawMessageCount = updated.messages.size,
                        hasParent = updated.parentMessage != null
                    ) || wroteThread
            }
        return wroteThread
    }

    private suspend fun resolveRealtimeTaskThreadId(
        task: ai.slock.compose.model.TaskSummary,
        activeConversationId: String?
    ): ThreadId? {
        task.threadId?.let { return it }
        task.channelId?.let { return ThreadId(it.value) }
        val activeThreadId = activeConversationId?.takeIf { it.isNotBlank() }?.let(::ThreadId) ?: return null
        val activeSnapshot = store.readThread(activeThreadId) ?: return null
        return if (activeSnapshot.containsRealtimeTaskIdentity(task)) activeThreadId else null
    }

    private fun latestThreadUpdates(events: List<ThreadUpdated>): List<ThreadUpdated> =
        events
            .fold(mutableMapOf<Pair<MessageId, ChannelId>, ThreadUpdated>()) { latest, event ->
                latest[event.parentMessageId to event.threadChannelId] = event
                latest
            }
            .values
            .toList()

    private fun latestTasks(
        tasks: List<ai.slock.compose.model.TaskSummary>
    ): List<ai.slock.compose.model.TaskSummary> =
        tasks
            .fold(mutableMapOf<TaskId, ai.slock.compose.model.TaskSummary>()) { latest, task ->
                latest[task.id] = task
                latest
            }
            .values
            .toList()

    private suspend fun updateHomePreview(
        serverId: ServerId,
        streamChannelId: ChannelId,
        messages: List<Message>,
        unreadCandidateMessages: List<Message>,
        incrementUnread: Boolean,
        activeConversationId: String?
    ): Boolean {
        val home = store.readHome(serverId) ?: return false
        val latest = messages.maxByOrNull { it.seq ?: Long.MIN_VALUE } ?: return false
        var totalUnreadIncrement = 0
        fun ConversationSummary.updateIfMatches(): ConversationSummary {
            if (messageStreamChannelId() != streamChannelId) return this
            val isActiveConversation =
                activeConversationId != null &&
                    (
                        threadId.value == activeConversationId ||
                            channelId?.value == activeConversationId ||
                            messageStreamChannelId().value == activeConversationId
                        )
            val unreadIncrement =
                if (!incrementUnread || isActiveConversation) {
                    0
                } else {
                    unreadCandidateMessages.count { message ->
                        message.id != lastMessageId &&
                        message.author.id != home.currentMember.id
                    }
                }
            totalUnreadIncrement += unreadIncrement
            return copy(
                lastMessageId = latest.id,
                lastMessagePreview = latest.body,
                lastMessageAt = latest.timestampLabel,
                lastMessageSenderName = latest.author.displayName,
                unreadCount = unreadCount + unreadIncrement,
                firstUnreadMessageId =
                    firstUnreadMessageId
                        ?: unreadCandidateMessages
                            .firstOrNull { message ->
                                unreadIncrement > 0 &&
                                    message.id != lastMessageId &&
                                    message.author.id != home.currentMember.id
                            }
                            ?.id,
                unreadCountKnown = unreadCountKnown || unreadIncrement > 0
            )
        }
        val updatedHome =
            home.copy(
                channels = home.channels.map { it.updateIfMatches() },
                directMessages = home.directMessages.map { it.updateIfMatches() },
                unreadTotal = home.unreadTotal + totalUnreadIncrement
            )
        if (updatedHome == home) return false
        store.writeHome(updatedHome)
        return true
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
        val fallbackName = fallbackHeader?.safeDisplayNameOrNull()
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
            name = if (nameLooksLikeIdentity && fallbackName != null) fallbackName else name
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
            name = name.takeIf { it.isNotBlank() } ?: threadedFallback.name
        )
    } else {
        this
    }
}

private fun HomeSnapshot.withStoredDirectMessageMetadata(previous: HomeSnapshot?): HomeSnapshot {
    if (previous == null) return this
    val previousDirectMessages =
        previous.directMessages.associateBy { it.channelId?.value ?: it.threadId.value }
    return copy(
        directMessages =
            directMessages.map { conversation ->
                val previousConversation =
                    previousDirectMessages[conversation.channelId?.value ?: conversation.threadId.value]
                if (previousConversation == null) {
                    conversation
                } else {
                    val nameLooksSparse = conversation.nameIsBlankOrIdentity()
                    conversation.copy(
                        name =
                            if (nameLooksSparse && !previousConversation.nameIsBlankOrIdentity()) {
                                previousConversation.name
                            } else {
                                conversation.name
                            },
                        subtitle = conversation.subtitle ?: previousConversation.subtitle,
                        avatarUrl = conversation.avatarUrl ?: previousConversation.avatarUrl,
                        avatar = conversation.avatar ?: previousConversation.avatar,
                        gravatarUrl = conversation.gravatarUrl ?: previousConversation.gravatarUrl,
                        directMessagePeerType =
                            conversation.directMessagePeerType ?: previousConversation.directMessagePeerType,
                        presence = conversation.presence ?: previousConversation.presence,
                        agentStatus = conversation.agentStatus ?: previousConversation.agentStatus,
                        presenceMemberId =
                            conversation.presenceMemberId ?: previousConversation.presenceMemberId
                    )
                }
            }
    )
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

private fun ConversationSummary.withUnreadCounts(
    unreadCounts: Map<ChannelId, Int>
): ConversationSummary {
    val channelKey = channelId ?: ChannelId(threadId.value)
    val unread = unreadCounts[channelKey] ?: return copy(unreadCount = 0, unreadCountKnown = true)
    return copy(unreadCount = unread, unreadCountKnown = true)
}

private fun HomeSnapshot.clearConversationUnread(channelId: ChannelId): HomeSnapshot {
    var cleared = 0
    fun ConversationSummary.clearIfMatches(): ConversationSummary {
        if (messageStreamChannelId() != channelId && this.channelId != channelId && threadId.value != channelId.value) {
            return this
        }
        cleared += unreadCount
        return copy(
            unreadCount = 0,
            unreadCountKnown = true,
            mentionCount = 0,
            mentionCountKnown = true,
            firstUnreadMessageId = null
        )
    }
    return copy(
        channels = channels.map { it.clearIfMatches() },
        directMessages = directMessages.map { it.clearIfMatches() },
        unreadTotal = (unreadTotal - cleared).coerceAtLeast(0)
    )
}

private fun HomeSnapshot.clearAllUnread(): HomeSnapshot {
    fun ConversationSummary.clearUnread(): ConversationSummary =
        copy(
            unreadCount = 0,
            unreadCountKnown = true,
            mentionCount = 0,
            mentionCountKnown = true,
            firstUnreadMessageId = null
        )
    return copy(
        channels = channels.map { it.clearUnread() },
        directMessages = directMessages.map { it.clearUnread() },
        unreadTotal = 0
    )
}

private fun ConversationSummary.withThreadMetadata(event: ThreadUpdated): ConversationSummary =
    if (lastMessageId == event.parentMessageId || thread?.parentMessageId == event.parentMessageId) {
        copy(
            thread =
                ThreadConversation(
                    parentChannelId = channelId ?: event.threadChannelId,
                    parentMessageId = event.parentMessageId,
                    streamChannelId = event.threadChannelId,
                    parentChannelKind = channelType.normalizedThreadChannelKind()
                )
        )
    } else {
        this
    }

private fun HomeSnapshot.threadIdsForMetadata(event: ThreadUpdated): List<ThreadId> =
    (channels + directMessages)
        .flatMap { conversation -> conversation.threadIdsForMetadata(event) }
        .distinct()

private fun ConversationSummary.threadIdsForMetadata(event: ThreadUpdated): List<ThreadId> {
    val ids = mutableListOf<ThreadId>()
    if (lastMessageId == event.parentMessageId || thread?.parentMessageId == event.parentMessageId) {
        ids += threadId
    }
    val parentChannelId = channelId ?: thread?.parentChannelId
    if (parentChannelId != null) {
        ids += threadIdentity(parentChannelId, event.parentMessageId)
    }
    ids += ThreadId(event.threadChannelId.value)
    return ids.distinct()
}

private fun ThreadSnapshot.withThreadMetadata(event: ThreadUpdated): ThreadSnapshot {
    fun Message.withMetadata(): Message =
        if (id == event.parentMessageId) {
            copy(
                threadChannelId = event.threadChannelId,
                replyCount = event.replyCount,
                unreadReplyCount = event.unreadCount ?: unreadReplyCount,
                firstUnreadReplyMessageId = event.firstUnreadMessageId
            )
        } else {
            this
        }
    return copy(
        conversation = conversation.withThreadMetadata(event),
        parentMessage = parentMessage?.withMetadata(),
        messages = messages.map { message -> message.withMetadata() }
    )
}

private fun List<TaskSummary>.mergeThreadTasks(
    incoming: List<TaskSummary>
): List<TaskSummary> {
    if (incoming.isEmpty()) return this
    val incomingById = incoming.associateBy { it.id }
    val incomingTaskNumbers = incoming.mapNotNull { it.taskNumber }.toSet()
    val incomingMessageIds = incoming.flatMap { task -> task.linkedMessageIds() }.toSet()
    val replacedIds = mutableSetOf<TaskId>()
    val merged =
        mapNotNull { task ->
            incomingById[task.id]?.also { replacedIds += it.id }
                ?: task.takeUnless { existing ->
                    existing.matchesAnyIncomingReference(
                        incomingTaskNumbers = incomingTaskNumbers,
                        incomingMessageIds = incomingMessageIds
                    )
                }
        } + incoming.filterNot { it.id in replacedIds }
    return merged.distinctBy { it.id }
}

private fun ThreadSnapshot.withRealtimeTasks(
    incoming: List<TaskSummary>
): ThreadSnapshot {
    fun Message.withTaskPatch(): Message {
        val updatedTask = incoming.firstOrNull { task -> task.matchesMessageIdentity(this) } ?: return this
        return copy(task = updatedTask)
    }
    return copy(
        tasks = tasks.mergeThreadTasks(incoming),
        parentMessage = parentMessage?.withTaskPatch(),
        messages = messages.map { message -> message.withTaskPatch() }
    )
}

private fun ThreadSnapshot.containsRealtimeTaskIdentity(
    task: TaskSummary
): Boolean {
    return tasks.any { existing -> existing.matchesTaskReference(task) } ||
        parentMessage?.matchesTaskIdentity(task) == true ||
        messages.any { message -> message.matchesTaskIdentity(task) }
}

private fun Message.matchesTaskIdentity(task: TaskSummary): Boolean =
    task.matchesMessageIdentity(this)

private fun TaskSummary.matchesMessageIdentity(message: Message): Boolean =
    message.task?.id == id ||
        message.id.value == id.value ||
        linkedMessageIds().any { messageId -> message.id == messageId } ||
        (taskNumber != null && message.task?.taskNumber == taskNumber)

private fun TaskSummary.matchesTaskReference(incoming: TaskSummary): Boolean =
    id == incoming.id ||
        linkedMessageIds().any { messageId -> messageId in incoming.linkedMessageIds() } ||
        (taskNumber != null && taskNumber == incoming.taskNumber)

private fun TaskSummary.matchesAnyIncomingReference(
    incomingTaskNumbers: Set<Int>,
    incomingMessageIds: Set<MessageId>
): Boolean =
    linkedMessageIds().any { messageId -> messageId in incomingMessageIds } ||
        (taskNumber != null && taskNumber in incomingTaskNumbers)

private fun TaskSummary.linkedMessageIds(): List<MessageId> =
    listOfNotNull(messageId, parentMessageId, MessageId(id.value)).distinct()

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

private fun Message.withHomeDirectMessageAvatar(avatarWireValue: String?): Message {
    val wireValue = avatarWireValue?.takeIf { it.isNotBlank() } ?: return this
    if (author.kind != MemberKind.Agent) return this
    val mergedAvatar =
        resolveMergedAvatarSlot(
            kind = MemberKind.Agent,
            id = author.id.value,
            currentAvatarWireValue = author.avatar?.wireValue ?: author.avatarUrl,
            currentGravatarHash = author.gravatarHash,
            incomingAvatarWireValue = wireValue
        )
    if (mergedAvatar.effectiveAvatarWireValue == (author.avatar?.wireValue ?: author.avatarUrl) &&
        mergedAvatar.gravatarHash == author.gravatarHash
    ) {
        return this
    }
    return copy(
        author =
            author.copy(
                avatarUrl = mergedAvatar.effectiveAvatarWireValue,
                avatar = AvatarReference.fromWireValue(mergedAvatar.effectiveAvatarWireValue),
                gravatarHash = mergedAvatar.gravatarHash
            )
    )
}

private fun HomeSnapshot.directMessageAvatarWireValuesByMemberId(): Map<String, String> =
    directMessages.mapNotNull { conversation ->
        val memberId = conversation.presenceMemberId?.value ?: return@mapNotNull null
        val avatarWireValue =
            conversation.avatar?.wireValue
                ?: conversation.avatarUrl
                ?: return@mapNotNull null
        memberId to avatarWireValue
    }.toMap()

private fun List<Message>.latestSeq(): Long =
    maxOfOrNull { it.seq ?: 0L } ?: 0L

private inline fun <T> List<T>.logIds(crossinline value: (T) -> String): String =
    take(8).joinToString(prefix = "[", postfix = if (size > 8) ",…]" else "]") { value(it) }

private fun <T> DataResult<T>.localWriteSkipReason(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty:$reason"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }
