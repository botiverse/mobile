package build.raft.app.network

import android.app.Instrumentation
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAttachmentDownloadCoordinatorRuntimeTest {

    @Test
    fun slowStreamPublishesExactBytesOnceWithoutResidue() = withRuntimeDirectory("slow") { directory ->
        val target = directory.resolve("attachment.bin")
        val transport = RuntimeTransport()
        val observer = RuntimeObserver()
        val sinkStats = RuntimeSinkStats()
        var now = 0L
        val engine = runtimeEngine(transport, sinkFactory = runtimeSinkFactory(sinkStats), nowMillis = { now })
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

        engine.start(runtimeRequest(target), observer)
        val call = transport.calls.single()
        call.response(bytes.size.toLong())
        bytes.toList().chunked(2).forEach { chunk ->
            now += 300L
            call.chunk(chunk.toByteArray())
        }
        call.complete()

        assertEquals(listOf("complete"), observer.results.map { it.status })
        assertArrayEquals(bytes, target.readBytes())
        assertEquals(bytes.sha256(), target.readBytes().sha256())
        assertFalse(directory.hasPartFiles())
        assertStrictlyIncreasing(observer.progress.map { it.sequence })
        assertEquals(
            listOf("response_wait", "receiving", "receiving", "receiving", "receiving", "complete"),
            observer.progress.map { it.phase }
        )
        assertEquals(listOf(0L, 2L, 4L, 6L, 8L, 8L), observer.progress.map { it.downloadedBytes })
        assertEquals(listOf(null, 25, 50, 75, 99, 99), observer.progress.map { it.percent })
        assertEquals(listOf(null, null, 6L, 6L, 6L, null), observer.progress.map { it.bytesPerSecond })
        assertEquals(listOf(1, 4, 1, 1, 0), sinkStats.snapshot())
        runtimeEvidence("slow_complete", target, observer, transport, sinkStats)
    }

    @Test
    fun cancelBeforePublishAndDuringBlockedWriteNeverPublishes() = withRuntimeDirectory("cancel") { directory ->
        val beforePublishEntered = CountDownLatch(1)
        val releasePublishAdmission = CountDownLatch(1)
        val target = directory.resolve("before-publish.bin").apply { writeBytes(byteArrayOf(9, 9)) }
        val transport = RuntimeTransport()
        val observer = RuntimeObserver()
        val sinkStats = RuntimeSinkStats()
        val engine =
            runtimeEngine(
                transport = transport,
                sinkFactory = runtimeSinkFactory(sinkStats),
                beforePublishAdmission = {
                    beforePublishEntered.countDown()
                    releasePublishAdmission.await(2, TimeUnit.SECONDS)
                }
            )
        val token = engine.start(runtimeRequest(target), observer)
        transport.calls.single().apply {
            response(2L)
            chunk(byteArrayOf(1, 2))
        }
        val completion = thread { transport.calls.single().complete() }
        assertTrue(beforePublishEntered.await(2, TimeUnit.SECONDS))
        assertEquals("cancelled", engine.control("download-runtime", token, "cancel"))
        releasePublishAdmission.countDown()
        completion.join(2_000L)

        assertArrayEquals(byteArrayOf(9, 9), target.readBytes())
        assertEquals(listOf("cancelled"), observer.results.map { it.status })
        assertFalse(directory.hasPartFiles())
        assertEquals(listOf(1, 1, 1, 0, 1), sinkStats.snapshot())

        val blockedTarget = directory.resolve("blocked-write.bin")
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val blockedSinkStats = RuntimeSinkStats()
        try {
            val blockedTransport = RuntimeTransport()
            val blockedObserver = RuntimeObserver()
            val blockedEngine =
                AttachmentDownloadCoordinatorEngine(
                    transport = blockedTransport,
                    sinkFactory = AttachmentDownloadSinkFactory { path, attempt ->
                        BlockingWriteSink(
                            RuntimeCountingSink(createAttachmentDownloadAtomicSink(path, attempt), blockedSinkStats),
                            writeEntered,
                            releaseWrite
                        )
                    },
                    watchdogScope = scope
                )
            val blockedToken = blockedEngine.start(runtimeRequest(blockedTarget, "download-blocked", "target-blocked"), blockedObserver)
            blockedTransport.calls.single().response(2L)
            val writer = thread { blockedTransport.calls.single().chunk(byteArrayOf(3, 4)) }
            assertTrue(writeEntered.await(2, TimeUnit.SECONDS))
            val started = System.nanoTime()
            assertEquals("cancelled", blockedEngine.control("download-blocked", blockedToken, "cancel"))
            assertTrue((System.nanoTime() - started) / 1_000_000L < 500L)
            releaseWrite.countDown()
            writer.join(2_000L)
            assertTrue(blockedObserver.awaitTerminal())
            waitForNoParts(directory)
            assertFalse(blockedTarget.exists())
            assertFalse(blockedObserver.results.any { it.status == "complete" })
            assertFalse(directory.hasPartFiles())
            assertEquals(listOf(1, 1, 0, 0, 1), blockedSinkStats.snapshot())
            runtimeEvidence("cancel_paths", blockedTarget, blockedObserver, blockedTransport, blockedSinkStats)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pauseResumeFreezesIdleAndKeepsProgressOrdered() = withRuntimeDirectory("pause") { directory ->
        val target = directory.resolve("attachment.bin")
        val transport = RuntimeTransport()
        val observer = RuntimeObserver()
        val sinkStats = RuntimeSinkStats()
        var now = 0L
        val engine = runtimeEngine(transport, sinkFactory = runtimeSinkFactory(sinkStats), nowMillis = { now })
        val token =
            engine.start(
                runtimeRequest(target).copy(interChunkIdleTimeoutMillis = 100L, wholeTransferTimeoutMillis = 20_000L),
                observer
            )
        val call = transport.calls.single()
        call.response(4L)
        call.chunk(byteArrayOf(1, 2))
        assertEquals("paused", engine.control("download-runtime", token, "pause"))
        val progressAtPause = observer.progress.size
        val writer = thread { call.chunk(byteArrayOf(3, 4)) }
        now = 10_000L
        engine.checkTimeouts(now)
        Thread.sleep(100L)
        assertEquals(progressAtPause, observer.progress.size)
        assertTrue(observer.results.isEmpty())
        assertEquals("resumed", engine.control("download-runtime", token, "resume"))
        writer.join(2_000L)
        call.complete()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), target.readBytes())
        assertEquals(listOf("complete"), observer.results.map { it.status })
        assertStrictlyIncreasing(observer.progress.map { it.sequence })
        assertFalse(directory.hasPartFiles())
        assertEquals(listOf(1, 2, 1, 1, 0), sinkStats.snapshot())
        runtimeEvidence("pause_resume", target, observer, transport, sinkStats)
    }

    @Test
    fun wholeDeadlineWinsWhileFinalProgressWaitsBeforePublish() = withRuntimeDirectory("timeout") { directory ->
        val target = directory.resolve("attachment.bin").apply { writeBytes(byteArrayOf(8)) }
        val transport = RuntimeTransport()
        val observer = RuntimeObserver()
        val pendingDrains = mutableListOf<() -> Unit>()
        val sinkStats = RuntimeSinkStats()
        var now = 0L
        val engine =
            runtimeEngine(
                transport = transport,
                sinkFactory = runtimeSinkFactory(sinkStats),
                observerDispatcher = { pendingDrains += it },
                nowMillis = { now }
            )
        engine.start(runtimeRequest(target).copy(wholeTransferTimeoutMillis = 100L), observer)
        transport.calls.single().apply {
            response(1L)
            chunk(byteArrayOf(7))
            complete()
        }
        now = 101L
        engine.checkTimeouts(now)
        pendingDrains.toList().forEach { it() }

        assertArrayEquals(byteArrayOf(8), target.readBytes())
        assertEquals(listOf("failed"), observer.results.map { it.status })
        assertEquals("whole_timeout", observer.results.single().errorKind)
        assertFalse(observer.results.any { it.status == "complete" })
        assertFalse(observer.progress.any { it.phase == "complete" })
        assertFalse(directory.hasPartFiles())
        assertEquals(listOf(1, 1, 0, 0, 1), sinkStats.snapshot())
        runtimeEvidence("whole_timeout", target, observer, transport, sinkStats)
    }

    @Test
    fun publishingOwnerRejectsReplacementThenNextGenerationPublishes() = withRuntimeDirectory("publishing") { directory ->
        val target = directory.resolve("attachment.bin")
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val transport = RuntimeTransport()
        val observers = List(3) { RuntimeObserver() }
        val created = AtomicInteger(0)
        val sinkStats = RuntimeSinkStats()
        val engine =
            AttachmentDownloadCoordinatorEngine(
                transport = transport,
                sinkFactory = AttachmentDownloadSinkFactory { path, attempt ->
                    val delegate = RuntimeCountingSink(createAttachmentDownloadAtomicSink(path, attempt), sinkStats)
                    if (created.getAndIncrement() == 0) BlockingPublishSink(delegate, publishEntered, releasePublish) else delegate
                }
            )
        engine.start(runtimeRequest(target, "download-a", "shared-target"), observers[0])
        transport.calls.single().apply {
            response(1L)
            chunk(byteArrayOf(1))
        }
        val completion = thread { transport.calls.first().complete() }
        assertTrue(publishEntered.await(2, TimeUnit.SECONDS))

        engine.start(runtimeRequest(target, "download-b", "shared-target"), observers[1])
        assertEquals(1, transport.calls.size)
        assertEquals(listOf("cancelled"), observers[1].results.map { it.status })
        assertEquals("target_publishing", observers[1].results.single().errorKind)

        releasePublish.countDown()
        completion.join(2_000L)
        assertArrayEquals(byteArrayOf(1), target.readBytes())
        assertEquals(listOf("complete"), observers[0].results.map { it.status })

        engine.start(runtimeRequest(target, "download-c", "shared-target"), observers[2])
        transport.calls.last().apply {
            response(1L)
            chunk(byteArrayOf(3))
            complete()
        }
        assertArrayEquals(byteArrayOf(3), target.readBytes())
        assertEquals(listOf("complete"), observers[2].results.map { it.status })
        assertFalse(directory.hasPartFiles())
        assertEquals(listOf(2, 2, 2, 2, 0), sinkStats.snapshot())
        runtimeEvidence("publishing_replacement", target, observers[2], transport, sinkStats)
    }

    @Test
    fun cleanupPrestartCancelAndQueuedObserverRemainGenerationSafe() = withRuntimeDirectory("generation") { directory ->
        val target = directory.resolve("attachment.bin")
        val transport = RuntimeTransport()
        val pendingDrains = mutableListOf<() -> Unit>()
        val sinkStats = RuntimeSinkStats()
        val engine =
            runtimeEngine(
                transport,
                sinkFactory = runtimeSinkFactory(sinkStats),
                observerDispatcher = { pendingDrains += it }
            )

        assertEquals("cancelled", engine.control("prestart", null, "cancel"))
        val prestartObserver = RuntimeObserver()
        engine.start(runtimeRequest(target, "prestart", "prestart-target"), prestartObserver)
        pendingDrains.toList().also { pendingDrains.clear() }.forEach { it() }
        assertEquals(listOf("cancelled"), prestartObserver.results.map { it.status })
        assertEquals(0, transport.calls.size)

        val generationObserver = GenerationFencedObserver()
        val activeToken = engine.start(runtimeRequest(target, "active", "cleanup-target"), generationObserver)
        generationObserver.expectedAttemptToken = activeToken
        transport.calls.single().apply {
            response(2L)
            chunk(byteArrayOf(4, 5))
        }
        val reservation = engine.beginTargetCleanup("cleanup-target")
        assertEquals("cancelled", reservation.status)
        target.delete()
        assertFalse(directory.hasPartFiles())
        val oldGenerationDrain = pendingDrains.removeAt(0)

        val rejectedObserver = RuntimeObserver()
        engine.start(runtimeRequest(target, "active", "cleanup-target"), rejectedObserver)
        pendingDrains.toList().also { pendingDrains.clear() }.forEach { it() }
        assertEquals("target_deleted", rejectedObserver.results.single().errorKind)
        assertEquals(1, transport.calls.size)

        engine.finishTargetCleanup("cleanup-target", reservation.token)
        val nextToken = engine.start(runtimeRequest(target, "active", "cleanup-target"), generationObserver)
        generationObserver.expectedAttemptToken = nextToken
        transport.calls.last().apply {
            response(2L)
            chunk(byteArrayOf(6, 7))
            complete()
        }
        pendingDrains.toList().also { pendingDrains.clear() }.forEach { it() }
        oldGenerationDrain()
        assertArrayEquals(byteArrayOf(6, 7), target.readBytes())
        assertEquals(listOf("response_wait", "receiving", "complete"), generationObserver.progress.map { it.phase })
        assertEquals(listOf("complete"), generationObserver.results.map { it.status })
        assertEquals(nextToken, generationObserver.results.single().attemptToken)
        assertTrue(generationObserver.staleCallbackCount.get() >= 3)
        assertFalse(directory.hasPartFiles())
        assertEquals(listOf(2, 2, 1, 1, 1), sinkStats.snapshot())
        runtimeEvidence("generation_safety", target, generationObserver, transport, sinkStats)
    }

    private fun runtimeEngine(
        transport: RuntimeTransport,
        sinkFactory: AttachmentDownloadSinkFactory = AttachmentDownloadSinkFactory(::createAttachmentDownloadAtomicSink),
        beforePublishAdmission: (String) -> Unit = {},
        observerDispatcher: ((() -> Unit) -> Unit)? = null,
        nowMillis: () -> Long = { 0L }
    ) =
        AttachmentDownloadCoordinatorEngine(
            transport = transport,
            sinkFactory = sinkFactory,
            beforePublishAdmission = beforePublishAdmission,
            observerDispatcher = observerDispatcher,
            nowMillis = nowMillis
        )

    private fun runtimeRequest(
        target: File,
        downloadId: String = "download-runtime",
        targetKey: String = "target-runtime"
    ) =
        AttachmentDownloadRequest(
            downloadId = downloadId,
            targetKey = targetKey,
            url = "https://runtime.invalid/attachment.bin",
            targetPath = target.absolutePath
        )

    private fun runtimeEvidence(
        scenario: String,
        target: File,
        observer: RuntimeEvidenceObserver,
        transport: RuntimeTransport,
        sinkStats: RuntimeSinkStats
    ) {
        val marker =
            "TASK2_RUNTIME scenario=$scenario targetExists=${target.exists()} targetBytes=${if (target.exists()) target.length() else 0} " +
                "progress=${observer.progress.size} terminals=${observer.results.size} transportStarts=${transport.calls.size} " +
                "transportCancels=${transport.calls.sumOf { it.cancelCount.get() }} " +
                "begin=${sinkStats.begin.get()} write=${sinkStats.write.get()} prepare=${sinkStats.prepare.get()} " +
                "publish=${sinkStats.publish.get()} abort=${sinkStats.abort.get()}"
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply {
                putString(Instrumentation.REPORT_KEY_STREAMRESULT, "$marker\n")
            }
        )
    }

    private fun assertStrictlyIncreasing(values: List<Long>) {
        assertTrue(values.size > 1)
        assertTrue(values.zipWithNext().all { (previous, next) -> previous < next })
    }

    private fun withRuntimeDirectory(name: String, block: (File) -> Unit) {
        val directory = Files.createTempDirectory("attachment-runtime-$name").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}

