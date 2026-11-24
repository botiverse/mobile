package build.raft.app.markdown

import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import com.tencent.kuiklybase.markdown.model.RaftMarkdownDialect
import com.tencent.kuiklybase.markdown.model.raftBlockMathSource
import com.tencent.kuiklybase.markdown.model.raftInlineMathSource
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Task #775: which text is math, by Obsidian's (Pandoc's) dollar rules. Ported verbatim from the
 * web's `packages/shared/src/markdownMath.test.ts` (botiverse/slock #10813, head
 * cdfb107ecda6e49cf3c7eecfb7857f9eff98e3a2; CJK guard: #16343, head
 * a3e1993f417df5ef6ec10ca34ec6a1aaf050118b); every expectation is what remark with `raftMath()`
 * and mdast-util-math gives for the same text.
 */
class MarkdownMathTest {
    @Test
    fun aSingleDollarPairIsMathWhenItHugsItsContent() {
        assertEquals(listOf("inline:\\pi r^2"), formulas("area \$\\pi r^2\$ here"))
        assertEquals(listOf("inline:x", "inline:y"), formulas("\$x\$, \$y\$."))
        assertEquals(listOf("inline:x^2"), formulas("\$x^2\$"))
    }

    @Test
    fun pricesStayText() {
        // The closing `$` is preceded by a space (and followed by a digit).
        assertEquals(emptyList(), formulas("从 \$5 到 \$10"))
        assertEquals(emptyList(), formulas("\$5 and \$10 each"))
        // The opening `$` is followed by a space.
        assertEquals(emptyList(), formulas("costs \$ 5 and \$6"))
        assertEquals(emptyList(), formulas("a \$ x\$ b"))
        // The closing `$` is preceded by a space.
        assertEquals(emptyList(), formulas("a \$x \$ b"))
        // The closing `$` is followed by a digit.
        assertEquals(emptyList(), formulas("\$5,\$10"))
    }

    @Test
    fun cjkProseBetweenTwoPricesStaysText() {
        assertEquals(emptyList(), formulas("页上写的是「US \$10」，但升级弹窗里只写「\$」。"))
        assertEquals(emptyList(), formulas("一个 \$5，另一个\$"))
        assertEquals(emptyList(), formulas("\$１０円\$"))
        assertEquals(emptyList(), formulas("\$𠮷\$"))
        // A backslash that starts no command doesn't make it math.
        assertEquals(emptyList(), formulas("\$5 存在 C:\\ 盘\$"))
        assertEquals(emptyList(), formulas("\$中文 \\ x\$"))
        // A rejected pair leaves the next one to pair on its own.
        assertEquals(listOf("inline:x^2"), formulas("价格 \$5，公式 \$x^2\$ 结束"))
        assertEquals(
            listOf("alice"),
            mentions("「US \$10」，问 @alice 弹窗里只写「\$」"),
        )
        // A command makes it math, CJK or not.
        assertEquals(listOf("inline:\\text{速度}=v"), formulas("\$\\text{速度}=v\$"))
        assertEquals(listOf("inline:x^2"), formulas("中文里 \$x^2\$ 照常"))
        // Double dollars are unaffected (inline and display blocks).
        assertEquals(listOf("inline:速度 = v"), formulas("\$\$速度 = v\$\$"))
        assertEquals(listOf("block:速度 = v"), formulas("\$\$\n速度 = v\n\$\$"))
    }

    @Test
    fun codeOwnsItsDollars() {
        assertEquals(emptyList(), formulas("`\$x\$` and\n```\n\$\$\nx\n\$\$\n```"))
        // A formula owns what it contains: emphasis markers and link brackets are TeX.
        assertEquals(listOf("inline:a_1 * b_2"), formulas("\$a_1 * b_2\$"))
        assertEquals(listOf("inline:[0, 1)"), formulas("on \$[0, 1)\$ only"))
    }

    private fun parse(source: String): ASTNode =
        MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(source)

    private fun formulas(source: String): List<String> {
        val found = mutableListOf<String>()
        fun visit(node: ASTNode) {
            when (node.type) {
                GFMElementTypes.INLINE_MATH -> found += "inline:${raftInlineMathSource(node, source)}"
                GFMElementTypes.BLOCK_MATH -> found += "block:${raftBlockMathSource(node, source)}"
            }
            node.children.forEach(::visit)
        }
        visit(parse(source))
        return found
    }

    /** The handles of the `@` references the app would resolve as mentions. */
    private fun mentions(source: String): List<String> =
        RaftMarkdownDialect.inlineRefs(parse(source), source)
            .map { it.sourceText }
            .filter { it.startsWith("@") }
            .map { it.removePrefix("@") }
}
