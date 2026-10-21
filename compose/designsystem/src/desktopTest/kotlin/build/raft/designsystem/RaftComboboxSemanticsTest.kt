package build.raft.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import build.raft.designsystem.components.RaftCombobox
import build.raft.designsystem.components.RaftComboboxOption
import build.raft.designsystem.components.RaftComboboxSheet
import build.raft.designsystem.components.RaftComboboxSheetThreshold
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftMultiCombobox
import build.raft.designsystem.components.RaftMultiComboboxSheet
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Semantics, search and picking of [RaftCombobox] / [RaftMultiCombobox] (compose ui-test, desktop). */
@OptIn(ExperimentalTestApi::class)
class RaftComboboxSemanticsTest {
    private fun themed(content: @Composable () -> Unit): @Composable () -> Unit = {
        RaftTheme(appearance = RaftAppearance.Light, systemDark = false, content = content)
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

    @Test
    fun aCreatingComboboxFocusesItsSearchAndOffersTheTypedValue() =
        runComposeUiTest {
            var value by mutableStateOf<String?>(null)
            var created: String? = null
            setContent(
                themed {
                    RaftCombobox(
                        value,
                        models,
                        { value = it },
                        emptyText = noModels,
                        placeholder = "Choose a model",
                        label = "Model",
                        create = {
                            created = it
                            value = it
                        },
                    )
                },
            )
            // A few values, but typing is the point: the sheet, with the keyboard up.
            onNodeWithContentDescription("Model, Choose a model").performClick()
            waitForIdle()
            onNode(hasSetTextAction()).assertIsFocused()
            // Exactly an option's label: nothing to create.
            onNode(hasSetTextAction()).performTextInput("claude opus")
            waitForIdle()
            onNodeWithText("Use “claude opus”").assertDoesNotExist()
            onNode(hasSetTextAction()).performTextInput("-x")
            waitForIdle()
            // Nothing matches, but the typed value is offered instead of the empty line.
            onNodeWithText(noModels("claude opus-x")).assertDoesNotExist()
            onNode(option("Use “claude opus-x”")).performClick()
            waitForIdle()
            assertEquals("claude opus-x", created)
            onAllNodes(hasSetTextAction()).assertCountEquals(0)
            onNodeWithContentDescription("Model, claude opus-x").assertExists()
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

    /** A sheet opened by the caller's own trigger: the caller's query filters; a pick closes it; Clear clears. */
    @Test
    fun aComboboxSheetFiltersByTheCallersQueryAndAPickCloses() =
        runComposeUiTest {
            var open by mutableStateOf(true)
            var value by mutableStateOf<String?>("opus")
            var query by mutableStateOf("")
            var cleared = false
            setContent(
                themed {
                    if (open) {
                        RaftComboboxSheet(
                            value = value,
                            options = models,
                            onValueChange = { value = it },
                            onDismissRequest = { open = false },
                            title = "Model",
                            emptyText = noModels,
                            query = query,
                            onQueryChange = { query = it },
                            onClear = { cleared = true },
                        )
                    }
                },
            )
            onNode(hasSetTextAction()).assertIsNotFocused()
            onNode(hasSetTextAction()).performTextInput("codex")
            waitForIdle()
            assertEquals("codex", query)
            onAllNodes(selectableRow).assertCountEquals(1)
            onNode(option("GPT-5 Codex")).performClick()
            waitForIdle()
            assertEquals("codex", value)
            assertEquals(false, open)

            open = true
            waitForIdle()
            onNodeWithText("Clear").performClick()
            waitForIdle()
            assertEquals(true, cleared)
            assertEquals(false, open)
        }

    /** The multiple sheet for the caller's own trigger stays open across picks until Done. */
    @Test
    fun aMultiComboboxSheetKeepsOpenUntilDone() =
        runComposeUiTest {
            var open by mutableStateOf(true)
            var values by mutableStateOf(listOf<String>())
            var query by mutableStateOf("")
            setContent(
                themed {
                    if (open) {
                        RaftMultiComboboxSheet(
                            values = values,
                            options = models,
                            onValuesChange = { values = it },
                            onDismissRequest = { open = false },
                            title = "Models",
                            emptyText = noModels,
                            query = query,
                            onQueryChange = { query = it },
                        )
                    }
                },
            )
            onNode(option("Claude Opus")).performClick()
            onNode(option("Claude Sonnet")).performClick()
            waitForIdle()
            assertEquals(listOf("opus", "sonnet"), values)
            assertEquals(true, open)
            onNodeWithText("Clear").performClick()
            waitForIdle()
            assertEquals(emptyList<String>(), values)
            onNodeWithText("Done").performClick()
            waitForIdle()
            assertEquals(false, open)
        }
}
