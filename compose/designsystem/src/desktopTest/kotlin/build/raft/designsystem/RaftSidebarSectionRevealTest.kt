package build.raft.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftSidebarChannelItem
import build.raft.designsystem.components.RaftSidebarSectionAction
import build.raft.designsystem.components.RaftSidebarSectionActionsShown
import build.raft.designsystem.components.RaftSidebarSectionHeader
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A sidebar section header's controls (sort, "+") with a mouse or trackpad show only while the
 * header is hovered, holds keyboard focus or a control's menu is open, as on the web
 * (SidebarSectionControls: "show on section hover, keyboard focus or an open menu, always on
 * touch"). Hidden they keep their room and their semantics. With a finger they always show.
 */
@OptIn(ExperimentalTestApi::class)
class RaftSidebarSectionRevealTest {
    private val shown = SemanticsMatcher.expectValue(RaftSidebarSectionActionsShown, true)
    private val hidden = SemanticsMatcher.expectValue(RaftSidebarSectionActionsShown, false)

    @Composable
    private fun Section(pointer: RaftPointerInput, keyboard: Boolean = false, sortMenuOpen: Boolean = false) {
        RaftTheme(appearance = RaftAppearance.Light, systemDark = false, pointerInput = pointer) {
            if (keyboard) {
                val inputModes = LocalInputModeManager.current
                SideEffect { inputModes.requestInputMode(InputMode.Keyboard) }
            }
            Column(Modifier.width(280.dp)) {
                RaftSidebarSectionHeader(
                    "Channels",
                    expanded = true,
                    onExpandedChange = {},
                    count = 3,
                    actions = {
                        RaftSidebarSectionAction(RaftGlyph.ArrowDownUp, "Sort Channels", onClick = {}, menuOpen = sortMenuOpen)
                        RaftSidebarSectionAction(RaftGlyph.Plus, "Create Channel", onClick = {})
                    },
                )
                RaftSidebarChannelItem("general", onClick = {})
            }
        }
    }

    @Test
    fun aMouseShowsTheControlsOnlyWhileTheHeaderIsHovered() =
        runComposeUiTest {
            setContent { Section(RaftPointerInput.Fine) }
            onNode(hidden, useUnmergedTree = true).assertExists()
            // Hidden, the controls are still there for a screen reader, in the same place.
            val create = onNodeWithContentDescription("Create Channel")
            create.assertExists()
            val restingBounds = create.getUnclippedBoundsInRoot()

            onNodeWithText("Channels").performMouseInput { moveTo(center) }
            waitForIdle()
            onNode(shown, useUnmergedTree = true).assertExists()
            assertEquals(restingBounds, create.getUnclippedBoundsInRoot(), "showing the controls moves nothing")

            // Over a row below the header: hidden again.
            onNodeWithText("general").performMouseInput { moveTo(center) }
            waitForIdle()
            onNode(hidden, useUnmergedTree = true).assertExists()

            onNodeWithText("Channels").performMouseInput { moveTo(center) }
            waitForIdle()
            onNode(shown, useUnmergedTree = true).assertExists()
            onNodeWithText("Channels").performMouseInput { moveTo(bottomRight + Offset(400f, 400f)) }
            waitForIdle()
            onNode(hidden, useUnmergedTree = true).assertExists()
        }

    @Test
    fun keyboardFocusOnAControlShowsThem() =
        runComposeUiTest {
            setContent { Section(RaftPointerInput.Fine, keyboard = true) }
            onNode(hidden, useUnmergedTree = true).assertExists()
            val create = onNodeWithContentDescription("Create Channel")
            create.requestFocus()
            create.assertIsFocused()
            waitForIdle()
            onNode(shown, useUnmergedTree = true).assertExists()
        }

    @Test
    fun keyboardFocusOnTheHeaderShowsThem() =
        runComposeUiTest {
            setContent { Section(RaftPointerInput.Fine, keyboard = true) }
            onNode(hidden, useUnmergedTree = true).assertExists()
            onNodeWithText("Channels").requestFocus()
            waitForIdle()
            onNode(shown, useUnmergedTree = true).assertExists()
        }

    @Test
    fun anOpenMenuKeepsThemShownWithAMouse() =
        runComposeUiTest {
            setContent { Section(RaftPointerInput.Fine, sortMenuOpen = true) }
            waitForIdle()
            onNode(shown, useUnmergedTree = true).assertExists()
        }

    @Test
    fun aFingerAlwaysSeesThem() =
        runComposeUiTest {
            setContent { Section(RaftPointerInput.Touch) }
            onNode(shown, useUnmergedTree = true).assertExists()
        }
}
