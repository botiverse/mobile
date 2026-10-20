package build.raft.app.markdown

import androidx.compose.runtime.Immutable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * Boxed inline chrome (inline code chips, reference / self-mention chips, message-permalink chips).
 *
 * Kuikly's Compose fork carried this on `SpanStyle(inlineBoxStyle = ...)` and its text renderer
 * painted (and reserved space for) the box. Upstream Compose has no such span attribute, so the
 * style travels as a string annotation ([RAFT_INLINE_BOX_ANNOTATION_TAG]) over the boxed range and
 * [raftInlineDecorations] paints it behind the text from the [TextLayoutResult].
 *
 * Horizontal padding + margin reserve advance width through gap placeholders that
 * [withRaftInlineChipGaps] puts on either side of the chip (Kuikly widened the run itself), so a
 * chip's painted padding never overlaps a neighbouring glyph or chip, and the margin leaves a
 * visible gap between two adjacent chips (web chips are `inline-flex` with real padding).
 * TODO(cmp-switch): the vertical extent is estimated from the span font size rather than real
 * font metrics.
 */
@Immutable
internal data class RaftInlineBoxStyle(
    val backgroundColor: Color = Color.Unspecified,
    val borderColor: Color = Color.Transparent,
    val borderWidth: Dp = 0.dp,
    val paddingStart: Dp = 0.dp,
    val paddingEnd: Dp = 0.dp,
    val paddingTop: Dp = 0.dp,
    val paddingBottom: Dp = 0.dp,
    val marginStart: Dp = 0.dp,
    val marginEnd: Dp = 0.dp,
    val cornerRadius: Dp = 0.dp,
) {
    fun encode(): String =
        listOf(
            RaftInlineDecorationEncodingVersion,
            backgroundColor.value.toString(),
            borderColor.value.toString(),
            borderWidth.value.toString(),
            paddingStart.value.toString(),
            paddingEnd.value.toString(),
            paddingTop.value.toString(),
            paddingBottom.value.toString(),
            marginStart.value.toString(),
            marginEnd.value.toString(),
            cornerRadius.value.toString(),
        ).joinToString(RaftInlineDecorationSeparator)

    companion object {
        fun decode(value: String): RaftInlineBoxStyle? {
            val parts = value.split(RaftInlineDecorationSeparator)
            if (parts.size != 11 || parts[0] != RaftInlineDecorationEncodingVersion) return null
            return runCatching {
                RaftInlineBoxStyle(
                    backgroundColor = Color(parts[1].toULong()),
                    borderColor = Color(parts[2].toULong()),
                    borderWidth = parts[3].toFloat().dp,
                    paddingStart = parts[4].toFloat().dp,
                    paddingEnd = parts[5].toFloat().dp,
                    paddingTop = parts[6].toFloat().dp,
                    paddingBottom = parts[7].toFloat().dp,
                    marginStart = parts[8].toFloat().dp,
                    marginEnd = parts[9].toFloat().dp,
                    cornerRadius = parts[10].toFloat().dp,
                )
            }.getOrNull()
        }
    }
}

/**
 * Custom underline (colour, thickness and offset below the baseline). Kuikly's SpanStyle had
 * `textDecorationColor/Thickness/Offset`; upstream only draws a text-coloured hairline, so the
 * reference underline is painted by [raftInlineDecorations] instead.
 */
@Immutable
internal data class RaftInlineUnderlineStyle(
    val color: Color,
    val thickness: TextUnit = 1.sp,
    val offset: TextUnit = 0.sp,
) {
    fun encode(): String =
        listOf(
            RaftInlineDecorationEncodingVersion,
            color.value.toString(),
            (if (thickness.isSpecified) thickness.value else 1f).toString(),
            (if (offset.isSpecified) offset.value else 0f).toString(),
        ).joinToString(RaftInlineDecorationSeparator)

    companion object {
        fun decode(value: String): RaftInlineUnderlineStyle? {
            val parts = value.split(RaftInlineDecorationSeparator)
            if (parts.size != 4 || parts[0] != RaftInlineDecorationEncodingVersion) return null
            return runCatching {
                RaftInlineUnderlineStyle(
                    color = Color(parts[1].toULong()),
                    thickness = parts[2].toFloat().sp,
                    offset = parts[3].toFloat().sp,
                )
            }.getOrNull()
        }
    }
}

