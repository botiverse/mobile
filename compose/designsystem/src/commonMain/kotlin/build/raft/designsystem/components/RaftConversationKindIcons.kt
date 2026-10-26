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
 * 18x18 viewBox at Lucide's 1.5-unit stroke (raft-design #751): a thread is two outlined
 * bubbles, the back one stopping a stroke short of the front one (raft-design #752, as in Lucide's
 * `messages-square`); a direct message is the outline bubble with a person. Inbox and Activity
 * titles lead with them, beside Lucide `hash` for a channel.
 */
enum class RaftConversationKindGlyph {
    Thread,
    DirectMessage,
}

private val BubbleOutline by lazy {
    parse("M16.25 5V4.25C16.25 3.15 15.35 2.25 14.25 2.25H3.75C2.65 2.25 1.75 3.15 1.75 4.25V11.25C1.75 12.35 2.65 13.25 3.75 13.25H5.75V16.25L7.5 14.85")
}
private val ThreadBackBubble by lazy {
    parse("M3.5 11.5L1.75 13.25V4.25A2 2 0 0 1 3.75 2.25H11.25")
}
private val ThreadFrontBubble by lazy {
    parse("M8.25 5H14.5C15.6 5 16.5 5.9 16.5 7V11.75C16.5 12.85 15.6 13.75 14.5 13.75H13V16.25L9.75 13.75H8.25C7.15 13.75 6.25 12.85 6.25 11.75V7C6.25 5.9 7.15 5 8.25 5Z")
}
private val DirectMessageHead by lazy {
    parse("M12.5 8a1.75 1.75 0 1 0 3.5 0a1.75 1.75 0 1 0 -3.5 0z")
}
private val DirectMessageShoulders by lazy {
    parse("M11 16.25C11.3 13.65 12.5 12 14.25 12S17.2 13.65 17.5 16.25")
}

private fun parse(data: String): Path = PathParser().parsePathString(data).toPath()

/**
 * Draws [glyph] at [size] in [color], else the current ink. Decorative: no semantics.
 * [strokeWidth] (in the 18-unit viewBox) replaces the glyph's own stroke, as raft-ui's
 * `strokeWidth` prop does: `SidebarItemThreadCount` draws the thread at 1.75.
 */
@Composable
fun RaftConversationKindIcon(
    glyph: RaftConversationKindGlyph,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
    color: Color = Color.Unspecified,
    strokeWidth: Float? = null,
) {
    val ink =
        when {
            color.isSpecified -> color
            else -> LocalRaftContentColor.current.takeIf { it.isSpecified } ?: RaftTheme.colors.foreground
        }
    Canvas(modifier.size(size)) {
        val unit = this.size.minDimension / 18f
        scale(unit, unit, pivot = Offset.Zero) {
            val stroke = Stroke(width = strokeWidth ?: 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (glyph) {
                RaftConversationKindGlyph.Thread -> {
                    drawPath(ThreadBackBubble, ink, style = stroke)
                    drawPath(ThreadFrontBubble, ink, style = stroke)
                }
                RaftConversationKindGlyph.DirectMessage -> {
                    drawPath(BubbleOutline, ink, style = stroke)
                    drawPath(DirectMessageHead, ink, style = stroke)
                    drawPath(DirectMessageShoulders, ink, style = stroke)
                }
            }
        }
    }
}
