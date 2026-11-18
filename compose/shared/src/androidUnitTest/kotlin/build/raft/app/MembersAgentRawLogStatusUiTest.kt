package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A raw-log status row (Kirby, raft-design) puts its explanation on its own line under the
 * status, as every other row puts its detail, instead of inline after the status word.
 */
/** The node's box in the root, not clipped to the window (the last rows sit below it). */
private fun SemanticsNode.unclippedBounds(): Rect = Rect(positionInRoot, size.toSize())

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MembersAgentRawLogStatusUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun aStatusExplanationIsItsOwnLineUnderTheStatus() =
        runRobolectricComposeUiTest {
            val log = rawLogStatusFixtureLog()
            assertEquals(listOf("Thinking", "Working", "Output", "Error", "Idle", "Stopped", "Crashed"), log.map { it.title })
            setContent {
                RaftDesignSystemTheme {
                    Column {
                        log.forEach { MembersAgentActivityRow(it, SubagentsFixtureTimeContext, onOpenUrl = {}) }
                    }
                }
            }
            // A Thinking row's title-to-detail step, which a status's explanation must match.
            val thinking = onNodeWithText("Thinking").fetchSemanticsNode().unclippedBounds()
            val thinkingDetail =
                onNodeWithText("Checking which call sites still read the old thread id.").fetchSemanticsNode().unclippedBounds()
            val detailStep = thinkingDetail.top - thinking.bottom
            for ((status, explanation) in listOf(
                "Working" to "Tool finished",
                "Error" to "No runtime output for 15 min",
                // web #15326: the reason alone on its line, no " - " lead-in.
                "Stopped" to "Computer stopped",
                "Crashed" to "exit code 1",
            )) {
                val title = onNodeWithText(status).assertExists().fetchSemanticsNode().unclippedBounds()
                val detail = onNodeWithText(explanation).assertExists().fetchSemanticsNode().unclippedBounds()
                assertTrue(detail.top >= title.bottom, "$explanation sits under $status")
                assertEquals(detailStep, detail.top - title.bottom, 0.5f, "$explanation: the detail step of other rows")
                onAllNodesWithText("$status $explanation", substring = true).assertCountEquals(0)
            }
            onNodeWithText("Idle").assertExists()
        }
}
