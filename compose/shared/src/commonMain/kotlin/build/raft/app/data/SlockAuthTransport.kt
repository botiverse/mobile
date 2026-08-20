package build.raft.app.data

interface SlockAuthTransport {
    /**
     * Returns a request view whose endpoint and environment generation are captured for the
     * lifetime of the view. Auth-resume flows use this rather than re-reading the mutable
     * developer environment at dispatch time.
     */
    fun bindToEnvironment(environment: SharedApiEnvironmentSnapshot): SlockAuthTransport = this

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
    suspend fun refreshWithAttemptForEnvironment(
        bodyText: String,
        attemptId: String,
        installationId: String,
        environment: SharedApiEnvironmentSnapshot,
    ): AuthNetworkResponse = refreshWithAttempt(bodyText, attemptId, installationId)
    suspend fun logout(bodyText: String): AuthNetworkResponse
    suspend fun servers(authorization: String): AuthNetworkResponse
    suspend fun serverOrder(authorization: String): AuthNetworkResponse
    suspend fun createServer(authorization: String, bodyText: String): AuthNetworkResponse
    suspend fun inviteInfo(token: String): AuthNetworkResponse = AuthNetworkResponse(501, null)
    suspend fun inviteInfoForEnvironment(
        token: String,
        environment: SharedApiEnvironmentSnapshot,
    ): AuthNetworkResponse = inviteInfo(token)
    suspend fun acceptInvite(authorization: String, bodyText: String): AuthNetworkResponse =
        AuthNetworkResponse(501, null)
    suspend fun acceptInviteForEnvironment(
        authorization: String,
        bodyText: String,
        environment: SharedApiEnvironmentSnapshot,
    ): AuthNetworkResponse = acceptInvite(authorization, bodyText)
    suspend fun consumeQaBootstrap(bodyText: String): AuthNetworkResponse = AuthNetworkResponse(501, null)
    suspend fun readyQaBootstrap(ticketDigest: String, authorization: String, bodyText: String): AuthNetworkResponse =
        AuthNetworkResponse(501, null)
    suspend fun statusQaBootstrap(ticketDigest: String, authorization: String): AuthNetworkResponse =
        AuthNetworkResponse(501, null)
}
