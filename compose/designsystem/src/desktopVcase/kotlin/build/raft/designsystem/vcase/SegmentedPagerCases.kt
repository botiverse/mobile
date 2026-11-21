package build.raft.designsystem.vcase

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.RaftSegmentedPager
import build.raft.designsystem.components.rememberRaftSegmentedPagerState
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke
import dev.vcase.VCase

/**
 * [RaftSegmentedPager] (#proj-mobile:#257, the agent's Activity: Overview / Raw Log / Sub-agents) in a
 * 358dp phone column: at rest on each segment, and 40% of the way through a drag from Overview to
 * Raw Log, where the sticker sits between the two segments (its edges interpolated) and both pages
 * show, the first moved 40% out. The labels change at halfway, so Overview is still the selected one.
 */
@VCase("ds.segmented-pager.gallery")
@Composable
fun SegmentedPagerGallery() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Caption("Selected: Overview")
        Activity(index = 0)
        Caption("Selected: Raw Log")
        Activity(index = 1)
        Caption("Selected: Subagents")
        Activity(index = 2)
        Caption("Mid-drag, 40% from Overview to Raw Log")
        Activity(index = 0, offset = 0.4f)
    }
}

private val Views = listOf("Overview", "Raw Log", "Subagents")

@Composable
private fun Activity(index: Int, offset: Float = 0f) {
    val colors = RaftTheme.colors
    val state = rememberRaftSegmentedPagerState(initialIndex = index, initialOffsetFraction = offset) { Views.size }
    RaftSegmentedPager(
        state = state,
        selectedIndex = index,
        onSelectedIndexChange = {},
        modifier = Modifier.size(358.dp, 150.dp).background(colors.layerCanvas),
        header = {
            Column {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    RaftSegmentedControl(selectionPosition = { state.position }) {
                        Views.forEachIndexed { i, label ->
                            RaftSegment(
                                text = label,
                                selected = i == index,
                                onClick = {},
                                count = if (i == 2) "2" else null,
                            )
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam.compositeOver(colors.layerCanvas)))
            }
        },
    ) { page ->
        Column(
            Modifier.fillMaxSize().background(if (page % 2 == 0) colors.layerCanvas else colors.fillMuted.compositeOver(colors.layerCanvas)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RaftText(Views[page], style = RaftTheme.typography.headline, color = colors.foreground)
            RaftText("The ${Views[page].lowercase()} of the agent's run.", style = RaftTheme.typography.body, color = colors.foregroundMuted)
        }
    }
}
