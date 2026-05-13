package ai.slock.android.core.data.auth

import ai.slock.android.core.data.network.ApiResponse
import okhttp3.RequestBody

interface AuthApi {
    suspend fun login(body: RequestBody): ApiResponse
    suspend fun register(body: RequestBody): ApiResponse
    suspend fun forgotPassword(body: RequestBody): ApiResponse
    suspend fun currentUser(authorization: String): ApiResponse
    suspend fun refresh(body: RequestBody): ApiResponse
    suspend fun logout(body: RequestBody): ApiResponse
    suspend fun servers(authorization: String): ApiResponse
    suspend fun createServer(
        authorization: String,
        body: RequestBody
    ): ApiResponse
}
