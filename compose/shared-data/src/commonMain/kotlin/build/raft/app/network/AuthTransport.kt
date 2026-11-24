package build.raft.app.network

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val selectedServerId: String? = null
) {
    val bearerAuthorization: String
        get() = "Bearer $accessToken"
}

interface AuthTokenProvider {
    suspend fun currentTokens(): AuthTokens?

    suspend fun refreshTokens(failedAccessToken: String): AuthTokens?

    suspend fun onUnauthorized(failedAccessToken: String?)
}

const val HEADER_SERVER_ID: String = "X-Server-Id"
const val HEADER_AUTHORIZATION: String = "Authorization"
