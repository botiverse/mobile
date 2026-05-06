package ai.slock.android.core.data.network

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
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

    @GET("channels")
    suspend fun channels(
        @Query("archived") archived: String = "include"
    ): Response<ResponseBody>

    @GET("channels/dm")
    suspend fun directMessages(): Response<ResponseBody>

    @GET("channels/inbox")
    suspend fun inbox(
        @Query("filter") filter: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int
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

    @GET("servers/unread-summary")
    suspend fun serverUnreadSummary(): Response<ResponseBody>
}
