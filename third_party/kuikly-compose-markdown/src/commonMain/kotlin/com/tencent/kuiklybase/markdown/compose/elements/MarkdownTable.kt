package com.tencent.kuiklybase.markdown.compose.elements

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.extension.bouncesEnable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.layout.wrapContentHeight
import com.tencent.kuikly.compose.foundation.layout.wrapContentWidth
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.composed
import com.tencent.kuikly.compose.ui.layout.layout
import com.tencent.kuikly.compose.ui.layout.onSizeChanged
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.IntSize
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.isSpecified
import com.tencent.kuikly.compose.ui.unit.isUnspecified
import com.tencent.kuiklybase.markdown.annotator.AnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.annotatorSettings
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownColors
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownComponents
import com.tencent.kuiklybase.markdown.compose.LocalMarkdownDimens
import com.tencent.kuiklybase.markdown.compose.handleElement
import org.intellij.markdown.MarkdownElementTypes.IMAGE
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.TABLE_SEPARATOR

@Composable
fun MarkdownTable(
    content: String,
    node: ASTNode,
    style: TextStyle,
    annotatorSettings: AnnotatorSettings = annotatorSettings(),
    headerBlock: @Composable (String, ASTNode, TextStyle, MutableMap<Int, Dp>, MutableState<Dp>) -> Unit = { content, header, style, columnWidths, rowWidths ->
        MarkdownTableHeader(
            content = content,
            header = header,
            style = style,
            annotatorSettings = annotatorSettings,
            columnWidths = columnWidths,
            rowWidths = rowWidths
        )
    },
    rowBlock: @Composable (String, ASTNode, TextStyle, MutableMap<Int, Dp>, MutableState<Dp>) -> Unit = { content, header, style, columnWidths, rowWidths ->
        MarkdownTableContent(
            content = content,
            header = header,
            style = style,
            annotatorSettings = annotatorSettings,
            columnWidths = columnWidths,
            rowWidths = rowWidths
        )
    },
    onSizeChanged: ((IntSize, MutableMap<Int, Dp>) -> Unit)? = null
) {
    val columnWidths = remember { mutableStateMapOf<Int, Dp>() }
    val rowWidths = remember { mutableStateOf(0.dp) }
    val tableStoke = LocalMarkdownColors.current.tableStroke
    val tableSeparatorThickness = LocalMarkdownDimens.current.tableSeparatorThickness

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .bouncesEnable(false)
            .onSizeChangedDp { onSizeChanged?.invoke(it, columnWidths) }
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                node.children.forEachIndexed { index, node ->
                    // 表格少一条边框
                    if (index == 0 && node.type != TABLE_SEPARATOR) {
                        MarkdownDivider(
                            modifier = Modifier.width(rowWidths.value),
                            color = tableStoke,
                            thickness = tableSeparatorThickness
                        )
                    }
                    when (node.type) {
                        HEADER -> headerBlock(
                            content,
                            node,
                            style,
                            columnWidths,
                            rowWidths
                        )

                        ROW -> rowBlock(content, node, style, columnWidths, rowWidths)
                        TABLE_SEPARATOR -> MarkdownDivider(
                            modifier = Modifier.width(rowWidths.value),
                            color = tableStoke,
                            thickness = tableSeparatorThickness
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MarkdownTableHeader(
    content: String,
    header: ASTNode,
    style: TextStyle,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    annotatorSettings: AnnotatorSettings = annotatorSettings(),
    columnWidths: MutableMap<Int, Dp>,
    rowWidths: MutableState<Dp>
) {
    val tableHeaderBackground = LocalMarkdownColors.current.tableHeaderBackground

    Column(modifier = Modifier.fillMaxWidth().background(tableHeaderBackground)) {
        Row(
            verticalAlignment = verticalAlignment,
            modifier = Modifier
                .wrapContentWidth()
                .onSizeChangedDp { rowWidths.value = it.width.dp },
        ) {
            MarkdownTableRow(
                content = content,
                node = header,
                style = style.merge(fontWeight = FontWeight.Bold),
                maxLines = maxLines,
                verticalAlignment = verticalAlignment,
                overflow = overflow,
                annotatorSettings = annotatorSettings,
                columnWidths = columnWidths
            )
        }
    }
}

@Composable
fun MarkdownTableContent(
    content: String,
    header: ASTNode,
    style: TextStyle,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    annotatorSettings: AnnotatorSettings = annotatorSettings(),
    columnWidths: MutableMap<Int, Dp>,
    rowWidths: MutableState<Dp>
) {
    val tableStoke = LocalMarkdownColors.current.tableStroke
    val tableSeparatorThickness = LocalMarkdownDimens.current.tableSeparatorThickness

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = verticalAlignment,
            modifier = Modifier.wrapContentWidth()
        ) {
            MarkdownTableRow(
                content = content,
                node = header,
                style = style,
                maxLines = maxLines,
                verticalAlignment = verticalAlignment,
                overflow = overflow,
                annotatorSettings = annotatorSettings,
                columnWidths = columnWidths
            )
        }
        MarkdownDivider(
            modifier = Modifier.width(rowWidths.value),
            color = tableStoke,
            thickness = tableSeparatorThickness
        )
    }
}

@Composable
private fun MarkdownTableRow(
    content: String,
    node: ASTNode,
    style: TextStyle,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    annotatorSettings: AnnotatorSettings = annotatorSettings(),
    columnWidths: MutableMap<Int, Dp>,
) {
    var height by remember { mutableStateOf(0.dp) }
    val markdownComponents = LocalMarkdownComponents.current
    val tableCellPadding = LocalMarkdownDimens.current.tableCellPadding
    val tableSeparatorThickness = LocalMarkdownDimens.current.tableSeparatorThickness
    val tableStoke = LocalMarkdownColors.current.tableStroke

    node.children.forEachIndexed { index, cell ->
        if (cell.type == CELL) {
            Row {
                if (cell.children.any { it.type == IMAGE }) {
                    handleElement(
                        node = cell,
                        components = markdownComponents,
                        content = content,
                        includeSpacer = false
                    )
                } else {
                    MarkdownTableCell(
                        content = content,
                        cell = cell,
                        index = index,
                        style = style,
                        maxLines = maxLines,
                        overflow = overflow,
                        annotatorSettings = annotatorSettings,
                        columnWidths = columnWidths,
                    ) {
                        height = maxOf(height, it.height.dp)
                    }
                }
            }
        } else if (cell.type == TABLE_SEPARATOR) {
            VerticalMarkdownDivider(
                modifier = Modifier.height(height + tableCellPadding * 2),
                color = tableStoke,
                thickness = tableSeparatorThickness
            )
        }
    }
}

@Composable
private fun MarkdownTableCell(
    content: String,
    cell: ASTNode,
    index: Int,
    style: TextStyle,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    annotatorSettings: AnnotatorSettings = annotatorSettings(),
    columnWidths: MutableMap<Int, Dp>,
    onSizeChanged: (IntSize) -> Unit = {}
) {
    val tableCellMaxWidth = LocalMarkdownDimens.current.tableCellWidth
    val tableCellPadding = LocalMarkdownDimens.current.tableCellPadding

    // 让同一列单元格宽度相同
    val columnWidth = when {
        columnWidths[index] != null -> columnWidths[index]!!
        tableCellMaxWidth.isUnspecified -> Int.MAX_VALUE.dp
        else -> tableCellMaxWidth
    }

    Box(modifier = Modifier.padding(tableCellPadding).width(columnWidth)) {

        val str = content.buildMarkdownAnnotatedString(
            textNode = cell,
            style = style,
            annotatorSettings = annotatorSettings,
        )

        MarkdownTableBasicText(
//            text = str,
            content = content,
            cell = cell,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
            annotatorSettings = annotatorSettings,
            modifier = Modifier
                .wrapContentWidth(unbounded = false)
                .layout { measurable, constraints ->
                    // 使用无限宽度约束测量
                    val placeable =
                        measurable.measure(constraints.copy(minWidth = 0, maxWidth = tableCellMaxWidth.roundToPx()))
                    layout(placeable.width, placeable.height) {
                        placeable.place(0, 0)
                    }
                }
                .onSizeChangedDp { size ->
                    onSizeChanged(size)
                    val newWidth = size.width.dp
                    if (tableCellMaxWidth.isSpecified) {
                        // 固定单元格宽度
                        columnWidths[index] = maxOf(columnWidths[index] ?: 0.dp, minOf(tableCellMaxWidth, newWidth))
                    } else {
                        // 未限制单元格宽度，则默认无限大
                        columnWidths[index] = maxOf(columnWidths[index] ?: 0.dp, newWidth)
                    }
                }
        )
    }
}

@Composable
fun MarkdownTableBasicText(
    content: String,
    cell: ASTNode,
    style: TextStyle,
    modifier: Modifier,
    maxLines: Int,
    overflow: TextOverflow = TextOverflow.Ellipsis,
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

private fun Modifier.onSizeChangedDp(
    onSizeChanged: (IntSize) -> Unit
): Modifier = composed {
    val density = LocalDensity.current
    this.onSizeChanged {
        with(density) {
            val dpSize = IntSize(
                width = it.width.toDp().value.toInt(),
                height = it.height.toDp().value.toInt()
            )
            onSizeChanged(dpSize)
        }
    }
}