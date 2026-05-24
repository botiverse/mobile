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
import ai.slock.compose.data.SlockNetworkClient
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
        store: AuthSessionStore,
        networkClient: SlockNetworkClient,
        warningLogger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w(
                    "SlockHomeData",
                    message
            )
        }
) : ChannelRepository {
    private val delegate =
            ai.slock.compose.data.RealChannelRepository(
                    sessionStore = ComposeAuthSessionStoreAdapter(store),
                    networkClient = networkClient,
                    warningLogger = warningLogger
            )

    override suspend fun getHome(serverId: ServerId): DataResult<HomeSnapshot> =
            delegate.getHome(serverId)

    override suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot> =
            delegate.getChannels(serverId)

    override suspend fun getDirectMessages(serverId: ServerId): DataResult<DirectMessageSnapshot> =
            delegate.getDirectMessages(serverId)

    override suspend fun openAgentDirectMessage(agentId: MemberId): DataResult<OpenDirectMessageResult> =
            delegate.openAgentDirectMessage(agentId)

    override suspend fun openUserDirectMessage(userId: MemberId): DataResult<OpenDirectMessageResult> =
            delegate.openUserDirectMessage(userId)

    override suspend fun createChannel(request: CreateChannelRequest): DataResult<CreateChannelResult> =
            delegate.createChannel(request.toSharedRequest())

    override suspend fun joinChannel(channelId: ChannelId): DataResult<ChannelJoinResult> =
            delegate.joinChannel(channelId)

    override suspend fun leaveChannel(channelId: ChannelId): DataResult<ChannelLeaveResult> =
            delegate.leaveChannel(channelId).toAndroidChannelLeaveResult()

    override suspend fun archiveChannel(channelId: ChannelId): DataResult<ChannelArchiveResult> =
            delegate.archiveChannel(channelId)

    override suspend fun deleteChannel(channelId: ChannelId): DataResult<ChannelDeleteResult> =
            delegate.deleteChannel(channelId)
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
        store: AuthSessionStore,
        networkClient: SlockNetworkClient,
        warningLogger: (String) -> Unit = { message ->
            ai.slock.android.core.data.diagnostics.SlockDiagnosticsLogger.w("SlockMembers", message)
        },
        logger: (String) -> Unit = {}
) : MemberRepository {
    private val delegate =
            ai.slock.compose.data.RealMemberRepository(
                    sessionStore = ComposeAuthSessionStoreAdapter(store),
                    networkClient = networkClient,
                    warningLogger = warningLogger,
                    logger = logger
            )

    override suspend fun getMembers(
            priority: SlockRequestPriority,
            serverId: ServerId
    ): DataResult<MemberSnapshot> =
            delegate.getMembers(priority.toDataRequestPriority(), serverId)

    override suspend fun getMemberProfile(
            priority: SlockRequestPriority,
            memberId: MemberId
    ): DataResult<MemberProfile> =
            delegate.getMemberProfile(priority.toDataRequestPriority(), memberId)

    override suspend fun updateAgentProfile(
            priority: SlockRequestPriority,
            request: AgentProfileUpdateRequest
    ): DataResult<MemberProfile> =
            delegate.updateAgentProfile(priority.toDataRequestPriority(), request.toSharedRequest())

    override suspend fun getAgentDetail(
            priority: SlockRequestPriority,
            agentId: MemberId
    ): DataResult<AgentDetailSnapshot> =
            delegate.getAgentDetail(priority.toDataRequestPriority(), agentId)

    override suspend fun getAgentDirectMessages(
            priority: SlockRequestPriority,
            agentId: MemberId
    ): DataResult<AgentDmSnapshot> =
            delegate.getAgentDirectMessages(priority.toDataRequestPriority(), agentId)

    override suspend fun getAgentReminders(
            priority: SlockRequestPriority,
            agentId: MemberId
    ): DataResult<AgentReminderSnapshot> =
            delegate.getAgentReminders(priority.toDataRequestPriority(), agentId)

    override suspend fun getAgentActivity(
            priority: SlockRequestPriority,
            agentId: MemberId
    ): DataResult<AgentActivitySnapshot> =
            delegate.getAgentActivity(priority.toDataRequestPriority(), agentId)

    override suspend fun getAgentWorkspaces(
            priority: SlockRequestPriority,
            agentId: MemberId
    ): DataResult<AgentWorkspaceSnapshot> =
            delegate.getAgentWorkspaces(priority.toDataRequestPriority(), agentId)

    override suspend fun getAgentWorkspaceTree(
            priority: SlockRequestPriority,
            agentId: MemberId
    ): DataResult<AgentWorkspaceTreeSnapshot> =
            delegate.getAgentWorkspaceTree(priority.toDataRequestPriority(), agentId)

    override suspend fun getAgentWorkspaceChildren(
            priority: SlockRequestPriority,
            agentId: MemberId,
            dirPath: String
    ): DataResult<AgentWorkspaceChildrenSnapshot> =
            delegate.getAgentWorkspaceChildren(priority.toDataRequestPriority(), agentId, dirPath)

    override suspend fun readAgentWorkspaceFile(
            priority: SlockRequestPriority,
            agentId: MemberId,
            path: String
    ): DataResult<AgentWorkspaceFilePreviewSnapshot> =
            delegate.readAgentWorkspaceFile(priority.toDataRequestPriority(), agentId, path)

    override suspend fun getAgentSkills(
            priority: SlockRequestPriority,
            agentId: MemberId
    ): DataResult<AgentSkillsSnapshot> =
            delegate.getAgentSkills(priority.toDataRequestPriority(), agentId)

    override suspend fun getChannelMembers(
            priority: SlockRequestPriority,
            channelId: ChannelId
    ): DataResult<ChannelMemberSnapshot> =
            delegate.getChannelMembers(priority.toDataRequestPriority(), channelId)

    override suspend fun addChannelMember(
            priority: SlockRequestPriority,
            request: AddChannelMemberRequest
    ): DataResult<ChannelMemberActionResult> =
            delegate.addChannelMember(priority.toDataRequestPriority(), request.toSharedRequest())
                    .toAndroidChannelMemberActionResult()

    override suspend fun removeChannelMember(
            priority: SlockRequestPriority,
            request: RemoveChannelMemberRequest
    ): DataResult<ChannelMemberActionResult> =
            delegate.removeChannelMember(priority.toDataRequestPriority(), request.toSharedRequest())
                    .toAndroidChannelMemberActionResult()
}

