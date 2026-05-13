package ai.slock.android.core.data.auth

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ai.slock.android.core.data.network.ApiResponse
import okio.Buffer

class AuthRepositoryTest {
    @Test
    fun loginStoresSessionAndReturnsUserSummary() {
        val store = InMemoryAuthSessionStore()
        val repository = testAuthRepository(store, FakeAuthApi())

        val result = runSuspend {
            repository.login(" user@slock.ai ", "password")
        }

        assertTrue(result is AuthResult.Success<*>)
        val summary = (result as AuthResult.Success).data
        assertEquals("user-1", summary.user.id)
        assertEquals("server-1", summary.selectedServerId)
        assertEquals("slock-android", summary.selectedServerSlug)
        assertEquals("access-token", store.session?.accessToken)
        assertEquals("refresh-token", store.session?.refreshToken)
    }

    @Test
    fun loginStoresSessionWhenEmailIsUnverified() {
        val store = InMemoryAuthSessionStore()
        val repository = testAuthRepository(
            store,
            FakeAuthApi(
                loginResponse = ApiResponse.success(
                    """{"user":${sampleUserJson(emailVerified = false)},"accessToken":"access-token","refreshToken":"refresh-token"}""".jsonBody()
                )
            )
        )

        val result = runSuspend {
            repository.login("user@slock.ai", "password")
        }

        assertTrue(result is AuthResult.Success<*>)
        val summary = (result as AuthResult.Success).data
        assertEquals(false, summary.user.emailVerified)
        assertEquals("access-token", store.session?.accessToken)
        assertEquals("server-1", summary.selectedServerId)
    }

    @Test
    fun loginMapsAvatarAliasesToSessionUser() {
        val store = InMemoryAuthSessionStore()
        val repository = testAuthRepository(
            store,
            FakeAuthApi(
                loginResponse = ApiResponse.success(
                    """
                    {
                      "user":{
                        "id":"user-1",
                        "email":"user@slock.ai",
                        "name":"artin",
                        "displayName":"artin",
                        "description":null,
                        "profileAvatar":"pixel:diamond",
                        "gravatar":"abc123",
                        "emailVerified":true
                      },
                      "accessToken":"access-token",
                      "refreshToken":"refresh-token"
                    }
                    """.trimIndent().jsonBody()
                )
            )
        )

        val result = runSuspend { repository.login("user@slock.ai", "password") }

        assertTrue(result is AuthResult.Success<*>)
        assertEquals("pixel:diamond", store.session?.user?.avatarUrl)
        assertEquals("abc123", store.session?.user?.gravatarHash)
        assertEquals("pixel:diamond", store.session?.user?.toMemberProfile()?.avatar?.wireValue)
    }

    @Test
    fun registerStoresSessionAndSendsWebCompatibleBody() {
        val store = InMemoryAuthSessionStore()
        val api = FakeAuthApi(
            registerResponse = ApiResponse.success(
                """{"user":${sampleUserJson(emailVerified = false)},"accessToken":"register-access","refreshToken":"register-refresh"}""".jsonBody()
            )
        )
        val repository = testAuthRepository(store, api)

        val result = runSuspend {
            repository.register(" New User ", " new@slock.ai ", "password123")
        }

        assertTrue(result is AuthResult.Success<*>)
        assertEquals("register-access", store.session?.accessToken)
        assertEquals("register-refresh", store.session?.refreshToken)
        assertEquals(
            """{"email":"new@slock.ai","password":"password123","name":"New User"}""",
            api.registerRequestBody
        )
        assertEquals(1, api.registerCallCount)
    }

    @Test
    fun forgotPasswordPostsEmailWithoutChangingSession() {
        val existing = AuthSession(
            user = sampleUser,
            accessToken = "access-token",
            refreshToken = "refresh-token"
        )
        val store = InMemoryAuthSessionStore(existing)
        val api = FakeAuthApi()
        val repository = testAuthRepository(store, api)

        val result = runSuspend { repository.forgotPassword(" user@slock.ai ") }

        assertEquals(AuthResult.Success(Unit), result)
        assertEquals(existing, store.session)
        assertEquals("""{"email":"user@slock.ai"}""", api.forgotPasswordRequestBody)
        assertEquals(1, api.forgotPasswordCallCount)
    }

