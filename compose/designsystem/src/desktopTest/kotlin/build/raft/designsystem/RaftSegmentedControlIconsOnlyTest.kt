package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftSegment
import build.raft.designsystem.components.RaftSegmentedControl
import build.raft.designsystem.components.raftSegmentIconsOnly
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * raft-ui SegmentedControl `iconsOnlyBelow="auto"` (mobile `iconsOnlyWhenLabelsDontFit`): the
 * segments show only their icons exactly while the labelled track doesn't fit its width, keep their
 * labels as accessible names, and come back to their labels once they fit.
 */
@OptIn(ExperimentalTestApi::class)
class RaftSegmentedControlIconsOnlyTest {
    private val labels = listOf("All", "Unread", "Mentions", "Pinned")

    @Composable
    private fun Filters(width: Dp, selected: Int, onSelect: (Int) -> Unit) =
        RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
            Box(Modifier.size(width, 60.dp)) {
                RaftSegmentedControl(iconsOnlyWhenLabelsDontFit = true) {
                    labels.forEachIndexed { index, label ->
                        RaftSegment(
                            label,
                            selected = index == selected,
                            onClick = { onSelect(index) },
                            iconsOnlyIcon = { Box(Modifier.size(16.dp)) },
                        )
                    }
                }
            }
        }

    @Test
    fun labelsShowWhileTheyFitAndOnlyIconsWhenTheyDont() =
        runComposeUiTest {
            var width by mutableStateOf(600.dp)
            var selected by mutableStateOf(2)
            setContent { Filters(width, selected) { selected = it } }

            fun assertLabelled(expected: Boolean, step: String) {
                waitForIdle()
                labels.forEach { label ->
                    // Never two nodes: the copy measured for the labelled width has no semantics.
                    assertEquals(if (expected) 1 else 0, onAllNodesWithText(label).fetchSemanticsNodes().size, "$step: $label text")
                    assertEquals(if (expected) 0 else 1, onAllNodesWithContentDescription(label).fetchSemanticsNodes().size, "$step: $label name")
                }
            }

            assertLabelled(true, "600dp")
            width = 160.dp
            assertLabelled(false, "160dp")
            // Icons only, each segment is still a radio named by its label, and still picks.
            onNodeWithContentDescription("Mentions").assertIsSelected()
            // A semantics click: an injected pointer would also hover the segment open its tooltip.
            onNodeWithContentDescription("Pinned").performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            assertEquals(3, selected)
            onNodeWithContentDescription("Pinned").assertIsSelected()
            width = 600.dp
            assertLabelled(true, "600dp again")
        }

    @Test
    fun theDecisionComparesTheLabelledWidthWithTheRoomGiven() {
        assertFalse(raftSegmentIconsOnly(labelledWidth = 300, availableWidth = 300, bounded = true))
        assertTrue(raftSegmentIconsOnly(labelledWidth = 301, availableWidth = 300, bounded = true))
        assertFalse(raftSegmentIconsOnly(labelledWidth = 301, availableWidth = Int.MAX_VALUE, bounded = false))
    }

    @Test
    fun withoutTheOptionTheSegmentsKeepTheirLabels() =
        runComposeUiTest {
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    Box(Modifier.width(120.dp)) {
                        RaftSegmentedControl {
                            labels.forEach { RaftSegment(it, selected = false, onClick = {}, iconsOnlyIcon = { Box(Modifier.size(16.dp)) }) }
                        }
                    }
                }
            }
            waitForIdle()
            labels.forEach { assertEquals(1, onAllNodesWithText(it).fetchSemanticsNodes().size, it) }
        }
}
