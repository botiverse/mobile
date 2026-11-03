package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftFrameInsets
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import build.raft.designsystem.components.LocalRaftWindowTitleBar
import build.raft.designsystem.components.RaftFrameInsets
import build.raft.designsystem.components.RaftWindowTitleBar
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The desktop / wide image lightbox (Telegram Desktop style) in a 1280x800 window, light and dark
 * app appearance: the dark backdrop, the fitted image, sender + time, "2 of 3", Download / Open
 * with / Close and both step arrows. Fixture images, no network. Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-mdpi")
class RaftImageLightboxScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun lightboxLight() = capture("image.lightbox", RaftAppAppearance.Light)

    @Test
    fun lightboxDark() = capture("image.lightbox.dark", RaftAppAppearance.Dark)

    /**
     * artin 11-03: the macOS window's title bar laid over the app (#4193): the bar is its row and
     * the sender starts after the traffic lights' corner instead of under them.
     */
    @Test
    fun lightboxWindowTitleBar() =
        capture(
            "image.lightbox.window-title-bar",
            RaftAppAppearance.Dark,
            frameInsets = RaftFrameInsets(top = 48.dp),
            titleBar = RaftWindowTitleBar(height = 48.dp, startInset = 70.dp)
        )

    private fun capture(
        name: String,
        appearance: RaftAppAppearance,
        frameInsets: RaftFrameInsets = RaftFrameInsets.Zero,
        titleBar: RaftWindowTitleBar = RaftWindowTitleBar.None
    ) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            CompositionLocalProvider(
                LocalRaftSpinnerStillStep provides 0,
                LocalRaftFrameInsets provides frameInsets,
                LocalRaftWindowTitleBar provides titleBar
            ) {
                RaftImageLightboxContent(
                    request = RaftImageLightboxRequest(lightboxFixtureItems, index = 1),
                    resolveFullUrl = { null },
                    onDismiss = {},
                    onDownload = {},
                    onOpenWith = {},
                    topSafeArea = frameInsets.top,
                    pageImage = { item, _, onContentSize -> LightboxFixtureImage(item, onContentSize) }
                )
            }
        }
}
