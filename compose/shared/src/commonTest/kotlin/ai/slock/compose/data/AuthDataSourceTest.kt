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
    fun googleSocialCompleteWritesStoredSessionAndFetchesServers() = runBlocking {
        val store = InMemorySlockAuthSessionStore()
        val api = FakeAuthApi()
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.completeSocialLogin("google", "social-code")

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("""{"code":"social-code"}""", api.socialCompleteBody)
        assertEquals("google", api.socialCompleteProvider)
        assertEquals("Bearer social-access", api.currentUserAuthorization)
        assertEquals("Bearer social-access", api.serversAuthorization)
        assertEquals("user-1", store.readSession()?.user?.id)
        assertEquals("social-access", store.readSession()?.accessToken)
        assertEquals("social-refresh", store.readSession()?.refreshToken)
        assertEquals("server-1", store.readSession()?.selectedServerId)
    }

    @Test
    fun googleIdTokenCompleteWritesStoredSessionAndFetchesServers() = runBlocking {
        val store = InMemorySlockAuthSessionStore()
        val api = FakeAuthApi()
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val result = source.completeGoogleIdTokenLogin("native-id-token", nonce = "nonce-1")

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("""{"idToken":"native-id-token","nonce":"nonce-1"}""", api.socialCompleteBody)
        assertEquals("google", api.socialCompleteProvider)
        assertEquals("Bearer social-access", api.currentUserAuthorization)
        assertEquals("Bearer social-access", api.serversAuthorization)
        assertEquals("user-1", store.readSession()?.user?.id)
        assertEquals("social-access", store.readSession()?.accessToken)
        assertEquals("social-refresh", store.readSession()?.refreshToken)
        assertEquals("server-1", store.readSession()?.selectedServerId)
    }

    @Test
    fun registerAndForgotPasswordUseSharedDtoBodies() = runBlocking {
        val store = InMemorySlockAuthSessionStore()
        val api = FakeAuthApi()
        val source = SharedAuthDataSource(transport = api, sessionStore = store)

        val registered = source.register(name = " Artin ", email = " user@slock.ai ", password = "password123")
        val forgot = source.forgotPassword(" user@slock.ai ")

        assertTrue(registered is SharedAuthResult.Success)
        assertTrue(forgot is SharedAuthResult.Success)
        assertEquals("""{"email":"user@slock.ai","password":"password123","name":"Artin"}""", api.registerBody)
        assertEquals("""{"email":"user@slock.ai"}""", api.forgotPasswordBody)
        assertEquals("user-1", store.readSession()?.user?.id)
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
    fun restoreSessionKeepsCachedSessionWhenCurrentUserThrows() = runBlocking {
        val store = InMemorySlockAuthSessionStore(storedSession())
        val source =
            SharedAuthDataSource(
                transport = FakeAuthApi(throwCurrentUser = true),
                sessionStore = store
            )

        val result = source.restoreSession()

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("access-token", result.data.accessToken)
        assertEquals("server-1", result.data.currentServerId)
        assertEquals("access-token", store.readSession()?.accessToken)
    }

    @Test
    fun restoreSessionKeepsCachedSessionWhenCurrentUserServerUnavailable() = runBlocking {
        val store = InMemorySlockAuthSessionStore(storedSession())
        val source =
            SharedAuthDataSource(
                transport =
                    FakeAuthApi(
                        currentUserResponses =
                            ArrayDeque(
                                listOf(
                                    AuthNetworkResponse(503, null, """{"message":"temporary outage"}""")
                                )
                            )
                    ),
                sessionStore = store
            )

        val result = source.restoreSession()

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("access-token", result.data.accessToken)
        assertEquals("server-1", result.data.currentServerId)
    }

    @Test
    fun restoreSessionKeepsCachedSessionWhenRefreshServerUnavailable() = runBlocking {
        val store = InMemorySlockAuthSessionStore(storedSession(accessToken = "expired-token"))
        val source =
            SharedAuthDataSource(
                transport =
                    FakeAuthApi(
                        currentUserResponses =
                            ArrayDeque(
                                listOf(
                                    AuthNetworkResponse(401, null)
                                )
                            ),
                        refreshResponse = AuthNetworkResponse(503, null, """{"message":"temporary outage"}""")
                    ),
                sessionStore = store
            )

        val result = source.restoreSession()

        assertTrue(result is SharedAuthResult.Success)
        assertEquals("expired-token", result.data.accessToken)
        assertEquals("server-1", result.data.currentServerId)
        assertEquals("expired-token", store.readSession()?.accessToken)
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

    private fun storedSession(accessToken: String = "access-token"): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@slock.ai"),
            accessToken = accessToken,
            refreshToken = "refresh-token",
            selectedServerId = "server-1",
            selectedServerSlug = "slock",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
        )

    private class FakeAuthApi(
        private val loginResponse: AuthNetworkResponse =
            AuthNetworkResponse(
                200,
                """
                {"user":{"id":"user-1","email":"user@slock.ai","displayName":"Artin"},"accessToken":"access-token","refreshToken":"refresh-token"}
                """.trimIndent()
            ),
        private val socialCompleteResponse: AuthNetworkResponse =
            AuthNetworkResponse(
                200,
                """{"provider":"google","mode":"login","accessToken":"social-access","refreshToken":"social-refresh"}"""
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
            AuthNetworkResponse(201, """{"id":"server-2","name":"Ops","slug":"ops"}"""),
        private val throwCurrentUser: Boolean = false
    ) : SlockAuthTransport {
        var loginBody: String? = null
            private set
        var registerBody: String? = null
            private set
        var forgotPasswordBody: String? = null
            private set
        var socialCompleteProvider: String? = null
            private set
        var socialCompleteBody: String? = null
            private set
        var currentUserAuthorization: String? = null
            private set
        var serversAuthorization: String? = null
            private set
        var createServerBody: String? = null
            private set

        override suspend fun login(bodyText: String): AuthNetworkResponse {
            loginBody = bodyText
            return loginResponse
        }

        override suspend fun completeSocialLogin(provider: String, bodyText: String): AuthNetworkResponse {
            socialCompleteProvider = provider
            socialCompleteBody = bodyText
            return socialCompleteResponse
        }

        override suspend fun register(bodyText: String): AuthNetworkResponse {
            registerBody = bodyText
            return loginResponse
        }

        override suspend fun forgotPassword(bodyText: String): AuthNetworkResponse {
            forgotPasswordBody = bodyText
            return AuthNetworkResponse(200, """{"ok":true,"message":"sent"}""")
        }

        override suspend fun currentUser(authorization: String): AuthNetworkResponse {
            currentUserAuthorization = authorization
            if (throwCurrentUser) error("bridge not ready")
            return if (currentUserResponses.size > 1) currentUserResponses.removeFirst() else currentUserResponses.first()
        }

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
