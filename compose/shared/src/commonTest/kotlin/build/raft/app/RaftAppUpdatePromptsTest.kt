package build.raft.app

import build.raft.app.data.APP_UPDATE_HOME_NOTIFICATION_ID
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.SharedSettingsUpdateTransactionState
import build.raft.app.data.toSharedHomeAppUpdateNotification
import build.raft.app.resources.Res
import build.raft.app.resources.app_update_detail_available
import build.raft.app.resources.app_update_detail_available_with_size
import build.raft.app.resources.app_update_detail_current
import build.raft.app.resources.desc
import build.raft.app.resources.format
import build.raft.app.settings.RaftTopBarUpdatePresentation
import build.raft.app.settings.raftAppTopBarUpdatePresentation
import build.raft.app.settings.raftAppUpdateChangelogPreviewLines
import build.raft.app.settings.raftAppUpdateStrongPromptDownloadSizeLabel
import build.raft.app.settings.raftAppUpdateStrongPromptVersionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftAppUpdatePromptsTest {
    @Test
    fun topBarPresentationMapsUpdateInstallAndRestartStates() {
        fun present(state: SharedSettingsUpdateTransactionState, latestLabel: String = "1.30.0") =
            raftAppTopBarUpdatePresentation(
                snapshot =
                    SharedSettingsUpdateCheckSnapshot(
                        supported = true,
                        updateAvailable = true,
                        latestVersionLabel = latestLabel,
                        latestVersionCode = 13_000_000L,
                        transactionState = state,
                    ),
                updateLabel = "Update",
                installLabel = "Install",
                restartLabel = "Restart to update",
                updateTooltipForVersion = { "Update Raft to $it" },
                installTooltipForVersion = { "Install Raft $it" },
                restartTooltipForVersion = { "Restart to update Raft to $it" },
            )

        assertEquals(
            RaftTopBarUpdatePresentation("Update", "Update Raft to 1.30.0"),
            present(SharedSettingsUpdateTransactionState.Idle),
        )
        assertEquals(
            RaftTopBarUpdatePresentation("Install", "Install Raft 1.30.0"),
            present(SharedSettingsUpdateTransactionState.ReadyToInstall),
        )
        assertEquals(
            RaftTopBarUpdatePresentation("Install", "Install Raft 1.30.0"),
            present(SharedSettingsUpdateTransactionState.InstallerOpened),
        )
        assertEquals(
            RaftTopBarUpdatePresentation("Restart to update", "Restart to update Raft to 1.30.0"),
            present(SharedSettingsUpdateTransactionState.ReadyToRestart),
        )
        // Blank version falls back cleanly to the short label.
        assertEquals(
            RaftTopBarUpdatePresentation("Update", "Update"),
            present(SharedSettingsUpdateTransactionState.Idle, latestLabel = "  "),
        )
    }

    @Test
    fun changelogPreviewTrimsHeadingsRulesStripsMarkdownAndLimitsToThreeLines() {
        val raw =
            """
            # Raft 1.30.0

            ---
            - Faster startup
            - Wide top bar update button
            - Smarter reminders
            - Extra line omitted
            """.trimIndent()

        assertEquals(
            listOf(
                "Raft 1.30.0",
                "• Faster startup",
                "• Wide top bar update button",
            ),
            raftAppUpdateChangelogPreviewLines(raw),
        )
        assertEquals(emptyList(), raftAppUpdateChangelogPreviewLines("   \n---\n***\n- - -\n```kotlin\n   "))
    }

    @Test
    fun changelogPreviewStripsInlineMarkdownAndPreservesIdentifiersAndMath() {
        val raw =
            """
            ### ✨ **New**
            - **App authorizations & review alerts**: Added **App authorizations** in `Settings` ([learn more](https://raft.build/docs)).
            * *Italic*, __underline bold__, ***both***, and ~~old~~ with `foo_bar_baz` and 2 * 3 * 4 items
            + ![Badge](https://raft.build/img.png) [Raft **Docs**](https://raft.build)
            > **Quoted** _note_ with escaped \*stars\*
            """.trimIndent()

        assertEquals(
            listOf(
                "✨ New",
                "• App authorizations & review alerts: Added App authorizations in Settings (learn more).",
                "• Italic, underline bold, both, and old with foo_bar_baz and 2 * 3 * 4 items",
                "• Badge Raft Docs",
                "Quoted note with escaped *stars*",
            ),
            raftAppUpdateChangelogPreviewLines(raw, maxLines = 5),
        )
        // Empty bullets or markdown-only lines are dropped cleanly.
        assertEquals(
            listOf("• Real item"),
            raftAppUpdateChangelogPreviewLines("- \n*   \n+ Real **item**"),
        )
    }

    @Test
    fun strongPromptDownloadSizePrefersPatchOverFullAndOmitsWhenReady() {
        val base =
            SharedSettingsUpdateCheckSnapshot(
                supported = true,
                updateAvailable = true,
                currentVersionLabel = "1.30.0",
                currentVersionCode = 13_000_000L,
                latestVersionLabel = "1.31.0",
                latestVersionCode = 13_100_000L,
                patchDownloadSizeBytes = 29_696L,
                fullDownloadSizeBytes = 94_411_540L,
            )

        // Not ready: patch size wins over full size.
        val patchLabel = raftAppUpdateStrongPromptDownloadSizeLabel(base, isReady = false)
        assertEquals("29.0 KB", patchLabel)
        assertEquals(
            "1.30.0 → 1.31.0 · 29.0 KB",
            raftAppUpdateStrongPromptVersionSummary("1.30.0 → 1.31.0", patchLabel),
        )

        // Not ready with no patch: full size is used.
        val fullLabel = raftAppUpdateStrongPromptDownloadSizeLabel(base.copy(patchDownloadSizeBytes = 0L), isReady = false)
        assertEquals("90.0 MB", fullLabel)
        assertEquals(
            "1.30.0 → 1.31.0 · 90.0 MB",
            raftAppUpdateStrongPromptVersionSummary("1.30.0 → 1.31.0", fullLabel),
        )

        // Not ready with unknown sizes: omitted.
        assertNull(
            raftAppUpdateStrongPromptDownloadSizeLabel(
                base.copy(patchDownloadSizeBytes = 0L, fullDownloadSizeBytes = 0L),
                isReady = false,
            )
        )
        assertEquals("1.30.0 → 1.31.0", raftAppUpdateStrongPromptVersionSummary("1.30.0 → 1.31.0", null))

        // Ready (ReadyToInstall / InstallerOpened / ReadyToRestart): size is omitted.
        assertNull(raftAppUpdateStrongPromptDownloadSizeLabel(base, isReady = true))
        assertNull(
            raftAppUpdateStrongPromptDownloadSizeLabel(
                base.copy(transactionState = SharedSettingsUpdateTransactionState.ReadyToInstall),
                isReady = true,
            )
        )
        assertNull(
            raftAppUpdateStrongPromptDownloadSizeLabel(
                base.copy(transactionState = SharedSettingsUpdateTransactionState.ReadyToRestart),
                isReady = true,
            )
        )
    }

    @Test
    fun appUpdateDetailUsesOnlyActualDownloadSizeWithoutFallbackOrApkJargon() {
        assertEquals(
            Res.string.app_update_detail_current.desc(),
            appUpdateDetailStringDesc(updateAvailable = false, sizeLabel = "90.0 MB", patchSizeLabel = "29.0 KB"),
        )
        // Patch size wins over full size (full size is never shown alongside patch).
        assertEquals(
            Res.string.app_update_detail_available_with_size.format(listOf("29.0 KB")),
            appUpdateDetailStringDesc(updateAvailable = true, sizeLabel = "90.0 MB", patchSizeLabel = "29.0 KB"),
        )
        assertEquals(
            Res.string.app_update_detail_available_with_size.format(listOf("29.0 KB")),
            appUpdateDetailStringDesc(updateAvailable = true, sizeLabel = null, patchSizeLabel = "29.0 KB"),
        )
        // Full size is used when patch is absent or blank.
        assertEquals(
            Res.string.app_update_detail_available_with_size.format(listOf("90.0 MB")),
            appUpdateDetailStringDesc(updateAvailable = true, sizeLabel = "90.0 MB", patchSizeLabel = "  "),
        )
        // Generic copy when neither size is known.
        assertEquals(
            Res.string.app_update_detail_available.desc(),
            appUpdateDetailStringDesc(updateAvailable = true, sizeLabel = null, patchSizeLabel = null),
        )
    }

    @Test
    fun homeSidebarBannerDeduplicatesOnlyWhenTopBarIsDrawn() {
        assertFalse(shouldShowHomeSidebarAppUpdateBanner(underAppShellTopBar = true))
        assertTrue(shouldShowHomeSidebarAppUpdateBanner(underAppShellTopBar = false))
    }

    @Test
    fun homeNotificationAndBannerCopyDistinguishReadyVsAvailableStages() {
        fun noticeFor(state: SharedSettingsUpdateTransactionState, storeChannel: String = "") =
            SharedSettingsUpdateCheckSnapshot(
                supported = true,
                updateAvailable = true,
                currentVersionLabel = "1.29.0",
                currentVersionCode = 12_900_000L,
                latestVersionLabel = "1.30.0",
                latestVersionCode = 13_000_000L,
                storeChannel = storeChannel,
                transactionState = state,
            ).toSharedHomeAppUpdateNotification()

        val readyToInstall = noticeFor(SharedSettingsUpdateTransactionState.ReadyToInstall)
        assertEquals("Update ready to install", readyToInstall?.title)
        assertEquals("Install", readyToInstall?.actionLabel)

        val installerOpened = noticeFor(SharedSettingsUpdateTransactionState.InstallerOpened)
        assertEquals("Update ready to install", installerOpened?.title)
        assertEquals("Install", installerOpened?.actionLabel)

        val readyToRestart = noticeFor(SharedSettingsUpdateTransactionState.ReadyToRestart, storeChannel = "google_play")
        assertEquals("Update ready to install", readyToRestart?.title)
        assertEquals("Restart to update", readyToRestart?.actionLabel)

        for (nonReadyState in listOf(
            SharedSettingsUpdateTransactionState.Idle,
            SharedSettingsUpdateTransactionState.Checking,
            SharedSettingsUpdateTransactionState.Downloading,
            SharedSettingsUpdateTransactionState.Patching,
            SharedSettingsUpdateTransactionState.Failed,
        )) {
            val nonReady = noticeFor(nonReadyState)
            assertEquals("App update available", nonReady?.title, "state=$nonReadyState")
        }

        // L1 HomeAppUpdateBanner trailing action uses Install / Restart to update for ready states, View otherwise.
        assertEquals(
            "Install",
            homeAppUpdateBannerActionLabel(
                RaftHomeNotificationItem(
                    id = APP_UPDATE_HOME_NOTIFICATION_ID,
                    title = "Update ready to install",
                    actionLabel = "Install",
                    primaryActionActive = true,
                ),
                fallbackViewLabel = "View",
            ),
        )
        assertEquals(
            "Restart to update",
            homeAppUpdateBannerActionLabel(
                RaftHomeNotificationItem(
                    id = APP_UPDATE_HOME_NOTIFICATION_ID,
                    title = "Update ready to install",
                    actionLabel = "Restart to update",
                    primaryActionActive = true,
                ),
                fallbackViewLabel = "View",
            ),
        )
        assertEquals(
            "View",
            homeAppUpdateBannerActionLabel(
                RaftHomeNotificationItem(
                    id = APP_UPDATE_HOME_NOTIFICATION_ID,
                    title = "App update available",
                    actionLabel = "Download",
                    primaryActionActive = false,
                ),
                fallbackViewLabel = "View",
            ),
        )
    }
}
