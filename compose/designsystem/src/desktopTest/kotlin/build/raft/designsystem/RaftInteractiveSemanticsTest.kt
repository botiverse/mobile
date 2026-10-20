package build.raft.designsystem

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.fillMaxWidth
import kotlin.math.abs
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftFieldItem
import build.raft.designsystem.components.RaftCheckbox
import build.raft.designsystem.components.appendRaftTextLink
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.components.RaftMenuSurface
import build.raft.designsystem.components.RaftPopupMargins
import build.raft.designsystem.components.RaftSafeArea
import build.raft.designsystem.components.raftMenuHeightCap
import build.raft.designsystem.components.RaftField
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftMenuSeparator
import build.raft.designsystem.components.RaftSelect
import build.raft.designsystem.components.RaftSwitch
import build.raft.designsystem.components.RaftTab
import build.raft.designsystem.components.RaftTabStrip
import build.raft.designsystem.components.RaftTabs
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftToggleGroup
import build.raft.designsystem.components.RaftToggleGroupItem
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Semantics, keyboard and touch of the interactive components (compose ui-test, desktop). */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class RaftInteractiveSemanticsTest {
    private fun themed(content: @Composable () -> Unit): @Composable () -> Unit = {
        RaftTheme(appearance = RaftAppearance.Light, systemDark = false, content = content)
    }

    @Test
    fun aMenuPopupHugsItsRowsInAWideWindow() =
        runComposeUiTest {
            setContent(
                themed {
                    Box(Modifier.width(1000.dp)) {
                        RaftDropdownMenu(expanded = true, onDismissRequest = {}) {
                            RaftMenuItem("Name", {})
                            RaftMenuSeparator()
                            RaftMenuItem("Mark All as Read", {})
                        }
                    }
                },
            )
            val row = onNodeWithText("Mark All as Read").fetchSemanticsNode().boundsInRoot.width / density.density
            // 240dp minimum less the 6dp padding each side; nowhere near the window's width.
            assertTrue(row in 227f..260f, "row is ${row}dp wide")
        }

    @Test
    fun aSelectListIsAsWideAsItsTrigger() =
        runComposeUiTest {
            setContent(
                themed {
                    Box(Modifier.width(360.dp)) {
                        RaftSelect<String>(null, listOf("Claude Code", "Codex"), {}, { it }, placeholder = "Select a runtime")
                    }
                },
            )
            onNodeWithText("Select a runtime").performClick()
            val row = onNodeWithText("Codex").fetchSemanticsNode().boundsInRoot.width / density.density
            assertEquals(348f, row, 1f, "trigger 360dp less the 6dp padding each side")
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

    /** A control beside the label keeps its own name; the field's control is still named by the label. */
    @Test
    fun aFieldLabelTrailingControlIsNamedOnItsOwn() =
        runComposeUiTest {
            var on by mutableStateOf(false)
            setContent(
                themed {
                    RaftField(
                        label = "Custom instructions",
                        labelTrailing = { RaftSwitch(checked = on, onCheckedChange = { on = it }, label = "Use Custom Instructions") },
                    ) {
                        RaftTextField("", {}, placeholder = "Answer in English.")
                    }
                },
            )
            onNodeWithContentDescription("Custom instructions").assertIsDisplayed()
            onNodeWithContentDescription("Use Custom Instructions")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
                .performClick()
            assertTrue(on, "the trailing switch works")
        }

    @Test
    fun aSelectTriggerSaysItsNameValueAndState() =
        runComposeUiTest {
            setContent(
                themed {
                    RaftField(label = "Runtime") {
                        RaftSelect<String>(null, listOf("Claude Code", "Codex"), {}, { it }, placeholder = "Select a runtime")
                    }
                },
            )
            val trigger = onNodeWithContentDescription("Runtime, Select a runtime")
            trigger.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
            trigger.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Expand))
            trigger.performClick()
            onNodeWithContentDescription("Runtime, Select a runtime").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.Collapse))
        }

    @Test
    fun menuKeyboard() =
        runComposeUiTest {
            var open by mutableStateOf(false)
            setContent(
                themed {
                    // A keyboard user: the menu is opened from the keyboard.
                    val inputModes = LocalInputModeManager.current
                    SideEffect { inputModes.requestInputMode(InputMode.Keyboard) }
                    Box {
                        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            RaftMenuItem("Archive", {})
                            RaftMenuItem("Mark as Read", {})
                            RaftMenuItem("Disabled", {}, enabled = false)
                            RaftMenuItem("Notify Me", {})
                        }
                    }
                },
            )
            waitForIdle()
            open = true
            waitForIdle()
            // Opened from the keyboard: the first row has focus.
            onNodeWithText("Archive").assertIsFocused()
            onNodeWithText("Archive").performKeyInput { pressKey(Key.DirectionDown) }
            onNodeWithText("Mark as Read").assertIsFocused()
            onNodeWithText("Mark as Read").performKeyInput { pressKey(Key.MoveEnd) }
            // Disabled rows are skipped.
            onNodeWithText("Notify Me").assertIsFocused()
            onNodeWithText("Notify Me").performKeyInput { pressKey(Key.DirectionDown) }
            onNodeWithText("Archive").assertIsFocused()
            onNodeWithText("Archive").performKeyInput { pressKey(Key.M) }
            onNodeWithText("Mark as Read").assertIsFocused()
            onNodeWithText("Mark as Read").performKeyInput { pressKey(Key.MoveHome) }
            onNodeWithText("Archive").assertIsFocused()
        }

    @Test
    fun aToggleGroupItemIsAToggleButtonNotASwitch() =
        runComposeUiTest {
            setContent(themed { RaftToggleGroup { RaftToggleGroupItem("Bold", pressed = true, onPressedChange = {}) } })
            onNodeWithText("Bold").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            onNodeWithText("Bold").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
        }

    @Test
    fun aSwitchIsNamedByItsLabel() =
        runComposeUiTest {
            setContent(themed { RaftSwitch(true, {}, label = "Desktop notifications") })
            onNodeWithContentDescription("Desktop notifications")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        }

    /** A 32dp tab takes a tap 5dp above its box (inside the 44dp target). */
    @Test
    fun aSmallTabTakesTapsInItsWidenedTarget() =
        runComposeUiTest {
            var picked = false
            setContent(
                themed {
                    Column(Modifier.padding(20.dp)) {
                        RaftTabs { RaftTab("Tasks", selected = false, onClick = { picked = true }) }
                    }
                },
            )
            onNodeWithText("Tasks").performTouchInput { click(Offset(centerX, -5.dp.toPx())) }
            assertTrue(picked, "a tap 5dp above the tab picks it")
        }

    @Test
    fun theSelectedTabScrollsIntoView() =
        runComposeUiTest {
            setContent(
                themed {
                    Box(Modifier.width(220.dp)) {
                        RaftTabStrip {
                            listOf("Profile", "Workspace", "Activity", "Settings", "Billing").forEach {
                                RaftTab(it, selected = it == "Billing", onClick = {})
                            }
                        }
                    }
                },
            )
            waitForIdle()
            val right = onNodeWithText("Billing").fetchSemanticsNode().boundsInRoot.right / density.density
            assertTrue(right <= 220f, "the selected tab ends at ${right}dp, inside the 220dp strip")
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
     * Only the strip's first reveal jumps: a tab that was not selected at first composition
     * animates into view the first time it is selected (still off screen one frame later),
     * and ends up in view.
     */
    @Test
    fun aTabSelectedLaterAnimatesIntoView() {
        // A strip composed with a tab selected, and one composed with none: neither jumps later.
        listOf<String?>("Profile", null).forEach { initial -> aTabSelectedLaterAnimatesIntoView(initial) }
    }

    private fun aTabSelectedLaterAnimatesIntoView(initial: String?) =
        runComposeUiTest {
            val tabs = listOf("Profile", "Workspace", "Activity", "Settings", "Billing")
            var selected by mutableStateOf(initial)
            setContent(
                themed {
                    Box(Modifier.width(220.dp)) {
                        RaftTabStrip {
                            tabs.forEach { RaftTab(it, selected = it == selected, onClick = { selected = it }) }
                        }
                    }
                },
            )
            waitForIdle()
            // Unclipped: boundsInRoot is cut to the strip's viewport.
            fun billingRight() =
                onNodeWithText("Billing").fetchSemanticsNode().let { it.positionInRoot.x + it.size.width } / density.density
            val before = billingRight()
            assertTrue(before > 220f, "Billing starts past the 220dp strip (${before}dp)")
            mainClock.autoAdvance = false
            selected = "Billing"
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()
            val midway = billingRight()
            assertTrue(midway > 220f, "initially $initial: two frames in, the strip is still animating (Billing ends at ${midway}dp), not jumped")
            mainClock.autoAdvance = true
            waitForIdle()
            val after = billingRight()
            assertTrue(after <= 220f, "the animation ends with Billing inside the strip (${after}dp)")
        }

    /**
     * A TextLink inside a sentence is a button of its own over its words: a tap on it runs
     * its action, a disabled one is disabled in semantics. A standalone one is a button too.
     */
    @Test
    fun aTextLinkInASentenceIsAButtonOverItsWords() =
        runComposeUiTest {
            var created = 0
            var forgot = 0
            setContent(
                themed {
                    Column {
                        RaftText(
                            buildAnnotatedString {
                                append("Don't have an account? ")
                                appendRaftTextLink("Create One", onClick = { created++ })
                                append(" ")
                                appendRaftTextLink("Log Out", onClick = { error("disabled") }, enabled = false)
                            },
                        )
                        RaftTextLink("Forgot Password", onClick = { forgot++ })
                    }
                },
            )
            onNodeWithText("Create\u00A0One").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
            assertEquals(1, created, "the inline link ran its action")
            onNodeWithText("Log\u00A0Out").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
            onNodeWithText("Forgot Password").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
            assertEquals(1, forgot)
        }

    /**
     * The text of a RaftText with links takes the width it is given: with fillMaxWidth and
     * TextAlign.End the line ends at the container's end, as plain RaftText does.
     */
    @Test
    fun aRaftTextWithALinkAlignsInTheWidthItIsGiven() =
        runComposeUiTest {
            setContent(
                themed {
                    Box(Modifier.width(320.dp)) {
                        RaftText(
                            buildAnnotatedString {
                                append("Don't have an account? ")
                                appendRaftTextLink("Create One", onClick = {})
                            },
                            Modifier.fillMaxWidth(),
                            textAlign = TextAlign.End,
                        )
                    }
                },
            )
            val link = onNodeWithText("Create\u00A0One").fetchSemanticsNode()
            val right = (link.positionInRoot.x + link.size.width) / density.density
            assertTrue(abs(right - 320f) <= 1f, "the end-aligned link ends at the 320dp container's end, not at ${right}dp")
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
            onNodeWithText("Choose\u00A0a\u00A0Plan\u00A0for\u00A0Your\u00A0Whole\u00A0Team").performClick()
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

    /**
     * A popup measured against a 640px window with a 300px keyboard: the menu (surface and
     * shadow room) is capped to the safe area less 8px each side, and its rows scroll.
     */
    @Test
    fun aLongMenuStopsAboveTheKeyboardAndScrolls() =
        runSkikoComposeUiTest(size = Size(400f, 640f), density = Density(1f)) {
            val insets = RaftPopupMargins(horizontal = 16, top = 8, bottom = 24)
            setContent(
                themed {
                    // Stands in for the popup window: loose constraints the window's size.
                    Box(Modifier.size(400.dp, 640.dp)) {
                        Box(
                            Modifier
                                .raftMenuHeightCap(windowMargin = 8, insets = insets, safeArea = RaftSafeArea(bottom = 300))
                                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                        ) {
                            RaftMenuSurface(Modifier.testTag("surface")) {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    (1..20).forEach { RaftMenuItem("Row $it", {}) }
                                }
                            }
                        }
                    }
                },
            )
            val surface = onNodeWithTag("surface").fetchSemanticsNode().boundsInRoot
            // 640 - 300 (keyboard) - 8 below, - 8 above.
            assertEquals(324f, surface.height, 0.5f, "surface height")
            onNodeWithText("Row 20").performScrollTo().assertIsDisplayed()
        }
}
