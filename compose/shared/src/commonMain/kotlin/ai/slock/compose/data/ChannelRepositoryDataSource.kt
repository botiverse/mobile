package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val channelRepositoryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private suspend fun SlockNetworkClient.channels(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "channels")

private suspend fun SlockNetworkClient.directMessages(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "channels/dm")

private suspend fun SlockNetworkClient.channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "channels/unread")

private suspend fun SlockNetworkClient.serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
    request(priority, "servers/unread-summary")

private suspend fun SlockNetworkClient.createChannel(priority: DataRequestPriority, bodyText: String): HomeNetworkResponse =
    request(priority, "channels", method = "POST", bodyText = bodyText)

private suspend fun SlockNetworkClient.joinChannel(priority: DataRequestPriority, channelId: ChannelId): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/join", method = "POST")

private suspend fun SlockNetworkClient.leaveChannel(priority: DataRequestPriority, channelId: ChannelId): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/leave", method = "POST")

private suspend fun SlockNetworkClient.archiveChannel(priority: DataRequestPriority, channelId: ChannelId): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/archive", method = "POST")

private suspend fun SlockNetworkClient.deleteChannel(priority: DataRequestPriority, channelId: ChannelId): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}", method = "DELETE")

private suspend fun SlockNetworkClient.addChannelMember(
    priority: DataRequestPriority,
    channelId: ChannelId,
    bodyText: String
): HomeNetworkResponse =
    request(priority, "channels/${channelId.value}/members", method = "POST", bodyText = bodyText)

private suspend fun SlockNetworkClient.openDirectMessage(priority: DataRequestPriority, bodyText: String): HomeNetworkResponse =
    request(priority, "channels/dm", method = "POST", bodyText = bodyText)

