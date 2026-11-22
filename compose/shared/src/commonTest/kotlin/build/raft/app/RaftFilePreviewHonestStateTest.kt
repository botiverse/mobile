package build.raft.app

import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RaftFilePreviewHonestStateTest {
    private val errorStrings = englishErrorPresentationStringsForTest()

    @Test
    fun agentWorkspaceLoadErrorShowsRawServerReasonAndKeepsCodeForDiagnostics() {
        val payload =
            RaftFilePreviewPayload()
                .withAgentWorkspaceLoadError(
                    ScreenError(
                        code = "workspace_backend_restarting",
                        serverMessage = "Workspace storage is restarting"
                    ),
                    errorStrings
                )

        assertEquals("error", payload.kind)
        assertEquals("Workspace storage is restarting", payload.message)
        assertNull(payload.messageDesc)
        assertEquals("workspace_backend_restarting", payload.error)
    }

    @Test
    fun agentWorkspacePermissionErrorUsesPermissionCopyInsteadOfCode() {
        val payload =
            RaftFilePreviewPayload()
                .withAgentWorkspacePermissionError(
                    PermissionBlocker(code = "forbidden"),
                    errorStrings
                )

        assertEquals("You do not have permission to view this page.", payload.message)
        assertEquals("forbidden", payload.error)
    }
}
