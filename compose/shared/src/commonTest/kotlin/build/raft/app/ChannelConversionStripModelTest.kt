package build.raft.app

import build.raft.app.data.ChannelConversionAction
import build.raft.app.data.ChannelConversionActionError
import build.raft.app.data.ChannelConversionSlice
import build.raft.app.data.RaftChannelConversion
import build.raft.app.data.RaftChannelConversionCommandKind
import build.raft.app.data.RaftChannelConversionJob
import build.raft.app.data.RaftChannelConversionJobStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the conversion strip shows for a slice (web botiverse/slock 922c8de664
 * `ChannelConversionStrip.tsx` and `channelConversionStrip.behavior.test.tsx`, the online-move
 * phases of 6ebc85285): one percentage and step per phase, a failure as a notice with Retry, done
 * as nothing, Cancel only while the job can still be canceled, and the strip for server owners and
 * admins only, never on #all.
 */
class ChannelConversionStripModelTest {
    private fun job(
        status: RaftChannelConversionJobStatus = RaftChannelConversionJobStatus.Running,
        phase: String = "move_parent_messages",
        canCancel: Boolean? = true,
        error: String? = null,
        canonicalCopyStarted: Boolean = false,
        uploadCount: Int? = null,
        errorCode: String? = null,
    ) = RaftChannelConversionJob("job-1", status, phase, canCancel, error, canonicalCopyStarted, uploadCount, errorCode)

    private fun slice(job: RaftChannelConversionJob?, commandPending: Boolean = false) =
        ChannelConversionSlice(
            read = true,
            conversion = RaftChannelConversion(commandPending, RaftChannelConversionCommandKind.Start.takeIf { commandPending }, job),
        )

    @Test
    fun everyPhaseHasTheWebsPercentageAndStep() {
        // phase → percent, step (prepare, preserve, verify, access, done).
        val table =
            listOf(
                Triple("prepare", 15, 0),
                Triple("prepare_tasks", 25, 1),
                Triple("move_parent_messages", 45, 1),
                Triple("online_prepare_threads", 45, 1),
                Triple("online_move", 45, 1),
                Triple("online_cancel", 45, 1),
                Triple("prepare_threads", 60, 1),
                Triple("move_thread_messages", 72, 1),
                Triple("verify", 82, 2),
                Triple("online_moved", 82, 2),
                Triple("audience_cutover", 90, 3),
                Triple("residual_cleanup", 95, 3),
                Triple("online_switched", 95, 3),
                Triple("finalize", 98, 3),
                Triple("done", 100, 4),
                Triple("a_phase_from_a_newer_server", 10, 0),
            )
        table.forEach { (phase, percent, step) ->
            val model = channelConversionStripModel(slice(job(phase = phase)))!!
            assertEquals(percent, model.percent, phase)
            assertEquals(step, model.stepIndex, phase)
            assertTrue(model.showsStrip, phase)
        }
    }

    @Test
    fun failedIsANoticeWithRetryAndNoStrip() {
        val model = channelConversionStripModel(slice(job(status = RaftChannelConversionJobStatus.Failed, phase = "verify", canCancel = true, error = "Verification failed")))!!
        assertFalse(model.showsStrip)
        assertTrue(model.failed)
        assertEquals(ChannelConversionErrorText.Server("Verification failed"), model.error)
        assertTrue(model.offersRetry)
        assertTrue(model.offersCancel, "a failed job the server can still undo also offers Cancel")
        assertFalse(channelConversionStripModel(slice(job(status = RaftChannelConversionJobStatus.Failed, canCancel = false)))!!.offersCancel)
    }

    @Test
    fun doneCanceledOrNeverStartedShowsNothing() {
        assertNull(channelConversionStripModel(slice(job(status = RaftChannelConversionJobStatus.Done, phase = "done"))))
        assertNull(channelConversionStripModel(slice(job(status = RaftChannelConversionJobStatus.Canceled))))
        assertNull(channelConversionStripModel(slice(null)))
        assertNull(channelConversionStripModel(ChannelConversionSlice()), "not read yet")
        assertNull(channelConversionStripModel(slice(job()).copy(canceledJobId = "job-1")), "the job this app canceled")
    }

    @Test
    fun cancelOnlyWhileTheJobCanStillBeCanceled() {
        assertFalse(channelConversionStripModel(slice(job(canCancel = false)))!!.offersCancel)
        // An older server sends no canCancel: only while it prepares and nothing was copied (web `canCancel`).
        assertTrue(channelConversionStripModel(slice(job(phase = "prepare", canCancel = null)))!!.offersCancel)
        assertFalse(channelConversionStripModel(slice(job(phase = "prepare", canCancel = null, canonicalCopyStarted = true)))!!.offersCancel)
        assertFalse(channelConversionStripModel(slice(job(phase = "verify", canCancel = null)))!!.offersCancel)
    }

    @Test
    fun anUnreadableStatusPausesTheStepsAndHoldsTheError() {
        val model =
            channelConversionStripModel(
                slice(job(status = RaftChannelConversionJobStatus.Failed, error = "Verification failed")).copy(statusUnavailable = true, outcomeUnknown = true),
            )!!
        assertEquals(ChannelConversionStatusNotice.OutcomeUnknown, model.statusNotice)
        assertTrue(model.paused)
        assertNull(model.error)
    }

    @Test
    fun aFailedActionSaysTheServersWordsOrTheAppsOwnWithItsTraceId() {
        val refused = slice(job()).copy(actionError = ChannelConversionActionError(ChannelConversionAction.Cancel, serverMessage = "Too late to cancel", traceId = "0123456789abcdef0123456789abcdef"))
        val model = channelConversionStripModel(refused)!!
        assertEquals(ChannelConversionErrorText.Server("Too late to cancel"), model.error)
        assertEquals("0123456789abcdef0123456789abcdef", model.errorTraceId)
        assertTrue(model.showsStrip, "a refused cancel stays above the running strip")
        val unknown = slice(job()).copy(actionError = ChannelConversionActionError(ChannelConversionAction.Cancel, noLongerCancelable = true))
        assertEquals(ChannelConversionErrorText.Local(ChannelConversionAction.Cancel, noLongerCancelable = true), channelConversionStripModel(unknown)!!.error)
    }

    @Test
    fun uploadsStillUnderWayGetTheirOwnSentenceWithHowMany() {
        val failed = RaftChannelConversionJobStatus.Failed
        assertEquals(
            ChannelConversionErrorText.UploadsInFlight(3),
            channelConversionStripModel(slice(job(status = failed, errorCode = "channel_conversion_uploads_in_flight", uploadCount = 3, error = "x")))!!.error,
        )
        assertEquals(
            ChannelConversionErrorText.UploadsInFlight(2),
            channelConversionStripModel(slice(job(status = failed, error = "Cannot convert because 2 attachment uploads are still in flight")))!!.error,
        )
        assertEquals(ChannelConversionErrorText.UploadsInFlight(1), channelConversionStripModel(slice(job(status = failed, errorCode = "channel_conversion_uploads_in_flight")))!!.error)
    }

    @Test
    fun onlyServerOwnersAndAdminsSeeItAndNeverOnAll() {
        assertTrue(channelConversionStripEnabled("owner", "ops"))
        assertTrue(channelConversionStripEnabled("admin", "#ops"))
        assertFalse(channelConversionStripEnabled("member", "ops"))
        assertFalse(channelConversionStripEnabled("guest", "ops"))
        assertFalse(channelConversionStripEnabled(null, "ops"))
        assertFalse(channelConversionStripEnabled("owner", "all"))
        assertFalse(channelConversionStripEnabled("admin", "#all"))
    }
}
