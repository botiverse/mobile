package build.raft.app.network

import com.tencent.kmm.network.export.NetworkBody
import com.tencent.kmm.network.export.NetworkError
import com.tencent.kmm.network.export.NetworkErrorKind
import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttachmentDownloadCoordinatorTest {
    @Test
    fun knownLengthStreamsThroughSinkAndPublishesOnlyAfterAtomicCommit() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver()
        var now = 1_000L
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks) { now }

        val token = engine.start(request(), observer)
        val call = transport.calls.single()
        call.start(statusCode = 200, contentLength = 10L)
        assertNull(observer.progress.single().percent, "response wait must not invent 0%")
        now += 100L
        call.chunk(byteArrayOf(1, 2, 3, 4))
        now += 300L
        call.chunk(byteArrayOf(5, 6, 7, 8, 9, 10))
        call.complete(successResponse())

        val sink = sinks.created.single()
        assertEquals(token, sink.attemptToken)
        assertEquals(10, sink.bytes.size)
        assertEquals(1, sink.commitCount)
        assertEquals(0, sink.abortCount)
        assertEquals(listOf("response_wait", "receiving", "receiving", "complete"), observer.progress.map { it.phase })
        assertEquals(listOf(null, 40, 100, 100), observer.progress.map { it.percent })
        assertEquals("complete", observer.results.single().status)
    }

    @Test
    fun unknownLengthNeverInventsPercentButStillCompletes() {
        val transport = FakeTransport()
        val observer = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, FakeSinkFactory()) { 1_000L }

        engine.start(request(), observer)
        transport.calls.single().apply {
            start(statusCode = 200, contentLength = null)
            chunk(byteArrayOf(1, 2, 3))
            complete(successResponse())
        }

        assertTrue(observer.progress.all { it.percent == null })
        assertEquals(3L, observer.results.single().downloadedBytes)
    }

    @Test
    fun sameIdReplacementAbortsOldAttemptAndRejectsAllLateCallbacks() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val firstObserver = RecordingObserver()
        val secondObserver = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks) { 1_000L }

        val firstToken = engine.start(request(), firstObserver)
        val first = transport.calls.single()
        val secondToken = engine.start(request(), secondObserver)
        val second = transport.calls.last()

        assertTrue(first.cancelled)
        assertEquals("cancelled", firstObserver.results.single().status)
        assertEquals(1, sinks.created.first().abortCount)
        first.start(200, 4L)
        first.chunk(byteArrayOf(1, 2, 3, 4))
        first.complete(successResponse())
        assertTrue(firstObserver.progress.isEmpty(), "late old-attempt callbacks must be fenced")

        second.start(200, 2L)
        second.chunk(byteArrayOf(8, 9))
        second.complete(successResponse())
        assertEquals("complete", secondObserver.results.single().status)
        assertEquals(1, sinks.created.last().commitCount)
        assertFalse(firstToken == secondToken)
    }

    @Test
    fun cancelReturnsAfterAbortAndLateTransportCallbacksStaySilent() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks) { 1_000L }

        engine.start(request(), observer)
        val call = transport.calls.single()
        call.start(200, 4L)
        call.chunk(byteArrayOf(1, 2))
        assertEquals("cancelled", engine.control("download-1", null, "cancel"))
        assertTrue(call.cancelled)
        assertEquals(1, sinks.created.single().abortCount)
        val progressCount = observer.progress.size

        call.chunk(byteArrayOf(3, 4))
        call.complete(successResponse())
        assertEquals(progressCount, observer.progress.size)
        assertEquals(listOf("cancelled"), observer.results.map { it.status })
        assertEquals(0, sinks.created.single().commitCount)
    }

    @Test
    fun progressCallbackFailureCancelsTransportAndCleansStagingExactlyOnce() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver(throwOnProgress = true)
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks) { 1_000L }

        engine.start(request(), observer)
        val call = transport.calls.single()
        call.start(200, 5L)

        assertTrue(call.cancelled)
        assertEquals(1, sinks.created.single().abortCount)
        assertEquals("failed", observer.results.single().status)
        assertEquals("progress_callback", observer.results.single().errorKind)
        call.complete(successResponse())
        assertEquals(1, observer.results.size)
    }

    @Test
    fun sinkCommitFailureNeverPublishesSuccessAndAlwaysCleansStaging() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory(commitSucceeds = false)
        val observer = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks) { 1_000L }

        engine.start(request(), observer)
        transport.calls.single().apply {
            start(200, 1L)
            chunk(byteArrayOf(1))
            complete(successResponse())
        }

        assertEquals("failed", observer.results.single().status)
        assertEquals("storage_commit", observer.results.single().errorKind)
        assertEquals(1, sinks.created.single().commitCount)
        assertEquals(1, sinks.created.single().abortCount)
    }

    @Test
    fun coordinatorPassesWholeAndPhaseTimeoutPolicyToNetworkOwner() {
        val transport = FakeTransport()
        val engine = AttachmentDownloadCoordinatorEngine(transport, FakeSinkFactory()) { 1_000L }
        val request =
            request().copy(
                connectTimeoutMillis = 1_111L,
                responseHeadersTimeoutMillis = 2_222L,
                interChunkIdleTimeoutMillis = 3_333L,
                wholeTransferTimeoutMillis = 4_444L
            )

        engine.start(request, RecordingObserver())

        assertEquals(request, transport.requests.single())
    }

    @Test
    fun idleOrWholeTimeoutFailsOnceAndAbortsStaging() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks) { 1_000L }

        engine.start(request(), observer)
        val call = transport.calls.single()
        call.start(200, 100L)
        call.chunk(byteArrayOf(1, 2, 3))
        call.complete(
            NetworkResponse(
                request = NetworkRequest(url = "https://example.test/file.bin"),
                statusCode = null,
                headers = emptyMap(),
                body = NetworkResponseBody(),
                error = NetworkError(NetworkErrorKind.TIMEOUT, "inter-chunk idle timeout")
            )
        )

        assertEquals(1, observer.results.size)
        assertEquals("failed", observer.results.single().status)
        assertEquals("timeout", observer.results.single().errorKind)
        assertEquals(1, sinks.created.single().abortCount)
        call.complete(successResponse())
        assertEquals(1, observer.results.size)
    }

    @Test
    fun knownLengthMustMatchExactlyIncludingDeclaredZero() {
        listOf(5L to 4, 5L to 6).forEach { (declared, actual) ->
            val transport = FakeTransport()
            val sinks = FakeSinkFactory()
            val observer = RecordingObserver()
            val engine = AttachmentDownloadCoordinatorEngine(transport, sinks) { 1_000L }
            engine.start(request(), observer)
            transport.calls.single().apply {
                start(200, declared)
                chunk(ByteArray(actual))
                complete(successResponse())
            }
            assertEquals("failed", observer.results.single().status)
            assertEquals("content_length_mismatch", observer.results.single().errorKind)
            assertEquals(0, sinks.created.single().commitCount)
            assertEquals(1, sinks.created.single().abortCount)
        }

        val zeroTransport = FakeTransport()
        val zeroObserver = RecordingObserver()
        val zeroSinks = FakeSinkFactory()
        val zeroEngine = AttachmentDownloadCoordinatorEngine(zeroTransport, zeroSinks) { 1_000L }
        zeroEngine.start(request(), zeroObserver)
        zeroTransport.calls.single().apply {
            start(200, 0L)
            complete(successResponse())
        }
        assertEquals("complete", zeroObserver.results.single().status)
        assertEquals(1, zeroSinks.created.single().commitCount)
        assertEquals(100, zeroObserver.progress.last().percent)
    }

    @Test
    fun successWithoutResponseStartFailsClosed() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks) { 1_000L }

        engine.start(request(), observer)
        transport.calls.single().complete(successResponse())

        assertEquals("failed", observer.results.single().status)
        assertEquals("missing_response_start", observer.results.single().errorKind)
        assertEquals(0, sinks.created.single().commitCount)
        assertEquals(1, sinks.created.single().abortCount)
    }

    @Test
    fun byteArithmeticSaturatesAndPercentCannotOverflow() {
        assertEquals(Long.MAX_VALUE, saturatingAdd(Long.MAX_VALUE - 2L, 10L))
        assertEquals(Long.MAX_VALUE, saturatingMultiply(Long.MAX_VALUE / 2L + 1L, 1_000L))
        assertEquals(99, attachmentDownloadPercent(Long.MAX_VALUE - 1L, Long.MAX_VALUE))
        assertEquals(100, attachmentDownloadPercent(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test
    fun finalProgressFailureWinsOverCommitAndSuccessTerminal() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver(throwOnPhase = "complete")
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks) { 1_000L }

        engine.start(request(), observer)
        transport.calls.single().apply {
            start(200, 1L)
            chunk(byteArrayOf(1))
            complete(successResponse())
        }

        assertEquals(listOf("failed"), observer.results.map { it.status })
        assertEquals("progress_callback", observer.results.single().errorKind)
        assertEquals(0, sinks.created.single().commitCount)
        assertEquals(1, sinks.created.single().abortCount)
    }

    @Test
    fun progressObserverCanReenterCancelWithoutDeadlockOrDoubleTerminal() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        lateinit var engine: AttachmentDownloadCoordinatorEngine
        val observer =
            object : AttachmentDownloadObserver {
                val results = mutableListOf<AttachmentDownloadResult>()

                override fun onProgress(progress: AttachmentDownloadProgress) {
                    if (progress.phase == "receiving") {
                        assertEquals("cancelled", engine.control("download-1", progress.attemptToken, "cancel"))
                    }
                }

                override fun onComplete(result: AttachmentDownloadResult) {
                    results += result
                }
            }
        engine = AttachmentDownloadCoordinatorEngine(transport, sinks) { 1_000L }

        engine.start(request(), observer)
        transport.calls.single().apply {
            start(200, 2L)
            chunk(byteArrayOf(1))
            complete(successResponse())
        }

        assertEquals(1, observer.results.size)
        assertEquals("cancelled", observer.results.single().status)
        assertEquals(1, sinks.created.single().abortCount)
        assertEquals(0, sinks.created.single().commitCount)
    }

    @Test
    fun monotonicWatchdogCancelsNoncooperativeHeaderIdleAndWholeTimeouts() {
        fun runTimeout(
            request: AttachmentDownloadRequest,
            prepare: (FakeTransportCall) -> Unit,
            now: Long,
            expectedKind: String
        ) {
            val transport = FakeTransport()
            val sinks = FakeSinkFactory()
            val observer = RecordingObserver()
            var clock = 0L
            val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, { clock })
            engine.start(request, observer)
            val call = transport.calls.single()
            prepare(call)
            clock = now
            engine.checkTimeouts(clock)
            assertTrue(call.cancelled, expectedKind)
            assertEquals(expectedKind, observer.results.single().errorKind)
            assertEquals(1, sinks.created.single().abortCount)
            call.complete(successResponse())
            assertEquals(1, observer.results.size)
        }

        runTimeout(
            request().copy(connectTimeoutMillis = 100L, responseHeadersTimeoutMillis = 200L, wholeTransferTimeoutMillis = 0L),
            prepare = {},
            now = 300L,
            expectedKind = "response_headers_timeout"
        )
        runTimeout(
            request().copy(interChunkIdleTimeoutMillis = 250L, wholeTransferTimeoutMillis = 0L),
            prepare = { it.start(200, null) },
            now = 250L,
            expectedKind = "idle_timeout"
        )
        runTimeout(
            request().copy(wholeTransferTimeoutMillis = 150L),
            prepare = {},
            now = 150L,
            expectedKind = "whole_timeout"
        )
    }

    @Test
    fun staleAttemptTokenCannotControlSameIdReplacement() {
        val transport = FakeTransport()
        val engine = AttachmentDownloadCoordinatorEngine(transport, FakeSinkFactory()) { 1_000L }
        val first = engine.start(request(), RecordingObserver())
        val second = engine.start(request(), RecordingObserver())

        assertEquals("stale", engine.control("download-1", first, "cancel"))
        assertEquals("cancelled", engine.control("download-1", second, "cancel"))
    }

    @Test
    fun preStartCancelTombstonePreventsSinkBeginAndTransportStart() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks) { 1_000L }

        assertEquals("cancelled", engine.control("download-prestart", null, "cancel"))
        engine.start(request().copy(downloadId = "download-prestart"), observer)

        assertTrue(sinks.created.isNotEmpty())
        assertEquals(0, sinks.created.single().beginCount)
        assertTrue(transport.calls.isEmpty())
        assertEquals("cancelled", observer.results.single().status)
    }

    @Test
    fun expiredPreStartCancelDoesNotKillAReusedDownloadId() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver()
        var clock = 0L
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, { clock })

        assertEquals("cancelled", engine.control("download-prestart", null, "cancel"))
        clock = 60_000L
        engine.start(request().copy(downloadId = "download-prestart"), observer)

        assertEquals(1, sinks.created.single().beginCount)
        assertEquals(1, transport.calls.size)
        assertTrue(observer.results.isEmpty())
    }

    private fun request(): AttachmentDownloadRequest =
        AttachmentDownloadRequest(
            downloadId = "download-1",
            targetKey = "attachment-1",
            url = "https://example.test/file.bin",
            targetPath = "/cache/file.bin"
        )

    private fun successResponse(): NetworkResponse =
        NetworkResponse(
            request = NetworkRequest(url = "https://example.test/file.bin", body = NetworkBody.Empty),
            statusCode = 200,
            headers = mapOf("Content-Type" to listOf("application/octet-stream")),
            body = NetworkResponseBody()
        )
}

