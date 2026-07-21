package build.raft.app.shared

import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAttachmentSystemDownloadEngineTest {
    private val root = Files.createTempDirectory("attachment-system-download").toFile()
    private val ownerA = androidAttachmentOwnerKey("account-a|server-1")
    private val ownerB = androidAttachmentOwnerKey("account-b|server-1")

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun systemDownloadPublishesAtomicallyAndClearsDurableRecord() {
        val backend = FakeBackend()
        val records = FakeRecordStore()
        val engine = engine(backend, records)
        val target = File(root, "cache/report.pdf")

        backend.onQuery = { AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Running, 10L, 100L) }
        var polls = 0
        val result =
            engine.download(
                "page-attempt-1",
                ownerA,
                target,
                "https://example.test/report",
                "report.pdf",
                "application/pdf"
            ) {
                polls += 1
                if (polls == 1) {
                    val reused = records.read(target.absolutePath)
                    assertEquals(1L, reused?.systemDownloadId)
                    backend.onQuery = {
                        File(reused!!.stagingPath).apply {
                            parentFile?.mkdirs()
                            writeBytes(byteArrayOf(1, 2, 3))
                        }
                        AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Successful, 3L, 3L)
                    }
                }
            }

        assertEquals("complete", result.status)
        assertEquals(1, backend.enqueueCount)
        assertEquals(listOf<Byte>(1, 2, 3), target.readBytes().toList())
        assertEquals(null, records.read(target.absolutePath))
    }

    @Test
    fun recreatedEngineRecoversCompletedDownloadAndPublishesCache() {
        val backend = FakeBackend()
        val records = FakeRecordStore()
        val target = File(root, "cache/archive.zip")
        val staging = File(root, "staging/archive.zip").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(4, 5, 6, 7))
        }
        records.write(
            AndroidSystemDownloadRecord(
                systemDownloadId = 42L,
                targetKey = target.absolutePath,
                stagingPath = staging.absolutePath,
                mimeType = "application/zip",
                attemptToken = "android-system-42",
                ownerKey = ownerA
            )
        )
        backend.snapshots[42L] = AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Successful, 4L, 4L)

        val recovered = engine(backend, records).cacheState(target, ownerA)

        assertTrue(recovered.cached)
        assertFalse(recovered.active)
        assertEquals(4L, recovered.sizeBytes)
        assertEquals(listOf<Byte>(4, 5, 6, 7), target.readBytes().toList())
        assertEquals(null, records.read(target.absolutePath))
        assertEquals(listOf(42L), backend.removed)
    }

    @Test
    fun newPageCallerReusesPersistedRunningDownload() {
        val backend = FakeBackend()
        val records = FakeRecordStore()
        val target = File(root, "cache/manual.pdf")
        val staging = File(root, "staging/manual.pdf")
        val record =
            AndroidSystemDownloadRecord(
                systemDownloadId = 77L,
                targetKey = target.absolutePath,
                stagingPath = staging.absolutePath,
                mimeType = "application/pdf",
                attemptToken = "android-system-77",
                ownerKey = ownerA
            )
        records.write(record)
        backend.snapshots[77L] = AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Running, 5L, 10L)
        var progressEvents = 0

        val result =
            engine(backend, records).download(
                "stable-page-id",
                ownerA,
                target,
                "https://example.test/fresh-signed-url",
                "manual.pdf",
                "application/pdf"
            ) {
                progressEvents += 1
                staging.apply {
                    parentFile?.mkdirs()
                    writeBytes(byteArrayOf(7, 7))
                }
                backend.snapshots[77L] = AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Successful, 2L, 2L)
            }

        assertEquals("complete", result.status)
        assertEquals(0, backend.enqueueCount)
        assertEquals(1, progressEvents)
        assertEquals(listOf<Byte>(7, 7), target.readBytes().toList())
    }

    @Test
    fun explicitStopCancelsButObserverDetachmentDoesNotOwnTransfer() {
        val backend = FakeBackend()
        val records = FakeRecordStore()
        val engine = engine(backend, records)
        val target = File(root, "cache/video.mp4")
        backend.onQuery = { AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Running, 1L, 10L) }
        var stopped = false

        val result =
            engine.download(
                "stable-logical-id",
                ownerA,
                target,
                "https://example.test/video",
                "video.mp4",
                "video/mp4"
            ) {
                if (!stopped) {
                    stopped = true
                    assertEquals("cancelled", engine.control("stable-logical-id", ownerA, it.attemptToken, "stop"))
                }
            }

        assertEquals("cancelled", result.status)
        assertEquals(listOf(1L), backend.removed)
        assertFalse(target.exists())
    }

    @Test
    fun activePersistedDownloadIsReportedWithoutTreatingPartialBytesAsCache() {
        val backend = FakeBackend()
        val records = FakeRecordStore()
        val target = File(root, "cache/large.bin")
        val staging = File(root, "staging/large.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9))
        }
        records.write(
            AndroidSystemDownloadRecord(
                systemDownloadId = 9L,
                targetKey = target.absolutePath,
                stagingPath = staging.absolutePath,
                mimeType = "application/octet-stream",
                attemptToken = "android-system-9",
                ownerKey = ownerA
            )
        )
        backend.snapshots[9L] = AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Running, 1L, 100L)

        val state = engine(backend, records).cacheState(target, ownerA)

        assertFalse(state.cached)
        assertTrue(state.active)
        assertEquals(0L, state.sizeBytes)
        assertFalse(target.exists())
    }

    @Test
    fun downloadAndCacheStateSimultaneousSuccessPublishesOnce() {
        val backend = FakeBackend()
        val records = FakeRecordStore()
        val target = File(root, "cache/simultaneous.pdf")
        val staging = File(root, "staging/simultaneous.pdf").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 3, 5, 7))
        }
        val record = record(21L, target, staging, ownerA)
        records.write(record)
        val successfulQueries = AtomicInteger()
        val simultaneousTerminalQueries = CountDownLatch(2)
        val downloadTerminalQueryEntered = CountDownLatch(1)
        backend.onQuery = { id ->
            assertEquals(21L, id)
            val query = successfulQueries.incrementAndGet()
            if (query >= 2) {
                if (query == 2) downloadTerminalQueryEntered.countDown()
                simultaneousTerminalQueries.countDown()
                assertTrue(simultaneousTerminalQueries.await(2, TimeUnit.SECONDS))
            }
            AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Successful, 4L, 4L)
        }
        val publishCount = AtomicInteger()
        val engine = engine(backend, records, beforePublishCommit = { publishCount.incrementAndGet() })
        val executor = Executors.newFixedThreadPool(2)

        try {
            val download = executor.submit<AndroidAttachmentSystemResult> {
                engine.download(
                    "simultaneous",
                    ownerA,
                    target,
                    "https://example.test/simultaneous",
                    "simultaneous.pdf",
                    "application/pdf"
                )
            }
            assertTrue(downloadTerminalQueryEntered.await(2, TimeUnit.SECONDS))
            val cache = executor.submit<AndroidAttachmentSystemCacheState> {
                engine.cacheState(target, ownerA, "simultaneous")
            }

            assertEquals("complete", download.get(3, TimeUnit.SECONDS).status)
            assertTrue(cache.get(3, TimeUnit.SECONDS).cached)
            assertEquals(1, publishCount.get())
            assertEquals(listOf(21L), backend.removed)
            assertEquals(listOf<Byte>(1, 3, 5, 7), target.readBytes().toList())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun stopWaitsForParkedPublishThenDeletesFinalTarget() {
        val backend = FakeBackend()
        val records = FakeRecordStore()
        val target = File(root, "cache/stop-race.bin")
        val staging = File(root, "staging/stop-race.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(2, 4, 6))
        }
        val record = record(31L, target, staging, ownerA)
        records.write(record)
        backend.snapshots[31L] = AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Successful, 3L, 3L)
        val publishEntered = CountDownLatch(1)
        val allowPublish = CountDownLatch(1)
        val engine =
            engine(
                backend,
                records,
                beforePublishCommit = {
                    publishEntered.countDown()
                    assertTrue(allowPublish.await(2, TimeUnit.SECONDS))
                }
            )
        backend.snapshots[31L] = AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Running, 1L, 3L)
        assertTrue(engine.cacheState(target, ownerA, "stop-race").active)
        backend.snapshots[31L] = AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Successful, 3L, 3L)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val download = executor.submit<AndroidAttachmentSystemResult> {
                engine.download(
                    "stop-race",
                    ownerA,
                    target,
                    "https://example.test/stop",
                    "stop-race.bin",
                    "application/octet-stream"
                )
            }
            assertTrue(publishEntered.await(2, TimeUnit.SECONDS))
            val stopStarted = CountDownLatch(1)
            val stop = executor.submit<String> {
                stopStarted.countDown()
                engine.control("stop-race", ownerA, record.attemptToken, "stop")
            }
            assertTrue(stopStarted.await(2, TimeUnit.SECONDS))
            assertFalse(stop.isDone)
            allowPublish.countDown()

            assertEquals("complete", download.get(3, TimeUnit.SECONDS).status)
            assertEquals("cancelled", stop.get(3, TimeUnit.SECONDS))
            assertFalse(target.exists())
            assertEquals(listOf(31L), backend.removed)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun deleteWaitsForParkedPublishAndCannotResurrectTarget() {
        val backend = FakeBackend()
        val records = FakeRecordStore()
        val target = File(root, "cache/delete-race.bin")
        val staging = File(root, "staging/delete-race.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(8, 6, 4, 2))
        }
        records.write(record(41L, target, staging, ownerA))
        backend.snapshots[41L] = AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Successful, 4L, 4L)
        val publishEntered = CountDownLatch(1)
        val allowPublish = CountDownLatch(1)
        val engine =
            engine(
                backend,
                records,
                beforePublishCommit = {
                    publishEntered.countDown()
                    assertTrue(allowPublish.await(2, TimeUnit.SECONDS))
                }
            )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val recover = executor.submit<AndroidAttachmentSystemCacheState> {
                engine.cacheState(target, ownerA, "delete-race")
            }
            assertTrue(publishEntered.await(2, TimeUnit.SECONDS))
            val deleteStarted = CountDownLatch(1)
            val delete = executor.submit<Boolean> {
                deleteStarted.countDown()
                engine.deleteTarget(target, ownerA)
            }
            assertTrue(deleteStarted.await(2, TimeUnit.SECONDS))
            assertFalse(delete.isDone)
            allowPublish.countDown()

            assertTrue(recover.get(3, TimeUnit.SECONDS).cached)
            assertTrue(delete.get(3, TimeUnit.SECONDS))
            assertFalse(target.exists())
            assertFalse(File(target.parentFile, "${target.name}.system-publish").exists())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun oldAttemptTerminalCannotRemoveSuccessor() {
        val backend = FakeBackend()
        val records = FakeRecordStore()
        val target = File(root, "cache/replacement.bin")
        val firstSleepEntered = CountDownLatch(1)
        val secondSleepEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val sleepCalls = AtomicInteger()
        val engine =
            engine(
                backend,
                records,
                sleepMillis = {
                    when (sleepCalls.incrementAndGet()) {
                        1 -> {
                            firstSleepEntered.countDown()
                            assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                        }
                        2 -> {
                            secondSleepEntered.countDown()
                            assertTrue(releaseSecond.await(2, TimeUnit.SECONDS))
                        }
                    }
                }
            )
        backend.onQuery = { id ->
            backend.snapshots[id] ?: AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Missing)
        }
        val executor = Executors.newFixedThreadPool(2)

        try {
            val old = executor.submit<AndroidAttachmentSystemResult> {
                engine.download(
                    "old-attempt",
                    ownerA,
                    target,
                    "https://example.test/old",
                    "replacement.bin",
                    "application/octet-stream"
                )
            }
            backend.snapshots[1L] = AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Running, 1L, 10L)
            assertTrue(firstSleepEntered.await(2, TimeUnit.SECONDS))
            val oldRecord = records.read(target.absolutePath)!!
            assertEquals("cancelled", engine.control("old-attempt", ownerA, oldRecord.attemptToken, "stop"))

            val successor = executor.submit<AndroidAttachmentSystemResult> {
                engine.download(
                    "successor-attempt",
                    ownerA,
                    target,
                    "https://example.test/new",
                    "replacement.bin",
                    "application/octet-stream"
                )
            }
            backend.snapshots[2L] = AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Running, 2L, 10L)
            assertTrue(secondSleepEntered.await(2, TimeUnit.SECONDS))
            val successorRecord = records.read(target.absolutePath)!!
            assertEquals(2L, successorRecord.systemDownloadId)

            releaseFirst.countDown()
            assertEquals("cancelled", old.get(3, TimeUnit.SECONDS).status)
            assertEquals(successorRecord, records.read(target.absolutePath))
            assertFalse(2L in backend.removed)

            File(successorRecord.stagingPath).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(9, 9))
            }
            backend.snapshots[2L] = AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Successful, 2L, 2L)
            releaseSecond.countDown()
            assertEquals("complete", successor.get(3, TimeUnit.SECONDS).status)
            assertEquals(listOf<Byte>(9, 9), target.readBytes().toList())
        } finally {
            releaseFirst.countDown()
            releaseSecond.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun foreignOwnerCannotObservePublishCancelOrReuseSameTarget() {
        val backend = FakeBackend()
        val records = FakeRecordStore()
        val target = File(root, "cache/foreign.bin")
        val staging = File(root, "staging/foreign.bin").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(7, 8))
        }
        val owned = record(51L, target, staging, ownerA)
        records.write(owned)
        backend.snapshots[51L] = AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Running, 1L, 2L)
        val engine = engine(backend, records)

        val foreignState = engine.cacheState(target, ownerB, "same-logical-id")
        val foreignDownload =
            engine.download(
                "same-logical-id",
                ownerB,
                target,
                "https://example.test/foreign",
                "foreign.bin",
                "application/octet-stream"
            )

        assertFalse(foreignState.cached)
        assertFalse(foreignState.active)
        assertEquals("owner_mismatch", foreignDownload.errorKind)
        assertEquals("missing", engine.control("same-logical-id", ownerB, owned.attemptToken, "stop"))
        assertFalse(engine.deleteTarget(target, ownerB))
        assertEquals(0, backend.enqueueCount)
        assertTrue(backend.removed.isEmpty())
        assertEquals(owned, records.read(target.absolutePath))
        assertTrue(staging.exists())

        val ownerState = engine.cacheState(target, ownerA, "same-logical-id")
        assertTrue(ownerState.active)
        assertEquals("missing", engine.control("same-logical-id", ownerB, owned.attemptToken, "stop"))
        assertEquals(owned, records.read(target.absolutePath))
    }

    @Test
    fun ownerFingerprintProducesDistinctPersistedKeys() {
        assertTrue(ownerA.isNotBlank())
        assertTrue(ownerB.isNotBlank())
        assertEquals(64, ownerA.length)
        assertEquals(64, ownerB.length)
        assertFalse(ownerA == ownerB)
        assertEquals(ownerA, androidAttachmentOwnerKey("account-a|server-1"))
    }

    @Test
    fun nativeOwnerFenceRejectsOldAAfterSwitchToBAndBackToA() {
        val fence = AndroidAttachmentOwnerFence()

        fence.update(ownerA, 1L)
        assertTrue(fence.allows(ownerA, 1L))
        fence.update(ownerB, 2L)
        assertFalse(fence.allows(ownerA, 1L))
        fence.update(ownerA, 1L)
        assertFalse(fence.allows(ownerA, 1L))
        fence.update(ownerA, 3L)

        assertFalse(fence.allows(ownerA, 1L))
        assertTrue(fence.allows(ownerA, 3L))
    }

    @Test
    fun nativeOwnerFenceRejectsParkedOldLaunchAndAdmitsFreshOwner() {
        val fence = AndroidAttachmentOwnerFence()
        val oldLaunchReady = CountDownLatch(1)
        val releaseOldLaunch = CountDownLatch(1)
        val launchCount = AtomicInteger(0)
        val oldResult = AtomicReference<Boolean?>()
        fence.update(ownerA, 1L)

        val oldThread = thread {
            oldLaunchReady.countDown()
            releaseOldLaunch.await()
            oldResult.set(
                fence.runIfCurrent(ownerA, 1L) {
                    launchCount.incrementAndGet()
                    true
                }
            )
        }
        assertTrue(oldLaunchReady.await(2, TimeUnit.SECONDS))
        fence.update(ownerB, 2L)
        releaseOldLaunch.countDown()
        oldThread.join(2_000L)

        assertFalse(oldThread.isAlive)
        assertEquals(null, oldResult.get())
        assertEquals(0, launchCount.get())
        assertEquals(
            true,
            fence.runIfCurrent(ownerB, 2L) {
                launchCount.incrementAndGet()
                true
            }
        )
        assertEquals(1, launchCount.get())
    }

    private fun engine(
        backend: FakeBackend,
        records: FakeRecordStore,
        sleepMillis: (Long) -> Unit = {},
        beforePublishCommit: (String) -> Unit = {}
    ): AndroidAttachmentSystemDownloadEngine =
        AndroidAttachmentSystemDownloadEngine(
            backend = backend,
            recordStore = records,
            stagingPath = { targetKey, displayName ->
                File(root, "staging/${targetKey.hashCode()}-$displayName").absolutePath
            },
            sleepMillis = sleepMillis,
            nowMillis = { 1L },
            pollIntervalMillis = 0L,
            beforePublishCommit = beforePublishCommit
        )

    private fun record(
        systemDownloadId: Long,
        target: File,
        staging: File,
        ownerKey: String
    ): AndroidSystemDownloadRecord =
        AndroidSystemDownloadRecord(
            systemDownloadId = systemDownloadId,
            targetKey = target.absolutePath,
            stagingPath = staging.absolutePath,
            mimeType = "application/octet-stream",
            attemptToken = "android-system-$systemDownloadId",
            ownerKey = ownerKey
        )
}

