package build.raft.app.core.data.diagnostics

import android.app.ActivityManager.RunningAppProcessInfo as Importance
import android.app.Application
import android.app.ApplicationExitInfo
import build.hands.update.HandsFeedbackException
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

    // --- Across launches -----------------------------------------------------------------------

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
        launch(store, sent, submit = { _, extras, _ -> sent += extras; throw IOException("connection reset") })
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

    // --- Merging by submission id (hands-android-sdk 0.13.2) ------------------------------------

    private val eventId = "3f2c8a3e-9b1d-4c7e-8f00-1a2b3c4d5e6f"

    /** The exited process's record, written by a build on 0.13.2: it holds the SDK's event id. */
    private val withEventId get() = snapshot.copy(handsProcessEventId = eventId, handsProcessStartMillis = start + 3L)

    /** One send as Hands saw it. */
    private data class Sent(val extras: Map<String, Any?>, val submissionId: String?)

    private fun answers(vararg outcomes: () -> SystemKillSubmission): Pair<MutableList<Sent>, suspend (String, Map<String, Any?>, String?) -> SystemKillSubmission> {
        val calls = mutableListOf<Sent>()
        val submit: suspend (String, Map<String, Any?>, String?) -> SystemKillSubmission = { _, extras, id ->
            calls += Sent(extras, id)
            // The last outcome repeats.
            outcomes[minOf(calls.size, outcomes.size) - 1]()
        }
        return calls to submit
    }

    private fun refused() = HandsFeedbackException(409, "{\"error\":\"submission_conflict\"}")

    @Test
    fun a409OnTheFirstSendFallsBackToOneStandaloneSendAndNeverLoops() = runTest {
        val store = FakeStore()
        val (calls, submit) = answers({ throw refused() })
        launch(store, mutableListOf(), telemetry = withEventId, submit = submit)
        // Once under the id, once standalone; the standalone 409 is a permanent rejection.
        assertEquals(listOf(eventId, null), calls.map { it.submissionId })
        assertTrue(store.persisted.queue.isEmpty())
        assertEquals(BackgroundDropEntry.Rejected, store.persisted.ledger.accumulating.single().category)

        launch(store, mutableListOf(), at = now + hour, telemetry = withEventId, submit = submit)
        assertEquals(2, calls.size)
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
        telemetry: MemoryTelemetrySnapshot = snapshot,
        waits: MutableList<Long> = mutableListOf(),
        submit: suspend (String, Map<String, Any?>, String?) -> SystemKillSubmission =
            { _, extras, _ -> sent += extras; SystemKillSubmission("ticket-app") },
    ) = runSystemKillReports(
        store = store,
        nowMillis = at,
        limits = SystemKillBudget.entries.associateWith { SystemKillBudgetLimit(3, 5) },
        readPlan = { progress, remaining ->
            val visible = remaining.getValue(SystemKillBudget.Visible)
            planSystemKillReports(
                exits = listOf(nativeExit()),
                snapshots = listOf(telemetry),
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
        wait = { waits += it },
    )

    private class FakeStore(var persisted: SystemKillProgress = SystemKillProgress()) : SystemKillProgressStore {
        override fun load(): SystemKillProgress = persisted

        override fun commit(progress: SystemKillProgress): Boolean {
            persisted = progress
            return true
        }
    }
}
