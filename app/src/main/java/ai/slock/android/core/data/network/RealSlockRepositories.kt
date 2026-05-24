package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.android.core.data.auth.ComposeAuthSessionStoreAdapter
import ai.slock.android.core.data.auth.KtorAuthRepository
import ai.slock.android.core.data.repository.AddChannelMemberRequest
import ai.slock.android.core.data.repository.AgentActivityEntry
import ai.slock.android.core.data.repository.AgentActivitySnapshot
import ai.slock.android.core.data.repository.AgentDetailSnapshot
import ai.slock.android.core.data.repository.AgentDmSnapshot
import ai.slock.android.core.data.repository.AgentDmSummary
import ai.slock.android.core.data.repository.AgentProfileUpdateRequest
import ai.slock.android.core.data.repository.AgentReminderSnapshot
import ai.slock.android.core.data.repository.AgentReminderSummary
import ai.slock.android.core.data.repository.AgentSkillGroup
import ai.slock.android.core.data.repository.AgentSkillPathGroup
import ai.slock.android.core.data.repository.AgentSkillSummary
import ai.slock.android.core.data.repository.AgentSkillsSnapshot
import ai.slock.android.core.data.repository.AgentWorkspaceChildrenSnapshot
import ai.slock.android.core.data.repository.AgentWorkspaceFilePreviewSnapshot
import ai.slock.android.core.data.repository.AgentWorkspaceSnapshot
import ai.slock.android.core.data.repository.AgentWorkspaceTreeSnapshot
import ai.slock.android.core.data.repository.AttachmentOpenTarget
import ai.slock.android.core.data.repository.AttachmentUploadFile
import ai.slock.android.core.data.repository.AttachmentUploadRequest
import ai.slock.android.core.data.repository.AttachmentUploadResult
import ai.slock.android.core.data.repository.ChannelArchiveResult
import ai.slock.android.core.data.repository.ChannelDeleteResult
import ai.slock.android.core.data.repository.ChannelFileItem
import ai.slock.android.core.data.repository.ChannelFilesSnapshot
import ai.slock.android.core.data.repository.ChannelJoinResult
import ai.slock.android.core.data.repository.ChannelLeaveResult
import ai.slock.android.core.data.repository.ChannelMemberActionResult
import ai.slock.android.core.data.repository.ChannelMemberSnapshot
import ai.slock.android.core.data.repository.ChannelRepository
import ai.slock.android.core.data.repository.ChannelSnapshot
import ai.slock.android.core.data.repository.ChannelUnreadSnapshot
import ai.slock.android.core.data.repository.ConversationRepository
import ai.slock.android.core.data.repository.CreateChannelRequest
import ai.slock.android.core.data.repository.CreateChannelResult
import ai.slock.compose.data.DataResult
import ai.slock.compose.data.DataRequestPriority
import ai.slock.compose.data.SharedHomeDataSource
import ai.slock.compose.data.parseSlockHome
import ai.slock.android.core.data.repository.DirectMessageSnapshot
import ai.slock.android.core.data.repository.HomeSnapshot
import ai.slock.android.core.data.repository.InboxFilter
import ai.slock.android.core.data.repository.InboxRepository
import ai.slock.android.core.data.repository.InboxSnapshot
import ai.slock.android.core.data.repository.MemberRepository
import ai.slock.android.core.data.repository.MemberSnapshot
import ai.slock.android.core.data.repository.OpenDirectMessageResult
import ai.slock.android.core.data.repository.ReadReceiptRepository
import ai.slock.android.core.data.repository.ReadReceiptResult
import ai.slock.android.core.data.repository.RemoveChannelMemberRequest
import ai.slock.android.core.data.repository.SavedRepository
import ai.slock.android.core.data.repository.SavedMessageMutationResult
import ai.slock.android.core.data.repository.SavedSnapshot
import ai.slock.android.core.data.repository.SearchRepository
import ai.slock.android.core.data.repository.SearchQueryOptions
import ai.slock.android.core.data.repository.SearchSnapshot
import ai.slock.android.core.data.repository.SendMessageResult
import ai.slock.android.core.data.repository.ServerUnreadRepository
import ai.slock.android.core.data.repository.ServerUnreadSummary
import ai.slock.android.core.data.repository.ServerUnreadSummarySnapshot
import ai.slock.android.core.data.repository.SettingsComputerWorkspaceSnapshot
import ai.slock.android.core.data.repository.TaskActionResult
import ai.slock.android.core.data.repository.TaskFilter
import ai.slock.android.core.data.repository.TaskRepository
import ai.slock.android.core.data.repository.TaskSnapshot
import ai.slock.android.core.data.repository.ThreadActionResult
import ai.slock.android.core.data.repository.ThreadListSnapshot
import ai.slock.android.core.data.repository.ThreadSnapshot
import ai.slock.android.core.model.AgentStatus
import ai.slock.android.core.model.Attachment
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.AvatarReference
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationPermissions
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.InboxItem
import ai.slock.android.core.model.InboxItemKind
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageDeliveryState
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.MessageType
import ai.slock.compose.model.sortedByMessageTime
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.ThreadConversation
import ai.slock.android.core.model.withUnreadReplyCount
import ai.slock.compose.model.SlockWorkspaceFileKind
import ai.slock.compose.model.SlockWorkspaceFilePreview
import ai.slock.compose.model.SlockWorkspaceNode
import ai.slock.compose.model.parentChannelIdOrNull
import ai.slock.compose.model.parentMessageIdOrNull
import ai.slock.compose.model.threadIdentity
import ai.slock.compose.model.toGravatarAvatarReference
import ai.slock.compose.network.SlockRequestPriority
import ai.slock.android.core.state.EmptyReason
import com.google.gson.Gson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import ai.slock.compose.model.topLevelThreadId