    @Test
    fun forgotPasswordValidatesEmailBeforeCallingApi() {
        val api = FakeAuthApi()
        val repository = testAuthRepository(InMemoryAuthSessionStore(), api)

        val result = runSuspend { repository.forgotPassword("not-an-email") }

        assertTrue(result is AuthResult.Failure)
        assertEquals(AuthErrorCode.Validation, (result as AuthResult.Failure).code)
        assertEquals(0, api.forgotPasswordCallCount)
    }

    @Test
    fun wrongCredentialsMapToInvalidCredentials() {
        val repository = testAuthRepository(
            InMemoryAuthSessionStore(),
            FakeAuthApi(loginResponse = ApiResponse.error(401, """{"error":"Invalid credentials"}""".jsonBody()))
        )

        val result = runSuspend {
            repository.login("user@slock.ai", "wrong")
        }

        assertTrue(result is AuthResult.Failure)
        assertEquals(AuthErrorCode.InvalidCredentials, (result as AuthResult.Failure).code)
    }

    @Test
    fun logoutClearsStoredSession() {
        val store = InMemoryAuthSessionStore(
            AuthSession(
                user = sampleUser,
                accessToken = "access-token",
                refreshToken = "refresh-token"
            )
        )
        val repository = testAuthRepository(store, FakeAuthApi())

        val result = runSuspend {
            repository.logout()
        }

        assertTrue(result is AuthResult.Success<*>)
        assertEquals(null, store.session)
    }

    @Test
    fun restoreSessionAcceptsWrappedCurrentUserAndServerResponses() {
        val store = InMemoryAuthSessionStore(
            AuthSession(
                user = sampleUser,
                accessToken = "access-token",
                refreshToken = "refresh-token",
                selectedServerId = "server-1",
                selectedServerSlug = "old-slug",
                servers = emptyList()
            )
        )
        val repository = testAuthRepository(
            store,
            FakeAuthApi(
                currentUserResponse = ApiResponse.success("""{"user":$sampleUserJson}""".jsonBody()),
                serversResponse = ApiResponse.success("""{"servers":[$sampleServerJson]}""".jsonBody())
            )
        )

        val result = runSuspend { repository.restoreSession() }

        assertTrue(result is AuthResult.Success<*>)
        val summary = (result as AuthResult.Success).data
        assertEquals("user-1", summary.user.id)
        assertEquals("server-1", summary.selectedServerId)
        assertEquals("slock-android", store.session?.selectedServerSlug)
        assertEquals(1, store.session?.servers?.size)
    }

    @Test
    fun restoreSessionRefreshesExpiredAccessTokenBeforeClearingSession() {
        val store = InMemoryAuthSessionStore(
            AuthSession(
                user = sampleUser,
                accessToken = "expired-access",
                refreshToken = "refresh-token",
                selectedServerId = "server-1",
                selectedServerSlug = "old-slug",
                servers = emptyList()
            )
        )
        val api = FakeAuthApi(
            currentUserResponses = listOf(
                ApiResponse.error(401, """{"error":"expired"}""".jsonBody()),
                ApiResponse.success("""{"user":$sampleUserJson}""".jsonBody())
            ),
            serversResponse = ApiResponse.success("""{"servers":[$sampleServerJson]}""".jsonBody())
        )
        val repository = testAuthRepository(store, api)

        val result = runSuspend { repository.restoreSession() }

        assertTrue(result is AuthResult.Success<*>)
        val summary = (result as AuthResult.Success).data
        assertEquals("user-1", summary.user.id)
        assertEquals("new-access", store.session?.accessToken)
        assertEquals("new-refresh", store.session?.refreshToken)
        assertEquals("slock-android", store.session?.selectedServerSlug)
        assertEquals(2, api.currentUserCallCount)
        assertEquals(1, api.refreshCallCount)
    }

