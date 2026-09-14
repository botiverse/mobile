package build.raft.app.network

import com.tencent.kmm.network.export.NetworkByteStream
import com.tencent.kmm.network.export.NetworkError
import com.tencent.kmm.network.export.NetworkErrorKind
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import com.tencent.kmm.network.export.NetworkTransferProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * task #783 production-path teeth: streaming fileRef multipart uploads carry
 * the 3-minute total budget on the built transport request (the transport's
 * hard deadline enforces it); bytes multipart keeps 30s; the executor adds no
 * app-layer bound of its own; timeout and cancel terminal semantics are
 * unchanged. All virtual-clock — no large buffers, no real sleeps.
 */
class NetworkKmmUploadTimeoutTest {

    private fun streamingFile(size: Long = 199_229_440L) =
        MultipartFile(
            name = "files",
            fileName = "large.bin",
            mimeType = "application/octet-stream",
            fileRef = "file:///cache/large.bin",
            sizeBytes = size,
            streamOpener =
                MultipartFileStreamOpener {
                    NetworkByteStream(
                        contentLength = size,
                        readAllBlock = { error("whole-file read forbidden") },
                        readChunksBlock = { }
                    )
                }
        )

    private fun bytesFile() =
        MultipartFile(
            name = "files",
            fileName = "small.txt",
            mimeType = "text/plain",
            bytes = "hello".encodeToByteArray()
        )

    private fun okResponse(request: NetworkRequest) =
        NetworkResponse(
            request = request,
            statusCode = 200,
            headers = emptyMap(),
            body = NetworkResponseBody(bytes = """{"ok":true}""".encodeToByteArray())
        )

    private fun fileBody() =
        SlockHttpFileBody(
            path = "/tmp/large.bin",
            contentType = "application/octet-stream",
            contentLength = 199_229_440L,
            openStream = {
                NetworkByteStream(
                    contentLength = 199_229_440L,
                    readAllBlock = { error("whole-file read forbidden") },
                    readChunksBlock = { }
                )
            }
        )

    private suspend fun NetworkKmmSlockHttpExecutor.uploadStreaming() =
        executeMultipart(
            priority = SlockRequestPriority.Foreground,
            url = "https://api.test/api/attachments/upload",
            fields = mapOf("channelId" to "c1"),
            files = listOf(streamingFile()),
            headers = emptyMap()
        )

    @Test
    fun streamingMultipartCarriesThreeMinuteBudget() = runTest {
        var captured: NetworkRequest? = null
        val executor =
            NetworkKmmSlockHttpExecutor(
                warningLogger = {},
                multipartTransportOverride = { request ->
                    captured = request
                    okResponse(request)
                }
            )

        val response = executor.uploadStreaming()

        val request = assertNotNull(captured)
        assertEquals(
            180_000L,
            request.policy.timeoutMillis,
            "streaming fileRef upload must carry the 3-minute total budget, not the generic 30s"
        )
        assertEquals(200, response.code)
        assertTrue(response.isSuccessful)
    }

    @Test
    fun presignedFileUploadIsFlaggedForHttp1Only() = runTest {
        var captured: NetworkRequest? = null
        val executor =
            NetworkKmmSlockHttpExecutor(
                warningLogger = {},
                requestStarterOverride = { request, onComplete ->
                    captured = request
                    onComplete(okResponse(request))
                    SlockNetworkRequestCancelHandle {}
                }
            )

        val response =
            executor.executeFile(
                priority = SlockRequestPriority.Foreground,
                url = "https://example.test/r2-bucket/key",
                method = "PUT",
                headers = emptyMap(),
                body = fileBody()
            )

        val request = assertNotNull(captured)
        assertTrue(request.isHttp1Only(), "presigned direct-upload PUT must opt out of HTTP/3")
        assertEquals(
            10_000L,
            request.policy.streamTimeouts.connectTimeoutMillis,
            "presigned direct-upload PUT must keep connect at 10s (task #1135)"
        )
        assertEquals(200, response.code)
    }

    @Test
    fun presignedBytesUploadIsFlaggedForHttp1Only() = runTest {
        var captured: NetworkRequest? = null
        val executor =
            NetworkKmmSlockHttpExecutor(
                warningLogger = {},
                requestStarterOverride = { request, onComplete ->
                    captured = request
                    onComplete(okResponse(request))
                    SlockNetworkRequestCancelHandle {}
                }
            )

        executor.executeBytes(
            priority = SlockRequestPriority.Foreground,
            url = "https://example.test/r2-bucket/key",
            method = "PUT",
            headers = emptyMap(),
            bodyBytes = "hello".encodeToByteArray()
        )

        val request = assertNotNull(captured)
        assertTrue(request.isHttp1Only(), "in-memory presigned PUT must also opt out of HTTP/3")
        assertEquals(
            10_000L,
            request.policy.streamTimeouts.connectTimeoutMillis,
            "in-memory presigned PUT must keep connect below its 30s total budget"
        )
    }

