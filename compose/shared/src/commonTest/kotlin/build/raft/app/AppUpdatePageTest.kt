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
    fun appUpdateRefreshesAfterReturningFromTheSystemInstaller() {
        assertFalse(appUpdatePageShouldRefreshOnResume(0))
        assertTrue(appUpdatePageShouldRefreshOnResume(1))
        assertTrue(appUpdatePageShouldRefreshOnResume(2))
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
        assertEquals("Install", labels[SharedSettingsUpdateTransactionState.ReadyToRestart])
        assertEquals("Download", labels[SharedSettingsUpdateTransactionState.Failed])
        assertEquals("Download", labels[SharedSettingsUpdateTransactionState.Stale])
        assertEquals("Installed", labels[SharedSettingsUpdateTransactionState.Installed])
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
        assertFalse(appUpdateCanSkipVersion(snapshot(SharedSettingsUpdateTransactionState.ReadyToRestart)))
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