class RealChannelRepository(
        private val store: AuthSessionStore,
        private val api: SlockApi,
        private val warningLogger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                    "SlockHomeData",
                    message
            )
        }
) : ChannelRepository {
    private val directMessageCacheMutex = Mutex()
    private var directMessageCache: CachedDirectMessages? = null
    private val conversationSummaryCacheLock = Any()
    private var conversationSummaryCache: CachedConversationSummaries? = null
    private val sharedHomeDataSource =
            SharedHomeDataSource(
                    sessionStore = ComposeAuthSessionStoreAdapter(store),
                    api = AndroidSlockHomeApi(api),
                    localWriteThrough = null,
                    parseHome = { payload, session ->
                        parseSlockHome(payload, session, warningLogger)
                    }
            )

    override suspend fun getHome(serverId: ServerId): DataResult<HomeSnapshot> {
        return when (val result = sharedHomeDataSource.loadHome(serverId, DataRequestPriority.Visible)) {
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
                                unreadTotal =
                                        channels.sumOf { it.unreadCount } +
                                                directMessages.sumOf { it.unreadCount }
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

    override suspend fun openAgentDirectMessage(
            agentId: MemberId
    ): DataResult<OpenDirectMessageResult> =
            openDirectMessage(memberId = agentId, fieldName = "agentId", source = "dm_open_agent")

    override suspend fun openUserDirectMessage(
            userId: MemberId
    ): DataResult<OpenDirectMessageResult> =
            openDirectMessage(memberId = userId, fieldName = "userId", source = "dm_open_user")

    override suspend fun createChannel(
            request: CreateChannelRequest
    ): DataResult<CreateChannelResult> {
        val context = currentContext() ?: return authRequired()
        val name = request.name.trim()
        if (name.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        val createResult =
                execute(
                        emptyReason = EmptyReason.NoChannels,
                        block = {
                            api.createChannel(currentSlockApiPriority(),
                                    mapOf(
                                                    "name" to name,
                                                    "description" to
                                                            request.description?.trim()?.takeIf {
                                                                it.isNotBlank()
                                                            }
                                            )
                                            .toJsonBody()
                            )
                        },
                        source = "channel_create",
                        warningLogger = warningLogger,
                        mapper = { bodyText ->
                            ai.slock.compose.data.parseSharedCreateChannelResult(bodyText)
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
            when (val result =
                            addChannelMember(
                                    channelId,
                                    "agentId",
                                    agentId,
                                    source = "channel_member_add_agent"
                            )
            ) {
                is DataResult.Content -> addedAgents += agentId
                else -> return result.mapError()
            }
        }
        for (userId in request.userMemberIds.distinct().filter { it.isNotBlank() }) {
            when (val result =
                            addChannelMember(
                                    channelId,
                                    "userId",
                                    userId,
                                    source = "channel_member_add_user"
                            )
            ) {
                is DataResult.Content -> addedUsers += userId
                else -> return result.mapError()
            }
        }
        val updatedChannel =
                createResult.data.channel.withMergedMemberCount(
                        (addedAgents.size + addedUsers.size).takeIf { it > 0 }
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
        return execute(
                emptyReason = EmptyReason.NoChannels,
                block = { api.joinChannel(currentSlockApiPriority(), channelId.value) },
                source = "channel_join",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    ai.slock.compose.data.parseSharedChannelJoinResult(bodyText, channelId)
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
        return execute(
                emptyReason = EmptyReason.NoChannels,
                block = { api.leaveChannel(currentSlockApiPriority(), channelId.value) },
                source = "channel_leave",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    val parsed = ai.slock.compose.data.parseSharedChannelLeaveResult(bodyText, channelId)
                            ?: throw IllegalStateException("Leave channel response missing ok")
                    ChannelLeaveResult(channelId = parsed.channelId, left = parsed.left)
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
        return execute(
                emptyReason = EmptyReason.NoChannels,
                block = { api.archiveChannel(currentSlockApiPriority(), channelId.value) },
                source = "channel_archive",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    ai.slock.compose.data.parseSharedChannelArchiveResult(bodyText, channelId)
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
        return execute(
                emptyReason = EmptyReason.NoChannels,
                block = { api.deleteChannel(currentSlockApiPriority(), channelId.value) },
                source = "channel_delete",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    ai.slock.compose.data.parseSharedChannelDeleteResult(bodyText, channelId)
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
            fieldName: String,
            memberId: String,
            source: String
    ): DataResult<Unit> =
            execute(
                    emptyReason = EmptyReason.NoMembers,
                    block = {
                        api.addChannelMember(currentSlockApiPriority(),
                                channelId.value,
                                mapOf(fieldName to memberId).toJsonBody()
                        )
                    },
                    source = source,
                    warningLogger = warningLogger,
                    mapper = { Unit }
            )

    private suspend fun fetchChannels(context: NetworkServerContext): DataResult<ChannelSnapshot> =
            execute(
                    emptyReason = EmptyReason.NoChannels,
                    block = { api.channels(currentSlockApiPriority()) },
                    emptyWhen = { it.channels.isEmpty() },
                    source = "home_channels",
                    warningLogger = warningLogger,
                    mapper = { bodyText ->
                        val parsed =
                                ai.slock.compose.data.parseSharedChannelSnapshot(
                                        bodyText,
                                        context.server
                                ) ?: throw IllegalStateException("Channels response missing channel array")
                        val channels = parsed.channels.dedupeHomeConversations()
                        rememberConversationSummaries(context.server.id, channels)
                        parsed.copy(
                                channels = channels.withCachedConversationSummaries(context.server.id)
                        )
                    }
            )

    private suspend fun fetchDirectMessages(
            context: NetworkServerContext
    ): DataResult<DirectMessageSnapshot> =
            when (
                    val result =
                            execute(
                                    emptyReason = EmptyReason.NoChannels,
                                    block = { api.directMessages(currentSlockApiPriority()) },
                                    emptyWhen = { it.directMessages.isEmpty() },
                                    source = "home_direct_messages",
                                    warningLogger = warningLogger,
                                    mapper = { bodyText ->
                                        val parsed =
                                                ai.slock.compose.data.parseSharedDirectMessageSnapshot(
                                                        bodyText,
                                                        context.server,
                                                        warningLogger
                                                )
                                                        ?: throw IllegalStateException(
                                                                "Direct messages response missing dm array"
                                                        )
                                        val directMessages = parsed.directMessages.dedupeHomeConversations()
                                        rememberConversationSummaries(context.server.id, directMessages)
                                        parsed.copy(
                                                directMessages =
                                                        directMessages.withCachedConversationSummaries(
                                                                context.server.id
                                                        )
                                        )
                                    }
                            )
            ) {
                is DataResult.Content -> {
                    val merged =
                            mergeDirectMessageCache(
                                    context.server.id,
                                    result.data.directMessages
                                )
                    DataResult.Content(result.data.copy(directMessages = merged))
                }
                else -> result
            }

    private suspend fun openDirectMessage(
            memberId: MemberId,
            fieldName: String,
            source: String
    ): DataResult<OpenDirectMessageResult> {
        val context = currentContext() ?: return authRequired()
        if (memberId.value.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        val result =
                execute(
                        emptyReason = EmptyReason.NoChannels,
                        block = {
                            api.openDirectMessage(currentSlockApiPriority(), mapOf(fieldName to memberId.value).toJsonBody())
                        },
                        source = source,
                        warningLogger = warningLogger,
                        mapper = { bodyText ->
                            ai.slock.compose.data.parseSharedOpenDirectMessageResult(bodyText)
                                    ?: throw IllegalStateException("Open DM response missing channel")
                        }
                )
        return when (result) {
            is DataResult.Content -> {
                val merged =
                        mergeDirectMessageCache(
                                context.server.id,
                                listOf(result.data.conversation)
                        )
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
                val existing =
                        directMessageCache?.takeIf { it.serverId == serverId }?.directMessages
                                .orEmpty()
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
            val existing =
                    conversationSummaryCache?.takeIf { it.serverId == serverId }?.summaries
                            .orEmpty()
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
            val existing =
                    conversationSummaryCache?.takeIf { it.serverId == serverId }?.summaries
                            ?: return
            val filtered =
                    existing.filterValues { summary ->
                        summary.channelId != channelId && summary.threadId.value != channelId.value
                    }
            conversationSummaryCache = CachedConversationSummaries(serverId, filtered)
        }
    }

    private fun List<ConversationSummary>.withCachedConversationSummaries(
            serverId: ServerId
    ): List<ConversationSummary> =
            synchronized(conversationSummaryCacheLock) {
                val cached =
                        conversationSummaryCache?.takeIf { it.serverId == serverId }?.summaries
                                .orEmpty()
                if (cached.isEmpty()) return@synchronized this
                map { conversation ->
                    val key = conversation.cacheKey()
                    if (key == null) conversation
                    else cached[key]?.mergeConversationSummary(conversation) ?: conversation
                }
            }

    private suspend fun serverContext(serverId: ServerId): NetworkServerContext? =
            store.readSession()?.toNetworkServerContext(serverId)

    private suspend fun currentContext(): NetworkServerContext? {
        val session = store.readSession() ?: return null
        val selectedId = session.currentServerId ?: return null
        return session.toNetworkServerContext(ServerId(selectedId))
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

class RealReadReceiptRepository(
        private val store: AuthSessionStore,
        private val api: SlockApi,
        private val warningLogger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w("SlockRead", message)
        }
) : ReadReceiptRepository {
    override suspend fun getUnreadCounts(serverId: ServerId): DataResult<ChannelUnreadSnapshot> {
        val context = store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoInboxItems,
                block = { api.channelUnreadCounts(currentSlockApiPriority()) },
                source = "read_unread_counts",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    ai.slock.compose.data.parseChannelUnreadSnapshot(bodyText, context.server)
                            ?: throw IllegalStateException("Unread counts response missing channel map")
                }
        )
    }

    override suspend fun markChannelReadToSeq(
            channelId: ChannelId,
            seq: Long
    ): DataResult<ReadReceiptResult> {
        currentContext() ?: return authRequired()
        if (seq <= 0L) return DataResult.Empty(EmptyReason.NoMessages)
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = {
                    api.markChannelReadToSeq(currentSlockApiPriority(), channelId.value, mapOf("seq" to seq).toJsonBody())
                },
                source = "read_channel_seq",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    ai.slock.compose.data.parseReadReceiptResult(
                            bodyText,
                            fallbackChannelId = channelId,
                            fallbackSeq = seq
                    ) ?: throw IllegalStateException("Read receipt response missing ok")
                }
        )
    }

    override suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = { api.markChannelReadAll(currentSlockApiPriority(), channelId.value) },
                source = "read_channel_all",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    ai.slock.compose.data.parseReadReceiptResult(bodyText, fallbackChannelId = channelId)
                            ?: throw IllegalStateException("Read receipt response missing ok")
                }
        )
    }

    override suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = { api.markChannelUnread(currentSlockApiPriority(), channelId.value) },
                source = "read_channel_unread",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    ai.slock.compose.data.parseReadReceiptResult(bodyText, fallbackChannelId = channelId)
                            ?: throw IllegalStateException("Unread response missing ok")
                }
        )
    }

    override suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult> {
        store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoInboxItems,
                block = { api.markInboxReadAll(currentSlockApiPriority()) },
                source = "read_inbox_all",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    ai.slock.compose.data.parseReadReceiptResult(bodyText)
                            ?: throw IllegalStateException("Inbox read-all response missing ok")
                }
        )
    }

    private suspend fun currentContext(): NetworkServerContext? {
        val session = store.readSession() ?: return null
        val selectedId = session.currentServerId ?: return null
        return session.toNetworkServerContext(ServerId(selectedId))
    }
}

class RealTaskRepository(
        private val store: AuthSessionStore,
        private val api: SlockApi,
        private val warningLogger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w("SlockTasks", message)
        }
) : TaskRepository {
    override suspend fun getTasks(
            serverId: ServerId,
            filter: TaskFilter
    ): DataResult<TaskSnapshot> {
        val context = store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoTasks,
                block = { api.serverTasks(currentSlockApiPriority()) },
                emptyWhen = { it.tasks.isEmpty() },
                source = "tasks_server",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    parseTaskSnapshotFromBody(bodyText, filter, currentUserId = context.session.user.id)
                }
        )
    }

    override suspend fun getChannelTasks(
            serverId: ServerId,
            channelId: ChannelId,
            filter: TaskFilter
    ): DataResult<TaskSnapshot> {
        val context = store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoTasks,
                block = { api.channelTasks(currentSlockApiPriority(), channelId.value) },
                emptyWhen = { it.tasks.isEmpty() },
                source = "tasks_channel",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    parseTaskSnapshotFromBody(bodyText, filter, currentUserId = context.session.user.id)
                }
        )
    }

    override suspend fun claimTask(taskId: TaskId): DataResult<TaskActionResult> {
        currentContext() ?: return authRequired()
        return execute(
            emptyReason = EmptyReason.NoTasks,
            block = { api.claimTask(currentSlockApiPriority(), taskId.value) },
                source = "tasks_claim",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    val task = parseTaskActionFromBody(bodyText)
                            ?: throw IllegalStateException("Task action response missing task")
                    TaskActionResult(task)
            }
        )
    }

    override suspend fun updateTaskStatus(
        taskId: TaskId,
        status: TaskStatus
    ): DataResult<TaskActionResult> {
        currentContext() ?: return authRequired()
        return execute(
            emptyReason = EmptyReason.NoTasks,
            block = { api.updateTaskStatus(currentSlockApiPriority(), taskId.value, mapOf("status" to status.toApiValue()).toJsonBody()) },
            source = "tasks_status",
            warningLogger = warningLogger,
            mapper = { bodyText ->
                val task = parseTaskActionFromBody(bodyText)
                    ?: throw IllegalStateException("Task status response missing task")
                TaskActionResult(task)
            }
        )
    }

    private suspend fun currentContext(): NetworkServerContext? {
        val session = store.readSession() ?: return null
        val selectedId = session.currentServerId ?: return null
        return session.toNetworkServerContext(ServerId(selectedId))
    }
}

