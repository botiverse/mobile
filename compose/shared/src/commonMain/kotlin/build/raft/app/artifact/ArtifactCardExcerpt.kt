package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.data.ArtifactExcerpt
import build.raft.app.data.ArtifactExcerptBlock
import build.raft.app.data.ArtifactExcerptListItem
import build.raft.app.data.ArtifactExcerptSpan
import build.raft.app.resources.*
import build.raft.app.thread.ArtifactCardScreenshotMaxHeight
import build.raft.app.thread.icon
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.text.forCjkLineBox
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import kotlin.math.roundToInt

// web `ArtifactCardExcerpt.tsx` (botiverse/slock staging, #13445 / #13472 / #13488): a Markdown
// version's excerpt where a message card's picture goes, on the card's own surface, so it reads as
// the page's start. Its first blocks, small, with their hierarchy, or its plain text for a version
// from before them or blocks this client can't read. Only ever text: no link, image or input.

/** web `max-h-48`: the picture's frame in a message. */
internal val ArtifactCardExcerptMaxHeight: Dp = ArtifactCardScreenshotMaxHeight

/**
 * In a message the words follow their height, at most what the 192dp picture frame held less its
 * padding (web `--excerpt-max: 168px`, slock #17371), and no minimum.
 */
internal val ArtifactCardExcerptMessageMaxHeight: Dp = ArtifactCardExcerptMaxHeight - 12.dp * 2

/**
 * On a phone a message card's excerpt is at most this many lines of its body type (#proj-mobile:#439,
 * web `max-md:[--excerpt-max:72px]`); a Server flag may set it later, within
 * [ArtifactCardExcerptLinesRange] (Kirby).
 */
internal const val ArtifactCardExcerptDefaultLines: Int = 4

/** The lines a phone's excerpt may be set to ([ArtifactCardExcerptDefaultLines]). */
internal val ArtifactCardExcerptLinesRange: IntRange = 2..8

/** The excerpt's body line (`ExcerptType.body`, 18sp). */
private val ArtifactCardExcerptLineHeight: Dp = 18.dp

/** A phone's excerpt height for [lines] lines, clamped to [ArtifactCardExcerptLinesRange]. */
internal fun artifactCardExcerptCompactMaxHeight(lines: Int): Dp = ArtifactCardExcerptLineHeight * lines.coerceIn(ArtifactCardExcerptLinesRange)

/** web `mask-fade-b-edge-at-max`: the fade's length at the foot once the words reach the max. */
private val ArtifactCardExcerptFade: Dp = 24.dp

/** web `p-3`: inner padding around excerpt words. */
private val ArtifactCardExcerptPadding: Dp = 12.dp

/** web `top-1.5 right-1.5` / `-mt-1.5 -mr-1.5`: top-right inset of corner badges on a tile cover. */
private val ArtifactCardExcerptCornerInset: Dp = 6.dp

/** web `ml-2`: horizontal breathing room between wrapped excerpt words and the top-right corner group. */
private val ArtifactCardExcerptCornerGapStart: Dp = 8.dp

/** web `mb-1`: vertical breathing room between the corner group and full-width text below it. */
private val ArtifactCardExcerptCornerGapBottom: Dp = 4.dp

/** Minimum remaining width (~2em of 12sp body) before text drops cleanly below a wide multi-badge group. */
private val ArtifactCardExcerptMinBesideWidth: Dp = 24.dp
private val ArtifactCardExcerptMinCodeBesideWidth: Dp = 48.dp
private val ArtifactCardExcerptMinTableBesideWidth: Dp = 64.dp

/** The thumbnail's own type scale (12/18, 15/20, 14/20, 11/16; Kirby, #13472): a page drawn small, fixed on purpose. */
private object ExcerptType {
    val body = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
    val heading1 = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val heading2 = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val smallHeading = body.copy(fontWeight = FontWeight.SemiBold)
    val code = TextStyle(fontSize = 11.sp, lineHeight = 16.sp)
}

/**
 * The excerpt's frame:
 * - [fitFrame] = false (web `fit="content"`, message cards): no frame of its own (the card sets it
 *   12dp in under the title); height follows the words up to [ArtifactCardExcerptMessageMaxHeight]
 *   (on a phone, [compact]: [compactMaxLines] lines, [artifactCardExcerptCompactMaxHeight]), fading at its foot
 *   once past the max.
 * - [fitFrame] = true (web `fit="frame"`, home tiles): fills its parent 16:10 frame on `layerCard`,
 *   clipped and fading at its foot; the caller's cover frame draws the hairline. When [corner] is
 *   supplied (web slock #16751), it sits at top-right (`6.dp` inset, matching screenshot covers),
 *   outside the fade and `clearAndSetSemantics`, and the opening lines wrap around it instead of
 *   rendering underneath it.
 */
@Composable
internal fun ArtifactCardExcerpt(
    excerpt: ArtifactExcerpt,
    modifier: Modifier = Modifier,
    fitFrame: Boolean = false,
    corner: (@Composable () -> Unit)? = null,
    compact: Boolean = false,
    compactMaxLines: Int = ArtifactCardExcerptDefaultLines,
) {
    val colors = RaftTheme.colors
    val textStyle = ExcerptType.body.copy(color = colors.foreground, fontFamily = RaftTheme.typography.callout.fontFamily)
    if (fitFrame && corner != null) {
        ArtifactCardExcerptWithCorner(
            excerpt = excerpt,
            baseStyle = textStyle,
            corner = corner,
            modifier = modifier,
        )
        return
    }
    val content: @Composable () -> Unit = {
        val blocks = excerpt.blocks
        if (blocks != null) {
            Column(Modifier.fillMaxWidth()) {
                blocks.forEachIndexed { index, block -> ExcerptBlock(block, first = index == 0, base = textStyle) }
            }
        } else {
            RaftText(excerpt.text, style = textStyle)
        }
    }
    if (fitFrame) {
        Box(
            modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(colors.layerCard)
                .padding(ArtifactCardExcerptPadding)
                .excerptFade(alwaysFade = true)
                .clearAndSetSemantics {}
                .testTag(if (excerpt.blocks != null) "artifact-card-excerpt-blocks" else "artifact-card-excerpt-text"),
        ) {
            content()
        }
        return
    }
    Column(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics {}
            .testTag(if (excerpt.blocks != null) "artifact-card-excerpt-blocks" else "artifact-card-excerpt-text"),
    ) {
        ArtifactCardExcerptFrame(
            Modifier.fillMaxWidth(),
            padding = 0.dp,
            minHeight = 0.dp,
            maxHeight = if (compact) artifactCardExcerptCompactMaxHeight(compactMaxLines) else ArtifactCardExcerptMessageMaxHeight,
            content = content,
        )
    }
}

