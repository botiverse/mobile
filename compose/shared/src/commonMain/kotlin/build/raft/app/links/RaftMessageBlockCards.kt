package build.raft.app.links

import build.raft.app.data.AppLinkResult
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

/*
 * Where a message draws its App link block cards (raft-design #1010 navigation.md "Block cards";
 * RFC 076 sections 13 and 14): after the paragraph the link is in, never in place of the link.
 */

/** web: at most 3 block cards in a message. */
internal const val MESSAGE_BLOCK_CARD_LIMIT: Int = 3

/**
 * The block cards of [content], by the start offset of the paragraph each follows (the markdown
 * node's `startOffset` in [content]), in the order their links come:
 *
 *  - only a bare link whose result is a [AppLinkResult.Card] with a [AppLinkResult.Card.block];
 *  - a link in a paragraph, or in a list item (its paragraph is inside the item, so the card is
 *    too); in a table, a heading or a block quote the author wrote only the chip shows, and such a
 *    link neither gets a card nor counts toward the limit;
 *  - the same URL once, at its first occurrence that can have a card;
 *  - at most [MESSAGE_BLOCK_CARD_LIMIT] in all, in message order;
 *  - several in one paragraph follow it in link order.
 */
internal fun planRaftBlockCards(content: String, results: Map<String, AppLinkResult>): Map<Int, List<AppLinkResult.Card>> {
    if (results.values.none { it is AppLinkResult.Card && it.block != null }) return emptyMap()
    val plan = LinkedHashMap<Int, MutableList<AppLinkResult.Card>>()
    val drawn = HashSet<String>()
    var count = 0
    scanRaftBareLinks(content) { href, node ->
        if (count >= MESSAGE_BLOCK_CARD_LIMIT || href in drawn) return@scanRaftBareLinks
        val card = results[href] as? AppLinkResult.Card ?: return@scanRaftBareLinks
        if (card.block == null) return@scanRaftBareLinks
        val paragraph = node.blockCardParagraph() ?: return@scanRaftBareLinks
        drawn += href
        count += 1
        plan.getOrPut(paragraph.startOffset) { mutableListOf() } += card
    }
    return plan
}

/** The paragraph a card may follow for a link found in this node, or null where only the chip shows. */
private fun ASTNode.blockCardParagraph(): ASTNode? {
    var paragraph: ASTNode? = null
    var node: ASTNode? = this
    while (node != null) {
        when (node.type) {
            MarkdownElementTypes.PARAGRAPH -> if (paragraph == null) paragraph = node
            MarkdownElementTypes.BLOCK_QUOTE,
            GFMElementTypes.TABLE,
            MarkdownElementTypes.ATX_1,
            MarkdownElementTypes.ATX_2,
            MarkdownElementTypes.ATX_3,
            MarkdownElementTypes.ATX_4,
            MarkdownElementTypes.ATX_5,
            MarkdownElementTypes.ATX_6,
            MarkdownElementTypes.SETEXT_1,
            MarkdownElementTypes.SETEXT_2,
            -> return null
        }
        node = node.parent
    }
    return paragraph
}
