package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.SharedSettingsUpdateCheckSnapshot
import build.raft.app.data.SharedUpdateDownloadProgress
import build.raft.app.transfer.RaftTransferMeter
import kotlin.test.Test
import kotlin.time.TestTimeSource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The More list attention dots and the app update download block (its progress bar and
 * Skip This Version link): the places that moved to the design system's status dot,
 * progress bar and text link. Light and dark; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h640dp-xxhdpi")
class AttentionAndUpdateProgressScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun attentionAndUpdateProgressLight() = capture("more.attention-update-progress", RaftAppAppearance.Light)

    @Test
    fun attentionAndUpdateProgressDark() = capture("more.attention-update-progress.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme {
                Column(
                    modifier = Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    RaftVerticalTabBar(
                        items =
                            listOf(
                                RaftTabItemPayload(key = "computers", title = "Computers", iconName = RaftLucideIconName.Monitor, section = "Workspace", attentionDot = true),
                                RaftTabItemPayload(key = "update", title = "App Update", iconName = RaftLucideIconName.Download, attentionDot = true),
                                RaftTabItemPayload(key = "about", title = "About", iconName = RaftLucideIconName.Info),
                            ),
                        showSelection = false,
                        onSelect = {}
                    )
                    val progress = SharedUpdateDownloadProgress("1", 26_214_400L, 104_857_600L, "downloading")
                    AppUpdateSummary(
                        snapshot =
                            SharedSettingsUpdateCheckSnapshot(
                                supported = true,
                                updateAvailable = true,
                                currentVersionLabel = "1.12.0",
                                currentVersionCode = 12,
                                latestVersionLabel = "1.13.0",
                                latestVersionCode = 13,
                                statusLabel = "Downloading",
                                actionLabel = "Downloading"
                            ),
                        loading = false,
                        actionRunning = false,
                        errorMessage = null,
                        onRetry = {},
                        onSkipVersion = {},
                        onAction = {},
                        progress = progress,
                        transfer = RaftTransferMeter(timeSource = TestTimeSource()).observe(progress)
                    )
                }
            }
        }
}