class RealMemberRepository(
        private val store: AuthSessionStore,
        private val api: SlockApi,
        private val warningLogger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w("SlockMembers", message)
        },
        private val logger: (String) -> Unit = {}
) : MemberRepository {
    private val agentActivityCacheMutex = Mutex()
    private val agentActivityCache = mutableMapOf<MemberId, List<AgentActivityEntry>>()

    override suspend fun getMembers(
            priority: SlockRequestPriority,
            serverId: ServerId
    ): DataResult<MemberSnapshot> {
        val context = store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        val result =
                execute(
                        emptyReason = EmptyReason.NoMembers,
                        block = { api.serverMembers(priority, serverId.value) },
                        source = "server_members",
                        warningLogger = warningLogger,
                        mapper = { bodyText ->
                            ai.slock.compose.data.parseSharedServerMemberSnapshot(
                                    bodyText,
                                    context.server
                            ) ?: throw IllegalStateException("Server members response missing member array")
                        }
                )
        val agentResult =
                execute(
                        emptyReason = EmptyReason.NoMembers,
                        block = { api.settingsAgents(priority) },
                        source = "server_agents",
                        warningLogger = warningLogger,
                        mapper = { bodyText ->
                            ai.slock.compose.data.parseSharedSettingsAgentsAsMembers(bodyText)
                                    ?: throw IllegalStateException("Agents response missing agent array")
                        }
                )
        val machineNameResult =
                execute(
                        emptyReason = EmptyReason.NoMembers,
                        block = { api.settingsMachines(priority, serverId.value) },
                        source = "server_agent_machines",
                        warningLogger = warningLogger,
                        mapper = { bodyText ->
                            ai.slock.compose.data.parseSharedSettingsAgentMachineNames(bodyText)
                                    ?: throw IllegalStateException("Machines response missing machines")
                        }
                )
        val mergedResult =
                when (result) {
                    is DataResult.Content -> {
                        val machineNames = (machineNameResult as? DataResult.Content)?.data.orEmpty()
                        val agents =
                                (agentResult as? DataResult.Content)
                                        ?.data
                                        .orEmpty()
                                        .resolveComputerLabels(machineNames)
                        val humans = result.data.members
                        val mergedMembers = (humans + agents).distinctBy { it.memberKey }
                        if (mergedMembers.isEmpty()) {
                            DataResult.Empty(EmptyReason.NoMembers)
                        } else {
                            DataResult.Content(result.data.copy(members = mergedMembers))
                        }
                    }
                    else -> result
                }
        mergedResult.logMemberCounts(
                source = "server_members",
                serverId = serverId.value,
                logger = logger
        )
        logger(
                "repository_agents_merge source=server_agents serverId=${serverId.value} result=${agentResult.memberResultName()} " +
                        "agentsCount=${(agentResult as? DataResult.Content)?.data?.count { it.kind == MemberKind.Agent } ?: 0}"
        )
        return mergedResult
    }

    override suspend fun getMemberProfile(
            priority: SlockRequestPriority,
            memberId: MemberId
    ): DataResult<MemberProfile> {
        currentContext() ?: return authRequired()
        return getHumanProfile(priority, memberId, source = "member_profile")
    }

    override suspend fun updateAgentProfile(
            priority: SlockRequestPriority,
            request: AgentProfileUpdateRequest
    ): DataResult<MemberProfile> {
        currentContext() ?: return authRequired()
        val displayName = request.displayName.trim()
        val description = request.description?.trim()?.takeIf { it.isNotBlank() }
        if (displayName.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        return execute(
                emptyReason = EmptyReason.NoMembers,
                block = {
                    api.updateAgent(priority,
                            request.agentId.value,
                            mapOf(
                                            "name" to displayName,
                                            "displayName" to displayName,
                                            "description" to description
                                    )
                                    .toJsonBody()
                    )
                },
                source = "agent_profile_update",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    ai.slock.compose.data.parseSharedAgentProfile(bodyText)
                            ?: throw IllegalStateException(
                                    "Agent profile update response missing agent"
                            )
                }
        )
    }

    override suspend fun getAgentDetail(
            priority: SlockRequestPriority,
            agentId: MemberId
    ): DataResult<AgentDetailSnapshot> {
        currentContext() ?: return authRequired()
        val directMessages = getAgentDirectMessages(priority, agentId)
        val reminders = getAgentReminders(priority, agentId)
        val activity = getAgentActivity(priority, agentId)
        val skills = getAgentSkills(priority, agentId)
        return when {
            directMessages is DataResult.Content &&
                    reminders is DataResult.Content &&
                    activity is DataResult.Content &&
                    skills is DataResult.Content ->
                    DataResult.Content(
                            AgentDetailSnapshot(
                                    agentId = agentId,
                                    directMessages = directMessages.data.directMessages,
                                    reminders = reminders.data.reminders,
                                    activity = activity.data.activity,
                                    skills = skills.data
                            )
                    )
            directMessages is DataResult.NoPermission -> directMessages
            reminders is DataResult.NoPermission -> reminders
            activity is DataResult.NoPermission -> activity
            skills is DataResult.NoPermission -> skills
            directMessages is DataResult.Error -> directMessages
            reminders is DataResult.Error -> reminders
            activity is DataResult.Error -> activity
            skills is DataResult.Error -> skills
            directMessages is DataResult.Empty &&
                    reminders is DataResult.Empty &&
                    activity is DataResult.Empty &&
                    skills is DataResult.Empty -> DataResult.Empty(EmptyReason.NoMembers)
            else -> DataResult.Empty(EmptyReason.NoMembers)
        }
    }

    override suspend fun getAgentDirectMessages(
            priority: SlockRequestPriority,
            agentId: MemberId
    ): DataResult<AgentDmSnapshot> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMembers,
                block = { api.agentDirectMessages(priority, agentId.value) },
                source = "agent_direct_messages",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    ai.slock.compose.data.parseSharedAgentDirectMessages(bodyText, agentId)
                            ?: throw IllegalStateException("Agent DM response missing dm array")
                }
        )
    }

    override suspend fun getAgentReminders(
            priority: SlockRequestPriority,
            agentId: MemberId
    ): DataResult<AgentReminderSnapshot> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMembers,
                block = { api.reminders(priority, agentId.value) },
                source = "agent_reminders",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    ai.slock.compose.data.parseSharedAgentReminders(bodyText, agentId)
                            ?: throw IllegalStateException("Agent reminders response missing reminders")
                }
        )
    }

    override suspend fun getAgentActivity(
            priority: SlockRequestPriority,
            agentId: MemberId
    ): DataResult<AgentActivitySnapshot> {
        currentContext() ?: return authRequired()
        val logResult =
                execute(
                        emptyReason = EmptyReason.NoMembers,
                        block = { api.agentActivityLog(priority, agentId.value, limit = 50) },
                        source = "agent_activity",
                        warningLogger = warningLogger,
                        mapper = { bodyText ->
                            val snapshot =
                                    ai.slock.compose.data.parseSharedAgentActivity(bodyText, agentId)
                                            ?: throw IllegalStateException(
                                                    "Agent activity response missing log array"
                                            )
                            logger(
                                    "agent_activity_log_fetch agentId=${agentId.value} mappedCount=${snapshot.activity.size}"
                            )
                            snapshot
                        }
                )
        val fallbackEntries = fetchAgentCurrentActivityFallback(priority, agentId)
        return when (logResult) {
            is DataResult.Content -> {
                val mergedInput = fallbackEntries + logResult.data.activity
                DataResult.Content(
                        logResult.data.copy(
                                activity = mergeAgentActivity(agentId, mergedInput)
                        )
                )
            }
            is DataResult.Empty -> {
                if (fallbackEntries.isNotEmpty()) {
                    DataResult.Content(
                            AgentActivitySnapshot(
                                    agentId = agentId,
                                    activity = mergeAgentActivity(agentId, fallbackEntries)
                            )
                    )
                } else {
                    logResult
                }
            }
            else -> logResult
        }
    }

    override suspend fun getAgentWorkspaces(
            priority: SlockRequestPriority,
            agentId: MemberId
    ): DataResult<AgentWorkspaceSnapshot> {
        val context = currentContext() ?: return authRequired()
        if (agentId.value.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        val lookupResult =
                execute(
                        emptyReason = EmptyReason.NoMembers,
                        block = { api.settingsAgents(priority) },
                        source = "agent_workspaces_lookup",
                        warningLogger = warningLogger,
                        mapper = { bodyText ->
                            ai.slock.compose.data.parseSharedAgentWorkspaceLookup(bodyText, agentId)
                                    ?: throw IllegalStateException(
                                            "Agent workspace lookup response missing agent"
                                    )
                        }
                )
        return when (lookupResult) {
            is DataResult.Content -> {
                val lookup = lookupResult.data
                val baseSnapshot =
                        AgentWorkspaceSnapshot(
                                agentId = agentId,
                                computerId = lookup.computerId,
                                computerName = lookup.computerName
                        )
                when {
                    lookup.directWorkspaces.isNotEmpty() ->
                            DataResult.Content(baseSnapshot.copy(workspaces = lookup.directWorkspaces))
                    !lookup.computerId.isNullOrBlank() -> {
                        val computerId = lookup.computerId.orEmpty()
                        val machineResult =
                                execute(
                                        emptyReason = EmptyReason.NoMembers,
                                        block = {
                                            api.settingsMachineWorkspaces(priority,
                                                    context.server.id.value,
                                                    computerId
                                            )
                                        },
                                        source = "agent_workspaces_machine",
                                        warningLogger = warningLogger,
                                mapper = { bodyText ->
                                    ai.slock.compose.data.parseSharedSettingsMachineWorkspaces(
                                            bodyText
                                    ) ?: throw IllegalStateException(
                                            "Machine workspaces response missing workspace array"
                                    )
                                }
                                )
                        when (machineResult) {
                            is DataResult.Content ->
                                    DataResult.Content(
                                            baseSnapshot.copy(workspaces = machineResult.data)
                                    )
                            is DataResult.Empty ->
                                    if (lookup.fallbackWorkspaces.isNotEmpty()) {
                                        DataResult.Content(
                                                baseSnapshot.copy(
                                                        workspaces = lookup.fallbackWorkspaces
                                                )
                                        )
                                    } else {
                                        machineResult
                                    }
                            is DataResult.Error ->
                                    if (lookup.fallbackWorkspaces.isNotEmpty()) {
                                        DataResult.Content(
                                                baseSnapshot.copy(
                                                        workspaces = lookup.fallbackWorkspaces
                                                )
                                        )
                                    } else {
                                        machineResult
                                    }
                            is DataResult.NoPermission ->
                                    if (lookup.fallbackWorkspaces.isNotEmpty()) {
                                        DataResult.Content(
                                                baseSnapshot.copy(
                                                        workspaces = lookup.fallbackWorkspaces
                                                )
                                        )
                                    } else {
                                        machineResult
                                    }
                        }
                    }
                    lookup.fallbackWorkspaces.isNotEmpty() ->
                            DataResult.Content(baseSnapshot.copy(workspaces = lookup.fallbackWorkspaces))
                    else -> DataResult.Empty(EmptyReason.NoMembers)
                }
            }
            is DataResult.Empty -> lookupResult
            is DataResult.Error -> lookupResult
            is DataResult.NoPermission -> lookupResult
        }
    }

    override suspend fun getAgentWorkspaceTree(
            priority: SlockRequestPriority,
            agentId: MemberId
    ): DataResult<AgentWorkspaceTreeSnapshot> {
        currentContext() ?: return authRequired()
        if (agentId.value.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        return execute(
                emptyReason = EmptyReason.NoMembers,
                block = { api.agentWorkspaceFiles(priority, agentId.value) },
                source = "agent_workspace_root",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    val children =
                            ai.slock.compose.data.parseSharedWorkspaceFileNodesOrNull(bodyText)
                                    ?: throw IllegalStateException(
                                            "Workspace file response missing files"
                                    )
                    AgentWorkspaceTreeSnapshot(
                            agentId = agentId,
                            rootPath = "Workspace",
                            root =
                                    SlockWorkspaceNode(
                                            id = "/",
                                            name = "workspace",
                                            path = "/",
                                            kind = SlockWorkspaceFileKind.Folder,
                                            children = children
                                    )
                    )
                }
        )
    }

    override suspend fun getAgentWorkspaceChildren(
            priority: SlockRequestPriority,
            agentId: MemberId,
            dirPath: String
    ): DataResult<AgentWorkspaceChildrenSnapshot> {
        currentContext() ?: return authRequired()
        if (agentId.value.isBlank() || dirPath.isBlank()) {
            return DataResult.Empty(EmptyReason.NoMembers)
        }
        return execute(
                emptyReason = EmptyReason.NoMembers,
                block = { api.agentWorkspaceFiles(priority, agentId.value, dirPath) },
                source = "agent_workspace_children",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    val children =
                            ai.slock.compose.data.parseSharedWorkspaceFileNodesOrNull(bodyText)
                                    ?: throw IllegalStateException(
                                            "Workspace file response missing files"
                                    )
                    AgentWorkspaceChildrenSnapshot(
                            agentId = agentId,
                            dirPath = dirPath,
                            children = children
                    )
                }
        )
    }

    override suspend fun readAgentWorkspaceFile(
            priority: SlockRequestPriority,
            agentId: MemberId,
            path: String
    ): DataResult<AgentWorkspaceFilePreviewSnapshot> {
        currentContext() ?: return authRequired()
        if (agentId.value.isBlank() || path.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        return execute(
                emptyReason = EmptyReason.NoMembers,
                block = { api.readAgentWorkspaceFile(priority, agentId.value, path) },
                source = "agent_workspace_file_read",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    ai.slock.compose.data.parseSharedAgentWorkspaceFilePreviewSnapshot(
                            bodyText,
                            agentId,
                            path
                    )
                }
        )
    }

    override suspend fun getAgentSkills(
            priority: SlockRequestPriority,
            agentId: MemberId
    ): DataResult<AgentSkillsSnapshot> {
        currentContext() ?: return authRequired()
        if (agentId.value.isBlank()) return DataResult.Empty(EmptyReason.NoMembers)
        return execute(
                emptyReason = EmptyReason.NoMembers,
                block = { api.agentSkills(priority, agentId.value) },
                source = "agent_skills",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    ai.slock.compose.data.parseSharedAgentSkillsSnapshot(bodyText, agentId)
                            ?: throw IllegalStateException("Agent skills response missing skill groups")
                }
        )
    }

    override suspend fun getChannelMembers(
            priority: SlockRequestPriority,
            channelId: ChannelId
    ): DataResult<ChannelMemberSnapshot> {
        currentContext() ?: return authRequired()
        val result =
                execute(
                        emptyReason = EmptyReason.NoMembers,
                        block = { api.channelMembers(priority, channelId.value) },
                        emptyWhen = { it.members.isEmpty() },
                        source = "channel_members",
                        warningLogger = warningLogger,
                        mapper = { bodyText ->
                            ai.slock.compose.data.parseSharedChannelMemberSnapshot(
                                    bodyText,
                                    channelId
                            ) ?: throw IllegalStateException(
                                    "Channel members response missing members"
                            )
                        }
                )
        result.logChannelMemberCounts(
                source = "channel_members",
                channelId = channelId.value,
                logger = logger
        )
        return result
    }

    override suspend fun addChannelMember(
            priority: SlockRequestPriority,
            request: AddChannelMemberRequest
    ): DataResult<ChannelMemberActionResult> {
        currentContext() ?: return authRequired()
        val fieldName = if (request.kind == MemberKind.Agent) "agentId" else "userId"
        return execute(
                emptyReason = EmptyReason.NoMembers,
                block = {
                    api.addChannelMember(priority,
                            request.channelId.value,
                            mapOf(fieldName to request.memberId.value).toJsonBody()
                    )
                },
                source = "channel_member_add",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    val parsed =
                            ai.slock.compose.data.parseSharedChannelMemberActionResult(
                            bodyText,
                            channelId = request.channelId,
                            memberId = request.memberId,
                            added = true
                    ) ?: throw IllegalStateException("Channel member add response missing ok")
                    ChannelMemberActionResult(
                            channelId = parsed.channelId,
                            memberId = parsed.memberId,
                            member = parsed.member,
                            added = parsed.added
                    )
                }
        )
    }

    override suspend fun removeChannelMember(
            priority: SlockRequestPriority,
            request: RemoveChannelMemberRequest
    ): DataResult<ChannelMemberActionResult> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMembers,
                block = {
                    api.removeChannelMember(priority,
                            channelId = request.channelId.value,
                            kind = request.kind.channelMemberPathSegment,
                            memberId = request.memberId.value
                    )
                },
                source = "channel_member_remove",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    val parsed =
                            ai.slock.compose.data.parseSharedChannelMemberActionResult(
                            bodyText,
                            channelId = request.channelId,
                            memberId = request.memberId,
                            added = false
                    ) ?: throw IllegalStateException("Channel member remove response missing ok")
                    ChannelMemberActionResult(
                            channelId = parsed.channelId,
                            memberId = parsed.memberId,
                            member = parsed.member,
                            added = parsed.added
                    )
                }
        )
    }

    private suspend fun currentContext(): NetworkServerContext? {
        val session = store.readSession() ?: return null
        val selectedId = session.currentServerId ?: return null
        return session.toNetworkServerContext(ServerId(selectedId))
    }

    private suspend fun mergeAgentActivity(
            agentId: MemberId,
            incoming: List<AgentActivityEntry>
    ): List<AgentActivityEntry> =
            agentActivityCacheMutex.withLock {
                val merged = mergeAgentActivityEntries(agentActivityCache[agentId].orEmpty(), incoming)
                agentActivityCache[agentId] = merged
                merged
            }

    private suspend fun fetchAgentCurrentActivityFallback(
            priority: SlockRequestPriority,
            agentId: MemberId
    ): List<AgentActivityEntry> {
        val result =
                execute<List<AgentActivityEntry>>(
                        emptyReason = EmptyReason.NoMembers,
                        block = { api.settingsAgents(priority) },
                        source = "agent_activity_current_fallback",
                        warningLogger = warningLogger,
                        mapper = { bodyText ->
                            val entries =
                                    ai.slock.compose.data.parseSharedCurrentAgentActivityEntries(
                                            bodyText,
                                            agentId
                                    ) ?: throw IllegalStateException(
                                            "Current agent activity response missing agent array"
                                    )
                            logger(
                                    "agent_activity_fallback_fetch agentId=${agentId.value} mappedCount=${entries.size}"
                            )
                            entries
                        }
                )
        return (result as? DataResult.Content)?.data.orEmpty()
    }

    private suspend fun enrichHumanProfiles(
            priority: SlockRequestPriority,
            members: List<MemberProfile>,
            source: String
    ): List<MemberProfile> {
        if (members.none { it.kind == MemberKind.Human }) return members
        val uniqueHumanIds = members
                .filter { it.kind == MemberKind.Human }
                .map { it.id }
                .distinct()
        val profileById: Map<MemberId, MemberProfile> = coroutineScope {
            uniqueHumanIds.map { memberId ->
                async {
                    val profileResult = getHumanProfile(priority, memberId, source = source)
                    val profile = (profileResult as? DataResult.Content)?.data
                    logger(
                            "human_profile_merge source=$source memberId=${memberId.value} " +
                                    "result=${profileResult.memberResultName()}"
                    )
                    memberId to profile
                }
            }.map { it.await() }
        }.mapNotNull { (id, profile) -> profile?.let { id to it } }.toMap()
        return members.map { member ->
            if (member.kind != MemberKind.Human) member
            else profileById[member.id]?.let { member.withHumanProfile(it) } ?: member
        }
    }

    private suspend fun getHumanProfile(
            priority: SlockRequestPriority,
            memberId: MemberId,
            source: String
    ): DataResult<MemberProfile> =
            currentContext()?.let { context ->
                execute(
                        emptyReason = EmptyReason.NoMembers,
                        block = { api.memberProfile(priority, context.server.id.value, memberId.value) },
                        source = source,
                        warningLogger = warningLogger,
                        mapper = { bodyText ->
                            ai.slock.compose.data.parseSharedMemberProfile(bodyText)
                                    ?: throw IllegalStateException(
                                            "Member profile response missing member"
                                    )
                        }
                )
            } ?: authRequired()
}

