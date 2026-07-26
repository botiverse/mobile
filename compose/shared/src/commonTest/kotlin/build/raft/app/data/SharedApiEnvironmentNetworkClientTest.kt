package build.raft.app.data

import build.raft.app.network.MultipartFile
import build.raft.app.network.SlockHttpExecutor
import build.raft.app.network.SlockHttpExecutorResponse
import build.raft.app.network.SlockRequestPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SharedApiEnvironmentNetworkClientTest {
    @Test
    fun boundMultipartAuthorityMismatchSkipsExecutorAndPlatformStreamOpen() = runBlocking {
        val sessionStore =
            InMemorySlockAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-b", email = "b@raft.build"),
                    accessToken = "access-b",
                    refreshToken = "refresh-b",
                    selectedServerId = "server-1",
                    servers = listOf(StoredAuthServer(id = "server-1", name = "Raft"))
                )
            )
        var streamOpenCalls = 0
        val resolver =
            object : SlockPlatformFileResolver {
                override suspend fun readFile(fileRef: String): SlockPlatformFileReadResult =
                    error("whole-file read must not run")

                override suspend fun statFile(fileRef: String): SlockPlatformFileStatResult =
                    SlockPlatformFileStatResult(code = 200, sizeBytes = 4L)

                override suspend fun openFileStream(
                    fileRef: String,
                    expectedSizeBytes: Long
                ): SlockPlatformFileChunkSource {
                    streamOpenCalls += 1
                    return object : SlockPlatformFileChunkSource {
                        override suspend fun read(maxBytes: Int): ByteArray = ByteArray(0)
                        override fun close() = Unit
                    }
                }
            }
        val executor = RecordingHttpExecutor()
        val client =
            SharedSlockNetworkClient(
                sessionStore = sessionStore,
                platformFileResolver = resolver,
                executor = executor
            )

        val response =
            client.uploadAttachmentsBound(
                priority = DataRequestPriority.Foreground,
                channelId = build.raft.app.model.ChannelId("channel-a"),
                files =
                    listOf(
                        MultipartFile(
                            name = "files",
                            fileName = "private.txt",
                            mimeType = "text/plain",
                            fileRef = "private/file",
                            sizeBytes = 4L
                        )
                    ),
                expectedAuthority =
                    SlockExpectedAuthBinding(
                        accountId = "user-a",
                        accessToken = "access-a",
                        refreshToken = "refresh-a",
                        serverId = "server-1"
                    )
            )

        assertEquals(409, response.code)
        assertEquals(0, executor.multipartCalls)
        assertEquals(0, streamOpenCalls)
    }

    @Test
    fun currentEnvironmentAuthDataSourceTracksLatestApiEnvironmentWithoutRecreation() = runBlocking {
        val environmentStore = SharedApiEnvironmentStore(InMemorySlockKeyValueStore())
        val executor = RecordingHttpExecutor(responseCode = 500)
        val sessionStore =
            InMemorySlockAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                    accessToken = "staging-access-token",
                    refreshToken = "staging-refresh-token"
                )
            )

        val dataSource = createSharedAuthDataSourceForCurrentEnvironment(
            apiEnvironmentStore = environmentStore,
            authStore = sessionStore,
            transportFactory = { baseUrlProvider ->
                SharedSlockAuthTransport(
                    baseUrlProvider = baseUrlProvider,
                    executor = executor
                )
            }
        )
        dataSource.loadServers()
        environmentStore.select("staging")
        dataSource.loadServers()

        assertEquals(
            listOf(
                "${SharedApiEnvironmentStore.Production.apiBaseUrl.trimEnd('/')}/servers",
                "${SharedApiEnvironmentStore.Staging.apiBaseUrl.trimEnd('/')}/servers"
            ),
            executor.urls
        )
    }

    @Test
    fun sharedNetworkClientReadsLatestApiEnvironmentForEachRequest() = runBlocking {
        val environmentStore = SharedApiEnvironmentStore(InMemorySlockKeyValueStore())
        val executor = RecordingHttpExecutor()
        val sessionStore =
            InMemorySlockAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    selectedServerId = "server-1",
                    servers = listOf(StoredAuthServer(id = "server-1", name = "Raft"))
                )
            )
        val client =
            SharedSlockNetworkClient(
                sessionStore = sessionStore,
                baseUrlProvider = { environmentStore.current().apiBaseUrl },
                executor = executor
            )

        client.request(
            priority = DataRequestPriority.Foreground,
            path = "channels",
            method = "GET",
            query = emptyList(),
            serverIdOverride = null,
            bodyText = null
        )
        environmentStore.select("staging")
        client.request(
            priority = DataRequestPriority.Foreground,
            path = "channels",
            method = "GET",
            query = emptyList(),
            serverIdOverride = null,
            bodyText = null
        )

        assertEquals(
            listOf(
                "${SharedApiEnvironmentStore.Production.apiBaseUrl.trimEnd('/')}/channels",
                "${SharedApiEnvironmentStore.Staging.apiBaseUrl.trimEnd('/')}/channels"
            ),
            executor.urls
        )
    }

    @Test
    fun sharedNetworkClientReservesOnlyHttp401EndpointDiagnostics() = runBlocking {
        val normal = mutableListOf<String>()
        val critical = mutableListOf<String>()

        suspend fun requestWith(code: Int) {
            val sessionStore =
                InMemorySlockAuthSessionStore(
                    StoredAuthSession(
                        user = StoredAuthUser(id = "user-$code", email = "user-$code@raft.build"),
                        accessToken = "access-$code",
                        refreshToken = "refresh-$code",
                        selectedServerId = "server-1",
                        servers = listOf(StoredAuthServer(id = "server-1", name = "Raft"))
                    )
                )
            SharedSlockNetworkClient(
                sessionStore = sessionStore,
                warningLogger = normal::add,
                criticalWarningLogger = critical::add,
                executor = RecordingHttpExecutor(responseCode = code)
            ).request(
                priority = DataRequestPriority.Foreground,
                path = "channels",
                method = "GET",
                query = emptyList(),
                serverIdOverride = null,
                bodyText = null
            )
        }

        requestWith(401)
        requestWith(403)

        assertTrue(critical.any { "endpoint category=channels" in it && "code=401" in it })
        assertFalse(critical.any { "endpoint category=channels" in it && "code=403" in it })
        assertTrue(normal.any { "endpoint category=channels" in it && "code=403" in it })
    }

    private class RecordingHttpExecutor(
        private val responseCode: Int = 200
    ) : SlockHttpExecutor {
        val urls = mutableListOf<String>()
        var multipartCalls = 0

        override suspend fun execute(
            priority: SlockRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean
        ): SlockHttpExecutorResponse {
            urls += url
            return SlockHttpExecutorResponse(code = responseCode, bodyText = "{}", errorBodyText = null)
        }

        override suspend fun executeMultipart(
            priority: SlockRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((build.raft.app.network.SlockTransferProgress) -> Unit)?
        ): SlockHttpExecutorResponse {
            multipartCalls += 1
            urls += url
            return SlockHttpExecutorResponse(code = responseCode, bodyText = "{}", errorBodyText = null)
        }
    }
}
