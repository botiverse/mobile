package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.AuthHeaderInterceptor
import ai.slock.android.core.data.auth.AuthRefreshInterceptor
import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.android.core.data.auth.RetrofitAuthRepository
import ai.slock.android.core.data.repository.AddChannelMemberRequest
import ai.slock.android.core.data.repository.AgentActivityEntry
import ai.slock.android.core.data.repository.AgentActivitySnapshot
import ai.slock.android.core.data.repository.AgentDetailSnapshot
import ai.slock.android.core.data.repository.AgentDmSnapshot
import ai.slock.android.core.data.repository.AgentDmSummary
import ai.slock.android.core.data.repository.AgentReminderSnapshot
import ai.slock.android.core.data.repository.AgentReminderSummary
import ai.slock.android.core.data.repository.AttachmentOpenTarget
import ai.slock.android.core.data.repository.AttachmentUploadFile
import ai.slock.android.core.data.repository.AttachmentUploadRequest
import ai.slock.android.core.data.repository.AttachmentUploadResult
import ai.slock.android.core.data.repository.ChannelJoinResult
import ai.slock.android.core.data.repository.ChannelMemberActionResult
import ai.slock.android.core.data.repository.ChannelMemberSnapshot
import ai.slock.android.core.data.repository.ChannelRepository
import ai.slock.android.core.data.repository.ChannelSnapshot
import ai.slock.android.core.data.repository.ChannelUnreadSnapshot
import ai.slock.android.core.data.repository.ConversationRepository
import ai.slock.android.core.data.repository.CreateChannelRequest
import ai.slock.android.core.data.repository.CreateChannelResult
import ai.slock.android.core.data.repository.DataResult
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
import ai.slock.android.core.data.repository.SavedSnapshot
import ai.slock.android.core.data.repository.SearchRepository
import ai.slock.android.core.data.repository.SearchSnapshot
import ai.slock.android.core.data.repository.SendMessageResult
import ai.slock.android.core.data.repository.ServerUnreadRepository
import ai.slock.android.core.data.repository.ServerUnreadSummary
import ai.slock.android.core.data.repository.ServerUnreadSummarySnapshot
import ai.slock.android.core.data.repository.TaskActionResult
import ai.slock.android.core.data.repository.TaskFilter
import ai.slock.android.core.data.repository.TaskRepository
import ai.slock.android.core.data.repository.TaskSnapshot
import ai.slock.android.core.data.repository.ThreadActionResult
import ai.slock.android.core.data.repository.ThreadListSnapshot
import ai.slock.android.core.data.repository.ThreadSnapshot
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
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.toGravatarAvatarReference
import ai.slock.android.core.state.EmptyReason
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit

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

    override suspend fun getHome(serverId: ServerId): DataResult<HomeSnapshot> {
        val context = serverContext(serverId) ?: return authRequired()
        val channels = fetchChannels(context)
        val directMessages = fetchDirectMessages(context)

        if (channels is DataResult.Error || channels is DataResult.NoPermission)
                return channels.mapError()
        if (directMessages is DataResult.Error || directMessages is DataResult.NoPermission)
                return directMessages.mapError()
        val channelItems = (channels as? DataResult.Content)?.data?.channels.orEmpty()
        val directMessageItems =
                (directMessages as? DataResult.Content)?.data?.directMessages.orEmpty()
        if (channelItems.isEmpty() && directMessageItems.isEmpty())
                return DataResult.Empty(EmptyReason.NoChannels)

        val allUnread =
                channelItems.sumOf { it.unreadCount } + directMessageItems.sumOf { it.unreadCount }
        return DataResult.Content(
                HomeSnapshot(
                        server = context.server,
                        currentMember = context.session.user.toMemberProfile(),
                        channels = channelItems,
                        directMessages = directMessageItems,
                        unreadTotal = allUnread
                )
        )
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
                            api.createChannel(
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
                        mapper = { json ->
                            val channel =
                                    json.unwrap("channel", "data")
                                            .toConversation(ConversationKind.Channel)
                            CreateChannelResult(channel = channel)
                        }
                )
        if (createResult !is DataResult.Content) return createResult
        val channelId =
                createResult.data.channel.channelId
                        ?: ChannelId(createResult.data.channel.threadId.value)
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
        return DataResult.Content(
                createResult.data.copy(
                        addedAgentMemberIds = addedAgents,
                        addedUserMemberIds = addedUsers
                )
        )
    }

    override suspend fun joinChannel(channelId: ChannelId): DataResult<ChannelJoinResult> {
        currentContext() ?: return authRequired()
        if (channelId.value.isBlank()) return DataResult.Empty(EmptyReason.NoChannels)
        return execute(
                emptyReason = EmptyReason.NoChannels,
                block = { api.joinChannel(channelId.value) },
                source = "channel_join",
                warningLogger = warningLogger,
                mapper = { json ->
                    val channel = json.joinedChannelOrNull(channelId)
                    ChannelJoinResult(
                            channelId = channelId,
                            joined = channel?.joined ?: true,
                            channel = channel
                    )
                }
        )
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
                        api.addChannelMember(
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
                    block = { api.channels() },
                    emptyWhen = { it.channels.isEmpty() },
                    source = "home_channels",
                    warningLogger = warningLogger,
                    mapper = { json ->
                        val channelItems =
                                json.homeConversationItems(
                                        source = "home_channels",
                                        names = arrayOf("channels", "items", "data"),
                                        warningLogger = warningLogger
                                )
                        ChannelSnapshot(
                                server = context.server,
                                channels =
                                        channelItems.mapNotNull { item ->
                                            val itemObject = item.asObjectOrNull()
                                            if (itemObject == null) {
                                                warningLogger(
                                                        "skip_item source=home_channels stage=item_map fieldPath=item reason=non_object"
                                                )
                                                null
                                            } else {
                                                itemObject.toConversationOrNull(
                                                        kind = ConversationKind.Channel,
                                                        source = "home_channels",
                                                        warningLogger = warningLogger
                                                )
                                            }
                                        }
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
                                    block = { api.directMessages() },
                                    emptyWhen = { it.directMessages.isEmpty() },
                                    source = "home_direct_messages",
                                    warningLogger = warningLogger,
                                    mapper = { json ->
                                        val directMessageItems =
                                                json.homeConversationItems(
                                                        source = "home_direct_messages",
                                                        names =
                                                                arrayOf(
                                                                        "directMessages",
                                                                        "dmChannels",
                                                                        "dms",
                                                                        "channels",
                                                                        "items",
                                                                        "data"
                                                                ),
                                                        warningLogger = warningLogger
                                                )
                                        DirectMessageSnapshot(
                                                server = context.server,
                                                directMessages =
                                                        directMessageItems.mapNotNull { item ->
                                                            val itemObject = item.asObjectOrNull()
                                                            if (itemObject == null) {
                                                                warningLogger(
                                                                        "skip_item source=home_direct_messages stage=item_map fieldPath=item reason=non_object"
                                                                )
                                                                null
                                                            } else {
                                                                itemObject.toConversationOrNull(
                                                                        kind =
                                                                                ConversationKind
                                                                                        .DirectMessage,
                                                                        source =
                                                                                "home_direct_messages",
                                                                        warningLogger =
                                                                                warningLogger
                                                                )
                                                            }
                                                        }
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
                            api.openDirectMessage(mapOf(fieldName to memberId.value).toJsonBody())
                        },
                        source = source,
                        warningLogger = warningLogger,
                        mapper = { json ->
                            val conversationRoot =
                                    json.firstObject("channel", "dm", "directMessage")
                                            ?: json.firstObject("data")
                                                    ?.firstObject("channel", "dm", "directMessage")
                                            ?: json.unwrap("data")
                            val conversation =
                                    conversationRoot
                                            .toConversation(
                                                    kind = ConversationKind.DirectMessage
                                            )
                            OpenDirectMessageResult(
                                    conversation = conversation,
                                    directMessages = emptyList()
                            )
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
                merged
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
                block = { api.channelUnreadCounts() },
                source = "read_unread_counts",
                warningLogger = warningLogger,
                mapper = { json ->
                    val unreadCounts = json.toChannelUnreadCounts()
                    ChannelUnreadSnapshot(
                            server = context.server.copy(unreadCount = unreadCounts.values.sum()),
                            unreadCounts = unreadCounts
                    )
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
                    api.markChannelReadToSeq(channelId.value, mapOf("seq" to seq).toJsonBody())
                },
                source = "read_channel_seq",
                warningLogger = warningLogger,
                mapper = { json ->
                    json.toReadReceiptResult(fallbackChannelId = channelId, fallbackSeq = seq)
                }
        )
    }

    override suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = { api.markChannelReadAll(channelId.value) },
                source = "read_channel_all",
                warningLogger = warningLogger,
                mapper = { json -> json.toReadReceiptResult(fallbackChannelId = channelId) }
        )
    }

    override suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = { api.markChannelUnread(channelId.value) },
                source = "read_channel_unread",
                warningLogger = warningLogger,
                mapper = { json -> json.toReadReceiptResult(fallbackChannelId = channelId) }
        )
    }

    override suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult> {
        store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoInboxItems,
                block = { api.markInboxReadAll() },
                source = "read_inbox_all",
                warningLogger = warningLogger,
                mapper = { json -> json.toReadReceiptResult() }
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
                block = { api.serverTasks() },
                emptyWhen = { it.tasks.isEmpty() },
                source = "tasks_server",
                warningLogger = warningLogger,
                mapper = { json ->
                    json.toTaskSnapshot()
                            .filteredBy(filter, currentUserId = context.session.user.id)
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
                block = { api.channelTasks(channelId.value) },
                emptyWhen = { it.tasks.isEmpty() },
                source = "tasks_channel",
                warningLogger = warningLogger,
                mapper = { json ->
                    json.toTaskSnapshot()
                            .filteredBy(filter, currentUserId = context.session.user.id)
                }
        )
    }

    override suspend fun claimTask(taskId: TaskId): DataResult<TaskActionResult> {
        currentContext() ?: return authRequired()
        return execute(
            emptyReason = EmptyReason.NoTasks,
            block = { api.claimTask(taskId.value) },
                source = "tasks_claim",
                warningLogger = warningLogger,
                mapper = { json ->
                    val task =
                            json.unwrap("task", "data").toTaskSummary()
                                    ?: throw IllegalStateException(
                                            "Task action response missing task"
                                    )
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
            block = { api.updateTaskStatus(taskId.value, mapOf("status" to status.toApiValue()).toJsonBody()) },
            source = "tasks_status",
            warningLogger = warningLogger,
            mapper = { json ->
                val task = json.unwrap("task", "data").toTaskSummary()
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

    override suspend fun getMembers(serverId: ServerId): DataResult<MemberSnapshot> {
        val context = store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        val result =
                execute(
                        emptyReason = EmptyReason.NoMembers,
                        block = { api.serverMembers(serverId.value) },
                        source = "server_members",
                        warningLogger = warningLogger,
                        mapper = { json ->
                            MemberSnapshot(server = context.server, members = json.memberItems())
                        }
                )
        val agentResult =
                execute(
                        emptyReason = EmptyReason.NoMembers,
                        block = { api.settingsAgents() },
                        source = "server_agents",
                        warningLogger = warningLogger,
                        mapper = { json -> json.agentMemberItems() }
                )
        val mergedResult =
                when (result) {
                    is DataResult.Content -> {
                        val agents = (agentResult as? DataResult.Content)?.data.orEmpty()
                        val humans =
                                enrichHumanProfiles(
                                        result.data.members,
                                        source = "server_members_profile"
                                )
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

    override suspend fun getMemberProfile(memberId: MemberId): DataResult<MemberProfile> {
        currentContext() ?: return authRequired()
        return getHumanProfile(memberId, source = "member_profile")
    }

    override suspend fun getAgentDetail(agentId: MemberId): DataResult<AgentDetailSnapshot> {
        currentContext() ?: return authRequired()
        val directMessages = getAgentDirectMessages(agentId)
        val reminders = getAgentReminders(agentId)
        val activity = getAgentActivity(agentId)
        return when {
            directMessages is DataResult.Content &&
                    reminders is DataResult.Content &&
                    activity is DataResult.Content ->
                    DataResult.Content(
                            AgentDetailSnapshot(
                                    agentId = agentId,
                                    directMessages = directMessages.data.directMessages,
                                    reminders = reminders.data.reminders,
                                    activity = activity.data.activity
                            )
                    )
            directMessages is DataResult.NoPermission -> directMessages
            reminders is DataResult.NoPermission -> reminders
            activity is DataResult.NoPermission -> activity
            directMessages is DataResult.Error -> directMessages
            reminders is DataResult.Error -> reminders
            activity is DataResult.Error -> activity
            directMessages is DataResult.Empty &&
                    reminders is DataResult.Empty &&
                    activity is DataResult.Empty -> DataResult.Empty(EmptyReason.NoMembers)
            else -> DataResult.Empty(EmptyReason.NoMembers)
        }
    }

    override suspend fun getAgentDirectMessages(agentId: MemberId): DataResult<AgentDmSnapshot> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMembers,
                block = { api.agentDirectMessages(agentId.value) },
                source = "agent_direct_messages",
                warningLogger = warningLogger,
                mapper = { json ->
                    val items =
                            json.items("directMessages", "dms", "items", "data").mapNotNull {
                                it.asObjectOrNull()?.toAgentDmSummary()
                            }
                    AgentDmSnapshot(agentId = agentId, directMessages = items)
                }
        )
    }

    override suspend fun getAgentReminders(agentId: MemberId): DataResult<AgentReminderSnapshot> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMembers,
                block = { api.reminders(agentId.value) },
                source = "agent_reminders",
                warningLogger = warningLogger,
                mapper = { json ->
                    val items =
                            json.items("reminders", "items", "data").mapNotNull {
                                it.asObjectOrNull()?.toAgentReminderSummary()
                            }
                    AgentReminderSnapshot(agentId = agentId, reminders = items)
                }
        )
    }

    override suspend fun getAgentActivity(agentId: MemberId): DataResult<AgentActivitySnapshot> {
        currentContext() ?: return authRequired()
        val logResult =
                execute(
                        emptyReason = EmptyReason.NoMembers,
                        block = { api.agentActivityLog(agentId.value, limit = 50) },
                        source = "agent_activity",
                        warningLogger = warningLogger,
                        mapper = { json ->
                            val items =
                                    json.items(
                                                    "activityLog",
                                                    "activity",
                                                    "trajectoryLogs",
                                                    "logs",
                                                    "items",
                                                    "entries",
                                                    "data"
                                            )
                                            .mapNotNull {
                                                it.asObjectOrNull()?.toAgentActivityEntry()
                                            }
                            logger(
                                    "agent_activity_log_fetch agentId=${agentId.value} mappedCount=${items.size}"
                            )
                            AgentActivitySnapshot(agentId = agentId, activity = items)
                        }
                )
        val fallbackEntries = fetchAgentCurrentActivityFallback(agentId)
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

    override suspend fun getChannelMembers(
            channelId: ChannelId
    ): DataResult<ChannelMemberSnapshot> {
        val context = currentContext() ?: return authRequired()
        val result =
                execute(
                        emptyReason = EmptyReason.NoMembers,
                        block = { api.channelMembers(channelId.value) },
                        emptyWhen = { it.members.isEmpty() },
                        source = "channel_members",
                        warningLogger = warningLogger,
                        mapper = { json ->
                            val root = json.unwrap("data", "channel")
                            ChannelMemberSnapshot(
                                    channelId = channelId,
                                    members = json.memberItems(),
                                    canAddMembers =
                                            root.boolean(
                                                    "canAddMembers",
                                                    "canAddMember",
                                                    "canInviteMember"
                                            )
                                                    ?: true
                            )
                        }
                )
        val enrichedResult =
                when (result) {
                    is DataResult.Content ->
                            DataResult.Content(
                                    result.data.copy(
                                            members =
                                                    enrichHumanProfiles(
                                                            result.data.members
                                                                    .withCurrentHumanMember(
                                                                            context.session.user
                                                                                    .toMemberProfile()
                                                                    ),
                                                            source = "channel_members_profile"
                                                    )
                                    )
                            )
                    else -> result
                }
        enrichedResult.logChannelMemberCounts(
                source = "channel_members",
                channelId = channelId.value,
                logger = logger
        )
        return enrichedResult
    }

    override suspend fun addChannelMember(
            request: AddChannelMemberRequest
    ): DataResult<ChannelMemberActionResult> {
        currentContext() ?: return authRequired()
        val fieldName = if (request.kind == MemberKind.Agent) "agentId" else "userId"
        return execute(
                emptyReason = EmptyReason.NoMembers,
                block = {
                    api.addChannelMember(
                            request.channelId.value,
                            mapOf(fieldName to request.memberId.value).toJsonBody()
                    )
                },
                source = "channel_member_add",
                warningLogger = warningLogger,
                mapper = { json ->
                    val member =
                            json.firstObject("member", "user", "agent", "data")
                                    ?.toChannelMemberProfile()
                                    ?: json.toChannelMemberProfile()
                    ChannelMemberActionResult(
                            channelId = request.channelId,
                            memberId = member?.id ?: request.memberId,
                            member = member,
                            added = json.boolean("added", "ok", "success") ?: true
                    )
                }
        )
    }

    override suspend fun removeChannelMember(
            request: RemoveChannelMemberRequest
    ): DataResult<ChannelMemberActionResult> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMembers,
                block = {
                    api.removeChannelMember(
                            channelId = request.channelId.value,
                            kind = request.kind.channelMemberPathSegment,
                            memberId = request.memberId.value
                    )
                },
                source = "channel_member_remove",
                warningLogger = warningLogger,
                mapper = { json ->
                    val member =
                            json.firstObject("member", "user", "agent", "data")
                                    ?.toChannelMemberProfile()
                                    ?: json.toChannelMemberProfile()
                    ChannelMemberActionResult(
                            channelId = request.channelId,
                            memberId = member?.id ?: request.memberId,
                            member = member,
                            added = false
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
            agentId: MemberId
    ): List<AgentActivityEntry> {
        val result =
                execute<List<AgentActivityEntry>>(
                        emptyReason = EmptyReason.NoMembers,
                        block = { api.settingsAgents() },
                        source = "agent_activity_current_fallback",
                        warningLogger = warningLogger,
                        mapper = { json ->
                            val entries =
                                    json.agentObjects().mapNotNull { agent ->
                                        agent.takeIf {
                                            it.string("id", "agentId", "_id") == agentId.value
                                        }?.toCurrentAgentActivityEntry()
                                    }
                            logger(
                                    "agent_activity_fallback_fetch agentId=${agentId.value} mappedCount=${entries.size}"
                            )
                            entries
                        }
                )
        return (result as? DataResult.Content)?.data.orEmpty()
    }

    private suspend fun enrichHumanProfiles(
            members: List<MemberProfile>,
            source: String
    ): List<MemberProfile> {
        if (members.none { it.kind == MemberKind.Human }) return members
        return members.map { member ->
            if (member.kind != MemberKind.Human) {
                member
            } else {
                val profileResult = getHumanProfile(member.id, source = source)
                val profile = (profileResult as? DataResult.Content)?.data
                logger(
                        "human_profile_merge source=$source memberId=${member.id.value} " +
                                "result=${profileResult.memberResultName()}"
                )
                profile?.let { member.withHumanProfile(it) } ?: member
            }
        }
    }

    private fun List<MemberProfile>.withCurrentHumanMember(
            currentMember: MemberProfile
    ): List<MemberProfile> =
            if (any { it.memberKey == currentMember.memberKey }) {
                this
            } else {
                listOf(currentMember) + this
            }

    private suspend fun getHumanProfile(
            memberId: MemberId,
            source: String
    ): DataResult<MemberProfile> =
            execute(
                    emptyReason = EmptyReason.NoMembers,
                    block = { api.memberProfile(memberId.value) },
                    source = source,
                    warningLogger = warningLogger,
                    mapper = { json ->
                        val profile =
                                json.firstObject("profile", "member", "user", "data")
                                        ?.toMemberProfile()
                                        ?: json.toMemberProfile()
                                                ?: throw IllegalStateException(
                                                "Member profile response missing member"
                                        )
                        profile.copy(kind = MemberKind.Human)
                    }
            )
}

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
                block = { api.inbox(filter.wireValue, limit, offset) },
                emptyWhen = { it.items.isEmpty() },
                mapper = { json ->
                    val items =
                            json.items("items", "messages", "results", "data")
                                    .mapNotNull { it.asObjectOrNull()?.toInboxItem() }
                                    .distinctBy { it.key }
                    InboxSnapshot(
                            server = context.server,
                            items = items,
                            totalCount = json.int("totalCount") ?: items.size,
                            totalUnreadCount = json.int("totalUnreadCount")
                                            ?: items.sumOf { it.unreadCount },
                            hasMore = json.boolean("hasMore") ?: false,
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

    private suspend fun load(
            serverId: ServerId,
            limit: Int,
            offset: Int
    ): DataResult<SavedSnapshot> {
        val context = store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoInboxItems,
                block = { api.saved(limit, offset) },
                emptyWhen = { it.items.isEmpty() },
                mapper = { json ->
                    val items =
                            json.items("items", "messages", "saved")
                                    .map {
                                        it.asObject()
                                                .toInboxItem(defaultKind = InboxItemKind.Channel)
                                    }
                                    .distinctBy { it.key }
                    SavedSnapshot(
                            server = context.server,
                            items = items,
                            totalCount = json.int("totalCount") ?: items.size,
                            hasMore = json.boolean("hasMore") ?: false,
                            nextOffset = offset + items.size
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
            limit: Int
    ): DataResult<SearchSnapshot> {
        if (query.isBlank()) return DataResult.Empty(EmptyReason.NoSearchResults)
        val context = store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoSearchResults,
                block = { api.searchMessages(query.trim(), limit, offset) },
                emptyWhen = { it.items.isEmpty() },
                mapper = { json ->
                    val items =
                            json.items("items", "results", "messages")
                                    .map {
                                        it.asObject()
                                                .toInboxItem(defaultKind = InboxItemKind.Channel)
                                    }
                                    .distinctBy { it.key }
                    SearchSnapshot(
                            server = context.server,
                            query = query,
                            items = items,
                            totalCount = json.int("totalCount") ?: items.size,
                            hasMore = json.boolean("hasMore") ?: false,
                            nextOffset = offset + items.size
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
    override suspend fun getThread(threadId: ThreadId): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        return loadChannelMessages(
                context = context,
                threadId = threadId,
                block = { api.channelMessages(threadId.value, MESSAGE_PAGE_LIMIT) },
                source = "messages_channel"
        )
    }

    override suspend fun getMessageContext(messageId: MessageId): DataResult<ThreadSnapshot> {
        val context = currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = { api.messageContext(messageId.value) },
                emptyWhen = { it.messages.isEmpty() },
                source = "messages_context",
                warningLogger = warningLogger,
                mapper = { json ->
                    json.toThreadSnapshot(
                            context = context,
                            fallbackThreadId =
                                    ThreadId(
                                            json.string("threadId", "threadChannelId", "channelId")
                                                    ?: messageId.value
                                    ),
                            highlightedMessageId = messageId
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
                    api.channelMessages(threadId.value, MESSAGE_PAGE_LIMIT, beforeSeq = beforeSeq)
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
                    api.messageSync(
                            sinceSeq = afterSeq,
                            limit = SYNC_PAGE_LIMIT,
                            channelId = threadId.value
                    )
                },
                emptyWhen = { it.messages.isEmpty() },
                source = "messages_sync",
                warningLogger = warningLogger,
                mapper = { json ->
                    json.toThreadSnapshot(
                            context = context,
                            fallbackThreadId = threadId,
                            fallbackConversationName = "Messages"
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
                block = { api.channelThreads(channelId.value, limit) },
                emptyWhen = { it.threads.isEmpty() },
                source = "channel_threads",
                warningLogger = warningLogger,
                mapper = { json -> json.toThreadListSnapshot(context) }
        )
    }

    override suspend fun getFollowedThreads(
            serverId: ServerId,
            limit: Int
    ): DataResult<ThreadListSnapshot> {
        val context = store.readSession()?.toNetworkServerContext(serverId) ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoInboxItems,
                block = { api.followedThreads(limit) },
                emptyWhen = { it.threads.isEmpty() },
                source = "followed_threads",
                warningLogger = warningLogger,
                mapper = { json -> json.toThreadListSnapshot(context) }
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
                api.createOrOpenThread(channelId.value, parentMessageId.toParentMessageThreadBody())
            }

    override suspend fun followThread(parentMessageId: MessageId): DataResult<ThreadActionResult> =
            threadAction(
                    fallbackThreadChannelId = null,
                    fallbackParentMessageId = parentMessageId,
                    source = "thread_follow_parent"
            ) { api.followThread(parentMessageId.toParentMessageThreadBody()) }

    override suspend fun followThread(threadChannelId: ChannelId): DataResult<ThreadActionResult> =
            threadAction(threadChannelId, source = "thread_follow") {
                api.followThread(threadChannelId.toThreadActionBody())
            }

    override suspend fun unfollowThread(
            threadChannelId: ChannelId
    ): DataResult<ThreadActionResult> =
            threadAction(threadChannelId, source = "thread_unfollow") {
                api.unfollowThread(threadChannelId.toThreadActionBody())
            }

    override suspend fun markThreadDone(
            threadChannelId: ChannelId
    ): DataResult<ThreadActionResult> =
            threadAction(threadChannelId, source = "thread_done") {
                api.markThreadDone(threadChannelId.toThreadActionBody())
            }

    override suspend fun markThreadUndone(
            threadChannelId: ChannelId
    ): DataResult<ThreadActionResult> =
            threadAction(threadChannelId, source = "thread_undone") {
                api.markThreadUndone(threadChannelId.toThreadActionBody())
            }

    override suspend fun uploadAttachments(
            request: AttachmentUploadRequest
    ): DataResult<AttachmentUploadResult> {
        currentContext() ?: return authRequired()
        if (request.files.isEmpty()) return DataResult.Empty(EmptyReason.NoMessages)
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = {
                    api.uploadAttachments(
                            channelId = request.channelId.value.toRequestBody(textMediaType),
                            files = request.files.map { it.toMultipartPart() }
                    )
                },
                source = "attachments_upload",
                warningLogger = warningLogger,
                mapper = { json ->
                    AttachmentUploadResult(
                            attachments =
                                    json.items("attachments", "files", "items", "data").mapNotNull {
                                        it.asObjectOrNull()?.toAttachment()
                                    }
                    )
                },
                emptyWhen = { it.attachments.isEmpty() }
        )
    }

    override suspend fun getAttachmentOpenTarget(
            attachmentId: AttachmentId
    ): DataResult<AttachmentOpenTarget> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = { api.attachmentUrl(attachmentId.value) },
                source = "attachment_open_url",
                warningLogger = warningLogger,
                mapper = { json ->
                    AttachmentOpenTarget(
                            attachmentId = attachmentId,
                            url = json.string("url", "href", "downloadUrl") ?: "",
                            expiresAt = json.string("expiresAt", "expires_at")
                    )
                },
                emptyWhen = { it.url.isBlank() }
        )
    }

    override suspend fun sendMessage(
            threadId: ThreadId,
            body: String,
            attachmentIds: List<AttachmentId>,
            asTask: Boolean
    ): DataResult<SendMessageResult> {
        val context = currentContext() ?: return authRequired()
        if (body.isBlank() && attachmentIds.isEmpty())
                return DataResult.Empty(EmptyReason.NoMessages)
        return execute(
                emptyReason = EmptyReason.NoMessages,
                block = { api.sendMessage(sendMessageBody(threadId, body, attachmentIds, asTask)) },
                source = "messages_send",
                warningLogger = warningLogger,
                mapper = { json ->
                    val message =
                            json.unwrap("message", "data").toMessage(fallbackThreadId = threadId)
                                    ?: json.toMessage(fallbackThreadId = threadId)
                                            ?: Message(
                                            id =
                                                    MessageId(
                                                            json.string("id", "messageId")
                                                                    ?: "message-sent"
                                                    ),
                                            threadId = threadId,
                                            channelId = ChannelId(threadId.value),
                                            author = context.session.user.toMemberProfile(),
                                            timestampLabel =
                                                    json.string("createdAt", "timestampLabel")
                                                            ?: "",
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
            block: suspend () -> retrofit2.Response<okhttp3.ResponseBody>,
            source: String
    ): DataResult<ThreadSnapshot> = coroutineScope {
        val messagesDeferred =
                async {
                    execute(
                            emptyReason = EmptyReason.NoMessages,
                            block = block,
                            emptyWhen = { it.messages.isEmpty() },
                            source = source,
                            warningLogger = warningLogger,
                            mapper = { json ->
                                json.toThreadSnapshot(
                                        context = context,
                                        fallbackThreadId = threadId,
                                        fallbackConversationName = "Messages"
                                )
                            }
                    )
                }
        val threadSummariesDeferred =
                async {
                    execute(
                            emptyReason = EmptyReason.NoInboxItems,
                            block = { api.channelThreads(threadId.value, MESSAGE_PAGE_LIMIT) },
                            emptyWhen = { false },
                            source = "${source}_thread_summaries",
                            warningLogger = warningLogger,
                            mapper = { json -> json.toThreadListSnapshot(context) }
                    )
                }
        val followedSummariesDeferred =
                async {
                    execute(
                            emptyReason = EmptyReason.NoInboxItems,
                            block = { api.followedThreads(MESSAGE_PAGE_LIMIT) },
                            emptyWhen = { false },
                            source = "${source}_followed_summaries",
                            warningLogger = warningLogger,
                            mapper = { json -> json.toThreadListSnapshot(context) }
                    )
                }
        val messagesResult = messagesDeferred.await()
        val threadSummaries = threadSummariesDeferred.await()
        val followedSummaries = followedSummariesDeferred.await()
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
            block: suspend () -> retrofit2.Response<okhttp3.ResponseBody>
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
            block: suspend () -> retrofit2.Response<okhttp3.ResponseBody>
    ): DataResult<ThreadActionResult> {
        currentContext() ?: return authRequired()
        return execute(
                emptyReason = EmptyReason.NoInboxItems,
                block = block,
                source = source,
                warningLogger = warningLogger,
                mapper = { json ->
                    val unwrapped = json.unwrap("thread", "summary", "data")
                    val parsedThreadChannelId =
                            unwrapped.threadActionThreadChannelId()
                                    ?: json.threadActionThreadChannelId()
                                    ?: fallbackThreadChannelId?.value
                                    ?: error("thread action response missing thread channel id")
                    ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.d(
                            "SlockThreadReplies",
                            "thread_action_result source=$source " +
                                    "threadChannelId=$parsedThreadChannelId " +
                                    "fallback=${fallbackThreadChannelId?.value.orEmpty()} " +
                                    "parentMessageId=${fallbackParentMessageId?.value.orEmpty()} " +
                                    "replyCount=${unwrapped.replyCountFromAliases() ?: json.replyCountFromAliases() ?: 0}"
                    )
                    ThreadActionResult(
                            threadChannelId = ChannelId(parsedThreadChannelId),
                            changed = unwrapped.boolean("changed", "ok", "success")
                                            ?: json.boolean("changed", "ok", "success") ?: true,
                            parentMessageId =
                                    unwrapped
                                            .string("parentMessageId", "parentId", "rootMessageId")
                                            ?.let(::MessageId)
                                            ?: json.string(
                                                            "parentMessageId",
                                                            "parentId",
                                                            "rootMessageId"
                                                    )
                                                    ?.let(::MessageId)
                                                    ?: fallbackParentMessageId,
                            replyCount = unwrapped.replyCountFromAliases()
                                            ?: json.replyCountFromAliases() ?: 0
                    )
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
                            block = { api.serverUnreadSummary() },
                            mapper = { json -> json.toServerUnreadSummarySnapshot(context.server) }
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
            sessionProvider: () -> AuthSession?,
            baseUrl: String = RetrofitAuthRepository.BASE_URL
    ): SlockApi =
            Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(
                            OkHttpClient.Builder()
                                    .addInterceptor(AuthHeaderInterceptor(sessionProvider))
                                    .build()
                    )
                    .build()
                    .create(SlockApi::class.java)

    fun create(
            store: AuthSessionStore,
            baseUrl: String = RetrofitAuthRepository.BASE_URL,
            tokenRefresher: AuthTokenRefresher =
                    AuthTokenRefresher(
                            store = store,
                            api = RetrofitAuthRepository.createAuthApi(baseUrl)
                    )
    ): SlockApi {
        val sessionProvider = { runBlocking { store.readSession() } }
        return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(
                        OkHttpClient.Builder()
                                .addInterceptor(AuthHeaderInterceptor(sessionProvider))
                                .addInterceptor(
                                        AuthRefreshInterceptor(sessionProvider, tokenRefresher)
                                )
                                .build()
                )
                .build()
                .create(SlockApi::class.java)
    }
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

private fun JsonObject.homeConversationItems(
        source: String,
        names: Array<String>,
        warningLogger: (String) -> Unit
): List<com.google.gson.JsonElement> {
    val items = items(*names)
    if (items.isNotEmpty()) return items
    warningLogger(
            "empty_items source=$source stage=list_extract fieldPath=${names.joinToString("|")}"
    )
    return emptyList()
}

private fun JsonObject.toConversationOrNull(
        kind: ConversationKind,
        source: String,
        warningLogger: (String) -> Unit
): ConversationSummary? {
    val id = string("id", "channelId", "threadChannelId", "_id")
    if (id == null) {
        warningLogger(
                "skip_item source=$source stage=item_map fieldPath=id|channelId|threadChannelId|_id reason=missing_id"
        )
        return null
    }
    return toConversation(kind, id)
}

private fun JsonObject.toConversation(kind: ConversationKind): ConversationSummary {
    val id = string("id", "channelId", "threadChannelId", "_id") ?: "unknown-channel"
    return toConversation(kind, id)
}

private fun JsonObject.toConversation(kind: ConversationKind, id: String): ConversationSummary {
    val presenceSource = firstObject("peer", "agent", "user", "member", "profile")
    val presenceMemberKind =
            explicitMemberKindOrNull() ?: presenceSource?.explicitMemberKindOrNull()
    val name =
            string(
                            "name",
                            "channelName",
                            "peerDisplayName",
                            "peerName",
                            "agentName",
                            "agentDisplayName",
                            "agentUsername",
                            "userDisplayName",
                            "username",
                            "title"
                    )
                    ?: presenceSource?.string("displayName", "name", "username", "email")
                            ?: if (kind == ConversationKind.DirectMessage) "Direct message"
                    else "channel"
    val joined = if (kind == ConversationKind.DirectMessage) true else channelJoined()
    val canSend = boolean("canSend", "canWrite", "canMessage") ?: joined
    val avatarWireValue = conversationAvatarWireValue()
    val presence =
            string(
                            "presence",
                            "status",
                            "activity",
                            "availability",
                            "onlineStatus",
                            "connectionStatus",
                            "state",
                            "peerPresence",
                            "peerStatus",
                            "peerAvailability",
                            "agentPresence",
                            "agentStatus",
                            "userPresence",
                            "userStatus"
                    )
                    .toPresenceOrNull()
                    ?: presenceSource
                            ?.string(
                                    "presence",
                                    "status",
                                    "activity",
                                    "availability",
                                    "onlineStatus",
                                    "connectionStatus",
                                    "state"
                            )
                            .toPresenceOrNull()
    return ConversationSummary(
            threadId = ThreadId(string("threadId", "threadChannelId", "id") ?: id),
            channelId = ChannelId(id),
            kind = kind,
            name = name,
            subtitle = string("description", "topic", "purpose", "peerDescription"),
            avatarUrl = avatarWireValue,
            avatar = AvatarReference.fromWireValue(avatarWireValue),
            unreadCount = int("unreadCount", "unread") ?: 0,
            mentionCount = int("mentionCount", "mentions") ?: 0,
            lastMessageId = string("lastMessageId", "latestMessageId")?.let(::MessageId),
            firstUnreadMessageId = string("firstUnreadMessageId")?.let(::MessageId),
            lastMessagePreview =
                    string("lastMessagePreview", "preview", "latestActivityPreview", "content"),
            lastMessageAt = string("lastMessageAt", "updatedAt", "latestActivityAt", "createdAt"),
            lastMessageSenderName = string("lastMessageSenderName", "senderName", "authorName"),
            presence =
                    presence.takeIf {
                        kind != ConversationKind.DirectMessage ||
                                presenceMemberKind == MemberKind.Agent
                    },
            presenceMemberId =
                    (string(
                                    "presenceMemberId",
                                    "peerMemberId",
                                    "peerId",
                                    "memberId",
                                    "agentId",
                                    "userId"
                            )
                                    ?: presenceSource?.string(
                                            "presenceMemberId",
                                            "peerMemberId",
                                            "peerId",
                                            "memberId",
                                            "agentId",
                                            "userId",
                                            "id"
                                    ))
                            ?.let(::MemberId),
            joined = joined,
            archived = boolean("archived", "isArchived") ?: false,
            permissions =
                    ConversationPermissions(
                            canRead = boolean("canRead") ?: true,
                            canSend = canSend,
                            canAttach = boolean("canAttach", "canUpload") ?: canSend,
                            canCreateTask = boolean("canCreateTask", "canCreateTasks") ?: canSend
                    )
    )
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

private val ConversationSummary.conversationCacheKey: String
    get() = channelId?.value ?: threadId.value

private fun ConversationSummary.withDirectMessageFallback(
        previous: ConversationSummary
): ConversationSummary =
        copy(
                name = name.takeUnless { it.isBlank() || it == "Direct message" } ?: previous.name,
                subtitle = subtitle ?: previous.subtitle,
                avatarUrl = avatarUrl ?: previous.avatarUrl,
                avatar = avatar ?: previous.avatar,
                unreadCount = maxOf(unreadCount, previous.unreadCount),
                mentionCount = maxOf(mentionCount, previous.mentionCount),
                lastMessageId = lastMessageId ?: previous.lastMessageId,
                firstUnreadMessageId = firstUnreadMessageId ?: previous.firstUnreadMessageId,
                lastMessagePreview = lastMessagePreview ?: previous.lastMessagePreview,
                lastMessageAt = lastMessageAt ?: previous.lastMessageAt,
                lastMessageSenderName = lastMessageSenderName ?: previous.lastMessageSenderName,
                presence = presence ?: previous.presence,
                presenceMemberId = presenceMemberId ?: previous.presenceMemberId,
                joined = joined || previous.joined,
                archived = archived || previous.archived
        )

private fun JsonObject.conversationAvatarWireValue(): String? =
        avatarWireValue(
                "avatarUrl",
                "avatar",
                "avatar_url",
                "imageUrl",
                "profileImageUrl",
                "peerAvatarUrl",
                "peerAvatar",
                "peerAvatar_url",
                "agentAvatarUrl",
                "agentAvatar",
                "userAvatarUrl",
                "userAvatar"
        )
                ?: firstObject("peer", "agent", "user", "member", "profile")
                        ?.avatarWireValue(
                                "avatarUrl",
                                "avatar",
                                "avatar_url",
                                "imageUrl",
                                "profileImageUrl"
                        )

private fun JsonObject.channelJoined(): Boolean {
    boolean("joined", "isJoined", "isMember", "member")?.let {
        return it
    }
    return when (string("membership", "membershipStatus", "memberStatus", "joinStatus")?.lowercase()
    ) {
        "joined", "member", "active", "owner", "admin" -> true
        "left", "unjoined", "not_joined", "not-member", "not_member", "none", "removed" -> false
        else -> true
    }
}

private fun JsonObject.joinedChannelOrNull(channelId: ChannelId): ConversationSummary? {
    val channelObject = firstObject("channel", "data") ?: this
    val hasChannelShape =
            channelObject.string("id", "channelId", "threadChannelId", "_id") != null ||
                    channelObject.string("name", "channelName", "title") != null
    if (!hasChannelShape) return null
    return channelObject
            .toConversation(ConversationKind.Channel, channelId.value)
            .copy(joined = true)
}

private fun JsonObject.toInboxItem(defaultKind: InboxItemKind? = null): InboxItem {
    val kind =
            defaultKind
                    ?: when (string("kind", "type")) {
                        "dm", "direct_message", "directMessage" -> InboxItemKind.DirectMessage
                        "thread" -> InboxItemKind.Thread
                        else -> InboxItemKind.Channel
                    }
    val channelId = string("channelId", "id", "threadChannelId") ?: "unknown-channel"
    val threadChannelId = string("threadChannelId")
    val key =
            when {
                kind == InboxItemKind.Thread && threadChannelId != null -> "thread:$threadChannelId"
                kind == InboxItemKind.DirectMessage -> "dm:$channelId"
                else -> "channel:$channelId"
            }
    return InboxItem(
            key = string("key") ?: key,
            kind = kind,
            channelId = ChannelId(channelId),
            channelName =
                    string(
                            "channelName",
                            "name",
                            "peerDisplayName",
                            "peerName",
                            "parentChannelName"
                    )
                            ?: "channel",
            unreadCount = int("unreadCount", "unread") ?: 0,
            firstUnreadMessageId = string("firstUnreadMessageId")?.let(::MessageId),
            lastMessageId =
                    string("lastMessageId", "latestMessageId", "messageId", "id")?.let(::MessageId),
            lastMessagePreview = string("lastMessagePreview", "preview", "content", "body"),
            lastMessageAt = string("lastMessageAt", "updatedAt", "createdAt"),
            lastMessageSenderName = string("lastMessageSenderName", "senderName", "authorName"),
            threadChannelId = threadChannelId?.let(::ChannelId),
            parentChannelId = string("parentChannelId")?.let(::ChannelId),
            parentChannelName = string("parentChannelName"),
            parentChannelKind =
                    string("parentChannelKind")?.let {
                        if (it == "dm") ConversationKind.DirectMessage else ConversationKind.Channel
                    },
            parentMessageId =
                    string("parentMessageId", "parentId", "rootMessageId", "messageId")
                            ?.let(::MessageId),
            parentMessagePreview = string("parentMessagePreview"),
            latestActivityMessageId = string("latestActivityMessageId")?.let(::MessageId),
            latestActivityPreview = string("latestActivityPreview"),
            latestActivityAt = string("latestActivityAt"),
            latestActivitySenderName = string("latestActivitySenderName"),
            replyCount = replyCountFromAliases() ?: 0,
            participantIds = stringList("participantIds", "participants").map(::MemberId),
            taskNumber = int("taskNumber"),
            taskStatus = string("taskStatus")?.toTaskStatus(),
            taskClaimedByName = string("taskClaimedByName", "claimedByName")
    )
}

private fun JsonObject.replyCountFromAliases(): Int? {
    int(
                    "replyCount",
                    "reply_count",
                    "replies",
                    "replies_count",
                    "threadReplyCount",
                    "thread_reply_count",
                    "repliesCount",
                    "threadRepliesCount",
                    "thread_replies_count",
                    "commentsCount",
                    "comments_count",
                    "childCount",
                    "child_count"
            )
            ?.let {
                return it
            }
    for (objectName in
            listOf(
                    "thread",
                    "replyThread",
                    "messageThread",
                    "repliesThread",
                    "summary",
                    "data",
                    "meta"
            )) {
        firstObject(objectName)?.replyCountFromAliases()?.let {
            return it
        }
    }
    return null
}

private fun JsonObject.toThreadSnapshot(
        context: NetworkServerContext,
        fallbackThreadId: ThreadId,
        fallbackConversationName: String? = null,
        highlightedMessageId: MessageId? = null
): ThreadSnapshot {
    val parentMessage =
            firstObject(
                            "parentMessage",
                            "parent",
                            "rootMessage",
                            "originMessage",
                            "threadParentMessage"
                    )
                    ?.toMessage(fallbackThreadId)
    val messages =
            messageItems()
                    .mapNotNull { it.asObjectOrNull()?.toMessage(fallbackThreadId) }
                    .distinctBy { it.id }
                    .sortedWith(
                            compareBy<Message> { it.seq ?: Long.MAX_VALUE }.thenBy {
                                it.timestampLabel
                            }
                    )
    val conversation =
            unwrap("conversation", "channel", "thread")
                    .toConversationOrDefault(
                            threadId = fallbackThreadId,
                            name = fallbackConversationName
                                            ?: messages.firstOrNull()?.threadId?.value ?: "Messages"
                    )
    return ThreadSnapshot(
            conversation = conversation,
            messages = messages,
            parentMessage = parentMessage ?: messages.firstOrNull { message ->
                message.id == highlightedMessageId
            },
            canLoadMoreBefore = canLoadMoreBefore(),
            canLoadMoreAfter = canLoadMoreAfter(),
            hasGap = boolean("hasGap", "gap") ?: false,
            historyLimited = boolean("historyLimited", "limited") ?: false,
            highlightedMessageId = highlightedMessageId
                            ?: string("highlightedMessageId", "messageId")?.let(::MessageId)
    )
}

private fun JsonObject.canLoadMoreBefore(): Boolean {
    val candidates = listOfNotNull(this, firstObject("data", "context")).distinct()
    candidates.forEach { candidate ->
        val direct =
                candidate.boolean(
                        "hasMoreBefore",
                        "has_more_before",
                        "hasMore",
                        "has_more",
                        "canLoadMoreBefore",
                        "can_load_more_before",
                        "hasPrevious",
                        "has_previous",
                        "hasPrev",
                        "has_prev"
                )
        if (direct != null) return direct
        for (objectName in listOf("pagination", "pageInfo", "paging", "meta")) {
            val nested =
                    candidate
                            .firstObject(objectName)
                            ?.boolean(
                                    "hasMoreBefore",
                                    "has_more_before",
                                    "hasMore",
                                    "has_more",
                                    "canLoadMoreBefore",
                                    "can_load_more_before",
                                    "hasPrevious",
                                    "has_previous",
                                    "hasPrev",
                                    "has_prev"
                            )
            if (nested != null) return nested
        }
    }
    return candidates.any { candidate ->
        candidate.hasNonBlankString(
                "previousCursor",
                "previous_cursor",
                "prevCursor",
                "prev_cursor",
                "beforeCursor",
                "before_cursor"
        ) ||
                listOf("pagination", "pageInfo", "paging", "meta").any { objectName ->
                    candidate
                            .firstObject(objectName)
                            ?.hasNonBlankString(
                                    "previousCursor",
                                    "previous_cursor",
                                    "prevCursor",
                                    "prev_cursor",
                                    "beforeCursor",
                                    "before_cursor"
                            ) == true
                }
    }
}

private fun JsonObject.canLoadMoreAfter(): Boolean {
    val candidates = listOfNotNull(this, firstObject("data", "context")).distinct()
    candidates.forEach { candidate ->
        val direct =
                candidate.boolean(
                        "hasMoreAfter",
                        "has_more_after",
                        "canLoadMoreAfter",
                        "can_load_more_after"
                )
        if (direct != null) return direct
        for (objectName in listOf("pagination", "pageInfo", "paging", "meta")) {
            val nested =
                    candidate
                            .firstObject(objectName)
                            ?.boolean(
                                    "hasMoreAfter",
                                    "has_more_after",
                                    "canLoadMoreAfter",
                                    "can_load_more_after"
                            )
            if (nested != null) return nested
        }
    }
    return candidates.any { candidate ->
        candidate.hasNonBlankString("nextCursor", "next_cursor", "afterCursor", "after_cursor") ||
                listOf("pagination", "pageInfo", "paging", "meta").any { objectName ->
                    candidate
                            .firstObject(objectName)
                            ?.hasNonBlankString(
                                    "nextCursor",
                                    "next_cursor",
                                    "afterCursor",
                                    "after_cursor"
                            ) == true
                }
    }
}

private fun JsonObject.hasNonBlankString(vararg names: String): Boolean =
        names.any { name -> string(name)?.isNotBlank() == true }

private fun JsonObject.toThreadListSnapshot(context: NetworkServerContext): ThreadListSnapshot {
    val threads =
            items("threads", "items", "data")
                    .mapNotNull { element ->
                        element.asObjectOrNull()?.toInboxItem(defaultKind = InboxItemKind.Thread)
                    }
                    .distinctBy { it.key }
    return ThreadListSnapshot(
            server = context.server,
            threads = threads,
            totalCount = int("totalCount", "count") ?: threads.size,
            hasMore = boolean("hasMore") ?: false,
            nextOffset = int("nextOffset", "offset")
    )
}

private fun ThreadSnapshot.withReplySummaries(
        threads: List<InboxItem>,
        logger: (String) -> Unit
): ThreadSnapshot {
    if (threads.isEmpty()) return this
    val updatedMessages =
            messages.map { message ->
                val summary =
                        threads.firstOrNull { thread ->
                            thread.matchesParentMessage(message.id) ||
                                    (message.threadChannelId != null &&
                                            (thread.channelId == message.threadChannelId ||
                                                    thread.threadChannelId ==
                                                            message.threadChannelId))
                        }
                if (summary == null) {
                    if (message.replyCount == 0) {
                        val candidates =
                                threads.take(2).joinToString { t ->
                                    "ch=${t.channelId.value.take(8)} " +
                                            "pMsgId=${t.parentMessageId?.value?.take(8) ?: "null"}"
                                }
                        logger(
                                "summary_join_miss msgId=${message.id.value.take(8)} " +
                                        "threadCh=${message.threadChannelId?.value?.take(8) ?: "null"} " +
                                        "threadCandidates=[$candidates] source=channel_threads"
                        )
                    }
                    return@map message
                }
                val summaryThreadChannelId = summary.threadChannelId ?: summary.channelId
                logger(
                        "summary_join parentMessageId=${message.id.value} " +
                                "channelId=${message.channelId?.value.orEmpty()} " +
                                "threadChannelId=${summaryThreadChannelId.value} " +
                                "replyCount=${maxOf(message.replyCount, summary.replyCount)} " +
                                "source=channel_threads"
                )
                message.copy(
                        threadChannelId = message.threadChannelId ?: summaryThreadChannelId,
                        replyCount = maxOf(message.replyCount, summary.replyCount)
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

private fun JsonObject.messageItems(): List<com.google.gson.JsonElement> {
    val unwrapped = unwrap("data", "context")
    val direct = unwrapped.items("messages", "items", "results")
    if (direct.isNotEmpty()) return direct
    val before = unwrapped.items("before", "previous")
    val after = unwrapped.items("after", "next")
    val current = unwrapped.get("message")?.let(::listOf).orEmpty()
    return before + current + after
}

private fun JsonObject.toMessage(fallbackThreadId: ThreadId): Message? {
    val id = string("id", "messageId", "_id") ?: return null
    val rawThreadId =
            string("threadId", "thread_id")
                    ?: firstObject("thread", "replyThread", "messageThread", "repliesThread")
                            ?.string("id", "threadId", "thread_id", "channelId", "channel_id")
    val rawChannelId =
            string("channelId", "channel_id")
                    ?: firstObject("channel", "parentChannel")
                            ?.string("id", "channelId", "channel_id")
    val explicitThreadChannelId =
            string(
                    "threadChannelId",
                    "thread_channel_id",
                    "replyThreadChannelId",
                    "reply_thread_channel_id",
                    "repliesThreadChannelId",
                    "replies_thread_channel_id",
                    "childThreadChannelId",
                    "child_thread_channel_id",
                    "threadChannel",
                    "thread_channel",
                    "replyChannelId",
                    "reply_channel_id",
                    "replyThreadId",
                    "reply_thread_id",
                    "commentsThreadChannelId",
                    "comments_thread_channel_id"
            )
    val parentMessageId =
            string(
                            "parentMessageId",
                            "parent_message_id",
                            "parentId",
                            "parent_id",
                            "rootMessageId",
                            "root_message_id"
                    )
                    ?.let(::MessageId)
    val replyCount =
            int(
                    "replyCount",
                    "reply_count",
                    "replies",
                    "threadReplyCount",
                    "thread_reply_count",
                    "repliesCount",
                    "replies_count",
                    "threadRepliesCount",
                    "thread_replies_count",
                    "commentsCount",
                    "comments_count",
                    "childCount",
                    "child_count"
            )
                    ?: firstObject("thread", "replyThread", "messageThread", "repliesThread")
                            ?.int(
                                    "replyCount",
                                    "reply_count",
                                    "replies",
                                    "repliesCount",
                                    "replies_count",
                                    "childCount",
                                    "child_count"
                            )
                            ?: 0
    val topLevelReplyThreadId =
            rawThreadId
                    ?.takeIf { parentMessageId == null && explicitThreadChannelId == null }
                    ?.takeIf { replyCount > 0 }
                    ?.takeIf { it != rawChannelId && it != fallbackThreadId.value }
    val threadId =
            ThreadId(
                    when {
                        topLevelReplyThreadId != null -> rawChannelId ?: fallbackThreadId.value
                        else -> rawThreadId
                                        ?: rawChannelId ?: explicitThreadChannelId
                                                ?: fallbackThreadId.value
                    }
            )
    val body = string("body", "content", "text", "message") ?: ""
    val author =
            firstObject(
                            "author",
                            "sender",
                            "user",
                            "createdBy",
                            "createdByUser",
                            "senderUser",
                            "agent",
                            "authorAgent"
                    )
                    ?.toMemberProfile()
                    ?: toMessageAuthorProfile()
    return Message(
            id = MessageId(id),
            threadId = threadId,
            channelId =
                    string("channelId", "channel_id", "threadChannelId", "thread_channel_id")
                            ?.let(::ChannelId),
            threadChannelId = (explicitThreadChannelId ?: topLevelReplyThreadId)?.let(::ChannelId),
            seq =
                    long(
                            "seq",
                            "sequence",
                            "sequenceNumber",
                            "sequence_number",
                            "messageSeq",
                            "message_seq",
                            "serverSeq",
                            "server_seq",
                            "createdSeq",
                            "created_seq"
                    ),
            author = author,
            timestampLabel = string("timestampLabel", "createdAt", "created_at", "updatedAt") ?: "",
            body = body,
            messageType = toMessageType(),
            systemEventType = systemEventType(),
            metadata = systemMetadata(),
            attachments = items("attachments").mapNotNull { it.asObjectOrNull()?.toAttachment() },
            deliveryState = MessageDeliveryState.Sent,
            task =
                    run {
                        val nested = firstObject("task")
                        if (nested != null) {
                            nested.toTaskSummary()
                        } else {
                            // Flat task fields embedded directly on the message row
                            val taskNum = int("taskNumber", "task_number")
                            val taskStatus = string("taskStatus", "task_status")
                            if (taskNum != null || taskStatus != null) {
                                val msgId = MessageId(id)
                                val assignee =
                                        claimedByFallback()
                                                ?: run {
                                                    val aid =
                                                            string(
                                                                    "taskAssigneeId",
                                                                    "task_assignee_id"
                                                            )
                                                    if (aid != null) {
                                                        val atype =
                                                                string(
                                                                        "taskAssigneeType",
                                                                        "task_assignee_type"
                                                                )
                                                        val aname =
                                                                string(
                                                                        "taskAssigneeName",
                                                                        "task_assignee_name"
                                                                )
                                                                        ?: aid
                                                        MemberProfile(
                                                                id = MemberId(aid),
                                                                displayName = aname,
                                                                handle = aname,
                                                                kind =
                                                                        if (atype == "agent")
                                                                                MemberKind.Agent
                                                                        else MemberKind.Human,
                                                                avatarLabel =
                                                                        aname.take(1).uppercase(),
                                                                presence = Presence.Offline
                                                        )
                                                    } else null
                                                }
                                TaskSummary(
                                        id = TaskId(string("taskId") ?: id),
                                        title = string("taskTitle", "task_title")
                                                        ?: taskNum?.let { "#$it" } ?: "",
                                        status = taskStatus?.toTaskStatus() ?: TaskStatus.Todo,
                                        taskNumber = taskNum,
                                        messageId = msgId,
                                        channelId =
                                                string("channelId", "channel_id")?.let(::ChannelId),
                                        threadId = null,
                                        assignee = assignee,
                                        updatedAtLabel = string("updatedAt", "createdAt") ?: "",
                                        canClaim = true
                                )
                            } else null
                        }
                    },
            replyCount = replyCount,
            parentMessageId = parentMessageId,
            updatedAtLabel = string("updatedAt")
    )
}

private fun JsonObject.toMemberProfile(): MemberProfile? {
    val id = memberId() ?: return null
    val displayName = memberDisplayName() ?: id
    val avatarUrl = memberAvatarUrl()
    val gravatarHash = memberGravatarHash()
    return MemberProfile(
            id = MemberId(id),
            displayName = displayName,
            handle = memberHandle(displayName),
            kind = memberKind(),
            roleDescription = memberRoleDescription(),
            avatarUrl = avatarUrl,
            gravatarHash = gravatarHash,
            avatarLabel = displayName.take(1).uppercase(),
            presence = memberPresence()
    )
}

private fun JsonObject.toAgentDmSummary(): AgentDmSummary? {
    val id = string("id", "dmId", "channelId", "threadId") ?: return null
    val peer = firstObject("peer", "user", "member", "profile")
    val peerName =
            string("peerName", "peerDisplayName", "displayName", "name")
                    ?: peer?.string("displayName", "name", "username", "email") ?: "Unknown"
    return AgentDmSummary(
            id = id,
            peerId = string("peerId", "peerUserId", "memberId", "userId")
                            ?: peer?.string("id", "userId", "memberId"),
            peerName = peerName,
            peerAvatarUrl = avatarWireValue("peerAvatarUrl", "peerAvatar", "avatarUrl", "avatar")
                            ?: peer?.avatarWireValue(
                                    "avatarUrl",
                                    "avatar",
                                    "profileAvatar",
                                    "imageUrl"
                            ),
            lastMessageAt = string("lastMessageAt", "createdAt", "updatedAt"),
            lastMessagePreview = string("lastMessagePreview", "preview", "body", "content", "text")
    )
}

private fun JsonObject.toAgentReminderSummary(): AgentReminderSummary? {
    val id = string("reminderId", "id", "_id") ?: return null
    val recurrence = firstObject("recurrence")
    return AgentReminderSummary(
            id = id,
            title = string("title", "body", "text", "content") ?: "Reminder",
            fireAt = string("fireAt", "fire_at", "scheduledAt", "scheduled_at"),
            recurrenceDescription = string("recurrenceDescription", "recurrence_description")
                            ?: recurrence?.string("description", "label"),
            messageRef = string("msgRef", "messageRef", "messageId"),
            messagePermalink = string("msgPermalink", "permalink", "messagePermalink"),
            ownerAgentId = string("ownerAgentId", "agentId"),
            status = string("status")
    )
}

private fun JsonObject.toAgentActivityEntry(): AgentActivityEntry? {
    val entry = firstObject("entry", "activity", "event", "data") ?: this
    val type =
            entry.string("type", "eventType", "kind", "activity")
                    ?.lowercase()
                    ?.takeIf { it.isNotBlank() }
                    ?: entry.string("status")?.lowercase()
                    ?: "status"
    val title = entry.string("title", "label") ?: type.toAgentActivityTitle()
    val id =
            entry.string("id", "entryId", "eventId")
                    ?: string("id", "entryId", "eventId")
                    ?: listOfNotNull(type, agentActivityTimestamp())
                            .joinToString(":")
                            .ifBlank { type }
    return AgentActivityEntry(
            id = id,
            type = type,
            title = title,
            body = entry.string("body", "text", "content", "message", "detail", "activityDetail")
                            ?: entry.string("status", "activity")
                            ?: string("detail", "activityDetail"),
            timestamp = agentActivityTimestamp()
    )
}

private fun JsonObject.toCurrentAgentActivityEntry(): AgentActivityEntry? {
    val type = string("activity", "status").toAgentActivityTypeOrNull() ?: return null
    val detail = string("activityDetail", "detail", "statusLabel", "status")
    return AgentActivityEntry(
            id = listOfNotNull("current", string("id", "agentId", "_id"), type, detail).joinToString(":"),
            type = type,
            title = type.toAgentActivityTitle(),
            body = detail,
            timestamp = string("updatedAt", "lastActivityAt", "lastSeenAt")
    )
}

private fun JsonObject.agentActivityTimestamp(): String? =
        string("timestamp", "createdAt", "created_at", "time")
                ?: long("timestamp", "createdAt", "created_at", "time")?.toString()

private fun String?.toAgentActivityTypeOrNull(): String? =
        when (val normalized = this?.lowercase()) {
            null, "", "unknown" -> null
            "active", "online", "working", "thinking", "error", "offline", "idle" -> normalized
            else -> normalized
        }

private fun String.toAgentActivityTitle(): String =
        when (this) {
            "thinking" -> "Thinking"
            "tool_start" -> "Tool started"
            "text" -> "Text"
            "system" -> "System"
            "status" -> "Status"
            "working" -> "Working"
            "online", "active" -> "Online"
            "offline" -> "Offline"
            "idle" -> "Idle"
            "error" -> "Error"
            "compaction_started" -> "Compaction started"
            "compaction_finished" -> "Compaction finished"
            else -> replace('_', ' ').replaceFirstChar { it.uppercase() }
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

private fun JsonObject.memberId(): String? =
        explicitMemberKindOrNull()?.let { kind ->
            when (kind) {
                MemberKind.Agent ->
                        string("agentId")
                                ?: nestedMemberObject()?.memberId()
                                ?: string("id", "_id", "memberId", "userId")
                MemberKind.Human ->
                        string("userId", "memberId")
                                ?: nestedMemberObject()?.memberId()
                                ?: string("id", "_id")
                MemberKind.System ->
                        string("id", "_id", "userId", "memberId", "agentId")
                                ?: nestedMemberObject()?.memberId()
            }
        }
                ?: nestedMemberObject()?.memberId()
                ?: string("userId", "memberId", "agentId", "id", "_id")

private fun JsonObject.memberDisplayName(): String? =
        string(
                "displayName",
                "display_name",
                "name",
                "username",
                "title",
                "label",
                "handle",
                "email"
        )
                ?: nestedMemberObject()?.memberDisplayName()
                        ?: firstObject("metadata", "meta")
                        ?.string(
                                "displayName",
                                "display_name",
                                "name",
                                "username",
                                "title",
                                "label"
                        )

private fun JsonObject.memberHandle(displayName: String): String =
        string("handle", "username", "email")
                ?: nestedMemberObject()?.string("handle", "username", "email") ?: displayName

private fun JsonObject.memberRoleDescription(): String? =
        string("description", "roleDescription", "role")
                ?: nestedMemberObject()?.string("description", "roleDescription", "role")

private fun JsonObject.memberAvatarUrl(): String? =
        avatarWireValue(
                "avatarUrl",
                "avatar",
                "avatar_url",
                "imageUrl",
                "profileImageUrl",
                "profileAvatar"
        )
                ?: nestedMemberObject()?.memberAvatarUrl()

private fun JsonObject.memberGravatarHash(): String? =
        string("gravatarHash", "gravatar_hash", "gravatar", "gravatarMd5", "gravatar_md5")
                ?: nestedMemberObject()?.memberGravatarHash()

private fun JsonObject.memberPresence(): Presence =
        string("presence", "status", "activity", "availability").toPresenceOrNull()
                ?: nestedMemberObject()?.memberPresence() ?: Presence.Offline

private fun JsonObject.memberKind(): MemberKind =
        explicitMemberKindOrNull()
                ?: nestedMemberObject()?.explicitMemberKindOrNull() ?: MemberKind.Human

private fun JsonObject.explicitMemberKindOrNull(
        allowAgentIdInference: Boolean = true
): MemberKind? {
    val kindValue = string("kind", "type", "memberType")?.lowercase()
    return when {
        kindValue == "human" ||
                kindValue == "user" ||
                string("userId") != null ||
                firstObject("user", "member") != null -> MemberKind.Human
        boolean("isAgent") == true ||
                kindValue == "agent" ||
                (allowAgentIdInference && string("agentId") != null) ||
                firstObject("agent") != null -> MemberKind.Agent
        else -> null
    }
}

private fun JsonObject.nestedMemberObject(): JsonObject? =
        firstObject("profile", "user", "account", "member", "agent")

private fun JsonObject.toChannelMemberProfile(defaultKind: MemberKind? = null): MemberProfile? {
    val profileObject = firstObject("member", "user", "agent", "profile") ?: this
    val profile = profileObject.toMemberProfile() ?: toMemberProfile() ?: return null
    val role =
            string("role", "channelRole", "memberRole", "roleDescription")
                    ?: profile.roleDescription
    val presence = string("presence", "status", "availability").toPresence(profile.presence)
    val badges = listOfNotNull(role).filter { it.isNotBlank() }
    return profile.copy(
            kind = defaultKind ?: explicitMemberKindOrNull() ?: profile.kind,
            roleDescription = role,
            presence = presence,
            permissionBadges = if (badges.isNotEmpty()) badges else profile.permissionBadges
    )
}

private fun JsonObject.memberItems(): List<MemberProfile> {
    val root = unwrap("data")
    val direct =
            root.items("members", "items").filterNot {
                it.asObjectOrNull()?.explicitMemberKindOrNull() == MemberKind.Agent
            }
    val users = root.directArrayItems("users")
    val agents = root.directArrayItems("agents")
    val fallback =
            root.items("data").filterNot {
                it.asObjectOrNull()?.explicitMemberKindOrNull() == MemberKind.Agent
            }
    val directMembers = direct.mapNotNull { it.asObjectOrNull()?.toChannelMemberProfile() }
    val splitMembers =
            users.mapNotNull { it.asObjectOrNull()?.toChannelMemberProfile(MemberKind.Human) } +
                    agents.mapNotNull {
                        it.asObjectOrNull()?.toChannelMemberProfile(MemberKind.Agent)
                    }
    val members =
            when {
                directMembers.isNotEmpty() || splitMembers.isNotEmpty() ->
                        directMembers + splitMembers
                else -> fallback.mapNotNull { it.asObjectOrNull()?.toChannelMemberProfile() }
            }
    return members.distinctBy { it.memberKey }
}

private fun JsonObject.agentMemberItems(): List<MemberProfile> {
    val agentContainers = agentObjects()
    if (agentContainers.isNotEmpty()) {
        return agentContainers
                .filterNot { it.isExplicitHumanMember() }
                .mapNotNull { it.toChannelMemberProfile(MemberKind.Agent) }
                .distinctBy { it.memberKey }
    }
    return emptyList()
}

private fun JsonObject.agentObjects(): List<JsonObject> {
    val root = unwrap("data")
    val agentContainers = root.directArrayItems("agents").mapNotNull { it.asObjectOrNull() }
    if (agentContainers.isNotEmpty()) {
        return agentContainers
    }
    val explicitAgents =
            root.get("agents")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.toList()
                    ?.mapNotNull { it.asObjectOrNull() }
                    .orEmpty()
    if (explicitAgents.isNotEmpty()) {
        return explicitAgents
    }
    return root.items("items", "nodes", "records", "rows", "list", "results")
            .mapNotNull { it.asObjectOrNull() }
}

private fun JsonObject.isExplicitHumanMember(): Boolean =
        string("kind", "type", "memberType")?.lowercase()?.let { it == "human" || it == "user" } ==
                true || string("userId") != null || firstObject("user", "member") != null

private fun JsonObject.directArrayItems(name: String): List<com.google.gson.JsonElement> =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()

private fun JsonObject.sizeNumberLabel(): String? =
        long("sizeBytes", "size_bytes", "bytes", "byteSize", "byte_size")
                ?.let { bytes -> "$bytes B" }

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
        when (this?.lowercase()) {
            "active", "online", "working", "available" -> Presence.Active
            "away", "idle", "busy" -> Presence.Away
            "offline", "inactive" -> Presence.Offline
            else -> null
        }

private fun JsonObject.toMessageAuthorProfile(): MemberProfile {
    val nestedAuthor =
            firstObject(
                            "author",
                            "sender",
                            "user",
                            "createdBy",
                            "createdByUser",
                            "senderUser",
                            "agent",
                            "authorAgent"
                    )
                    ?.toMemberProfile()
    val authorId =
            string(
                    "authorId",
                    "senderId",
                    "userId",
                    "createdById",
                    "createdByUserId",
                    "senderUserId",
                    "agentId",
                    "authorAgentId",
                    "agentUserId"
            )
                    ?: nestedAuthor?.id?.value ?: "unknown-author"
    val displayName =
            string(
                    "authorDisplayName",
                    "authorName",
                    "senderDisplayName",
                    "senderName",
                    "userDisplayName",
                    "userName",
                    "createdByName",
                    "createdByDisplayName",
                    "createdByUsername",
                    "createdByUserDisplayName",
                    "agentName",
                    "agentDisplayName",
                    "agentUsername",
                    "agentTitle",
                    "authorAgentName",
                    "authorAgentDisplayName",
                    "senderAgentName",
                    "senderAgentDisplayName",
                    "profileDisplayName",
                    "profileName",
                    "authorHandle",
                    "senderHandle",
                    "email"
            )
                    ?: nestedAuthor?.displayName ?: authorId
    val handle =
            string(
                    "authorHandle",
                    "senderHandle",
                    "userHandle",
                    "agentHandle",
                    "agentUsername",
                    "handle",
                    "email"
            )
                    ?: nestedAuthor?.handle ?: displayName
    val isAgent =
            boolean("authorIsAgent", "senderIsAgent", "isAgent") == true ||
                    string("authorKind", "senderKind", "kind", "type") == "agent" ||
                    string("agentId", "authorAgentId", "agentUserId") != null ||
                    nestedAuthor?.kind == MemberKind.Agent
    return MemberProfile(
            id = MemberId(authorId),
            displayName = displayName,
            handle = handle,
            kind = if (isAgent) MemberKind.Agent else MemberKind.Human,
            roleDescription =
                    string(
                            "authorDescription",
                            "senderDescription",
                            "agentDescription",
                            "roleDescription"
                    )
                            ?: nestedAuthor?.roleDescription,
            avatarUrl =
                    avatarWireValue(
                            "authorAvatarUrl",
                            "authorAvatar",
                            "senderAvatarUrl",
                            "senderAvatar",
                            "agentAvatarUrl",
                            "agentAvatar",
                            "authorAgentAvatarUrl",
                            "authorAgentAvatar",
                            "senderAgentAvatarUrl",
                            "senderAgentAvatar",
                            "profileAvatar",
                            "avatarUrl",
                            "avatar"
                    )
                            ?: nestedAuthor?.avatarUrl,
            gravatarHash =
                    string(
                            "authorGravatarHash",
                            "authorGravatar",
                            "senderGravatarHash",
                            "senderGravatar",
                            "userGravatarHash",
                            "userGravatar",
                            "gravatarHash",
                            "gravatar_hash",
                            "gravatar"
                    )
                            ?: nestedAuthor?.gravatarHash,
            avatarLabel = displayName.take(1).uppercase(),
            presence =
                    string(
                                    "authorPresence",
                                    "senderPresence",
                                    "agentPresence",
                                    "presence",
                                    "status",
                                    "activity"
                            )
                            .toPresence(nestedAuthor?.presence ?: Presence.Offline)
    )
}

private fun JsonObject.toTaskSummary(): TaskSummary? {
    val id = string("id", "taskId") ?: return null
    val title = string("title", "body", "content") ?: return null
    val assignee =
            firstObject(
                            "assignee",
                            "claimedBy",
                            "assignedTo",
                            "assigneeUser",
                            "claimedByUser",
                            "claimedByAgent"
                    )
                    ?.toMemberProfile()
                    ?: claimedByFallback()
    val messageId = string("messageId")?.let(::MessageId)
    val explicitThreadId =
            string(
                    "threadChannelId",
                    "replyThreadChannelId",
                    "replyThreadId",
                    "repliesThreadChannelId",
                    "childThreadChannelId",
                    "commentsThreadChannelId"
            )
    val nestedThreadId =
            firstObject("thread", "replyThread", "messageThread", "repliesThread")
                    ?.string(
                            "threadChannelId",
                            "replyThreadChannelId",
                            "replyThreadId",
                            "id",
                            "channelId"
                    )
    val fallbackThreadId = string("threadId") ?: nestedThreadId
    val channelType = string("channelType", "type", "kind")?.lowercase()
    val channelId =
            string("channelId", "parentChannelId")
                    ?: firstObject("channel", "parentChannel", "conversation")
                            ?.string("id", "channelId", "parentChannelId")
                    ?: if (channelType == "channel") fallbackThreadId else null
    val taskThreadId = explicitThreadId ?: fallbackThreadId
    val parentMessageId =
            string("parentMessageId", "parentId", "rootMessageId", "threadParentMessageId")
                    ?.let(::MessageId) ?: if (taskThreadId != null) messageId else null
    return TaskSummary(
            id = TaskId(id),
            title = title,
            status = string("status", "taskStatus")?.toTaskStatus() ?: TaskStatus.Todo,
            taskNumber = int("taskNumber", "number"),
            messageId = messageId,
            parentMessageId = parentMessageId,
            channelId = channelId?.let(::ChannelId),
            threadId = taskThreadId?.let(::ThreadId),
            isThreadTask =
                    parentMessageId != null ||
                            explicitThreadId != null ||
                            (channelType == "thread" && fallbackThreadId != null),
            assignee = assignee,
            channelName = string("channelName") ?: firstObject("channel")?.string("name", "slug"),
            updatedAtLabel = string("updatedAt", "createdAt") ?: "",
            canClaim = boolean("canClaim") ?: true
    )
}

private fun JsonObject.toTaskSnapshot(): TaskSnapshot {
    val tasks =
            items("tasks", "serverTasks", "items", "data").mapNotNull {
                it.asObjectOrNull()?.toTaskSummary()
            }
    return TaskSnapshot(tasks = tasks, summaryCounts = taskSummaryCounts(tasks))
}

private fun TaskSnapshot.filteredBy(filter: TaskFilter, currentUserId: String): TaskSnapshot {
    val filteredTasks =
            tasks.filter { task ->
                (filter.statuses.isEmpty() || task.status in filter.statuses) &&
                        (!filter.assignedToMeOnly || task.assignee?.id?.value == currentUserId)
            }
    return copy(tasks = filteredTasks, summaryCounts = taskSummaryCounts(filteredTasks))
}

private fun taskSummaryCounts(tasks: List<TaskSummary>): Map<TaskStatus, Int> =
        TaskStatus.entries.associateWith { status -> tasks.count { it.status == status } }

private fun JsonObject.claimedByFallback(): MemberProfile? {
    val claimedById =
            string("claimedById", "assigneeId", "assignedToId", "taskAssigneeId") ?: return null
    val claimedByType =
            string("claimedByType", "assigneeType", "assignedToType", "taskAssigneeType")
    val label =
            string("claimedByName", "assigneeName", "assignedToName", "claimedByHandle")
                    ?: claimedById
    return MemberProfile(
            id = MemberId(claimedById),
            displayName = label,
            handle = string("claimedByHandle", "assigneeHandle", "assignedToHandle") ?: label,
            kind = if (claimedByType == "agent") MemberKind.Agent else MemberKind.Human,
            avatarLabel = label.take(1).uppercase(),
            presence = Presence.Offline
    )
}

private fun JsonObject.toAttachment(): Attachment? {
    val id =
            string("id", "attachmentId", "attachment_id", "fileId", "file_id", "_id")
                    ?: firstObject("attachment", "file", "image", "media")
                            ?.string(
                                    "id",
                                    "attachmentId",
                                    "attachment_id",
                                    "fileId",
                                    "file_id",
                                    "_id"
                            ) ?: return null
    val nested = firstObject("attachment", "file", "image", "media")
    return Attachment(
            id = AttachmentId(id),
            name =
                    string("name", "fileName", "filename", "originalName", "original_name", "title", "label")
                            ?: nested?.string(
                                    "name",
                                    "fileName",
                                    "filename",
                                    "originalName",
                                    "original_name",
                                    "title",
                                    "label"
                            ) ?: "Attachment",
            mimeType =
                    string("mimeType", "mime_type", "contentType", "content_type", "type")
                            ?: nested?.string(
                                    "mimeType",
                                    "mime_type",
                                    "contentType",
                                    "content_type",
                                    "type"
                            ) ?: "application/octet-stream",
            sizeLabel =
                    string("sizeLabel", "size_label", "size", "fileSize", "file_size")
                            ?: nested?.string(
                                    "sizeLabel",
                                    "size_label",
                                    "size",
                                    "fileSize",
                                    "file_size"
                            ) ?: sizeNumberLabel() ?: nested?.sizeNumberLabel() ?: "",
            thumbnailUrl =
                    string(
                            "thumbnailUrl",
                            "thumbnail_url",
                            "thumbUrl",
                            "thumb_url",
                            "previewUrl",
                            "preview_url",
                            "imageUrl",
                            "image_url"
                    )
                            ?: nested?.string(
                                    "thumbnailUrl",
                                    "thumbnail_url",
                                    "thumbUrl",
                                    "thumb_url",
                                    "previewUrl",
                                    "preview_url",
                                    "imageUrl",
                                    "image_url"
                            )
    )
}

private fun JsonObject.toMessageType(): MessageType {
    val type = string("type", "messageType", "kind", "messageKind")?.lowercase()
    return if (type == "system" || type == "event" || systemEventType() != null) {
        MessageType.System
    } else {
        MessageType.Chat
    }
}

private fun JsonObject.systemEventType(): String? =
        string(
                "systemEventType",
                "system_event_type",
                "eventType",
                "event_type",
                "systemType",
                "subtype",
                "action"
        )
                ?: firstObject("systemEvent", "event")
                        ?.string("type", "eventType", "name", "action")

private fun JsonObject.systemMetadata(): Map<String, String> {
    val metadataObject = firstObject("metadata", "meta", "data", "payload") ?: return emptyMap()
    return metadataObject
            .entrySet()
            .mapNotNull { (key, value) ->
                val stringValue =
                        when {
                            value.isJsonPrimitive -> value.asJsonPrimitive.asString
                            value.isJsonObject ->
                                    value.asJsonObject.string("id", "name", "label", "value")
                            else -> null
                        }?.takeIf { it.isNotBlank() }
                stringValue?.let { key to it }
            }
            .toMap()
}

private fun JsonObject.toConversationOrDefault(
        threadId: ThreadId,
        name: String
): ConversationSummary =
        if (entrySet().isEmpty()) {
            ConversationSummary(
                    threadId = threadId,
                    channelId = ChannelId(threadId.value),
                    kind = ConversationKind.Channel,
                    name = name
            )
        } else {
            toConversation(ConversationKind.Channel)
        }

private fun JsonObject.unwrap(vararg names: String): JsonObject {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonObject) return value.asJsonObject
    }
    return this
}

private fun JsonObject.firstObject(vararg names: String): JsonObject? {
    for (name in names) {
        val value = get(name)
        if (value != null && value.isJsonObject) return value.asJsonObject
    }
    return null
}

private fun JsonObject.threadActionThreadChannelId(): String? =
        string(
                        "threadChannelId",
                        "thread_channel_id",
                        "replyThreadChannelId",
                        "reply_thread_channel_id",
                        "repliesThreadChannelId",
                        "replies_thread_channel_id",
                        "childThreadChannelId",
                        "child_thread_channel_id",
                        "replyChannelId",
                        "reply_channel_id",
                        "replyThreadId",
                        "reply_thread_id",
                        "commentsThreadChannelId",
                        "comments_thread_channel_id"
                )
                ?: firstObject("channel", "threadChannel", "replyThread", "thread")
                        ?.string(
                                "threadChannelId",
                                "thread_channel_id",
                                "channelId",
                                "channel_id",
                                "id",
                                "_id"
                        )

private fun ChannelId.toThreadActionBody(): RequestBody =
        mapOf("threadChannelId" to value, "channelId" to value).toJsonBody()

private fun MessageId.toParentMessageThreadBody(): RequestBody =
        mapOf("parentMessageId" to value).toJsonBody()

private fun sendMessageBody(
        threadId: ThreadId,
        body: String,
        attachmentIds: List<AttachmentId>,
        asTask: Boolean
): RequestBody =
        mapOf(
                        "channelId" to threadId.value,
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

private fun JsonObject.toServerUnreadSummarySnapshot(
        selectedServer: ServerSummary
): ServerUnreadSummarySnapshot {
    val summaries =
            items("servers", "items", "summaries").mapNotNull {
                it.asObject().toServerUnreadSummary()
            }
    val selectedSummary =
            summaries.firstOrNull { it.serverId == selectedServer.id }
                    ?: toServerUnreadSummary(defaultServerId = selectedServer.id)
    val totalUnread =
            int("totalUnreadCount", "totalUnread", "unreadTotal", "unreadCount")
                    ?: selectedSummary?.totalUnreadCount ?: summaries.sumOf { it.totalUnreadCount }
    return ServerUnreadSummarySnapshot(
            server =
                    selectedServer.copy(
                            unreadCount = selectedSummary?.totalUnreadCount ?: totalUnread
                    ),
            summaries = summaries.ifEmpty { selectedSummary?.let(::listOf).orEmpty() },
            selectedServerSummary = selectedSummary,
            totalUnreadCount = totalUnread,
            mentionCount = int("mentionCount", "mentions") ?: selectedSummary?.mentionCount ?: 0
    )
}

private fun JsonObject.toServerUnreadSummary(
        defaultServerId: ServerId? = null
): ServerUnreadSummary? {
    val serverId = string("serverId", "id")?.let(::ServerId) ?: defaultServerId ?: return null
    val channelUnread = int("channelUnreadCount", "channelsUnread", "channels") ?: 0
    val dmUnread =
            int("directMessageUnreadCount", "dmUnreadCount", "directMessagesUnread", "dms") ?: 0
    val mentionCount = int("mentionCount", "mentions") ?: 0
    val taskUnread = int("taskUnreadCount", "tasksUnread", "tasks") ?: 0
    val total =
            int("totalUnreadCount", "totalUnread", "unreadTotal", "unreadCount")
                    ?: (channelUnread + dmUnread + mentionCount + taskUnread)
    return ServerUnreadSummary(
            serverId = serverId,
            totalUnreadCount = total,
            channelUnreadCount = channelUnread,
            directMessageUnreadCount = dmUnread,
            mentionCount = mentionCount,
            taskUnreadCount = taskUnread,
            updatedAt = string("updatedAt", "lastActivityAt")
    )
}

private fun JsonObject.toChannelUnreadCounts(): Map<ChannelId, Int> {
    val unwrapped = unwrap("data", "unreadCounts", "counts")
    val directEntries =
            unwrapped.entrySet().mapNotNull { (key, value) ->
                val count =
                        when {
                            value.isJsonPrimitive -> value.asJsonPrimitive.asString.toIntOrNull()
                            value.isJsonObject ->
                                    value.asJsonObject.int("unreadCount", "unread", "count")
                            else -> null
                        }
                if (count == null) null else ChannelId(key) to count
            }
    if (directEntries.isNotEmpty()) return directEntries.toMap()
    return unwrapped
            .items("channels", "items", "counts")
            .mapNotNull { element ->
                val item = element.asObjectOrNull() ?: return@mapNotNull null
                val channelId =
                        item.string("channelId", "id", "threadChannelId") ?: return@mapNotNull null
                val count = item.int("unreadCount", "unread", "count") ?: 0
                ChannelId(channelId) to count
            }
            .toMap()
}

private fun JsonObject.toReadReceiptResult(
        fallbackChannelId: ChannelId? = null,
        fallbackSeq: Long? = null
): ReadReceiptResult {
    val unwrapped = unwrap("data", "result")
    return ReadReceiptResult(
            channelId = unwrapped.string("channelId", "id")?.let(::ChannelId) ?: fallbackChannelId,
            seq = unwrapped.long("seq", "readSeq", "lastReadSeq") ?: fallbackSeq,
            unreadCount = unwrapped.int("unreadCount", "unread", "count") ?: 0,
            changed = unwrapped.boolean("changed", "ok", "success") ?: true
    )
}

private fun String.toTaskStatus(): TaskStatus? =
    when (lowercase()) {
        "todo", "to_do", "open" -> TaskStatus.Todo
        "in_progress", "inprogress", "progress" -> TaskStatus.InProgress
        "in_review", "inreview", "review" -> TaskStatus.InReview
        "done", "complete", "completed" -> TaskStatus.Done
        "closed" -> TaskStatus.Closed
        else -> null
    }

private fun TaskStatus.toApiValue(): String =
        when (this) {
            TaskStatus.Todo -> "todo"
            TaskStatus.InProgress -> "in_progress"
            TaskStatus.InReview -> "in_review"
            TaskStatus.Done -> "done"
            TaskStatus.Closed -> "closed"
        }
