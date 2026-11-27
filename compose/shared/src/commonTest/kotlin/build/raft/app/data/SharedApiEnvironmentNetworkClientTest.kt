package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.network.HEADER_AUTHORIZATION
import build.raft.app.network.HEADER_SERVER_ID
import build.raft.app.network.MultipartFile
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftRequestPriority
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun unboundRequestCapturesServerIdAtInitiationAndKeepsAcross401RefreshRetry() = runBlocking {
        val initial = multiServerSession(selectedServerId = "server-1")
        val backing = InMemoryRaftAuthSessionStore(initial)
        var readCount = 0
        // Simulate a server switch right after request initiation (before auth middleware executes).
        val switchingStore =
            object : RaftAuthSessionStore by backing {
                override suspend fun readSession(): StoredAuthSession? {
                    val current = backing.readSession()
                    readCount += 1
                    if (readCount == 1 && current != null) {
                        backing.saveSession(current.copy(selectedServerId = "server-2"))
                    }
                    return current
                }
            }
        val executor =
            ScriptedHttpExecutor { url, headers ->
                when {
                    url.endsWith("/auth/refresh") ->
                        RaftHttpExecutorResponse(
                            code = 200,
                            bodyText = """{"accessToken":"fresh-access","refreshToken":"fresh-refresh"}""",
                            errorBodyText = null,
                        )
                    headers[HEADER_AUTHORIZATION] == "Bearer access-token" ->
                        RaftHttpExecutorResponse(
                            code = 401,
                            bodyText = null,
                            errorBodyText = """{"error":"expiredToken"}""",
                        )
                    else ->
                        RaftHttpExecutorResponse(code = 200, bodyText = """{"ok":true}""", errorBodyText = null)
                }
            }
        val client =
            SharedRaftNetworkClient(
                sessionStore = switchingStore,
                baseUrl = "https://api.raft.build/api/",
                executor = executor,
            )

        val response = client.request(DataRequestPriority.Foreground, "channels/channel-1/messages")

        assertEquals(200, response.code)
        val resourceCalls = executor.requests.filter { it.first.endsWith("/channels/channel-1/messages") }
        assertEquals(2, resourceCalls.size)
        assertEquals("server-1", resourceCalls[0].second[HEADER_SERVER_ID])
        assertEquals("Bearer access-token", resourceCalls[0].second[HEADER_AUTHORIZATION])
        assertEquals("server-1", resourceCalls[1].second[HEADER_SERVER_ID])
        assertEquals("Bearer fresh-access", resourceCalls[1].second[HEADER_AUTHORIZATION])
        assertEquals("server-2", backing.readSession()?.currentServerId)
    }

    @Test
    fun explicitServerIdOverridePreservedAcross401RefreshWhenCurrentServerSwitches() = runBlocking {
        val initial = multiServerSession(selectedServerId = "server-1")
        val store = InMemoryRaftAuthSessionStore(initial)
        val executor =
            ScriptedHttpExecutor { url, headers ->
                when {
                    url.endsWith("/auth/refresh") -> {
                        store.updateSelectedServer(serverId = "server-2", serverSlug = null)
                        RaftHttpExecutorResponse(
                            code = 200,
                            bodyText = """{"accessToken":"fresh-access","refreshToken":"fresh-refresh"}""",
                            errorBodyText = null,
                        )
                    }
                    headers[HEADER_AUTHORIZATION] == "Bearer access-token" ->
                        RaftHttpExecutorResponse(
                            code = 401,
                            bodyText = null,
                            errorBodyText = """{"error":"expiredToken"}""",
                        )
                    else ->
                        RaftHttpExecutorResponse(code = 200, bodyText = """{"ok":true}""", errorBodyText = null)
                }
            }
        val client =
            SharedRaftNetworkClient(
                sessionStore = store,
                baseUrl = "https://api.raft.build/api/",
                executor = executor,
            )

        val response =
            client.request(
                priority = DataRequestPriority.Foreground,
                path = "channels",
                serverIdOverride = ServerId("server-explicit"),
            )

        assertEquals(200, response.code)
        val resourceCalls = executor.requests.filter { it.first.endsWith("/channels") }
        assertEquals(2, resourceCalls.size)
        assertEquals(listOf("server-explicit", "server-explicit"), resourceCalls.map { it.second[HEADER_SERVER_ID] })
        assertEquals("Bearer fresh-access", resourceCalls[1].second[HEADER_AUTHORIZATION])
        assertEquals("server-2", store.readSession()?.currentServerId)
    }

    @Test
    fun unboundMultipartUploadCapturesServerIdBeforeFileResolutionAndKeepsAcross401Refresh() = runBlocking {
        val initial = multiServerSession(selectedServerId = "server-1")
        val store = InMemoryRaftAuthSessionStore(initial)
        val resolver =
            object : RaftPlatformFileResolver {
                override suspend fun readFile(fileRef: String): RaftPlatformFileReadResult =
                    error("whole-file read must not run")

                override suspend fun statFile(fileRef: String): RaftPlatformFileStatResult {
                    // User switches servers while the file is being prepared before upload dispatch.
                    store.saveSession(initial.copy(selectedServerId = "server-2"))
                    return RaftPlatformFileStatResult(code = 200, sizeBytes = 4L)
                }

                override suspend fun openFileStream(
                    fileRef: String,
                    expectedSizeBytes: Long,
                ): RaftPlatformFileChunkSource =
                    object : RaftPlatformFileChunkSource {
                        private var sent = false
                        override suspend fun read(maxBytes: Int): ByteArray =
                            if (sent) {
                                ByteArray(0)
                            } else {
                                sent = true
                                byteArrayOf(1, 2, 3, 4)
                            }

                        override fun close() = Unit
                    }
            }
        val executor =
            ScriptedHttpExecutor(
                onExecute = { url, _ ->
                    if (url.endsWith("/auth/refresh")) {
                        RaftHttpExecutorResponse(
                            code = 200,
                            bodyText = """{"accessToken":"fresh-access","refreshToken":"fresh-refresh"}""",
                            errorBodyText = null,
                        )
                    } else {
                        RaftHttpExecutorResponse(code = 200, bodyText = "{}", errorBodyText = null)
                    }
                },
                onMultipart = { _, headers ->
                    if (headers[HEADER_AUTHORIZATION] == "Bearer access-token") {
                        RaftHttpExecutorResponse(
                            code = 401,
                            bodyText = null,
                            errorBodyText = """{"error":"expiredToken"}""",
                        )
                    } else {
                        RaftHttpExecutorResponse(code = 200, bodyText = """{"ok":true}""", errorBodyText = null)
                    }
                },
            )
        val client =
            SharedRaftNetworkClient(
                sessionStore = store,
                baseUrl = "https://api.raft.build/api/",
                platformFileResolver = resolver,
                executor = executor,
            )

        val response =
            client.uploadAttachments(
                priority = DataRequestPriority.Foreground,
                channelId = ChannelId("channel-a"),
                files =
                    listOf(
                        MultipartFile(
                            name = "files",
                            fileName = "note.txt",
                            mimeType = "text/plain",
                            fileRef = "local/note.txt",
                            sizeBytes = 4L,
                        )
                    ),
            )

        assertEquals(200, response.code)
        assertEquals(2, executor.multipartHeaders.size)
        assertEquals(listOf("server-1", "server-1"), executor.multipartHeaders.map { it[HEADER_SERVER_ID] })
        assertEquals("Bearer fresh-access", executor.multipartHeaders[1][HEADER_AUTHORIZATION])
        assertEquals("server-2", store.readSession()?.currentServerId)
    }

    @Test
    fun boundRequestStillRejectsWhenServerSwitchesBetweenInitiationAndDispatch() = runBlocking {
        val initial = multiServerSession(selectedServerId = "server-1")
        val backing = InMemoryRaftAuthSessionStore(initial)
        var readCount = 0
        val switchingStore =
            object : RaftAuthSessionStore by backing {
                override suspend fun readSession(): StoredAuthSession? {
                    val current = backing.readSession()
                    readCount += 1
                    if (readCount == 1 && current != null) {
                        backing.saveSession(current.copy(selectedServerId = "server-2"))
                    }
                    return current
                }
            }
        val executor = RecordingHttpExecutor()
        val baseUrl = "https://api.raft.build/api/"
        val client =
            SharedRaftNetworkClient(
                sessionStore = switchingStore,
                baseUrl = baseUrl,
                executor = executor,
            ).boundTo(expectedBinding(environmentId = baseUrl))

        val response = client.request(DataRequestPriority.Foreground, "channels/channel-1")

        assertEquals(409, response.code)
        assertEquals(emptyList(), executor.urls)
        assertEquals(true, response.errorBodyText?.contains("auth_authority_changed"))
    }

    private class ScriptedHttpExecutor(
        private val onExecute: suspend (String, Map<String, String>) -> RaftHttpExecutorResponse,
        private val onMultipart: suspend (String, Map<String, String>) -> RaftHttpExecutorResponse = { _, _ ->
            RaftHttpExecutorResponse(code = 200, bodyText = "{}", errorBodyText = null)
        },
    ) : RaftHttpExecutor {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val multipartHeaders = mutableListOf<Map<String, String>>()

        override suspend fun execute(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean,
        ): RaftHttpExecutorResponse {
            requests += url to headers
            return onExecute(url, headers)
        }

        override suspend fun executeMultipart(
            priority: RaftRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((build.raft.app.network.RaftWireTransferProgress) -> Unit)?,
        ): RaftHttpExecutorResponse {
            multipartHeaders += headers
            return onMultipart(url, headers)
        }
    }

    private fun multiServerSession(selectedServerId: String): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
            accessToken = "access-token",
            refreshToken = "refresh-token",
            selectedServerId = selectedServerId,
            servers =
                listOf(
                    StoredAuthServer(id = "server-1", name = "Raft One"),
                    StoredAuthServer(id = "server-2", name = "Raft Two"),
                ),
        )

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
