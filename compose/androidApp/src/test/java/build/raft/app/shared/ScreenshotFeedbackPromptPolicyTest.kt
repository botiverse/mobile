package build.raft.app.shared

import build.raft.app.data.RaftPrivacyMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotFeedbackPromptPolicyTest {
    @Test
    fun enablesForServerFlagOrAlphaOnlyWhenSignedInPrivacyOffAndApi34Plus() {
        // Production build (alpha=false) with live server allowlist flag ON
        assertTrue(
            shouldEnableScreenshotFeedback(
                serverFlagEnabled = true,
                buildAlphaEnabled = false,
                signedIn = true,
                privacyMode = RaftPrivacyMode.Off,
                sdkInt = 34,
            )
        )
        // Alpha fallback when server flag is OFF
        assertTrue(
            shouldEnableScreenshotFeedback(
                serverFlagEnabled = false,
                buildAlphaEnabled = true,
                signedIn = true,
                privacyMode = RaftPrivacyMode.Off,
                sdkInt = 34,
            )
        )
        // Both flags OFF -> disabled
        assertFalse(
            shouldEnableScreenshotFeedback(
                serverFlagEnabled = false,
                buildAlphaEnabled = false,
                signedIn = true,
                privacyMode = RaftPrivacyMode.Off,
                sdkInt = 34,
            )
        )
        // Signed out -> strictly blocked even when flags are ON
        assertFalse(
            shouldEnableScreenshotFeedback(
                serverFlagEnabled = true,
                buildAlphaEnabled = true,
                signedIn = false,
                privacyMode = RaftPrivacyMode.Off,
                sdkInt = 34,
            )
        )
        // Privacy mode On or Unknown -> strictly blocked
        assertFalse(
            shouldEnableScreenshotFeedback(
                serverFlagEnabled = true,
                buildAlphaEnabled = true,
                signedIn = true,
                privacyMode = RaftPrivacyMode.On,
                sdkInt = 34,
            )
        )
        assertFalse(
            shouldEnableScreenshotFeedback(
                serverFlagEnabled = true,
                buildAlphaEnabled = true,
                signedIn = true,
                privacyMode = RaftPrivacyMode.Unknown,
                sdkInt = 34,
            )
        )
        // Below Android 14 (API 34) -> disabled
        assertFalse(
            shouldEnableScreenshotFeedback(
                serverFlagEnabled = true,
                buildAlphaEnabled = true,
                signedIn = true,
                privacyMode = RaftPrivacyMode.Off,
                sdkInt = 33,
            )
        )
    }

    @Test
    fun suppressesDuplicateCallbacksInsideMinimumInterval() {
        val gate = ScreenshotFeedbackEventGate(minimumIntervalMillis = 1_500L)

        assertTrue(gate.accept(nowMillis = 10_000L))
        assertFalse(gate.accept(nowMillis = 11_499L))
        assertTrue(gate.accept(nowMillis = 11_500L))
    }

    @Test
    fun deferredRegistrationCancelsBeforeFirstFrameAndStartsWhenFlagTurnsOnAfterLaunch() {
        var registered = 0
        var unregistered = 0
        var scheduled: (() -> Unit)? = null
        val deferred =
            DeferredScreenCaptureRegistration<String>(
                registration =
                    FailClosedScreenCaptureRegistration(
                        register = { registered += 1 },
                        unregister = { unregistered += 1 },
                    ),
                schedule = { task ->
                    scheduled = task
                    { scheduled = null }
                },
            )

        // Start then stop before Frame 1 finishes -> never calls platform register
        deferred.start("cb-1")
        deferred.stop()
        scheduled?.invoke()
        assertFalse(registered > 0)

        // Flag flips ON after launch -> schedules and registers cleanly
        deferred.start("cb-2")
        scheduled?.invoke()
        assertTrue(registered == 1)
        deferred.stop()
        assertTrue(unregistered == 1)
    }
}
