package ai.slock.android.core.data.network

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.Part
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface SlockApi {
    @GET("auth/me")
    suspend fun settingsAccount(): Response<ResponseBody>

    @PATCH("auth/me")
    suspend fun updateSettingsAccount(@Body body: RequestBody): Response<ResponseBody>

    @GET("auth/sessions")
    suspend fun settingsSessions(): Response<ResponseBody>

    @DELETE("auth/sessions/{sessionId}")
    suspend fun revokeSettingsSession(
        @Path("sessionId") sessionId: String
    ): Response<ResponseBody>

    @GET("servers/{serverId}/machines")
    suspend fun settingsMachines(
        @Path("serverId") serverId: String
    ): Response<ResponseBody>

    @GET("servers/{serverId}/machines/{machineId}/workspaces")
    suspend fun settingsMachineWorkspaces(
        @Path("serverId") serverId: String,
        @Path("machineId") machineId: String
    ): Response<ResponseBody>

    @GET("agents")
    suspend fun settingsAgents(): Response<ResponseBody>

    @GET("channels")
    suspend fun channels(
        @Query("archived") archived: String = "include"
    ): Response<ResponseBody>

    @GET("servers/{serverId}/members")
    suspend fun serverMembers(
        @Path("serverId") serverId: String
    ): Response<ResponseBody>

    @GET("members/{memberId}/profile")
    suspend fun memberProfile(
        @Path("memberId") memberId: String
    ): Response<ResponseBody>

    @GET("agents/{agentId}/agent-dms")
    suspend fun agentDirectMessages(
        @Path("agentId") agentId: String
    ): Response<ResponseBody>

    @GET("reminders")
    suspend fun reminders(
        @Query("ownerAgentId") ownerAgentId: String,
        @Query("status") status: String = "scheduled"
    ): Response<ResponseBody>

    @GET("trajectoryLogs/{agentId}")
    suspend fun trajectoryLogs(
        @Path("agentId") agentId: String
    ): Response<ResponseBody>

    @GET("channels/{channelId}/members")
    suspend fun channelMembers(
        @Path("channelId") channelId: String
    ): Response<ResponseBody>

    @POST("channels")
    suspend fun createChannel(@Body body: RequestBody): Response<ResponseBody>

    @POST("channels/{channelId}/join")
    suspend fun joinChannel(
        @Path("channelId") channelId: String
    ): Response<ResponseBody>

    @POST("channels/{channelId}/members")
    suspend fun addChannelMember(
        @Path("channelId") channelId: String,
        @Body body: RequestBody
    ): Response<ResponseBody>

    @DELETE("channels/{channelId}/members/{memberId}")
    suspend fun removeChannelMember(
        @Path("channelId") channelId: String,
        @Path("memberId") memberId: String,
        @Query("kind") kind: String
    ): Response<ResponseBody>

    @GET("channels/dm")
    suspend fun directMessages(): Response<ResponseBody>

    @GET("channels/unread")
    suspend fun channelUnreadCounts(): Response<ResponseBody>

    @POST("channels/{channelId}/read")
    suspend fun markChannelReadToSeq(
        @Path("channelId") channelId: String,
        @Body body: RequestBody
    ): Response<ResponseBody>

    @POST("channels/{channelId}/read-all")
    suspend fun markChannelReadAll(
        @Path("channelId") channelId: String
    ): Response<ResponseBody>

    @POST("channels/{channelId}/unread")
    suspend fun markChannelUnread(
        @Path("channelId") channelId: String
    ): Response<ResponseBody>

    @GET("channels/inbox")
    suspend fun inbox(
        @Query("filter") filter: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int
    ): Response<ResponseBody>

    @POST("channels/inbox/read-all")
    suspend fun markInboxReadAll(): Response<ResponseBody>

    @GET("tasks/server")
    suspend fun serverTasks(): Response<ResponseBody>

    @PATCH("tasks/{taskId}/claim")
    suspend fun claimTask(
        @Path("taskId") taskId: String
    ): Response<ResponseBody>

    @GET("channels/saved")
    suspend fun saved(
        @Query("limit") limit: Int,
        @Query("offset") offset: Int
    ): Response<ResponseBody>

    @GET("messages/search")
    suspend fun searchMessages(
        @Query("q") query: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int
    ): Response<ResponseBody>

    @GET("messages/channel/{channelId}")
    suspend fun channelMessages(
        @Path("channelId") channelId: String,
        @Query("limit") limit: Int,
        @Query("before") beforeSeq: Long? = null
    ): Response<ResponseBody>

    @GET("messages/context/{messageId}")
    suspend fun messageContext(
        @Path("messageId") messageId: String
    ): Response<ResponseBody>

    @GET("messages/sync")
    suspend fun messageSync(
        @Query("since_seq") sinceSeq: Long,
        @Query("limit") limit: Int,
        @Query("channel_id") channelId: String? = null
    ): Response<ResponseBody>

    @POST("messages")
    suspend fun sendMessage(@Body body: RequestBody): Response<ResponseBody>

    @Multipart
    @POST("attachments/upload")
    suspend fun uploadAttachments(
        @Part("channelId") channelId: RequestBody,
        @Part files: List<okhttp3.MultipartBody.Part>
    ): Response<ResponseBody>

    @GET("attachments/{attachmentId}/url")
    suspend fun attachmentUrl(
        @Path("attachmentId") attachmentId: String
    ): Response<ResponseBody>

    @GET("channels/{channelId}/threads")
    suspend fun channelThreads(
        @Path("channelId") channelId: String,
        @Query("limit") limit: Int
    ): Response<ResponseBody>

    @POST("channels/{channelId}/threads")
    suspend fun createOrOpenThread(
        @Path("channelId") channelId: String,
        @Body body: RequestBody
    ): Response<ResponseBody>

    @GET("channels/threads/followed")
    suspend fun followedThreads(
        @Query("limit") limit: Int
    ): Response<ResponseBody>

    @POST("channels/threads/follow")
    suspend fun followThread(@Body body: RequestBody): Response<ResponseBody>

    @POST("channels/threads/unfollow")
    suspend fun unfollowThread(@Body body: RequestBody): Response<ResponseBody>

    @POST("channels/threads/done")
    suspend fun markThreadDone(@Body body: RequestBody): Response<ResponseBody>

    @POST("channels/threads/undone")
    suspend fun markThreadUndone(@Body body: RequestBody): Response<ResponseBody>

    @GET("servers/unread-summary")
    suspend fun serverUnreadSummary(): Response<ResponseBody>
}
