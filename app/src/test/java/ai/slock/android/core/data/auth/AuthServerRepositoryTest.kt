package ai.slock.android.core.data.auth

import ai.slock.android.core.data.repository.CreateServerRequest
import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.model.ServerId
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import ai.slock.android.core.data.network.ApiResponse
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthServerRepositoryTest {
    @Test
    fun getServersMapsSessionServersAndSelectedServer() {
        val store = InMemoryAuthSessionStore(sampleSession)
        val repository = AuthServerRepository(store, FakeAuthRepository())

        val result = runSuspend { repository.getServers() }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals(2, snapshot.servers.size)
        assertEquals(ServerId("server-2"), snapshot.selectedServer?.id)
        assertEquals("team", snapshot.selectedServer?.plan)
        assertEquals("admin", snapshot.selectedServer?.role)
        assertEquals("agent-2", snapshot.selectedServer?.onboardingAgentId)
    }

    @Test
    fun selectServerPersistsSelectedServerInSessionStore() {
        val store = InMemoryAuthSessionStore(sampleSession)
        val repository = AuthServerRepository(store, FakeAuthRepository())

        val result = runSuspend { repository.selectServer(ServerId("server-1")) }

        assertTrue(result is DataResult.Content)
        assertEquals(ServerId("server-1"), (result as DataResult.Content).data.selectedServer?.id)
        assertEquals("server-1", store.session?.selectedServerId)
        assertEquals("slock-one", store.session?.selectedServerSlug)
    }

    @Test
    fun getServersDoesNotAutoSelectWhenSessionHasNoSelectedServer() {
        val sessionWithoutSelection =
            sampleSession.copy(selectedServerId = null, selectedServerSlug = null)
        val store = InMemoryAuthSessionStore(sessionWithoutSelection)
        val repository = AuthServerRepository(
            store,
            FakeAuthRepository(sessionWithoutSelection)
        )

        val result = runSuspend { repository.getServers() }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals(2, snapshot.servers.size)
        assertEquals(null, snapshot.selectedServer)
    }

    @Test
    fun missingSessionMapsToNoPermission() {
        val repository = AuthServerRepository(InMemoryAuthSessionStore(null), FakeAuthRepository())

        val result = runSuspend { repository.getServers() }

        assertTrue(result is DataResult.NoPermission)
    }

    @Test
    fun createServerPostsRequestAndSelectsCreatedServer() {
        val store = InMemoryAuthSessionStore(sampleSession)
        val api = FakeAuthApi(
            createResponse = ApiResponse.success("""{"server":$createdServerJson}""".jsonBody()),
            serversResponse = ApiResponse.success("""{"servers":[${sampleServerJson("server-1", "Slock One", "slock-one")},$createdServerJson]}""".jsonBody())
        )
        val repository = AuthServerRepository(store, FakeAuthRepository(), api)

        val result = runSuspend {
            repository.createServer(CreateServerRequest(name = "New Server", slug = "new-server"))
        }

        assertTrue(result is DataResult.Content)
        val snapshot = (result as DataResult.Content).data
        assertEquals(ServerId("server-3"), snapshot.selectedServer?.id)
        assertEquals("server-3", store.session?.selectedServerId)
        assertEquals("new-server", store.session?.selectedServerSlug)
        assertTrue(api.createBody.orEmpty().contains("New Server"))
        assertTrue(api.createBody.orEmpty().contains("new-server"))
    }

    private class InMemoryAuthSessionStore(
        var session: AuthSession?
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

    private class FakeAuthRepository(
        private val session: AuthSession = sampleSession
    ) : AuthRepository {
        override suspend fun login(email: String, password: String): AuthResult<AuthSessionSummary> =
            AuthResult.Failure(AuthErrorCode.Validation, "Not used in this test.")

        override suspend fun register(
            name: String,
            email: String,
            password: String
        ): AuthResult<AuthSessionSummary> =
            AuthResult.Failure(AuthErrorCode.Validation, "Not used in this test.")

        override suspend fun forgotPassword(email: String): AuthResult<Unit> =
            AuthResult.Failure(AuthErrorCode.Validation, "Not used in this test.")

        override suspend fun restoreSession(): AuthResult<AuthSessionSummary> =
            AuthResult.Failure(AuthErrorCode.Validation, "Not used in this test.")

        override suspend fun currentUser(): AuthResult<AuthSessionSummary> =
            AuthResult.Success(session.summary())

        override suspend fun refresh(refreshToken: String): AuthResult<AuthSessionSummary> =
            AuthResult.Failure(AuthErrorCode.Validation, "Not used in this test.")

        override suspend fun logout(): AuthResult<Unit> =
            AuthResult.Success(Unit)
    }

    private class FakeAuthApi(
        private val createResponse: ApiResponse,
        private val serversResponse: ApiResponse
    ) : AuthApi {
        var createBody: String? = null

        override suspend fun login(body: RequestBody): ApiResponse =
            ApiResponse.success("""{}""".jsonBody())

        override suspend fun register(body: RequestBody): ApiResponse =
            ApiResponse.success("""{}""".jsonBody())

        override suspend fun forgotPassword(body: RequestBody): ApiResponse =
            ApiResponse.success("""{}""".jsonBody())

        override suspend fun currentUser(authorization: String): ApiResponse =
            ApiResponse.success("""{}""".jsonBody())

        override suspend fun refresh(body: RequestBody): ApiResponse =
            ApiResponse.success("""{}""".jsonBody())

        override suspend fun logout(body: RequestBody): ApiResponse =
            ApiResponse.success("""{}""".jsonBody())

        override suspend fun servers(authorization: String): ApiResponse =
            serversResponse

        override suspend fun createServer(
            authorization: String,
            body: RequestBody
        ): ApiResponse {
            val buffer = okio.Buffer()
            body.writeTo(buffer)
            createBody = buffer.readUtf8()
            return createResponse
        }
    }

    companion object {
        private val sampleUser = AuthUser(
            id = "user-1",
            email = "user@example.test",
            name = "User",
            displayName = "Slock User",
            description = null,
            avatarUrl = null,
            emailVerified = true
        )

        private val sampleSession = AuthSession(
            user = sampleUser,
            accessToken = "sample-access",
            refreshToken = "sample-refresh",
            selectedServerId = "server-2",
            selectedServerSlug = "slock-two",
            servers = listOf(
                AuthServer(
                    id = "server-1",
                    name = "Slock One",
                    slug = "slock-one",
                    ownerId = "user-1",
                    onboardingAgentId = null,
                    plan = "hobby",
                    planDowngradedAt = null,
                    role = "owner",
                    createdAt = "2026-05-06T00:00:00Z"
                ),
                AuthServer(
                    id = "server-2",
                    name = "Slock Two",
                    slug = "slock-two",
                    ownerId = "user-2",
                    onboardingAgentId = "agent-2",
                    plan = "team",
                    planDowngradedAt = null,
                    role = "admin",
                    createdAt = "2026-05-06T00:00:00Z"
                )
            )
        )

        private val createdServerJson = sampleServerJson("server-3", "New Server", "new-server")

        private fun sampleServerJson(id: String, name: String, slug: String): String =
            """
                {
                  "id":"$id",
                  "name":"$name",
                  "slug":"$slug",
                  "ownerId":"user-1",
                  "onboardingAgentId":null,
                  "plan":"hobby",
                  "planDowngradedAt":null,
                  "role":"owner",
                  "createdAt":"2026-05-06T00:00:00Z"
                }
            """.trimIndent()

        private fun AuthSession.summary(): AuthSessionSummary =
            AuthSessionSummary(
                user = user,
                selectedServerId = selectedServerId,
                selectedServerSlug = selectedServerSlug,
                servers = servers
            )
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

private val json = "application/json; charset=utf-8".toMediaType()

private fun String.jsonBody(): ResponseBody =
    ResponseBody.create(json, this)
