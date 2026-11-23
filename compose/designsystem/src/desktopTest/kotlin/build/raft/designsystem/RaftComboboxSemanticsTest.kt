package build.raft.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import build.raft.designsystem.components.RaftCombobox
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftComboboxSheetThreshold
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftMultiCombobox
import build.raft.designsystem.components.RaftSheetScrimTestTag
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Semantics, search and picking of [RaftCombobox] / [RaftMultiCombobox] (compose ui-test, desktop).
 * The test window is 1024dp wide, so the cases pin the window's scale: a phone's
 * ([RaftScale.Compact], the sheet) unless they are about a wide window.
 */
@OptIn(ExperimentalTestApi::class)
class RaftComboboxSemanticsTest {
    private fun themed(scale: RaftScale = RaftScale.Compact, content: @Composable () -> Unit): @Composable () -> Unit = {
        RaftTheme(appearance = RaftAppearance.Light, systemDark = false, scale = scale, content = content)
    }

    /** A few values: the anchored list. */
    private val models =
        listOf(
            RaftComboboxOption("opus", "Claude Opus", group = "Anthropic"),
            RaftComboboxOption("sonnet", "Claude Sonnet", group = "Anthropic"),
            RaftComboboxOption("codex", "GPT-5 Codex", group = "OpenAI", keywords = listOf("openai")),
        )

    /** More than [RaftComboboxSheetThreshold]: the sheet. */
    private val manyModels = models + (1..RaftComboboxSheetThreshold).map { RaftComboboxOption("m$it", "Model $it", group = "Other") }

    private val noModels: (String) -> String = { "No models match \"$it\"" }

