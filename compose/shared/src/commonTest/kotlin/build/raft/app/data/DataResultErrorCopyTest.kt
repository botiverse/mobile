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