/** A heading's words as compared with a title: whitespace collapsed, case folded (web `comparable`). */
private fun String.comparableTitle(): String = trim().replace(Regex("\\s+"), " ").lowercase()

/**
 * The excerpt as a message card shows it under its title (#proj-mobile:#439, web slock #17371
 * `excerptWithoutTitle`): the title row already says the title. With [ArtifactExcerpt.blocks], a
 * level-1 heading at the start goes, whatever it says, and the first line of the text with it;
 * plain text from before blocks has no headings, so its first line goes when it repeats [title].
 * Null when nothing is left: the card then shows no excerpt.
 */
internal fun ArtifactExcerpt.withoutLeadingTitle(title: String): ArtifactExcerpt? {
    val blocks = blocks
    val first = blocks?.firstOrNull()
    val dropFirstLine =
        if (blocks != null) {
            first is ArtifactExcerptBlock.Heading && first.level == 1
        } else {
            val comparable = title.comparableTitle()
            comparable.isNotEmpty() && text.substringBefore('\n').comparableTitle() == comparable
        }
    val rest = if (!dropFirstLine) text else text.substringAfter('\n', "")
    if (blocks != null) {
        val kept = if (dropFirstLine) blocks.drop(1) else blocks
        return if (kept.isEmpty()) null else if (dropFirstLine) ArtifactExcerpt(rest, kept) else this
    }
    return if (rest.isBlank()) null else if (dropFirstLine) ArtifactExcerpt(rest) else this
}

/**
 * Exclusion zone (in word-box coordinates, origin at `(padPx, padPx)`) carved out by the top-right
 * corner badge group. Lines whose top `< exclusionHeight` wrap within [besideWidth]; once a line
 * reaches `y >= exclusionHeight` (or a word cannot fit whole in [besideWidth]), layout continues at
 * full inner width below the corner.
 */
internal data class ExcerptCornerExclusion(
    val besideWidth: Int,
    val exclusionHeight: Int,
)

internal fun computeExcerptCornerExclusion(
    frameWidth: Int,
    padPx: Int,
    cornerInsetPx: Int,
    gapStartPx: Int,
    gapBottomPx: Int,
    minBesideWidthPx: Int,
    cornerWidth: Int,
    cornerHeight: Int,
): ExcerptCornerExclusion {
    val innerWidth = (frameWidth - 2 * padPx).coerceAtLeast(0)
    if (cornerWidth <= 0 || cornerHeight <= 0) {
        return ExcerptCornerExclusion(besideWidth = innerWidth, exclusionHeight = 0)
    }
    val rawBesideWidth = ((frameWidth - cornerInsetPx - cornerWidth - gapStartPx) - padPx).coerceAtLeast(0)
    val besideWidth = if (rawBesideWidth < minBesideWidthPx) 0 else rawBesideWidth.coerceAtMost(innerWidth)
    val exclusionHeight = (cornerInsetPx + cornerHeight + gapBottomPx - padPx).coerceAtLeast(0)
    return ExcerptCornerExclusion(besideWidth = besideWidth, exclusionHeight = exclusionHeight)
}

private const val ExcerptCjkClosingPunctuation = "。，、！？：；）》」』】〉〕．％·…—～"
private const val ExcerptCjkOpeningPunctuation = "（《「『【〈〔"

private fun Char.isExcerptCjkWrapChar(): Boolean =
    this in '\u1100'..'\u11FF' ||
        this in '\u2E80'..'\u9FFF' ||
        this in '\uA960'..'\uA97F' ||
        this in '\uAC00'..'\uD7FF' ||
        this in '\uF900'..'\uFAFF' ||
        this in '\uFE10'..'\uFE6F' ||
        this in '\uFF00'..'\uFFEF'

/**
 * Whether [lineEnd] (from `TextLayoutResult.getLineEnd(0, visibleEnd = false)`) represents a
 * whole-word break (`wrap-normal`) rather than a mid-word cut forced because the first token on the
 * line overflowed the narrow width beside the corner badges.
 */
internal fun isCleanExcerptWrapBoundary(
    remaining: CharSequence,
    lineEnd: Int,
    visibleEnd: Int,
): Boolean {
    if (visibleEnd <= 0 || lineEnd <= 0) return false
    if (lineEnd >= remaining.length) return true
    val prev = remaining[lineEnd - 1]
    val next = remaining[lineEnd]
    if (prev == ' ' || prev == '\t' || prev == '\n' || prev == '\r') return true
    if (next == ' ' || next == '\t' || next == '\n' || next == '\r') return true
    if (visibleEnd < lineEnd) return true
    if (prev == '-' && visibleEnd > 1) return true
    if (prev.isExcerptCjkWrapChar() || next.isExcerptCjkWrapChar()) {
        return next !in ExcerptCjkClosingPunctuation && prev !in ExcerptCjkOpeningPunctuation
    }
    return false
}

/**
 * Advances past the wrapped line ending at [lineEnd], normalizing Android (`StaticLayout`, includes
 * trailing `\n`) and Skia (iOS/Desktop, stops before `\n`) while preserving blank lines (`\n\n`)
 * and stripping horizontal spaces that caused a soft wrap.
 */
internal fun advancePastExcerptWrappedLine(
    remaining: CharSequence,
    lineEnd: Int,
): Int {
    val clamped = lineEnd.coerceIn(0, remaining.length)
    if (clamped >= remaining.length) return remaining.length
    if (clamped > 0 && (remaining[clamped - 1] == '\n' || remaining[clamped - 1] == '\r')) {
        return clamped
    }
    if (remaining[clamped] == '\r' || remaining[clamped] == '\n') {
        return advancePastSingleLineBreak(remaining, clamped)
    }
    var next = clamped
    while (next < remaining.length && (remaining[next] == ' ' || remaining[next] == '\t')) {
        next++
    }
    if (next < remaining.length && (remaining[next] == '\r' || remaining[next] == '\n')) {
        return advancePastSingleLineBreak(remaining, next)
    }
    return next
}

private sealed interface PreparedExcerptItem {
    val first: Boolean
    val heading: Boolean get() = false

    data class TextBlock(
        val text: AnnotatedString,
        val style: TextStyle,
        override val first: Boolean,
        override val heading: Boolean = false,
    ) : PreparedExcerptItem

    data class QuoteBlock(
        val text: AnnotatedString,
        val style: TextStyle,
        override val first: Boolean,
    ) : PreparedExcerptItem

    data class ListBlock(
        val items: List<PreparedListItem>,
        override val first: Boolean,
    ) : PreparedExcerptItem

