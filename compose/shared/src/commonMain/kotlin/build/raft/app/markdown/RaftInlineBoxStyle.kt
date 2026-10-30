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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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
 * link underline is painted by [raftInlineDecorations] instead.
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

/**
 * A mark the painter draws inside a chip's leading spacer ([appendRaftInlineSpacer]): the task
 * chip's status pixel (raft-ui `TaskStatusPixel`), a [size] square [inset] from the spacer's start,
 * filled and edged, vertically centred on the chip box. Painted rather than placed as inline
 * content so the chip's lead stays text and never wraps away from its label.
 */
@Immutable
internal data class RaftInlineMarkStyle(
    val fill: Color,
    val stroke: Color,
    val inset: Dp,
    val size: Dp,
    val strokeWidth: Dp,
    val cornerRadius: Dp,
) {
    fun encode(): String =
        listOf(
            RaftInlineDecorationEncodingVersion,
            fill.value.toString(),
            stroke.value.toString(),
            inset.value.toString(),
            size.value.toString(),
            strokeWidth.value.toString(),
            cornerRadius.value.toString(),
        ).joinToString(RaftInlineDecorationSeparator)

    companion object {
        fun decode(value: String): RaftInlineMarkStyle? {
            val parts = value.split(RaftInlineDecorationSeparator)
            if (parts.size != 7 || parts[0] != RaftInlineDecorationEncodingVersion) return null
            return runCatching {
                RaftInlineMarkStyle(
                    fill = Color(parts[1].toULong()),
                    stroke = Color(parts[2].toULong()),
                    inset = parts[3].toFloat().dp,
                    size = parts[4].toFloat().dp,
                    strokeWidth = parts[5].toFloat().dp,
                    cornerRadius = parts[6].toFloat().dp,
                )
            }.getOrNull()
        }
    }
}

internal const val RAFT_INLINE_BOX_ANNOTATION_TAG = "raft.build.markdown.inlineBox"
internal const val RAFT_INLINE_UNDERLINE_ANNOTATION_TAG = "raft.build.markdown.inlineUnderline"
internal const val RAFT_INLINE_MARK_ANNOTATION_TAG = "raft.build.markdown.inlineMark"
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

/**
 * Appends a spacer [width] wide inside a chip box that never wraps away from the label, in the
 * form each text engine actually honours ([asPlaceholder], see
 * [raftPlatformInlineSpacerIsPlaceholder]). [leading]: the spacer precedes the label (the word
 * joiner goes after it), else it follows the label (the joiner goes before it).
 *
 * - Android (Minikin): a gap placeholder glued by a word joiner. Android 15+ Minikin adds no letter
 *   spacing at the edges of a style run, so a one-character letter-spaced run has no advance at
 *   all: the letter-spaced joiner below collapsed to 0 and the chip lost its padding (artin 10-30,
 *   "#3954" clipped). Android's line breaker honours the joiner around a placeholder.
 * - Skia (iOS, desktop): a word joiner whose advance is its letter spacing. Skia's line breaker
 *   makes every placeholder a word of its own and breaks on either side of it, word joiner or
 *   not, so a placeholder lead could end a line while the label wrapped, leaving the chip box as a
 *   sliver at the line end (artin 10-29). Skia letter-spaces a lone joiner.
 *
 * [mark] is painted inside the spacer by [raftInlineDecorations].
 */
internal fun AnnotatedString.Builder.appendRaftInlineSpacer(
    width: Dp,
    leading: Boolean,
    mark: RaftInlineMarkStyle? = null,
    asPlaceholder: Boolean = raftPlatformInlineSpacerIsPlaceholder(),
) {
    if (width <= 0.dp) return
    if (asPlaceholder) {
        if (!leading) append(RaftInlineWordJoiner)
        if (mark != null) pushStringAnnotation(RAFT_INLINE_MARK_ANNOTATION_TAG, mark.encode())
        appendRaftInlineGap(width)
        if (mark != null) pop()
        if (leading) append(RaftInlineWordJoiner)
        return
    }
    if (mark != null) pushStringAnnotation(RAFT_INLINE_MARK_ANNOTATION_TAG, mark.encode())
    withStyle(SpanStyle(letterSpacing = width.value.sp)) { append(RaftInlineWordJoiner) }
    if (mark != null) pop()
}