    @Test
    fun restoreSessionClearsSessionWhenRefreshTokenIsUnauthorized() {
        val store = InMemoryAuthSessionStore(
            AuthSession(
                user = sampleUser,
                accessToken = "expired-access",
                refreshToken = "refresh-token"
            )
        )
        val api = FakeAuthApi(
            currentUserResponse = ApiResponse.error(401, """{"error":"expired"}""".jsonBody()),
            refreshResponse = ApiResponse.error(401, """{"error":"refresh expired"}""".jsonBody())
        )
        val repository = testAuthRepository(store, api)

        val result = runSuspend { repository.restoreSession() }

        assertEquals(AuthResult.Unauthenticated, result)
        assertEquals(null, store.session)
        assertEquals(1, api.currentUserCallCount)
        assertEquals(1, api.refreshCallCount)
    }

    @Test
    fun restoreSessionUsesCachedSessionWhenCurrentUserHasNetworkFailure() {
        val cached = AuthSession(
            user = sampleUser,
            accessToken = "access-token",
            refreshToken = "refresh-token",
            selectedServerId = "server-1",
            selectedServerSlug = "slock-android",
            servers = listOf(sampleServer)
        )
        val store = InMemoryAuthSessionStore(cached)
        val api = FakeAuthApi(currentUserFailure = IOException("offline"))
        val repository = testAuthRepository(store, api)

        val result = runSuspend { repository.restoreSession() }

        assertTrue(result is AuthResult.Success<*>)
        val summary = (result as AuthResult.Success).data
        assertEquals("user-1", summary.user.id)
        assertEquals("server-1", summary.selectedServerId)
        assertEquals(cached, store.session)
        assertEquals(1, api.currentUserCallCount)
        assertEquals(0, api.refreshCallCount)
    }

    @Test
    fun restoreSessionUsesCachedSessionWhenRefreshHasNetworkFailure() {
        val cached = AuthSession(
            user = sampleUser,
            accessToken = "expired-access",
            refreshToken = "refresh-token",
            selectedServerId = "server-1",
            selectedServerSlug = "slock-android",
            servers = listOf(sampleServer)
        )
        val store = InMemoryAuthSessionStore(cached)
        val api = FakeAuthApi(
            currentUserResponse = ApiResponse.error(401, """{"error":"expired"}""".jsonBody()),
            refreshFailure = IOException("offline")
        )
        val repository = testAuthRepository(store, api)

        val result = runSuspend { repository.restoreSession() }

        assertTrue(result is AuthResult.Success<*>)
        val summary = (result as AuthResult.Success).data
        assertEquals("user-1", summary.user.id)
        assertEquals("server-1", summary.selectedServerId)
        assertEquals(cached, store.session)
        assertEquals(1, api.currentUserCallCount)
        assertEquals(1, api.refreshCallCount)
    }

    @Test
    fun tokenRefresherStoresNewTokens() {
        val store = InMemoryAuthSessionStore(
            AuthSession(
                user = sampleUser,
                accessToken = "expired-access",
                refreshToken = "refresh-token"
            )
        )
        val api = FakeAuthApi()
        val refresher = testTokenRefresher(store, api)

        val session = runSuspend { refresher.refreshIfCurrent("expired-access") }

        assertEquals("new-access", session?.accessToken)
        assertEquals("new-refresh", session?.refreshToken)
        assertEquals("new-access", store.session?.accessToken)
        assertEquals(1, api.refreshCallCount)
    }

    @Test
    fun tokenRefresherSkipsWhenAnotherRequestAlreadyRefreshed() {
        val store = InMemoryAuthSessionStore(
            AuthSession(
                user = sampleUser,
                accessToken = "newer-access",
                refreshToken = "newer-refresh"
            )
        )
        val api = FakeAuthApi()
        val refresher = testTokenRefresher(store, api)

        val session = runSuspend { refresher.refreshIfCurrent("expired-access") }

        assertEquals("newer-access", session?.accessToken)
        assertEquals(0, api.refreshCallCount)
    }

