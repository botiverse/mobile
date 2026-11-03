package build.raft.designsystem

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftBadge
import build.raft.designsystem.components.RaftBadgeAppearance
import build.raft.designsystem.components.RaftBadgeVariant
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * Crash 69fdce17: a two-column [RaftDescriptionList] whose value is a row of chips, in a
 * `LazyColumn` (a Computer's Info section on a tablet). Reading the value's first baseline while
 * measuring made Compose 1.12 place the chips of a reused, not yet placed item, and RectManager
 * threw "LayoutNode not found in RectList". The shape follows androidx fd550bed79
 * (`RectListIntegrationTest.testLazyColumn_alignmentLinesQueriedDuringMeasure_withReuse`), with
 * the description list in place of its baseline-querying layout.
 */
@OptIn(ExperimentalTestApi::class)
class RaftDescriptionListReuseTest {
    @Test
    fun horizontalListWithChipsInALazyColumnSurvivesItemReuse() =
        runComposeUiTest {
            val itemDp = 80.dp
            val itemPx = with(density) { itemDp.roundToPx() }
            val lazyListState = LazyListState()
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    // 480dp: at least 384dp, so the label and the value sit side by side.
                    LazyColumn(state = lazyListState, modifier = Modifier.width(480.dp).height(itemDp * 2)) {
                        items(10) { index ->
                            RaftDescriptionList(Modifier.height(itemDp), direction = RaftDescriptionListDirection.Horizontal) {
                                Item("OS", "macOS $index")
                                Item("Runtimes") {
                                    // Each item's first line is a little taller, so its chips' baseline
                                    // differs from the item whose slot it reuses (androidx gives each
                                    // item the baseline `index`).
                                    Spacer(Modifier.size(width = 1.dp, height = (20 + 2 * index).dp))
                                    RaftBadge("Claude Code", appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Information)
                                    RaftBadge("Codex $index", appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Information)
                                }
                            }
                        }
                    }
                }
            }

            // Item 0 scrolls off-screen, but is still measured to advance the first visible index.
            runOnIdle { lazyListState.dispatchRawDelta(itemPx.toFloat()) }
            // Item 0 is no longer composed and its slot is moved to the reuse pool.
            runOnIdle { lazyListState.dispatchRawDelta(itemPx.toFloat()) }
            // Item 4 reuses the slot of item 0 and lays out its facts.
            runOnIdle { lazyListState.dispatchRawDelta(itemPx.toFloat()) }
            waitForIdle()

            onNodeWithText("Codex 4").assertExists()
        }
}
