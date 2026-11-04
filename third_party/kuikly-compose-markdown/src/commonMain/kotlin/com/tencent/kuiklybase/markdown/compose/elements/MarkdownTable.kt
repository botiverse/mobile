package com.tencent.kuiklybase.markdown.compose.elements

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import build.raft.designsystem.gesture.raftKeepsHorizontalDrags
import build.raft.designsystem.gesture.rememberRaftBackSwipeScroll
import com.tencent.kuiklybase.markdown.annotator.AnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.annotatorSettings
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownColors
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownComponents
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownDimens
import com.tencent.kuiklybase.markdown.compose.LocalRaftMarkdownTableCellPadding
import com.tencent.kuiklybase.markdown.compose.LocalRaftMarkdownTableClosedGrid
import com.tencent.kuiklybase.markdown.compose.handleElement
import org.intellij.markdown.MarkdownElementTypes.IMAGE
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL

/**
 * A GFM table laid out like the web's `<div class="overflow-x-auto"><table>` (HTML automatic
 * table layout): the table takes its content's width up to the width it is given, cells wrap only
 * when the content would not fit, and a table whose columns cannot shrink enough scrolls sideways.
 * Column widths come from [markdownTableColumnWidths]. Header cells do not wrap (web `th`
 * `whitespace-nowrap`).
 */
@Composable
fun MarkdownTable(
    content: String,
    node: ASTNode,
    style: TextStyle,
    annotatorSettings: AnnotatorSettings = annotatorSettings(),
    headerStyle: TextStyle = style.merge(fontWeight = FontWeight.Bold),
) {
    val rows = remember(node) { markdownTableRows(node) }
    val columnCount = rows.maxOfOrNull { it.cells.size } ?: 0
    if (columnCount == 0) return

    val dimens = LocalMarkdownDimens.current
    val colors = LocalMarkdownColors.current
    val cellPadding = LocalRaftMarkdownTableCellPadding.current ?: PaddingValues(dimens.tableCellPadding)
    val closedGrid = LocalRaftMarkdownTableClosedGrid.current
    val markdownComponents = LocalMarkdownComponents.current
    val strokeColor = colors.tableStroke
    val stroke = dimens.tableSeparatorThickness
    val headerRows = rows.count { it.header }
    val verticalRules = if (closedGrid) columnCount + 1 else columnCount - 1

    val cells: @Composable () -> Unit = {
        rows.forEach { row ->
            for (column in 0 until columnCount) {
                val cell = row.cells.getOrNull(column)
                Box(Modifier.padding(cellPadding)) {
                    when {
                        cell == null -> Unit
                        cell.children.any { it.type == IMAGE } ->
                            handleElement(node = cell, components = markdownComponents, content = content, includeSpacer = false)
                        else -> MarkdownTableBasicText(
                            content = content,
                            cell = cell,
                            style = if (row.header) headerStyle else style,
                            modifier = Modifier,
                            maxLines = Int.MAX_VALUE,
                            annotatorSettings = annotatorSettings,
                        )
                    }
                }
            }
        }
    }
    val headerBackground: @Composable () -> Unit = {
        if (headerRows > 0) Spacer(Modifier.background(colors.tableHeaderBackground))
    }
    val rules: @Composable () -> Unit = {
        repeat(rows.size + 1 + verticalRules) { Spacer(Modifier.background(strokeColor)) }
    }

    // Raft: a wide table scrolls sideways; at its start a back-direction drag goes to the host's
    // full-page back swipe, away from it the table keeps the drag.
    val scroll = rememberScrollState()
    val backSwipe = rememberRaftBackSwipeScroll(scroll)
    BoxWithConstraints {
        // The scroller measures its content with an unbounded width, so the width the table may
        // take without scrolling is read here, outside it.
        val availableWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        Layout(
            contents = listOf(cells, headerBackground, rules),
            modifier = Modifier
                // A drag that starts on the table is the table's, even at its end (no hand-off to
                // the thread list's drag-to-see-times).
                .raftKeepsHorizontalDrags()
                .then(backSwipe.modifier)
                .horizontalScroll(scroll, enabled = backSwipe.userScrollEnabled),
            measurePolicy = remember(rows, columnCount, headerRows, closedGrid, stroke, availableWidth) {
                MarkdownTableMeasurePolicy(
                    rowHeaders = rows.map { it.header },
                    columnCount = columnCount,
                    closedGrid = closedGrid,
                    stroke = stroke,
                    availableWidth = availableWidth,
                )
            },
        )
    }
}

private class MarkdownTableRow(val header: Boolean, val cells: List<ASTNode>)

private fun markdownTableRows(table: ASTNode): List<MarkdownTableRow> =
    table.children.mapNotNull { child ->
        when (child.type) {
            HEADER -> MarkdownTableRow(header = true, cells = child.children.filter { it.type == CELL })
            ROW -> MarkdownTableRow(header = false, cells = child.children.filter { it.type == CELL })
            else -> null
        }
    }

/**
 * Cells row by row (every row padded to [columnCount]), then the header background, then the
 * rules: one above every row plus one below the last, then the vertical ones left to right.
 */
