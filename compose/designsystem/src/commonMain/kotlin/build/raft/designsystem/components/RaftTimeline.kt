package build.raft.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiPalette

/** Builds the items of a [RaftTimeline], oldest first. */
class RaftTimelineScope internal constructor() {
    internal val items = mutableListOf<RaftTimelineItemSpec>()

    /**
     * One event: [title] (`text-callout` `foreground`), [time] at the end of the title's line
     * (Space Mono 12 `foreground-hint`, one line), and [content] under both, full width, in
     * Space Mono 12 `foreground-hint` ([LocalRaftContentColor]). [taskStatus] paints the marker
     * in that status's colour (raft-ui `TimelineItem taskStatus`); without it the marker is
     * the neutral `presence-offline` pixel. [titleBold]: the title at 700 (web task History's
     * `TimelineText bold`).
     */
    fun item(
        title: String,
        time: String? = null,
        titleBold: Boolean = false,
        taskStatus: RaftTaskStatus? = null,
        content: (@Composable () -> Unit)? = null,
    ) {
        items += RaftTimelineItemSpec(title, time, titleBold, taskStatus, content)
    }
}

internal class RaftTimelineItemSpec(
    val title: String,
    val time: String?,
    val titleBold: Boolean,
    val taskStatus: RaftTaskStatus?,
    val content: (@Composable () -> Unit)?,
)

private val RaftTimelineMarkerSize = 9.dp
private val RaftTimelineMarkerStroke = 1.5.dp
private val RaftTimelineMarkerRadius = 2.dp
private val RaftTimelineGap = 12.dp
private val RaftTimelineItemGap = 12.dp
private val RaftTimelineInnerGap = 2.dp

/** The gap the hairline leaves above and below each marker (`margin-top: 2px`). */
private val RaftTimelineLineGap = 2.dp

/**
 * raft-ui 0.40 Timeline (`size="sm"`, markers on the left), as the web task History uses it: an
 * ordered list of events, each a 9dp status pixel (1.5dp sticker-ink edge, 2dp radius) centred
 * on the title's first 20dp line, strung on a 1dp `line-hairline` that stops 2dp short of each
 * pixel; the title and the time share the first line, whatever follows runs the full width;
 * 12dp between the axis and the text and between items. The last item has no line after it.
 */
@Composable
fun RaftTimeline(modifier: Modifier = Modifier, content: RaftTimelineScope.() -> Unit) {
    val items = RaftTimelineScope().apply(content).items
    Column(modifier) {
        items.forEachIndexed { index, item -> RaftTimelineItem(item, last = index == items.lastIndex) }
    }
}

@Composable
private fun RaftTimelineItem(item: RaftTimelineItemSpec, last: Boolean) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val fill = item.taskStatus?.pixelColor(colors) ?: RaftUiPalette.presenceOffline
    val hairline = colors.lineHairline
    val lineHeight = typography.callout.lineHeight
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .drawBehind {
                val markerPx = RaftTimelineMarkerSize.toPx()
                val strokePx = RaftTimelineMarkerStroke.toPx()
                val lineHeightPx = if (lineHeight.isSp) lineHeight.toPx() else 20.dp.toPx()
                val offset = (lineHeightPx - markerPx) / 2f
                val inset = strokePx / 2f
                val rectSize = Size(markerPx - strokePx, markerPx - strokePx)
                val radius = CornerRadius(RaftTimelineMarkerRadius.toPx())
                drawRoundRect(fill, Offset(inset, offset + inset), rectSize, radius)
                drawRoundRect(RaftUiPalette.stickerInk, Offset(inset, offset + inset), rectSize, radius, style = Stroke(strokePx))
                if (!last) {
                    // Runs on through the next item's offset, stopping 2dp short of its marker.
                    val gap = RaftTimelineLineGap.toPx()
                    val x = markerPx / 2f
                    drawLine(
                        hairline,
                        Offset(x, offset + markerPx + gap),
                        Offset(x, size.height + offset - gap),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            },
        horizontalArrangement = Arrangement.spacedBy(RaftTimelineGap),
    ) {
        Spacer(Modifier.width(RaftTimelineMarkerSize))
        Column(
            Modifier.weight(1f).padding(bottom = if (last) 0.dp else RaftTimelineItemGap),
            verticalArrangement = Arrangement.spacedBy(RaftTimelineInnerGap),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(RaftTimelineGap), verticalAlignment = Alignment.Top) {
                RaftText(
                    item.title,
                    Modifier.weight(1f),
                    style = if (item.titleBold) typography.callout.copy(fontWeight = FontWeight.Bold) else typography.callout,
                    color = colors.foreground,
                )
                item.time?.let { time ->
                    RaftText(
                        time,
                        style = typography.monoMeta,
                        color = colors.foregroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        // The time sits on the title's 20dp line.
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            item.content?.let { body ->
                CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundHint) { body() }
            }
        }
    }
}

/** A detail line of a [RaftTimeline] item: Space Mono 12 `foreground-hint`, wrapping. */
@Composable
fun RaftTimelineDetail(text: String, modifier: Modifier = Modifier, color: Color = RaftTheme.colors.foregroundHint) {
    RaftText(text, modifier, style = RaftTheme.typography.monoMeta, color = color)
}