private fun runtimeSinkFactory(stats: RuntimeSinkStats): AttachmentDownloadSinkFactory =
    AttachmentDownloadSinkFactory { path, attempt ->
        RuntimeCountingSink(createAttachmentDownloadAtomicSink(path, attempt), stats)
    }

private class RuntimeSinkStats {
    val begin = AtomicInteger(0)
    val write = AtomicInteger(0)
    val prepare = AtomicInteger(0)
    val publish = AtomicInteger(0)
    val abort = AtomicInteger(0)

    fun snapshot(): List<Int> = listOf(begin.get(), write.get(), prepare.get(), publish.get(), abort.get())
}

private class RuntimeCountingSink(
    private val delegate: AttachmentDownloadAtomicSink,
    private val stats: RuntimeSinkStats
) : AttachmentDownloadAtomicSink {
    override fun begin(): Boolean {
        stats.begin.incrementAndGet()
        return delegate.begin()
    }

    override fun write(bytes: ByteArray): Boolean {
        stats.write.incrementAndGet()
        return delegate.write(bytes)
    }

    override fun prepare(): Boolean {
        stats.prepare.incrementAndGet()
        return delegate.prepare()
    }

    override fun publish(): Boolean {
        stats.publish.incrementAndGet()
        return delegate.publish()
    }

    override fun abort() {
        stats.abort.incrementAndGet()
        delegate.abort()
    }
}