    data class CodeBlock(
        val text: String,
        val style: TextStyle,
        override val first: Boolean,
    ) : PreparedExcerptItem

    data class TableBlock(
        val auxIndex: Int,
        override val first: Boolean,
    ) : PreparedExcerptItem

    data class LabelBlock(
        val auxIndex: Int,
        val text: AnnotatedString,
        val style: TextStyle,
        override val first: Boolean,
    ) : PreparedExcerptItem

    data class RuleBlock(
        override val first: Boolean,
    ) : PreparedExcerptItem
}

private data class PreparedListItem(
    val level: Int,
    val order: Long?,
    val checked: Boolean?,
    val checkAuxIndex: Int?,
    val text: AnnotatedString,
    val style: TextStyle,
)

private sealed interface ExcerptDrawOp {
    data class Text(
        val result: TextLayoutResult,
        val x: Int,
        val y: Int,
        val clipWidth: Int,
    ) : ExcerptDrawOp

    data class Rect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val color: Color,
    ) : ExcerptDrawOp

    data class Circle(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val color: Color,
    ) : ExcerptDrawOp

    data class Checkbox(
        val x: Int,
        val y: Int,
        val size: Int,
        val radius: Float,
        val checked: Boolean,
        val strokeWidth: Float,
        val fillColor: Color,
        val borderColor: Color,
    ) : ExcerptDrawOp

    data class CodeBox(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val radius: Float,
        val padH: Int,
        val padV: Int,
        val background: Color,
        val text: TextLayoutResult,
    ) : ExcerptDrawOp
}

private data class ExcerptAuxPlacement(
    val placeable: Placeable,
    val logicalX: Int,
    val y: Int,
)

private data class ExcerptWrappedPlan(
    val padPx: Int,
    val innerWidth: Int,
    val innerHeight: Int,
    val drawOps: List<ExcerptDrawOp>,
    val auxPlacements: List<ExcerptAuxPlacement>,
)

private class ExcerptWrappedPlanHolder {
    var plan: ExcerptWrappedPlan? = null
}

private data class WrappedTextLayout(
    val firstLineTop: Int,
    val bottomY: Int,
)

/**
 * Single-pass `Layout` (no `SubcomposeLayout`, safe inside `IntrinsicSize.Max` grid rows) that
 * measures [corner] first at its top-right `6.dp` inset, wraps every Markdown excerpt block around
 * the corner's exclusion rect, fades the bottom `24.dp` of words + auxiliary elements, and places
 * [corner] on top outside the fade and outside `clearAndSetSemantics`.
 */
