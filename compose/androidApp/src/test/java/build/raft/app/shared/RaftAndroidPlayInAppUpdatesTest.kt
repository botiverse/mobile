package build.raft.app.shared

import android.app.Activity
import build.raft.app.data.RaftMinBuildGateDecision
import build.raft.app.data.SharedSettingsUpdateTransactionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaftAndroidPlayInAppUpdatesTest {
    @Test
    fun mapsAvailableUpdateWithPriorityAndStalenessIntoBridgePayload() {
        val snapshot =
            playSnapshot(
                updateAvailability = PlayUpdateAvailability.UPDATE_AVAILABLE,
                installStatus = PlayInstallStatus.UNKNOWN,
                availableVersionCode = 13_000_005L,
                updatePriority = 4,
                stalenessDays = 5,
            )

        val mapping = playInAppUpdateStateMapping(snapshot)
        assertEquals(SharedSettingsUpdateTransactionState.Idle, mapping.transactionState)
        assertTrue(mapping.updateAvailable)
        assertEquals(13_000_005L, mapping.targetVersionCode)
        assertTrue(mapping.retryable)
        assertEquals("", mapping.errorCode)
        assertEquals("", mapping.progressToken)

        val payload = playInAppUpdateStatusBridgePayload(snapshot, mapping, checkedAtMillis = 1_700_000_000L)
        assertEquals("ok", payload["status"])
        assertEquals(true, payload["updateCheckSupported"])
        assertEquals("idle", payload["updateTransactionState"])
        assertEquals(true, payload["updateAvailable"])
        assertEquals("1.29.0", payload["currentVersionLabel"])
        assertEquals(12_900_001L, payload["currentVersionCode"])
        assertEquals("1.30.0", payload["latestVersionLabel"])
        assertEquals(13_000_005L, payload["latestVersionCode"])
        assertEquals(4, payload["updatePriority"])
        assertEquals(5L, payload["updateStalenessDays"])
        assertEquals("https://play.google.com/store/apps/details?id=build.raft.app", payload["updateDownloadUrl"])
        assertEquals("", payload["updateDownloadProgressToken"])
        assertEquals("", payload["updateCheckErrorCode"])
    }

    @Test
    fun mapsPendingAndDownloadingWithLiveProgressToken() {
        for (status in listOf(PlayInstallStatus.PENDING, PlayInstallStatus.DOWNLOADING)) {
            val snapshot =
                playSnapshot(
                    updateAvailability = PlayUpdateAvailability.UPDATE_AVAILABLE,
                    installStatus = status,
                    availableVersionCode = 13_000_005L,
                    bytesDownloaded = 12_000_000L,
                    totalBytesToDownload = 48_000_000L,
                )
            val mapping = playInAppUpdateStateMapping(snapshot)
            assertEquals("status=$status", SharedSettingsUpdateTransactionState.Downloading, mapping.transactionState)
            assertTrue("status=$status", mapping.updateAvailable)
            assertFalse("status=$status", mapping.retryable)
            assertEquals("status=$status", "play_in_app:13000005", mapping.progressToken)
            assertEquals(13_000_005L, playInAppUpdateProgressTokenVersionCode(mapping.progressToken))
        }
    }

    @Test
    fun mapsDownloadedAndInstallingToReadyToRestartWithoutProgressToken() {
        val downloaded =
            playInAppUpdateStateMapping(
                playSnapshot(
                    updateAvailability = PlayUpdateAvailability.UPDATE_AVAILABLE,
                    installStatus = PlayInstallStatus.DOWNLOADED,
                    availableVersionCode = 13_000_005L,
                )
            )
        assertEquals(SharedSettingsUpdateTransactionState.ReadyToRestart, downloaded.transactionState)
        assertTrue(downloaded.updateAvailable)
        assertTrue(downloaded.retryable)
        assertEquals("", downloaded.progressToken)
        assertEquals("", downloaded.errorCode)

        val installing =
            playInAppUpdateStateMapping(
                playSnapshot(
                    updateAvailability = PlayUpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS,
                    installStatus = PlayInstallStatus.INSTALLING,
                    availableVersionCode = 13_000_005L,
                )
            )
        assertEquals(SharedSettingsUpdateTransactionState.ReadyToRestart, installing.transactionState)
        assertEquals("Installing", installing.statusLabel)
        assertEquals("", installing.progressToken)
    }

    @Test
    fun mapsInstalledFailedCanceledAndUnavailableTransitions() {
        val installed =
            playInAppUpdateStateMapping(
                playSnapshot(
                    updateAvailability = PlayUpdateAvailability.UPDATE_NOT_AVAILABLE,
                    installStatus = PlayInstallStatus.INSTALLED,
                    availableVersionCode = 13_000_005L,
                )
            )
        assertEquals(SharedSettingsUpdateTransactionState.Installed, installed.transactionState)
        assertFalse(installed.updateAvailable)
        assertFalse(installed.retryable)
        assertEquals("", installed.errorCode)

        val failed =
            playInAppUpdateStateMapping(
                playSnapshot(
                    updateAvailability = PlayUpdateAvailability.UPDATE_AVAILABLE,
                    installStatus = PlayInstallStatus.FAILED,
                    availableVersionCode = 13_000_005L,
                    installErrorCode = -6,
                )
            )
        assertEquals(SharedSettingsUpdateTransactionState.Failed, failed.transactionState)
        assertTrue(failed.updateAvailable)
        assertTrue(failed.retryable)
        assertEquals("play_in_app_update_failed_-6", failed.errorCode)

        val canceled =
            playInAppUpdateStateMapping(
                playSnapshot(
                    updateAvailability = PlayUpdateAvailability.UPDATE_AVAILABLE,
                    installStatus = PlayInstallStatus.CANCELED,
                    availableVersionCode = 13_000_005L,
                )
            )
        assertEquals(SharedSettingsUpdateTransactionState.Idle, canceled.transactionState)
        assertTrue(canceled.updateAvailable)
        assertTrue(canceled.retryable)
        assertEquals("", canceled.errorCode)

        val upToDate =
            playInAppUpdateStateMapping(
                playSnapshot(
                    updateAvailability = PlayUpdateAvailability.UPDATE_NOT_AVAILABLE,
                    installStatus = PlayInstallStatus.UNKNOWN,
                    availableVersionCode = 12_900_001L,
                )
            )
        assertEquals(SharedSettingsUpdateTransactionState.Idle, upToDate.transactionState)
        assertFalse(upToDate.updateAvailable)
        assertEquals("", upToDate.errorCode)

        val unknown =
            playInAppUpdateStateMapping(
                playSnapshot(
                    updateAvailability = PlayUpdateAvailability.UNKNOWN,
                    installStatus = PlayInstallStatus.UNKNOWN,
                    availableVersionCode = 0L,
                )
            )
        assertEquals(SharedSettingsUpdateTransactionState.Idle, unknown.transactionState)
        assertFalse(unknown.updateAvailable)
        assertEquals("store_update_unknown", unknown.errorCode)
    }

    @Test
    fun omitsStalenessWithNegativeSentinelWhenAbsent() {
        val snapshot =
            playSnapshot(
                updateAvailability = PlayUpdateAvailability.UPDATE_AVAILABLE,
                installStatus = PlayInstallStatus.UNKNOWN,
                availableVersionCode = 13_000_005L,
                stalenessDays = null,
            )
        val payload = playInAppUpdateStatusBridgePayload(snapshot, playInAppUpdateStateMapping(snapshot), checkedAtMillis = 1L)
        assertEquals(-1L, payload["updateStalenessDays"])
    }

    @Test
    fun formatsProgressPayloadAcrossActiveReadyFailedAndMismatchedTokens() {
        val token = "play_in_app:13000005"

        assertEquals(
            mapOf(
                "status" to "ok",
                "token" to token,
                "phase" to "downloading",
                "downloadedBytes" to 5_000L,
                "totalBytes" to 20_000L,
            ),
            playInAppUpdateProgressPayload(
                token = token,
                installStatus = PlayInstallStatus.DOWNLOADING,
                targetVersionCode = 13_000_005L,
                bytesDownloaded = 5_000L,
                totalBytesToDownload = 20_000L,
            ),
        )
        assertEquals(
            mapOf(
                "status" to "ok",
                "token" to token,
                "phase" to "downloading",
                "downloadedBytes" to 0L,
                "totalBytes" to -1L,
            ),
            playInAppUpdateProgressPayload(
                token = token,
                installStatus = PlayInstallStatus.PENDING,
                targetVersionCode = 13_000_005L,
                bytesDownloaded = -5L,
                totalBytesToDownload = 0L,
            ),
        )
        for (readyStatus in listOf(PlayInstallStatus.DOWNLOADED, PlayInstallStatus.INSTALLING, PlayInstallStatus.INSTALLED)) {
            assertEquals(
                "ready",
                playInAppUpdateProgressPayload(
                    token = token,
                    installStatus = readyStatus,
                    targetVersionCode = 13_000_005L,
                    bytesDownloaded = 20_000L,
                    totalBytesToDownload = 20_000L,
                )?.get("phase"),
            )
        }
        for (failedStatus in listOf(PlayInstallStatus.FAILED, PlayInstallStatus.CANCELED)) {
            assertEquals(
                "failed",
                playInAppUpdateProgressPayload(
                    token = token,
                    installStatus = failedStatus,
                    targetVersionCode = 13_000_005L,
                    bytesDownloaded = 2_000L,
                    totalBytesToDownload = 20_000L,
                )?.get("phase"),
            )
        }
        assertNull(
            playInAppUpdateProgressPayload(
                token = token,
                installStatus = PlayInstallStatus.DOWNLOADING,
                targetVersionCode = 13_000_006L,
                bytesDownloaded = 1_000L,
                totalBytesToDownload = 20_000L,
            )
        )
        assertNull(
            playInAppUpdateProgressPayload(
                token = "hands_sdk:raft-android:main:13000005",
                installStatus = PlayInstallStatus.DOWNLOADING,
                targetVersionCode = 13_000_005L,
                bytesDownloaded = 1_000L,
                totalBytesToDownload = 20_000L,
            )
        )
        assertNull(
            playInAppUpdateProgressPayload(
                token = token,
                installStatus = PlayInstallStatus.UNKNOWN,
                targetVersionCode = 13_000_005L,
                bytesDownloaded = 0L,
                totalBytesToDownload = 0L,
            )
        )
    }

    @Test
    fun interceptsImmediateUpdateOnlyWhenMinBuildGateBlocksAndListingMatches() {
        val block =
            RaftMinBuildGateDecision.Block(
                threshold = 13_000_000L,
                latestBuild = 13_000_005L,
                updateUrl = "https://play.google.com/store/apps/details?id=build.raft.app",
            )
        val banner = RaftMinBuildGateDecision.Banner(threshold = 13_000_000L, updateUrl = block.updateUrl)

        assertTrue(
            shouldInterceptPlayImmediateUpdate(
                url = "https://play.google.com/store/apps/details?id=build.raft.app",
                packageName = "build.raft.app",
                minBuildGateDecision = block,
            )
        )
        assertTrue(
            shouldInterceptPlayImmediateUpdate(
                url = "https://play.google.com/store/apps/details?id=build.raft.app&hl=en",
                packageName = "build.raft.app",
                minBuildGateDecision = block,
            )
        )
        assertFalse(
            shouldInterceptPlayImmediateUpdate(
                url = "https://play.google.com/store/apps/details?id=build.raft.app.other",
                packageName = "build.raft.app",
                minBuildGateDecision = block,
            )
        )
        assertFalse(
            shouldInterceptPlayImmediateUpdate(
                url = "https://hands.build/releases/raft.apk",
                packageName = "build.raft.app",
                minBuildGateDecision = block,
            )
        )
        assertFalse(
            shouldInterceptPlayImmediateUpdate(
                url = "https://play.google.com/store/apps/details?id=build.raft.app",
                packageName = "build.raft.app",
                minBuildGateDecision = banner,
            )
        )
        assertFalse(
            shouldInterceptPlayImmediateUpdate(
                url = "https://play.google.com/store/apps/details?id=build.raft.app",
                packageName = "build.raft.app",
                minBuildGateDecision = RaftMinBuildGateDecision.None,
            )
        )
    }

    @Test
    fun labelsPlayActivityResultsAndIgnoresUnrelatedRequestCodes() {
        assertEquals("ok", playInAppUpdateActivityResultLabel(PLAY_FLEXIBLE_UPDATE_REQUEST_CODE, Activity.RESULT_OK))
        assertEquals("canceled", playInAppUpdateActivityResultLabel(PLAY_FLEXIBLE_UPDATE_REQUEST_CODE, Activity.RESULT_CANCELED))
        assertEquals("failed", playInAppUpdateActivityResultLabel(PLAY_IMMEDIATE_UPDATE_REQUEST_CODE, PLAY_ACTIVITY_RESULT_IN_APP_UPDATE_FAILED))
        assertEquals("unknown_99", playInAppUpdateActivityResultLabel(PLAY_IMMEDIATE_UPDATE_REQUEST_CODE, 99))
        assertNull(playInAppUpdateActivityResultLabel(1234, Activity.RESULT_OK))
    }

    @Test
    fun formatsPlayVersionLabelFromBuildNumber() {
        assertEquals("1.30.0", playVersionLabelOfCode(13_000_005L))
        assertEquals("1.29.2", playVersionLabelOfCode(12_902_000L))
        assertEquals("", playVersionLabelOfCode(0L))
        assertEquals("", playVersionLabelOfCode(9_999_999L))
    }

    private fun playSnapshot(
        updateAvailability: Int,
        installStatus: Int,
        availableVersionCode: Long,
        currentVersionCode: Long = 12_900_001L,
        currentVersionLabel: String = "1.29.0",
        updatePriority: Int = 0,
        stalenessDays: Int? = null,
        bytesDownloaded: Long = 0L,
        totalBytesToDownload: Long = 0L,
        installErrorCode: Int = 0,
    ): PlayInAppUpdateSnapshot =
        PlayInAppUpdateSnapshot(
            updateAvailability = updateAvailability,
            installStatus = installStatus,
            availableVersionCode = availableVersionCode,
            currentVersionCode = currentVersionCode,
            currentVersionLabel = currentVersionLabel,
            updatePriority = updatePriority,
            stalenessDays = stalenessDays,
            bytesDownloaded = bytesDownloaded,
            totalBytesToDownload = totalBytesToDownload,
            installErrorCode = installErrorCode,
            flexibleAllowed = true,
            immediateAllowed = true,
            listingUrl = "https://play.google.com/store/apps/details?id=build.raft.app",
        )
}
