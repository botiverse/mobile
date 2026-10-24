package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import build.raft.designsystem.theme.RaftTypography

// raft-ui search recipes (packages/ui/src/components/search/*.recipe.ts) for the Home search
// panel. The parts take their type from the caller (a style or the designsystem
// RaftTypography) instead of reading RaftTheme.typography themselves, so a part cannot be
// placed where that read would throw; callers read it inside RaftDesignSystemTheme.

internal object RaftSearchMetrics {
    /** Rows, headings and the summary: `px-4`. */
    val Inset = 16.dp

    /** search-entity / search-message rows: `py-3`. */
    val RowPaddingVertical = 12.dp

    /** search-entity: `gap-3` between the leading slot and the text. */
    val EntityGap = 12.dp

    /** search-entity: `max-md:min-h-16`. */
    val EntityMinHeight = 64.dp

    /** Avatar `sm` / IconTile `sm`. */
    val EntityLeading = 27.dp

    /** search-entity separator: `not-first:before:left-[55px]` (under the text). */
    val EntitySeparatorInset = 55.dp

    /** search-message / search-thread separator: `not-first:before:left-4`. */
    val MessageSeparatorInset = 16.dp

    /** Avatar `3xs` beside the sender (`preview-mini`). */
    val SenderAvatar = 14.dp
}

/**
 * `not-first:before:border-t border-line-hairline`: a 1dp hairline across the top of every
 * row but the first, starting [inset] from the left and running to the right edge.
 */
internal fun Modifier.raftSearchRowSeparator(show: Boolean, inset: Dp, color: Color): Modifier =
    if (!show) {
        this
    } else {
        drawBehind {
            val stroke = 1.dp.toPx()
            drawRect(color, topLeft = Offset(inset.toPx(), 0f), size = Size((size.width - inset.toPx()).coerceAtLeast(0f), stroke))
        }
    }

/** search-entity / search-message `hover:bg-fill-pressed active:bg-fill-pressed`. */
internal fun Modifier.raftSearchRowPressed(pressed: Boolean, color: Color): Modifier =
    if (pressed) background(color) else this

/** `clickable`'s press delay inside a scrolling container (Android's tap timeout). */
internal const val RaftSearchRowPressDelayMillis = 100L

/**
 * The press feedback `clickable` gives a row in a scrolling list, for rows that run their
 * own `detectTapGestures`: [PressInteraction.Press] lands on [interactionSource] only after
 * [delayMillis], so a touch that turns into a scroll (or is otherwise cancelled) before then
 * never flashes the pressed fill; a tap released sooner still gets a Press and Release.
 * [awaitRelease] is the gesture's `tryAwaitRelease` (true on release, false on cancel); the
 * delayed press runs in [scope].
 */
internal suspend fun raftDelayedRowPress(
    offset: Offset,
    interactionSource: MutableInteractionSource,
    scope: CoroutineScope,
    delayMillis: Long = RaftSearchRowPressDelayMillis,
    awaitRelease: suspend () -> Boolean,
) {
    var press: PressInteraction.Press? = null
    val delayed =
        scope.launch {
            delay(delayMillis)
            val emitted = PressInteraction.Press(offset)
            press = emitted
            interactionSource.emit(emitted)
        }
    var released = false
    try {
        released = awaitRelease()
    } finally {
        if (delayed.isActive) {
            delayed.cancel()
            if (released) {
                val tap = PressInteraction.Press(offset)
                interactionSource.tryEmit(tap)
                interactionSource.tryEmit(PressInteraction.Release(tap))
            }
        } else {
            press?.let { interactionSource.tryEmit(if (released) PressInteraction.Release(it) else PressInteraction.Cancel(it)) }
        }
    }
}

/**
 * search `sectionHeading`: `px-4 pt-5 pb-2 text-footnote font-bold text-foreground-hint`.
 * [style] is the designsystem `footnote` in bold (`RaftTheme.typography.footnote`, read by
 * the caller inside RaftDesignSystemTheme).
 */
