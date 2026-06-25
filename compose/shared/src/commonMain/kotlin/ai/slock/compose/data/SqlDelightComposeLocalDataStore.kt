package ai.slock.compose.data

import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.Attachment
import ai.slock.compose.model.AttachmentId
import ai.slock.compose.model.AvatarReference
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationPermissions
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageDeliveryState
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageReaction
import ai.slock.compose.model.MessageType
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.SessionSnapshot
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.ThreadConversation
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.messageStreamChannelId
import ai.slock.compose.model.parentMessageIdOrNull
import ai.slock.compose.model.threadIdentity
import ai.slock.shared.data.local.AttachmentEntity
import ai.slock.shared.data.local.ChannelEntity
import ai.slock.shared.data.local.MessageEntity
import ai.slock.shared.data.local.MessageReactionEntity
import ai.slock.shared.data.local.SlockDatabaseQueries
import ai.slock.shared.data.local.TaskSummaryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class SqlDelightComposeLocalDataStore(
    private val database: SlockSqlDatabase,
    private val fallback: ComposeLocalDataStore,
    private val currentServerId: suspend () -> ServerId?,
    private val clock: () -> Long = { 0L }
) : ComposeLocalDataStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowStructuredMapKeys = true
    }

    override fun observeSession(): Flow<SessionSnapshot?> =
        fallback.observeSession()

    override suspend fun readSession(): SessionSnapshot? =
        fallback.readSession()

    override suspend fun writeSession(session: SessionSnapshot?) {
        fallback.writeSession(session)
    }

    override fun observeHome(serverId: ServerId): Flow<HomeSnapshot?> =
        fallback.observeHome(serverId)

    override suspend fun readHome(serverId: ServerId): HomeSnapshot? =
        fallback.readHome(serverId)

    override suspend fun writeHome(snapshot: HomeSnapshot) {
        val now = clock()
        val queries = database.queries
        database.transaction {
            (snapshot.channels + snapshot.directMessages).forEach { conversation ->
                queries.upsertConversationChannel(
                    serverId = snapshot.server.id.value,
                    channel = conversation,
                    now = now
                )
            }
        }
        fallback.writeHome(snapshot)
    }

    override fun observeThread(threadId: ThreadId): Flow<ThreadSnapshot?> =
        flow { emit(readThread(threadId)) }

    override suspend fun readThread(threadId: ThreadId): ThreadSnapshot? {
        val queries = database.queries
        val preferredServerId = currentServerId()
        val resolved =
            preferredServerId?.let { serverId ->
                queries.readThreadChannel(serverId, threadId)?.let { channel -> serverId to channel }
            }
                ?: queries.selectChannelServerId(threadId.value).executeAsOneOrNull()
                    ?.let(::ServerId)
                    ?.let { serverId ->
                        queries.readThreadChannel(serverId, threadId)?.let { channel -> serverId to channel }
                    }
                ?: return fallback.readThread(threadId)
        val serverId = resolved.first
        val channel = resolved.second
        val messages =
            queries.selectMessagesForChannel(serverId.value, channel.channelId)
                .executeAsList()
                .map { entity -> entity.toMessage(serverId) }
        val parentMessage =
            channel.parentMessageId
                ?.let { queries.selectMessage(it).executeAsOneOrNull() }
                ?.toMessage(serverId)
        val tasks =
            (messages.mapNotNull { it.task } + messages.flatMap { message ->
                queries.selectTaskReferencesForMessage(serverId.value, message.id.value, message.id.value)
                    .executeAsList()
                    .map { it.toTaskSummary() }
            })
                .distinctBy { it.id }
                .sortedWith(compareBy(nullsLast<Int>()) { it.taskNumber })
        return ThreadSnapshot(
            conversation = channel.toConversationSummary(),
            messages = messages,
            parentMessage = parentMessage,
            tasks = tasks,
            lastSeq = messages.maxOfOrNull { it.seq ?: 0L } ?: 0L
        )
    }

    private fun SlockDatabaseQueries.readThreadChannel(
        serverId: ServerId,
        threadId: ThreadId
    ): ChannelEntity? =
        selectChannel(serverId.value, threadId.value).executeAsOneOrNull()
            ?: threadId.parentMessageIdOrNull()
                ?.let { parentMessageId ->
                    selectThreadForParentMessage(serverId.value, parentMessageId.value)
                        .executeAsOneOrNull()
                }

    override suspend fun writeThread(snapshot: ThreadSnapshot) {
        val serverId = currentServerId() ?: return fallback.writeThread(snapshot)
        val now = clock()
        val queries = database.queries
        database.transaction {
            val channel = snapshot.conversation
            val messageChannelId = channel.messageStreamChannelId()
            queries.upsertConversationChannel(
                serverId = serverId.value,
                channel = channel,
                now = now,
                canonicalChannelId = messageChannelId.value,
                replyCount = snapshot.parentMessage?.replyCount?.toLong() ?: 0L
            )
            val allMessages =
                (listOfNotNull(snapshot.parentMessage) + snapshot.messages)
                    .distinctBy { it.id }
            allMessages.forEach { message ->
                message.author.writeMemberProfile(serverId, now)
                message.task?.assignee?.writeMemberProfile(serverId, now)
                message.task?.creator?.writeMemberProfile(serverId, now)
                val effectiveChannelId = message.channelId ?: messageChannelId
                queries.upsertMessage(
                    id = message.id.value,
                    serverId = serverId.value,
                    channelId = effectiveChannelId.value,
                    seq = message.seq,
                    authorId = message.author.id.value,
                    authorDisplayName = message.author.displayName,
                    authorHandle = message.author.handle,
                    authorKind = message.author.kind.toStoredKind(),
                    authorDescription = message.author.description,
                    timestampLabel = message.timestampLabel,
                    body = message.body,
                    messageType = message.messageType.toStoredType(),
                    systemEventType = message.systemEventType,
                    metadataJson = json.encodeToString(message.metadata),
                    deliveryState = message.deliveryState.toStoredState(),
                    replyCount = message.replyCount.toLong(),
                    parentMessageId = message.parentMessageId?.value,
                    updatedAtLabel = message.updatedAtLabel,
                    lastSyncedAtMs = now
                )
                queries.deleteAttachmentsForMessage(message.id.value)
                message.attachments.forEachIndexed { index, attachment ->
                    queries.insertAttachment(
                        id = attachment.id.value,
                        messageId = message.id.value,
                        name = attachment.name,
                        mimeType = attachment.mimeType,
                        sizeLabel = attachment.sizeLabel,
                        thumbnailUrl = attachment.thumbnailUrl,
                        width = attachment.width?.toLong(),
                        height = attachment.height?.toLong(),
                        sortOrder = index.toLong()
                    )
                }
                queries.deleteReactionsForMessage(message.id.value)
                message.reactions.orEmpty().forEach { reaction ->
                    reaction.writeMessageReaction(message.id.value, now)
                }
                message.task?.writeTaskSummary(serverId, now)
            }
            snapshot.tasks.forEach { task ->
                task.assignee?.writeMemberProfile(serverId, now)
                task.creator?.writeMemberProfile(serverId, now)
                task.writeTaskSummary(serverId, now)
            }
        }
        fallback.writeThread(snapshot)
    }

    override fun observeUnread(serverId: ServerId): Flow<ChannelUnreadSnapshot?> =
        fallback.observeUnread(serverId)

    override suspend fun readUnread(serverId: ServerId): ChannelUnreadSnapshot? =
        fallback.readUnread(serverId)

    override suspend fun writeUnread(snapshot: ChannelUnreadSnapshot) {
        fallback.writeUnread(snapshot)
    }

    override suspend fun clearChannelUnread(channelId: ChannelId) {
        val queries = database.queries
        val serverId = queries.selectChannelServerId(channelId.value).executeAsOneOrNull()
        if (serverId != null) {
            queries.clearChannelUnread(clock(), serverId, channelId.value)
        }
        fallback.clearChannelUnread(channelId)
    }

    private fun MessageEntity.toMessage(serverId: ServerId): Message {
        val attachments =
            database.queries.selectAttachmentsForMessage(id)
                .executeAsList()
                .map { it.toAttachment() }
        val reactions =
            database.queries.selectReactionsForMessage(id)
                .executeAsList()
                .toMessageReactions()
        val task =
            database.queries.selectTaskReferencesForMessage(serverId.value, id, id)
                .executeAsList()
                .firstOrNull()
                ?.toTaskSummary()
        return Message(
            id = MessageId(id),
            threadId = ThreadId(channelId),
            channelId = ChannelId(channelId),
            seq = seq,
            author = toAuthorProfile(),
            timestampLabel = timestampLabel,
            body = body,
            messageType = messageTypeFromStored(messageType),
            systemEventType = systemEventType,
            metadata = decodeMetadata(metadataJson),
            attachments = attachments,
            deliveryState = messageDeliveryStateFromStored(deliveryState),
            task = task,
            replyCount = replyCount.toInt(),
            parentMessageId = parentMessageId?.let(::MessageId),
            updatedAtLabel = updatedAtLabel,
            reactions = reactions
        )
    }

    private fun MessageReaction.writeMessageReaction(messageId: String, now: Long) {
        val cleanEmoji = emoji.takeIf { it.isNotBlank() } ?: return
        if (count <= 0) return
        reactorIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { reactorId ->
                database.queries.upsertMessageReaction(
                    messageId = messageId,
                    reactorKind = MessageReactionReactorKindUser,
                    reactorId = reactorId,
                    emoji = cleanEmoji,
                    createdAtMs = null,
                    lastSyncedAtMs = now
                )
            }
    }

    private fun List<MessageReactionEntity>.toMessageReactions(): List<MessageReaction>? =
        groupBy { it.emoji }
            .mapNotNull { (emoji, rows) ->
                val cleanEmoji = emoji.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val reactorIds =
                    rows.map { it.reactorId }
                        .filter { it.isNotBlank() }
                        .distinct()
                val count = reactorIds.size
                if (count <= 0) return@mapNotNull null
                MessageReaction(
                    emoji = cleanEmoji,
                    count = count,
                    reactorIds = reactorIds
                )
            }
            .takeIf { it.isNotEmpty() }

    private fun MessageEntity.toAuthorProfile(): MemberProfile =
        MemberProfile(
            id = MemberId(authorId),
            displayName = authorDisplayName,
            handle = authorHandle,
            kind = memberKindFromStored(authorKind),
            description = authorDescription,
            presence = Presence.Offline
        )

    private fun ChannelEntity.toConversationSummary(): ConversationSummary {
        val kind = type.toConversationKind()
        val streamChannelId = ChannelId(channelId)
        val parentChannel = parentChannelId?.let(::ChannelId)
        val parentMessage = parentMessageId?.let(::MessageId)
        val thread =
            if (kind == ConversationKind.Thread && parentChannel != null && parentMessage != null) {
                ThreadConversation(
                    parentChannelId = parentChannel,
                    parentMessageId = parentMessage,
                    streamChannelId = streamChannelId
                )
            } else {
                null
            }
        return ConversationSummary(
            threadId = if (thread != null) threadIdentity(thread.parentChannelId, thread.parentMessageId) else ThreadId(channelId),
            channelId = if (thread != null) thread.parentChannelId else ChannelId(channelId),
            channelType = type,
            thread = thread,
            kind = kind,
            name = name,
            subtitle = description,
            avatarUrl = avatarUrl,
            avatar = AvatarReference.fromWireValue(avatarUrl),
            unreadCount = unreadCount.toInt(),
            unreadCountKnown = true,
            mentionCount = mentionCount.toInt(),
            mentionCountKnown = true,
            lastMessageId = lastMessageId?.let(::MessageId),
            firstUnreadMessageId = firstUnreadMessageId?.let(::MessageId),
            lastMessagePreview = lastMessagePreview,
            lastMessageAt = updatedAtLabel,
            lastMessageSenderName = lastMessageSenderName,
            presence = presence?.let { runCatching { Presence.valueOf(it) }.getOrNull() },
            agentStatus = agentStatus?.let { runCatching { AgentStatus.valueOf(it) }.getOrNull() },
            presenceMemberId = presenceMemberId?.let(::MemberId),
            hasDraft = hasDraft != 0L,
            hasDraftKnown = true,
            joined = joined != 0L,
            archived = archived != 0L,
            memberCount = memberCount?.toInt(),
            jointChannelId = jointChannelId,
            jointRole = jointRole,
            jointPeerServerId = jointPeerServerId?.let(::ServerId),
            jointPeerServerName = jointPeerServerName,
            jointPeerServerSlug = jointPeerServerSlug,
            jointPeerStatus = jointPeerStatus,
            permissions = capabilities.toConversationPermissions()
        )
    }

    private fun AttachmentEntity.toAttachment(): Attachment =
        Attachment(
            id = AttachmentId(id),
            name = name,
            mimeType = mimeType,
            sizeLabel = sizeLabel,
            thumbnailUrl = thumbnailUrl,
            width = width?.toInt(),
            height = height?.toInt()
        )

    private fun TaskSummaryEntity.toTaskSummary(): TaskSummary =
        TaskSummary(
            id = TaskId(id),
            title = title,
            status = TaskStatus.fromWireValue(status) ?: TaskStatus.Todo,
            taskNumber = taskNumber?.toInt(),
            messageId = messageId?.let(::MessageId),
            parentMessageId = parentMessageId?.let(::MessageId),
            channelId = channelId?.let(::ChannelId),
            threadId = channelId?.let(::ThreadId),
            isThreadTask = isThreadTask != 0L,
            assignee =
                assigneeId?.let { id ->
                    MemberProfile(
                        id = MemberId(id),
                        displayName = assigneeDisplayName ?: assigneeHandle ?: id,
                        handle = assigneeHandle ?: assigneeDisplayName ?: id,
                        kind = memberKindFromStored(assigneeKind)
                    )
                },
            channelName = channelName,
            updatedAtLabel = updatedAtLabel,
            canClaim = capabilities and 1L != 0L
        )

    private fun MemberProfile.writeMemberProfile(serverId: ServerId, now: Long) {
        database.queries.upsertMemberSnapshot(
            id = id.value,
            serverId = serverId.value,
            displayName = displayName,
            handle = handle,
            kind = kind.toStoredKind(),
            avatarUrl = avatarUrl,
            gravatarHash = gravatarHash,
            avatarLabel = avatarLabel,
            presence = presence.name,
            description = description,
            roleDescription = roleDescription,
            membershipStatus = membershipStatus,
            lastSyncedAtMs = now
        )
    }

    private fun TaskSummary.writeTaskSummary(serverId: ServerId, now: Long) {
        database.queries.upsertTaskReference(
            id = id.value,
            serverId = serverId.value,
            messageId = messageId?.value,
            parentMessageId = parentMessageId?.value,
            channelId = channelId?.value ?: threadId?.value,
            title = title,
            status = status.toWireValue(),
            taskNumber = taskNumber?.toLong(),
            isThreadTask = isThreadTask.toLongFlag(),
            assigneeId = assignee?.id?.value,
            assigneeDisplayName = assignee?.displayName,
            assigneeHandle = assignee?.handle,
            assigneeKind = assignee?.kind?.toStoredKind(),
            channelName = channelName,
            updatedAtLabel = updatedAtLabel,
            capabilities = if (canClaim) 1L else 0L,
            lastSyncedAtMs = now
        )
    }

    private fun SlockDatabaseQueries.upsertConversationChannel(
        serverId: String,
        channel: ConversationSummary,
        now: Long,
        canonicalChannelId: String = channel.messageStreamChannelId().value,
        replyCount: Long = 0L
    ) {
        upsertChannel(
            serverId = serverId,
            channelId = canonicalChannelId,
            type = channel.toChannelType(),
            name = channel.name.ifBlank { canonicalChannelId },
            description = channel.subtitle,
            parentChannelId = channel.thread?.parentChannelId?.value,
            parentMessageId = channel.thread?.parentMessageId?.value,
            avatarUrl = channel.avatar?.wireValue ?: channel.avatarUrl,
            presence = channel.presence?.name,
            agentStatus = channel.agentStatus?.name,
            presenceMemberId = channel.presenceMemberId?.value,
            joined = channel.joined.toLongFlag(),
            archived = channel.archived.toLongFlag(),
            memberCount = channel.memberCount?.toLong(),
            unreadCount = channel.unreadCount.toLong(),
            mentionCount = channel.mentionCount.toLong(),
            lastMessageId = channel.lastMessageId?.value,
            firstUnreadMessageId = channel.firstUnreadMessageId?.value,
            lastMessagePreview = channel.lastMessagePreview,
            lastMessageSenderName = channel.lastMessageSenderName,
            hasDraft = channel.hasDraft.toLongFlag(),
            replyCount = replyCount,
            jointChannelId = channel.jointChannelId,
            jointRole = channel.jointRole,
            jointPeerServerId = channel.jointPeerServerId?.value,
            jointPeerServerName = channel.jointPeerServerName,
            jointPeerServerSlug = channel.jointPeerServerSlug,
            jointPeerStatus = channel.jointPeerStatus,
            capabilities = channel.permissions.toCapabilityMask(),
            updatedAtLabel = channel.lastMessageAt,
            lastSyncedAtMs = now
        )
        channel.conversationAliases(canonicalChannelId).forEach { alias ->
            upsertConversationAlias(
                serverId = serverId,
                aliasId = alias.id,
                aliasKind = alias.kind.name,
                canonicalChannelId = canonicalChannelId,
                lastSyncedAtMs = now
            )
        }
    }

    private data class StoredConversationAlias(
        val id: String,
        val kind: SharedConversationAliasKind
    )

    private fun ConversationSummary.conversationAliases(canonicalChannelId: String): Set<StoredConversationAlias> =
        buildSet {
            add(StoredConversationAlias(canonicalChannelId, SharedConversationAliasKind.Stream))
            channelId?.value?.takeIf { it.isNotBlank() }?.let { add(StoredConversationAlias(it, SharedConversationAliasKind.Channel)) }
            threadId.value.takeIf { it.isNotBlank() }?.let { add(StoredConversationAlias(it, SharedConversationAliasKind.Thread)) }
            thread?.streamChannelId?.value?.takeIf { it.isNotBlank() }?.let { add(StoredConversationAlias(it, SharedConversationAliasKind.Stream)) }
            thread?.parentChannelId?.value?.takeIf { it.isNotBlank() }?.let { add(StoredConversationAlias(it, SharedConversationAliasKind.ParentChannel)) }
            thread?.parentMessageId?.value?.takeIf { it.isNotBlank() }?.let { add(StoredConversationAlias(it, SharedConversationAliasKind.ParentMessage)) }
            presenceMemberId?.value?.takeIf { it.isNotBlank() }?.let { add(StoredConversationAlias(it, SharedConversationAliasKind.PresenceMember)) }
        }

    private fun decodeMetadata(value: String): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(value) }.getOrDefault(emptyMap())

    private fun ConversationSummary.toChannelType(): String =
        channelType?.takeIf { it.isNotBlank() } ?: when (kind) {
            ConversationKind.Thread -> "thread"
            ConversationKind.Joint -> "joint"
            ConversationKind.Channel -> "channel"
            ConversationKind.DirectMessage -> "dm"
            ConversationKind.Inbox,
            ConversationKind.System -> "channel"
        }

    private fun String.toConversationKind(): ConversationKind =
        when (lowercase()) {
            "thread" -> ConversationKind.Thread
            "joint" -> ConversationKind.Joint
            "dm" -> ConversationKind.DirectMessage
            else -> ConversationKind.Channel
        }

    private fun ConversationPermissions.toCapabilityMask(): Long =
        (if (canRead) 1L else 0L) or
            (if (canSend) 2L else 0L) or
            (if (canAttach) 4L else 0L) or
            (if (canCreateTask) 8L else 0L)

    private fun Long.toConversationPermissions(): ConversationPermissions =
        ConversationPermissions(
            canRead = this and 1L != 0L,
            canSend = this and 2L != 0L,
            canAttach = this and 4L != 0L,
            canCreateTask = this and 8L != 0L
        )

    private fun Boolean.toLongFlag(): Long = if (this) 1L else 0L

    private fun MemberKind.toStoredKind(): String =
        when (this) {
            MemberKind.Agent -> "agent"
            MemberKind.Human -> "human"
            MemberKind.System -> "system"
        }

    private fun memberKindFromStored(value: String?): MemberKind =
        when (value?.lowercase()) {
            "agent" -> MemberKind.Agent
            "system" -> MemberKind.System
            else -> MemberKind.Human
        }

    private fun MessageType.toStoredType(): String =
        when (this) {
            MessageType.System -> "system"
            MessageType.Chat -> "chat"
        }

    private fun messageTypeFromStored(value: String): MessageType =
        if (value.equals("system", ignoreCase = true) || value.equals("System", ignoreCase = true)) {
            MessageType.System
        } else {
            MessageType.Chat
        }

    private fun MessageDeliveryState.toStoredState(): String =
        name.lowercase()

    private fun messageDeliveryStateFromStored(value: String): MessageDeliveryState =
        when (value.lowercase()) {
            "sending" -> MessageDeliveryState.Sending
            "failed" -> MessageDeliveryState.Failed
            else -> MessageDeliveryState.Sent
        }

    private fun TaskStatus.toWireValue(): String =
        when (this) {
            TaskStatus.Todo -> "todo"
            TaskStatus.InProgress -> "in_progress"
            TaskStatus.InReview -> "in_review"
            TaskStatus.Done -> "done"
            TaskStatus.Closed -> "closed"
        }
}

private const val MessageReactionReactorKindUser = "user"
