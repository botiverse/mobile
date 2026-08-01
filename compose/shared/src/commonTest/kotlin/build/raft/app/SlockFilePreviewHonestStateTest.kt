package build.raft.app

import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockFilePreviewHonestStateTest {
    @Test
    fun agentWorkspaceLoadErrorShowsRawServerReasonAndKeepsCodeForDiagnostics() {
        val payload =
            SlockFilePreviewPayload()
                .withAgentWorkspaceLoadError(
                    ScreenError(
                        code = "workspace_backend_restarting",
                        serverMessage = "Workspace storage is restarting"
                    )
                )

        assertEquals("error", payload.kind)
        assertEquals("Workspace storage is restarting", payload.message)
        assertNull(payload.messageDesc)
        assertEquals("workspace_backend_restarting", payload.error)
    }

    @Test
    fun agentWorkspaceLoadErrorWithoutServerReasonUsesGenericSentence() {
        val payload =
            SlockFilePreviewPayload()
                .withAgentWorkspaceLoadError(ScreenError(code = "workspace_preview_unknown"))

        assertEquals("Something went wrong. Please try again.", payload.message)
        assertEquals("workspace_preview_unknown", payload.error)
    }

    @Test
    fun agentWorkspacePermissionErrorUsesPermissionCopyInsteadOfCode() {
        val payload =
            SlockFilePreviewPayload()
                .withAgentWorkspacePermissionError(PermissionBlocker(code = "forbidden"))

        assertEquals("You do not have permission to view this page.", payload.message)
        assertEquals("forbidden", payload.error)
    }

    @Test
    fun visibleErrorDetailNeverLetsDiagnosticCodeOverrideDisplayCopy() {
        assertEquals(
            "Workspace storage is restarting",
            slockFilePreviewVisibleErrorDetail(
                localizedMessage = "Workspace storage is restarting",
                fallbackMessage = "The file preview could not be loaded"
            )
        )
        assertEquals(
            "The file preview could not be loaded",
            slockFilePreviewVisibleErrorDetail(
                localizedMessage = "",
                fallbackMessage = "The file preview could not be loaded"
            )
        )
    }
}