@Composable
internal fun RaftSearchSectionHeading(
    label: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().padding(start = RaftSearchMetrics.Inset, top = 20.dp, end = RaftSearchMetrics.Inset, bottom = 8.dp)) {
        RaftText(
            text = label,
            style = style,
            color = RaftColor.ForegroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * search `summary`: `px-4 pt-2 text-footnote text-foreground-hint`. [typography] is the
 * designsystem type scale (`RaftTheme.typography`, read by the caller inside
 * RaftDesignSystemTheme).
 */
@Composable
internal fun RaftSearchSummary(text: String, typography: RaftTypography, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(start = RaftSearchMetrics.Inset, top = 8.dp, end = RaftSearchMetrics.Inset)) {
        RaftText(
            text = text,
            style = typography.footnote,
            color = RaftColor.ForegroundHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * raft-ui EmptyState as the search panel uses it (SearchEmpty / SearchError): `px-6 py-12`
 * centred, a 36dp `foreground-hint` icon, a `text-headline` bold `foreground-muted` title,
 * a `text-callout` `foreground-hint` description at most 300dp wide (all 8dp apart) and the
 * actions 20dp below. [typography] is the designsystem type scale (`RaftTheme.typography`,
 * read by the caller inside RaftDesignSystemTheme).
 */
@Composable
internal fun RaftSearchEmptyState(
    title: String,
    detail: String?,
    typography: RaftTypography,
    modifier: Modifier = Modifier,
    iconName: RaftLucideIconName? = RaftLucideIconName.Search,
    actions: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 384.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            iconName?.let {
                RaftLucideIcon(it, modifier = Modifier.size(36.dp), color = raftArgbHex(RaftColor.ForegroundHint))
            }
            RaftText(
                text = title,
                style = typography.headline,
                color = RaftColor.TextMuted,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.takeIf { it.isNotBlank() }?.let {
                RaftText(
                    text = it,
                    style = typography.callout,
                    color = RaftColor.ForegroundHint,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 300.dp),
                )
            }
            if (actions != null) {
                Box(Modifier.padding(top = 12.dp)) { actions() }
            }
        }
    }
}

/**
 * Text with the DS search hit (raft-ui lib/search-hit.ts, stdrc S1) on every [query] term: the
 * hit keeps the surrounding font and weight in `sticker-ink` on a `you` fill (yellow in every
 * theme: [RaftColor.Brand]), sticker radius, no border or ring. The fill is drawn behind the
 * glyphs, so it takes no layout room: web pads it 2px each side and 1px above and below the
 * line box, and here it reaches up to 2dp into a neighbouring space or line edge but never over
 * a neighbouring glyph. Touching hits are already one range ([homeSearchHighlightRanges]), so
 * one fill. Hits hidden behind an ellipsis draw nothing.
 */
@Composable
internal fun RaftSearchHitText(
    text: String,
    query: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    // Only this part of [text] can hold a hit (a row's fixed label stays unmarked); null = all.
    highlightRange: IntRange? = null,
) {
    val ranges = remember(text, query, highlightRange) { homeSearchHighlightRanges(text, query, highlightRange) }
    val annotated = remember(text, ranges) { raftSearchHitAnnotatedString(text, ranges) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val fill = RaftColor.Brand
    RaftText(
        text = annotated,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layout = it },
        modifier =
            if (ranges.isEmpty()) {
                modifier
            } else {
                modifier.drawBehind {
                    val result = layout ?: return@drawBehind
                    if (result.layoutInput.text.text != text) return@drawBehind
                    drawRaftSearchHits(result, ranges, fill)
                }
            },
    )
}

internal fun raftSearchHitAnnotatedString(text: String, ranges: List<IntRange>): AnnotatedString {
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        val hit = SpanStyle(color = RaftColor.StickerInk)
        ranges.forEach { range -> addStyle(hit, start = range.first, end = range.last + 1) }
    }
}

/** web `px-[2px]`: the room the hit fill wants beside the hit glyphs. */
private val RaftSearchHitPadX = 2.dp

/** web `py-px`: fill above and below the line box. */
private val RaftSearchHitPadY = 1.dp

private fun DrawScope.drawRaftSearchHits(
    layout: TextLayoutResult,
    ranges: List<IntRange>,
    fill: Color,
) {
    val radius = CornerRadius(RaftAppRadius.Sticker.toPx())
    val padY = RaftSearchHitPadY.toPx()
    val padX = RaftSearchHitPadX.toPx()
    val layoutWidth = layout.size.width.toFloat()
    val text = layout.layoutInput.text.text
    val textLength = text.length
    // Free room beside a hit: a line edge allows the full pad, a space half its advance (so
    // two hits a space apart never meet), a glyph none (the sticker stays on the hit).
    fun roomBeside(neighbour: Int, lineEdge: Boolean): Float =
        when {
            lineEdge || neighbour !in 0 until textLength -> padX
            text[neighbour].isWhitespace() -> layout.getBoundingBox(neighbour).width / 2f
            else -> 0f
        }
    ranges.forEach { range ->
        val start = range.first.coerceIn(0, textLength)
        val end = (range.last + 1).coerceIn(0, textLength)
        if (start >= end) return@forEach
        val firstLine = layout.getLineForOffset(start)
        val lastLine = minOf(layout.getLineForOffset(end - 1), layout.lineCount - 1)
        for (line in firstLine..lastLine) {
            val ellipsized = layout.isLineEllipsized(line)
            // On an ellipsized line the visible end stops before the ellipsis, so a hit (or the
            // part of one) hidden behind it draws nothing.
            val lineStart = layout.getLineStart(line)
            val visibleEnd = layout.getLineEnd(line, visibleEnd = true)
            val segment = raftSearchHitLineSegment(start, end, lineStart, visibleEnd) ?: continue
            val segmentEnd = segment.last + 1
            // The path covers every glyph run of the slice, so mixed-direction text gets the
            // box round what is actually drawn.
            val glyphs = layout.getPathForRange(segment.first, segmentEnd).getBounds()
            if (glyphs.isEmpty) continue
            val before = roomBeside(segment.first - 1, lineEdge = segment.first == lineStart)
            val after = if (ellipsized && segmentEnd == visibleEnd) 0f else roomBeside(segmentEnd, lineEdge = segmentEnd == visibleEnd)
            val rtl = layout.getParagraphDirection(segment.first) == ResolvedTextDirection.Rtl
            val extent =
                raftSearchHitStickerExtent(
                    glyphLeft = glyphs.left,
                    glyphRight = glyphs.right,
                    roomLeft = if (rtl) after else before,
                    roomRight = if (rtl) before else after,
                    padX = padX,
                    layoutWidth = layoutWidth,
                ) ?: continue
            val top = layout.getLineTop(line)
            val bottom = layout.getLineBottom(line)
            // The whole line box (ascent to descent) plus `py-px`, so descenders ("y", "g") sit inside it.
            val box = Rect(extent.start, top - padY, extent.endInclusive, bottom + padY)
            drawRoundRect(fill, topLeft = box.topLeft, size = box.size, cornerRadius = radius)
        }
    }
}

/**
 * The slice `[rangeStart, rangeEnd)` of a hit that shows on a line running from [lineStart]
 * to [lineVisibleEnd] (exclusive; on an ellipsized line, where the ellipsis starts), or null
 * when none of it shows there.
 */
internal fun raftSearchHitLineSegment(rangeStart: Int, rangeEnd: Int, lineStart: Int, lineVisibleEnd: Int): IntRange? {
    val start = maxOf(rangeStart, lineStart)
    val end = minOf(rangeEnd, lineVisibleEnd)
    return if (start < end) start until end else null
}

/**
 * Horizontal extent of one sticker: the hit glyphs `[glyphLeft, glyphRight]` grown by [padX]
 * each side but by no more than the free room there ([roomLeft] / [roomRight]), so the
 * sticker never covers a neighbouring glyph, then clamped to the text's `0..layoutWidth`.
 * Null when nothing is left, i.e. the glyphs sit wholly outside the text box.
 */
internal fun raftSearchHitStickerExtent(
    glyphLeft: Float,
    glyphRight: Float,
    roomLeft: Float,
    roomRight: Float,
    padX: Float,
    layoutWidth: Float,
): ClosedFloatingPointRange<Float>? {
    val left = (glyphLeft - padX.coerceAtMost(roomLeft).coerceAtLeast(0f)).coerceIn(0f, layoutWidth)
    val right = (glyphRight + padX.coerceAtMost(roomRight).coerceAtLeast(0f)).coerceIn(0f, layoutWidth)
    return if (right > left) left..right else null
}
