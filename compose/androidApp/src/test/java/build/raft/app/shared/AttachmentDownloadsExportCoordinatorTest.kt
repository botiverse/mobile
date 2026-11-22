package build.raft.app.shared

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentDownloadsExportCoordinatorTest {
    @Test
    fun ownerSwitchBeforeRegistrationRejectsOldExportAndFreshOwnerPublishes() =
        withSourceFile(4096) { source ->
            val registry = AndroidAttachmentOwnerRegistry()
            val old = ownerLease("scope-a", 1L)
            // Another account signs in on the shell (a server switch keeps exports: see below).
            val fresh = ownerLease("scope-b", 2L, authEpoch = 2L)
            val registrationReady = CountDownLatch(1)
            val releaseRegistration = CountDownLatch(1)
            registry.bind(old)
            val coordinator =
                coordinator(
                    ownerRegistry = registry,
                    beforeOwnerRegistration = {
                        if (Thread.currentThread().name == "old-export") {
                            registrationReady.countDown()
                            releaseRegistration.await()
                        }
                    }
                )
            val oldBackend = FakeExportBackend()
            val oldResult = AtomicReference<AttachmentDownloadsExportResult>()
            val oldThread = thread(name = "old-export") {
                oldResult.set(
                    coordinator.export(
                        request(source, "old-owner").copy(owner = old),
                        oldBackend
                    )
                )
            }
            assertTrue(registrationReady.await(2, TimeUnit.SECONDS))
            registry.bind(fresh)
            releaseRegistration.countDown()
            oldThread.join(2_000L)

            assertFalse(oldThread.isAlive)
            assertEquals("cancelled", oldResult.get()?.status)
            assertEquals(0, oldBackend.insertCount.get())
            assertEquals(0, oldBackend.publishCount.get())

            val freshBackend = FakeExportBackend()
            val freshResult =
                coordinator.export(request(source, "fresh-owner").copy(owner = fresh), freshBackend)
            assertEquals("saved", freshResult.status)
            assertEquals(1, freshBackend.insertCount.get())
            assertEquals(1, freshBackend.publishCount.get())
        }

    @Test
    fun serverSwitchDuringExportLetsItPublish() =
        withSourceFile(1024 * 1024) { source ->
            val registry = AndroidAttachmentOwnerRegistry()
            val serverA = ownerLease("scope-a", 1L)
            val serverB = ownerLease("scope-b", 2L)
            val writeEntered = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            registry.bind(serverA)
            val backend = FakeExportBackend(writeEntered = writeEntered, writeBlock = releaseWrite)
            val coordinator = coordinator(ownerRegistry = registry, stageTimeoutMs = 5_000L)
            val result = AtomicReference<AttachmentDownloadsExportResult>()
            val exportThread = thread {
                result.set(coordinator.export(request(source, "server-switch").copy(owner = serverA), backend))
            }
            assertTrue(writeEntered.await(2, TimeUnit.SECONDS))

            // Same account, another server: the save keeps going (artin, 1.15.5).
            registry.bind(serverB)
            releaseWrite.countDown()
            exportThread.join(2_000L)

            assertFalse(exportThread.isAlive)
            assertEquals("saved", result.get()?.status)
            assertEquals(1, backend.publishCount.get())
            assertEquals(0, backend.deleteCount.get())
        }

    @Test
    fun cacheHit34MbExportsExactBytesAndPublishesOnce() = withSourceFile(34 * 1024 * 1024) { source ->
        val sourceLengthBefore = source.length()
        val sourceHashBefore = source.sha256()
        val sourceModifiedBefore = source.lastModified()
        val backend = FakeExportBackend()
        val progress = Collections.synchronizedList(mutableListOf<AttachmentDownloadsExportProgress>())
        val result = coordinator().export(request(source, "download-success"), backend, progress::add)

        assertEquals("saved", result.status)
        assertEquals(sourceLengthBefore, result.sizeBytes)
        assertEquals(sourceHashBefore, backend.bytes().sha256())
        assertEquals(1, backend.insertCount.get())
        assertEquals(1, backend.openCount.get())
        assertEquals(1, backend.publishCount.get())
        assertEquals(0, backend.deleteCount.get())
        assertEquals(listOf("export_insert", "export_open", "export_copy", "export_publish"), progress.map { it.phase }.distinct())
        assertTrue(progress.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
        assertTrue(source.exists())
        assertEquals(sourceLengthBefore, source.length())
        assertEquals(sourceHashBefore, source.sha256())
        assertEquals(sourceModifiedBefore, source.lastModified())
    }

    @Test
    fun blockedWriteCanBeCancelledAndNeverPublishes() = withSourceFile(1024 * 1024) { source ->
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val backend = FakeExportBackend(writeEntered = writeEntered, writeBlock = releaseWrite)
        val coordinator = coordinator(stageTimeoutMs = 5_000L)
        var result: AttachmentDownloadsExportResult? = null
        val exportThread = thread {
            result = coordinator.export(request(source, "download-cancel"), backend)
        }
        assertTrue(writeEntered.await(2, TimeUnit.SECONDS))
        assertEquals("cancelled", coordinator.control("download-cancel", null, "cancel"))
        exportThread.join(1_000L)
        assertFalse(exportThread.isAlive)
        assertEquals("cancelled", result?.status)
        releaseWrite.countDown()
        assertTrue(backend.awaitDelete())
        assertEquals(AttachmentDownloadsRowState.Missing, backend.rowState("content://downloads/1"))
        assertEquals(0, backend.publishCount.get())
        assertTrue(source.exists())
    }

    @Test
    fun publishFailureDeletesPendingRowAndKeepsCache() = withSourceFile(4096) { source ->
        val backend = FakeExportBackend(publishResult = false)
        val result = coordinator().export(request(source, "download-publish-fail"), backend)

        assertEquals("storage_failed", result.status)
        assertEquals("export_publish", result.failurePhase)
        assertTrue(backend.awaitDelete())
        assertEquals(AttachmentDownloadsRowState.Missing, backend.rowState("content://downloads/1"))
        assertEquals(1, backend.publishCount.get())
        assertTrue(source.exists())
    }

    @Test
    fun doubleClickJoinsSingleFlightAndBothGenerationsReceiveTerminal() = withSourceFile(1024 * 1024) { source ->
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val backend = FakeExportBackend(writeEntered = writeEntered, writeBlock = releaseWrite)
        val coordinator = coordinator()
        val firstProgress = Collections.synchronizedList(mutableListOf<AttachmentDownloadsExportProgress>())
        val secondProgress = Collections.synchronizedList(mutableListOf<AttachmentDownloadsExportProgress>())
        var first: AttachmentDownloadsExportResult? = null
        var second: AttachmentDownloadsExportResult? = null
        val firstThread = thread {
            first = coordinator.export(request(source, "download-a"), backend, firstProgress::add)
        }
        assertTrue(writeEntered.await(2, TimeUnit.SECONDS))
        val secondThread = thread {
            second = coordinator.export(request(source, "download-b"), backend, secondProgress::add)
        }
        waitUntil { secondProgress.isNotEmpty() }
        assertEquals(1, backend.insertCount.get())
        assertEquals(1, backend.openCount.get())
        releaseWrite.countDown()
        firstThread.join(2_000L)
        secondThread.join(2_000L)

        assertEquals("saved", first?.status)
        assertEquals("saved", second?.status)
        assertEquals(1, backend.publishCount.get())
        assertEquals(setOf("download-a"), firstProgress.map { it.downloadId }.toSet())
        assertEquals(setOf("download-b"), secondProgress.map { it.downloadId }.toSet())
        assertNotNull(firstProgress.firstOrNull()?.attemptToken)
        assertNotNull(secondProgress.firstOrNull()?.attemptToken)
        assertTrue(source.exists())
    }

    private fun coordinator(
        stageTimeoutMs: Long = 2_000L,
        cleanupAttempts: Int = 3,
        deferredCleanupDelayMs: Long = 5_000L,
        insertAdmissionWaitMs: Long = 2_000L,
        worker: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "attachment-export-test-worker").apply { isDaemon = true }
        },
        terminalWorker: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "attachment-export-test-terminal").apply { isDaemon = true }
        },
        afterInsertOwnerAcquired: () -> Unit = {},
        beforeOwnerRegistration: () -> Unit = {},
        afterQueuedReservation: () -> Unit = {},
        ownerRegistry: AndroidAttachmentOwnerRegistry = ProcessAndroidAttachmentOwners
    ) =
        AttachmentDownloadsExportCoordinator(
            worker = worker,
            terminalWorker = terminalWorker,
            stageTimeoutMs = stageTimeoutMs,
            wholeTimeoutMs = 5_000L,
            copyBufferBytes = 64 * 1024,
            progressCadenceMs = 0L,
            cleanupAttempts = cleanupAttempts,
            insertAdmissionWaitMs = insertAdmissionWaitMs,
            deferredCleanupDelayMs = deferredCleanupDelayMs,
            afterInsertOwnerAcquired = afterInsertOwnerAcquired,
            beforeOwnerRegistration = beforeOwnerRegistration,
            afterQueuedReservation = afterQueuedReservation,
            ownerRegistry = ownerRegistry
        )

    private fun request(source: File, downloadId: String, operationId: String = "") =
        AttachmentDownloadsExportRequest(
            downloadId = downloadId,
            operationId = operationId,
            source = source,
            displayName = "fixture.bin",
            mimeType = "application/octet-stream"
        )

    private fun ownerLease(scopeId: String, generation: Long, authEpoch: Long = 1L) =
        AndroidAttachmentOwnerLease(
            shellInstanceId = "shell",
            scopeId = scopeId,
            authEpoch = authEpoch,
            principalKey = "a".repeat(64),
            generation = generation
        )

    private fun withSourceFile(size: Int, block: (File) -> Unit) {
        val directory = Files.createTempDirectory("attachment-export-test").toFile()
        val source = directory.resolve("cached.bin")
        source.outputStream().buffered().use { output ->
            val chunk = ByteArray(64 * 1024) { index -> (index % 251).toByte() }
            var remaining = size
            while (remaining > 0) {
                val count = minOf(remaining, chunk.size)
                output.write(chunk, 0, count)
                remaining -= count
            }
        }
        try {
            block(source)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10L)
        assertTrue(predicate())
    }
}

