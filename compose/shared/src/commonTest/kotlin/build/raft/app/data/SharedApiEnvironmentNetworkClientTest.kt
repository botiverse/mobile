package build.raft.app.data

import build.raft.app.network.MultipartFile
import build.raft.app.network.SlockHttpExecutor
import build.raft.app.network.SlockHttpExecutorResponse
import build.raft.app.network.SlockRequestPriority
import build.raft.app.network.SlockRequestScheduler
import build.raft.app.network.SlockRequestTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SharedApiEnvironmentNetworkClientTest {
    @Test
    fun authInviteTransportKeepsRawExecutorUrlButRedactsSuccessAndHttpFailureDiagnostics() = runBlocking {
        val token = "invite-capability-token"
        listOf(200, 500).forEach { responseCode ->
            val logs = mutableListOf<String>()
            val schedulerLogs = mutableListOf<String>()
            val executor = RecordingHttpExecutor(responseCode = responseCode)
            val transport =
                SharedSlockAuthTransport(
                    baseUrl = "https://api.raft.build/api/",
                    executor = executor,
                    warningLogger = logs::add,
                    requestScheduler = SlockRequestScheduler(logger = schedulerLogs::add),
                )

            val response = transport.inviteInfo(token)

            assertEquals(responseCode, response.code)
            assertEquals(
                "https://api.raft.build/api/auth/invite-info?token=$token",
                executor.urls.single(),
            )
            val diagnostics = (logs + schedulerLogs).joinToString("\n")
            assertFalse(diagnostics.contains(token))
            assertTrue(logs.any { "path=auth/invite-info?token=[redacted]" in it })
            assertTrue(schedulerLogs.any { "key=auth/invite-info?token=[redacted]" in it })
        }
    }

    @Test
    fun authInviteTransportDoesNotCopyRawTimeoutExceptionTextIntoErrorOrDiagnostics() = runBlocking {
        val token = "invite-timeout-capability"
        val logs = mutableListOf<String>()
        val schedulerLogs = mutableListOf<String>()
        val executor =
            RecordingHttpExecutor(
                executeException =
                    SlockRequestTimeoutException(
                        "executor timeout url=https://api.raft.build/api/auth/invite-info?token=$token#fragment",
                    ),
            )
        val transport =
            SharedSlockAuthTransport(
                baseUrl = "https://api.raft.build/api/",
                executor = executor,
                warningLogger = logs::add,
                requestScheduler = SlockRequestScheduler(logger = schedulerLogs::add),
            )

        val response = transport.inviteInfo(token)

        assertEquals(0, response.code)
        assertFalse(response.errorBodyText.orEmpty().contains(token))
        assertEquals(
            "https://api.raft.build/api/auth/invite-info?token=$token",
            executor.urls.single(),
        )
        val diagnostics = (logs + schedulerLogs).joinToString("\n")
        assertFalse(diagnostics.contains(token))
        assertTrue(response.errorBodyText.orEmpty().contains("request_timeout"))
    }

    @Test
    fun refreshAttemptUsesCanonicalReplayHeaders() = runBlocking {
        val executor = RecordingHttpExecutor(responseCode = 401)
        val transport =
            SharedSlockAuthTransport(
                baseUrl = "https://api.raft.build/api/",
                executor = executor,
            )

        transport.refreshWithAttempt(
            bodyText = """{"refreshToken":"secret-predecessor"}""",
            attemptId = "arf_0123456789abcdef",
            installationId = "ari_0123456789abcdef0123456789abcdef",
        )

        assertEquals("arf_0123456789abcdef", executor.headers.single()[SLOCK_AUTH_REFRESH_ATTEMPT_HEADER])
        assertEquals(
            "ari_0123456789abcdef0123456789abcdef",
            executor.headers.single()[SLOCK_AUTH_INSTALLATION_HEADER],
        )
    }

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
    fun boundJsonEnvironmentMismatchSkipsExecutor() = runBlocking {
        val environmentStore = SharedApiEnvironmentStore(InMemorySlockKeyValueStore())
        environmentStore.select("staging")
        val executor = RecordingHttpExecutor()
        val client =
            SharedSlockNetworkClient(
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
        val environmentStore = SharedApiEnvironmentStore(InMemorySlockKeyValueStore())
        val executor = RecordingHttpExecutor()
        val capturedEnvironment = environmentStore.current().apiBaseUrl
        val client =
            SharedSlockNetworkClient(
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
        val environmentStore = SharedApiEnvironmentStore(InMemorySlockKeyValueStore())
        val capturedEnvironment = environmentStore.current().apiBaseUrl
        val executor =
            RecordingHttpExecutor(
                responseCode = 401,
                onExecute = { environmentStore.select("staging") },
            )
        val client =
            SharedSlockNetworkClient(
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
    fun productionInviteInfoUsesProductionTransportAndNonProductionHasZeroEgress() = runBlocking {
        val environmentStore = SharedApiEnvironmentStore(InMemorySlockKeyValueStore())
        val executor =
            RecordingHttpExecutor(
                responseBodyText =
                    """{"kind":"join_link","serverName":"Raft","memberCount":1,"agentCount":0}""",
            )
        val dataSource =
            createSharedAuthDataSourceForCurrentEnvironment(
                apiEnvironmentStore = environmentStore,
                authStore = InMemorySlockAuthSessionStore(),
                transportFactory = { baseUrlProvider ->
                    SharedSlockAuthTransport(baseUrlProvider = baseUrlProvider, executor = executor)
                },
            )
        val token = "production-invite-token"

        val production = dataSource.loadInviteInfo(token)

        assertTrue(production is SharedInviteInfoResult.Success)
        assertEquals(
            listOf("${SharedApiEnvironmentStore.Production.apiBaseUrl.trimEnd('/')}/auth/invite-info?token=$token"),
            executor.urls,
        )

        environmentStore.select("staging")
        val staging = dataSource.loadInviteInfo(token)
        assertTrue(staging is SharedInviteInfoResult.Failure)
        assertEquals(AuthInviteEnvironmentUnavailableCode, staging.errorCode)
        assertEquals(1, executor.urls.size)

        environmentStore.save(
            SharedSettingsApiEnvironmentRequest(
                key = SharedApiEnvironmentStore.CustomKey,
                label = "Custom",
                apiBaseUrl = "http://evil.example/api/",
                socketBaseUrl = "http://evil.example",
                webUrl = "http://evil.example",
            )
        )
        val custom = dataSource.loadInviteInfo(token)
        assertTrue(custom is SharedInviteInfoResult.Failure)
        assertEquals(AuthInviteEnvironmentUnavailableCode, custom.errorCode)
        assertEquals(1, executor.urls.size)
    }

    @Test
    fun productionInviteAcceptUsesProductionTransportAndNonProductionHasZeroEgress() = runBlocking {
        val environmentStore = SharedApiEnvironmentStore(InMemorySlockKeyValueStore())
        val sessionStore = authenticatedSessionStore()
        val executor =
            RecordingHttpExecutor(
                responseBodyText = """{"serverId":"server-1","serverName":"Raft"}""",
            )
        val dataSource =
            createSharedAuthDataSourceForCurrentEnvironment(
                apiEnvironmentStore = environmentStore,
                authStore = sessionStore,
                transportFactory = { baseUrlProvider ->
                    SharedSlockAuthTransport(baseUrlProvider = baseUrlProvider, executor = executor)
                },
            )
        val token = "production-invite-token"

        val production = dataSource.acceptInvite(token)

        assertTrue(production is SharedInviteAcceptResult.Success)
        assertEquals("user-1", production.ownerAccountId)
        assertEquals(
            listOf("${SharedApiEnvironmentStore.Production.apiBaseUrl.trimEnd('/')}/auth/accept-invite"),
            executor.urls,
        )

        environmentStore.select("staging")
        val staging = dataSource.acceptInvite(token)
        assertTrue(staging is SharedInviteAcceptResult.Failure)
        assertEquals(AuthInviteEnvironmentUnavailableCode, staging.errorCode)
        assertEquals(1, executor.urls.size)

        environmentStore.save(
            SharedSettingsApiEnvironmentRequest(
                key = SharedApiEnvironmentStore.CustomKey,
                label = "Custom",
                apiBaseUrl = "http://evil.example/api/",
                socketBaseUrl = "http://evil.example",
                webUrl = "http://evil.example",
            )
        )
        val custom = dataSource.acceptInvite(token)
        assertTrue(custom is SharedInviteAcceptResult.Failure)
        assertEquals(AuthInviteEnvironmentUnavailableCode, custom.errorCode)
        assertEquals(1, executor.urls.size)
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
        private val responseCode: Int = 200,
        private val responseBodyText: String? = "{}",
        private val responseErrorBodyText: String? = null,
        private val executeException: Throwable? = null,
        private val onExecute: (String) -> Unit = {},
    ) : SlockHttpExecutor {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
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
            this.headers += headers
            onExecute(url)
            executeException?.let { throw it }
            return SlockHttpExecutorResponse(
                code = responseCode,
                bodyText = responseBodyText,
                errorBodyText = responseErrorBodyText,
            )
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
            return SlockHttpExecutorResponse(
                code = responseCode,
                bodyText = responseBodyText,
                errorBodyText = responseErrorBodyText,
            )
        }
    }

    private fun authenticatedSessionStore(): InMemorySlockAuthSessionStore =
        InMemorySlockAuthSessionStore(
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                accessToken = "access-token",
                refreshToken = "refresh-token",
                selectedServerId = "server-1",
                servers = listOf(StoredAuthServer(id = "server-1", name = "Raft")),
            )
        )

    private fun expectedBinding(environmentId: String): SlockExpectedAuthBinding =
        SlockExpectedAuthBinding(
            accountId = "user-1",
            accessToken = "access-token",
            refreshToken = "refresh-token",
            serverId = "server-1",
            environmentId = environmentId,
        )
}
