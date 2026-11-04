package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftUiMenuSurface
import build.raft.app.data.ArtifactKind
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The in-app Artifact viewer's native frame: the header painted from the opener's card (title,
 * version, Open in browser) over the state a 403/404 puts in place of the web page; and, where
 * Desktop Mode is offered, the header's ⋯ menu (Desktop Mode checked, Open in browser), its surface
 * drawn in place under the ⋯ (a popup has no window here). Verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class RaftArtifactViewerScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun unavailableLight() = capture("artifact.viewer.unavailable", RaftAppAppearance.Light)

    @Test
    fun unavailableDark() = capture("artifact.viewer.unavailable.dark", RaftAppAppearance.Dark)

    @Test
    fun desktopModeMenuLight() = captureMenu("artifact.viewer.desktop-mode.menu", RaftAppAppearance.Light)

    @Test
    fun desktopModeMenuDark() = captureMenu("artifact.viewer.desktop-mode.menu.dark", RaftAppAppearance.Dark)

    private fun captureMenu(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                Box(Modifier.fillMaxSize()) {
                    RaftArtifactViewerFrame(
                        title = "q4-launch-plan.html",
                        subtitle = "v1",
                        kind = ArtifactKind.Html,
                        onBack = {},
                        onOpenInBrowser = {},
                        desktopMode = true,
                        topSafeAreaOverride = 0.dp,
                    ) {
                        Box(Modifier.fillMaxSize())
                    }
                    RaftUiMenuSurface(
                        items = raftArtifactViewerMenuItems(desktopMode = true, openInBrowser = true),
                        width = 180.dp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = RaftAppSize.PageHeaderHeight, end = 8.dp),
                    )
                }
            }
        }

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                RaftArtifactViewerFrame(
                    title = "q4-launch-plan.html",
                    subtitle = "v1",
                    kind = ArtifactKind.Html,
                    onBack = {},
                    onOpenInBrowser = {},
                    topSafeAreaOverride = 0.dp,
                ) {
                    RaftArtifactViewerUnavailable(onClose = {})
                }
            }
        }
}
