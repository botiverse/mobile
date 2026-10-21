package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftFocusedPreview
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftMessageListJumpControls
import build.raft.designsystem.components.RaftMessageListJumpPill
import build.raft.designsystem.components.RaftMessageListJumpPillDismiss
import build.raft.designsystem.components.RaftMessageListJumpPillGroup
import build.raft.designsystem.components.RaftMessageListJumpSide
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * raft-ui MessageList > Jump controls (`message-list/compositions/back-to-bottom`,
 * `new-messages`, `next-unread-thread`, `phone`): the pill at rest, pressed and focused,
 * the pill with its dismiss, and the controls in a wide list (one row) and on a 375dp
 * phone (stacked, Back to Bottom nearest the edge), plus the top pill.
 */
class MessageListJumpScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    private fun capture(name: String, content: @Composable () -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, content = content)
        }

    @Test
    fun messageListJump() =
        capture("message_list_jump") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Caption("Pill: rest, pressed, focused")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("Rest", "Pressed", "Focused").forEach { state ->
                        StateCell(pressed = state == "Pressed", focused = state == "Focused") {
                            RaftMessageListJumpPill("Back to Bottom", onClick = {})
                        }
                    }
                    RaftMessageListJumpPill("3 New Messages", onClick = {})
                }
                Caption("Group: pill + dismiss (rest, dismiss pressed)")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    UnreadThreadGroup("Next Unread Thread (3)", RaftGlyph.ArrowDown)
                    StateCell(pressed = true) {
                        RaftMessageListJumpPillGroup {
                            RaftMessageListJumpPillDismiss(label = "Dismiss", onClick = {})
                        }
                    }
                    UnreadThreadGroup("Earliest Unread Thread (2)", RaftGlyph.ArrowUp)
                }
                Caption("Controls on a wide list: one row, 8dp apart")
                ListFrame(width = 560) {
                    RaftMessageListJumpControls(Modifier.align(Alignment.BottomCenter)) {
                        RaftMessageListJumpPill("Back to Bottom", onClick = {})
                        UnreadThreadGroup("Next Unread Thread (3)", RaftGlyph.ArrowDown)
                    }
                }
                Caption("On a 375dp phone: stacked 12dp apart, Back to Bottom nearest the edge; the up pill alone at the top")
                ListFrame(width = 375) {
                    RaftMessageListJumpControls(Modifier.align(Alignment.TopCenter), side = RaftMessageListJumpSide.Top) {
                        UnreadThreadGroup("Earliest Unread Thread (2)", RaftGlyph.ArrowUp)
                    }
                    RaftMessageListJumpControls(Modifier.align(Alignment.BottomCenter)) {
                        RaftMessageListJumpPill("12 New Messages", onClick = {})
                        UnreadThreadGroup("Next Unread Thread (3)", RaftGlyph.ArrowDown)
                    }
                }
            }
        }

    @Composable
    private fun UnreadThreadGroup(text: String, icon: RaftGlyph) {
        RaftMessageListJumpPillGroup {
            RaftMessageListJumpPill(text, onClick = {}, icon = icon)
            RaftMessageListJumpPillDismiss(label = "Dismiss", onClick = {})
        }
    }

    @Composable
    private fun ListFrame(width: Int, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
        Box(
            Modifier
                .width(width.dp)
                .height(180.dp)
                .background(RaftTheme.colors.layerCanvasMuted)
                .border(1.dp, RaftTheme.colors.lineHairline),
            content = content,
        )
    }

    @Composable
    private fun StateCell(pressed: Boolean = false, focused: Boolean = false, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalRaftPressedPreview provides pressed,
            LocalRaftFocusedPreview provides focused,
            content = content,
        )
    }

    @Composable
    private fun Caption(text: String) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
