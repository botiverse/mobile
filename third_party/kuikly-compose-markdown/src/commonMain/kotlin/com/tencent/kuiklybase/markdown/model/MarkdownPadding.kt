package com.tencent.kuiklybase.markdown.model

import androidx.compose.runtime.Immutable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

interface MarkdownPadding {
    val block: Dp

    /** Padding top and bottom of a list (per layer) */
    val list: Dp

    /** Padding top of a list item */
    val listItemTop: Dp

    /** Padding bottom of a list item */
    val listItemBottom: Dp

    /** The indent per list level */
    val listIndent: Dp
    val codeBlock: PaddingValues
    val blockQuote: PaddingValues
    val blockQuoteText: PaddingValues
    val blockQuoteBar: PaddingValues.Absolute

    val paragraphVerPadding: PaddingValues
}

@Immutable
private class DefaultMarkdownPadding(
    override val block: Dp,
    override val list: Dp,
    override val listItemTop: Dp,
    override val listItemBottom: Dp,
    override val listIndent: Dp,
    override val codeBlock: PaddingValues,
    override val blockQuote: PaddingValues,
    override val blockQuoteText: PaddingValues,
    override val blockQuoteBar: PaddingValues.Absolute,
    override val paragraphVerPadding: PaddingValues
) : MarkdownPadding

fun markdownPadding(
    block: Dp = 2.dp,
    list: Dp = 0.dp,
    listItemTop: Dp = 0.dp,
    listItemBottom: Dp = 4.dp,
    /** Deprecated, please use `listIndent` instead */
    indentList: Dp? = null,
    listIndent: Dp = 4.dp,
    codeBlock: PaddingValues = PaddingValues(8.dp),
    blockQuote: PaddingValues = PaddingValues(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 0.dp),
    blockQuoteText: PaddingValues = PaddingValues(start = 0.dp, top = 4.dp, end = 0.dp, bottom = 4.dp),
    blockQuoteBar: PaddingValues.Absolute = PaddingValues.Absolute(
        left = 4.dp,
        top = 2.dp,
        right = 4.dp,
        bottom = 2.dp
    ),
    paragraphVerPadding: PaddingValues = PaddingValues(vertical = 8.dp)
): MarkdownPadding = DefaultMarkdownPadding(
    block = block,
    list = list,
    listItemTop = listItemTop,
    listItemBottom = listItemBottom,
    listIndent = indentList ?: listIndent,
    codeBlock = codeBlock,
    blockQuote = blockQuote,
    blockQuoteText = blockQuoteText,
    blockQuoteBar = blockQuoteBar,
    paragraphVerPadding = paragraphVerPadding
)

fun MarkdownPadding.merge(
    block: Dp? = null,
    list: Dp? = null,
    listItemTop: Dp? = null,
    listItemBottom: Dp? = null,
    /** Deprecated, please use `listIndent` instead */
    indentList: Dp? = null,
    listIndent: Dp? = null,
    codeBlock: PaddingValues? = null,
    blockQuote: PaddingValues? = null,
    blockQuoteText: PaddingValues? = null,
    blockQuoteBar: PaddingValues.Absolute? = null,
    paragraphVerPadding: PaddingValues? = null
): MarkdownPadding {
    return DefaultMarkdownPadding(
        block = block ?: this.block,
        list = list ?: this.list,
        listItemTop = listItemTop ?: this.listItemTop,
        listItemBottom = listItemBottom ?: this.listItemBottom,
        listIndent = indentList ?: listIndent ?: this.listIndent,
        codeBlock = codeBlock ?: this.codeBlock,
        blockQuote = blockQuote ?: this.blockQuote,
        blockQuoteText = blockQuoteText ?: this.blockQuoteText,
        blockQuoteBar = blockQuoteBar ?: this.blockQuoteBar,
        paragraphVerPadding = paragraphVerPadding ?: this.paragraphVerPadding
    )
}
