package build.raft.app

import build.raft.app.data.AgentBlockedState
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.model.AgentSpawnFailure
import build.raft.app.model.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Web `agentNeedsManualRestart` (botiverse/slock #13345 utils/activity.ts, and its cases in
 * agentRuntimeErrorBanner.behavior.test.tsx): which statuses get Retry.
 */
class AgentStatusRetryTest {
    private fun needs(
        status: AgentStatus?,
        detailKind: String? = null,
        spawnFailure: AgentSpawnFailure? = null,
        runtimeErrorClass: String? = null,
        external: Boolean = false,
        blocked: AgentBlockedState? = null,
        likelyOnline: Boolean = false,
    ) = agentNeedsManualRestart(status, detailKind, spawnFailure, runtimeErrorClass, external, blocked, likelyOnline)

    @Test
    fun theErrorLightGetsRetryExceptARateLimit() {
        assertTrue(needs(AgentStatus.Error))
        assertTrue(needs(AgentStatus.Error, runtimeErrorClass = "AuthError"))
        assertFalse(needs(AgentStatus.Error, runtimeErrorClass = "RateLimitError"), "a restart cannot lift a rate limit")
    }

    @Test
    fun pausedWakesGetRetry() {
        assertTrue(needs(AgentStatus.Offline, detailKind = "wake_crash_loop_blocked"))
        assertTrue(needs(AgentStatus.Offline, detailKind = "terminal_failure_paused"))
        // A failed start without a typed reason (an older daemon): Retry.
        assertTrue(needs(AgentStatus.Offline, detailKind = "runtime_unavailable"))
    }

    @Test
    fun aFailedStartOnlyOnceTheServerPausedItAndNeverForOneOnlyResetFixes() {
        assertFalse(
            needs(AgentStatus.Offline, "runtime_unavailable", AgentSpawnFailure("runtime_start_timeout", autoRestartPaused = false)),
            "the server is still retrying: a tap would race its start"
        )
        assertTrue(needs(AgentStatus.Offline, "runtime_unavailable", AgentSpawnFailure("runtime_not_found", autoRestartPaused = true)))
        assertFalse(needs(AgentStatus.Offline, "runtime_unavailable", AgentSpawnFailure("session_needs_reset", autoRestartPaused = true)))
        assertFalse(needs(AgentStatus.Offline, "runtime_unavailable", AgentSpawnFailure("session_runtime_mismatch", autoRestartPaused = true)))
    }

    @Test
    fun noRetryForAnAgentThatWakesByItselfOrCannotBeReached() {
        assertFalse(needs(AgentStatus.Offline), "plainly offline: it wakes on its next message")
        assertFalse(needs(AgentStatus.Offline, detailKind = "stopped"))
        assertFalse(needs(AgentStatus.Online))
        assertFalse(needs(AgentStatus.Working))
        assertFalse(needs(null))
        assertFalse(needs(AgentStatus.Error, external = true), "an external agent's runtime is not Raft's to restart")
        assertFalse(needs(AgentStatus.Error, blocked = AgentBlockedState.ComputerAccessRevoked), "a restart cannot reach a revoked Computer")
        assertFalse(needs(null, likelyOnline = true))
    }

    @Test
    fun theDetailKindIsTheLiveOneElseTheProfilesWhileTheyAgree() {
        val liveOffline =
            SharedServerMemberStatus(status = AgentStatus.Offline, presence = AgentStatus.Offline.presence, external = false)
                .toRaftAgentStatusIndicatorState()
        val liveKind = liveOffline.copy(detailKind = "wake_crash_loop_blocked")
        assertEquals("wake_crash_loop_blocked", membersAgentStatusDetailKind(liveKind, "active", "offline", "runtime_unavailable"))
        assertEquals("runtime_unavailable", membersAgentStatusDetailKind(liveOffline, "active", "offline", "runtime_unavailable"))
        assertEquals("runtime_unavailable", membersAgentStatusDetailKind(null, "active", "offline", "runtime_unavailable"))
        val liveWorking =
            SharedServerMemberStatus(status = AgentStatus.Working, presence = AgentStatus.Working.presence, external = false)
                .toRaftAgentStatusIndicatorState()
        assertNull(membersAgentStatusDetailKind(liveWorking, "active", "offline", "runtime_unavailable"), "a stale profile kind")
    }

    @Test
    fun aRevokedComputersAgentIsPlacedWithoutItsSource() {
        assertTrue(membersMigrationPlacesWithoutSource("byoc", AgentBlockedState.ComputerAccessRevoked))
        assertTrue(membersMigrationPlacesWithoutSource(null, AgentBlockedState.ComputerAccessRevoked))
        assertFalse(membersMigrationPlacesWithoutSource("hosted", AgentBlockedState.ComputerAccessRevoked))
        assertFalse(membersMigrationPlacesWithoutSource("byoc", null))
    }
}
