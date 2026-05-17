package ai.slock.compose.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthDataSourceTest {
    @Test
    fun loginWritesStoredSessionAndFetchesServers() = runBlocking {
        val store = InMemorySlockAuthSessionStore()
        val api = FakeAuthApi()
        val source = SharedAuthDataSource(api = api, sessionStore = store)

        val result = source.login(" user@slock.ai ", "password")

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("""{"email":"user@slock.ai","password":"password"}""", api.loginBody)
        assertEquals("Bearer access-token", api.serversAuthorization)
        assertEquals("user-1", store.readSession()?.user?.id)
        assertEquals("access-token", store.readSession()?.accessToken)
        assertEquals("server-1", store.readSession()?.selectedServerId)
        assertEquals("slock", store.readSession()?.selectedServerSlug)
    }

    @Test
    fun loginMapsHttpFailureWithoutWritingSession() = runBlocking {
        val store = InMemorySlockAuthSessionStore()
        val source =
            SharedAuthDataSource(
                api = FakeAuthApi(loginResponse = AuthNetworkResponse(401, null, """{"error":"bad"}""")),
                sessionStore = store
            )

        val result = source.login("user@slock.ai", "wrong")

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals(SharedAuthErrorCode.InvalidCredentials, result.code)
        assertEquals(null, store.readSession())
    }

    private class FakeAuthApi(
        private val loginResponse: AuthNetworkResponse =
            AuthNetworkResponse(
                200,
                """
                {"user":{"id":"user-1","email":"user@slock.ai","displayName":"Artin"},"accessToken":"access-token","refreshToken":"refresh-token"}
                """.trimIndent()
            ),
        private val serversResponse: AuthNetworkResponse =
            AuthNetworkResponse(
                200,
                """{"data":{"servers":[{"id":"server-1","name":"Slock","slug":"slock","role":"owner"}]}}"""
            )
    ) : SlockAuthApi {
        var loginBody: String? = null
            private set
        var serversAuthorization: String? = null
            private set

        override suspend fun login(bodyText: String): AuthNetworkResponse {
            loginBody = bodyText
            return loginResponse
        }

        override suspend fun servers(authorization: String): AuthNetworkResponse {
            serversAuthorization = authorization
            return serversResponse
        }
    }
}
