package build.raft.app

import com.tencent.kuiklybase.markdown.model.SlockGFMFlavourDescriptor
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LongMarkdownTableVisualFixtureTest {

    @Test
    fun regressionBodyPinsTallTableFollowedByDistinctDetailTable() {
        val body = THREAD_MESSAGE_VISUAL_MD_LONG_TABLE_OVERLAP_BODY
        val tree = MarkdownParser(SlockGFMFlavourDescriptor()).buildMarkdownTreeFromString(body)
        val tables = tree.findAll(GFMElementTypes.TABLE)

        assertEquals(2, tables.size)
        assertEquals(14, tables.first().children.count { it.type == GFMElementTypes.ROW })
        assertEquals(5, tables.last().children.count { it.type == GFMElementTypes.ROW })
        assertTrue(tables.first().endOffset <= tables.last().startOffset)
        assertTrue(body.substring(tables.last().startOffset, tables.last().endOffset).contains("酒店明细（不得覆盖上表）"))
        assertTrue(body.substring(tables.last().endOffset).contains("长表格尾部哨兵"))
    }

    @Test
    fun matrixBoldAndPlainTwinsParseToIdenticalTableShape() {
        // The matrix's bold/plain comparison is only valid if removing `**`
        // changes nothing about the parsed table structure.
        val parser = MarkdownParser(SlockGFMFlavourDescriptor())
        for ((fixture, entry) in THREAD_MESSAGE_VISUAL_MD_TABLE_MATRIX) {
            if (!fixture.startsWith("md_tblmx_")) continue
            val body = entry.second
            val tables = parser.buildMarkdownTreeFromString(body).findAll(GFMElementTypes.TABLE)
            assertTrue(tables.isNotEmpty(), "fixture $fixture parsed no table")
            val plainTables =
                parser.buildMarkdownTreeFromString(body.replace("**", "")).findAll(GFMElementTypes.TABLE)
            assertEquals(
                tables.map { table -> table.children.count { it.type == GFMElementTypes.ROW } },
                plainTables.map { table -> table.children.count { it.type == GFMElementTypes.ROW } },
                "fixture $fixture: removing ** changed parsed table shape",
            )
        }
    }

    private fun ASTNode.findAll(type: org.intellij.markdown.IElementType): List<ASTNode> =
        buildList {
            if (this@findAll.type == type) add(this@findAll)
            children.forEach { addAll(it.findAll(type)) }
        }
}
