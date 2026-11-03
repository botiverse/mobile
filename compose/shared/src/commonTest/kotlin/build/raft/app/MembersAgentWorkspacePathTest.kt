package build.raft.app

import build.raft.app.data.AgentDetailSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Workspace tab's path bar follows web AgentWorkspace (`workspacePath`, `hosted`). */
class MembersAgentWorkspacePathTest {
    @Test
    fun reportedPathWins() {
        val summary = AgentDetailSummary(id = "agent-1", name = "noel", workspacePath = "/Users/noel/agents/noel/")
        assertEquals("/Users/noel/agents/noel/", membersAgentWorkspacePath("agent-1", summary))
    }

    @Test
    fun withoutAReportedPathItIsTheDefaultAgentDirectory() {
        assertEquals("~/.slock/agents/agent-1/", membersAgentWorkspacePath("agent-1", AgentDetailSummary(id = "agent-1", name = "noel")))
        assertEquals("~/.slock/agents/agent-1/", membersAgentWorkspacePath("agent-1", null))
        assertEquals(
            "~/.slock/agents/agent-1/",
            membersAgentWorkspacePath("agent-1", AgentDetailSummary(id = "agent-1", name = "noel", workspacePath = "  "))
        )
    }

    @Test
    fun anExternalAgentHasNoPathBar() {
        assertNull(membersAgentWorkspacePath("agent-1", AgentDetailSummary(id = "agent-1", name = "noel", isExternal = true)))
    }
}