private data class AgentWorkspaceLookup(
        val computerId: String?,
        val computerName: String?,
        val directWorkspaces: List<SettingsComputerWorkspaceSnapshot>,
        val fallbackWorkspaces: List<SettingsComputerWorkspaceSnapshot>
)

class RealInboxRepository(private val store: AuthSessionStore, private val api: SlockApi) :
        InboxRepository {
    override suspend fun loadInitial(
            serverId: ServerId,
            filter: InboxFilter,
            limit: Int
    ): DataResult<InboxSnapshot> = load(serverId, filter, limit, offset = 0)

    override suspend fun loadMore(
            serverId: ServerId,
            filter: InboxFilter,
            offset: Int,
            limit: Int
    ): DataResult<InboxSnapshot> = load(serverId, filter, limit, offset)

    private suspend fun load(
            serverId: ServerId,
            filter: InboxFilter,
            limit: Int,
            offset: Int
    ): DataResult<InboxSnapshot> {
        val context = store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoInboxItems,
                block = { api.inbox(currentSlockApiPriority(), filter.wireValue, limit, offset) },
                emptyWhen = { it.items.isEmpty() },
                mapper = { bodyText ->
                    val parsed = ai.slock.compose.data.parseSharedInboxSnapshot(bodyText)
                    val items = parsed?.items.orEmpty()
                    if (parsed != null &&
                            ((parsed.totalCount != items.size) ||
                                    (parsed.totalUnreadCount != items.sumOf { it.unreadCount }))) {
                        ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                                "SlockInboxScope",
                                "inbox_count_override serverId=${serverId.value} filter=${filter.wireValue} offset=$offset wireTotal=${parsed.totalCount} wireUnread=${parsed.totalUnreadCount} itemCount=${items.size} itemUnread=${items.sumOf { it.unreadCount }}"
                        )
                    }
                    InboxSnapshot(
                            server = context.server,
                            items = items,
                            totalCount = parsed?.totalCount ?: items.size,
                            totalUnreadCount =
                                    parsed?.totalUnreadCount ?: items.sumOf { it.unreadCount },
                            hasMore = parsed?.hasMore ?: false,
                            nextOffset = offset + items.size
                    )
                }
        )
    }
}

