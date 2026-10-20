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
    fun eachMissingConditionStopsWithItsReason() {
        val cases =
            listOf(
                allSatisfied.copy(fcmUnavailable = false) to "play_services_available",
                allSatisfied.copy(keepAlivePreferenceEnabled = false) to "preference_disabled",
                allSatisfied.copy(signedIn = false) to "signed_out",
                allSatisfied.copy(messageNotificationsEnabled = false) to "message_notifications_disabled",
                allSatisfied.copy(systemNotificationsAllowed = false) to "system_notifications_blocked",
            )
        for ((inputs, reason) in cases) {
            assertEquals(inputs.toString(), Decision.Stop(reason), RaftBackgroundConnectionPolicy.decide(inputs))
        }
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
    fun sessionEndsAndOptOutsStopTheServiceButTransientDisconnectsDoNot() {
        for (reason in listOf("logout", "auth_invalid", "api_environment_changed", "preference_disabled", "missing_session")) {
            assertTrue(reason, RaftBackgroundConnectionPolicy.isTerminalStopReason(reason))
        }
        for (reason in listOf("server_switch", "shared_disconnect", "server_removed")) {
            assertFalse(reason, RaftBackgroundConnectionPolicy.isTerminalStopReason(reason))
        }
    }

    @Test
    fun systemRestartWithoutARealtimeSessionStops() {
        assertFalse(RaftBackgroundConnectionPolicy.shouldKeepRunningAfterServiceStart(isSystemRestart = true, hasRealtimeSession = false))
        assertTrue(RaftBackgroundConnectionPolicy.shouldKeepRunningAfterServiceStart(isSystemRestart = true, hasRealtimeSession = true))
        assertTrue(RaftBackgroundConnectionPolicy.shouldKeepRunningAfterServiceStart(isSystemRestart = false, hasRealtimeSession = false))
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
    fun stickyRestartStopsWithoutForegroundUnlessSessionAndPolicyAllow() {
        fun outcome(hasSession: Boolean, decision: Decision) =
            RaftBackgroundConnectionPolicy.startCommandOutcome(
                isSystemRestart = true,
                hasRealtimeSession = hasSession,
                stopRequested = false,
                decision = decision,
            )
        assertEquals(StartCommandOutcome.StopWithoutForeground, outcome(false, Decision.Run))
        assertEquals(StartCommandOutcome.StopWithoutForeground, outcome(true, Decision.Stop("signed_out")))
        assertEquals(StartCommandOutcome.PromoteAndRun, outcome(true, Decision.Run))
    }

    @Test
    fun nonTerminalDisconnectKeepsBackgroundKeepAliveOnlyWhileServiceActive() {
        for (reason in listOf("server_switch", "page_released", "shared_disconnect", "server_deleted")) {
            assertTrue(reason, RaftBackgroundConnectionPolicy.shouldRestoreBackgroundKeepAliveAfterDisconnect(reason, true))
            assertFalse(reason, RaftBackgroundConnectionPolicy.shouldRestoreBackgroundKeepAliveAfterDisconnect(reason, false))
        }
        for (reason in listOf("logout", "auth_invalid", "api_environment_changed", "process_teardown", "preference_disabled", "missing_session")) {
            assertFalse(reason, RaftBackgroundConnectionPolicy.shouldRestoreBackgroundKeepAliveAfterDisconnect(reason, true))
        }
    }

    @Test
    fun seededPreferenceDoesNotKeepAliveWhenFallbackCannotRun() {
        assertFalse(
            RaftBackgroundConnectionPolicy.shouldEnableBackgroundKeepAlive(
                fallbackApplies = true,
                decision = Decision.Stop("system_notifications_blocked"),
            )
        )
        assertFalse(
            RaftBackgroundConnectionPolicy.shouldEnableBackgroundKeepAlive(
                fallbackApplies = true,
                decision = Decision.Stop("message_notifications_disabled"),
            )
        )
        assertTrue(RaftBackgroundConnectionPolicy.shouldEnableBackgroundKeepAlive(true, Decision.Run))
        // Play services devices: the plain foreground keep-alive preference is unchanged.
        assertTrue(
            RaftBackgroundConnectionPolicy.shouldEnableBackgroundKeepAlive(
                fallbackApplies = false,
                decision = Decision.Stop("play_services_available"),
            )
        )
    }
}
