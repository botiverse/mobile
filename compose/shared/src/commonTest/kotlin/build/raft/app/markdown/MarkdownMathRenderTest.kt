package build.raft.app.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import build.raft.app.RaftType
import com.tencent.kuiklybase.markdown.annotator.DefaultAnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import com.tencent.kuiklybase.markdown.model.RaftGFMFlavourDescriptor
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task #775 (slock #10813): what a message line becomes around math. A formula is one inline
 * placeholder carrying its TeX; a `$` that opens nothing stays text; a formula KaTeX would reject
 * (an unknown command, or `\href` / `\url` / `\html*`, which the web's `trust: false` refuses)
 * shows its source in KaTeX's errorColor instead.
 */
class MarkdownMathRenderTest {
    @Test
    fun aFormulaIsOnePlaceholderCarryingItsSource() {
        val annotated = render("area \$\\pi r^2\$ here")
        assertEquals("area \\pi r^2 here", annotated.text)
        assertEquals(setOf(raftInlineMathContentId("\\pi r^2")), annotated.raftMarkdownInlineContentIds())
    }

    @Test
    fun dollarsThatOpenNoFormulaStayText() {
        for (body in listOf("从 \$5 到 \$10", "\$5,\$10", "costs \$ 5 and \$6", "\\\$x\\\$")) {
            val annotated = render(body)
            assertTrue(annotated.raftMarkdownInlineContentIds().isEmpty(), body)
            assertEquals(body.replace("\\\$", "\$"), annotated.text, body)
        }
    }

    @Test
    fun aFormulaKatexRejectsShowsItsSourceInTheErrorColour() {
        for (source in listOf("\\frac{1}{\\oops}", "\\href{javascript:alert(1)}{x}", "\\htmlId{evil}{y}", "\\url{x}")) {
            val annotated = render("a \$$source\$ b")
            assertEquals("a $source b", annotated.text, source)
            assertTrue(annotated.raftMarkdownInlineContentIds().isEmpty(), source)
            val start = annotated.text.indexOf(source)
            assertTrue(
                annotated.spanStyles.any { it.item.color == RaftMathErrorColor && it.start <= start && it.end >= start + source.length },
                source,
            )
        }
    }

    @Test
    fun theCommonKatexSubsetRenders() {
        for (source in listOf(
            "\\frac{a}{b}",
            "x_i^2",
            "\\sqrt{2}",
            "\\sqrt[3]{x}",
            "\\sum_{i=1}^{n} i",
            "\\int_0^1 x^2\\,dx",
            "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}",
            "\\begin{bmatrix} 1 & 0 \\\\ 0 & 1 \\end{bmatrix}",
            "\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}",
            "\\alpha + \\beta = \\Gamma",
            "\\text{面积} = \\pi r^2",
        )) {
            assertTrue(raftMathFormulaRenders(source), source)
        }
        assertFalse(raftMathFormulaRenders("{".repeat(RaftMathFormulas.MAX_NESTING + 1) + "x" + "}".repeat(RaftMathFormulas.MAX_NESTING + 1)))
    }

    @Test
    fun escapedLineBreakDecodingLeavesFormulasAndCodeAlone() {
        // One line, so a literal `\n` would otherwise be decoded as an escaped line break.
        val oneLine = "\$a \\neq b\$ and \$\\nu\$"
        assertEquals(oneLine, oneLine.normalizeThreadMarkdownBody())
        assertEquals(listOf("inline:\\nu", "inline:a \\neq b"), inlineFormulas(oneLine.normalizeThreadMarkdownBody()))
        // Outside them, escaped line breaks are still decoded.
        assertEquals(
            "x \$\\nu\$ and `a\\nb`\ny",
            "x \$\\nu\$ and `a\\nb`\\ny".normalizeThreadMarkdownBody(),
        )
        // An escaped `$$` block becomes a block once decoded.
        assertEquals("\$\$\nx\n\$\$", "\$\$\\nx\\n\$\$".normalizeThreadMarkdownBody())
    }

    private fun inlineFormulas(body: String): List<String> =
        render(body).raftMarkdownInlineContentIds().map { "inline:" + it.removePrefix(RAFT_INLINE_MATH_CONTENT_ID_PREFIX) }.sorted()

    /** The paragraph as the message renderer builds it. */
    private fun render(body: String): AnnotatedString {
        val parserContent = MarkdownParserContent.from(body).parserContent
        val tree = MarkdownParser(RaftGFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        val paragraph = tree.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        val settings =
            DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = raftMarkdownLinkSpanStyle(RaftType.Body)),
                codeSpanStyle = SpanStyle(),
                annotator = raftMarkdownAnnotator(body),
            )
        return buildAnnotatedString { buildMarkdownAnnotatedString(parserContent, paragraph, settings) }
    }
}
