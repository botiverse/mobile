package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
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
    fun fileRefUploadUsesNativeFallbackWithoutKtorRequest() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val httpClient =
            HttpClient(
                MockEngine { request ->
                    requests += request
                    respond("""{"unexpected":true}""", HttpStatusCode.OK)
                }
            )
        val fallback = RecordingFallbackNetworkClient()
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
                nativeFileRefFallback = fallback,
                foregroundHttpClient = httpClient,
                visibleHttpClient = httpClient,
                backgroundHttpClient = httpClient
            )

        val response = client.uploadAccountAvatar(DataRequestPriority.Foreground, file)

        assertEquals(201, response.code)
        assertTrue(requests.isEmpty())
        assertEquals(listOf("account:avatar.png:native://avatar"), fallback.calls)
    }

    private class RecordingFallbackNetworkClient : SlockNetworkClient {
        val calls = mutableListOf<String>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse =
            error("unexpected request fallback")

        override suspend fun uploadAttachments(
            priority: DataRequestPriority,
            channelId: ChannelId,
            files: List<MultipartFile>
        ): HomeNetworkResponse {
            calls += "attachments:${channelId.value}:${files.size}"
            return HomeNetworkResponse(code = 201, bodyText = """{"ok":true}""")
        }

        override suspend fun uploadAccountAvatar(
            priority: DataRequestPriority,
            file: MultipartFile
        ): HomeNetworkResponse {
            calls += "account:${file.fileName}:${file.fileRef}"
            return HomeNetworkResponse(code = 201, bodyText = """{"ok":true}""")
        }

        override suspend fun uploadAgentAvatar(
            priority: DataRequestPriority,
            agentId: MemberId,
            file: MultipartFile
        ): HomeNetworkResponse {
            calls += "agent:${agentId.value}:${file.fileName}"
            return HomeNetworkResponse(code = 201, bodyText = """{"ok":true}""")
        }

        override suspend fun uploadConnectedAppLogo(
            priority: DataRequestPriority,
            clientRecordId: String,
            file: MultipartFile
        ): HomeNetworkResponse {
            calls += "client:$clientRecordId:${file.fileName}"
            return HomeNetworkResponse(code = 201, bodyText = """{"ok":true}""")
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
