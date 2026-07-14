package build.raft.app.data

interface SlockAuthTransport {
    suspend fun login(bodyText: String): AuthNetworkResponse
    suspend fun completeSocialLogin(provider: String, bodyText: String): AuthNetworkResponse
    suspend fun startMobileOAuth(bodyText: String, authorization: String? = null): AuthNetworkResponse
    suspend fun completeMobileOAuth(bodyText: String, authorization: String? = null): AuthNetworkResponse
    suspend fun startMobileOAuthLink(provider: String, bodyText: String, authorization: String): AuthNetworkResponse
    suspend fun completeMobileOAuthLink(provider: String, bodyText: String, authorization: String): AuthNetworkResponse
    suspend fun register(bodyText: String): AuthNetworkResponse
    suspend fun forgotPassword(bodyText: String): AuthNetworkResponse
    suspend fun resendVerification(authorization: String): AuthNetworkResponse
    suspend fun verifyEmail(bodyText: String): AuthNetworkResponse
    suspend fun currentUser(authorization: String): AuthNetworkResponse
    suspend fun refresh(bodyText: String): AuthNetworkResponse
    suspend fun logout(bodyText: String): AuthNetworkResponse
    suspend fun servers(authorization: String): AuthNetworkResponse
    suspend fun serverOrder(authorization: String): AuthNetworkResponse
    suspend fun createServer(authorization: String, bodyText: String): AuthNetworkResponse
}
