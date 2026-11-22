package build.raft.designsystem

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldDefaults
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The value-based Raft fields edit a TextFieldState (task #69). Their controlled API must behave
 * as before: edits reach the owner, the owner's own changes reach the editor, a rejected edit is
 * undone, IME actions run, a password stays masked.
 */
@OptIn(ExperimentalTestApi::class)
class RaftTextFieldStateUiTest {
    private val tag = "field"

    private fun androidx.compose.ui.test.ComposeUiTest.show(content: @Composable () -> Unit) =
        setContent { RaftTheme(appearance = RaftAppearance.Light, systemDark = false, content = content) }

    private fun SemanticsNodeInteraction.editableText(): String =
        fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun typingReachesTheOwnerOnceAndOwnerChangesReachTheEditor() =
        runComposeUiTest {
            var value by mutableStateOf("")
            val seen = mutableListOf<String>()
            show {
                RaftTextField(value = value, onValueChange = { seen += it; value = it }, modifier = Modifier.testTag(tag))
            }
            val field = onNodeWithTag(tag)
            field.performTextInput("hi")
            waitForIdle()
            assertEquals("hi", value)
            assertEquals(listOf("hi"), seen)

            // The owner clears the field (after a send): no echo back to the owner.
            value = ""
            waitForIdle()
            assertEquals("", field.editableText())
            assertEquals(listOf("hi"), seen)
        }

    @Test
    fun theImeActionRunsItsKeyboardAction() =
        runComposeUiTest {
            var searched = 0
            show {
                RaftTextField(
                    value = "q",
                    onValueChange = {},
                    modifier = Modifier.testTag(tag),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searched++ }),
                )
            }
            onNodeWithTag(tag).performImeAction()
            waitForIdle()
            assertEquals(1, searched)
        }

    /** CMP-10674 mitigation: name / ID fields turn autocorrect off and change nothing else. */
    @Test
    fun noAutoCorrectOptionsOnlyTurnAutocorrectOff() {
        val options = RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions
        assertEquals(false, options.autoCorrectEnabled)
        assertEquals(KeyboardOptions(autoCorrectEnabled = false), options)
        assertEquals(KeyboardOptions.Default.imeAction, options.imeAction)
        assertEquals(KeyboardOptions.Default.keyboardType, options.keyboardType)
        assertEquals(false, options.copy(imeAction = ImeAction.Done).autoCorrectEnabled)
    }

    @Test
    fun aPasswordIsShownMasked() =
        runComposeUiTest {
            show {
                RaftTextField(
                    value = "secret",
                    onValueChange = {},
                    modifier = Modifier.testTag(tag),
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            val node = onNodeWithTag(tag).fetchSemanticsNode()
            assertEquals("•".repeat(6), node.config[SemanticsProperties.EditableText].text)
        }
}
