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
    fun requestPreservesSearchQueryThroughUrlEncoding() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val logs = mutableListOf<String>()
        val httpClient =
            HttpClient(
                MockEngine { request ->
                    requests += request
                    respond(
                        content = """{"results":[],"hasMore":false}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            )
        val client =
            SharedKtorSlockNetworkClient(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                baseUrl = "https://api.slock.test/api",
                warningLogger = logs::add,
                foregroundHttpClient = httpClient,
                visibleHttpClient = httpClient,
                backgroundHttpClient = httpClient
            )

        val query = " do not C++ #tag/中文 "
        val response =
            client.request(
                priority = DataRequestPriority.Visible,
                path = "messages/search",
                query = listOf("q" to query, "limit" to "30", "offset" to "0")
            )

        assertEquals(200, response.code)
        val request = requests.single()
        assertEquals("/api/messages/search", request.url.encodedPath)
        assertEquals(query, request.url.parameters["q"])
        assertEquals("30", request.url.parameters["limit"])
        assertEquals("0", request.url.parameters["offset"])
        assertEquals("q=%20do%20not%20C%2B%2B%20%23tag%2F%E4%B8%AD%E6%96%87%20&limit=30&offset=0", request.url.encodedQuery)
        assertTrue(logs.any { it.contains("category=message_search") && it.contains("queryMeta=qLen=18;qWords=4;qFlags=space|plus|hash|nonAscii") })
        assertTrue(logs.none { it.contains("do not") || it.contains("中文") })
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
