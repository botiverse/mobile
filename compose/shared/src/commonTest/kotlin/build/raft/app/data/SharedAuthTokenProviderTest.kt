package build.raft.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SharedAuthTokenProviderTest {
    @Test
    fun coalescesRefreshAcrossProviderInstances() = runBlocking {
        val store =
            InMemorySlockAuthSessionStore(
                sampleSession(accessToken = "old-access", refreshToken = "old-refresh")
            )
        val api =
            FakeAuthApi(
                refreshTokenAssertion = "old-refresh",
                responses =
                    listOf(
                        AuthApiResult.Success(AuthRefreshResponse("fresh-access", "fresh-refresh")),
                        AuthApiResult.Failure(code = 401, errorBodyText = """{"error":"invalid refresh"}""")
                    ),
                delayMillis = 50
            )
        val firstProvider =
            SharedAuthTokenProvider(
                sessionStore = store,
                authApi = api,
                clockMillis = { 0L }
            )
        val secondProvider =
            SharedAuthTokenProvider(
                sessionStore = store,
                authApi = api,
                clockMillis = { 0L }
            )

        coroutineScope {
            val first = async { firstProvider.refreshSession(failedAccessToken = "old-access") }
            val second = async { secondProvider.refreshSession(failedAccessToken = "old-access") }

            assertEquals("fresh-access", first.await()?.accessToken)
            assertEquals("fresh-access", second.await()?.accessToken)
        }
        assertEquals(1, api.refreshCalls)
        assertEquals("fresh-access", store.readSession()?.accessToken)
    }

    @Test
    fun clearsSessionWhenRefreshTokenIsUnauthorized() = runBlocking {
        val warnings = mutableListOf<String>()
        val store =
            InMemorySlockAuthSessionStore(
                sampleSession(accessToken = "old-access", refreshToken = "old-refresh")
            )
        val provider =
            SharedAuthTokenProvider(
                sessionStore = store,
                authApi =
                    FakeAuthApi(
                        refreshTokenAssertion = "old-refresh",
                        responses =
                            listOf(
                                AuthApiResult.Failure(code = 401, errorBodyText = """{"error":"invalid refresh"}""")
                            )
                    ),
                clockMillis = { 0L },
                warningLogger = warnings::add
            )

        assertNull(provider.refreshSession(failedAccessToken = "old-access"))
        assertNull(store.readSession())
        assertEquals(
            listOf(
                "refresh_failed status=401 reason=unknown caller=unknown",
                "refresh_unauthorized_session_cleared status=401 reason=unknown caller=unknown"
            ),
            warnings
        )
    }

    @Test
    fun logsRefreshLifecycleWithTokenFingerprintsOnly() = runBlocking {
        val logs = mutableListOf<String>()
        val store =
            InMemorySlockAuthSessionStore(
                sampleSession(accessToken = "old-access-token", refreshToken = "old-refresh-token")
            )
        val provider =
            SharedAuthTokenProvider(
                sessionStore = store,
                authApi =
                    FakeAuthApi(
                        refreshTokenAssertion = "old-refresh-token",
                        responses =
                            listOf(
                                AuthApiResult.Success(
                                    AuthRefreshResponse("fresh-access-token", "fresh-refresh-token")
                                )
                            )
                    ),
                clockMillis = { 0L },
                logger = logs::add
            )

        val refreshed =
            provider.refreshSession(
                failedAccessToken = "old-access-token",
                reason = "http_401",
                caller = "shared_network:auth/me"
            )

        assertEquals("fresh-access-token", refreshed?.accessToken)
        val start = logs.single { it.startsWith("refresh_start") }
        assertContains(start, "reason=http_401")
        assertContains(start, "caller=shared_network:auth/me")
        assertContains(start, "access=len=")
        assertContains(start, "refresh=len=")
        val success = logs.single { it.startsWith("refresh_success") }
        assertContains(success, "accessRotated=true")
        assertContains(success, "refreshRotated=true")
        assertContains(success, "oldAccess=len=")
        assertContains(success, "newAccess=len=")
        assertContains(success, "oldRefresh=len=")
        assertContains(success, "newRefresh=len=")
        logs.forEach { log ->
            assertFalse(log.contains("old-access-token"), "refresh diagnostics must not log full old access token")
            assertFalse(log.contains("fresh-access-token"), "refresh diagnostics must not log full new access token")
            assertFalse(log.contains("old-refresh-token"), "refresh diagnostics must not log full old refresh token")
            assertFalse(log.contains("fresh-refresh-token"), "refresh diagnostics must not log full new refresh token")
        }
    }

    @Test
    fun preservesSessionWhenRefreshTokenIsUnauthorizedAndClearDisabled() = runBlocking {
        val warnings = mutableListOf<String>()
        val store =
            InMemorySlockAuthSessionStore(
                sampleSession(accessToken = "preserve-access", refreshToken = "preserve-refresh")
            )
        val provider =
            SharedAuthTokenProvider(
                sessionStore = store,
                authApi =
                    FakeAuthApi(
                        refreshTokenAssertion = "preserve-refresh",
                        responses =
                            listOf(
                                AuthApiResult.Failure(code = 401, errorBodyText = """{"error":"invalid refresh"}""")
                            )
                    ),
                clockMillis = { 0L },
                warningLogger = warnings::add
            )

        assertNull(
            provider.refreshSession(
                failedAccessToken = "preserve-access",
                clearOnUnauthorized = false
            )
        )
        assertEquals("preserve-access", store.readSession()?.accessToken)
        assertEquals(
            listOf(
                "refresh_failed status=401 reason=unknown caller=unknown",
                "refresh_unauthorized_session_preserved status=401 reason=unknown caller=unknown"
            ),
            warnings
        )
    }

    @Test
    fun preservesSessionWhenRefreshTokenIsMissingAndClearDisabled() = runBlocking {
        val warnings = mutableListOf<String>()
        val store =
            InMemorySlockAuthSessionStore(
                sampleSession(accessToken = "old-access", refreshToken = "")
            )
        val provider =
            SharedAuthTokenProvider(
                sessionStore = store,
                authApi =
                    FakeAuthApi(
                        refreshTokenAssertion = "unused",
                        responses = emptyList()
                    ),
                clockMillis = { 0L },
                warningLogger = warnings::add
            )

        assertNull(
            provider.currentSession(
                refreshIfExpiringSoon = true,
                clearOnUnauthorized = false
            )
        )
        assertEquals("old-access", store.readSession()?.accessToken)
        assertEquals(
            listOf(
                "refresh_skip reason=missing_refresh_token caller=current_session requestReason=missing_exp",
                "refresh_missing_refresh_token_session_preserved caller=current_session requestReason=missing_exp"
            ),
            warnings
        )
    }

    private class FakeAuthApi(
        private val refreshTokenAssertion: String,
        private val responses: List<AuthApiResult<AuthRefreshResponse>>,
        private val delayMillis: Long = 0L
    ) : SlockAuthApi {
        var refreshCalls: Int = 0
            private set

        override suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse> {
            assertEquals(refreshTokenAssertion, refreshToken)
            refreshCalls += 1
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

        override suspend fun startMobileOAuth(
            provider: String,
            mode: String,
            platform: String,
            appEnv: String,
            returnUri: String,
            codeChallenge: String,
            authorization: String?
        ): AuthApiResult<AuthMobileOAuthStartResponse> =
            error("not used")

        override suspend fun completeMobileOAuth(
            code: String,
            codeVerifier: String,
            platform: String,
            acceptLegalTerms: Boolean,
            authorization: String?
        ): AuthApiResult<AuthLoginResponse> =
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

        override suspend fun register(name: String, email: String, password: String): AuthApiResult<AuthLoginResponse> =
            error("not used")

        override suspend fun forgotPassword(email: String): AuthApiResult<Unit> =
            error("not used")

        override suspend fun resendVerification(authorization: String): AuthApiResult<Unit> =
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

    private fun sampleSession(
        accessToken: String,
        refreshToken: String
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai"),
            accessToken = accessToken,
            refreshToken = refreshToken,
            selectedServerId = "server-1",
            selectedServerSlug = "slock",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
        )
}
