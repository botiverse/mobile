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
    fun blockedOpenTimesOutAndDeletesPendingRow() = withSourceFile(1024) { source ->
        val releaseOpen = CountDownLatch(1)
        val backend = FakeExportBackend(openBlock = releaseOpen)
        val progress = Collections.synchronizedList(mutableListOf<AttachmentDownloadsExportProgress>())
        val result =
            coordinator(stageTimeoutMs = 80L)
                .export(request(source, "download-open-timeout"), backend, progress::add)

        assertEquals("storage_failed", result.status)
        assertEquals("export_open", result.failurePhase)
        val progressCountAtTerminal = progress.size
        releaseOpen.countDown()
        assertTrue(backend.awaitDelete())
        Thread.sleep(50L)
        assertEquals(progressCountAtTerminal, progress.size)
        assertEquals(AttachmentDownloadsRowState.Missing, backend.rowState("content://downloads/1"))
        assertEquals(0, backend.publishCount.get())
        assertTrue(source.exists())
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
    fun foreignOwnerCannotCancelActiveExportWithSameDownloadId() = withSourceFile(1024 * 1024) { source ->
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val backend = FakeExportBackend(writeEntered = writeEntered, writeBlock = releaseWrite)
        val coordinator = coordinator(stageTimeoutMs = 5_000L)
        coordinator.updateOwnerBinding("owner-a", 1L)
        var result: AttachmentDownloadsExportResult? = null
        val exportThread = thread {
            result =
                coordinator.export(
                    request(source, "same-download-id").copy(ownerKey = "owner-a", ownerGeneration = 1L),
                    backend
                )
        }
        assertTrue(writeEntered.await(2, TimeUnit.SECONDS))

        assertEquals(
            "cancelled",
            coordinator.control("same-download-id", null, "cancel", ownerKey = "owner-b")
        )
        releaseWrite.countDown()
        exportThread.join(2_000L)

        assertFalse(exportThread.isAlive)
        assertEquals("saved", result?.status)
        assertEquals(1, backend.publishCount.get())
        assertEquals(0, backend.deleteCount.get())
    }

    @Test
    fun ownerChangeCancelsForeignActiveExportBeforePublication() = withSourceFile(1024 * 1024) { source ->
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val backend = FakeExportBackend(writeEntered = writeEntered, writeBlock = releaseWrite)
        val coordinator = coordinator(stageTimeoutMs = 5_000L)
        coordinator.updateOwnerBinding("owner-a", 1L)
        var result: AttachmentDownloadsExportResult? = null
        val exportThread = thread {
            result =
                coordinator.export(
                    request(source, "owner-change").copy(ownerKey = "owner-a", ownerGeneration = 1L),
                    backend
                )
        }
        assertTrue(writeEntered.await(2, TimeUnit.SECONDS))

        coordinator.updateOwnerBinding("owner-b", 2L)
        exportThread.join(2_000L)

        assertFalse(exportThread.isAlive)
        assertEquals("cancelled", result?.status)
        assertEquals(0, backend.publishCount.get())
        releaseWrite.countDown()
        assertTrue(backend.awaitDelete())
    }

    @Test
    fun ownerSwitchBeforeRegistrationRejectsOldExportAndAdmitsFreshOwner() =
        withSourceFile(4096) { source ->
            val oldRegistrationReady = CountDownLatch(1)
            val releaseOldRegistration = CountDownLatch(1)
            val coordinator =
                coordinator(
                    beforeRegistration = {
                        if (Thread.currentThread().name == "old-export") {
                            oldRegistrationReady.countDown()
                            releaseOldRegistration.await()
                        }
                    }
                )
            val oldBackend = FakeExportBackend()
            val freshBackend = FakeExportBackend()
            val oldResult = AtomicReference<AttachmentDownloadsExportResult>()
            coordinator.updateOwnerBinding("owner-a", 1L)

            val oldThread = thread(name = "old-export") {
                oldResult.set(
                    coordinator.export(
                        request(source, "old-owner").copy(ownerKey = "owner-a", ownerGeneration = 1L),
                        oldBackend
                    )
                )
            }
            assertTrue(oldRegistrationReady.await(2, TimeUnit.SECONDS))
            coordinator.updateOwnerBinding("owner-b", 2L)
            releaseOldRegistration.countDown()
            oldThread.join(2_000L)

            assertFalse(oldThread.isAlive)
            assertEquals("cancelled", oldResult.get()?.status)
            assertEquals(0, oldBackend.insertCount.get())
            assertEquals(0, oldBackend.publishCount.get())

            val freshResult =
                coordinator.export(
                    request(source, "fresh-owner").copy(ownerKey = "owner-b", ownerGeneration = 2L),
                    freshBackend
                )
            assertEquals("saved", freshResult.status)
            assertEquals(1, freshBackend.insertCount.get())
            assertEquals(1, freshBackend.publishCount.get())
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
    fun writeFailureDeletesPendingRowAndKeepsCache() = withSourceFile(4096) { source ->
        val backend = FakeExportBackend(failWrite = true)
        val result = coordinator().export(request(source, "download-write-fail"), backend)

        assertEquals("storage_failed", result.status)
        assertEquals("export_copy", result.failurePhase)
        assertTrue(backend.awaitDelete())
        assertEquals(AttachmentDownloadsRowState.Missing, backend.rowState("content://downloads/1"))
        assertEquals(0, backend.publishCount.get())
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

    @Test
    fun cleanupRetryIsPartOfTerminalAndLeavesNoPendingRow() = withSourceFile(4096) { source ->
        val backend = FakeExportBackend(publishResult = false, failedDeleteAttempts = 1)
        val result = coordinator().export(request(source, "download-cleanup-retry"), backend)

        assertEquals("storage_failed", result.status)
        assertEquals("export_publish", result.failurePhase)
        assertEquals(AttachmentDownloadsRowState.Missing, backend.rowState("content://downloads/1"))
        assertEquals(2, backend.deleteCount.get())
    }

    @Test
    fun unknownRowQueryIsNotTreatedAsMissingAndStillDeletesPending() = withSourceFile(4096) { source ->
        val backend = FakeExportBackend(publishResult = false, unknownQueryAttempts = 1)
        val result = coordinator().export(request(source, "download-null-query"), backend)

        assertEquals("storage_failed", result.status)
        assertEquals("export_publish", result.failurePhase)
        assertEquals(1, backend.deleteCount.get())
        assertEquals(AttachmentDownloadsRowState.Missing, backend.rowState("content://downloads/1"))
    }

    @Test
    fun blockedPublishTimeoutLetsConditionalDeleteOrPublishChooseOneWinner() = withSourceFile(4096) { source ->
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val backend =
            FakeExportBackend(
                publishEntered = publishEntered,
                publishBlock = releasePublish,
                ignorePublishInterrupt = true
            )
        val resultRef = arrayOfNulls<AttachmentDownloadsExportResult>(1)
        val exportThread = thread {
            resultRef[0] = coordinator(stageTimeoutMs = 80L).export(request(source, "download-publish-timeout"), backend)
        }
        assertTrue(publishEntered.await(2, TimeUnit.SECONDS))
        exportThread.join(2_000L)
        assertFalse(exportThread.isAlive)
        assertEquals("storage_failed", resultRef[0]?.status)
        assertEquals(AttachmentDownloadsRowState.Missing, backend.rowState("content://downloads/1"))
        releasePublish.countDown()
        Thread.sleep(50L)
        assertEquals(AttachmentDownloadsRowState.Missing, backend.rowState("content://downloads/1"))
    }

    @Test
    fun prestartCancelReceiptStopsExportBeforeInsert() = withSourceFile(4096) { source ->
        val backend = FakeExportBackend()
        val coordinator = coordinator()

        assertEquals("cancelled", coordinator.control("download-prestart", null, "cancel"))
        val result = coordinator.export(request(source, "download-prestart"), backend)

        assertEquals("cancelled", result.status)
        assertEquals(0, backend.insertCount.get())
        assertEquals(AttachmentDownloadsRowState.Missing, backend.rowState("content://downloads/1"))
    }

    @Test
    fun registeredCancelSeesFutureBeforeWorkerCanExecute() = withSourceFile(4096) { source ->
        val worker = GatedExecutorService()
        val backend = FakeExportBackend()
        val coordinator = coordinator(worker = worker)
        var result: AttachmentDownloadsExportResult? = null
        val exportThread = thread {
            result = coordinator.export(request(source, "download-worker-publication"), backend)
        }
        assertTrue(worker.executeEntered.await(2, TimeUnit.SECONDS))

        assertEquals("cancelled", coordinator.control("download-worker-publication", null, "stop"))
        worker.releaseExecute.countDown()
        exportThread.join(1_000L)
        Thread.sleep(50L)

        assertFalse(exportThread.isAlive)
        assertEquals("cancelled", result?.status)
        assertEquals(0, backend.insertCount.get())
        worker.shutdownNow()
    }

    @Test
    fun acquiredInsertOwnerStoppedBeforeInsertReleasesReservation() = withSourceFile(4096) { source ->
        val ownerAcquired = CountDownLatch(1)
        val releaseOwner = CountDownLatch(1)
        val backend = FakeExportBackend()
        val coordinator =
            coordinator(
                insertAdmissionWaitMs = 80L,
                afterInsertOwnerAcquired = {
                    ownerAcquired.countDown()
                    releaseOwner.awaitPossiblyIgnoringInterrupt(true)
                }
            )
        var result: AttachmentDownloadsExportResult? = null
        val exportThread = thread {
            result = coordinator.export(request(source, "download-owner-before-insert"), backend)
        }
        assertTrue(ownerAcquired.await(2, TimeUnit.SECONDS))

        assertEquals("cancelled", coordinator.control("download-owner-before-insert", null, "stop"))
        exportThread.join(1_000L)
        assertFalse(exportThread.isAlive)
        assertEquals("storage_failed", result?.status)
        assertEquals("export_cleanup", result?.failurePhase)
        assertEquals(0, backend.insertCount.get())

        releaseOwner.countDown()
        assertTrue(
            coordinator.awaitKeyReservationReleaseForTesting(
                request(source, "download-owner-before-insert"),
                2_000L
            )
        )
        assertEquals(0, backend.insertCount.get())

        val retryBackend = FakeExportBackend()
        val retryResult =
            coordinator.export(request(source, "download-owner-after-release"), retryBackend)
        assertEquals("saved", retryResult.status)
        assertEquals(1, retryBackend.insertCount.get())
        assertEquals(1, retryBackend.publishCount.get())
    }

    @Test
    fun staleNetworkTokenCancelsRegisteredExportBeforePublication() = withSourceFile(4096) { source ->
        val insertEntered = CountDownLatch(1)
        val releaseInsert = CountDownLatch(1)
        val terminalWorker = ObservingExecutorService()
        val backend =
            FakeExportBackend(
                insertEntered = insertEntered,
                insertBlock = releaseInsert,
                ignoreInsertInterrupt = true,
                unknownQueryAttempts = 1
            )
        val coordinator = coordinator(terminalWorker = terminalWorker)
        var result: AttachmentDownloadsExportResult? = null
        val exportThread = thread {
            result = coordinator.export(request(source, "download-transition"), backend)
        }
        assertTrue(insertEntered.await(2, TimeUnit.SECONDS))

        assertEquals("cancelled", coordinator.control("download-transition", "old-network-token", "cancel"))
        assertTrue(terminalWorker.commandStarted.await(2, TimeUnit.SECONDS))
        assertTrue(exportThread.isAlive)
        assertEquals(null, result)
        releaseInsert.countDown()
        assertTrue(backend.awaitDelete())
        assertEquals(0, backend.openCount.get())
        assertEquals(0, backend.publishCount.get())
        assertEquals(AttachmentDownloadsRowState.Missing, backend.rowState("content://downloads/1"))
        exportThread.join(1_000L)
        assertFalse(exportThread.isAlive)
        assertEquals("cancelled", result?.status)
        terminalWorker.shutdownNow()
    }

    @Test
    fun lateInsertCleanupFailureIsTerminalAuthoritative() = withSourceFile(4096) { source ->
        val insertEntered = CountDownLatch(1)
        val releaseInsert = CountDownLatch(1)
        val terminalWorker = ObservingExecutorService()
        val backend =
            FakeExportBackend(
                insertEntered = insertEntered,
                insertBlock = releaseInsert,
                ignoreInsertInterrupt = true,
                failedDeleteAttempts = Int.MAX_VALUE,
                unknownQueryAttempts = Int.MAX_VALUE
            )
        val coordinator =
            coordinator(
                cleanupAttempts = 1,
                deferredCleanupDelayMs = 60_000L,
                terminalWorker = terminalWorker
            )
        var result: AttachmentDownloadsExportResult? = null
        val exportThread = thread {
            result = coordinator.export(request(source, "download-late-insert-cleanup-fail"), backend)
        }
        assertTrue(insertEntered.await(2, TimeUnit.SECONDS))

        assertEquals(
            "cancelled",
            coordinator.control("download-late-insert-cleanup-fail", null, "stop")
        )
        assertTrue(terminalWorker.commandStarted.await(2, TimeUnit.SECONDS))
        assertTrue(exportThread.isAlive)
        assertEquals(null, result)
        releaseInsert.countDown()
        exportThread.join(1_000L)

        assertFalse(exportThread.isAlive)
        assertEquals("storage_failed", result?.status)
        assertEquals("export_cleanup", result?.failurePhase)
        assertEquals(0, backend.openCount.get())
        assertEquals(0, backend.writeCount.get())
        assertEquals(0, backend.publishCount.get())
        terminalWorker.shutdownNow()
    }

    @Test
    fun permanentlyBlockedInsertHasBoundedCleanupTerminalAndLateUriOwner() = withSourceFile(4096) { source ->
        val insertEntered = CountDownLatch(1)
        val releaseInsert = CountDownLatch(1)
        val backend =
            FakeExportBackend(
                insertEntered = insertEntered,
                insertBlock = releaseInsert,
                ignoreInsertInterrupt = true
            )
        val coordinator =
            coordinator(
                stageTimeoutMs = 80L,
                insertAdmissionWaitMs = 80L
            )
        var result: AttachmentDownloadsExportResult? = null
        val exportThread = thread {
            result = coordinator.export(request(source, "download-never-returning-insert"), backend)
        }
        assertTrue(insertEntered.await(2, TimeUnit.SECONDS))

        val startedAt = System.nanoTime()
        exportThread.join(1_000L)
        val terminalElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertFalse(exportThread.isAlive)
        assertTrue(terminalElapsedMs < 1_000L)
        assertEquals("storage_failed", result?.status)
        assertEquals("export_cleanup", result?.failurePhase)
        assertEquals(0, backend.openCount.get())
        assertEquals(0, backend.writeCount.get())
        assertEquals(0, backend.publishCount.get())
        val staleOwner = checkNotNull(
            coordinator.keyReservationOwnerForTesting(
                request(source, "download-never-returning-insert")
            )
        )

        val retryBackend = FakeExportBackend()
        val retryResult =
            coordinator.export(request(source, "download-same-key-retry"), retryBackend)
        assertEquals("storage_failed", retryResult.status)
        assertEquals("export_cleanup", retryResult.failurePhase)
        assertEquals(0, retryBackend.insertCount.get())

        releaseInsert.countDown()
        assertTrue(backend.awaitDelete())
        assertTrue(
            coordinator.awaitKeyReservationReleaseForTesting(
                request(source, "download-never-returning-insert"),
                2_000L
            )
        )
        assertEquals(AttachmentDownloadsRowState.Missing, backend.rowState("content://downloads/1"))
        assertEquals(0, backend.openCount.get())
        assertEquals(0, backend.writeCount.get())
        assertEquals(0, backend.publishCount.get())

        val nextWriteEntered = CountDownLatch(1)
        val releaseNextWrite = CountDownLatch(1)
        val afterCleanupBackend =
            FakeExportBackend(
                writeEntered = nextWriteEntered,
                writeBlock = releaseNextWrite
            )
        var afterCleanupResult: AttachmentDownloadsExportResult? = null
        val afterCleanupThread = thread {
            afterCleanupResult =
                coordinator.export(request(source, "download-after-key-release"), afterCleanupBackend)
        }
        assertTrue(nextWriteEntered.await(2, TimeUnit.SECONDS))
        assertEquals(1, afterCleanupBackend.insertCount.get())

        // Replay a stale old-generation release after the replacement reservation is active.
        coordinator.releaseKeyReservationOwnerForTesting(staleOwner)
        val joinedProgress = CountDownLatch(1)
        val staleReleaseRetryBackend = FakeExportBackend()
        var staleReleaseRetryResult: AttachmentDownloadsExportResult? = null
        val staleReleaseRetryThread = thread {
            staleReleaseRetryResult =
                coordinator.export(
                    request(source, "download-after-stale-release"),
                    staleReleaseRetryBackend
                ) { joinedProgress.countDown() }
        }
        assertTrue(joinedProgress.await(2, TimeUnit.SECONDS))
        assertEquals(0, staleReleaseRetryBackend.insertCount.get())

        releaseNextWrite.countDown()
        afterCleanupThread.join(1_000L)
        staleReleaseRetryThread.join(1_000L)
        assertEquals("saved", afterCleanupResult?.status)
        assertEquals("saved", staleReleaseRetryResult?.status)
        assertEquals(1, afterCleanupBackend.publishCount.get())
    }

    @Test
    fun cancelWinnerStopsAfterLateOpenReturnsIgnoringInterrupt() = withSourceFile(4096) { source ->
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val terminalGate = BlockedExecutorService()
        val backend =
            FakeExportBackend(
                openEntered = openEntered,
                openBlock = releaseOpen,
                ignoreOpenInterrupt = true
            )
        val coordinator = coordinator(terminalWorker = terminalGate)
        var result: AttachmentDownloadsExportResult? = null
        val exportThread = thread {
            result = coordinator.export(request(source, "download-late-open"), backend)
        }
        assertTrue(openEntered.await(2, TimeUnit.SECONDS))

        assertEquals("cancelled", coordinator.control("download-late-open", null, "stop"))
        releaseOpen.countDown()
        assertTrue(backend.awaitOpenReturned())

        assertEquals(0, backend.writeCount.get())
        assertEquals(0, backend.publishCount.get())
        terminalGate.release.countDown()
        assertTrue(backend.awaitDelete())
        exportThread.join(1_000L)
        assertFalse(exportThread.isAlive)
        assertEquals("cancelled", result?.status)
        terminalGate.shutdownNow()
    }

    @Test
    fun cancelWinnerStopsAfterLateWriteReturnsIgnoringInterrupt() = withSourceFile(256 * 1024) { source ->
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val terminalGate = BlockedExecutorService()
        val backend =
            FakeExportBackend(
                writeEntered = writeEntered,
                writeBlock = releaseWrite,
                ignoreWriteInterrupt = true
            )
        val coordinator = coordinator(terminalWorker = terminalGate)
        var result: AttachmentDownloadsExportResult? = null
        val exportThread = thread {
            result = coordinator.export(request(source, "download-late-write"), backend)
        }
        assertTrue(writeEntered.await(2, TimeUnit.SECONDS))

        assertEquals("cancelled", coordinator.control("download-late-write", null, "stop"))
        releaseWrite.countDown()
        assertTrue(backend.awaitWriteReturned())

        assertEquals(1, backend.writeCount.get())
        assertEquals(0, backend.publishCount.get())
        terminalGate.release.countDown()
        assertTrue(backend.awaitDelete())
        exportThread.join(1_000L)
        assertFalse(exportThread.isAlive)
        assertEquals("cancelled", result?.status)
        terminalGate.shutdownNow()
    }

    @Test
    fun deferredCleanupRetainsOwnershipAcrossRepeatedUnknownQueries() = withSourceFile(4096) { source ->
        val backend =
            FakeExportBackend(
                publishResult = false,
                failedDeleteAttempts = 2,
                unknownQueryAttempts = 8,
                queryBarrierCount = 9
            )
        val coordinator = coordinator(cleanupAttempts = 1, deferredCleanupDelayMs = 20L)
        val result = coordinator.export(request(source, "download-deferred-unknown"), backend)

        assertEquals("storage_failed", result.status)
        assertEquals("export_cleanup", result.failurePhase)
        assertTrue(backend.awaitDelete())
        assertTrue(backend.awaitQueryBarrier())
        assertTrue(backend.deleteCount.get() >= 3)
        assertEquals(AttachmentDownloadsRowState.Missing, backend.rowState("content://downloads/1"))
    }

    @Test
    fun stopReceiptCanBeCancelledWhilePublishedWinnerReturnsSingleSavedTerminal() = withSourceFile(4096) { source ->
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val backend =
            FakeExportBackend(
                publishEntered = publishEntered,
                publishBlock = releasePublish,
                ignorePublishInterrupt = true,
                publishBeforeBlock = true
            )
        val coordinator = coordinator()
        var result: AttachmentDownloadsExportResult? = null
        val exportThread = thread {
            result = coordinator.export(request(source, "download-publish-wins"), backend)
        }
        assertTrue(publishEntered.await(2, TimeUnit.SECONDS))

        // The synchronous control value acknowledges the stop request. The row-state winner is
        // authoritative for the one eventual terminal, so an already-published row remains saved.
        assertEquals("cancelled", coordinator.control("download-publish-wins", null, "stop"))
        exportThread.join(1_000L)
        assertEquals("saved", result?.status)
        assertEquals(0, backend.deleteCount.get())
        releasePublish.countDown()
        Thread.sleep(50L)
        assertEquals(1, backend.publishCount.get())
        assertEquals(AttachmentDownloadsRowState.Published, backend.rowState("content://downloads/1"))
    }

    @Test
    fun blockedObserverCannotBlockCancelTerminalOrAdmitLateProgress() = withSourceFile(4096) { source ->
        val observerEntered = CountDownLatch(1)
        val releaseObserver = CountDownLatch(1)
        val callbackCount = AtomicInteger(0)
        val token = AtomicReference<String>()
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val backend = FakeExportBackend(writeEntered = writeEntered, writeBlock = releaseWrite)
        val coordinator = coordinator()
        var result: AttachmentDownloadsExportResult? = null
        val exportThread = thread {
            result =
                coordinator.export(request(source, "download-blocked-observer"), backend) { progress ->
                    token.compareAndSet(null, progress.attemptToken)
                    callbackCount.incrementAndGet()
                    observerEntered.countDown()
                    releaseObserver.await()
                }
        }
        assertTrue(observerEntered.await(2, TimeUnit.SECONDS))
        assertTrue(writeEntered.await(2, TimeUnit.SECONDS))
        val startedAt = System.nanoTime()
        assertEquals(
            "cancelled",
            coordinator.control("download-blocked-observer", token.get(), "cancel")
        )
        exportThread.join(1_000L)
        assertFalse(exportThread.isAlive)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 1_000L)
        assertEquals("cancelled", result?.status)
        val callbacksAtTerminal = callbackCount.get()
        releaseWrite.countDown()
        releaseObserver.countDown()
        Thread.sleep(50L)
        assertEquals(callbacksAtTerminal, callbackCount.get())
        assertTrue(backend.awaitDelete())
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
        beforeRegistration: () -> Unit = {}
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
            beforeRegistration = beforeRegistration
        )

    private fun request(source: File, downloadId: String) =
        AttachmentDownloadsExportRequest(
            downloadId = downloadId,
            source = source,
            displayName = "fixture.bin",
            mimeType = "application/octet-stream"
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

private class GatedExecutorService : AbstractExecutorService() {
    val executeEntered = CountDownLatch(1)
    val releaseExecute = CountDownLatch(1)
    private val delegate =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "attachment-export-gated-worker").apply { isDaemon = true }
        }
    @Volatile private var shutdown = false

    override fun execute(command: Runnable) {
        executeEntered.countDown()
        releaseExecute.awaitPossiblyIgnoringInterrupt(true)
        delegate.execute(command)
    }

    override fun shutdown() {
        shutdown = true
        delegate.shutdown()
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdown = true
        releaseExecute.countDown()
        return delegate.shutdownNow()
    }

    override fun isShutdown(): Boolean = shutdown
    override fun isTerminated(): Boolean = delegate.isTerminated
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
        delegate.awaitTermination(timeout, unit)
}

private class BlockedExecutorService : AbstractExecutorService() {
    val release = CountDownLatch(1)
    private val delegate =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "attachment-export-blocked-terminal").apply { isDaemon = true }
        }
    @Volatile private var shutdown = false

    init {
        delegate.execute { release.awaitPossiblyIgnoringInterrupt(true) }
    }

    override fun execute(command: Runnable) = delegate.execute(command)

    override fun shutdown() {
        shutdown = true
        release.countDown()
        delegate.shutdown()
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdown = true
        release.countDown()
        return delegate.shutdownNow()
    }

    override fun isShutdown(): Boolean = shutdown
    override fun isTerminated(): Boolean = delegate.isTerminated
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
        delegate.awaitTermination(timeout, unit)
}

private class ObservingExecutorService : AbstractExecutorService() {
    val commandStarted = CountDownLatch(1)
    private val delegate =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "attachment-export-observed-terminal").apply { isDaemon = true }
        }
    @Volatile private var shutdown = false

    override fun execute(command: Runnable) {
        delegate.execute {
            commandStarted.countDown()
            command.run()
        }
    }

    override fun shutdown() {
        shutdown = true
        delegate.shutdown()
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdown = true
        return delegate.shutdownNow()
    }

    override fun isShutdown(): Boolean = shutdown
    override fun isTerminated(): Boolean = delegate.isTerminated
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
        delegate.awaitTermination(timeout, unit)
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