/**
 * True where in-box chip padding must be a placeholder rather than a letter-spaced word joiner:
 * Android's Minikin (no letter spacing on a one-character run since Android 15). False on Skia
 * (iOS, desktop), whose line breaker splits around placeholders. See [appendRaftInlineSpacer].
 */
internal expect fun raftPlatformInlineSpacerIsPlaceholder(): Boolean

/** Width a gap placeholder id reserves, or null when [id] is not a gap. */
internal fun raftInlineGapWidth(id: String): Dp? =
    id.takeIf { it.startsWith(RaftInlineGapInlineIdPrefix) }
        ?.removePrefix(RaftInlineGapInlineIdPrefix)
        ?.toFloatOrNull()
        ?.takeIf { it > 0f }
        ?.dp

/**
 * Makes a linked chip's reserved gaps tap as the chip (task #wg-mobile:#9).
 *
 * [withRaftInlineChipGaps] reserves a chip's horizontal padding as gap placeholders outside the
 * chip's link, and [raftInlineDecorations] paints the chip box over them. Without this a tap on the
 * painted padding of a chip (a self @mention's yellow sticker, a #channel or task chip, a permalink
 * or App link chip) hit no link and fell through to the message body, which opens the thread. Each
 * gap glued (by a word joiner) to the start or end of a link gets an unstyled copy of that link, so
 * the whole visible chip dispatches the chip's action and only that. The link ranges over the
 * chip's own characters, and their styles, are unchanged.
 */
internal fun AnnotatedString.withRaftInlineChipGapLinks(): AnnotatedString {
    val links = getLinkAnnotations(0, length)
    if (links.isEmpty()) return this
    val gaps =
        getStringAnnotations(RaftInlineContentAnnotationTag, 0, length)
            .filter { it.item.startsWith(RaftInlineGapInlineIdPrefix) && it.end > it.start }
    if (gaps.isEmpty()) return this
    val gapEndingAt = gaps.associateBy { it.end }
    val gapStartingAt = gaps.associateBy { it.start }
    val linked = links.flatMap { range ->
        val leading =
            if (range.start >= 1 && text[range.start - 1] == '⁠') {
                gapEndingAt[range.start - 1]?.let { gap -> gap.start until range.start }
            } else {
                null
            }
        val trailing =
            if (range.end < length && text[range.end] == '⁠') {
                gapStartingAt[range.end + 1]?.let { gap -> range.end until gap.end }
            } else {
                null
            }
        listOfNotNull(leading, trailing).map { flank -> range.item.withoutRaftLinkStyles() to flank }
    }
    if (linked.isEmpty()) return this
    val occupied = links.map { it.start until it.end }
    return AnnotatedString.Builder(this)
        .apply {
            linked.forEach { (link, flank) ->
                // A gap already inside another link (a chip inside a Markdown link) keeps that link.
                if (occupied.any { it.first <= flank.first && flank.last <= it.last }) return@forEach
                when (link) {
                    is LinkAnnotation.Url -> addLink(link, flank.first, flank.last + 1)
                    is LinkAnnotation.Clickable -> addLink(link, flank.first, flank.last + 1)
                }
            }
        }
        .toAnnotatedString()
}

private fun LinkAnnotation.withoutRaftLinkStyles(): LinkAnnotation =
    when (this) {
        is LinkAnnotation.Url ->
            LinkAnnotation.Url(url = url, styles = RaftUnstyledLinkStyles, linkInteractionListener = linkInteractionListener)
        is LinkAnnotation.Clickable ->
            LinkAnnotation.Clickable(tag = tag, styles = RaftUnstyledLinkStyles, linkInteractionListener = linkInteractionListener)
        else -> this
    }

