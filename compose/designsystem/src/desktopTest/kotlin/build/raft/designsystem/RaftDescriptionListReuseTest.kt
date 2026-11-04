package build.raft.designsystem

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
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
import kotlin.test.assertFalse

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

    /**
     * The cause of crash 69fdce17 without the luck it needs: reading the value's first baseline
     * while measuring lays out (places) the chips inside it before the list is placed, which
     * Compose 1.12 gets wrong for a reused item. A two-column list must lay out its values only
     * when it is placed.
     */
    @Test
    fun horizontalListPlacesItsChipsOnlyAfterMeasuring() =
        runComposeUiTest {
            var measuring = false
            var placedWhileMeasuring = false
            val probe =
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        if (measuring) placedWhileMeasuring = true
                        placeable.place(0, 0)
                    }
                }
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    Layout(
                        content = {
                            RaftDescriptionList(Modifier.width(480.dp), direction = RaftDescriptionListDirection.Horizontal) {
                                Item("Runtimes") {
                                    RaftBadge("Claude Code", probe, appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Information)
                                }
                            }
                        },
                    ) { measurables, constraints ->
                        measuring = true
                        val placeable = measurables.single().measure(constraints)
                        measuring = false
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                }
            }
            waitForIdle()

            onNodeWithText("Claude Code").assertExists()
            assertFalse(placedWhileMeasuring, "the chip was laid out while the list was measuring")
        }

    /** The inline list (a task dialog's facts row) in a `LazyColumn` survives item reuse too. */
    @Test
    fun inlineListWithChipsInALazyColumnSurvivesItemReuse() =
        runComposeUiTest {
            val itemDp = 80.dp
            val itemPx = with(density) { itemDp.roundToPx() }
            val lazyListState = LazyListState()
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    LazyColumn(state = lazyListState, modifier = Modifier.width(480.dp).height(itemDp * 2)) {
                        items(10) { index ->
                            RaftDescriptionList(Modifier.height(itemDp), direction = RaftDescriptionListDirection.Inline) {
                                Item("Status", "In progress $index")
                                Item("Assignee") {
                                    Spacer(Modifier.size(width = 1.dp, height = (20 + 2 * index).dp))
                                    RaftBadge("Cindy $index", appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Information)
                                }
                            }
                        }
                    }
                }
            }

            runOnIdle { lazyListState.dispatchRawDelta(itemPx.toFloat()) }
            runOnIdle { lazyListState.dispatchRawDelta(itemPx.toFloat()) }
            runOnIdle { lazyListState.dispatchRawDelta(itemPx.toFloat()) }
            waitForIdle()

            onNodeWithText("Cindy 4").assertExists()
        }

    /** An inline fact lays out its value only when it is placed, as the two-column list does. */
    @Test
    fun inlineListPlacesItsChipsOnlyAfterMeasuring() =
        runComposeUiTest {
            var measuring = false
            var placedWhileMeasuring = false
            val probe =
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        if (measuring) placedWhileMeasuring = true
                        placeable.place(0, 0)
                    }
                }
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    Layout(
                        content = {
                            RaftDescriptionList(Modifier.width(480.dp), direction = RaftDescriptionListDirection.Inline) {
                                Item("Assignee") {
                                    RaftBadge("Cindy", probe, appearance = RaftBadgeAppearance.Soft, variant = RaftBadgeVariant.Information)
                                }
                            }
                        },
                    ) { measurables, constraints ->
                        measuring = true
                        val placeable = measurables.single().measure(constraints)
                        measuring = false
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                }
            }
            waitForIdle()

            onNodeWithText("Cindy").assertExists()
            assertFalse(placedWhileMeasuring, "the chip was laid out while the list was measuring")
        }
}
