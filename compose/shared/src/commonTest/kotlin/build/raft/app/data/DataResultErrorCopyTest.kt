package build.raft.app.data

import build.raft.app.englishErrorPresentationStringsForTest
import build.raft.app.userFacingMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class DataResultErrorCopyTest {
    private val errorStrings = englishErrorPresentationStringsForTest()

    @Test
    fun screenErrorCopyMapsNetworkAndDnsCodesToUserFacingMessages() {
        assertEquals(
            "The server could not be reached. Check your connection and try again.",
            ScreenError("dns_lookup_failed").userFacingMessage(errorStrings)
        )
        assertEquals(
            "The network connection failed. Check your connection and try again.",
            ScreenError("settings_network").userFacingMessage(errorStrings)
        )
        assertEquals(
            "The server did not respond in time. Please try again.",
            ScreenError("network_timeout").userFacingMessage(errorStrings)
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
            ).userFacingMessage(errorStrings)
        )
    }

    @Test
    fun knownCategoryOverlayBeatsServerMessage() {
        assertEquals(
            "Your session expired. Sign in again to continue.",
            ScreenError(
                code = "auth_required",
                serverMessage = "raw auth text from server"
            ).userFacingMessage(errorStrings)
        )
    }

    @Test
    fun unknownCodeWithoutServerMessageKeepsGenericSentence() {
        assertEquals(
            "Something went wrong. Please try again.",
            ScreenError("totally_unmapped_code").userFacingMessage(errorStrings)
        )
        assertEquals(
            "Something went wrong. Please try again.",
            ScreenError(code = "totally_unmapped_code", serverMessage = "  ").userFacingMessage(errorStrings)
        )
    }

    @Test
    fun screenErrorCopyDoesNotTreatMessageTextAsCode() {
        assertEquals(
            "Something went wrong. Please try again.",
            ScreenError("Workspace settings failed to load.").userFacingMessage(errorStrings)
        )
    }

    @Test
    fun permissionAndEmptyCopyUseFriendlyMessages() {
        assertEquals(
            "You do not have permission to view this page.",
            PermissionBlocker("server_admin_required").userFacingMessage(errorStrings)
        )
        assertEquals(
            "You do not have permission to view this page.",
            PermissionBlocker("server_forbidden").userFacingMessage(errorStrings)
        )
        assertEquals(
            "These settings are not available right now.",
            EmptyReason.NoSettingsAvailable.userFacingMessage(errorStrings)
        )
    }

    @Test
    fun monthlyUploadQuotaAndChannelReadOnlyCodesMapToLocalizedPresentationKeys() {
        assertEquals(
            "This month's uploads are used up",
            ScreenError(
                code = "FILE_UPLOAD_QUOTA_EXCEEDED",
                retryable = false,
                serverMessage = "Monthly file upload quota exceeded.",
                serverCode = "FILE_UPLOAD_QUOTA_EXCEEDED"
            ).userFacingMessage(errorStrings)
        )
        assertEquals(
            "This month's uploads are used up",
            ScreenError("UPLOAD_QUOTA_EXCEEDED", retryable = false).userFacingMessage(errorStrings)
        )
        assertEquals(
            "This channel is read-only.",
            ScreenError("external_limit_frozen", retryable = false).userFacingMessage(errorStrings)
        )
        assertEquals(
            "This channel is read-only.",
            ScreenError("channel_read_only", retryable = false).userFacingMessage(errorStrings)
        )
        assertEquals(
            "This channel is archived.",
            ScreenError("channel_archived", retryable = false).userFacingMessage(errorStrings)
        )
    }
}