internal const val RAFT_INLINE_BOX_ANNOTATION_TAG = "raft.build.markdown.inlineBox"
internal const val RAFT_INLINE_UNDERLINE_ANNOTATION_TAG = "raft.build.markdown.inlineUnderline"
private const val RaftInlineDecorationEncodingVersion = "v1"
private const val RaftInlineDecorationSeparator = "|"

internal inline fun <R : Any> AnnotatedString.Builder.withRaftInlineBox(
    style: RaftInlineBoxStyle?,
    block: AnnotatedString.Builder.() -> R
): R {
    if (style == null) return block()
    val index = pushStringAnnotation(RAFT_INLINE_BOX_ANNOTATION_TAG, style.encode())
    return try {
        block()
    } finally {
        pop(index)
    }
}

/**
 * Reserves [style]'s start/end padding + margin around one whole chip ([block] — its box, link
 * and click annotations) as gap placeholders, glued to the chip with word joiners so a gap never
 * wraps away from it. The painter draws the padding into the gaps and leaves the margin clear.
 * The gaps sit outside every chip annotation (box, link, tag), so those ranges stay the label.
 * Placeholders resolve through [raftInlineGapInlineContent]; [raftTextWithoutInlineGaps] drops them.
 */
internal inline fun <R : Any> AnnotatedString.Builder.withRaftInlineChipGaps(
    style: RaftInlineBoxStyle?,
    block: AnnotatedString.Builder.() -> R
): R {
    if (style == null) return block()
    val leadingGap = style.raftInlineLeadingReservedWidth()
    val trailingGap = style.raftInlineTrailingReservedWidth()
    if (leadingGap > 0.dp) {
        appendRaftInlineGap(leadingGap)
        append(RaftInlineWordJoiner)
    }
    val result = block()
    if (trailingGap > 0.dp) {
        append(RaftInlineWordJoiner)
        appendRaftInlineGap(trailingGap)
    }
    return result
}

internal const val RaftInlineWordJoiner = "\u2060"
internal const val RaftInlineGapInlineIdPrefix = "raft-inline-gap:"

/** Advance width reserved before a boxed range: start padding plus start margin. */
internal fun RaftInlineBoxStyle.raftInlineLeadingReservedWidth(): Dp = paddingStart + marginStart

/** Advance width reserved after a boxed range: end padding plus end margin. */
internal fun RaftInlineBoxStyle.raftInlineTrailingReservedWidth(): Dp = paddingEnd + marginEnd

internal fun raftInlineGapInlineId(width: Dp): String = RaftInlineGapInlineIdPrefix + width.value

internal fun AnnotatedString.Builder.appendRaftInlineGap(width: Dp) {
    appendInlineContent(raftInlineGapInlineId(width), "\uFFFC")
}

/** Width a gap placeholder id reserves, or null when [id] is not a gap. */
internal fun raftInlineGapWidth(id: String): Dp? =
    id.takeIf { it.startsWith(RaftInlineGapInlineIdPrefix) }
        ?.removePrefix(RaftInlineGapInlineIdPrefix)
        ?.toFloatOrNull()
        ?.takeIf { it > 0f }
        ?.dp

/**
 * The text without reserved chip gaps (each gap placeholder and the word joiner gluing it to its
 * chip): exactly the characters the source rendered.
 */
internal fun AnnotatedString.raftTextWithoutInlineGaps(): String {
    val gaps =
        getStringAnnotations(RaftInlineContentAnnotationTag, 0, length)
            .filter { it.item.startsWith(RaftInlineGapInlineIdPrefix) }
    if (gaps.isEmpty()) return text
    val drop = BooleanArray(length)
    gaps.forEach { gap ->
        for (i in gap.start until gap.end) drop[i] = true
        if (gap.end < length && text[gap.end] == '\u2060') drop[gap.end] = true
        if (gap.start > 0 && text[gap.start - 1] == '\u2060') drop[gap.start - 1] = true
    }
    return buildString(length) { text.forEachIndexed { i, c -> if (!drop[i]) append(c) } }
}

