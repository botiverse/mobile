package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette

/*
 * raft-ui 0.8.0 Timeline (timeline.css), markers on the left: each item is a 9dp status
 * pixel (2dp corners, 1.5dp `sticker-ink` edge, the item's colour) centred on the title's
 * 20dp first line, strung on a 1dp `line-hairline` that stops 2dp short of each pixel;
 * 12dp from the pixel to the text and 12dp between items. The title shares its line with
 * the time (Space Mono 12, `foreground-hint`) on the right; the detail runs the full width
 * under them. There is no Timeline in :designsystem yet; this ports the one recipe the
 * agent activity log needs.
 */

private val RaftTimelineMarker = 10.dp
private val RaftTimelineLine = 20.dp
private val RaftTimelineAxisOffset = (RaftTimelineLine - RaftTimelineMarker) / 2
private val RaftTimelineGap = 12.dp

/** The activity log's title type: Space Mono at the timeline's 14/20 (raft-ui Timeline > Activity log). */
@Composable
internal fun raftTimelineMonoTitleStyle(): TextStyle =
    RaftTheme.typography.monoMeta.copy(fontSize = 14.sp, lineHeight = 20.sp)

/**
 * One raft-ui TimelineItem. [last] hides the line to the next item and the gap under it.
 * [title] fills the first line beside [time]; [content] follows under both.
 */
@Composable
internal fun RaftTimelineItem(
    color: Color,
    last: Boolean,
    modifier: Modifier = Modifier,
    time: String? = null,
    title: @Composable () -> Unit,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val hairline = RaftTheme.colors.lineHairline
    val shape = RoundedCornerShape(2.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                if (!last) {
                    // The line runs on through the next item's offset, 2dp short of each pixel.
                    val x = RaftTimelineMarker.toPx() / 2f
                    val top = (RaftTimelineAxisOffset + RaftTimelineMarker + 2.dp).toPx()
                    val bottom = size.height + (RaftTimelineAxisOffset - 2.dp).toPx()
                    drawLine(hairline, Offset(x, top), Offset(x, bottom), strokeWidth = RaftStroke.Hairline.toPx())
                }
            },
        horizontalArrangement = Arrangement.spacedBy(RaftTimelineGap)
    ) {
        Box(Modifier.width(RaftTimelineMarker).padding(top = RaftTimelineAxisOffset)) {
            Box(
                Modifier
                    .size(RaftTimelineMarker)
                    .background(color, shape)
                    .border(RaftStroke.Control, RaftUiPalette.stickerInk, shape)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (last) 0.dp else RaftTimelineGap),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RaftTimelineGap),
                verticalAlignment = Alignment.Top
            ) {
                Box(Modifier.weight(1f)) { title() }
                if (!time.isNullOrBlank()) {
                    RaftText(
                        text = time,
                        style = RaftTheme.typography.monoMeta.copy(lineHeight = 20.sp),
                        color = RaftTheme.colors.foregroundHint,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    )
                }
            }
            content?.invoke(this)
        }
    }
}
