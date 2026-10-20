package build.raft.app

import build.raft.app.data.NeutralAgentStatusLabel
import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftHomeDmAgentLiveActivityTest {
    private fun state(
        status: AgentStatus?,
        statusLabel: String? = null,
        external: Boolean = false,
        presence: Presence = status?.presence ?: Presence.Offline
    ): RaftAgentStatusIndicatorState =
        SharedServerMemberStatus(
            status = status,
            presence = presence,
            statusLabel = statusLabel,
            external = external
        ).toRaftAgentStatusIndicatorState()

    @Test
    fun unresolvedStatusShowsNothing() {
        assertNull(raftHomeDmAgentLiveActivity(null))
    }

    @Test
    fun workingWithDetailShowsTheDetailVerbatim() {
        assertEquals(
            RaftHomeDmAgentLiveActivity.Detail("Tool finished"),
            raftHomeDmAgentLiveActivity(state(AgentStatus.Working, "Tool finished"))
        )
        assertEquals(
            RaftHomeDmAgentLiveActivity.Detail("Running command..."),
            raftHomeDmAgentLiveActivity(state(AgentStatus.Working, "  Running command...  "))
        )
    }

    @Test
    fun workingWithoutDetailShowsGenericWorking() {
        listOf(null, "", "   ", "Working", "working", "Working...", "Working…", NeutralAgentStatusLabel).forEach { label ->
            assertEquals(
                RaftHomeDmAgentLiveActivity.Working,
                raftHomeDmAgentLiveActivity(state(AgentStatus.Working, label)),
                "label=$label"
            )
        }
    }

    @Test
    fun thinkingAlwaysShowsThinkingAndNeverTheThoughtText() {
        assertEquals(
            RaftHomeDmAgentLiveActivity.Thinking,
            raftHomeDmAgentLiveActivity(state(AgentStatus.Thinking, "Let me check the failing test first"))
        )
        assertEquals(
            RaftHomeDmAgentLiveActivity.Thinking,
            raftHomeDmAgentLiveActivity(state(AgentStatus.Thinking))
        )
    }

    @Test
    fun idleOnlineOfflineAndErrorShowNothing() {
        listOf(
            AgentStatus.Online,
            AgentStatus.Idle,
            AgentStatus.Offline,
            AgentStatus.Disconnected,
            AgentStatus.Error
        ).forEach { status ->
            assertNull(raftHomeDmAgentLiveActivity(state(status, "Tool finished")), "status=$status")
        }
        assertNull(raftHomeDmAgentLiveActivity(state(status = null, presence = Presence.Active)))
    }

    @Test
    fun externalAgentsNeverShowLiveActivity() {
        assertNull(raftHomeDmAgentLiveActivity(state(AgentStatus.Working, "Tool finished", external = true)))
        assertNull(raftHomeDmAgentLiveActivity(state(AgentStatus.Thinking, external = true)))
    }
}