private class MarkdownTableMeasurePolicy(
    private val rowHeaders: List<Boolean>,
    private val columnCount: Int,
    private val closedGrid: Boolean,
    private val stroke: Dp,
    private val availableWidth: Int,
) : MultiContentMeasurePolicy {
    override fun MeasureScope.measure(
        measurables: List<List<Measurable>>,
        constraints: Constraints,
    ): MeasureResult {
        val (cells, background, rules) = measurables
        val rowCount = rowHeaders.size
        val strokePx = if (stroke.value > 0f) stroke.roundToPx().coerceAtLeast(1) else 0
        val verticalRules = if (closedGrid) columnCount + 1 else columnCount - 1

        // Per column: the narrowest it can get without breaking a word (a header never wraps, so
        // its whole line) and the width that fits every cell on one line.
        val minWidths = IntArray(columnCount)
        val maxWidths = IntArray(columnCount)
        cells.forEachIndexed { index, cell ->
            val column = index % columnCount
            // Capped so a pathological one-line cell still fits in Constraints; it wraps there.
            val max = cell.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(MaxColumnWidthPx)
            val min = if (rowHeaders[index / columnCount]) max else cell.minIntrinsicWidth(Constraints.Infinity).coerceAtMost(max)
            minWidths[column] = maxOf(minWidths[column], min)
            maxWidths[column] = maxOf(maxWidths[column], max)
        }
        val widths = markdownTableColumnWidths(
            minWidths = minWidths,
            maxWidths = maxWidths,
            availableWidth = if (availableWidth == Int.MAX_VALUE) Int.MAX_VALUE else availableWidth - verticalRules * strokePx,
        )

        val placeables = arrayOfNulls<Placeable>(cells.size)
        val rowHeights = IntArray(rowCount)
        cells.forEachIndexed { index, cell ->
            val placeable = cell.measure(Constraints(minWidth = widths[index % columnCount], maxWidth = widths[index % columnCount]))
            placeables[index] = placeable
            rowHeights[index / columnCount] = maxOf(rowHeights[index / columnCount], placeable.height)
        }

        val tableWidth = widths.sum() + verticalRules * strokePx
        val tableHeight = rowHeights.sum() + (rowCount + 1) * strokePx
        val headerHeight = (0 until rowCount).filter { rowHeaders[it] }.sumOf { rowHeights[it] + strokePx }
        val backgroundPlaceable = background.firstOrNull()?.measure(Constraints.fixed(tableWidth, headerHeight.coerceAtLeast(0)))
        val horizontalRule = Constraints.fixed(tableWidth, strokePx)
        val verticalRule = Constraints.fixed(strokePx, tableHeight)
        val rulePlaceables = rules.mapIndexed { index, rule ->
            rule.measure(if (index <= rowCount) horizontalRule else verticalRule)
        }

        return layout(tableWidth, tableHeight) {
            backgroundPlaceable?.placeRelative(0, 0)
            val firstColumnX = if (closedGrid) strokePx else 0
            var y = strokePx
            for (row in 0 until rowCount) {
                var x = firstColumnX
                for (column in 0 until columnCount) {
                    val placeable = placeables[row * columnCount + column]!!
                    // Vertically centred in the row, like the previous table.
                    placeable.placeRelative(x, y + (rowHeights[row] - placeable.height) / 2)
                    x += widths[column] + strokePx
                }
                y += rowHeights[row] + strokePx
            }
            var ruleY = 0
            for (row in 0..rowCount) {
                rulePlaceables[row].placeRelative(0, ruleY)
                if (row < rowCount) ruleY += strokePx + rowHeights[row]
            }
            var ruleX = if (closedGrid) 0 else widths[0]
            for (rule in 0 until verticalRules) {
                rulePlaceables[rowCount + 1 + rule].placeRelative(ruleX, 0)
                val nextColumn = if (closedGrid) rule else rule + 1
                ruleX += strokePx + (widths.getOrNull(nextColumn) ?: 0)
            }
        }
    }
}

private const val MaxColumnWidthPx = 16_383

/**
 * Column widths of an automatic-layout table (CSS 2.1 §17.5.2.2, as browsers implement it) whose
 * columns have the given min-content [minWidths] and max-content [maxWidths], given
 * [availableWidth] for all columns together:
 * - everything fits on one line: every column takes its max-content width (the table hugs its
 *   content and does not stretch);
 * - not even the min-content widths fit: every column takes its min-content width and the table
 *   overflows (the caller scrolls it);
 * - otherwise every column gets its min-content width plus a share of the remaining width in
 *   proportion to how much it would grow to reach its max-content width; the result fills
 *   [availableWidth] exactly.
 */
internal fun markdownTableColumnWidths(minWidths: IntArray, maxWidths: IntArray, availableWidth: Int): IntArray {
    require(minWidths.size == maxWidths.size)
    val min = IntArray(minWidths.size) { minWidths[it].coerceAtLeast(0) }
    val max = IntArray(minWidths.size) { maxOf(maxWidths[it], min[it]) }
    val minSum = min.fold(0L) { sum, width -> sum + width }
    val maxSum = max.fold(0L) { sum, width -> sum + width }
    if (maxSum <= availableWidth) return max
    if (minSum >= availableWidth) return min
    val extra = availableWidth - minSum
    val growth = maxSum - minSum
    val widths = IntArray(min.size) { min[it] + (extra * (max[it] - min[it]) / growth).toInt() }
    var leftover = availableWidth - widths.fold(0L) { sum, width -> sum + width }
    while (leftover > 0) {
        for (column in widths.indices) {
            if (leftover == 0L) break
            if (widths[column] < max[column]) {
                widths[column]++
                leftover--
            }
        }
    }
    return widths
}

@Composable
fun MarkdownTableBasicText(
    content: String,
    cell: ASTNode,
    style: TextStyle,
    modifier: Modifier,
    maxLines: Int,
    overflow: TextOverflow = TextOverflow.Clip,
    annotatorSettings: AnnotatorSettings = annotatorSettings(),
) {
    val str = content.buildMarkdownAnnotatedString(
        textNode = cell,
        style = style,
        annotatorSettings = annotatorSettings,
    )

    MarkdownText(
        content = str,
        modifier = modifier,
        style = style.merge(
            color = LocalMarkdownColors.current.tableText,
        ),
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = null
    )
}
