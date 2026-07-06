package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.network.HEADER_SERVER_ID
import build.raft.app.network.MultipartFile
import build.raft.app.network.SlockHttpExecutor
import build.raft.app.network.SlockHttpExecutorResponse
import build.raft.app.network.SlockRequestPriority
import build.raft.app.network.SlockRequestScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class SharedSlockNetworkClientTest {
    private class RecordedRequest(
        val priority: SlockRequestPriority,
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val bodyText: String?
    )

    private class RecordedMultipart(
        val url: String,
        val fields: Map<String, String>,
        val files: List<MultipartFile>,
        val headers: Map<String, String>
    )

    /**
     * Seam fake: the client's contract with the transport is fully observable
     * at [SlockHttpExecutor] — including the exact assembled/encoded URL the
     * transport receives, which the old MockEngine tests only saw re-parsed.
     */
    private class RecordingExecutor(
        private val respond: suspend (url: String) -> SlockHttpExecutorResponse
    ) : SlockHttpExecutor {
        val requests = mutableListOf<RecordedRequest>()
        val multiparts = mutableListOf<RecordedMultipart>()

        override suspend fun execute(
            priority: SlockRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean
        ): SlockHttpExecutorResponse {
            requests += RecordedRequest(priority, url, method, headers, bodyText)
            return respond(url)
        }

        override suspend fun executeMultipart(
            priority: SlockRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>
        ): SlockHttpExecutorResponse {
            multiparts += RecordedMultipart(url, fields, files, headers)
            return respond(url)
        }
    }

    private fun ok(body: String = """{"ok":true}""", code: Int = 200): SlockHttpExecutorResponse =
        SlockHttpExecutorResponse(code = code, bodyText = body, errorBodyText = null)

    @Test
    fun requestUsesExecutorWithAuthHeadersAndServerOverride() = runBlocking {
        val executor = RecordingExecutor { ok() }
        val client =
            SharedSlockNetworkClient(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                baseUrl = "https://api.slock.test/api",
                executor = executor
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
        val request = executor.requests.single()
        assertEquals("GET", request.method)
        assertEquals(SlockRequestPriority.Visible, request.priority)
        assertEquals("https://api.slock.test/api/servers/server-override/members?limit=20", request.url)
        assertEquals("Bearer access-token", request.headers["Authorization"])
        assertEquals("server-override", request.headers[HEADER_SERVER_ID])
    }

    @Test
    fun requestPreservesSearchQueryThroughUrlEncoding() = runBlocking {
        val logs = mutableListOf<String>()
        val executor = RecordingExecutor { ok("""{"results":[],"hasMore":false}""") }
        val client =
            SharedSlockNetworkClient(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                baseUrl = "https://api.slock.test/api",
                warningLogger = logs::add,
                executor = executor
            )

        val query = " do not C++ #tag/中文 "
        val response =
            client.request(
                priority = DataRequestPriority.Visible,
                path = "messages/search",
                query = listOf("q" to query, "limit" to "30", "offset" to "0")
            )

        assertEquals(200, response.code)
        val request = executor.requests.single()
        assertEquals(
            "https://api.slock.test/api/messages/search" +
                "?q=%20do%20not%20C%2B%2B%20%23tag%2F%E4%B8%AD%E6%96%87%20&limit=30&offset=0",
            request.url
        )
        assertTrue(logs.any { it.contains("category=message_search") && it.contains("queryMeta=qLen=18;qWords=4;qFlags=space|plus|hash|nonAscii") })
        assertTrue(logs.none { it.contains("do not") || it.contains("中文") })
    }

    @Test
    fun requestTimeoutReturnsNetworkErrorResponse() = runBlocking {
        val logs = mutableListOf<String>()
        val executor =
            RecordingExecutor {
                delay(50)
                ok()
            }
        val client =
            SharedSlockNetworkClient(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                baseUrl = "https://api.slock.test/api",
                warningLogger = logs::add,
                requestScheduler = SlockRequestScheduler(requestTimeoutMillis = 1),
                executor = executor
            )

        val response =
            client.request(
                priority = DataRequestPriority.Visible,
                path = "servers/server-1/machines",
                serverIdOverride = ServerId("server-1")
            )

        assertEquals(0, response.code)
        assertTrue(response.errorBodyText.orEmpty().contains("network_timeout"))
        assertTrue(logs.any { it.contains("path=servers/server-1/machines") && it.contains("result=network_error") })
    }

    @Test
    fun fileRefUploadUsesAppScopeResolverThenMultipartRequest() = runBlocking {
        val executor = RecordingExecutor { ok(code = 201) }
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
            SharedSlockNetworkClient(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                baseUrl = "https://api.slock.test/api",
                platformFileResolver = resolver,
                executor = executor
            )

        val response = client.uploadAccountAvatar(DataRequestPriority.Foreground, file)

        assertEquals(201, response.code)
        assertEquals(listOf("native://avatar"), resolver.calls)
        val request = executor.multiparts.single()
        assertEquals("https://api.slock.test/api/auth/me/avatar", request.url)
        assertEquals("Bearer access-token", request.headers["Authorization"])
        assertEquals(listOf("avatar"), request.files.map { it.name })
        assertTrue(request.files.single().bytes?.isNotEmpty() == true)
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
