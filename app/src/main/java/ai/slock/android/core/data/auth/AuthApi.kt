package ai.slock.android.core.data.auth

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface AuthApi {
    @POST("auth/login")
    suspend fun login(@Body body: RequestBody): Response<ResponseBody>

    @GET("auth/me")
    suspend fun currentUser(@Header("Authorization") authorization: String): Response<ResponseBody>

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RequestBody): Response<ResponseBody>

    @POST("auth/logout")
    suspend fun logout(@Body body: RequestBody): Response<ResponseBody>

    @GET("servers")
    suspend fun servers(@Header("Authorization") authorization: String): Response<ResponseBody>

    @POST("servers")
    suspend fun createServer(
        @Header("Authorization") authorization: String,
        @Body body: RequestBody
    ): Response<ResponseBody>
}
