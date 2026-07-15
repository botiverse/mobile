package build.raft.app

import build.raft.app.data.AgentIssueReportResult
import kotlin.test.Test
import kotlin.test.assertEquals

class MembersAgentIssueReportReceiptTest {
    @Test
    fun copiedReferenceKeepsFullReportAndArtifactIds() {
        val reference =
            buildMembersAgentIssueReportReference(
                receipt =
                    AgentIssueReportResult(
                        reportId = "7e91c120-f424-42d7-96e5-21eb816866d0",
                        artifactId = "95d629bd-3ed7-42ff-97eb-3ed35c6dc6ab",
                        serverId = "65598229-cd0a-4e92-96f3-c33e7e2e4cc1",
                        issueDescription = "Agent stopped responding",
                        transcriptRequested = true
                    )
            )

        assertEquals(
            "reportId: 7e91c120-f424-42d7-96e5-21eb816866d0\n" +
                "serverId: 65598229-cd0a-4e92-96f3-c33e7e2e4cc1\n" +
                "artifactId: 95d629bd-3ed7-42ff-97eb-3ed35c6dc6ab\n" +
                "issueDescription:\nAgent stopped responding",
            reference
        )
    }

    @Test
    fun copiedReferenceOmitsMissingArtifactWithoutLosingReportId() {
        val reference =
            buildMembersAgentIssueReportReference(
                receipt =
                    AgentIssueReportResult(
                        reportId = "report-12345678",
                        artifactId = null,
                        serverId = "server-1",
                        issueDescription = null,
                        transcriptRequested = false
                    )
            )

        assertEquals(
            "reportId: report-12345678\nserverId: server-1",
            reference
        )
    }
}
