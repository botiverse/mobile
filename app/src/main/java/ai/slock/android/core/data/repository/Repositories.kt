package ai.slock.android.core.data.repository

import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.compose.data.DataResult
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

typealias SettingsRepository = ai.slock.compose.data.SettingsRepository

interface ConversationRepository {
    suspend fun getThread(
        threadId: ThreadId,
        includeFollowedSummaries: Boolean = true
    ): DataResult<ThreadSnapshot>
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

typealias TaskRepository = ai.slock.compose.data.TaskRepository

typealias MemberRepository = ai.slock.compose.data.MemberRepository

interface AgentRepository {
    suspend fun getAgents(serverId: ServerId): DataResult<AgentSnapshot>
}

typealias ChannelRepository = ai.slock.compose.data.ChannelRepository

typealias InboxRepository = ai.slock.compose.data.InboxRepository

typealias SavedRepository = ai.slock.compose.data.SavedRepository

typealias SearchRepository = ai.slock.compose.data.SearchRepository

typealias SearchQueryOptions = ai.slock.compose.data.SearchQueryOptions

typealias SearchSort = ai.slock.compose.data.SearchSort

typealias InboxFilter = ai.slock.compose.data.InboxFilter

typealias ServerUnreadRepository = ai.slock.compose.data.ServerUnreadRepository

interface RealtimeRepository {
    suspend fun connect(serverId: ServerId, lastSeq: Long = 0): DataResult<Unit>
    fun disconnect()
    fun joinChannel(channelId: ChannelId)
    fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription
}

fun interface RealtimeSubscription {
    fun close()
}

typealias TaskFilter = ai.slock.compose.data.TaskFilter

const val DEFAULT_PAGE_LIMIT = 30

typealias AccountProfileUpdateRequest = ai.slock.compose.data.AccountProfileUpdateRequest

typealias SettingsServerProfileUpdateRequest = ai.slock.compose.data.SettingsServerProfileUpdateRequest

typealias SettingsServerInviteCreateRequest = ai.slock.compose.data.SettingsServerInviteCreateRequest

typealias SettingsServerDeleteRequest = ai.slock.compose.data.SettingsServerDeleteRequest

typealias SettingsServerActionResult = ai.slock.compose.data.SettingsServerActionResult

typealias ChannelLeaveResult = ai.slock.compose.data.ChannelLeaveResult

data class CreateServerRequest(
    val name: String,
    val slug: String? = null,
    val region: String? = null
)

typealias CreateChannelRequest = ai.slock.compose.data.CreateChannelRequest

typealias AddChannelMemberRequest = ai.slock.compose.data.AddChannelMemberRequest

typealias RemoveChannelMemberRequest = ai.slock.compose.data.RemoveChannelMemberRequest

typealias AgentProfileUpdateRequest = ai.slock.compose.data.AgentProfileUpdateRequest

typealias ChannelMemberActionResult = ai.slock.compose.data.ChannelMemberActionResult
