package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
import build.raft.app.data.InboxFilter
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Activity column's head and toolbar on the wide layout (a 380dp list column in a tablet
 * window), as the web draws them from 768 (TabRootBar / raft-ui PanelSectionHeader and the inbox
 * toolbar): the head on the canvas with "Activity" and the counts on one line, then the toolbar,
 * 49 with a finger and 44 under a mouse or trackpad (raft-design spacing.md, the list toolbar line).
 *  - `screens.activity.wide.head.touch` / `.dark`: a finger (56 head, 49 toolbar);
 *  - `screens.activity.wide.head.fine`: a mouse or trackpad (44 head, 44 toolbar).
 * The phone head (the frame-coloured tab root, two lines) and its 54dp toolbar keep their own
 * goldens ([ActivityHeaderRefreshIndicatorScreenshotTest], [ActivityFilterBarScreenshotTest]).
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w944dp-h140dp-xhdpi")
class ActivityWideHeadScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun touch() = capture("screens.activity.wide.head.touch", RaftAppAppearance.Light, RaftPointerInput.Touch)

    @Test
    fun touchDark() = capture("screens.activity.wide.head.touch.dark", RaftAppAppearance.Dark, RaftPointerInput.Touch)

    @Test
    fun fine() = capture("screens.activity.wide.head.fine", RaftAppAppearance.Light, RaftPointerInput.Fine)

    private fun capture(name: String, appearance: RaftAppAppearance, pointerInput: RaftPointerInput) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(
                    appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light,
                    pointerInput = pointerInput,
                ) {
                    RaftDesignSystemTheme { WideActivityHead() }
                }
            }
        }
}

@Composable
private fun WideActivityHead() {
    // The list column beside the open conversation (the app's default list pane width).
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        Column(Modifier.width(380.dp)) {
            SharedActivityHeader(
                totalCount = 506,
                totalUnreadCount = 657,
                onClose = null,
                topSafeAreaPadding = 0.dp,
                refreshing = false,
                mainTab = true,
            )
            SharedActivityFilterBar(
                selectedFilter = InboxFilter.All,
                totalUnreadCount = 657,
                onFilterSelected = {},
                onMarkAllRead = {},
            )
        }
    }
}
