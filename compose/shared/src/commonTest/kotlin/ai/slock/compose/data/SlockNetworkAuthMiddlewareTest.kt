package ai.slock.compose.data

import ai.slock.compose.model.ServerId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockNetworkAuthMiddlewareTest {
    @Test
    fun retriesRequestWithRefreshedTokenAfterUnauthorized() = runBlocking {
        val store = InMemorySlockAuthSessionStore(sampleSession())
        val api = FakeAuthApi(refreshResult = AuthApiResult.Success(AuthRefreshResponse("fresh-access", "fresh-refresh")))
        val sent = mutableListOf<SlockAuthenticatedRequestContext>()
        val middleware = SlockNetworkAuthMiddleware(store, api)

        val response =
            middleware.request(serverIdOverride = ServerId("server-2")) { auth ->
                sent += auth
                if (sent.size == 1) {
                    HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"expiredToken"}""")
                } else {
                    HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
                }
            }

        assertEquals(200, response.code)
        assertEquals(2, sent.size)
        assertEquals("Bearer expired-access", sent[0].authorization)
        assertEquals("server-2", sent[0].serverId)
        assertEquals("Bearer fresh-access", sent[1].authorization)
        assertEquals("server-2", sent[1].serverId)
        assertEquals("fresh-access", store.readSession()?.accessToken)
        assertEquals("fresh-refresh", store.readSession()?.refreshToken)
    }

    @Test
    fun clearsSessionWhenRefreshTokenIsUnauthorized() = runBlocking {
        val store = InMemorySlockAuthSessionStore(sampleSession())
        val middleware =
            SlockNetworkAuthMiddleware(
                store,
                FakeAuthApi(
                    refreshResult =
                        AuthApiResult.Failure(
                            code = 401,
                            errorBodyText = """{"error":"expiredToken"}"""
                        )
                )
            )

        val response =
            middleware.request { HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"expiredToken"}""") }

        assertEquals(401, response.code)
        assertNull(store.readSession())
    }

    @Test
    fun preservesRefreshedSessionWhenRetryStillUnauthorized() = runBlocking {
        val store = InMemorySlockAuthSessionStore(sampleSession())
        val middleware =
            SlockNetworkAuthMiddleware(
                store,
                FakeAuthApi(refreshResult = AuthApiResult.Success(AuthRefreshResponse("fresh-access", "fresh-refresh")))
            )
        var calls = 0

        val response =
            middleware.request {
                calls += 1
                HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"expiredToken"}""")
            }

        assertEquals(401, response.code)
        assertEquals(2, calls)
        assertEquals("fresh-access", store.readSession()?.accessToken)
        assertEquals("fresh-refresh", store.readSession()?.refreshToken)
    }

    @Test
    fun treatsExpiredTokenBodyAsRefreshableEvenWhenStatusIsNot401() = runBlocking {
        val store = InMemorySlockAuthSessionStore(sampleSession())
        val middleware =
            SlockNetworkAuthMiddleware(
                store,
                FakeAuthApi(refreshResult = AuthApiResult.Success(AuthRefreshResponse("fresh-access", "fresh-refresh")))
            )
        var calls = 0

        val response =
            middleware.request { _ ->
                calls += 1
                if (calls == 1) {
                    HomeNetworkResponse(code = 400, bodyText = null, errorBodyText = """{"message":"jwt expired"}""")
                } else {
                    HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
                }
            }

        assertEquals(200, response.code)
        assertEquals(2, calls)
        assertEquals("fresh-access", store.readSession()?.accessToken)
    }

    @Test
    fun retriesWithNewerSessionWhenAnotherCallerAlreadyRefreshed() = runBlocking {
        val store = InMemorySlockAuthSessionStore(sampleSession())
        val middleware = SlockNetworkAuthMiddleware(store, FakeAuthApi(refreshResult = null))
        val sent = mutableListOf<SlockAuthenticatedRequestContext>()

        val response =
            middleware.request { auth ->
                sent += auth
                if (sent.size == 1) {
                    store.saveSession(sampleSession(accessToken = "fresh-access", refreshToken = "fresh-refresh"))
                    HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"expiredToken"}""")
                } else {
                    HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
                }
            }

        assertEquals(200, response.code)
        assertEquals("expired-access", sent[0].accessToken)
        assertEquals("fresh-access", sent[1].accessToken)
        assertEquals("fresh-access", store.readSession()?.accessToken)
    }

    private class FakeAuthApi(
        private val refreshResult: AuthApiResult<AuthRefreshResponse>?
    ) : SlockAuthApi {
        override suspend fun login(email: String, password: String): AuthApiResult<AuthLoginResponse> =
            error("not used")

        override suspend fun completeSocialLogin(
            provider: String,
            bodyText: String
        ): AuthApiResult<AuthSocialCompleteResponse> =
            error("not used")

        override suspend fun register(name: String, email: String, password: String): AuthApiResult<AuthLoginResponse> =
            error("not used")

        override suspend fun forgotPassword(email: String): AuthApiResult<Unit> =
            error("not used")

        override suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser> =
            error("not used")

        override suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse> {
            assertEquals("refresh-token", refreshToken)
            return refreshResult ?: error("refresh should not be called")
        }

        override suspend fun logout(refreshToken: String): AuthApiResult<Unit> =
            error("not used")

        override suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>> =
            error("not used")

        override suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer> =
            error("not used")
    }

    private fun sampleSession(
        accessToken: String = "expired-access",
        refreshToken: String = "refresh-token"
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
