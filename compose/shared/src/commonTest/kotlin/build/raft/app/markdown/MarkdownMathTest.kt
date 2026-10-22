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
 * cdfb107ecda6e49cf3c7eecfb7857f9eff98e3a2); every expectation is what remark with `raftMath()`
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
    fun singleDollarMathStaysOnOneLineAndEscapedDollarsAreText() {
        assertEquals(emptyList(), formulas("\$a\nb\$"))
        assertEquals(emptyList(), formulas("\\\$x\\\$"))
    }

    @Test
    fun doubleDollarsAreMathInlineAndAsABlock() {
        assertEquals(listOf("inline:E = mc^2"), formulas("then \$\$E = mc^2\$\$ holds"))
        assertEquals(listOf("block:\\int_0^1 x\\,dx"), formulas("\$\$\n\\int_0^1 x\\,dx\n\$\$"))
        // A space after `$$` is fine: the single-dollar rules don't apply.
        assertEquals(listOf("inline:a + b"), formulas("\$\$ a + b \$\$"))
    }

    @Test
    fun anAtInsideAFormulaIsNotAMentionOutsideItStillIs() {
        assertEquals(emptyList(), mentions("see \$a @alice\$ and \$\$ @bob \$\$"))
        assertEquals(listOf("alice"), mentions("\$5 for @alice and \$10"))
        assertEquals(listOf("bob"), mentions("\$x\$ @bob"))
    }

    // Mobile-only: the block shapes the web gets from micromark-extension-math's mathFlow, which
    // the cases above reach only once.
    @Test
    fun mathBlocksFollowTheFenceRules() {
        // A fence interrupts a paragraph; blank lines stay in the formula.
        assertEquals(
            listOf("block:a\n\nb"),
            formulas("text\n\$\$\na\n\nb\n\$\$"),
        )
        // A longer closing fence closes; the opening line may carry text without a `$`.
        assertEquals(listOf("block:x"), formulas("\$\$\$ tex\nx\n\$\$\$\$"))
        // Unclosed: runs to the end of the message.
        assertEquals(listOf("block:x\ny"), formulas("\$\$\nx\ny"))
        // In a quote and a list item, the container marker is not part of the formula.
        assertEquals(listOf("block:a^2"), formulas("> \$\$\n> a^2\n> \$\$"))
        assertEquals(listOf("block:a^2"), formulas("- \$\$\n  a^2\n  \$\$"))
        // The formula is not Markdown: `# h` and `- x` are TeX here.
        assertEquals(listOf("block:# h\n- x"), formulas("\$\$\n# h\n- x\n\$\$"))
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