@Composable
private fun ArtifactCardExcerptWithCorner(
    excerpt: ArtifactExcerpt,
    baseStyle: TextStyle,
    corner: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val codeFamily = RaftTheme.typography.monoCode.fontFamily ?: androidx.compose.ui.text.font.FontFamily.Monospace
    val codeBackground = colors.fillMuted
    val auxComposables = mutableListOf<@Composable () -> Unit>()
    val preparedItems = mutableListOf<PreparedExcerptItem>()
    val blocks = excerpt.blocks
    if (blocks == null) {
        val annotated = AnnotatedString(excerpt.text)
        preparedItems += PreparedExcerptItem.TextBlock(annotated, baseStyle.forCjkLineBox(annotated), first = true)
    } else {
        blocks.forEachIndexed { index, block ->
            val first = index == 0
            when (block) {
                is ArtifactExcerptBlock.Heading -> {
                    val headingStyle =
                        when (block.level) {
                            1 -> ExcerptType.heading1
                            2 -> ExcerptType.heading2
                            else -> ExcerptType.smallHeading
                        }
                    val annotated = excerptWords(block.spans, codeBackground, codeFamily)
                    // common-map-compat: allow-nonmap-merge (TextStyle.merge)
                    preparedItems += PreparedExcerptItem.TextBlock(annotated, baseStyle.merge(headingStyle).forCjkLineBox(annotated), first = first, heading = true)
                }
                is ArtifactExcerptBlock.Paragraph -> {
                    val annotated = excerptWords(block.spans, codeBackground, codeFamily)
                    preparedItems += PreparedExcerptItem.TextBlock(annotated, baseStyle.forCjkLineBox(annotated), first = first)
                }
                is ArtifactExcerptBlock.Quote -> {
                    val annotated = excerptWords(block.spans, codeBackground, codeFamily)
                    preparedItems += PreparedExcerptItem.QuoteBlock(annotated, baseStyle.copy(color = colors.foregroundMuted).forCjkLineBox(annotated), first = first)
                }
                is ArtifactExcerptBlock.ListBlock -> {
                    val preparedListItems =
                        block.items.map { item ->
                            val checkAuxIndex =
                                if (item.checked == true) {
                                    auxComposables.size.also {
                                        auxComposables += {
                                            RaftLucideIcon(RaftLucideIconName.Check, Modifier.size(10.dp), color = raftArgbHex(colors.layerCard))
                                        }
                                    }
                                } else {
                                    null
                                }
                            val annotated = excerptWords(item.spans, codeBackground, codeFamily)
                            PreparedListItem(
                                level = item.level,
                                order = item.order,
                                checked = item.checked,
                                checkAuxIndex = checkAuxIndex,
                                text = annotated,
                                style = baseStyle.forCjkLineBox(annotated),
                            )
                        }
                    preparedItems += PreparedExcerptItem.ListBlock(preparedListItems, first = first)
                }
                is ArtifactExcerptBlock.Code -> {
                    val codeText = block.lines.joinToString("\n")
                    // common-map-compat: allow-nonmap-merge (TextStyle.merge)
                    val codeStyle = baseStyle.merge(ExcerptType.code).copy(fontFamily = codeFamily).forCjkLineBox(codeText)
                    preparedItems += PreparedExcerptItem.CodeBlock(codeText, codeStyle, first = first)
                }
                is ArtifactExcerptBlock.Table -> {
                    val auxIndex = auxComposables.size
                    auxComposables += { ExcerptTable(block, first = true, base = baseStyle) }
                    preparedItems += PreparedExcerptItem.TableBlock(auxIndex, first = first)
                }
                is ArtifactExcerptBlock.Image -> {
                    val label =
                        if (block.alt.isEmpty()) {
                            stringResource(Res.string.artifacts_card_excerpt_image)
                        } else {
                            stringResource(Res.string.artifacts_card_excerpt_image_named, block.alt)
                        }
                    val auxIndex = auxComposables.size
                    auxComposables += { RaftLucideIcon(RaftLucideIconName.Image, Modifier.size(12.dp), color = raftArgbHex(colors.foregroundHint)) }
                    val annotated = AnnotatedString(label)
                    preparedItems += PreparedExcerptItem.LabelBlock(auxIndex, annotated, baseStyle.copy(color = colors.foregroundHint).forCjkLineBox(annotated), first = first)
                }
                is ArtifactExcerptBlock.Images -> {
                    val label = stringResource(Res.string.artifacts_card_excerpt_images, block.count)
                    val auxIndex = auxComposables.size
                    auxComposables += { RaftLucideIcon(RaftLucideIconName.Image, Modifier.size(12.dp), color = raftArgbHex(colors.foregroundHint)) }
                    val annotated = AnnotatedString(label)
                    preparedItems += PreparedExcerptItem.LabelBlock(auxIndex, annotated, baseStyle.copy(color = colors.foregroundHint).forCjkLineBox(annotated), first = first)
                }
                ArtifactExcerptBlock.Diagram -> {
                    val label = stringResource(Res.string.artifacts_card_excerpt_diagram)
                    val auxIndex = auxComposables.size
                    auxComposables += { RaftLucideIcon(RaftLucideIconName.Workflow, Modifier.size(12.dp), color = raftArgbHex(colors.foregroundHint)) }
                    val annotated = AnnotatedString(label)
                    preparedItems += PreparedExcerptItem.LabelBlock(auxIndex, annotated, baseStyle.copy(color = colors.foregroundHint).forCjkLineBox(annotated), first = first)
                }
                ArtifactExcerptBlock.Rule -> {
                    preparedItems += PreparedExcerptItem.RuleBlock(first = first)
                }
            }
        }
    }

    val measurer = rememberTextMeasurer(cacheSize = 24)
    val planHolder = remember { ExcerptWrappedPlanHolder() }
    val bodyOrderStyle = baseStyle.forCjkLineBox("1.")

    Layout(
        contents =
            listOf(
                {
                    auxComposables.forEach { aux ->
                        Box(Modifier.clearAndSetSemantics {}) { aux() }
                    }
                },
                {
                    Box(
                        Modifier.drawBehind {
                            val fadePx = ArtifactCardExcerptFade.toPx().coerceAtMost(size.height)
                            if (fadePx > 0f && size.width > 0f) {
                                drawRect(
                                    brush =
                                        Brush.verticalGradient(
                                            colors = listOf(colors.layerCard.copy(alpha = 0f), colors.layerCard),
                                            startY = size.height - fadePx,
                                            endY = size.height,
                                        ),
                                    topLeft = Offset(0f, size.height - fadePx),
                                    size = Size(size.width, fadePx),
                                )
                            }
                        },
                    )
                    Box(Modifier.background(colors.layerCard))
                },
                {
                    Box(Modifier.testTag(ArtifactsHomeTags.TileExcerptCorner)) {
                        corner()
                    }
                },
            ),
        modifier =
            modifier
                .fillMaxSize()
                .clipToBounds()
                .background(colors.layerCard)
                .testTag(if (excerpt.blocks != null) "artifact-card-excerpt-blocks" else "artifact-card-excerpt-text")
                .drawBehind {
                    val plan = planHolder.plan ?: return@drawBehind
                    drawWrappedExcerptPlan(plan)
                },
    ) { (auxMeasurables, fadeMeasurables, cornerMeasurables), constraints ->
        val frameWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val frameHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val padPx = ArtifactCardExcerptPadding.roundToPx()
        val cornerInsetPx = ArtifactCardExcerptCornerInset.roundToPx()
        val gapStartPx = ArtifactCardExcerptCornerGapStart.roundToPx()
        val gapBottomPx = ArtifactCardExcerptCornerGapBottom.roundToPx()
        val minBesideWidthPx = ArtifactCardExcerptMinBesideWidth.roundToPx()
        val minCodeBesideWidthPx = ArtifactCardExcerptMinCodeBesideWidth.roundToPx()
        val minTableBesideWidthPx = ArtifactCardExcerptMinTableBesideWidth.roundToPx()
        val innerWidth = (frameWidth - 2 * padPx).coerceAtLeast(0)
        val innerHeight = (frameHeight - 2 * padPx).coerceAtLeast(0)
        val cornerMaxWidth = (frameWidth - 2 * cornerInsetPx).coerceAtLeast(0)
        val cornerMaxHeight = (frameHeight - 2 * cornerInsetPx).coerceAtLeast(0)
        val cornerPlaceable =
            cornerMeasurables.firstOrNull()?.measure(
                Constraints(minWidth = 0, maxWidth = cornerMaxWidth, minHeight = 0, maxHeight = cornerMaxHeight),
            )
        val exclusion =
            computeExcerptCornerExclusion(
                frameWidth = frameWidth,
                padPx = padPx,
                cornerInsetPx = cornerInsetPx,
                gapStartPx = gapStartPx,
                gapBottomPx = gapBottomPx,
                minBesideWidthPx = minBesideWidthPx,
                cornerWidth = cornerPlaceable?.width ?: 0,
                cornerHeight = cornerPlaceable?.height ?: 0,
            )

        val isRtl = layoutDirection == LayoutDirection.Rtl
        val blockGapPx = 6.dp.roundToPx()
        val headingGapPx = 8.dp.roundToPx()
        val quoteBarWidthPx = 2.dp.roundToPx()
        val quoteGapPx = 8.dp.roundToPx()
        val listNestedIndentPx = 16.dp.roundToPx()
        val listGapPx = 6.dp.roundToPx()
        val checkboxSizePx = 12.dp.roundToPx()
        val checkboxTopPx = 3.dp.roundToPx()
        val checkboxCheckInsetPx = 1.dp.roundToPx()
        val checkboxRadiusPx = RaftRadius.Xs.toPx()
        val hairlineStrokePx = RaftStroke.Hairline.toPx()
        val hairlineHeightPx = RaftStroke.Hairline.roundToPx().coerceAtLeast(1)
        val bulletSizePx = 4.dp.roundToPx()
        val bulletTopPx = 7.dp.roundToPx()
        val labelIconSizePx = 12.dp.roundToPx()
        val labelIconTopPx = 3.dp.roundToPx()
        val labelGapPx = 4.dp.roundToPx()
        val codePadHPx = 8.dp.roundToPx()
        val codePadVPx = 4.dp.roundToPx()
        val codeRadiusPx = RaftRadius.Sticker.toPx()

        val drawOps = mutableListOf<ExcerptDrawOp>()
        val auxPlacements = mutableListOf<ExcerptAuxPlacement>()
        var defaultLineHeightPx = -1
        fun blankLineHeight(style: TextStyle): Int {
            if (style == bodyOrderStyle && defaultLineHeightPx > 0) return defaultLineHeightPx
            val measured =
                measurer
                    .measure(
                        text = AnnotatedString(" "),
                        style = style,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = innerWidth.coerceAtLeast(1)),
                    )
                    .size
                    .height
                    .coerceAtLeast(1)
            if (style == bodyOrderStyle) defaultLineHeightPx = measured
            return measured
        }

        fun layoutWrappedText(
            text: AnnotatedString,
            style: TextStyle,
            startX: Int,
            startY: Int,
            fullWidth: Int,
            narrowWidth: Int,
        ): WrappedTextLayout {
            if (text.isEmpty() || fullWidth <= 0 || startY >= innerHeight) {
                return WrappedTextLayout(firstLineTop = startY, bottomY = startY)
            }
            fun physicalLineX(lineWidth: Int): Int =
                if (isRtl) (innerWidth - startX - lineWidth).coerceAtLeast(0) else startX

            if (startY >= exclusion.exclusionHeight || narrowWidth >= fullWidth) {
                val result =
                    measurer.measure(
                        text = text,
                        style = style,
                        overflow = TextOverflow.Clip,
                        constraints = Constraints(minWidth = if (isRtl) fullWidth else 0, maxWidth = fullWidth),
                    )
                drawOps += ExcerptDrawOp.Text(result, x = physicalLineX(fullWidth), y = startY, clipWidth = fullWidth)
                return WrappedTextLayout(firstLineTop = startY, bottomY = startY + result.size.height)
            }

            var y = startY
            var offset = 0
            var firstLineTop = -1
            while (y < exclusion.exclusionHeight && offset < text.length && y < innerHeight) {
                val ch = text[offset]
                if (ch == '\r' || ch == '\n') {
                    if (firstLineTop < 0) firstLineTop = y
                    offset = advancePastSingleLineBreak(text, offset)
                    y += blankLineHeight(style)
                    continue
                }
                if (narrowWidth <= 0) break
                val remaining = text.subSequence(offset, text.length)
                val lineResult =
                    measurer.measure(
                        text = remaining,
                        style = style,
                        overflow = TextOverflow.Clip,
                        maxLines = 1,
                        constraints = Constraints(minWidth = if (isRtl) narrowWidth else 0, maxWidth = narrowWidth),
                    )
                if (lineResult.lineCount <= 0) break
                val visibleEnd = lineResult.getLineEnd(0, visibleEnd = true).coerceIn(0, remaining.length)
                val lineEnd = lineResult.getLineEnd(0, visibleEnd = false).coerceIn(0, remaining.length)
                val linePixelWidth = (lineResult.getLineRight(0) - lineResult.getLineLeft(0)).coerceAtLeast(0f)
                if (linePixelWidth > narrowWidth + 1f) break
                if (!isCleanExcerptWrapBoundary(remaining, lineEnd, visibleEnd)) break
                val advance = advancePastExcerptWrappedLine(remaining, lineEnd)
                if (advance <= 0) break
                if (firstLineTop < 0) firstLineTop = y
                drawOps += ExcerptDrawOp.Text(lineResult, x = physicalLineX(narrowWidth), y = y, clipWidth = narrowWidth)
                y += lineResult.size.height.coerceAtLeast(1)
                offset += advance
            }

            if (offset < text.length && y < innerHeight) {
                if (y < exclusion.exclusionHeight) {
                    y = exclusion.exclusionHeight
                }
                if (y < innerHeight) {
                    val rest = text.subSequence(offset, text.length)
                    val restResult =
                        measurer.measure(
                            text = rest,
                            style = style,
                            overflow = TextOverflow.Clip,
                            constraints = Constraints(minWidth = if (isRtl) fullWidth else 0, maxWidth = fullWidth),
                        )
                    if (firstLineTop < 0) firstLineTop = y
                    drawOps += ExcerptDrawOp.Text(restResult, x = physicalLineX(fullWidth), y = y, clipWidth = fullWidth)
                    y += restResult.size.height
                }
            }
            return WrappedTextLayout(firstLineTop = if (firstLineTop < 0) startY else firstLineTop, bottomY = y)
        }

        var y = 0
        for (item in preparedItems) {
            if (y >= innerHeight) break
            if (!item.first) {
                y += if (item.heading) headingGapPx else blockGapPx
            }
            if (y >= innerHeight) break
            when (item) {
                is PreparedExcerptItem.TextBlock -> {
                    val laid =
                        layoutWrappedText(
                            text = item.text,
                            style = item.style,
                            startX = 0,
                            startY = y,
                            fullWidth = innerWidth,
                            narrowWidth = exclusion.besideWidth,
                        )
                    y = laid.bottomY
                }
                is PreparedExcerptItem.QuoteBlock -> {
                    val indent = quoteBarWidthPx + quoteGapPx
                    val laid =
                        layoutWrappedText(
                            text = item.text,
                            style = item.style,
                            startX = indent,
                            startY = y,
                            fullWidth = (innerWidth - indent).coerceAtLeast(0),
                            narrowWidth = (exclusion.besideWidth - indent).coerceAtLeast(0),
                        )
                    val barHeight = (laid.bottomY - laid.firstLineTop).coerceAtLeast(0)
                    if (barHeight > 0) {
                        val barX = if (isRtl) (innerWidth - quoteBarWidthPx).coerceAtLeast(0) else 0
                        drawOps += ExcerptDrawOp.Rect(barX, laid.firstLineTop, quoteBarWidthPx, barHeight, colors.lineHairline)
                    }
                    y = laid.bottomY
                }
                is PreparedExcerptItem.ListBlock -> {
                    for (listItem in item.items) {
                        if (y >= innerHeight) break
                        val levelIndent = if (listItem.level == 1) listNestedIndentPx else 0
                        val orderResult =
                            listItem.order?.let { order ->
                                measurer.measure(
                                    text = AnnotatedString("$order."),
                                    style = bodyOrderStyle,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        val markerWidth =
                            when {
                                listItem.checked != null -> checkboxSizePx
                                orderResult != null -> orderResult.size.width
                                else -> bulletSizePx
                            }
                        val textStartX = levelIndent + markerWidth + listGapPx
                        val laid =
                            layoutWrappedText(
                                text = listItem.text,
                                style = listItem.style,
                                startX = textStartX,
                                startY = y,
                                fullWidth = (innerWidth - textStartX).coerceAtLeast(0),
                                narrowWidth = (exclusion.besideWidth - textStartX).coerceAtLeast(0),
                            )
                        val itemTop = laid.firstLineTop
                        if (itemTop < innerHeight) {
                            when {
                                listItem.checked != null -> {
                                    val boxX = if (isRtl) (innerWidth - levelIndent - checkboxSizePx).coerceAtLeast(0) else levelIndent
                                    drawOps +=
                                        ExcerptDrawOp.Checkbox(
                                            x = boxX,
                                            y = itemTop + checkboxTopPx,
                                            size = checkboxSizePx,
                                            radius = checkboxRadiusPx,
                                            checked = listItem.checked,
                                            strokeWidth = hairlineStrokePx,
                                            fillColor = colors.foreground,
                                            borderColor = colors.foregroundMuted,
                                        )
                                    val checkIdx = listItem.checkAuxIndex
                                    if (listItem.checked && checkIdx != null && checkIdx < auxMeasurables.size) {
                                        val checkPlaceable =
                                            auxMeasurables[checkIdx].measure(
                                                Constraints(minWidth = 0, maxWidth = checkboxSizePx, minHeight = 0, maxHeight = checkboxSizePx),
                                            )
                                        auxPlacements +=
                                            ExcerptAuxPlacement(
                                                placeable = checkPlaceable,
                                                logicalX = levelIndent + checkboxCheckInsetPx,
                                                y = itemTop + checkboxTopPx + checkboxCheckInsetPx,
                                            )
                                    }
                                }
                                orderResult != null -> {
                                    val orderX = if (isRtl) (innerWidth - levelIndent - orderResult.size.width).coerceAtLeast(0) else levelIndent
                                    drawOps += ExcerptDrawOp.Text(orderResult, x = orderX, y = itemTop, clipWidth = orderResult.size.width)
                                }
                                else -> {
                                    val radius = bulletSizePx / 2f
                                    val dotLeft = if (isRtl) (innerWidth - levelIndent - bulletSizePx).coerceAtLeast(0) else levelIndent
                                    drawOps += ExcerptDrawOp.Circle(dotLeft + radius, itemTop + bulletTopPx + radius, radius, baseStyle.color)
                                }
                            }
                        }
                        y = maxOf(laid.bottomY, itemTop + blankLineHeight(bodyOrderStyle))
                    }
                }
                is PreparedExcerptItem.CodeBlock -> {
                    val codeResult =
                        measurer.measure(
                            text = AnnotatedString(item.text),
                            style = item.style,
                            softWrap = false,
                        )
                    val boxHeight = codeResult.size.height + 2 * codePadVPx
                    val boxWidth =
                        if (y < exclusion.exclusionHeight) {
                            if (exclusion.besideWidth >= minCodeBesideWidthPx) {
                                exclusion.besideWidth
                            } else {
                                y = exclusion.exclusionHeight
                                innerWidth
                            }
                        } else {
                            innerWidth
                        }
                    if (y < innerHeight && boxWidth > 0) {
                        val boxX = if (isRtl) (innerWidth - boxWidth).coerceAtLeast(0) else 0
                        drawOps +=
                            ExcerptDrawOp.CodeBox(
                                x = boxX,
                                y = y,
                                width = boxWidth,
                                height = boxHeight,
                                radius = codeRadiusPx,
                                padH = codePadHPx,
                                padV = codePadVPx,
                                background = colors.fillMuted,
                                text = codeResult,
                            )
                    }
                    y += boxHeight
                }
                is PreparedExcerptItem.TableBlock -> {
                    val tableWidth =
                        if (y < exclusion.exclusionHeight) {
                            if (exclusion.besideWidth >= minTableBesideWidthPx) {
                                exclusion.besideWidth
                            } else {
                                y = exclusion.exclusionHeight
                                innerWidth
                            }
                        } else {
                            innerWidth
                        }
                    if (y < innerHeight && tableWidth > 0 && item.auxIndex < auxMeasurables.size) {
                        val tablePlaceable =
                            auxMeasurables[item.auxIndex].measure(
                                Constraints(minWidth = 0, maxWidth = tableWidth, minHeight = 0, maxHeight = Constraints.Infinity),
                            )
                        auxPlacements += ExcerptAuxPlacement(tablePlaceable, logicalX = 0, y = y)
                        y += tablePlaceable.height
                    }
                }
                is PreparedExcerptItem.LabelBlock -> {
                    val textStartX = labelIconSizePx + labelGapPx
                    val laid =
                        layoutWrappedText(
                            text = item.text,
                            style = item.style,
                            startX = textStartX,
                            startY = y,
                            fullWidth = (innerWidth - textStartX).coerceAtLeast(0),
                            narrowWidth = (exclusion.besideWidth - textStartX).coerceAtLeast(0),
                        )
                    val itemTop = laid.firstLineTop
                    if (itemTop < innerHeight && item.auxIndex < auxMeasurables.size) {
                        val iconPlaceable =
                            auxMeasurables[item.auxIndex].measure(
                                Constraints(minWidth = 0, maxWidth = labelIconSizePx, minHeight = 0, maxHeight = labelIconSizePx),
                            )
                        auxPlacements += ExcerptAuxPlacement(iconPlaceable, logicalX = 0, y = itemTop + labelIconTopPx)
                    }
                    y = maxOf(laid.bottomY, itemTop + blankLineHeight(bodyOrderStyle))
                }
                is PreparedExcerptItem.RuleBlock -> {
                    val ruleWidth =
                        if (y < exclusion.exclusionHeight) {
                            if (exclusion.besideWidth > 0) {
                                exclusion.besideWidth
                            } else {
                                y = exclusion.exclusionHeight
                                innerWidth
                            }
                        } else {
                            innerWidth
                        }
                    if (y < innerHeight && ruleWidth > 0) {
                        val ruleX = if (isRtl) (innerWidth - ruleWidth).coerceAtLeast(0) else 0
                        drawOps += ExcerptDrawOp.Rect(ruleX, y, ruleWidth, hairlineHeightPx, colors.lineHairline)
                    }
                    y += hairlineHeightPx
                }
            }
        }

        planHolder.plan =
            ExcerptWrappedPlan(
                padPx = padPx,
                innerWidth = innerWidth,
                innerHeight = innerHeight,
                drawOps = drawOps,
                auxPlacements = auxPlacements,
            )

        val fadePlaceable =
            fadeMeasurables.getOrNull(0)?.measure(Constraints.fixed(innerWidth, innerHeight))
        val bottomMaskHeight = (frameHeight - (padPx + innerHeight)).coerceAtLeast(0)
        val bottomMaskPlaceable =
            fadeMeasurables.getOrNull(1)?.measure(Constraints.fixed(frameWidth, bottomMaskHeight))

        layout(frameWidth, frameHeight) {
            auxPlacements.forEach { placement ->
                if (placement.y < innerHeight) {
                    placement.placeable.placeRelative(padPx + placement.logicalX, padPx + placement.y)
                }
            }
            if (fadePlaceable != null && innerWidth > 0 && innerHeight > 0) {
                fadePlaceable.placeRelative(padPx, padPx)
            }
            if (bottomMaskPlaceable != null && bottomMaskHeight > 0) {
                bottomMaskPlaceable.placeRelative(0, padPx + innerHeight)
            }
            if (cornerPlaceable != null) {
                val cornerStart = (frameWidth - cornerInsetPx - cornerPlaceable.width).coerceAtLeast(0)
                cornerPlaceable.placeRelative(cornerStart, cornerInsetPx)
            }
        }
    }
}

private fun DrawScope.drawWrappedExcerptPlan(plan: ExcerptWrappedPlan) {
    if (plan.innerWidth <= 0 || plan.innerHeight <= 0) return
    val left = plan.padPx.toFloat()
    val top = plan.padPx.toFloat()
    clipRect(
        left = left,
        top = top,
        right = left + plan.innerWidth,
        bottom = top + plan.innerHeight,
    ) {
        for (op in plan.drawOps) {
            when (op) {
                is ExcerptDrawOp.Text -> {
                    val x = left + op.x
                    val y = top + op.y
                    clipRect(
                        left = x,
                        top = top,
                        right = (x + op.clipWidth).coerceAtMost(left + plan.innerWidth),
                        bottom = top + plan.innerHeight,
                    ) {
                        drawText(textLayoutResult = op.result, topLeft = Offset(x, y))
                    }
                }
                is ExcerptDrawOp.Rect -> {
                    drawRect(
                        color = op.color,
                        topLeft = Offset(left + op.x, top + op.y),
                        size = Size(op.width.toFloat(), op.height.toFloat()),
                    )
                }
                is ExcerptDrawOp.Circle -> {
                    drawCircle(
                        color = op.color,
                        radius = op.radius,
                        center = Offset(left + op.centerX, top + op.centerY),
                    )
                }
                is ExcerptDrawOp.Checkbox -> {
                    val cornerRadius = CornerRadius(op.radius, op.radius)
                    if (op.checked) {
                        drawRoundRect(
                            color = op.fillColor,
                            topLeft = Offset(left + op.x, top + op.y),
                            size = Size(op.size.toFloat(), op.size.toFloat()),
                            cornerRadius = cornerRadius,
                        )
                    } else {
                        val half = op.strokeWidth / 2f
                        val insetSize = (op.size.toFloat() - op.strokeWidth).coerceAtLeast(0f)
                        drawRoundRect(
                            color = op.borderColor,
                            topLeft = Offset(left + op.x + half, top + op.y + half),
                            size = Size(insetSize, insetSize),
                            cornerRadius = cornerRadius,
                            style = Stroke(width = op.strokeWidth),
                        )
                    }
                }
                is ExcerptDrawOp.CodeBox -> {
                    val boxLeft = left + op.x
                    val boxTop = top + op.y
                    drawRoundRect(
                        color = op.background,
                        topLeft = Offset(boxLeft, boxTop),
                        size = Size(op.width.toFloat(), op.height.toFloat()),
                        cornerRadius = CornerRadius(op.radius, op.radius),
                    )
                    val contentLeft = boxLeft + op.padH
                    val contentTop = boxTop + op.padV
                    val contentRight = (boxLeft + op.width - op.padH).coerceAtLeast(contentLeft)
                    val contentBottom = (boxTop + op.height - op.padV).coerceAtLeast(contentTop)
                    clipRect(left = contentLeft, top = contentTop, right = contentRight, bottom = contentBottom) {
                        drawText(textLayoutResult = op.text, topLeft = Offset(contentLeft, contentTop))
                    }
                }
            }
        }
    }
}

/**
 * Measures [content] at its own height and shows it, with [padding] all round, between [minHeight]
 * and [maxHeight]; past the max the words are cut by the padding's edge and fade out at their foot.
 */
@Composable
private fun ArtifactCardExcerptFrame(modifier: Modifier, padding: Dp, minHeight: Dp, maxHeight: Dp, content: @Composable () -> Unit) {
    Layout(content = { Box(Modifier.excerptFade().fillMaxWidth()) { content() } }, modifier = modifier) { measurables, constraints ->
        val pad = padding.roundToPx()
        val minPx = minHeight.roundToPx()
        val maxPx = maxHeight.roundToPx()
        val innerWidth = if (constraints.hasBoundedWidth) (constraints.maxWidth - 2 * pad).coerceAtLeast(0) else Constraints.Infinity
        // The words' box takes their own height between the bounds (less the padding), cut past the max.
        val placeable =
            measurables.first().measure(
                Constraints(maxWidth = innerWidth, minHeight = (minPx - 2 * pad).coerceAtLeast(0), maxHeight = (maxPx - 2 * pad).coerceAtLeast(0)),
            )
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width + 2 * pad
        val height = placeable.height + 2 * pad
        layout(width, height) { placeable.place(pad, pad) }
    }
}

/**
 * Clips the words to the box and fades its last [ArtifactCardExcerptFade] to transparent (web
 * `mask-fade-b-edge` when [alwaysFade], or `mask-fade-b-edge-at-max` when taller than the box).
 */
private fun Modifier.excerptFade(alwaysFade: Boolean = false): Modifier {
    var overflowing = false
    return this
        .clipToBounds()
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            if (alwaysFade || overflowing) {
                val fade = ArtifactCardExcerptFade.toPx().coerceAtMost(size.height)
                drawRect(
                    brush = Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - fade, endY = size.height), // raft-color: DstIn alpha mask for the fade; only alpha is used
                    topLeft = Offset(0f, size.height - fade),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
        .layout { measurable, constraints ->
            // The words at their own height, top-aligned in the box: taller than it overflows.
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val height = if (alwaysFade && constraints.hasBoundedHeight) constraints.maxHeight else placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight)
            overflowing = placeable.height > height
            layout(placeable.width, height) { placeable.place(0, 0) }
        }
}

private fun excerptWords(spans: List<ArtifactExcerptSpan>, codeBackground: Color, codeFamily: androidx.compose.ui.text.font.FontFamily): AnnotatedString =
    buildAnnotatedString {
        for (span in spans) {
            val style =
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.SemiBold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    fontFamily = if (span.code) codeFamily else null,
                    fontSize = if (span.code) ExcerptType.code.fontSize else androidx.compose.ui.unit.TextUnit.Unspecified,
                    background = if (span.code) codeBackground else Color.Unspecified,
                )
            withStyle(style) { append(span.text) }
        }
    }

