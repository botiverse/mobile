package build.raft.designsystem

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
