package build.raft.app.data

import build.raft.app.model.AgentStatus
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
}
