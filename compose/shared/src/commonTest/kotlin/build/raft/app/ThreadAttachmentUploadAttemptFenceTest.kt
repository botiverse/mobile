package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThreadAttachmentUploadAttemptFenceTest {
    @Test
    fun currentAttemptIsAcceptedUntilItCompletes() {
        val admission = ThreadAttachmentUploadAttemptFence().begin("picked-local-1")

        assertTrue(admission.fence.accepts("picked-local-1", admission.attemptToken))
        assertFalse(
            admission.fence
                .complete("picked-local-1", admission.attemptToken)
                .accepts("picked-local-1", admission.attemptToken)
        )
    }

    @Test
    fun failedAttemptRejectsLateCompletionAndRetryUsesANewToken() {
        val first = ThreadAttachmentUploadAttemptFence().begin("picked-local-1")
        val failed = first.fence.terminate(setOf("picked-local-1"))

        assertFalse(failed.accepts("picked-local-1", first.attemptToken))

        val retry = failed.begin("picked-local-1")
        assertNotEquals(first.attemptToken, retry.attemptToken)
        assertTrue(retry.fence.accepts("picked-local-1", retry.attemptToken))
        assertFalse(retry.fence.accepts("picked-local-1", first.attemptToken))
    }

    @Test
    fun siblingFailureTerminatesEveryActiveAttemptBeforeLateResultsArrive() {
        val first = ThreadAttachmentUploadAttemptFence().begin("picked-local-1")
        val second = first.fence.begin("picked-local-2")
        val failedBatch = second.fence.terminate(second.fence.activeAttemptTokens.keys)

        assertFalse(failedBatch.accepts("picked-local-1", first.attemptToken))
        assertFalse(failedBatch.accepts("picked-local-2", second.attemptToken))
    }

    @Test
    fun routeDisposalCannotContaminateANewDraftUpload() {
        val old = ThreadAttachmentUploadAttemptFence().begin("picked-old-draft")
        val disposed = old.fence.terminate(old.fence.activeAttemptTokens.keys)
        val fresh = disposed.begin("picked-new-draft")

        assertFalse(fresh.fence.accepts("picked-old-draft", old.attemptToken))
        assertTrue(fresh.fence.accepts("picked-new-draft", fresh.attemptToken))
    }

    @Test
    fun removeTerminalRejectsCompletionWithoutAReplacementAttempt() {
        val admission = ThreadAttachmentUploadAttemptFence().begin("picked-local-1")
        val removed = admission.fence.terminate(setOf("picked-local-1"))

        assertFalse(removed.accepts("picked-local-1", admission.attemptToken))
    }
}
