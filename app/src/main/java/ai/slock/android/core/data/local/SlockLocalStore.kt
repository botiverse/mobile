package ai.slock.android.core.data.local

import ai.slock.android.core.model.Attachment
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageDeliveryState
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.MessageType
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.ThreadConversation
import ai.slock.android.core.model.withTitleFallback

interface SlockLocalStore {
    fun upsertConversation(conversation: ConversationSummary, syncedAtMs: Long)
    fun getConversation(id: ThreadId): ConversationSummary?
    fun getThreadTimeline(threadId: ThreadId): LocalThreadTimeline?
    fun upsertMessage(message: Message, syncedAtMs: Long)
    fun getMessagesForThread(threadId: ThreadId): List<Message>
    fun upsertTaskReference(task: TaskSummary, syncedAtMs: Long)
    fun getTaskReferencesForMessage(messageId: MessageId): List<TaskSummary>
    fun upsertMemberSnapshot(member: MemberProfile, syncedAtMs: Long)
    fun getMemberSnapshot(memberId: MemberId): MemberProfile?
    fun upsertSyncCursor(cursor: LocalSyncCursor)
    fun getSyncCursor(scopeId: String): LocalSyncCursor?
    fun enqueueOutboxMessage(message: LocalOutboxMessage)
    fun getPendingOutboxMessages(): List<LocalOutboxMessage>
    fun updateOutboxStatus(localId: String, status: LocalOutboxStatus, errorCode: String?)
}

data class LocalThreadTimeline(
    val conversation: ConversationSummary,
    val messages: List<Message>,
    val tasks: List<TaskSummary> = emptyList(),
    val cursor: LocalSyncCursor? = null
)

