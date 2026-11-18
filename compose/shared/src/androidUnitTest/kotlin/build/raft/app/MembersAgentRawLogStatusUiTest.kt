package build.raft.app

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
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
            assertEquals(listOf("Thinking", "Working", "Output", "Error", "Idle"), log.map { it.title })
            setContent {
                RaftDesignSystemTheme {
                    Column {
                        log.forEach { MembersAgentActivityRow(it, SubagentsFixtureTimeContext, onOpenUrl = {}) }
                    }
                }
            }
            for ((status, explanation) in listOf("Working" to "Tool finished", "Error" to "No runtime output for 15 min")) {
                val title = onNodeWithText(status).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val detail = onNodeWithText(explanation).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(detail.top >= title.bottom, "$explanation sits under $status")
                onAllNodesWithText("$status $explanation", substring = true).assertCountEquals(0)
            }
            onNodeWithText("Idle").assertIsDisplayed()
        }
}
