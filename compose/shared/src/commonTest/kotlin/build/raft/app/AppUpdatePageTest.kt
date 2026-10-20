package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.SharedSettingsUpdateTransactionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdatePageTest {
    private val errorStrings = englishErrorPresentationStringsForTest()

    @Test
    fun appUpdateErrorsUseHonestStateMessagesInsteadOfBareCodes() {
        assertEquals(
            "Update service maintenance is in progress",
            DataResult.Error(
                ScreenError(
                    code = "update_backend_restarting",
                    serverMessage = "Update service maintenance is in progress"
                )
            ).appUpdateErrorMessage("Updates are unavailable", errorStrings)
        )
        assertEquals(
            "Something went wrong. Please try again.",
            DataResult.Error(ScreenError(code = "update_unknown"))
                .appUpdateErrorMessage("Updates are unavailable", errorStrings)
        )
        assertEquals(
            "You do not have permission to view this page.",
            DataResult.NoPermission(PermissionBlocker(code = "forbidden"))
                .appUpdateErrorMessage("Updates are unavailable", errorStrings)
        )
        assertEquals(
            "Updates are unavailable",
            DataResult.Empty(EmptyReason.NoSettingsAvailable)
                .appUpdateErrorMessage("Updates are unavailable", errorStrings)
        )
    }

    @Test
    fun appUpdateContentFailurePreservesItsHumanReadableDetail() {
        val result =
            DataResult.Content(
                SharedSettingsUpdateCheckSnapshot(
                    errorCode = "update_download_failed",
                    detail = "The update package could not be downloaded"
                )
            )

        assertEquals(
            "The update package could not be downloaded",
            result.appUpdateErrorMessage("Updates are unavailable", errorStrings)
        )
    }

    @Test
    fun appUpdateNetworkFailureKeepsSnapshotButDisplaysCurrentServerReason() {
        val previous =
            SharedSettingsUpdateCheckSnapshot(
                latestVersionLabel = "1.9.0",
                detail = "Previous successful update detail"
            )

        val projection =
            DataResult.Error(
                ScreenError(
                    code = "update_backend_restarting",
                    serverMessage = "Update service maintenance is in progress"
                )
            ).toAppUpdateResultProjection(previous, "Updates are unavailable", errorStrings)

        assertEquals(previous, projection.snapshot)
        assertEquals("Update service maintenance is in progress", projection.errorMessage)
    }

    @Test
    fun appUpdateContentFailureUsesCurrentDetailBeforeSnapshotFallback() {
        val previous =
            SharedSettingsUpdateCheckSnapshot(
                latestVersionCode = 1_000_011L,
                detail = "Previous successful update detail",
                downloadSizesResolved = true,
                fullDownloadSizeBytes = 94_411_540L
            )
        val current =
            SharedSettingsUpdateCheckSnapshot(
                transactionTargetVersionCode = 1_000_011L,
                errorCode = "update_download_failed",
                detail = "This update download failed",
                downloadSizesResolved = false
            )

        val projection =
            DataResult.Content(current)
                .toAppUpdateResultProjection(previous, "Updates are unavailable", errorStrings)

        assertEquals("This update download failed", projection.errorMessage)
        assertEquals("This update download failed", projection.snapshot?.detail)
        assertEquals(94_411_540L, projection.snapshot?.fullDownloadSizeBytes)
    }

    @Test
    fun appUpdateRefreshesAfterReturningFromTheSystemInstaller() {
        assertFalse(appUpdatePageShouldRefreshOnResume(0))
        assertTrue(appUpdatePageShouldRefreshOnResume(1))
        assertTrue(appUpdatePageShouldRefreshOnResume(2))
    }

    @Test
    fun appUpdateRefreshIsSingleFlightAcrossResumeAndStatusEvents() {
        val gate = AppUpdateRefreshGate()

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        assertFalse(gate.tryStart())
        assertTrue(gate.completeAndShouldContinue())
        assertFalse(gate.completeAndShouldContinue())
        assertTrue(gate.tryStart())
        gate.reset()
        assertTrue(gate.tryStart())
    }

    @Test
    fun installCommandKeepsSameTargetReleaseCopyWhenStatusResponseIsSparse() {
        val previous =
            SharedSettingsUpdateCheckSnapshot(
                latestVersionLabel = "1.0.0-alpha",
                latestVersionCode = 1_000_011L,
                latestChangelog = "# 中文更新说明",
                downloadUrl = "https://example.test/1000011.apk",
                detail = "增量包 93.0 KB；完整包备用 90.0 MB。",
                downloadSizesResolved = true,
                patchDownloadSizeBytes = 95_256L,
                fullDownloadSizeBytes = 94_411_540L
            )
        val sparse =
            SharedSettingsUpdateCheckSnapshot(
                transactionState = SharedSettingsUpdateTransactionState.Downloading,
                transactionTargetVersionCode = 1_000_011L
            ).withAppUpdateDisplayFallback(previous)
        val successor =
            SharedSettingsUpdateCheckSnapshot(
                latestVersionCode = 1_000_012L
            ).withAppUpdateDisplayFallback(previous)
        val sparseSuccessor =
            SharedSettingsUpdateCheckSnapshot(
                transactionState = SharedSettingsUpdateTransactionState.Checking,
                transactionTargetVersionCode = 1_000_012L
            ).withAppUpdateDisplayFallback(previous)

        assertEquals("1.0.0-alpha", sparse.latestVersionLabel)
        assertEquals(1_000_011L, sparse.latestVersionCode)
        assertEquals("# 中文更新说明", sparse.latestChangelog)
        assertEquals("https://example.test/1000011.apk", sparse.downloadUrl)
        assertEquals("增量包 93.0 KB；完整包备用 90.0 MB。", sparse.detail)
        assertTrue(sparse.downloadSizesResolved)
        assertEquals(95_256L, sparse.patchDownloadSizeBytes)
        assertEquals(94_411_540L, sparse.fullDownloadSizeBytes)
        assertEquals("", successor.latestVersionLabel)
        assertEquals("", successor.latestChangelog)
        assertEquals("", successor.downloadUrl)
        assertEquals("", sparseSuccessor.latestVersionLabel)
        assertEquals("", sparseSuccessor.latestChangelog)
        assertEquals("", sparseSuccessor.downloadUrl)
    }

    @Test
    fun freshSameTargetSizeAuthorityClearsAWithdrawnPatch() {
        val previous =
            SharedSettingsUpdateCheckSnapshot(
                latestVersionCode = 1_000_011L,
                detail = "增量包 93.0 KB；完整包备用 90.0 MB。",
                downloadSizesResolved = true,
                patchDownloadSizeBytes = 95_256L,
                fullDownloadSizeBytes = 94_411_540L
            )
        val refreshed =
            SharedSettingsUpdateCheckSnapshot(
                latestVersionCode = 1_000_011L,
                detail = "Raft 有可用更新（90.0 MB APK）。",
                downloadSizesResolved = true,
                patchDownloadSizeBytes = 0L,
                fullDownloadSizeBytes = 94_411_540L
            ).withAppUpdateDisplayFallback(previous)

        assertTrue(refreshed.downloadSizesResolved)
        assertEquals("Raft 有可用更新（90.0 MB APK）。", refreshed.detail)
        assertEquals(0L, refreshed.patchDownloadSizeBytes)
        assertEquals(94_411_540L, refreshed.fullDownloadSizeBytes)
    }

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
    fun appUpdateActionMapsEveryTransactionStateWithoutReadingEnglishHostLabels() {
        val labels =
            SharedSettingsUpdateTransactionState.entries
                .filterNot { it == SharedSettingsUpdateTransactionState.Unknown }
                .associateWith { state ->
                    appUpdatePrimaryActionLabel(
                        snapshot =
                            SharedSettingsUpdateCheckSnapshot(
                                transactionState = state,
                                statusLabel = "not-an-action",
                                actionLabel = "not-an-action"
                            ),
                        actionRunning = false
                    )
                }

        assertEquals("Download", labels[SharedSettingsUpdateTransactionState.Idle])
        assertEquals("Checking", labels[SharedSettingsUpdateTransactionState.Checking])
        assertEquals("Preparing", labels[SharedSettingsUpdateTransactionState.Patching])
        assertEquals("Downloading", labels[SharedSettingsUpdateTransactionState.Downloading])
        assertEquals("Install", labels[SharedSettingsUpdateTransactionState.ReadyToInstall])
        assertEquals("Install", labels[SharedSettingsUpdateTransactionState.InstallerOpened])
        assertEquals("Download", labels[SharedSettingsUpdateTransactionState.Failed])
        assertEquals("Download", labels[SharedSettingsUpdateTransactionState.Stale])
        assertEquals("Installed", labels[SharedSettingsUpdateTransactionState.Installed])
    }

    @Test
    fun appUpdateActionOnlyEnablesSupportedActionableState() {
        assertTrue(
            appUpdatePrimaryActionEnabled(
                snapshot = null,
                loading = false,
                actionRunning = false,
                errorMessage = "The network connection failed."
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
                errorMessage = null
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
                errorMessage = "The download was canceled."
            )
        )
        assertFalse(
            appUpdatePrimaryActionEnabled(
                snapshot = SharedSettingsUpdateCheckSnapshot(supported = false, updateAvailable = true),
                loading = false,
                actionRunning = false,
                errorMessage = null
            )
        )
        assertFalse(
            appUpdatePrimaryActionEnabled(
                snapshot = SharedSettingsUpdateCheckSnapshot(supported = true, updateAvailable = true, statusLabel = "downloading"),
                loading = false,
                actionRunning = false,
                errorMessage = null
            )
        )
        assertFalse(
            appUpdatePrimaryActionEnabled(
                snapshot = SharedSettingsUpdateCheckSnapshot(supported = true, updateAvailable = true, statusLabel = "installing"),
                loading = false,
                actionRunning = false,
                errorMessage = null
            )
        )
        assertTrue(
            appUpdatePrimaryActionEnabled(
                snapshot = SharedSettingsUpdateCheckSnapshot(supported = true, updateAvailable = true, statusLabel = "install"),
                loading = false,
                actionRunning = false,
                errorMessage = null
            )
        )
        val transactionEnabled =
            SharedSettingsUpdateTransactionState.entries
                .filterNot { it == SharedSettingsUpdateTransactionState.Unknown }
                .associateWith { state ->
                    appUpdatePrimaryActionEnabled(
                        snapshot =
                            SharedSettingsUpdateCheckSnapshot(
                                supported = true,
                                updateAvailable = true,
                                transactionState = state,
                                transactionRetryable =
                                    state == SharedSettingsUpdateTransactionState.Failed ||
                                        state == SharedSettingsUpdateTransactionState.Stale
                            ),
                        loading = false,
                        actionRunning = false,
                        errorMessage = null
                    )
                }
        assertTrue(transactionEnabled.getValue(SharedSettingsUpdateTransactionState.Idle))
        assertFalse(transactionEnabled.getValue(SharedSettingsUpdateTransactionState.Checking))
        assertFalse(transactionEnabled.getValue(SharedSettingsUpdateTransactionState.Patching))
        assertFalse(transactionEnabled.getValue(SharedSettingsUpdateTransactionState.Downloading))
        assertTrue(transactionEnabled.getValue(SharedSettingsUpdateTransactionState.ReadyToInstall))
        assertTrue(transactionEnabled.getValue(SharedSettingsUpdateTransactionState.InstallerOpened))
        assertTrue(transactionEnabled.getValue(SharedSettingsUpdateTransactionState.Failed))
        assertTrue(transactionEnabled.getValue(SharedSettingsUpdateTransactionState.Stale))
        assertFalse(transactionEnabled.getValue(SharedSettingsUpdateTransactionState.Installed))
        assertTrue(
            appUpdatePrimaryActionEnabled(
                snapshot =
                    SharedSettingsUpdateCheckSnapshot(
                        supported = true,
                        updateAvailable = false,
                        transactionState = SharedSettingsUpdateTransactionState.Failed,
                        transactionRetryable = true
                    ),
                loading = false,
                actionRunning = false,
                errorMessage = "The network connection failed."
            )
        )
        assertFalse(
            appUpdatePrimaryActionEnabled(
                snapshot =
                    SharedSettingsUpdateCheckSnapshot(
                        supported = true,
                        updateAvailable = true,
                        transactionState = SharedSettingsUpdateTransactionState.Failed,
                        transactionRetryable = false
                    ),
                loading = false,
                actionRunning = false,
                errorMessage = "The package signer did not match."
            )
        )
    }

    @Test
    fun appUpdateUsesInstallAndCompletionIconsForPersistedTransaction() {
        assertEquals(RaftLucideIconName.RefreshCw, appUpdatePrimaryActionIcon(null, "network_error"))
        assertEquals(
            RaftLucideIconName.PackageCheck,
            appUpdatePrimaryActionIcon(
                SharedSettingsUpdateCheckSnapshot(
                    transactionState = SharedSettingsUpdateTransactionState.ReadyToInstall
                ),
                null
            )
        )
        assertEquals(
            RaftLucideIconName.CircleCheckBig,
            appUpdatePrimaryActionIcon(
                SharedSettingsUpdateCheckSnapshot(
                    transactionState = SharedSettingsUpdateTransactionState.Installed
                ),
                null
            )
        )
        assertEquals(
            RaftLucideIconName.RefreshCw,
            appUpdatePrimaryActionIcon(
                SharedSettingsUpdateCheckSnapshot(
                    transactionState = SharedSettingsUpdateTransactionState.Failed,
                    transactionRetryable = true
                ),
                "download_failed"
            )
        )
    }

    @Test
    fun skipVersionDisappearsOnceAnUpdateTransactionStarts() {
        fun snapshot(state: SharedSettingsUpdateTransactionState) =
            SharedSettingsUpdateCheckSnapshot(
                supported = true,
                updateAvailable = true,
                currentVersionCode = 1_000_010L,
                latestVersionCode = 1_000_011L,
                transactionState = state
            )

        assertTrue(appUpdateCanSkipVersion(snapshot(SharedSettingsUpdateTransactionState.Idle)))
        assertTrue(appUpdateCanSkipVersion(snapshot(SharedSettingsUpdateTransactionState.Stale)))
        assertFalse(appUpdateCanSkipVersion(snapshot(SharedSettingsUpdateTransactionState.Patching)))
        assertFalse(appUpdateCanSkipVersion(snapshot(SharedSettingsUpdateTransactionState.Downloading)))
        assertFalse(appUpdateCanSkipVersion(snapshot(SharedSettingsUpdateTransactionState.ReadyToInstall)))
        assertFalse(appUpdateCanSkipVersion(snapshot(SharedSettingsUpdateTransactionState.Installed)))
    }

    @Test
    fun sameVersionNameShowsTheBuildOnBothSides() {
        assertEquals(
            "1.13.0 (11300001)" to "1.13.0 (11300008)",
            appUpdateVersionLabels("1.13.0", 11_300_001L, "1.13.0", 11_300_008L),
        )
        // Different versions keep the plain names.
        assertEquals("1.13.0" to "1.13.1", appUpdateVersionLabels("1.13.0", 11_300_008L, "1.13.1", 11_301_001L))
        // Unknown build on either side, or the very same build: nothing to add.
        assertEquals("1.13.0" to "1.13.0", appUpdateVersionLabels("1.13.0", 0L, "1.13.0", 11_300_008L))
        assertEquals("1.13.0" to "1.13.0", appUpdateVersionLabels("1.13.0", 11_300_008L, "1.13.0", 11_300_008L))
        // Unknown names stay blank (the page shows "-"), never "- (1)".
        assertEquals("" to "", appUpdateVersionLabels("", 1L, "", 2L))
    }
}
