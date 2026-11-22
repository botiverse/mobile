package build.raft.app.network

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
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { now })

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
        // Every byte staged is 99%: 100 belongs to the host's completion, after the atomic commit.
        assertEquals(listOf(null, 40, 99, 99), observer.progress.map { it.percent })
        assertEquals("complete", observer.results.single().status)
    }

    @Test
    fun sameIdReplacementAbortsOldAttemptAndRejectsAllLateCallbacks() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val firstObserver = RecordingObserver()
        val secondObserver = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

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
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

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
    fun sinkCommitFailureNeverPublishesSuccessAndAlwaysCleansStaging() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory(commitSucceeds = false)
        val observer = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

        engine.start(request(), observer)
        transport.calls.single().apply {
            start(200, 1L)
            chunk(byteArrayOf(1))
            complete(successResponse())
        }

        assertEquals("failed", observer.results.single().status)
        assertEquals("storage_publish", observer.results.single().errorKind)
        assertEquals(1, sinks.created.single().commitCount)
        assertEquals(1, sinks.created.single().abortCount)
    }

    @Test
    fun idleOrWholeTimeoutFailsOnceAndAbortsStaging() {
        val transport = FakeTransport()
        val sinks = FakeSinkFactory()
        val observer = RecordingObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

        engine.start(request(), observer)
        val call = transport.calls.single()
        call.start(200, 100L)
        call.chunk(byteArrayOf(1, 2, 3))
        call.complete(
            AttachmentDownloadCompletion(statusCode = null, headers = emptyMap(), errorKind = "timeout", errorMessage = "inter-chunk idle timeout")
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
            val engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })
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
        val zeroEngine = AttachmentDownloadCoordinatorEngine(zeroTransport, zeroSinks, nowMillis = { 1_000L })
        zeroEngine.start(request(), zeroObserver)
        zeroTransport.calls.single().apply {
            start(200, 0L)
            complete(successResponse())
        }
        assertEquals("complete", zeroObserver.results.single().status)
        assertEquals(1, zeroSinks.created.single().commitCount)
        assertEquals("complete", zeroObserver.progress.last().phase)
        assertNull(zeroObserver.progress.last().percent, "an empty body has no percent before the host completes it")
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
        engine = AttachmentDownloadCoordinatorEngine(transport, sinks, nowMillis = { 1_000L })

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

    private fun request(): AttachmentDownloadRequest =
        AttachmentDownloadRequest(
            downloadId = "download-1",
            targetKey = "attachment-1",
            url = "https://example.test/file.bin",
            targetPath = "/cache/file.bin"
        )

    private fun successResponse(): AttachmentDownloadCompletion =
        AttachmentDownloadCompletion(statusCode = 200, headers = mapOf("Content-Type" to listOf("application/octet-stream")))
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

    fun complete(response: AttachmentDownloadCompletion) {
        callbacks.onComplete(response)
    }
}

private class FakeSinkFactory(
    private val beginSucceeds: Boolean = true,
    private val writeSucceeds: Boolean = true,
    private val commitSucceeds: Boolean = true,
    private val onAbort: () -> Unit = {}
) : AttachmentDownloadSinkFactory {
    val created = mutableListOf<FakeSink>()

    override fun create(targetPath: String, attemptToken: String): AttachmentDownloadAtomicSink =
        FakeSink(
            attemptToken,
            beginSucceeds,
            writeSucceeds,
            commitSucceeds,
            onAbort
        ).also(created::add)
}

private class FakeSink(
    val attemptToken: String,
    private val beginSucceeds: Boolean,
    private val writeSucceeds: Boolean,
    private val commitSucceeds: Boolean,
    private val onAbort: () -> Unit
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

    override fun prepare(): Boolean = true

    override fun publish(): Boolean {
        commitCount += 1
        return commitSucceeds
    }

    override fun abort() {
        onAbort()
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