    @Test
    fun tokenRefresherClearsSessionWhenRefreshIsUnauthorized() {
        val store = InMemoryAuthSessionStore(
            AuthSession(
                user = sampleUser,
                accessToken = "expired-access",
                refreshToken = "refresh-token"
            )
        )
        val api = FakeAuthApi(
            refreshResponse = ApiResponse.error(401, """{"error":"expired"}""".jsonBody())
        )
        val refresher = testTokenRefresher(store, api)

        val session = runSuspend { refresher.refreshIfCurrent("expired-access") }

        assertEquals(null, session)
        assertEquals(null, store.session)
        assertEquals(1, api.refreshCallCount)
    }

    @Test
    fun tokenRefresherCanPreserveSessionWhenProactiveRefreshIsUnauthorized() {
        val store = InMemoryAuthSessionStore(
            AuthSession(
                user = sampleUser,
                accessToken = "expired-access",
                refreshToken = "refresh-token"
            )
        )
        val api = FakeAuthApi(
            refreshResponse = ApiResponse.error(401, """{"error":"expired"}""".jsonBody())
        )
        val refresher = testTokenRefresher(store, api)

        val session = runSuspend {
            refresher.refreshIfCurrent(
                failedAccessToken = "expired-access",
                clearOnUnauthorized = false
            )
        }

        assertEquals(null, session)
        assertEquals("expired-access", store.session?.accessToken)
        assertEquals("refresh-token", store.session?.refreshToken)
        assertEquals(1, api.refreshCallCount)
    }

    @Test
    fun tokenRefresherProactivelyRefreshesMissingExpToken() {
        val store = InMemoryAuthSessionStore(
            AuthSession(
                user = sampleUser,
                accessToken = "opaque-access",
                refreshToken = "refresh-token"
            )
        )
        val api = FakeAuthApi()
        val refresher = testTokenRefresher(store, api)

        val session = runSuspend { refresher.refreshIfExpiringSoon() }

        assertEquals("new-access", session?.accessToken)
        assertEquals(1, api.refreshCallCount)
    }

