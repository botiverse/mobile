package ai.slock.android.core.data.auth

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

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
                loginResponse = Response.success(
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
                loginResponse = Response.success(
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
    fun wrongCredentialsMapToInvalidCredentials() {
        val repository = testAuthRepository(
            InMemoryAuthSessionStore(),
            FakeAuthApi(loginResponse = Response.error(401, """{"error":"Invalid credentials"}""".jsonBody()))
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
                currentUserResponse = Response.success("""{"user":$sampleUserJson}""".jsonBody()),
                serversResponse = Response.success("""{"servers":[$sampleServerJson]}""".jsonBody())
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
                Response.error(401, """{"error":"expired"}""".jsonBody()),
                Response.success("""{"user":$sampleUserJson}""".jsonBody())
            ),
            serversResponse = Response.success("""{"servers":[$sampleServerJson]}""".jsonBody())
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
            currentUserResponse = Response.error(401, """{"error":"expired"}""".jsonBody()),
            refreshResponse = Response.error(401, """{"error":"refresh expired"}""".jsonBody())
        )
        val repository = testAuthRepository(store, api)

        val result = runSuspend { repository.restoreSession() }

        assertEquals(AuthResult.Unauthenticated, result)
        assertEquals(null, store.session)
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
            refreshResponse = Response.error(401, """{"error":"expired"}""".jsonBody())
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
            refreshResponse = Response.error(401, """{"error":"expired"}""".jsonBody())
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
    ) = RetrofitAuthRepository(
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
        private val loginResponse: Response<okhttp3.ResponseBody> = Response.success(
            """{"user":$sampleUserJson,"accessToken":"access-token","refreshToken":"refresh-token"}""".jsonBody()
        ),
        private val currentUserResponse: Response<okhttp3.ResponseBody> = Response.success(sampleUserJson.jsonBody()),
        private val currentUserResponses: List<Response<okhttp3.ResponseBody>> = emptyList(),
        private val serversResponse: Response<okhttp3.ResponseBody> = Response.success("""[$sampleServerJson]""".jsonBody()),
        private val refreshResponse: Response<okhttp3.ResponseBody> = Response.success(
            """{"accessToken":"new-access","refreshToken":"new-refresh"}""".jsonBody()
        ),
        private val refreshFailure: Throwable? = null
    ) : AuthApi {
        var currentUserCallCount: Int = 0
            private set

        var refreshCallCount: Int = 0
            private set

        override suspend fun login(body: okhttp3.RequestBody): Response<okhttp3.ResponseBody> = loginResponse

        override suspend fun currentUser(authorization: String): Response<okhttp3.ResponseBody> {
            val response = currentUserResponses.getOrNull(currentUserCallCount) ?: currentUserResponse
            currentUserCallCount += 1
            return response
        }

        override suspend fun refresh(body: okhttp3.RequestBody): Response<okhttp3.ResponseBody> {
            refreshCallCount += 1
            refreshFailure?.let { throw it }
            return refreshResponse
        }

        override suspend fun logout(body: okhttp3.RequestBody): Response<okhttp3.ResponseBody> =
            Response.success("""{"ok":true}""".jsonBody())

        override suspend fun servers(authorization: String): Response<okhttp3.ResponseBody> =
            serversResponse

        override suspend fun createServer(
            authorization: String,
            body: okhttp3.RequestBody
        ): Response<okhttp3.ResponseBody> =
            Response.success("""{"server":$sampleServerJson}""".jsonBody())
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
