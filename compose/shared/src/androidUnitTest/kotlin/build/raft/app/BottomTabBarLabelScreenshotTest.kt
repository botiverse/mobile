package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.components.RaftMobileNavServerPopoverItem
import build.raft.designsystem.components.rememberRaftMobileNavServerPopoverState
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone's bottom tab bar as the shell draws it on a gesture-navigation phone (a 24dp bottom
 * inset under the bar), with the current-server tab last, at font scale 1.0 and 1.3. artin
 * 10-29 (Pixel, zh-CN): the labels' lower strokes (主页 动态 任务 搜索) were cut off by the
 * label's 14dp line box. Every label must show whole, CJK and Latin, at either scale.
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomTabBarLabelScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN-w412dp-h300dp-420dpi")
    fun bottomTabBarLabelsChinese() = capture("shell.bottom-tab-bar.labels.zh")

    @Test
    @Config(sdk = [34], qualifiers = "en-w412dp-h300dp-420dpi")
    fun bottomTabBarLabelsEnglish() = capture("shell.bottom-tab-bar.labels.en")

    private fun capture(name: String) =
        RaftRobolectricScreenshot.capture(
            name,
            RaftAppAppearance.Light,
            reportDir = "build/reports/home-screenshots",
            activities = activities,
        ) {
            RaftDesignSystemTheme {
                Column(
                    Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    GestureNavBar(fontScale = 1f)
                    GestureNavBar(fontScale = 1.3f)
                }
            }
        }
}

/** Pixel-class gesture navigation: the handle's inset under the bar. */
private val GestureNavBottomInset = 24.dp

@Composable
private fun GestureNavBar(fontScale: Float) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
        RaftSharedBottomTabBarBand(
            items =
                appShellPhoneBottomTabs(
                    selectedTab = "home",
                    homeLabel = stringResource(Res.string.tab_home),
                    activityLabel = stringResource(Res.string.tab_activity),
                    searchLabel = stringResource(Res.string.home_nav_search),
                    tasksLabel = stringResource(Res.string.tab_tasks),
                    // The last slot (AppShellServerTabKey) is the current server, drawn by serverTab.
                    moreLabel = "Raft",
                ),
            onSelect = {},
            modifier = Modifier.fillMaxWidth(),
            bottomSafeArea = GestureNavBottomInset,
            serverTab = { modifier ->
                RaftDesignSystemTheme {
                    RaftMobileNavServerPopoverItem(
                        label = "Raft",
                        state = rememberRaftMobileNavServerPopoverState(),
                        avatar = { size ->
                            Box(Modifier.size(size).background(RaftColor.AccentPink, RoundedCornerShape(5.dp)))
                        },
                        modifier = modifier,
                    )
                }
            },
        )
    }
}
