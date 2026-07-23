package build.raft.app.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotFeedbackPromptPolicyTest {
    @Test
    fun requiresBuildRuntimeAndAndroid14Gates() {
        assertTrue(
            shouldEnableScreenshotFeedback(
                buildEnabled = true,
                runtimeEnabled = true,
                sdkInt = 34,
            )
        )
        assertFalse(shouldEnableScreenshotFeedback(false, true, 34))
        assertFalse(shouldEnableScreenshotFeedback(true, false, 34))
        assertFalse(shouldEnableScreenshotFeedback(true, true, 33))
    }

    @Test
    fun suppressesDuplicateCallbacksInsideMinimumInterval() {
        val gate = ScreenshotFeedbackEventGate(minimumIntervalMillis = 1_500L)

        assertTrue(gate.accept(nowMillis = 10_000L))
        assertFalse(gate.accept(nowMillis = 11_499L))
        assertTrue(gate.accept(nowMillis = 11_500L))
    }

    @Test
    fun rejectsStoppedAndPreviousStartCallbacks() {
        val gate = ScreenshotFeedbackListeningGate()
        val firstGeneration = gate.start()

        assertTrue(gate.accepts(firstGeneration))
        gate.stop()
        assertFalse(gate.accepts(firstGeneration))

        val secondGeneration = gate.start()
        assertFalse(gate.accepts(firstGeneration))
        assertTrue(gate.accepts(secondGeneration))
    }
}
