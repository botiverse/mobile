package build.raft.app.shared

import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAttachmentSystemDownloadEngineTest {
    private val root = Files.createTempDirectory("attachment-system-v2").toFile()
    private val principalA = "a".repeat(64)
    private val principalB = "b".repeat(64)

    @After fun tearDown() { root.deleteRecursively() }

    @Test
    fun crashAfterPreparedBeforeEnqueueRecoversExactlyOneJob() {
        val backend = FakeBackend()
        val store = FakeStore()
        val spec = spec(principalA)
        val crashed = engine(backend, store, afterPrepared = { throw SimulatedCrash() })
        runCatching { crashed.download(spec) }

        assertEquals(AndroidSystemDownloadPhase.Prepared, store.read(spec.targetFile.absolutePath)?.phase)
        assertEquals(0, backend.enqueueCount.get())

        backend.completeOnQuery = true
        val result = engine(backend, store).download(spec)
        assertEquals("complete", result.status)
        assertEquals(1, backend.enqueueCount.get())
        assertTrue(spec.targetFile.isFile)
    }

    @Test
    fun progressSamplesUseTransferSequenceAndPreserveKnownTotal() {
        val backend = FakeBackend()
        val store = FakeStore()
        val spec = spec(principalA)
        backend.scriptedQueries +=
            AndroidSystemDownloadRow(
                systemDownloadId = 1L,
                requestToken = "token-0",
                localPath = null,
                status = AndroidSystemDownloadStatus.Running,
                downloadedBytes = 69_765L,
                totalBytes = 48_535_109L
            )
        backend.scriptedQueries +=
            AndroidSystemDownloadRow(
                systemDownloadId = 1L,
                requestToken = "token-0",
                localPath = null,
                status = AndroidSystemDownloadStatus.Running,
                downloadedBytes = 24_267_554L,
                totalBytes = 48_535_109L
            )
        backend.scriptedQueries +=
            AndroidSystemDownloadRow(
                systemDownloadId = 1L,
                requestToken = "token-0",
                localPath = null,
                status = AndroidSystemDownloadStatus.Successful,
                downloadedBytes = 48_535_109L,
                totalBytes = 48_535_109L
            )
        val samples = mutableListOf<AndroidSystemDownloadProgress>()

        val result = engine(backend, store).download(spec, observer = samples::add)

        assertEquals("complete", result.status)
        assertEquals(listOf(1L, 2L, 3L), samples.map { it.sequence })
        assertEquals(listOf(48_535_109L, 48_535_109L, 48_535_109L), samples.map { it.totalBytes })
        assertEquals(listOf(1, 49, 100), samples.map { it.percent })
        assertEquals(1L, samples.first().toAndroidBridgePayload()["sequence"])
        assertEquals(48_535_109L, samples.first().toAndroidBridgePayload()["totalBytes"])
        assertEquals(1, samples.first().toAndroidBridgePayload()["percent"])
        assertEquals(1, samples.map { it.recordVersion }.distinct().size)
    }

    @Test
    fun crashAfterSystemRowBeforeCommitAdoptsTokenWithoutDoubleEnqueue() {
        val backend = FakeBackend()
        val store = FakeStore()
        val spec = spec(principalA)
        val crashed = engine(backend, store, afterEnqueue = { _, _ -> throw SimulatedCrash() })
        runCatching { crashed.download(spec) }

        assertEquals(1, backend.enqueueCount.get())
        assertEquals(AndroidSystemDownloadPhase.Prepared, store.read(spec.targetFile.absolutePath)?.phase)
        backend.completeOnQuery = true
        val result = engine(backend, store).download(spec)

        assertEquals("complete", result.status)
        assertEquals(1, backend.enqueueCount.get())
    }

    @Test
    fun concurrentRecoveryHasSingleExecutorAndSingleEnqueue() {
        val backend = FakeBackend()
        val store = FakeStore()
        val spec = spec(principalA)
        store.putIfAbsent(prepared(spec, "token"))
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        backend.beforeEnqueue = {
            entered.countDown()
            release.await(100, TimeUnit.MILLISECONDS)
        }
        val engine = engine(backend, store)
        val first = thread { engine.cacheState(spec) }
        val second = thread { engine.cacheState(spec) }
        release.countDown()
        first.join(2_000L)
        second.join(2_000L)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertEquals(1, backend.enqueueCount.get())
    }

    @Test
    fun duplicateTokenRowsConvergeToOneDeterministicWinner() {
        val backend = FakeBackend()
        val store = FakeStore()
        val spec = spec(principalA)
        val record = prepared(spec, "token")
        store.putIfAbsent(record)
        backend.rows += row(9L, "token", AndroidSystemDownloadStatus.Running, record.stagingPath)
        backend.rows += row(4L, "token", AndroidSystemDownloadStatus.Successful, record.stagingPath)
        File(record.stagingPath).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }

        val result = engine(backend, store).cacheState(spec)

        assertEquals("complete", result.status)
        assertEquals(listOf(9L, 4L), backend.removed)
        assertEquals(0, backend.enqueueCount.get())
    }

    @Test
    fun orphanTaggedRowIsRemovedWithoutForeignAdoption() {
        val backend = FakeBackend()
        val store = FakeStore()
        val orphan = File(root, "orphan.part").apply { writeText("orphan") }
        backend.rows += row(7L, "orphan-token", AndroidSystemDownloadStatus.Running, orphan.absolutePath)

        engine(backend, store).recoverAll()

        assertEquals(listOf(7L), backend.removed)
        assertFalse(orphan.exists())
        assertTrue(store.records().isEmpty())
    }

    @Test
    fun foreignPrincipalCannotObserveOrControlRecord() {
        val backend = FakeBackend()
        val store = FakeStore()
        val specA = spec(principalA)
        val record = prepared(specA, "token").copy(
            phase = AndroidSystemDownloadPhase.Enqueued,
            systemDownloadId = 3L,
            recordVersion = 2L
        )
        store.putIfAbsent(record)
        backend.rows += row(3L, "token", AndroidSystemDownloadStatus.Running, record.stagingPath)

        val foreign = engine(backend, store).cacheState(spec(principalB).copy(targetFile = specA.targetFile))
        val control = engine(backend, store).control(principalB, record.logicalDownloadId, record.attemptToken, 2L, "stop")

        assertEquals("owner_mismatch", foreign.status)
        assertEquals("missing", control)
        assertTrue(backend.removed.isEmpty())
        assertEquals(record, store.read(record.targetKey))
    }

    @Test
    fun oldStopCannotDeleteCommittedSuccessor() {
        val backend = FakeBackend()
        val store = FakeStore()
        val spec = spec(principalA)
        val old = prepared(spec, "old").copy(recordVersion = 2L, phase = AndroidSystemDownloadPhase.Enqueued, systemDownloadId = 1L)
        val successor = old.copy(requestToken = "new", attemptToken = "android-system-new", recordVersion = 3L, systemDownloadId = 2L)
        store.putIfAbsent(old)
        assertTrue(store.compareAndSet(old, successor))
        spec.targetFile.apply { parentFile?.mkdirs(); writeText("successor") }

        val status = engine(backend, store).control(principalA, old.logicalDownloadId, old.attemptToken, old.recordVersion, "stop")

        assertEquals("missing", status)
        assertEquals("successor", spec.targetFile.readText())
        assertEquals(successor, store.read(spec.targetFile.absolutePath))
    }

    @Test
    fun stopAndDeleteRequireExactAttemptAndRecordVersion() {
        val backend = FakeBackend()
        val store = FakeStore()
        val spec = spec(principalA)
        val current = prepared(spec, "token").copy(
            phase = AndroidSystemDownloadPhase.Enqueued,
            systemDownloadId = 1L,
            recordVersion = 2L
        )
        store.putIfAbsent(current)

        assertEquals("missing", engine(backend, store).control(principalA, current.logicalDownloadId, null, null, "stop"))
        assertEquals("missing", engine(backend, store).deleteTarget(principalA, spec.targetFile, null, null))
        assertEquals(current, store.read(current.targetKey))
    }

    @Test
    fun oldDeleteParkedBehindPublishCannotDeleteCommittedSuccessor() {
        val backend = FakeBackend().apply { completeOnQuery = true }
        val store = FakeStore()
        val spec = spec(principalA)
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val engine = engine(
            backend,
            store,
            beforePublish = {
                publishEntered.countDown()
                releasePublish.await(2, TimeUnit.SECONDS)
            }
        )
        var downloadResult: AndroidSystemDownloadResult? = null
        val downloadThread = thread { downloadResult = engine.download(spec) }
        assertTrue(publishEntered.await(2, TimeUnit.SECONDS))
        val enqueued = store.read(spec.targetFile.absolutePath)
        assertNotNull(enqueued)
        val exactEnqueued = enqueued!!
        var deleteResult: String? = null
        val deleteThread = thread {
            deleteResult = engine.deleteTarget(
                principalA,
                spec.targetFile,
                exactEnqueued.attemptToken,
                exactEnqueued.recordVersion
            )
        }

        releasePublish.countDown()
        downloadThread.join(2_000L)
        deleteThread.join(2_000L)

        assertEquals("complete", downloadResult?.status)
        assertEquals("missing", deleteResult)
        assertTrue(spec.targetFile.isFile)
        assertEquals(AndroidSystemDownloadPhase.Complete, store.read(spec.targetFile.absolutePath)?.phase)
    }

    @Test
    fun samePrincipalScopesObserveOneSuccessfulCompletion() {
        val backend = FakeBackend().apply { completeOnQuery = true }
        val store = FakeStore()
        val spec = spec(principalA)
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val firstPublish = AtomicInteger(0)
        val engine = engine(
            backend,
            store,
            beforePublish = {
                if (firstPublish.incrementAndGet() == 1) {
                    publishEntered.countDown()
                    releasePublish.await(2, TimeUnit.SECONDS)
                }
            }
        )
        var firstResult: AndroidSystemDownloadResult? = null
        var secondResult: AndroidSystemDownloadResult? = null
        val first = thread { firstResult = engine.download(spec) }
        assertTrue(publishEntered.await(2, TimeUnit.SECONDS))
        val second = thread { secondResult = engine.download(spec) }
        releasePublish.countDown()
        first.join(2_000L)
        second.join(2_000L)

        assertEquals("complete", firstResult?.status)
        assertEquals("complete", secondResult?.status)
        assertEquals(1, backend.enqueueCount.get())
        assertTrue(spec.targetFile.isFile)
    }

    @Test
    fun operationExactPreProgressCancelCannotPoisonSameIdentitySuccessor() {
        val backend = FakeBackend()
        val store = FakeStore()
        val operationA = spec(principalA, operationId = "operation-a")
        val preparedA = prepared(operationA, "operation-a-token")
        store.putIfAbsent(preparedA)
        val engine = engine(backend, store)

        assertEquals(
            "cancelled",
            engine.controlExisting(principalA, operationA.logicalDownloadId, "operation-a", "cancel")
        )
        assertEquals(null, store.read(operationA.targetFile.absolutePath))

        backend.completeOnQuery = true
        val operationB = operationA.copy(operationId = "operation-b")
        val successor = engine.download(operationB)

        assertEquals("complete", successor.status)
        assertEquals("operation-b", store.read(operationB.targetFile.absolutePath)?.operationId)
        assertEquals(
            "missing",
            engine.controlExisting(principalA, operationA.logicalDownloadId, "operation-a", "cancel")
        )
        assertTrue(operationB.targetFile.isFile)
    }

    @Test
    fun persistedForeignOperationIsCleanedBeforeSuccessorReservation() {
        val backend = FakeBackend().apply { completeOnQuery = true }
        val store = FakeStore()
        val operationA = spec(principalA, operationId = "operation-a")
        val enqueuedA =
            prepared(operationA, "operation-a-token").copy(
                phase = AndroidSystemDownloadPhase.Enqueued,
                systemDownloadId = 41L,
                recordVersion = 2L
            )
        File(enqueuedA.stagingPath).apply { parentFile?.mkdirs(); writeText("stale") }
        store.putIfAbsent(enqueuedA)
        backend.rows +=
            row(41L, enqueuedA.requestToken, AndroidSystemDownloadStatus.Running, enqueuedA.stagingPath)

        val operationB = operationA.copy(operationId = "operation-b")
        val result = engine(backend, store).download(operationB)

        assertEquals("complete", result.status)
        assertEquals(listOf(41L, 1L), backend.removed)
        assertFalse(File(enqueuedA.stagingPath).exists())
        assertEquals("operation-b", store.read(operationB.targetFile.absolutePath)?.operationId)
        assertTrue(operationB.targetFile.isFile)
    }

    private fun engine(
        backend: FakeBackend,
        store: FakeStore,
        afterPrepared: (AndroidSystemDownloadRecord) -> Unit = {},
        afterEnqueue: (AndroidSystemDownloadRecord, Long) -> Unit = { _, _ -> },
        beforePublish: (AndroidSystemDownloadRecord) -> Unit = {}
    ) = AndroidAttachmentSystemDownloadEngine(
        backend = backend,
        recordStore = store,
        tokenFactory = { "token-${backend.enqueueCount.get()}" },
        sleepMillis = {},
        pollIntervalMillis = 0L,
        afterPreparedPersisted = afterPrepared,
        afterEnqueueCreated = afterEnqueue,
        beforePublishCommit = beforePublish
    )

    private fun spec(principal: String, operationId: String = "") = AndroidSystemDownloadSpec(
        principalKey = principal,
        logicalDownloadId = "download",
        operationId = operationId,
        targetFile = File(root, "$principal/target.bin"),
        stagingDirectory = File(root, "system-staging"),
        url = "https://example.test/file",
        displayName = "file.bin",
        mimeType = "application/octet-stream"
    )

    private fun prepared(spec: AndroidSystemDownloadSpec, token: String) = AndroidSystemDownloadRecord(
        targetKey = spec.targetFile.absolutePath,
        principalKey = spec.principalKey,
        logicalDownloadId = spec.logicalDownloadId,
        operationId = spec.operationId,
        requestToken = token,
        attemptToken = "android-system-$token",
        recordVersion = 1L,
        phase = AndroidSystemDownloadPhase.Prepared,
        url = spec.url,
        displayName = spec.displayName,
        mimeType = spec.mimeType,
        stagingPath = File(spec.targetFile.parentFile, ".target.$token.part").absolutePath
    )

    private fun row(id: Long, token: String, status: AndroidSystemDownloadStatus, path: String) =
        AndroidSystemDownloadRow(id, token, path, status, downloadedBytes = 3L, totalBytes = 3L)
}