private fun SlockRequestPriority.toDataRequestPriority(): DataRequestPriority =
        when (this) {
            SlockRequestPriority.Foreground -> DataRequestPriority.Foreground
            SlockRequestPriority.Visible -> DataRequestPriority.Visible
            SlockRequestPriority.Background -> DataRequestPriority.Background
        }

private fun CreateChannelRequest.toSharedRequest(): ai.slock.compose.data.CreateChannelRequest =
        ai.slock.compose.data.CreateChannelRequest(
                name = name,
                description = description,
                agentMemberIds = agentMemberIds,
                userMemberIds = userMemberIds
        )

private fun DataResult<ai.slock.compose.data.ChannelLeaveResult>.toAndroidChannelLeaveResult():
        DataResult<ChannelLeaveResult> =
        when (this) {
            is DataResult.Content ->
                    DataResult.Content(
                            ChannelLeaveResult(
                                    channelId = data.channelId,
                                    left = data.left
                            )
                    )
            is DataResult.Empty -> this
            is DataResult.Error -> this
            is DataResult.NoPermission -> this
        }

private fun AgentProfileUpdateRequest.toSharedRequest(): ai.slock.compose.data.AgentProfileUpdateRequest =
        ai.slock.compose.data.AgentProfileUpdateRequest(
                agentId = agentId,
                displayName = displayName,
                description = description
        )

private fun AddChannelMemberRequest.toSharedRequest(): ai.slock.compose.data.AddChannelMemberRequest =
        ai.slock.compose.data.AddChannelMemberRequest(
                channelId = channelId,
                memberId = memberId,
                kind = kind
        )

private fun RemoveChannelMemberRequest.toSharedRequest(): ai.slock.compose.data.RemoveChannelMemberRequest =
        ai.slock.compose.data.RemoveChannelMemberRequest(
                channelId = channelId,
                memberId = memberId,
                kind = kind
        )

private fun DataResult<ai.slock.compose.data.ChannelMemberActionResult>.toAndroidChannelMemberActionResult(): DataResult<ChannelMemberActionResult> =
        when (this) {
            is DataResult.Content ->
                    DataResult.Content(
                            ChannelMemberActionResult(
                                    channelId = data.channelId,
                                    memberId = data.memberId,
                                    member = data.member,
                                    added = data.added
                            )
                    )
            is DataResult.Empty -> this
            is DataResult.Error -> this
            is DataResult.NoPermission -> this
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