class RealSavedRepository(private val store: AuthSessionStore, private val api: SlockApi) :
        SavedRepository {
    override suspend fun loadInitial(serverId: ServerId, limit: Int): DataResult<SavedSnapshot> =
            load(serverId, limit, offset = 0)

    override suspend fun loadMore(
            serverId: ServerId,
            offset: Int,
            limit: Int
    ): DataResult<SavedSnapshot> = load(serverId, limit, offset)

    override suspend fun saveMessage(
            serverId: ServerId,
            messageId: MessageId
    ): DataResult<SavedMessageMutationResult> =
            mutateSavedMessage(serverId, messageId, save = true)

    override suspend fun unsaveMessage(
            serverId: ServerId,
            messageId: MessageId
    ): DataResult<SavedMessageMutationResult> =
            mutateSavedMessage(serverId, messageId, save = false)

    private suspend fun mutateSavedMessage(
            serverId: ServerId,
            messageId: MessageId,
            save: Boolean
    ): DataResult<SavedMessageMutationResult> {
        store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoInboxItems,
                block = {
                    if (save) {
                        api.saveMessage(currentSlockApiPriority(), mapOf("messageId" to messageId.value).toJsonBody())
                    } else {
                        api.unsaveMessage(currentSlockApiPriority(), messageId.value)
                    }
                },
                mapper = { SavedMessageMutationResult(messageId) }
        )
    }

    private suspend fun load(
            serverId: ServerId,
            limit: Int,
            offset: Int
    ): DataResult<SavedSnapshot> {
        val context = store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoInboxItems,
                block = { api.saved(currentSlockApiPriority(), limit, offset) },
                emptyWhen = { it.items.isEmpty() },
                mapper = { bodyText ->
                    val parsed = ai.slock.compose.data.parseSharedSavedItems(bodyText)
                            ?: ai.slock.compose.data.SharedSavedItems(emptyList(), false, 0)
                    SavedSnapshot(
                            server = context.server,
                            items = parsed.items,
                            totalCount = parsed.totalCount,
                            hasMore = parsed.hasMore,
                            nextOffset = offset + parsed.items.size
                    )
                }
        )
    }
}

class RealSearchRepository(private val store: AuthSessionStore, private val api: SlockApi) :
        SearchRepository {
    override suspend fun search(
            serverId: ServerId,
            query: String,
            offset: Int,
            limit: Int,
            options: SearchQueryOptions
    ): DataResult<SearchSnapshot> {
        if (query.isBlank()) return DataResult.Empty(EmptyReason.NoSearchResults)
        val context = store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoSearchResults,
                block = {
                    api.searchMessages(
                            priority = currentSlockApiPriority(),
                            query = query.trim(),
                            limit = limit,
                            offset = offset,
                            sort = options.sort.wireValue,
                            senderId = options.senderId?.takeIf { it.isNotBlank() },
                            channelId = options.channelId?.value,
                            after = options.afterIso?.takeIf { it.isNotBlank() },
                            before = options.beforeIso?.takeIf { it.isNotBlank() }
                    )
                },
                emptyWhen = { it.items.isEmpty() },
                mapper = { bodyText ->
                    val parsed = ai.slock.compose.data.parseSharedSearchItems(bodyText)
                            ?: ai.slock.compose.data.SharedSearchItems(emptyList(), false)
                    SearchSnapshot(
                            server = context.server,
                            query = query,
                            items = parsed.items,
                            totalCount = parsed.items.size,
                            hasMore = parsed.hasMore,
                            nextOffset = offset + parsed.items.size
                    )
                }
        )
    }
}