private class SimulatedCrash : RuntimeException()

private class FakeStore : AndroidSystemDownloadRecordStore {
    private val values = ConcurrentHashMap<String, AndroidSystemDownloadRecord>()
    override fun records() = values.values.toList()
    override fun read(targetKey: String) = values[targetKey]
    override fun putIfAbsent(record: AndroidSystemDownloadRecord) = values.putIfAbsent(record.targetKey, record) ?: record
    override fun compareAndSet(expected: AndroidSystemDownloadRecord, updated: AndroidSystemDownloadRecord) =
        values.replace(expected.targetKey, expected, updated)
    override fun removeIfExact(expected: AndroidSystemDownloadRecord) = values.remove(expected.targetKey, expected)
}

private class FakeBackend : AndroidSystemDownloadBackend {
    val rows = mutableListOf<AndroidSystemDownloadRow>()
    val scriptedQueries = mutableListOf<AndroidSystemDownloadRow>()
    val removed = mutableListOf<Long>()
    val enqueueCount = AtomicInteger(0)
    var completeOnQuery = false
    var beforeEnqueue: () -> Unit = {}

    override fun enqueue(request: AndroidSystemDownloadEnqueueRequest): Long {
        beforeEnqueue()
        val id = enqueueCount.incrementAndGet().toLong()
        File(request.stagingPath).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        rows += row(id, request.requestToken, AndroidSystemDownloadStatus.Running, request.stagingPath)
        return id
    }

    override fun taggedRows(): List<AndroidSystemDownloadRow> = synchronized(rows) { rows.toList() }

    override fun query(systemDownloadId: Long): AndroidSystemDownloadRow {
        if (scriptedQueries.isNotEmpty()) return scriptedQueries.removeAt(0)
        val current = synchronized(rows) { rows.firstOrNull { it.systemDownloadId == systemDownloadId } }
            ?: return AndroidSystemDownloadRow(systemDownloadId, "", null, AndroidSystemDownloadStatus.Missing)
        return if (completeOnQuery) current.copy(status = AndroidSystemDownloadStatus.Successful) else current
    }

    override fun remove(systemDownloadId: Long) {
        removed += systemDownloadId
        synchronized(rows) { rows.removeAll { it.systemDownloadId == systemDownloadId } }
    }

    private fun row(id: Long, token: String, status: AndroidSystemDownloadStatus, path: String) =
        AndroidSystemDownloadRow(id, token, path, status, downloadedBytes = 3L, totalBytes = 3L)
}
