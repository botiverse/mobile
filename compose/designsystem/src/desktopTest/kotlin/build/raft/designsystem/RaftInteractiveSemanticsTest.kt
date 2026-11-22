package build.raft.designsystem

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import build.raft.designsystem.components.RaftTextarea
import build.raft.designsystem.components.RaftTextFieldClearAction
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftDialogSurface
import build.raft.designsystem.components.RaftFieldLabel
import build.raft.designsystem.theme.LocalRaftStrings
import build.raft.designsystem.theme.RaftStrings
import androidx.compose.runtime.CompositionLocalProvider
import build.raft.designsystem.components.RaftFieldItem
import build.raft.designsystem.components.RaftCheckbox
import build.raft.designsystem.components.appendRaftTextLink
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftSelect
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.components.RaftModeBar
import build.raft.designsystem.components.RaftTab
import build.raft.designsystem.components.RaftTabStrip
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import build.raft.designsystem.components.raftClickable

/** Semantics, keyboard and touch of the interactive components (compose ui-test, desktop). */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class RaftInteractiveSemanticsTest {
    private fun themed(content: @Composable () -> Unit): @Composable () -> Unit = {
        RaftTheme(appearance = RaftAppearance.Light, systemDark = false, content = content)
    }

    /** A short prefix leaves the input the rest of the field, not half of it (#3453 held half for the prefix). */
    @Test
    fun aShortPrefixLeavesTheInputTheRestOfTheField() =
        runComposeUiTest {
            val placeholder = "partner-workspace-for-the-launch-week-review"
            setContent(
                themed {
                    Box(Modifier.width(360.dp)) {
                        RaftTextField("", {}, prefix = "/", placeholder = placeholder)
                    }
                },
            )
            val input = onNodeWithText(placeholder, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val start = input.left / density.density
            val end = input.right / density.density
            assertTrue(start < 40f, "the input starts right after the short prefix, at ${start}dp")
            assertEquals(360f - 12f - 1.5f, end, 1f, "the input runs to the field's 12dp end padding inside the border")
        }

    @Test
    fun aFieldNamesItsControlAndCarriesItsError() =
        runComposeUiTest {
            setContent(
                themed {
                    RaftField(label = "Agent name", error = "Enter a name") {
                        RaftTextField("", {}, placeholder = "Wug")
                    }
                },
            )
            onNodeWithContentDescription("Agent name")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Enter a name"))
            onNodeWithText("Enter a name")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }

    /** The app's words reach the optional marker, the dialog's × and a busy button. */
    @Test
    fun providedStringsReplaceTheEnglishDefaults() =
        runComposeUiTest {
            setContent(
                themed {
                    CompositionLocalProvider(
                        LocalRaftStrings provides RaftStrings(optional = "(facultatif)", close = "Fermer", loading = "Chargement"),
                    ) {
                        Column {
                            RaftFieldLabel("Description", optional = true)
                            RaftDialogSurface(title = "Renommer", onClose = {})
                            RaftButton("Créer", {}, loading = true)
                        }
                    }
                },
            )
            onNodeWithText("Description (facultatif)").assertIsDisplayed()
            onNodeWithContentDescription("Fermer").assertIsDisplayed()
            onNodeWithContentDescription("Chargement").assertExists()
        }

    /** On a TextFieldValue the textarea types at the caller's caret, counts the text and focuses its editor. */
    @Test
    fun aTextareaOnATextFieldValueTypesAtTheCallersCaret() =
        runComposeUiTest {
            var value by mutableStateOf(TextFieldValue("Hello", selection = TextRange(5)))
            var focused = false
            setContent(
                themed {
                    RaftField(label = "Message") {
                        RaftTextarea(
                            value = value,
                            onValueChange = { value = it },
                            limit = 10,
                            inputModifier = Modifier.onFocusChanged { focused = it.isFocused },
                        )
                    }
                },
            )
            val textarea = onNodeWithContentDescription("Message")
            textarea.requestFocus()
            assertTrue(focused, "the input modifier reaches the editor")
            textarea.performTextInput(" Wug")
            // The editor's TextFieldState reports the edit to the owner after the next composition.
            waitForIdle()
            assertEquals("Hello Wug", value.text)
            assertEquals(TextRange(9), value.selection)
            onNodeWithText("9/10").assertExists()
        }

    /** The clear button addon is a named button of its own inside the field, and clears it. */
    @Test
    fun aTextFieldClearActionIsANamedButtonThatClears() =
        runComposeUiTest {
            var value by mutableStateOf("design")
            setContent(
                themed {
                    RaftField(label = "Search channels") {
                        RaftTextField(
                            value,
                            { value = it },
                            trailingAction = if (value.isNotEmpty()) ({ RaftTextFieldClearAction({ value = "" }, label = "Clear Search") }) else null,
                        )
                    }
                },
            )
            onNodeWithContentDescription("Clear Search")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .performClick()
            assertEquals("", value)
            onNodeWithContentDescription("Clear Search").assertDoesNotExist()
            onNodeWithContentDescription("Search channels").assertIsDisplayed()
        }

    /**
     * The strip scrolls only itself: a page scrolled so the strip is off screen stays put
     * when a tab is selected (a bring-into-view request would scroll the page to it).
     */
    @Test
    fun selectingATabDoesNotScrollThePageAroundTheStrip() =
        runComposeUiTest {
            val tabs = listOf("Profile", "Workspace", "Activity", "Settings", "Billing")
            var selected by mutableStateOf("Profile")
            val page = ScrollState(0)
            setContent(
                themed {
                    Column(Modifier.size(220.dp, 200.dp).verticalScroll(page)) {
                        Spacer(Modifier.height(400.dp))
                        RaftTabStrip {
                            tabs.forEach { RaftTab(it, selected = it == selected, onClick = { selected = it }) }
                        }
                        Spacer(Modifier.height(400.dp))
                    }
                },
            )
            waitForIdle()
            assertEquals(0, page.value, "the first composition left the page where it was")
            selected = "Billing"
            waitForIdle()
            assertEquals(0, page.value, "selecting a tab left the page where it was")
            val right = onNodeWithText("Billing").fetchSemanticsNode().boundsInRoot.right / density.density
            assertTrue(right <= 220f, "the strip itself scrolled: the selected tab ends at ${right}dp, inside the 220dp strip")
        }

    /**
     * A link that wraps takes taps only on its own words, on every line: a tap beside its
     * last line (inside the box round all its lines) misses it; taps on its last line and
     * on its first line hit it. It is still one node for screen readers.
     */
    @Test
    fun aWrappedLinkTakesTapsOnlyOnItsWords() =
        runComposeUiTest {
            var taps = 0
            setContent(
                themed {
                    Box(Modifier.width(150.dp)) {
                        RaftText(
                            buildAnnotatedString {
                                appendRaftTextLink("Choose a Plan for Your Whole Team", onClick = { taps++ })
                                append(" now")
                            },
                            Modifier.testTag("text"),
                            style = RaftTheme.typography.footnote,
                        )
                    }
                },
            )
            val text = onNodeWithTag("text")
            val textHeight = text.fetchSemanticsNode().size.height / density.density
            assertTrue(textHeight >= 36f, "the text wraps onto two lines (${textHeight}dp tall)")
            // Last line: "Whole Team now"; its end is plain text and room, not the link.
            text.performTouchInput { click(Offset(width - 2.dp.toPx(), height - 9.dp.toPx())) }
            assertEquals(0, taps, "a tap past the link on its last line misses it")
            text.performTouchInput { click(Offset(4.dp.toPx(), height - 9.dp.toPx())) }
            assertEquals(1, taps, "a tap on the link's last line hits it")
            onNodeWithText("Choose a Plan for Your Whole Team").performClick()
            assertEquals(2, taps, "the link's one node (its first line) hits it")
        }

    /**
     * FieldItem (Base UI Field.Item / Field.Label): the label names the control, and a tap
     * on the label works it.
     */
    @Test
    fun aFieldItemLabelNamesAndWorksItsControl() =
        runComposeUiTest {
            var checked by mutableStateOf(false)
            var on by mutableStateOf(false)
            setContent(
                themed {
                    Column {
                        RaftFieldItem("Send as a task") { RaftCheckbox(checked, { checked = it }) }
                        RaftFieldItem("Notify me") { RaftSwitch(on, { on = it }) }
                    }
                },
            )
            val box = onNodeWithContentDescription("Send as a task")
            box.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            box.performTouchInput { click(Offset(width + 40.dp.toPx(), centerY)) }
            assertTrue(checked, "a tap on the label toggled the checkbox")
            val switch = onNodeWithContentDescription("Notify me")
            switch.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            switch.performTouchInput { click(Offset(width + 30.dp.toPx(), centerY)) }
            assertTrue(on, "a tap on the label toggled the switch")
        }

    /** A select opened and closed from the keyboard hands focus back to its trigger. */
    @Test
    fun aKeyboardSelectReturnsFocusToItsTrigger() =
        runComposeUiTest {
            var value by mutableStateOf<String?>(null)
            setContent(
                themed {
                    val inputModes = LocalInputModeManager.current
                    SideEffect { inputModes.requestInputMode(InputMode.Keyboard) }
                    RaftField(label = "Runtime") {
                        RaftSelect(value, listOf("Claude Code", "Codex"), { value = it }, { it }, placeholder = "Select a runtime")
                    }
                },
            )
            val trigger = onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
            trigger.requestFocus()
            trigger.assertIsFocused()
            // Open with Enter, move to the second row, pick it with Enter.
            trigger.performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            onNodeWithText("Claude Code").assertIsFocused()
            onNodeWithText("Claude Code").performKeyInput { pressKey(Key.DirectionDown) }
            onNodeWithText("Codex").performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals("Codex", value)
            trigger.assertIsFocused()
            // Open again and close with Escape: focus is back on the trigger too.
            trigger.performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            onNodeWithText("Claude Code").assertIsFocused()
            onNodeWithText("Claude Code").performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            onNodeWithText("Claude Code").assertDoesNotExist()
            trigger.assertIsFocused()
        }

    /** ModeBar: the words are a polite live region; Done is a button outside it, 44dp to touch. */
    @Test
    fun theModeBarActionEndsTheModeAndTheWordsAreLive() =
        runComposeUiTest {
            var done = false
            setContent(
                themed {
                    RaftModeBar(
                        title = "Commenting · 1 draft.",
                        description = "Select text to comment on it.",
                        actionLabel = "Done",
                        onAction = { done = true },
                    )
                },
            )
            onNodeWithText("Commenting · 1 draft. Select text to comment on it.")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            onNodeWithText("Done").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            onNodeWithText("Done").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
            onNodeWithText("Done").performTouchInput { click(Offset(centerX, height + 8.dp.toPx())) }
            assertTrue(done, "a tap 8dp under the word ends the mode")
        }

    /** raftClickable: a button by default (or the role asked for) with its click label, the same box, and no click when disabled. */
    @Test
    fun raftClickableIsANamedButtonThatTakesNoRoom() =
        runComposeUiTest {
            var clicks = 0
            var enabled by mutableStateOf(true)
            setContent(
                themed {
                    Column {
                        Box(
                            Modifier
                                .testTag("plain")
                                .size(width = 120.dp, height = 32.dp),
                        )
                        Box(
                            Modifier
                                .testTag("pressable")
                                .size(width = 120.dp, height = 32.dp)
                                .raftClickable(onClick = { clicks++ }, enabled = enabled, onClickLabel = "Open details"),
                        )
                        Box(
                            Modifier
                                .testTag("tab")
                                .size(40.dp)
                                .raftClickable(onClick = {}, role = Role.Tab),
                        )
                    }
                },
            )
            val pressable = onNodeWithTag("pressable")
            pressable.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            assertEquals("Open details", pressable.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
            assertEquals(
                onNodeWithTag("plain").fetchSemanticsNode().size,
                pressable.fetchSemanticsNode().size,
                "the modifier takes no layout room",
            )
            pressable.performClick()
            assertEquals(1, clicks)
            onNodeWithTag("tab").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            enabled = false
            waitForIdle()
            pressable.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
            pressable.performClick()
            assertEquals(1, clicks, "a disabled raftClickable does not click")
        }
}
