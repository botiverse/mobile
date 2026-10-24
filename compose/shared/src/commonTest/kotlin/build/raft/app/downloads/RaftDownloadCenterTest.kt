package build.raft.app.downloads

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.RaftFilePreviewDownloadPhase
import build.raft.app.RaftFilePreviewDownloadStatusTerms
import build.raft.app.raftThreadAttachmentCardDownloadLabel
import build.raft.app.toFilePreviewDownloadView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RaftDownloadCenterTest {
    private val store = InMemoryRaftKeyValueStore()
    private var now = 1_000L

    /** A transfer the test drives by hand: progress samples, then a host status. */
    private class ScriptedTask : RaftDownloadTask {
        val runs = mutableListOf<Int>()
        val controls = mutableListOf<RaftDownloadControl>()
        var progress: ((RaftDownloadProgressUpdate) -> Unit)? = null
        var result = CompletableDeferred<RaftDownloadOutcome>()

        override suspend fun run(attempt: Int, onProgress: (RaftDownloadProgressUpdate) -> Unit): RaftDownloadOutcome {
            runs += attempt
            progress = onProgress
            return result.await()
        }

        override fun control(action: RaftDownloadControl) {
            controls += action
        }

        fun finish(outcome: RaftDownloadOutcome) {
            result.complete(outcome)
        }

        fun rearm() {
            result = CompletableDeferred()
        }
    }

    private fun TestScope.center(scope: CoroutineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))) =
        RaftDownloadCenter(scope = scope, repository = RaftDownloadHistoryRepository(store), nowMillis = { now })

    private fun descriptor(id: String = "a1", owner: String = "owner-1", account: String = "") =
        RaftDownloadDescriptor(
            id = id,
            ownerKey = owner,
            attachmentId = "att-$id",
            fileName = "$id.pdf",
            mimeType = "application/pdf",
            accountKey = account
        )

    private fun RaftDownloadCenter.row(id: String = "a1"): RaftDownloadEntry =
        state.value.entries.firstOrNull { it.id == id } ?: error("no row $id in ${state.value.entries}")

    @Test
    fun downloadSurvivesTheStartingPageScope() = runTest {
        val center = center()
        val task = ScriptedTask()
        // The page awaits the outcome in its own scope, then leaves (its scope is cancelled).
        val pageScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        var pageSaw: RaftDownloadOutcome? = null
        val result = center.start(descriptor(), task)
        pageScope.launchAwait(result) { pageSaw = it }
        runCurrent()
        pageScope.cancel()
        runCurrent()

        task.progress!!(RaftDownloadProgressUpdate(downloadedBytes = 512, totalBytes = 1024, bytesPerSecond = 256))
        assertEquals(RaftDownloadStatus.Downloading, center.row().status, "page exit must not cancel the centre run")
        assertEquals(50, center.row().percent)

        task.finish(RaftDownloadOutcome.Completed())
        runCurrent()
        assertEquals(RaftDownloadStatus.Completed, center.row().status)
        assertEquals(RaftDownloadOutcome.Completed(), result.await())
        assertNull(pageSaw, "the cancelled page must not receive the outcome")
        assertTrue(task.controls.isEmpty(), "page exit sent no control to the host: ${task.controls}")
    }

    @Test
    fun serverSwitchKeepsTheDownloadRunningAndListedUntilItCompletes() = runTest {
        val center = center()
        val task = ScriptedTask()
        center.start(descriptor(id = "a1", owner = "server-a", account = "account-1"), task)
        center.start(descriptor(id = "x1", owner = "server-x", account = "account-2"), ScriptedTask())
        runCurrent()
        task.progress!!(RaftDownloadProgressUpdate(downloadedBytes = 256, totalBytes = 1024))

        // The shell now shows server B of the same account: the centre lists the running row.
        val onServerB = center.entries(ownerKey = "server-b", accountKey = "account-1").first()
        assertEquals(listOf("a1"), onServerB.map { it.id }, "another account's row never shows")
        task.progress!!(RaftDownloadProgressUpdate(downloadedBytes = 768, totalBytes = 1024))
        assertEquals(RaftDownloadStatus.Downloading, center.row("a1").status)
        assertEquals(75, center.row("a1").percent)

        task.finish(RaftDownloadOutcome.Completed())
        runCurrent()
        assertEquals(RaftDownloadStatus.Completed, center.row("a1").status)
        // Finished, it belongs to its own server's list again.
        assertEquals(emptyList(), center.entries(ownerKey = "server-b", accountKey = "account-1").first())
        assertEquals(listOf("a1"), center.entries(ownerKey = "server-a", accountKey = "account-1").first().map { it.id })
    }

    @Test
    fun progressStatesPauseResumeAndSaving() = runTest {
        val center = center()
        val task = ScriptedTask()
        center.start(descriptor(), task)
        runCurrent()

        task.progress!!(RaftDownloadProgressUpdate(downloadedBytes = 100, totalBytes = 400, bytesPerSecond = 50))
        assertEquals(25, center.row().percent)
        assertEquals(50L, center.row().bytesPerSecond)

        center.pause("a1")
        assertEquals(RaftDownloadStatus.Paused, center.row().status)
        assertNull(center.row().bytesPerSecond)
        // A late sample of the paused transfer keeps the row paused.
        task.progress!!(RaftDownloadProgressUpdate(downloadedBytes = 120, totalBytes = 400))
        assertEquals(RaftDownloadStatus.Paused, center.row().status)

        center.resume("a1")
        assertEquals(RaftDownloadStatus.Downloading, center.row().status)
        task.progress!!(RaftDownloadProgressUpdate(downloadedBytes = 400, totalBytes = 400, saving = true))
        assertTrue(center.row().saving)
        assertEquals(listOf(RaftDownloadControl.Pause, RaftDownloadControl.Resume), task.controls)

        task.finish(RaftDownloadOutcome.Completed())
        runCurrent()
        val done = center.row()
        assertEquals(RaftDownloadStatus.Completed, done.status)
        assertFalse(done.saving)
        assertEquals(400L, done.downloadedBytes)
        assertEquals(100, done.percent)
    }

    @Test
    fun startWhileActiveJoinsTheRunningDownload() = runTest {
        val center = center()
        val task = ScriptedTask()
        val first = center.start(descriptor(), task)
        val second = center.start(descriptor(), ScriptedTask())
        runCurrent()
        assertTrue(first === second, "same id while active must single-flight")
        assertEquals(listOf(1), task.runs)
        assertEquals(1, center.state.value.entries.size)
    }

    @Test
    fun cancelControlsTheHostAndSettlesCancelled() = runTest {
        val center = center()
        val task = ScriptedTask()
        val result = center.start(descriptor(), task)
        runCurrent()

        center.cancel("a1")
        runCurrent()

        assertEquals(listOf(RaftDownloadControl.Cancel), task.controls)
        assertEquals(RaftDownloadStatus.Cancelled, center.row().status)
        assertTrue(result.await() is RaftDownloadOutcome.Cancelled)
        assertFalse(center.isActive("a1"))
        assertTrue(center.canRetry("a1"))
    }

    @Test
    fun failureThenRetryReusesTheRowWithTheNextAttempt() = runTest {
        val center = center()
        val task = ScriptedTask()
        center.start(descriptor(), task)
        runCurrent()
        now = 2_000L
        task.finish(RaftDownloadOutcome.Failed(hostStatus = "download_failed", message = "HTTP 500"))
        runCurrent()
        assertEquals(RaftDownloadStatus.Failed, center.row().status)
        assertEquals(RaftDownloadErrorKind.Failed, center.row().errorKind)
        assertEquals("HTTP 500", center.row().errorMessage)

        task.rearm()
        now = 3_000L
        val retried = assertNotNull(center.retry("a1"))
        runCurrent()
        assertEquals(listOf(1, 2), task.runs)
        val row = center.row()
        assertEquals(RaftDownloadStatus.Downloading, row.status)
        assertNull(row.errorKind)
        assertEquals(1_000L, row.createdAtMillis, "retry keeps the row's creation time")
        assertEquals(1, center.state.value.entries.size)

        task.finish(RaftDownloadOutcome.Completed())
        runCurrent()
        assertEquals(RaftDownloadOutcome.Completed(), retried.await())
        assertEquals(RaftDownloadStatus.Completed, center.row().status)
    }

    @Test
    fun hostCancelledStatusMapsToCancelledOutcome() {
        assertEquals(RaftDownloadOutcome.Completed("saved"), raftDownloadOutcomeForSaveStatus("saved", null))
        assertEquals(RaftDownloadOutcome.Completed("exported"), raftDownloadOutcomeForSaveStatus("exported", null))
        assertEquals(RaftDownloadOutcome.Cancelled("cancelled"), raftDownloadOutcomeForSaveStatus("cancelled", "x"))
        assertEquals(
            RaftDownloadOutcome.Failed("storage_failed", "disk full"),
            raftDownloadOutcomeForSaveStatus("storage_failed", "disk full")
        )
        assertEquals(RaftDownloadOutcome.Failed("failed", null), raftDownloadOutcomeForSaveStatus("", null))
    }

    @Test
    fun historyPersistsAndInterruptedRunsRestoreAsFailed() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val first = center(scope)
        val done = ScriptedTask()
        first.start(descriptor("done"), done)
        first.start(descriptor("running"), ScriptedTask())
        runCurrent()
        done.finish(RaftDownloadOutcome.Completed())
        runCurrent()
        scope.cancel() // the process dies with "running" still active

        val restored = center()
        val rows = restored.state.value.entries.associateBy { it.id }
        assertEquals(setOf("done", "running"), rows.keys)
        assertEquals(RaftDownloadStatus.Completed, rows.getValue("done").status)
        assertEquals(RaftDownloadStatus.Failed, rows.getValue("running").status)
        assertEquals(RaftDownloadErrorKind.Interrupted, rows.getValue("running").errorKind)
        assertFalse(restored.canRetry("running"), "a restored row has no task of this process")
    }

    @Test
    fun deleteAndClearFinishedArePersistedPerOwner() = runTest {
        val center = center()
        listOf("a" to "owner-1", "b" to "owner-1", "c" to "owner-2").forEach { (id, owner) ->
            val task = ScriptedTask()
            center.start(descriptor(id, owner), task)
            runCurrent()
            task.finish(RaftDownloadOutcome.Completed())
            runCurrent()
        }
        val active = ScriptedTask()
        center.start(descriptor("d", "owner-1"), active)
        runCurrent()

        center.delete("a")
        center.clearFinished("owner-1")
        assertEquals(listOf("d", "c"), center.state.value.entries.map { it.id }, "owner-2 rows and active rows stay")
        assertEquals(listOf("d"), center.state.value.forOwner("owner-1").map { it.id })

        val reloaded = RaftDownloadHistoryRepository(store).load().map { it.id }
        assertEquals(listOf("d", "c"), reloaded)
    }

    @Test
    fun repositoryCapsAndToleratesCorruptDocuments() {
        val repository = RaftDownloadHistoryRepository(store, maxEntries = 2)
        val rows =
            (1..3).map { index ->
                RaftDownloadEntry(
                    id = "r$index",
                    ownerKey = "o",
                    attachmentId = "a$index",
                    fileName = "f$index",
                    status = RaftDownloadStatus.Completed,
                    bytesPerSecond = 10,
                    createdAtMillis = index.toLong(),
                    updatedAtMillis = index.toLong()
                )
            }
        repository.save(rows)
        val loaded = repository.load()
        assertEquals(listOf("r1", "r2"), loaded.map { it.id })
        assertNull(loaded.first().bytesPerSecond, "speed is not persisted")

        store.putString(RAFT_DOWNLOAD_HISTORY_STORAGE_KEY, "{not json")
        assertEquals(emptyList(), repository.load())
    }

    @Test
    fun sizeLabelsUseOneDecimalBinaryUnits() {
        assertEquals("512 B", raftDownloadSizeLabel(512))
        assertEquals("1.0 KB", raftDownloadSizeLabel(1024))
        assertEquals("1.5 MB", raftDownloadSizeLabel(1_572_864))
        assertEquals("2.0 GB", raftDownloadSizeLabel(2L * 1024 * 1024 * 1024))
        assertEquals("850.0 KB/s", raftDownloadSpeedLabel(850L * 1024))
        assertNull(raftDownloadSpeedLabel(0))
    }

    @Test
    fun attachmentSelectorDrivesTheCardAndFilePageProgressStates() = runTest {
        val center = center()
        val task = ScriptedTask()
        val terms = RaftFilePreviewDownloadStatusTerms(downloading = "Downloading", paused = "Paused", saving = "Saving")
        suspend fun entry() = center.attachmentEntry(ownerKey = "owner-1", attachmentId = "att-a1").first()

        assertNull(entry(), "nothing downloaded yet")
        assertNull(raftThreadAttachmentCardDownloadLabel(entry(), terms))

        center.start(descriptor(), task)
        runCurrent()
        assertEquals("Downloading...", raftThreadAttachmentCardDownloadLabel(entry(), terms), "no total yet")
        assertNull(center.attachmentEntry(ownerKey = "owner-2", attachmentId = "att-a1").first(), "another principal sees nothing")

        task.progress!!(RaftDownloadProgressUpdate(downloadedBytes = 256, totalBytes = 1024, bytesPerSecond = 128))
        assertEquals("Downloading 25%", raftThreadAttachmentCardDownloadLabel(entry(), terms))
        val active = entry()!!.toFilePreviewDownloadView()
        assertEquals(RaftFilePreviewDownloadPhase.Active, active.phase)
        assertEquals(25, active.percent)
        assertEquals(256L, active.downloadedBytes)
        assertEquals(1024L, active.totalBytes)
        assertTrue(active.inProgress)

        center.pause("a1")
        assertEquals("Paused 25%", raftThreadAttachmentCardDownloadLabel(entry(), terms))
        assertEquals(RaftFilePreviewDownloadPhase.Paused, entry()!!.toFilePreviewDownloadView().phase)

        center.resume("a1")
        task.progress!!(RaftDownloadProgressUpdate(downloadedBytes = 1024, totalBytes = 1024, saving = true))
        assertEquals("Saving 100%", raftThreadAttachmentCardDownloadLabel(entry(), terms))
        assertEquals(RaftFilePreviewDownloadPhase.Saving, entry()!!.toFilePreviewDownloadView().phase)

        task.finish(RaftDownloadOutcome.Completed())
        runCurrent()
        assertEquals(RaftDownloadStatus.Completed, entry()!!.status)
        assertNull(raftThreadAttachmentCardDownloadLabel(entry(), terms), "a finished download leaves the card")
        assertFalse(entry()!!.toFilePreviewDownloadView().inProgress)

        task.rearm()
        center.retry("a1")
        runCurrent()
        task.finish(RaftDownloadOutcome.Failed(hostStatus = "download_failed", message = "network lost"))
        runCurrent()
        val failed = entry()!!.toFilePreviewDownloadView()
        assertEquals(RaftFilePreviewDownloadPhase.Failed, failed.phase, "the file page offers Retry")
        assertEquals("network lost", failed.errorMessage)
        assertNull(raftThreadAttachmentCardDownloadLabel(entry(), terms))
    }

    private fun CoroutineScope.launchAwait(
        result: kotlinx.coroutines.Deferred<RaftDownloadOutcome>,
        onResult: (RaftDownloadOutcome) -> Unit
    ) {
        launch { onResult(result.await()) }
    }
}
