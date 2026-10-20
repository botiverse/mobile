package build.raft.app.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.tencent.kuiklybase.markdown.compose.LocalOrderedListHandler
import com.tencent.kuiklybase.markdown.compose.components.MarkdownComponent
import com.tencent.kuiklybase.markdown.compose.components.MarkdownComponents
import com.tencent.kuiklybase.markdown.compose.components.markdownComponents
import com.tencent.kuiklybase.markdown.compose.elements.MarkdownListItems
import com.tencent.kuiklybase.markdown.compose.elements.listDepth
import com.tencent.kuiklybase.markdown.compose.elements.material.MarkdownBasicText
import com.tencent.kuiklybase.markdown.utils.getUnescapedTextInNode
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

/**
 * Vertical margins of the web message Markdown blocks (slock `MarkdownContent`
 * BASE_MARKDOWN_COMPONENTS, compact density): `p` mb-1 last:mb-0, `ul`/`ol` mb-1,
 * `blockquote` my-1, table / hr / code block / image my-2, h1 mt-3 mb-1,
 * h2/h3 mt-2 mb-1, h4-h6 mt-1 mb-0.5. Adjacent margins collapse (CSS), so the
 * gap between two blocks is the larger of the two.
 */
internal data class RaftMarkdownBlockMargin(val top: Dp, val bottom: Dp)

internal fun raftMarkdownWebBlockMargin(type: IElementType): RaftMarkdownBlockMargin? =
    when (type) {
        MarkdownElementTypes.PARAGRAPH,
        MarkdownElementTypes.UNORDERED_LIST,
        MarkdownElementTypes.ORDERED_LIST -> RaftMarkdownBlockMargin(0.dp, 4.dp)
        MarkdownElementTypes.BLOCK_QUOTE -> RaftMarkdownBlockMargin(4.dp, 4.dp)
        GFMElementTypes.TABLE,
        MarkdownTokenTypes.HORIZONTAL_RULE,
        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.CODE_BLOCK -> RaftMarkdownBlockMargin(8.dp, 8.dp)
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.SETEXT_1 -> RaftMarkdownBlockMargin(12.dp, 4.dp)
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.SETEXT_2 -> RaftMarkdownBlockMargin(8.dp, 4.dp)
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6 -> RaftMarkdownBlockMargin(4.dp, 2.dp)
        else -> null
    }

/**
 * Space a block already paints inside itself: the rule and the code surface carry
 * their web `my-2` as their own vertical padding, so the gap must not add it again.
 */
private fun raftMarkdownBlockInternalInset(type: IElementType): RaftMarkdownBlockMargin =
    when (type) {
        MarkdownTokenTypes.HORIZONTAL_RULE,
        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.CODE_BLOCK -> RaftMarkdownBlockMargin(8.dp, 8.dp)
        else -> RaftMarkdownBlockMargin(0.dp, 0.dp)
    }

private fun ASTNode.isRaftMarkdownSpacingContainer(): Boolean =
    type == MarkdownElementTypes.MARKDOWN_FILE || type == MarkdownElementTypes.BLOCK_QUOTE

private fun ASTNode.raftMarkdownBlockSiblings(): List<ASTNode>? {
    val container = parent?.takeIf { it.isRaftMarkdownSpacingContainer() } ?: return null
    return container.children.filter { raftMarkdownWebBlockMargin(it.type) != null }
}

/** Space above [node] when it is the first block of the document (web keeps the first block's mt). */
internal fun raftMarkdownBlockGapBefore(node: ASTNode): Dp {
    val siblings = node.raftMarkdownBlockSiblings() ?: return 0.dp
    if (node.parent?.type != MarkdownElementTypes.MARKDOWN_FILE) return 0.dp
    if (siblings.firstOrNull() !== node) return 0.dp
    val margin = raftMarkdownWebBlockMargin(node.type) ?: return 0.dp
    return (margin.top - raftMarkdownBlockInternalInset(node.type).top).coerceAtLeast(0.dp)
}

/** Space below [node]: the collapsed margin to the next block, or its own bottom margin when last. */
internal fun raftMarkdownBlockGapAfter(node: ASTNode): Dp {
    val siblings = node.raftMarkdownBlockSiblings() ?: return 0.dp
    val margin = raftMarkdownWebBlockMargin(node.type) ?: return 0.dp
    val index = siblings.indexOfFirst { it === node }
    if (index < 0) return 0.dp
    val inset = raftMarkdownBlockInternalInset(node.type)
    val next = siblings.getOrNull(index + 1)
    if (next == null) {
        // `p` is last:mb-0; inside a quote the quote's own margin takes over.
        if (node.type == MarkdownElementTypes.PARAGRAPH) return 0.dp
        if (node.parent?.type != MarkdownElementTypes.MARKDOWN_FILE) return 0.dp
        return (margin.bottom - inset.bottom).coerceAtLeast(0.dp)
    }
    val nextMargin = raftMarkdownWebBlockMargin(next.type) ?: return 0.dp
    val collapsed = maxOf(margin.bottom, nextMargin.top)
    return (collapsed - inset.bottom - raftMarkdownBlockInternalInset(next.type).top).coerceAtLeast(0.dp)
}

