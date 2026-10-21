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
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextInputTrace
import build.raft.designsystem.components.RaftTextInputTracing
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun anEditTheOwnerRejectsIsUndone() =
        runComposeUiTest {
            var value by mutableStateOf("abc")
            show {
                RaftTextField(value = value, onValueChange = { value = it.take(3) }, modifier = Modifier.testTag(tag))
            }
            val field = onNodeWithTag(tag)
            field.performTextReplacement("abcdef")
            waitForIdle()
            assertEquals("abc", value)
            assertEquals("abc", field.editableText())
        }

    /** Handle drags move the selection without touching the text: the owner follows, nothing reverts it. */
    @Test
    fun selectionOnlyChangesReachTheOwnerAndStay() =
        runComposeUiTest {
            val text = "alpha beta gamma"
            var value by mutableStateOf(TextFieldValue(text, TextRange(text.length)))
            show {
                RaftTextarea(value = value, onValueChange = { value = it }, inputModifier = Modifier.testTag(tag))
            }
            val field = onNodeWithTag(tag)
            listOf(TextRange(6, 10), TextRange(6, 16), TextRange(0, 16), TextRange(0, 8)).forEach { range ->
                field.performTextInputSelection(range)
                waitForIdle()
                assertEquals(range, value.selection, "owner follows $range")
                assertEquals(range, field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange], "editor keeps $range")
                assertEquals(text, value.text)
            }
        }

    @Test
    fun aStringOwnersFieldKeepsItsSelection() =
        runComposeUiTest {
            var value by mutableStateOf("alpha beta gamma")
            var changes = 0
            show {
                RaftTextField(value = value, onValueChange = { changes++; value = it }, modifier = Modifier.testTag(tag))
            }
            val field = onNodeWithTag(tag)
            field.performTextInputSelection(TextRange(6, 10))
            waitForIdle()
            field.performTextInputSelection(TextRange(0, 16))
            waitForIdle()
            assertEquals(TextRange(0, 16), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            assertEquals(0, changes, "a String owner hears about text only")
        }

    @Test
    fun aTextFieldValueOwnerPlacesTheCaret() =
        runComposeUiTest {
            var value by mutableStateOf(TextFieldValue("hi @al", TextRange(6)))
            show {
                RaftTextarea(value = value, onValueChange = { value = it }, inputModifier = Modifier.testTag(tag))
            }
            // A mention picker completes the handle and puts the caret after it.
            value = TextFieldValue("hi @alice ", TextRange(10))
            waitForIdle()
            val node = onNodeWithTag(tag).fetchSemanticsNode()
            assertEquals("hi @alice ", node.config[SemanticsProperties.EditableText].text)
            assertEquals(TextRange(10), node.config[SemanticsProperties.TextSelectionRange])
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

    @Test
    fun theTraceSeesEditsAndSyncsWithoutTheText() =
        runComposeUiTest {
            val events = mutableListOf<String>()
            RaftTextInputTracing.trace = RaftTextInputTrace { field, message -> events += "$field $message" }
            try {
                var value by mutableStateOf("")
                show { RaftTextField(value = value, onValueChange = { value = it }, modifier = Modifier.testTag(tag)) }
                onNodeWithTag(tag).performTextInput("secret")
                waitForIdle()
                value = ""
                waitForIdle()
                assertTrue(events.any { it.startsWith("RaftTextField edit ") }, events.toString())
                assertTrue(events.any { it.startsWith("RaftTextField report ") }, events.toString())
                assertTrue(events.any { it.startsWith("RaftTextField push ") }, events.toString())
                assertTrue(events.none { "secret" in it }, events.toString())
            } finally {
                RaftTextInputTracing.trace = null
            }
        }
}
