package build.raft.app

import build.raft.app.data.AgentSummary
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task #148 (HuaTuo 23bc896b): the members screen showed a just-created agent from a stand-in row
 * that carried no computer, and re-entering the page served that row from cache, so a new agent sat
 * under "no computer". The stand-in must always carry the agent's computer.
 */
class CreatedAgentMemberProfileTest {
    private fun created(machineId: String?, machineName: String?) =
        AgentSummary(
            id = "agent-1", name = "qa-148", displayName = null, description = null, serverRole = null,
            status = "inactive", model = null, runtimeModelKind = null, runtimeProvider = null, runtime = "builtin",
            reasoning = null, executionMode = null, machineId = machineId, machineName = machineName,
            daemonVersion = null, avatarUrl = null, creatorId = null, creatorType = null, creator = null,
            createdAt = null, envVarNames = emptyList(), envVars = emptyMap(), createdAgents = emptyList(),
            activity = null, activityDetail = null, deletedAt = null
        )

    private val machines = listOf(SharedCreateAgentMachineOption(id = "machine-1", name = "daybed-earth", status = "online"))

    @Test
    fun aJustCreatedAgentIsGroupedUnderItsComputer() {
        // Create response: machineId, no name (a fresh agent's runtime profile is not in it).
        val fromResponse = createdAgentMemberProfile(created("machine-1", null), submit(null), machines)
        assertEquals(listOf("computer:daybed-earth", "computer-id:machine-1"), fromResponse.permissionBadges)
        assertEquals("daybed-earth", groupedAgentMembers(listOf(fromResponse)).single().label)

        // A response without machineId falls back to the computer the dialog sent.
        val fromSubmit = createdAgentMemberProfile(created(null, null), submit("machine-1"), machines)
        assertEquals(listOf("computer:daybed-earth", "computer-id:machine-1"), fromSubmit.permissionBadges)

        // The machine list not loaded yet: still on the computer, shown by id.
        val unloaded = createdAgentMemberProfile(created("machine-1", null), submit(null), emptyList())
        assertEquals("machine-1", groupedAgentMembers(listOf(unloaded)).single().label)
    }

    private fun submit(machineId: String?) = SharedCreateAgentDialogSubmit(name = "qa-148", description = null, machineId = machineId)
}
