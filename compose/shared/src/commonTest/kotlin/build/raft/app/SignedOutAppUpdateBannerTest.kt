package build.raft.app

import build.raft.app.data.APP_UPDATE_HOME_NOTIFICATION_ID
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.SharedSettingsUpdateTransactionState
import build.raft.app.settings.loadSignedOutAppUpdateNotification
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignedOutAppUpdateBannerTest {
    private val update124 =
        SharedSettingsUpdateCheckSnapshot(
            supported = true,
            updateAvailable = true,
            currentVersionLabel = "1.23.0",
            currentVersionCode = 12_300_004L,
            latestVersionLabel = "1.24.0",
            latestVersionCode = 12_400_002L
        )

    @Test
    fun signedOutPagesShowHomesUpdateNoticeWhenHandsHasANewerBuild() = runTest {
        var checks = 0
        val notice =
            loadSignedOutAppUpdateNotification(skippedVersionCode = 0L) {
                checks += 1
                DataResult.Content(update124)
            }

        assertEquals(1, checks)
        assertEquals(APP_UPDATE_HOME_NOTIFICATION_ID, notice?.id)
        assertEquals(APP_UPDATE_HOME_NOTIFICATION_ID, notice?.targetId)
        assertEquals("App update available", notice?.title)
        assertEquals("Current version 1.23.0. Latest version 1.24.0 (12400002).", notice?.body)
    }

    @Test
    fun signedOutNoticeSwitchesToReadyCopyWhenDownloadedOrReadyToRestart() = runTest {
        val readyToInstall =
            loadSignedOutAppUpdateNotification(skippedVersionCode = 0L) {
                DataResult.Content(update124.copy(transactionState = SharedSettingsUpdateTransactionState.ReadyToInstall))
            }
        assertEquals("Update ready to install", readyToInstall?.title)
        assertEquals("Install", readyToInstall?.actionLabel)

        val readyToRestart =
            loadSignedOutAppUpdateNotification(skippedVersionCode = 0L) {
                DataResult.Content(update124.copy(transactionState = SharedSettingsUpdateTransactionState.ReadyToRestart))
            }
        assertEquals("Update ready to install", readyToRestart?.title)
        assertEquals("Restart to update", readyToRestart?.actionLabel)
    }

    @Test
    fun noNoticeWithoutInAppUpdatesOrWhenTheCheckFails() = runTest {
        // iOS and Google Play builds: the host has no in-app update.
        assertNull(
            loadSignedOutAppUpdateNotification(skippedVersionCode = 0L) {
                DataResult.Content(update124.copy(supported = false))
            }
        )
        assertNull(
            loadSignedOutAppUpdateNotification(skippedVersionCode = 0L) {
                DataResult.Content(update124.copy(updateAvailable = false))
            }
        )
        assertNull(
            loadSignedOutAppUpdateNotification(skippedVersionCode = 0L) {
                DataResult.Error(ScreenError(code = "hands_apk_update_disabled"))
            }
        )
        assertNull(
            loadSignedOutAppUpdateNotification(skippedVersionCode = 0L) {
                throw IllegalStateException("Hands unreachable")
            }
        )
    }
}
