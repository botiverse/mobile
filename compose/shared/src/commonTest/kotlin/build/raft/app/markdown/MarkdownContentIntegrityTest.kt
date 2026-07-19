package build.raft.app.markdown

import com.tencent.kuikly.compose.ui.text.SpanStyle
import com.tencent.kuikly.compose.ui.text.TextLinkStyles
import com.tencent.kuikly.compose.ui.text.buildAnnotatedString
import com.tencent.kuiklybase.markdown.annotator.DefaultAnnotatorSettings
import com.tencent.kuiklybase.markdown.annotator.buildMarkdownAnnotatedString
import com.tencent.kuiklybase.markdown.model.MarkdownParserContent
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Round-trip content-integrity net for the markdown annotator (task #495).
 *
 * The task #493 bug class: a lexer token type with no dispatch branch is
 * silently DROPPED — `artin <artin@cat.ms>` rendered as "artin <>" because
 * EMAIL_AUTOLINK had no branch (fixed in mobile#995). These tests parse real
 * message shapes and assert the human-visible content survives into the
 * rendered text. The static sibling is scripts/patrol/renderer_coverage.mjs,
 * which diffs the library's type universe against the dispatch nightly.
 */
class MarkdownContentIntegrityTest {

    @Test
    fun emailInsideCodeSpanStaysVerbatim() {
        val rendered = renderTopParagraphs("Inline: `artin <artin@cat.ms>` end.")
        assertTrue(
            rendered.any { it.contains("artin <artin@cat.ms>") },
            "code span must keep the angle-bracket email literal; got: $rendered"
        )
    }

    @Test
    fun genericAngleBracketPlaceholdersInsideCodeSpanStayVerbatim() {
        val rendered = renderTopParagraphs("Routes: `/apps/<app>/latest` and `/share/<token>`.")
        assertTrue(
            rendered.any { it.contains("/apps/<app>/latest") && it.contains("/share/<token>") },
            "code spans must keep generic angle-bracket placeholders literal; got: $rendered"
        )
    }

    @Test
    fun bareEmailAutolinkKeepsTheAddressText() {
        val rendered = renderTopParagraphs("Bare: <artin@cat.ms> end.")
        assertTrue(
            rendered.any { it.contains("artin@cat.ms") },
            "email autolink text must survive rendering; got: $rendered"
        )
    }

    @Test
    fun boldAndStrikethroughContentSurvives() {
        val rendered = renderTopParagraphs("Keep **bold words** and ~~struck words~~ intact.")
        assertTrue(
            rendered.any { it.contains("bold words") && it.contains("struck words") },
            "emphasis/strikethrough content must survive rendering; got: $rendered"
        )
    }

    @Test
    fun escapedBacktickSurvivesAsLiteralBacktick() {
        val rendered = renderTopParagraphs("Type \\` to start a code span.")
        assertTrue(
            rendered.any { it.contains("Type ` to start") },
            "escaped backtick must render as a literal backtick; got: $rendered"
        )
    }

    @Test
    fun unpairedTildeSurvivesInPlainText() {
        val rendered = renderTopParagraphs("Takes ~5 minutes to finish.")
        assertTrue(
            rendered.any { it.contains("~5 minutes") },
            "unpaired tilde must survive rendering; got: $rendered"
        )
    }

    @Test
    fun strikethroughDelimitersDoNotLeakTildes() {
        val rendered = renderTopParagraphs("Keep ~~struck words~~ clean.")
        assertTrue(
            rendered.any { it.contains("struck words") && !it.contains("~") },
            "strikethrough delimiter tildes must not leak into text; got: $rendered"
        )
    }

    private fun renderTopParagraphs(content: String): List<String> {
        // Uses the PRODUCTION annotator (slockMarkdownAnnotator) so app-side
        // annotate hooks are part of the integrity contract too.
        val parserContent = MarkdownParserContent.from(content).parserContent
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(parserContent)
        val paragraphs = mutableListOf<ASTNode>()
        fun collect(node: ASTNode) {
            if (node.type == MarkdownElementTypes.PARAGRAPH) paragraphs += node
            node.children.forEach(::collect)
        }
        collect(tree)
        val settings =
            DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = SpanStyle()),
                codeSpanStyle = SpanStyle(),
                annotator = slockMarkdownAnnotator(parserContent)
            )
        return paragraphs.map { paragraph ->
            buildAnnotatedString {
                buildMarkdownAnnotatedString(parserContent, paragraph, settings)
            }.text
        }
    }
}
