package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.SyncResumeResponse
import ai.slock.compose.model.TaskCreated
import ai.slock.compose.model.TaskUpdated
import ai.slock.compose.model.ThreadConversation
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.ThreadUpdated
import ai.slock.compose.model.explicitMessageStreamChannelId
import ai.slock.compose.model.messageStreamChannelId
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

    suspend fun writeHomeResult(result: DataResult<HomeSnapshot>, source: String) {
        val snapshot = (result as? DataResult.Content)?.data
        if (snapshot == null) {
            logger("write_home_skip source=$source reason=${result.localWriteSkipReason()}")
            return
        }
        store.writeHome(snapshot.withStoredDirectMessagePresence(store.readHome(snapshot.server.id)))
        logger(
            "write_home source=$source serverId=${snapshot.server.id.value} channels=${snapshot.channels.size} directMessages=${snapshot.directMessages.size}"
        )
    }

    suspend fun writeThreadResult(
        result: DataResult<ThreadSnapshot>,
        source: String,
        fallbackThreadId: ThreadId? = null,
        fallbackHeader: ConversationSummary? = null,
        fallbackParentMessage: Message? = null
    ) {
        val snapshot = (result as? DataResult.Content)?.data
        if (snapshot == null) {
            logger(
                "write_thread_skip source=$source reason=${result.localWriteSkipReason()} fallbackThreadId=${fallbackThreadId?.value.orEmpty()}"
            )
            return
        }
        writeThreadSnapshot(
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
            store.writeHome(
                home.copy(
                    channels = home.channels.map { it.withUnreadCounts(snapshot.unreadCounts) },
                    directMessages =
                        home.directMessages.map { it.withUnreadCounts(snapshot.unreadCounts) },
                    unreadTotal = snapshot.totalUnreadCount
                )
            )
        }
        logger(
            "write_home_unread source=$source serverId=${snapshot.server.id.value} counts=${snapshot.unreadCounts.size} total=${snapshot.totalUnreadCount}"
        )
    }

    suspend fun clearConversationUnread(channelId: ChannelId, source: String) {
        store.clearChannelUnread(channelId)
        logger("clear_home_unread source=$source channelId=${channelId.value}")
    }

    suspend fun writeRealtimeEvent(serverId: ServerId, event: RealtimeEvent) {
        when (event) {
            is MessageNew -> writeRealtimeMessages(serverId, listOf(event.message), incrementUnread = true)
            is MessageUpdated -> writeRealtimeMessages(serverId, listOf(event.message), incrementUnread = false)
            is SyncResumeResponse -> writeRealtimeMessages(serverId, event.messages, incrementUnread = true)
            is ThreadUpdated -> writeThreadMetadata(serverId, event)
            is TaskCreated -> writeRealtimeTasks(event.tasks)
            is TaskUpdated -> writeRealtimeTasks(listOf(event.task))
            is Heartbeat -> logger("write_realtime_skip type=heartbeat seq=${event.seq}")
            else -> Unit
        }
    }

    private suspend fun writeThreadSnapshot(
        snapshot: ThreadSnapshot,
        source: String,
        fallbackThreadId: ThreadId? = null,
        fallbackHeader: ConversationSummary? = null,
        fallbackParentMessage: Message? = null
    ) {
        val storedConversation =
            fallbackThreadId?.let { store.readThread(it)?.conversation }
                ?: store.readThread(snapshot.conversation.threadId)?.conversation
        val resolvedParentMessage = snapshot.parentMessage ?: fallbackParentMessage
        val conversation =
            snapshot.conversation
                .withTitleFallback(fallbackHeader)
                .withResolvedReplyThreadMetadata(
                    storedConversation = storedConversation,
                    fallbackHeader = fallbackHeader,
                    parentMessage = resolvedParentMessage,
                    fallbackThreadId = fallbackThreadId
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
        store.writeThread(updatedSnapshot)
        logger(
            "write_thread source=$source threadId=${conversation.threadId.value} streamChannelId=${streamChannelId.value} messages=${snapshot.messages.size} parent=${resolvedParentMessage != null} tasks=${snapshot.tasks.size} lastSeq=${updatedSnapshot.lastSeq}"
        )
    }

    private suspend fun writeRealtimeMessages(
        serverId: ServerId,
        messages: List<Message>,
        incrementUnread: Boolean
    ) {
        if (messages.isEmpty()) return
        messages.groupBy { message -> message.channelId ?: ChannelId(message.threadId.value) }
            .forEach { (streamChannelId, streamMessages) ->
                val threadId = ThreadId(streamChannelId.value)
                val existing = store.readThread(threadId)
                if (existing != null) {
                    store.writeThread(
                        existing.copy(
                            messages = (existing.messages + streamMessages).dedupeAndSortMessages(),
                            lastSeq =
                                maxOf(
                                    existing.lastSeq,
                                    streamMessages.latestSeq()
                                )
                        )
                    )
                }
                updateHomePreview(serverId, streamChannelId, streamMessages, incrementUnread)
            }
    }

    private suspend fun writeThreadMetadata(serverId: ServerId, event: ThreadUpdated) {
        val home = store.readHome(serverId) ?: return
        val updatedHome =
            home.copy(
                channels = home.channels.map { conversation -> conversation.withThreadMetadata(event) },
                directMessages =
                    home.directMessages.map { conversation -> conversation.withThreadMetadata(event) }
            )
        store.writeHome(updatedHome)
        logger(
            "write_thread_metadata parentMessageId=${event.parentMessageId.value} threadChannelId=${event.threadChannelId.value} replyCount=${event.replyCount}"
        )
    }

    private suspend fun writeRealtimeTasks(tasks: List<ai.slock.compose.model.TaskSummary>) {
        if (tasks.isEmpty()) return
        tasks.groupBy { it.threadId }.forEach { (threadId, threadTasks) ->
            if (threadId == null) return@forEach
            val existing = store.readThread(threadId) ?: return@forEach
            store.writeThread(
                existing.copy(tasks = (existing.tasks + threadTasks).distinctBy { it.id })
            )
        }
    }

    private suspend fun updateHomePreview(
        serverId: ServerId,
        streamChannelId: ChannelId,
        messages: List<Message>,
        incrementUnread: Boolean
    ) {
        val home = store.readHome(serverId) ?: return
        val latest = messages.maxByOrNull { it.seq ?: Long.MIN_VALUE } ?: return
        fun ConversationSummary.updateIfMatches(): ConversationSummary {
            if (messageStreamChannelId() != streamChannelId) return this
            return copy(
                lastMessageId = latest.id,
                lastMessagePreview = latest.body,
                lastMessageAt = latest.timestampLabel,
                lastMessageSenderName = latest.author.displayName,
                unreadCount = if (incrementUnread) unreadCount + 1 else unreadCount,
                unreadCountKnown = unreadCountKnown || incrementUnread
            )
        }
        store.writeHome(
            home.copy(
                channels = home.channels.map { it.updateIfMatches() },
                directMessages = home.directMessages.map { it.updateIfMatches() },
                unreadTotal = if (incrementUnread) home.unreadTotal + 1 else home.unreadTotal
            )
        )
    }
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
        val fallbackName =
            fallbackHeader?.name?.takeIf {
                it.isNotBlank() &&
                    it != fallbackHeader.threadId.value &&
                    it != fallbackHeader.channelId?.value
            }
        val nameLooksLikeIdentity =
            name.isBlank() ||
                name == threadId.value ||
                name == channelId?.value ||
                name == streamChannelId?.value
        return copy(
            threadId = threadIdentity(resolvedParentChannelId, parentMessage.id),
            channelId = resolvedParentChannelId,
            thread =
                ThreadConversation(
                    parentChannelId = resolvedParentChannelId,
                    parentMessageId = parentMessage.id,
                    streamChannelId = parentMessage.threadChannelId ?: streamChannelId
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

private fun HomeSnapshot.withStoredDirectMessagePresence(previous: HomeSnapshot?): HomeSnapshot {
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
                    conversation.copy(
                        presence = conversation.presence ?: previousConversation.presence,
                        agentStatus = conversation.agentStatus ?: previousConversation.agentStatus,
                        presenceMemberId =
                            conversation.presenceMemberId ?: previousConversation.presenceMemberId
                    )
                }
            }
    )
}

