package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftPullDownPanel
import build.raft.designsystem.components.RaftTaskStatus
import build.raft.designsystem.components.RaftTaskStatusBadge
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke
import kotlin.test.Test

/**
 * [RaftPullDownPanel], light and dark, in a 358dp phone column: a bar with its seam, then rows of
 * content with the panel pulled down over them (`layer-canvas`, `shadow-float` under it, the
 * `line-seam` hairline on its bottom edge, no scrim: the rows below it stay as they are).
 */
class PullDownPanelScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    @Test
    fun pullDownPanel() =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("pull_down_panel_${appearance.name.lowercase()}", appearance) {
                val colors = RaftTheme.colors
                Column(Modifier.width(358.dp).background(colors.layerCanvas)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        RaftText("#raft-mobile:#7", style = RaftTheme.typography.monoMeta, color = colors.foregroundMuted)
                        RaftText("Task #7", style = RaftTheme.typography.subhead, color = colors.foreground)
                    }
                    Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam.compositeOver(colors.layerCanvas)))
                    Box(Modifier.fillMaxWidth().height(320.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            repeat(9) { index ->
                                RaftText("Message ${index + 1}: the conversation goes on under the panel", style = RaftTheme.typography.callout)
                            }
                        }
                        RaftPullDownPanel(open = true, onDismiss = {}, paneTitle = "Task #7", reducedMotion = true) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                RaftText("Ship the hero copy for the phone landing page", style = RaftTheme.typography.headline)
                                RaftText(
                                    "Draft the hero headline and the two lines under it, for phone and desktop.",
                                    style = RaftTheme.typography.subhead,
                                    color = colors.foregroundMuted,
                                )
                                RaftDescriptionList(direction = RaftDescriptionListDirection.Inline) {
                                    Item("Status") { RaftTaskStatusBadge(RaftTaskStatus.InProgress, "In Progress") }
                                    Item("Created by", "Artin")
                                }
                            }
                        }
                    }
                }
            }
        }
}
