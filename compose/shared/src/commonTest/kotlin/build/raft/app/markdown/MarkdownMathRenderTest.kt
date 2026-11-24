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
    fun formulasWithSurrogatesShowTheirSourceInsteadOfReachingSkia() {
        // A lone high surrogate, a lone low surrogate, and a whole emoji pair.
        for (source in listOf("a\uD83D", "\uDE00 + b", "x = 😀")) {
            assertFalse(raftMathFormulaRenders(source), source)
        }
    }

    @Test
    fun cjkProseBetweenDollarsRendersAsPlainTextWithNoMathPlaceholder() {
        val body = "页上写的是「US \$10」，但升级弹窗里只写「\$」。"
        val annotated = render(body)
        assertEquals(body, annotated.text)
        assertTrue(annotated.raftMarkdownInlineContentIds().isEmpty())
    }

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
