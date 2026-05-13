package ai.slock.android.core.data.network

import okhttp3.RequestBody

interface SlockApi {
    suspend fun settingsAccount(): ApiResponse
    suspend fun updateSettingsAccount(body: RequestBody): ApiResponse
    suspend fun settingsSessions(): ApiResponse
    suspend fun revokeSettingsSession(
        sessionId: String
    ): ApiResponse
    suspend fun settingsMachines(
        serverId: String
    ): ApiResponse
    suspend fun settingsMachineWorkspaces(
        serverId: String,
        machineId: String
    ): ApiResponse
    suspend fun settingsAgents(): ApiResponse
    suspend fun updateAgent(
        agentId: String,
        body: RequestBody
    ): ApiResponse
    suspend fun channels(
        archived: String? = null
    ): ApiResponse
    suspend fun serverMembers(
        serverId: String
    ): ApiResponse
    suspend fun updateSettingsServerProfile(
        serverId: String,
        body: RequestBody
    ): ApiResponse
    suspend fun settingsServerUsage(
        serverId: String
    ): ApiResponse
    suspend fun settingsBillingSubscription(): ApiResponse
    suspend fun createSettingsServerInvite(
        serverId: String,
        body: RequestBody
    ): ApiResponse
    suspend fun settingsServerOnboardingSettings(
        serverId: String
    ): ApiResponse
    suspend fun updateSettingsServerOnboardingSettings(
        serverId: String,
        body: RequestBody
    ): ApiResponse
    suspend fun deleteSettingsServer(
        serverId: String
    ): ApiResponse
    suspend fun memberProfile(
        memberId: String
    ): ApiResponse
    suspend fun agentWorkspaceFiles(
        agentId: String,
        dirPath: String? = null
    ): ApiResponse
    suspend fun readAgentWorkspaceFile(
        agentId: String,
        path: String
    ): ApiResponse
    suspend fun agentDirectMessages(
        agentId: String
    ): ApiResponse
    suspend fun reminders(
        ownerAgentId: String,
        status: String = "scheduled"
    ): ApiResponse
    suspend fun agentActivityLog(
        agentId: String,
        limit: Int = 50
    ): ApiResponse
    suspend fun channelMembers(
        channelId: String
    ): ApiResponse
    suspend fun createChannel(body: RequestBody): ApiResponse
    suspend fun joinChannel(
        channelId: String
    ): ApiResponse
    suspend fun archiveChannel(
        channelId: String
    ): ApiResponse
    suspend fun deleteChannel(
        channelId: String
    ): ApiResponse
    suspend fun addChannelMember(
        channelId: String,
        body: RequestBody
    ): ApiResponse
    suspend fun removeChannelMember(
        channelId: String,
        kind: String,
        memberId: String
    ): ApiResponse
    suspend fun directMessages(): ApiResponse
    suspend fun openDirectMessage(body: RequestBody): ApiResponse
    suspend fun channelUnreadCounts(): ApiResponse
    suspend fun markChannelReadToSeq(
        channelId: String,
        body: RequestBody
    ): ApiResponse
    suspend fun markChannelReadAll(
        channelId: String
    ): ApiResponse
    suspend fun markChannelUnread(
        channelId: String
    ): ApiResponse
    suspend fun inbox(
        filter: String,
        limit: Int,
        offset: Int
    ): ApiResponse
    suspend fun markInboxReadAll(): ApiResponse
    suspend fun serverTasks(): ApiResponse
    suspend fun channelTasks(
        channelId: String
    ): ApiResponse
    suspend fun claimTask(
        taskId: String
    ): ApiResponse
    suspend fun updateTaskStatus(
        taskId: String,
        body: RequestBody
    ): ApiResponse
    suspend fun saved(
        limit: Int,
        offset: Int
    ): ApiResponse
    suspend fun saveMessage(
        messageId: String
    ): ApiResponse
    suspend fun unsaveMessage(
        messageId: String
    ): ApiResponse
    suspend fun searchMessages(
        query: String,
        limit: Int,
        offset: Int
    ): ApiResponse
    suspend fun channelMessages(
        channelId: String,
        limit: Int,
        beforeSeq: Long? = null
    ): ApiResponse
    suspend fun messageContext(
        messageId: String
    ): ApiResponse
    suspend fun messageSync(
        sinceSeq: Long,
        limit: Int,
        channelId: String? = null
    ): ApiResponse
    suspend fun sendMessage(body: RequestBody): ApiResponse
    suspend fun uploadAttachments(
        channelId: RequestBody,
        files: List<okhttp3.MultipartBody.Part>
    ): ApiResponse
    suspend fun attachmentUrl(
        attachmentId: String
    ): ApiResponse
    suspend fun channelThreads(
        channelId: String,
        limit: Int
    ): ApiResponse
    suspend fun createOrOpenThread(
        channelId: String,
        body: RequestBody
    ): ApiResponse
    suspend fun followedThreads(
        limit: Int
    ): ApiResponse
    suspend fun followThread(body: RequestBody): ApiResponse
    suspend fun unfollowThread(body: RequestBody): ApiResponse
    suspend fun markThreadDone(body: RequestBody): ApiResponse
    suspend fun markThreadUndone(body: RequestBody): ApiResponse
    suspend fun serverUnreadSummary(): ApiResponse
}
