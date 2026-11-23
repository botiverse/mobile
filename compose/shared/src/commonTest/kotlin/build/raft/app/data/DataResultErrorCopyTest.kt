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

    @Test
    fun generic403And404ScreenErrorsPreserveUsableServerRefusalMessageAndFallbackWhenBlank() {
        assertEquals(
            "You must join this channel to modify tasks",
            ScreenError(
                code = "tasks_amend_403",
                retryable = false,
                serverMessage = "You must join this channel to modify tasks",
            ).userFacingMessage(errorStrings)
        )
        assertEquals(
            "You do not have permission to view this page.",
            ScreenError(code = "http_403", retryable = false, serverMessage = "   ").userFacingMessage(errorStrings)
        )
        assertEquals(
            "Invite link has already been revoked.",
            ScreenError(
                code = "http_404",
                retryable = false,
                serverMessage = "Invite link has already been revoked.",
            ).userFacingMessage(errorStrings)
        )
        assertEquals(
            "This item could not be found. It may have been moved or deleted.",
            ScreenError(code = "http_404", retryable = false).userFacingMessage(errorStrings)
        )
    }

    @Test
    fun serverCodeTakesPrecedenceOverGenericHttpCodeWithoutSubstringFalsePositives() {
        assertEquals(
            "This month's uploads are used up",
            ScreenError(
                code = "http_403",
                retryable = false,
                serverMessage = "Monthly file upload quota exceeded.",
                serverCode = "FILE_UPLOAD_QUOTA_EXCEEDED",
            ).userFacingMessage(errorStrings)
        )
        assertEquals(
            "This channel is archived.",
            ScreenError(
                code = "http_409",
                retryable = false,
                serverMessage = "Channel is archived",
                serverCode = "channel_archived",
            ).userFacingMessage(errorStrings)
        )
        assertEquals(
            "This channel is archived.",
            PermissionBlocker(
                code = "http_403",
                serverMessage = "Channel is archived",
                serverCode = "channel_archived",
            ).userFacingMessage(errorStrings)
        )
        assertEquals(
            "Your session expired. Sign in again to continue.",
            ScreenError(
                code = "http_403",
                retryable = false,
                serverMessage = "Invalid bearer token",
                serverCode = "unauthenticated",
            ).userFacingMessage(errorStrings)
        )
        // Real server refusal codes containing "host", "auth", "rate", or "mismatch" must never be
        // misclassified as local network/auth/parse/rate-limit errors.
        assertEquals(
            "Host server reached its external channel limit.",
            ScreenError(
                code = "joint_channel_host_limit",
                retryable = false,
                serverMessage = "Host server reached its external channel limit.",
                serverCode = "joint_channel_host_limit",
            ).userFacingMessage(errorStrings)
        )
        assertEquals(
            "Channel changed during conversion.",
            ScreenError(
                code = "channel_conversion_fence_mismatch",
                retryable = false,
                serverMessage = "Channel changed during conversion.",
                serverCode = "channel_conversion_fence_mismatch",
            ).userFacingMessage(errorStrings)
        )
        assertEquals(
            "OAuth provider is not enabled.",
            ScreenError(
                code = "oauth_provider_disabled",
                retryable = false,
                serverMessage = "OAuth provider is not enabled.",
                serverCode = "oauth_provider_disabled",
            ).userFacingMessage(errorStrings)
        )
    }

    @Test
    fun serverFaultsHtmlAndStackTracesNeverLeakThroughScreenErrorOrPermissionBlocker() {
        assertEquals(
            "The server is temporarily unavailable. Please try again later.",
            ScreenError(
                code = "http_500",
                serverMessage = "SQLSTATE[42P01]: relation missing",
            ).userFacingMessage(errorStrings)
        )
        assertEquals(
            "Something went wrong. Please try again.",
            ScreenError(
                code = "http_409",
                serverCode = "internal_error",
                serverMessage = "Unhandled TypeError in route",
            ).userFacingMessage(errorStrings)
        )
        assertEquals(
            "Something went wrong. Please try again.",
            ScreenError(
                code = "http_400",
                serverMessage = "<!DOCTYPE html><html><body>502 Bad Gateway</body></html>",
            ).userFacingMessage(errorStrings)
        )
        assertEquals(
            "This item could not be found. It may have been moved or deleted.",
            ScreenError(
                code = "http_404",
                serverMessage = "Error: lookup failed\n    at route.ts:42",
            ).userFacingMessage(errorStrings)
        )
        assertEquals(
            "You do not have permission to view this page.",
            PermissionBlocker(
                code = "http_403",
                serverMessage = "<html><head><title>403 Forbidden</title></head></html>",
            ).userFacingMessage(errorStrings)
        )
        assertEquals(
            "Something went wrong. Please try again.",
            PermissionBlocker(
                code = "http_403",
                serverCode = "internal_error",
                serverMessage = "raw server exception",
            ).userFacingMessage(errorStrings)
        )
    }
}
