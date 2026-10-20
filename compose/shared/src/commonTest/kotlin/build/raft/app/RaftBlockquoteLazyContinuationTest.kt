package build.raft.app

import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftBlockquoteLazyContinuationTest {

    private fun blockTypes(markdown: String) =
        MarkdownParser(RaftGFMFlavourDescriptor())
            .buildMarkdownTreeFromString(markdown)
            .children.map { it.type }.filter { it != MarkdownTokenTypes.EOL }

    @Test
    fun quoteEndsAtTheUnmarkedReplyLine() {
        // task #527: artin's real message shape. CommonMark lazy continuation
        // absorbed the reply into the quote on mobile while web split them.
        assertEquals(
            listOf(MarkdownElementTypes.BLOCK_QUOTE, MarkdownElementTypes.PARAGRAPH),
            blockTypes("> 锚点还在解析的那 ~0.5 秒内抬键盘才会白\n那听起来修复的很简单啊"),
        )
    }

    @Test
    fun consecutiveQuoteLinesStayOneQuote() {
        assertEquals(
            listOf(MarkdownElementTypes.BLOCK_QUOTE),
            blockTypes("> first line\n> second line"),
        )
    }

    @Test
    fun listLazyContinuationIsUnaffected() {
        assertEquals(
            listOf(MarkdownElementTypes.UNORDERED_LIST),
            blockTypes("- item text\ncontinuation line"),
        )
    }
}