private fun MarkdownComponent.withRaftBlockGaps(): MarkdownComponent {
    val inner = this
    return { model ->
        val before = raftMarkdownBlockGapBefore(model.node)
        if (before > 0.dp) Spacer(Modifier.height(before))
        inner(model)
        val after = raftMarkdownBlockGapAfter(model.node)
        if (after > 0.dp) Spacer(Modifier.height(after))
    }
}

/** Adds the web block margins around every block-level component. */
internal fun MarkdownComponents.withRaftWebBlockSpacing(): MarkdownComponents =
    markdownComponents(
        text = text,
        eol = eol,
        codeFence = codeFence.withRaftBlockGaps(),
        codeBlock = codeBlock.withRaftBlockGaps(),
        heading1 = heading1.withRaftBlockGaps(),
        heading2 = heading2.withRaftBlockGaps(),
        heading3 = heading3.withRaftBlockGaps(),
        heading4 = heading4.withRaftBlockGaps(),
        heading5 = heading5.withRaftBlockGaps(),
        heading6 = heading6.withRaftBlockGaps(),
        setextHeading1 = setextHeading1.withRaftBlockGaps(),
        setextHeading2 = setextHeading2.withRaftBlockGaps(),
        blockQuote = blockQuote.withRaftBlockGaps(),
        paragraph = paragraph.withRaftBlockGaps(),
        orderedList = orderedList.withRaftBlockGaps(),
        unorderedList = unorderedList.withRaftBlockGaps(),
        image = image,
        linkDefinition = @Suppress("DEPRECATION") linkDefinition,
        horizontalRule = horizontalRule.withRaftBlockGaps(),
        table = table.withRaftBlockGaps(),
        checkbox = checkbox,
        custom = custom,
    )

/** web `ul`/`ol` `pl-5`: the marker column every list item's text starts after. */
internal val RaftMarkdownListMarkerWidth = 20.dp

/** web `li` `mb-0.5`. */
internal val RaftMarkdownListItemBottom = 2.dp

// web `list-disc` on a 16px body: a ~5px disc, its left edge ~2.5px into the marker column.
private val RaftMarkdownBulletSize = 5.dp
private val RaftMarkdownBulletInset = 2.5.dp

// The space after the marker's period (`1. `) at the body size.
private val RaftMarkdownOrderedMarkerGap = 4.dp

private fun TextStyle.raftMarkdownMarkerLineHeight(): Dp =
    if (lineHeight.isSpecified) lineHeight.value.dp else 24.dp

/** web `ul list-disc pl-5`: a filled disc at every depth (each nested `ul` is `list-disc` too). */
internal val RaftMarkdownUnorderedList: MarkdownComponent = { model ->
    val style = model.typography.bullet
    MarkdownListItems(model.content, model.node, model.listDepth) { _, _ ->
        Box(
            modifier = Modifier.width(RaftMarkdownListMarkerWidth).height(style.raftMarkdownMarkerLineHeight()),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .offset(x = RaftMarkdownBulletInset)
                    .size(RaftMarkdownBulletSize)
                    .clip(CircleShape)
                    .background(style.color)
            )
        }
    }
}

/** web `ol list-decimal pl-5`: the `N.` marker ends just before the item text. */
internal val RaftMarkdownOrderedList: MarkdownComponent = { model ->
    val style = model.typography.ordered
    val depth = model.listDepth
    val handler = LocalOrderedListHandler.current
    MarkdownListItems(model.content, model.node, depth) { index, child ->
        Box(
            modifier = Modifier.width(RaftMarkdownListMarkerWidth).height(style.raftMarkdownMarkerLineHeight()),
            contentAlignment = Alignment.CenterEnd
        ) {
            MarkdownBasicText(
                text =
                    handler.transform(
                        type = MarkdownTokenTypes.LIST_NUMBER,
                        bullet = child?.getUnescapedTextInNode(model.content),
                        index = index,
                        depth = depth
                    ),
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Visible,
                modifier =
                    Modifier
                        .wrapContentWidth(align = Alignment.End, unbounded = true)
                        .padding(end = RaftMarkdownOrderedMarkerGap)
            )
        }
    }
}
