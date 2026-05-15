package ai.slock.android.core.data.repository

import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.compose.network.SlockRequestPriority

interface AuthSessionRepository {
    suspend fun currentSession(): DataResult<ai.slock.android.core.model.SessionSnapshot>
}

interface ServerRepository {
    suspend fun getServers(): DataResult<ServerListSnapshot>
    suspend fun selectServer(serverId: ServerId): DataResult<ServerListSnapshot>
    suspend fun createServer(request: CreateServerRequest): DataResult<ServerListSnapshot>
    suspend fun getHomeSnapshot(serverId: ServerId): DataResult<HomeSnapshot>
    suspend fun getInbox(serverId: ServerId): DataResult<InboxSnapshot>
    suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot>
    suspend fun getSettings(serverId: ServerId): DataResult<SettingsSnapshot>
}

interface SettingsRepository {
    suspend fun getAccount(): DataResult<AccountSettingsSnapshot>
    suspend fun updateProfile(request: AccountProfileUpdateRequest): DataResult<AccountSettingsSnapshot>
    suspend fun getSessions(): DataResult<SessionSettingsSnapshot>
    suspend fun revokeSession(sessionId: String): DataResult<SessionActionResult>
    suspend fun getComputers(): DataResult<SettingsComputersSnapshot>
    suspend fun getComputerDetail(machineId: String): DataResult<SettingsComputerDetailSnapshot>
    suspend fun getBilling(): DataResult<SettingsBillingSnapshot>
    suspend fun getServerSettings(): DataResult<SettingsServerSnapshot>
    suspend fun updateServerProfile(request: SettingsServerProfileUpdateRequest): DataResult<SettingsServerSnapshot>
    suspend fun createServerInvite(request: SettingsServerInviteCreateRequest): DataResult<SettingsServerActionResult>
    suspend fun deleteServer(request: SettingsServerDeleteRequest): DataResult<SettingsServerActionResult>
    suspend fun getMobileSettings(): DataResult<SettingsMobileSnapshot>
}

interface ConversationRepository {
    suspend fun getThread(threadId: ThreadId): DataResult<ThreadSnapshot>
    suspend fun getMessageContext(messageId: ai.slock.android.core.model.MessageId): DataResult<ThreadSnapshot>
    suspend fun loadOlderMessages(threadId: ThreadId, beforeSeq: Long): DataResult<ThreadSnapshot>
    suspend fun loadNewerMessages(threadId: ThreadId, afterSeq: Long): DataResult<ThreadSnapshot>
    suspend fun getChannelThreads(channelId: ChannelId, limit: Int = DEFAULT_PAGE_LIMIT): DataResult<ThreadListSnapshot>
    suspend fun getFollowedThreads(serverId: ServerId, limit: Int = DEFAULT_PAGE_LIMIT): DataResult<ThreadListSnapshot>
    suspend fun createOrOpenThread(channelId: ChannelId, parentMessageId: ai.slock.android.core.model.MessageId): DataResult<ThreadActionResult>
    suspend fun followThread(parentMessageId: ai.slock.android.core.model.MessageId): DataResult<ThreadActionResult>
    suspend fun followThread(threadChannelId: ChannelId): DataResult<ThreadActionResult>
    suspend fun unfollowThread(threadChannelId: ChannelId): DataResult<ThreadActionResult>
    suspend fun markThreadDone(threadChannelId: ChannelId): DataResult<ThreadActionResult>
    suspend fun markThreadUndone(threadChannelId: ChannelId): DataResult<ThreadActionResult>
    suspend fun uploadAttachments(request: AttachmentUploadRequest): DataResult<AttachmentUploadResult>
    suspend fun getAttachmentOpenTarget(
        attachmentId: ai.slock.android.core.model.AttachmentId
    ): DataResult<AttachmentOpenTarget>
    suspend fun getChannelFiles(
        channelId: ChannelId,
        limit: Int = 50,
        cursor: String? = null
    ): DataResult<ChannelFilesSnapshot>
    suspend fun sendMessage(
        channelId: ChannelId,
        body: String,
        attachmentIds: List<ai.slock.android.core.model.AttachmentId> = emptyList(),
        asTask: Boolean = false
    ): DataResult<SendMessageResult>
}

