package ai.slock.android.core.data.local

import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.ChannelUnreadSnapshot
import ai.slock.android.core.data.repository.HomeSnapshot
import ai.slock.android.core.data.repository.SendMessageResult
import ai.slock.android.core.data.repository.TaskSnapshot
import ai.slock.android.core.data.repository.ThreadSnapshot
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.MessageNew
import ai.slock.android.core.model.MessageUpdated
import ai.slock.android.core.model.DirectMessageCreated
import ai.slock.android.core.model.ThreadUpdated
import ai.slock.android.core.model.TaskCreated
import ai.slock.android.core.model.TaskUpdated
import ai.slock.android.core.model.TaskDeleted
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.MemberPresenceUpdated
import ai.slock.android.core.model.SyncResumeResponse
import ai.slock.android.core.model.Heartbeat
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.ThreadConversation
import ai.slock.compose.model.messageStreamChannelId
import ai.slock.compose.model.threadIdentity
import ai.slock.compose.model.withTitleFallback
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.thread.ComposerState
import ai.slock.android.feature.thread.ComposerStatus
import ai.slock.android.feature.thread.ThreadContent
import ai.slock.android.feature.thread.ThreadUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ai.slock.compose.model.explicitMessageStreamChannelId

class SlockLocalWriteThrough(
    private val store: SlockLocalStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val logger: (String) -> Unit = {}
) {
    fun readHomeSnapshot(
        serverId: ServerId,
        serverFallback: ServerSummary,
        currentMemberFallback: MemberProfile
    ): HomeSnapshot? {
        val home = store.getHomeConversations(serverId)
        logger(
            "read_home serverId=${serverId.value} hit=${home.isNotEmpty} channels=${home.channels.size} directMessages=${home.directMessages.size}"
        )
        return home.toHomeSnapshot(
            serverFallback = serverFallback,
            currentMemberFallback = currentMemberFallback
        )
    }

    fun readThreadTimeline(threadId: ThreadId, serverId: ServerId? = null): LocalThreadTimeline? =
        store.getThreadTimeline(threadId, serverId).also { timeline ->
            logger(
                "read_thread serverId=${serverId?.value.orEmpty()} threadId=${threadId.value} hit=${timeline != null} messages=${timeline?.messages?.size ?: 0} tasks=${timeline?.tasks?.size ?: 0}"
            )
        }

    fun taskContext(task: TaskSummary, serverId: ServerId? = null): LocalTaskContext =
        store.getTaskContext(task, serverId).also { context ->
            logger(
                "read_task_context serverId=${serverId?.value.orEmpty()} taskId=${task.id.value} parent=${context.parentMessage?.id?.value.orEmpty()} source=${context.sourceConversation?.messageStreamChannelId()?.value.orEmpty()} reply=${context.replyConversation?.messageStreamChannelId()?.value.orEmpty()}"
            )
        }

    fun observeThreadTimeline(threadId: ThreadId, serverId: ServerId? = null): Flow<LocalThreadTimeline?> =
        store.observeThreadTimeline(threadId, serverId)

    fun readTaskSnapshot(serverId: ServerId? = null): TaskSnapshot? {
        deleteLegacySampleTaskReferences(serverId)
        val tasks = store.getTaskReferences(serverId)
        logger("read_tasks serverId=${serverId?.value.orEmpty()} hit=${tasks.isNotEmpty()} count=${tasks.size}")
        return tasks.takeIf { it.isNotEmpty() }?.toTaskSnapshot()
    }

    fun observeTaskSnapshot(serverId: ServerId? = null): Flow<TaskSnapshot?> =
        store.observeTaskReferences(serverId)
            .map { tasks ->
                val cleanedTasks = tasks.withoutLegacySampleTaskReferences(serverId)
                logger("observe_tasks_emit serverId=${serverId?.value.orEmpty()} count=${cleanedTasks.size}")
                cleanedTasks.takeIf { it.isNotEmpty() }?.toTaskSnapshot()
            }

    fun observeHomeSnapshot(
        serverId: ServerId,
        serverFallback: ServerSummary,
        currentMemberFallback: MemberProfile
    ): Flow<HomeSnapshot?> =
        store.observeHomeConversations(serverId)
            .map { home ->
                home.toHomeSnapshot(
                    serverFallback = serverFallback,
                    currentMemberFallback = currentMemberFallback
                )
            }

    fun readThreadUiState(
        threadId: ThreadId,
        fallbackHeader: ConversationSummary? = null,
        parentMessage: Message? = null,
        serverId: ServerId? = null
    ): ThreadUiState? =
        readThreadTimeline(threadId, serverId)?.toThreadUiState(
            fallbackHeader = fallbackHeader,
            parentMessage = parentMessage,
            serverId = serverId
        )

    fun clearConversationUnread(channelId: ChannelId, source: String, serverId: ServerId? = null) {
        store.clearConversationUnread(channelId, clock(), serverId)
        logger("clear_home_unread source=$source serverId=${serverId?.value.orEmpty()} channelId=${channelId.value}")
    }

    fun clearAllHomeUnread(serverId: ServerId, source: String) {
        val home = store.getHomeConversations(serverId)
        if (!home.isNotEmpty) {
            logger("clear_home_unread_all_skip source=$source serverId=${serverId.value} reason=missing_home")
            return
        }
        val syncedAtMs = clock()
        (home.channels + home.directMessages)
            .mapNotNull { it.channelId ?: ChannelId(it.threadId.value) }
            .distinct()
            .forEach { channelId -> store.clearConversationUnread(channelId, syncedAtMs, serverId) }
        logger("clear_home_unread_all source=$source serverId=${serverId.value}")
    }

    fun writeHomeConversations(
        serverId: ServerId,
        conversations: List<ConversationSummary>,
        source: String
    ) {
        if (conversations.isEmpty()) return
        val syncedAtMs = clock()
        conversations.forEach { conversation ->
            store.upsertConversation(conversation, syncedAtMs, serverId)
        }
        logger(
            "write_home_conversations source=$source serverId=${serverId.value} count=${conversations.size}"
        )
    }

    fun writeAgentDirectMessagePresence(
        serverId: ServerId,
        members: List<MemberProfile>,
        source: String
    ): Boolean {
        val agents = members.filter { it.kind == MemberKind.Agent }
        if (agents.isEmpty()) return false
        val home = store.getHomeConversations(serverId)
        if (!home.isNotEmpty) {
            logger("write_home_presence_skip source=$source serverId=${serverId.value} reason=missing_home")
            return false
        }
        val syncedAtMs = clock()
        var changed = false
        home.directMessages
            .map { conversation ->
                if (conversation.presence != null) {
                    conversation
                } else {
                    val agent = agents.firstOrNull { it.matchesDirectMessage(conversation) }
                    if (agent != null) {
                        changed = true
                        conversation.copy(
                            presence = agent.presence,
                            agentStatus = agent.agentStatus,
                            presenceMemberId = conversation.presenceMemberId ?: agent.id
                        )
                    } else {
                        conversation
                    }
                }
            }
            .forEach { conversation -> store.upsertConversation(conversation, syncedAtMs, serverId) }
        logger(
            "write_home_presence source=$source serverId=${serverId.value} changed=$changed agents=${agents.size}"
        )
        return changed
    }

    fun writeUnreadCounts(result: DataResult<ChannelUnreadSnapshot>, source: String) {
        val snapshot = (result as? DataResult.Content)?.data
        if (snapshot == null) {
            logger("write_home_unread_skip source=$source reason=${result.localWriteSkipReason()}")
            return
        }
        val serverId = snapshot.server.id
        val home = store.getHomeConversations(serverId)
        if (!home.isNotEmpty) {
            logger("write_home_unread_skip source=$source reason=missing_home serverId=${serverId.value}")
            return
        }
        val syncedAtMs = clock()
        (home.channels + home.directMessages)
            .map { conversation -> conversation.withUnreadCounts(snapshot.unreadCounts) }
            .forEach { conversation -> store.upsertConversation(conversation, syncedAtMs, serverId) }
        logger(
            "write_home_unread source=$source serverId=${serverId.value} counts=${snapshot.unreadCounts.size} total=${snapshot.totalUnreadCount}"
        )
    }

    fun writeThreadResult(
        result: DataResult<ThreadSnapshot>,
        source: String,
        fallbackThreadId: ThreadId? = null,
        fallbackHeader: ConversationSummary? = null,
        fallbackParentMessage: Message? = null,
        serverId: ServerId? = null
    ) {
        val snapshot = (result as? DataResult.Content)?.data
        if (snapshot == null) {
            logger("write_thread_skip source=$source reason=${result.localWriteSkipReason()} fallbackThreadId=${fallbackThreadId?.value.orEmpty()}")
            return
        }
        writeThreadSnapshot(
            snapshot = snapshot,
            source = source,
            fallbackThreadId = fallbackThreadId,
            fallbackHeader = fallbackHeader,
            fallbackParentMessage = fallbackParentMessage,
            serverId = serverId
        )
    }

    fun writeHomeResult(result: DataResult<HomeSnapshot>, source: String) {
        val snapshot = (result as? DataResult.Content)?.data
        if (snapshot == null) {
            logger("write_home_skip source=$source reason=${result.localWriteSkipReason()}")
            return
        }
        val syncedAtMs = clock()
        val serverId = snapshot.server.id
        val existingHome = store.getHomeConversations(serverId)
        val existingDirectMessages =
            existingHome.directMessages.associateBy { it.channelId?.value ?: it.threadId.value }
        val mergedDirectMessages =
            snapshot.directMessages.map { conversation ->
                val previous =
                    existingDirectMessages[conversation.channelId?.value ?: conversation.threadId.value]
                if (previous != null) {
                    conversation.withStoredDirectMessagePresence(previous)
                } else {
                    conversation
                }
            }
        (snapshot.channels + mergedDirectMessages).forEach { conversation ->
            store.upsertConversation(conversation, syncedAtMs, serverId)
        }
        store.upsertMemberSnapshot(snapshot.currentMember, syncedAtMs, serverId)
        logger(
            "write_home source=$source serverId=${serverId.value} channels=${snapshot.channels.size} directMessages=${snapshot.directMessages.size}"
        )
    }

    fun writeSendResult(result: DataResult<SendMessageResult>, source: String, serverId: ServerId? = null) {
        val message = (result as? DataResult.Content)?.data?.message
        if (message == null) {
            logger("write_send_skip source=$source reason=${result.localWriteSkipReason()}")
            return
        }
        writeMessages(listOf(message), source, serverId)
    }

    private fun ConversationSummary.withStoredDirectMessagePresence(
        previous: ConversationSummary
    ): ConversationSummary =
        copy(
            presence = presence ?: previous.presence,
            agentStatus = agentStatus ?: previous.agentStatus,
            presenceMemberId = presenceMemberId ?: previous.presenceMemberId
        )

    private fun List<TaskSummary>.toTaskSnapshot(): TaskSnapshot =
        TaskSnapshot(
            tasks = this,
            summaryCounts =
                ai.slock.android.core.model.TaskStatus.entries.associateWith { status ->
                    count { task -> task.status == status }
                }
        )

    fun writeRealtimeEvent(serverId: ServerId, event: RealtimeEvent) {
        when (event) {
            is MessageNew -> {
                writeMessages(listOf(event.message), "socket_message_new", serverId)
                updateHomeConversationsForMessages(serverId, listOf(event.message), incrementUnread = true)
            }
            is MessageUpdated -> {
                writeMessages(listOf(event.message), "socket_message_updated", serverId)
                updateHomeConversationsForMessages(serverId, listOf(event.message), incrementUnread = false)
            }
            is SyncResumeResponse -> {
                writeMessages(event.messages, "socket_sync_resume", serverId)
                updateHomeConversationsForMessages(serverId, event.messages, incrementUnread = true)
            }
            is TaskCreated -> writeTasks(event.tasks, "socket_task_created", serverId)
            is TaskUpdated -> writeTasks(listOf(event.task), "socket_task_updated", serverId)
            is TaskDeleted -> deleteTaskReference(event.taskId, "socket_task_deleted", serverId)
            is ThreadUpdated -> updateThreadMetadata(event, "socket_thread_updated", serverId)
            is MemberPresenceUpdated -> updateHomePresence(serverId, event)
            else -> Unit
        }
    }

    fun writeTaskResult(result: DataResult<TaskSnapshot>, source: String, serverId: ServerId? = null) {
        (result as? DataResult.Content)?.data?.tasks?.let { tasks ->
            writeTasks(tasks, source, serverId)
        }
    }

    private fun writeThreadSnapshot(
        snapshot: ThreadSnapshot,
        source: String,
        fallbackThreadId: ThreadId? = null,
        fallbackHeader: ConversationSummary? = null,
        fallbackParentMessage: Message? = null,
        serverId: ServerId? = null
    ) {
        val storedConversation =
            fallbackThreadId?.let(store::getConversation) ?: store.getConversation(snapshot.conversation.threadId)
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
        requireValidSnapshotId(conversation.threadId.value, "conversation.threadId", source)
        requireValidSnapshotId(conversation.channelId?.value, "conversation.channelId", source)
        val syncedAtMs = clock()
        val streamChannelId = conversation.explicitMessageStreamChannelId()
        store.upsertConversation(conversation, syncedAtMs, serverId)
        val persistedMessages = mutableListOf<Message>()
        resolvedParentMessage?.let { parentMessage ->
            if (writeMessage(parentMessage, syncedAtMs, source, parentMessage.channelId ?: streamChannelId, serverId)) {
                persistedMessages += parentMessage
            }
        }
        snapshot.messages.forEach { message ->
            if (writeMessage(message, syncedAtMs, source, message.channelId ?: streamChannelId, serverId)) {
                persistedMessages += message
            }
        }
        snapshot.tasks.forEach { store.upsertTaskReference(it, syncedAtMs, serverId) }
        val timelineSeqs = persistedMessages.mapNotNull(Message::seq)
        store.upsertSyncCursor(
            LocalSyncCursor(
                serverId = serverId,
                scopeId = requireNotNull(streamChannelId) {
                    "Missing local thread stream channel id for ${conversation.threadId.value}"
                }.value,
                scopeKind = LocalSyncScopeKind.Thread,
                latestSeq = snapshot.lastSeq.takeIf { it > 0L },
                oldestLoadedSeq = timelineSeqs.minOrNull(),
                newestLoadedSeq = timelineSeqs.maxOrNull(),
                hasLoadedOldest = !snapshot.canLoadMoreBefore,
                hasLoadedNewest = !snapshot.canLoadMoreAfter,
                updatedAtMs = syncedAtMs
            )
        )
        logger(
            "write_thread source=$source threadId=${conversation.threadId.value} streamChannelId=${streamChannelId.value} messages=${snapshot.messages.size} parent=${resolvedParentMessage != null} tasks=${snapshot.tasks.size} lastSeq=${snapshot.lastSeq}"
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
            return copy(
                threadId = threadIdentity(resolvedParentChannelId, parentMessage.id),
                channelId = resolvedParentChannelId,
                thread =
                    ThreadConversation(
                        parentChannelId = resolvedParentChannelId,
                        parentMessageId = parentMessage.id,
                        streamChannelId = parentMessage.threadChannelId ?: streamChannelId
                    ),
                kind = ConversationKind.Thread
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
                    (
                        streamChannelId == null ||
                            candidate.messageStreamChannelId() == streamChannelId
                        )
            }
        val threadedFallbackThread = threadedFallback?.thread
        return if (threadedFallback != null && threadedFallbackThread != null) {
            copy(
                threadId = threadedFallback.threadId,
                channelId = threadedFallback.channelId ?: threadedFallbackThread.parentChannelId,
                thread =
                    threadedFallbackThread.copy(
                        streamChannelId = threadedFallbackThread.streamChannelId ?: streamChannelId
                    ),
                kind = ConversationKind.Thread
            )
        } else {
            this
        }
    }

    private fun writeMessages(messages: List<Message>, source: String, serverId: ServerId? = null) {
        if (messages.isEmpty()) return
        val syncedAtMs = clock()
        messages.forEach { message ->
            writeMessage(message, syncedAtMs, source, message.channelId, serverId)
        }
        messages.mapNotNull { message -> message.channelId?.let { it to message } }
            .groupBy({ it.first }, { it.second })
            .forEach { (channelId, channelMessages) ->
                val seqs = channelMessages.mapNotNull(Message::seq)
                store.upsertSyncCursor(
                    LocalSyncCursor(
                        serverId = serverId,
                        scopeId = channelId.value,
                        scopeKind = LocalSyncScopeKind.Thread,
                        latestSeq = seqs.maxOrNull(),
                        newestLoadedSeq = seqs.maxOrNull(),
                        updatedAtMs = syncedAtMs
                    )
                )
            }
        logger(
            "write_messages source=$source count=${messages.size} channels=${messages.mapNotNull { it.channelId?.value }.distinct().joinToString()}"
        )
    }

    private fun writeTasks(tasks: List<TaskSummary>, source: String, serverId: ServerId? = null) {
        if (tasks.isEmpty()) return
        val syncedAtMs = clock()
        store.upsertTaskReferences(tasks, syncedAtMs, serverId)
        logger("write_tasks source=$source serverId=${serverId?.value.orEmpty()} count=${tasks.size}")
    }

    private fun deleteTaskReference(
        taskId: ai.slock.android.core.model.TaskId,
        source: String,
        serverId: ServerId? = null
    ) {
        store.deleteTaskReference(taskId, serverId)
        logger("write_tasks source=$source deleted=${taskId.value}")
    }

    private fun deleteLegacySampleTaskReferences(serverId: ServerId? = null) {
        legacySampleTaskReferenceIds.forEach { taskId ->
            store.deleteTaskReference(taskId, serverId)
        }
    }

    private fun List<TaskSummary>.withoutLegacySampleTaskReferences(serverId: ServerId? = null): List<TaskSummary> {
        val legacyIds = legacySampleTaskReferenceIds.mapTo(mutableSetOf()) { it.value }
        val cleaned = filterNot { task -> task.id.value in legacyIds }
        if (cleaned.size != size) {
            deleteLegacySampleTaskReferences(serverId)
        }
        return cleaned
    }

    private fun updateThreadMetadata(event: ThreadUpdated, source: String, serverId: ServerId) {
        store.updateMessageThreadMetadata(
            parentMessageId = event.parentMessageId,
            threadChannelId = event.threadChannelId,
            replyCount = event.replyCount,
            lastReplyAt = event.lastReplyAt,
            syncedAtMs = clock(),
            serverId = serverId
        )
        logger(
            "write_thread_metadata source=$source parentMessageId=${event.parentMessageId.value} threadChannelId=${event.threadChannelId.value} replyCount=${event.replyCount}"
        )
    }

    private fun updateHomeConversationsForMessages(
        serverId: ServerId,
        messages: List<Message>,
        incrementUnread: Boolean
    ) {
        if (messages.isEmpty()) return
        val home = store.getHomeConversations(serverId)
        if (!home.isNotEmpty) return
        val syncedAtMs = clock()
        val updatedConversations =
            (home.channels + home.directMessages).map { conversation ->
                messages.fold(conversation) { current, message ->
                    current.mergeHomeMessage(message, incrementUnread)
                }
            }
        updatedConversations.forEach { conversation ->
            store.upsertConversation(conversation, syncedAtMs, serverId)
        }
        logger(
            "write_home_realtime source=message serverId=${serverId.value} messages=${messages.size} incrementUnread=$incrementUnread"
        )
    }

    private fun updateHomePresence(serverId: ServerId, event: MemberPresenceUpdated) {
        val syncedAtMs = clock()
        store.updateMemberPresence(
            memberId = event.memberId,
            presence = event.presence,
            kind = event.kind,
            syncedAtMs = syncedAtMs,
            serverId = serverId
        )
        val home = store.getHomeConversations(serverId)
        if (!home.isNotEmpty) return
        home.directMessages
            .map { conversation ->
                if (conversation.matchesPresenceMember(event)) {
                    conversation.copy(
                        presence = event.presence,
                        agentStatus = event.agentStatus ?: conversation.agentStatus,
                        presenceMemberId = conversation.presenceMemberId ?: event.memberId
                    )
                } else {
                    conversation
                }
            }
            .forEach { conversation ->
                store.upsertConversation(conversation, syncedAtMs, serverId)
            }
        logger(
            "write_home_realtime source=presence serverId=${serverId.value} memberId=${event.memberId.value}"
        )
    }

    private fun writeMessage(
        message: Message,
        syncedAtMs: Long,
        source: String,
        streamChannelId: ChannelId?,
        serverId: ServerId?
    ): Boolean {
        if (streamChannelId == null) {
            logger(
                "write_message_skip source=$source reason=missing_stream_channel_id messageId=${message.id.value} threadId=${message.threadId.value}"
            )
            return false
        }
        store.upsertMessage(message, syncedAtMs, streamChannelId, serverId)
        return true
    }

    private companion object {
        val legacySampleTaskReferenceIds =
            listOf(
                TaskId("sample-task"),
                TaskId("task-6"),
                TaskId("task-9"),
                TaskId("task-review")
            )
    }
}

private fun LocalHomeConversations.toHomeSnapshot(
    serverFallback: ServerSummary,
    currentMemberFallback: MemberProfile
): HomeSnapshot? {
    if (!isNotEmpty) return null
    val unreadTotal = channels.sumOf { it.unreadCount } + directMessages.sumOf { it.unreadCount }
    return HomeSnapshot(
        server = serverFallback,
        currentMember = currentMemberFallback,
        channels = channels,
        directMessages = directMessages,
        unreadTotal = unreadTotal
    )
}

private fun requireValidSnapshotId(value: String?, fieldName: String, source: String) {
    require(!value.isNullOrBlank() && value != "unknown-channel") {
        "Invalid local write-through id source=$source field=$fieldName value=${value.orEmpty()}"
    }
}

private fun ConversationSummary.mergeHomeMessage(
    message: Message,
    incrementUnread: Boolean
): ConversationSummary {
    val matches =
        threadId.value == message.threadId.value ||
            channelId?.value == message.channelId?.value ||
            channelId?.value == message.threadId.value ||
            threadId.value == message.channelId?.value
    if (!matches) return this
    val isNewUnread = incrementUnread && lastMessageId != message.id
    return copy(
        unreadCount = unreadCount + if (isNewUnread) 1 else 0,
        unreadCountKnown = true,
        firstUnreadMessageId =
            when {
                !isNewUnread -> firstUnreadMessageId
                firstUnreadMessageId != null -> firstUnreadMessageId
                else -> message.id
            },
        lastMessageId = message.id,
        lastMessagePreview = message.body,
        lastMessageAt = message.timestampLabel,
        lastMessageSenderName = message.author.displayName
    )
}

private fun ConversationSummary.withUnreadCounts(
    unreadCounts: Map<ChannelId, Int>
): ConversationSummary {
    val id = channelId ?: ChannelId(threadId.value)
    val count = unreadCounts[id] ?: 0
    return copy(
        unreadCount = count,
        unreadCountKnown = true,
        mentionCount = 0,
        mentionCountKnown = true,
        firstUnreadMessageId = firstUnreadMessageId.takeIf { count > 0 }
    )
}

private fun ConversationSummary.matchesPresenceMember(
    event: MemberPresenceUpdated
): Boolean =
    kind == ConversationKind.DirectMessage &&
        (event.kind == null || event.kind == MemberKind.Agent || event.kind == MemberKind.Human) &&
        (presenceMemberId == event.memberId ||
            channelId?.value == event.memberId.value ||
            threadId.value == event.memberId.value)

private fun MemberProfile.matchesDirectMessage(conversation: ConversationSummary): Boolean =
    conversation.presenceMemberId == id ||
        conversation.channelId?.value == id.value ||
        conversation.threadId.value == id.value ||
        handle.equals(conversation.name, ignoreCase = true) ||
        displayName.equals(conversation.name, ignoreCase = true) ||
        handle.equals(conversation.name.removePrefix("@"), ignoreCase = true)

fun LocalThreadTimeline.toThreadUiState(
    fallbackHeader: ConversationSummary? = null,
    parentMessage: Message? = null,
    serverId: ServerId? = null
): ThreadUiState {
    val header =
        conversation.copy(
            name = conversation.name.takeIf { it.isNotBlank() } ?: fallbackHeader?.name.orEmpty(),
            channelId = conversation.channelId ?: fallbackHeader?.channelId,
            avatarUrl = conversation.avatarUrl ?: fallbackHeader?.avatarUrl
        )
    return ThreadUiState(
        loadState =
            ScreenLoadState.Content(
                ThreadContent(
                    header = header,
                    messages = messages,
                    tasks = tasks,
                    canLoadMoreBefore = cursor?.hasLoadedOldest == false,
                    canLoadMoreAfter = cursor?.hasLoadedNewest == false,
                    parentMessage = parentMessage ?: this@toThreadUiState.parentMessage
                )
            ),
        serverId = serverId,
        composerState = header.toLocalComposerState()
    )
}

private fun ConversationSummary.toLocalComposerState(): ComposerState =
    ComposerState(
        status =
            if (permissions.canSend) {
                ComposerStatus.IdleEmpty
            } else {
                ComposerStatus.NoPermission
            },
        enabled = permissions.canSend,
        noPermissionCode = if (permissions.canSend) null else "cannot_send"
    )

private fun DataResult<*>.localWriteSkipReason(): String =
    when (this) {
        is DataResult.Content -> "content"
        is DataResult.Empty -> "empty:${reason.name}"
        is DataResult.Error -> "error:${error.code}"
        is DataResult.NoPermission -> "no_permission:${blocker.code}"
    }
