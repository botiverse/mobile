package build.raft.app.data

import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.network.MultipartFile
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftHttpFileBody
import build.raft.app.network.RaftRequestPriority
import build.raft.app.network.RaftRequestScheduler
import build.raft.app.network.RaftRequestTimeoutException
import build.raft.app.network.RaftByteStreamSink
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DirectAttachmentUploadNetworkContractTest {
    @Test
    fun unboundRepositoryDirectSessionOperationsNeverReachExecutorOrFileResolver() = runBlocking {
        val executor = RecordingExecutor()
        val resolver = RecordingResolver(RaftPlatformFileStatResult(code = 200, sizeBytes = 3L))
        val repository =
            RealConversationRepository(
                sessionStore = authenticatedSessionStore(),
                networkClient = client(executor = executor, resolver = resolver),
                routeEnvironmentIdProvider = { ApiBaseUrl },
            )

        val results =
            listOf(
                repository.createAttachmentUploadSession(createRequest()),
                repository.putAttachmentUploadBytes(putContract(), byteArrayOf(1, 2, 3)),
                repository.putAttachmentUploadFile(putContract(), file(sizeBytes = 99L)),
                repository.completeAttachmentUploadSession(UploadId),
                repository.cancelAttachmentUploadSession(UploadId),
                repository.statusAttachmentUploadSession(UploadId),
            )

        results.forEach { result ->
            val error = assertIs<DataResult.Error>(result).error
            assertEquals("attachment_upload_authority_binding_required", error.code)
        }
        assertEquals(0, executor.totalCalls)
        assertTrue(resolver.statCalls.isEmpty())
        assertTrue(resolver.openCalls.isEmpty())
    }

    @Test
    fun capturedGalleryOperationDispatchesCapabilityCreatePutAndComplete() = runBlocking {
        val executor =
            RecordingExecutor(respond = { method, url ->
                when {
                    url.endsWith("/attachments/upload-capabilities") ->
                        response(
                            body =
                                """{"directUploadEnabled":true,"maxBytes":1048576,"sessionExpiresInSeconds":600}"""
                        )
                    method == "POST" && url.endsWith("/attachments/upload-sessions") ->
                        response(
                            body =
                                """{"uploadId":"$UploadId","attachmentId":"$AttachmentIdValue","state":"pending","expiresAt":"2026-09-02T00:00:00Z","upload":{"method":"PUT","url":"$PresignedUrl","headers":{"Content-Type":"image/jpeg","If-None-Match":"*"}}}"""
                        )
                    method == "POST" && url.endsWith("/attachments/upload-sessions/$UploadId/complete") ->
                        response(
                            body =
                                """{"uploadId":"$UploadId","state":"completed","attachment":{"id":"$AttachmentIdValue","filename":"photo.jpg","mimeType":"image/jpeg","sizeBytes":3}}"""
                        )
                    else -> error("unexpected request: $method $url")
                }
            })
        val sessionStore = authenticatedSessionStore()
        val repository =
            RealConversationRepository(
                sessionStore = sessionStore,
                networkClient = client(executor = executor),
                routeEnvironmentIdProvider = { ApiBaseUrl },
            )
        val operation = requireNotNull(repository.directAttachmentUploadOperation(expectedBinding()))

        val result = operation.upload(ChannelId("channel-1"), listOf(file(bytes = byteArrayOf(1, 2, 3))))

        val batch = assertIs<DataResult.Content<DirectAttachmentUploadBatch>>(result).data
        assertEquals(listOf(AttachmentId(AttachmentIdValue)), batch.uploads.map { it.attachment.id })
        assertEquals(
            listOf(
                "GET ${ApiBaseUrl.trimEnd('/')}/attachments/upload-capabilities",
                "POST ${ApiBaseUrl.trimEnd('/')}/attachments/upload-sessions",
                "POST ${ApiBaseUrl.trimEnd('/')}/attachments/upload-sessions/$UploadId/complete",
            ),
            executor.requests.map { "${it.method} ${it.url}" },
        )
        assertEquals(1, executor.byteBodies.size)
        assertEquals(PresignedUrl, executor.byteBodies.single().url)
        assertEquals(listOf<Byte>(1, 2, 3), executor.byteBodies.single().bytes.toList())
        assertTrue("Authorization" !in executor.byteBodies.single().headers)
    }

    @Test
    fun directFilePutRejectsMissingZeroAndNegativeStatLengthsBeforeTransport() = runBlocking {
        listOf<Long?>(null, 0L, -1L).forEach { statSize ->
            val executor = RecordingExecutor()
            val resolver = RecordingResolver(RaftPlatformFileStatResult(code = 200, sizeBytes = statSize))
            val bound = client(executor = executor, resolver = resolver).boundTo(expectedBinding())

            val response =
                bound.putAttachmentUploadFile(
                    priority = DataRequestPriority.Foreground,
                    upload = putContract(),
                    file = file(sizeBytes = 999L),
                )

            assertEquals(400, response.code, "statSize=$statSize")
            assertContains(response.errorBodyText.orEmpty(), "attachment_size_unavailable")
            assertEquals(listOf(FileRef), resolver.statCalls)
            assertTrue(resolver.openCalls.isEmpty())
            assertTrue(executor.fileBodies.isEmpty())
        }
    }

    @Test
    fun directFilePutRejectsFileRefAndBytesBeforeStatOrTransport() = runBlocking {
        val executor = RecordingExecutor()
        val resolver = RecordingResolver(RaftPlatformFileStatResult(code = 200, sizeBytes = 3L))
        val bound = client(executor = executor, resolver = resolver).boundTo(expectedBinding())

        val response =
            bound.putAttachmentUploadFile(
                priority = DataRequestPriority.Foreground,
                upload = putContract(),
                file =
                    file(
                        sizeBytes = 3L,
                        bytes = byteArrayOf(1, 2, 3),
                    ).copy(fileRef = FileRef),
            )

        assertEquals(400, response.code)
        assertContains(response.errorBodyText.orEmpty(), "attachment_file_input_conflict")
        assertTrue(resolver.statCalls.isEmpty())
        assertTrue(resolver.openCalls.isEmpty())
        assertEquals(0, executor.totalCalls)
    }

    @Test
    fun resolverStatExceptionFailsClosedBeforeStreamOpenOrTransport() = runBlocking {
        val executor = RecordingExecutor()
        val resolver =
            RecordingResolver(
                stat = RaftPlatformFileStatResult(code = 200, sizeBytes = 3L),
                statFailure = IllegalStateException("private resolver path"),
            )
        val bound = client(executor = executor, resolver = resolver).boundTo(expectedBinding())

        val response =
            bound.putAttachmentUploadFile(
                priority = DataRequestPriority.Foreground,
                upload = putContract(),
                file = file(sizeBytes = 999L),
            )

        assertEquals(400, response.code)
        assertContains(response.errorBodyText.orEmpty(), "attachment_size_unavailable")
        assertTrue(resolver.statCalls.isNotEmpty())
        assertTrue(resolver.openCalls.isEmpty())
        assertEquals(0, executor.totalCalls)
    }

    @Test
    fun resolverStatErrorsUseCanonicalPrivatePathSafeClassification() = runBlocking {
        listOf(404 to false, 503 to true).forEach { (status, expectedRetryable) ->
            val executor = RecordingExecutor()
            val resolver =
                RecordingResolver(
                    RaftPlatformFileStatResult(
                        code = status,
                        errorCode = "platform_error_$FileRef",
                        message = "could not stat $FileRef",
                    )
                )
            val bound = client(executor = executor, resolver = resolver).boundTo(expectedBinding())

            val preparation = assertIs<RaftPlatformFilePreparationResult.Rejected>(
                bound.prepareAttachmentUploadFile(file(sizeBytes = 999L))
            )
            val response =
                bound.putAttachmentUploadFile(
                    priority = DataRequestPriority.Foreground,
                    upload = putContract(),
                    file = file(sizeBytes = 999L),
                )

            assertEquals("attachment_size_unavailable", preparation.code, "status=$status")
            assertEquals(expectedRetryable, preparation.retryable, "status=$status")
            assertEquals(400, response.code, "status=$status")
            assertEquals(
                """{"error":"attachment_size_unavailable"}""",
                response.errorBodyText,
                "status=$status",
            )
            assertFalse(response.errorBodyText.orEmpty().contains(FileRef), "status=$status")
            assertTrue(resolver.openCalls.isEmpty(), "status=$status")
            assertEquals(0, executor.totalCalls, "status=$status")
        }
    }

    @Test
    fun resolverStatCancellationPropagatesBeforeStreamOpenOrTransport() = runBlocking {
        val executor = RecordingExecutor()
        val cancellation = CancellationException("caller canceled file admission")
        val resolver =
            RecordingResolver(
                stat = RaftPlatformFileStatResult(code = 200, sizeBytes = 3L),
                statFailure = cancellation,
            )
        val bound = client(executor = executor, resolver = resolver).boundTo(expectedBinding())

        val failure =
            failureOf {
                bound.prepareAttachmentUploadFile(file(sizeBytes = 999L))
            }

        assertIs<CancellationException>(failure)
        assertTrue(resolver.statCalls.isNotEmpty())
        assertTrue(resolver.openCalls.isEmpty())
        assertEquals(0, executor.totalCalls)
    }

    @Test
    fun invalidPathBearingSessionIdsFailBeforeExecutor() = runBlocking {
        val executor = RecordingExecutor()
        val bound = client(executor = executor).boundTo(expectedBinding())

        val responses =
            listOf(
                bound.completeAttachmentUploadSession(
                    priority = DataRequestPriority.Foreground,
                    uploadId = "../complete-other-session",
                ),
                bound.cancelAttachmentUploadSession(
                    priority = DataRequestPriority.Foreground,
                    uploadId = "not-a-session-id",
                ),
                bound.statusAttachmentUploadSession(
                    priority = DataRequestPriority.Foreground,
                    uploadId = " ",
                ),
            )

        responses.forEach { response ->
            assertEquals(400, response.code)
            assertContains(response.errorBodyText.orEmpty(), "attachment_upload_session_invalid_request")
        }
        assertEquals(0, executor.totalCalls)
    }

    @Test
    fun staleAuthorityRejectsDirectFileBeforeStatOrExecutor() = runBlocking {
        val executor = RecordingExecutor()
        val resolver = RecordingResolver(RaftPlatformFileStatResult(code = 200, sizeBytes = 3L))
        val staleBinding = expectedBinding().copy(accountId = "other-user")
        val bound = client(executor = executor, resolver = resolver).boundTo(staleBinding)

        val response =
            bound.putAttachmentUploadFile(
                priority = DataRequestPriority.Foreground,
                upload = putContract(),
                file = file(sizeBytes = 3L),
            )

        assertEquals(409, response.code)
        assertContains(response.errorBodyText.orEmpty(), "auth_authority_changed")
        assertTrue(resolver.statCalls.isEmpty())
        assertTrue(resolver.openCalls.isEmpty())
        assertEquals(0, executor.totalCalls)
    }

    @Test
    fun directFilePutUsesResolverStatInsteadOfCallerHintAndClosesAfterSuccess() = runBlocking {
        val source = CountingChunkSource(totalBytes = 3L)
        val resolver =
            RecordingResolver(
                stat = RaftPlatformFileStatResult(code = 200, sizeBytes = 3L),
                source = source,
            )
        val executor =
            RecordingExecutor(
                consumeFile = { body -> body.openStream().readChunks(DiscardingSink) },
            )
        val bound = client(executor = executor, resolver = resolver).boundTo(expectedBinding())

        val response =
            bound.putAttachmentUploadFile(
                priority = DataRequestPriority.Foreground,
                upload = putContract(),
                file = file(sizeBytes = 999L),
            )

        assertEquals(200, response.code)
        assertEquals(3L, executor.fileBodies.single().contentLength)
        assertEquals(listOf(FileRef to 3L), resolver.openCalls)
        assertEquals(1, source.closeCount)
    }

    @Test
    fun directFilePutClosesStreamWhenTransportConsumerThrows() = runBlocking {
        val source = CountingChunkSource(totalBytes = 3L)
        val resolver =
            RecordingResolver(
                stat = RaftPlatformFileStatResult(code = 200, sizeBytes = 3L),
                source = source,
            )
        val executor =
            RecordingExecutor(
                consumeFile = { body ->
                    body.openStream().readChunks(
                        object : RaftByteStreamSink {
                            override suspend fun write(bytes: ByteArray) {
                                error("transport sink failed")
                            }
                        }
                    )
                },
            )
        val bound = client(executor = executor, resolver = resolver).boundTo(expectedBinding())

        val failure =
            failureOf {
                bound.putAttachmentUploadFile(
                    priority = DataRequestPriority.Foreground,
                    upload = putContract(),
                    file = file(sizeBytes = 999L),
                )
            }

        assertIs<IllegalStateException>(failure)
        assertEquals(listOf(FileRef to 3L), resolver.openCalls)
        assertEquals(1, source.closeCount)
    }

    @Test
    fun directBytesPutRejectsEmptyBodyBeforeSchedulerOrExecutor() = runBlocking {
        val executor = RecordingExecutor()
        val schedulerEvents = mutableListOf<String>()
        val bound =
            client(
                executor = executor,
                warningLogger = { schedulerEvents += it },
                requestScheduler = RaftRequestScheduler(logger = { schedulerEvents += it }),
            ).boundTo(expectedBinding())

        val response =
            bound.putAttachmentUploadBytes(
                priority = DataRequestPriority.Foreground,
                upload = putContract(),
                bytes = ByteArray(0),
            )

        assertEquals(400, response.code)
        assertContains(response.errorBodyText.orEmpty(), "attachment_size_unavailable")
        assertEquals(0, executor.totalCalls)
        assertTrue(schedulerEvents.isEmpty(), "empty PUT must not acquire or run a scheduler slot")
    }

    @Test
    fun directBytesPutDiscardsCustomSuccessBodyAndSensitiveHeaders() = runBlocking {
        val leaked = "response=$PresignedUrl"
        val executor =
            RecordingExecutor(
                bytesResponse =
                    RaftHttpExecutorResponse(
                        code = 200,
                        bodyText = leaked,
                        errorBodyText = null,
                        transportDiagnostics = leaked,
                        responseHeaders = mapOf("x-leaked-url" to listOf(leaked)),
                    )
            )
        val bound = client(executor = executor).boundTo(expectedBinding())

        val response =
            bound.putAttachmentUploadBytes(
                priority = DataRequestPriority.Foreground,
                upload = putContract(),
                bytes = byteArrayOf(1, 2, 3),
            )

        assertEquals(200, response.code)
        assertNull(response.bodyText)
        assertNull(response.errorBodyText)
        assertFalse(response.transportDiagnostics.orEmpty().contains(PresignedUrl))
        assertTrue(response.responseHeaders.isEmpty())
    }

    @Test
    fun directBytesPutReplacesCustomErrorBodyWithStableClassification() = runBlocking {
        val leaked = "upstream rejected $PresignedUrl"
        val executor =
            RecordingExecutor(
                bytesResponse =
                    RaftHttpExecutorResponse(
                        code = 403,
                        bodyText = null,
                        errorBodyText = leaked,
                        transportDiagnostics = leaked,
                        responseHeaders = mapOf("x-leaked-url" to listOf(leaked)),
                    )
            )
        val bound = client(executor = executor).boundTo(expectedBinding())

        val response =
            bound.putAttachmentUploadBytes(
                priority = DataRequestPriority.Foreground,
                upload = putContract(),
                bytes = byteArrayOf(1, 2, 3),
            )

        assertEquals(403, response.code)
        assertEquals("attachment_presigned_upload_http_403", response.errorBodyText)
        assertNull(response.bodyText)
        assertFalse(response.errorBodyText.orEmpty().contains(PresignedUrl))
        assertFalse(response.transportDiagnostics.orEmpty().contains(PresignedUrl))
        assertTrue(response.responseHeaders.isEmpty())
    }

    @Test
    fun directBytesPutTimeoutUsesUrlSafeClassification() = runBlocking {
        val executor =
            RecordingExecutor(
                bytesFailure =
                    RaftRequestTimeoutException(
                        "Raft request timed out after 383000ms " +
                            "key=attachments/upload-sessions/presigned-put reason=PUT url=$PresignedUrl"
                    )
            )
        val bound = client(executor = executor).boundTo(expectedBinding())

        val response =
            bound.putAttachmentUploadBytes(
                priority = DataRequestPriority.Foreground,
                upload = putContract(),
                bytes = byteArrayOf(1, 2, 3),
            )

        assertEquals(0, response.code)
        assertEquals(
            """{"error":"network_timeout","message":"attachment_presigned_upload_timeout"}""",
            response.errorBodyText,
        )
        assertFalse(response.errorBodyText.orEmpty().contains(PresignedUrl))
        assertFalse(response.errorBodyText.orEmpty().contains("presigned-put"))
        assertFalse(response.errorBodyText.orEmpty().contains("reason=PUT"))
    }

    @Test
    fun directFilePutClosesStreamWhenTransportSetupFailsAfterOpeningStream() = runBlocking {
        val source = CountingChunkSource(totalBytes = 3L)
        val resolver =
            RecordingResolver(
                stat = RaftPlatformFileStatResult(code = 200, sizeBytes = 3L),
                source = source,
            )
        val executor =
            RecordingExecutor(
                consumeFile = { body ->
                    body.openStream()
                    error("transport setup failed")
                },
            )
        val bound = client(executor = executor, resolver = resolver).boundTo(expectedBinding())

        val failure =
            failureOf {
                bound.putAttachmentUploadFile(
                    priority = DataRequestPriority.Foreground,
                    upload = putContract(),
                    file = file(sizeBytes = 999L),
                )
            }

        assertIs<IllegalStateException>(failure)
        assertEquals(listOf(FileRef to 3L), resolver.openCalls)
        assertEquals(1, source.closeCount)
    }

    private fun client(
        executor: RecordingExecutor,
        resolver: RaftPlatformFileResolver? = null,
        warningLogger: (String) -> Unit = {},
        requestScheduler: RaftRequestScheduler = RaftRequestScheduler(logger = warningLogger),
    ): SharedRaftNetworkClient =
        SharedRaftNetworkClient(
            sessionStore = authenticatedSessionStore(),
            baseUrl = ApiBaseUrl,
            platformFileResolver = resolver,
            executor = executor,
            warningLogger = warningLogger,
            requestScheduler = requestScheduler,
        )

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

    private fun expectedBinding(): RaftExpectedAuthBinding =
        RaftExpectedAuthBinding(
            accountId = "user-1",
            accessToken = "access-token",
            refreshToken = "refresh-token",
            serverId = "server-1",
            environmentId = ApiBaseUrl,
        )

    private fun createRequest(): AttachmentUploadSessionCreateRequest =
        AttachmentUploadSessionCreateRequest(
            channelId = ChannelId("channel-1"),
            filename = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 3L,
            clientRequestId = "11111111-1111-4111-8111-111111111111",
        )

    private fun putContract(): AttachmentUploadSessionPut =
        AttachmentUploadSessionPut(
            method = "PUT",
            url = PresignedUrl,
            headers =
                mapOf(
                    "Content-Type" to "image/jpeg",
                    "If-None-Match" to "*",
                ),
        )

    private fun file(
        sizeBytes: Long? = null,
        bytes: ByteArray = ByteArray(0),
    ): PlatformFile =
        PlatformFile(
            id = "local-1",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = sizeBytes,
            bytes = bytes,
            fileRef = FileRef.takeIf { bytes.isEmpty() },
        )

    private suspend fun failureOf(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        error("Expected failure")
    }

    private class RecordingExecutor(
        private val respond: suspend (method: String, url: String) -> RaftHttpExecutorResponse = { _, _ ->
            response()
        },
        private val consumeFile: suspend (RaftHttpFileBody) -> Unit = {},
        private val bytesResponse: RaftHttpExecutorResponse = response(),
        private val bytesFailure: Throwable? = null,
    ) : RaftHttpExecutor {
        data class Request(val method: String, val url: String)
        data class ByteBody(val url: String, val headers: Map<String, String>, val bytes: ByteArray)

        val requests = mutableListOf<Request>()
        val byteBodies = mutableListOf<ByteBody>()
        val fileBodies = mutableListOf<RaftHttpFileBody>()
        var multipartCalls: Int = 0
            private set

        val totalCalls: Int
            get() = requests.size + byteBodies.size + fileBodies.size + multipartCalls

        override suspend fun execute(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyText: String?,
            jsonBody: Boolean,
        ): RaftHttpExecutorResponse {
            requests += Request(method = method, url = url)
            return respond(method, url)
        }

        override suspend fun executeMultipart(
            priority: RaftRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)?,
        ): RaftHttpExecutorResponse {
            multipartCalls += 1
            return respond("POST", url)
        }

        override suspend fun executeBytes(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyBytes: ByteArray,
            onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)?,
        ): RaftHttpExecutorResponse {
            byteBodies += ByteBody(url = url, headers = headers, bytes = bodyBytes)
            bytesFailure?.let { throw it }
            return bytesResponse
        }

        override suspend fun executeFile(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            body: RaftHttpFileBody,
            onUploadProgress: ((build.raft.app.network.RaftTransferProgress) -> Unit)?,
        ): RaftHttpExecutorResponse {
            fileBodies += body
            consumeFile(body)
            return response()
        }
    }

    private class RecordingResolver(
        private val stat: RaftPlatformFileStatResult,
        private val source: RaftPlatformFileChunkSource = CountingChunkSource(0L),
        private val statFailure: Throwable? = null,
    ) : RaftPlatformFileResolver {
        val statCalls = mutableListOf<String>()
        val openCalls = mutableListOf<Pair<String, Long>>()

        override suspend fun readFile(fileRef: String): RaftPlatformFileReadResult =
            error("direct file PUT must not materialize the whole file")

        override suspend fun statFile(fileRef: String): RaftPlatformFileStatResult {
            statCalls += fileRef
            statFailure?.let { throw it }
            return stat
        }

        override suspend fun openFileStream(
            fileRef: String,
            expectedSizeBytes: Long,
        ): RaftPlatformFileChunkSource {
            openCalls += fileRef to expectedSizeBytes
            return source
        }
    }

    private class CountingChunkSource(
        totalBytes: Long,
    ) : RaftPlatformFileChunkSource {
        private var remaining = totalBytes
        var closeCount: Int = 0
            private set

        override suspend fun read(maxBytes: Int): ByteArray {
            if (remaining <= 0L) return ByteArray(0)
            val count = minOf(remaining, maxBytes.toLong()).toInt()
            remaining -= count
            return ByteArray(count) { 1 }
        }

        override fun close() {
            closeCount += 1
        }
    }

    private object DiscardingSink : RaftByteStreamSink {
        override suspend fun write(bytes: ByteArray) = Unit
    }

    private companion object {
        const val ApiBaseUrl = "https://api.raft.test/api"
        const val PresignedUrl = "https://upload.raft.test/object/signed?token=secret"
        const val FileRef = "file:///private/cache/photo.jpg"
        const val UploadId = "00000000-0000-4000-8000-000000000001"
        const val AttachmentIdValue = "00000000-0000-4000-8000-000000000101"

        fun response(
            code: Int = 200,
            body: String? = "{}",
        ): RaftHttpExecutorResponse =
            RaftHttpExecutorResponse(code = code, bodyText = body, errorBodyText = null)
    }
}
