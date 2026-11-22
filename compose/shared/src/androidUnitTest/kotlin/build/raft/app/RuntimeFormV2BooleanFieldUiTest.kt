package build.raft.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.bottomRight
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.topLeft
import androidx.compose.ui.geometry.Offset
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A runtime form boolean field (Codex "Fast mode") is web's option card (botiverse/slock #14981): the
 * whole card is one checkbox target, its semantics say checked or not, the hint is said once (inside
 * the card), and a disabled field does not toggle.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeFormV2BooleanFieldUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

    @Test
    fun tappingAnywhereOnTheCardTogglesItAndSemanticsSayTheState() = runRobolectricComposeUiTest {
        var checked by mutableStateOf(false)
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RuntimeFormV2BooleanField(
                    label = "Fast mode",
                    checked = checked,
                    onCheckedChange = { checked = it },
                    hint = "Launch this runtime with higher speed at a higher cost per token.",
                    error = null,
                    required = false,
                    enabled = true
                )
            }
        }
        // One target: a single checkbox node, named by the card's title and hint.
        assertEquals(1, onAllNodes(checkbox).fetchSemanticsNodes().size)
        onNode(checkbox).assertIsEnabled().assertIsOff()
        assertEquals(ToggleableState.Off, onNode(checkbox).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
        // The hint is drawn once, inside the card, not again under the field.
        assertEquals(1, onAllNodes(SemanticsMatcher("hint text") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.contains("higher cost per token") }
        }, useUnmergedTree = true).fetchSemanticsNodes().size)

        onNodeWithText("Fast mode", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(true, checked)
        onNode(checkbox).assertIsOn()

        onNodeWithText("Launch this runtime with higher speed at a higher cost per token.", useUnmergedTree = true).performClick()
        waitForIdle()
        assertEquals(false, checked)
        onNode(checkbox).assertIsOff()

        // The card's padding, away from the checkbox and the text, is the same target.
        onNode(checkbox).performTouchInput { click(bottomRight - Offset(4f, 4f)) }
        waitForIdle()
        assertEquals(true, checked)
        onNode(checkbox).performTouchInput { click(topLeft + Offset(4f, 4f)) }
        waitForIdle()
        assertEquals(false, checked)
    }
}
