package build.raft.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Ktor transport keeps the [RaftHttpExecutor] contract the curl executor had (artin 2026-10-23). */
class KtorRaftHttpExecutorTest {
    @Test
    fun ordinaryRequestRunsOnItsLaneAndKeepsAuthTraceAndAppHeaders() = runBlocking {
        val harness = Harness { respond("""{"ok":true}""", HttpStatusCode.OK) }

        val response =
            harness.executor.execute(
                priority = RaftRequestPriority.Visible,
                url = "https://api.raft.test/api/channels?limit=50",
                method = "get",
                headers = mapOf("Authorization" to "Bearer access-1", HEADER_SERVER_ID to "server-1"),
                bodyText = null,
                jsonBody = false
            )

        assertEquals(200, response.code)
        assertEquals("""{"ok":true}""", response.bodyText)
        assertEquals(listOf(RaftRequestPriority.Visible to RaftKtorCallKind.Idempotent), harness.lanes)
        val sent = harness.requests.single()
        assertEquals("GET", sent.method.value)
        // The auth middleware's header reaches the wire untouched; refresh/replay stays above the executor.
        assertEquals("Bearer access-1", sent.headers["Authorization"])
        assertEquals("server-1", sent.headers[HEADER_SERVER_ID])
        assertTrue(sent.headers["x-request-id"].orEmpty().startsWith("mobile-"))
        assertTrue(sent.headers["traceparent"].orEmpty().startsWith("00-"))
        assertTrue(sent.headers[RAFT_CLIENT_PLATFORM_HEADER].orEmpty().isNotBlank())
        val diagnostics = response.transportDiagnostics.orEmpty()
        assertTrue("engine=ktor_test status=200 kind=none" in diagnostics, diagnostics)
        val requestId = sent.headers["x-request-id"]
        assertTrue(harness.logs.any { it.startsWith("slock_http_execute_finish") && "clientRequestId=$requestId" in it && "code=200" in it })
    }

    @Test
    fun onlyGetHeadAndOptionsMayBeRetriedByTheEngine() = runBlocking {
        val harness = Harness { respond("{}", HttpStatusCode.OK) }
        val methods = listOf("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE")

        RaftRequestPriority.entries.forEach { priority ->
            methods.forEach { method ->
                harness.executor.execute(priority, "https://api.raft.test/api/x", method, emptyMap(), null, false)
            }
            harness.executor.executeMultipart(priority, "https://api.raft.test/api/upload", mapOf("a" to "b"), emptyList(), emptyMap())
            harness.executor.executeBytes(priority, "https://bucket.test/k?sig=1", "PUT", emptyMap(), ByteArray(3))
        }

        val expectedPerLane =
            listOf(
                RaftKtorCallKind.Idempotent,
                RaftKtorCallKind.Idempotent,
                RaftKtorCallKind.Idempotent,
                RaftKtorCallKind.NonIdempotent,
                RaftKtorCallKind.NonIdempotent,
                RaftKtorCallKind.NonIdempotent,
                RaftKtorCallKind.NonIdempotent,
                RaftKtorCallKind.MultipartUpload,
                RaftKtorCallKind.PresignedUpload,
            )
        assertEquals(
            RaftRequestPriority.entries.flatMap { priority -> expectedPerLane.map { priority to it } },
            harness.lanes
        )
    }

    @Test
    fun presignedUrlIsSentVerbatimAndNeverLogged() = runBlocking {
        val url =
            "https://bucket.r2.test/obj/key%2Fa%20b.png?X-Amz-Credential=a%2F20261023%2Fauto&" +
                "X-Amz-Signature=ab%2Bcd%3D&x=1+2"
        val harness = Harness { respond("secret-body", HttpStatusCode.Forbidden) }

        val response =
            harness.executor.executeBytes(
                priority = RaftRequestPriority.Foreground,
                url = url,
                method = "PUT",
                headers = mapOf("Content-Type" to "image/png"),
                bodyBytes = ByteArray(10) { it.toByte() },
            )

        assertEquals(url, harness.requests.single().url.toString())
        assertEquals("image/png", harness.requests.single().body.contentType.toString())
        assertEquals(403, response.code)
        assertNull(response.bodyText)
        assertEquals("attachment_presigned_upload_http_403", response.errorBodyText)
        assertTrue(response.responseHeaders.isEmpty())
        assertFalse(harness.logs.any { "X-Amz" in it || "bucket.r2" in it || "secret-body" in it }, harness.logs.joinToString("\n"))
    }

