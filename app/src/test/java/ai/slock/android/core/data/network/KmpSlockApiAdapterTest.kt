package ai.slock.android.core.data.network

import ai.slock.android.core.data.auth.AuthApi
import ai.slock.android.core.data.auth.AuthServer
import ai.slock.android.core.data.auth.AuthSession
import ai.slock.android.core.data.auth.AuthSessionStore
import ai.slock.android.core.data.auth.AuthTokenRefresher
import ai.slock.android.core.data.auth.AuthUser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ai.slock.android.core.data.network.ApiResponse

class KmpSlockApiAdapterTest {
    @Test
    fun channelMessagesUsesKmpHttpClientWithAuthHeadersAndQuery() {
        var captured: HttpRequestData? = null
        val client =
                HttpClient(
                        MockEngine { request ->
                            captured = request
                            respond(
                                    content = """{"messages":[]}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                )
        val api =
                KmpSlockApiAdapter(
                        store = InMemoryAuthSessionStore(sampleSession),
                        baseUrl = "https://api.example.test/api/",
                        httpClient = client
                )

        val response =
                runBlocking {
                    api.channelMessages(channelId = "channel-1", limit = 50, beforeSeq = 42)
                }

        assertTrue(response.isSuccessful)
        val request = requireNotNull(captured)
        assertEquals("https", request.url.protocol.name)
        assertEquals("api.example.test", request.url.host)
        assertEquals("/api/messages/channel/channel-1", request.url.encodedPath)
        assertEquals("50", request.url.parameters["limit"])
        assertEquals("42", request.url.parameters["before"])
        assertEquals("Bearer old-access", request.headers["Authorization"])
        assertEquals("server-1", request.headers["X-Server-Id"])
    }

    @Test
    fun requestHeadersUseCurrentSessionEvenWhenClientHasDefaults() {
        var captured: HttpRequestData? = null
        val client =
                HttpClient(MockEngine { request ->
                    captured = request
                    respond(
                            content = """{"messages":[]}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }) {
                    defaultRequest {
                        header(HttpHeaders.Authorization, "Bearer default-token")
                        header("X-Server-Id", "default-server")
                    }
                }
        val api =
                KmpSlockApiAdapter(
                        store = InMemoryAuthSessionStore(sampleSession),
                        baseUrl = "https://api.example.test/api/",
                        httpClient = client
                )

        val response = runBlocking { api.channels() }

        assertTrue(response.isSuccessful)
        val request = requireNotNull(captured)
        assertEquals("Bearer old-access", request.headers["Authorization"])
        assertEquals("server-1", request.headers["X-Server-Id"])
    }

    @Test
    fun retriesOnceWithRefreshedTokenAfterUnauthorized() {
        val authorizations = mutableListOf<String?>()
        val client =
                HttpClient(
                        MockEngine { request ->
                            authorizations += request.headers["Authorization"]
                            if (authorizations.size == 1) {
                                respond(
                                        content = """{"error":"unauthorized"}""",
                                        status = HttpStatusCode.Unauthorized,
                                        headers =
                                                headersOf(HttpHeaders.ContentType, "application/json")
                                )
                            } else {
                                respond(
                                        content = """{"channels":[]}""",
                                        status = HttpStatusCode.OK,
                                        headers =
                                                headersOf(HttpHeaders.ContentType, "application/json")
                                )
                            }
                        }
                )
        val store = InMemoryAuthSessionStore(sampleSession)
        val api =
                KmpSlockApiAdapter(
                        store = store,
                        baseUrl = "https://api.example.test/api/",
                        tokenRefresher =
                                AuthTokenRefresher(
                                        store = store,
                                        api = RefreshingAuthApi(),
                                        logger = {},
                                        warningLogger = {}
                                ),
                        httpClient = client,
                        logger = {},
                        warningLogger = {}
                )

        val response = runBlocking { api.channels() }

        assertTrue(response.isSuccessful)
        assertEquals(listOf("Bearer old-access", "Bearer new-access"), authorizations)
        assertEquals("new-access", runBlocking { store.readSession() }?.accessToken)
    }

    @Test
    fun clearsSessionWhenRetriedRequestIsStillUnauthorized() {
        val authorizations = mutableListOf<String?>()
        val client =
                HttpClient(
                        MockEngine { request ->
                            authorizations += request.headers["Authorization"]
                            respond(
                                    content = """{"error":"unauthorized"}""",
                                    status = HttpStatusCode.Unauthorized,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                )
        val store = InMemoryAuthSessionStore(sampleSession)
        val api =
                KmpSlockApiAdapter(
                        store = store,
                        baseUrl = "https://api.example.test/api/",
                        tokenRefresher =
                                AuthTokenRefresher(
                                        store = store,
                                        api = RefreshingAuthApi(),
                                        logger = {},
                                        warningLogger = {}
                                ),
                        httpClient = client,
                        logger = {},
                        warningLogger = {}
                )

        val response = runBlocking { api.channels() }

        assertEquals(401, response.code())
        assertEquals(listOf("Bearer old-access", "Bearer new-access"), authorizations)
        assertEquals(null, runBlocking { store.readSession() })
    }

    @Test
    fun uploadAttachmentsUsesKmpMultipartBody() {
        var captured: HttpRequestData? = null
        val client =
                HttpClient(
                        MockEngine { request ->
                            captured = request
                            respond(
                                    content =
                                            """{"attachments":[{"id":"attachment-1","filename":"sample.png"}]}""",
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        }
                )
        val api =
                KmpSlockApiAdapter(
                        store = InMemoryAuthSessionStore(sampleSession),
                        baseUrl = "https://api.example.test/api/",
                        httpClient = client
                )
        val channelId = "channel-1".toRequestBody("text/plain".toMediaType())
        val file =
                MultipartBody.Part.createFormData(
                        "files",
                        "sample.png",
                        "hello".toRequestBody("image/png".toMediaType())
                )

        val response = runBlocking { api.uploadAttachments(channelId, listOf(file)) }

        assertTrue(response.isSuccessful)
        val request = requireNotNull(captured)
        val body = runBlocking { request.body.toByteArray() }.decodeToString()
        assertEquals("/api/attachments/upload", request.url.encodedPath)
        assertEquals("Bearer old-access", request.headers["Authorization"])
        assertEquals("server-1", request.headers["X-Server-Id"])
        assertTrue(body.contains("name=channelId"))
        assertTrue(body.contains("channel-1"))
        assertTrue(body.contains("name=files"))
        assertTrue(body.contains("filename=\"sample.png\"") || body.contains("filename=sample.png"))
        assertTrue(body.contains("Content-Type: image/png"))
        assertTrue(body.contains("hello"))
    }

    private class InMemoryAuthSessionStore(
            private var session: AuthSession?
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

    private class RefreshingAuthApi : AuthApi {
        override suspend fun login(body: RequestBody): ApiResponse =
                unsupported()

        override suspend fun register(body: RequestBody): ApiResponse =
                unsupported()

        override suspend fun forgotPassword(body: RequestBody): ApiResponse =
                unsupported()

        override suspend fun currentUser(authorization: String): ApiResponse =
                unsupported()

        override suspend fun refresh(body: RequestBody): ApiResponse =
                ApiResponse.success(
                        """{"accessToken":"new-access","refreshToken":"new-refresh"}"""
                                .toResponseBody()
                )

        override suspend fun logout(body: RequestBody): ApiResponse =
                unsupported()

        override suspend fun servers(authorization: String): ApiResponse =
                unsupported()

        override suspend fun createServer(
                authorization: String,
                body: RequestBody
        ): ApiResponse = unsupported()

        private fun unsupported(): ApiResponse =
                error("Unexpected auth API call")
    }

    private companion object {
        private val sampleSession =
                AuthSession(
                        user =
                                AuthUser(
                                        id = "user-1",
                                        email = "user@slock.ai",
                                        name = "User",
                                        displayName = "Slock User",
                                        description = null,
                                        avatarUrl = null,
                                        emailVerified = true
                                ),
                        accessToken = "old-access",
                        refreshToken = "old-refresh",
                        selectedServerId = "server-1",
                        selectedServerSlug = "slock",
                        servers =
                                listOf(
                                        AuthServer(
                                                id = "server-1",
                                                name = "Slock",
                                                slug = "slock",
                                                ownerId = "user-1",
                                                onboardingAgentId = null,
                                                plan = "team",
                                                planDowngradedAt = null,
                                                role = "owner",
                                                createdAt = null
                                        )
                                )
                )
    }
}
