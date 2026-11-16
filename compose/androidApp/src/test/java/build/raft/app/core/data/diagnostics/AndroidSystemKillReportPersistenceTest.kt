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

        // Both are handled (the visible one captured into the queue) before any upload is tried.
        assertEquals(sameMillisecond, store.persisted.watermarkMillis)
        assertEquals(listOf(SystemKillExitKey(2, sameMillisecond)), store.persisted.queue.map { it.key })
        // The watermark never passes a millisecond with an unhandled exit.
        val both = planFor(exits, SystemKillProgress())
        assertEquals(
            0L,
            advanceSystemKillWatermark(0L, both, setOf(SystemKillExitKey(1, sameMillisecond))).first,
        )

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

        oneLaunch(store, listOf(exit), listOf(old(1, exit.timestampMillis)), limits = limits(backgroundPerDay = 0))

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
        // Review 2 was attempted and holds a batch; evicted, it hands the batch back to the ledger.
        val bound = BackgroundDropBatch("batch-1", listOf(BackgroundDropEntry(12600001L, "2026-11-15", 2)))
        listOf(
            queued(1, SystemKillBudget.Visible),
            queued(2, SystemKillBudget.Review).copy(attempted = true, sentBatch = bound),
            queued(3, SystemKillBudget.Visible),
        ).forEach { progress = progress.enqueue(it, cap = 3) }

        progress = progress.enqueue(queued(4, SystemKillBudget.Review), cap = 3) // evicts review 2
        assertEquals(listOf(1, 3, 4), progress.queue.map { it.key.pid })
        assertEquals(bound, progress.ledger.pending)
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
    fun anEvictedInBudgetBackgroundReportIsCountedAsAnEvictionNotABudgetDrop() {
        fun queued(pid: Int, budget: SystemKillBudget) =
            QueuedSystemKillReport(SystemKillExitKey(pid, now - 10_000L + pid), budget, 12600001L, "m$pid", emptyMap())
        // A full queue (cap 2) holding an in-budget background report; a new visible exit arrives.
        var progress = SystemKillProgress(queue = listOf(queued(1, SystemKillBudget.Background), queued(2, SystemKillBudget.Visible)))

        progress = progress.enqueue(queued(3, SystemKillBudget.Visible), cap = 2)

        assertEquals(listOf(2, 3), progress.queue.map { it.key.pid })
        // Survives the device store, then goes out as an eviction with category background.
        val ledger = BackgroundDropLedger.decode(progress.ledger.encode())
        val extras = ledger.attach { "batch-1" }.second!!.extras()
        assertFalse(extras.containsKey("crash_background_dropped"))
        val evicted = extras["crash_pending_evicted"] as JSONArray
        assertEquals(1, evicted.length())
        assertEquals("background", evicted.getJSONObject(0).getString("category"))
        assertEquals(1, evicted.getJSONObject(0).getInt("count"))
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
        assertFalse(decoded.attempted)
        assertEquals(null, decoded.sentBatch)

        val batch =
            BackgroundDropBatch(
                "batch-1",
                listOf(
                    BackgroundDropEntry(12600001L, "2026-11-14", 2),
                    BackgroundDropEntry(12600001L, "2026-11-15", 1, BackgroundDropEntry.Rejected, 422),
                ),
            )
        val attempted = report.copy(attempted = true, sentBatch = batch)
        val decodedAttempted = QueuedSystemKillReport.decodeAll(QueuedSystemKillReport.encodeAll(listOf(attempted))).single()
        assertTrue(decodedAttempted.attempted)
        assertEquals(batch, decodedAttempted.sentBatch)
        // Attempted with no counts stays "no counts".
        val bare = QueuedSystemKillReport.decodeAll(QueuedSystemKillReport.encodeAll(listOf(report.copy(attempted = true)))).single()
        assertTrue(bare.attempted)
        assertEquals(null, bare.sentBatch)
    }

    @Test
    fun aQueueEntryStoredBeforeTheBatchWasBoundLoadsAsNotAttemptedAndTakesTheOldPendingBatch() = runTest {
        // Written by the previous version: no attempted / sent_batch, and a frozen pending batch in the ledger.
        val stored =
            """[{"pid":9,"timestamp":${now - 50_000L},"budget":"Visible","version_code":12600001,""" +
                """"message":"old","extras":{"crash_process_id":9}}]"""
        val ledger =
            """{"pending_id":"batch-old","pending":[{"version_code":12600001,"exit_day_utc":"2026-11-14",""" +
                """"category":"background","http_status":0,"count":3}],"accumulating":[]}"""
        val old = QueuedSystemKillReport.decodeAll(stored).single()
        assertFalse(old.attempted)
        assertEquals(null, old.sentBatch)
        assertEquals(9, (old.extras["crash_process_id"] as Number).toInt())

        val store = FakeStore(SystemKillProgress(ledger = BackgroundDropLedger.decode(ledger), queue = listOf(old)))
        val sent = mutableListOf<Map<String, Any?>>()
        oneLaunch(store, emptyList(), submit = { _, extras -> sent += extras; throw IOException("offline") })
        oneLaunch(store, emptyList(), submit = { _, extras -> sent += extras; "ticket" })

        // The old batch goes out under its own id with that report, and is resent unchanged.
        assertEquals(listOf("batch-old", "batch-old"), sent.map { it["crash_background_dropped_id"] })
        assertEquals(sent[0]["crash_background_dropped"].toString(), sent[1]["crash_background_dropped"].toString())
        assertEquals(null, store.persisted.ledger.pending)
        assertTrue(store.persisted.queue.isEmpty())
    }

    @Test
    fun aResendAfterALostResponseCarriesExactlyTheFirstAttemptsCountsAndKeepsNewerOnesForTheNextReport() = runTest {
        // Hands stored the first attempt; the app never saw the answer (offline or died before the
        // commit). Hands replays a resend with the first ticket and stores nothing from it.
        for (initialCounts in listOf(false, true)) {
            for (diesBeforeCommit in listOf(false, true)) {
                val case = "initialCounts=$initialCounts diesBeforeCommit=$diesBeforeCommit"
                val ledger = if (initialCounts) BackgroundDropLedger().record(12600001L, now - 50_000L) else BackgroundDropLedger()
                val store = FakeStore(SystemKillProgress(ledger = ledger))
                val a = exit(pid = 1, at = now - 3_000L, importance = IMPORTANCE_VISIBLE)
                val sent = mutableListOf<Map<String, Any?>>()
                var ids = 0
                val newId = { "batch-${++ids}" }

                if (diesBeforeCommit) store.failCommitWhere = { progress -> progress.queue.isEmpty() }
                oneLaunch(store, listOf(a), newBatchId = newId, submit = { _, extras ->
                    sent += extras
                    if (diesBeforeCommit) "ticket-a" else throw IOException("response lost")
                })
                store.failCommitWhere = { false }
                assertEquals(case, 1, store.persisted.queue.size)

                // Next launch: a background kill over the budget is counted, then A is resent.
                val dropped = exit(pid = 2, at = now - 2_000L, importance = IMPORTANCE_CACHED)
                oneLaunch(
                    store,
                    listOf(a, dropped),
                    listOf(old(2, dropped.timestampMillis)),
                    limits = limits(backgroundPerDay = 0),
                    newBatchId = newId,
                    submit = { _, extras -> sent += extras; "ticket-a" },
                )
                assertEquals(case, 2, sent.size)
                assertEquals(case, wire(sent[0]), wire(sent[1]))
                assertTrue(case, store.persisted.queue.isEmpty())
                // The new drop was not sent with the replay; it waits for the next report.
                assertEquals(case, 1, store.persisted.ledger.accumulating.single().count)

                oneLaunch(store, listOf(exit(pid = 3, at = now - 1_000L, importance = IMPORTANCE_VISIBLE)), newBatchId = newId, submit = { _, extras ->
                    sent += extras
                    "ticket-c"
                })
                val next = sent.last()
                assertNotEquals(case, sent[0]["crash_background_dropped_id"], next["crash_background_dropped_id"])
                assertEquals(case, 1, (next["crash_background_dropped"] as JSONArray).getJSONObject(0).getInt("count"))
                assertTrue(case, store.persisted.ledger.accumulating.isEmpty())
            }
        }
    }

    @Test
    fun aNewerReportSentFirstNeverTakesTheBatchBoundToAnAttemptedReport() = runTest {
        // Background report A is attempted with the counts (batch-1) and fails.
        val store = FakeStore(SystemKillProgress(ledger = BackgroundDropLedger().record(12600001L, now - 50_000L)))
        val a = exit(pid = 1, at = now - 3_000L, importance = IMPORTANCE_CACHED)
        val snapshots = mutableListOf(old(1, a.timestampMillis))
        val sent = mutableListOf<Map<String, Any?>>()
        var ids = 0
        val newId = { "batch-${++ids}" }
        oneLaunch(store, listOf(a), snapshots, newBatchId = newId, submit = { _, extras -> sent += extras; throw IOException("offline") })
        assertEquals("batch-1", store.persisted.queue.single().sentBatch?.id)

        // Next launch: a background kill over the budget is counted, and a new visible report C
        // goes out before A (visible first).
        val dropped = exit(pid = 2, at = now - 2_000L, importance = IMPORTANCE_CACHED)
        snapshots += old(2, dropped.timestampMillis)
        val c = exit(pid = 3, at = now - 1_000L, importance = IMPORTANCE_VISIBLE)
        oneLaunch(store, listOf(a, dropped, c), snapshots, limits = limits(backgroundPerDay = 1), newBatchId = newId, submit = { _, extras ->
            sent += extras
            "ticket"
        })

        assertEquals(listOf<Any?>(1, 3, 1), sent.map { it["crash_process_id"] })
        // C carries only the new drop, under a new id; A's resend is its first attempt, unchanged.
        assertEquals("batch-2", sent[1]["crash_background_dropped_id"])
        assertEquals(wire(sent[0]), wire(sent[2]))
        val counts =
            sent.drop(1).mapNotNull { it["crash_background_dropped"] as JSONArray? }
                .flatMap { array -> (0 until array.length()).map { array.getJSONObject(it).getInt("count") } }
        assertEquals(listOf(1, 1), counts)
        assertTrue(store.persisted.queue.isEmpty())
        assertEquals(null, store.persisted.ledger.pending)
        assertTrue(store.persisted.ledger.accumulating.isEmpty())
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

    @Test
    fun aNewVisibleExitIsCapturedAndSentBeforeAnOldReviewReportThatKeepsFailing() = runTest {
        val oldReview =
            QueuedSystemKillReport(SystemKillExitKey(9, now - 50_000L), SystemKillBudget.Review, 12600001L, "old review", mapOf("crash_process_id" to 9))
        val store = FakeStore(SystemKillProgress(queue = listOf(oldReview)))
        val visible = exit(pid = 2, at = now - 1_000L, importance = IMPORTANCE_VISIBLE)
        val attempts = mutableListOf<String>()

        oneLaunch(store, listOf(visible), submit = { message, _ ->
            attempts += message
            if (message == "old review") throw IOException("server error") else "ticket"
        })

        assertEquals(2, attempts.size)
        assertTrue(attempts[0], attempts[0].startsWith("System kill:")) // the new visible one first
        assertEquals("old review", attempts[1])
        assertEquals(listOf(oldReview.copy(attempted = true)), store.persisted.queue)
        assertEquals(listOf(now), store.persisted.recent(SystemKillBudget.Visible, now))
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
    fun aMixedQueueIsSentByBudgetWithinEachPerLaunchLimit() = runTest {
        fun queued(pid: Int, budget: SystemKillBudget) =
            QueuedSystemKillReport(SystemKillExitKey(pid, now - 10_000L + pid), budget, 12600001L, budget.label, emptyMap())
        val queue =
            (1..4).map { queued(it, SystemKillBudget.Background) } +
                (5..8).map { queued(it, SystemKillBudget.Review) } +
                (9..12).map { queued(it, SystemKillBudget.Visible) }
        val store = FakeStore(SystemKillProgress(queue = queue))
        val sent = mutableListOf<String>()

        oneLaunch(store, emptyList(), submit = { message, _ -> sent += message; "ticket" })

        assertEquals(List(3) { "visible" } + List(3) { "review" } + List(3) { "background" }, sent)
        assertEquals(listOf(4, 8, 12), store.persisted.queue.map { it.key.pid }.sorted())
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
    fun aMalformedReportRejectedWith422IsGivenUpAndCountedInCrashRejected() = runTest {
        val bad = QueuedSystemKillReport(SystemKillExitKey(9, now - 50_000L), SystemKillBudget.Visible, 12600001L, "bad", emptyMap())
        // It takes the waiting drop count with it; rejected, the count goes back for a later report.
        val store = FakeStore(SystemKillProgress(ledger = BackgroundDropLedger().record(12600001L, now - 60_000L), queue = listOf(bad)))

        oneLaunch(store, emptyList(), submit = { _, _ -> throw HandsFeedbackException(422, "invalid") })
        assertTrue(store.persisted.queue.isEmpty())
        assertTrue(store.persisted.recent(SystemKillBudget.Visible, now).isEmpty())

        // The next uploaded report carries it.
        val carried = mutableListOf<Map<String, Any?>>()
        oneLaunch(store, listOf(exit(pid = 2, at = now - 1_000L, importance = IMPORTANCE_VISIBLE)), submit = { _, extras ->
            carried += extras
            "ticket"
        })
        val rejected = carried.single()["crash_rejected"] as JSONArray
        assertEquals(422, rejected.getJSONObject(0).getInt("http_status"))
        assertEquals(1, rejected.getJSONObject(0).getInt("count"))
        assertEquals(12600001L, rejected.getJSONObject(0).getLong("version_code"))
        assertEquals(1, (carried.single()["crash_background_dropped"] as JSONArray).getJSONObject(0).getInt("count"))
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

    @Test
    fun aReportAcceptedButNotSavedIsResentWithTheSameEventAndBatchIds() = runTest {
        val store = FakeStore(SystemKillProgress(ledger = BackgroundDropLedger().record(12600001L, now - 50_000L)))
        val exits = listOf(exit(pid = 2, at = now - 1_000L, importance = IMPORTANCE_VISIBLE))
        val sent = mutableListOf<Map<String, Any?>>()
        var ids = 0

        // Hands accepts it, but the commit after the upload is not saved.
        store.failCommitWhere = { progress -> progress.queue.isEmpty() && progress.handledAboveWatermark.isEmpty() && progress.watermarkMillis > 0L }
        oneLaunch(store, exits, newBatchId = { "batch-${++ids}" }, submit = { _, extras -> sent += extras; "ticket" })
        assertEquals(1, store.persisted.queue.size)

        store.failCommitWhere = { false }
        oneLaunch(store, exits, newBatchId = { "batch-${++ids}" }, submit = { _, extras -> sent += extras; "ticket" })

        assertEquals(2, sent.size)
        assertEquals(sent[0]["crash_exit_event_id"], sent[1]["crash_exit_event_id"])
        assertEquals("batch-1", sent[0]["crash_background_dropped_id"])
        assertEquals("batch-1", sent[1]["crash_background_dropped_id"])
        assertTrue(store.persisted.queue.isEmpty())
    }

    /** The upload's fields as sent (a JSONArray compares by identity). */
    private fun wire(extras: Map<String, Any?>): Map<String, String> = extras.mapValues { it.value.toString() }

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

    private fun planFor(exits: List<SystemKillExit>, progress: SystemKillProgress) =
        planSystemKillReports(exits, emptyList(), 0L, progress.watermarkMillis, emptyList(), now, 3, 5, mainProcess)

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