    /** `slock.attachment.upload_finished`: what the transport hands up for the two upload requests. */
    @Test
    fun uploadRequestsReportBodySizePhaseTimesAndEdgeLocation() = runBlocking {
        val facts = "reusedConnection=false connectSetupMs=57 requestBodyMs=1840 responseWaitMs=312"
        val harness =
            Harness(transportFacts = { requestId -> facts.takeIf { requestId.startsWith("mobile-") } }) {
                respond("{}", HttpStatusCode.OK, headersOf("X-Amz-Cf-Pop", "NRT57-P2"))
            }
        val traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"

        val legacy =
            harness.executor.executeMultipart(
                priority = RaftRequestPriority.Foreground,
                url = "https://api.raft.test/api/attachments/upload",
                fields = mapOf("channelId" to "c1"),
                files =
                    listOf(
                        MultipartFile(
                            name = "files",
                            fileName = "a much longer file name than the file is.jpg",
                            mimeType = "image/jpeg",
                            bytes = ByteArray(64) { 1 },
                        )
                    ),
                headers = mapOf("Authorization" to "Bearer access-1", "traceparent" to traceparent),
            )
        val direct =
            harness.executor.executeBytes(
                priority = RaftRequestPriority.Foreground,
                url = "https://bucket.r2.test/obj/key?X-Amz-Signature=abc",
                method = "PUT",
                // A traceparent handed to the presigned path by mistake is not sent either.
                headers = mapOf("Content-Type" to "image/jpeg", "traceparent" to traceparent),
                bodyBytes = ByteArray(10) { it.toByte() },
            )
        val ordinary = harness.executor.execute(RaftRequestPriority.Visible, "https://api.raft.test/api/x", "GET", emptyMap(), null, false)

        val (post, put, _) = harness.requests
        // The caller's traceparent (the upload's trace) goes to the API; the storage host gets none.
        assertEquals(traceparent, post.headers["traceparent"])
        assertNull(put.headers["traceparent"])
        assertTrue(put.headers["x-request-id"].orEmpty().startsWith("mobile-"))
        // The file's own bytes: the multipart framing (which holds the file name) is not counted.
        assertEquals(
            RaftUploadWireFacts(requestBytes = 64L, connectMs = 57L, requestBodyMs = 1_840L, responseWaitMs = 312L, edgePop = "NRT57-P2"),
            legacy.uploadFacts,
        )
        // The log line of an upload's API call names the trace it joins beside its own request id.
        assertTrue(
            harness.logs.any { it.startsWith("slock_http_multipart_start") && "sentTraceId=0af7651916cd43dd8448eb211c80319c " in it },
            harness.logs.joinToString("\n"),
        )
        // A presigned PUT keeps none of the storage answer's headers, only the edge location's code.
        assertTrue(direct.responseHeaders.isEmpty())
        assertEquals(
            RaftUploadWireFacts(requestBytes = 10L, connectMs = 57L, requestBodyMs = 1_840L, responseWaitMs = 312L, edgePop = "NRT57-P2"),
            direct.uploadFacts,
        )
        assertNull(ordinary.uploadFacts, "only upload requests carry upload facts")
    }