    @Test
    fun tokenRefresherRethrowsCancellationWithoutClearingSession() {
        val store = InMemoryAuthSessionStore(
            AuthSession(
                user = sampleUser,
                accessToken = "expired-access",
                refreshToken = "refresh-token"
            )
        )
        val api = FakeAuthApi(refreshFailure = CancellationException("cancelled"))
        val refresher = testTokenRefresher(store, api)
        var cancelled = false

        try {
            runSuspend { refresher.refreshIfCurrent("expired-access") }
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals("expired-access", store.session?.accessToken)
        assertEquals("refresh-token", store.session?.refreshToken)
        assertEquals(1, api.refreshCallCount)
    }

    private class InMemoryAuthSessionStore(
        var session: AuthSession? = null
    ) : AuthSessionStore {
        override suspend fun readSession(): AuthSession? = session

        override suspend fun saveSession(session: AuthSession) {
            this.session = session
        }

        override suspend fun updateTokens(accessToken: String, refreshToken: String) {
            session = session?.copy(accessToken = accessToken, refreshToken = refreshToken)
        }

        override suspend fun updateSelectedServer(serverId: String?, serverSlug: String?) {
            session = session?.copy(selectedServerId = serverId, selectedServerSlug = serverSlug)
        }

        override suspend fun clearSession() {
            session = null
        }
    }

    private fun testAuthRepository(
        store: AuthSessionStore,
        api: AuthApi
    ) = KtorAuthRepository(
        store = store,
        api = api,
        logger = {},
        warningLogger = {}
    )

    private fun testTokenRefresher(
        store: AuthSessionStore,
        api: AuthApi
    ) = AuthTokenRefresher(
        store = store,
        api = api,
        logger = {},
        warningLogger = {}
    )

    private class FakeAuthApi(
        private val loginResponse: ApiResponse = ApiResponse.success(
            """{"user":$sampleUserJson,"accessToken":"access-token","refreshToken":"refresh-token"}""".jsonBody()
        ),
        private val registerResponse: ApiResponse = ApiResponse.success(
            """{"user":$sampleUserJson,"accessToken":"register-access","refreshToken":"register-refresh"}""".jsonBody()
        ),
        private val forgotPasswordResponse: ApiResponse = ApiResponse.success("""{"ok":true}""".jsonBody()),
        private val currentUserResponse: ApiResponse = ApiResponse.success(sampleUserJson.jsonBody()),
        private val currentUserResponses: List<ApiResponse> = emptyList(),
        private val serversResponse: ApiResponse = ApiResponse.success("""[$sampleServerJson]""".jsonBody()),
        private val refreshResponse: ApiResponse = ApiResponse.success(
            """{"accessToken":"new-access","refreshToken":"new-refresh"}""".jsonBody()
        ),
        private val currentUserFailure: Throwable? = null,
        private val refreshFailure: Throwable? = null
    ) : AuthApi {
        var currentUserCallCount: Int = 0
            private set

        var refreshCallCount: Int = 0
            private set

        var registerCallCount: Int = 0
            private set

        var forgotPasswordCallCount: Int = 0
            private set

        var registerRequestBody: String? = null
            private set

        var forgotPasswordRequestBody: String? = null
            private set

        override suspend fun login(body: okhttp3.RequestBody): ApiResponse = loginResponse

        override suspend fun register(body: okhttp3.RequestBody): ApiResponse {
            registerCallCount += 1
            registerRequestBody = body.bodyAsString()
            return registerResponse
        }

        override suspend fun forgotPassword(body: okhttp3.RequestBody): ApiResponse {
            forgotPasswordCallCount += 1
            forgotPasswordRequestBody = body.bodyAsString()
            return forgotPasswordResponse
        }

        override suspend fun currentUser(authorization: String): ApiResponse {
            val response = currentUserResponses.getOrNull(currentUserCallCount) ?: currentUserResponse
            currentUserCallCount += 1
            currentUserFailure?.let { throw it }
            return response
        }

        override suspend fun refresh(body: okhttp3.RequestBody): ApiResponse {
            refreshCallCount += 1
            refreshFailure?.let { throw it }
            return refreshResponse
        }

        override suspend fun logout(body: okhttp3.RequestBody): ApiResponse =
            ApiResponse.success("""{"ok":true}""".jsonBody())

        override suspend fun servers(authorization: String): ApiResponse =
            serversResponse

        override suspend fun createServer(
            authorization: String,
            body: okhttp3.RequestBody
        ): ApiResponse =
            ApiResponse.success("""{"server":$sampleServerJson}""".jsonBody())
    }

    companion object {
        private val json = "application/json; charset=utf-8".toMediaType()

        private val sampleUser = AuthUser(
            id = "user-1",
            email = "user@slock.ai",
            name = "User",
            displayName = "Slock User",
            description = null,
            avatarUrl = null,
            emailVerified = true
        )

        private const val sampleUserJson =
            """{"id":"user-1","email":"user@slock.ai","name":"User","displayName":"Slock User","description":null,"avatarUrl":null,"emailVerified":true}"""

        private fun sampleUserJson(emailVerified: Boolean): String =
            """{"id":"user-1","email":"user@slock.ai","name":"User","displayName":"Slock User","description":null,"avatarUrl":null,"emailVerified":$emailVerified}"""

        private const val sampleServerJson =
            """{"id":"server-1","name":"Slock Android","slug":"slock-android","ownerId":"user-1","onboardingAgentId":null,"plan":"trial","planDowngradedAt":null,"role":"owner","createdAt":"2026-05-06T00:00:00Z"}"""

        private val sampleServer = AuthServer(
            id = "server-1",
            name = "Slock Android",
            slug = "slock-android",
            ownerId = "user-1",
            onboardingAgentId = null,
            plan = "trial",
            planDowngradedAt = null,
            role = "owner",
            createdAt = "2026-05-06T00:00:00Z"
        )

        private fun String.jsonBody(): okhttp3.ResponseBody = toResponseBody(json)
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var value: T? = null
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                result.onSuccess { value = it }
                result.onFailure { failure = it }
            }
        }
    )
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value as T
}

private fun okhttp3.RequestBody.bodyAsString(): String {
    val buffer = Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}