@Composable
private fun words(spans: List<ArtifactExcerptSpan>): AnnotatedString =
    excerptWords(spans, RaftTheme.colors.fillMuted, RaftTheme.typography.monoCode.fontFamily ?: androidx.compose.ui.text.font.FontFamily.Monospace)

/** web `BLOCK_GAP` (6dp between blocks) and `HEADING_GAP` (8dp above a heading); the first has nothing above it. */
private fun Modifier.blockGap(first: Boolean, heading: Boolean = false): Modifier = padding(top = if (first) 0.dp else if (heading) 8.dp else 6.dp)

@Composable
private fun ExcerptBlock(block: ArtifactExcerptBlock, first: Boolean, base: TextStyle) {
    val colors = RaftTheme.colors
    when (block) {
        is ArtifactExcerptBlock.Heading -> {
            val style =
                when (block.level) {
                    1 -> ExcerptType.heading1
                    2 -> ExcerptType.heading2
                    else -> ExcerptType.smallHeading
                }
            // common-map-compat: allow-nonmap-merge (TextStyle.merge)
            RaftText(words(block.spans), Modifier.blockGap(first, heading = true), style = base.merge(style))
        }
        is ArtifactExcerptBlock.Paragraph -> RaftText(words(block.spans), Modifier.blockGap(first), style = base)
        is ArtifactExcerptBlock.Quote ->
            Row(Modifier.blockGap(first).height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(colors.lineHairline))
                RaftText(words(block.spans), Modifier.padding(start = 8.dp), style = base.copy(color = colors.foregroundMuted))
            }
        is ArtifactExcerptBlock.ListBlock ->
            Column(Modifier.blockGap(first)) {
                block.items.forEach { item -> ExcerptListItem(item, base) }
            }
        is ArtifactExcerptBlock.Code ->
            Box(
                Modifier
                    .blockGap(first)
                    .fillMaxWidth()
                    .clipToBounds()
                    .background(colors.fillMuted, RoundedCornerShape(RaftRadius.Sticker))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                RaftText(
                    block.lines.joinToString("\n"),
                    Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
                    // common-map-compat: allow-nonmap-merge (TextStyle.merge)
                    style = base.merge(ExcerptType.code).copy(fontFamily = RaftTheme.typography.monoCode.fontFamily),
                    softWrap = false,
                )
            }
        is ArtifactExcerptBlock.Table -> ExcerptTable(block, first, base)
        is ArtifactExcerptBlock.Image ->
            ExcerptLabel(
                RaftLucideIconName.Image,
                if (block.alt.isEmpty()) stringResource(Res.string.artifacts_card_excerpt_image) else stringResource(Res.string.artifacts_card_excerpt_image_named, block.alt),
                first,
                base,
            )
        is ArtifactExcerptBlock.Images -> ExcerptLabel(RaftLucideIconName.Image, stringResource(Res.string.artifacts_card_excerpt_images, block.count), first, base)
        // web draws lucide `Workflow`.
        ArtifactExcerptBlock.Diagram -> ExcerptLabel(RaftLucideIconName.Workflow, stringResource(Res.string.artifacts_card_excerpt_diagram), first, base)
        ArtifactExcerptBlock.Rule -> Box(Modifier.blockGap(first).fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineHairline))
    }
}

