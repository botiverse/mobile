package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Hands f0be77a8: a server switch's new window (Multiple app windows) showed a plain full-screen
 * box while it read the stored session, a white screen in the light theme. It now shows the shell
 * on the canvas: the head with the tab's name, a list skeleton and the tab bar with that tab
 * selected.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class AppShellNewWindowRestoreFrameTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun restoreShowsTheShellFrameWithASkeletonNotAPlainBox() = runRobolectricComposeUiTest {
        setContent {
            InPage { AppShellNewWindowRestoreFrame(RaftAdaptivePaneState(), selectedTab = "activity", animated = false) }
        }
        onNodeWithTag(AppShellRestoreFrameTestTag).assertIsDisplayed()
        onNodeWithTag(AppShellRestoreFrameSkeletonTestTag).assertIsDisplayed()
        // The head names the tab and the tab bar carries it too (Activity selected), beside the
        // other tabs: the frame, not an empty box.
        assertEquals(2, onAllNodesWithText("Activity", useUnmergedTree = true).fetchSemanticsNodes().size)
        for (tab in listOf("Home", "Tasks", "Search", "More")) {
            assertEquals(1, onAllNodesWithText(tab, useUnmergedTree = true).fetchSemanticsNodes().size, tab)
        }
    }

    @Test
    fun headTitleIsTheTabBarLabelOfTheOpeningTab() {
        val labels =
            mapOf(
                RaftAppDestination.Chat.id to "Chat",
                RaftAppDestination.Activity.id to "Activity",
                RaftAppDestination.Tasks.id to "Tasks",
                RaftAppDestination.Search.id to "Search",
                RaftAppDestination.Server.id to "More",
            )
        assertEquals("Activity", appShellRestoreFrameTitle("activity", labels))
        assertEquals("Tasks", appShellRestoreFrameTitle("tasks", labels))
        assertEquals("Chat", appShellRestoreFrameTitle("home", labels))
    }
}

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

    @Test
    fun activityTabDark() = capture("app-shell.new-window-restore.activity.dark", RaftAppAppearance.Dark)

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
