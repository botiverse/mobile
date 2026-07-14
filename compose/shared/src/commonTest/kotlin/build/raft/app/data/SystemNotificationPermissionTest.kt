package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemNotificationPermissionTest {
    @Test
    fun mapsAllIosAuthorizationStatesToSettingsSnapshots() {
        val authorized = systemNotificationPermissionSnapshot("permission_authorized", "Allowed by iOS")
        assertEquals("Allowed", authorized?.statusLabel)
        assertTrue(authorized?.available == true)
        assertEquals("Allowed by iOS", authorized?.detail)

        val provisional = systemNotificationPermissionSnapshot("permission_provisional", "Quiet delivery")
        assertEquals("Provisional", provisional?.statusLabel)
        assertTrue(provisional?.available == true)

        val ephemeral = systemNotificationPermissionSnapshot("permission_ephemeral", "Temporary")
        assertEquals("Allowed", ephemeral?.statusLabel)
        assertTrue(ephemeral?.available == true)

        val denied = systemNotificationPermissionSnapshot("permission_denied", "Open Settings")
        assertEquals("Denied", denied?.statusLabel)
        assertFalse(denied?.available ?: true)

        val notDetermined = systemNotificationPermissionSnapshot("permission_not_determined", "Ask after login")
        assertEquals("Not requested", notDetermined?.statusLabel)
        assertTrue(notDetermined?.available == true)
    }

    @Test
    fun ignoresUnsupportedPlatformPermissionCodes() {
        assertNull(systemNotificationPermissionSnapshot("notification_unsupported", "Unsupported"))
        assertNull(systemNotificationPermissionSnapshot("settings_capability_unknown", "Unknown"))
    }
}
