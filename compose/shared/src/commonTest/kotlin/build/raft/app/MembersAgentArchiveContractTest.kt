package build.raft.app

import build.raft.app.data.AgentArchiveWorkspaceChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #515 — mobile port of the web `AgentArchiveDialog` contract
 * (`packages/web/src/components/agentArchive/AgentArchiveDialog.tsx`):
 * archiving an agent asks what happens to its workspace on the Computer
 * (`keep` = server default / what mobile delete always did, `delete` removes
 * it), and the request is `DELETE /agents/:id` with `{"workspace":...}`.
 *
 * Web gate (`hasComputerWorkspace`): the choice is only shown when the agent
 * has a workspace on a Computer — `machineId` set and the runtime is not
 * external. The server's backstop is 409 `archive_workspace_unavailable`.
 */
class MembersAgentArchiveContractTest {

    @Test
    fun archiveWorkspaceChoiceSerializesToWebWireValues() {
        assertEquals("""{"workspace":"keep"}""", AgentArchiveWorkspaceChoice.Keep.toRequestBody())
        assertEquals("""{"workspace":"delete"}""", AgentArchiveWorkspaceChoice.Delete.toRequestBody())
        assertEquals("keep", AgentArchiveWorkspaceChoice.Keep.wireValue)
        assertEquals("delete", AgentArchiveWorkspaceChoice.Delete.wireValue)
        assertEquals(
            """{"workspace":"keep"}""",
            membersAgentArchiveRequestBody(AgentArchiveWorkspaceChoice.Keep)
        )
    }

    @Test
    fun archiveGateOffersChoiceOnlyForManagedAgentWithMachine() {
        // Managed agent with a Computer workspace → radio rows appear.
        assertTrue(membersAgentArchiveHasComputerWorkspace(machineId = "machine-1", isExternalAgent = false))
        // Managed agent without a Computer → no workspace choice (keep is implicit).
        assertFalse(membersAgentArchiveHasComputerWorkspace(machineId = null, isExternalAgent = false))
        assertFalse(membersAgentArchiveHasComputerWorkspace(machineId = "", isExternalAgent = false))
        assertFalse(membersAgentArchiveHasComputerWorkspace(machineId = "  ", isExternalAgent = false))
        // External agents never get the choice — the server would refuse
        // (`archive_workspace_unavailable`) and web hides the radio too.
        assertFalse(membersAgentArchiveHasComputerWorkspace(machineId = "machine-1", isExternalAgent = true))
    }
}
