package build.raft.app

import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdatePageTest {
    @Test
    fun appUpdateActionReflectsHostStatus() {
        assertEquals("Retry", appUpdatePrimaryActionLabel(null, actionRunning = false))
        assertEquals(
            "Downloading",
            appUpdatePrimaryActionLabel(
                SharedSettingsUpdateCheckSnapshot(statusLabel = "downloading"),
                actionRunning = false
            )
        )
        assertEquals(
            "Install now",
            appUpdatePrimaryActionLabel(
                SharedSettingsUpdateCheckSnapshot(actionLabel = "Install now"),
                actionRunning = false
            )
        )
        assertEquals("Working...", appUpdatePrimaryActionLabel(null, actionRunning = true))
    }

    @Test
    fun appUpdateActionOnlyEnablesSupportedActionableState() {
        assertTrue(
            appUpdatePrimaryActionEnabled(
                snapshot = null,
                loading = false,
                actionRunning = false,
                errorCode = "network_error"
            )
        )
        assertFalse(
            appUpdatePrimaryActionEnabled(
                snapshot = SharedSettingsUpdateCheckSnapshot(supported = false, updateAvailable = true),
                loading = false,
                actionRunning = false,
                errorCode = null
            )
        )
        assertFalse(
            appUpdatePrimaryActionEnabled(
                snapshot = SharedSettingsUpdateCheckSnapshot(supported = true, updateAvailable = true, statusLabel = "downloading"),
                loading = false,
                actionRunning = false,
                errorCode = null
            )
        )
        assertTrue(
            appUpdatePrimaryActionEnabled(
                snapshot = SharedSettingsUpdateCheckSnapshot(supported = true, updateAvailable = true, statusLabel = "install"),
                loading = false,
                actionRunning = false,
                errorCode = null
            )
        )
    }
}
