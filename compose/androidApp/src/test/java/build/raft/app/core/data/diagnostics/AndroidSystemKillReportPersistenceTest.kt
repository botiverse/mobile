package build.raft.app.core.data.diagnostics

import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
import android.app.ApplicationExitInfo
import build.hands.update.HandsFeedbackException
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
        assertTrue(store.persisted.ledger.pending.isEmpty())
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
        assertTrue(store.persisted.ledger.pending.isEmpty())
        assertTrue(store.persisted.ledger.accumulating.isEmpty())
    }

    @Test
    fun offlineLaunchesWhileTheExitHistoryRollsOverStillCaptureEveryReportAndSendItOnceOnline() = runTest {
        val store = FakeStore()
        val all = mutableListOf<SystemKillExit>()
        // Three offline launches, each seeing only its five newest exits (older ones rolled out).
        for (round in 0 until 3) {
            val batch =
                (1..5).map { i ->
                    val pid = round * 5 + i
                    exit(pid = pid, at = now - 100_000L + pid, importance = if (i % 2 == 0) IMPORTANCE_SERVICE else IMPORTANCE_VISIBLE)
                }
            all += batch
            oneLaunch(store, batch, submit = { _, _ -> throw IOException("offline") })
        }
        assertEquals(15, store.persisted.queue.size)

        // Back online, a day apart (5 per budget per day): every report goes out once.
        val sent = mutableListOf<Any?>()
        var day = 0
        while (store.persisted.queue.isNotEmpty() && day < 10) {
            oneLaunch(store, emptyList(), at = now + day * this@AndroidSystemKillReportPersistenceTest.day, submit = { _, extras ->
                sent += extras["crash_process_id"]
                "ticket"
            })
            day += 1
        }
        assertEquals(all.map { it.pid }.sorted(), sent.map { (it as Number).toInt() }.sorted())
    }

    @Test
    fun aQueuedAndANewReportSurviveA401AndA404AndAreSentOnceTheConfigurationIsFixed() = runTest {
        for (status in listOf(401, 404)) {
            val old = QueuedSystemKillReport(SystemKillExitKey(9, now - 50_000L), SystemKillBudget.Visible, 12600001L, "old", emptyMap())
            val store = FakeStore(SystemKillProgress(queue = listOf(old)))
            val fresh = exit(pid = 2, at = now - 1_000L, importance = IMPORTANCE_SERVICE)

            oneLaunch(store, listOf(fresh), submit = { _, _ -> throw HandsFeedbackException(status, "bad key or slug") })
            assertEquals("$status", 2, store.persisted.queue.size)
            assertTrue("$status", store.persisted.recentReportMillis.values.all { it.isEmpty() })
            assertTrue("$status", store.persisted.ledger.accumulating.isEmpty())

            val sent = mutableListOf<String>()
            oneLaunch(store, listOf(fresh), submit = { message, _ -> sent += message; "ticket" })
            assertEquals("$status", 2, sent.size)
            assertTrue("$status", store.persisted.queue.isEmpty())
        }
    }

    @Test
    fun aCommitThatReturnsFalseStopsTheLaunchAndARestartLosesAndDoublesNothing() = runTest {
        val exits =
            listOf(
                exit(pid = 1, at = now - 2_000L, importance = IMPORTANCE_CACHED), // background, dropped
                exit(pid = 2, at = now - 1_000L, importance = IMPORTANCE_VISIBLE),
            )
        val snapshots = listOf(old(1, now - 2_000L))
        val limits = limits(backgroundPerDay = 0)
        val store = FakeStore()
        store.failCommitWhere = { true }
        val sent = mutableListOf<Map<String, Any?>>()

        oneLaunch(store, exits, snapshots, limits = limits, submit = { _, extras -> sent += extras; "ticket" })
        assertTrue(sent.isEmpty())
        assertEquals(SystemKillProgress(), store.persisted)

        store.failCommitWhere = { false }
        oneLaunch(store, exits, snapshots, limits = limits, submit = { _, extras -> sent += extras; "ticket" })
        oneLaunch(store, exits, snapshots, limits = limits, submit = { _, extras -> sent += extras; "ticket" })

        assertEquals(listOf<Any?>(2), sent.map { it["crash_process_id"] })
        val dropped = sent.single()["crash_background_dropped"] as JSONArray
        assertEquals(1, dropped.getJSONObject(0).getInt("count"))
        assertTrue(store.persisted.ledger.accumulating.isEmpty())
    }

    /**
     * Persisted state. A commit matching [dieOnCommitWhere] is lost and kills the "process"; one
     * matching [failCommitWhere] is not saved and returns false, as `Editor.commit()` can.
     */
    private class FakeStore(var persisted: SystemKillProgress = SystemKillProgress()) : SystemKillProgressStore {
        var dieOnCommitWhere: (SystemKillProgress) -> Boolean = { false }
        var failCommitWhere: (SystemKillProgress) -> Boolean = { false }
        val commits = mutableListOf<SystemKillProgress>()

        override fun load(): SystemKillProgress = persisted

        override fun commit(progress: SystemKillProgress): Boolean {
            if (dieOnCommitWhere(progress)) throw IllegalStateException("process died before this commit")
            if (failCommitWhere(progress)) return false
            persisted = progress
            commits += progress
            return true
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
        submit = { message, extras, _ -> SystemKillSubmission(submit(message, extras)) },
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