private const val RaftInlineContentAnnotationTag = "androidx.compose.foundation.text.inlineContent"

/** Empty spacer content for every gap placeholder id in [ids]. */
internal fun raftInlineGapInlineContent(ids: Collection<String>): Map<String, InlineTextContent> {
    var result: MutableMap<String, InlineTextContent>? = null
    ids.forEach { id ->
        val width = raftInlineGapWidth(id) ?: return@forEach
        val map = result ?: LinkedHashMap<String, InlineTextContent>().also { result = it }
        map[id] =
            InlineTextContent(
                placeholder =
                    Placeholder(
                        width = width.value.sp,
                        height = 1.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    )
            ) {}
    }
    return result ?: emptyMap()
}

internal inline fun <R : Any> AnnotatedString.Builder.withRaftInlineUnderline(
    style: RaftInlineUnderlineStyle?,
    block: AnnotatedString.Builder.() -> R
): R {
    if (style == null) return block()
    val index = pushStringAnnotation(RAFT_INLINE_UNDERLINE_ANNOTATION_TAG, style.encode())
    return try {
        block()
    } finally {
        pop(index)
    }
}

internal fun AnnotatedString.raftInlineBoxRanges(): List<AnnotatedString.Range<RaftInlineBoxStyle>> =
    getStringAnnotations(RAFT_INLINE_BOX_ANNOTATION_TAG, 0, length).mapNotNull { range ->
        RaftInlineBoxStyle.decode(range.item)?.let { AnnotatedString.Range(it, range.start, range.end) }
    }

internal fun AnnotatedString.raftInlineUnderlineRanges(): List<AnnotatedString.Range<RaftInlineUnderlineStyle>> =
    getStringAnnotations(RAFT_INLINE_UNDERLINE_ANNOTATION_TAG, 0, length).mapNotNull { range ->
        RaftInlineUnderlineStyle.decode(range.item)?.let { AnnotatedString.Range(it, range.start, range.end) }
    }

/** Pre-decoded decoration ranges of one text, plus the per-offset font size used for geometry. */
@Immutable
internal class RaftInlineDecorations(
    val boxes: List<AnnotatedString.Range<RaftInlineBoxStyle>>,
    val underlines: List<AnnotatedString.Range<RaftInlineUnderlineStyle>>,
    private val fontSizes: List<AnnotatedString.Range<TextUnit>>,
    private val baseFontSize: TextUnit,
) {
    val isEmpty: Boolean get() = boxes.isEmpty() && underlines.isEmpty()

    fun fontSizeAt(offset: Int): TextUnit =
        fontSizes.lastOrNull { offset >= it.start && offset < it.end }?.item ?: baseFontSize

    companion object {
        val Empty = RaftInlineDecorations(emptyList(), emptyList(), emptyList(), 14.sp)
    }
}

internal fun AnnotatedString.raftInlineDecorations(baseFontSize: TextUnit): RaftInlineDecorations {
    val boxes = raftInlineBoxRanges()
    val underlines = raftInlineUnderlineRanges()
    if (boxes.isEmpty() && underlines.isEmpty()) return RaftInlineDecorations.Empty
    val fontSizes =
        spanStyles
            .filter { it.item.fontSize.isSpecified }
            .map { AnnotatedString.Range(it.item.fontSize, it.start, it.end) }
    return RaftInlineDecorations(
        boxes = boxes,
        underlines = underlines,
        fontSizes = fontSizes,
        baseFontSize = if (baseFontSize.isSpecified) baseFontSize else 14.sp,
    )
}

/**
 * Paints [RaftInlineBoxStyle] boxes and [RaftInlineUnderlineStyle] underlines behind the text.
 * Apply as the innermost modifier of the Text so the draw coordinates match the text layout.
 */
