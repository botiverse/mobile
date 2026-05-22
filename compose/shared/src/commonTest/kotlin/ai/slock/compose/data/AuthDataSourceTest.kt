package ai.slock.compose.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthDataSourceTest {
    @Test
    fun buildsSharedThreadSendBodyWithNativeRepositoryShape() {
        val body =
            buildSharedThreadSendMessageBody(
                channelId = "channel-1",
                body = "hello",
                attachmentIds = emptyList(),
                asTask = false
            )

        assertEquals("""{"channelId":"channel-1","content":"hello","attachmentIds":[],"asTask":false}""", body)
    }

    @Test
    fun loginWritesStoredSessionAndFetchesServers() = runBlocking {
        val store = InMemorySlockAuthSessionStore()
        val api = FakeAuthApi()
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

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
                transport = FakeAuthApi(loginResponse = AuthNetworkResponse(401, null, """{"error":"bad"}""")),
                sessionStore = store
            )

        val result = source.login("user@slock.ai", "wrong")

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals(SharedAuthErrorCode.InvalidCredentials, result.code)
        assertEquals(null, store.readSession())
    }

    @Test
    fun loginRejectsLegacyTokenAliasesAtTypedApiBoundary() = runBlocking {
        val store = InMemorySlockAuthSessionStore()
        val source =
            SharedAuthDataSource(
                transport =
                    FakeAuthApi(
                        loginResponse =
                            AuthNetworkResponse(
                                200,
                                """{"user":{"id":"user-1","email":"user@slock.ai"},"token":"legacy-access","refresh_token":"legacy-refresh"}"""
                            )
                    ),
                sessionStore = store
            )

        val result = source.login("user@slock.ai", "password")

        assertTrue(result is SharedAuthResult.Failure)
        assertEquals(SharedAuthErrorCode.Unknown, result.code)
        assertEquals("auth_login_contract_mismatch", result.message)
        assertEquals(null, store.readSession())
    }

    @Test
    fun restoreSessionRefreshesUnauthorizedCurrentUserInSharedLayer() = runBlocking {
        val store =
            InMemorySlockAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                    accessToken = "expired-token",
                    refreshToken = "refresh-token",
                    selectedServerId = "server-1",
                    selectedServerSlug = "slock",
                    servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
                )
            )
        val source =
            SharedAuthDataSource(
                transport =
                    FakeAuthApi(
                        currentUserResponses =
                            ArrayDeque(
                                listOf(
                                    AuthNetworkResponse(401, null),
                                    AuthNetworkResponse(200, """{"id":"user-1","email":"user@slock.ai","displayName":"Artin"}""")
                                )
                            ),
                        refreshResponse = AuthNetworkResponse(200, """{"accessToken":"fresh-token","refreshToken":"fresh-refresh"}""")
                    ),
                sessionStore = store
            )

        val result = source.restoreSession()

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("fresh-token", store.readSession()?.accessToken)
        assertEquals("fresh-refresh", store.readSession()?.refreshToken)
    }

    @Test
    fun selectServerPersistsSelectionInSharedLayer() = runBlocking {
        val store =
            InMemorySlockAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    servers =
                        listOf(
                            StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"),
                            StoredAuthServer(id = "server-2", name = "Ops", slug = "ops")
                        )
                )
            )
        val source = SharedAuthDataSource(transport = FakeAuthApi(), sessionStore = store)

        val result = source.selectServer("server-2")

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("server-2", store.readSession()?.selectedServerId)
        assertEquals("ops", store.readSession()?.selectedServerSlug)
    }

    @Test
    fun createServerSelectsCreatedServerInSharedLayer() = runBlocking {
        val store =
            InMemorySlockAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
                )
            )
        val api =
            FakeAuthApi(
                createServerResponse = AuthNetworkResponse(201, """{"id":"server-2","name":"Ops","slug":"ops"}"""),
                serversResponse = AuthNetworkResponse(200, """[{"id":"server-1","name":"Slock","slug":"slock"},{"id":"server-2","name":"Ops","slug":"ops"}]""")
            )
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.createServer(" Ops ")

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("""{"name":"Ops"}""", api.createServerBody)
        assertEquals("server-2", store.readSession()?.selectedServerId)
        assertEquals(2, store.readSession()?.servers?.size)
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
                """[{"id":"server-1","name":"Slock","slug":"slock","role":"owner"}]"""
            ),
        private val currentUserResponses: ArrayDeque<AuthNetworkResponse> =
            ArrayDeque(
                listOf(
                    AuthNetworkResponse(
                        200,
                        """{"id":"user-1","email":"user@slock.ai","displayName":"Artin"}"""
                    )
                )
            ),
        private val refreshResponse: AuthNetworkResponse =
            AuthNetworkResponse(200, """{"accessToken":"access-token","refreshToken":"refresh-token"}"""),
        private val logoutResponse: AuthNetworkResponse =
            AuthNetworkResponse(200, """{"ok":true}"""),
        private val createServerResponse: AuthNetworkResponse =
            AuthNetworkResponse(201, """{"id":"server-2","name":"Ops","slug":"ops"}""")
    ) : SlockAuthTransport {
        var loginBody: String? = null
            private set
        var serversAuthorization: String? = null
            private set
        var createServerBody: String? = null
            private set

        override suspend fun login(bodyText: String): AuthNetworkResponse {
            loginBody = bodyText
            return loginResponse
        }

        override suspend fun currentUser(authorization: String): AuthNetworkResponse =
            if (currentUserResponses.size > 1) currentUserResponses.removeFirst() else currentUserResponses.first()

        override suspend fun refresh(bodyText: String): AuthNetworkResponse =
            refreshResponse

        override suspend fun logout(bodyText: String): AuthNetworkResponse =
            logoutResponse

        override suspend fun servers(authorization: String): AuthNetworkResponse {
            serversAuthorization = authorization
            return serversResponse
        }

        override suspend fun createServer(authorization: String, bodyText: String): AuthNetworkResponse {
            createServerBody = bodyText
            return createServerResponse
        }
    }
}
