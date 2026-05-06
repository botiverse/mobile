package ai.slock.android.core.data.repository

import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.RealtimeEvent

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
    suspend fun getServerSettings(): DataResult<SettingsServerSnapshot>
    suspend fun getMobileSettings(): DataResult<SettingsMobileSnapshot>
}

interface ConversationRepository {
    suspend fun getThread(threadId: ThreadId): DataResult<ThreadSnapshot>
    suspend fun getMessageContext(messageId: ai.slock.android.core.model.MessageId): DataResult<ThreadSnapshot>
    suspend fun loadOlderMessages(threadId: ThreadId, beforeSeq: Long): DataResult<ThreadSnapshot>
    suspend fun loadNewerMessages(threadId: ThreadId, afterSeq: Long): DataResult<ThreadSnapshot>
    suspend fun sendMessage(
        threadId: ThreadId,
        body: String,
        attachmentIds: List<ai.slock.android.core.model.AttachmentId> = emptyList(),
        asTask: Boolean = false
    ): DataResult<SendMessageResult>
}

interface TaskRepository {
    suspend fun getTasks(serverId: ServerId, filter: TaskFilter): DataResult<TaskSnapshot>
    suspend fun claimTask(taskId: TaskId): DataResult<TaskActionResult>
}

interface MemberRepository {
    suspend fun getMembers(serverId: ServerId): DataResult<MemberSnapshot>
}

interface AgentRepository {
    suspend fun getAgents(serverId: ServerId): DataResult<AgentSnapshot>
}

interface ChannelRepository {
    suspend fun getHome(serverId: ServerId): DataResult<HomeSnapshot>
    suspend fun getChannels(serverId: ServerId): DataResult<ChannelSnapshot>
    suspend fun getDirectMessages(serverId: ServerId): DataResult<DirectMessageSnapshot>
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
}

interface SearchRepository {
    suspend fun search(
        serverId: ServerId,
        query: String,
        offset: Int = 0,
        limit: Int = DEFAULT_PAGE_LIMIT
    ): DataResult<SearchSnapshot>
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
    Unread("unread")
}

const val DEFAULT_PAGE_LIMIT = 30

data class AccountProfileUpdateRequest(
    val displayName: String,
    val handle: String,
    val email: String
)

data class CreateServerRequest(
    val name: String,
    val slug: String? = null,
    val region: String? = null
)
