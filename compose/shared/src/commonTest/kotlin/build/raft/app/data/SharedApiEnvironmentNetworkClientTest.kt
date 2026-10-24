package build.raft.app.data

import build.raft.app.network.MultipartFile
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftRequestPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SharedApiEnvironmentNetworkClientTest {
    @Test
    fun refreshAttemptUsesCanonicalReplayHeaders() = runBlocking {
        val executor = RecordingHttpExecutor(responseCode = 401)
        val transport =
            SharedRaftAuthTransport(
                baseUrl = "https://api.raft.build/api/",
                executor = executor,
            )

        transport.refreshWithAttempt(
            bodyText = """{"refreshToken":"secret-predecessor"}""",
            attemptId = "arf_0123456789abcdef",
            installationId = "ari_0123456789abcdef0123456789abcdef",
        )

        assertEquals("arf_0123456789abcdef", executor.headers.single()[RAFT_AUTH_REFRESH_ATTEMPT_HEADER])
        assertEquals(
            "ari_0123456789abcdef0123456789abcdef",
            executor.headers.single()[RAFT_AUTH_INSTALLATION_HEADER],
        )
    }

    @Test
    fun boundMultipartAuthorityMismatchSkipsExecutorAndPlatformStreamOpen() = runBlocking {
        val sessionStore =
            InMemoryRaftAuthSessionStore(
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
            object : RaftPlatformFileResolver {
                override suspend fun readFile(fileRef: String): RaftPlatformFileReadResult =
                    error("whole-file read must not run")

                override suspend fun statFile(fileRef: String): RaftPlatformFileStatResult =
                    RaftPlatformFileStatResult(code = 200, sizeBytes = 4L)

                override suspend fun openFileStream(
                    fileRef: String,
                    expectedSizeBytes: Long
                ): RaftPlatformFileChunkSource {
                    streamOpenCalls += 1
                    return object : RaftPlatformFileChunkSource {
                        override suspend fun read(maxBytes: Int): ByteArray = ByteArray(0)
                        override fun close() = Unit
                    }
                }
            }
        val executor = RecordingHttpExecutor()
        val client =
            SharedRaftNetworkClient(
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
                    RaftExpectedAuthBinding(
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
    fun boundJsonEnvironmentMismatchSkipsExecutor() = runBlocking {
        val environmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore())
        environmentStore.select("staging")
        val executor = RecordingHttpExecutor()
        val client =
            SharedRaftNetworkClient(
                sessionStore = authenticatedSessionStore(),
                baseUrlProvider = { environmentStore.current().apiBaseUrl },
                executor = executor,
            ).boundTo(
                expectedBinding(
                    environmentId = SharedApiEnvironmentStore.Production.apiBaseUrl,
                )
            )

        val response = client.request(DataRequestPriority.Foreground, "channels/channel-1")

        assertEquals(409, response.code)
        assertEquals(emptyList(), executor.urls)
    }

    @Test
    fun boundJsonRequestUsesCapturedEnvironmentBaseUrl() = runBlocking {
        val environmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore())
        val executor = RecordingHttpExecutor()
        val capturedEnvironment = environmentStore.current().apiBaseUrl
        val client =
            SharedRaftNetworkClient(
                sessionStore = authenticatedSessionStore(),
                baseUrlProvider = { environmentStore.current().apiBaseUrl },
                executor = executor,
            ).boundTo(expectedBinding(environmentId = capturedEnvironment))

        val response = client.request(DataRequestPriority.Foreground, "channels/channel-1")

        assertEquals(200, response.code)
        assertEquals(
            listOf("${capturedEnvironment.trimEnd('/')}/channels/channel-1"),
            executor.urls,
        )
    }

    @Test
    fun boundJsonEnvironmentChangeDuringTransportSkipsOldEnvironmentRefresh() = runBlocking {
        val environmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore())
        val capturedEnvironment = environmentStore.current().apiBaseUrl
        val executor =
            RecordingHttpExecutor(
                responseCode = 401,
                onExecute = { environmentStore.select("staging") },
            )
        val client =
            SharedRaftNetworkClient(
                sessionStore = authenticatedSessionStore(),
                baseUrlProvider = { environmentStore.current().apiBaseUrl },
                executor = executor,
            ).boundTo(expectedBinding(environmentId = capturedEnvironment))

        val response = client.request(DataRequestPriority.Foreground, "channels/channel-1")

        assertEquals(409, response.code)
        assertEquals(
            listOf("${capturedEnvironment.trimEnd('/')}/channels/channel-1"),
            executor.urls,
        )
    }

    @Test
    fun currentEnvironmentAuthDataSourceTracksLatestApiEnvironmentWithoutRecreation() = runBlocking {
        val environmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore())
        val executor = RecordingHttpExecutor(responseCode = 500)
        val sessionStore =
            InMemoryRaftAuthSessionStore(
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
                SharedRaftAuthTransport(
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
        val environmentStore = SharedApiEnvironmentStore(InMemoryRaftKeyValueStore())
        val executor = RecordingHttpExecutor()
        val sessionStore =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                    accessToken = "access-token",
                    refreshToken = "refresh-token",
                    selectedServerId = "server-1",
                    servers = listOf(StoredAuthServer(id = "server-1", name = "Raft"))
                )
            )
        val client =
            SharedRaftNetworkClient(
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
                InMemoryRaftAuthSessionStore(
                    StoredAuthSession(
                        user = StoredAuthUser(id = "user-$code", email = "user-$code@raft.build"),
                        accessToken = "access-$code",
                        refreshToken = "refresh-$code",
                        selectedServerId = "server-1",
                        servers = listOf(StoredAuthServer(id = "server-1", name = "Raft"))
                    )
                )
            SharedRaftNetworkClient(
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
        private val responseCode: Int = 200,
        private val onExecute: (String) -> Unit = {},
    ) : RaftHttpExecutor {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        var multipartCalls = 0

        override suspend fun execute(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean
        ): RaftHttpExecutorResponse {
            urls += url
            this.headers += headers
            onExecute(url)
            return RaftHttpExecutorResponse(code = responseCode, bodyText = "{}", errorBodyText = null)
        }

        override suspend fun executeMultipart(
            priority: RaftRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((build.raft.app.network.RaftWireTransferProgress) -> Unit)?
        ): RaftHttpExecutorResponse {
            multipartCalls += 1
            urls += url
            return RaftHttpExecutorResponse(code = responseCode, bodyText = "{}", errorBodyText = null)
        }
    }

    private fun authenticatedSessionStore(): InMemoryRaftAuthSessionStore =
        InMemoryRaftAuthSessionStore(
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                accessToken = "access-token",
                refreshToken = "refresh-token",
                selectedServerId = "server-1",
                servers = listOf(StoredAuthServer(id = "server-1", name = "Raft")),
            )
        )

    private fun expectedBinding(environmentId: String): RaftExpectedAuthBinding =
        RaftExpectedAuthBinding(
            accountId = "user-1",
            accessToken = "access-token",
            refreshToken = "refresh-token",
            serverId = "server-1",
            environmentId = environmentId,
        )
}
