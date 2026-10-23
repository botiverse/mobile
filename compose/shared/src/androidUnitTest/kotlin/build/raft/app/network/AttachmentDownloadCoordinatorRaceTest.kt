package build.raft.app.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentDownloadCoordinatorRaceTest {
    @Test
    fun cancelDuringBeginAbortsLateStagingAndNeverStartsTransport() {
        val beginEntered = CountDownLatch(1)
        val releaseBegin = CountDownLatch(1)
        val sink = RaceSink(beginEntered = beginEntered, releaseBegin = releaseBegin)
        val transport = RaceTransport()
        val observer = RaceObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, AttachmentDownloadSinkFactory { _, _ -> sink }, nowMillis = { 0L })

        val starter = thread { engine.start(request(), observer) }
        assertTrue(beginEntered.await(1, TimeUnit.SECONDS))
        assertEquals("cancelled", engine.control("download-race", null, "cancel"))
        releaseBegin.countDown()
        starter.join(1_000L)

        assertFalse(starter.isAlive)
        assertEquals(0, transport.startCount)
        assertEquals(1, sink.abortCount)
        assertEquals(listOf("cancelled"), observer.results.map { it.status })
    }

    @Test
    fun pausedChunkCancelReturnsBoundedAndRejectsLateWrite() {
        // A second admission-hook entry proves the first round resolved to Paused
        // (a Written round exits the admission loop and never re-enters the hook).
        val pausedRoundCompleted = CountDownLatch(2)
        val releaseChunk = CountDownLatch(1)
        val sink = RaceSink()
        val transport = RaceTransport()
        val observer = RaceObserver()
        val engine =
            AttachmentDownloadCoordinatorEngine(
                transport = transport,
                sinkFactory = AttachmentDownloadSinkFactory { _, _ -> sink },
                beforeChunkAdmission = {
                    pausedRoundCompleted.countDown()
                    if (pausedRoundCompleted.count == 0L) releaseChunk.await(1, TimeUnit.SECONDS)
                },
                nowMillis = { 0L }
            )
        val token = engine.start(request(), observer)
        transport.call.start(200, 1L)
        assertEquals("paused", engine.control("download-race", token, "pause"))
        val chunkThread = thread { transport.call.chunk(byteArrayOf(1)) }
        assertTrue(pausedRoundCompleted.await(1, TimeUnit.SECONDS), "chunk never completed a paused admission round")

        // The chunk thread is parked at the admission hook: cancel completing here
        // proves it does not wait on the fenced chunk (bounded by construction).
        assertEquals("cancelled", engine.control("download-race", token, "cancel"))
        releaseChunk.countDown()
        chunkThread.join(1_000L)

        assertFalse(chunkThread.isAlive)
        assertEquals(0, sink.writeCount)
        assertEquals(1, sink.abortCount)
    }

    @Test
    fun pauseReturningBeforeWriteAdmissionFencesTheChunkUntilResume() {
        val admissionEntered = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        // Two hook entries prove the first (released) round resolved to Paused
        // instead of Written — replaces a vacuous fixed-length sleep window.
        val pausedRoundCompleted = CountDownLatch(2)
        val sink = RaceSink()
        val transport = RaceTransport()
        val observer = RaceObserver()
        val engine =
            AttachmentDownloadCoordinatorEngine(
                transport = transport,
                sinkFactory = AttachmentDownloadSinkFactory { _, _ -> sink },
                beforeChunkAdmission = {
                    pausedRoundCompleted.countDown()
                    admissionEntered.countDown()
                    releaseAdmission.await(1, TimeUnit.SECONDS)
                },
                nowMillis = { 0L }
            )
        val token = engine.start(request(), observer)
        transport.call.start(200, 1L)
        val chunkThread = thread { transport.call.chunk(byteArrayOf(1)) }
        assertTrue(admissionEntered.await(1, TimeUnit.SECONDS))

        assertEquals("paused", engine.control("download-race", token, "pause"))
        releaseAdmission.countDown()
        assertTrue(pausedRoundCompleted.await(1, TimeUnit.SECONDS), "chunk never completed a paused admission round")
        assertEquals(0, sink.writeCount)
        assertEquals("resumed", engine.control("download-race", token, "resume"))
        chunkThread.join(1_000L)

        assertFalse(chunkThread.isAlive)
        assertEquals(1, sink.writeCount)
    }

    @Test
    fun missingCancelAdmissionCapturesAConcurrentlyPublishedAttemptWithoutLeavingReceipt() {
        val cancelAdmissionEntered = CountDownLatch(1)
        val releaseCancelAdmission = CountDownLatch(1)
        val transport = RaceTransport()
        val sinks = mutableListOf<RaceSink>()
        val engine =
            AttachmentDownloadCoordinatorEngine(
                transport = transport,
                sinkFactory = AttachmentDownloadSinkFactory { _, _ -> RaceSink().also(sinks::add) },
                beforeMissingControlAdmission = {
                    cancelAdmissionEntered.countDown()
                    releaseCancelAdmission.await(1, TimeUnit.SECONDS)
                },
                nowMillis = { 0L }
            )
        var cancelStatus = ""
        val cancelThread = thread { cancelStatus = engine.control("download-race", null, "cancel") }
        assertTrue(cancelAdmissionEntered.await(1, TimeUnit.SECONDS))
        engine.start(request(), RaceObserver())
        releaseCancelAdmission.countDown()
        cancelThread.join(1_000L)

        assertEquals("cancelled", cancelStatus)
        assertEquals(1, sinks.first().abortCount)
        val nextObserver = RaceObserver()
        engine.start(request(), nextObserver)
        assertEquals(2, sinks.size)
        assertTrue(nextObserver.results.isEmpty())
    }

    @Test
    fun slowProgressDoesNotBlockCancelAndTerminalWaitsForAdmittedCallback() {
        val progressEntered = CountDownLatch(1)
        val releaseProgress = CountDownLatch(1)
        val observer = RaceObserver(progressEntered, releaseProgress)
        val transport = RaceTransport()
        val sink = RaceSink()
        val engine = AttachmentDownloadCoordinatorEngine(transport, AttachmentDownloadSinkFactory { _, _ -> sink }, nowMillis = { 0L })
        val token = engine.start(request(), observer)
        transport.call.start(200, 1L)
        val chunkThread = thread { transport.call.chunk(byteArrayOf(1)) }
        assertTrue(progressEntered.await(1, TimeUnit.SECONDS))

        val started = System.nanoTime()
        assertEquals("cancelled", engine.control("download-race", token, "cancel"))
        val durationMs = (System.nanoTime() - started) / 1_000_000L
        assertTrue(durationMs < 500L, "cancel waited for slow callback: ${durationMs}ms")
        assertTrue(observer.results.isEmpty(), "terminal must queue behind the admitted progress callback")

        releaseProgress.countDown()
        chunkThread.join(1_000L)
        assertEquals(listOf("cancelled"), observer.results.map { it.status })
    }

    @Test
    fun replacementWinningDuringFinalProgressPreventsOldCommitAndSuccess() {
        val finalProgressEntered = CountDownLatch(1)
        val releaseFinalProgress = CountDownLatch(1)
        val oldObserver = RaceObserver(finalProgressEntered = finalProgressEntered, releaseFinalProgress = releaseFinalProgress)
        val newObserver = RaceObserver()
        val transport = RaceTransport()
        val oldSink = RaceSink()
        val newSink = RaceSink()
        var sinkIndex = 0
        val engine =
            AttachmentDownloadCoordinatorEngine(
                transport,
                AttachmentDownloadSinkFactory { _, _ -> if (sinkIndex++ == 0) oldSink else newSink }
            ) { 0L }
        engine.start(request(), oldObserver)
        val oldCall = transport.calls.first()
        oldCall.start(200, 1L)
        oldCall.chunk(byteArrayOf(1))
        val completionThread = thread { oldCall.complete(successResponse()) }
        assertTrue(finalProgressEntered.await(1, TimeUnit.SECONDS))

        engine.start(request(), newObserver)
        releaseFinalProgress.countDown()
        completionThread.join(1_000L)

        assertEquals(0, oldSink.publishCount)
        assertEquals(1, oldSink.abortCount)
        assertEquals(listOf("cancelled"), oldObserver.results.map { it.status })
        assertFalse(oldObserver.results.any { it.status == "complete" })
    }

    @Test
    fun cancelAfterPrepareBeforePublishingPreventsRenameAndSuccess() {
        val admissionEntered = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        val sink = RaceSink()
        val transport = RaceTransport()
        val observer = RaceObserver()
        val engine =
            AttachmentDownloadCoordinatorEngine(
                transport = transport,
                sinkFactory = AttachmentDownloadSinkFactory { _, _ -> sink },
                beforePublishAdmission = {
                    admissionEntered.countDown()
                    releaseAdmission.await(1, TimeUnit.SECONDS)
                },
                nowMillis = { 0L }
            )
        val token = engine.start(request(), observer)
        transport.call.start(200, 1L)
        transport.call.chunk(byteArrayOf(1))
        val completionThread = thread { transport.call.complete(successResponse()) }
        assertTrue(admissionEntered.await(1, TimeUnit.SECONDS))

        assertEquals("cancelled", engine.control("download-race", token, "cancel"))
        releaseAdmission.countDown()
        completionThread.join(1_000L)

        assertFalse(completionThread.isAlive)
        assertEquals(0, sink.publishCount)
        assertEquals(1, sink.abortCount)
        assertEquals(listOf("cancelled"), observer.results.map { it.status })
    }

    @Test
    fun publishingAdmissionWinningMakesCancelLoseAndRenameSuccessComplete() {
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val sink = RaceSink(publishEntered = publishEntered, releasePublish = releasePublish)
        val transport = RaceTransport()
        val observer = RaceObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, AttachmentDownloadSinkFactory { _, _ -> sink }, nowMillis = { 0L })
        val token = engine.start(request(), observer)
        transport.call.start(200, 1L)
        transport.call.chunk(byteArrayOf(1))
        val completionThread = thread { transport.call.complete(successResponse()) }
        assertTrue(publishEntered.await(1, TimeUnit.SECONDS))

        assertEquals("finishing", engine.control("download-race", token, "cancel"))
        releasePublish.countDown()
        completionThread.join(1_000L)

        assertFalse(completionThread.isAlive)
        assertEquals(1, sink.publishCount)
        assertEquals(0, sink.abortCount)
        assertEquals(listOf("complete"), observer.results.map { it.status })
    }

    @Test
    fun publishingAttemptKeepsTargetOwnershipUntilRenameReturns() {
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val oldSink = RaceSink(publishEntered = publishEntered, releasePublish = releasePublish)
        val rejectedSink = RaceSink()
        val nextSink = RaceSink()
        val sinks = mutableListOf(oldSink, rejectedSink, nextSink)
        val transport = RaceTransport()
        val oldObserver = RaceObserver()
        val rejectedObserver = RaceObserver()
        val nextObserver = RaceObserver()
        val engine =
            AttachmentDownloadCoordinatorEngine(
                transport = transport,
                sinkFactory = AttachmentDownloadSinkFactory { _, _ -> sinks.removeAt(0) },
                nowMillis = { 0L }
            )
        engine.start(request(), oldObserver)
        val oldCall = transport.calls.single()
        oldCall.start(200, 1L)
        oldCall.chunk(byteArrayOf(1))
        val completionThread = thread { oldCall.complete(successResponse()) }
        assertTrue(publishEntered.await(1, TimeUnit.SECONDS))

        engine.start(request(downloadId = "replacement-race"), rejectedObserver)

        assertEquals(1, transport.startCount, "replacement must be rejected before transport while target publishes")
        assertEquals(0, rejectedSink.writeCount)
        assertEquals(0, rejectedSink.publishCount)
        assertEquals(listOf("cancelled"), rejectedObserver.results.map { it.status })
        assertEquals(listOf("target_publishing"), rejectedObserver.results.map { it.errorKind })

        releasePublish.countDown()
        completionThread.join(1_000L)
        assertFalse(completionThread.isAlive)
        assertEquals(1, oldSink.publishCount)
        assertEquals(listOf("complete"), oldObserver.results.map { it.status })

        engine.start(request(downloadId = "next-race"), nextObserver)
        assertEquals(2, transport.startCount, "next generation may start after publishing owner terminals")
        assertEquals(0, nextSink.publishCount)
        assertTrue(nextObserver.results.isEmpty())
    }

    private fun request(downloadId: String = "download-race") =
        AttachmentDownloadRequest(
            downloadId = downloadId,
            targetKey = "/cache/final.bin",
            url = "https://example.test/file.bin",
            targetPath = "/cache/final.bin"
        )

    private fun successResponse() =
        AttachmentDownloadCompletion(statusCode = 200, headers = emptyMap())
}

