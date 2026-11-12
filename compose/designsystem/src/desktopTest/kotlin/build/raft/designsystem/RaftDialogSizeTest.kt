package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDialogSize
import build.raft.designsystem.components.RaftDialogSurface
import build.raft.designsystem.theme.LocalRaftMisuse
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * raft-ui Dialog sizes (dialog.recipe): sm 400 (the decision width), md 448, lg 640, wide 896
 * at `min(760, window - 2rem)` tall; the footer note only in lg and wide.
 */
@OptIn(ExperimentalTestApi::class)
class RaftDialogSizeTest {
    @Test
    fun eachSizeTakesRaftUiWidth() =
        runComposeUiTest {
            var size by mutableStateOf(RaftDialogSize.Sm)
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    Box(Modifier.size(1200.dp, 1000.dp)) {
                        RaftDialogSurface(title = "Dialog", modifier = Modifier.testTag("dialog"), size = size, description = "Body")
                    }
                }
            }
            val widths: List<Pair<RaftDialogSize, Dp>> =
                listOf(RaftDialogSize.Sm to 400.dp, RaftDialogSize.Md to 448.dp, RaftDialogSize.Lg to 640.dp, RaftDialogSize.Wide to 896.dp)
            widths.forEach { (each, width) ->
                size = each
                waitForIdle()
                onNodeWithTag("dialog").assertWidthIsEqualTo(width)
            }
        }

    @Test
    fun aWideDialogKeepsItsHeightUpToTheWindow() =
        runComposeUiTest {
            var windowHeight by mutableStateOf(1000.dp)
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    Box(Modifier.size(1200.dp, windowHeight)) {
                        RaftDialogSurface(title = "Settings", modifier = Modifier.testTag("dialog"), size = RaftDialogSize.Wide, description = "Body")
                    }
                }
            }
            onNodeWithTag("dialog").assertHeightIsEqualTo(760.dp)
            windowHeight = 500.dp
            waitForIdle()
            onNodeWithTag("dialog").assertHeightIsEqualTo(500.dp)
        }

    @Test
    fun aFooterNoteShowsOnlyInLgAndWide() =
        runComposeUiTest {
            var size by mutableStateOf(RaftDialogSize.Lg)
            val misuses = mutableListOf<String>()
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    CompositionLocalProvider(LocalRaftMisuse provides { misuses += it }) {
                        Box(Modifier.size(1200.dp, 1000.dp)) {
                            RaftDialogSurface(
                                title = "Invite People",
                                size = size,
                                footerNote = "Billed monthly",
                                footer = {
                                    RaftButton("Cancel", {}, variant = RaftButtonVariant.Ghost)
                                    RaftButton("Send Invites", {})
                                },
                            )
                        }
                    }
                }
            }
            onNodeWithText("Billed monthly").assertExists()
            assertTrue(misuses.isEmpty(), "a note in a Lg dialog is allowed")
            size = RaftDialogSize.Md
            waitForIdle()
            onNodeWithText("Billed monthly").assertDoesNotExist()
            assertTrue(misuses.isNotEmpty(), "a note in a Md dialog is reported")
        }

    @Test
    fun theFooterPutsCancelFirstAndTheCommitLastAtTheEnd() =
        runComposeUiTest {
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    Box(Modifier.size(1200.dp, 1000.dp)) {
                        RaftDialogSurface(
                            title = "Invite People",
                            modifier = Modifier.testTag("dialog"),
                            size = RaftDialogSize.Lg,
                            footerNote = "Billed monthly",
                            footer = {
                                RaftButton("Cancel", {}, variant = RaftButtonVariant.Ghost)
                                RaftButton("Send Invites", {})
                            },
                        )
                    }
                }
            }
            val dialog = onNodeWithTag("dialog").fetchSemanticsNode().boundsInRoot
            val note = onNodeWithText("Billed monthly").fetchSemanticsNode().boundsInRoot
            val cancel = onNodeWithText("Cancel").fetchSemanticsNode().boundsInRoot
            val commit = onNodeWithText("Send Invites").fetchSemanticsNode().boundsInRoot
            assertTrue(note.left < cancel.left, "the note is at the start")
            assertTrue(cancel.right <= commit.left, "Cancel comes before the commit")
            // The commit ends 20dp inside the card's end (the footer's padding), on one line with the note.
            assertEquals(dialog.right - 20 * density.density, commit.right, 1f)
            assertTrue(note.top < commit.bottom && note.bottom > commit.top, "the note sits beside the buttons")
        }
}
