package build.raft.designsystem.screenshot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftFocusedPreview
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftMessageReaction
import build.raft.designsystem.components.RaftMessageReactionAdd
import build.raft.designsystem.components.RaftMessageReplies
import build.raft.designsystem.components.RaftMessageRepliesItem
import build.raft.designsystem.components.RaftMessageRepliesItemTime
import build.raft.designsystem.components.RaftMessageSavedIndicator
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiPalette
import kotlin.test.Test

/**
 * raft-ui MessageItem footer and MessageReplies (artin task #20, gaps 6, 7, 23):
 *  - `message_reaction`: the reaction chip at rest, yours (`attention-soft`), failed, pressed
 *    and focused, a bare emoji and a wide count; the add chip at rest and pressed; the saved
 *    marker; a footer row as a message carries it.
 *  - `message_replies`: the thread summary with no new replies, with a new count sticker and a
 *    draft, pressed, and a narrow one whose previews truncate.
 * The emoji is a drawn stand-in (the app draws its sprite atlas through the glyph slot).
 */
class MessageReactionScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    private fun capture(name: String, content: @Composable () -> Unit) =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("${name}_${appearance.name.lowercase()}", appearance, content = content)
        }

    @Test
    fun messageReaction() =
        capture("message_reaction") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Chip: rest, mine, failed, pressed, focused, no count, wide count")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftMessageReaction(count = 1, mine = false, glyph = { Face() }, onClick = {})
                    RaftMessageReaction(count = 3, mine = true, glyph = { Face() }, onClick = {})
                    RaftMessageReaction(count = 2, mine = true, failed = true, glyph = { Face() }, onClick = {})
                    StateCell(pressed = true) { RaftMessageReaction(count = 4, mine = false, glyph = { Face() }, onClick = {}) }
                    StateCell(focused = true) { RaftMessageReaction(count = 5, mine = true, glyph = { Face() }, onClick = {}) }
                    RaftMessageReaction(count = 0, mine = false, glyph = { Face() })
                    RaftMessageReaction(count = 128, mine = false, glyph = { Face() })
                }
                Caption("Add chip: rest, pressed. Saved marker")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftMessageReactionAdd(label = "Add Reaction", onClick = {})
                    StateCell(pressed = true) { RaftMessageReactionAdd(label = "Add Reaction", onClick = {}) }
                    RaftMessageSavedIndicator(label = "Saved")
                }
                Caption("A footer: saved, two reactions, add")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RaftMessageSavedIndicator(label = "Saved")
                    RaftMessageReaction(count = 3, mine = true, glyph = { Face() }, onClick = {})
                    RaftMessageReaction(count = 1, mine = false, glyph = { Face(RaftUiPalette.attention) }, onClick = {})
                    RaftMessageReactionAdd(label = "Add Reaction", onClick = {})
                }
            }
        }

    @Test
    fun messageReplies() =
        capture("message_replies") {
            Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("No new replies")
                RaftMessageReplies(countLabel = "2 replies", onClick = {}) {
                    Reply("Carol", "Ping me if the error rate moves.", "2:05 AM")
                }
                Caption("One new, with a draft")
                RaftMessageReplies(
                    countLabel = "4 replies",
                    onClick = {},
                    newCount = 1,
                    newCountLabel = "1 new",
                    draftLabel = "Draft",
                ) {
                    Reply("Carol", "Is the migration safe to run twice?", "1:52 AM")
                    Reply("Alpha", "Yes, no breaking changes. The new column has a default value.", "2:08 AM")
                }
                Caption("Pressed, 12 new")
                StateCell(pressed = true) {
                    RaftMessageReplies(countLabel = "31 replies", onClick = {}, newCount = 12, newCountLabel = "12 new") {
                        Reply("Alpha", "Rolled back; the error rate is flat again.", "2:10 AM")
                    }
                }
                Caption("Narrow: the preview truncates, then the name")
                Box(Modifier.width(240.dp)) {
                    RaftMessageReplies(countLabel = "1 reply", onClick = {}, draftLabel = "Draft") {
                        Reply("Carol", "Is the migration safe to run twice?", "1:52 AM")
                        Reply("A very long display name for a person", "Short.", "2:08 AM")
                    }
                }
            }
        }

    @Composable
    private fun Reply(sender: String, content: String, time: String) {
        RaftMessageRepliesItem(
            sender = sender,
            content = content,
            modifier = Modifier.height(20.dp),
            avatar = {
                RaftAvatar(
                    type = if (sender == "Alpha") RaftAvatarType.Agent else RaftAvatarType.Human,
                    size = RaftAvatarSize.Xxs,
                    initials = sender,
                )
            },
            time = { RaftMessageRepliesItemTime(time) },
        )
    }

    /** A 16dp emoji stand-in: a face in [fill] with an ink edge and eyes. */
    @Composable
    private fun Face(fill: Color = RaftUiPalette.selected) {
        Canvas(Modifier.size(16.dp).padding(1.dp)) {
            val ink = RaftUiPalette.stickerInk
            drawCircle(fill)
            drawCircle(ink, style = Stroke(1.5.dp.toPx()))
            val r = 1.dp.toPx()
            drawCircle(ink, r, Offset(size.width * 0.36f, size.height * 0.42f))
            drawCircle(ink, r, Offset(size.width * 0.64f, size.height * 0.42f))
        }
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
