package build.raft.app.shared

import build.raft.app.shared.RaftBackgroundConnectionPolicy.BackgroundTransitionAction
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

    @Test
    fun directApkGmsOverrideDefaultsOffAndRunsWhenOptedIn() {
        val gmsDirectDefaultOff =
            Inputs(
                fcmUnavailable = false,
                keepAlivePreferenceEnabled = true,
                signedIn = true,
                messageNotificationsEnabled = true,
                systemNotificationsAllowed = true,
                directGmsKeepAliveOverrideAllowed = true,
                directGmsKeepAliveOverrideEnabled = false,
            )
        assertEquals(Decision.Stop("play_services_available"), RaftBackgroundConnectionPolicy.decide(gmsDirectDefaultOff))

        // Direct APK opt-in ON with GMS runs even when the separate Background realtime switch is off.
        val gmsDirectOptedIn =
            gmsDirectDefaultOff.copy(
                keepAlivePreferenceEnabled = false,
                directGmsKeepAliveOverrideEnabled = true,
            )
        assertEquals(Decision.Run, RaftBackgroundConnectionPolicy.decide(gmsDirectOptedIn))
        assertEquals(Decision.Stop("signed_out"), RaftBackgroundConnectionPolicy.decide(gmsDirectOptedIn.copy(signedIn = false)))
        assertEquals(
            Decision.Stop("message_notifications_disabled"),
            RaftBackgroundConnectionPolicy.decide(gmsDirectOptedIn.copy(messageNotificationsEnabled = false)),
        )
        assertEquals(
            Decision.Stop("system_notifications_blocked"),
            RaftBackgroundConnectionPolicy.decide(gmsDirectOptedIn.copy(systemNotificationsAllowed = false)),
        )
    }

    @Test
    fun playBuildNeverHonorsPersistedDirectGmsOverrideFlag() {
        val playWithStaleFlag =
            Inputs(
                fcmUnavailable = false,
                keepAlivePreferenceEnabled = true,
                signedIn = true,
                messageNotificationsEnabled = true,
                systemNotificationsAllowed = true,
                directGmsKeepAliveOverrideAllowed = false,
                directGmsKeepAliveOverrideEnabled = true,
            )
        assertFalse(
            RaftBackgroundConnectionPolicy.isDirectGmsOverrideActive(
                fcmUnavailable = false,
                directGmsKeepAliveOverrideAllowed = false,
                directGmsKeepAliveOverrideEnabled = true,
            )
        )
        assertEquals(Decision.Stop("play_services_available"), RaftBackgroundConnectionPolicy.decide(playWithStaleFlag))
    }

    @Test
    fun noGmsDevicesIgnoreDirectGmsOverrideAndFollowExistingKeepAlivePreference() {
        assertFalse(
            RaftBackgroundConnectionPolicy.isDirectGmsOverrideActive(
                fcmUnavailable = true,
                directGmsKeepAliveOverrideAllowed = true,
                directGmsKeepAliveOverrideEnabled = true,
            )
        )
        val noGmsPrefOffWithStaleOverride =
            allSatisfied.copy(
                keepAlivePreferenceEnabled = false,
                directGmsKeepAliveOverrideAllowed = true,
                directGmsKeepAliveOverrideEnabled = true,
            )
        assertEquals(Decision.Stop("preference_disabled"), RaftBackgroundConnectionPolicy.decide(noGmsPrefOffWithStaleOverride))
    }

    @Test
    fun shouldEnableBackgroundKeepAliveRespectsServiceDecisionAndPlainPreference() {
        assertTrue(RaftBackgroundConnectionPolicy.shouldEnableBackgroundKeepAlive(false, Decision.Run, keepAlivePreferenceEnabled = false))
        assertFalse(
            RaftBackgroundConnectionPolicy.shouldEnableBackgroundKeepAlive(
                fallbackApplies = false,
                decision = Decision.Stop("system_notifications_blocked"),
                keepAlivePreferenceEnabled = false,
            )
        )
        assertTrue(
            RaftBackgroundConnectionPolicy.shouldEnableBackgroundKeepAlive(
                fallbackApplies = false,
                decision = Decision.Stop("play_services_available"),
                keepAlivePreferenceEnabled = true,
            )
        )
        assertFalse(
            RaftBackgroundConnectionPolicy.shouldEnableBackgroundKeepAlive(
                fallbackApplies = true,
                decision = Decision.Stop("system_notifications_blocked"),
                keepAlivePreferenceEnabled = true,
            )
        )
    }

    @Test
    fun backgroundTransitionStopsStaleServiceWhenOverrideTurnedOffAndNoOpsOnPlay() {
        assertEquals(
            BackgroundTransitionAction.None,
            RaftBackgroundConnectionPolicy.backgroundTransitionAction(
                fallbackApplies = false,
                decision = Decision.Stop("play_services_available"),
                serviceActive = false,
                stopQueued = false,
            )
        )
        assertEquals(
            BackgroundTransitionAction.Stop("play_services_available"),
            RaftBackgroundConnectionPolicy.backgroundTransitionAction(
                fallbackApplies = false,
                decision = Decision.Stop("play_services_available"),
                serviceActive = true,
                stopQueued = false,
            )
        )
    }

    @Test
    fun directApkFcmAutoFallbackRunsEvenWithoutManualOverrideAndNeverRunsOnPlay() {
        val directAutoFallback =
            Inputs(
                fcmUnavailable = false,
                keepAlivePreferenceEnabled = false,
                signedIn = true,
                messageNotificationsEnabled = true,
                systemNotificationsAllowed = true,
                directGmsKeepAliveOverrideAllowed = true,
                directGmsKeepAliveOverrideEnabled = false,
                fcmAutoFallbackActive = true,
            )
        assertTrue(
            RaftBackgroundConnectionPolicy.isDirectGmsAutoFallbackActive(
                fcmUnavailable = false,
                directGmsKeepAliveOverrideAllowed = true,
                fcmAutoFallbackRequested = true,
            )
        )
        assertEquals(Decision.Run, RaftBackgroundConnectionPolicy.decide(directAutoFallback))
        assertEquals(Decision.Stop("signed_out"), RaftBackgroundConnectionPolicy.decide(directAutoFallback.copy(signedIn = false)))
        assertEquals(
            Decision.Stop("message_notifications_disabled"),
            RaftBackgroundConnectionPolicy.decide(directAutoFallback.copy(messageNotificationsEnabled = false)),
        )
        assertEquals(
            Decision.Stop("system_notifications_blocked"),
            RaftBackgroundConnectionPolicy.decide(directAutoFallback.copy(systemNotificationsAllowed = false)),
        )

        // When auto-fallback turns OFF after FCM recovers, manual override ON still keeps running.
        assertEquals(
            Decision.Run,
            RaftBackgroundConnectionPolicy.decide(
                directAutoFallback.copy(
                    directGmsKeepAliveOverrideEnabled = true,
                    fcmAutoFallbackActive = false,
                )
            ),
        )
        // When both manual override and auto-fallback are OFF, service stops cleanly.
        assertEquals(
            Decision.Stop("play_services_available"),
            RaftBackgroundConnectionPolicy.decide(directAutoFallback.copy(fcmAutoFallbackActive = false)),
        )

        // Defense-in-depth: Play build NEVER activates auto-fallback even if requested=true.
        assertFalse(
            RaftBackgroundConnectionPolicy.isDirectGmsAutoFallbackActive(
                fcmUnavailable = false,
                directGmsKeepAliveOverrideAllowed = false,
                fcmAutoFallbackRequested = true,
            )
        )
        assertEquals(
            Decision.Stop("play_services_available"),
            RaftBackgroundConnectionPolicy.decide(directAutoFallback.copy(directGmsKeepAliveOverrideAllowed = false)),
        )
    }
}
