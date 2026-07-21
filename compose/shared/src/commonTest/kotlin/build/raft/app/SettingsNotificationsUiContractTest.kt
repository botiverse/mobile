package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import build.raft.app.data.PushPreviewMode

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

    @Test
    fun previewToggleMapsToCanonicalServerModes() {
        assertEquals(PushPreviewMode.Full, pushPreviewModeForEnabled(true))
        assertEquals(PushPreviewMode.Private, pushPreviewModeForEnabled(false))
    }
}
