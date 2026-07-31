package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class DataResultErrorCopyTest {
    @Test
    fun screenErrorCopyMapsNetworkAndDnsCodesToUserFacingMessages() {
        assertEquals(
            "The server could not be reached. Check your connection and try again.",
            ScreenError("dns_lookup_failed").userFacingMessage()
        )
        assertEquals(
            "The network connection failed. Check your connection and try again.",
            ScreenError("settings_network").userFacingMessage()
        )
        assertEquals(
            "The server did not respond in time. Please try again.",
            ScreenError("network_timeout").userFacingMessage()
        )
    }

    // task #193 honest-states: unknown codes fall back to the server's raw
    // reason; humane category copy stays an overlay; bare codes never surface.
    @Test
    fun unknownCodeFallsBackToRawServerMessage() {
        assertEquals(
            "Agent name \"Nova\" is already taken",
            ScreenError(
                code = "http_409",
                serverMessage = "Agent name \"Nova\" is already taken"
            ).userFacingMessage()
        )
    }

    @Test
    fun knownCategoryOverlayBeatsServerMessage() {
        assertEquals(
            "Your session expired. Sign in again to continue.",
            ScreenError(
                code = "auth_required",
                serverMessage = "raw auth text from server"
            ).userFacingMessage()
        )
    }

    @Test
    fun unknownCodeWithoutServerMessageKeepsGenericSentence() {
        assertEquals(
            "Something went wrong. Please try again.",
            ScreenError("totally_unmapped_code").userFacingMessage()
        )
        assertEquals(
            "Something went wrong. Please try again.",
            ScreenError(code = "totally_unmapped_code", serverMessage = "  ").userFacingMessage()
        )
    }

    @Test
    fun createAgentFallbackPrefersServerTextThenLocalizedGenericWithCode() {
        assertEquals(
            "Cindy agent already exists in this server",
            createAgentFallbackErrorText(
                ScreenError(code = "http_409", serverMessage = "Cindy agent already exists in this server"),
                genericPrefix = "无法创建 Agent"
            )
        )
        assertEquals(
            "无法创建 Agent: http_418",
            createAgentFallbackErrorText(ScreenError("http_418"), genericPrefix = "无法创建 Agent")
        )
    }

    @Test
    fun screenErrorCopyDoesNotTreatMessageTextAsCode() {
        assertEquals(
            "Something went wrong. Please try again.",
            ScreenError("Workspace settings failed to load.").userFacingMessage()
        )
    }

    @Test
    fun permissionAndEmptyCopyUseFriendlyMessages() {
        assertEquals(
            "You do not have permission to view this page.",
            PermissionBlocker("server_admin_required").userFacingMessage()
        )
        assertEquals(
            "You do not have permission to view this page.",
            PermissionBlocker("server_forbidden").userFacingMessage()
        )
        assertEquals(
            "These settings are not available right now.",
            EmptyReason.NoSettingsAvailable.userFacingMessage()
        )
    }
}
