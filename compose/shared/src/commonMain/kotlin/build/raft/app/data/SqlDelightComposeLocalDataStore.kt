package build.raft.app.data

import build.raft.app.model.AgentStatus
import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageDeliveryState
import build.raft.app.model.MessageId
import build.raft.app.model.MessageReaction
import build.raft.app.model.MessageType
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.SessionSnapshot
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.model.messageStreamChannelId
import build.raft.app.model.parentMessageIdOrNull
import build.raft.app.model.threadIdentity
import build.raft.app.shared.data.local.AttachmentEntity
import build.raft.app.shared.data.local.ChannelEntity
import build.raft.app.shared.data.local.MessageEntity
import build.raft.app.shared.data.local.MessageReactionEntity
import build.raft.app.shared.data.local.SlockDatabaseQueries
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
        flow { emit(null) }

    override suspend fun readHome(serverId: ServerId): HomeSnapshot? =
        null

    override suspend fun writeHome(snapshot: HomeSnapshot) {
        // Durable HomeSnapshot blob storage has been removed. This method is
        // retained only for temporary source compatibility with older tests.
    }

    override fun observeThread(threadId: ThreadId): Flow<ThreadSnapshot?> =
        flow { emit(readThread(threadId)) }

    override suspend fun readThread(threadId: ThreadId): ThreadSnapshot? {
        val queries = database.queries
        val preferredServerId = currentServerId()
        val fallbackSnapshot = fallback.readThread(threadId)
        val resolved =
            preferredServerId?.let { serverId ->
                queries.readThreadChannel(serverId, threadId)?.let { channel -> serverId to channel }
            }
                ?: queries.selectChannelServerId(threadId.value).executeAsOneOrNull()
                    ?.let(::ServerId)
                    ?.let { serverId ->
                        queries.readThreadChannel(serverId, threadId)?.let { channel -> serverId to channel }
                    }
                ?: return fallbackSnapshot
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
        return ThreadSnapshot(
            conversation = channel.toConversationSummary(),
            messages = messages,
            parentMessage = parentMessage,
            tasks = fallbackSnapshot?.tasks.orEmpty(),
            lastSeq = messages.maxOfOrNull { it.seq ?: 0L } ?: 0L
        ).withPagingMetadataFrom(fallbackSnapshot)
    }

    private fun ThreadSnapshot.withPagingMetadataFrom(fallbackSnapshot: ThreadSnapshot?): ThreadSnapshot {
        if (fallbackSnapshot == null) return this
        val sameThread =
            fallbackSnapshot.conversation.threadId == conversation.threadId ||
                fallbackSnapshot.conversation.messageStreamChannelId() == conversation.messageStreamChannelId()
        if (!sameThread) return this
        return copy(
            canLoadMoreBefore = fallbackSnapshot.canLoadMoreBefore,
            canLoadMoreAfter = fallbackSnapshot.canLoadMoreAfter,
            hasGap = fallbackSnapshot.hasGap,
            historyLimited = fallbackSnapshot.historyLimited,
            highlightedMessageId = fallbackSnapshot.highlightedMessageId,
            newUpdateCount = fallbackSnapshot.newUpdateCount
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
            val messageChannelId = channel.storageCanonicalChannelId()
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
                        sizeBytes = attachment.sizeBytes,
                        thumbnailUrl = attachment.thumbnailUrl,
                        width = attachment.width?.toLong(),
                        height = attachment.height?.toLong(),
                        sortOrder = index.toLong()
                    )
                }
                queries.deleteReactionsForMessage(message.id.value)
                message.reactions.orEmpty().forEachIndexed { index, reaction ->
                    reaction.writeMessageReaction(message.id.value, sortOrder = index.toLong(), now)
                }
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
            task = null,
            replyCount = replyCount.toInt(),
            parentMessageId = parentMessageId?.let(::MessageId),
            updatedAtLabel = updatedAtLabel,
            reactions = reactions
        )
    }

    private fun MessageReaction.writeMessageReaction(messageId: String, sortOrder: Long, now: Long) {
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
                    sortOrder = sortOrder,
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
            unreadCount = unreadCount.toInt(),
            unreadCountKnown = true,
            mentionCount = mentionCount.toInt(),
            mentionCountKnown = true,
            lastMessageId = lastMessageId?.let(::MessageId),
            firstUnreadMessageId = firstUnreadMessageId?.let(::MessageId),
            lastMessagePreview = lastMessagePreview,
            lastMessageAt = updatedAtLabel,
            lastMessageSenderName = lastMessageSenderName,
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
            sizeBytes = sizeBytes,
            thumbnailUrl = thumbnailUrl,
            width = width?.toInt(),
            height = height?.toInt()
        )

    private fun MemberProfile.writeMemberProfile(serverId: ServerId, now: Long) {
        database.queries.upsertMemberSnapshot(
            id = id.value,
            serverId = serverId.value,
            displayName = displayName,
            handle = handle,
            kind = kind.toStoredKind(),
            avatarUrl = avatarUrl,
            avatarLabel = avatarLabel,
            presence = presence.name,
            description = description,
            roleDescription = roleDescription,
            membershipStatus = membershipStatus,
            lastSyncedAtMs = now
        )
    }

    private fun ConversationSummary.storageCanonicalChannelId(): ChannelId =
        if (kind == ConversationKind.Thread) {
            messageStreamChannelId()
        } else {
            channelId ?: ChannelId(threadId.value)
        }

    private fun decodeMetadata(value: String): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(value) }.getOrDefault(emptyMap())

    private fun String.toConversationKind(): ConversationKind =
        when (lowercase()) {
            "thread" -> ConversationKind.Thread
            "joint" -> ConversationKind.Joint
            "dm" -> ConversationKind.DirectMessage
            else -> ConversationKind.Channel
        }

    private fun Long.toConversationPermissions(): ConversationPermissions =
        ConversationPermissions(
            canRead = this and 1L != 0L,
            canSend = this and 2L != 0L,
            canAttach = this and 4L != 0L,
            canCreateTask = this and 8L != 0L
        )

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

}

private const val MessageReactionReactorKindUser = "user"
