package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.network.HEADER_SERVER_ID
import build.raft.app.network.MultipartFile
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftRequestPriority
import build.raft.app.network.RaftRequestScheduler
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class SharedRaftNetworkClientTest {
    private class RecordedRequest(
        val priority: RaftRequestPriority,
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
     * at [RaftHttpExecutor] — including the exact assembled/encoded URL the
     * transport receives, which the old MockEngine tests only saw re-parsed.
     */
    private class RecordingExecutor(
        private val respond: suspend (url: String) -> RaftHttpExecutorResponse
    ) : RaftHttpExecutor {
        val requests = mutableListOf<RecordedRequest>()
        val multiparts = mutableListOf<RecordedMultipart>()

        override suspend fun execute(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean
        ): RaftHttpExecutorResponse {
            requests += RecordedRequest(priority, url, method, headers, bodyText)
            return respond(url)
        }

        override suspend fun executeMultipart(
            priority: RaftRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((build.raft.app.network.RaftWireTransferProgress) -> Unit)?
        ): RaftHttpExecutorResponse {
            multiparts += RecordedMultipart(url, fields, files, headers)
            return respond(url)
        }
    }

    private fun ok(body: String = """{"ok":true}""", code: Int = 200): RaftHttpExecutorResponse =
        RaftHttpExecutorResponse(code = code, bodyText = body, errorBodyText = null)

    @Test
    fun authTransportLogsSanitizedStartAndFinishForCompletedRequest() = runBlocking {
        val logs = mutableListOf<String>()
        val executor =
            RecordingExecutor {
                RaftHttpExecutorResponse(
                    code = 401,
                    bodyText = null,
                    errorBodyText = """{"error":"invalid_credentials"}""",
                    transportDiagnostics = "engine=networkkmm status=401 kind=none rawCode=OK detail=none"
                )
            }
        val transport =
            SharedRaftAuthTransport(
                baseUrl = "https://api.slock.test/api",
                executor = executor,
                warningLogger = logs::add
            )

        val response = transport.login("""{"email":"a@example.com","password":"secret-password"}""")

        assertEquals(401, response.code)
        val startLog = logs.single { it.startsWith("slock_auth_transport_start") }
        assertContains(startLog, "path=auth/login")
        assertContains(startLog, "method=POST")
        assertContains(startLog, "authorizationPresent=false")
        assertContains(startLog, "requestBytes=")
        val finishLog = logs.single { it.startsWith("slock_auth_transport_finish") }
        assertContains(finishLog, "path=auth/login")
        assertContains(finishLog, "code=401")
        assertContains(finishLog, "status=401")
        logs.forEach { log ->
            assertEquals(false, log.contains("secret-password"), "auth transport diagnostics must not log request bodies")
            assertEquals(false, log.contains("a@example.com"), "auth transport diagnostics must not log request bodies")
        }
    }

    @Test
    fun authTransportLogsRequestIdHeaderOnFailedRefresh() = runBlocking {
        val logs = mutableListOf<String>()
        val executor =
            RecordingExecutor {
                RaftHttpExecutorResponse(
                    code = 401,
                    bodyText = null,
                    errorBodyText = """{"error":"invalid_refresh"}""",
                    responseHeaders = mapOf("x-request-id" to listOf("req_auth_refresh_123"))
                )
            }
        val transport =
            SharedRaftAuthTransport(
                baseUrl = "https://api.slock.test/api",
                executor = executor,
                warningLogger = logs::add
            )

        val response = transport.refresh("""{"refreshToken":"secret-refresh-token"}""")

        assertEquals(401, response.code)
        val finishLog = logs.single { it.startsWith("slock_auth_transport_finish") }
        assertContains(finishLog, "path=auth/refresh")
        assertContains(finishLog, "code=401")
        assertContains(finishLog, "requestId=req_auth_refresh_123")
        logs.forEach { log ->
            assertEquals(false, log.contains("secret-refresh-token"), "auth transport diagnostics must not log request bodies")
        }
    }

    @Test
    fun rawAuthMeFailureLogsRequestIdHeader() = runBlocking {
        val logs = mutableListOf<String>()
        val executor =
            RecordingExecutor {
                RaftHttpExecutorResponse(
                    code = 401,
                    bodyText = null,
                    errorBodyText = """{"error":"unauthorized"}""",
                    responseHeaders = mapOf("X-Request-Id" to listOf("req_auth_me_456"))
                )
            }
        val client =
            SharedRaftNetworkClient(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                baseUrl = "https://api.slock.test/api",
                warningLogger = logs::add,
                executor = executor
            )

        val response =
            client.requestRawAbsoluteUrl(
                priority = DataRequestPriority.Foreground,
                url = "https://api.slock.test/api/auth/me",
                method = "GET",
                headers = emptyMap(),
                bodyText = null
            )

        assertEquals(401, response.code)
        val endpointLog = logs.single { it.startsWith("endpoint category=raw_absolute_url") }
        assertContains(endpointLog, "path=https://api.slock.test/api/auth/me")
        assertContains(endpointLog, "requestId=req_auth_me_456")
    }

    @Test
    fun authTransportTimeoutReturnsNetworkFailureAndLogsPath() = runBlocking {
        val logs = mutableListOf<String>()
        val executor =
            RecordingExecutor {
                delay(200)
                ok()
            }
        val transport =
            SharedRaftAuthTransport(
                baseUrl = "https://api.slock.test/api",
                executor = executor,
                warningLogger = logs::add,
                requestScheduler = RaftRequestScheduler(requestTimeoutMillis = 10)
            )

        val response = transport.login("""{"email":"a@example.com","password":"password"}""")

        assertEquals(0, response.code)
        assertContains(response.errorBodyText.orEmpty(), "network_timeout")
        val request = executor.requests.single()
        assertEquals("POST", request.method)
        assertEquals(RaftRequestPriority.Foreground, request.priority)
        assertEquals("https://api.slock.test/api/auth/login", request.url)
        val startLog = logs.single { it.contains("slock_auth_transport_start") }
        assertContains(startLog, "path=auth/login")
        val timeoutLog = logs.single { it.contains("slock_auth_transport_failed") }
        assertContains(timeoutLog, "path=auth/login")
        assertContains(timeoutLog, "kind=timeout")
        assertEquals(
            emptyList(),
            logs.filter { it.contains("slock_auth_transport_finish") },
            "timed out auth requests must log the timeout failure instead of a finish marker"
        )
    }

    @Test
    fun requestUsesExecutorWithAuthHeadersAndServerOverride() = runBlocking {
        val executor = RecordingExecutor { ok() }
        val client =
            SharedRaftNetworkClient(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
        assertEquals(RaftRequestPriority.Visible, request.priority)
        assertEquals("https://api.slock.test/api/servers/server-override/members?limit=20", request.url)
        assertEquals("Bearer access-token", request.headers["Authorization"])
        assertEquals("server-override", request.headers[HEADER_SERVER_ID])
    }

    @Test
    fun requestPreservesSearchQueryThroughUrlEncoding() = runBlocking {
        val logs = mutableListOf<String>()
        val executor = RecordingExecutor { ok("""{"results":[],"hasMore":false}""") }
        val client =
            SharedRaftNetworkClient(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
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
            SharedRaftNetworkClient(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                baseUrl = "https://api.slock.test/api",
                warningLogger = logs::add,
                requestScheduler = RaftRequestScheduler(requestTimeoutMillis = 1),
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
            SharedRaftNetworkClient(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                baseUrl = "https://api.slock.test/api",
                platformFileResolver = resolver,
                executor = executor
            )

        val response = client.uploadAccountAvatar(DataRequestPriority.Foreground, file)

        assertEquals(201, response.code)
        assertEquals(listOf("native://avatar"), resolver.readCalls)
        val request = executor.multiparts.single()
        assertEquals("https://api.slock.test/api/auth/me/avatar", request.url)
        assertEquals("Bearer access-token", request.headers["Authorization"])
        assertEquals(listOf("avatar"), request.files.map { it.name })
        assertTrue(request.files.single().bytes.isNotEmpty())
        assertEquals(null, request.files.single().streamOpener)
    }

    @Test
    fun threadAttachmentUploadStreamsFileRefAndKeepsFilesFieldName() = runBlocking {
        val executor = RecordingExecutor { ok("""{"attachments":[]}""", code = 200) }
        val resolver = RecordingPlatformFileResolver()
        val file =
            MultipartFile(
                name = "files",
                fileName = "photo.png",
                mimeType = "image/png",
                fileRef = "content://picker/photo",
                sizeBytes = 42
            )
        val client =
            SharedRaftNetworkClient(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                baseUrl = "https://api.slock.test/api",
                platformFileResolver = resolver,
                executor = executor
            )

        val response = client.uploadAttachments(DataRequestPriority.Foreground, ChannelId("channel-1"), listOf(file))

        assertEquals(200, response.code)
        assertEquals(listOf("content://picker/photo"), resolver.statCalls)
        val request = executor.multiparts.single()
        assertEquals("https://api.slock.test/api/attachments/upload", request.url)
        assertEquals(mapOf("channelId" to "channel-1"), request.fields)
        assertEquals("Bearer access-token", request.headers["Authorization"])
        assertEquals("server-1", request.headers[HEADER_SERVER_ID])
        assertEquals(listOf("files"), request.files.map { it.name })
        val resolvedFile = request.files.single()
        assertEquals("photo.png", resolvedFile.fileName)
        assertEquals("image/png", resolvedFile.mimeType)
        assertEquals("content://picker/photo", resolvedFile.fileRef)
        assertEquals(42, resolvedFile.sizeBytes)
        assertTrue(resolvedFile.bytes.isEmpty())
        val opener = assertNotNull(resolvedFile.streamOpener)
        val firstAttempt = opener.open()
        val secondAttempt = opener.open()
        assertNotSame(firstAttempt, secondAttempt)
        assertEquals(
            listOf("content://picker/photo", "content://picker/photo"),
            resolver.openCalls.map { it.first }
        )
        assertEquals(listOf(42L, 42L), resolver.openCalls.map { it.second })
    }

    @Test
    fun frozenLargeThreadAttachmentNeverMaterializesWholeFileBytes() = runTest {
        val executor = RecordingExecutor { ok("""{"attachments":[]}""") }
        val resolver = RecordingPlatformFileResolver(sizeBytes = 199_229_440L)
        val logs = mutableListOf<String>()
        val client =
            SharedRaftNetworkClient(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                baseUrl = "https://api.slock.test/api",
                platformFileResolver = resolver,
                warningLogger = logs::add,
                executor = executor
            )

        val response =
            client.uploadAttachments(
                DataRequestPriority.Foreground,
                ChannelId("channel-1"),
                listOf(
                    MultipartFile(
                        name = "files",
                        fileName = "frozen-190m.bin",
                        mimeType = "application/octet-stream",
                        fileRef = "file:///cache/frozen-190m.bin",
                        sizeBytes = 199_229_440L
                    )
                )
            )

        assertEquals(200, response.code)
        assertEquals(listOf("file:///cache/frozen-190m.bin"), resolver.statCalls)
        assertTrue(resolver.openCalls.isEmpty(), "transport seam must own lazy per-attempt opens")
        val streamed = executor.multiparts.single().files.single()
        assertTrue(streamed.bytes.isEmpty(), "190 MiB must not be copied into MultipartFile.bytes")
        assertEquals(199_229_440L, streamed.sizeBytes)
        assertNotNull(streamed.streamOpener)
        assertContains(logs.single(), "requestBytes=199229440")
    }

    @Test
    fun missingPlatformSizeFailsBeforeMultipartTransport() = runTest {
        val executor = RecordingExecutor { ok() }
        val resolver = RecordingPlatformFileResolver(sizeBytes = null)
        val client =
            SharedRaftNetworkClient(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                baseUrl = "https://api.slock.test/api",
                platformFileResolver = resolver,
                executor = executor
            )

        val response =
            client.uploadAttachments(
                DataRequestPriority.Foreground,
                ChannelId("channel-1"),
                listOf(
                    MultipartFile(
                        name = "files",
                        fileName = "unknown.bin",
                        mimeType = "application/octet-stream",
                        fileRef = "file:///cache/unknown.bin"
                    )
                )
            )

        assertEquals(400, response.code)
        assertContains(response.errorBodyText.orEmpty(), "attachment_size_unavailable")
        assertTrue(executor.multiparts.isEmpty(), "stat failure must terminate before transport")
        assertTrue(resolver.openCalls.isEmpty())
    }

    @Test
    fun multiFileMetadataAdmissionIsAllOrNoneBeforeAnyAttemptOpens() = runTest {
        val executor = RecordingExecutor { ok() }
        val resolver = PerFileRecordingResolver(mapOf("file:///a" to 3L, "file:///b" to null))
        val client =
            SharedRaftNetworkClient(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                baseUrl = "https://api.slock.test/api",
                platformFileResolver = resolver,
                executor = executor
            )

        val response =
            client.uploadAttachments(
                DataRequestPriority.Foreground,
                ChannelId("channel-1"),
                listOf(
                    MultipartFile("files", "a.bin", "application/octet-stream", fileRef = "file:///a"),
                    MultipartFile("files", "b.bin", "application/octet-stream", fileRef = "file:///b")
                )
            )

        assertEquals(400, response.code)
        assertEquals(listOf("file:///a", "file:///b"), resolver.statCalls)
        assertTrue(resolver.openCalls.isEmpty(), "metadata admission must not open A before B is admitted")
        assertTrue(executor.multiparts.isEmpty(), "no partial multipart may start")
    }

    @Test
    fun inMemorySmallAttachmentKeepsBufferedPathWithoutPlatformResolver() = runTest {
        val executor = RecordingExecutor { ok("""{"attachments":[]}""") }
        val resolver = RecordingPlatformFileResolver()
        val client =
            SharedRaftNetworkClient(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                baseUrl = "https://api.slock.test/api",
                platformFileResolver = resolver,
                executor = executor
            )

        val response =
            client.uploadAttachments(
                DataRequestPriority.Foreground,
                ChannelId("channel-1"),
                listOf(
                    MultipartFile(
                        name = "files",
                        fileName = "small.txt",
                        mimeType = "text/plain",
                        bytes = byteArrayOf(1, 2, 3),
                        sizeBytes = 3
                    )
                )
            )

        assertEquals(200, response.code)
        assertTrue(resolver.readCalls.isEmpty())
        assertTrue(resolver.statCalls.isEmpty())
        assertTrue(resolver.openCalls.isEmpty())
        val buffered = executor.multiparts.single().files.single()
        assertEquals(listOf<Byte>(1, 2, 3), buffered.bytes.toList())
        assertEquals(null, buffered.streamOpener)
    }

    @Test
    fun attachmentUploadLogsMultipartShapeAndTransportDiagnosticsForErrors() = runBlocking {
        val logs = mutableListOf<String>()
        val executor =
            RecordingExecutor {
                RaftHttpExecutorResponse(
                    code = 400,
                    bodyText = null,
                    errorBodyText = """{"error":"No files provided"}""",
                    transportDiagnostics =
                        "engine=networkkmm status=400 kind=http rawCode=400 detail=bad_request"
                )
            }
        val client =
            SharedRaftNetworkClient(
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                baseUrl = "https://api.slock.test/api",
                warningLogger = logs::add,
                executor = executor
            )

        val response =
            client.uploadAttachments(
                DataRequestPriority.Foreground,
                ChannelId("channel-1"),
                listOf(
                    MultipartFile(
                        name = "files",
                        fileName = "photo.png",
                        mimeType = "image/png",
                        bytes = byteArrayOf(1, 2, 3),
                        sizeBytes = 3
                    )
                )
            )

        assertEquals(400, response.code)
        assertContains(logs.single(), "category=attachments_upload")
        assertContains(logs.single(), "fieldKeys=channelId")
        assertContains(logs.single(), "fileFields=files")
        assertContains(logs.single(), "fileNameMeta=named=1;ext=png")
        assertContains(logs.single(), "result=http_error")
        assertContains(logs.single(), "errorBody=error:No_files_provided")
        assertContains(logs.single(), "engine=networkkmm")
        assertContains(logs.single(), "rawCode=400")
        assertContains(logs.single(), "detail=bad_request")
    }

    private class RecordingPlatformFileResolver(
        private val sizeBytes: Long? = 42L
    ) : RaftPlatformFileResolver {
        val readCalls = mutableListOf<String>()
        val statCalls = mutableListOf<String>()
        val openCalls = mutableListOf<Pair<String, Long>>()

        override suspend fun readFile(fileRef: String): RaftPlatformFileReadResult {
            readCalls += fileRef
            return RaftPlatformFileReadResult(code = 200, bytes = byteArrayOf(1, 2, 3), sizeBytes = 3)
        }

        override suspend fun statFile(fileRef: String): RaftPlatformFileStatResult {
            statCalls += fileRef
            return if (sizeBytes != null) {
                RaftPlatformFileStatResult(code = 200, sizeBytes = sizeBytes)
            } else {
                RaftPlatformFileStatResult(
                    code = 400,
                    errorCode = "attachment_size_unavailable",
                    message = "Attachment size unavailable"
                )
            }
        }

        override suspend fun openFileStream(
            fileRef: String,
            expectedSizeBytes: Long
        ): RaftPlatformFileChunkSource {
            openCalls += fileRef to expectedSizeBytes
            return virtualChunkSource(expectedSizeBytes)
        }
    }

    private class PerFileRecordingResolver(
        private val sizes: Map<String, Long?>
    ) : RaftPlatformFileResolver {
        val statCalls = mutableListOf<String>()
        val openCalls = mutableListOf<String>()

        override suspend fun readFile(fileRef: String): RaftPlatformFileReadResult =
            RaftPlatformFileReadResult(code = 200, bytes = byteArrayOf(1), sizeBytes = 1)

        override suspend fun statFile(fileRef: String): RaftPlatformFileStatResult {
            statCalls += fileRef
            val size = sizes[fileRef]
            return if (size != null) {
                RaftPlatformFileStatResult(code = 200, sizeBytes = size)
            } else {
                RaftPlatformFileStatResult(
                    code = 400,
                    errorCode = "attachment_size_unavailable",
                    message = "Attachment size unavailable"
                )
            }
        }

        override suspend fun openFileStream(
            fileRef: String,
            expectedSizeBytes: Long
        ): RaftPlatformFileChunkSource {
            openCalls += fileRef
            return virtualChunkSource(expectedSizeBytes)
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

private fun virtualChunkSource(sizeBytes: Long): RaftPlatformFileChunkSource {
    var remaining = sizeBytes
    return object : RaftPlatformFileChunkSource {
        override suspend fun read(maxBytes: Int): ByteArray {
            if (remaining <= 0L) return ByteArray(0)
            val count = minOf(remaining, maxBytes.toLong()).toInt()
            remaining -= count
            return ByteArray(count)
        }

        override fun close() = Unit
    }
}
