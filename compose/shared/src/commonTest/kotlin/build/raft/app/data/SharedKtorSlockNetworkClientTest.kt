package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.network.HEADER_SERVER_ID
import build.raft.app.network.MultipartFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SharedKtorSlockNetworkClientTest {
    @Test
    fun requestUsesKtorWithAuthHeadersAndServerOverride() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val httpClient =
            HttpClient(
                MockEngine { request ->
                    requests += request
                    respond(
                        content = """{"ok":true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            )
        val client =
            SharedKtorSlockNetworkClient(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                baseUrl = "https://api.slock.test/api",
                foregroundHttpClient = httpClient,
                visibleHttpClient = httpClient,
                backgroundHttpClient = httpClient
            )

        val response =
            client.request(
                priority = DataRequestPriority.Visible,
                path = "servers/server-override/members",
                method = "GET",
                query = listOf("limit" to "20"),
                serverIdOverride = ServerId("server-override"),
                bodyText = null
            )

        assertEquals(200, response.code)
        val request = requests.single()
        assertEquals("GET", request.method.value)
        assertEquals("/api/servers/server-override/members", request.url.encodedPath)
        assertEquals("20", request.url.parameters["limit"])
        assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
        assertEquals("server-override", request.headers[HEADER_SERVER_ID])
    }

    @Test
    fun fileRefUploadUsesAppScopeResolverThenKtorRequest() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val httpClient =
            HttpClient(
                MockEngine { request ->
                    requests += request
                    respond("""{"ok":true}""", HttpStatusCode.Created)
                }
            )
        val resolver = RecordingPlatformFileResolver()
        val file =
            MultipartFile(
                name = "avatar",
                fileName = "avatar.png",
                mimeType = "image/png",
                fileRef = "native://avatar",
                sizeBytes = 42
            )
        val client =
            SharedKtorSlockNetworkClient(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                baseUrl = "https://api.slock.test/api",
                platformFileResolver = resolver,
                foregroundHttpClient = httpClient,
                visibleHttpClient = httpClient,
                backgroundHttpClient = httpClient
            )

        val response = client.uploadAccountAvatar(DataRequestPriority.Foreground, file)

        assertEquals(201, response.code)
        assertEquals(listOf("native://avatar"), resolver.calls)
        val request = requests.single()
        assertEquals("POST", request.method.value)
        assertEquals("/api/auth/me/avatar", request.url.encodedPath)
        assertEquals("Bearer access-token", request.headers[HttpHeaders.Authorization])
    }

    private class RecordingPlatformFileResolver : SlockPlatformFileResolver {
        val calls = mutableListOf<String>()

        override suspend fun readFile(fileRef: String): SlockPlatformFileReadResult {
            calls += fileRef
            return SlockPlatformFileReadResult(
                code = 200,
                bytes = byteArrayOf(1, 2, 3),
                sizeBytes = 3
            )
        }
    }

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.com", name = "User"),
            accessToken = "access-token",
            refreshToken = "refresh-token",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock"))
        )
}
