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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.data.ArtifactExcerpt
import build.raft.app.data.ArtifactExcerptBlock
import build.raft.app.data.ArtifactExcerptListItem
import build.raft.app.data.ArtifactExcerptSpan
import build.raft.app.resources.*
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

// web `ArtifactCardExcerpt.tsx` (botiverse/slock staging, #13445 / #13472 / #13488): a Markdown
// version's excerpt where a message card's picture goes, on the card's own surface, so it reads as
// the page's start. Its first blocks, small, with their hierarchy, or its plain text for a version
// from before them or blocks this client can't read. Only ever text: no link, image or input.

/** web `min-h-24`: four lines, about the smallest preview there is. */
internal val ArtifactCardExcerptMinHeight: Dp = 96.dp

/** web `max-h-48`: the picture's frame in a message. */
internal val ArtifactCardExcerptMaxHeight: Dp = ArtifactCardScreenshotMaxHeight

/** web `mask-fade-b-edge-at-max`: the fade's length at the foot once the words reach the max. */
private val ArtifactCardExcerptFade: Dp = 24.dp

/** The thumbnail's own type scale (12/18, 15/20, 14/20, 11/16; Kirby, #13472): a page drawn small, fixed on purpose. */
private object ExcerptType {
    val body = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
    val heading1 = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val heading2 = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val smallHeading = body.copy(fontWeight = FontWeight.SemiBold)
    val code = TextStyle(fontSize = 11.sp, lineHeight = 16.sp)
}

/**
 * The excerpt's frame (web `fit="content"`): its height follows the words, at least
 * [ArtifactCardExcerptMinHeight], at most [ArtifactCardExcerptMaxHeight], where the words are cut
 * and fade out at its foot. Decoration, as a screenshot is: hidden from assistive technology, which
 * reads the card's title instead; nothing in it takes focus or a tap of its own.
 */
@Composable
internal fun ArtifactCardExcerpt(excerpt: ArtifactExcerpt, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    val textStyle = ExcerptType.body.copy(color = colors.foreground, fontFamily = RaftTheme.typography.callout.fontFamily)
    Column(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics {}
            .testTag(if (excerpt.blocks != null) "artifact-card-excerpt-blocks" else "artifact-card-excerpt-text"),
    ) {
        ArtifactCardExcerptFrame(Modifier.fillMaxWidth().background(colors.layerCard), padding = 12.dp) {
            val blocks = excerpt.blocks
            if (blocks != null) {
                Column(Modifier.fillMaxWidth()) {
                    blocks.forEachIndexed { index, block -> ExcerptBlock(block, first = index == 0, base = textStyle) }
                }
            } else {
                RaftText(excerpt.text, style = textStyle)
            }
        }
        Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineHairline))
    }
}

/**
 * Measures [content] at its own height and shows it, with [padding] all round, between
 * [ArtifactCardExcerptMinHeight] and [ArtifactCardExcerptMaxHeight]; past the max the words are
 * cut by the padding's edge and fade out at their foot.
 */
@Composable
private fun ArtifactCardExcerptFrame(modifier: Modifier, padding: Dp, content: @Composable () -> Unit) {
    Layout(content = { Box(Modifier.excerptFade().fillMaxWidth()) { content() } }, modifier = modifier) { measurables, constraints ->
        val pad = padding.roundToPx()
        val minHeight = ArtifactCardExcerptMinHeight.roundToPx()
        val maxHeight = ArtifactCardExcerptMaxHeight.roundToPx()
        val innerWidth = if (constraints.hasBoundedWidth) (constraints.maxWidth - 2 * pad).coerceAtLeast(0) else Constraints.Infinity
        // The words' box takes their own height between the bounds (less the padding), cut past the max.
        val placeable =
            measurables.first().measure(
                Constraints(maxWidth = innerWidth, minHeight = (minHeight - 2 * pad).coerceAtLeast(0), maxHeight = (maxHeight - 2 * pad).coerceAtLeast(0)),
            )
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width + 2 * pad
        val height = placeable.height + 2 * pad
        layout(width, height) { placeable.place(pad, pad) }
    }
}

/**
 * Clips the words to the box and, when they are taller than it (cut at the max), fades its last
 * [ArtifactCardExcerptFade] to transparent (web `mask-fade-b-edge-at-max`).
 */
private fun Modifier.excerptFade(): Modifier {
    var overflowing = false
    return this
        .clipToBounds()
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            if (overflowing) {
                val fade = ArtifactCardExcerptFade.toPx()
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
            val height = placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight)
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
        // web draws lucide `Workflow`; the app's icon set has no Workflow glyph, GitBranch reads the same at 12dp.
        ArtifactExcerptBlock.Diagram -> ExcerptLabel(RaftLucideIconName.GitBranch, stringResource(Res.string.artifacts_card_excerpt_diagram), first, base)
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
