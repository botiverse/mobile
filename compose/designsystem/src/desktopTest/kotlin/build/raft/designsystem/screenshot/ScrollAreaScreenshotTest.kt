package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftScrollbarSize
import build.raft.designsystem.components.RaftScrollbarTrigger
import build.raft.designsystem.components.raftScrollbar
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * raft-ui 0.60 ScrollArea's overlay scrollbar, drawn at rest (`trigger = None`, as it shows while
 * scrolling), light and dark: a scroll at its top, one scrolled down, a small one and a lazy list
 * scrolled into its middle. The thumb is `foreground` at 42%, 3dp from the edge, with no track.
 */
class ScrollAreaScreenshotTest {
    @Test
    fun scrollAreaScrollbar() =
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            RaftScreenshot.capture("scroll_area_scrollbar_${appearance.name.lowercase()}", appearance) {
                val colors = RaftTheme.colors
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(0 to RaftScrollbarSize.Medium, 400 to RaftScrollbarSize.Medium, 200 to RaftScrollbarSize.Small).forEach { (start, size) ->
                        val scroll = rememberScrollState(start)
                        Column(
                            Modifier
                                .width(110.dp)
                                .height(240.dp)
                                .background(colors.layerCard)
                                .raftScrollbar(scroll, trigger = RaftScrollbarTrigger.None, size = size)
                                .verticalScroll(scroll)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            repeat(24) { RaftText("Row ${it + 1}", style = RaftTheme.typography.callout) }
                        }
                    }
                    val list = rememberLazyListState(initialFirstVisibleItemIndex = 40)
                    LazyColumn(
                        Modifier
                            .width(110.dp)
                            .height(240.dp)
                            .background(colors.layerCard)
                            .raftScrollbar(list, trigger = RaftScrollbarTrigger.None),
                        state = list,
                    ) {
                        items(100) { RaftText("Item ${it + 1}", Modifier.padding(8.dp), style = RaftTheme.typography.callout) }
                    }
                }
            }
        }
}