/** A check is drawn, not an input; an ordered item's number with its dot; else a dot. Nested items sit 16dp in. */
@Composable
private fun ExcerptListItem(item: ArtifactExcerptListItem, base: TextStyle) {
    val colors = RaftTheme.colors
    Row(Modifier.padding(start = if (item.level == 1) 16.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val checked = item.checked
        val order = item.order
        when {
            checked != null ->
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .size(12.dp)
                        .then(
                            if (checked) {
                                Modifier.background(colors.foreground, RoundedCornerShape(RaftRadius.Xs))
                            } else {
                                Modifier.border(RaftStroke.Hairline, colors.foregroundMuted, RoundedCornerShape(RaftRadius.Xs))
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (checked) RaftLucideIcon(RaftLucideIconName.Check, Modifier.size(10.dp), color = raftArgbHex(colors.layerCard))
                }
            order != null -> RaftText("$order.", style = base)
            else -> Box(Modifier.padding(top = 7.dp).size(4.dp).background(base.color, CircleShape))
        }
        RaftText(words(item.spans), Modifier.weight(1f), style = base)
    }
}

/** Its natural width, cut by the frame when wider: nothing here scrolls. Cells don't wrap. */
@Composable
private fun ExcerptTable(block: ArtifactExcerptBlock.Table, first: Boolean, base: TextStyle) {
    val colors = RaftTheme.colors
    val columns = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    Box(Modifier.blockGap(first).fillMaxWidth().clipToBounds()) {
        Layout(
            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true),
            content = {
                val allRows = listOf(block.header) + block.rows
                allRows.forEachIndexed { rowIndex, row ->
                    for (column in 0 until columns) {
                        val cell = row.getOrNull(column).orEmpty()
                        RaftText(
                            words(cell),
                            Modifier.border(RaftStroke.Hairline, colors.lineHairline).padding(horizontal = 6.dp),
                            style = if (rowIndex == 0) base.copy(fontWeight = FontWeight.SemiBold) else base,
                            softWrap = false,
                            maxLines = 1,
                        )
                    }
                }
            },
        ) { measurables, _ ->
            val rowCount = if (columns == 0) 0 else measurables.size / columns
            val widths = IntArray(columns) { column -> (0 until rowCount).maxOf { measurables[it * columns + column].maxIntrinsicWidth(Constraints.Infinity) } }
            val heights = IntArray(rowCount) { row -> (0 until columns).maxOf { measurables[row * columns + it].maxIntrinsicHeight(widths[it]) } }
            val cells =
                measurables.mapIndexed { index, measurable ->
                    measurable.measure(Constraints.fixed(widths[index % columns], heights[index / columns]))
                }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                for (row in 0 until rowCount) {
                    var x = 0
                    for (column in 0 until columns) {
                        cells[row * columns + column].place(x, y)
                        x += widths[column]
                    }
                    y += heights[row]
                }
            }
        }
    }
}

/** What stands in for something not drawn: an icon and the hint colour, so it doesn't read as the document's own words. */
@Composable
private fun ExcerptLabel(icon: RaftLucideIconName, label: String, first: Boolean, base: TextStyle) {
    val hint = RaftTheme.colors.foregroundHint
    Row(Modifier.blockGap(first), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftLucideIcon(icon, Modifier.padding(top = 3.dp).size(12.dp), color = raftArgbHex(hint))
        RaftText(label, Modifier.weight(1f), style = base.copy(color = hint))
    }
}
