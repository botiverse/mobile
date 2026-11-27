package build.raft.app

import build.raft.app.accepts
import build.raft.app.thread.accepts
import kotlin.test.Test
import kotlin.test.assertFalse

class ThreadAttachmentUploadAttemptFenceTest {
    @Test
    fun removeTerminalRejectsCompletionWithoutAReplacementAttempt() {
        val admission = requireNotNull(ThreadAttachmentUploadAttemptFence().begin("picked-local-1", "draft-a"))
        val removed = admission.fence.terminate("draft-a", setOf("picked-local-1"))

        assertFalse(removed.accepts("picked-local-1", "draft-a", admission.attemptToken))
        assertFalse(
            removed
                .complete("picked-local-1", "draft-a", admission.attemptToken)
                .accepts("picked-local-1", "draft-a", admission.attemptToken)
        )
    }
}
