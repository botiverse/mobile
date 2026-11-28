package build.raft.app.transfer

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The composer chip states (web MessageInput), from the upload meter only. */
class RaftComposerUploadDisplayTest {
    private fun meter() = RaftTransferMeter(bytesTotal = 1_000L)

    @Test
    fun theUploadBarNeverReadsHundredBeforeTheServerConfirms() {
        val meter = meter()
        val shown = (50L..1_000L step 50L).map { bytes -> raftComposerUploadDisplay("uploading", meter.bytes(bytes))?.percent }
        assertEquals(99, shown.last())
        assertEquals(shown.filterNotNull().sorted(), shown.filterNotNull(), "never goes back")
        assertNull(raftComposerUploadDisplay("ready", meter.done()), "a confirmed upload is a ready attachment")
    }

    @Test
    fun removingAndDiscardFailedMapToDedicatedOverlayPhasesWithoutProgressBar() {
        val removing = raftComposerUploadDisplay("removing", null)
        assertEquals(RaftComposerUploadPhase.Removing, removing?.phase)
        assertNull(removing?.percent)
        assertEquals(Res.string.thread_composer_attachment_removing.desc(), removing?.label)

        val discardFailed = raftComposerUploadDisplay("discard_failed", null)
        assertEquals(RaftComposerUploadPhase.DiscardFailed, discardFailed?.phase)
        assertNull(discardFailed?.percent)
        assertEquals(Res.string.thread_composer_attachment_discard_failed.desc(), discardFailed?.label)
    }
}
