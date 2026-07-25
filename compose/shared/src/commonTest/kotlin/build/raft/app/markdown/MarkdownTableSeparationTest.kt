package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.model.SlockGFMFlavourDescriptor
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Raft tables may directly follow prose without a blank line. The canonical
 * flavour recognizes that chat-friendly continuation at the block-parser
 * boundary while preserving the original source and offsets.
 */
class MarkdownTableSeparationTest {

    private val tightTable =
        "**Hard assertions**：\n" +
            "| Assertion | Result |\n" +
            "|-----------|--------|\n" +
            "| Submit indicator | PASS |"

    @Test
    fun tightTableParsesWithoutRewritingSource() {
        val table = parseTable(tightTable)

        assertNotNull(table, "Raft flavour must parse the tight header as a TABLE")
        assertEquals(tightTable.indexOf("| Assertion"), table.startOffset)
        assertEquals(tightTable.length, table.endOffset)
        assertFalse(parseContainsTable(tightTable, GFMFlavourDescriptor()))
    }

    @Test
    fun alreadySeparatedTableIsUnchanged() {
        val body = "Intro line.\n\n| A | B |\n|---|---|\n| 1 | 2 |"
        val table = parseTable(body)

        assertNotNull(table)
        assertEquals(body.indexOf("| A | B |"), table.startOffset)
    }

    @Test
    fun pipesInsideCodeFenceAreUntouched() {
        val body =
            "Shell example:\n" +
                "```\n" +
                "| not | a | table |\n" +
                "|-----|---|-------|\n" +
                "```"
        assertFalse(parseContainsTable(body))
    }

    @Test
    fun pipeTextWithoutSeparatorRowIsUntouched() {
        val body = "Options are read | write | admin\n| but this line has pipes |\nplain tail."
        assertFalse(parseContainsTable(body))
    }

    @Test
    fun tableAfterTableRowIsNotDoubleSeparated() {
        val body = "| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |"
        val table = parseTable(body)

        assertNotNull(table)
        assertEquals(body.length, table.endOffset)
    }

    @Test
    fun tightTableWithCrLfKeepsOriginalOffsets() {
        val body = "结论：\r\n| 项 | 值 |\r\n|---|---|\r\n| 状态 | PASS |"
        val table = parseTable(body)

        assertNotNull(table)
        assertEquals(body.indexOf("| 项"), table.startOffset)
        assertEquals(body.length, table.endOffset)
    }

    @Test
    fun customMarkerProcessorPreservesGfmTaskListAndAlertProviders() {
        val tree = parseTree("- [x] done\n\n> [!NOTE]\n> body")

        assertNotNull(tree.findFirst(GFMTokenTypes.CHECK_BOX))
        assertNotNull(tree.findFirst(GFMElementTypes.ALERT))
    }

    @Test
    fun escapedPipeIsCellContentNotAnExtraDelimiter() {
        val body = "Intro\na \\| b | c\n---|---\nleft|right"
        val table = parseTable(body)

        assertNotNull(table)
        assertEquals(body.indexOf("a \\| b | c"), table.startOffset)
    }

    @Test
    fun escapedPipeAloneDoesNotCreateSingleColumnTable() {
        val body = "Intro\na \\| b\n---\nTail"

        assertFalse(parseContainsTable(body))
    }

    @Test
    fun bareDashesKeepSetextMeaning() {
        val body = "some text\n---"

        assertFalse(parseContainsTable(body))
        assertEquals(
            astSignature(body, GFMFlavourDescriptor()),
            astSignature(body, SlockGFMFlavourDescriptor()),
        )
    }

    @Test
    fun singleColumnTableRequiresAnUnescapedPipeInHeader() {
        val positive = "Intro\n| a |\n|---|\n| 1 |"
        val negative = "Intro\na\n|---|\nTail"

        assertNotNull(parseTable(positive))
        assertFalse(parseContainsTable(negative))
    }

    @Test
    fun blockQuoteLazySeparatorDoesNotInterruptParagraph() {
        val body = "> Intro\n> a | b\n---|---\nTail"

        assertFalse(parseContainsTable(body))
        assertEquals(
            astSignature(body, GFMFlavourDescriptor()),
            astSignature(body, SlockGFMFlavourDescriptor()),
        )
    }

    @Test
    fun nonTightCanonicalGfmAstMatchesStockParser() {
        val body =
            """
            # Heading

            - [x] done
            - ordinary item

            > [!NOTE]
            > body

            | A | B |
            |---|---|
            | 1 | 2 |

            `code` and **strong** text.
            """.trimIndent()

        assertEquals(
            astSignature(body, GFMFlavourDescriptor()),
            astSignature(body, SlockGFMFlavourDescriptor()),
        )
    }

    private fun parseTable(content: String): ASTNode? =
        parseTree(content).findFirst(GFMElementTypes.TABLE)

    private fun parseTree(content: String): ASTNode =
        MarkdownParser(SlockGFMFlavourDescriptor()).buildMarkdownTreeFromString(content)

    private fun parseContainsTable(
        content: String,
        flavour: GFMFlavourDescriptor = SlockGFMFlavourDescriptor(),
    ): Boolean =
        MarkdownParser(flavour)
            .buildMarkdownTreeFromString(content)
            .findFirst(GFMElementTypes.TABLE) != null

    private fun astSignature(content: String, flavour: GFMFlavourDescriptor): List<String> =
        buildList {
            fun walk(node: ASTNode) {
                add("${node.type}:${node.startOffset}:${node.endOffset}")
                node.children.forEach(::walk)
            }
            walk(MarkdownParser(flavour).buildMarkdownTreeFromString(content))
        }

    private fun ASTNode.findFirst(type: org.intellij.markdown.IElementType): ASTNode? {
        if (this.type == type) return this
        return children.firstNotNullOfOrNull { it.findFirst(type) }
    }
}
