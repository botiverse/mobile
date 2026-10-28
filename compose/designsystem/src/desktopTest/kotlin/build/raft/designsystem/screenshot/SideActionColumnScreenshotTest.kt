package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftTooltipPreview
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftSideAction
import build.raft.designsystem.components.RaftSideActionColumn
import build.raft.designsystem.components.RaftSideActionColumnSide
import build.raft.designsystem.components.RaftSideColumnKeepOut
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * The iPhone Duo side action column beside a stand-in page: a pushed page (Back and two page
 * actions right under it) and a top-level page (actions only, against the bottom), a short column whose actions overflow into the ⋯,
 * a top-level column above the host's native tab bar (drawn here as a grey stand-in) under a
 * status-bar band, the bell's attention pixel (on the bell, and on the ⋯ when the bell is in
 * it), a Back tooltip opening toward the content, and a right column in a right-to-left layout
 * (it does not mirror).
 */
class SideActionColumnScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    @Test
    fun sideActionColumnAllActions() =
        capture("side_action_column_all") {
            Page(520.dp) { RaftSideActionColumn(primary = listOf(back), actions = listOf(search, settings)) }
            Page(520.dp) { RaftSideActionColumn(primary = emptyList(), actions = listOf(bell(), search)) }
        }

    @Test
    fun sideActionColumnOverflow() =
        capture("side_action_column_overflow") {
            // Two actions at most: two stay at the bottom, the ⋯ above them holds the other three.
            Page(520.dp) { RaftSideActionColumn(primary = emptyList(), actions = listOf(bell(), search, members, home, settings)) }
            // Room for two slots only: one action stays, the ⋯ holds the other two.
            Page(124.dp) { RaftSideActionColumn(primary = emptyList(), actions = listOf(search, members, settings)) }
            // Back allows two actions whatever the height, under it; the ⋯ under them.
            Page(520.dp) { RaftSideActionColumn(primary = listOf(back), actions = listOf(search, settings, members)) }
        }

    @Test
    fun sideActionColumnTabBar() =
        capture("side_action_column_tab_bar") {
            Page(520.dp) {
                Box(Modifier.fillMaxHeight()) {
                    RaftSideActionColumn(
                        primary = emptyList(),
                        // Two actions, then the ⋯: its slot would cross the camera band, so it goes above it.
                        actions = listOf(bell(), search, members),
                        topReserved = 24.dp,
                        bottomReserved = 220.dp,
                        keepOut = listOf(RaftSideColumnKeepOut(150.dp, 190.dp)),
                    )
                    // Stand-ins for the host: the camera keep-out and the native tab bar.
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 150.dp).width(20.dp).height(40.dp).background(HostStandIn))
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(220.dp).padding(start = 1.dp).background(HostStandIn))
                }
            }
        }

    @Test
    fun sideActionColumnAttention() =
        capture("side_action_column_attention") {
            Page(240.dp) { RaftSideActionColumn(primary = emptyList(), actions = listOf(bell(attention = true), search)) }
            // Room for two: Search stays (listed first), the ⋯ holds the bell and wears its pixel.
            Page(124.dp) { RaftSideActionColumn(primary = emptyList(), actions = listOf(search, bell(attention = true), settings)) }
        }

    @Test
    fun sideActionColumnTooltip() =
        capture("side_action_column_tooltip", settleFrames = 2) {
            CompositionLocalProvider(LocalRaftTooltipPreview provides true) {
                Page(160.dp, pageWidth = 200.dp) { RaftSideActionColumn(primary = listOf(back), actions = emptyList()) }
            }
        }

    @Test
    fun sideActionColumnRtl() =
        capture("side_action_column_rtl") {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.height(320.dp).width(220.dp)) {
                    RaftText("Page", Modifier.align(Alignment.Center), style = RaftTheme.typography.headline)
                    // Still on the physical right, its hairline on its left.
                    RaftSideActionColumn(
                        primary = listOf(back),
                        actions = listOf(search, settings),
                        modifier = Modifier.align(AbsoluteAlignment.CenterRight),
                        side = RaftSideActionColumnSide.Right,
                    )
                }
            }
        }

    private fun capture(name: String, settleFrames: Int = 0, content: @Composable () -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, settleFrames = settleFrames) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { content() }
            }
        }

    /** A stand-in page [height] tall, its content left of the column. */
    @Composable
    private fun Page(height: Dp, pageWidth: Dp = 140.dp, column: @Composable () -> Unit) {
        Row(Modifier.height(height)) {
            Column(Modifier.width(pageWidth).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                RaftText("Page", style = RaftTheme.typography.headline)
            }
            column()
        }
    }

    private val back = action("back", "Back") { RaftGlyphIcon(RaftGlyph.ArrowLeft, size = it) }
    private val search = action("search", "Search") { RaftGlyphIcon(RaftGlyph.Search, size = it) }
    private val settings = action("settings", "Channel Settings") { AppIconImage(AppIcon.Settings, it, LocalRaftContentColor.current) }
    private val members = action("members", "Members") { AppIconImage(AppIcon.Users, it, LocalRaftContentColor.current) }
    private val home = action("home", "Home") { AppIconImage(AppIcon.House, it, LocalRaftContentColor.current) }

    private fun bell(attention: Boolean = false) =
        RaftSideAction(
            id = "notifications",
            label = "Notifications",
            onClick = {},
            attention = attention,
            attentionDescription = "new",
        ) { AppIconImage(AppIcon.Bell, it, LocalRaftContentColor.current) }

    private fun action(id: String, label: String, icon: @Composable (Dp) -> Unit) = RaftSideAction(id = id, label = label, onClick = {}, icon = icon)

    private companion object {
        val HostStandIn = Color(0x33808080)
    }
}
