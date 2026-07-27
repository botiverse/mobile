package build.raft.app

import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.SharedSettingsUpdateTransactionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdatePageTest {
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
    fun appUpdateUsesScopedHandsChangelogWithoutRewritingMarkdown() {
        val markdown = "# D / 1000004\n\n- **Dedicated** Alpha notes"
        assertEquals(
            markdown,
            appUpdateInlineChangelog(
                SharedSettingsUpdateCheckSnapshot(
                    latestVersionCode = 1_000_004L,
                    latestChangelog = markdown
                )
            )
        )
        assertEquals(null, appUpdateInlineChangelog(SharedSettingsUpdateCheckSnapshot(latestVersionCode = 1_000_004L)))
    }

    @Test
    fun installCommandKeepsSameTargetReleaseCopyWhenStatusResponseIsSparse() {
        val previous =
            SharedSettingsUpdateCheckSnapshot(
                latestVersionLabel = "1.0.0-alpha",
                latestVersionCode = 1_000_011L,
                latestChangelog = "# 中文更新说明",
                downloadUrl = "https://example.test/1000011.apk"
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
        assertEquals("", successor.latestVersionLabel)
        assertEquals("", successor.latestChangelog)
        assertEquals("", successor.downloadUrl)
        assertEquals("", sparseSuccessor.latestVersionLabel)
        assertEquals("", sparseSuccessor.latestChangelog)
        assertEquals("", sparseSuccessor.downloadUrl)
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
                        errorCode = null
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
                errorCode = "network_error"
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
                errorCode = "signer_mismatch"
            )
        )
    }

    @Test
    fun appUpdateUsesInstallAndCompletionIconsForPersistedTransaction() {
        assertEquals(SlockLucideIconName.RefreshCw, appUpdatePrimaryActionIcon(null, "network_error"))
        assertEquals(
            SlockLucideIconName.PackageCheck,
            appUpdatePrimaryActionIcon(
                SharedSettingsUpdateCheckSnapshot(
                    transactionState = SharedSettingsUpdateTransactionState.ReadyToInstall
                ),
                null
            )
        )
        assertEquals(
            SlockLucideIconName.CircleCheckBig,
            appUpdatePrimaryActionIcon(
                SharedSettingsUpdateCheckSnapshot(
                    transactionState = SharedSettingsUpdateTransactionState.Installed
                ),
                null
            )
        )
        assertEquals(
            SlockLucideIconName.RefreshCw,
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
}
