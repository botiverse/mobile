package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftDropdownMenu
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
}