    @Test
    fun aMultipartUploadResetByTheConnectionIsSentOnceMore() = runBlocking {
        // Hands a5eae49e: a VPN reset the upload's stream after the body reached the server.
        var calls = 0
        val harness =
            Harness {
                calls += 1
                if (calls == 1) throw IllegalStateException("stream was reset: PROTOCOL_ERROR")
                respond("""{"ok":true}""", HttpStatusCode.OK)
            }

        val response =
            harness.executor.executeMultipart(
                RaftRequestPriority.Foreground, "https://api.raft.test/api/attachments/upload", mapOf("channelId" to "c1"), emptyList(), emptyMap()
            )

        assertEquals(200, response.code)
        assertEquals(2, harness.requests.size)
        assertTrue(harness.logs.any { it.startsWith("slock_http_multipart_retry") }, harness.logs.joinToString("\n"))
        // slock.attachment.upload_finished: the body went twice inside this one call.
        assertEquals(2, response.uploadFacts?.sends)
    }

    @Test
    fun aMultipartUploadIsRetriedOnlyOnceAndNeverAfterATimeout() = runBlocking {
        val reset = Harness { throw IllegalStateException("stream was reset: PROTOCOL_ERROR") }
        val resetResponse =
            reset.executor.executeMultipart(RaftRequestPriority.Foreground, "https://api.raft.test/api/attachments/upload", emptyMap(), emptyList(), emptyMap())
        assertEquals(0, resetResponse.code)
        assertEquals(2, reset.requests.size)

        val timeout = Harness { throw HttpRequestTimeoutException("https://api.raft.test/api/attachments/upload", 180_000L) }
        timeout.executor.executeMultipart(RaftRequestPriority.Foreground, "https://api.raft.test/api/attachments/upload", emptyMap(), emptyList(), emptyMap())
        assertEquals(1, timeout.requests.size)

        val server = Harness { respond("""{"error":"too_large"}""", HttpStatusCode.PayloadTooLarge) }
        server.executor.executeMultipart(RaftRequestPriority.Foreground, "https://api.raft.test/api/attachments/upload", emptyMap(), emptyList(), emptyMap())
        assertEquals(1, server.requests.size)
    }

    @Test
    fun httpErrorsAreReturnedNotThrownAndEdgeBlockPagesKeepTheirMarker() = runBlocking {
        val harness =
            Harness { request ->
                if (request.url.encodedPath.endsWith("/edge")) {
                    respond("<html>blocked</html>", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "text/html"))
                } else {
                    respond("""{"error":"nope"}""", HttpStatusCode.InternalServerError)
                }
            }

        val server = harness.executor.execute(RaftRequestPriority.Visible, "https://api.raft.test/api/x", "GET", emptyMap(), null, false)
        val edge = harness.executor.execute(RaftRequestPriority.Visible, "https://api.raft.test/api/edge", "GET", emptyMap(), null, false)

