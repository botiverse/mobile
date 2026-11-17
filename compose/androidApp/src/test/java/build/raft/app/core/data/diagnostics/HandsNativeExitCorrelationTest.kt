package build.raft.app.core.data.diagnostics

import android.app.ActivityManager.RunningAppProcessInfo as Importance
import android.app.Application
import android.app.ApplicationExitInfo
import build.raft.app.shared.RaftBuildStamp
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Native-crash exits are skipped only on a confirmed Hands ticket for that exit
 * (HandsNativeExitCorrelation.kt); otherwise reported once, after a bounded wait while the SDK
 * still holds a record that may be the exit's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HandsNativeExitCorrelationTest {
    @get:Rule val folder = TemporaryFolder()

    private val mainProcess = "build.raft.app"
    private val start = 1_000_000L
    private val exitAt = 2_000_000L
    private val now = exitAt + 60_000L
    private val hour = 60L * 60 * 1000
    private val identity = NativeExitIdentity(pid = 4242, exitTimestampMillis = exitAt, processStartMillis = start)

    // --- Matching ------------------------------------------------------------------------------

    @Test
    fun aReusedPidWithAnotherProcessStartDoesNotMatch() {
        val ticket = HandsNativeTicketConfirmation(pid = 4242, crashAtMillis = exitAt - 100L, processStartMillis = start, ticketId = "t-1")
        assertTrue(ticket.matchesExit(identity))
        // Same pid and crash time, but another process: a reused pid.
        assertFalse(ticket.copy(processStartMillis = start + 30_000L).matchesExit(identity))
        // Within the start tolerance it is the same process.
        assertTrue(ticket.copy(processStartMillis = start + ProcessStartToleranceMillis).matchesExit(identity))
        assertFalse(ticket.copy(processStartMillis = start + ProcessStartToleranceMillis + 1).matchesExit(identity))
        // A start unknown on either side never matches: the exit is reported.
        assertFalse(ticket.copy(processStartMillis = null).matchesExit(identity))
        assertFalse(ticket.matchesExit(identity.copy(processStartMillis = null)))
    }

    @Test
    fun aMatchNeedsTheSamePidACrashNearTheExitAndATicketId() {
        val ticket = HandsNativeTicketConfirmation(pid = 4242, crashAtMillis = exitAt, processStartMillis = start, ticketId = "t-1")
        assertFalse(ticket.copy(pid = 4243).matchesExit(identity))
        assertTrue(ticket.copy(crashAtMillis = exitAt - NativeExitMatchWindowMillis).matchesExit(identity))
        assertTrue(ticket.copy(crashAtMillis = exitAt + NativeExitMatchWindowMillis).matchesExit(identity))
        assertFalse(ticket.copy(crashAtMillis = exitAt - NativeExitMatchWindowMillis - 1).matchesExit(identity))
        assertFalse(ticket.copy(crashAtMillis = exitAt + NativeExitMatchWindowMillis + 1).matchesExit(identity))
        // No ticket id: an upload that started is not a filed ticket.
        assertFalse(ticket.copy(ticketId = "").matchesExit(identity))
        // A crash before the process started is another process's.
        val shortLived = identity.copy(processStartMillis = exitAt - 1_000L)
        assertFalse(ticket.copy(crashAtMillis = exitAt - 2_000L, processStartMillis = exitAt - 1_000L).matchesExit(shortLived))
    }

    @Test
    fun aPendingRecordOfAReusedPidBeforeTheProcessStartedIsNotThisExits() {
        val record = HandsNativePendingRecord(pid = 4242, crashAtMillis = exitAt - 1_000L)
        assertTrue(record.mayBelongTo(identity))
        assertFalse(record.copy(pid = 1).mayBelongTo(identity))
        assertFalse(record.copy(crashAtMillis = exitAt - NativeExitMatchWindowMillis - 1).mayBelongTo(identity))
        assertFalse(record.mayBelongTo(identity.copy(processStartMillis = exitAt - 500L)))
    }

    // --- Deciding ------------------------------------------------------------------------------

    @Test
    fun theWaitIsBoundedByLaunchesAndByTime() {
        val pending = listOf(HandsNativePendingRecord(4242, exitAt))
        val wait = NativeExitWait(identity, firstSeenMillis = now)
        assertEquals(NativeExitVerdict.Wait, decideNativeExit(wait, emptyList(), pending, now))
        assertEquals(
            NativeExitVerdict.Report(NativeCorrelationWaitExpired),
            decideNativeExit(wait.copy(launchesWaited = NativeExitMaxLaunchesWaited), emptyList(), pending, now),
        )
        assertEquals(
            NativeExitVerdict.Report(NativeCorrelationWaitExpired),
            decideNativeExit(wait, emptyList(), pending, now + NativeExitMaxWaitMillis),
        )
        // A clock set back is not more time to wait.
        assertEquals(NativeExitVerdict.Report(NativeCorrelationWaitExpired), decideNativeExit(wait, emptyList(), pending, now - 1L))
        // Nothing pending and nothing confirmed: report now.
        assertEquals(NativeExitVerdict.Report(NativeCorrelationUnconfirmed), decideNativeExit(wait, emptyList(), emptyList(), now))
    }

    // --- Across launches -----------------------------------------------------------------------

    @Test
    fun aPendingSdkUploadThatIsLaterConfirmedIsSkipped() = runTest {
        val store = FakeStore()
        val sent = mutableListOf<Map<String, Any?>>()
        val pending = listOf(HandsNativePendingRecord(4242, exitAt + 3L))

        // Launch 1: the SDK still holds the record. Nothing is sent, the decision waits.
        launch(store, sent, pending = pending)
        assertTrue(sent.isEmpty())
        assertEquals(1, store.persisted.queue.single().nativeExit?.launchesWaited)
        assertFalse(store.persisted.queue.single().attempted)

        // Launch 2 (restart): Hands confirmed the SDK's ticket for this exit and process.
        val confirmed = HandsNativeTicketConfirmation(4242, exitAt + 3L, start, "ticket-sdk")
        launch(store, sent, at = now + hour, confirmations = listOf(confirmed))
        assertTrue("confirmed elsewhere: the app files nothing", sent.isEmpty())
        assertTrue(store.persisted.queue.isEmpty())

        // And nothing comes back on a later launch.
        launch(store, sent, at = now + 2 * hour)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aPendingSdkUploadThatNeverConfirmsIsReportedOnce() = runTest {
        val store = FakeStore()
        val sent = mutableListOf<Map<String, Any?>>()
        val pending = listOf(HandsNativePendingRecord(4242, exitAt))

        repeat(NativeExitMaxLaunchesWaited) { launchIndex ->
            launch(store, sent, at = now + launchIndex * hour, pending = pending)
            assertTrue("launch $launchIndex waits", sent.isEmpty())
        }
        launch(store, sent, at = now + NativeExitMaxLaunchesWaited * hour, pending = pending)
        assertEquals(1, sent.size)
        assertEquals(NativeCorrelationWaitExpired, sent.single()["crash_native_correlation"])
        assertEquals("system_kill", sent.single()["crash_type"])
        assertEquals(4242, sent.single()["crash_process_id"])
        assertEquals(exitAt, sent.single()["crash_exit_timestamp"])

        // Accepted: never again, even if a confirmation turns up afterwards.
        launch(store, sent, at = now + 10 * hour, pending = pending, confirmations = listOf(HandsNativeTicketConfirmation(4242, exitAt, start, "late")))
        assertEquals(1, sent.size)
        assertTrue(store.persisted.queue.isEmpty())
    }

    @Test
    fun aRecordThatDisappearedIsNotAConfirmation() = runTest {
        val store = FakeStore()
        val sent = mutableListOf<Map<String, Any?>>()
        launch(store, sent, pending = listOf(HandsNativePendingRecord(4242, exitAt)))
        assertTrue(sent.isEmpty())
        // The SDK deleted its record: uploaded, unparsable or trimmed, the app cannot tell which.
        launch(store, sent, at = now + hour)
        assertEquals(NativeCorrelationUnconfirmed, sent.single()["crash_native_correlation"])
    }

    @Test
    fun aReusedPidConfirmationDoesNotSkipTheExit() = runTest {
        val store = FakeStore()
        val sent = mutableListOf<Map<String, Any?>>()
        val otherProcess = HandsNativeTicketConfirmation(4242, exitAt, start + 60_000L, "ticket-of-another-process")
        launch(store, sent, confirmations = listOf(otherProcess))
        assertEquals(1, sent.size)
        assertEquals(NativeCorrelationUnconfirmed, sent.single()["crash_native_correlation"])
    }

    @Test
    fun anUploadWhoseResponseIsLostIsNotTreatedAsReported() = runTest {
        val store = FakeStore()
        val sent = mutableListOf<Map<String, Any?>>()
        // The upload started and maybe reached Hands, but no ticket id came back.
        launch(store, sent, submit = { _, extras -> sent += extras; throw IOException("connection reset") })
        assertEquals(1, sent.size)
        val queued = store.persisted.queue.single()
        assertTrue("still queued: not reported", queued.attempted)
        assertEquals(NativeCorrelationUnconfirmed, queued.extras["crash_native_correlation"])

        // Next launch: resent as frozen, the same event id, decided once (a confirmation now changes nothing).
        launch(store, sent, at = now + hour, confirmations = listOf(HandsNativeTicketConfirmation(4242, exitAt, start, "sdk")))
        assertEquals(2, sent.size)
        assertEquals(sent[0]["crash_exit_event_id"], sent[1]["crash_exit_event_id"])
        assertEquals(sent[0], sent[1])
        assertTrue(store.persisted.queue.isEmpty())
    }

    @Test
    fun theExitEventIdIsStableAndPerExit() {
        val exit = nativeExit()
        val id = systemKillExitEventId("install-a", exit)
        assertEquals(id, systemKillExitEventId("install-a", exit.copy()))
        assertEquals(32, id.length)
        assertNotEquals(id, systemKillExitEventId("install-a", exit.copy(timestampMillis = exitAt + 1L)))
        assertNotEquals(id, systemKillExitEventId("install-a", exit.copy(pid = 4243)))
        assertNotEquals(id, systemKillExitEventId("install-b", exit))
        // The ticket carries the fields Hands can pair with the SDK's native ticket.
        val extras = systemKillExtras(exit, telemetry = null, installId = "install-a")
        assertEquals(id, extras["crash_exit_event_id"])
        assertEquals(4242, extras["crash_process_id"])
        assertEquals(exitAt, extras["crash_exit_timestamp"])
    }

    @Test
    fun theWaitStateSurvivesARestart() {
        val report =
            QueuedSystemKillReport(
                key = SystemKillExitKey(4242, exitAt),
                budget = SystemKillBudget.Review,
                versionCode = 1L,
                message = "m",
                extras = mapOf("crash_type" to "system_kill"),
                nativeExit = NativeExitWait(identity, firstSeenMillis = now, launchesWaited = 2),
            )
        val unknownStart = report.copy(nativeExit = NativeExitWait(identity.copy(processStartMillis = null), now))
        assertEquals(listOf(report, unknownStart), QueuedSystemKillReport.decodeAll(QueuedSystemKillReport.encodeAll(listOf(report, unknownStart))))
        // Entries written before this field existed load without a wait state.
        val old = report.copy(nativeExit = null)
        assertNull(QueuedSystemKillReport.decodeAll(QueuedSystemKillReport.encodeAll(listOf(old))).single().nativeExit)
    }

    // --- Reading the SDK's records -------------------------------------------------------------

    @Test
    fun onlyThePidAndCrashTimeAreReadFromASdkRecord() {
        assertEquals(
            HandsNativePendingRecord(4242, 1_700_000_000_123L),
            parseHandsNativeRecordHeader("QNC2\nsignal 11\npid 4242\ntid 4250\ncrash_at 1700000000123\nframes\n#00 pc 0x1\n"),
        )
        assertEquals(HandsNativePendingRecord(7, 5L), parseHandsNativeRecordHeader("QNC1\npid 7\ncrash_at 5\n"))
        assertNull(parseHandsNativeRecordHeader("QNC9\npid 7\ncrash_at 5\n"))
        assertNull(parseHandsNativeRecordHeader("QNC2\ncrash_at 5\n"))
        assertNull(parseHandsNativeRecordHeader("QNC2\npid 7\n"))
        assertNull(parseHandsNativeRecordHeader("QNC2\npid -3\ncrash_at 5\n"))
        assertNull(parseHandsNativeRecordHeader(""))
    }

    @Test
    fun aScanCountsUnreadableRecordsAndIgnoresOtherFiles() {
        val dir = handsNativeCrashDir(folder.root)
        assertEquals(HandsNativePendingScan(emptyList(), 0), scanHandsNativePendingRecords(dir))
        dir.mkdirs()
        dir.resolve("a.qnc").writeText("QNC2\npid 4242\ncrash_at 2000000\n")
        dir.resolve("b.qnc").writeText("garbage")
        dir.resolve("a.qnc.exit.json").writeText("{}")
        val scan = scanHandsNativePendingRecords(dir)
        assertEquals(listOf(HandsNativePendingRecord(4242, 2_000_000L)), scan.records)
        assertEquals(1, scan.unreadable)
    }

    // --- Helpers -------------------------------------------------------------------------------

    private fun nativeExit() =
        SystemKillExit(
            pid = 4242,
            timestampMillis = exitAt,
            reasonCode = ApplicationExitInfo.REASON_CRASH_NATIVE,
            status = 6,
            importance = Importance.IMPORTANCE_FOREGROUND,
            pssKb = 0L,
            rssKb = 0L,
            build = RaftBuildStamp.StampedBuild("1.27.0", 12700001L),
            processName = mainProcess,
        )

    /** The exited process's telemetry: it gives the exit its process start. */
    private val snapshot =
        MemoryTelemetrySnapshot(
            pid = 4242,
            processStartMillis = start,
            writtenAtMillis = exitAt - 1_000L,
            foreground = true,
            foregroundChangedAtMillis = start,
            sampledAtMillis = exitAt - 1_000L,
        )

    private suspend fun launch(
        store: FakeStore,
        sent: MutableList<Map<String, Any?>>,
        at: Long = now,
        pending: List<HandsNativePendingRecord> = emptyList(),
        confirmations: List<HandsNativeTicketConfirmation> = emptyList(),
        submit: suspend (String, Map<String, Any?>) -> String = { _, extras -> sent += extras; "ticket-app" },
    ) = runSystemKillReports(
        store = store,
        nowMillis = at,
        limits = SystemKillBudget.entries.associateWith { SystemKillBudgetLimit(3, 5) },
        readPlan = { progress, remaining ->
            val visible = remaining.getValue(SystemKillBudget.Visible)
            planSystemKillReports(
                exits = listOf(nativeExit()),
                snapshots = listOf(snapshot),
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
        buildReport = { item -> systemKillMessage(item.exit, item.telemetry) to systemKillExtras(item.exit, item.telemetry, installId = "install-a") },
        newBatchId = { "batch" },
        submit = submit,
        nativeConfirmations = confirmations,
        nativePending = pending,
    )

    private class FakeStore(var persisted: SystemKillProgress = SystemKillProgress()) : SystemKillProgressStore {
        override fun load(): SystemKillProgress = persisted

        override fun commit(progress: SystemKillProgress): Boolean {
            persisted = progress
            return true
        }
    }
}
