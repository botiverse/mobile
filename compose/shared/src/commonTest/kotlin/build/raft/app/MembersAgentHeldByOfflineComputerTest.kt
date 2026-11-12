package build.raft.app

import build.raft.app.data.AgentBlockedState
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Web `agentHeldByOfflineComputer` (#triage:#206), as the agent profile's "now" card reads it
 * (web #14585: "Computer offline" under Recent activity while the agent's Computer is offline).
 */
class MembersAgentHeldByOfflineComputerTest {
    private fun indicator(status: AgentStatus?, detailKind: String? = null) =
        raftAgentStatusIndicatorState(status = status, presence = Presence.Offline, external = false).copy(detailKind = detailKind)

    private fun held(
        computerOffline: Boolean = true,
        indicator: RaftAgentStatusIndicatorState? = indicator(AgentStatus.Offline),
        lifecycleStatus: String? = "active",
        external: Boolean = false,
    ) = membersAgentHeldByOfflineComputer(computerOffline, indicator, lifecycleStatus, external)

    @Test
    fun anAgentOnAnOfflineComputerReadsComputerOfflineWhateverItLastDid() {
        assertTrue(held())
        assertTrue(held(indicator = indicator(AgentStatus.Working)))
        assertTrue(held(indicator = null))
    }

    @Test
    fun itsOwnStateWinsWhenAPersonStoppedItItFailedOrTheServerPausedIt() {
        assertFalse(held(computerOffline = false))
        assertFalse(held(lifecycleStatus = "stopped"))
        assertFalse(held(indicator = indicator(AgentStatus.Error)))
        assertFalse(held(indicator = indicator(AgentStatus.Offline, detailKind = "wake_crash_loop_blocked")))
        assertFalse(held(indicator = indicator(AgentStatus.Offline, detailKind = "terminal_failure_paused")))
        assertFalse(held(external = true))
        assertFalse(held(indicator = indicator(AgentStatus.Error).copy(blocked = AgentBlockedState.ComputerUpdateRequired)))
    }
}
