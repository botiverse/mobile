package build.raft.app

import build.raft.app.data.SharedServerMemberStatus
import build.raft.app.messageRes
import build.raft.app.model.AgentStatus
import build.raft.app.model.Presence
import build.raft.app.resources.*
import build.raft.app.thread.messageRes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The activity log and the status line say the client's own words in the reader's language: tool
 * labels, the parsers' fallback titles, and the daemon's freshness hold / delivery_unconsumed
 * details (web `utils/activity.ts`), instead of the English the data layer keeps as ids.
 */
class AgentActivityLocalizedCopyTest {
    @Test
    fun freshnessHoldBodyReadsAsCountAndTarget() {
        assertEquals(
            AgentFreshnessHoldDetail(count = 3, target = "#general"),
            parseAgentFreshnessHoldText("target: #general\nnew messages: 3 newer messages since your last read")
        )
        assertEquals(
            AgentFreshnessHoldDetail(count = 2, target = "#proj:abc123"),
            parseAgentFreshnessHoldText("target: #proj:abc123\nunreviewed synced context for this target: 2")
        )
        assertNull(parseAgentFreshnessHoldText("target: #general"))
        assertNull(parseAgentFreshnessHoldText("new messages: 3 newer"))
        assertNull(parseAgentFreshnessHoldText(null))
        assertEquals(Res.string.member_activity_log_freshness_hold_detail_one, AgentFreshnessHoldDetail(1, "#x").messageRes())
        assertEquals(Res.string.member_activity_log_freshness_hold_detail_other, AgentFreshnessHoldDetail(4, "#x").messageRes())
    }

    @Test
    fun statusLineMapsDaemonDetailKindsWhateverTheActivity() {
        assertEquals(
            RaftAgentActivityDetailText.FreshnessHold(AgentFreshnessHold.TaskClaim),
            state(AgentStatus.Working, "Task claim held by freshness check", detailKind = "freshness_hold")
                .workingActivityDetailText()
        )
        // An unknown hold title still reads as the hold, never as the daemon's English.
        assertEquals(
            RaftAgentActivityDetailText.FreshnessHold(AgentFreshnessHold.Send),
            state(AgentStatus.Working, "Something held", detailKind = "freshness_hold").serverKindActivityDetailText()
        )
        assertEquals(
            RaftAgentActivityDetailText.DeliveryUnconsumed,
            state(AgentStatus.Online, "3 deliveries unconsumed", detailKind = "delivery_unconsumed")
                .serverKindActivityDetailText()
        )
        assertNull(state(AgentStatus.Working, "Reading", detailKind = "starting").serverKindActivityDetailText())
        assertNull(
            state(null, "External", external = true, detailKind = "delivery_unconsumed").serverKindActivityDetailText()
        )
    }

    @Test
    fun knownToolsUseTheirTranslatedLogLabel() {
        assertEquals(Res.string.member_activity_log_tool_bash, agentToolLogLabelRes("Bash"))
        assertEquals(Res.string.member_activity_log_tool_bash, agentToolLogLabelRes("command_execution"))
        assertEquals(Res.string.member_activity_log_tool_read_file, agentToolLogLabelRes("Read"))
        assertEquals(Res.string.member_activity_log_tool_check_messages, agentToolLogLabelRes("mcp__chat__check_messages"))
        assertEquals(Res.string.member_activity_log_tool_check_messages, agentToolLogLabelRes("receive_message"))
        // Web KNOWN_TOOL_ALIASES: sub-agents and todo lists are known only by the runtimes' names
        // (Agent / Task, TodoWrite / SetTodoList); the bare semantic name is an unknown tool there.
        assertEquals(Res.string.member_activity_log_tool_spawn_subagent, agentToolLogLabelRes("Agent"))
        assertEquals(Res.string.member_activity_log_tool_spawn_subagent, agentToolLogLabelRes("Task"))
        assertEquals(Res.string.member_activity_log_tool_todo_write, agentToolLogLabelRes("TodoWrite"))
        assertNull(agentToolLogLabelRes("spawn_subagent"))
        assertNull(agentToolLogLabelRes("todo_write"))
        // A tool Raft doesn't know keeps its own name.
        assertNull(agentToolLogLabelRes("mcp__linear__create_issue"))
        assertNull(agentToolLogLabelRes(""))
    }

    private fun state(
        status: AgentStatus?,
        statusLabel: String?,
        external: Boolean = false,
        detailKind: String? = null
    ): RaftAgentStatusIndicatorState =
        SharedServerMemberStatus(
            status = status,
            presence = status?.presence ?: Presence.Offline,
            statusLabel = statusLabel,
            detailKind = detailKind,
            external = external
        ).toRaftAgentStatusIndicatorState()
}
