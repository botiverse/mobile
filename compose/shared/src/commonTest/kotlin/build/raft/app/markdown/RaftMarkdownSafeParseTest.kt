package build.raft.app.markdown

import build.raft.app.links.extractFirstQuotedRaftPermalink
import build.raft.app.links.scanRaftBareLinks
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import com.tencent.kuiklybase.markdown.model.parseMarkdownInlineSyntax
import com.tencent.kuiklybase.markdown.model.parseMarkdownTopLevelNodes
import com.tencent.kuiklybase.markdown.model.prepareMarkdownContent
import kotlinx.coroutines.test.runTest
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownParsingException
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hands raft-android crash 82acc456 (1.14.0): `MarkdownParsingException: Intersecting parsed
 * nodes detected: Node(range=180..182, type=Markdown:BLOCK_QUOTE) vs Node(range=179..181,
 * type=Markdown:LINK_DESTINATION)` thrown out of the thread's Markdown pre-parse.
 *
 * Trigger: a link definition label (`[x]:`) at a block start whose destination is on the next
 * line and starts with a nested quote (`>> y`). The parser records the definition's ranges,
 * rejects the definition (text follows the destination), and the inner quote then starts inside
 * the recorded destination. Same shape as the report: quote at n+1..n+3 vs destination n..n+2.
 */
class RaftMarkdownSafeParseTest {
    private val minimal = "[x]:\n>> y"
    private val inMessage = "Notes for the review @alice\n\n[ref]:\n>> quoted https://example.test/a `code` #tag"

    @Test
    fun stockParserStillThrowsOnTheReportedShape() {
        for (input in listOf(minimal, inMessage)) {
            val error =
                assertFailsWith<MarkdownParsingException>(
                    "the vendored parser no longer rejects this input; the guard may be redundant",
                ) {
                    MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(input)
                }
            val message = error.message.orEmpty()
            assertTrue("Intersecting parsed nodes detected" in message, message)
            assertTrue("BLOCK_QUOTE" in message && "LINK_DESTINATION" in message, message)
        }
        val minimalMessage =
            assertFailsWith<MarkdownParsingException> {
                MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(minimal)
            }.message.orEmpty()
        assertTrue("range=6..8, type=Markdown:BLOCK_QUOTE" in minimalMessage, minimalMessage)
        assertTrue("range=5..7, type=Markdown:LINK_DESTINATION" in minimalMessage, minimalMessage)
    }

    @Test
    fun rejectedTextFallsBackToOnePlainParagraphAndLogsNoContent() {
        val lines = mutableListOf<String>()
        val tree =
            MarkdownParser(RaftGFMFlavourDescriptor())
                .buildMarkdownTreeOrPlainText(inMessage, source = "test", diagnostics = { lines += it })

        assertPlainText(tree, inMessage)
        assertEquals(
            listOf(
                "markdown_parse_fallback source=test length=${inMessage.length} " +
                    "reason=intersecting_parsed_nodes_detected fallback=plain_text",
            ),
            lines,
        )
        assertFalse(lines.single().contains("quoted") || lines.single().contains("ref"), lines.single())
    }

    @Test
    fun wellFormedTextParsesAsBeforeWithoutDiagnostics() {
        val lines = mutableListOf<String>()
        val source = "[x]: https://example.test/a\n\n> quote\n\nafter"
        val tree =
            MarkdownParser(RaftGFMFlavourDescriptor())
                .buildMarkdownTreeOrPlainText(source, source = "test", diagnostics = { lines += it })

        assertEquals(emptyList(), lines)
        val types = tree.children.map { it.type }
        assertTrue(MarkdownElementTypes.LINK_DEFINITION in types, types.toString())
        assertTrue(MarkdownElementTypes.BLOCK_QUOTE in types, types.toString())
    }

    @Test
    fun messageRenderPathsNoLongerThrow() = runTest {
        // The crashing path: thread pre-parse on the Markdown parse dispatcher.
        prepareMarkdownContent(inMessage)
        assertPlainTextNodes(parseMarkdownTopLevelNodes(minimal), minimal)
        parseMarkdownInlineSyntax(inMessage)
        raftMarkdownQuoteRanges(inMessage)
        raftCardMarkdownCheck(inMessage)
        scanRaftBareLinks(inMessage) { _, _ -> }
        extractFirstQuotedRaftPermalink(inMessage, setOf("example.test"), currentServerSlug = "s")
        flattenMarkdownPreview(inMessage)
    }

    private fun assertPlainText(tree: ASTNode, source: String) {
        assertEquals(MarkdownElementTypes.MARKDOWN_FILE, tree.type)
        assertPlainTextNodes(tree.children, source)
    }

    private fun assertPlainTextNodes(nodes: List<ASTNode>, source: String) {
        val paragraph = nodes.single()
        assertEquals(MarkdownElementTypes.PARAGRAPH, paragraph.type)
        val text = paragraph.children.single()
        assertEquals(MarkdownTokenTypes.TEXT, text.type)
        assertEquals(0 until source.length, text.startOffset until text.endOffset)
    }
}