class RealConversationRepository(
        private val store: AuthSessionStore,
        private val api: SlockApi,
        private val warningLogger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                    "SlockConversation",
                    message
            )
        }
) : ConversationRepository {
    override suspend fun getThread(
            threadId: ThreadId,
            includeFollowedSummaries: Boolean
    ): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        return loadChannelMessages(
                context = context,
                threadId = threadId,
                block = { api.channelMessages(currentSlockApiPriority(), threadId.value, MESSAGE_PAGE_LIMIT) },
                source = "messages_channel",
                includeFollowedSummaries = includeFollowedSummaries
        )
    }

    override suspend fun getMessageContext(messageId: MessageId): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = { api.messageContext(currentSlockApiPriority(), messageId.value) },
                emptyWhen = { it.messages.isEmpty() && it.parentMessage == null },
                source = "messages_context",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    val fallbackThreadId = ThreadId(messageId.value)
                    val payload =
                            ai.slock.compose.data.parseSharedMessageContext(
                                    bodyText,
                                    fallbackThreadId
                            )
                    val resolvedThreadId = payload.channelId?.let { ThreadId(it.value) }
                            ?: fallbackThreadId
                    buildSharedThreadSnapshot(
                            payload = payload,
                            threadId = resolvedThreadId,
                            canLoadMoreBefore = payload.hasOlder,
                            canLoadMoreAfter = payload.hasNewer,
                            highlightedMessageId = payload.targetMessageId ?: messageId
                    )
                }
        )
    }

    override suspend fun loadOlderMessages(
            threadId: ThreadId,
            beforeSeq: Long
    ): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        return loadChannelMessages(
                context = context,
                threadId = threadId,
                block = {
                    api.channelMessages(currentSlockApiPriority(), threadId.value, MESSAGE_PAGE_LIMIT, beforeSeq = beforeSeq)
                },
                source = "messages_channel_older"
        )
    }

    override suspend fun loadNewerMessages(
            threadId: ThreadId,
            afterSeq: Long
    ): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = {
                    api.messageSync(currentSlockApiPriority(),
                            sinceSeq = afterSeq,
                            limit = SYNC_PAGE_LIMIT,
                            channelId = threadId.value
                    )
                },
                emptyWhen = { it.messages.isEmpty() },
                source = "messages_sync",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    val payload =
                            ai.slock.compose.data.parseSharedSyncMessages(
                                    bodyText,
                                    threadId
                            )
                    buildSharedThreadSnapshot(
                            payload = payload,
                            threadId = threadId
                    )
                }
        )
    }

    override suspend fun getChannelThreads(
            channelId: ChannelId,
            limit: Int
    ): DataResult<ThreadListSnapshot> {
        val context = currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoInboxItems,
                block = { api.channelThreads(currentSlockApiPriority(), channelId.value, limit) },
                emptyWhen = { it.threads.isEmpty() },
                source = "channel_threads",
                warningLogger = warningLogger,
                mapper = { bodyText -> parseThreadListSnapshotFromBody(bodyText, context) }
        )
    }

    override suspend fun getFollowedThreads(
            serverId: ServerId,
            limit: Int
    ): DataResult<ThreadListSnapshot> {
        val context = store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoInboxItems,
                block = { api.followedThreads(currentSlockApiPriority(), limit) },
                emptyWhen = { it.threads.isEmpty() },
                source = "followed_threads",
                warningLogger = warningLogger,
                mapper = { bodyText -> parseThreadListSnapshotFromBody(bodyText, context) }
        )
    }

    override suspend fun createOrOpenThread(
            channelId: ChannelId,
            parentMessageId: MessageId
    ): DataResult<ThreadActionResult> =
            threadAction(
                    fallbackThreadChannelId = null,
                    fallbackParentMessageId = parentMessageId,
                    source = "thread_create_or_open"
            ) {
                api.createOrOpenThread(currentSlockApiPriority(), channelId.value, parentMessageId.toParentMessageThreadBody())
            }

    override suspend fun followThread(parentMessageId: MessageId): DataResult<ThreadActionResult> =
            threadAction(
                    fallbackThreadChannelId = null,
                    fallbackParentMessageId = parentMessageId,
                    source = "thread_follow_parent"
            ) { api.followThread(currentSlockApiPriority(), parentMessageId.toParentMessageThreadBody()) }

    override suspend fun followThread(threadChannelId: ChannelId): DataResult<ThreadActionResult> =
            threadAction(threadChannelId, source = "thread_follow") {
                api.followThread(currentSlockApiPriority(), threadChannelId.toThreadActionBody())
            }

    override suspend fun unfollowThread(
            threadChannelId: ChannelId
    ): DataResult<ThreadActionResult> =
            threadAction(threadChannelId, source = "thread_unfollow") {
                api.unfollowThread(currentSlockApiPriority(), threadChannelId.toThreadActionBody())
            }

    override suspend fun markThreadDone(
            threadChannelId: ChannelId
    ): DataResult<ThreadActionResult> =
            threadAction(threadChannelId, source = "thread_done") {
                api.markThreadDone(currentSlockApiPriority(), threadChannelId.toThreadActionBody())
            }

    override suspend fun markThreadUndone(
            threadChannelId: ChannelId
    ): DataResult<ThreadActionResult> =
            threadAction(threadChannelId, source = "thread_undone") {
                api.markThreadUndone(currentSlockApiPriority(), threadChannelId.toThreadActionBody())
            }

    override suspend fun uploadAttachments(
            request: AttachmentUploadRequest
    ): DataResult<AttachmentUploadResult> {
        currentContext() ?: return authRequired()
        if (request.files.isEmpty()) return DataResult.Empty(EmptyReason.NoMessages)
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = {
                    api.uploadAttachments(currentSlockApiPriority(),
                            channelId = request.channelId.value.toRequestBody(textMediaType),
                            files = request.files.map { it.toMultipartPart() }
                    )
                },
                source = "attachments_upload",
                warningLogger = warningLogger,
                mapper = { bodyText -> ai.slock.compose.data.parseSharedAttachmentUploadResult(bodyText) },
                emptyWhen = { it.attachments.isEmpty() }
        )
    }

    override suspend fun getAttachmentOpenTarget(
            attachmentId: AttachmentId
    ): DataResult<AttachmentOpenTarget> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = { api.attachmentUrl(currentSlockApiPriority(), attachmentId.value) },
                source = "attachment_open_url",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    ai.slock.compose.data.parseSharedAttachmentOpenTarget(
                            bodyText,
                            attachmentId
                    ) ?: throw IllegalStateException("Attachment URL response missing target")
                },
                emptyWhen = { it.url.isBlank() }
        )
    }

    override suspend fun getChannelFiles(
            channelId: ChannelId,
            limit: Int,
            cursor: String?
    ): DataResult<ChannelFilesSnapshot> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = { api.channelFiles(currentSlockApiPriority(), channelId.value, limit, cursor) },
                source = "channel_files",
                warningLogger = warningLogger,
                mapper = { bodyText -> ai.slock.compose.data.parseSharedChannelFilesSnapshot(bodyText, channelId) },
                emptyWhen = { it.items.isEmpty() }
        )
    }

    override suspend fun sendMessage(
            channelId: ChannelId,
            body: String,
            attachmentIds: List<AttachmentId>,
            asTask: Boolean
    ): DataResult<SendMessageResult> {
        val context = currentContext() ?: return authRequired()
        if (body.isBlank() && attachmentIds.isEmpty())
                return DataResult.Empty(EmptyReason.NoMessages)
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = { api.sendMessage(currentSlockApiPriority(), sendMessageBody(channelId, body, attachmentIds, asTask)) },
                source = "messages_send",
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    val fallbackThreadId = topLevelThreadId(channelId)
                    val message =
                            ai.slock.compose.data.parseSharedSentMessage(
                                    bodyText,
                                    fallbackThreadId
                            )
                                    ?: Message(
                                            id = MessageId("message-sent"),
                                            threadId = fallbackThreadId,
                                            channelId = channelId,
                                            author =
                                                    context.session.user.toMemberProfile(),
                                            timestampLabel = "",
                                            body = body,
                                            deliveryState = MessageDeliveryState.Sent
                                    )
                    SendMessageResult(message)
                }
        )
    }

    private suspend fun loadChannelMessages(
            context: NetworkServerContext,
            threadId: ThreadId,
            block: suspend () -> ApiResponse,
            source: String,
            includeFollowedSummaries: Boolean = true
    ): DataResult<ThreadSnapshot> = coroutineScope {
        val messagesDeferred =
                async {
                    execute(
                            emptyReason = EmptyReason.NoMessages,
                            block = block,
                            emptyWhen = { it.messages.isEmpty() },
                            source = source,
                            warningLogger = warningLogger,
                            mapper = { bodyText ->
                                val payload =
                                        ai.slock.compose.data.parseSharedChannelMessages(
                                                bodyText,
                                                threadId
                                        )
                                buildSharedThreadSnapshot(
                                        payload = payload,
                                        threadId = threadId,
                                        canLoadMoreBefore =
                                                payload.messages.size >= MESSAGE_PAGE_LIMIT
                                )
                            }
                    )
                }
        val threadSummariesDeferred =
                async {
                    execute(
                            emptyReason = EmptyReason.NoInboxItems,
                            block = { api.channelThreads(currentSlockApiPriority(), threadId.value, MESSAGE_PAGE_LIMIT) },
                            emptyWhen = { false },
                            source = "${source}_thread_summaries",
                            warningLogger = warningLogger,
                            mapper = { bodyText -> parseThreadListSnapshotFromBody(bodyText, context) }
                    )
                }
        val followedSummariesDeferred =
                if (includeFollowedSummaries) {
                    async {
                        execute(
                                emptyReason = EmptyReason.NoInboxItems,
                                block = { api.followedThreads(currentSlockApiPriority(), MESSAGE_PAGE_LIMIT) },
                                emptyWhen = { false },
                                source = "${source}_followed_summaries",
                                warningLogger = warningLogger,
                                mapper = { bodyText -> parseThreadListSnapshotFromBody(bodyText, context) }
                        )
                    }
                } else {
                    null
                }
        val messagesResult = messagesDeferred.await()
        val threadSummaries = threadSummariesDeferred.await()
        val followedSummaries = followedSummariesDeferred?.await()
        if (messagesResult !is DataResult.Content) {
            messagesResult
        } else {
            val channelThreads =
                    if (threadSummaries is DataResult.Content) threadSummaries.data.threads
                    else emptyList()
            val followedForChannel =
                    if (followedSummaries is DataResult.Content)
                            followedSummaries.data.threads.filter {
                                it.parentChannelId?.value == threadId.value
                            }
                    else emptyList()
            val mergedThreads =
                    (channelThreads + followedForChannel).distinctBy { it.key }
            if (threadSummaries is DataResult.Error && followedSummaries is DataResult.Error) {
                warningLogger(
                        "thread_summaries_error source=$source " +
                                "channel_code=${threadSummaries.error.code} " +
                                "followed_code=${followedSummaries.error.code}"
                )
                messagesResult
            } else {
                warningLogger(
                        "thread_summaries_loaded source=$source " +
                                "channel=${channelThreads.size} followed_filtered=${followedForChannel.size} " +
                                "merged=${mergedThreads.size} " +
                                "candidateIds=${mergedThreads.take(3).joinToString { t ->
                                    "${t.channelId.value.take(8)}/" +
                                            "${t.threadChannelId?.value?.take(8) ?: "null"}/" +
                                            "${t.parentMessageId?.value?.take(8) ?: "null"}"
                                }}"
                )
                DataResult.Content(
                        messagesResult.data.withReplySummaries(mergedThreads, warningLogger)
                )
            }
        }
    }

    private suspend fun threadAction(
            threadChannelId: ChannelId,
            source: String,
            block: suspend () -> ApiResponse
    ): DataResult<ThreadActionResult> =
            threadAction(
                    fallbackThreadChannelId = threadChannelId,
                    fallbackParentMessageId = null,
                    source = source,
                    block = block
            )

    private suspend fun threadAction(
            fallbackThreadChannelId: ChannelId?,
            fallbackParentMessageId: MessageId?,
            source: String,
            block: suspend () -> ApiResponse
    ): DataResult<ThreadActionResult> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoInboxItems,
                block = block,
                source = source,
                warningLogger = warningLogger,
                mapper = { bodyText ->
                    val result =
                            ai.slock.compose.data.parseSharedThreadActionResult(
                                    bodyText = bodyText,
                                    fallbackThreadChannelId = fallbackThreadChannelId,
                                    fallbackParentMessageId = fallbackParentMessageId
                            ) ?: error("thread action response missing thread channel id")
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockThreadReplies",
                            "thread_action_result source=$source " +
                                    "threadChannelId=${result.threadChannelId.value} " +
                                    "fallback=${fallbackThreadChannelId?.value.orEmpty()} " +
                                    "parentMessageId=${fallbackParentMessageId?.value.orEmpty()} " +
                                    "replyCount=${result.replyCount}"
                    )
                    result
                }
        )
    }

    private suspend fun currentContext(): NetworkServerContext? {
        val session = store.readSession() ?: return null
        val selectedId = session.currentServerId ?: return null
        return session.toNetworkServerContext(ServerId(selectedId))
    }

    private companion object {
        const val MESSAGE_PAGE_LIMIT = 50
        const val SYNC_PAGE_LIMIT = 200
    }
}

