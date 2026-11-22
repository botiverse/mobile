package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * raft-ui 0.102.6 (`usePopupAnchorDismiss`): an open [RaftDropdownMenu] closes, as Back does, when
 * its trigger is scrolled out of its scrolling region or leaves the composition, and stays open
 * while the trigger shows (compose ui-test, desktop).
 */
@OptIn(ExperimentalTestApi::class)
class RaftPopupAnchorDismissTest {
    private fun themed(content: @Composable () -> Unit): @Composable () -> Unit = {
        RaftTheme(appearance = RaftAppearance.Light, systemDark = false, content = content)
    }

    @Test
    fun aMenuClosesOnceItsTriggerIsScrolledOutOfItsRegion() =
        runComposeUiTest {
            var open by mutableStateOf(true)
            var dismissals = 0
            setContent(
                themed {
                    // The region sits inside the window, so the trigger scrolled out of it is still
                    // over the window: clipped by the region, not laid out past the window.
                    Column(Modifier.padding(top = 400.dp).height(200.dp).verticalScroll(rememberScrollState())) {
                        Spacer(Modifier.height(100.dp))
                        Box {
                            RaftText("Trigger")
                            RaftDropdownMenu(expanded = open, onDismissRequest = { open = false; dismissals++ }) {
                                RaftMenuItem("Rename", onClick = {})
                            }
                        }
                        Spacer(Modifier.height(400.dp))
                        RaftText("Bottom")
                    }
                },
            )
            waitForIdle()
            assertTrue(open, "the trigger shows: the menu stays open")
            onNodeWithText("Rename").assertExists()

            onNodeWithText("Bottom").performScrollTo()
            waitForIdle()
            assertFalse(open, "the trigger is scrolled out: the menu closes")
            assertEquals(1, dismissals)
            onNodeWithText("Rename").assertDoesNotExist()
        }

    /**
     * #306 review: a popup people are typing in (its search row focused, the keyboard up) stays
     * open when its trigger is clipped: in a centred dialog that keyboard shrinks the window and
     * clips the field the popup hangs from.
     */
    @Test
    fun aMenuSomeoneIsTypingInStaysOpenWhenItsTriggerIsScrolledOut() =
        runComposeUiTest {
            var open by mutableStateOf(true)
            var dismissals = 0
            setContent(
                themed {
                    Column(Modifier.padding(top = 400.dp).height(200.dp).verticalScroll(rememberScrollState())) {
                        Spacer(Modifier.height(100.dp))
                        Box {
                            RaftText("Trigger")
                            RaftDropdownMenu(expanded = open, onDismissRequest = { open = false; dismissals++ }, typing = true) {
                                RaftMenuItem("Rename", onClick = {})
                            }
                        }
                        Spacer(Modifier.height(400.dp))
                        RaftText("Bottom")
                    }
                },
            )
            waitForIdle()
            onNodeWithText("Bottom").performScrollTo()
            waitForIdle()
            assertTrue(open, "typing in it: the clipped trigger does not close it")
            assertEquals(0, dismissals)
            onNodeWithText("Rename").assertExists()
        }

    @Test
    fun closingAMenuDoesNotDismissItAgain() =
        runComposeUiTest {
            var open by mutableStateOf(true)
            var dismissals = 0
            setContent(
                themed {
                    Box {
                        RaftText("Trigger")
                        RaftDropdownMenu(expanded = open, onDismissRequest = { open = false; dismissals++ }) {
                            RaftMenuItem("Rename", onClick = {})
                        }
                    }
                },
            )
            waitForIdle()
            open = false
            waitForIdle()
            assertEquals(0, dismissals, "closed by its owner: no extra onDismissRequest")
        }
}
