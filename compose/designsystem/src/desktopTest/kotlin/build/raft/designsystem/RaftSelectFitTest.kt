package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import build.raft.designsystem.components.RaftSelectFitMaxWidth
import build.raft.designsystem.components.RaftSelectTrigger
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * raft-ui 0.95.1 `SelectTrigger fit`: as wide as the longest option and the placeholder, 11rem to
 * 20rem, the same whichever value is chosen; without `fit` the trigger fills its width.
 */
@OptIn(ExperimentalTestApi::class)
class RaftSelectFitTest {
    private val levels = listOf("All messages", "Mentions only", "Nothing, not even mentions or replies to you")

    private fun triggerWidth(value: String?, labels: List<String>, fit: Boolean = true, placeholder: String = ""): Dp {
        var width = 0.dp
        runComposeUiTest {
            setContent {
                RaftTheme {
                    Box(Modifier.width(600.dp)) {
                        RaftSelectTrigger(
                            value = value,
                            onClick = {},
                            modifier = Modifier.testTag("trigger"),
                            placeholder = placeholder,
                            fit = fit,
                            fitLabels = labels,
                        )
                    }
                }
            }
            width = onNodeWithTag("trigger").getBoundsInRoot().width
        }
        return width
    }

    @Test
    fun aLongOptionWidensItUpToTheMaximumWhicheverValueIsChosen() {
        val short = triggerWidth("All messages", levels.take(2))
        val long = triggerWidth("All messages", levels)
        assertTrue(long > short, "the longest option widens it: $short -> $long")
        assertEquals(RaftSelectFitMaxWidth, long, "past 20rem it stops and the value ends in an ellipsis")
        assertEquals(long, triggerWidth(levels.last(), levels), "the chosen value doesn't move its end")
        assertEquals(long, triggerWidth(null, levels, placeholder = "Choose"), "nor does the placeholder")
    }

    @Test
    fun theSizerLabelsAreNotReadOrShown() =
        runComposeUiTest {
            setContent {
                RaftTheme {
                    RaftSelectTrigger(value = "All", onClick = {}, fit = true, fitLabels = listOf("All", "Mentions only"))
                }
            }
            assertEquals(0, onAllNodesWithText("Mentions only").fetchSemanticsNodes().size)
        }
}