private class FakeTransport : AttachmentDownloadTransport {
    val requests = mutableListOf<AttachmentDownloadRequest>()
    val calls = mutableListOf<FakeTransportCall>()

    override fun start(
        request: AttachmentDownloadRequest,
        callbacks: AttachmentDownloadTransportCallbacks
    ): AttachmentDownloadTransportCall {
        requests += request
        return FakeTransportCall(callbacks).also(calls::add)
    }
}

private class FakeTransportCall(
    private val callbacks: AttachmentDownloadTransportCallbacks
) : AttachmentDownloadTransportCall {
    var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    fun start(statusCode: Int?, contentLength: Long?) {
        callbacks.onResponseStart(statusCode, contentLength, emptyMap())
    }

    fun chunk(bytes: ByteArray) {
        callbacks.onChunk(bytes)
    }

    fun complete(response: NetworkResponse) {
        callbacks.onComplete(response)
    }
}

private class FakeSinkFactory(
    private val beginSucceeds: Boolean = true,
    private val writeSucceeds: Boolean = true,
    private val commitSucceeds: Boolean = true
) : AttachmentDownloadSinkFactory {
    val created = mutableListOf<FakeSink>()

    override fun create(targetPath: String, attemptToken: String): AttachmentDownloadAtomicSink =
        FakeSink(attemptToken, beginSucceeds, writeSucceeds, commitSucceeds).also(created::add)
}

private class FakeSink(
    val attemptToken: String,
    private val beginSucceeds: Boolean,
    private val writeSucceeds: Boolean,
    private val commitSucceeds: Boolean
) : AttachmentDownloadAtomicSink {
    var bytes = ByteArray(0)
    var commitCount = 0
    var abortCount = 0
    var beginCount = 0

    override fun begin(): Boolean {
        beginCount += 1
        return beginSucceeds
    }

    override fun write(bytes: ByteArray): Boolean {
        if (writeSucceeds) this.bytes += bytes
        return writeSucceeds
    }

    override fun commit(): Boolean {
        commitCount += 1
        return commitSucceeds
    }

    override fun abort() {
        abortCount += 1
    }
}

private class RecordingObserver(
    private val throwOnProgress: Boolean = false,
    private val throwOnPhase: String? = null
) : AttachmentDownloadObserver {
    val progress = mutableListOf<AttachmentDownloadProgress>()
    val results = mutableListOf<AttachmentDownloadResult>()

    override fun onProgress(progress: AttachmentDownloadProgress) {
        if (throwOnProgress || progress.phase == throwOnPhase) error("observer failed")
        this.progress += progress
    }

    override fun onComplete(result: AttachmentDownloadResult) {
        results += result
    }
}
