package ai.slock.compose.data

interface SlockAuthTransport {
    suspend fun login(bodyText: String): AuthNetworkResponse
    suspend fun completeSocialLogin(provider: String, bodyText: String): AuthNetworkResponse
    suspend fun register(bodyText: String): AuthNetworkResponse
    suspend fun forgotPassword(bodyText: String): AuthNetworkResponse
    suspend fun currentUser(authorization: String): AuthNetworkResponse
    suspend fun refresh(bodyText: String): AuthNetworkResponse
    suspend fun logout(bodyText: String): AuthNetworkResponse
    suspend fun servers(authorization: String): AuthNetworkResponse
    suspend fun createServer(authorization: String, bodyText: String): AuthNetworkResponse
}
