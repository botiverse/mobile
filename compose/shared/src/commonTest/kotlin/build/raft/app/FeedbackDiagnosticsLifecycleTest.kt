package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackDiagnosticsLifecycleTest {
    private val binding = FeedbackDiagnosticsBinding("account-1", 4L, 9L)

    @Test
    fun staleAndDuplicateRequestsAreRejected() {
        val lifecycle = FeedbackDiagnosticsLifecycle()
        assertTrue(lifecycle.start("request-1", binding))
        assertFalse(lifecycle.start("request-1", binding))
        assertFalse(lifecycle.start("request-2", binding))
        assertFalse(lifecycle.beginUpload("request-1", binding.copy(webViewGeneration = 10L), 12L))
        assertTrue(lifecycle.beginUpload("request-1", binding, 12L))
    }

    @Test
    fun cancellationInvalidatesLateProgressAndDone() {
        val lifecycle = FeedbackDiagnosticsLifecycle()
        lifecycle.start("request-1", binding)
        lifecycle.beginUpload("request-1", binding, 12L)
        assertTrue(lifecycle.cancel("request-1", binding))
        assertFalse(lifecycle.cancel("request-1", binding))
        assertNull(lifecycle.progress("request-1", binding, 12L, 12L))
        assertNull(
            lifecycle.done(
                "request-1",
                binding,
                FeedbackDiagnosticsReference("r2/key", "diag.zip", "application/zip", 12L),
            )
        )
    }

    @Test
    fun terminalDoneIsOneShotAndLateEventsAreIgnored() {
        val lifecycle = FeedbackDiagnosticsLifecycle()
        lifecycle.start("request-1", binding)
        lifecycle.beginUpload("request-1", binding, 12L)
        val done = lifecycle.done(
            "request-1",
            binding,
            FeedbackDiagnosticsReference("r2/key", "diag.zip", "application/zip", 12L),
        )
        assertEquals("r2/key", (done as FeedbackDiagnosticsLifecycleEvent.Done).ref.r2Key)
        assertNull(lifecycle.done("request-1", binding, done.ref))
        assertNull(lifecycle.failed("request-1", binding, FeedbackDiagnosticsFailureReason.UploadFailed))
    }
}
