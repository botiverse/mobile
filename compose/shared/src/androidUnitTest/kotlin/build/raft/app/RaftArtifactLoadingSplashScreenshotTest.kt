package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.thread.icon
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #301: the App's Artifact loading splash (the web's ArtifactLoadingSplash, ported), for design
 * review: each kind's icon (web page, document, image), light and dark, mid-animation (assembling,
 * 30% into the cycle) and the final frame (loaded: every cell filled in, before the fade), and the
 * reduced-motion still icon. Frames are chosen ([raftArtifactSplashFrame]), not timed. Verify /
 * record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h420dp-xxhdpi")
class RaftArtifactLoadingSplashScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    private val mid = RAFT_ARTIFACT_SPLASH_APPEAR_AFTER_MS + RAFT_ARTIFACT_SPLASH_CYCLE_MS * 3 / 10
    private val final = mid + RAFT_ARTIFACT_SPLASH_FILL_MS + RAFT_ARTIFACT_SPLASH_HOLD_MS / 2

    @Test fun webFinalLight() = shot("artifact.splash.web.final", RaftAppAppearance.Light, RaftArtifactSplashIcon.Web, final, loadedAfterMs = mid)

    @Test fun webFinalDark() = shot("artifact.splash.web.final.dark", RaftAppAppearance.Dark, RaftArtifactSplashIcon.Web, final, loadedAfterMs = mid)

    @Test fun reducedMotionLight() = shot("artifact.splash.reduced-motion", RaftAppAppearance.Light, RaftArtifactSplashIcon.Web, 5_000, reducedMotion = true)

    private fun shot(
        name: String,
        appearance: RaftAppAppearance,
        icon: RaftArtifactSplashIcon,
        elapsedMs: Long,
        loadedAfterMs: Long? = null,
        reducedMotion: Boolean = false,
    ) = capture(name, appearance) {
        RaftArtifactLoadingSplashFrameContent(
            icon = icon,
            frame = raftArtifactSplashFrame(icon, elapsedMs, loadedAfterMs, reducedMotion),
            label = "Getting the page",
        )
    }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme { content() }
        }
}
