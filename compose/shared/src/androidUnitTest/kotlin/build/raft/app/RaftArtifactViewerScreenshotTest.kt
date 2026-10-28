package build.raft.app

import androidx.compose.ui.unit.dp
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
 * version, the "More actions" menu with Open in browser) over the state a 403/404 puts in place of
 * the web page. Verify / record as [RaftRobolectricScreenshot].
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
