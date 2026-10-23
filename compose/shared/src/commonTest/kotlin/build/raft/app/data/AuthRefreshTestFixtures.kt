package build.raft.app.data

import kotlinx.coroutines.delay
import kotlin.test.assertEquals

/** Scripted [RaftAuthApi] for refresh-coordinator tests: answers refresh calls in order. */
internal class FakeRefreshAuthApi(
    private val refreshTokenAssertion: String,
    private val responses: List<AuthApiResult<AuthRefreshResponse>>,
    private val delayMillis: Long = 0L,
    private val beforeResponse: suspend (Int) -> Unit = {}
) : RaftAuthApi {
    var refreshCalls: Int = 0
        private set
    val attempts = mutableListOf<AuthRefreshAttemptContext>()

    override suspend fun refreshWithAttempt(
        refreshToken: String,
        attempt: AuthRefreshAttemptContext,
    ): AuthApiResult<AuthRefreshResponse> {
        attempts += attempt
        return refresh(refreshToken)
    }

    override suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse> {
        assertEquals(refreshTokenAssertion, refreshToken)
        refreshCalls += 1
        beforeResponse(refreshCalls)
        if (delayMillis > 0) delay(delayMillis)
        return responses.getOrElse(refreshCalls - 1) { responses.last() }
    }

    override suspend fun login(email: String, password: String): AuthApiResult<AuthLoginResponse> =
        error("not used")

    override suspend fun completeSocialLogin(
        provider: String,
        bodyText: String
    ): AuthApiResult<AuthSocialCompleteResponse> =
        error("not used")

    override suspend fun fetchSocialAuthProviders(platform: String): AuthApiResult<Set<String>> =
        error("not used")

    override suspend fun startMobileOAuthLink(
        provider: String,
        returnUri: String,
        codeChallenge: String,
        authorization: String
    ): AuthApiResult<AuthMobileOAuthStartResponse> =
        error("not used")

    override suspend fun completeMobileOAuthLink(
        provider: String,
        code: String,
        codeVerifier: String,
        authorization: String
    ): AuthApiResult<AuthMobileOAuthLinkCompleteResponse> =
        error("not used")

    override suspend fun resendVerification(authorization: String): AuthApiResult<Unit> =
        error("not used")

    override suspend fun verifyEmail(token: String): AuthApiResult<Unit> =
        error("not used")

    override suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser> =
        error("not used")

    override suspend fun logout(refreshToken: String): AuthApiResult<Unit> =
        error("not used")

    override suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>> =
        error("not used")

    override suspend fun serverOrder(authorization: String): AuthApiResult<ServerSwitcherOrderPreference> =
        error("not used")

    override suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer> =
        error("not used")
}