internal fun Modifier.raftInlineDecorations(
    decorations: RaftInlineDecorations,
    layout: () -> TextLayoutResult?
): Modifier {
    if (decorations.isEmpty) return this
    return drawBehind {
        val result = layout() ?: return@drawBehind
        val textLength = result.layoutInput.text.length
        decorations.boxes.forEach { range ->
            drawRaftInlineRange(result, range.start, range.end, textLength) { segment ->
                drawRaftInlineBoxSegment(result, decorations, range.item, segment)
            }
        }
        decorations.underlines.forEach { range ->
            drawRaftInlineRange(result, range.start, range.end, textLength) { segment ->
                val style = range.item
                val thickness = style.thickness.toPx().coerceAtLeast(1f)
                val y = result.getLineBaseline(segment.line) + style.offset.toPx()
                drawRect(
                    color = style.color,
                    topLeft = Offset(segment.left, y),
                    size = Size(segment.right - segment.left, thickness),
                )
            }
        }
    }
}

private class RaftInlineSegment(
    val line: Int,
    val start: Int,
    val end: Int,
    val left: Float,
    val right: Float,
    val isFirst: Boolean,
    val isLast: Boolean,
)

private inline fun drawRaftInlineRange(
    result: TextLayoutResult,
    start: Int,
    end: Int,
    textLength: Int,
    draw: (RaftInlineSegment) -> Unit
) {
    val rangeStart = start.coerceIn(0, textLength)
    val rangeEnd = end.coerceIn(rangeStart, textLength)
    if (rangeEnd <= rangeStart) return
    val firstLine = result.getLineForOffset(rangeStart)
    val lastLine = result.getLineForOffset(rangeEnd - 1)
    for (line in firstLine..lastLine) {
        if (line >= result.lineCount) break
        val segmentStart = maxOf(rangeStart, result.getLineStart(line))
        val segmentEnd = minOf(rangeEnd, result.getLineEnd(line, visibleEnd = true))
        if (segmentEnd <= segmentStart) continue
        val first = result.getBoundingBox(segmentStart)
        val last = result.getBoundingBox(segmentEnd - 1)
        val left = minOf(first.left, last.left)
        val right = maxOf(first.right, last.right)
        if (right <= left) continue
        draw(
            RaftInlineSegment(
                line = line,
                start = segmentStart,
                end = segmentEnd,
                left = left,
                right = right,
                isFirst = segmentStart == rangeStart,
                isLast = segmentEnd == rangeEnd,
            )
        )
    }
}

private fun DrawScope.drawRaftInlineBoxSegment(
    result: TextLayoutResult,
    decorations: RaftInlineDecorations,
    style: RaftInlineBoxStyle,
    segment: RaftInlineSegment,
) {
    val fontSizePx = decorations.fontSizeAt(segment.start).toPx()
    val baseline = result.getLineBaseline(segment.line)
    // Approximate ascent/descent of the UI fonts; see the TODO(cmp-switch) on RaftInlineBoxStyle.
    val glyphTop = baseline - fontSizePx * 0.92f
    val glyphBottom = baseline + fontSizePx * 0.26f
    val left = segment.left - if (segment.isFirst) style.paddingStart.toPx() else 0f
    val right = segment.right + if (segment.isLast) style.paddingEnd.toPx() else 0f
    val top = glyphTop - style.paddingTop.toPx()
    val bottom = glyphBottom + style.paddingBottom.toPx()
    val width = right - left
    val height = bottom - top
    if (width <= 0f || height <= 0f) return
    val radius = CornerRadius(style.cornerRadius.toPx().coerceAtMost(minOf(width, height) / 2f))
    if (style.backgroundColor.isSpecified && style.backgroundColor.alpha > 0f) {
        drawRoundRect(
            color = style.backgroundColor,
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = radius,
        )
    }
    val borderPx = style.borderWidth.toPx()
    if (borderPx > 0f && style.borderColor.isSpecified && style.borderColor.alpha > 0f) {
        val inset = borderPx / 2f
        drawRoundRect(
            color = style.borderColor,
            topLeft = Offset(left + inset, top + inset),
            size = Size((width - borderPx).coerceAtLeast(0f), (height - borderPx).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((radius.x - inset).coerceAtLeast(0f)),
            style = Stroke(width = borderPx),
        )
    }
}
