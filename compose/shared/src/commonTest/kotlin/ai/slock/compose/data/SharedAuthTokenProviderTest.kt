package ai.slock.compose.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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
                "refresh_failed status=401",
                "refresh_unauthorized_session_cleared"
            ),
            warnings
        )
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
                "refresh_failed status=401",
                "refresh_unauthorized_session_preserved"
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
                "refresh_skip reason=missing_refresh_token",
                "refresh_missing_refresh_token_session_preserved"
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
            authorization: String?
        ): AuthApiResult<AuthLoginResponse> =
            error("not used")

        override suspend fun register(name: String, email: String, password: String): AuthApiResult<AuthLoginResponse> =
            error("not used")

        override suspend fun forgotPassword(email: String): AuthApiResult<Unit> =
            error("not used")

        override suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser> =
            error("not used")

        override suspend fun logout(refreshToken: String): AuthApiResult<Unit> =
            error("not used")

        override suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>> =
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