private class FakeBackend : AndroidSystemDownloadBackend {
    val snapshots = ConcurrentHashMap<Long, AndroidSystemDownloadSnapshot>()
    val removed = CopyOnWriteArrayList<Long>()
    private val enqueues = AtomicInteger()
    val enqueueCount: Int
        get() = enqueues.get()
    @Volatile
    var onQuery: ((Long) -> AndroidSystemDownloadSnapshot)? = null

    override fun enqueue(request: AndroidSystemDownloadEnqueueRequest): Long {
        return enqueues.incrementAndGet().toLong().also { id ->
            snapshots[id] = AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Pending)
        }
    }

    override fun query(systemDownloadId: Long): AndroidSystemDownloadSnapshot =
        if (systemDownloadId in removed) {
            AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Missing)
        } else onQuery?.invoke(systemDownloadId)
            ?: snapshots[systemDownloadId]
            ?: AndroidSystemDownloadSnapshot(AndroidSystemDownloadStatus.Missing)

    override fun remove(systemDownloadId: Long) {
        removed += systemDownloadId
        snapshots.remove(systemDownloadId)
    }
}

private class FakeRecordStore : AndroidSystemDownloadRecordStore {
    private val records = ConcurrentHashMap<String, AndroidSystemDownloadRecord>()

    override fun read(targetKey: String): AndroidSystemDownloadRecord? = records[targetKey]

    override fun write(record: AndroidSystemDownloadRecord) {
        records[record.targetKey] = record
    }

    override fun remove(targetKey: String) {
        records.remove(targetKey)
    }
}
