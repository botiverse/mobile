package build.raft.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme

/**
 * raft-ui's two conversation-kind glyphs (`ThreadIcon`, `DirectMessageIcon`), drawn on their
 * 18x18 viewBox: a thread is the outline bubble with a filled reply bubble in front (1-unit
 * stroke); a direct message is the outline bubble with a person (1.5-unit stroke). Inbox and
 * Activity titles lead with them, beside Lucide `hash` for a channel.
 */
enum class RaftConversationKindGlyph {
    Thread,
    DirectMessage,
}

private val BubbleOutline by lazy {
    parse("M16.25 5V4.25C16.25 3.15 15.35 2.25 14.25 2.25H3.75C2.65 2.25 1.75 3.15 1.75 4.25V11.25C1.75 12.35 2.65 13.25 3.75 13.25H5.75V16.25L7.5 14.85")
}
private val ThreadReplyBubble by lazy {
    parse("M8.75 5.5H15.5C16.6 5.5 17.5 6.4 17.5 7.5V12.25C17.5 13.35 16.6 14.25 15.5 14.25H14V16.75L10.75 14.25H8.75C7.65 14.25 6.75 13.35 6.75 12.25V7.5C6.75 6.4 7.65 5.5 8.75 5.5Z")
}
private val DirectMessageHead by lazy {
    parse("M12.5 8a1.75 1.75 0 1 0 3.5 0a1.75 1.75 0 1 0 -3.5 0z")
}
private val DirectMessageShoulders by lazy {
    parse("M11 16.25C11.3 13.65 12.5 12 14.25 12S17.2 13.65 17.5 16.25")
}

private fun parse(data: String): Path = PathParser().parsePathString(data).toPath()

/** Draws [glyph] at [size] in [color], else the current ink. Decorative: no semantics. */
@Composable
fun RaftConversationKindIcon(
    glyph: RaftConversationKindGlyph,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    color: Color = Color.Unspecified,
) {
    val ink =
        when {
            color.isSpecified -> color
            else -> LocalRaftContentColor.current.takeIf { it.isSpecified } ?: RaftTheme.colors.foreground
        }
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 18f
        scale(unit, unit, pivot = Offset.Zero) {
            when (glyph) {
                RaftConversationKindGlyph.Thread -> {
                    val stroke = Stroke(width = 1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    drawPath(BubbleOutline, ink, style = stroke)
                    drawPath(ThreadReplyBubble, ink, style = Fill)
                }
                RaftConversationKindGlyph.DirectMessage -> {
                    val stroke = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    drawPath(BubbleOutline, ink, style = stroke)
                    drawPath(DirectMessageHead, ink, style = stroke)
                    drawPath(DirectMessageShoulders, ink, style = stroke)
                }
            }
        }
    }
}
