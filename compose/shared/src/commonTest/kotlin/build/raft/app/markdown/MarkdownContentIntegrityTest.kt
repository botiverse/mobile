package build.raft.app.markdown

import com.tencent.kuikly.compose.ui.text.LinkAnnotation
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
import kotlin.test.assertEquals
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

    @Test
    fun generatedLiteralBoundariesConserveEveryVisibleCharacter() {
        literalBoundarySources().forEach { source ->
            val rendered = renderTopParagraphsWithLinks(source).single()
            assertEquals(
                source,
                rendered.text,
                "literal boundary lost or rewrote source characters; source=$source",
            )
            assertEquals(
                emptyList(),
                rendered.urls,
                "literal boundary unexpectedly became a link; source=$source; rendered=${rendered.text}",
            )
        }
    }

    @Test
    fun generatedUrlBoundariesConserveTextAndExactLinkTarget() {
        generatedUrlBoundaryCases().forEach { case ->
            val rendered = renderTopParagraphsWithLinks(case.source).single()
            assertEquals(
                case.source,
                rendered.text,
                "URL boundary lost or rewrote visible characters; source=${case.source}",
            )
            assertEquals(
                listOf(case.url),
                rendered.urls,
                "URL annotation did not preserve the generated balanced target; source=${case.source}",
            )
        }
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

    private fun renderTopParagraphsWithLinks(content: String): List<RenderedParagraph> {
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
                annotator = slockMarkdownAnnotator(parserContent),
            )
        return paragraphs.map { paragraph ->
            val annotated =
                buildAnnotatedString {
                    buildMarkdownAnnotatedString(parserContent, paragraph, settings)
                }
            RenderedParagraph(
                text = annotated.text,
                urls =
                    annotated
                        .getLinkAnnotations(0, annotated.length)
                        .mapNotNull { range -> (range.item as? LinkAnnotation.Url)?.url },
            )
        }
    }

    /**
     * Grammar-generated messages with no valid Markdown construct. Their
     * conservation law is stronger than a hand-written case list: every source
     * character must remain visible and none may acquire link semantics.
     */
    private fun literalBoundarySources(): List<String> {
        val leftContexts = listOf("a", "aas", "before ")
        val boundaryAtoms =
            buildList {
                val pairs = listOf('<' to '>', '(' to ')', '[' to ']', '{' to '}')
                pairs.forEach { (open, close) ->
                    add("$open$close")
                    add("${open}x$close")
                    add("$open")
                    add("$close")
                }
                add("<>")
                add("<<>>")
            }
        val rightContexts = listOf("", "z", " after")
        return leftContexts.flatMap { left ->
            boundaryAtoms.flatMap { atom ->
                rightContexts.map { right -> "$left$atom$right" }
            }
        }.distinct()
    }

    /**
     * Generates balanced URL paths and surrounds them with prose punctuation.
     * The generator itself owns the exact URL slice, so the oracle can assert
     * both visible-text conservation and link-target boundaries without
     * encoding individual examples such as `asd(xxx)`.
     */
    private fun generatedUrlBoundaryCases(): List<GeneratedUrlBoundaryCase> {
        val base = "https://xxx.com/xx/asd"
        val balancedSuffixes =
            listOf("") + generatedBalancedParenthesisPayloads(maxDepth = 3).map { payload -> "($payload)" }
        val contexts =
            listOf<(String) -> String>(
                { url -> url },
                { url -> "$url." },
                { url -> "$url, next" },
                { url -> "See $url now" },
                { url -> "See ($url) now" },
                { url -> "See [$url] now" },
            )
        return balancedSuffixes.flatMap { suffix ->
            val url = base + suffix
            contexts.map { context -> GeneratedUrlBoundaryCase(source = context(url), url = url) }
        }
    }

    private fun generatedBalancedParenthesisPayloads(maxDepth: Int): List<String> {
        fun atDepth(depth: Int): List<String> {
            val literals = listOf("x", "xxx", "a-b", "a_b")
            if (depth == 0) return literals
            val nested = atDepth(depth - 1).map { inner -> "a($inner)c" }
            return literals + nested
        }
        return atDepth(maxDepth).distinct()
    }

    private data class GeneratedUrlBoundaryCase(
        val source: String,
        val url: String,
    )

    private data class RenderedParagraph(
        val text: String,
        val urls: List<String>,
    )
}