private class FakeExportBackend(
    private val insertEntered: CountDownLatch? = null,
    private val insertBlock: CountDownLatch? = null,
    private val ignoreInsertInterrupt: Boolean = false,
    private val openEntered: CountDownLatch? = null,
    private val openBlock: CountDownLatch? = null,
    private val ignoreOpenInterrupt: Boolean = false,
    private val writeEntered: CountDownLatch? = null,
    private val writeBlock: CountDownLatch? = null,
    private val ignoreWriteInterrupt: Boolean = false,
    private val failWrite: Boolean = false,
    private val publishResult: Boolean = true,
    private val publishEntered: CountDownLatch? = null,
    private val publishBlock: CountDownLatch? = null,
    private val ignorePublishInterrupt: Boolean = false,
    private val publishBeforeBlock: Boolean = false,
    private val failedDeleteAttempts: Int = 0,
    private val unknownQueryAttempts: Int = 0,
    queryBarrierCount: Int = 0
) : AttachmentDownloadsExportBackend {
    val insertCount = AtomicInteger(0)
    val openCount = AtomicInteger(0)
    val writeCount = AtomicInteger(0)
    val publishCount = AtomicInteger(0)
    val deleteCount = AtomicInteger(0)
    private val deleted = CountDownLatch(1)
    private val openReturned = CountDownLatch(1)
    private val writeReturned = CountDownLatch(1)
    private val output = ByteArrayOutputStream()
    private val rowState = java.util.concurrent.atomic.AtomicReference(AttachmentDownloadsRowState.Missing)
    private val queryCount = AtomicInteger(0)
    private val queryBarrier = CountDownLatch(queryBarrierCount)

    override fun insert(displayName: String, mimeType: String): String {
        insertCount.incrementAndGet()
        insertEntered?.countDown()
        insertBlock?.awaitPossiblyIgnoringInterrupt(ignoreInsertInterrupt)
        rowState.set(AttachmentDownloadsRowState.Pending)
        return "content://downloads/1"
    }

    override fun openOutput(uri: String): OutputStream {
        openCount.incrementAndGet()
        openEntered?.countDown()
        openBlock?.awaitPossiblyIgnoringInterrupt(ignoreOpenInterrupt)
        openReturned.countDown()
        return object : OutputStream() {
            override fun write(value: Int) {
                write(byteArrayOf(value.toByte()), 0, 1)
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                writeCount.incrementAndGet()
                writeEntered?.countDown()
                writeBlock?.awaitPossiblyIgnoringInterrupt(ignoreWriteInterrupt)
                if (failWrite) throw java.io.IOException("fixture write failure")
                output.write(bytes, offset, length)
                writeReturned.countDown()
            }
        }
    }

    override fun publish(uri: String): Boolean {
        publishCount.incrementAndGet()
        if (publishBeforeBlock && publishResult) {
            rowState.compareAndSet(AttachmentDownloadsRowState.Pending, AttachmentDownloadsRowState.Published)
        }
        publishEntered?.countDown()
        if (publishBlock != null) {
            if (ignorePublishInterrupt) {
                while (true) {
                    try {
                        publishBlock.await()
                        break
                    } catch (_: InterruptedException) {
                        // Model a ContentResolver call that ignores Future.cancel(true).
                    }
                }
            } else {
                publishBlock.await()
            }
        }
        if (!publishResult) return false
        if (publishBeforeBlock) return rowState.get() == AttachmentDownloadsRowState.Published
        return rowState.compareAndSet(AttachmentDownloadsRowState.Pending, AttachmentDownloadsRowState.Published)
    }

    override fun deletePending(uri: String): Boolean {
        val attempt = deleteCount.incrementAndGet()
        if (attempt <= failedDeleteAttempts) return false
        val result = rowState.compareAndSet(AttachmentDownloadsRowState.Pending, AttachmentDownloadsRowState.Missing)
        if (result) deleted.countDown()
        return result
    }

    override fun rowState(uri: String): AttachmentDownloadsRowState {
        val query = queryCount.incrementAndGet()
        queryBarrier.countDown()
        return if (query <= unknownQueryAttempts) {
            AttachmentDownloadsRowState.Unknown
        } else {
            rowState.get()
        }
    }

    fun bytes(): ByteArray = output.toByteArray()
    fun awaitDelete(): Boolean = deleted.await(2, TimeUnit.SECONDS)
    fun awaitOpenReturned(): Boolean = openReturned.await(2, TimeUnit.SECONDS)
    fun awaitWriteReturned(): Boolean = writeReturned.await(2, TimeUnit.SECONDS)
    fun awaitQueryBarrier(): Boolean = queryBarrier.await(2, TimeUnit.SECONDS)
}

private fun CountDownLatch.awaitPossiblyIgnoringInterrupt(ignoreInterrupt: Boolean) {
    if (!ignoreInterrupt) {
        await()
        return
    }
    while (true) {
        try {
            await()
            return
        } catch (_: InterruptedException) {
            // Model ContentResolver/OutputStream calls that do not honor Future.cancel(true).
        }
    }
}

private fun File.sha256(): String = inputStream().use { it.sha256() }

private fun ByteArray.sha256(): String = inputStream().use { it.sha256() }

private fun java.io.InputStream.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