private class RuntimeTransport : AttachmentDownloadTransport {
    val calls = Collections.synchronizedList(mutableListOf<RuntimeCall>())

    override fun start(
        request: AttachmentDownloadRequest,
        callbacks: AttachmentDownloadTransportCallbacks
    ): AttachmentDownloadTransportCall = RuntimeCall(request, callbacks).also(calls::add)
}

private class RuntimeCall(
    private val request: AttachmentDownloadRequest,
    private val callbacks: AttachmentDownloadTransportCallbacks
) : AttachmentDownloadTransportCall {
    val cancelCount = AtomicInteger(0)

    override fun cancel() {
        cancelCount.incrementAndGet()
    }

    fun response(length: Long?) = callbacks.onResponseStart(200, length, mapOf("Content-Type" to listOf("application/octet-stream")))

    fun chunk(bytes: ByteArray) = callbacks.onChunk(bytes)

    fun complete() =
        callbacks.onComplete(
            AttachmentDownloadCompletion(statusCode = 200, headers = mapOf("Content-Type" to listOf("application/octet-stream")))
        )
}

private interface RuntimeEvidenceObserver : AttachmentDownloadObserver {
    val progress: MutableList<AttachmentDownloadProgress>
    val results: MutableList<AttachmentDownloadResult>
}

