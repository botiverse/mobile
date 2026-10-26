package build.raft.app

import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone notification center (the Home bell's popover) with its fixture notifications, light
 * and dark: raft-ui 0.24 (#737) NotificationCenterPopup `viewport="mobile"`, the screen's width
 * less 8dp on each side (at most 480dp), 8dp from both screen edges.
 *
 * A 390dp-wide phone, the top 640dp. Goldens: `src/androidUnitTest/screenshots/<name>.png`;
 * record with `RAFT_SCREENSHOT_RECORD=true`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h640dp-xxhdpi")
class HomeNotificationPopoverScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun light() = capture("home.notification-popover", RaftAppAppearance.Light)

    @Test fun dark() = capture("home.notification-popover.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = ReportDir, activities = activities) {
            RaftDesignSystemTheme { HomeNotificationCenterVisualScreen() }
        }

    private companion object {
        const val ReportDir = "build/reports/home-screenshots"
    }
}