private fun ConversationSummary.withUnreadCounts(
    unreadCounts: Map<ChannelId, Int>
): ConversationSummary {
    val channelKey = channelId ?: ChannelId(threadId.value)
    val unread = unreadCounts[channelKey] ?: return copy(unreadCount = 0, unreadCountKnown = true)
    return copy(unreadCount = unread, unreadCountKnown = true)
}

private fun ConversationSummary.withThreadMetadata(event: ThreadUpdated): ConversationSummary =
    if (lastMessageId == event.parentMessageId || thread?.parentMessageId == event.parentMessageId) {
        copy(
            thread =
                ThreadConversation(
                    parentChannelId = channelId ?: event.threadChannelId,
                    parentMessageId = event.parentMessageId,
                    streamChannelId = event.threadChannelId
                )
        )
    } else {
        this
    }

private fun List<Message>.dedupeAndSortMessages(): List<Message> =
    distinctBy { it.id }.sortedWith(
        compareBy<Message> { it.timestampLabel.isBlank() }
            .thenBy { it.timestampLabel }
            .thenBy { it.seq ?: Long.MIN_VALUE }
            .thenBy { it.id.value }
    )

private fun List<Message>.latestSeq(): Long =
    maxOfOrNull { it.seq ?: 0L } ?: 0L

private fun <T> DataResult<T>.localWriteSkipReason(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty:$reason"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }
