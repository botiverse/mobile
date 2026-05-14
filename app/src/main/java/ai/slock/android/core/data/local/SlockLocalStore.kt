package ai.slock.android.core.data.local

import ai.slock.android.core.model.Attachment
import ai.slock.android.core.model.AgentStatus
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.AvatarReference
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationPermissions
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageDeliveryState
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.MessageType
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.ThreadConversation
import ai.slock.compose.model.messageStreamChannelId
import ai.slock.compose.model.parentChannelIdOrNull
import ai.slock.compose.model.parentMessageIdOrNull
import ai.slock.compose.model.sortedByMessageTime
import ai.slock.compose.model.toGravatarAvatarReference
import ai.slock.compose.model.withTitleFallback
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import ai.slock.compose.model.explicitMessageStreamChannelId

interface SlockLocalStore {
    fun upsertConversation(
        conversation: ConversationSummary,
        syncedAtMs: Long,
        serverId: ServerId? = null
    )
    fun getConversation(id: ThreadId): ConversationSummary?
    fun getHomeConversations(serverId: ServerId): LocalHomeConversations
    fun observeHomeConversations(serverId: ServerId): Flow<LocalHomeConversations>
    fun clearConversationUnread(channelId: ChannelId, syncedAtMs: Long, serverId: ServerId? = null)
    fun getThreadTimeline(threadId: ThreadId, serverId: ServerId? = null): LocalThreadTimeline?
    fun observeThreadTimeline(threadId: ThreadId, serverId: ServerId? = null): Flow<LocalThreadTimeline?>
    fun upsertMessage(
        message: Message,
        syncedAtMs: Long,
        streamChannelId: ChannelId? = null,
        serverId: ServerId? = null
    )
    fun getMessagesForThread(threadId: ThreadId, serverId: ServerId? = null): List<Message>
    fun upsertTaskReference(task: TaskSummary, syncedAtMs: Long, serverId: ServerId? = null)
    fun deleteTaskReference(taskId: TaskId, serverId: ServerId? = null)
    fun getTaskReferences(serverId: ServerId? = null): List<TaskSummary>
    fun observeTaskReferences(serverId: ServerId? = null): Flow<List<TaskSummary>>
    fun getTaskContext(task: TaskSummary, serverId: ServerId? = null): LocalTaskContext
    fun getTaskReferencesForMessage(messageId: MessageId, serverId: ServerId? = null): List<TaskSummary>
    fun upsertMemberSnapshot(member: MemberProfile, syncedAtMs: Long, serverId: ServerId? = null)
    fun getMemberSnapshot(memberId: MemberId, serverId: ServerId? = null): MemberProfile?
    fun updateMemberPresence(
        memberId: MemberId,
        presence: Presence,
        kind: MemberKind?,
        syncedAtMs: Long,
        serverId: ServerId? = null
    )
    fun updateMessageThreadMetadata(
        parentMessageId: MessageId,
        threadChannelId: ChannelId,
        replyCount: Int,
        lastReplyAt: String?,
        syncedAtMs: Long,
        serverId: ServerId? = null
    )
    fun upsertSyncCursor(cursor: LocalSyncCursor)
    fun getSyncCursor(scopeId: String, serverId: ServerId? = null): LocalSyncCursor?
    fun enqueueOutboxMessage(message: LocalOutboxMessage)
    fun getPendingOutboxMessages(serverId: ServerId? = null): List<LocalOutboxMessage>
    fun updateOutboxStatus(localId: String, status: LocalOutboxStatus, errorCode: String?)
}

data class LocalThreadTimeline(
    val conversation: ConversationSummary,
    val messages: List<Message>,
    val parentMessage: Message? = null,
    val tasks: List<TaskSummary> = emptyList(),
    val cursor: LocalSyncCursor? = null
)

data class LocalHomeConversations(
    val channels: List<ConversationSummary>,
    val directMessages: List<ConversationSummary>
) {
    val isNotEmpty: Boolean
        get() = channels.isNotEmpty() || directMessages.isNotEmpty()
}

data class LocalTaskContext(
    val task: TaskSummary,
    val parentMessage: Message? = null,
    val sourceConversation: ConversationSummary? = null,
    val replyConversation: ConversationSummary? = null
)

data class LocalSyncCursor(
    val serverId: ServerId? = null,
    val scopeId: String,
    val scopeKind: LocalSyncScopeKind,
    val latestSeq: Long? = null,
    val oldestLoadedSeq: Long? = null,
    val newestLoadedSeq: Long? = null,
    val hasLoadedOldest: Boolean = false,
    val hasLoadedNewest: Boolean = true,
    val updatedAtMs: Long
)

enum class LocalSyncScopeKind {
    Conversation,
    Thread
}

data class LocalOutboxMessage(
    val localId: String,
    val serverId: ServerId? = null,
    val channelId: ChannelId,
    val threadId: ThreadId,
    val body: String,
    val attachmentIds: List<AttachmentId> = emptyList(),
    val asTask: Boolean = false,
    val status: LocalOutboxStatus = LocalOutboxStatus.Pending,
    val createdAtMs: Long,
    val lastErrorCode: String? = null
)

enum class LocalOutboxStatus {
    Pending,
    Sending,
    Sent,
    Failed
}

class SqlDelightSlockLocalStore(
    private val database: SlockDatabase
) : SlockLocalStore {
    private val queries = database.slockDatabaseQueries
    private val fallbackServerId = ServerId("__local__")

    override fun upsertConversation(
        conversation: ConversationSummary,
        syncedAtMs: Long,
        serverId: ServerId?
    ) {
        val storedConversation = getConversation(conversation.threadId)
        val conversationToStore =
            conversation
                .withTitleFallback(storedConversation)
                .withUnreadFallback(storedConversation)
        val parentChannelId = conversationToStore.thread?.parentChannelId
            ?: conversationToStore.threadId.parentChannelIdOrNull()
        val parentMessageId = conversationToStore.thread?.parentMessageId
            ?: conversationToStore.threadId.parentMessageIdOrNull()
        val storedChannelId = conversationToStore.channelId ?: parentChannelId
        val streamChannelId = conversationToStore.explicitMessageStreamChannelId()
        val storedServerId =
            serverId?.value
                ?: selectStoredConversationServerId(conversationToStore.threadId).orEmpty()
                    .takeIf(String::isNotBlank)
                ?: fallbackServerId.value
        requireValidLocalId(conversationToStore.threadId.value, "conversation.threadId")
        requireValidLocalId(storedChannelId?.value, "conversation.channelId")
        requireValidLocalId(streamChannelId?.value, "conversation.streamChannelId")
        queries.upsertConversation(
            id = conversationToStore.threadId.value,
            channel_id = storedChannelId?.value ?: conversationToStore.threadId.value,
            thread_id = conversationToStore.threadId.value,
            stream_channel_id = streamChannelId?.value,
            server_id = storedServerId,
            kind = conversationToStore.kind.wireValue,
            name = conversationToStore.name,
            subtitle = conversationToStore.subtitle,
            parent_channel_id = parentChannelId?.value,
            parent_message_id = parentMessageId?.value,
            avatar_url = conversationToStore.avatarUrl,
            unread_count = conversationToStore.unreadCount.toLong(),
            mention_count = conversationToStore.mentionCount.toLong(),
            last_message_id = conversationToStore.lastMessageId?.value,
            first_unread_message_id = conversationToStore.firstUnreadMessageId?.value,
            last_message_preview = conversationToStore.lastMessagePreview,
            last_message_sender_name = conversationToStore.lastMessageSenderName,
            presence = conversationToStore.presence?.name,
            agent_status = conversationToStore.agentStatus?.name,
            presence_member_id = conversationToStore.presenceMemberId?.value,
            has_draft = if (conversationToStore.hasDraft) 1L else 0L,
            joined = if (conversationToStore.joined) 1L else 0L,
            archived = if (conversationToStore.archived) 1L else 0L,
            member_count = conversationToStore.memberCount?.toLong(),
            can_read = if (conversationToStore.permissions.canRead) 1L else 0L,
            can_send = if (conversationToStore.permissions.canSend) 1L else 0L,
            can_attach = if (conversationToStore.permissions.canAttach) 1L else 0L,
            can_create_task = if (conversationToStore.permissions.canCreateTask) 1L else 0L,
            updated_at_label = conversationToStore.lastMessageAt,
            last_synced_at_ms = syncedAtMs
        )
    }

    private fun selectStoredConversationServerId(id: ThreadId): String? =
        queries.selectConversationServerId(
            id.value,
            id.value,
            id.value,
            id.value,
            id.value,
            id.value,
            id.value
        ).executeAsOneOrNull()?.server_id

    override fun getConversation(id: ThreadId): ConversationSummary? =
        queries.selectConversation(
            id.value,
            id.value,
            id.value,
            id.value,
            id.value,
            id.value,
            id.value
        ).executeAsOneOrNull()?.toConversationSummary()

    override fun getHomeConversations(serverId: ServerId): LocalHomeConversations {
        val conversations =
            queries.selectHomeConversations(serverId.value)
                .executeAsList()
                .map { it.toConversationSummary() }
                .dedupeHomeConversations()
        return LocalHomeConversations(
            channels = conversations.filter { it.kind == ConversationKind.Channel },
            directMessages = conversations.filter { it.kind == ConversationKind.DirectMessage }
        )
    }

    override fun observeHomeConversations(serverId: ServerId): Flow<LocalHomeConversations> =
        queries
            .selectHomeConversations(serverId.value)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { getHomeConversations(serverId) }
            .onStart { emit(getHomeConversations(serverId)) }

    override fun clearConversationUnread(channelId: ChannelId, syncedAtMs: Long, serverId: ServerId?) {
        val resolvedServerId = serverId ?: fallbackServerId
        queries.clearConversationUnread(
            last_synced_at_ms = syncedAtMs,
            server_id = resolvedServerId.value,
            channel_id = channelId.value,
            thread_id = channelId.value,
            stream_channel_id = channelId.value,
            id = channelId.value
        )
    }

    override fun getThreadTimeline(threadId: ThreadId, serverId: ServerId?): LocalThreadTimeline? {
        val conversation = getConversation(threadId) ?: return null
        val resolvedServerId =
            serverId ?: selectStoredConversationServerId(conversation.threadId)?.let(::ServerId) ?: fallbackServerId
        val streamChannelId = conversation.explicitMessageStreamChannelId() ?: return null
        val messages = getMessagesForThread(ThreadId(streamChannelId.value), resolvedServerId)
        val parentMessage =
            conversation.thread?.parentMessageId?.let { getMessage(it, resolvedServerId) }
        val tasks = messages.flatMap { message -> getTaskReferencesForMessage(message.id, resolvedServerId) }
            .distinctBy { it.id }
        val cursor = getSyncCursor(streamChannelId.value, resolvedServerId)
        return LocalThreadTimeline(
            conversation = conversation,
            messages = messages,
            parentMessage = parentMessage,
            tasks = tasks,
            cursor = cursor
        )
    }

    override fun observeThreadTimeline(threadId: ThreadId, serverId: ServerId?): Flow<LocalThreadTimeline?> =
        flowForThreadTimeline(threadId, serverId)

    private fun flowForThreadTimeline(threadId: ThreadId, serverId: ServerId?): Flow<LocalThreadTimeline?> {
        val resolvedServerId =
            serverId ?: selectStoredConversationServerId(threadId)?.let(::ServerId)
                ?: fallbackServerId
        val key = threadId.value
        return queries
            .selectThreadTimelineInvalidation(
                resolvedServerId.value,
                key,
                key,
                key,
                key,
                resolvedServerId.value,
                key,
                key,
                key,
                key,
                resolvedServerId.value,
                key,
                key,
                resolvedServerId.value,
                key,
                key,
                key,
                resolvedServerId.value,
                key,
                key,
                key,
                resolvedServerId.value
            )
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { getThreadTimeline(threadId, resolvedServerId) }
            .onStart { emit(getThreadTimeline(threadId, resolvedServerId)) }
    }

    override fun upsertMessage(
        message: Message,
        syncedAtMs: Long,
        streamChannelId: ChannelId?,
        serverId: ServerId?
    ) {
        requireValidLocalId(message.threadId.value, "message.threadId")
        requireValidLocalId(streamChannelId?.value, "message.streamChannelId")
        val storedServerId =
            serverId?.value
                ?: selectStoredConversationServerId(message.threadId)
                ?: streamChannelId?.let { selectStoredConversationServerId(ThreadId(it.value)) }
                ?: fallbackServerId.value
        val storedChannelId = requireNotNull(message.channelId ?: streamChannelId) {
            "Missing local message stream channel id for message ${message.id.value}"
        }
        requireValidLocalId(storedChannelId.value, "message.channelId")
        database.transaction {
            val currentMessage = queries.selectMessage(message.id.value).executeAsOneOrNull()
            val currentAuthorSnapshot = getMemberSnapshot(message.author.id, ServerId(storedServerId))
                ?: currentMessage?.author_id?.let { getMemberSnapshot(MemberId(it), ServerId(storedServerId)) }
            val storedAuthor = message.author
                .withStoredFallback(currentMessage)
                .withProfileFallback(currentAuthorSnapshot)
            val storedThreadChannelId = message.threadChannelId?.value ?: currentMessage?.thread_channel_id
            val storedReplyCount =
                if (message.replyCount > 0 || message.threadChannelId != null) {
                    message.replyCount.toLong()
                } else {
                    currentMessage?.reply_count ?: 0L
                }
            val storedMessageType =
                if (message.messageType == MessageType.Chat &&
                    message.systemEventType == null &&
                    currentMessage?.system_event_type != null
                ) {
                    currentMessage.message_type
                } else {
                    message.messageType.wireValue
                }
            queries.upsertMessage(
                id = message.id.value,
                server_id = storedServerId,
                thread_id = message.threadId.value,
                channel_id = storedChannelId.value,
                thread_channel_id = storedThreadChannelId,
                seq = message.seq ?: currentMessage?.seq,
                author_id = storedAuthor.id.value,
                author_display_name = storedAuthor.displayName,
                author_handle = storedAuthor.handle,
                author_kind = storedAuthor.kind.wireValue,
                author_description = storedAuthor.description,
                timestamp_label = message.timestampLabel.ifBlank {
                    currentMessage?.timestamp_label.orEmpty()
                },
                body = message.body.ifBlank { currentMessage?.body.orEmpty() },
                message_type = storedMessageType,
                system_event_type = message.systemEventType ?: currentMessage?.system_event_type,
                metadata_json = message.metadata.toMetadataJson(currentMessage?.metadata_json),
                delivery_state = message.deliveryState.wireValue,
                reply_count = storedReplyCount,
                parent_message_id = message.parentMessageId?.value ?: currentMessage?.parent_message_id,
                updated_at_label = message.updatedAtLabel ?: currentMessage?.updated_at_label,
                last_synced_at_ms = syncedAtMs
            )
            val attachmentsToStore =
                message.attachments.ifEmpty {
                    queries.selectAttachmentsForMessage(message.id.value)
                        .executeAsList()
                        .map { it.toAttachment() }
                }
            queries.deleteAttachmentsForMessage(message.id.value)
            attachmentsToStore.forEachIndexed { index, attachment ->
                queries.insertAttachment(
                    id = attachment.id.value,
                    message_id = message.id.value,
                    name = attachment.name,
                    mime_type = attachment.mimeType,
                    size_label = attachment.sizeLabel,
                    thumbnail_url = attachment.thumbnailUrl,
                    sort_order = index.toLong()
                )
            }
            message.task?.let { upsertTaskReference(it, syncedAtMs, ServerId(storedServerId)) }
            upsertMemberSnapshot(storedAuthor, syncedAtMs, ServerId(storedServerId))
        }
    }

    override fun getMessagesForThread(threadId: ThreadId, serverId: ServerId?): List<Message> {
        val resolvedServerId =
            serverId ?: selectStoredConversationServerId(threadId)?.let(::ServerId) ?: fallbackServerId
        return queries.selectMessagesForThread(resolvedServerId.value, threadId.value)
            .executeAsList()
            .map { row ->
                val attachments =
                    queries.selectAttachmentsForMessage(row.id).executeAsList().map { it.toAttachment() }
                val task = getTaskReferencesForMessage(MessageId(row.id), resolvedServerId).firstOrNull()
                row.toMessage(attachments = attachments, task = task)
                    .withLocalAuthorSnapshot(resolvedServerId)
            }
            .sortedForLocalThreadTimeline()
    }

    private fun getMessage(messageId: MessageId, serverId: ServerId): Message? =
        queries.selectMessage(messageId.value)
            .executeAsOneOrNull()
            ?.takeIf { it.server_id == serverId.value }
            ?.let { row ->
                val attachments =
                    queries.selectAttachmentsForMessage(row.id).executeAsList().map { it.toAttachment() }
                val task = getTaskReferencesForMessage(MessageId(row.id), serverId).firstOrNull()
                row.toMessage(attachments = attachments, task = task)
                    .withLocalAuthorSnapshot(serverId)
            }

    private fun Message.withLocalAuthorSnapshot(serverId: ServerId): Message =
        copy(author = author.withProfileFallback(getMemberSnapshot(author.id, serverId)))

    private fun TaskSummary.withLocalAssigneeSnapshot(serverId: ServerId): TaskSummary =
        assignee?.let { assignee ->
            copy(assignee = assignee.withProfileFallback(getMemberSnapshot(assignee.id, serverId)))
        } ?: this

    override fun upsertTaskReference(task: TaskSummary, syncedAtMs: Long, serverId: ServerId?) {
        val resolvedServerId =
            serverId
                ?: task.channelId?.let { selectStoredConversationServerId(ThreadId(it.value))?.let(::ServerId) }
                ?: task.threadId?.let { selectStoredConversationServerId(it)?.let(::ServerId) }
                ?: fallbackServerId
        val current = queries.selectTaskReference(resolvedServerId.value, task.id.value)
            .executeAsOneOrNull()
            ?.toTaskSummary()
            ?.withLocalAssigneeSnapshot(resolvedServerId)
        val incoming = task.withLocalAssigneeSnapshot(resolvedServerId)
        val merged = current?.withReferencesFrom(incoming)?.withLocalAssigneeSnapshot(resolvedServerId) ?: incoming
        queries.upsertTaskReference(
            id = merged.id.value,
            server_id = resolvedServerId.value,
            message_id = merged.messageId?.value,
            parent_message_id = merged.parentMessageId?.value,
            channel_id = merged.channelId?.value,
            thread_id = merged.threadId?.value,
            title = merged.title,
            status = merged.status.wireValue,
            task_number = merged.taskNumber?.toLong(),
            is_thread_task = merged.isThreadTask.toLongFlag(),
            assignee_id = merged.assignee?.id?.value,
            assignee_display_name = merged.assignee?.displayName,
            assignee_handle = merged.assignee?.handle,
            assignee_kind = merged.assignee?.kind?.wireValue,
            channel_name = merged.channelName,
            updated_at_label = merged.updatedAtLabel,
            can_claim = merged.canClaim.toLongFlag(),
            last_synced_at_ms = syncedAtMs
        )
        merged.assignee?.let { upsertMemberSnapshot(it, syncedAtMs, resolvedServerId) }
    }

    override fun getTaskReferencesForMessage(messageId: MessageId, serverId: ServerId?): List<TaskSummary> {
        val resolvedServerId = serverId ?: fallbackServerId
        return queries.selectTaskReferencesForMessage(resolvedServerId.value, messageId.value, messageId.value)
            .executeAsList()
            .map { it.toTaskSummary().withLocalAssigneeSnapshot(resolvedServerId) }
    }

    override fun getTaskReferences(serverId: ServerId?): List<TaskSummary> {
        val resolvedServerId = serverId ?: fallbackServerId
        return queries.selectAllTaskReferences(resolvedServerId.value)
            .executeAsList()
            .map { it.toTaskSummary().withLocalAssigneeSnapshot(resolvedServerId) }
    }

    override fun observeTaskReferences(serverId: ServerId?): Flow<List<TaskSummary>> {
        val resolvedServerId = serverId ?: fallbackServerId
        return queries.selectAllTaskReferences(resolvedServerId.value)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { getTaskReferences(resolvedServerId) }
            .onStart { emit(getTaskReferences(resolvedServerId)) }
    }

    override fun getTaskContext(task: TaskSummary, serverId: ServerId?): LocalTaskContext {
        val resolvedServerId =
            serverId
                ?: task.channelId?.let { selectStoredConversationServerId(ThreadId(it.value))?.let(::ServerId) }
                ?: task.threadId?.let { selectStoredConversationServerId(it)?.let(::ServerId) }
                ?: fallbackServerId
        val cachedTask =
            queries.selectTaskContext(
                server_id = resolvedServerId.value,
                id = task.id.value,
                message_id = task.messageId?.value ?: task.id.value,
                parent_message_id = task.parentMessageId?.value ?: task.id.value,
                task_number = task.taskNumber?.toLong() ?: Long.MIN_VALUE
            )
                .executeAsOneOrNull()
                ?.toTaskSummary()
                ?.withLocalAssigneeSnapshot(resolvedServerId)
        val incomingTask = task.withLocalAssigneeSnapshot(resolvedServerId)
        val mergedTask = cachedTask?.withReferencesFrom(incomingTask) ?: incomingTask
        val parentMessageId = mergedTask.parentMessageId ?: mergedTask.messageId
        val parentMessage = parentMessageId?.let { getMessage(it, resolvedServerId) }
        val sourceChannelId =
            mergedTask.channelId
                ?: parentMessage?.channelId
                ?: parentMessage?.threadId?.parentChannelIdOrNull()
        val replyThreadId =
            mergedTask.threadId?.takeIf { threadId ->
                sourceChannelId == null || threadId.value != sourceChannelId.value
            }
                ?: parentMessage?.threadChannelId?.let { ThreadId(it.value) }
        return LocalTaskContext(
            task =
                mergedTask.copy(
                    messageId = mergedTask.messageId ?: parentMessage?.task?.messageId ?: parentMessage?.id,
                    parentMessageId = mergedTask.parentMessageId ?: parentMessage?.id,
                    channelId = mergedTask.channelId ?: sourceChannelId,
                    threadId = mergedTask.threadId ?: replyThreadId,
                    isThreadTask =
                        mergedTask.isThreadTask ||
                            replyThreadId != null ||
                            parentMessage?.threadChannelId != null,
                    channelName = mergedTask.channelName ?: sourceChannelId?.value
                ),
            parentMessage = parentMessage,
            sourceConversation = sourceChannelId?.let { getConversation(ThreadId(it.value)) },
            replyConversation = replyThreadId?.let(::getConversation)
        )
    }

    override fun deleteTaskReference(taskId: TaskId, serverId: ServerId?) {
        val resolvedServerId = serverId ?: fallbackServerId
        queries.deleteTaskReference(resolvedServerId.value, taskId.value)
    }

    override fun upsertMemberSnapshot(member: MemberProfile, syncedAtMs: Long, serverId: ServerId?) {
        val resolvedServerId = serverId ?: fallbackServerId
        val merged = member.withProfileFallback(getMemberSnapshot(member.id, resolvedServerId))
        queries.upsertMemberSnapshot(
            id = merged.id.value,
            server_id = resolvedServerId.value,
            display_name = merged.displayName,
            handle = merged.handle,
            kind = merged.kind.wireValue,
            avatar_url = merged.avatarUrl,
            gravatar_hash = merged.gravatarHash,
            avatar_label = merged.avatarLabel,
            presence = merged.presence.wireValue,
            description = merged.description,
            role_description = merged.roleDescription,
            last_synced_at_ms = syncedAtMs
        )
    }

    override fun getMemberSnapshot(memberId: MemberId, serverId: ServerId?): MemberProfile? {
        val resolvedServerId = serverId ?: fallbackServerId
        return queries.selectMemberSnapshot(resolvedServerId.value, memberId.value)
            .executeAsOneOrNull()
            ?.toMemberProfile()
    }

    override fun updateMemberPresence(
        memberId: MemberId,
        presence: Presence,
        kind: MemberKind?,
        syncedAtMs: Long,
        serverId: ServerId?
    ) {
        val resolvedServerId = serverId ?: fallbackServerId
        val current = getMemberSnapshot(memberId, resolvedServerId)
        if (current == null) {
            upsertMemberSnapshot(
                MemberProfile(
                    id = memberId,
                    displayName = memberId.value,
                    handle = memberId.value,
                    kind = kind ?: MemberKind.Human,
                    presence = presence
                ),
                syncedAtMs,
                resolvedServerId
            )
            return
        }
        queries.updateMemberPresence(
            presence = presence.wireValue,
            value = kind?.wireValue,
            last_synced_at_ms = syncedAtMs,
            server_id = resolvedServerId.value,
            id = memberId.value
        )
    }

    override fun updateMessageThreadMetadata(
        parentMessageId: MessageId,
        threadChannelId: ChannelId,
        replyCount: Int,
        lastReplyAt: String?,
        syncedAtMs: Long,
        serverId: ServerId?
    ) {
        val resolvedServerId = serverId ?: fallbackServerId
        queries.updateMessageThreadMetadata(
            thread_channel_id = threadChannelId.value,
            reply_count = replyCount.toLong(),
            value = lastReplyAt,
            last_synced_at_ms = syncedAtMs,
            server_id = resolvedServerId.value,
            id = parentMessageId.value
        )
    }

    override fun upsertSyncCursor(cursor: LocalSyncCursor) {
        val resolvedServerId = cursor.serverId ?: fallbackServerId
        val current = queries.selectSyncCursor(resolvedServerId.value, cursor.scopeId)
            .executeAsOneOrNull()
            ?.toLocalSyncCursor()
        val merged = current?.withUpdate(cursor) ?: cursor
        queries.upsertSyncCursor(
            scope_id = merged.scopeId,
            server_id = resolvedServerId.value,
            scope_kind = merged.scopeKind.wireValue,
            latest_seq = merged.latestSeq,
            oldest_loaded_seq = merged.oldestLoadedSeq,
            newest_loaded_seq = merged.newestLoadedSeq,
            has_loaded_oldest = merged.hasLoadedOldest.toLongFlag(),
            has_loaded_newest = merged.hasLoadedNewest.toLongFlag(),
            updated_at_ms = merged.updatedAtMs
        )
    }

    override fun getSyncCursor(scopeId: String, serverId: ServerId?): LocalSyncCursor? {
        val resolvedServerId = serverId ?: fallbackServerId
        return queries.selectSyncCursor(resolvedServerId.value, scopeId)
            .executeAsOneOrNull()
            ?.toLocalSyncCursor()
    }

    override fun enqueueOutboxMessage(message: LocalOutboxMessage) {
        queries.insertOutboxMessage(
            local_id = message.localId,
            server_id = (message.serverId ?: fallbackServerId).value,
            channel_id = message.channelId.value,
            thread_id = message.threadId.value,
            body = message.body,
            attachment_ids_json = message.attachmentIds.joinToString(prefix = "[", postfix = "]") {
                "\"${it.value}\""
            },
            as_task = message.asTask.toLongFlag(),
            status = message.status.wireValue,
            created_at_ms = message.createdAtMs,
            last_error_code = message.lastErrorCode
        )
    }

    override fun getPendingOutboxMessages(serverId: ServerId?): List<LocalOutboxMessage> {
        val resolvedServerId = serverId ?: fallbackServerId
        return queries.selectPendingOutboxMessages(resolvedServerId.value)
            .executeAsList()
            .map { it.toLocalOutboxMessage() }
    }

    override fun updateOutboxStatus(localId: String, status: LocalOutboxStatus, errorCode: String?) {
        queries.updateOutboxStatus(
            status = status.wireValue,
            last_error_code = errorCode,
            local_id = localId
        )
    }
}

private fun requireValidLocalId(value: String?, fieldName: String) {
    require(!value.isNullOrBlank() && value != "unknown-channel") {
        "Invalid local store id for $fieldName: ${value.orEmpty()}"
    }
}

private fun Local_conversation.toConversationSummary(): ConversationSummary =
    ConversationSummary(
        threadId = ThreadId(thread_id),
        channelId = ChannelId(channel_id),
        thread =
            if (kind.toConversationKind() == ConversationKind.Thread && parent_message_id != null) {
                ThreadConversation(
                    parentChannelId = parent_channel_id?.let(::ChannelId) ?: ChannelId(channel_id),
                    parentMessageId = MessageId(parent_message_id),
                    streamChannelId = stream_channel_id?.let(::ChannelId)
                )
            } else {
                null
            },
        kind = kind.toConversationKind(),
        name = name,
        subtitle = subtitle,
        avatarUrl = avatar_url,
        unreadCount = unread_count.toInt(),
        unreadCountKnown = true,
        mentionCount = mention_count.toInt(),
        mentionCountKnown = true,
        lastMessageId = last_message_id?.let(::MessageId),
        firstUnreadMessageId = first_unread_message_id?.let(::MessageId),
        lastMessagePreview = last_message_preview,
        lastMessageAt = updated_at_label,
        lastMessageSenderName = last_message_sender_name,
        presence = presence.toPresenceOrNull(),
        agentStatus = agent_status.toAgentStatusOrNull(),
        presenceMemberId = presence_member_id?.let(::MemberId),
        hasDraft = has_draft != 0L,
        joined = joined != 0L,
        archived = archived != 0L,
        memberCount = member_count?.toInt(),
        permissions =
            ConversationPermissions(
                canRead = can_read != 0L,
                canSend = can_send != 0L,
                canAttach = can_attach != 0L,
                canCreateTask = can_create_task != 0L
            )
    )

private fun List<ConversationSummary>.dedupeHomeConversations(): List<ConversationSummary> {
    val deduped = LinkedHashMap<String, ConversationSummary>()
    for (conversation in this) {
        val key = conversation.homeConversationKey()
        if (key !in deduped) {
            deduped[key] = conversation
        }
    }
    return deduped.values.toList()
}

private fun ConversationSummary.homeConversationKey(): String =
    when (kind) {
        ConversationKind.Thread -> threadId.value
        else -> channelId?.value ?: threadId.value
    }

private fun ConversationSummary.withUnreadFallback(
    storedConversation: ConversationSummary?
): ConversationSummary {
    if (storedConversation == null) return this
    return copy(
        unreadCount =
            when {
                unreadCountKnown -> unreadCount
                storedConversation.unreadCountKnown -> storedConversation.unreadCount
                else -> unreadCount
            },
        unreadCountKnown = unreadCountKnown || storedConversation.unreadCountKnown,
        mentionCount =
            when {
                mentionCountKnown -> mentionCount
                storedConversation.mentionCountKnown -> storedConversation.mentionCount
                else -> mentionCount
            },
        mentionCountKnown = mentionCountKnown || storedConversation.mentionCountKnown,
        firstUnreadMessageId =
            if (unreadCountKnown && unreadCount <= 0) {
                null
            } else {
                firstUnreadMessageId ?: storedConversation.firstUnreadMessageId
            }
    )
}

private fun String?.toPresenceOrNull(): Presence? =
    enumValues<Presence>().firstOrNull { it.name == this }

private fun String?.toAgentStatusOrNull(): AgentStatus? =
    enumValues<AgentStatus>().firstOrNull { it.name == this }

private fun Local_message.toMessage(
    attachments: List<Attachment>,
    task: TaskSummary?
): Message =
    Message(
        id = MessageId(id),
        threadId = ThreadId(thread_id),
        channelId = ChannelId(channel_id),
        threadChannelId = thread_channel_id?.let(::ChannelId),
        seq = seq,
        author = MemberProfile(
            id = MemberId(author_id),
            displayName = author_display_name,
            handle = author_handle,
            kind = author_kind.toMemberKind(),
            description = author_description
        ),
        timestampLabel = timestamp_label,
        body = body,
        messageType = message_type.toMessageType(),
        systemEventType = system_event_type,
        metadata = metadata_json.toMetadataMap(),
        attachments = attachments,
        deliveryState = delivery_state.toMessageDeliveryState(),
        task = task,
        replyCount = reply_count.toInt(),
        parentMessageId = parent_message_id?.let(::MessageId),
        updatedAtLabel = updated_at_label
    )

private fun List<Message>.sortedForLocalThreadTimeline(): List<Message> =
    sortedByMessageTime()

private fun Local_attachment.toAttachment(): Attachment =
    Attachment(
        id = AttachmentId(id),
        name = name,
        mimeType = mime_type,
        sizeLabel = size_label,
        thumbnailUrl = thumbnail_url
    )

private val metadataGson = Gson()

private val metadataMapType = object : TypeToken<Map<String, String>>() {}.type

private fun Map<String, String>.toMetadataJson(storedJson: String?): String =
    if (isNotEmpty()) {
        metadataGson.toJson(this)
    } else {
        storedJson?.takeIf { it.isNotBlank() } ?: "{}"
    }

private fun String.toMetadataMap(): Map<String, String> =
    try {
        metadataGson.fromJson<Map<String, String>>(this, metadataMapType) ?: emptyMap()
    } catch (_: JsonSyntaxException) {
        emptyMap()
    }

private fun MemberProfile.withStoredFallback(currentMessage: Local_message?): MemberProfile {
    if (currentMessage == null) return this
    val incomingUnknown = id.value == "unknown-author"
    val currentId = MemberId(currentMessage.author_id)
    val resolvedId = if (incomingUnknown) currentId else id
    val resolvedDisplayName =
        displayName.takeUnless { it.isBlank() || it == id.value || incomingUnknown }
            ?: currentMessage.author_display_name
    val resolvedHandle =
        handle.takeUnless { it.isBlank() || it == id.value || incomingUnknown }
            ?: currentMessage.author_handle
    return copy(
        id = resolvedId,
        displayName = resolvedDisplayName,
        handle = resolvedHandle,
        kind = if (incomingUnknown) currentMessage.author_kind.toMemberKind() else kind,
        description = description ?: currentMessage.author_description
    )
}

private fun MemberProfile.withProfileFallback(fallback: MemberProfile?): MemberProfile {
    if (fallback == null) return withResolvedAvatar()
    val incomingUnknown = id.isUnknownMemberId()
    val fallbackAvatarUrl = fallback.avatarUrl?.takeIf { it.isNotBlank() }
    val fallbackGravatarHash = fallback.gravatarHash?.takeIf { it.isNotBlank() }
    val mergedAvatarUrl = avatarUrl?.takeIf { it.isNotBlank() } ?: fallbackAvatarUrl
    val mergedGravatarHash = gravatarHash?.takeIf { it.isNotBlank() } ?: fallbackGravatarHash
    val mergedAvatar =
        avatar
            ?: fallback.avatar
            ?: AvatarReference.fromWireValue(mergedAvatarUrl)
            ?: mergedGravatarHash?.toGravatarAvatarReference()
    val incomingHasIdentityDetail =
        avatar != null ||
            !avatarUrl.isNullOrBlank() ||
            !gravatarHash.isNullOrBlank() ||
            displayName.isMeaningfulProfileText(id, incomingUnknown)
    val resolvedDisplayName =
        displayName.takeIf { it.isMeaningfulProfileText(id, incomingUnknown) }
            ?: fallback.displayName
    val resolvedHandle =
        handle.takeIf { it.isMeaningfulProfileText(id, incomingUnknown) }
            ?: fallback.handle
    val resolvedAvatarLabel =
        avatarLabel.takeIf {
            incomingHasIdentityDetail && it.isMeaningfulAvatarLabel(id, displayName, incomingUnknown)
        }
            ?: fallback.avatarLabel
    return copy(
        id = if (incomingUnknown) fallback.id else id,
        displayName = resolvedDisplayName,
        handle = resolvedHandle,
        kind = if (incomingUnknown) fallback.kind else kind,
        description = description ?: fallback.description,
        roleDescription = roleDescription ?: fallback.roleDescription,
        avatarUrl = mergedAvatarUrl,
        gravatarHash = mergedGravatarHash,
        avatar = mergedAvatar,
        avatarLabel = resolvedAvatarLabel,
        presence = if (presence == Presence.Offline && fallback.presence != Presence.Offline) fallback.presence else presence,
        agentStatus = agentStatus ?: fallback.agentStatus,
        permissionBadges = permissionBadges.ifEmpty { fallback.permissionBadges }
    )
}

private fun MemberProfile.withResolvedAvatar(): MemberProfile {
    val resolvedAvatar =
        avatar
            ?: AvatarReference.fromWireValue(avatarUrl)
            ?: gravatarHash?.takeIf { it.isNotBlank() }?.toGravatarAvatarReference()
    return if (resolvedAvatar == avatar) this else copy(avatar = resolvedAvatar)
}

private fun MemberId.isUnknownMemberId(): Boolean =
    value == "unknown-author" || value == "unknown-member" || value == "unknown-assignee"

private fun String.isMeaningfulProfileText(memberId: MemberId, incomingUnknown: Boolean): Boolean =
    isNotBlank() &&
        !incomingUnknown &&
        this != memberId.value &&
        !equals("unknown", ignoreCase = true) &&
        !startsWith("unknown-", ignoreCase = true)

private fun String.isMeaningfulAvatarLabel(
    memberId: MemberId,
    displayName: String,
    incomingUnknown: Boolean
): Boolean =
    isNotBlank() &&
        !incomingUnknown &&
        this != memberId.value &&
        this != displayName &&
        this != displayName.take(1).uppercase() &&
        !equals("U", ignoreCase = true)

private fun Local_task_reference.toTaskSummary(): TaskSummary =
    TaskSummary(
        id = TaskId(id),
        title = title,
        status = status.toTaskStatus(),
        taskNumber = task_number?.toInt(),
        messageId = message_id?.let(::MessageId),
        parentMessageId = parent_message_id?.let(::MessageId),
        channelId = channel_id?.let(::ChannelId),
        threadId = thread_id?.let(::ThreadId),
        isThreadTask = is_thread_task.toBooleanFlag(),
        assignee =
            assignee_id?.let { id ->
                MemberProfile(
                    id = MemberId(id),
                    displayName = assignee_display_name ?: id,
                    handle = assignee_handle ?: assignee_display_name ?: id,
                    kind = assignee_kind?.toMemberKind() ?: MemberKind.Human
                )
            },
        channelName = channel_name,
        updatedAtLabel = updated_at_label,
        canClaim = can_claim.toBooleanFlag()
    )

private fun Local_member_snapshot.toMemberProfile(): MemberProfile =
    MemberProfile(
        id = MemberId(id),
        displayName = display_name,
        handle = handle,
        kind = kind.toMemberKind(),
        description = description,
        roleDescription = role_description,
        avatarUrl = avatar_url,
        gravatarHash = gravatar_hash,
        avatar =
            AvatarReference.fromWireValue(avatar_url)
                ?: gravatar_hash?.takeIf { it.isNotBlank() }?.toGravatarAvatarReference(),
        avatarLabel = avatar_label ?: display_name.take(1).uppercase(),
        presence = presence?.toPresence() ?: Presence.Offline
    )

private fun Local_sync_cursor.toLocalSyncCursor(): LocalSyncCursor =
    LocalSyncCursor(
        serverId = ServerId(server_id),
        scopeId = scope_id,
        scopeKind = scope_kind.toLocalSyncScopeKind(),
        latestSeq = latest_seq,
        oldestLoadedSeq = oldest_loaded_seq,
        newestLoadedSeq = newest_loaded_seq,
        hasLoadedOldest = has_loaded_oldest.toBooleanFlag(),
        hasLoadedNewest = has_loaded_newest.toBooleanFlag(),
        updatedAtMs = updated_at_ms
    )

private fun Local_outbox_message.toLocalOutboxMessage(): LocalOutboxMessage =
    LocalOutboxMessage(
        localId = local_id,
        serverId = ServerId(server_id),
        channelId = ChannelId(channel_id),
        threadId = ThreadId(thread_id),
        body = body,
        attachmentIds = attachment_ids_json.toAttachmentIds(),
        asTask = as_task.toBooleanFlag(),
        status = status.toLocalOutboxStatus(),
        createdAtMs = created_at_ms,
        lastErrorCode = last_error_code
    )

private fun TaskSummary.withReferencesFrom(incoming: TaskSummary): TaskSummary =
    incoming.copy(
        title = incoming.title.ifBlank { title },
        taskNumber = incoming.taskNumber ?: taskNumber,
        messageId = incoming.messageId ?: messageId,
        parentMessageId = incoming.parentMessageId ?: parentMessageId,
        channelId = incoming.channelId ?: channelId,
        threadId = incoming.threadId ?: threadId,
        isThreadTask = incoming.isThreadTask || isThreadTask,
        assignee = incoming.assignee?.withProfileFallback(assignee) ?: assignee,
        channelName = incoming.channelName ?: channelName,
        updatedAtLabel = incoming.updatedAtLabel.ifBlank { updatedAtLabel },
        canClaim = incoming.canClaim
    )

private fun LocalSyncCursor.withUpdate(incoming: LocalSyncCursor): LocalSyncCursor =
    incoming.copy(
        latestSeq = maxSeq(latestSeq, incoming.latestSeq),
        oldestLoadedSeq = minSeq(oldestLoadedSeq, incoming.oldestLoadedSeq),
        newestLoadedSeq = maxSeq(newestLoadedSeq, incoming.newestLoadedSeq),
        hasLoadedOldest = hasLoadedOldest || incoming.hasLoadedOldest,
        hasLoadedNewest = hasLoadedNewest || incoming.hasLoadedNewest
    )

private fun minSeq(current: Long?, incoming: Long?): Long? =
    when {
        current == null -> incoming
        incoming == null -> current
        else -> minOf(current, incoming)
    }

private fun maxSeq(current: Long?, incoming: Long?): Long? =
    when {
        current == null -> incoming
        incoming == null -> current
        else -> maxOf(current, incoming)
    }

private val ConversationKind.wireValue: String get() = name
private fun String.toConversationKind(): ConversationKind =
    enumValueOfOrDefault(this, ConversationKind.Channel)

private val MemberKind.wireValue: String get() = name
private fun String.toMemberKind(): MemberKind = enumValueOfOrDefault(this, MemberKind.Human)

private val Presence.wireValue: String get() = name
private fun String.toPresence(): Presence = enumValueOfOrDefault(this, Presence.Offline)

private val MessageType.wireValue: String get() = name
private fun String.toMessageType(): MessageType = enumValueOfOrDefault(this, MessageType.Chat)

private val MessageDeliveryState.wireValue: String get() = name
private fun String.toMessageDeliveryState(): MessageDeliveryState =
    enumValueOfOrDefault(this, MessageDeliveryState.Sent)

private val TaskStatus.wireValue: String get() = name
private fun String.toTaskStatus(): TaskStatus = enumValueOfOrDefault(this, TaskStatus.Todo)

private val LocalSyncScopeKind.wireValue: String get() = name
private fun String.toLocalSyncScopeKind(): LocalSyncScopeKind =
    enumValueOfOrDefault(this, LocalSyncScopeKind.Thread)

private val LocalOutboxStatus.wireValue: String
    get() = name.lowercase()

private fun String.toLocalOutboxStatus(): LocalOutboxStatus =
    when (lowercase()) {
        "sending" -> LocalOutboxStatus.Sending
        "sent" -> LocalOutboxStatus.Sent
        "failed" -> LocalOutboxStatus.Failed
        else -> LocalOutboxStatus.Pending
    }

private inline fun <reified T : Enum<T>> enumValueOfOrDefault(value: String, default: T): T =
    enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: default

private fun Boolean.toLongFlag(): Long = if (this) 1L else 0L

private fun Long.toBooleanFlag(): Boolean = this != 0L

private fun String.toAttachmentIds(): List<AttachmentId> =
    trim()
        .removePrefix("[")
        .removeSuffix("]")
        .split(',')
        .mapNotNull { raw ->
            raw.trim().removeSurrounding("\"").takeIf { it.isNotBlank() }?.let(::AttachmentId)
        }
