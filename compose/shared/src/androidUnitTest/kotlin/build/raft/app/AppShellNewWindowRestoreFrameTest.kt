package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.shell.AppShellNewWindowRestoreFrame
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The restore frame in light and dark (canvas, head on the frame layer, skeleton rows, tab bar).
 * Record on CI (`record_screenshots=true`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class AppShellNewWindowRestoreFrameScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun activityTabLight() = capture("app-shell.new-window-restore.activity", RaftAppAppearance.Light)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            InPage(zeroSafeArea = true) {
                AppShellNewWindowRestoreFrame(RaftAdaptivePaneState(), selectedTab = "activity", animated = false)
            }
        }
}

@Composable
private fun InPage(zeroSafeArea: Boolean = false, content: @Composable () -> Unit) {
    val pager =
        remember {
            object : Pager() {}.apply {
                pageData = PageData(JSONObject().apply { if (zeroSafeArea) put("slockVisualZeroSafeArea", true) })
            }
        }
    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme(content) }
}
