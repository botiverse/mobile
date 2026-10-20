package build.raft.app.markdown

import androidx.compose.ui.unit.dp
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftMarkdownBlockSpacingTest {

    private fun blocks(markdown: String): List<ASTNode> =
        MarkdownParser(RaftGFMFlavourDescriptor())
            .buildMarkdownTreeFromString(markdown)
            .children
            .filter { it.type != MarkdownTokenTypes.EOL && it.type != MarkdownTokenTypes.WHITE_SPACE }

    @Test
    fun paragraphsAreFourApartAndTheLastHasNoBottomMargin() {
        val (first, second) = blocks("one\n\ntwo")
        assertEquals(0.dp, raftMarkdownBlockGapBefore(first))
        assertEquals(4.dp, raftMarkdownBlockGapAfter(first))
        assertEquals(0.dp, raftMarkdownBlockGapAfter(second))
    }

    @Test
    fun ruleMarginsCollapseWithTheParagraphAndAreAlreadyInsideTheRule() {
        // web: hr my-2 collapses with p mb-1; the rule paints its own 8dp on each side.
        val (rule, paragraph) = blocks("---\n\ntext")
        assertEquals(0.dp, raftMarkdownBlockGapBefore(rule))
        assertEquals(0.dp, raftMarkdownBlockGapAfter(rule))
        assertEquals(0.dp, raftMarkdownBlockGapAfter(paragraph))
    }

    @Test
    fun listKeepsItsBottomMarginBeforeTheNextParagraph() {
        val (intro, list, outro) = blocks("intro\n\n- a\n- b\n\noutro")
        assertEquals(4.dp, raftMarkdownBlockGapAfter(intro))
        assertEquals(4.dp, raftMarkdownBlockGapAfter(list))
        assertEquals(0.dp, raftMarkdownBlockGapAfter(outro))
    }

    @Test
    fun headingTopMarginWinsOverTheParagraphBottomMargin() {
        val (paragraph, heading) = blocks("text\n\n## Title")
        assertEquals(8.dp, raftMarkdownBlockGapAfter(paragraph))
        assertEquals(4.dp, raftMarkdownBlockGapAfter(heading))
    }
}