private class RaceTransport : AttachmentDownloadTransport {
    val calls = mutableListOf<RaceCall>()
    val call: RaceCall get() = calls.single()
    var startCount = 0

    override fun start(
        request: AttachmentDownloadRequest,
        callbacks: AttachmentDownloadTransportCallbacks
    ): AttachmentDownloadTransportCall {
        startCount += 1
        return RaceCall(callbacks).also(calls::add)
    }
}

private class RaceCall(private val callbacks: AttachmentDownloadTransportCallbacks) : AttachmentDownloadTransportCall {
    var cancelled = false
    override fun cancel() { cancelled = true }
    fun start(code: Int?, length: Long?) = callbacks.onResponseStart(code, length, emptyMap())
    fun chunk(bytes: ByteArray) = callbacks.onChunk(bytes)
    fun complete(response: AttachmentDownloadCompletion) = callbacks.onComplete(response)
}

private class RaceSink(
    private val beginEntered: CountDownLatch? = null,
    private val releaseBegin: CountDownLatch? = null,
    private val publishEntered: CountDownLatch? = null,
    private val releasePublish: CountDownLatch? = null
) : AttachmentDownloadAtomicSink {
    var writeCount = 0
    var publishCount = 0
    var abortCount = 0

    override fun begin(): Boolean {
        beginEntered?.countDown()
        releaseBegin?.await(1, TimeUnit.SECONDS)
        return true
    }

    override fun write(bytes: ByteArray): Boolean {
        writeCount += 1
        return true
    }

    override fun prepare(): Boolean = true

    override fun publish(): Boolean {
        publishCount += 1
        publishEntered?.countDown()
        releasePublish?.await(1, TimeUnit.SECONDS)
        return true
    }

    override fun abort() {
        abortCount += 1
    }
}

private class RaceObserver(
    private val progressEntered: CountDownLatch? = null,
    private val releaseProgress: CountDownLatch? = null,
    private val finalProgressEntered: CountDownLatch? = null,
    private val releaseFinalProgress: CountDownLatch? = null
) : AttachmentDownloadObserver {
    val results = mutableListOf<AttachmentDownloadResult>()

    override fun onProgress(progress: AttachmentDownloadProgress) {
        if (progress.phase == "receiving" && progressEntered != null) {
            progressEntered.countDown()
            releaseProgress?.await(1, TimeUnit.SECONDS)
        }
        if (progress.phase == "complete" && finalProgressEntered != null) {
            finalProgressEntered.countDown()
            releaseFinalProgress?.await(1, TimeUnit.SECONDS)
        }
    }

    override fun onComplete(result: AttachmentDownloadResult) {
        results += result
    }
}
