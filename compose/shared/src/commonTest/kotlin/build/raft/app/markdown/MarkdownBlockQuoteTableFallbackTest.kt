package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.compose.elements.blockQuoteTablePlainText
import com.tencent.kuiklybase.markdown.compose.elements.requiresBlockQuotePlainTextFallback
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.intellij.markdown.MarkdownElementTypes.BLOCK_QUOTE
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.parser.MarkdownParser

class MarkdownBlockQuoteTableFallbackTest {
    @Test
    fun removesOnlyLeadingQuoteSyntaxFromRawTableLines() {
        assertEquals(
            """| Check | Result |
| --- | --- |
| Source > cache | Ready |
| Android | Pending |""",
            blockQuoteTablePlainText(
                """| Check | Result |
> | --- | --- |
> | Source > cache | Ready |
>| Android | Pending |"""
            )
        )
    }

    @Test
    fun directAndListNestedTablesRequireFallback() {
        assertTrue(
            blockQuoteContent(
                """> | Check | Result |
> | --- | --- |
> | Android | Pending |"""
            ).any(::requiresBlockQuotePlainTextFallback)
        )
        assertTrue(
            blockQuoteContent(
                "> - Status\n" +
                    ">   \n" +
                    ">   | Check | Result |\n" +
                    ">   | --- | --- |\n" +
                    ">   | Android | Pending |"
            ).any(::requiresBlockQuotePlainTextFallback)
        )
    }

    @Test
    fun nonLazyQuoteContentKeepsRichRendering() {
        val content = """> Paragraph with **bold** and ![alt](assets://icon.png).
>
> - nested
>   - list
>
> ```text
> | table-looking | code |
> | --- | --- |
> ```"""

        assertFalse(blockQuoteContent(content).any(::requiresBlockQuotePlainTextFallback))
    }

    private fun blockQuoteContent(content: String): List<ASTNode> {
        val tree = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(content)
        val blockQuote = requireNotNull(tree.findChildOfType(BLOCK_QUOTE))
        return blockQuote.children.filter { it.type != BLOCK_QUOTE }
    }
}
