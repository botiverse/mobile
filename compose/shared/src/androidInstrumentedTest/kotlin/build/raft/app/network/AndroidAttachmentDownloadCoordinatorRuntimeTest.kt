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

private fun File.hasPartFiles(): Boolean = listFiles().orEmpty().any { it.name.contains(".part-") }

private fun waitForNoParts(directory: File) {
    repeat(100) {
        if (!directory.hasPartFiles()) return
        Thread.sleep(10L)
    }
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte -> "%02x".format(byte) }
