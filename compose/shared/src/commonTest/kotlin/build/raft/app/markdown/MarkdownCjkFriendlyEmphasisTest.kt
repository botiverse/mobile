package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * artin task #11: `**` beside CJK text bolds as on the web with remark-cjk-friendly. Every
 * expectation is what remark + remark-gfm + remark-cjk-friendly 2.0.2 renders for the same text.
 */
class MarkdownCjkFriendlyEmphasisTest {
    @Test
    fun strongBesideCjkPunctuationOpensAndCloses() {
        assertEquals(
            listOf("“副作用放进 `--help` 而不只是 PR 正文”这个判断对"),
            strong("你说**“副作用放进 `--help` 而不只是 PR 正文”这个判断对**——我同意"),
        )
        assertEquals(listOf("中文，"), strong("**中文，**中文"))
        assertEquals(listOf("“重点”"), strong("这是**“重点”**。"))
        assertEquals(listOf("「強調」"), strong("日本語の**「強調」**です"))
        assertEquals(listOf("（括号）"), strong("**（括号）**里"))
        assertEquals(listOf("“강조”"), strong("한국어**“강조”**입니다"))
    }

    @Test
    fun everythingElseStaysCommonMark() {
        assertEquals(listOf("bar"), strong("foo **bar** baz"))
        assertEquals(emptyList(), strong("a**\"b\"**c"))
        assertEquals(listOf("强调"), spans("中文*强调*中文", MarkdownElementTypes.EMPH))
        // `_` inside a word never emphasises, CJK or not.
        assertEquals(emptyList(), spans("中文_强调_中文", MarkdownElementTypes.EMPH))
        // An emoji (outside the BMP) is neither CJK nor punctuation.
        assertEquals(emptyList(), strong("emoji 😀**“x”**y"))
    }

    private fun strong(text: String): List<String> = spans(text, MarkdownElementTypes.STRONG)

    /** The text inside each [type] span (its markers dropped), in document order. */
    private fun spans(text: String, type: IElementType): List<String> {
        val root = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
        val marker = if (type == MarkdownElementTypes.STRONG) 2 else 1
        val found = mutableListOf<String>()
        fun walk(node: ASTNode) {
            if (node.type == type) found += text.substring(node.startOffset + marker, node.endOffset - marker)
            node.children.forEach(::walk)
        }
        walk(root)
        return found
    }
}
