package build.raft.app

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
        presence: Presence = status?.presence ?: Presence.Offline,
        detailKind: String? = null,
        toolName: String? = null,
        runtime: String? = null
    ): RaftAgentStatusIndicatorState =
        SharedServerMemberStatus(
            status = status,
            presence = presence,
            statusLabel = statusLabel,
            detailKind = detailKind,
            activityToolName = toolName,
            runtime = runtime,
            external = external
        ).toRaftAgentStatusIndicatorState()

    private fun raw(text: String) =
        RaftHomeDmAgentLiveActivity.Detail(RaftAgentActivityDetailText.Raw(text))

    @Test
    fun unresolvedStatusShowsNothing() {
        assertNull(raftHomeDmAgentLiveActivity(null))
        assertNull(raftHomeDmAgentMeta(null))
    }

    @Test
    fun workingWithDetailShowsTheDetailVerbatim() {
        assertEquals(raw("Tool finished"), raftHomeDmAgentLiveActivity(state(AgentStatus.Working, "Tool finished")))
        assertEquals(raw("Running tests"), raftHomeDmAgentLiveActivity(state(AgentStatus.Working, "  Running tests  ")))
    }

    @Test
    fun clientBuiltToolLabelIsLocalizedByToolName() {
        assertEquals(
            RaftHomeDmAgentLiveActivity.Detail(RaftAgentActivityDetailText.Tool("run_command")),
            raftHomeDmAgentLiveActivity(state(AgentStatus.Working, "Running command...", toolName = "run_command"))
        )
    }

    @Test
    fun thinkingAlwaysShowsThinkingAndNeverTheThoughtText() {
        assertEquals(
            RaftHomeDmAgentLiveActivity.Thinking,
            raftHomeDmAgentLiveActivity(state(AgentStatus.Thinking, "Let me check the failing test first"))
        )
        assertEquals(RaftHomeDmAgentLiveActivity.Thinking, raftHomeDmAgentLiveActivity(state(AgentStatus.Thinking)))
    }

    @Test
    fun externalAgentsNeverShowLiveActivity() {
        assertNull(raftHomeDmAgentLiveActivity(state(AgentStatus.Working, "Tool finished", external = true)))
        assertNull(raftHomeDmAgentLiveActivity(state(AgentStatus.Thinking, external = true)))
        assertNull(state(AgentStatus.Working, "Starting…", external = true, detailKind = "starting").workingActivityDetailText())
    }

    @Test
    fun unreadBadgeIsShownAloneHidingDraftAndAgentMeta() {
        assertEquals(
            listOf(RaftHomeDmTrailingSlot.UnreadBadge),
            raftHomeDmTrailingSlots(badge = "3", hasDraft = true, isAgent = true)
        )
        assertEquals(
            listOf(RaftHomeDmTrailingSlot.UnreadBadge),
            raftHomeDmTrailingSlots(badge = "99+", hasDraft = false, isAgent = false)
        )
    }
}