/**
 * Link styles that style nothing, for a link that must look like the text under it (a chip's
 * padding gaps, the message body's tap target). Never pass `styles = null` to a link the markdown
 * renders: Material3 `Text` gives every link whose styles are null its default
 * `colorScheme.primary` + underline look, and with no MaterialTheme in the tree that is the M3
 * baseline purple — a chip's padding showed an underline beside its label (artin 10-29, Compose
 * desktop). An empty, non-null [TextLinkStyles] is left alone by Material3 and by the foundation.
 */
internal val RaftUnstyledLinkStyles: TextLinkStyles = TextLinkStyles()

/**
 * A chip's link styles (mention / channel / thread / task / permalink / App link chips): never
 * underlined in any state — the chip box is the affordance. Hover and focus look as at rest (the
 * foundation applies a null hovered / focused style as "no change", and an inherited underline
 * would survive that); [pressed] keeps the chip's press wash.
 */
internal fun raftChipLinkStyles(style: SpanStyle, pressed: SpanStyle = style): TextLinkStyles {
    val rest = style.copy(textDecoration = TextDecoration.None)
    return TextLinkStyles(
        style = rest,
        focusedStyle = rest,
        hoveredStyle = rest,
        pressedStyle = pressed.copy(textDecoration = TextDecoration.None),
    )
}

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

/**
 * Each chip box's full extent: the boxed label plus the padding reserved around it by
 * [withRaftInlineChipGaps] (a gap placeholder glued by a word joiner) or [appendRaftInlineSpacer]
 * (a letter-spaced word joiner), on either side. A link over the chip's padding as well as its
 * label is still the chip.
 */
internal fun AnnotatedString.raftInlineChipExtents(): List<IntRange> {
    val boxes = raftInlineBoxRanges()
    if (boxes.isEmpty()) return emptyList()
    val gaps =
        getStringAnnotations(RaftInlineContentAnnotationTag, 0, length)
            .filter { it.item.startsWith(RaftInlineGapInlineIdPrefix) && it.end > it.start }
    val gapEndingAt = gaps.associateBy { it.end }
    val gapStartingAt = gaps.associateBy { it.start }
    return boxes.map { box ->
        var start = box.start
        while (start > 0 && text[start - 1] == '\u2060') start--
        gapEndingAt[start]?.let { start = it.start }
        var end = box.end
        while (end < length && text[end] == '\u2060') end++
        gapStartingAt[end]?.let { end = it.end }
        start until end
    }
}

internal fun AnnotatedString.raftInlineMarkRanges(): List<AnnotatedString.Range<RaftInlineMarkStyle>> =
    getStringAnnotations(RAFT_INLINE_MARK_ANNOTATION_TAG, 0, length).mapNotNull { range ->
        RaftInlineMarkStyle.decode(range.item)?.let { AnnotatedString.Range(it, range.start, range.end) }
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
    val marks: List<AnnotatedString.Range<RaftInlineMarkStyle>>,
    private val fontSizes: List<AnnotatedString.Range<TextUnit>>,
    private val baseFontSize: TextUnit,
) {
    val isEmpty: Boolean get() = boxes.isEmpty() && underlines.isEmpty() && marks.isEmpty()

    /**
     * Font size at [offset] in sp, resolving the covering spans in order as Android does: an sp
     * size replaces the size so far, an em size (a 0.875em chip) scales it.
     */
    fun fontSizeAt(offset: Int): TextUnit {
        var size = baseFontSize.value
        fontSizes.forEach { range ->
            if (offset < range.start || offset >= range.end) return@forEach
            val item = range.item
            if (item.isEm) size *= item.value else if (item.isSp) size = item.value
        }
        return size.sp
    }

    companion object {
        val Empty = RaftInlineDecorations(emptyList(), emptyList(), emptyList(), emptyList(), 14.sp)
    }
}

