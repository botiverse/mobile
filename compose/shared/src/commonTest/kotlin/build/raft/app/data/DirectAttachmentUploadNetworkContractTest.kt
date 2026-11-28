package build.raft.app.data

import build.raft.app.isConversationAccessLost
import build.raft.app.model.AttachmentId
import build.raft.app.model.ChannelId
import build.raft.app.network.MultipartFile
import build.raft.app.network.RAFT_ATTACHMENT_UPLOAD_COMPLETE_TIMEOUT_MILLIS
import build.raft.app.network.RaftHttpExecutor
import build.raft.app.network.RaftHttpExecutorResponse
import build.raft.app.network.RaftHttpFileBody
import build.raft.app.network.RaftRequestPriority
import build.raft.app.network.RaftRequestScheduler
import build.raft.app.network.RaftByteStreamSink
import build.raft.app.network.RaftTraceIds
import build.raft.app.network.RaftUploadWireFacts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
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

    /**
     * `slock.attachment.upload_finished`: a composer upload's recorder travels in the coroutine
     * context, so the network client fills it in without any repository carrying telemetry. These
     * drive the composer's own path (`uploadOneThreadFile`, as ThreadPage calls it inside the
     * recorder's context) through the real repository and network client.
     */
    private suspend fun composerUpload(executor: RecordingExecutor): DirectAttachmentUploadOperation =
        requireNotNull(
            RealConversationRepository(
                sessionStore = authenticatedSessionStore(),
                networkClient = client(executor = executor),
                routeEnvironmentIdProvider = { ApiBaseUrl },
            ).directAttachmentUploadOperation(expectedBinding())
        )

    private fun recorder(elapsedMs: () -> Long = { 120L }) =
        RaftAttachmentUploadTimingRecorder(
            elapsedMs = elapsedMs,
            traceIds = RaftTraceIds(traceId = TraceId, spanId = "b7ad6b7169203331"),
        )

    private fun RecordingExecutor.sessionCalls(): List<String> =
        requests.filter { "/attachments/upload-sessions" in it.url }.map { "${it.method} ${it.url.removePrefix(ApiBaseUrl)}" }

    @Test
    fun composerDirectUploadFillsTheRecorderAndJoinsItsTraceOnTheSessionCalls() = runBlocking {
        val putFacts = RaftUploadWireFacts(requestBytes = 3L, connectMs = 25L, requestBodyMs = 40L, responseWaitMs = 15L, edgePop = "NRT57-P2")
        val executor =
            RecordingExecutor(
                respond = { method, url ->
                    when {
                        method == "POST" && url.endsWith("/attachments/upload-sessions") -> response(body = CreatedSessionBody)
                        method == "POST" && url.endsWith("/complete") -> response(body = CompletedSessionBody)
                        else -> error("unexpected request: $method $url")
                    }
                },
                bytesResponse = response().copy(uploadFacts = putFacts),
            )
        val recorder = recorder()

        val result =
            withContext(recorder) {
                composerUpload(executor).uploadOneThreadFile(
                    channelId = ChannelId("channel-1"),
                    file = file(bytes = byteArrayOf(1, 2, 3)),
                    clientRequestId = "11111111-1111-4111-8111-111111111111",
                    onUploadProgress = {},
                )
            }

        assertIs<DataResult.Content<*>>(result)
        val timing = recorder.snapshot()
        assertEquals(RaftAttachmentUploadRouteDirect, timing.route)
        assertEquals(120L, timing.prepareMs, "preparation ends at the session create call")
        assertEquals(putFacts, timing.facts)
        assertEquals(1, timing.bodySends)
        assertNull(timing.controlTimeoutPhase)
        assertEquals(listOf("POST /attachments/upload-sessions", "POST /attachments/upload-sessions/$UploadId/complete"), executor.sessionCalls())
        executor.requests.forEach { call -> assertEquals(Traceparent, call.headers["traceparent"], call.url) }
        assertTrue(executor.byteBodies.single().headers.keys.none { it.equals("traceparent", ignoreCase = true) })
    }

    /**
     * The PUT stalls: the app checks the session, then tries to complete it (three times while the
     * store says the object is not there). All of it is one attempt, on one trace, reported as a
     * timeout of the transfer, whatever code the recovery ends with.
     */
    @Test
    fun composerUploadRecoveringFromAStalledPutStaysOneAttemptAndReportsATransferTimeout() = runBlocking {
        val stalled =
            RaftUploadWireFacts(
                requestBytes = 3L,
                requestBodyPartialMs = 60_000L,
                failureKind = build.raft.app.network.RaftTransportFailureKind.TIMEOUT,
                transportError = "okhttp:SocketTimeoutException",
            )
        val executor =
            RecordingExecutor(
                respond = { method, url ->
                    when {
                        method == "POST" && url.endsWith("/attachments/upload-sessions") -> response(body = CreatedSessionBody)
                        method == "GET" && url.endsWith("/attachments/upload-sessions/$UploadId") ->
                            response(body = """{"uploadId":"$UploadId","state":"pending","expiresAt":"2026-09-02T00:00:00Z"}""")
                        method == "POST" && url.endsWith("/complete") ->
                            response(code = 409, body = """{"error":"object not found","code":"UPLOAD_OBJECT_NOT_FOUND"}""")
                        else -> error("unexpected request: $method $url")
                    }
                },
                // The redacted text of a presigned PUT failure says nothing of a timeout; the facts do.
                bytesResponse =
                    RaftHttpExecutorResponse(
                        code = 0,
                        bodyText = null,
                        errorBodyText = "attachment_presigned_upload_transport_failed",
                        uploadFacts = stalled,
                    ),
            )
        val recorder = recorder()

        val result =
            withContext(recorder) {
                composerUpload(executor).uploadOneThreadFile(
                    channelId = ChannelId("channel-1"),
                    file = file(bytes = byteArrayOf(1, 2, 3)),
                    clientRequestId = "11111111-1111-4111-8111-111111111111",
                )
            }

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("UPLOAD_OBJECT_PUT_UNCERTAIN", error.code)
        assertEquals(
            listOf("POST /attachments/upload-sessions", "GET /attachments/upload-sessions/$UploadId") +
                List(3) { "POST /attachments/upload-sessions/$UploadId/complete" },
            executor.sessionCalls(),
        )
        // Status and every complete retry join the attempt's trace.
        executor.requests.forEach { call -> assertEquals(Traceparent, call.headers["traceparent"], "${call.method} ${call.url}") }
        val timing = recorder.snapshot()
        assertEquals(stalled, timing.facts)
        assertEquals(1, timing.bodySends, "the PUT is never sent again inside an attempt")
        val attrs =
            raftAttachmentUploadFinishedAttrs(
                RaftAttachmentUploadFinished(
                    route = timing.route,
                    errorCode = error.code,
                    serverCode = error.serverCode,
                    durationMs = 70_000L,
                    prepareMs = timing.prepareMs,
                    facts = timing.facts,
                    bodySends = timing.bodySends,
                    controlTimeoutPhase = timing.controlTimeoutPhase,
                )
            )
        assertEquals("direct", attrs.getValue("route").jsonPrimitive.content)
        assertEquals("timeout", attrs.getValue("outcome").jsonPrimitive.content)
        assertEquals("transfer", attrs.getValue("timeout_phase").jsonPrimitive.content)
        assertEquals("UPLOAD_OBJECT_PUT_UNCERTAIN", attrs.getValue("error_code").jsonPrimitive.content)
        assertEquals("60000", attrs.getValue("request_body_partial_ms").jsonPrimitive.content)
    }

    @Test
    fun composerUploadRefusedByTheStoreCancelsItsSessionOnTheSameTrace() = runBlocking {
        val executor =
            RecordingExecutor(
                respond = { method, url ->
                    when {
                        method == "POST" && url.endsWith("/attachments/upload-sessions") -> response(body = CreatedSessionBody)
                        method == "DELETE" && url.endsWith("/attachments/upload-sessions/$UploadId") ->
                            response(body = """{"uploadId":"$UploadId","state":"canceled","expiresAt":"2026-09-02T00:00:00Z"}""")
                        else -> error("unexpected request: $method $url")
                    }
                },
                bytesResponse =
                    RaftHttpExecutorResponse(
                        code = 404,
                        bodyText = null,
                        errorBodyText = "attachment_presigned_upload_http_404",
                        uploadFacts = RaftUploadWireFacts(requestBytes = 3L, requestBodyMs = 5L, responseWaitMs = 9L),
                    ),
            )
        val recorder = recorder()

        val result =
            withContext(recorder) {
                composerUpload(executor).uploadOneThreadFile(
                    channelId = ChannelId("channel-1"),
                    file = file(bytes = byteArrayOf(1, 2, 3)),
                    clientRequestId = "11111111-1111-4111-8111-111111111111",
                )
            }

        assertIs<DataResult.Error>(result)
        // An HTTP refusal of the PUT is not a timeout; the cleanup call joins the trace and is no phase.
        assertTrue("DELETE /attachments/upload-sessions/$UploadId" in executor.sessionCalls(), executor.sessionCalls().toString())
        executor.requests.forEach { call -> assertEquals(Traceparent, call.headers["traceparent"], "${call.method} ${call.url}") }
        val timing = recorder.snapshot()
        assertNull(timing.facts?.failureKind)
        assertNull(timing.controlTimeoutPhase)
        assertNull(raftAttachmentUploadTimeoutPhase(timing.facts, timing.controlTimeoutPhase))
    }

    /**
     * web slock #15743: a control call of an upload that never answers ends in a retryable timeout at
     * its own budget, never a spinner. The capability probe and the session create get the 30 s of
     * every API call (a probe that runs out of time sends the upload to multipart); the complete call
     * gets 45 s, past the server's own 30 s storage check, so its answer is heard. A timed-out call is
     * never sent again inside the attempt. Virtual time; only the hung call never answers.
     */
    @Test
    fun aControlCallThatNeverAnswersEndsInARetryableTimeoutAtItsOwnBudget() = runTest {
        class Case(
            val name: String,
            val budgetMillis: Long,
            /** The attempt's `timeout_phase` for it; the probe has none (it falls back instead). */
            val phase: String?,
            val hangs: (method: String, url: String) -> Boolean,
            val call: suspend (DirectAttachmentUploadOperation) -> DataResult<*>,
        )
        val upload: suspend (DirectAttachmentUploadOperation) -> DataResult<*> = { operation ->
            operation.uploadOneThreadFile(
                channelId = ChannelId("channel-1"),
                file = file(bytes = byteArrayOf(1, 2, 3)),
                clientRequestId = "11111111-1111-4111-8111-111111111111",
            )
        }
        val cases =
            listOf(
                Case(
                    name = "capability",
                    budgetMillis = 30_000L,
                    phase = null,
                    hangs = { _, url -> url.endsWith("/attachments/upload-capabilities") },
                    call = { operation -> operation.capabilities() },
                ),
                Case(
                    name = RaftAttachmentUploadPhaseCreate,
                    budgetMillis = 30_000L,
                    phase = RaftAttachmentUploadPhaseCreate,
                    hangs = { method, url -> method == "POST" && url.endsWith("/attachments/upload-sessions") },
                    call = upload,
                ),
                Case(
                    name = RaftAttachmentUploadPhaseComplete,
                    budgetMillis = RAFT_ATTACHMENT_UPLOAD_COMPLETE_TIMEOUT_MILLIS,
                    phase = RaftAttachmentUploadPhaseComplete,
                    hangs = { method, url -> method == "POST" && url.endsWith("/complete") },
                    call = upload,
                ),
            )
        for (case in cases) {
            // The real repository parses a successful answer (the session create) on its own parse
            // thread: the budget is measured from the moment the hung call is sent, not from the start.
            val hungCallSent = CompletableDeferred<Unit>()
            var calledOff = 0
            val executor =
                RecordingExecutor(
                    respond = { method, url ->
                        when {
                            case.hangs(method, url) -> {
                                hungCallSent.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    calledOff += 1
                                }
                            }
                            url.endsWith("/attachments/upload-capabilities") ->
                                response(body = """{"directUploadEnabled":true,"maxBytes":1048576,"sessionExpiresInSeconds":600}""")
                            method == "POST" && url.endsWith("/attachments/upload-sessions") -> response(body = CreatedSessionBody)
                            method == "POST" && url.endsWith("/complete") -> response(body = CompletedSessionBody)
                            else -> error("unexpected request: $method $url")
                        }
                    },
                )
            val recorder = recorder()
            val result = async { withContext(recorder) { case.call(composerUpload(executor)) } }
            hungCallSent.await()
            val startedAt = currentTime

            advanceTimeBy(case.budgetMillis - 1)
            runCurrent()
            assertFalse(result.isCompleted, "${case.name}: still waiting 1 ms before its budget")
            advanceTimeBy(1)
            runCurrent()
            assertTrue(result.isCompleted, "${case.name}: ended at its budget")

            val error = assertIs<DataResult.Error>(result.await(), case.name).error
            assertEquals(startedAt + case.budgetMillis, currentTime, case.name)
            assertEquals("network_timeout", error.code, case.name)
            assertTrue(error.retryable, case.name)
            assertEquals(1, executor.requests.count { case.hangs(it.method, it.url) }, "${case.name}: sent once")
            assertEquals(1, calledOff, "${case.name}: the hung request is called off, so a late answer cannot land")
            assertEquals(case.phase, recorder.snapshot().controlTimeoutPhase, case.name)
        }
    }

    /** web slock #15743: the upload's own cancel during a hung control call stays a cancel, never a timeout. */
    @Test
    fun aCancelDuringAHungControlCallStaysACancelAndLeavesNoDeadlineBehind() = runTest {
        var calledOff = 0
        val executor =
            RecordingExecutor(
                respond = { method, url ->
                    if (method == "POST" && url.endsWith("/attachments/upload-sessions")) {
                        try {
                            awaitCancellation()
                        } finally {
                            calledOff += 1
                        }
                    } else {
                        error("unexpected request: $method $url")
                    }
                },
            )
        val recorder = recorder()
        val upload =
            async {
                withContext(recorder) {
                    composerUpload(executor).uploadOneThreadFile(
                        channelId = ChannelId("channel-1"),
                        file = file(bytes = byteArrayOf(1, 2, 3)),
                        clientRequestId = "11111111-1111-4111-8111-111111111111",
                    )
                }
            }
        advanceTimeBy(1_000L)
        runCurrent()
        assertFalse(upload.isCompleted)

        upload.cancel()
        runCurrent()

        assertTrue(upload.isCancelled)
        assertEquals(1, calledOff, "the hung create is called off")
        assertNull(recorder.snapshot().controlTimeoutPhase, "a cancel is no timeout")
        // Nothing is left to fire later: the create's 30 s deadline went with it.
        advanceUntilIdle()
        assertEquals(1_000L, currentTime)
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
    fun legacyMultipart403MonthlyQuotaExceedReturnsTypedErrorInsteadOfPermissionDenied() = runBlocking {
        val executor =
            RecordingExecutor(
                respond = { method, url ->
                    assertEquals("POST", method)
                    assertTrue(url.endsWith("/attachments/upload"), url)
                    RaftHttpExecutorResponse(
                        code = 403,
                        bodyText = null,
                        errorBodyText =
                            """{"error":"Monthly file upload quota exceeded. Free includes 100 MB of file uploads per month; Pro has no monthly upload limit.","errorCode":"FILE_UPLOAD_QUOTA_EXCEEDED","requestedBytes":524288}""",
                    )
                },
            )
        val repository =
            RealConversationRepository(
                sessionStore = authenticatedSessionStore(),
                networkClient = client(executor = executor),
                routeEnvironmentIdProvider = { ApiBaseUrl },
            )

        val result =
            repository.uploadAttachments(
                AttachmentUploadRequest(
                    channelId = ChannelId("channel-1"),
                    files = listOf(file(sizeBytes = 3L, bytes = byteArrayOf(1, 2, 3))),
                )
            )

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("FILE_UPLOAD_QUOTA_EXCEEDED", error.code)
        assertEquals("FILE_UPLOAD_QUOTA_EXCEEDED", error.serverCode)
        assertFalse(error.retryable)
        assertContains(error.serverMessage.orEmpty(), "Monthly file upload quota exceeded")
        assertFalse(result.isConversationAccessLost())
    }

    @Test
    fun directSessionCreate403MonthlyQuotaExceedParsesCodeAndMessageAsTypedError() = runBlocking {
        val executor =
            RecordingExecutor(
                respond = { method, url ->
                    assertEquals("POST", method)
                    assertTrue(url.endsWith("/attachments/upload-sessions"), url)
                    RaftHttpExecutorResponse(
                        code = 403,
                        bodyText = null,
                        errorBodyText =
                            """{"code":"FILE_UPLOAD_QUOTA_EXCEEDED","message":"Monthly file upload quota exceeded for this server.","retryable":false}""",
                    )
                },
            )

        val result =
            composerUpload(executor).uploadOneThreadFile(
                channelId = ChannelId("channel-1"),
                file = file(bytes = byteArrayOf(1, 2, 3)),
                clientRequestId = "11111111-1111-4111-8111-111111111111",
            )

        val error = assertIs<DataResult.Error>(result).error
        assertEquals("FILE_UPLOAD_QUOTA_EXCEEDED", error.code)
        assertEquals("FILE_UPLOAD_QUOTA_EXCEEDED", error.serverCode)
        assertEquals("Monthly file upload quota exceeded for this server.", error.serverMessage)
        assertFalse(error.retryable)
        assertFalse(result.isConversationAccessLost())
    }

    @Test
    fun monthlyQuota403ExtractsMonthlyUploadAndLegacyQuotaSnapshots() = runBlocking {
        val directExecutor =
            RecordingExecutor(
                respond = { _, _ ->
                    RaftHttpExecutorResponse(
                        code = 403,
                        bodyText = null,
                        errorBodyText =
                            """{"code":"FILE_UPLOAD_QUOTA_EXCEEDED","message":"Monthly quota exceeded.","monthlyUpload":{"month":"2026-11","limitBytes":104857600,"remainingBytes":2097152}}""",
                    )
                },
            )
        val directResult =
            composerUpload(directExecutor).uploadOneThreadFile(
                channelId = ChannelId("channel-1"),
                file = file(bytes = byteArrayOf(1, 2, 3)),
                clientRequestId = "11111111-1111-4111-8111-111111111112",
            )
        val directQuota = requireNotNull(assertIs<DataResult.Error>(directResult).error.monthlyUploadQuota)
        assertEquals("2026-11", directQuota.month)
        assertTrue(directQuota.limited)
        assertEquals(104857600L, directQuota.limitBytes)
        assertEquals(2097152L, directQuota.remainingBytes)

        val legacyExecutor =
            RecordingExecutor(
                respond = { _, _ ->
                    RaftHttpExecutorResponse(
                        code = 403,
                        bodyText = null,
                        errorBodyText =
                            """{"errorCode":"FILE_UPLOAD_QUOTA_EXCEEDED","error":"Quota exceeded.","quota":{"month":"2026-12","limited":true,"limitBytes":104857600,"remainingBytes":0}}""",
                    )
                },
            )
        val legacyRepository =
            RealConversationRepository(
                sessionStore = authenticatedSessionStore(),
                networkClient = client(executor = legacyExecutor),
                routeEnvironmentIdProvider = { ApiBaseUrl },
            )
        val legacyResult =
            legacyRepository.uploadAttachments(
                AttachmentUploadRequest(
                    channelId = ChannelId("channel-1"),
                    files = listOf(file(sizeBytes = 3L, bytes = byteArrayOf(1, 2, 3))),
                )
            )
        val legacyQuota = requireNotNull(assertIs<DataResult.Error>(legacyResult).error.monthlyUploadQuota)
        assertEquals("2026-12", legacyQuota.month)
        assertTrue(legacyQuota.limited)
        assertEquals(104857600L, legacyQuota.limitBytes)
        assertEquals(0L, legacyQuota.remainingBytes)
    }

    @Test
    fun appSumoQuota403PreservesSourceAndRetainedUsageAcrossDirectAndLegacyFormats() = runBlocking {
        val directExecutor =
            RecordingExecutor(
                respond = { _, _ ->
                    RaftHttpExecutorResponse(
                        code = 403,
                        bodyText = null,
                        errorBodyText =
                            """{"code":"FILE_UPLOAD_QUOTA_EXCEEDED","message":"The AppSumo storage limit has been reached. Delete unused files to free space.","retryable":false,"monthlyUpload":{"month":"2026-11","limitBytes":1073741824,"remainingBytes":0,"source":"appsumo"}}""",
                    )
                },
            )
        val directResult =
            composerUpload(directExecutor).uploadOneThreadFile(
                channelId = ChannelId("channel-1"),
                file = file(bytes = byteArrayOf(1, 2, 3)),
                clientRequestId = "11111111-1111-4111-8111-111111111113",
            )
        val directQuota = requireNotNull(assertIs<DataResult.Error>(directResult).error.monthlyUploadQuota)
        assertEquals("2026-11", directQuota.month)
        assertTrue(directQuota.limited)
        assertEquals(1_073_741_824L, directQuota.limitBytes)
        assertEquals(0L, directQuota.remainingBytes)
        assertNull(directQuota.usedBytes)
        assertNull(directQuota.reservedBytes)
        assertEquals("appsumo", directQuota.source)

        val directNoMonthExecutor =
            RecordingExecutor(
                respond = { _, _ ->
                    RaftHttpExecutorResponse(
                        code = 403,
                        bodyText = null,
                        errorBodyText =
                            """{"code":"FILE_UPLOAD_QUOTA_EXCEEDED","message":"The AppSumo storage limit has been reached.","monthlyUpload":{"limitBytes":1073741824,"remainingBytes":0,"source":"appsumo"}}""",
                    )
                },
            )
        val directNoMonthResult =
            composerUpload(directNoMonthExecutor).uploadOneThreadFile(
                channelId = ChannelId("channel-1"),
                file = file(bytes = byteArrayOf(1, 2, 3)),
                clientRequestId = "11111111-1111-4111-8111-111111111114",
            )
        val directNoMonthQuota = requireNotNull(assertIs<DataResult.Error>(directNoMonthResult).error.monthlyUploadQuota)
        assertNull(directNoMonthQuota.month)
        assertEquals("appsumo", directNoMonthQuota.source)

        val legacyExecutor =
            RecordingExecutor(
                respond = { _, _ ->
                    RaftHttpExecutorResponse(
                        code = 403,
                        bodyText = null,
                        errorBodyText =
                            """{"errorCode":"FILE_UPLOAD_QUOTA_EXCEEDED","error":"The AppSumo storage limit has been reached. Delete unused files to free space.","quota":{"month":"2026-11","plan":"pro","appsumo":{"tier":1,"lifetime":true},"limited":true,"enforced":true,"limitBytes":1073741824,"usedBytes":4294967296,"reservedBytes":1048576,"remainingBytes":0}}""",
                    )
                },
            )
        val legacyRepository =
            RealConversationRepository(
                sessionStore = authenticatedSessionStore(),
                networkClient = client(executor = legacyExecutor),
                routeEnvironmentIdProvider = { ApiBaseUrl },
            )
        val legacyResult =
            legacyRepository.uploadAttachments(
                AttachmentUploadRequest(
                    channelId = ChannelId("channel-1"),
                    files = listOf(file(sizeBytes = 3L, bytes = byteArrayOf(1, 2, 3))),
                )
            )
        val legacyQuota = requireNotNull(assertIs<DataResult.Error>(legacyResult).error.monthlyUploadQuota)
        assertEquals("2026-11", legacyQuota.month)
        assertEquals(1_073_741_824L, legacyQuota.limitBytes)
        assertEquals(0L, legacyQuota.remainingBytes)
        assertEquals(4_294_967_296L, legacyQuota.usedBytes)
        assertEquals(1_048_576L, legacyQuota.reservedBytes)
        assertEquals("appsumo", legacyQuota.source)

        val invalidTierExecutor =
            RecordingExecutor(
                respond = { _, _ ->
                    RaftHttpExecutorResponse(
                        code = 403,
                        bodyText = null,
                        errorBodyText =
                            """{"errorCode":"FILE_UPLOAD_QUOTA_EXCEEDED","error":"Quota exceeded.","quota":{"month":"2026-11","appsumo":{"tier":99},"limited":true,"limitBytes":104857600,"remainingBytes":0}}""",
                    )
                },
            )
        val invalidTierRepository =
            RealConversationRepository(
                sessionStore = authenticatedSessionStore(),
                networkClient = client(executor = invalidTierExecutor),
                routeEnvironmentIdProvider = { ApiBaseUrl },
            )
        val invalidTierResult =
            invalidTierRepository.uploadAttachments(
                AttachmentUploadRequest(
                    channelId = ChannelId("channel-1"),
                    files = listOf(file(sizeBytes = 3L, bytes = byteArrayOf(1, 2, 3))),
                )
            )
        val invalidTierQuota = requireNotNull(assertIs<DataResult.Error>(invalidTierResult).error.monthlyUploadQuota)
        assertNull(invalidTierQuota.source)
    }

    @Test
    fun legacyMultipart403BillingFrozenAndReadOnlyReturnTypedErrorsWhileTrueForbiddenStaysNoPermission() = runBlocking {
        suspend fun uploadWith403(body: String): DataResult<AttachmentUploadResult> {
            val executor =
                RecordingExecutor(
                    respond = { _, _ ->
                        RaftHttpExecutorResponse(code = 403, bodyText = null, errorBodyText = body)
                    },
                )
            val repository =
                RealConversationRepository(
                    sessionStore = authenticatedSessionStore(),
                    networkClient = client(executor = executor),
                    routeEnvironmentIdProvider = { ApiBaseUrl },
                )
            return repository.uploadAttachments(
                AttachmentUploadRequest(
                    channelId = ChannelId("channel-1"),
                    files = listOf(file(sizeBytes = 3L, bytes = byteArrayOf(1, 2, 3))),
                )
            )
        }

        val frozen =
            uploadWith403(
                """{"error":"This external channel is read-only on the Free plan.","code":"external_limit_frozen","externalFreezeAt":"2026-11-20T00:00:00.000Z"}"""
            )
        val frozenError = assertIs<DataResult.Error>(frozen).error
        assertEquals("external_limit_frozen", frozenError.code)
        assertEquals("external_limit_frozen", frozenError.serverCode)
        assertFalse(frozenError.retryable)

        val readOnly =
            uploadWith403(
                """{"error":"This channel is read-only because its quota was exceeded","code":"channel_read_only"}"""
            )
        val readOnlyError = assertIs<DataResult.Error>(readOnly).error
        assertEquals("channel_read_only", readOnlyError.code)
        assertEquals("channel_read_only", readOnlyError.serverCode)
        assertFalse(readOnlyError.retryable)

        val uploadForbidden =
            uploadWith403(
                """{"code":"UPLOAD_FORBIDDEN","message":"You do not have permission to upload to this channel"}"""
            )
        val uploadForbiddenBlocker = assertIs<DataResult.NoPermission>(uploadForbidden).blocker
        assertEquals("server_forbidden", uploadForbiddenBlocker.code)
        assertEquals("UPLOAD_FORBIDDEN", uploadForbiddenBlocker.serverCode)
        assertEquals("You do not have permission to upload to this channel", uploadForbiddenBlocker.serverMessage)

        val uncodedForbidden = uploadWith403("""{"error":"You do not have access to this channel"}""")
        val uncodedBlocker = assertIs<DataResult.NoPermission>(uncodedForbidden).blocker
        assertEquals("server_forbidden", uncodedBlocker.code)
        assertNull(uncodedBlocker.serverCode)
        assertEquals("You do not have access to this channel", uncodedBlocker.serverMessage)

        val blankPrimaryFields =
            uploadWith403(
                """{"code":" ","errorCode":"FILE_UPLOAD_QUOTA_EXCEEDED","error":" ","message":"Monthly file upload quota exceeded."}"""
            )
        val blankPrimaryError = assertIs<DataResult.Error>(blankPrimaryFields).error
        assertEquals("FILE_UPLOAD_QUOTA_EXCEEDED", blankPrimaryError.code)
        assertEquals("FILE_UPLOAD_QUOTA_EXCEEDED", blankPrimaryError.serverCode)
        assertEquals("Monthly file upload quota exceeded.", blankPrimaryError.serverMessage)
        assertFalse(blankPrimaryError.retryable)
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
        data class Request(val method: String, val url: String, val headers: Map<String, String> = emptyMap())
        data class ByteBody(val url: String, val headers: Map<String, String>, val bytes: ByteArray)

        val requests = mutableListOf<Request>()
        val byteBodies = mutableListOf<ByteBody>()
        val fileBodies = mutableListOf<RaftHttpFileBody>()
        val multipartHeaders = mutableListOf<Map<String, String>>()
        val multipartProgress = mutableListOf<((build.raft.app.network.RaftWireTransferProgress) -> Unit)?>()
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
            requests += Request(method = method, url = url, headers = headers)
            return respond(method, url)
        }

        override suspend fun executeMultipart(
            priority: RaftRequestPriority,
            url: String,
            fields: Map<String, String>,
            files: List<MultipartFile>,
            headers: Map<String, String>,
            onUploadProgress: ((build.raft.app.network.RaftWireTransferProgress) -> Unit)?,
        ): RaftHttpExecutorResponse {
            multipartCalls += 1
            multipartHeaders += headers
            multipartProgress += onUploadProgress
            return respond("POST", url)
        }

        override suspend fun executeBytes(
            priority: RaftRequestPriority,
            url: String,
            method: String,
            headers: Map<String, String>,
            bodyBytes: ByteArray,
            onUploadProgress: ((build.raft.app.network.RaftWireTransferProgress) -> Unit)?,
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
            onUploadProgress: ((build.raft.app.network.RaftWireTransferProgress) -> Unit)?,
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
        const val TraceId = "0af7651916cd43dd8448eb211c80319c"
        const val Traceparent = "00-$TraceId-b7ad6b7169203331-01"
        const val CreatedSessionBody =
            """{"uploadId":"$UploadId","attachmentId":"$AttachmentIdValue","state":"pending","expiresAt":"2026-09-02T00:00:00Z","upload":{"method":"PUT","url":"$PresignedUrl","headers":{"Content-Type":"image/jpeg","If-None-Match":"*"}}}"""
        const val CompletedSessionBody =
            """{"uploadId":"$UploadId","state":"completed","attachment":{"id":"$AttachmentIdValue","filename":"photo.jpg","mimeType":"image/jpeg","sizeBytes":3}}"""

        fun response(
            code: Int = 200,
            body: String? = "{}",
        ): RaftHttpExecutorResponse =
            RaftHttpExecutorResponse(code = code, bodyText = body, errorBodyText = null)
    }
}