class RealServerUnreadRepository(
        private val store: AuthSessionStore,
        private val api: SlockApi,
        private val throttleWindowMillis: Long = 5_000L,
        private val clockMillis: () -> Long = { System.currentTimeMillis() },
        private val logger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d("SlockUnread", message)
        },
        private val warningLogger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w("SlockUnread", message)
        }
) : ServerUnreadRepository {
    private val throttleMutex = Mutex()
    private var cachedSummary: ThrottledUnreadSummary? = null

    override suspend fun getUnreadSummary(
            serverId: ServerId
    ): DataResult<ServerUnreadSummarySnapshot> {
        val context = store.readSession()?.toNetworkServerContext(serverId)
        if (context == null) {
            warningLogger(
                    "repository_skip reason=auth_or_server_mismatch serverId=${serverId.value}"
            )
            return authRequired()
        }
        return throttleMutex.withLock {
            val now = clockMillis()
            val cached = cachedSummary
            if (cached != null &&
                            cached.serverId == serverId &&
                            now - cached.loadedAtMillis < throttleWindowMillis
            ) {
                logger(
                        "repository_throttle_hit serverId=${serverId.value} ageMs=${now - cached.loadedAtMillis}"
                )
                return@withLock cached.result
            }

            logger("repository_fetch_start serverId=${serverId.value}")
            val result =
                    execute(
                            emptyReason = EmptyReason.NoInboxItems,
                            block = { api.serverUnreadSummary(currentSlockApiPriority()) },
                            mapper = { bodyText ->
                                ai.slock.compose.data.parseServerUnreadSummarySnapshot(
                                        bodyText,
                                        context.server
                                ) ?: throw IllegalStateException(
                                        "Server unread summary response missing servers"
                                )
                            }
                    )
            when (result) {
                is DataResult.Content -> {
                    logger(
                            "repository_fetch_success serverId=${serverId.value} total=${result.data.totalUnreadCount} selectedServerUnread=${result.data.selectedServerUnreadCount}"
                    )
                }
                is DataResult.Empty -> {
                    logger(
                            "repository_fetch_empty serverId=${serverId.value} empty=${result.reason}"
                    )
                }
                is DataResult.Error -> {
                    warningLogger(
                            "repository_fetch_error serverId=${serverId.value} code=${result.error.code}"
                    )
                }
                is DataResult.NoPermission -> {
                    warningLogger(
                            "repository_fetch_no_permission serverId=${serverId.value} code=${result.blocker.code}"
                    )
                }
            }
            cachedSummary =
                    ThrottledUnreadSummary(
                            serverId = serverId,
                            loadedAtMillis = now,
                            result = result
                    )
            result
        }
    }

    private data class ThrottledUnreadSummary(
            val serverId: ServerId,
            val loadedAtMillis: Long,
            val result: DataResult<ServerUnreadSummarySnapshot>
    )
}

object RealSlockApiFactory {
    fun create(
            store: AuthSessionStore,
            baseUrl: String = KtorAuthRepository.BASE_URL,
            tokenRefresher: AuthTokenRefresher =
                    AuthTokenRefresher(
                            store = store,
                            api = KtorAuthRepository.createAuthApi(baseUrl)
                    )
    ): SlockApi =
        ComposeSlockApiAdapter(
                store = store,
                baseUrl = baseUrl,
                tokenRefresher = tokenRefresher
        )
}

private data class NetworkServerContext(val session: AuthSession, val server: ServerSummary)

private suspend fun AuthSessionStore.toNetworkServerContext(
        serverId: ServerId
): NetworkServerContext? = readSession()?.toNetworkServerContext(serverId)

