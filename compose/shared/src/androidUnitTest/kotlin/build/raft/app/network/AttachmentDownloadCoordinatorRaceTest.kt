package build.raft.app.network

import com.tencent.kmm.network.export.NetworkRequest
import com.tencent.kmm.network.export.NetworkResponse
import com.tencent.kmm.network.export.NetworkResponseBody
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
        val engine = AttachmentDownloadCoordinatorEngine(transport, AttachmentDownloadSinkFactory { _, _ -> sink }) { 0L }

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
        val sink = RaceSink()
        val transport = RaceTransport()
        val observer = RaceObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, AttachmentDownloadSinkFactory { _, _ -> sink }) { 0L }
        val token = engine.start(request(), observer)
        transport.call.start(200, 1L)
        assertEquals("paused", engine.control("download-race", token, "pause"))
        val chunkThread = thread { transport.call.chunk(byteArrayOf(1)) }
        Thread.sleep(50L)

        val started = System.nanoTime()
        assertEquals("cancelled", engine.control("download-race", token, "cancel"))
        val durationMs = (System.nanoTime() - started) / 1_000_000L
        chunkThread.join(1_000L)

        assertTrue(durationMs < 500L, "cancel took ${durationMs}ms")
        assertFalse(chunkThread.isAlive)
        assertEquals(0, sink.writeCount)
        assertEquals(1, sink.abortCount)
    }

    @Test
    fun slowProgressDoesNotBlockCancelAndTerminalWaitsForAdmittedCallback() {
        val progressEntered = CountDownLatch(1)
        val releaseProgress = CountDownLatch(1)
        val observer = RaceObserver(progressEntered, releaseProgress)
        val transport = RaceTransport()
        val sink = RaceSink()
        val engine = AttachmentDownloadCoordinatorEngine(transport, AttachmentDownloadSinkFactory { _, _ -> sink }) { 0L }
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

        assertEquals(0, oldSink.commitCount)
        assertEquals(1, oldSink.abortCount)
        assertEquals(listOf("cancelled"), oldObserver.results.map { it.status })
        assertFalse(oldObserver.results.any { it.status == "complete" })
    }

    @Test
    fun atomicRenameWinningBeforeCancelIsTheSuccessLinearizationPoint() {
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val sink = RaceSink(commitEntered = commitEntered, releaseCommit = releaseCommit)
        val transport = RaceTransport()
        val observer = RaceObserver()
        val engine = AttachmentDownloadCoordinatorEngine(transport, AttachmentDownloadSinkFactory { _, _ -> sink }) { 0L }
        val token = engine.start(request(), observer)
        transport.call.start(200, 1L)
        transport.call.chunk(byteArrayOf(1))
        val completionThread = thread { transport.call.complete(successResponse()) }
        assertTrue(commitEntered.await(1, TimeUnit.SECONDS))

        var cancelStatus = ""
        val cancelThread = thread { cancelStatus = engine.control("download-race", token, "cancel") }
        Thread.sleep(50L)
        assertTrue(cancelThread.isAlive, "cancel must serialize with the publication operation")
        releaseCommit.countDown()
        completionThread.join(1_000L)
        cancelThread.join(1_000L)

        assertFalse(completionThread.isAlive)
        assertFalse(cancelThread.isAlive)
        assertTrue(cancelStatus == "finishing" || cancelStatus == "missing", cancelStatus)
        assertEquals(1, sink.commitCount)
        assertEquals(0, sink.abortCount)
        assertEquals(listOf("complete"), observer.results.map { it.status })
    }

    private fun request() =
        AttachmentDownloadRequest(
            downloadId = "download-race",
            targetKey = "/cache/final.bin",
            url = "https://example.test/file.bin",
            targetPath = "/cache/final.bin"
        )

    private fun successResponse() =
        NetworkResponse(
            request = NetworkRequest(url = "https://example.test/file.bin"),
            statusCode = 200,
            headers = emptyMap(),
            body = NetworkResponseBody()
        )
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
    fun complete(response: NetworkResponse) = callbacks.onComplete(response)
}

private class RaceSink(
    private val beginEntered: CountDownLatch? = null,
    private val releaseBegin: CountDownLatch? = null,
    private val commitEntered: CountDownLatch? = null,
    private val releaseCommit: CountDownLatch? = null
) : AttachmentDownloadAtomicSink {
    var writeCount = 0
    var commitCount = 0
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

    override fun commit(): Boolean {
        commitCount += 1
        commitEntered?.countDown()
        releaseCommit?.await(1, TimeUnit.SECONDS)
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
