package ai.slock.compose.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

class SlockApiClientContractTest {
    @Test
    fun memberProfileUsesServerScopedUpstreamRoute() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = apiClient(requests)

        client.memberProfile(SlockRequestPriority.Visible, "server-1", "user-2")

        val request = requests.single()
        assertEquals("GET", request.method.value)
        assertEquals("/api/servers/server-1/members/user-2/profile", request.url.encodedPath)
    }

    @Test
    fun savedMessageMutationsUseChannelsSavedContract() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = apiClient(requests)

        client.saveMessage(SlockRequestPriority.Visible, """{"messageId":"msg-1"}""")
        client.unsaveMessage(SlockRequestPriority.Visible, "msg-1")

        assertEquals("POST", requests[0].method.value)
        assertEquals("/api/channels/saved", requests[0].url.encodedPath)
        assertEquals("DELETE", requests[1].method.value)
        assertEquals("/api/channels/saved/msg-1", requests[1].url.encodedPath)
    }

    @Test
    fun filesAndSearchRoutesUseCanonicalUpstreamContracts() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = apiClient(requests)

        client.channelFiles(SlockRequestPriority.Visible, "channel-1", limit = 25, cursor = "cursor-1")
        client.attachmentUrl(SlockRequestPriority.Visible, "attachment-1", disposition = "attachment")
        client.attachmentPreview(SlockRequestPriority.Visible, "attachment-1")
        client.attachmentHtmlPreviewUrl(SlockRequestPriority.Visible, "attachment-1")
        client.messageContext(SlockRequestPriority.Visible, "short123", channelId = "channel-1")
        client.searchMessages(SlockRequestPriority.Visible, query = "release", limit = 10, offset = 20, sort = "recent")

        assertEquals("/api/channels/channel-1/files", requests[0].url.encodedPath)
        assertEquals("25", requests[0].url.parameters["limit"])
        assertEquals("cursor-1", requests[0].url.parameters["cursor"])
        assertEquals("/api/attachments/attachment-1/url", requests[1].url.encodedPath)
        assertEquals("attachment", requests[1].url.parameters["disposition"])
        assertEquals("/api/attachments/attachment-1/preview", requests[2].url.encodedPath)
        assertEquals("/api/attachments/attachment-1/html-preview-url", requests[3].url.encodedPath)
        assertEquals("/api/messages/context/short123", requests[4].url.encodedPath)
        assertEquals("channel-1", requests[4].url.parameters["channelId"])
        assertEquals("/api/messages/search", requests[5].url.encodedPath)
        assertEquals("release", requests[5].url.parameters["q"])
        assertEquals("recent", requests[5].url.parameters["sort"])
    }

    @Test
    fun messageReactionsUseCanonicalRouteAndMethod() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val client = apiClient(requests)

        client.addMessageReaction(SlockRequestPriority.Visible, "message-1", "👀")
        client.removeMessageReaction(SlockRequestPriority.Visible, "message-1", "👀")

        assertEquals("POST", requests[0].method.value)
        assertEquals("/api/messages/message-1/reactions", requests[0].url.encodedPath)
        assertEquals("DELETE", requests[1].method.value)
        assertEquals("/api/messages/message-1/reactions", requests[1].url.encodedPath)
    }

    private fun apiClient(requests: MutableList<HttpRequestData>): SlockApiClient {
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = """{"ok":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient =
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }
        return SlockApiClient(
            baseUrl = "https://api.slock.test/api",
            httpClient = httpClient,
            authTransport =
                AuthenticatedKtorTransport(
                    tokenProvider =
                        object : AuthTokenProvider {
                            override suspend fun currentTokens(): AuthTokens? = null
                            override suspend fun refreshTokens(failedAccessToken: String): AuthTokens? = null
                            override suspend fun onUnauthorized(failedAccessToken: String?) = Unit
                        }
                )
        )
    }
}