class RealChannelRepository(
    private val sessionStore: SlockAuthSessionStore,
    private val networkClient: SlockNetworkClient,
    private val warningLogger: (String) -> Unit = {}
) : ChannelRepository {
    private val directMessageCacheMutex = Mutex()
    private var directMessageCache: CachedDirectMessages? = null
    private val conversationSummaryCacheLock = Any()
    private var conversationSummaryCache: CachedConversationSummaries? = null
    private val homeDataSource =
        SharedHomeDataSource(
            sessionStore = sessionStore,
            api = NetworkSlockHomeApi(networkClient),
            localWriteThrough = null,
            parseHome = { payload, session ->
                parseSlockHome(payload, session, warningLogger)
            }
        )

    override suspend fun getHome(serverId: ServerId): DataResult<HomeSnapshot> {
        return when (val result = homeDataSource.loadHome(serverId, DataRequestPriority.Visible)) {
            is DataResult.Content -> {
                val snapshot = result.data
                rememberConversationSummaries(serverId, snapshot.channels)
                rememberConversationSummaries(serverId, snapshot.directMessages)
                val channels = snapshot.channels.withCachedConversationSummaries(serverId)
                val directMessages =
                    mergeDirectMessageCache(serverId, snapshot.directMessages)
                        .withCachedConversationSummaries(serverId)
                DataResult.Content(
                    snapshot.copy(
                        channels = channels,
                        directMessages = directMessages,
                        unreadTotal = channels.sumOf { it.unreadCount } + directMessages.sumOf { it.unreadCount }
                    )
                )
            }
            else -> result
        }
    }

    override suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot> {
        val context = serverContext(serverId) ?: return authRequired()
        return fetchChannels(context)
    }

    override suspend fun getDirectMessages(serverId: ServerId): DataResult<DirectMessageSnapshot> {
        val context = serverContext(serverId) ?: return authRequired()
        return fetchDirectMessages(context)
    }

    override suspend fun openAgentDirectMessage(agentId: MemberId): DataResult<OpenDirectMessageResult> =
        openDirectMessage(memberId = agentId, bodyText = buildOpenAgentDirectMessageBody(agentId), source = "dm_open_agent")

    override suspend fun openUserDirectMessage(userId: MemberId): DataResult<OpenDirectMessageResult> =
        openDirectMessage(memberId = userId, bodyText = buildOpenUserDirectMessageBody(userId), source = "dm_open_user")

    override suspend fun createChannel(request: CreateChannelRequest): DataResult<CreateChannelResult> {
        val context = currentContext() ?: return authRequired()
        val name = request.name.trim()
        if (name.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        val createResult =
            executeChannel(
                emptyReason = EmptyReason.NoChannels,
                block = { networkClient.createChannel(DataRequestPriority.Foreground, buildCreateChannelBody(request)) },
                source = "channel_create",
                mapper = { bodyText ->
                    parseSharedCreateChannelResult(bodyText)
                        ?: throw IllegalStateException("Create channel response missing channel")
                }
            )
        if (createResult !is DataResult.Content) return createResult
        val channelId =
            createResult.data.channel.channelId
                ?: ChannelId(createResult.data.channel.threadId.value)
        rememberConversationSummaries(context.server.id, listOf(createResult.data.channel))
        val addedAgents = mutableListOf<String>()
        val addedUsers = mutableListOf<String>()
        for (agentId in request.agentMemberIds.distinct().filter { it.isNotBlank() }) {
            when (
                val result =
                    addChannelMember(
                        channelId,
                        buildAddAgentChannelMemberBody(MemberId(agentId)),
                        source = "channel_member_add_agent"
                    )
            ) {
                is DataResult.Content -> addedAgents += agentId
                else -> return result.mapError()
            }
        }
        for (userId in request.userMemberIds.distinct().filter { it.isNotBlank() }) {
            when (
                val result =
                    addChannelMember(
                        channelId,
                        buildAddUserChannelMemberBody(MemberId(userId)),
                        source = "channel_member_add_user"
                    )
            ) {
                is DataResult.Content -> addedUsers += userId
                else -> return result.mapError()
            }
        }
        val updatedChannel =
            createResult.data.channel.copy(
                memberCount =
                    (addedAgents.size + addedUsers.size).takeIf { it > 0 }
                        ?: createResult.data.channel.memberCount
            )
        rememberConversationSummaries(context.server.id, listOf(updatedChannel))
        return DataResult.Content(
            createResult.data.copy(
                channel = updatedChannel,
                addedAgentMemberIds = addedAgents,
                addedUserMemberIds = addedUsers
            )
        )
    }

    override suspend fun joinChannel(channelId: ChannelId): DataResult<ChannelJoinResult> {
        val context = currentContext() ?: return authRequired()
        if (channelId.value.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        return executeChannel(
            emptyReason = EmptyReason.NoChannels,
            block = { networkClient.joinChannel(DataRequestPriority.Foreground, channelId) },
            source = "channel_join",
            mapper = { bodyText ->
                parseSharedChannelJoinResult(bodyText, channelId)
                    ?: throw IllegalStateException("Join channel response missing ok")
            }
        ).also { result ->
            (result as? DataResult.Content)?.data?.channel?.let { channel ->
                rememberConversationSummaries(context.server.id, listOf(channel))
            }
        }
    }

    override suspend fun leaveChannel(channelId: ChannelId): DataResult<ChannelLeaveResult> {
        val context = currentContext() ?: return authRequired()
        if (channelId.value.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        return executeChannel(
            emptyReason = EmptyReason.NoChannels,
            block = { networkClient.leaveChannel(DataRequestPriority.Foreground, channelId) },
            source = "channel_leave",
            mapper = { bodyText ->
                parseSharedChannelLeaveResult(bodyText, channelId)
                    ?: throw IllegalStateException("Leave channel response missing ok")
            }
        ).also { result ->
            if (result is DataResult.Content) {
                removeConversationSummary(context.server.id, channelId)
            }
        }
    }

    override suspend fun archiveChannel(channelId: ChannelId): DataResult<ChannelArchiveResult> {
        val context = currentContext() ?: return authRequired()
        if (channelId.value.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        return executeChannel(
            emptyReason = EmptyReason.NoChannels,
            block = { networkClient.archiveChannel(DataRequestPriority.Foreground, channelId) },
            source = "channel_archive",
            mapper = { bodyText ->
                parseSharedChannelArchiveResult(bodyText, channelId)
                    ?: throw IllegalStateException("Archive channel response missing channel")
            }
        ).also { result ->
            if (result is DataResult.Content) {
                removeConversationSummary(context.server.id, channelId)
            }
        }
    }

    override suspend fun deleteChannel(channelId: ChannelId): DataResult<ChannelDeleteResult> {
        val context = currentContext() ?: return authRequired()
        if (channelId.value.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        return executeChannel(
            emptyReason = EmptyReason.NoChannels,
            block = { networkClient.deleteChannel(DataRequestPriority.Foreground, channelId) },
            source = "channel_delete",
            mapper = { bodyText ->
                parseSharedChannelDeleteResult(bodyText, channelId)
                    ?: throw IllegalStateException("Delete channel response missing ok")
            }
        ).also { result ->
            if (result is DataResult.Content) {
                removeConversationSummary(context.server.id, channelId)
            }
        }
    }

    private suspend fun addChannelMember(
        channelId: ChannelId,
        bodyText: String,
        source: String
    ): DataResult<Unit> =
        executeChannel(
            emptyReason = EmptyReason.NoMembers,
            block = { networkClient.addChannelMember(DataRequestPriority.Foreground, channelId, bodyText) },
            source = source,
            mapper = { Unit }
        )

    private suspend fun fetchChannels(context: SharedChannelServerContext): DataResult<ChannelSnapshot> =
        executeChannel(
            emptyReason = EmptyReason.NoChannels,
            block = { networkClient.channels(DataRequestPriority.Visible) },
            emptyWhen = { it.channels.isEmpty() },
            source = "home_channels",
            mapper = { bodyText ->
                val parsed =
                    parseSharedChannelSnapshot(bodyText, context.server)
                        ?: throw IllegalStateException("Channels response missing channel array")
                val channels = parsed.channels.dedupeChannelConversations()
                rememberConversationSummaries(context.server.id, channels)
                parsed.copy(
                    channels = channels.withCachedConversationSummaries(context.server.id)
                )
            }
        )

    private suspend fun fetchDirectMessages(context: SharedChannelServerContext): DataResult<DirectMessageSnapshot> =
        when (
            val result =
                executeChannel(
                    emptyReason = EmptyReason.NoChannels,
                    block = { networkClient.directMessages(DataRequestPriority.Visible) },
                    emptyWhen = { it.directMessages.isEmpty() },
                    source = "home_direct_messages",
                    mapper = { bodyText ->
                        val parsed =
                            parseSharedDirectMessageSnapshot(bodyText, context.server, warningLogger)
                                ?: throw IllegalStateException("Direct messages response missing dm array")
                        val directMessages = parsed.directMessages.dedupeChannelConversations()
                        rememberConversationSummaries(context.server.id, directMessages)
                        parsed.copy(
                            directMessages = directMessages.withCachedConversationSummaries(context.server.id)
                        )
                    }
                )
        ) {
            is DataResult.Content -> {
                val merged = mergeDirectMessageCache(context.server.id, result.data.directMessages)
                DataResult.Content(result.data.copy(directMessages = merged))
            }
            else -> result
        }

    private suspend fun openDirectMessage(
        memberId: MemberId,
        bodyText: String,
        source: String
    ): DataResult<OpenDirectMessageResult> {
        val context = currentContext() ?: return authRequired()
        if (memberId.value.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        val result =
            executeChannel(
                emptyReason = EmptyReason.NoChannels,
                block = { networkClient.openDirectMessage(DataRequestPriority.Foreground, bodyText) },
                source = source,
                mapper = { responseBody ->
                    parseSharedOpenDirectMessageResult(responseBody)
                        ?: throw IllegalStateException("Open DM response missing channel")
                }
            )
        return when (result) {
            is DataResult.Content -> {
                val merged = mergeDirectMessageCache(context.server.id, listOf(result.data.conversation))
                DataResult.Content(result.data.copy(directMessages = merged))
            }
            else -> result
        }
    }

    private suspend fun mergeDirectMessageCache(
        serverId: ServerId,
        incoming: List<ConversationSummary>
    ): List<ConversationSummary> =
        directMessageCacheMutex.withLock {
            val existing = directMessageCache?.takeIf { it.serverId == serverId }?.directMessages.orEmpty()
            val merged = mergeDirectMessages(existing, incoming)
            directMessageCache = CachedDirectMessages(serverId, merged)
            rememberConversationSummaries(serverId, merged)
            merged
        }

    private fun rememberConversationSummaries(
        serverId: ServerId,
        conversations: List<ConversationSummary>
    ) {
        if (conversations.isEmpty()) return
        synchronized(conversationSummaryCacheLock) {
            val existing = conversationSummaryCache?.takeIf { it.serverId == serverId }?.summaries.orEmpty()
            val merged = existing.toMutableMap()
            conversations.forEach { conversation ->
                val key = conversation.cacheKey() ?: return@forEach
                merged[key] = merged[key]?.mergeConversationSummary(conversation) ?: conversation
            }
            conversationSummaryCache = CachedConversationSummaries(serverId, merged)
        }
    }

    private fun removeConversationSummary(serverId: ServerId, channelId: ChannelId) {
        synchronized(conversationSummaryCacheLock) {
            val existing = conversationSummaryCache?.takeIf { it.serverId == serverId }?.summaries ?: return
            val filtered =
                existing.filterValues { summary ->
                    summary.channelId != channelId && summary.threadId.value != channelId.value
                }
            conversationSummaryCache = CachedConversationSummaries(serverId, filtered)
        }
    }

    private fun List<ConversationSummary>.withCachedConversationSummaries(serverId: ServerId): List<ConversationSummary> =
        synchronized(conversationSummaryCacheLock) {
            val cached = conversationSummaryCache?.takeIf { it.serverId == serverId }?.summaries.orEmpty()
            if (cached.isEmpty()) return@synchronized this
            map { conversation ->
                val key = conversation.cacheKey()
                if (key == null) conversation else cached[key]?.mergeConversationSummary(conversation) ?: conversation
            }
        }

    private suspend fun serverContext(serverId: ServerId): SharedChannelServerContext? =
        sessionStore.readSession()?.toChannelServerContext(serverId)

    private suspend fun currentContext(): SharedChannelServerContext? {
        val session = sessionStore.readSession() ?: return null
        val selectedId = session.currentServerId ?: return null
        return session.toChannelServerContext(ServerId(selectedId))
    }

    private suspend fun <T> executeChannel(
        emptyReason: EmptyReason,
        block: suspend () -> HomeNetworkResponse,
        emptyWhen: (T) -> Boolean = { false },
        source: String,
        mapper: (String) -> T
    ): DataResult<T> {
        var statusCode: Int? = null
        return try {
            val response = block()
            statusCode = response.code
            if (!response.isSuccessful) return response.toChannelDataError()
            val body = response.bodyText.orEmpty()
            if (body.isBlank()) return DataResult.Empty(emptyReason)
            val result = mapper(body)
            if (emptyWhen(result)) DataResult.Empty(emptyReason) else DataResult.Content(result)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            warningLogger(
                "parse_failed source=$source stage=response_or_mapper status=${statusCode ?: "unknown"} exception=${exception::class.simpleName}"
            )
            DataResult.Error(ScreenError(code = "network_parse_failed", retryable = true))
        }
    }

    private data class CachedDirectMessages(
        val serverId: ServerId,
        val directMessages: List<ConversationSummary>
    )

    private data class CachedConversationSummaries(
        val serverId: ServerId,
        val summaries: Map<String, ConversationSummary>
    )
}

private class NetworkSlockHomeApi(
    private val networkClient: SlockNetworkClient
) : SlockHomeApi {
    override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.channels(priority)

    override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.directMessages(priority)

    override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.channelUnreadCounts(priority)

    override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.serverUnreadSummary(priority)

    override suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(priority, "channels", serverIdOverride = serverId)

    override suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(priority, "channels/dm", serverIdOverride = serverId)

    override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(priority, "channels/unread", serverIdOverride = serverId)

    override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
        networkClient.request(priority, "servers/unread-summary", serverIdOverride = serverId)
}

@Serializable
private data class CreateChannelBody(
    val name: String,
    val description: String? = null
)

private fun buildCreateChannelBody(request: CreateChannelRequest): String =
    channelRepositoryJson.encodeToString(
        CreateChannelBody(
            name = request.name.trim(),
            description = request.description?.trim()?.takeIf { it.isNotBlank() }
        )
    )

@Serializable
private data class OpenAgentDirectMessageBody(val agentId: String)

@Serializable
private data class OpenUserDirectMessageBody(val userId: String)

private fun buildOpenAgentDirectMessageBody(agentId: MemberId): String =
    channelRepositoryJson.encodeToString(OpenAgentDirectMessageBody(agentId.value))

private fun buildOpenUserDirectMessageBody(userId: MemberId): String =
    channelRepositoryJson.encodeToString(OpenUserDirectMessageBody(userId.value))

@Serializable
private data class ChannelAddAgentMemberBody(val agentId: String)

@Serializable
private data class ChannelAddUserMemberBody(val userId: String)

private fun buildAddAgentChannelMemberBody(agentId: MemberId): String =
    channelRepositoryJson.encodeToString(ChannelAddAgentMemberBody(agentId.value))

private fun buildAddUserChannelMemberBody(userId: MemberId): String =
    channelRepositoryJson.encodeToString(ChannelAddUserMemberBody(userId.value))

private data class SharedChannelServerContext(
    val session: StoredAuthSession,
    val server: ServerSummary
)

private fun StoredAuthSession.toChannelServerContext(serverId: ServerId): SharedChannelServerContext? {
    val selectedId = currentServerId ?: return null
    if (selectedId != serverId.value) return null
    val server = servers.firstOrNull { it.id == selectedId }?.toServerSummary() ?: return null
    return SharedChannelServerContext(session = this, server = server)
}

private fun <T> authRequired(): DataResult<T> =
    DataResult.NoPermission(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session"))

private fun <T> HomeNetworkResponse.toChannelDataError(): DataResult<T> =
    when (code) {
        401 -> authRequired()
        403 -> DataResult.NoPermission(PermissionBlocker(code = "server_forbidden", requiredCapability = "server_access"))
        in 500..599 -> DataResult.Error(ScreenError(code = "server_unavailable", retryable = true))
        else -> DataResult.Error(ScreenError(code = "http_$code", retryable = code != 404))
    }

private fun <T> DataResult<*>.mapError(): DataResult<T> =
    when (this) {
        is DataResult.Empty -> this
        is DataResult.Error -> this
        is DataResult.NoPermission -> this
        is DataResult.Content -> error("Cannot map content as error.")
    }

private fun mergeDirectMessages(
    existing: List<ConversationSummary>,
    incoming: List<ConversationSummary>
): List<ConversationSummary> {
    val merged = LinkedHashMap<String, ConversationSummary>()
    for (conversation in existing) {
        merged[conversation.conversationCacheKey] = conversation
    }
    for (conversation in incoming) {
        val key = conversation.conversationCacheKey
        val previous = merged[key]
        merged.remove(key)
        merged[key] =
            if (previous == null) {
                conversation
            } else {
                conversation.withDirectMessageFallback(previous)
            }
    }
    return merged.values.toList()
}

private fun List<ConversationSummary>.dedupeChannelConversations(): List<ConversationSummary> {
    val deduped = LinkedHashMap<String, ConversationSummary>()
    for (conversation in this) {
        val key = conversation.conversationCacheKey
        deduped.remove(key)
        deduped[key] = conversation
    }
    return deduped.values.toList()
}

private val ConversationSummary.conversationCacheKey: String
    get() =
        if (kind == ConversationKind.Thread) threadId.value
        else channelId?.value ?: threadId.value

private fun ConversationSummary.cacheKey(): String? =
    if (kind == ConversationKind.Thread) threadId.value else channelId?.value ?: threadId.value

private fun ConversationSummary.mergeConversationSummary(incoming: ConversationSummary): ConversationSummary =
    incoming.copy(
        name = incoming.name.takeUnless { it.isGenericConversationName(incoming) } ?: name,
        subtitle = incoming.subtitle ?: subtitle,
        avatarUrl = incoming.avatarUrl ?: avatarUrl,
        avatar = incoming.avatar ?: avatar,
        unreadCount =
            when {
                incoming.unreadCountKnown -> incoming.unreadCount
                unreadCountKnown -> unreadCount
                else -> incoming.unreadCount
            },
        unreadCountKnown = incoming.unreadCountKnown || unreadCountKnown,
        mentionCount =
            when {
                incoming.mentionCountKnown -> incoming.mentionCount
                mentionCountKnown -> mentionCount
                else -> incoming.mentionCount
            },
        mentionCountKnown = incoming.mentionCountKnown || mentionCountKnown,
        lastMessageId = incoming.lastMessageId ?: lastMessageId,
        firstUnreadMessageId = incoming.firstUnreadMessageId ?: firstUnreadMessageId,
        lastMessagePreview = incoming.lastMessagePreview ?: lastMessagePreview,
        lastMessageAt = incoming.lastMessageAt ?: lastMessageAt,
        lastMessageSenderName = incoming.lastMessageSenderName ?: lastMessageSenderName,
        presence = incoming.presence ?: presence,
        agentStatus = incoming.agentStatus ?: agentStatus,
        presenceMemberId = incoming.presenceMemberId ?: presenceMemberId,
        hasDraft = if (incoming.hasDraftKnown) incoming.hasDraft else hasDraft,
        hasDraftKnown = incoming.hasDraftKnown || hasDraftKnown,
        joined = incoming.joined || joined,
        archived = incoming.archived || archived,
        memberCount = incoming.memberCount ?: memberCount
    )

private fun ConversationSummary.withDirectMessageFallback(previous: ConversationSummary): ConversationSummary =
    copy(
        name = name.takeUnless { it.isGenericConversationName(this) } ?: previous.name,
        subtitle = subtitle ?: previous.subtitle,
        avatarUrl = avatarUrl ?: previous.avatarUrl,
        avatar = avatar ?: previous.avatar,
        unreadCount =
            when {
                unreadCountKnown -> unreadCount
                previous.unreadCountKnown -> previous.unreadCount
                else -> unreadCount
            },
        unreadCountKnown = unreadCountKnown || previous.unreadCountKnown,
        mentionCount =
            when {
                mentionCountKnown -> mentionCount
                previous.mentionCountKnown -> previous.mentionCount
                else -> mentionCount
            },
        mentionCountKnown = mentionCountKnown || previous.mentionCountKnown,
        lastMessageId = lastMessageId ?: previous.lastMessageId,
        firstUnreadMessageId = firstUnreadMessageId ?: previous.firstUnreadMessageId,
        lastMessagePreview = lastMessagePreview ?: previous.lastMessagePreview,
        lastMessageAt = lastMessageAt ?: previous.lastMessageAt,
        lastMessageSenderName = lastMessageSenderName ?: previous.lastMessageSenderName,
        presence = presence ?: previous.presence,
        agentStatus = agentStatus ?: previous.agentStatus,
        presenceMemberId = presenceMemberId ?: previous.presenceMemberId,
        hasDraft = if (hasDraftKnown) hasDraft else previous.hasDraft,
        hasDraftKnown = hasDraftKnown || previous.hasDraftKnown,
        joined = joined || previous.joined,
        archived = archived || previous.archived,
        memberCount = memberCount ?: previous.memberCount
    )

private fun String.isGenericConversationName(conversation: ConversationSummary): Boolean =
    isBlank() || this == conversation.threadId.value || this == conversation.channelId?.value
