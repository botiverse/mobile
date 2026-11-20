package build.raft.app.markdown

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Unit tests for per-block RTL text direction and link bidi isolation
 * (botiverse/slock #15039 `tests/textDirection.test.ts`).
 */
class RaftTextDirectionTest {
    @Test
    fun rtlScriptDecidesRtlOtherLetterDecidesLtrAndNoneReturnsNull() {
        assertEquals(RaftTextDirection.Rtl, raftTextDirection("אפשר לבדוק את TASK-123 היום?"))
        assertEquals(RaftTextDirection.Rtl, raftTextDirection("مرحبا بالعالم"))
        assertEquals(RaftTextDirection.Ltr, raftTextDirection("Can you check TASK-123 today?"))
        assertEquals(RaftTextDirection.Ltr, raftTextDirection("你好"))
        assertNull(raftTextDirection("123 ?!"))
        assertNull(raftTextDirection(""))
    }

    @Test
    fun mentionsRefsLinksAndInlineCodeDoNotDecideDirection() {
        assertEquals(RaftTextDirection.Rtl, raftTextDirection("@alice אפשר לבדוק את TASK-123 היום?"))
        assertEquals(RaftTextDirection.Rtl, raftTextDirection("@alice @bob שלום"))
        assertEquals(RaftTextDirection.Rtl, raftTextDirection("#general https://example.com `code` שלום"))
        assertNull(raftTextDirection("@alice"))
        assertNull(raftTextDirection("#general https://example.com `code`"))
    }

    @Test
    fun markdownBlocksSkipLinksAndInlineCodeWhenDecidingDirection() {
        val hebrewWithCodeAndLink = "[Alice Smith](https://example.com) `npm run` אפשר לבדוק את TASK-123 היום?"
        val hebrewParagraph = firstNodeOfType(hebrewWithCodeAndLink, MarkdownElementTypes.PARAGRAPH)
        assertEquals(RaftTextDirection.Rtl, raftMarkdownBlockDirection(hebrewWithCodeAndLink, hebrewParagraph))

        val englishWithLink = "[Alice Smith](https://example.com) Can you check TASK-123 today?"
        val englishParagraph = firstNodeOfType(englishWithLink, MarkdownElementTypes.PARAGRAPH)
        assertEquals(RaftTextDirection.Ltr, raftMarkdownBlockDirection(englishWithLink, englishParagraph))

        val hebrewHeading = "## `api` שלום עולם"
        val headingNode = firstNodeOfType(hebrewHeading, MarkdownElementTypes.ATX_2)
        assertEquals(RaftTextDirection.Rtl, raftMarkdownBlockDirection(hebrewHeading, headingNode))

        val hebrewQuote = "> @alice שלום מציטוט"
        val quoteNode = firstNodeOfType(hebrewQuote, MarkdownElementTypes.BLOCK_QUOTE)
        assertEquals(RaftTextDirection.Rtl, raftMarkdownBlockDirection(hebrewQuote, quoteNode))
    }

    @Test
    fun listTakesDirectionFromItsTextWhileEachItemDecidesItsOwnParagraph() {
        val mixedList = "- פריט ראשון\n- second item"
        val tree = parse(mixedList)
        val ul = findNode(tree, MarkdownElementTypes.UNORDERED_LIST)!!
        assertEquals(RaftTextDirection.Rtl, raftMarkdownBlockDirection(mixedList, ul))

        val paragraphs = collectNodes(ul, MarkdownElementTypes.PARAGRAPH)
        assertEquals(2, paragraphs.size)
        assertEquals(RaftTextDirection.Rtl, raftMarkdownBlockDirection(mixedList, paragraphs[0]))
        assertEquals(RaftTextDirection.Ltr, raftMarkdownBlockDirection(mixedList, paragraphs[1]))

        val tableMd = "| כותרת | Header |\n| --- | --- |\n| תא עברי | english cell |"
        val table = firstNodeOfType(tableMd, GFMElementTypes.TABLE)
        val cells = collectNodes(table, GFMTokenTypes.CELL)
        assertEquals(
            listOf(RaftTextDirection.Rtl, RaftTextDirection.Ltr, RaftTextDirection.Rtl, RaftTextDirection.Ltr),
            cells.map { raftMarkdownBlockDirection(tableMd, it) },
        )
    }

    @Test
    fun composeHelpersMapDirectionCleanly() {
        assertEquals(TextDirection.Rtl, RaftTextDirection.Rtl.toComposeTextDirection())
        assertEquals(TextDirection.Ltr, RaftTextDirection.Ltr.toComposeTextDirection())
        assertEquals(TextDirection.Content, (null as RaftTextDirection?).toComposeTextDirection())

        assertEquals(LayoutDirection.Rtl, RaftTextDirection.Rtl.toComposeLayoutDirection(LayoutDirection.Ltr))
        assertEquals(LayoutDirection.Ltr, RaftTextDirection.Ltr.toComposeLayoutDirection(LayoutDirection.Rtl))
        assertEquals(LayoutDirection.Ltr, (null as RaftTextDirection?).toComposeLayoutDirection(LayoutDirection.Ltr))

        val base = TextStyle.Default
        assertSame(base, base.withRaftTextDirection(null))
        assertEquals(TextDirection.Rtl, base.withRaftTextDirection(RaftTextDirection.Rtl).textDirection)
        assertEquals(TextDirection.Ltr, base.withRaftTextDirection(RaftTextDirection.Ltr).textDirection)
    }

    @Test
    fun rtlLinkBidiIsolationWrapsLinksPreservesAnnotationOffsetsAndCleansOnCopy() {
        val original =
            buildAnnotatedString {
                withLink(LinkAnnotation.Clickable(tag = "mention-1", linkInteractionListener = {})) {
                    pushStringAnnotation(tag = "box", annotation = "chip")
                    append("@alice")
                    pop()
                }
                append(" אפשר לבדוק ")
                withLink(LinkAnnotation.Url("https://example.com")) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("https://example.com")
                    }
                }
                append(" היום?")
            }

        val isolated = original.withRaftRtlLinkBidiIsolation()
        assertEquals(
            "${RAFT_BIDI_FSI}@alice${RAFT_BIDI_PDI} אפשר לבדוק ${RAFT_BIDI_FSI}https://example.com${RAFT_BIDI_PDI} היום?",
            isolated.text,
        )

        val links = isolated.getLinkAnnotations(0, isolated.length)
        assertEquals(2, links.size)
        assertEquals("@alice", isolated.text.substring(links[0].start, links[0].end))
        assertEquals("https://example.com", isolated.text.substring(links[1].start, links[1].end))

        val box = isolated.getStringAnnotations("box", 0, isolated.length).single()
        assertEquals("@alice", isolated.text.substring(box.start, box.end))

        val bold = isolated.spanStyles.single()
        assertEquals("https://example.com", isolated.text.substring(bold.start, bold.end))

        // Copy / accessibility strips FSI/PDI back to exact rendered source.
        assertEquals(original.text, isolated.text.raftCopyText())
    }

    private fun parse(markdown: String): ASTNode =
        MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)

    private fun firstNodeOfType(markdown: String, type: org.intellij.markdown.IElementType): ASTNode =
        findNode(parse(markdown), type) ?: error("Expected node $type in: $markdown")

    private fun findNode(node: ASTNode, type: org.intellij.markdown.IElementType): ASTNode? {
        if (node.type == type) return node
        for (child in node.children) {
            findNode(child, type)?.let { return it }
        }
        return null
    }

    private fun collectNodes(node: ASTNode, type: org.intellij.markdown.IElementType): List<ASTNode> {
        val result = mutableListOf<ASTNode>()
        fun visit(current: ASTNode) {
            if (current.type == type) result += current
            for (child in current.children) visit(child)
        }
        visit(node)
        return result
    }
}
