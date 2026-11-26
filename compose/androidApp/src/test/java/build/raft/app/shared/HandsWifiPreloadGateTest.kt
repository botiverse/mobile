package build.raft.app.shared

import android.app.DownloadManager
import build.raft.app.data.SharedSettingsUpdateTransactionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsWifiPreloadGateTest {
    @Test
    fun allowsPreloadWhenAllConditionsAreSatisfied() {
        assertEquals(HandsWifiPreloadDecision.Preload, decideHandsWifiPreload(eligibleContext()))
    }

    @Test
    fun skipsWhenApkUpdatesAreDisabled() {
        assertEquals(
            HandsWifiPreloadDecision.SkipDisabled,
            decideHandsWifiPreload(eligibleContext(apkUpdateEnabled = false)),
        )
    }

    @Test
    fun skipsWhenPrivacyModeDisallowsAutomaticChecks() {
        assertEquals(
            HandsWifiPreloadDecision.SkipPrivacyRestricted,
            decideHandsWifiPreload(eligibleContext(automaticAllowed = false)),
        )
    }

    @Test
    fun skipsOnMeteredOrNonWifiNetworks() {
        assertEquals(
            HandsWifiPreloadDecision.SkipMeteredOrNotWifi,
            decideHandsWifiPreload(eligibleContext(unmeteredWifi = false)),
        )
    }

    @Test
    fun skipsWhenBatteryIsConstrained() {
        assertEquals(
            HandsWifiPreloadDecision.SkipBatteryConstrained,
            decideHandsWifiPreload(eligibleContext(batteryNotLow = false)),
        )
    }

    @Test
    fun skipsWhenNoNewerUpdateIsAvailable() {
        assertEquals(
            HandsWifiPreloadDecision.SkipNotAvailable,
            decideHandsWifiPreload(eligibleContext(updateAvailable = false)),
        )
        assertEquals(
            HandsWifiPreloadDecision.SkipNotAvailable,
            decideHandsWifiPreload(eligibleContext(latestVersionCode = 0L)),
        )
        assertEquals(
            HandsWifiPreloadDecision.SkipNotAvailable,
            decideHandsWifiPreload(eligibleContext(currentVersionCode = 13_000_002L, latestVersionCode = 13_000_002L)),
        )
        assertEquals(
            HandsWifiPreloadDecision.SkipNotAvailable,
            decideHandsWifiPreload(eligibleContext(currentVersionCode = 13_000_003L, latestVersionCode = 13_000_002L)),
        )
    }

    @Test
    fun skipsWhenTransactionIsAlreadyActiveReadyOrInstalled() {
        val blockedStates =
            listOf(
                SharedSettingsUpdateTransactionState.Checking,
                SharedSettingsUpdateTransactionState.Patching,
                SharedSettingsUpdateTransactionState.Downloading,
                SharedSettingsUpdateTransactionState.ReadyToInstall,
                SharedSettingsUpdateTransactionState.InstallerOpened,
                SharedSettingsUpdateTransactionState.ReadyToRestart,
                SharedSettingsUpdateTransactionState.Installed,
            )
        blockedStates.forEach { state ->
            assertEquals(
                "state=$state",
                HandsWifiPreloadDecision.SkipAlreadyActiveOrReady,
                decideHandsWifiPreload(eligibleContext(transactionState = state)),
            )
        }

        val resumableStates =
            listOf(
                SharedSettingsUpdateTransactionState.Unknown,
                SharedSettingsUpdateTransactionState.Idle,
                SharedSettingsUpdateTransactionState.Failed,
                SharedSettingsUpdateTransactionState.Stale,
            )
        resumableStates.forEach { state ->
            assertEquals(
                "state=$state",
                HandsWifiPreloadDecision.Preload,
                decideHandsWifiPreload(eligibleContext(transactionState = state)),
            )
        }
    }

    @Test
    fun suppressesDuplicatePreloadAttemptsForSameVersionButAllowsNewerVersion() {
        assertEquals(
            HandsWifiPreloadDecision.SkipAlreadyAttempted,
            decideHandsWifiPreload(
                eligibleContext(
                    latestVersionCode = 13_000_002L,
                    alreadyAttemptedVersionCode = 13_000_002L,
                )
            ),
        )
        assertEquals(
            HandsWifiPreloadDecision.Preload,
            decideHandsWifiPreload(
                eligibleContext(
                    latestVersionCode = 13_000_003L,
                    alreadyAttemptedVersionCode = 13_000_002L,
                )
            ),
        )
    }

    @Test
    fun batteryHelperEnforcesThresholdChargingAndPowerSaveMode() {
        assertTrue(isHandsWifiPreloadBatteryNotLow(batteryPercent = 16, isCharging = false, isPowerSaveMode = false))
        assertTrue(isHandsWifiPreloadBatteryNotLow(batteryPercent = 100, isCharging = false, isPowerSaveMode = false))
        assertFalse(isHandsWifiPreloadBatteryNotLow(batteryPercent = 15, isCharging = false, isPowerSaveMode = false))
        assertFalse(isHandsWifiPreloadBatteryNotLow(batteryPercent = 5, isCharging = false, isPowerSaveMode = false))
        assertFalse(isHandsWifiPreloadBatteryNotLow(batteryPercent = null, isCharging = false, isPowerSaveMode = false))
        assertTrue(isHandsWifiPreloadBatteryNotLow(batteryPercent = 10, isCharging = true, isPowerSaveMode = false))
        assertTrue(isHandsWifiPreloadBatteryNotLow(batteryPercent = null, isCharging = true, isPowerSaveMode = false))
        assertFalse(isHandsWifiPreloadBatteryNotLow(batteryPercent = 90, isCharging = true, isPowerSaveMode = true))
        assertFalse(isHandsWifiPreloadBatteryNotLow(batteryPercent = 90, isCharging = false, isPowerSaveMode = true))
    }

    @Test
    fun explicitInstallReplacesWifiOnlyPausedOrPendingPreloadsAndQueuedForWifiOrRetryPausedDownloads() {
        // Silent Wi-Fi-only preload paused or pending off Wi-Fi must be replaced with an unrestricted download.
        assertTrue(
            shouldReplaceHandsDownloadOnExplicitInstall(
                status = DownloadManager.STATUS_PAUSED,
                reason = DownloadManager.PAUSED_QUEUED_FOR_WIFI,
                unmeteredWifiOnly = true,
            )
        )
        assertTrue(
            shouldReplaceHandsDownloadOnExplicitInstall(
                status = DownloadManager.STATUS_PAUSED,
                reason = DownloadManager.PAUSED_WAITING_FOR_NETWORK,
                unmeteredWifiOnly = true,
            )
        )
        assertTrue(
            shouldReplaceHandsDownloadOnExplicitInstall(
                status = DownloadManager.STATUS_PENDING,
                reason = null,
                unmeteredWifiOnly = true,
            )
        )

        // Actively running or completed Wi-Fi preload is reused instead of discarding progress.
        assertFalse(
            shouldReplaceHandsDownloadOnExplicitInstall(
                status = DownloadManager.STATUS_RUNNING,
                reason = null,
                unmeteredWifiOnly = true,
            )
        )
        assertFalse(
            shouldReplaceHandsDownloadOnExplicitInstall(
                status = DownloadManager.STATUS_SUCCESSFUL,
                reason = null,
                unmeteredWifiOnly = true,
            )
        )

        // User-initiated download: keep running/pending/waiting-for-network, but replace queued-for-Wi-Fi, retry-paused, or failed.
        assertFalse(
            shouldReplaceHandsDownloadOnExplicitInstall(
                status = DownloadManager.STATUS_PENDING,
                reason = null,
                unmeteredWifiOnly = false,
            )
        )
        assertFalse(
            shouldReplaceHandsDownloadOnExplicitInstall(
                status = DownloadManager.STATUS_PAUSED,
                reason = DownloadManager.PAUSED_WAITING_FOR_NETWORK,
                unmeteredWifiOnly = false,
            )
        )
        assertTrue(
            shouldReplaceHandsDownloadOnExplicitInstall(
                status = DownloadManager.STATUS_PAUSED,
                reason = DownloadManager.PAUSED_QUEUED_FOR_WIFI,
                unmeteredWifiOnly = false,
            )
        )
        assertTrue(
            shouldReplaceHandsDownloadOnExplicitInstall(
                status = DownloadManager.STATUS_PAUSED,
                reason = DownloadManager.PAUSED_WAITING_TO_RETRY,
                unmeteredWifiOnly = false,
            )
        )
        assertTrue(
            shouldReplaceHandsDownloadOnExplicitInstall(
                status = DownloadManager.STATUS_FAILED,
                reason = null,
                unmeteredWifiOnly = false,
            )
        )
    }

    @Test
    fun silentPausedWifiPreloadFallsBackToIdleWhileExplicitAndRunningDownloadsStayDownloading() {
        assertFalse(
            shouldExposeHandsDownloadAsDownloading(
                status = DownloadManager.STATUS_PAUSED,
                unmeteredWifiOnly = true,
                autoOpenOnComplete = false,
            )
        )
        assertTrue(
            shouldExposeHandsDownloadAsDownloading(
                status = DownloadManager.STATUS_RUNNING,
                unmeteredWifiOnly = true,
                autoOpenOnComplete = false,
            )
        )
        assertTrue(
            shouldExposeHandsDownloadAsDownloading(
                status = DownloadManager.STATUS_PAUSED,
                unmeteredWifiOnly = false,
                autoOpenOnComplete = true,
            )
        )
        assertTrue(
            shouldExposeHandsDownloadAsDownloading(
                status = DownloadManager.STATUS_PAUSED,
                unmeteredWifiOnly = true,
                autoOpenOnComplete = true,
            )
        )
        assertFalse(
            shouldExposeHandsDownloadAsDownloading(
                status = DownloadManager.STATUS_FAILED,
                unmeteredWifiOnly = false,
                autoOpenOnComplete = true,
            )
        )
    }

    private fun eligibleContext(
        apkUpdateEnabled: Boolean = true,
        automaticAllowed: Boolean = true,
        unmeteredWifi: Boolean = true,
        batteryNotLow: Boolean = true,
        updateAvailable: Boolean = true,
        currentVersionCode: Long = 13_000_001L,
        latestVersionCode: Long = 13_000_002L,
        transactionState: SharedSettingsUpdateTransactionState = SharedSettingsUpdateTransactionState.Idle,
        alreadyAttemptedVersionCode: Long = 0L,
    ): HandsWifiPreloadContext =
        HandsWifiPreloadContext(
            apkUpdateEnabled = apkUpdateEnabled,
            automaticAllowed = automaticAllowed,
            unmeteredWifi = unmeteredWifi,
            batteryNotLow = batteryNotLow,
            updateAvailable = updateAvailable,
            currentVersionCode = currentVersionCode,
            latestVersionCode = latestVersionCode,
            transactionState = transactionState,
            alreadyAttemptedVersionCode = alreadyAttemptedVersionCode,
        )
}
