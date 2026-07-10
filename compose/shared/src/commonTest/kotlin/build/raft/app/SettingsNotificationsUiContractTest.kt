package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsNotificationsUiContractTest {
    @Test
    fun permissionGrantedRecognizesPositivePlatformStatuses() {
        assertTrue(settingsNotificationPermissionGranted("Allowed"))
        assertTrue(settingsNotificationPermissionGranted(" granted "))
        assertTrue(settingsNotificationPermissionGranted("Enabled"))
        assertTrue(settingsNotificationPermissionGranted("ON"))
    }

    @Test
    fun permissionGrantedRejectsActionAndUnavailableStatuses() {
        assertFalse(settingsNotificationPermissionGranted("Requested"))
        assertFalse(settingsNotificationPermissionGranted("Disabled"))
        assertFalse(settingsNotificationPermissionGranted("Unsupported"))
        assertFalse(settingsNotificationPermissionGranted(""))
    }
}
