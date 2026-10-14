package build.raft.app.data

interface SlockAuthTransport {
    /** Unauthenticated `GET <baseUrl>/version` used by the private-deployment save-time gate. */
    suspend fun serverVersion(): AuthNetworkResponse = AuthNetworkResponse(501, null)
    suspend fun startAppLogin(bodyText: String): AuthNetworkResponse = AuthNetworkResponse(501, null)
    suspend fun completeAppLogin(bodyText: String): AuthNetworkResponse = AuthNetworkResponse(501, null)
    suspend fun login(bodyText: String): AuthNetworkResponse
    suspend fun completeSocialLogin(provider: String, bodyText: String): AuthNetworkResponse
    suspend fun socialAuthProviders(platform: String): AuthNetworkResponse
    suspend fun startMobileOAuth(bodyText: String, authorization: String? = null): AuthNetworkResponse
    suspend fun completeMobileOAuth(bodyText: String, authorization: String? = null): AuthNetworkResponse
    suspend fun startNativeAppleMobileOAuth(bodyText: String, authorization: String? = null): AuthNetworkResponse =
        AuthNetworkResponse(code = 501, bodyText = null)
    suspend fun authorizeNativeAppleMobileOAuth(bodyText: String, authorization: String? = null): AuthNetworkResponse =
        AuthNetworkResponse(code = 501, bodyText = null)
    suspend fun startMobileOAuthLink(provider: String, bodyText: String, authorization: String): AuthNetworkResponse
    suspend fun completeMobileOAuthLink(provider: String, bodyText: String, authorization: String): AuthNetworkResponse
    suspend fun register(bodyText: String): AuthNetworkResponse
    suspend fun forgotPassword(bodyText: String): AuthNetworkResponse
    suspend fun resendVerification(authorization: String): AuthNetworkResponse
    suspend fun verifyEmail(bodyText: String): AuthNetworkResponse
    suspend fun currentUser(authorization: String): AuthNetworkResponse
    suspend fun completeProfile(authorization: String, bodyText: String): AuthNetworkResponse =
        AuthNetworkResponse(code = 501, bodyText = null, errorBodyText = "Profile setup is not implemented")
    suspend fun refresh(bodyText: String): AuthNetworkResponse
    suspend fun refreshWithAttempt(
        bodyText: String,
        attemptId: String,
        installationId: String,
    ): AuthNetworkResponse = refresh(bodyText)
    suspend fun logout(bodyText: String): AuthNetworkResponse
    suspend fun servers(authorization: String): AuthNetworkResponse
    suspend fun serverOrder(authorization: String): AuthNetworkResponse
    suspend fun createServer(authorization: String, bodyText: String): AuthNetworkResponse
    suspend fun consumeQaBootstrap(bodyText: String): AuthNetworkResponse = AuthNetworkResponse(501, null)
    suspend fun readyQaBootstrap(ticketDigest: String, authorization: String, bodyText: String): AuthNetworkResponse =
        AuthNetworkResponse(501, null)
    suspend fun statusQaBootstrap(ticketDigest: String, authorization: String): AuthNetworkResponse =
        AuthNetworkResponse(501, null)
}