data class LocalSyncCursor(
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

    override fun upsertConversation(conversation: ConversationSummary, syncedAtMs: Long) {
        val storedConversation = getConversation(conversation.threadId)
        val conversationToStore = conversation.withTitleFallback(storedConversation)
        requireValidLocalId(conversationToStore.threadId.value, "conversation.threadId")
        requireValidLocalId(conversationToStore.channelId?.value, "conversation.channelId")
        queries.upsertConversation(
            id = conversationToStore.threadId.value,
            channel_id = conversationToStore.channelId?.value ?: conversationToStore.threadId.value,
            thread_id = conversationToStore.threadId.value,
            kind = conversationToStore.kind.wireValue,
            name = conversationToStore.name,
            parent_channel_id = conversationToStore.thread?.parentChannelId?.value,
            parent_message_id = conversationToStore.thread?.parentMessageId?.value,
            avatar_url = conversationToStore.avatarUrl,
            unread_count = conversationToStore.unreadCount.toLong(),
            updated_at_label = conversationToStore.lastMessageAt,
            last_synced_at_ms = syncedAtMs
        )
    }

    override fun getConversation(id: ThreadId): ConversationSummary? =
        queries.selectConversation(
            id.value,
            id.value,
            id.value,
            id.value,
            id.value
        ).executeAsOneOrNull()?.toConversationSummary()

    override fun getThreadTimeline(threadId: ThreadId): LocalThreadTimeline? {
        val conversation = getConversation(threadId) ?: return null
        val messages = getMessagesForThread(threadId)
        val tasks = messages.flatMap { message -> getTaskReferencesForMessage(message.id) }
            .distinctBy { it.id }
        val cursor = getSyncCursor(threadId.value)
        return LocalThreadTimeline(
            conversation = conversation,
            messages = messages,
            tasks = tasks,
            cursor = cursor
        )
    }

    override fun upsertMessage(message: Message, syncedAtMs: Long) {
        requireValidLocalId(message.threadId.value, "message.threadId")
        requireValidLocalId(message.channelId?.value, "message.channelId")
        database.transaction {
            queries.upsertMessage(
                id = message.id.value,
                thread_id = message.threadId.value,
                channel_id = message.channelId?.value ?: message.threadId.value,
                seq = message.seq,
                author_id = message.author.id.value,
                author_display_name = message.author.displayName,
                author_handle = message.author.handle,
                author_kind = message.author.kind.wireValue,
                timestamp_label = message.timestampLabel,
                body = message.body,
                message_type = message.messageType.wireValue,
                system_event_type = message.systemEventType,
                metadata_json = "{}",
                delivery_state = message.deliveryState.wireValue,
                reply_count = message.replyCount.toLong(),
                parent_message_id = message.parentMessageId?.value,
                updated_at_label = message.updatedAtLabel,
                last_synced_at_ms = syncedAtMs
            )
            queries.deleteAttachmentsForMessage(message.id.value)
            message.attachments.forEachIndexed { index, attachment ->
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
            message.task?.let { upsertTaskReference(it, syncedAtMs) }
            upsertMemberSnapshot(message.author, syncedAtMs)
        }
    }

    override fun getMessagesForThread(threadId: ThreadId): List<Message> =
        queries.selectMessagesForThread(threadId.value)
            .executeAsList()
            .map { row ->
                val attachments =
                    queries.selectAttachmentsForMessage(row.id).executeAsList().map { it.toAttachment() }
                val task = getTaskReferencesForMessage(MessageId(row.id)).firstOrNull()
                row.toMessage(attachments = attachments, task = task)
            }

    override fun upsertTaskReference(task: TaskSummary, syncedAtMs: Long) {
        val current = queries.selectTaskReference(task.id.value).executeAsOneOrNull()?.toTaskSummary()
        val merged = current?.withReferencesFrom(task) ?: task
        queries.upsertTaskReference(
            id = merged.id.value,
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
    }

    override fun getTaskReferencesForMessage(messageId: MessageId): List<TaskSummary> =
        queries.selectTaskReferencesForMessage(messageId.value, messageId.value)
            .executeAsList()
            .map { it.toTaskSummary() }

    override fun upsertMemberSnapshot(member: MemberProfile, syncedAtMs: Long) {
        queries.upsertMemberSnapshot(
            id = member.id.value,
            display_name = member.displayName,
            handle = member.handle,
            kind = member.kind.wireValue,
            avatar_url = member.avatarUrl,
            gravatar_hash = member.gravatarHash,
            avatar_label = member.avatarLabel,
            presence = member.presence.wireValue,
            role_description = member.roleDescription,
            last_synced_at_ms = syncedAtMs
        )
    }

    override fun getMemberSnapshot(memberId: MemberId): MemberProfile? =
        queries.selectMemberSnapshot(memberId.value).executeAsOneOrNull()?.toMemberProfile()

    override fun upsertSyncCursor(cursor: LocalSyncCursor) {
        val current = queries.selectSyncCursor(cursor.scopeId).executeAsOneOrNull()?.toLocalSyncCursor()
        val merged = current?.withUpdate(cursor) ?: cursor
        queries.upsertSyncCursor(
            scope_id = merged.scopeId,
            scope_kind = merged.scopeKind.wireValue,
            latest_seq = merged.latestSeq,
            oldest_loaded_seq = merged.oldestLoadedSeq,
            newest_loaded_seq = merged.newestLoadedSeq,
            has_loaded_oldest = merged.hasLoadedOldest.toLongFlag(),
            has_loaded_newest = merged.hasLoadedNewest.toLongFlag(),
            updated_at_ms = merged.updatedAtMs
        )
    }

    override fun getSyncCursor(scopeId: String): LocalSyncCursor? =
        queries.selectSyncCursor(scopeId).executeAsOneOrNull()?.toLocalSyncCursor()

    override fun enqueueOutboxMessage(message: LocalOutboxMessage) {
        queries.insertOutboxMessage(
            local_id = message.localId,
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

    override fun getPendingOutboxMessages(): List<LocalOutboxMessage> =
        queries.selectPendingOutboxMessages().executeAsList().map { it.toLocalOutboxMessage() }

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
                    parentMessageId = MessageId(parent_message_id)
                )
            } else {
                null
            },
        kind = kind.toConversationKind(),
        name = name,
        avatarUrl = avatar_url,
        unreadCount = unread_count.toInt(),
        lastMessageAt = updated_at_label
    )

private fun Local_message.toMessage(
    attachments: List<Attachment>,
    task: TaskSummary?
): Message =
    Message(
        id = MessageId(id),
        threadId = ThreadId(thread_id),
        channelId = ChannelId(channel_id),
        seq = seq,
        author = MemberProfile(
            id = MemberId(author_id),
            displayName = author_display_name,
            handle = author_handle,
            kind = author_kind.toMemberKind()
        ),
        timestampLabel = timestamp_label,
        body = body,
        messageType = message_type.toMessageType(),
        systemEventType = system_event_type,
        attachments = attachments,
        deliveryState = delivery_state.toMessageDeliveryState(),
        task = task,
        replyCount = reply_count.toInt(),
        parentMessageId = parent_message_id?.let(::MessageId),
        updatedAtLabel = updated_at_label
    )

private fun Local_attachment.toAttachment(): Attachment =
    Attachment(
        id = AttachmentId(id),
        name = name,
        mimeType = mime_type,
        sizeLabel = size_label,
        thumbnailUrl = thumbnail_url
    )

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
        roleDescription = role_description,
        avatarUrl = avatar_url,
        gravatarHash = gravatar_hash,
        avatarLabel = avatar_label ?: display_name.take(1).uppercase(),
        presence = presence?.toPresence() ?: Presence.Offline
    )

private fun Local_sync_cursor.toLocalSyncCursor(): LocalSyncCursor =
    LocalSyncCursor(
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
        assignee = incoming.assignee ?: assignee,
        channelName = incoming.channelName ?: channelName,
        updatedAtLabel = incoming.updatedAtLabel.ifBlank { updatedAtLabel },
        canClaim = incoming.canClaim
    )

private fun LocalSyncCursor.withUpdate(incoming: LocalSyncCursor): LocalSyncCursor =
    incoming.copy(
        latestSeq = incoming.latestSeq ?: latestSeq,
        oldestLoadedSeq = incoming.oldestLoadedSeq ?: oldestLoadedSeq,
        newestLoadedSeq = incoming.newestLoadedSeq ?: newestLoadedSeq
    )

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
