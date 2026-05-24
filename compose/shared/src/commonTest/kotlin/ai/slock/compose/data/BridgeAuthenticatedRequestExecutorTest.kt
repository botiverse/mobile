package ai.slock.compose.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BridgeAuthenticatedRequestExecutorTest {
    @Test
    fun retriesRequestWithRefreshedTokenAfterUnauthorized() = runBlocking {
        val store = InMemorySlockAuthSessionStore(sampleSession())
        val api = FakeAuthApi(refreshResult = AuthApiResult.Success(AuthRefreshResponse("fresh-access", "fresh-refresh")))
        val sent = mutableListOf<BridgeAuthenticatedRequest>()
        val executor =
            BridgeAuthenticatedRequestExecutor(
                sessionStore = store,
                authApi = api,
                request = { command ->
                    sent += command
                    if (sent.size == 1) {
                        HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"expiredToken"}""")
                    } else {
                        HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
                    }
                }
            )

        val response = executor.request(path = "channels", method = "GET")

        assertEquals(200, response.code)
        assertEquals(2, sent.size)
        assertEquals("Bearer expired-access", sent[0].authorization)
        assertEquals("Bearer fresh-access", sent[1].authorization)
        assertEquals("fresh-access", store.readSession()?.accessToken)
        assertEquals("fresh-refresh", store.readSession()?.refreshToken)
    }

    @Test
    fun clearsSessionWhenRefreshTokenIsExpired() = runBlocking {
        val store = InMemorySlockAuthSessionStore(sampleSession())
        val api =
            FakeAuthApi(
                refreshResult =
                    AuthApiResult.Failure(
                        code = 401,
                        errorBodyText = """{"error":"expiredToken"}"""
                    )
            )
        val executor =
            BridgeAuthenticatedRequestExecutor(
                sessionStore = store,
                authApi = api,
                request = {
                    HomeNetworkResponse(code = 401, bodyText = null, errorBodyText = """{"error":"expiredToken"}""")
                }
            )

        val response = executor.request(path = "channels", method = "GET")

        assertEquals(401, response.code)
        assertNull(store.readSession())
    }

    @Test
    fun treatsExpiredTokenBodyAsRefreshableEvenWhenStatusIsNot401() = runBlocking {
        val store = InMemorySlockAuthSessionStore(sampleSession())
        val api = FakeAuthApi(refreshResult = AuthApiResult.Success(AuthRefreshResponse("fresh-access", "fresh-refresh")))
        var calls = 0
        val executor =
            BridgeAuthenticatedRequestExecutor(
                sessionStore = store,
                authApi = api,
                request = {
                    calls += 1
                    if (calls == 1) {
                        HomeNetworkResponse(code = 400, bodyText = null, errorBodyText = """{"message":"expiredToken"}""")
                    } else {
                        HomeNetworkResponse(code = 200, bodyText = """{"ok":true}""")
                    }
                }
            )

        val response = executor.request(path = "channels", method = "GET")

        assertEquals(200, response.code)
        assertEquals("fresh-access", store.readSession()?.accessToken)
    }

    private class FakeAuthApi(
        private val refreshResult: AuthApiResult<AuthRefreshResponse>
    ) : SlockAuthApi {
        override suspend fun login(email: String, password: String): AuthApiResult<AuthLoginResponse> =
            error("not used")

        override suspend fun register(name: String, email: String, password: String): AuthApiResult<AuthLoginResponse> =
            error("not used")

        override suspend fun forgotPassword(email: String): AuthApiResult<Unit> =
            error("not used")

        override suspend fun currentUser(authorization: String): AuthApiResult<StoredAuthUser> =
            error("not used")

        override suspend fun refresh(refreshToken: String): AuthApiResult<AuthRefreshResponse> {
            assertEquals("refresh-token", refreshToken)
            return refreshResult
        }

        override suspend fun logout(refreshToken: String): AuthApiResult<Unit> =
            error("not used")

        override suspend fun servers(authorization: String): AuthApiResult<List<StoredAuthServer>> =
            error("not used")

        override suspend fun createServer(authorization: String, bodyText: String): AuthApiResult<StoredAuthServer> =
            error("not used")
    }

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai"),
            accessToken = "expired-access",
            refreshToken = "refresh-token",
            selectedServerId = "server-1",
            selectedServerSlug = "slock",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
        )
}
