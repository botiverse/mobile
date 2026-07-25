package build.raft.app

import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdatePageTest {
    @Test
    fun skippedVersionSuppressesOnlyMatchingHandsBuild() {
        val version160 =
            SharedSettingsUpdateCheckSnapshot(
                updateAvailable = true,
                latestVersionCode = 1_060_000L
            )
        assertFalse(version160.withSkippedAppUpdateVersionApplied(1_060_000L).updateAvailable)
        assertTrue(version160.withSkippedAppUpdateVersionApplied(1_061_000L).updateAvailable)
    }

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
        assertEquals(
            "Installing",
            appUpdatePrimaryActionLabel(
                SharedSettingsUpdateCheckSnapshot(statusLabel = "installing"),
                actionRunning = false
            )
        )
        assertEquals("Working...", appUpdatePrimaryActionLabel(null, actionRunning = true))
        assertEquals(
            "Downloading",
            appUpdatePrimaryActionLabel(
                SharedSettingsUpdateCheckSnapshot(
                    statusLabel = "download",
                    actionLabel = "download"
                ),
                actionRunning = true
            )
        )
        assertEquals(
            "Working...",
            appUpdatePrimaryActionLabel(
                SharedSettingsUpdateCheckSnapshot(
                    statusLabel = "install",
                    actionLabel = "install"
                ),
                actionRunning = true
            )
        )
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
                snapshot =
                    SharedSettingsUpdateCheckSnapshot(
                        supported = true,
                        updateAvailable = true,
                        statusLabel = "download",
                        actionLabel = "download"
                    ),
                loading = false,
                actionRunning = true,
                errorCode = null
            )
        )
        assertTrue(
            appUpdatePrimaryActionEnabled(
                snapshot =
                    SharedSettingsUpdateCheckSnapshot(
                        supported = true,
                        updateAvailable = true,
                        statusLabel = "download",
                        actionLabel = "download"
                    ),
                loading = false,
                actionRunning = false,
                errorCode = "download_canceled"
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
        assertFalse(
            appUpdatePrimaryActionEnabled(
                snapshot = SharedSettingsUpdateCheckSnapshot(supported = true, updateAvailable = true, statusLabel = "installing"),
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
