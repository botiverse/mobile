package build.raft.app.downloads

import build.raft.app.data.InMemoryRaftKeyValueStore
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
import build.raft.app.transfer.testTransfer
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

    /** A host meter's sample: the percent it would report for these bytes. */
    private fun progressSample(downloadedBytes: Long, totalBytes: Long?, bytesPerSecond: Long? = null, saving: Boolean = false) =
        RaftDownloadProgressUpdate(
            transfer =
                testTransfer(
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    percent = totalBytes?.let { (downloadedBytes * 100L / it).toInt() },
                    bytesPerSecond = bytesPerSecond
                ),
            saving = saving
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

        task.progress!!(progressSample(downloadedBytes = 512, totalBytes = 1024, bytesPerSecond = 256))
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
        task.progress!!(progressSample(downloadedBytes = 256, totalBytes = 1024))

        // The shell now shows server B of the same account: the centre lists the running row.
        val onServerB = center.entries(ownerKey = "server-b", accountKey = "account-1").first()
        assertEquals(listOf("a1"), onServerB.map { it.id }, "another account's row never shows")
        task.progress!!(progressSample(downloadedBytes = 768, totalBytes = 1024))
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
        assertEquals("download_failed", center.row().errorHostStatus)

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
                    transfer = testTransfer(downloadedBytes = 10L, totalBytes = 20L, percent = 50, bytesPerSecond = 10L),
                    createdAtMillis = index.toLong(),
                    updatedAtMillis = index.toLong()
                )
            }
        repository.save(rows)
        val loaded = repository.load()
        assertEquals(listOf("r1", "r2"), loaded.map { it.id })
        assertNull(loaded.first().transfer, "the live meter state (speed) is not persisted")

        store.putString(RAFT_DOWNLOAD_HISTORY_STORAGE_KEY, "{not json")
        assertEquals(emptyList(), repository.load())
    }

    private fun CoroutineScope.launchAwait(
        result: kotlinx.coroutines.Deferred<RaftDownloadOutcome>,
        onResult: (RaftDownloadOutcome) -> Unit
    ) {
        launch { onResult(result.await()) }
    }
}
