package com.tencent.kuiklybase.markdown.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

interface MarkdownDimens {
    val dividerThickness: Dp
    val codeBackgroundCornerSize: Dp
    val blockQuoteThickness: Dp
    val tableMaxWidth: Dp
    val tableCellWidth: Dp
    val tableSeparatorThickness: Dp
    val tableCellPadding: Dp
    val tableCornerSize: Dp
}

@Immutable
private class DefaultMarkdownDimens(
    override val dividerThickness: Dp,
    override val codeBackgroundCornerSize: Dp,
    override val blockQuoteThickness: Dp,
    override val tableMaxWidth: Dp,
    override val tableSeparatorThickness: Dp,
    override val tableCellWidth: Dp,
    override val tableCellPadding: Dp,
    override val tableCornerSize: Dp,
) : MarkdownDimens

@Composable
fun markdownDimens(
    dividerThickness: Dp = 0.5.dp,
    codeBackgroundCornerSize: Dp = 8.dp,
    blockQuoteThickness: Dp = 1.dp,
    tableMaxWidth: Dp = Dp.Unspecified,
    tableCellWidth: Dp = 160.dp,
    tableSeparatorThickness: Dp = 1.dp,
    tableCellPadding: Dp = 12.dp,
    tableCornerSize: Dp = 8.dp,
): MarkdownDimens = DefaultMarkdownDimens(
    dividerThickness = dividerThickness,
    codeBackgroundCornerSize = codeBackgroundCornerSize,
    blockQuoteThickness = blockQuoteThickness,
    tableMaxWidth = tableMaxWidth,
    tableSeparatorThickness = tableSeparatorThickness,
    tableCellWidth = tableCellWidth,
    tableCellPadding = tableCellPadding,
    tableCornerSize = tableCornerSize,
)

fun MarkdownDimens.merge(
    dividerThickness: Dp? = null,
    codeBackgroundCornerSize: Dp? = null,
    blockQuoteThickness: Dp? = null,
    tableMaxWidth: Dp? = null,
    tableSeparatorThickness: Dp? = null,
    tableCellWidth: Dp? = null,
    tableCellPadding: Dp? = null,
    tableCornerSize: Dp? = null,
): MarkdownDimens {
    return DefaultMarkdownDimens(
        dividerThickness = dividerThickness ?: this.dividerThickness,
        codeBackgroundCornerSize = codeBackgroundCornerSize ?: this.codeBackgroundCornerSize,
        blockQuoteThickness = blockQuoteThickness ?: this.blockQuoteThickness,
        tableMaxWidth = tableMaxWidth ?: this.tableMaxWidth,
        tableCellWidth = tableCellWidth ?: this.tableCellWidth,
        tableSeparatorThickness = tableSeparatorThickness ?: this.tableSeparatorThickness,
        tableCellPadding = tableCellPadding ?: this.tableCellPadding,
        tableCornerSize = tableCornerSize ?: this.tableCornerSize,
    )
}
