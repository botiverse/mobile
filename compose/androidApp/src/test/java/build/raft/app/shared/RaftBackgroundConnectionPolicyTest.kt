package build.raft.app.shared

import build.raft.app.shared.RaftBackgroundConnectionPolicy.Decision
import build.raft.app.shared.RaftBackgroundConnectionPolicy.Inputs
import build.raft.app.shared.RaftBackgroundConnectionPolicy.StartCommandOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftBackgroundConnectionPolicyTest {
    private val allSatisfied =
        Inputs(
            fcmUnavailable = true,
            keepAlivePreferenceEnabled = true,
            signedIn = true,
            messageNotificationsEnabled = true,
            systemNotificationsAllowed = true,
        )

    @Test
    fun runsOnlyWhenEveryConditionHolds() {
        assertEquals(Decision.Run, RaftBackgroundConnectionPolicy.decide(allSatisfied))
    }

    @Test
    fun playServicesDeviceNeverRunsWhateverElseIsTrue() {
        assertEquals(
            Decision.Stop("play_services_available"),
            RaftBackgroundConnectionPolicy.decide(
                Inputs(
                    fcmUnavailable = false,
                    keepAlivePreferenceEnabled = false,
                    signedIn = false,
                    messageNotificationsEnabled = false,
                    systemNotificationsAllowed = false,
                )
            )
        )
    }

    @Test
    fun defaultIsSeededOnlyForNoPlayServicesDevicesWithoutAUserChoice() {
        assertTrue(RaftBackgroundConnectionPolicy.shouldSeedKeepAliveDefault(true, null, null))
        // An explicit choice, under the namespaced or the legacy key, is never overwritten.
        assertFalse(RaftBackgroundConnectionPolicy.shouldSeedKeepAliveDefault(true, "false", null))
        assertFalse(RaftBackgroundConnectionPolicy.shouldSeedKeepAliveDefault(true, null, "false"))
        assertFalse(RaftBackgroundConnectionPolicy.shouldSeedKeepAliveDefault(true, "true", null))
        // Play services devices keep the old default (off).
        assertFalse(RaftBackgroundConnectionPolicy.shouldSeedKeepAliveDefault(false, null, null))
    }

    @Test
    fun pendingForegroundStartAlwaysPromotesBeforeStopping() {
        val stop = Decision.Stop("preference_disabled")
        assertEquals(
            StartCommandOutcome.PromoteThenStop("stop_requested"),
            RaftBackgroundConnectionPolicy.startCommandOutcome(
                isSystemRestart = false,
                hasRealtimeSession = true,
                stopRequested = true,
                decision = Decision.Run,
            )
        )
        // Inputs changed between the start request and a late (cold start) command: still promote first.
        for (hasSession in listOf(true, false)) {
            assertEquals(
                StartCommandOutcome.PromoteThenStop("preference_disabled"),
                RaftBackgroundConnectionPolicy.startCommandOutcome(
                    isSystemRestart = false,
                    hasRealtimeSession = hasSession,
                    stopRequested = false,
                    decision = stop,
                )
            )
        }
        assertEquals(
            StartCommandOutcome.PromoteAndRun,
            RaftBackgroundConnectionPolicy.startCommandOutcome(
                isSystemRestart = false,
                hasRealtimeSession = false,
                stopRequested = false,
                decision = Decision.Run,
            )
        )
    }
}
