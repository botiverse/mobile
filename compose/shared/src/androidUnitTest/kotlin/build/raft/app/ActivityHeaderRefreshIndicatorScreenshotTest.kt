package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Activity header during a silent refresh (artin 10-27): the shared refresh indicator at
 * the top-right of the head, light and dark, on the main-tab head (phone Activity tab) and the
 * panel head (secondary route / wide list pane). A user pull never shows it (artin task #22).
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h96dp-xxhdpi")
class ActivityHeaderRefreshIndicatorScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun mainTabSilentRefreshLight() = capture("screens.activity.header.refreshing", RaftAppAppearance.Light, mainTab = true)

    @Test
    fun mainTabSilentRefreshDark() = capture("screens.activity.header.refreshing.dark", RaftAppAppearance.Dark, mainTab = true)

    @Test
    fun panelSilentRefreshLight() = capture("screens.activity.panel-header.refreshing", RaftAppAppearance.Light, mainTab = false)

    @Test
    fun panelSilentRefreshDark() = capture("screens.activity.panel-header.refreshing.dark", RaftAppAppearance.Dark, mainTab = false)

    private fun capture(name: String, appearance: RaftAppAppearance, mainTab: Boolean) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
                        SharedActivityHeader(
                            totalCount = 24,
                            totalUnreadCount = 3,
                            onClose = if (mainTab) null else ({}),
                            topSafeAreaPadding = 0.dp,
                            refreshing = true,
                            mainTab = mainTab,
                        )
                    }
                }
            }
        }
}
