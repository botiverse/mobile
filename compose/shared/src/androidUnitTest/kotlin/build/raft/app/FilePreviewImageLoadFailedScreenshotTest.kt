package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The image preview's failure state: the thumbnail stays on screen (a flat stand-in here, as the
 * image loader is not part of a Robolectric run) with the "Couldn't load full image" banner and
 * its Retry action at the bottom. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h760dp-xxhdpi")
class FilePreviewImageLoadFailedScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun imageLoadFailedLight() = capture("file.preview.image.load.failed", RaftAppAppearance.Light)

    @Test
    fun imageLoadFailedDark() = capture("file.preview.image.load.failed.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                Box(Modifier.fillMaxSize().background(Color(0xFF8A8F98))) {
                    RaftFilePreviewImageLoadFailedBanner(onRetry = {}, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
}