private fun AuthSession.toNetworkServerContext(serverId: ServerId): NetworkServerContext? {
    val selectedId = currentServerId ?: return null
    if (selectedId != serverId.value) return null
    val server = servers.firstOrNull { it.id == selectedId }?.toServerSummary() ?: return null
    return NetworkServerContext(session = this, server = server)
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

private fun List<ConversationSummary>.dedupeHomeConversations(): List<ConversationSummary> {
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

private fun ConversationSummary.mergeConversationSummary(
        incoming: ConversationSummary
): ConversationSummary =
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

private fun String.isGenericConversationName(conversation: ConversationSummary): Boolean =
        isBlank() || this == conversation.threadId.value ||
                this == conversation.channelId?.value

private fun ConversationSummary.withMergedMemberCount(incoming: Int?): ConversationSummary =
        copy(memberCount = incoming ?: memberCount)

private fun ConversationSummary.withDirectMessageFallback(
        previous: ConversationSummary
): ConversationSummary =
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

private fun parseTaskSnapshotFromBody(bodyText: String, filter: TaskFilter, currentUserId: String): TaskSnapshot {
    val payload = ai.slock.compose.data.parseSharedServerTasksPayload(bodyText)
            ?: ai.slock.compose.data.SharedTasksPayload(emptyList())
    return TaskSnapshot(tasks = payload.tasks, summaryCounts = taskSummaryCounts(payload.tasks))
            .filteredByTaskFilter(filter, currentUserId)
}

private fun parseTaskActionFromBody(bodyText: String): TaskSummary? =
        ai.slock.compose.data.parseSharedTaskActionPayload(bodyText)

private fun parseThreadListSnapshotFromBody(
        bodyText: String,
        context: NetworkServerContext
): ThreadListSnapshot {
    val parsed =
            ai.slock.compose.data.parseSharedThreadListItems(bodyText)
                    ?: throw IllegalStateException("Thread list response missing threads")
    return ThreadListSnapshot(
            server = context.server,
            threads = parsed.items,
            totalCount = parsed.totalCount,
            hasMore = parsed.hasMore,
            nextOffset = parsed.nextOffset
    )
}

private fun TaskSnapshot.filteredByTaskFilter(filter: TaskFilter, currentUserId: String): TaskSnapshot {
    val filteredTasks =
            tasks.filter { task ->
                (filter.statuses.isEmpty() || task.status in filter.statuses) &&
                        (!filter.assignedToMeOnly || task.assignee?.id?.value == currentUserId)
            }
    return copy(tasks = filteredTasks, summaryCounts = taskSummaryCounts(filteredTasks))
}

private fun taskSummaryCounts(tasks: List<TaskSummary>): Map<TaskStatus, Int> =
        TaskStatus.entries.associateWith { status -> tasks.count { it.status == status } }


private fun buildSharedThreadSnapshot(
        payload: ai.slock.compose.data.SharedMessagesPayload,
        threadId: ThreadId,
        canLoadMoreBefore: Boolean = false,
        canLoadMoreAfter: Boolean = false,
        highlightedMessageId: MessageId? = null
): ThreadSnapshot {
    val highlighted =
            highlightedMessageId?.let { id ->
                payload.messages.firstOrNull { it.id == id }
            }
    val baseConversation = buildDefaultConversation(threadId, threadId.value)
    val conversation =
            if (highlighted != null) {
                baseConversation.withThreadAnchor(
                        parentMessage = highlighted,
                        streamChannelId = ChannelId(threadId.value)
                )
            } else {
                baseConversation
            }
    return ThreadSnapshot(
            conversation = conversation,
            messages = payload.messages,
            parentMessage = highlighted,
            canLoadMoreBefore = canLoadMoreBefore,
            canLoadMoreAfter = canLoadMoreAfter,
            historyLimited = payload.historyLimited,
            highlightedMessageId = highlightedMessageId
    )
}

private fun buildDefaultConversation(threadId: ThreadId, name: String): ConversationSummary {
    val parentChannelId = threadId.parentChannelIdOrNull()
    val parentMessageId = threadId.parentMessageIdOrNull()
    return ConversationSummary(
            threadId = threadId,
            channelId = parentChannelId ?: ChannelId(threadId.value),
            thread =
                    if (parentChannelId != null && parentMessageId != null) {
                        ThreadConversation(
                                parentChannelId = parentChannelId,
                                parentMessageId = parentMessageId,
                                streamChannelId = null
                        )
                    } else null,
            kind =
                    if (parentChannelId != null && parentMessageId != null) {
                        ConversationKind.Thread
                    } else {
                        ConversationKind.Channel
                    },
            name = name
    )
}

private fun ThreadSnapshot.withReplySummaries(
        threads: List<InboxItem>,
        logger: (String) -> Unit
): ThreadSnapshot {
    if (threads.isEmpty()) return this
    val summariesByParentMessageId =
            threads
                    .asSequence()
                    .mapNotNull { thread -> thread.parentMessageId?.let { it.value to thread } }
                    .toMap()
    val summariesByThreadChannelId =
            threads
                    .asSequence()
                    .flatMap { thread ->
                        sequenceOf(
                                thread.threadChannelId?.value?.let { it to thread },
                                thread.channelId.value to thread
                        ).filterNotNull()
                    }
                    .toMap()
    var joinMissCount = 0
    var firstJoinMissMessageId: MessageId? = null
    var firstJoinMissThreadChannelId: ChannelId? = null
    val updatedMessages =
            messages.map { message ->
                val summary =
                        summariesByParentMessageId[message.id.value]
                                ?: message.threadChannelId?.value?.let(summariesByThreadChannelId::get)
                                ?: threads.firstOrNull { thread ->
                                    thread.matchesParentMessage(message.id)
                                }
                if (summary == null) {
                    if (message.replyCount == 0) {
                        joinMissCount += 1
                        if (firstJoinMissMessageId == null) {
                            firstJoinMissMessageId = message.id
                            firstJoinMissThreadChannelId = message.threadChannelId
                        }
                    }
                    return@map message
                }
                val summaryThreadChannelId = summary.threadChannelId ?: summary.channelId
                logger(
                        "summary_join parentMessageId=${message.id.value} " +
                                "channelId=${message.channelId?.value.orEmpty()} " +
                                "threadChannelId=${summaryThreadChannelId.value} " +
                                "replyCount=${maxOf(message.replyCount, summary.replyCount)} " +
                                "unreadCount=${summary.unreadCount} " +
                                "source=channel_threads"
                )
                message.copy(
                        threadChannelId = message.threadChannelId ?: summaryThreadChannelId,
                        replyCount = maxOf(message.replyCount, summary.replyCount)
                ).withUnreadReplyCount(summary.unreadCount)
            }
    if (joinMissCount > 0) {
        val candidates =
                threads.take(2).joinToString { thread ->
                    "ch=${thread.channelId.value.take(8)} " +
                            "pMsgId=${thread.parentMessageId?.value?.take(8) ?: "null"}"
                }
        logger(
                "summary_join_miss_count count=$joinMissCount " +
                        "firstMsgId=${firstJoinMissMessageId?.value?.take(8).orEmpty()} " +
                        "firstThreadCh=${firstJoinMissThreadChannelId?.value?.take(8) ?: "null"} " +
                        "threadCandidates=[$candidates] source=channel_threads"
        )
    }
    return copy(messages = updatedMessages)
}

private fun InboxItem.matchesParentMessage(messageId: MessageId): Boolean {
    val candidateIds =
            listOfNotNull(
                    parentMessageId?.value,
                    lastMessageId?.value,
                    latestActivityMessageId?.value,
                    parentMessagePreview?.extractSlockMessageReferenceId(),
                    latestActivityPreview?.extractSlockMessageReferenceId(),
                    lastMessagePreview?.extractSlockMessageReferenceId(),
                    threadChannelId?.value,
                    channelId.value,
                    key.removePrefix("thread:").takeIf { it != key }
            )
    return candidateIds.any { candidate -> messageId.value.matchesThreadSummaryId(candidate) }
}

private fun String.extractSlockMessageReferenceId(): String? =
        Regex("#[A-Za-z0-9_-]+:([A-Fa-f0-9]{8})(?:[A-Fa-f0-9-]{0,28})?")
                .find(this)
                ?.groupValues
                ?.getOrNull(1)

private fun String.matchesThreadSummaryId(candidate: String): Boolean {
    if (equals(candidate, ignoreCase = true)) return true
    val parentShortId = take(8)
    val candidateShortId = candidate.take(8)
    return parentShortId.length == 8 &&
            candidateShortId.length == 8 &&
            parentShortId.equals(candidateShortId, ignoreCase = true)
}



private fun mergeAgentActivityEntries(
        existing: List<AgentActivityEntry>,
        incoming: List<AgentActivityEntry>
): List<AgentActivityEntry> {
    val merged = LinkedHashMap<String, AgentActivityEntry>()
    for (entry in existing) {
        merged[entry.activityCacheKey] = entry
    }
    for (entry in incoming) {
        merged[entry.activityCacheKey] = entry
    }
    return merged.values.sortedWith(compareBy<AgentActivityEntry> { it.timestamp.orEmpty() }.thenBy { it.id })
}

private val AgentActivityEntry.activityCacheKey: String
    get() = id.ifBlank { listOf(type, timestamp.orEmpty(), body.orEmpty()).joinToString(":") }

private fun MemberProfile.withHumanProfile(profile: MemberProfile): MemberProfile {
    val mergedAvatarUrl = profile.avatarUrl ?: avatarUrl
    val mergedGravatarHash = profile.gravatarHash ?: gravatarHash
    return copy(
            displayName = profile.displayName.takeUnless { it.isBlank() || it == profile.id.value }
                            ?: displayName,
            handle = profile.handle.takeUnless { it.isBlank() || it == profile.id.value } ?: handle,
            kind = MemberKind.Human,
            description = profile.description ?: description,
            roleDescription = profile.roleDescription ?: roleDescription,
            avatarUrl = mergedAvatarUrl,
            gravatarHash = mergedGravatarHash,
            avatar = profile.avatar
                            ?: avatar ?: AvatarReference.fromWireValue(mergedAvatarUrl)
                                    ?: mergedGravatarHash?.toGravatarAvatarReference(),
            avatarLabel = profile.avatarLabel.takeIf { it.isNotBlank() } ?: avatarLabel,
            presence = profile.presence,
            permissionBadges =
                    if (profile.permissionBadges.isNotEmpty()) profile.permissionBadges
                    else permissionBadges
    )
}

private fun String.toMemberComputerBadge(): String = "$MemberComputerBadgePrefix$this"
private fun String.toMemberComputerIdBadge(): String = "$MemberComputerIdBadgePrefix$this"
private fun String.memberComputerLabelOrNull(): String? =
        removePrefix(MemberComputerBadgePrefix)
                .takeIf { startsWith(MemberComputerBadgePrefix) && it.isNotBlank() }
private fun String.memberComputerIdOrNull(): String? =
        removePrefix(MemberComputerIdBadgePrefix)
                .takeIf { startsWith(MemberComputerIdBadgePrefix) && it.isNotBlank() }

private const val MemberComputerBadgePrefix = "computer:"
private const val MemberComputerIdBadgePrefix = "computer-id:"

private fun List<MemberProfile>.resolveComputerLabels(
        machineNames: Map<String, String>
): List<MemberProfile> = map { it.resolveComputerLabel(machineNames) }

private fun MemberProfile.resolveComputerLabel(machineNames: Map<String, String>): MemberProfile {
    val badgesWithoutIds =
            permissionBadges.filterNot { it.startsWith(MemberComputerIdBadgePrefix) }
    val currentComputerLabel =
            permissionBadges.firstNotNullOfOrNull { it.memberComputerLabelOrNull() }
    if (!currentComputerLabel.isNullOrBlank()) {
        return if (badgesWithoutIds == permissionBadges) this else copy(permissionBadges = badgesWithoutIds)
    }
    val machineId =
            permissionBadges.firstNotNullOfOrNull { it.memberComputerIdOrNull() } ?: return this
    val resolvedName = machineNames[machineId]?.takeIf { it.isNotBlank() }
    return if (resolvedName != null) {
        copy(permissionBadges = badgesWithoutIds + resolvedName.toMemberComputerBadge())
    } else if (badgesWithoutIds == permissionBadges) {
        this
    } else {
        copy(permissionBadges = badgesWithoutIds)
    }
}

private val MemberProfile.memberKey: String
    get() = "${kind.name}:${id.value}"

private val MemberKind.channelMemberPathSegment: String
    get() =
            when (this) {
                MemberKind.Agent -> "agent"
                MemberKind.Human -> "user"
                MemberKind.System -> "system"
            }

private fun DataResult<MemberSnapshot>.logMemberCounts(
        source: String,
        serverId: String,
        logger: (String) -> Unit
) {
    val members = (this as? DataResult.Content)?.data?.members.orEmpty()
    logger(
            "members_count source=$source serverId=$serverId result=${memberResultName()} " +
                    "agentsCount=${members.count { it.kind == MemberKind.Agent }} humansCount=${members.count { it.kind == MemberKind.Human }}"
    )
}

private fun DataResult<ChannelMemberSnapshot>.logChannelMemberCounts(
        source: String,
        channelId: String,
        logger: (String) -> Unit
) {
    val members = (this as? DataResult.Content)?.data?.members.orEmpty()
    logger(
            "members_count source=$source channelId=$channelId result=${memberResultName()} " +
                    "agentsCount=${members.count { it.kind == MemberKind.Agent }} humansCount=${members.count { it.kind == MemberKind.Human }}"
    )
}

private fun DataResult<*>.memberResultName(): String =
        when (this) {
            is DataResult.Content -> "content"
            is DataResult.Empty -> "empty"
            is DataResult.Error -> "error:${error.code}"
            is DataResult.NoPermission -> "noPermission:${blocker.code}"
        }

private fun String?.toPresence(fallback: Presence = Presence.Offline): Presence =
        toPresenceOrNull() ?: fallback

private fun String?.toPresenceOrNull(): Presence? =
        toAgentStatusOrNull()?.presence
                ?: when (this?.lowercase()?.replace('-', '_')) {
            "active", "online", "working", "available", "connected" -> Presence.Active
            "away", "idle", "busy" -> Presence.Away
            "offline", "inactive", "disconnected", "not_connected", "notconnected" ->
                    Presence.Offline
            else -> null
        }

private fun String?.toAgentStatusOrNull(): AgentStatus? = AgentStatus.fromWireValue(this)




private fun ConversationSummary.withThreadAnchor(
        parentMessage: Message?,
        streamChannelId: ChannelId,
        parentChannelName: String? = null
): ConversationSummary {
    fun ConversationSummary.withParentNameFallback(): ConversationSummary {
        val fallbackName =
                parentChannelName?.takeIf { name.isBlank() || name == threadId.value || name == channelId?.value }
                        ?: return this
        return copy(name = fallbackName)
    }
    if (kind == ConversationKind.Thread && thread?.streamChannelId == null) {
        return copy(
                thread = thread?.copy(streamChannelId = streamChannelId)
        ).withParentNameFallback()
    }
    val parentChannelId = parentMessage?.channelId ?: return withParentNameFallback()
    return copy(
            threadId = threadIdentity(parentChannelId, parentMessage.id),
            channelId = parentChannelId,
            thread =
                    ThreadConversation(
                            parentChannelId = parentChannelId,
                            parentMessageId = parentMessage.id,
                            streamChannelId = parentMessage.threadChannelId ?: streamChannelId
                    ),
            kind = ConversationKind.Thread
    ).withParentNameFallback()
}

private fun ChannelId.toThreadActionBody(): RequestBody =
        mapOf("threadChannelId" to value, "channelId" to value).toJsonBody()

private fun MessageId.toParentMessageThreadBody(): RequestBody =
        mapOf("parentMessageId" to value).toJsonBody()

private fun sendMessageBody(
        channelId: ChannelId,
        body: String,
        attachmentIds: List<AttachmentId>,
        asTask: Boolean
): RequestBody =
        mapOf(
                        "channelId" to channelId.value,
                        "content" to body,
                        "attachmentIds" to attachmentIds.map { it.value },
                        "asTask" to asTask
                )
                .toJsonBody()

private fun AttachmentUploadFile.toMultipartPart(): MultipartBody.Part {
    val body = bytes.toRequestBody((mimeType ?: "application/octet-stream").toMediaTypeOrNull())
    return MultipartBody.Part.createFormData("files", fileName, body)
}

private fun Map<String, Any?>.toJsonBody(): RequestBody =
        Gson().toJson(this).toRequestBody(jsonMediaType)

private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
private val textMediaType = "text/plain; charset=utf-8".toMediaType()

private fun TaskStatus.toApiValue(): String =
        when (this) {
            TaskStatus.Todo -> "todo"
            TaskStatus.InProgress -> "in_progress"
            TaskStatus.InReview -> "in_review"
            TaskStatus.Done -> "done"
            TaskStatus.Closed -> "closed"
        }