private class RuntimeObserver : RuntimeEvidenceObserver {
    override val progress = Collections.synchronizedList(mutableListOf<AttachmentDownloadProgress>())
    override val results = Collections.synchronizedList(mutableListOf<AttachmentDownloadResult>())
    private val terminal = CountDownLatch(1)

    override fun onProgress(progress: AttachmentDownloadProgress) {
        this.progress += progress
    }

    override fun onComplete(result: AttachmentDownloadResult) {
        results += result
        terminal.countDown()
    }

    fun awaitTerminal(): Boolean = terminal.await(2, TimeUnit.SECONDS)
}

private class GenerationFencedObserver : RuntimeEvidenceObserver {
    @Volatile
    var expectedAttemptToken: String = ""
    override val progress = Collections.synchronizedList(mutableListOf<AttachmentDownloadProgress>())
    override val results = Collections.synchronizedList(mutableListOf<AttachmentDownloadResult>())
    val staleCallbackCount = AtomicInteger(0)

    override fun onProgress(progress: AttachmentDownloadProgress) {
        if (progress.attemptToken != expectedAttemptToken) {
            staleCallbackCount.incrementAndGet()
            return
        }
        this.progress += progress
    }

    override fun onComplete(result: AttachmentDownloadResult) {
        if (result.attemptToken != expectedAttemptToken) {
            staleCallbackCount.incrementAndGet()
            return
        }
        results += result
    }
}

private class BlockingWriteSink(
    private val delegate: AttachmentDownloadAtomicSink,
    private val entered: CountDownLatch,
    private val release: CountDownLatch
) : AttachmentDownloadAtomicSink by delegate {
    override fun write(bytes: ByteArray): Boolean {
        entered.countDown()
        release.await(2, TimeUnit.SECONDS)
        return delegate.write(bytes)
    }
}

private class BlockingPublishSink(
    private val delegate: AttachmentDownloadAtomicSink,
    private val entered: CountDownLatch,
    private val release: CountDownLatch
) : AttachmentDownloadAtomicSink by delegate {
    override fun publish(): Boolean {
        entered.countDown()
        release.await(2, TimeUnit.SECONDS)
        return delegate.publish()
    }
}

private fun File.hasPartFiles(): Boolean = listFiles().orEmpty().any { it.name.contains(".part-") }

private fun waitForNoParts(directory: File) {
    repeat(100) {
        if (!directory.hasPartFiles()) return
        Thread.sleep(10L)
    }
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte -> "%02x".format(byte) }