    /** An option row in the open list: the selectable node with this label (not the trigger). */
    private fun option(label: String) = hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    private val selectableRow = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)

    @Test
    fun aFewValuesOpenTheAnchoredListWithoutSearchAndAPickClosesIt() =
        runComposeUiTest {
            var value by mutableStateOf<String?>("opus")
            setContent(
                themed {
                    RaftField(label = "Model") {
                        RaftCombobox(value, models, { value = it }, emptyText = noModels, placeholder = "Choose a model")
                    }
                },
            )
            val trigger = onNodeWithContentDescription("Model, Claude Opus")
            trigger.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
            trigger.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
            trigger.performClick()
            waitForIdle()
            onNodeWithContentDescription("Model, Claude Opus").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
            // A Select's list: no search row, so no keyboard.
            onAllNodes(hasSetTextAction()).assertCountEquals(0)
            // Each option is one selectable node with its state; group labels are headings.
            onAllNodes(selectableRow).assertCountEquals(models.size)
            onNode(option("Claude Opus")).assertIsSelected()
            onNode(option("Claude Sonnet")).assertIsNotSelected()
            onNodeWithText("Anthropic").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            onNode(option("GPT-5 Codex")).performClick()
            waitForIdle()
            assertEquals("codex", value)
            onAllNodes(selectableRow).assertCountEquals(0)
            onNodeWithContentDescription("Model, GPT-5 Codex").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
        }

    @Test
    fun manyValuesOpenTheSheetWithAnUnfocusedSearchThatFilters() =
        runComposeUiTest {
            var value by mutableStateOf<String?>(null)
            setContent(
                themed {
                    RaftCombobox(value, manyModels, { value = it }, emptyText = noModels, placeholder = "Choose a model", label = "Model")
                },
            )
            onNodeWithContentDescription("Model, Choose a model").performClick()
            waitForIdle()
            // A phone: the bottom sheet over its scrim.
            onNodeWithTag(RaftSheetScrimTestTag).assertExists()
            // A pure pick: the search row waits for a tap; no keyboard on open.
            onNode(hasSetTextAction()).assertIsNotFocused()
            onNode(hasSetTextAction()).performTextInput("openai")
            waitForIdle()
            onAllNodes(selectableRow).assertCountEquals(1)
            // The other groups' labels go with their options.
            onNodeWithText("Anthropic").assertDoesNotExist()
            onNode(hasSetTextAction()).performTextInput("zz")
            waitForIdle()
            onAllNodes(selectableRow).assertCountEquals(0)
            onNodeWithText("No models match \"openaizz\"")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }

    /**
     * #306: a wide window (a tablet, the web's layout) opens the search in place, raft-ui's
     * Combobox popup under the field, as the web does: no sheet over a scrim. Its search row
     * (not focused for a pure pick) filters, and a pick closes it.
     */
    @Test
    fun onAWideWindowManyValuesOpenTheSearchUnderTheFieldNotASheet() =
        runComposeUiTest {
            var value by mutableStateOf<String?>(null)
            setContent(
                themed(RaftScale.Wide) {
                    RaftCombobox(value, manyModels, { value = it }, emptyText = noModels, placeholder = "Choose a model", label = "Model")
                },
            )
            onNodeWithContentDescription("Model, Choose a model").performClick()
            waitForIdle()
            onNodeWithTag(RaftSheetScrimTestTag).assertDoesNotExist()
            onNode(hasSetTextAction()).assertIsNotFocused()
            onNode(hasSetTextAction()).performTextInput("openai")
            waitForIdle()
            onAllNodes(selectableRow).assertCountEquals(1)
            onNode(option("GPT-5 Codex")).performClick()
            waitForIdle()
            assertEquals("codex", value)
            onAllNodes(hasSetTextAction()).assertCountEquals(0)
            onNodeWithContentDescription("Model, GPT-5 Codex").assertExists()
        }

    /**
     * #306 review: the wide popup lists its rows lazily (models and people can be hundreds), and
     * the keyboard still reaches every one of them: opened from the keyboard its first row has
     * focus, End takes the last row, out of view below, scrolled to and focused, and Enter picks it.
     */
    @Test
    fun onAWideWindowTheKeyboardReachesEveryRowOfTheLazyList() =
        runComposeUiTest {
            var value by mutableStateOf<String?>(null)
            setContent(
                themed(RaftScale.Wide) {
                    val inputModes = LocalInputModeManager.current
                    SideEffect { inputModes.requestInputMode(InputMode.Keyboard) }
                    RaftCombobox(value, manyModels, { value = it }, emptyText = noModels, placeholder = "Choose a model", label = "Model")
                },
            )
            val trigger = onNodeWithContentDescription("Model, Choose a model")
            trigger.requestFocus()
            trigger.performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            onNode(option("Claude Opus")).assertIsFocused()
            onNode(option("Claude Opus")).performKeyInput { pressKey(Key.MoveEnd) }
            waitForIdle()
            onNode(option("Model $RaftComboboxSheetThreshold")).assertIsFocused()
            onNode(option("Model $RaftComboboxSheetThreshold")).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals("m$RaftComboboxSheetThreshold", value)
        }

    @Test
    fun aMultipleComboboxKeepsTheSheetOpenUntilDoneAndChipsRemove() =
        runComposeUiTest {
            var values by mutableStateOf(listOf("opus"))
            setContent(
                themed {
                    RaftField(label = "Models") {
                        RaftMultiCombobox(values, models, { values = it }, emptyText = noModels, placeholder = "Add models")
                    }
                },
            )
            onNodeWithContentDescription("Remove Claude Opus").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            onNodeWithContentDescription("Models, Claude Opus").performClick()
            waitForIdle()
            // Several values: always the sheet, its search not focused (no create).
            onNode(hasSetTextAction()).assertIsNotFocused()
            onNode(option("Claude Sonnet")).performClick()
            waitForIdle()
            assertEquals(listOf("opus", "sonnet"), values)
            // Still open; both are chosen. Picking a chosen one removes it.
            onNode(option("Claude Sonnet")).assertIsSelected()
            onNode(option("Claude Opus")).performClick()
            waitForIdle()
            assertEquals(listOf("sonnet"), values)
            onNodeWithText("Done").performClick()
            waitForIdle()
            onAllNodes(selectableRow).assertCountEquals(0)
            onNodeWithContentDescription("Remove Claude Sonnet").performClick()
            waitForIdle()
            assertEquals(emptyList<String>(), values)
            onNodeWithContentDescription("Models, Add models").assertExists()
        }
}
