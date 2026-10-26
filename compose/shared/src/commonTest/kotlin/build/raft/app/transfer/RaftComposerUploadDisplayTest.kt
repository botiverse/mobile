package build.raft.app.transfer

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The composer chip states (web MessageInput), from the upload meter only. */
class RaftComposerUploadDisplayTest {
    private fun meter() = RaftTransferMeter(bytesTotal = 1_000L)

    @Test
    fun chipStatesFollowTheMeter() {
        data class Case(val name: String, val status: String, val transfer: RaftTransferProgress?, val expected: RaftComposerUploadDisplay?)
        val cases =
            listOf(
                Case("checking: nothing sent yet", "uploading", null,
                    RaftComposerUploadDisplay(RaftComposerUploadPhase.Checking, null, Res.string.thread_composer_attachment_checking.desc())),
                Case("0%: Uploading over the empty bar", "uploading", meter().progress,
                    RaftComposerUploadDisplay(RaftComposerUploadPhase.Uploading, 0, Res.string.thread_composer_attachment_uploading.desc())),
                Case("unknown total: Uploading", "uploading", RaftTransferMeter().bytes(500L),
                    RaftComposerUploadDisplay(RaftComposerUploadPhase.Uploading, null, Res.string.thread_composer_attachment_uploading.desc())),
                Case("first byte is 1%", "uploading", meter().bytes(1L),
                    RaftComposerUploadDisplay(RaftComposerUploadPhase.Uploading, 1, StringDesc.Raw("1%"))),
                Case("mid", "uploading", meter().bytes(420L),
                    RaftComposerUploadDisplay(RaftComposerUploadPhase.Uploading, 42, StringDesc.Raw("42%"))),
                Case("last byte handed off: finishing, not 100", "uploading", meter().bytes(1_000L),
                    RaftComposerUploadDisplay(RaftComposerUploadPhase.Finishing, 99, Res.string.thread_composer_attachment_finishing.desc())),
                Case("server verifying", "uploading", meter().apply { bytes(1_000L) }.finishing(),
                    RaftComposerUploadDisplay(RaftComposerUploadPhase.Finishing, 99, Res.string.thread_composer_attachment_finishing.desc())),
                Case("queued behind others: no overlay", "queued", null, null),
                Case("failed", "failed", meter().bytes(420L),
                    RaftComposerUploadDisplay(RaftComposerUploadPhase.Failed, null, Res.string.thread_composer_attachment_upload_failed.desc())),
                Case("ready: no overlay", "ready", meter().done(), null)
            )
        for (case in cases) {
            assertEquals(case.expected, raftComposerUploadDisplay(case.status, case.transfer), case.name)
        }
    }

    @Test
    fun theUploadBarNeverReadsHundredBeforeTheServerConfirms() {
        val meter = meter()
        val shown = (50L..1_000L step 50L).map { bytes -> raftComposerUploadDisplay("uploading", meter.bytes(bytes))?.percent }
        assertEquals(99, shown.last())
        assertEquals(shown.filterNotNull().sorted(), shown.filterNotNull(), "never goes back")
        assertNull(raftComposerUploadDisplay("ready", meter.done()), "a confirmed upload is a ready attachment")
    }
}
