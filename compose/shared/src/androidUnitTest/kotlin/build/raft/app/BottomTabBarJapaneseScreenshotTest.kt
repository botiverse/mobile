package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone's bottom tab bar in Japanese, light: 「アクティビティ」 is wider than its cell at
 * 11.5sp, so it steps down to fit instead of reading 「アクティビ…」 (Play screenshot review
 * 10-28). Bars at 360dp and 412dp, Home selected and then Activity selected (the bold label is
 * the widest). Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ja-w412dp-h300dp-xxhdpi")
class BottomTabBarJapaneseScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun bottomTabBarJapanese() =
        RaftRobolectricScreenshot.capture(
            "shell.bottom-tab-bar.ja",
            RaftAppAppearance.Light,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
        ) {
            RaftDesignSystemTheme {
                Column(
                    Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    JapaneseBar(width = 360.dp, selected = "home")
                    JapaneseBar(width = 360.dp, selected = "activity")
                    JapaneseBar(width = 412.dp, selected = "activity")
                }
            }
        }
}

@Composable
private fun JapaneseBar(width: Dp, selected: String) {
    RaftBottomTabBar(
        items =
            appShellPhoneBottomTabs(
                selectedTab = selected,
                homeLabel = stringResource(Res.string.tab_home),
                activityLabel = stringResource(Res.string.tab_activity),
                searchLabel = stringResource(Res.string.home_nav_search),
                tasksLabel = stringResource(Res.string.tab_tasks),
                moreLabel = stringResource(Res.string.tab_more),
            ),
        onSelect = {},
        modifier = Modifier.width(width),
    )
}
