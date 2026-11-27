package build.raft.app

import build.raft.app.data.AgentIssueReportResult
import build.raft.app.members.buildMembersAgentIssueReportReference
import build.raft.app.members.membersAgentIssueCanIncludeMachineLog
import build.raft.app.members.membersAgentIssueMachineLogTailChecked
import kotlin.test.Test
import kotlin.test.assertEquals

class MembersAgentIssueReportReceiptTest {
    // Web ReportIssueDialog handleCopyReportReference: reportId, serverId, then the description.
    @Test
    fun copiedReferenceMatchesWebReportReference() {
        listOf(
            "Agent stopped responding" to
                "reportId: 7e91c120-f424-42d7-96e5-21eb816866d0\n" +
                "serverId: 65598229-cd0a-4e92-96f3-c33e7e2e4cc1\n" +
                "issueDescription:\nAgent stopped responding",
            null to
                "reportId: 7e91c120-f424-42d7-96e5-21eb816866d0\n" +
                "serverId: 65598229-cd0a-4e92-96f3-c33e7e2e4cc1"
        ).forEach { (description, expected) ->
            val reference =
                buildMembersAgentIssueReportReference(
                    receipt =
                        AgentIssueReportResult(
                            reportId = "7e91c120-f424-42d7-96e5-21eb816866d0",
                            artifactId = "95d629bd-3ed7-42ff-97eb-3ed35c6dc6ab",
                            serverId = "65598229-cd0a-4e92-96f3-c33e7e2e4cc1",
                            issueDescription = description,
                            transcriptRequested = true
                        )
                )
            assertEquals(expected, reference)
        }
    }

    @Test
    fun machineLogTailFollowsOwnershipUntilTouched() {
        assertEquals(true, membersAgentIssueMachineLogTailChecked(choice = null, canIncludeMachineLog = true))
        assertEquals(false, membersAgentIssueMachineLogTailChecked(choice = null, canIncludeMachineLog = false))
        assertEquals(false, membersAgentIssueMachineLogTailChecked(choice = false, canIncludeMachineLog = true))
        assertEquals(false, membersAgentIssueMachineLogTailChecked(choice = true, canIncludeMachineLog = false))
    }

    @Test
    fun ownerOrAdminIsOfferedAnotherPersonsMachineLogUnticked() {
        // slock #15306: offered to whoever may edit the Computer, ticked only on one's own.
        assertEquals(true, membersAgentIssueCanIncludeMachineLog(hasMachine = true, attachedByCurrentUser = true, serverRole = "member"))
        assertEquals(true, membersAgentIssueCanIncludeMachineLog(hasMachine = true, attachedByCurrentUser = false, serverRole = "admin"))
        assertEquals(true, membersAgentIssueCanIncludeMachineLog(hasMachine = true, attachedByCurrentUser = false, serverRole = "owner"))
        assertEquals(false, membersAgentIssueCanIncludeMachineLog(hasMachine = true, attachedByCurrentUser = false, serverRole = "member"))
        assertEquals(false, membersAgentIssueCanIncludeMachineLog(hasMachine = false, attachedByCurrentUser = false, serverRole = "owner"))

        assertEquals(false, membersAgentIssueMachineLogTailChecked(choice = null, canIncludeMachineLog = true, ownAttachedMachine = false))
        assertEquals(true, membersAgentIssueMachineLogTailChecked(choice = true, canIncludeMachineLog = true, ownAttachedMachine = false))
        assertEquals(true, membersAgentIssueMachineLogTailChecked(choice = null, canIncludeMachineLog = true, ownAttachedMachine = true))
    }
}