        assertEquals(500, server.code)
        assertEquals("""{"error":"nope"}""", server.errorBodyText)
        assertEquals(403, edge.code)
        assertEquals("""{"error":"edge_blocked"}""", edge.errorBodyText)
    }

    @Test
    fun callerCancellationPropagatesAndCancelsTheEngineCall() = runBlocking {
        val reachedEngine = CompletableDeferred<Unit>()
        val engineCancelled = CompletableDeferred<Unit>()
        val harness =
            Harness {
                reachedEngine.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    engineCancelled.complete(Unit)
                }
            }

        val call = async { harness.executor.execute(RaftRequestPriority.Foreground, "https://api.raft.test/api/x", "POST", emptyMap(), "{}", true) }
        withTimeout(10_000) { reachedEngine.await() }
        call.cancel()

        assertFailsWith<CancellationException> { call.await() }
        withTimeout(10_000) { engineCancelled.await() }
        // Hands 50a29f61: a cancelled call must still log a finish line, never look like a hang.
        assertTrue(
            harness.logs.any { it.startsWith("slock_http_execute_finish") && "kind=CANCELLED" in it && "detail=caller_cancelled" in it },
            harness.logs.joinToString("\n")
        )
    }

    /** A lane whose platform replaces its connections after a timeout (iOS, Hands 43837e84). */
    private class RecyclingLane(private val recycleOnTimeout: Boolean) : RaftKtorClientProvider {
        var generation = 0L
        val sent = mutableListOf<Long>()
        val outcomes = mutableListOf<Pair<Long, Boolean>>()
        private val clients = mutableMapOf<Long, HttpClient>()

        override fun client(priority: RaftRequestPriority, kind: RaftKtorCallKind): HttpClient {
            val gen = generation
            return clients.getOrPut(gen) {
                HttpClient(
                    MockEngine {
                        sent += gen
                        if (gen == 0L) throw SocketTimeoutException("Socket timeout has expired") else respond("""{"ok":true}""", HttpStatusCode.OK)
                    }
                ) { configureRaftKtorClient() }
            }
        }

        override fun generation(priority: RaftRequestPriority): Long = generation

        override fun recordOutcome(priority: RaftRequestPriority, generation: Long, timedOut: Boolean) {
            outcomes += generation to timedOut
            if (timedOut && recycleOnTimeout && generation == this.generation) this.generation += 1
        }
    }

    private fun executorOn(lane: RaftKtorClientProvider, logs: MutableList<String>) =
        KtorRaftHttpExecutor(
            warningLogger = { logs += it },
            clients = lane,
            requestLifetimeCoordinator = RaftNetworkRequestLifetimeCoordinator(),
            transportFacts = { null },
            engineLabel = "ktor_test",
            uploadProgress = BodyWriterUploadProgressTracker,
        )

    @Test
    fun aGetThatTimedOutWhileItsLaneWasReplacedRunsOnceMoreOnTheFreshConnections() = runBlocking {
        val lane = RecyclingLane(recycleOnTimeout = true)
        val logs = mutableListOf<String>()

        val response = executorOn(lane, logs).execute(RaftRequestPriority.Visible, "https://api.raft.test/api/attachments/a/url", "GET", emptyMap(), null, false)

        assertEquals(200, response.code)
        assertEquals(listOf(0L, 1L), lane.sent)
        assertEquals(listOf(0L to true, 1L to false), lane.outcomes)
        assertTrue(logs.any { it.startsWith("slock_http_execute_retry_fresh_session lane=visible_idempotent") }, logs.joinToString("\n"))
    }

    @Test
    fun aPostIsNeverResentOnTheFreshConnections() = runBlocking {
        val lane = RecyclingLane(recycleOnTimeout = true)
        val response =
            executorOn(lane, mutableListOf()).execute(RaftRequestPriority.Visible, "https://api.raft.test/api/messages", "POST", emptyMap(), "{}", true)

        assertEquals(0, response.code)
        assertEquals(listOf(0L), lane.sent)
        assertEquals(1L, lane.generation, "the lane is still replaced for the next calls")
    }

    @Test
    fun failedResponseTraceIdIsLoggedAndRecordedForTheErrorShownNext() = runBlocking {
        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        for ((status, recorded) in listOf(HttpStatusCode.InternalServerError to traceId, HttpStatusCode.Unauthorized to null, HttpStatusCode.OK to null)) {
            val traceIds = RaftRecentFailedTraceId()
            val harness =
                Harness(failedTraceIds = traceIds) {
                    respond("""{"error":"boom"}""", status, headersOf("x-trace-id", traceId.uppercase()))
                }

            val response =
                harness.executor.execute(
                    priority = RaftRequestPriority.Visible,
                    url = "https://api.raft.test/api/messages/context",
                    method = "GET",
                    headers = emptyMap(),
                    bodyText = null,
                    jsonBody = false
                )

            assertEquals(status.value, response.code)
            assertEquals(recorded, traceIds.recent(), "recorded trace id for $status")
            val finish = harness.logs.single { it.startsWith("slock_http_execute_finish") }
            assertTrue("traceId=$traceId" in finish, finish)
        }
    }

    @Test
    fun failureTraceHookCapturesHttpAndEdgeFailuresAndSkipsSuccessAndCancellation() = runBlocking {
        class RecordedFailure(
            val method: String,
            val url: String,
            val sentTraceId: String?,
            val statusCode: Int,
            val errorBodyText: String?,
            val redactSensitive: Boolean,
        )
        val recorded = mutableListOf<RecordedFailure>()
        val traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
        val reachedCancel = CompletableDeferred<Unit>()
        val harness =
            Harness(
                recordHttpFailureTrace = { method, url, _, sentTraceId, code, errorBody, _, _, redact ->
                    recorded += RecordedFailure(method, url, sentTraceId, code, errorBody, redact)
                },
            ) { request ->
                when {
                    request.url.encodedPath.endsWith("/ok") -> respond("""{"ok":true}""", HttpStatusCode.OK)
                    request.url.encodedPath.endsWith("/edge") ->
                        respond("<html>blocked</html>", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "text/html"))
                    request.url.encodedPath.endsWith("/hang") -> {
                        reachedCancel.complete(Unit)
                        awaitCancellation()
                    }
                    else -> respond("""{"error":"boom"}""", HttpStatusCode.InternalServerError)
                }
            }

        harness.executor.execute(RaftRequestPriority.Visible, "https://api.raft.test/api/ok", "GET", emptyMap(), null, false)
        harness.executor.execute(
            RaftRequestPriority.Visible,
            "https://api.raft.test/api/edge",
            "GET",
            mapOf("traceparent" to traceparent),
            null,
            false,
        )
        harness.executor.executeBytes(
            RaftRequestPriority.Foreground,
            "https://bucket.r2.test/obj/key?X-Amz-Signature=abc",
            "PUT",
            emptyMap(),
            ByteArray(4),
        )
        val cancelled = async {
            harness.executor.execute(RaftRequestPriority.Visible, "https://api.raft.test/api/hang", "GET", emptyMap(), null, false)
        }
        withTimeout(10_000) { reachedCancel.await() }
        cancelled.cancel()
        assertFailsWith<CancellationException> { cancelled.await() }

        assertEquals(2, recorded.size)
        assertEquals("GET", recorded[0].method)
        assertEquals("0af7651916cd43dd8448eb211c80319c", recorded[0].sentTraceId)
        assertEquals(403, recorded[0].statusCode)
        assertEquals("""{"error":"edge_blocked"}""", recorded[0].errorBodyText)
        assertFalse(recorded[0].redactSensitive)

        assertEquals("PUT", recorded[1].method)
        assertEquals(500, recorded[1].statusCode)
        assertTrue(recorded[1].redactSensitive)
    }

    private class Harness(
        coordinator: RaftNetworkRequestLifetimeCoordinator = RaftNetworkRequestLifetimeCoordinator(),
        engineReportsProtocol: Boolean = true,
        failedTraceIds: RaftRecentFailedTraceId = RaftRecentFailedTraceId(),
        transportFacts: (requestId: String) -> String? = { null },
        recordHttpFailureTrace: (
            method: String,
            url: String,
            requestHeaders: Map<String, String>,
            sentTraceId: String?,
            statusCode: Int,
            errorBodyText: String?,
            transportDiagnostics: String?,
            durationMs: Long,
            redactSensitive: Boolean,
        ) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        val requests = mutableListOf<HttpRequestData>()
        val lanes = mutableListOf<Pair<RaftRequestPriority, RaftKtorCallKind>>()
        val logs = mutableListOf<String>()
        private val client =
            HttpClient(
                MockEngine { request ->
                    requests += request
                    handler(request)
                }
            ) { configureRaftKtorClient() }
        val executor =
            KtorRaftHttpExecutor(
                warningLogger = { logs += it },
                clients = RaftKtorClientProvider { priority, kind ->
                    lanes += priority to kind
                    client
                },
                requestLifetimeCoordinator = coordinator,
                transportFacts = transportFacts,
                engineLabel = "ktor_test",
                engineReportsProtocol = engineReportsProtocol,
                // MockEngine consumes the body itself: count at the body writer here.
                uploadProgress = BodyWriterUploadProgressTracker,
                failedTraceIds = failedTraceIds,
                recordHttpFailureTrace = recordHttpFailureTrace,
            )
    }
}

/** Platform exception types (java.net / NSURLError) and the kind each must map to. */
internal expect fun platformFailureCases(): List<Pair<Throwable, String>>
