package build.raft.app.core.data.diagnostics

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
import android.app.ApplicationExitInfo
import build.raft.app.shared.RaftBuildStamp
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the reporter persists survives failed uploads, budget deferrals, process deaths between
 * steps and the system's exit list or snapshot ring rolling over.
 */
class AndroidSystemKillReportPersistenceTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 10 * day
    private val mainProcess = "build.raft.app"
    private val sameMillisecond = now - 5_000L

    @Test
    fun aRetryableFailureAtAMillisecondAnotherExitSharesIsRetriedNextLaunch() = runTest {
        // A user swipe-away (no report) and a visible kill in the same millisecond, the swipe-away first.
        val exits =
            listOf(
                exit(pid = 1, at = sameMillisecond, importance = IMPORTANCE_VISIBLE, reason = ApplicationExitInfo.REASON_USER_REQUESTED),
                exit(pid = 2, at = sameMillisecond, importance = IMPORTANCE_VISIBLE),
            )
        val store = FakeStore()

        oneLaunch(store, exits, submit = { _, _ -> throw IOException("offline") })

        // The watermark stays below the shared millisecond; the swipe-away is kept as handled.
        assertTrue(store.persisted.watermarkMillis < sameMillisecond)
        assertEquals(setOf(SystemKillExitKey(1, sameMillisecond)), store.persisted.handledAboveWatermark)

        val sent = mutableListOf<Any?>()
        oneLaunch(store, exits, submit = { _, extras -> sent += extras["crash_process_id"]; "ticket" })
        assertEquals(listOf<Any?>(2), sent)
        assertEquals(sameMillisecond, store.persisted.watermarkMillis)
        assertTrue(store.persisted.handledAboveWatermark.isEmpty())
    }

    @Test
    fun aDeferredExitAtAMillisecondAnotherExitSharesIsSentNextLaunch() = runTest {
        val exits =
            listOf(
                exit(pid = 1, at = sameMillisecond, importance = IMPORTANCE_VISIBLE, reason = ApplicationExitInfo.REASON_USER_REQUESTED),
                exit(pid = 2, at = sameMillisecond, importance = IMPORTANCE_VISIBLE),
            )
        val store = FakeStore()

        // No visible slot this launch: pid 2 is built and queued, in the commit that handles it.
        oneLaunch(store, exits, limits = limits(visiblePerLaunch = 0), submit = { _, _ -> error("not sent") })
        assertEquals(listOf(SystemKillExitKey(2, sameMillisecond)), store.persisted.queue.map { it.key })

        val sent = mutableListOf<Any?>()
        oneLaunch(store, exits, submit = { _, extras -> sent += extras["crash_process_id"]; "ticket" })
        assertEquals(listOf<Any?>(2), sent)
        assertTrue(store.persisted.queue.isEmpty())
    }

    @Test
    fun aVisibleReportOverTheDaysBudgetIsQueuedAndSentAfter24Hours() = runTest {
        val exit = exit(pid = 1, at = now - 1_000L, importance = IMPORTANCE_VISIBLE)
        val store = FakeStore(SystemKillProgress(recentReportMillis = mapOf(SystemKillBudget.Visible to List(5) { now - 10_000L })))

        oneLaunch(store, listOf(exit), submit = { _, _ -> error("not sent") })
        assertEquals(1, store.persisted.queue.size)

        // A restart within the day: still no room, still queued.
        oneLaunch(store, listOf(exit), at = now + 60_000L, submit = { _, _ -> error("not sent") })
        assertEquals(1, store.persisted.queue.size)

        // A day later the budget is free again: the queued report goes out first.
        val sent = mutableListOf<String>()
        oneLaunch(store, emptyList(), at = now + day, submit = { message, _ -> sent += message; "ticket" })
        assertEquals(1, sent.size)
        assertTrue(sent.single(), sent.single().startsWith("System kill: low_memory"))
        assertTrue(store.persisted.queue.isEmpty())
    }

    @Test
    fun aQueuedReportIsSentAfterItsExitLeftTheSystemsExitList() = runTest {
        val exit = exit(pid = 1, at = now - 100_000L, importance = IMPORTANCE_VISIBLE)
        val store = FakeStore()
        oneLaunch(store, listOf(exit), limits = limits(visiblePerLaunch = 0), submit = { _, _ -> error("not sent") })

        // The next launch reads only the newest 16 exits; this one is no longer among them.
        val newer = (2..17).map { exit(pid = it, at = now - 50_000L + it, importance = IMPORTANCE_CACHED, reason = ApplicationExitInfo.REASON_USER_REQUESTED) }
        val sent = mutableListOf<Any?>()
        oneLaunch(store, newer, submit = { _, extras -> sent += extras["crash_process_id"]; "ticket" })

        assertEquals(listOf<Any?>(1), sent)
    }

    @Test
    fun aQueuedReportKeepsItsClassificationAfterItsSnapshotLeftTheRing() = runTest {
        // A kill at importance 400 one second after an interaction: visible by recent foreground.
        val exit = exit(pid = 1, at = now - 100_000L, importance = IMPORTANCE_CACHED)
        val snapshot = old(1, exit.timestampMillis).copy(lastInteractionAtMillis = exit.timestampMillis - 1_000L)
        val store = FakeStore()
        oneLaunch(store, listOf(exit), listOf(snapshot), limits = limits(visiblePerLaunch = 0), submit = { _, _ -> error("not sent") })
        assertEquals(SystemKillBudget.Visible, store.persisted.queue.single().budget)

        // Six newer processes pushed it out of the ring.
        val ring = (2..7).map { old(it, now) }
        val sent = mutableListOf<Map<String, Any?>>()
        oneLaunch(store, emptyList(), ring, submit = { _, extras -> sent += extras; "ticket" })

        val extras = sent.single()
        assertEquals(true, extras["crash_recent_foreground"])
        assertEquals("interaction", extras["crash_recent_foreground_source"])
        assertEquals(400, (extras["crash_exit_importance"] as Number).toInt())
    }

    @Test
    fun aProcessDeathBeforeTheDropIsCommittedCountsTheExitOnceAfterRelaunch() = runTest {
        // Four confirmed background kills; the fourth is over the background budget.
        val exits = (1..4).map { exit(pid = it, at = now - 10_000L + it, importance = IMPORTANCE_CACHED) }
        val snapshots = exits.map { old(it.pid, it.timestampMillis) }
        val limits = limits(backgroundPerLaunch = 3, backgroundPerDay = 3)
        val store = FakeStore()

        // The process dies while handling the drop: that commit never lands.
        store.dieOnCommitWhere = { progress -> progress.ledger.accumulating.isNotEmpty() }
        runCatching { oneLaunch(store, exits, snapshots, limits = limits) }
        assertTrue(store.persisted.ledger.accumulating.isEmpty())
        assertFalse(SystemKillExitKey(4, exits.last().timestampMillis) in store.persisted.handledAboveWatermark)

        // Relaunch: the drop is counted, and the next upload of the same launch carries it.
        store.dieOnCommitWhere = { false }
        val sent = mutableListOf<Map<String, Any?>>()
        oneLaunch(store, exits, snapshots, limits = limits, submit = { _, extras -> sent += extras; "ticket" })

        // A further relaunch handles, counts and sends nothing more.
        oneLaunch(store, exits, snapshots, limits = limits, submit = { _, extras -> sent += extras; "ticket" })

        val carried =
            sent.mapNotNull { it["crash_background_dropped"] as JSONArray? }
                .flatMap { array -> (0 until array.length()).map { array.getJSONObject(it).getInt("count") } }
        assertEquals(listOf(1), carried)
        assertEquals(3, sent.size)
        assertTrue(store.persisted.ledger.accumulating.isEmpty())
        assertEquals(null, store.persisted.ledger.pending)
    }

    @Test
    fun theDropCountAndTheHandledMarkerAreOneCommit() = runTest {
        val exit = exit(pid = 1, at = now - 1_000L, importance = IMPORTANCE_CACHED)
        val store = FakeStore()

        oneLaunch(store, listOf(exit), listOf(old(1, exit.timestampMillis)), limits = limits(backgroundPerLaunch = 0))

        val committed = store.commits.single()
        assertEquals(exit.timestampMillis, committed.watermarkMillis)
        assertEquals(1, committed.ledger.accumulating.single().count)
    }

    @Test
    fun aFailedUploadResendsTheSameDroppedBatchAndEventIdAndOnlySuccessClearsTheBatch() = runTest {
        val store = FakeStore(SystemKillProgress(ledger = BackgroundDropLedger().record(12600001L, now - 50_000L)))
        val exits = listOf(exit(pid = 1, at = now - 1_000L, importance = IMPORTANCE_VISIBLE))
        val carried = mutableListOf<Map<String, Any?>>()
        var ids = 0

        oneLaunch(store, exits, newBatchId = { "batch-${++ids}" }, submit = { _, extras -> carried += extras; throw IOException("offline") })
        oneLaunch(store, exits, newBatchId = { "batch-${++ids}" }, submit = { _, extras -> carried += extras; "ticket" })

        assertEquals(listOf("batch-1", "batch-1"), carried.map { it["crash_background_dropped_id"] })
        assertEquals(carried[0]["crash_background_dropped"].toString(), carried[1]["crash_background_dropped"].toString())
        assertEquals(carried[0]["crash_exit_event_id"], carried[1]["crash_exit_event_id"])
        assertEquals(null, store.persisted.ledger.pending)
        assertTrue(store.persisted.ledger.accumulating.isEmpty())
    }

    @Test
    fun queueOverflowEvictsTheOldestReviewThenTheOldestVisibleAndCountsEach() {
        fun queued(pid: Int, budget: SystemKillBudget) =
            QueuedSystemKillReport(SystemKillExitKey(pid, now - 10_000L + pid), budget, 12600001L, "m$pid", emptyMap())
        var progress = SystemKillProgress()
        listOf(
            queued(1, SystemKillBudget.Visible),
            queued(2, SystemKillBudget.Review),
            queued(3, SystemKillBudget.Visible),
        ).forEach { progress = progress.enqueue(it, cap = 3) }

        progress = progress.enqueue(queued(4, SystemKillBudget.Review), cap = 3) // evicts review 2
        assertEquals(listOf(1, 3, 4), progress.queue.map { it.key.pid })
        progress = progress.enqueue(queued(5, SystemKillBudget.Visible), cap = 3) // evicts review 4
        assertEquals(listOf(1, 3, 5), progress.queue.map { it.key.pid })
        progress = progress.enqueue(queued(6, SystemKillBudget.Visible), cap = 3) // no review left: visible 1
        assertEquals(listOf(3, 5, 6), progress.queue.map { it.key.pid })

        assertEquals(
            mapOf("review" to 2, "visible" to 1),
            progress.ledger.accumulating.associate { it.category to it.count },
        )
    }

    @Test
    fun evictionsGoToCrashPendingEvictedAndBudgetDropsToCrashBackgroundDropped() {
        val ledger =
            BackgroundDropLedger()
                .record(12600001L, now, category = BackgroundDropEntry.Background)
                .record(12600001L, now, category = "visible")
        val (_, batch) = ledger.attach { "batch-1" }

        val extras = batch!!.extras()

        assertEquals("batch-1", extras["crash_background_dropped_id"])
        val dropped = extras["crash_background_dropped"] as JSONArray
        assertEquals(1, dropped.length())
        assertFalse(dropped.getJSONObject(0).has("category"))
        val evicted = extras["crash_pending_evicted"] as JSONArray
        assertEquals(1, evicted.length())
        assertEquals("visible", evicted.getJSONObject(0).getString("category"))
        assertEquals(1, evicted.getJSONObject(0).getInt("count"))

        // Only background drops: no eviction field.
        val (_, backgroundOnly) = BackgroundDropLedger().record(12600001L, now).attach { "batch-2" }
        assertFalse(backgroundOnly!!.extras().containsKey("crash_pending_evicted"))
    }

    @Test
    fun aQueuedReportRoundTripsThroughItsStoredForm() {
        val report =
            QueuedSystemKillReport(
                SystemKillExitKey(4321, now),
                SystemKillBudget.Review,
                12600001L,
                "System kill: low_memory\nline two",
                mapOf("crash_exit_importance" to 300, "crash_recent_foreground_source" to "unknown", "crash_foreground" to false),
            )

        val decoded = QueuedSystemKillReport.decodeAll(QueuedSystemKillReport.encodeAll(listOf(report))).single()

        assertEquals(report.key, decoded.key)
        assertEquals(report.budget, decoded.budget)
        assertEquals(report.message, decoded.message)
        assertEquals(300, (decoded.extras["crash_exit_importance"] as Number).toInt())
        assertEquals("unknown", decoded.extras["crash_recent_foreground_source"])
        assertEquals(false, decoded.extras["crash_foreground"])
    }

    @Test
    fun theExitEventIdIsStablePerInstallAndDiffersAcrossInstalls() {
        val exit = exit(pid = 4321, at = now, importance = IMPORTANCE_SERVICE)

        val a = systemKillExitEventId("install-a", exit)
        assertEquals(a, systemKillExitEventId("install-a", exit.copy()))
        assertNotEquals(a, systemKillExitEventId("install-b", exit))
        assertNotEquals(a, systemKillExitEventId("install-a", exit.copy(timestampMillis = now + 1L)))
        assertEquals(32, a.length)
        assertEquals(a, systemKillExtras(exit, null, installId = "install-a")["crash_exit_event_id"])
        assertFalse(systemKillExtras(exit, null).containsKey("crash_exit_event_id"))
    }

    /** Persisted state; a commit matching [dieOnCommitWhere] is lost and kills the "process". */
    private class FakeStore(var persisted: SystemKillProgress = SystemKillProgress()) : SystemKillProgressStore {
        var dieOnCommitWhere: (SystemKillProgress) -> Boolean = { false }
        val commits = mutableListOf<SystemKillProgress>()

        override fun load(): SystemKillProgress = persisted

        override fun commit(progress: SystemKillProgress) {
            if (dieOnCommitWhere(progress)) throw IllegalStateException("process died before this commit")
            persisted = progress
            commits += progress
        }
    }

    private fun limits(
        visiblePerLaunch: Int = 3,
        backgroundPerLaunch: Int = 3,
        backgroundPerDay: Int = 5,
    ) = mapOf(
        SystemKillBudget.Visible to SystemKillBudgetLimit(visiblePerLaunch, 5),
        SystemKillBudget.Review to SystemKillBudgetLimit(3, 5),
        SystemKillBudget.Background to SystemKillBudgetLimit(backgroundPerLaunch, backgroundPerDay),
    )

    /** One launch over [exits] and [snapshots] at [at]. */
    private suspend fun oneLaunch(
        store: FakeStore,
        exits: List<SystemKillExit>,
        snapshots: List<MemoryTelemetrySnapshot> = emptyList(),
        at: Long = now,
        limits: Map<SystemKillBudget, SystemKillBudgetLimit> = limits(),
        newBatchId: () -> String = { "batch" },
        submit: suspend (String, Map<String, Any?>) -> String = { _, _ -> "ticket" },
    ) = runSystemKillReports(
        store = store,
        nowMillis = at,
        limits = limits,
        readPlan = { progress, remaining ->
            val visible = remaining.getValue(SystemKillBudget.Visible)
            planSystemKillReports(
                exits = exits,
                snapshots = snapshots,
                cutoffMillis = 0L,
                watermarkMillis = progress.watermarkMillis,
                recentReportMillis = visible.recentReportMillis,
                nowMillis = at,
                perLaunchCap = visible.perLaunch,
                dailyCap = visible.perDay,
                mainProcessName = mainProcess,
                review = remaining.getValue(SystemKillBudget.Review),
                background = remaining.getValue(SystemKillBudget.Background),
                handledAboveWatermark = progress.handledAboveWatermark,
            )
        },
        buildReport = { item ->
            systemKillMessage(item.exit, item.telemetry) to systemKillExtras(item.exit, item.telemetry, installId = "install-a")
        },
        newBatchId = newBatchId,
        submit = submit,
    )

    /** A foreground snapshot whose sample and interaction are both 600 s before [at]: recent foreground false. */
    private fun old(pid: Int, at: Long) =
        MemoryTelemetrySnapshot(
            pid = pid,
            processStartMillis = 0L,
            writtenAtMillis = at - 600_000L,
            foreground = true,
            foregroundChangedAtMillis = at - 700_000L,
            sampledAtMillis = at - 600_000L,
            lastInteractionAtMillis = at - 600_000L,
        )

    private fun exit(pid: Int, at: Long, importance: Int, reason: Int = ApplicationExitInfo.REASON_LOW_MEMORY) =
        SystemKillExit(
            pid = pid,
            timestampMillis = at,
            reasonCode = reason,
            status = 0,
            importance = importance,
            pssKb = 0L,
            rssKb = 0L,
            build = RaftBuildStamp.StampedBuild("1.26.0", 12600001L),
            processName = mainProcess,
        )
}
