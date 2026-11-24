package build.raft.app

import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.SharedSettingsUpdateTransactionState
import build.raft.app.data.toSharedHomeAppUpdateNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun changelogPreviewTrimsHeadingsRulesAndLimitsToThreeNonEmptyLines() {
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
                "- Faster startup",
                "- Wide top bar update button",
            ),
            raftAppUpdateChangelogPreviewLines(raw),
        )
        assertEquals(emptyList(), raftAppUpdateChangelogPreviewLines("   \n---\n   "))
    }

    @Test
    fun homeSidebarBannerDeduplicatesOnlyWhenTopBarIsDrawn() {
        assertFalse(shouldShowHomeSidebarAppUpdateBanner(underAppShellTopBar = true))
        assertTrue(shouldShowHomeSidebarAppUpdateBanner(underAppShellTopBar = false))
    }

    @Test
    fun homeNotificationActionLabelUsesRestartToUpdateForReadyToRestartEvenOnPlayChannel() {
        val notice =
            SharedSettingsUpdateCheckSnapshot(
                supported = true,
                updateAvailable = true,
                currentVersionLabel = "1.29.0",
                currentVersionCode = 12_900_000L,
                latestVersionLabel = "1.30.0",
                latestVersionCode = 13_000_000L,
                storeChannel = "google_play",
                transactionState = SharedSettingsUpdateTransactionState.ReadyToRestart,
            ).toSharedHomeAppUpdateNotification()

        assertEquals("Restart to update", notice?.actionLabel)
    }
}
