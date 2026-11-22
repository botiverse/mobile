package build.raft.designsystem.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke

/** raft-ui TableHead / TableCell `align`: the same value on a column's heading and its cells. */
enum class RaftTableAlign(internal val alignment: Alignment) {
    Start(Alignment.TopStart),
    End(Alignment.TopEnd),
}

/** A [RaftTable] column: its heading (sentence case; it never wraps) and how it aligns. */
data class RaftTableColumn(val title: String, val align: RaftTableAlign = RaftTableAlign.Start)

private val RaftTableCellPaddingX = 12.dp
private val RaftTableCellPaddingY = 8.dp

/**
 * raft-ui Table in its TableScroll (`table.recipe.ts`): rows and columns of data, for values
 * compared down columns. The header row is `text-footnote` bold in `foreground-hint` and never
 * wraps; every cell has 12dp / 8dp of padding and aligns to the top; a `line-hairline` goes under
 * the header and every row but the last. No outer border, no header fill, no vertical lines. A
 * row under a mouse or trackpad lays `fill-subtle` over itself.
 *
 * A column takes the width of its content, as a browser's auto table layout gives it: with room to
 * spare each column grows in proportion to its content; without, each shrinks from its content
 * toward its heading's width and its text wraps. [minWidth] is the least width the columns read at:
 * a table wider than its container scrolls sideways in an area of its own (a thin scrollbar while
 * it scrolls) and never widens the page.
 *
 * [cell] draws one cell (the type is the caller's: raft-ui's is `text-callout` in `foreground`,
 * numbers in Space Mono). [caption] names the table to assistive tech.
 */
@Composable
fun RaftTable(
    columns: List<RaftTableColumn>,
    rowCount: Int,
    caption: String,
    modifier: Modifier = Modifier,
    minWidth: Dp = 0.dp,
    rowKey: (Int) -> Any = { it },
    cell: @Composable (row: Int, column: Int) -> Unit,
) {
    val colors = RaftTheme.colors
    val scroll = rememberScrollState()
    Box(
        modifier
            .fillMaxWidth()
            .raftScrollbar(scroll, trigger = RaftScrollbarTrigger.Hover, size = RaftScrollbarSize.Small)
            .horizontalScroll(scroll),
        // The table fills its container: the scroller's own width reaches it as the least.
        propagateMinConstraints = true,
    ) {
        Layout(
            modifier =
                Modifier.semantics {
                    contentDescription = caption
                    collectionInfo = CollectionInfo(rowCount = rowCount + 1, columnCount = columns.size)
                },
            content = {
                // One line or wash per row (the header first), under the cells.
                RaftTableRowBackground(line = true, hovers = false)
                for (row in 0 until rowCount) {
                    key(rowKey(row)) { RaftTableRowBackground(line = row != rowCount - 1, hovers = true) }
                }
                columns.forEachIndexed { index, column ->
                    RaftTableCell(
                        align = column.align,
                        modifier =
                            Modifier.semantics {
                                heading()
                                collectionItemInfo = CollectionItemInfo(0, 1, index, 1)
                            },
                    ) {
                        RaftText(
                            column.title,
                            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Bold),
                            color = colors.foregroundHint,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                for (row in 0 until rowCount) {
                    key(rowKey(row)) {
                        columns.forEachIndexed { index, column ->
                            RaftTableCell(
                                align = column.align,
                                modifier = Modifier.semantics { collectionItemInfo = CollectionItemInfo(row + 1, 1, index, 1) },
                            ) { cell(row, index) }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val rows = rowCount + 1
            val backgrounds = measurables.subList(0, rows)
            val cells = measurables.subList(rows, measurables.size)
            val widths =
                raftTableColumnWidths(
                    // The scroller passes its own width on as the least.
                    container = maxOf(constraints.minWidth, minWidth.roundToPx()),
                    minimums =
                        columns.indices.map { column ->
                            (0 until rows).maxOf { row ->
                                val measurable = cells[row * columns.size + column]
                                // A heading never wraps; a cell wraps down to its longest word.
                                if (row == 0) measurable.maxIntrinsicWidth(Constraints.Infinity) else measurable.minIntrinsicWidth(Constraints.Infinity)
                            }
                        },
                    contents =
                        columns.indices.map { column ->
                            (0 until rows).maxOf { row -> cells[row * columns.size + column].maxIntrinsicWidth(Constraints.Infinity) }
                        },
                )
            val placeables = cells.mapIndexed { index, measurable -> measurable.measure(Constraints.fixedWidth(widths[index % columns.size])) }
            val heights = List(rows) { row -> columns.indices.maxOfOrNull { placeables[row * columns.size + it].height } ?: 0 }
            val width = widths.sum()
            val rowPlaceables = backgrounds.mapIndexed { row, measurable -> measurable.measure(Constraints.fixed(width, heights[row])) }
            layout(width, heights.sum()) {
                var y = 0
                for (row in 0 until rows) {
                    rowPlaceables[row].placeRelative(0, y)
                    var x = 0
                    for (column in columns.indices) {
                        placeables[row * columns.size + column].placeRelative(x, y)
                        x += widths[column]
                    }
                    y += heights[row]
                }
            }
        }
    }
}

/**
 * CSS auto table layout for [RaftTable]: [contents] are the columns' one-line widths and [minimums]
 * the least each can be. The table is as wide as its [container], or as the minimums need. Room
 * over the contents is shared in proportion to them; with less, each column gives up its share of
 * the gap between its content and its minimum.
 */
internal fun raftTableColumnWidths(container: Int, minimums: List<Int>, contents: List<Int>): List<Int> {
    if (contents.isEmpty()) return emptyList()
    val least = minimums.sum()
    val wanted = contents.indices.sumOf { maxOf(contents[it], minimums[it]) }
    val total = maxOf(container, least)
    val exact =
        contents.indices.map { index ->
            val content = maxOf(contents[index], minimums[index])
            when {
                wanted <= total -> if (wanted == 0) total.toDouble() / contents.size else content.toDouble() * total / wanted
                else -> minimums[index] + (total - least).toDouble() * (content - minimums[index]) / (wanted - least)
            }
        }
    // Whole pixels that still add up: each column ends where the exact sum so far rounds to.
    var sum = 0.0
    var edge = 0
    return exact.map { width ->
        sum += width
        val next = (sum + 0.5).toInt()
        (next - edge).also { edge = next }
    }
}

@Composable
private fun RaftTableCell(align: RaftTableAlign, modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.padding(horizontal = RaftTableCellPaddingX, vertical = RaftTableCellPaddingY),
        contentAlignment = align.alignment,
    ) { content() }
}

@Composable
private fun RaftTableRowBackground(line: Boolean, hovers: Boolean) {
    val color = RaftTheme.colors.lineHairline
    val source = remember { MutableInteractionSource() }
    val overlay = rememberRaftStateOverlay(source, RaftHoverTier.Subtle, enabled = hovers)
    Box(
        Modifier
            .raftStateOverlay(overlay, RoundedCornerShape(0.dp))
            .raftPointerHover(source, enabled = hovers)
            .drawBehind {
                if (!line) return@drawBehind
                val stroke = RaftStroke.Hairline.toPx()
                drawRect(color, topLeft = Offset(0f, size.height - stroke), size = Size(size.width, stroke))
            },
    )
}
