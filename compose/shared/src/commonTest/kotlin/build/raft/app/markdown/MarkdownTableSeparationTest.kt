package build.raft.app.markdown

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #497: GFM tables directly following a text line (no blank line) are
 * paragraph continuation per spec, so the parser rendered raw pipes while web
 * shows a table. `ensureThreadMarkdownTableSeparation` inserts the missing
 * blank line during body preparation; these tests pin both the string
 * transform and the parse outcome (a TABLE node must exist afterwards).
 */
class MarkdownTableSeparationTest {

    private val tightTable =
        "**Hard assertions**：\n" +
            "| Assertion | Result |\n" +
            "|-----------|--------|\n" +
            "| Submit indicator | PASS |"

    @Test
    fun tightTableGainsBlankLineAndParsesToTable() {
        val prepared = tightTable.ensureThreadMarkdownTableSeparation()
        assertTrue(
            prepared.contains("：\n\n| Assertion"),
            "blank line must be inserted before the header row; got: $prepared"
        )
        assertTrue(parseContainsTable(prepared), "prepared body must parse to a TABLE node")
        assertFalse(parseContainsTable(tightTable), "control: tight body must NOT parse to a TABLE node")
    }

    @Test
    fun alreadySeparatedTableIsUnchanged() {
        val body = "Intro line.\n\n| A | B |\n|---|---|\n| 1 | 2 |"
        assertEquals(body, body.ensureThreadMarkdownTableSeparation())
        assertTrue(parseContainsTable(body))
    }

    @Test
    fun pipesInsideCodeFenceAreUntouched() {
        val body =
            "Shell example:\n" +
                "```\n" +
                "| not | a | table |\n" +
                "|-----|---|-------|\n" +
                "```"
        assertEquals(body, body.ensureThreadMarkdownTableSeparation())
    }

    @Test
    fun pipeTextWithoutSeparatorRowIsUntouched() {
        val body = "Options are read | write | admin\n| but this line has pipes |\nplain tail."
        assertEquals(body, body.ensureThreadMarkdownTableSeparation())
    }

    @Test
    fun tableAfterTableRowIsNotDoubleSeparated() {
        val body = "| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |"
        assertEquals(body, body.ensureThreadMarkdownTableSeparation())
    }

    private fun parseContainsTable(content: String): Boolean {
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
        var found = false
        fun walk(node: ASTNode) {
            if (node.type == GFMElementTypes.TABLE) found = true
            node.children.forEach(::walk)
        }
        walk(tree)
        return found
    }
}