internal fun AnnotatedString.raftInlineDecorations(baseFontSize: TextUnit): RaftInlineDecorations {
    val boxes = raftInlineBoxRanges()
    val underlines = raftInlineUnderlineRanges()
    val marks = raftInlineMarkRanges()
    if (boxes.isEmpty() && underlines.isEmpty() && marks.isEmpty()) return RaftInlineDecorations.Empty
    val fontSizes =
        spanStyles
            .filter { it.item.fontSize.isSpecified }
            .map { AnnotatedString.Range(it.item.fontSize, it.start, it.end) }
    return RaftInlineDecorations(
        boxes = boxes,
        underlines = underlines,
        marks = marks,
        fontSizes = fontSizes,
        baseFontSize = if (baseFontSize.isSp) baseFontSize else 14.sp,
    )
}

/**
 * Paints [RaftInlineBoxStyle] boxes and [RaftInlineUnderlineStyle] underlines behind the text.
 * Apply as the innermost modifier of the Text so the draw coordinates match the text layout.
 */
internal fun Modifier.raftInlineDecorations(
    decorations: RaftInlineDecorations,
    /** An underline range under a mouse or trackpad and the colour it takes then (read at draw time). */
    hovered: () -> Pair<IntRange, Color>? = { null },
    layout: () -> TextLayoutResult?
): Modifier {
    if (decorations.isEmpty) return this
    return drawBehind {
        val result = layout() ?: return@drawBehind
        val textLength = result.layoutInput.text.length
        val text = result.layoutInput.text.text
        decorations.boxes.forEach { range ->
            // A box is painted only on the lines that hold something of the chip: a line fragment
            // of only padding spacers (a lead that ended a line while the label wrapped) would be
            // a sliver of chip at the line end.
            drawRaftInlineRange(result, range.start, range.end, textLength, labelOnly = text) { segment ->
                drawRaftInlineBoxSegment(result, decorations, range.item, segment)
            }
        }
        decorations.marks.forEach { range ->
            drawRaftInlineMark(result, decorations, range.item, range.start, textLength)
        }
        val hover = hovered()
        decorations.underlines.forEach { range ->
            val hoverColor = hover?.takeIf { (link, _) -> link.first == range.start && link.last + 1 == range.end }?.second
            drawRaftInlineRange(result, range.start, range.end, textLength) { segment ->
                val style = range.item
                val thickness = style.thickness.toPx().coerceAtLeast(1f)
                val y = result.getLineBaseline(segment.line) + style.offset.toPx()
                drawRect(
                    color = hoverColor ?: style.color,
                    topLeft = Offset(segment.left, y),
                    size = Size(segment.right - segment.left, thickness),
                )
            }
        }
    }
}

/** One line's share of a decorated range; [isFirst] / [isLast] are relative to the fragments drawn. */
internal data class RaftInlineLineFragment(
    val line: Int,
    val start: Int,
    val end: Int,
    val isFirst: Boolean,
    val isLast: Boolean,
)

/**
 * Splits [start, end) over lines ([lineRanges]: each line's start and visible end). With
 * [labelOnly] (the laid-out text) a fragment holding nothing visible — only word joiners (the
 * chip's padding spacers), zero-width spaces or whitespace — is dropped, and first / last move to
 * the fragments that remain, so a chip box is never painted as a padding-only sliver. A placeholder
 * inside a box (an icon, a busy spinner) is drawn content and keeps its fragment.
 */
internal fun raftInlineLineFragments(
    lineRanges: List<IntRange>,
    start: Int,
    end: Int,
    labelOnly: CharSequence? = null,
): List<RaftInlineLineFragment> {
    if (end <= start) return emptyList()
    val pieces = mutableListOf<Triple<Int, Int, Int>>()
    lineRanges.forEachIndexed { line, lineRange ->
        val segmentStart = maxOf(start, lineRange.first)
        val segmentEnd = minOf(end, lineRange.last + 1)
        if (segmentEnd <= segmentStart) return@forEachIndexed
        if (labelOnly != null && !raftInlineHasLabelChar(labelOnly, segmentStart, segmentEnd)) return@forEachIndexed
        pieces += Triple(line, segmentStart, segmentEnd)
    }
    return pieces.mapIndexed { index, (line, segmentStart, segmentEnd) ->
        RaftInlineLineFragment(
            line = line,
            start = segmentStart,
            end = segmentEnd,
            isFirst = index == 0 && (labelOnly != null || segmentStart == start),
            isLast = index == pieces.lastIndex && (labelOnly != null || segmentEnd == end),
        )
    }
}

