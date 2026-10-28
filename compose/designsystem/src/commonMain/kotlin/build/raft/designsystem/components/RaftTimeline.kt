package build.raft.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.unit.LayoutDirection
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

/** raft-ui 0.40 timeline.css `--r-timeline-icon-size`. */
private val RaftTimelineMarkerSize = 10.dp
private val RaftTimelineMarkerStroke = 1.5.dp
private val RaftTimelineMarkerRadius = 2.dp
private val RaftTimelineGap = 12.dp
private val RaftTimelineItemGap = 12.dp
private val RaftTimelineInnerGap = 2.dp

/** The gap the hairline leaves above and below each marker (`margin-top: 2px`). */
private val RaftTimelineLineGap = 2.dp

/**
 * raft-ui 0.40 Timeline (`size="sm"`, markers on the left), as the web task History uses it: an
 * ordered list of events, each a 10dp status pixel (1.5dp sticker-ink edge, 2dp radius) centred
 * on the title's first 20dp line, strung on a 1dp `line-hairline` that stops 2dp short of each
 * pixel; the title and the time share the first line (one baseline), whatever follows runs the
 * full width; title and details are one line each, a 12dp chevron unfolding a cut one;
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
    // Dark mode: the die-cut `sticker-edge` ring around the ink edge, as every sticker has.
    val edge = colors.stickerEdge
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
                // The axis is on the start side: the right edge in a right-to-left layout.
                val left = if (layoutDirection == LayoutDirection.Rtl) size.width - markerPx else 0f
                drawRoundRect(fill, Offset(left + inset, offset + inset), rectSize, radius)
                drawRoundRect(RaftUiPalette.stickerInk, Offset(left + inset, offset + inset), rectSize, radius, style = Stroke(strokePx))
                val ringPx = 1.dp.toPx()
                drawRoundRect(
                    edge,
                    Offset(left - ringPx / 2f, offset - ringPx / 2f),
                    Size(markerPx + ringPx, markerPx + ringPx),
                    CornerRadius(RaftTimelineMarkerRadius.toPx() + ringPx / 2f),
                    style = Stroke(ringPx),
                )
                if (!last) {
                    // Runs on through the next item's offset, stopping 2dp short of its marker.
                    val gap = RaftTimelineLineGap.toPx()
                    val x = left + markerPx / 2f
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
            // The time sits on the title's first baseline (raft-ui: one grid row, `items-baseline`).
            Row(horizontalArrangement = Arrangement.spacedBy(RaftTimelineGap)) {
                RaftTimelineFoldText(
                    item.title,
                    style = if (item.titleBold) typography.callout.copy(fontWeight = FontWeight.Bold) else typography.callout,
                    color = colors.foreground,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                )
                item.time?.let { time ->
                    RaftText(
                        time,
                        style = typography.monoMeta,
                        color = colors.foregroundHint,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            item.content?.let { body ->
                CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundHint) { body() }
            }
        }
    }
}

/**
 * A detail line of a [RaftTimeline] item: Space Mono 12 `foreground-hint`, one line; when it is
 * cut, a 12dp chevron after it unfolds the rest (web `TimelineText`).
 */
@Composable
fun RaftTimelineDetail(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = RaftTheme.colors.foregroundHint,
    initiallyOpen: Boolean = false,
) {
    RaftTimelineFoldText(text, RaftTheme.typography.monoMeta, color, modifier, initiallyOpen)
}

/**
 * Web `TimelineText`: one line; a text that does not fit gets a 12dp chevron after it and opens
 * (and folds again) on a tap, a button that says whether it is expanded.
 */
@Composable
private fun RaftTimelineFoldText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
) {
    var open by remember(text) { mutableStateOf(initiallyOpen) }
    var long by remember(text) { mutableStateOf(initiallyOpen) }
    Row(
        modifier.then(
            if (long) {
                Modifier
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { open = !open }
                    .semantics { if (open) collapse { open = false; true } else expand { open = true; true } }
            } else {
                Modifier
            }
        ),
    ) {
        RaftText(
            text,
            Modifier.weight(1f, fill = false).alignByBaseline(),
            style = style,
            color = color,
            maxLines = if (open) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout -> if (!open && layout.hasVisualOverflow) long = true },
        )
        if (long) {
            RaftGlyphIcon(
                if (open) RaftGlyph.ChevronDown else RaftGlyph.ChevronRight,
                Modifier.padding(start = 4.dp).align(Alignment.CenterVertically),
                size = 12.dp,
                color = RaftTheme.colors.foregroundHint,
            )
        }
    }
}
