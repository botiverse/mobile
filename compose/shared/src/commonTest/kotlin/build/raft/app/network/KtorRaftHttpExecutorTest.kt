package build.raft.app.network

import build.raft.app.data.RaftPlatformFileChunkSource
import build.raft.app.data.boundedPlatformFileNetworkStream
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.network.sockets.ConnectTimeoutException
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
                RaftKtorCallKind.NonIdempotent,
                RaftKtorCallKind.PresignedUpload,
            )
        assertEquals(
            RaftRequestPriority.entries.flatMap { priority -> expectedPerLane.map { priority to it } },
            harness.lanes
        )
    }

    @Test
    fun callerContentTypeMovesOntoTheBody() = runBlocking {
        val harness = Harness { respond("{}", HttpStatusCode.Created) }

        val response =
            harness.executor.execute(
                priority = RaftRequestPriority.Foreground,
                url = "https://api.raft.test/api/messages",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json; charset=utf-8", "Content-Length" to "999"),
                bodyText = """{"content":"hi"}""",
                jsonBody = true
            )

        assertEquals(201, response.code)
        val body = harness.requests.single().body
        assertEquals("application/json; charset=utf-8", body.contentType.toString())
        assertEquals("""{"content":"hi"}""", body.toByteArray().decodeToString())
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

    @Test
    fun transportFailuresMapToTheExistingErrorKinds() = runBlocking {
        val cases: List<Pair<Throwable, String>> =
            listOf(
                HttpRequestTimeoutException("https://api.raft.test/api/x", 30_000L) to "TIMEOUT",
                ConnectTimeoutException("connect timed out") to "TIMEOUT",
                IllegalStateException("boom") to "UNKNOWN",
            ) + platformFailureCases()
        cases.forEach { (failure, kind) ->
            val harness = Harness { throw failure }

            val response = harness.executor.execute(RaftRequestPriority.Visible, "https://api.raft.test/api/x", "GET", emptyMap(), null, false)

            val label = failure::class.simpleName
            assertEquals(0, response.code, "$label")
            assertTrue("kind=$kind " in response.transportDiagnostics.orEmpty(), "$label: ${response.transportDiagnostics}")
            if (kind == "TIMEOUT") {
                // Settings / Home map error codes containing "timeout" to the timed-out copy.
                assertTrue(response.errorBodyText.orEmpty().contains("timeout"), "$label: ${response.errorBodyText}")
            }
        }
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
    fun multipartUploadStreamsTheFileAndReportsProgressToTheEnd() = runBlocking {
        val fileBytes = ByteArray(300 * 1024) { (it % 251).toByte() }
        val harness = Harness { request -> respond(request.body.toByteArray().size.toString(), HttpStatusCode.OK) }
        val progress = mutableListOf<RaftTransferProgress>()

        val response =
            harness.executor.executeMultipart(
                priority = RaftRequestPriority.Foreground,
                url = "https://api.raft.test/api/attachments/upload",
                fields = mapOf("channelId" to "c1"),
                files =
                    listOf(
                        MultipartFile(
                            name = "file",
                            fileName = "photo\".png",
                            mimeType = "image/png",
                            fileRef = "content://photo",
                            sizeBytes = fileBytes.size.toLong(),
                            streamOpener = { boundedPlatformFileNetworkStream(fileBytes.size.toLong(), ByteChunkSource(fileBytes)) }
                        )
                    ),
                headers = mapOf(HEADER_SERVER_ID to "server-1"),
                onUploadProgress = { progress += it }
            )

        assertEquals(200, response.code)
        val total = response.bodyText!!.toLong()
        assertEquals(total, harness.requests.single().body.contentLength)
        assertTrue(progress.size > 2, "progress=$progress")
        assertEquals(progress.map { it.bytesTransferred }.sorted(), progress.map { it.bytesTransferred })
        assertEquals(RaftTransferProgress(total, total), progress.last())
        assertTrue(harness.requests.single().body.contentType.toString().startsWith("multipart/form-data; boundary="))
        assertEquals(listOf(RaftRequestPriority.Foreground to RaftKtorCallKind.NonIdempotent), harness.lanes)
    }

    @Test
    fun presignedFilePutCarriesItsLengthAndReportsProgress() = runBlocking {
        val fileBytes = ByteArray(150 * 1024) { 7 }
        val received = CompletableDeferred<ByteArray>()
        val harness = Harness { request ->
            received.complete(request.body.toByteArray())
            respond("", HttpStatusCode.OK)
        }
        val progress = mutableListOf<RaftTransferProgress>()

        val response =
            harness.executor.executeFile(
                priority = RaftRequestPriority.Visible,
                url = "https://bucket.test/k?sig=1",
                method = "PUT",
                headers = emptyMap(),
                body =
                    RaftHttpFileBody(
                        path = "content://doc",
                        contentType = "application/pdf",
                        contentLength = fileBytes.size.toLong(),
                        openStream = { boundedPlatformFileNetworkStream(fileBytes.size.toLong(), ByteChunkSource(fileBytes)) }
                    ),
                onUploadProgress = { progress += it }
            )

        assertEquals(200, response.code)
        assertTrue(received.await().contentEquals(fileBytes))
        val body = harness.requests.single().body
        assertEquals(fileBytes.size.toLong(), body.contentLength)
        assertEquals("application/pdf", body.contentType.toString())
        assertEquals(RaftTransferProgress(fileBytes.size.toLong(), fileBytes.size.toLong()), progress.last())
    }

    @Test
    fun visibilityRetirementCancelsTheInFlightReadLikeBefore() = runBlocking {
        val coordinator = RaftNetworkRequestLifetimeCoordinator()
        coordinator.onProcessVisibilityChanged(foreground = true)
        val reachedEngine = CompletableDeferred<Unit>()
        val engineCancelled = CompletableDeferred<Unit>()
        val harness =
            Harness(coordinator) {
                reachedEngine.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    engineCancelled.complete(Unit)
                }
            }

        val call = async { harness.executor.execute(RaftRequestPriority.Visible, "https://api.raft.test/api/x", "GET", emptyMap(), null, false) }
        withTimeout(10_000) { reachedEngine.await() }
        assertEquals(1, coordinator.onProcessVisibilityChanged(foreground = false).retiredRequestCount)

        val response = call.await()
        assertEquals(0, response.code)
        assertEquals("network_request_lifecycle_retired", response.errorBodyText)
        assertTrue("kind=CANCELLED" in response.transportDiagnostics.orEmpty())
        withTimeout(10_000) { engineCancelled.await() }
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

    private class Harness(
        coordinator: RaftNetworkRequestLifetimeCoordinator = RaftNetworkRequestLifetimeCoordinator(),
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
                transportFacts = { null },
                engineLabel = "ktor_test",
                // MockEngine consumes the body itself: count at the body writer here.
                uploadProgress = BodyWriterUploadProgressTracker,
            )
    }

    private class ByteChunkSource(private val bytes: ByteArray) : RaftPlatformFileChunkSource {
        private var offset = 0

        override suspend fun read(maxBytes: Int): ByteArray {
            val end = minOf(bytes.size, offset + maxBytes)
            return bytes.copyOfRange(offset, end).also { offset = end }
        }

        override fun close() = Unit
    }
}

/** Platform exception types (java.net / NSURLError) and the kind each must map to. */
internal expect fun platformFailureCases(): List<Pair<Throwable, String>>
