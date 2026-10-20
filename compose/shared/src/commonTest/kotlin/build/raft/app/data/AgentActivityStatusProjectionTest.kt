package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberPresenceUpdated
import kotlin.test.assertIs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentActivityStatusProjectionTest {
    @Test
    fun latestActivityEntryProjectsToolStatus() {
        val projection =
            listOf(
                AgentActivityEntry(
                    id = "1",
                    type = "status",
                    title = "Idle",
                    statusActivity = "online",
                    statusDetail = "Idle"
                ),
                AgentActivityEntry(
                    id = "2",
                    type = "tool_start",
                    title = "Run command",
                    toolName = "run_command"
                )
            ).latestAgentActivityStatusProjection()

        assertEquals(AgentStatus.Working, projection?.status)
        assertEquals("Running command...", projection?.detail)
        assertEquals("run_command", projection?.toolName)
        assertNull(projection?.detailKind)
    }

    @Test
    fun toolStartCarriesTheNormalizedToolNameForLocalization() {
        val projection = projectAgentActivityStatus(kind = "tool_start", toolName = "mcp__chat__send_message")
        assertEquals("send_message", projection?.toolName)
        assertEquals("Using send_message...", projection?.detail)
        assertNull(projectAgentActivityStatus(kind = "tool_start", toolName = "  ")?.toolName)
    }

    @Test
    fun compactionStartedIsTheCompactingContextKind() {
        val projection = projectAgentActivityStatus(kind = "compaction_started")
        assertEquals(AgentStatus.Working, projection?.status)
        assertEquals("compacting_context", projection?.detailKind)
        assertNull(projection?.toolName)
    }

    @Test
    fun statusEntryCarriesServerDetailKind() {
        val projection =
            listOf(
                AgentActivityEntry(
                    id = "1",
                    type = "status",
                    title = "Starting",
                    statusActivity = "working",
                    statusDetail = "Starting…",
                    statusDetailKind = "starting"
                )
            ).latestAgentActivityStatusProjection()
        assertEquals(AgentStatus.Working, projection?.status)
        assertEquals("starting", projection?.detailKind)
        assertEquals("starting", projection?.toMemberPresenceUpdated(build.raft.app.model.MemberId("a"))?.detailKind)
    }

    @Test
    fun latestActivityEntrySkipsNonStatusRows() {
        val projection =
            listOf(
                AgentActivityEntry(id = "1", type = "text", title = "Output"),
                AgentActivityEntry(
                    id = "2",
                    type = "status",
                    title = "Offline",
                    statusActivity = "offline",
                    statusDetail = "Machine disconnected"
                ),
                AgentActivityEntry(id = "3", type = "system", title = "System")
            ).latestAgentActivityStatusProjection()

        assertEquals(AgentStatus.Offline, projection?.status)
        assertEquals("Machine disconnected", projection?.detail)
    }

    @Test
    fun summaryFallbackOnlyUsesRecognizedStatus() {
        assertEquals(
            AgentActivityStatusProjection(AgentStatus.Working, "Building"),
            AgentDetailSummary(
                id = "agent-1",
                name = "Agent",
                status = "offline",
                activity = "working",
                activityDetail = "Building"
            ).agentActivityStatusFallbackProjection()
        )
        assertNull(
            AgentDetailSummary(
                id = "agent-1",
                name = "Agent",
                status = "unknown"
            ).agentActivityStatusFallbackProjection()
        )
    }

    @Test
    fun accountLifecycleActiveDoesNotProjectAsLiveOnline() {
        assertNull(
            AgentDetailSummary(
                id = "agent-1",
                name = "Agent",
                status = "active"
            ).agentActivityStatusFallbackProjection()
        )
        assertNull(normalizedAgentRuntimeStatus(activity = "active", lifecycleStatus = null))
    }

    @Test
    fun explicitStoppedLifecycleProjectsOfflineWithoutRuntimeActivity() {
        assertEquals(
            AgentStatus.Offline,
            normalizedAgentRuntimeStatus(activity = null, lifecycleStatus = "stopped")
        )
        assertEquals(
            AgentStatus.Offline,
            normalizedAgentRuntimeStatus(activity = "active", lifecycleStatus = "inactive")
        )
    }

    @Test
    fun activityEntryAccountLifecycleActiveDoesNotProjectAsLiveOnline() {
        assertNull(
            projectAgentActivityStatus(
                kind = "status",
                activity = "active"
            )
        )
    }

    @Test
    fun realtimeAgentActivityCarriesDetailKind() {
        val event =
            RealtimeEventMapper().map(
                RealtimeSocketEvents.AgentActivity,
                """
                {"agentId":"agent-1","activity":"working","activityKind":"working","detail":"Compacting context…","detailKind":"compacting_context","timestamp":"2026-10-20T00:00:00.000Z","serverSeq":7}
                """.trimIndent(),
            )
        val presence = assertIs<MemberPresenceUpdated>(event)
        assertEquals(AgentStatus.Working, presence.agentStatus)
        assertEquals("Compacting context…", presence.statusLabel)
        assertEquals("compacting_context", presence.detailKind)
        assertNull(presence.activityToolName)
    }
}