interface TaskRepository {
    suspend fun getTasks(serverId: ServerId, filter: TaskFilter): DataResult<TaskSnapshot>
    suspend fun getChannelTasks(
        serverId: ServerId,
        channelId: ChannelId,
        filter: TaskFilter
    ): DataResult<TaskSnapshot>
    suspend fun claimTask(taskId: TaskId): DataResult<TaskActionResult>
    suspend fun updateTaskStatus(
        taskId: TaskId,
        status: ai.slock.android.core.model.TaskStatus
    ): DataResult<TaskActionResult>
}

interface MemberRepository {
    suspend fun getMembers(
        priority: SlockRequestPriority,
        serverId: ServerId
    ): DataResult<MemberSnapshot>
    suspend fun getMemberProfile(
        priority: SlockRequestPriority,
        memberId: MemberId
    ): DataResult<MemberProfile>
    suspend fun updateAgentProfile(
        priority: SlockRequestPriority,
        request: AgentProfileUpdateRequest
    ): DataResult<MemberProfile>
    suspend fun getAgentDetail(
        priority: SlockRequestPriority,
        agentId: MemberId
    ): DataResult<AgentDetailSnapshot>
    suspend fun getAgentDirectMessages(
        priority: SlockRequestPriority,
        agentId: MemberId
    ): DataResult<AgentDmSnapshot>
    suspend fun getAgentReminders(
        priority: SlockRequestPriority,
        agentId: MemberId
    ): DataResult<AgentReminderSnapshot>
    suspend fun getAgentActivity(
        priority: SlockRequestPriority,
        agentId: MemberId
    ): DataResult<AgentActivitySnapshot>
    suspend fun getAgentWorkspaces(
        priority: SlockRequestPriority,
        agentId: MemberId
    ): DataResult<AgentWorkspaceSnapshot>
    suspend fun getAgentWorkspaceTree(
        priority: SlockRequestPriority,
        agentId: MemberId
    ): DataResult<AgentWorkspaceTreeSnapshot>
    suspend fun getAgentWorkspaceChildren(
        priority: SlockRequestPriority,
        agentId: MemberId,
        dirPath: String
    ): DataResult<AgentWorkspaceChildrenSnapshot>
    suspend fun readAgentWorkspaceFile(
        priority: SlockRequestPriority,
        agentId: MemberId,
        path: String
    ): DataResult<AgentWorkspaceFilePreviewSnapshot>
    suspend fun getAgentSkills(
        priority: SlockRequestPriority,
        agentId: MemberId
    ): DataResult<AgentSkillsSnapshot>
    suspend fun getChannelMembers(
        priority: SlockRequestPriority,
        channelId: ChannelId
    ): DataResult<ChannelMemberSnapshot>
    suspend fun addChannelMember(
        priority: SlockRequestPriority,
        request: AddChannelMemberRequest
    ): DataResult<ChannelMemberActionResult>
    suspend fun removeChannelMember(
        priority: SlockRequestPriority,
        request: RemoveChannelMemberRequest
    ): DataResult<ChannelMemberActionResult>
}

interface AgentRepository {
    suspend fun getAgents(serverId: ServerId): DataResult<AgentSnapshot>
}

interface ChannelRepository {
    suspend fun getHome(serverId: ServerId): DataResult<HomeSnapshot>
    suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot>
    suspend fun getDirectMessages(serverId: ServerId): DataResult<DirectMessageSnapshot>
    suspend fun openAgentDirectMessage(agentId: MemberId): DataResult<OpenDirectMessageResult>
    suspend fun openUserDirectMessage(userId: MemberId): DataResult<OpenDirectMessageResult>
    suspend fun createChannel(request: CreateChannelRequest): DataResult<CreateChannelResult>
    suspend fun joinChannel(channelId: ChannelId): DataResult<ChannelJoinResult>
    suspend fun archiveChannel(channelId: ChannelId): DataResult<ChannelArchiveResult>
    suspend fun deleteChannel(channelId: ChannelId): DataResult<ChannelDeleteResult>
}

