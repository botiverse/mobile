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
    fun createAgentFallbackPrefersServerTextThenLocalizedGenericWithoutLeakingCode() {
        assertEquals(
            "Cindy agent already exists in this server",
            createAgentFallbackErrorText(
                ScreenError(code = "http_409", serverMessage = "Cindy agent already exists in this server"),
                genericPrefix = "无法创建 Agent"
            )
        )
        assertEquals(
            "无法创建 Agent",
            createAgentFallbackErrorText(ScreenError("http_418"), genericPrefix = "无法创建 Agent")
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
    fun permissionPresentationUsesStatusSuffixAndDeterministicRawPriority() {
        assertEquals(
            "Your session expired. Sign in again to continue.",
            PermissionBlocker(
                code = "tasks_401",
                serverMessage = "  サーバーの認証文言は表示しない  "
            ).userFacingMessage(errorStrings),
            "401 suffix must always use localized session copy"
        )

        val exactServerReason = "  この操作は監査ポリシーで拒否されました。  "
        assertEquals(
            exactServerReason,
            PermissionBlocker(
                code = "home_http_403",
                serverMessage = exactServerReason
            ).userFacingMessage(errorStrings),
            "audited nonblank 403 reason must remain verbatim and untranslated"
        )

        listOf(null, "", " \t\n ").forEach { blankReason ->
            assertEquals(
                "You do not have permission to view this page.",
                PermissionBlocker(
                    code = "home_http_403",
                    serverMessage = blankReason
                ).userFacingMessage(errorStrings)
            )
        }
    }

    @Test
    fun unknownErrorRawFallbackIsVerbatimAndUntranslated() {
        val exactServerReason = "  访问策略拒绝了此操作 / ポリシー拒否  "
        assertEquals(
            exactServerReason,
            ScreenError(
                code = "route_specific_unmapped_failure",
                serverMessage = exactServerReason
            ).userFacingMessage(errorStrings)
        )
    }
}