/** True when [text] holds, in [start, end), something drawn: a label glyph or an inline-content placeholder. */
internal fun raftInlineHasLabelChar(text: CharSequence, start: Int, end: Int): Boolean {
    for (i in start until minOf(end, text.length)) {
        val c = text[i]
        if (c == '\u2060' || c == '\u200B' || c.isWhitespace()) continue
        return true
    }
    return false
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
    labelOnly: CharSequence? = null,
    draw: (RaftInlineSegment) -> Unit
) {
    val rangeStart = start.coerceIn(0, textLength)
    val rangeEnd = end.coerceIn(rangeStart, textLength)
    if (rangeEnd <= rangeStart) return
    val firstLine = result.getLineForOffset(rangeStart)
    val lastLine = minOf(result.getLineForOffset(rangeEnd - 1), result.lineCount - 1)
    if (lastLine < firstLine) return
    // Lines outside [firstLine, lastLine] are passed empty so the fragment line indices stay real.
    val lineRanges =
        List(lastLine + 1) { line ->
            if (line < firstLine) IntRange.EMPTY
            else result.getLineStart(line) until result.getLineEnd(line, visibleEnd = true)
        }
    raftInlineLineFragments(lineRanges, rangeStart, rangeEnd, labelOnly).forEach { fragment ->
        val first = result.getBoundingBox(fragment.start)
        val last = result.getBoundingBox(fragment.end - 1)
        val left = minOf(first.left, last.left)
        val right = maxOf(first.right, last.right)
        if (right <= left) return@forEach
        draw(
            RaftInlineSegment(
                line = fragment.line,
                start = fragment.start,
                end = fragment.end,
                left = left,
                right = right,
                isFirst = fragment.isFirst,
                isLast = fragment.isLast,
            )
        )
    }
}

/** Approximate ascent / descent of the UI fonts; see the TODO(cmp-switch) on RaftInlineBoxStyle. */
private const val RaftInlineGlyphAscentEm = 0.92f
private const val RaftInlineGlyphDescentEm = 0.26f

private fun DrawScope.drawRaftInlineMark(
    result: TextLayoutResult,
    decorations: RaftInlineDecorations,
    style: RaftInlineMarkStyle,
    offset: Int,
    textLength: Int,
) {
    if (offset !in 0 until textLength) return
    val line = result.getLineForOffset(offset)
    val spacer = result.getBoundingBox(offset)
    val fontSizePx = decorations.fontSizeAt(offset).toPx()
    val baseline = result.getLineBaseline(line)
    // Centred on the chip box the painter draws around the same text.
    val centerY = baseline - fontSizePx * (RaftInlineGlyphAscentEm - RaftInlineGlyphDescentEm) / 2f
    val sizePx = style.size.toPx()
    val strokePx = style.strokeWidth.toPx()
    val inset = strokePx / 2f
    val topLeft = Offset(spacer.left + style.inset.toPx() + inset, centerY - sizePx / 2f + inset)
    val rectSize = Size(sizePx - strokePx, sizePx - strokePx)
    val radius = CornerRadius(style.cornerRadius.toPx())
    drawRoundRect(color = style.fill, topLeft = topLeft, size = rectSize, cornerRadius = radius)
    if (strokePx > 0f) {
        drawRoundRect(color = style.stroke, topLeft = topLeft, size = rectSize, cornerRadius = radius, style = Stroke(width = strokePx))
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
    val glyphTop = baseline - fontSizePx * RaftInlineGlyphAscentEm
    val glyphBottom = baseline + fontSizePx * RaftInlineGlyphDescentEm
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