    @Test
    fun bytesMultipartKeepsOrdinaryWallClockBound() = runTest {
        var captured: NetworkRequest? = null
        val executor =
            NetworkKmmSlockHttpExecutor(
                warningLogger = {},
                multipartTransportOverride = { request ->
                    captured = request
                    okResponse(request)
                }
            )

        executor.executeMultipart(
            priority = SlockRequestPriority.Foreground,
            url = "https://api.test/api/attachments/upload",
            fields = emptyMap(),
            files = listOf(bytesFile()),
            headers = emptyMap()
        )

        assertEquals(
            30_000L,
            assertNotNull(captured).policy.timeoutMillis,
            "in-memory bytes multipart keeps the ordinary request bound"
        )
    }

    @Test
    fun multipartForwardsTransportByteProgressWithoutSynthesizingPercentages() = runTest {
        val observed = mutableListOf<SlockTransferProgress>()
        val executor =
            NetworkKmmSlockHttpExecutor(
                warningLogger = {},
                multipartTransportOverride = { request ->
                    request.progress.uploadProgress?.invoke(NetworkTransferProgress(64L, 256L))
                    request.progress.uploadProgress?.invoke(NetworkTransferProgress(256L, 256L))
                    okResponse(request)
                }
            )

        executor.executeMultipart(
            priority = SlockRequestPriority.Foreground,
            url = "https://api.test/api/attachments/upload",
            fields = emptyMap(),
            files = listOf(bytesFile()),
            headers = emptyMap(),
            onUploadProgress = observed::add
        )

        assertEquals(
            listOf(SlockTransferProgress(64L, 256L), SlockTransferProgress(256L, 256L)),
            observed
        )
    }

    @Test
    fun transferLongerThanOldWallClockCompletes() = runTest {
        val executor =
            NetworkKmmSlockHttpExecutor(
                warningLogger = {},
                multipartTransportOverride = { request ->
                    // 120s of virtual transfer — four times the old 30s wall
                    // clock, inside the 3-minute budget — must complete: the
                    // executor adds no app-layer bound of its own.
                    delay(120_000L)
                    okResponse(request)
                }
            )

        val response = executor.uploadStreaming()

        assertEquals(200, response.code)
        assertNull(response.errorBodyText)
        assertTrue(
            testScheduler.currentTime >= 120_000L,
            "the slow upload must have run far past the old 30s wall clock"
        )
    }

    @Test
    fun transportTimeoutMapsToOrdinaryFailureResponse() = runTest {
        val executor =
            NetworkKmmSlockHttpExecutor(
                warningLogger = {},
                multipartTransportOverride = { request ->
                    delay(180_000L)
                    NetworkResponse(
                        request = request,
                        statusCode = null,
                        headers = emptyMap(),
                        body = NetworkResponseBody(),
                        error =
                            NetworkError(
                                kind = NetworkErrorKind.TIMEOUT,
                                message =
                                    "[timeout] SocketTimeoutException: Request wall-clock deadline " +
                                        "exceeded [configuredTimeoutMs=180000, deadlineElapsedMs=180002]"
                            )
                    )
                }
            )

        val response = executor.uploadStreaming()

        assertEquals(0, response.code, "transport timeout must map to code 0, never throw")
        val error = assertNotNull(response.errorBodyText)
        assertTrue(error.contains("[timeout]"), "timeout classification must survive the mapping: $error")
    }

    @Test
    fun externalCancellationPropagatesAndReachesTransport() = runTest {
        var transportCancelled = false
        var cancellationPropagated = false
        var mappedToFailure = false
        val executor =
            NetworkKmmSlockHttpExecutor(
                warningLogger = {},
                multipartTransportOverride = {
                    try {
                        awaitCancellation()
                    } finally {
                        transportCancelled = true
                    }
                }
            )

        val job =
            launch {
                try {
                    val response = executor.uploadStreaming()
                    mappedToFailure = response.errorBodyText != null
                } catch (cancellation: CancellationException) {
                    cancellationPropagated = true
                    throw cancellation
                }
            }
        advanceTimeBy(5_000L)
        runCurrent()
        job.cancel()
        job.join()

        assertTrue(transportCancelled, "explicit cancel must reach the in-flight transport attempt")
        assertTrue(cancellationPropagated, "cancel must propagate as CancellationException")
        assertFalse(mappedToFailure, "cancel must not be rewritten into a failure response")
    }
}