interface ReadReceiptRepository {
    suspend fun getUnreadCounts(serverId: ServerId): DataResult<ChannelUnreadSnapshot>
    suspend fun markChannelReadToSeq(channelId: ChannelId, seq: Long): DataResult<ReadReceiptResult>
    suspend fun markChannelReadAll(channelId: ChannelId): DataResult<ReadReceiptResult>
    suspend fun markChannelUnread(channelId: ChannelId): DataResult<ReadReceiptResult>
    suspend fun markInboxReadAll(serverId: ServerId): DataResult<ReadReceiptResult>
}

interface InboxRepository {
    suspend fun loadInitial(
        serverId: ServerId,
        filter: InboxFilter = InboxFilter.All,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<InboxSnapshot>

    suspend fun loadMore(
        serverId: ServerId,
        filter: InboxFilter = InboxFilter.All,
        offset: Int,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<InboxSnapshot>

    suspend fun refresh(
        serverId: ServerId,
        filter: InboxFilter = InboxFilter.All,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<InboxSnapshot> = loadInitial(serverId, filter, limit)
}

interface SavedRepository {
    suspend fun loadInitial(
        serverId: ServerId,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<SavedSnapshot>

    suspend fun loadMore(
        serverId: ServerId,
        offset: Int,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<SavedSnapshot>

    suspend fun saveMessage(
        serverId: ServerId,
        messageId: ai.slock.android.core.model.MessageId
    ): DataResult<SavedMessageMutationResult>

    suspend fun unsaveMessage(
        serverId: ServerId,
        messageId: ai.slock.android.core.model.MessageId
    ): DataResult<SavedMessageMutationResult>
}

interface SearchRepository {
    suspend fun search(
        serverId: ServerId,
        query: String,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT,
        options: SearchQueryOptions = SearchQueryOptions()
    ): DataResult<SearchSnapshot>
}

data class SearchQueryOptions(
    val sort: SearchSort = SearchSort.Relevance,
    val senderId: String? = null,
    val channelId: ChannelId? = null,
    val afterIso: String? = null,
    val beforeIso: String? = null
)

enum class SearchSort {
    Relevance,
    Recent;

    val wireValue: String?
        get() =
            when (this) {
                Relevance -> null
                Recent -> "recent"
            }
}

interface ServerUnreadRepository {
    suspend fun getUnreadSummary(serverId: ServerId): DataResult<ServerUnreadSummarySnapshot>
}

interface RealtimeRepository {
    suspend fun connect(serverId: ServerId, lastSeq: Long = 0): DataResult<Unit>
    fun disconnect()
    fun joinChannel(channelId: ChannelId)
    fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription
}

fun interface RealtimeSubscription {
    fun close()
}

data class TaskFilter(
    val statuses: Set<ai.slock.android.core.model.TaskStatus> = emptySet(),
    val assignedToMeOnly: Boolean = false
)

enum class InboxFilter(val wireValue: String) {
    All("all"),
    Unread("unread"),
    Mentions("mentions")
}

const val DEFAULT_PAGE_LIMIT = 30

data class AccountProfileUpdateRequest(
    val displayName: String,
    val handle: String,
    val email: String
)

data class SettingsServerProfileUpdateRequest(
    val name: String
)

data class SettingsServerInviteCreateRequest(
    val email: String
)

data class SettingsServerDeleteRequest(
    val confirmationName: String
)

data class SettingsServerActionResult(
    val success: Boolean,
    val message: String? = null
)

data class CreateServerRequest(
    val name: String,
    val slug: String? = null,
    val region: String? = null
)

data class CreateChannelRequest(
    val name: String,
    val description: String? = null,
    val agentMemberIds: List<String> = emptyList(),
    val userMemberIds: List<String> = emptyList()
)

data class AddChannelMemberRequest(
    val channelId: ChannelId,
    val memberId: MemberId,
    val kind: MemberKind
)

data class RemoveChannelMemberRequest(
    val channelId: ChannelId,
    val memberId: MemberId,
    val kind: MemberKind
)

data class AgentProfileUpdateRequest(
    val agentId: MemberId,
    val displayName: String,
    val description: String?
)

data class ChannelMemberActionResult(
    val channelId: ChannelId,
    val memberId: MemberId,
    val member: MemberProfile? = null,
    val added: Boolean = true
)
