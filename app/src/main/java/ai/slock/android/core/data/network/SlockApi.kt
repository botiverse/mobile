package ai.slock.android.core.data.network

import ai.slock.compose.network.SlockRequestPriority
import ai.slock.compose.network.SlockRequestPriorityElement
import kotlin.coroutines.coroutineContext
import okhttp3.RequestBody

interface SlockApi {
    suspend fun settingsAccount(priority: SlockRequestPriority): ApiResponse
    suspend fun updateSettingsAccount(priority: SlockRequestPriority, body: RequestBody): ApiResponse
    suspend fun settingsSessions(priority: SlockRequestPriority): ApiResponse
    suspend fun revokeSettingsSession(
        priority: SlockRequestPriority,
        sessionId: String
    ): ApiResponse
    suspend fun settingsMachines(
        priority: SlockRequestPriority,
        serverId: String
    ): ApiResponse
    suspend fun settingsMachineWorkspaces(
        priority: SlockRequestPriority,
        serverId: String,
        machineId: String
    ): ApiResponse
    suspend fun settingsAgents(priority: SlockRequestPriority): ApiResponse
    suspend fun updateAgent(
        priority: SlockRequestPriority,
        agentId: String,
        body: RequestBody
    ): ApiResponse
    suspend fun channels(
        priority: SlockRequestPriority,
        archived: String? = null
    ): ApiResponse
    suspend fun serverMembers(
        priority: SlockRequestPriority,
        serverId: String
    ): ApiResponse
    suspend fun updateSettingsServerProfile(
        priority: SlockRequestPriority,
        serverId: String,
        body: RequestBody
    ): ApiResponse
    suspend fun settingsServerUsage(
        priority: SlockRequestPriority,
        serverId: String
    ): ApiResponse
    suspend fun settingsBillingSubscription(priority: SlockRequestPriority): ApiResponse
    suspend fun createSettingsServerInvite(
        priority: SlockRequestPriority,
        serverId: String,
        body: RequestBody
    ): ApiResponse
    suspend fun settingsServerOnboardingSettings(
        priority: SlockRequestPriority,
        serverId: String
    ): ApiResponse
    suspend fun updateSettingsServerOnboardingSettings(
        priority: SlockRequestPriority,
        serverId: String,
        body: RequestBody
    ): ApiResponse
    suspend fun deleteSettingsServer(
        priority: SlockRequestPriority,
        serverId: String
    ): ApiResponse
    suspend fun memberProfile(
        priority: SlockRequestPriority,
        memberId: String
    ): ApiResponse
    suspend fun agentWorkspaceFiles(
        priority: SlockRequestPriority,
        agentId: String,
        dirPath: String? = null
    ): ApiResponse
    suspend fun readAgentWorkspaceFile(
        priority: SlockRequestPriority,
        agentId: String,
        path: String
    ): ApiResponse
    suspend fun agentDirectMessages(
        priority: SlockRequestPriority,
        agentId: String
    ): ApiResponse
    suspend fun reminders(
        priority: SlockRequestPriority,
        ownerAgentId: String,
        status: String = "scheduled"
    ): ApiResponse
    suspend fun agentActivityLog(
        priority: SlockRequestPriority,
        agentId: String,
        limit: Int = 50
    ): ApiResponse
    suspend fun channelMembers(
        priority: SlockRequestPriority,
        channelId: String
    ): ApiResponse
    suspend fun createChannel(priority: SlockRequestPriority, body: RequestBody): ApiResponse
    suspend fun joinChannel(
        priority: SlockRequestPriority,
        channelId: String
    ): ApiResponse
    suspend fun archiveChannel(
        priority: SlockRequestPriority,
        channelId: String
    ): ApiResponse
    suspend fun deleteChannel(
        priority: SlockRequestPriority,
        channelId: String
    ): ApiResponse
    suspend fun addChannelMember(
        priority: SlockRequestPriority,
        channelId: String,
        body: RequestBody
    ): ApiResponse
    suspend fun removeChannelMember(
        priority: SlockRequestPriority,
        channelId: String,
        kind: String,
        memberId: String
    ): ApiResponse
    suspend fun directMessages(priority: SlockRequestPriority): ApiResponse
    suspend fun openDirectMessage(priority: SlockRequestPriority, body: RequestBody): ApiResponse
    suspend fun channelUnreadCounts(priority: SlockRequestPriority): ApiResponse
    suspend fun markChannelReadToSeq(
        priority: SlockRequestPriority,
        channelId: String,
        body: RequestBody
    ): ApiResponse
    suspend fun markChannelReadAll(
        priority: SlockRequestPriority,
        channelId: String
    ): ApiResponse
    suspend fun markChannelUnread(
        priority: SlockRequestPriority,
        channelId: String
    ): ApiResponse
    suspend fun inbox(
        priority: SlockRequestPriority,
        filter: String,
        limit: Int,
        offset: Int
    ): ApiResponse
    suspend fun markInboxReadAll(priority: SlockRequestPriority): ApiResponse
    suspend fun serverTasks(priority: SlockRequestPriority): ApiResponse
    suspend fun channelTasks(
        priority: SlockRequestPriority,
        channelId: String
    ): ApiResponse
    suspend fun claimTask(
        priority: SlockRequestPriority,
        taskId: String
    ): ApiResponse
    suspend fun updateTaskStatus(
        priority: SlockRequestPriority,
        taskId: String,
        body: RequestBody
    ): ApiResponse
    suspend fun saved(
        priority: SlockRequestPriority,
        limit: Int,
        offset: Int
    ): ApiResponse
    suspend fun saveMessage(
        priority: SlockRequestPriority,
        messageId: String
    ): ApiResponse
    suspend fun unsaveMessage(
        priority: SlockRequestPriority,
        messageId: String
    ): ApiResponse
    suspend fun searchMessages(
        priority: SlockRequestPriority,
        query: String,
        limit: Int,
        offset: Int
    ): ApiResponse
    suspend fun channelMessages(
        priority: SlockRequestPriority,
        channelId: String,
        limit: Int,
        beforeSeq: Long? = null
    ): ApiResponse
    suspend fun messageContext(
        priority: SlockRequestPriority,
        messageId: String
    ): ApiResponse
    suspend fun messageSync(
        priority: SlockRequestPriority,
        sinceSeq: Long,
        limit: Int,
        channelId: String? = null
    ): ApiResponse
    suspend fun sendMessage(priority: SlockRequestPriority, body: RequestBody): ApiResponse
    suspend fun uploadAttachments(
        priority: SlockRequestPriority,
        channelId: RequestBody,
        files: List<okhttp3.MultipartBody.Part>
    ): ApiResponse
    suspend fun attachmentUrl(
        priority: SlockRequestPriority,
        attachmentId: String
    ): ApiResponse
    suspend fun channelFiles(
        priority: SlockRequestPriority,
        channelId: String,
        limit: Int = 50,
        cursor: String? = null
    ): ApiResponse
    suspend fun channelThreads(
        priority: SlockRequestPriority,
        channelId: String,
        limit: Int
    ): ApiResponse
    suspend fun createOrOpenThread(
        priority: SlockRequestPriority,
        channelId: String,
        body: RequestBody
    ): ApiResponse
    suspend fun followedThreads(
        priority: SlockRequestPriority,
        limit: Int
    ): ApiResponse
    suspend fun followThread(priority: SlockRequestPriority, body: RequestBody): ApiResponse
    suspend fun unfollowThread(priority: SlockRequestPriority, body: RequestBody): ApiResponse
    suspend fun markThreadDone(priority: SlockRequestPriority, body: RequestBody): ApiResponse
    suspend fun markThreadUndone(priority: SlockRequestPriority, body: RequestBody): ApiResponse
    suspend fun serverUnreadSummary(priority: SlockRequestPriority): ApiResponse
}

internal suspend fun currentSlockApiPriority(): SlockRequestPriority =
        coroutineContext[SlockRequestPriorityElement]?.priority
                ?: SlockRequestPriority.Visible
