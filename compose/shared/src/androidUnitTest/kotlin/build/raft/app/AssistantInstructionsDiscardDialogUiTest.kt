package build.raft.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.assistant.AssistantInstructionsDiscardDialog
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * botiverse/slock #14958 (a6158cff91) and #14967 (9499490bd) on the prompt before closing the
 * assistant's instructions, or the WeChat connect flow, with unsaved edits: two buttons, so a
 * question, "Discard changes?" with Discard (web `agent.detail.editProfile.discardTitle` /
 * `.discard`), not "Unsaved changes" / "Discard Changes"; a small dialog (raft-ui Dialog
 * `size="sm"`, 400dp) with × in the head, Keep editing first and Discard last. × keeps editing.
 * The window is wide, so the dialog's width shows its size.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h900dp")
class AssistantInstructionsDiscardDialogUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun theTwoButtonPromptAsksDiscardChangesInTheSmallDialog() = runRobolectricComposeUiTest {
        var kept = 0
        var discarded = 0
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftDesignSystemTheme {
                    AssistantInstructionsDiscardDialog(onKeepEditing = { kept += 1 }, onDiscard = { discarded += 1 }, testTag = "wechat-discard-confirm")
                }
            }
        }
        try {
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("wechat-discard-confirm").fetchSemanticsNodes().isNotEmpty() }
        } catch (timeout: Throwable) {
            error("waited for the discard prompt: ${timeout.message}")
        }
        onAllNodesWithText("Discard changes?").assertCountEquals(1)
        onAllNodesWithText("Unsaved changes").assertCountEquals(0)
        onAllNodesWithText("Discard Changes").assertCountEquals(0)
        onNodeWithTag("wechat-discard-confirm").assertWidthIsEqualTo(400.dp)
        val keep = onNodeWithText("Keep editing").getBoundsInRoot()
        val discard = onNodeWithText("Discard").getBoundsInRoot()
        assertTrue(keep.right <= discard.left, "Keep editing first, Discard last: keep=$keep discard=$discard")

        onAllNodesWithContentDescription("Close").onFirst().performClick()
        assertEquals(1 to 0, kept to discarded, "× keeps editing")
    }
}
